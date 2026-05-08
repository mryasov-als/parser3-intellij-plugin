package ru.artlebedev.parser3.index;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Сервис для поиска объявлений классов в Parser3 файлах.
 *
 * Использует FileBasedIndex для быстрого поиска.
 * Индекс автоматически обновляется при изменении файлов.
 */
@Service(Service.Level.PROJECT)
public final class P3ClassIndex {

	private static final Logger LOG = Logger.getInstance(P3ClassIndex.class);

	private final Project project;
	private final PsiManager psiManager;

	public P3ClassIndex(@NotNull Project project) {
		this.project = project;
		this.psiManager = PsiManager.getInstance(project);
	}

	public static P3ClassIndex getInstance(@NotNull Project project) {
		return project.getService(P3ClassIndex.class);
	}

	/**
	 * Находит все объявления класса по имени.
	 */
	public @NotNull List<P3ClassDeclaration> findByName(@NotNull String name) {
		List<P3ClassDeclaration> result = new ArrayList<>();
		GlobalSearchScope scope = P3IndexMaintenance.getParser3IndexScope(project);

		// Получаем все файлы, содержащие этот класс
		Collection<VirtualFile> files = safeGetContainingFiles(name, scope);

		for (VirtualFile file : files) {
			if (!file.isValid()) continue;
			result.addAll(findInFileByName(file, name));
		}

		return result;
	}

	/**
	 * Возвращает все классы в проекте.
	 * ОПТИМИЗИРОВАНО: один проход по файлам, кеширование методов.
	 */
	public @NotNull List<P3ClassDeclaration> getAllClasses() {
		List<P3ClassDeclaration> result = new ArrayList<>();

		// Проверяем что не в dumb mode
		com.intellij.openapi.project.DumbService dumbService = com.intellij.openapi.project.DumbService.getInstance(project);
		if (dumbService.isDumb()) {
			return result;
		}

		// Получаем все файлы проекта
		List<VirtualFile> allFiles = P3ScopeContext.getAllProjectFiles(project);

		// Один проход по файлам
		for (VirtualFile file : allFiles) {
			if (!file.isValid()) continue;

			// Получаем данные классов для файла
			java.util.Map<String, List<P3ClassFileIndex.ClassInfo>> classData =
					safeGetClassFileData(file);

			if (classData.isEmpty()) continue;

			// Получаем данные методов для файла ОДИН РАЗ (кеш для этого файла)
			java.util.Map<String, List<P3MethodFileIndex.MethodInfo>> methodData =
					safeGetMethodFileData(file);

			// Создаём классы с методами
			for (java.util.Map.Entry<String, List<P3ClassFileIndex.ClassInfo>> entry : classData.entrySet()) {
				String className = entry.getKey();
				for (P3ClassFileIndex.ClassInfo info : entry.getValue()) {
					// Собираем методы в диапазоне класса из уже загруженных данных
					List<P3MethodDeclaration> methods = extractMethodsInRange(
							file, methodData, info.startOffset, info.endOffset);

					result.add(new P3ClassDeclaration(
							className,
							file,
							info.startOffset,
							info.endOffset,
							info.baseClassName,
							info.options,
							methods,
							"MAIN".equals(className),
							null  // Не загружаем PsiElement — это дорого и не нужно для автокомплита
					));
				}
			}
		}

		return result;
	}

	/**
	 * Извлекает методы в диапазоне из уже загруженных данных индекса.
	 * Не делает дополнительных запросов к индексу.
	 */
	private @NotNull List<P3MethodDeclaration> extractMethodsInRange(
			@NotNull VirtualFile file,
			@NotNull java.util.Map<String, List<P3MethodFileIndex.MethodInfo>> methodData,
			int startOffset,
			int endOffset
	) {
		List<P3MethodDeclaration> result = new ArrayList<>();

		for (java.util.Map.Entry<String, List<P3MethodFileIndex.MethodInfo>> entry : methodData.entrySet()) {
			String methodName = entry.getKey();
			for (P3MethodFileIndex.MethodInfo info : entry.getValue()) {
				if (info.offset >= startOffset && info.offset < endOffset) {
					// Конвертируем DocParam в P3MethodParameter
					List<ru.artlebedev.parser3.model.P3MethodParameter> docParams = new ArrayList<>();
					for (P3MethodFileIndex.DocParam dp : info.docParams) {
						docParams.add(new ru.artlebedev.parser3.model.P3MethodParameter(
								dp.name, dp.type, dp.description, false));
					}

					ru.artlebedev.parser3.model.P3MethodParameter docResult = null;
					if (info.docResult != null) {
						docResult = new ru.artlebedev.parser3.model.P3MethodParameter(
								info.docResult.name,
								info.docResult.type,
								info.docResult.description,
								true);
					}

					result.add(new P3MethodDeclaration(
							methodName,
							file,
							info.offset,
							null,  // Не загружаем PsiElement
							info.parameterNames,
							info.docText,
							docParams,
							docResult,
							info.inferredResult,
							info.isGetter,
							info.callType
					));
				}
			}
		}

		return result;
	}

	/**
	 * Находит все объявления классов в конкретном файле.
	 */
	public @NotNull List<P3ClassDeclaration> findInFile(@NotNull VirtualFile file) {
		List<P3ClassDeclaration> result = new ArrayList<>();

		// Получаем все данные для этого файла напрямую
		java.util.Map<String, List<P3ClassFileIndex.ClassInfo>> fileData =
				safeGetClassFileData(file);

		for (java.util.Map.Entry<String, List<P3ClassFileIndex.ClassInfo>> entry : fileData.entrySet()) {
			String className = entry.getKey();
			for (P3ClassFileIndex.ClassInfo info : entry.getValue()) {
				result.add(createClassDeclaration(file, className, info));
			}
		}

		return result;
	}

	/**
	 * Находит объявления конкретного класса в файле.
	 */
	private @NotNull List<P3ClassDeclaration> findInFileByName(@NotNull VirtualFile file, @NotNull String name) {
		List<P3ClassDeclaration> result = new ArrayList<>();

		List<P3ClassFileIndex.ClassInfo> infos = safeGetClassFileData(file).get(name);

		if (infos != null) {
			for (P3ClassFileIndex.ClassInfo info : infos) {
				result.add(createClassDeclaration(file, name, info));
			}
		}

		return result;
	}

	/**
	 * Создаёт P3ClassDeclaration из данных индекса.
	 * PSI element НЕ загружается — он нужен только для навигации и будет загружен лениво.
	 */
	private @NotNull P3ClassDeclaration createClassDeclaration(
			@NotNull VirtualFile file,
			@NotNull String className,
			@NotNull P3ClassFileIndex.ClassInfo info
	) {
		// Получаем методы этого класса из индекса (без PSI)
		List<P3MethodDeclaration> methods = getMethodsInRange(file, info.startOffset, info.endOffset);

		return new P3ClassDeclaration(
				className,
				file,
				info.startOffset,
				info.endOffset,
				info.baseClassName,
				info.options,
				methods,
				"MAIN".equals(className),
				null // PSI загружается лениво через getElement()
		);
	}

	/**
	 * Получает методы в заданном диапазоне offset.
	 * PSI element НЕ загружается — передаётся null, будет загружен лениво при навигации.
	 */
	private @NotNull List<P3MethodDeclaration> getMethodsInRange(
			@NotNull VirtualFile file,
			int startOffset,
			int endOffset
	) {
		List<P3MethodDeclaration> result = new ArrayList<>();

		// Убеждаемся что индекс актуален
		com.intellij.openapi.project.DumbService dumbService = com.intellij.openapi.project.DumbService.getInstance(project);
		if (dumbService.isDumb()) {
			return result;
		}

		// Получаем все данные для этого файла напрямую
		java.util.Map<String, List<P3MethodFileIndex.MethodInfo>> fileData =
				safeGetMethodFileData(file);


		for (java.util.Map.Entry<String, List<P3MethodFileIndex.MethodInfo>> entry : fileData.entrySet()) {
			String methodName = entry.getKey();
			for (P3MethodFileIndex.MethodInfo info : entry.getValue()) {
				if (info.offset >= startOffset && info.offset < endOffset) {
					// Конвертируем DocParam в P3MethodParameter
					List<ru.artlebedev.parser3.model.P3MethodParameter> docParams = new ArrayList<>();
					for (P3MethodFileIndex.DocParam dp : info.docParams) {
						docParams.add(new ru.artlebedev.parser3.model.P3MethodParameter(
								dp.name, dp.type, dp.description, false));
					}

					ru.artlebedev.parser3.model.P3MethodParameter docResult = null;
					if (info.docResult != null) {
						docResult = new ru.artlebedev.parser3.model.P3MethodParameter(
								info.docResult.name,
								info.docResult.type,
								info.docResult.description,
								true);
					}

					result.add(new P3MethodDeclaration(
							methodName,
							file,
							info.offset,
							null, // PSI загружается лениво при навигации
							info.parameterNames,
							info.docText,
							docParams,
							docResult,
							info.inferredResult,
							info.isGetter,
							info.callType
					));
				}
			}
		}

		return result;
	}

	/**
	 * Находит ПОСЛЕДНЕЕ (актуальное) объявление класса в видимых файлах.
	 * В Parser3 классы переопределяются - берётся последний по порядку видимости.
	 *
	 * @param name имя класса
	 * @param visibleFiles список видимых файлов В ПОРЯДКЕ ПРИОРИТЕТА
	 * @return последнее объявление или null
	 */
	public @Nullable P3ClassDeclaration findLastInFiles(
			@NotNull String name,
			@NotNull List<VirtualFile> visibleFiles
	) {
		P3ClassDeclaration last = null;

		// Идём по файлам в порядке приоритета
		// Последний файл с этим классом "выигрывает"
		for (VirtualFile file : visibleFiles) {
			if (!file.isValid()) continue;

			List<P3ClassDeclaration> fileDecls = findInFileByName(file, name);
			for (P3ClassDeclaration decl : fileDecls) {
				last = decl; // Перезаписываем - последний выигрывает
			}
		}

		return last;
	}

	/**
	 * Находит ВСЕ объявления в видимых файлах.
	 */
	public @NotNull List<P3ClassDeclaration> findInFiles(
			@NotNull String name,
			@NotNull List<VirtualFile> visibleFiles
	) {
		List<P3ClassDeclaration> result = new ArrayList<>();

		for (VirtualFile file : visibleFiles) {
			if (!file.isValid()) continue;
			result.addAll(findInFileByName(file, name));
		}

		return result;
	}

	/**
	 * Находит класс, содержащий данный offset в файле.
	 * Используется для определения контекста (в каком классе находится курсор).
	 *
	 * @param file файл
	 * @param offset позиция в файле
	 * @return класс или null если offset вне всех классов
	 */
	public @Nullable P3ClassDeclaration findClassAtOffset(@NotNull VirtualFile file, int offset) {
		List<P3ClassDeclaration> classes = findInFile(file);

		for (P3ClassDeclaration clazz : classes) {
			if (clazz.containsOffset(offset)) {
				return clazz;
			}
		}

		return null;
	}

	/**
	 * Возвращает все имена классов в проекте.
	 * Полезно для автодополнения.
	 */
	public @NotNull Collection<String> getAllClassNames() {
		List<String> names = new ArrayList<>();
		try {
			FileBasedIndex.getInstance().processAllKeys(
					P3ClassFileIndex.NAME,
					name -> {
						names.add(name);
						return true;
					},
					project
			);
		} catch (com.intellij.ide.startup.ServiceNotReadyException ignored) {
			return names;
		} catch (RuntimeException e) {
			logIndexAccessFailure("processAllKeys(parser3.classes)", e);
		}
		return names;
	}

	private @NotNull Collection<VirtualFile> safeGetContainingFiles(
			@NotNull String className,
			@NotNull GlobalSearchScope scope
	) {
		try {
			return FileBasedIndex.getInstance().getContainingFiles(P3ClassFileIndex.NAME, className, scope);
		} catch (RuntimeException e) {
			if (e instanceof com.intellij.openapi.progress.ProcessCanceledException) {
				throw e;
			}
			if (e instanceof com.intellij.ide.startup.ServiceNotReadyException) {
				return java.util.Collections.emptyList();
			}
			logIndexAccessFailure("getContainingFiles(" + className + ")", e);
			return java.util.Collections.emptyList();
		}
	}

	private @NotNull java.util.Map<String, List<P3ClassFileIndex.ClassInfo>> safeGetClassFileData(
			@NotNull VirtualFile file
	) {
		try {
			return FileBasedIndex.getInstance().getFileData(P3ClassFileIndex.NAME, file, project);
		} catch (RuntimeException e) {
			if (e instanceof com.intellij.openapi.progress.ProcessCanceledException) {
				throw e;
			}
			if (e instanceof com.intellij.ide.startup.ServiceNotReadyException) {
				return java.util.Collections.emptyMap();
			}
			logIndexAccessFailure("getFileData(parser3.classes, " + file.getPath() + ")", e);
			return java.util.Collections.emptyMap();
		}
	}

	private @NotNull java.util.Map<String, List<P3MethodFileIndex.MethodInfo>> safeGetMethodFileData(
			@NotNull VirtualFile file
	) {
		try {
			return FileBasedIndex.getInstance().getFileData(P3MethodFileIndex.NAME, file, project);
		} catch (RuntimeException e) {
			if (e instanceof com.intellij.openapi.progress.ProcessCanceledException) {
				throw e;
			}
			if (e instanceof com.intellij.ide.startup.ServiceNotReadyException) {
				return java.util.Collections.emptyMap();
			}
			logIndexAccessFailure("getFileData(parser3.methods, " + file.getPath() + ")", e);
			return java.util.Collections.emptyMap();
		}
	}

	private static void logIndexAccessFailure(@NotNull String operation, @NotNull RuntimeException e) {
		LOG.warn("[Parser3ClassIndex] " + operation + " failed, returning empty result", e);
	}

	/**
	 * Проверяет виден ли @autouse[] из указанного файла.
	 * Если @autouse виден — все классы проекта доступны для автодополнения в этом файле.
	 *
	 * @autouse считается видимым если он находится в MAIN классе одного из:
	 * - цепочки auto.p (от document_root до директории файла)
	 * - файлов, подключённых через @USE / ^use[]
	 * - самого файла
	 *
	 * @param file файл, из которого проверяем видимость
	 * @param cursorOffset позиция, для которой учитываются позиционные ^use[]
	 * @return true если @autouse виден из этого файла
	 */
	public boolean hasAutouseVisibleFrom(@NotNull VirtualFile file, int cursorOffset) {
		return new P3ScopeContext(project, file, cursorOffset).hasAutouse();
	}

	/**
	 * Находит все классы-наследники указанного класса.
	 * Ищет классы, у которых @BASE равен указанному имени.
	 *
	 * @param className имя базового класса
	 * @param visibleFiles файлы для поиска (null = весь проект)
	 * @return список классов-наследников
	 */
	public @NotNull List<P3ClassDeclaration> findChildClasses(
			@NotNull String className,
			@Nullable List<VirtualFile> visibleFiles
	) {
		List<P3ClassDeclaration> result = new ArrayList<>();
		List<P3ClassDeclaration> allClasses;

		if (visibleFiles != null) {
			// Ищем только в указанных файлах
			allClasses = new ArrayList<>();
			for (VirtualFile file : visibleFiles) {
				allClasses.addAll(findInFile(file));
			}
		} else {
			// Ищем во всём проекте
			allClasses = getAllClasses();
		}

		for (P3ClassDeclaration cls : allClasses) {
			if (className.equals(cls.getBaseClassName())) {
				result.add(cls);
			}
		}

		return result;
	}
}
