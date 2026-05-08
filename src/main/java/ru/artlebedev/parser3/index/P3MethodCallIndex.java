package ru.artlebedev.parser3.index;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервис для поиска вызовов методов через индекс.
 *
 * Использует P3MethodCallFileIndex для быстрого поиска всех вызовов метода.
 */
@Service(Service.Level.PROJECT)
public final class P3MethodCallIndex {

	private static final boolean DEBUG = false;

	private final Project project;

	public P3MethodCallIndex(@NotNull Project project) {
		this.project = project;
	}

	public static P3MethodCallIndex getInstance(@NotNull Project project) {
		return project.getService(P3MethodCallIndex.class);
	}

	/**
	 * Результат поиска вызова — содержит файл и информацию о вызове
	 */
	public static final class MethodCallResult {
		public final @NotNull VirtualFile file;
		public final @NotNull P3MethodCallFileIndex.MethodCallInfo callInfo;

		public MethodCallResult(@NotNull VirtualFile file, @NotNull P3MethodCallFileIndex.MethodCallInfo callInfo) {
			this.file = file;
			this.callInfo = callInfo;
		}
	}

	/**
	 * Находит все вызовы метода по имени во всех файлах проекта.
	 */
	public @NotNull List<MethodCallResult> findByName(@NotNull String methodName) {
		List<MethodCallResult> results = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();

		index.processValues(
				P3MethodCallFileIndex.NAME,
				methodName,
				null,
				(file, calls) -> {
					for (P3MethodCallFileIndex.MethodCallInfo call : calls) {
						results.add(new MethodCallResult(file, call));
					}
					return true;
				},
				P3IndexMaintenance.getParser3IndexScope(project)
		);

		return results;
	}

	/**
	 * Находит все вызовы метода в указанных файлах.
	 */
	public @NotNull List<MethodCallResult> findInFiles(@NotNull String methodName, @NotNull List<VirtualFile> files) {
		List<MethodCallResult> results = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();

		for (VirtualFile file : files) {
			List<P3MethodCallFileIndex.MethodCallInfo> calls = index.getValues(
							P3MethodCallFileIndex.NAME,
							methodName,
							com.intellij.psi.search.GlobalSearchScope.fileScope(project, file)
					).stream()
					.flatMap(List::stream)
					.toList();

			for (P3MethodCallFileIndex.MethodCallInfo call : calls) {
				results.add(new MethodCallResult(file, call));
			}
		}

		return results;
	}

	/**
	 * Находит вызовы метода, подходящие для определения метода в указанном классе.
	 * Учитывает наследование: для метода в классе User также ищет вызовы из наследников.
	 *
	 * Для метода в классе User ищем:
	 * - ^method[] из класса User и его наследников
	 * - ^self.method[] из класса User и его наследников
	 * - ^User:method[] из любого места
	 * - ^User::method[] из любого места
	 *
	 * Для метода в MAIN ищем:
	 * - ^method[] из MAIN
	 * - ^self.method[] из MAIN
	 * - ^MAIN:method[] из любого места
	 *
	 * @param methodName имя метода
	 * @param ownerClassName класс-владелец метода (null или "MAIN" для MAIN)
	 * @param visibleFiles файлы для поиска
	 * @return список подходящих вызовов
	 */
	public @NotNull List<MethodCallResult> findCallsForMethod(
			@NotNull String methodName,
			@Nullable String ownerClassName,
			@NotNull List<VirtualFile> visibleFiles
	) {
		// Собираем все классы, от которых нужно искать вызовы
		// Для именованного класса — сам класс и все его наследники
		java.util.Set<String> relevantClasses = new java.util.HashSet<>();
		boolean ownerIsMain = (ownerClassName == null || "MAIN".equals(ownerClassName));

		if (!ownerIsMain) {
			relevantClasses.add(ownerClassName);
			// Ищем наследников
			java.util.Set<String> descendants = findDescendantClasses(ownerClassName, visibleFiles);
			relevantClasses.addAll(descendants);
		}

		List<MethodCallResult> allCalls = findInFiles(methodName, visibleFiles);
		List<MethodCallResult> matchingCalls = new ArrayList<>();

		for (MethodCallResult call : allCalls) {
			if (isCallMatchingMethodWithInheritance(call, ownerClassName, ownerIsMain, relevantClasses)) {
				matchingCalls.add(call);
			}
		}

		return matchingCalls;
	}

	/**
	 * Находит все классы-наследники указанного класса.
	 */
	private @NotNull java.util.Set<String> findDescendantClasses(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles
	) {
		java.util.Set<String> descendants = new java.util.HashSet<>();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		// Получаем все классы из видимых файлов
		for (VirtualFile file : visibleFiles) {
			List<P3ClassDeclaration> classes = classIndex.findInFile(file);
			for (P3ClassDeclaration cls : classes) {
				// Проверяем цепочку наследования
				if (isDescendantOf(cls, className, classIndex, visibleFiles, new java.util.HashSet<>())) {
					descendants.add(cls.getName());
				}
			}
		}

		return descendants;
	}

	/**
	 * Проверяет является ли класс наследником указанного класса.
	 */
	private boolean isDescendantOf(
			@NotNull P3ClassDeclaration cls,
			@NotNull String ancestorName,
			@NotNull P3ClassIndex classIndex,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull java.util.Set<String> visited
	) {
		String baseClassName = cls.getBaseClassName();
		if (baseClassName == null) {
			return false;
		}

		// Защита от циклов
		if (!visited.add(cls.getName())) {
			return false;
		}

		// Прямой наследник
		if (ancestorName.equals(baseClassName)) {
			return true;
		}

		// Рекурсивно проверяем базовый класс
		List<P3ClassDeclaration> baseClasses = classIndex.findByName(baseClassName);
		for (P3ClassDeclaration baseClass : baseClasses) {
			if (visibleFiles.contains(baseClass.getFile())) {
				if (isDescendantOf(baseClass, ancestorName, classIndex, visibleFiles, visited)) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Проверяет подходит ли вызов для метода указанного класса.
	 * Учитывает наследование через relevantClasses.
	 */
	private boolean isCallMatchingMethodWithInheritance(
			@NotNull MethodCallResult callResult,
			@Nullable String ownerClassName,
			boolean ownerIsMain,
			@NotNull java.util.Set<String> relevantClasses
	) {
		P3MethodCallFileIndex.MethodCallInfo call = callResult.callInfo;
		P3MethodCallFileIndex.CallType callType = call.callType;
		boolean callerIsMain = (call.callerClassName == null);

		if (ownerIsMain) {
			// Метод в MAIN
			switch (callType) {
				case MAIN:
					// ^MAIN:method[] — всегда подходит для MAIN
					return true;

				case SIMPLE:
				case SELF:
					// ^method[] или ^self.method[] — подходит если вызов из MAIN
					return callerIsMain;

				case CLASS_STATIC:
				case CLASS_CONSTRUCTOR:
				case BASE:
				case OBJECT:
					return false;

				default:
					return false;
			}
		} else {
			// Метод в именованном классе
			switch (callType) {
				case CLASS_STATIC:
				case CLASS_CONSTRUCTOR:
					// ^ClassName:method[] или ^ClassName::method[] — подходит если класс совпадает с владельцем
					return ownerClassName.equals(call.targetClassName);

				case SELF:
				case SIMPLE:
					// ^self.method[] или ^method[] — подходит если вызов из класса-владельца или наследника
					return relevantClasses.contains(call.callerClassName);

				case BASE:
					// ^BASE:method[] — подходит если вызов из наследника
					// (наследник вызывает метод базового класса)
					return relevantClasses.contains(call.callerClassName) &&
							!ownerClassName.equals(call.callerClassName);

				case OBJECT:
					return isObjectCallMatchingMethod(callResult, relevantClasses);

				case MAIN:
					return false;

				default:
					return false;
			}
		}
	}

	private boolean isObjectCallMatchingMethod(
			@NotNull MethodCallResult callResult,
			@NotNull java.util.Set<String> relevantClasses
	) {
		P3MethodCallFileIndex.MethodCallInfo call = callResult.callInfo;
		if (call.receiverVarKey == null || call.receiverVarKey.isEmpty()) {
			return false;
		}

		List<VirtualFile> visibleFiles = new P3ScopeContext(project, callResult.file, call.offset)
				.getVariableSearchFiles();
		P3VariableIndex variableIndex = P3VariableIndex.getInstance(project);
		P3VariableIndex.ChainResolveInfo chainInfo = variableIndex.resolveEffectiveChain(
				call.receiverVarKey,
				visibleFiles,
				callResult.file,
				call.offset
		);
		String receiverClassName = chainInfo != null ? chainInfo.className : null;

		if (DEBUG) System.out.println("[P3MethodCallIndex] receiverVarKey=" + call.receiverVarKey
				+ " receiverClassName=" + receiverClassName
				+ " file=" + callResult.file.getPath());

		return receiverClassName != null && relevantClasses.contains(receiverClassName);
	}

	/**
	 * Находит все вызовы, где указан конкретный класс.
	 * Ищет паттерны: ^ClassName::method[], ^ClassName:method[]
	 *
	 * @param className имя класса
	 * @param visibleFiles файлы для поиска
	 * @return список вызовов с targetClassName == className
	 */
	public @NotNull List<MethodCallResult> findCallsByClassName(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles
	) {
		List<MethodCallResult> results = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();

		// Проходим по всем методам во всех видимых файлах
		for (VirtualFile file : visibleFiles) {
			// Получаем все данные для этого файла
			java.util.Map<String, List<P3MethodCallFileIndex.MethodCallInfo>> fileData =
					index.getFileData(P3MethodCallFileIndex.NAME, file, project);

			for (java.util.Map.Entry<String, List<P3MethodCallFileIndex.MethodCallInfo>> entry : fileData.entrySet()) {
				for (P3MethodCallFileIndex.MethodCallInfo call : entry.getValue()) {
					// Проверяем что вызов направлен на нужный класс
					if (className.equals(call.targetClassName)) {
						results.add(new MethodCallResult(file, call));
					}
				}
			}
		}

		return results;
	}

	/**
	 * Результат поиска использования класса — содержит файл, информацию о вызове и имя метода.
	 */
	public static final class ClassUsageResult {
		public final @NotNull VirtualFile file;
		public final @NotNull P3MethodCallFileIndex.MethodCallInfo callInfo;
		public final @NotNull String methodName;

		public ClassUsageResult(
				@NotNull VirtualFile file,
				@NotNull P3MethodCallFileIndex.MethodCallInfo callInfo,
				@NotNull String methodName
		) {
			this.file = file;
			this.callInfo = callInfo;
			this.methodName = methodName;
		}
	}

	/**
	 * Находит все вызовы, где указан конкретный класс, с именами методов.
	 * Ищет паттерны: ^ClassName::method[], ^ClassName:method[]
	 *
	 * @param className имя класса
	 * @param visibleFiles файлы для поиска
	 * @return список использований класса с именами методов
	 */
	public @NotNull List<ClassUsageResult> findClassUsages(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles
	) {
		List<ClassUsageResult> results = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();

		// Проходим по всем методам во всех видимых файлах
		for (VirtualFile file : visibleFiles) {
			// Получаем все данные для этого файла
			java.util.Map<String, List<P3MethodCallFileIndex.MethodCallInfo>> fileData =
					index.getFileData(P3MethodCallFileIndex.NAME, file, project);

			for (java.util.Map.Entry<String, List<P3MethodCallFileIndex.MethodCallInfo>> entry : fileData.entrySet()) {
				String methodName = entry.getKey();
				for (P3MethodCallFileIndex.MethodCallInfo call : entry.getValue()) {
					// Проверяем что вызов направлен на нужный класс
					if (className.equals(call.targetClassName)) {
						results.add(new ClassUsageResult(file, call, methodName));
					}
				}
			}
		}

		return results;
	}

	/**
	 * Находит все ^BASE: вызовы в указанном классе.
	 * Используется для поиска использований родительского класса.
	 *
	 * @param childClassName имя класса-наследника, в котором искать ^BASE: вызовы
	 * @param visibleFiles файлы для поиска
	 * @return список ^BASE: вызовов
	 */
	public @NotNull List<ClassUsageResult> findBaseCallsInClass(
			@NotNull String childClassName,
			@NotNull List<VirtualFile> visibleFiles
	) {
		List<ClassUsageResult> results = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();

		for (VirtualFile file : visibleFiles) {
			java.util.Map<String, List<P3MethodCallFileIndex.MethodCallInfo>> fileData =
					index.getFileData(P3MethodCallFileIndex.NAME, file, project);

			for (java.util.Map.Entry<String, List<P3MethodCallFileIndex.MethodCallInfo>> entry : fileData.entrySet()) {
				String methodName = entry.getKey();
				for (P3MethodCallFileIndex.MethodCallInfo call : entry.getValue()) {
					// Ищем ^BASE: вызовы внутри нужного класса
					if (call.callType == P3MethodCallFileIndex.CallType.BASE &&
							childClassName.equals(call.callerClassName)) {
						results.add(new ClassUsageResult(file, call, methodName));
					}
				}
			}
		}

		return results;
	}

	/**
	 * Находит все ^self. вызовы в указанном классе.
	 * Используется для поиска использований унаследованных методов.
	 *
	 * @param className имя класса, в котором искать ^self. вызовы
	 * @param visibleFiles файлы для поиска
	 * @return список ^self. вызовов
	 */
	public @NotNull List<ClassUsageResult> findSelfCallsInClass(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles
	) {
		List<ClassUsageResult> results = new ArrayList<>();
		FileBasedIndex index = FileBasedIndex.getInstance();

		for (VirtualFile file : visibleFiles) {
			java.util.Map<String, List<P3MethodCallFileIndex.MethodCallInfo>> fileData =
					index.getFileData(P3MethodCallFileIndex.NAME, file, project);

			for (java.util.Map.Entry<String, List<P3MethodCallFileIndex.MethodCallInfo>> entry : fileData.entrySet()) {
				String methodName = entry.getKey();
				for (P3MethodCallFileIndex.MethodCallInfo call : entry.getValue()) {
					// Ищем ^self. вызовы внутри нужного класса
					if (call.callType == P3MethodCallFileIndex.CallType.SELF &&
							className.equals(call.callerClassName)) {
						results.add(new ClassUsageResult(file, call, methodName));
					}
				}
			}
		}

		return results;
	}
}
