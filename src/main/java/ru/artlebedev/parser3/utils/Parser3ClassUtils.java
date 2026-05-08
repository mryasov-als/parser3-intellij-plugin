package ru.artlebedev.parser3.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;

import java.util.*;

/**
 * Utility for working with Parser3 classes and their methods.
 */
public final class Parser3ClassUtils {

	private Parser3ClassUtils() {
	}

	/**
	 * Get all methods of a class including inherited methods and partial definitions.
	 * Handles:
	 * - Inheritance chain via @BASE
	 * - Partial class definitions (@OPTIONS partial)
	 *
	 * @param classDecl the class (any partial definition)
	 * @param visibleFiles files where base classes and partial definitions can be found
	 * @param project current project
	 * @return list of all methods (own + inherited + from other partial definitions)
	 */
	public static @NotNull List<P3MethodDeclaration> getAllMethods(
			@NotNull P3ClassDeclaration classDecl,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Project project
	) {
		return getAllMethods(classDecl.getName(), visibleFiles, project, false);
	}

	/**
	 * Get all methods of a class by name, including inherited methods and partial definitions.
	 *
	 * @param className the class name
	 * @param visibleFiles files where classes can be found
	 * @param project current project
	 * @return list of all methods
	 */
	public static @NotNull List<P3MethodDeclaration> getAllMethodsByClassName(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Project project
	) {
		return getAllMethods(className, visibleFiles, project, false);
	}

	/**
	 * Get all methods of a class, ignoring partial rules.
	 * Used for "all methods" mode where we show ALL methods from ALL definitions.
	 *
	 * @param className the class name
	 * @param allFiles all project files
	 * @param project current project
	 * @return list of all methods from all definitions
	 */
	public static @NotNull List<P3MethodDeclaration> getAllMethodsIgnorePartialRules(
			@NotNull String className,
			@NotNull List<VirtualFile> allFiles,
			@NotNull Project project
	) {
		return getAllMethods(className, allFiles, project, true);
	}

	/**
	 * БЫСТРЫЙ метод для режима "все методы".
	 * Использует индекс напрямую (findByName) вместо перебора всех файлов.
	 *
	 * @param className имя класса
	 * @param project проект
	 * @return список всех методов из всех определений класса
	 */
	public static @NotNull List<P3MethodDeclaration> getAllMethodsFast(
			@NotNull String className,
			@NotNull Project project
	) {
		Map<String, P3MethodDeclaration> methodMap = new LinkedHashMap<>();
		Set<String> visitedClasses = new HashSet<>();

		collectMethodsRecursiveFast(className, project, methodMap, visitedClasses);

		return new ArrayList<>(methodMap.values());
	}

	/**
	 * Быстрый рекурсивный сбор методов.
	 * Использует findByName (индекс) вместо findInFiles (перебор файлов).
	 */
	private static void collectMethodsRecursiveFast(
			@NotNull String className,
			@NotNull Project project,
			@NotNull Map<String, P3MethodDeclaration> methodMap,
			@NotNull Set<String> visitedClasses
	) {
		// Защита от бесконечной рекурсии
		if (visitedClasses.contains(className)) {
			return;
		}
		visitedClasses.add(className);

		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		// БЫСТРО: используем индекс напрямую — не перебираем все файлы!
		List<P3ClassDeclaration> allDefinitions = classIndex.findByName(className);

		if (allDefinitions.isEmpty()) {
			return;
		}

		// Сначала собираем методы из базового класса
		String baseClassName = null;
		for (P3ClassDeclaration decl : allDefinitions) {
			if (decl.getBaseClassName() != null) {
				baseClassName = decl.getBaseClassName();
				break;
			}
		}

		if (baseClassName != null) {
			collectMethodsRecursiveFast(baseClassName, project, methodMap, visitedClasses);
		}

		// Собираем методы из всех определений
		for (P3ClassDeclaration decl : allDefinitions) {
			for (P3MethodDeclaration method : decl.getMethods()) {
				methodMap.put(method.getName(), method);
			}
		}
	}

	/**
	 * Internal method to get all methods with optional partial rules.
	 *
	 * @param ignorePartialRules если true — всегда объединяем все определения (для режима "все методы")
	 */
	private static @NotNull List<P3MethodDeclaration> getAllMethods(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Project project,
			boolean ignorePartialRules
	) {
		Map<String, P3MethodDeclaration> methodMap = new LinkedHashMap<>();
		Set<String> visitedClasses = new HashSet<>();

		collectMethodsRecursive(className, visibleFiles, project, methodMap, visitedClasses, ignorePartialRules);

		return new ArrayList<>(methodMap.values());
	}

	/**
	 * Recursively collect methods from class, its partial definitions, and base classes.
	 *
	 * Логика:
	 * - ignorePartialRules=true (режим "все методы") — всегда объединяем все определения
	 * - ignorePartialRules=false (режим USE):
	 *   - Если класс partial (@OPTIONS partial) — объединяем методы из всех определений
	 *   - Если класс НЕ partial — берём только последнее определение (переопределение)
	 *
	 * Порядок сбора методов (последний переопределяет):
	 * 1. Методы базового класса (через @BASE)
	 * 2. Методы класса
	 */
	private static void collectMethodsRecursive(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Project project,
			@NotNull Map<String, P3MethodDeclaration> methodMap,
			@NotNull Set<String> visitedClasses,
			boolean ignorePartialRules
	) {
		// Защита от бесконечной рекурсии
		if (visitedClasses.contains(className)) {
			return;
		}
		visitedClasses.add(className);

		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		// Находим ВСЕ определения класса
		List<P3ClassDeclaration> allDefinitions = classIndex.findInFiles(className, visibleFiles);

		if (allDefinitions.isEmpty()) {
			return;
		}

		// Определяем какие определения использовать
		List<P3ClassDeclaration> definitionsToUse;

		if (ignorePartialRules) {
			// Режим "все методы" — всегда объединяем все определения
			definitionsToUse = allDefinitions;
		} else {
			// Режим USE — проверяем partial
			boolean hasPartial = false;
			for (P3ClassDeclaration decl : allDefinitions) {
				if (decl.isPartial()) {
					hasPartial = true;
					break;
				}
			}

			if (hasPartial) {
				// Partial — объединяем все определения
				definitionsToUse = allDefinitions;
			} else {
				// Не partial — берём только последнее (оно переопределяет предыдущие)
				definitionsToUse = Collections.singletonList(allDefinitions.get(allDefinitions.size() - 1));
			}
		}

		// Сначала собираем методы из базового класса (берём @BASE из первого определения где он есть)
		String baseClassName = null;
		for (P3ClassDeclaration decl : definitionsToUse) {
			if (decl.getBaseClassName() != null) {
				baseClassName = decl.getBaseClassName();
				break;
			}
		}

		if (baseClassName != null) {
			collectMethodsRecursive(baseClassName, visibleFiles, project, methodMap, visitedClasses, ignorePartialRules);
		}

		// Собираем методы из определений
		for (P3ClassDeclaration decl : definitionsToUse) {
			for (P3MethodDeclaration method : decl.getMethods()) {
				methodMap.put(method.getName(), method);
			}
		}
	}

	/**
	 * Get the "main" class declaration (last in visibility order, or first partial).
	 * Useful for checking class options like static/dynamic.
	 */
	public static @Nullable P3ClassDeclaration getMainClassDeclaration(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull Project project
	) {
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		return classIndex.findLastInFiles(className, visibleFiles);
	}

	// ─── Границы классов и методов по тексту файла ─────────────────────────

	// Паттерн @CLASS
	private static final java.util.regex.Pattern CLASS_PATTERN = java.util.regex.Pattern.compile(
			"@CLASS\\s*[\\r\\n]+\\s*(" + Parser3IdentifierUtils.NAME_REGEX + ")",
			java.util.regex.Pattern.MULTILINE
	);

	// Паттерн @methodName (объявление метода)
	private static final java.util.regex.Pattern METHOD_DECL_PATTERN = java.util.regex.Pattern.compile(
			"(?:^|(?<=\\{))\\^?@(?:(static|dynamic):)?([\\p{L}_][\\p{L}0-9_]*)(?:\\s*\\[([^\\]]*?)\\]|\\s*$|\\s+)",
			java.util.regex.Pattern.MULTILINE
	);

	// Паттерн для парсинга [locals] / [x;y] после @method[params]
	// @method[params][locals_spec]
	private static final java.util.regex.Pattern METHOD_LOCALS_PATTERN = java.util.regex.Pattern.compile(
			"(?:^|(?<=\\{))\\^?@(?:(static|dynamic):)?([\\p{L}_][\\p{L}0-9_]*)\\s*\\[[^\\]]*?\\]\\s*\\[([^\\]]*)\\]",
			java.util.regex.Pattern.MULTILINE
	);

	// Паттерн @OPTIONS
	private static final java.util.regex.Pattern OPTIONS_PATTERN = java.util.regex.Pattern.compile(
			"@OPTIONS\\s*[\\r\\n]+([^@]*?)(?=@|$)",
			java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.DOTALL
	);

	// Зарезервированные директивы — не методы (auto, conf, unhandled_exception — это методы, не директивы)
	private static final Set<String> RESERVED_DIRECTIVES = Set.of(
			"CLASS", "BASE", "OPTIONS", "USE", "static", "dynamic", "locals", "partial"
	);

	/**
	 * Информация о границе класса в тексте файла
	 */
	public static final class ClassBoundary {
		public final @NotNull String name;
		public final int start;
		public final int end;

		public ClassBoundary(@NotNull String name, int start, int end) {
			this.name = name;
			this.start = start;
			this.end = end;
		}
	}

	/**
	 * Информация о границе метода в тексте файла
	 */
	public static final class MethodBoundary {
		public final @NotNull String name;
		public final int start;  // offset @methodName
		public final int end;    // до следующего @method или конца класса
		public final @Nullable String ownerClass;  // null = MAIN
		public final boolean hasLocals;  // true если метод объявлен с [locals]
		public final @Nullable Set<String> localVarNames;  // конкретные локальные переменные [x;y] или null
		public final @NotNull List<String> parameterNames;  // параметры из первой скобки метода

		public MethodBoundary(@NotNull String name, int start, int end, @Nullable String ownerClass) {
			this(name, start, end, ownerClass, false, null, List.of());
		}

		public MethodBoundary(@NotNull String name, int start, int end, @Nullable String ownerClass,
							  boolean hasLocals, @Nullable Set<String> localVarNames) {
			this(name, start, end, ownerClass, hasLocals, localVarNames, List.of());
		}

		public MethodBoundary(@NotNull String name, int start, int end, @Nullable String ownerClass,
							  boolean hasLocals, @Nullable Set<String> localVarNames,
							  @NotNull List<String> parameterNames) {
			this.name = name;
			this.start = start;
			this.end = end;
			this.ownerClass = ownerClass;
			this.hasLocals = hasLocals;
			this.localVarNames = localVarNames;
			this.parameterNames = List.copyOf(parameterNames);
		}

		/**
		 * Проверяет является ли переменная локальной в этом методе.
		 * true если это параметр метода, [locals] или переменная в списке [x;y].
		 */
		public boolean isVariableLocal(@NotNull String varName) {
			if (parameterNames.contains(varName)) return true;
			if (hasLocals) return true;
			if (localVarNames != null) return localVarNames.contains(varName);
			return false;
		}
	}

	/**
	 * Парсит имена параметров из первой скобки объявления метода.
	 */
	public static @NotNull List<String> parseParameterNames(@Nullable String paramsStr) {
		if (paramsStr == null || paramsStr.trim().isEmpty()) {
			return List.of();
		}

		List<String> result = new ArrayList<>();
		String[] parts = paramsStr.split(";");
		for (String part : parts) {
			String param = part.trim();
			if (!param.isEmpty()) {
				result.add(param);
			}
		}
		return result;
	}

	/**
	 * Находит границы всех классов в тексте файла.
	 * Возвращает список ClassBoundary с корректными start/end.
	 */
	public static @NotNull List<ClassBoundary> findClassBoundaries(@NotNull String text) {
		List<ClassBoundary> rawList = new ArrayList<>();

		java.util.regex.Matcher matcher = CLASS_PATTERN.matcher(text);
		while (matcher.find()) {
			rawList.add(new ClassBoundary(matcher.group(1), matcher.start(), 0));
		}

		// Устанавливаем end для каждого класса
		List<ClassBoundary> result = new ArrayList<>(rawList.size());
		for (int i = 0; i < rawList.size(); i++) {
			ClassBoundary cur = rawList.get(i);
			// +1 чтобы курсор в позиции text.length() (конец файла без \n) попадал в последний класс
			int end = (i + 1 < rawList.size()) ? rawList.get(i + 1).start : text.length() + 1;
			result.add(new ClassBoundary(cur.name, cur.start, end));
		}

		return result;
	}

	/**
	 * Определяет в каком классе находится указанный offset.
	 * Возвращает null если в MAIN (вне @CLASS).
	 */
	public static @Nullable String findOwnerClass(int offset, @NotNull List<ClassBoundary> classBoundaries) {
		for (ClassBoundary cls : classBoundaries) {
			if (offset >= cls.start && offset < cls.end) {
				return cls.name;
			}
		}
		return null; // MAIN
	}

	/**
	 * Находит границы всех методов в тексте файла.
	 * Каждый метод — от своего @method до следующего @method или конца класса/файла.
	 */
	public static @NotNull List<MethodBoundary> findMethodBoundaries(@NotNull String text, @NotNull List<ClassBoundary> classBoundaries) {
		List<MethodBoundary> allMethods = new ArrayList<>();

		// Сначала собираем информацию о [locals]/[x;y] для каждого метода
		Map<String, Map<Integer, Object[]>> methodLocalsInfo = new HashMap<>(); // ownerClass -> {offset -> [hasLocals, localVarNames]}
		java.util.regex.Matcher localsMatcher = METHOD_LOCALS_PATTERN.matcher(text);
		while (localsMatcher.find()) {
			String mName = localsMatcher.group(2);
			if (RESERVED_DIRECTIVES.contains(mName)) continue;

			int offset = text.charAt(localsMatcher.start()) == '^' ? localsMatcher.start() + 1 : localsMatcher.start();
			String localsSpec = localsMatcher.group(3).trim();
			String ownerCls = findOwnerClass(offset, classBoundaries);

			boolean hasLocals = false;
			Set<String> localVarNames = null;
			if (!localsSpec.isEmpty()) {
				localVarNames = new HashSet<>();
				for (String v : localsSpec.split(";")) {
					String trimmed = v.trim();
					if ("locals".equalsIgnoreCase(trimmed)) {
						hasLocals = true;
						continue;
					}
					if (!trimmed.isEmpty()) {
						localVarNames.add(trimmed);
					}
				}
				if (localVarNames.isEmpty()) localVarNames = null;
			}

			String key = ownerCls != null ? ownerCls : "__MAIN__";
			methodLocalsInfo.computeIfAbsent(key, k -> new HashMap<>())
					.put(offset, new Object[]{hasLocals, localVarNames});
		}

		java.util.regex.Matcher matcher = METHOD_DECL_PATTERN.matcher(text);
		while (matcher.find()) {
			String name = matcher.group(2);
			if (RESERVED_DIRECTIVES.contains(name)) continue;

			int offset = text.charAt(matcher.start()) == '^' ? matcher.start() + 1 : matcher.start();
			List<String> parameterNames = parseParameterNames(matcher.group(3));
			String ownerClass = findOwnerClass(offset, classBoundaries);

			// Ищем [locals] информацию для этого метода
			String key = ownerClass != null ? ownerClass : "__MAIN__";
			boolean hasLocals = false;
			Set<String> localVarNames = null;
			Map<Integer, Object[]> classLocals = methodLocalsInfo.get(key);
			if (classLocals != null) {
				Object[] info = classLocals.get(offset);
				if (info != null) {
					hasLocals = (boolean) info[0];
					@SuppressWarnings("unchecked")
					Set<String> names = (Set<String>) info[1];
					localVarNames = names;
				}
			}

			allMethods.add(new MethodBoundary(name, offset, 0, ownerClass, hasLocals, localVarNames, parameterNames));
		}

		// Устанавливаем end: до следующего метода в том же классе, или до конца класса/файла
		for (int i = 0; i < allMethods.size(); i++) {
			MethodBoundary cur = allMethods.get(i);
			// +1 чтобы курсор в позиции text.length() (конец файла без \n) попадал в метод
			int classEnd = text.length() + 1;

			// Находим конец текущего класса
			if (cur.ownerClass != null) {
				for (ClassBoundary cls : classBoundaries) {
					if (cls.name.equals(cur.ownerClass) && cur.start >= cls.start && cur.start < cls.end) {
						classEnd = cls.end;
						break;
					}
				}
			} else {
				// MAIN — до первого @CLASS или конца файла
				if (!classBoundaries.isEmpty()) {
					classEnd = classBoundaries.get(0).start;
				}
			}

			// Ищем следующий метод в том же классе
			int end = classEnd;
			for (int j = i + 1; j < allMethods.size(); j++) {
				MethodBoundary next = allMethods.get(j);
				if (java.util.Objects.equals(next.ownerClass, cur.ownerClass)) {
					// Для MAIN: оба null — проверяем что next тоже до classEnd
					if (next.start < classEnd) {
						end = next.start;
						break;
					}
				}
			}

			allMethods.set(i, new MethodBoundary(cur.name, cur.start, end, cur.ownerClass,
					cur.hasLocals, cur.localVarNames, cur.parameterNames));
		}

		return allMethods;
	}

	/**
	 * Определяет в каком методе находится указанный offset.
	 * Возвращает null если не в методе (например в MAIN до первого метода).
	 */
	public static @Nullable MethodBoundary findOwnerMethod(int offset, @NotNull List<MethodBoundary> methodBoundaries) {
		for (MethodBoundary m : methodBoundaries) {
			if (offset >= m.start && offset < m.end) {
				return m;
			}
		}
		return null;
	}

	/**
	 * Проверяет есть ли @OPTIONS locals для MAIN в файле.
	 * MAIN — это текст до первого @CLASS.
	 */
	public static boolean hasMainLocals(@NotNull String text, @NotNull List<ClassBoundary> classBoundaries) {
		int mainEnd = classBoundaries.isEmpty() ? text.length() : classBoundaries.get(0).start;
		return hasMainLocalsBefore(text, classBoundaries, mainEnd);
	}

	/**
	 * Проверяет включён ли @OPTIONS locals для MAIN перед указанной позицией.
	 */
	public static boolean hasMainLocalsBefore(
			@NotNull String text,
			@NotNull List<ClassBoundary> classBoundaries,
			int offset
	) {
		int mainEnd = classBoundaries.isEmpty() ? text.length() : classBoundaries.get(0).start;
		int end = Math.max(0, Math.min(offset, mainEnd));
		String mainText = text.substring(0, end);

		java.util.regex.Matcher matcher = OPTIONS_PATTERN.matcher(mainText);
		while (matcher.find()) {
			String optionsText = matcher.group(1).trim();
			for (String line : optionsText.split("[\\s\\r\\n]+")) {
				if ("locals".equals(line.trim())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Проверяет есть ли @OPTIONS locals для указанного класса.
	 */
	public static boolean hasClassLocals(@NotNull String text, @NotNull ClassBoundary classBoundary) {
		return hasClassLocalsBefore(text, classBoundary, classBoundary.end);
	}

	/**
	 * Проверяет включён ли @OPTIONS locals для класса перед указанной позицией.
	 */
	public static boolean hasClassLocalsBefore(
			@NotNull String text,
			@NotNull ClassBoundary classBoundary,
			int offset
	) {
		int end = Math.min(classBoundary.end, text.length());
		end = Math.max(classBoundary.start, Math.min(offset, end));
		String classText = text.substring(classBoundary.start, end);

		java.util.regex.Matcher matcher = OPTIONS_PATTERN.matcher(classText);
		while (matcher.find()) {
			String optionsText = matcher.group(1).trim();
			for (String line : optionsText.split("[\\s\\r\\n]+")) {
				if ("locals".equals(line.trim())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Check if a class has @OPTIONS static.
	 * Methods can only be called with single colon.
	 */
	public static boolean isStaticClass(@NotNull P3ClassDeclaration classDecl) {
		return classDecl.isStatic();
	}

	/**
	 * Check if a class has @OPTIONS dynamic.
	 * Methods can only be called with double colon or through object.
	 */
	public static boolean isDynamicClass(@NotNull P3ClassDeclaration classDecl) {
		return classDecl.isDynamic();
	}

	/**
	 * Проверяет, что метод статический (@static:method).
	 */
	public static boolean isStaticMethod(@NotNull P3MethodDeclaration method) {
		return method.getCallType() == ru.artlebedev.parser3.model.P3MethodCallType.STATIC;
	}

	/**
	 * Возвращает имя метода без префикса типа вызова.
	 */
	public static @NotNull String getCleanMethodName(@NotNull P3MethodDeclaration method) {
		String name = method.getName();
		if (name.startsWith("static:")) {
			return name.substring(7); // "static:".length()
		}
		if (name.startsWith("dynamic:")) {
			return name.substring(8); // "dynamic:".length()
		}
		return name;
	}

	/**
	 * Check if method should be shown in completion for given call type.
	 *
	 * @param method the method
	 * @param classDecl the class
	 * @param isSingleColon true for User:method, false for User::method
	 * @return true if method is valid for this call type
	 */
	public static boolean isMethodValidForCallType(
			@NotNull P3MethodDeclaration method,
			@NotNull P3ClassDeclaration classDecl,
			boolean isSingleColon
	) {
		return isSingleColon
				? method.getCallType().allowsStaticCall()
				: method.getCallType().allowsDynamicCall();
	}

	/**
	 * Проверяет находится ли offset внутри комментария.
	 * Комментарии в Parser3:
	 * - # в колонке 0 (первый символ строки) — до конца строки
	 * - ^rem{...} / ^rem[...] / ^rem(...)
	 */
	public static boolean isOffsetInComment(@NotNull String text, int offset) {
		// 1. Проверяем # комментарий — # должен быть в колонке 0
		int lineStart = text.lastIndexOf('\n', offset - 1) + 1;
		if (lineStart < text.length() && text.charAt(lineStart) == '#') {
			return true;
		}

		// 2. Проверяем ^rem{...} / ^rem[...] / ^rem(...)
		int searchStart = 0;
		while (true) {
			int remPos = text.indexOf("^rem", searchStart);
			if (remPos == -1 || remPos >= offset) {
				break;
			}

			int bracketPos = remPos + 4; // длина "^rem"
			if (bracketPos < text.length()) {
				char bracket = text.charAt(bracketPos);
				if (bracket == '{' || bracket == '[' || bracket == '(') {
					char closeBracket = bracket == '{' ? '}' : (bracket == '[' ? ']' : ')');
					int closePos = findMatchingBracketSimple(text, bracketPos, bracket, closeBracket);
					if (closePos > offset) {
						return true;
					}
				}
			}

			searchStart = remPos + 1;
		}

		return false;
	}

	/**
	 * Простой поиск парной скобки с учётом вложенности (без учёта строк и экранирования).
	 * Для использования в индексах — скорость важнее точности.
	 */
	private static int findMatchingBracketSimple(String text, int openPos, char open, char close) {
		int depth = 1;
		for (int i = openPos + 1; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == open) {
				depth++;
			} else if (c == close) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return text.length();
	}
}
