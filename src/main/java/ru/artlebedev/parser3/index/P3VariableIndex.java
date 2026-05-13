package ru.artlebedev.parser3.index;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.visibility.P3EffectiveScopeService;
import ru.artlebedev.parser3.visibility.P3ScopeContext;
import ru.artlebedev.parser3.visibility.P3VariableScopeContext;
import ru.artlebedev.parser3.utils.Parser3ChainUtils;
import ru.artlebedev.parser3.utils.Parser3ClassUtils;
import ru.artlebedev.parser3.lang.Parser3BuiltinMethods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Сервис для поиска типов переменных в Parser3.
 *
 * ЕДИНСТВЕННЫЙ ИСТОЧНИК ИСТИНЫ для видимости переменных.
 * Основной метод: getVisibleVariables() — собирает все видимые переменные.
 * Всё остальное (автокомплит, навигация, тип переменной) — обёртки над ним.
 */
@Service(Service.Level.PROJECT)
public final class P3VariableIndex {

	private static final boolean DEBUG_ALWAYS = false;
	private static final boolean DEBUG_PERF = false;
	private static final Map<String, HashEntryInfo> TRY_EXCEPTION_HASH_KEYS = createTryExceptionHashKeys();
	private static final List<String> STRING_MATCH_COLUMNS = List.of("prematch", "match", "postmatch", "1");
	private static final ThreadLocal<ResolveRequestCache> RESOLVE_REQUEST_CACHE =
			ThreadLocal.withInitial(ResolveRequestCache::new);

	private final Project project;

	public P3VariableIndex(@NotNull Project project) {
		this.project = project;
	}

	public static P3VariableIndex getInstance(@NotNull Project project) {
		return project.getService(P3VariableIndex.class);
	}

	public <T> T withSharedResolveCache(@NotNull Supplier<T> supplier) {
		return withResolveRequestCache(supplier);
	}

	private <T> T withResolveRequestCache(@NotNull Supplier<T> supplier) {
		ResolveRequestCache cache = RESOLVE_REQUEST_CACHE.get();
		cache.depth++;
		try {
			return supplier.get();
		} finally {
			cache.depth--;
			if (cache.depth == 0) {
				cache.clear();
			}
		}
	}

	private static final class ResolveRequestCache {
		int depth = 0;
		final @NotNull Map<ScopeFilesKey, CachedScopeFiles> scopeFiles = new HashMap<>();
		final @NotNull Map<ChainResolveKey, ChainResolveInfo> chainResolve = new HashMap<>();
		final @NotNull Set<ChainResolveKey> chainResolveMisses = new HashSet<>();
		final @NotNull Map<ScopeFilesKey, CursorContext> cursorContexts = new HashMap<>();
		final @NotNull Map<ScopeFilesKey, Map<String, List<P3VariableFileIndex.VariableTypeInfo>>> parsedCurrentFiles = new HashMap<>();

		void clear() {
			scopeFiles.clear();
			chainResolve.clear();
			chainResolveMisses.clear();
			cursorContexts.clear();
			parsedCurrentFiles.clear();
		}
	}

	private static final class CachedScopeFiles {
		final @NotNull List<VirtualFile> variableFiles;
		final @NotNull List<VirtualFile> classFiles;

		CachedScopeFiles(
				@NotNull List<VirtualFile> variableFiles,
				@NotNull List<VirtualFile> classFiles
		) {
			this.variableFiles = variableFiles;
			this.classFiles = classFiles;
		}
	}

	private static final class ScopeFilesKey {
		final @NotNull VirtualFile file;
		final int offset;

		ScopeFilesKey(@NotNull VirtualFile file, int offset) {
			this.file = file;
			this.offset = offset;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof ScopeFilesKey)) return false;
			ScopeFilesKey key = (ScopeFilesKey) other;
			return offset == key.offset && file.equals(key.file);
		}

		@Override
		public int hashCode() {
			return 31 * file.hashCode() + offset;
		}
	}

	private static final class ChainResolveKey {
		final @NotNull String varKey;
		final @NotNull VirtualFile file;
		final int offset;
		final @NotNull List<VirtualFile> visibleFiles;
		final @NotNull List<VirtualFile> classFiles;
		final @NotNull java.util.Set<String> visitedValues;
		final @NotNull java.util.Set<String> resolvingVarKeys;

		ChainResolveKey(
				@NotNull String varKey,
				@NotNull List<VirtualFile> visibleFiles,
				@NotNull List<VirtualFile> classFiles,
				@NotNull VirtualFile file,
				int offset,
				@NotNull java.util.Set<String> visitedValues,
				@NotNull java.util.Set<String> resolvingVarKeys
		) {
			this.varKey = varKey;
			this.visibleFiles = List.copyOf(visibleFiles);
			this.classFiles = List.copyOf(classFiles);
			this.file = file;
			this.offset = offset;
			this.visitedValues = java.util.Set.copyOf(visitedValues);
			this.resolvingVarKeys = java.util.Set.copyOf(resolvingVarKeys);
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof ChainResolveKey)) return false;
			ChainResolveKey key = (ChainResolveKey) other;
			return offset == key.offset
					&& varKey.equals(key.varKey)
					&& file.equals(key.file)
					&& visibleFiles.equals(key.visibleFiles)
					&& classFiles.equals(key.classFiles)
					&& visitedValues.equals(key.visitedValues)
					&& resolvingVarKeys.equals(key.resolvingVarKeys);
		}

		@Override
		public int hashCode() {
			int result = varKey.hashCode();
			result = 31 * result + file.hashCode();
			result = 31 * result + offset;
			result = 31 * result + visibleFiles.hashCode();
			result = 31 * result + classFiles.hashCode();
			result = 31 * result + visitedValues.hashCode();
			result = 31 * result + resolvingVarKeys.hashCode();
			return result;
		}
	}

	// ===== Видимая переменная — результат работы единственного источника =====

	/**
	 * Одна видимая переменная с полной информацией.
	 * Ключ индекса (varKey) сохраняется — для поиска по конкретному имени.
	 */
	public static final class VisibleVariable {
		public final @NotNull String varKey;          // Ключ индекса: "var", "self.var", "MAIN:var"
		public final @NotNull String cleanName;       // Чистое имя без префикса: "var"
		public final @NotNull String className;       // Тип (имя класса) или METHOD_CALL_MARKER (не резолвлено)
		public final @Nullable String ownerClass;     // Класс-владелец: null = MAIN, "ClassName" = класс
		public final @NotNull VirtualFile file;       // Файл определения
		public final int offset;                      // Offset определения в файле
		public final @Nullable List<String> columns;  // Колонки таблицы (для table::create{col1\tcol2})
		public final @Nullable String sourceVarKey;   // Ключ переменной-источника (для table::create[$var])
		public final @Nullable java.util.Map<String, HashEntryInfo> hashKeys; // Ключи хеша (для hash-литералов)
		public final @Nullable java.util.List<String> hashSourceVars; // Переменные-источники ключей хеша
		public final boolean isAdditive;             // true если запись только добавляет структуру и не должна затирать тип
		public final boolean isLocal;                // true если простая $var локальна в своём методе

		public final boolean isMethodParam;           // true если это параметр метода

		// Для ленивого резолва METHOD_CALL_MARKER
		public final @Nullable String methodName;        // Имя метода (для METHOD_CALL_MARKER)
		public final @Nullable String targetClassName;   // Класс метода (для METHOD_CALL_MARKER)
		public final @Nullable String receiverVarKey;    // Receiver для object method call

		// Полный конструктор
		private VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
								@Nullable String ownerClass, @NotNull VirtualFile file, int offset,
								@Nullable List<String> columns, @Nullable String sourceVarKey,
								@Nullable String methodName, @Nullable String targetClassName,
								@Nullable java.util.Map<String, HashEntryInfo> hashKeys,
								@Nullable java.util.List<String> hashSourceVars,
								boolean isAdditive,
								boolean isLocal,
								boolean isMethodParam,
								@Nullable String receiverVarKey) {
			this.varKey = varKey;
			this.cleanName = cleanName;
			this.className = className;
			this.ownerClass = ownerClass;
			this.file = file;
			this.offset = offset;
			this.columns = columns;
			this.sourceVarKey = sourceVarKey;
			this.methodName = methodName;
			this.targetClassName = targetClassName;
			this.receiverVarKey = receiverVarKey;
			this.hashKeys = hashKeys;
			this.hashSourceVars = hashSourceVars;
			this.isAdditive = isAdditive;
			this.isLocal = isLocal;
			this.isMethodParam = isMethodParam;
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset) {
			this(varKey, cleanName, className, ownerClass, file, offset, null, null, null, null, null, null, false, false, false, null);
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset,
							   @Nullable List<String> columns) {
			this(varKey, cleanName, className, ownerClass, file, offset, columns, null, null, null, null, null, false, false, false, null);
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset,
							   @Nullable List<String> columns, @Nullable String sourceVarKey) {
			this(varKey, cleanName, className, ownerClass, file, offset, columns, sourceVarKey, null, null, null, null, false, false, false, null);
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset,
							   @Nullable List<String> columns, @Nullable String sourceVarKey,
							   @Nullable String methodName, @Nullable String targetClassName) {
			this(varKey, cleanName, className, ownerClass, file, offset, columns, sourceVarKey, methodName, targetClassName, null, null, false, false, false, null);
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset,
							   @Nullable List<String> columns, @Nullable String sourceVarKey,
							   @Nullable String methodName, @Nullable String targetClassName,
							   @Nullable java.util.Map<String, HashEntryInfo> hashKeys) {
			this(varKey, cleanName, className, ownerClass, file, offset, columns, sourceVarKey, methodName, targetClassName, hashKeys, null, false, false, false, null);
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset,
							   @Nullable List<String> columns, @Nullable String sourceVarKey,
							   @Nullable String methodName, @Nullable String targetClassName,
							   @Nullable java.util.Map<String, HashEntryInfo> hashKeys,
							   @Nullable java.util.List<String> hashSourceVars) {
			this(varKey, cleanName, className, ownerClass, file, offset, columns, sourceVarKey, methodName, targetClassName, hashKeys, hashSourceVars, false, false, false, null);
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset,
							   @Nullable List<String> columns, @Nullable String sourceVarKey,
							   @Nullable String methodName, @Nullable String targetClassName,
							   @Nullable java.util.Map<String, HashEntryInfo> hashKeys,
							   @Nullable java.util.List<String> hashSourceVars,
							   @Nullable String receiverVarKey) {
			this(varKey, cleanName, className, ownerClass, file, offset, columns, sourceVarKey, methodName, targetClassName, hashKeys, hashSourceVars, false, false, false, receiverVarKey);
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset,
							   @Nullable List<String> columns, @Nullable String sourceVarKey,
							   @Nullable String methodName, @Nullable String targetClassName,
							   @Nullable java.util.Map<String, HashEntryInfo> hashKeys,
							   @Nullable java.util.List<String> hashSourceVars,
							   boolean isAdditive,
							   @Nullable String receiverVarKey) {
			this(varKey, cleanName, className, ownerClass, file, offset, columns, sourceVarKey, methodName,
					targetClassName, hashKeys, hashSourceVars, isAdditive, false, false, receiverVarKey);
		}

		public VisibleVariable(@NotNull String varKey, @NotNull String cleanName, @NotNull String className,
							   @Nullable String ownerClass, @NotNull VirtualFile file, int offset,
							   @Nullable List<String> columns, @Nullable String sourceVarKey,
							   @Nullable String methodName, @Nullable String targetClassName,
							   @Nullable java.util.Map<String, HashEntryInfo> hashKeys,
							   @Nullable java.util.List<String> hashSourceVars,
							   boolean isAdditive,
							   boolean isLocal,
							   @Nullable String receiverVarKey) {
			this(varKey, cleanName, className, ownerClass, file, offset, columns, sourceVarKey, methodName,
					targetClassName, hashKeys, hashSourceVars, isAdditive, isLocal, false, receiverVarKey);
		}

		/** Factory-метод для параметра метода. */
		public static VisibleVariable ofMethodParam(@NotNull String paramName, @Nullable String paramType,
													@NotNull VirtualFile file, int offset) {
			String type = (paramType != null && !paramType.isEmpty()) ? paramType : "";
			return new VisibleVariable(paramName, paramName, type, null, file, offset,
					null, null, null, null, null, null, false, true, true, null);
		}

		public static VisibleVariable ofMethodParam(
				@NotNull String paramName,
				@NotNull String paramType,
				@NotNull VirtualFile file,
				int offset,
				@Nullable List<String> columns,
				@Nullable java.util.Map<String, HashEntryInfo> hashKeys
		) {
			return new VisibleVariable(paramName, paramName, paramType, null, file, offset,
					columns, null, null, null, hashKeys, null, false, true, true, null);
		}

		/**
		 * true если тип требует ленивого резолва (METHOD_CALL_MARKER).
		 */
		public boolean needsTypeResolve() {
			return P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(className);
		}

		/**
		 * Тип для отображения в UI (скрывает METHOD_CALL_MARKER).
		 */
		public @NotNull String getDisplayType() {
			if (needsTypeResolve()) return "";
			if (P3VariableFileIndex.UNKNOWN_TYPE.equals(className)) return "";
			return className;
		}
	}

	public static final class VariableCompletionInfo {
		public final @NotNull VisibleVariable variable;
		public final @Nullable List<String> columns;
		public final @Nullable Map<String, HashEntryInfo> hashKeys;
		public final boolean appendDot;

		private VariableCompletionInfo(
				@NotNull VisibleVariable variable,
				@Nullable List<String> columns,
				@Nullable Map<String, HashEntryInfo> hashKeys,
				boolean appendDot
		) {
			this.variable = variable;
			this.columns = columns;
			this.hashKeys = hashKeys;
			this.appendDot = appendDot;
		}
	}

	public static final class ChainResolveInfo {
		public final @Nullable String className;
		public final @Nullable VisibleVariable rootVariable;
		public final @Nullable HashEntryInfo hashEntry;
		public final @Nullable Map<String, HashEntryInfo> hashKeys;
		public final @Nullable List<String> columns;

		public ChainResolveInfo(
				@Nullable String className,
				@Nullable VisibleVariable rootVariable,
				@Nullable HashEntryInfo hashEntry,
				@Nullable Map<String, HashEntryInfo> hashKeys,
				@Nullable List<String> columns
		) {
			this.className = className;
			this.rootVariable = rootVariable;
			this.hashEntry = hashEntry;
			this.hashKeys = hashKeys;
			this.columns = columns;
		}
	}

	// ===== Контекст курсора =====

	static final class CursorContext {
		final @Nullable String text;
		final @Nullable List<Parser3ClassUtils.ClassBoundary> classBoundaries;
		final @Nullable List<Parser3ClassUtils.MethodBoundary> methodBoundaries;
		final @Nullable String ownerClass;
		final @Nullable Parser3ClassUtils.MethodBoundary method;
		final boolean inheritedMainLocals;
		final boolean hasLocals;
		final int offset;
		/** Иерархия классов: текущий класс + все @BASE предки. Для MAIN — пустой. */
		final @NotNull java.util.Set<String> classHierarchy;

		CursorContext(@Nullable String text,
					  @Nullable List<Parser3ClassUtils.ClassBoundary> classBoundaries,
					  @Nullable List<Parser3ClassUtils.MethodBoundary> methodBoundaries,
					  @Nullable String ownerClass,
					  @Nullable Parser3ClassUtils.MethodBoundary method,
					  boolean inheritedMainLocals, boolean hasLocals, int offset,
					  @NotNull java.util.Set<String> classHierarchy) {
			this.text = text;
			this.classBoundaries = classBoundaries;
			this.methodBoundaries = methodBoundaries;
			this.ownerClass = ownerClass;
			this.method = method;
			this.inheritedMainLocals = inheritedMainLocals;
			this.hasLocals = hasLocals;
			this.offset = offset;
			this.classHierarchy = classHierarchy;
		}
	}

	private static final class TemporaryScope {
		final int startOffset;
		final int endOffset;

		TemporaryScope(int startOffset, int endOffset) {
			this.startOffset = startOffset;
			this.endOffset = endOffset;
		}
	}

	private @NotNull CursorContext buildCursorContext(@NotNull VirtualFile file, int offset) {
		ResolveRequestCache cache = RESOLVE_REQUEST_CACHE.get();
		ScopeFilesKey key = new ScopeFilesKey(file, offset);
		if (cache.depth > 0) {
			CursorContext cached = cache.cursorContexts.get(key);
			if (cached != null) {
				return cached;
			}
		}

		String text = readFileTextSmart(file);
		CursorContext result;
		if (text == null) {
			result = new CursorContext(null, null, null, null, null, false, false, offset,
					java.util.Collections.emptySet());
			if (cache.depth > 0) {
				cache.cursorContexts.put(key, result);
			}
			return result;
		}
		List<Parser3ClassUtils.ClassBoundary> cb = Parser3ClassUtils.findClassBoundaries(text);
		List<Parser3ClassUtils.MethodBoundary> mb = Parser3ClassUtils.findMethodBoundaries(text, cb);
		String ownerClass = Parser3ClassUtils.findOwnerClass(offset, cb);
		Parser3ClassUtils.MethodBoundary method = Parser3ClassUtils.findOwnerMethod(offset, mb);
		boolean inheritedMainLocals = ownerClass == null
				&& P3EffectiveScopeService.getInstance(project).hasInheritedMainLocals(file, offset);
		// @OPTIONS locals (уровень класса) ИЛИ [locals] у текущего метода
		int localsCheckOffset = method != null ? method.start : offset;
		boolean hasLocals = inheritedMainLocals
				|| hasLocalsForContextBefore(text, ownerClass, cb, localsCheckOffset)
				|| (method != null && method.hasLocals);

		// Собираем иерархию классов (@BASE цепочка)
		java.util.Set<String> classHierarchy = buildClassHierarchy(ownerClass);

		result = new CursorContext(text, cb, mb, ownerClass, method, inheritedMainLocals, hasLocals, offset, classHierarchy);
		if (cache.depth > 0) {
			cache.cursorContexts.put(key, result);
		}
		return result;
	}

	private @NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parseCurrentFileVariables(
			@NotNull CursorContext ctx,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		if (ctx.text == null) {
			return java.util.Collections.emptyMap();
		}

		ResolveRequestCache cache = RESOLVE_REQUEST_CACHE.get();
		ScopeFilesKey key = new ScopeFilesKey(currentFile, currentOffset);
		if (cache.depth > 0) {
			Map<String, List<P3VariableFileIndex.VariableTypeInfo>> cached = cache.parsedCurrentFiles.get(key);
			if (cached != null) {
				return cached;
			}
		}

		Map<String, List<P3VariableFileIndex.VariableTypeInfo>> parsed =
				P3VariableFileIndex.parseVariablesFromText(ctx.text);
		if (cache.depth > 0) {
			cache.parsedCurrentFiles.put(key, parsed);
		}
		return parsed;
	}

	/**
	 * Собирает цепочку наследования: текущий класс + все @BASE предки.
	 * Для MAIN (ownerClass=null) возвращает пустой набор.
	 */
	private @NotNull java.util.Set<String> buildClassHierarchy(@Nullable String ownerClass) {
		java.util.Set<String> hierarchy = new java.util.LinkedHashSet<>();
		if (ownerClass == null) return hierarchy;

		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		String current = ownerClass;
		while (current != null && !hierarchy.contains(current)) {
			hierarchy.add(current);
			List<ru.artlebedev.parser3.model.P3ClassDeclaration> classes = classIndex.findByName(current);
			if (DEBUG_ALWAYS) System.out.println("[buildClassHierarchy] current=" + current + " found=" + classes.size()
					+ " baseName=" + (!classes.isEmpty() ? classes.get(classes.size() - 1).getBaseClassName() : "N/A"));
			if (!classes.isEmpty()) {
				current = classes.get(classes.size() - 1).getBaseClassName();
			} else {
				current = null;
			}
		}
		if (DEBUG_ALWAYS) System.out.println("[buildClassHierarchy] result=" + hierarchy);
		return hierarchy;
	}

	/**
	 * Собирает цепочку наследования от БАЗОВОГО класса (без текущего).
	 * Для $BASE:var — ищем только в @BASE предках.
	 */
	private @NotNull java.util.Set<String> buildBaseOnlyHierarchy(@NotNull String ownerClass) {
		java.util.Set<String> hierarchy = new java.util.LinkedHashSet<>();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		// Находим базовый класс текущего класса
		List<ru.artlebedev.parser3.model.P3ClassDeclaration> classes = classIndex.findByName(ownerClass);
		if (classes.isEmpty()) return hierarchy;

		String baseName = classes.get(classes.size() - 1).getBaseClassName();
		if (baseName == null) return hierarchy;

		// Собираем иерархию от базового класса
		String current = baseName;
		while (current != null && !hierarchy.contains(current)) {
			hierarchy.add(current);
			classes = classIndex.findByName(current);
			if (!classes.isEmpty()) {
				current = classes.get(classes.size() - 1).getBaseClassName();
			} else {
				current = null;
			}
		}
		return hierarchy;
	}

	// ===== ЕДИНСТВЕННЫЙ ИСТОЧНИК: getVisibleVariables =====

	/**
	 * ЕДИНСТВЕННЫЙ ИСТОЧНИК видимых переменных.
	 *
	 * Возвращает ВСЕ переменные видимые из данной позиции курсора.
	 * Автокомплит, навигация, определение типа — всё через эту функцию.
	 *
	 * Для каждого cleanName возвращается последнее присваивание (наибольший offset).
	 * Дедупликация по cleanName+ownerClass: $var и $self.var (одно и то же без locals) → одна запись.
	 */
	public @NotNull List<VisibleVariable> getVisibleVariables(
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		P3ScopeContext.VariableSearchData variableSearchData = P3ScopeContext.buildVariableSearchData(
				project, currentFile, cursorOffset, visibleFiles);
		return getVisibleVariables(new P3VariableScopeContext(
				project, currentFile, cursorOffset, variableSearchData.files, variableSearchData.useOffsetMap));
	}

	public @NotNull List<VisibleVariable> getVisibleVariables(
			@NotNull P3VariableScopeContext scopeContext
	) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		VirtualFile currentFile = scopeContext.getCurrentFile();
		int cursorOffset = scopeContext.getCursorOffset();
		long cursorContextStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		CursorContext ctx = buildCursorContext(currentFile, cursorOffset);
		if (DEBUG_PERF) {
			System.out.println("[P3VarIndex.PERF] buildCursorContext: "
					+ (System.currentTimeMillis() - cursorContextStart) + "ms"
					+ " file=" + currentFile.getName()
					+ " ownerClass=" + ctx.ownerClass
					+ " method=" + (ctx.method != null ? ctx.method.name : "null")
					+ " hasLocals=" + ctx.hasLocals
					+ " inheritedMainLocals=" + ctx.inheritedMainLocals);
		}
		boolean debug = DEBUG_ALWAYS;
		if (debug) System.out.println("[P3VarIndex.getVisibleVariables] file=" + currentFile.getName()
				+ " offset=" + cursorOffset + " ownerClass=" + ctx.ownerClass + " hasLocals=" + ctx.hasLocals
				+ " method=" + (ctx.method != null ? ctx.method.name : "null"));

		// Ключ дедупликации: cleanName + "|" + ownerClass → последнее VisibleVariable
		Map<String, VisibleVariable> dedup = new LinkedHashMap<>();
		// Параллельная карта: dedupKey → "эффективный offset" в контексте текущего файла
		// Для текущего файла: эффективный offset = реальный offset переменной
		// Для других файлов: эффективный offset = offset ^use[] в текущем файле
		Map<String, Integer> dedupEffectiveOffset = new HashMap<>();
		Map<String, List<P3VariableFileIndex.VariableTypeInfo>> currentFileData = null;

		if (debug) System.out.println("[P3VarIndex.getVisibleVariables] searchFiles="
				+ scopeContext.getSearchFiles().size());

		// 1. Текущий файл — парсим из Document (несохранённые изменения)
		if (ctx.text != null) {
			long currentParseStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			currentFileData = parseCurrentFileVariables(ctx, currentFile, cursorOffset);
			if (DEBUG_PERF) {
				System.out.println("[P3VarIndex.PERF] parseCurrentFileVariables: "
						+ (System.currentTimeMillis() - currentParseStart) + "ms"
						+ " keys=" + currentFileData.size()
						+ " textLength=" + ctx.text.length()
						+ " file=" + currentFile.getName());
			}
			long currentCollectStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			collectVisibleFromFileData(currentFileData, currentFile, ctx, cursorOffset, true, dedup, dedupEffectiveOffset);
			if (DEBUG_PERF) {
				System.out.println("[P3VarIndex.PERF] collectCurrentFileVariables: "
						+ (System.currentTimeMillis() - currentCollectStart) + "ms"
						+ " dedup=" + dedup.size()
						+ " file=" + currentFile.getName());
			}
		}

		// 2. Остальные файлы — из индекса: глобальные MAIN переменные + переменные из @BASE классов
		FileBasedIndex index = FileBasedIndex.getInstance();
		int otherFilesCount = 0;
		int skippedInvalidFiles = 0;
		int skippedCurrentFiles = 0;
		int skippedInvisibleFiles = 0;
		int emptyIndexFiles = 0;
		long otherFilesStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		long otherIndexTime = 0;
		long otherCollectTime = 0;
		for (VirtualFile file : scopeContext.getSearchFiles()) {
			// Проверка отмены — если пользователь набрал символ, IntelliJ отменяет completion
			com.intellij.openapi.progress.ProgressManager.checkCanceled();
			if (!file.isValid()) {
				skippedInvalidFiles++;
				continue;
			}
			if (file.equals(currentFile)) {
				skippedCurrentFiles++;
				continue;
			}
			if (!scopeContext.isSourceVisibleAtCursor(file)) {
				skippedInvisibleFiles++;
				continue;
			}

			long fileIndexStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			var fileData = index.getFileData(P3VariableFileIndex.NAME, file, project);
			if (DEBUG_PERF) {
				otherIndexTime += System.currentTimeMillis() - fileIndexStart;
			}
			if (fileData.isEmpty()) {
				emptyIndexFiles++;
				continue;
			}

			otherFilesCount++;
			// Эффективный offset: -1 для файлов из auto.p chain / main_auto (до кода текущего файла)
			int useOffset = scopeContext.getUseOffset(file);
			boolean inheritedMainLocals = scopeContext.hasInheritedMainLocalsForSourceFile(file);
			long fileCollectStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			collectVisibleFromOtherFile(fileData, file, ctx.classHierarchy, dedup, dedupEffectiveOffset, useOffset, inheritedMainLocals);
			if (DEBUG_PERF) {
				long collectElapsed = System.currentTimeMillis() - fileCollectStart;
				otherCollectTime += collectElapsed;
				if (collectElapsed > 20) {
					System.out.println("[P3VarIndex.PERF] collectOtherFileVariables SLOW: "
							+ collectElapsed + "ms"
							+ " file=" + file.getName()
							+ " keys=" + fileData.size()
							+ " dedup=" + dedup.size()
							+ " useOffset=" + useOffset
							+ " inheritedMainLocals=" + inheritedMainLocals);
				}
			}
		}
		if (DEBUG_PERF) {
			System.out.println("[P3VarIndex.PERF] otherFilesLoop: "
					+ (System.currentTimeMillis() - otherFilesStart) + "ms"
					+ " searchFiles=" + scopeContext.getSearchFiles().size()
					+ " withData=" + otherFilesCount
					+ " empty=" + emptyIndexFiles
					+ " skippedCurrent=" + skippedCurrentFiles
					+ " skippedInvalid=" + skippedInvalidFiles
					+ " skippedInvisible=" + skippedInvisibleFiles
					+ " indexTime=" + otherIndexTime + "ms"
					+ " collectTime=" + otherCollectTime + "ms");
		}

		// 3. Параметры текущего метода — перезаписывают глобальные переменные с тем же именем.
		// Если в dedup уже есть присваивание из текущего метода — параметр не перезаписывает его.
		long paramsStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		int paramsCount = 0;
		long parameterTypeTime = 0;
		if (ctx.method != null && ctx.text != null) {
			Set<String> params = getMethodParams(ctx.method, ctx.text);
			paramsCount = params.size();
			for (String paramName : params) {
				String dedupKey = paramName + "|null";
				VisibleVariable existing = dedup.get(dedupKey);
				// Если уже есть локальное присваивание внутри текущего метода — не перезаписываем
				boolean hasLocalAssignment = hasLocalAssignmentInCurrentMethod(dedup, paramName, currentFile, ctx.method);
				if (hasLocalAssignment) continue;
				long parameterTypeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
				String paramType = findParameterType(paramName, currentFile, cursorOffset);
				if (DEBUG_PERF) {
					parameterTypeTime += System.currentTimeMillis() - parameterTypeStart;
				}
				int paramOffset = findParamOffsetInMethod(paramName, ctx.method, ctx.text);
				VisibleVariable specialParam = createSpecialMethodParam(paramName, currentFile, paramOffset, ctx.method, ctx.text);
				dedup.put(dedupKey, specialParam != null
						? specialParam
						: VisibleVariable.ofMethodParam(paramName, paramType, currentFile, paramOffset));
			}
		}
		if (DEBUG_PERF) {
			System.out.println("[P3VarIndex.PERF] methodParams: "
					+ (System.currentTimeMillis() - paramsStart) + "ms"
					+ " params=" + paramsCount
					+ " findParameterTypeTime=" + parameterTypeTime + "ms");
		}

		long tryStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		addTryExceptionVariable(ctx, currentFile, currentFileData, dedup);
		addStringMatchVariable(ctx, currentFile, dedup);
		if (DEBUG_PERF) {
			System.out.println("[P3VarIndex.PERF] addTemporaryVariables: "
					+ (System.currentTimeMillis() - tryStart) + "ms");
		}

		if (DEBUG_PERF) {
			long elapsed = System.currentTimeMillis() - startTime;
			System.out.println("[P3VarIndex.PERF] getVisibleVariables: " + elapsed + "ms"
					+ " visibleFiles=" + scopeContext.getSearchFiles().size()
					+ " otherFilesWithData=" + otherFilesCount
					+ " resultVars=" + dedup.size()
					+ " file=" + currentFile.getName());
		}

		return new ArrayList<>(dedup.values());
	}

	private static boolean hasLocalAssignmentInCurrentMethod(
			@NotNull Map<String, VisibleVariable> dedup,
			@NotNull String paramName,
			@NotNull VirtualFile currentFile,
			@NotNull Parser3ClassUtils.MethodBoundary method
	) {
		for (VisibleVariable variable : dedup.values()) {
			if (variable.isMethodParam) continue;
			if (!paramName.equals(variable.cleanName)) continue;
			if (!variable.file.equals(currentFile)) continue;
			if (variable.offset <= method.start || variable.offset >= method.end) continue;
			if (variable.varKey.startsWith("self.") || variable.varKey.startsWith("MAIN:")) continue;
			return true;
		}
		return false;
	}

	private @Nullable VisibleVariable createSpecialMethodParam(
			@NotNull String paramName,
			@NotNull VirtualFile currentFile,
			int paramOffset,
			@NotNull Parser3ClassUtils.MethodBoundary method,
			@NotNull String text
	) {
		if (!"unhandled_exception".equals(method.name)) {
			return null;
		}

		List<String> params = getMethodParamsInOrder(method, text);
		int paramIndex = params.indexOf(paramName);
		if (paramIndex == 0) {
			return VisibleVariable.ofMethodParam(paramName, "hash", currentFile, paramOffset,
					null, TRY_EXCEPTION_HASH_KEYS);
		}
		if (paramIndex == 1) {
			return VisibleVariable.ofMethodParam(paramName, "table", currentFile, paramOffset,
					new ArrayList<>(TRY_EXCEPTION_HASH_KEYS.keySet()), null);
		}
		return null;
	}

	private void addTryExceptionVariable(
			@NotNull CursorContext ctx,
			@NotNull VirtualFile currentFile,
			@Nullable Map<String, List<P3VariableFileIndex.VariableTypeInfo>> currentFileData,
			@NotNull Map<String, VisibleVariable> dedup
	) {
		if (ctx.text == null) return;

		TemporaryScope scope = findTryExceptionScope(ctx.text, ctx.offset);
		if (scope == null) return;
		Map<String, HashEntryInfo> hashKeys = mergeHashEntryMaps(
				TRY_EXCEPTION_HASH_KEYS,
				attachHashEntryFiles(collectTryExceptionHashKeys(ctx, scope, currentFileData), currentFile)
		);

		dedup.put("exception|null", new VisibleVariable(
				"exception",
				"exception",
				"hash",
				ctx.ownerClass,
				currentFile,
				scope.startOffset,
				null,
				null,
				null,
				null,
				hashKeys,
				null
		));
	}

	private void addStringMatchVariable(
			@NotNull CursorContext ctx,
			@NotNull VirtualFile currentFile,
			@NotNull Map<String, VisibleVariable> dedup
	) {
		if (ctx.text == null) return;

		TemporaryScope scope = findStringMatchScope(ctx.text, ctx.offset);
		if (scope == null) return;

		dedup.put("match|null", new VisibleVariable(
				"match",
				"match",
				"table",
				ctx.ownerClass,
				currentFile,
				scope.startOffset,
				STRING_MATCH_COLUMNS,
				null,
				null,
				null,
				null,
				null
		));
	}

	private @Nullable Map<String, HashEntryInfo> collectTryExceptionHashKeys(
			@NotNull CursorContext ctx,
			@NotNull TemporaryScope scope,
			@Nullable Map<String, List<P3VariableFileIndex.VariableTypeInfo>> currentFileData
	) {
		if (ctx.text == null || currentFileData == null) return null;
		List<P3VariableFileIndex.VariableTypeInfo> infos = currentFileData.get("exception");
		if (infos == null || infos.isEmpty()) return null;

		Map<String, HashEntryInfo> result = null;
		for (P3VariableFileIndex.VariableTypeInfo info : infos) {
			if (info.offset >= ctx.offset) continue;
			if (!info.isAdditive) continue;
			if (info.hashKeys == null || info.hashKeys.isEmpty()) continue;
			if (isCursorInsideAssignmentRHS(ctx.text, info.offset, ctx.offset)) continue;
			if (!isVariableVisible(info, ctx, null)) continue;
			TemporaryScope infoScope = findTryExceptionScope(ctx.text, info.offset);
			if (!isSameTemporaryScope(scope, infoScope)) continue;

			result = mergeHashEntryMaps(result, info.hashKeys);
		}
		return result;
	}

	private static boolean isSameTemporaryScope(
			@NotNull TemporaryScope expected,
			@Nullable TemporaryScope actual
	) {
		return actual != null
				&& expected.startOffset == actual.startOffset
				&& expected.endOffset == actual.endOffset;
	}

	private static @Nullable TemporaryScope findTryExceptionScope(@NotNull String text, int cursorOffset) {
		TemporaryScope result = null;
		int safeOffset = Math.max(0, Math.min(cursorOffset, text.length()));

		for (int i = 0; i + 4 <= safeOffset; i++) {
			if (text.charAt(i) != '^') continue;
			if (i > 0 && text.charAt(i - 1) == '^') continue;
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, i)) continue;

			int pos = findExceptionBodyStart(text, i);
			if (pos >= text.length() || text.charAt(pos) != '{') continue;

			int firstBlockEnd = ru.artlebedev.parser3.lexer.Parser3LexerUtils.findMatchingBracket(
					text, pos, text.length(), '{', '}');
			if (firstBlockEnd < 0) continue;

			pos = skipWhitespace(text, firstBlockEnd + 1);
			if (pos >= text.length() || text.charAt(pos) != '{') continue;

			int catchStart = pos;
			int catchEnd = ru.artlebedev.parser3.lexer.Parser3LexerUtils.findMatchingBracket(
					text, catchStart, text.length(), '{', '}');
			if (catchEnd < 0) continue;

			if (safeOffset > catchStart && safeOffset <= catchEnd) {
				result = new TemporaryScope(catchStart, catchEnd);
			}
		}

		return result;
	}

	private static @Nullable TemporaryScope findStringMatchScope(@NotNull String text, int cursorOffset) {
		TemporaryScope result = null;
		int safeOffset = Math.max(0, Math.min(cursorOffset, text.length()));

		for (int i = 0; i + 7 <= safeOffset; i++) {
			if (text.charAt(i) != '^') continue;
			if (i > 0 && text.charAt(i - 1) == '^') continue;
			if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, i)) continue;

			int headerEnd = findParserCallHeaderEnd(text, i + 1);
			if (headerEnd <= i + 1) continue;
			String header = text.substring(i + 1, headerEnd);
			if (!header.endsWith(".match")) continue;

			int pos = skipWhitespace(text, headerEnd);
			boolean hasArguments = false;
			while (pos < text.length()) {
				char ch = text.charAt(pos);
				if (ch != '[' && ch != '(') break;
				char close = ch == '[' ? ']' : ')';
				int end = ru.artlebedev.parser3.lexer.Parser3LexerUtils.findMatchingBracket(
						text, pos, text.length(), ch, close);
				if (end < 0) break;
				hasArguments = true;
				pos = skipWhitespace(text, end + 1);
			}
			if (!hasArguments || pos >= text.length() || text.charAt(pos) != '{') continue;

			int replacementStart = pos;
			int replacementEnd = ru.artlebedev.parser3.lexer.Parser3LexerUtils.findMatchingBracket(
					text, replacementStart, text.length(), '{', '}');
			if (replacementEnd < 0) continue;

			if (safeOffset > replacementStart && safeOffset <= replacementEnd) {
				result = new TemporaryScope(replacementStart, replacementEnd);
			}
		}

		return result;
	}

	private static int findParserCallHeaderEnd(@NotNull String text, int pos) {
		int result = pos;
		while (result < text.length()) {
			char ch = text.charAt(result);
			if (ch == '[' || ch == '(' || ch == '{' || Character.isWhitespace(ch)) break;
			result++;
		}
		return result;
	}

	private static int findExceptionBodyStart(@NotNull String text, int caretOffset) {
		if (startsWithParserOperator(text, caretOffset, "try")) {
			return skipWhitespace(text, caretOffset + 4);
		}
		if (startsWithParserOperator(text, caretOffset, "cache")) {
			int pos = skipWhitespace(text, caretOffset + 6);
			while (pos < text.length()) {
				char ch = text.charAt(pos);
				if (ch != '[' && ch != '(') break;
				char close = ch == '[' ? ']' : ')';
				int end = ru.artlebedev.parser3.lexer.Parser3LexerUtils.findMatchingBracket(
						text, pos, text.length(), ch, close);
				if (end < 0) return text.length();
				pos = skipWhitespace(text, end + 1);
			}
			return pos;
		}
		return text.length();
	}

	private static boolean startsWithParserOperator(
			@NotNull String text,
			int caretOffset,
			@NotNull String operatorName
	) {
		int nameStart = caretOffset + 1;
		if (!text.startsWith(operatorName, nameStart)) return false;
		int nameEnd = nameStart + operatorName.length();
		return nameEnd >= text.length() || !isIdentChar(text.charAt(nameEnd));
	}

	private static int skipWhitespace(@NotNull String text, int pos) {
		int result = pos;
		while (result < text.length() && Character.isWhitespace(text.charAt(result))) {
			result++;
		}
		return result;
	}

	private static boolean isIdentChar(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '_';
	}

	private static @NotNull Map<String, HashEntryInfo> createTryExceptionHashKeys() {
		Map<String, HashEntryInfo> keys = new LinkedHashMap<>();
		keys.put("type", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, -1));
		keys.put("source", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, -1));
		keys.put("comment", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, -1));
		keys.put("file", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, -1));
		keys.put("lineno", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, -1));
		keys.put("colno", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, -1));
		keys.put("handled", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE, null, null, -1));
		return keys;
	}

	private static @Nullable Map<String, HashEntryInfo> attachHashEntryFiles(
			@Nullable Map<String, HashEntryInfo> hashKeys,
			@NotNull VirtualFile file
	) {
		if (hashKeys == null || hashKeys.isEmpty()) return hashKeys;
		Map<String, HashEntryInfo> result = new LinkedHashMap<>(hashKeys.size());
		for (Map.Entry<String, HashEntryInfo> entry : hashKeys.entrySet()) {
			result.put(entry.getKey(), entry.getValue().withFile(file));
		}
		return result;
	}

	/**
	 * Собирает видимые переменные из данных текущего файла.
	 * Эффективный offset = реальный offset переменной.
	 */
	private void collectVisibleFromFileData(
			@NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> fileData,
			@NotNull VirtualFile file,
			@NotNull CursorContext ctx,
			int maxOffset,
			boolean isCurrentFile,
			@NotNull Map<String, VisibleVariable> dedup,
			@NotNull Map<String, Integer> dedupEffectiveOffset
	) {
		for (Map.Entry<String, List<P3VariableFileIndex.VariableTypeInfo>> entry : fileData.entrySet()) {
			String varKey = entry.getKey();
			List<P3VariableFileIndex.VariableTypeInfo> infos = entry.getValue();

			P3VariableEffectiveShape shape = new P3VariableEffectiveShape(extractPureVarName(varKey), true);

			for (P3VariableFileIndex.VariableTypeInfo info : infos) {
				if (info.offset >= maxOffset) continue;
				// Пропускаем присваивание, внутри правой части которого находится курсор.
				// $var[^var.split[,]] — курсор внутри [...], переменная ещё не перезаписана.
				if (isCurrentFile && ctx.text != null
						&& isCursorInsideAssignmentRHS(ctx.text, info.offset, maxOffset)) {
					continue;
				}
				String pureVarName = extractPureVarName(varKey);
				if (isCurrentFile
						&& ctx.text != null
						&& isTemporaryVariableName(pureVarName)
						&& !isTemporaryVariableInfoVisibleAtCursor(ctx.text, pureVarName, info.offset, ctx.offset)) {
					continue;
				}
				if (!isVariableVisible(info, ctx, null)) continue;
				shape.add(info);
			}

			if (shape.hasVisible()) {
				P3VariableFileIndex.VariableTypeInfo structuralVisible = shape.structuralVisible();
				if (structuralVisible == null) continue;
				// ЛЕНИВЫЙ РЕЗОЛВ: не вызываем resolveClassName(), сохраняем сырой тип
				// METHOD_CALL_MARKER + methodName/targetClassName для резолва по запросу
				String className = shape.className();

				// Используем merged hashKeys/sourceVars вместо только lastVisible
				Map<String, HashEntryInfo> fileHashKeys = attachHashEntryFiles(shape.hashKeys(), file);
				List<String> effectiveColumns = P3VariableEffectiveShape.applyColumnHashMutations(structuralVisible.columns, fileHashKeys);
				List<String> fileHashSourceVars = shape.hashSourceVars();
				String effectiveSourceVarKey = shape.effectiveSourceVarKey();
				boolean isTryExceptionCopy = "exception".equals(effectiveSourceVarKey)
						&& ctx.text != null
						&& findTryExceptionScope(ctx.text, structuralVisible.offset) != null;
				boolean isStringMatchCopy = "match".equals(effectiveSourceVarKey)
						&& ctx.text != null
						&& findStringMatchScope(ctx.text, structuralVisible.offset) != null;
				if (isTryExceptionCopy) {
					if (P3VariableFileIndex.UNKNOWN_TYPE.equals(className)) {
						className = "hash";
					}
					if (fileHashKeys == null || fileHashKeys.isEmpty()) {
						fileHashKeys = TRY_EXCEPTION_HASH_KEYS;
					}
				}
				if (isStringMatchCopy) {
					if (P3VariableFileIndex.UNKNOWN_TYPE.equals(className)) {
						className = "table";
					}
					if (effectiveColumns == null || effectiveColumns.isEmpty()) {
						effectiveColumns = STRING_MATCH_COLUMNS;
					}
				}

				String cleanName = extractPureVarName(varKey);
				// При @OPTIONS locals: $setting и $self.setting — разные переменные.
				// Включаем prefix в ключ дедупликации чтобы они не мержились.
				String varPrefix = extractVarPrefix(varKey);
				String dedupKey = (ctx.hasLocals && varPrefix != null ? varPrefix : "")
						+ cleanName + "|" + structuralVisible.ownerClass;

				if (DEBUG_ALWAYS) System.out.println("[collectVisible] varKey=" + varKey + " cleanName=" + cleanName
						+ " dedupKey=" + dedupKey + " className=" + className
						+ " columns=" + effectiveColumns + " sourceVarKey=" + structuralVisible.sourceVarKey
						+ " already=" + dedup.containsKey(dedupKey));
				boolean isLocal = isInfoLocal(structuralVisible, ctx.inheritedMainLocals);

				// Дедупликация по cleanName+ownerClass: $var, $self.var, $MAIN:var — одна переменная.
				// Для текущего файла: эффективный offset = реальный offset переменной.
				int effectiveOffset = structuralVisible.offset;
				int existingEffective = dedupEffectiveOffset.getOrDefault(dedupKey, Integer.MIN_VALUE);

				VisibleVariable existing = dedup.get(dedupKey);
				if (existing == null || effectiveOffset > existingEffective) {
					// Мержим hashKeys с предыдущей записью только для кросс-файловых дополнений.
					// Внутри того же файла одноимённые переменные из разных foreach/блоков
					// не должны наследовать структуру друг друга.
					Map<String, HashEntryInfo> mergedHashKeys = fileHashKeys;
					List<String> mergedHashSourceVars = fileHashSourceVars;
					boolean shouldMergeExistingShape = existing != null && !file.equals(existing.file);
					if (shouldMergeExistingShape && existing.hashKeys != null && !existing.hashKeys.isEmpty()) {
						mergedHashKeys = mergeHashEntryMaps(existing.hashKeys, fileHashKeys);
					}
					if (shouldMergeExistingShape && existing.hashSourceVars != null && !existing.hashSourceVars.isEmpty()) {
						if (mergedHashSourceVars == null) {
							mergedHashSourceVars = existing.hashSourceVars;
						} else {
							mergedHashSourceVars = new ArrayList<>(existing.hashSourceVars);
							for (String sv : fileHashSourceVars) {
								if (!mergedHashSourceVars.contains(sv)) mergedHashSourceVars.add(sv);
							}
						}
					}

					String mergedClassName = preferExistingClassNameForAdditive(existing, className, structuralVisible.isAdditive);

					dedup.put(dedupKey, new VisibleVariable(varKey, cleanName, mergedClassName,
							structuralVisible.ownerClass, file, structuralVisible.offset, effectiveColumns,
							effectiveSourceVarKey,
							structuralVisible.methodName, structuralVisible.targetClassName, mergedHashKeys, mergedHashSourceVars,
							structuralVisible.isAdditive, isLocal, structuralVisible.receiverVarKey));
					dedupEffectiveOffset.put(dedupKey, effectiveOffset);
				} else if (existing != null && fileHashKeys != null && !fileHashKeys.isEmpty()) {
					// Offset меньше — не перезаписываем, но мержим hashKeys
					Map<String, HashEntryInfo> mergedHashKeys = mergeHashEntryMaps(existing.hashKeys, fileHashKeys);
					dedup.put(dedupKey, new VisibleVariable(existing.varKey, existing.cleanName, existing.className,
							existing.ownerClass, existing.file, existing.offset, existing.columns, existing.sourceVarKey,
							existing.methodName, existing.targetClassName, mergedHashKeys, existing.hashSourceVars,
							existing.isAdditive, existing.isLocal, existing.receiverVarKey));
				}
			}
		}
	}

	/**
	 * Собирает видимые переменные из другого файла:
	 * - Глобальные MAIN переменные (ownerClass=null, !isLocal)
	 * - Переменные из @BASE классов (ownerClass входит в classHierarchy)
	 * Учитывает локальность из индекса и унаследованный @OPTIONS locals.
	 *
	 * @param useOffset "виртуальный offset" ^use[] в текущем файле (-1 для auto.p chain)
	 */
	private void collectVisibleFromOtherFile(
			@NotNull Map<String, List<P3VariableFileIndex.VariableTypeInfo>> fileData,
			@NotNull VirtualFile file,
			@NotNull java.util.Set<String> classHierarchy,
			@NotNull Map<String, VisibleVariable> dedup,
			@NotNull Map<String, Integer> dedupEffectiveOffset,
			int useOffset,
			boolean inheritedMainLocals
	) {
		for (Map.Entry<String, List<P3VariableFileIndex.VariableTypeInfo>> entry : fileData.entrySet()) {
			String varKey = entry.getKey();
			List<P3VariableFileIndex.VariableTypeInfo> infos = entry.getValue();
			String pureVarName = extractPureVarName(varKey);
			String fileText = null;

			P3VariableEffectiveShape shape = new P3VariableEffectiveShape(pureVarName, true);
			for (P3VariableFileIndex.VariableTypeInfo info : infos) {
				// Локальные переменные не видны из других файлов
				if (isInfoLocal(info, inheritedMainLocals)) continue;
				if (isTemporaryVariableName(pureVarName)) {
					if (fileText == null) fileText = readFileTextSmart(file);
					if (fileText != null && findTemporaryVariableScope(fileText, pureVarName, info.offset) != null) continue;
				}

				if (info.ownerClass == null) {
					// MAIN переменная — видна
				} else if (!classHierarchy.isEmpty() && classHierarchy.contains(info.ownerClass)) {
					// Переменная из @BASE класса — видна через наследование
				} else {
					// Переменная чужого класса — не видна
					continue;
				}

				shape.add(info);
			}

			if (shape.hasVisible()) {
				P3VariableFileIndex.VariableTypeInfo structuralVisible = shape.structuralVisible();
				if (structuralVisible == null) continue;
				// ЛЕНИВЫЙ РЕЗОЛВ: не вызываем resolveClassName(), сохраняем сырой тип
				String className = shape.className();
				Map<String, HashEntryInfo> fileHashKeys = attachHashEntryFiles(shape.hashKeys(), file);
				List<String> effectiveColumns = P3VariableEffectiveShape.applyColumnHashMutations(structuralVisible.columns, fileHashKeys);
				List<String> fileHashSourceVars = shape.hashSourceVars();
				String effectiveSourceVarKey = shape.effectiveSourceVarKey();

				String cleanName = extractPureVarName(varKey);
				String varPrefix = extractVarPrefix(varKey);
				String dedupKey = (varPrefix != null ? varPrefix : "")
						+ cleanName + "|" + structuralVisible.ownerClass;

				// Дедупликация с учётом позиции ^use[] в текущем файле:
				// - useOffset > existingEffective → этот файл подключён позже → перезаписываем
				// - useOffset == existingEffective, другой файл → позже в цепочке → перезаписываем
				// - useOffset == existingEffective, тот же файл → больший offset побеждает
				// - useOffset < existingEffective → не перезаписываем
				int existingEffective = dedupEffectiveOffset.getOrDefault(dedupKey, Integer.MIN_VALUE);
				VisibleVariable existing = dedup.get(dedupKey);

				if (existing == null ||
						useOffset > existingEffective ||
						(useOffset == existingEffective && (!file.equals(existing.file) || structuralVisible.offset > existing.offset))) {
					String mergedClassName = preferExistingClassNameForAdditive(existing, className, structuralVisible.isAdditive);

					dedup.put(dedupKey, new VisibleVariable(varKey, cleanName, mergedClassName,
							structuralVisible.ownerClass, file, structuralVisible.offset, effectiveColumns, effectiveSourceVarKey,
							structuralVisible.methodName, structuralVisible.targetClassName, fileHashKeys, fileHashSourceVars,
							structuralVisible.isAdditive, structuralVisible.receiverVarKey));
					dedupEffectiveOffset.put(dedupKey, useOffset);
				} else if (existing != null
						&& existing.isAdditive
						&& !structuralVisible.isAdditive
						&& className != null
						&& !className.isEmpty()
						&& !P3VariableFileIndex.UNKNOWN_TYPE.equals(className)) {
					Map<String, HashEntryInfo> mergedHashKeys = mergeHashEntryMaps(existing.hashKeys, fileHashKeys);
					List<String> mergedHashSourceVars = fileHashSourceVars;
					if (existing.hashSourceVars != null && !existing.hashSourceVars.isEmpty()) {
						if (mergedHashSourceVars == null) {
							mergedHashSourceVars = existing.hashSourceVars;
						} else {
							mergedHashSourceVars = new ArrayList<>(mergedHashSourceVars);
							for (String sv : existing.hashSourceVars) {
								if (!mergedHashSourceVars.contains(sv)) mergedHashSourceVars.add(sv);
							}
						}
					}
					dedup.put(dedupKey, new VisibleVariable(varKey, cleanName, className,
							structuralVisible.ownerClass, file, structuralVisible.offset, effectiveColumns, effectiveSourceVarKey,
							structuralVisible.methodName, structuralVisible.targetClassName, mergedHashKeys, mergedHashSourceVars,
							false, structuralVisible.receiverVarKey));
				}
			}
		}
	}

	private static @Nullable String preferExistingClassNameForAdditive(
			@Nullable VisibleVariable existing,
			@Nullable String newClassName,
			boolean isAdditive
	) {
		if (!isAdditive || existing == null) {
			return newClassName;
		}
		if (newClassName == null || newClassName.isEmpty() || "hash".equals(newClassName)
				|| P3VariableFileIndex.UNKNOWN_TYPE.equals(newClassName)) {
			if (existing.className != null && !existing.className.isEmpty()
					&& !P3VariableFileIndex.UNKNOWN_TYPE.equals(existing.className)) {
				return existing.className;
			}
		}
		return newClassName;
	}

	// ===== Публичные обёртки =====

	/**
	 * Нормализует список видимых переменных по контексту обращения.
	 *
	 * contextType определяет какие переменные видны:
	 * - "normal" / "$" → все (текущий класс + MAIN)
	 * - "self" → только текущий класс и @BASE иерархия (в CLASS) или MAIN (в MAIN)
	 * - "MAIN" → только MAIN (ownerClass=null)
	 *
	 * @param variables результат getVisibleVariables()
	 * @param contextType "normal", "self", "MAIN"
	 * @param cursorOwnerClass класс курсора (null = MAIN)
	 * @return Map: чистое имя → VisibleVariable
	 */
	public static @NotNull Map<String, VisibleVariable> filterByContext(
			@NotNull List<VisibleVariable> variables,
			@NotNull String contextType,
			@Nullable String cursorOwnerClass
	) {
		return filterByContext(variables, contextType, cursorOwnerClass, null);
	}

	/**
	 * Нормализует список видимых переменных по контексту обращения.
	 * Вариант с иерархией классов для $self.var — учитывает @BASE предков.
	 *
	 * @param classHierarchy набор классов: текущий + @BASE предки (null — без учёта иерархии)
	 */
	public static @NotNull Map<String, VisibleVariable> filterByContext(
			@NotNull List<VisibleVariable> variables,
			@NotNull String contextType,
			@Nullable String cursorOwnerClass,
			@Nullable java.util.Set<String> classHierarchy
	) {
		return filterByContext(variables, contextType, cursorOwnerClass, classHierarchy, false, null);
	}

	public static @NotNull Map<String, VisibleVariable> filterByContext(
			@NotNull List<VisibleVariable> variables,
			@NotNull String contextType,
			@Nullable String cursorOwnerClass,
			@Nullable java.util.Set<String> classHierarchy,
			boolean hasLocals,
			@Nullable java.util.Set<String> methodParams
	) {
		long filterStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		int duplicateCount = 0;
		int syntheticCheckCount = 0;
		long syntheticCheckTime = 0;
		int syntheticExistingWins = 0;
		int syntheticNewSkipped = 0;
		Map<String, VisibleVariable> result = new LinkedHashMap<>();

		for (VisibleVariable v : variables) {
			// Параметр метода: проходит только в normal-контексте, побеждает любую другую переменную с тем же именем
			if (v.isMethodParam) {
				if ("normal".equals(contextType)) {
					result.put(v.cleanName, v);
				}
				continue;
			}

			// В normal-контексте ($var): если X является параметром метода,
			// он затеняет $self.X — не показываем $self.X в списке.
			if ("normal".equals(contextType)
					&& v.varKey.startsWith("self.")
					&& methodParams != null && methodParams.contains(v.cleanName)) {
				continue;
			}

			// Фильтрация по контексту
			if ("MAIN".equals(contextType)) {
				// $MAIN:var — только MAIN
				if (v.ownerClass != null) continue;
				// При hasLocals: простая $var — локальная, не MAIN-переменная
				if (hasLocals && v.isLocal && !v.varKey.startsWith("MAIN:") && !v.varKey.startsWith("self.")) continue;
			} else if ("BASE".equals(contextType)) {
				// $BASE:var — только переменные из @BASE иерархии (без текущего класса)
				if (classHierarchy != null && !classHierarchy.isEmpty()) {
					if (!classHierarchy.contains(v.ownerClass)) continue;
				} else {
					// Нет иерархии — нечего показывать
					continue;
				}
			} else if ("self".equals(contextType) && cursorOwnerClass != null) {
				// $self.var в CLASS — текущий класс и @BASE иерархия
				// В MAIN: ownerClass=null эквивалентен "MAIN"
				// При hasLocals: простая $var — локальная, в $self. её быть не должно
				if (hasLocals && v.isLocal && !v.varKey.startsWith("self.")) continue;
				String effectiveOwner = v.ownerClass;
				if (effectiveOwner == null && "MAIN".equals(cursorOwnerClass)) {
					effectiveOwner = "MAIN";
				}
				if (classHierarchy != null && !classHierarchy.isEmpty()) {
					if (!classHierarchy.contains(effectiveOwner)) continue;
				} else {
					if (!cursorOwnerClass.equals(effectiveOwner)) continue;
				}
			}
			// "normal" — все видимые (текущий класс + @BASE + MAIN)

			// Дедупликация по cleanName: первый побеждает,
			// но локальная $var всегда побеждает над $self.var (при @OPTIONS locals),
			// и параметр метода не перезаписывается ничем.
			if (result.containsKey(v.cleanName)) {
				duplicateCount++;
				VisibleVariable existing = result.get(v.cleanName);
				if (existing.isMethodParam) continue; // параметр не перезаписываем
				long syntheticStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
				boolean existingSyntheticReadChain = isSyntheticReadChainOnly(existing);
				boolean newSyntheticReadChain = isSyntheticReadChainOnly(v);
				if (DEBUG_PERF) {
					syntheticCheckCount += 2;
					syntheticCheckTime += System.currentTimeMillis() - syntheticStart;
				}
				if (existingSyntheticReadChain && !newSyntheticReadChain) {
					syntheticExistingWins++;
					result.put(v.cleanName, v);
					continue;
				}
				if (!existingSyntheticReadChain && newSyntheticReadChain) {
					syntheticNewSkipped++;
					continue;
				}
				if ("normal".equals(contextType)
						&& existing.varKey.startsWith("MAIN:")
						&& !v.varKey.startsWith("MAIN:")
						&& !v.varKey.startsWith("self.")) {
					result.put(v.cleanName, v);
					continue;
				}
				if ("MAIN".equals(contextType)
						&& !existing.varKey.startsWith("MAIN:")
						&& v.varKey.startsWith("MAIN:")) {
					result.put(v.cleanName, v);
					continue;
				}
				// Если в result уже $self.var, а новый — простой $var (без self.) — заменяем
				if (existing.varKey.startsWith("self.") && !v.varKey.startsWith("self.")) {
					result.put(v.cleanName, v);
				}
			} else {
				result.put(v.cleanName, v);
			}
		}

		if (DEBUG_PERF) {
			System.out.println("[P3VarIndex.PERF] filterByContext: "
					+ (System.currentTimeMillis() - filterStart) + "ms"
					+ " context=" + contextType
					+ " input=" + variables.size()
					+ " output=" + result.size()
					+ " duplicates=" + duplicateCount
					+ " syntheticChecks=" + syntheticCheckCount
					+ " syntheticCheckTime=" + syntheticCheckTime + "ms"
					+ " syntheticExistingWins=" + syntheticExistingWins
					+ " syntheticNewSkipped=" + syntheticNewSkipped
					+ " hasLocals=" + hasLocals
					+ " owner=" + cursorOwnerClass);
		}

		return result;
	}

	private static boolean isSyntheticReadChainOnly(@NotNull VisibleVariable variable) {
		if (!variable.isAdditive) return false;
		P3VariableFileIndex.VariableTypeInfo info = new P3VariableFileIndex.VariableTypeInfo(
				variable.offset, variable.className, variable.ownerClass, null,
				variable.methodName, variable.targetClassName, extractVarPrefix(variable.varKey), variable.isLocal,
				variable.columns, variable.sourceVarKey, variable.hashKeys, variable.hashSourceVars,
				variable.isAdditive, variable.receiverVarKey);
		return P3VariableEffectiveShape.isSyntheticReadChainAdditive(info);
	}

	/**
	 * ЕДИНСТВЕННАЯ ТОЧКА ВХОДА для поиска переменной по varKey.
	 * Используется везде: автокомплит, навигация, определение типа, ключи хеша.
	 *
	 * Логика: getVisibleVariables() → filterByContext() → поиск по cleanName.
	 * Параметры метода уже включены в getVisibleVariables() (шаг 3).
	 *
	 * Для поиска только в текущем файле — передавать visibleFiles = [currentFile].
	 */
	public @Nullable VisibleVariable findVariable(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		String prefix = extractVarPrefix(varKey);
		String pureVarName = extractPureVarName(varKey);

		List<VisibleVariable> allVisible = getVisibleVariables(visibleFiles, currentFile, currentOffset);

		CursorContext ctx = buildCursorContext(currentFile, currentOffset);
		String contextType;
		if ("MAIN:".equals(prefix)) contextType = "MAIN";
		else if ("self.".equals(prefix)) contextType = "self";
		else if ("BASE:".equals(prefix)) contextType = "BASE";
		else contextType = "normal";

		Set<String> hierarchyForFilter = ctx.classHierarchy;
		if ("BASE".equals(contextType) && ctx.ownerClass != null) {
			hierarchyForFilter = buildBaseOnlyHierarchy(ctx.ownerClass);
		}

		Set<String> methodParamsSet = (ctx.method != null && ctx.text != null)
				? getMethodParams(ctx.method, ctx.text) : null;
		Map<String, VisibleVariable> filtered = filterByContext(allVisible, contextType, ctx.ownerClass,
				hierarchyForFilter, ctx.hasLocals, methodParamsSet);

		return filtered.get(pureVarName);
	}

	/**
	 * Находит offset параметра в сигнатуре метода.
	 */
	private int findParamOffsetInMethod(@NotNull String paramName,
										@NotNull Parser3ClassUtils.MethodBoundary method, @NotNull String text) {
		int bracketStart = text.indexOf('[', method.start);
		if (bracketStart < 0) return method.start;
		int bracketEnd = text.indexOf(']', bracketStart);
		if (bracketEnd < 0) return method.start;
		int pos = text.indexOf(paramName, bracketStart + 1);
		return (pos >= 0 && pos < bracketEnd) ? pos : method.start;
	}

	/**
	 * Результат поиска определения переменной.
	 */
	public static final class VariableDefinitionLocation {
		public final @NotNull VirtualFile file;
		public final int offset;

		public VariableDefinitionLocation(@NotNull VirtualFile file, int offset) {
			this.file = file;
			this.offset = offset;
		}
	}

	/**
	 * Находит место определения переменной. Обёртка над findVariable().
	 */
	public @Nullable VariableDefinitionLocation findVariableDefinitionLocation(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		VisibleVariable v = findVariable(varKey, visibleFiles, currentFile, currentOffset);
		if (v == null) return null;
		return new VariableDefinitionLocation(v.file, v.offset);
	}
	/**
	 * Поиск переменной только в текущем файле — обёртка над findVariable().
	 * Используется внутри для резолва hash-цепочек и псевдонимов.
	 *
	 * Возвращает null если: не найдена, тип = METHOD_CALL_MARKER или UNKNOWN_TYPE без sourceVarKey
	 * (в этих случаях вызывающий код должен использовать полный поиск).
	 */
	public @Nullable VisibleVariable findVariableInCurrentFileOnly(
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		VisibleVariable result = findVariable(varKey, java.util.Collections.singletonList(currentFile), currentFile, currentOffset);
		if (result == null) return null;
		result = resolveMethodCallVariable(result, java.util.Collections.singletonList(currentFile), currentFile, currentOffset);
		if (P3VariableFileIndex.UNKNOWN_TYPE.equals(result.className)
				&& (result.sourceVarKey == null || result.sourceVarKey.isEmpty())
				&& (result.hashKeys == null || result.hashKeys.isEmpty())
				&& (result.columns == null || result.columns.isEmpty())) return null;
		return result;
	}


	public @Nullable String findVariableClassInFiles(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		ChainResolveInfo chainInfo =
				resolveEffectiveChain(varKey, visibleFiles, currentFile, currentOffset);
		return chainInfo != null ? chainInfo.className : null;
	}

	/**
	 * Ленивый резолв типа переменной.
	 * Если тип = METHOD_CALL_MARKER — ищет # $result(type) в документации метода.
	 * Для остальных типов — возвращает как есть.
	 */
	public @Nullable String resolveVariableType(@NotNull VisibleVariable v, @NotNull List<VirtualFile> visibleFiles) {
		VisibleVariable resolved = resolveMethodCallVariable(v, visibleFiles, v.file, v.offset);
		return resolved != null ? resolved.className : P3VariableFileIndex.UNKNOWN_TYPE;
	}

	public @NotNull VisibleVariable resolveVisibleVariable(
			@NotNull VisibleVariable variable,
			@NotNull List<VirtualFile> visibleFiles
	) {
		return resolveMethodCallVariable(variable, visibleFiles, variable.file, variable.offset);
	}

	public @NotNull VisibleVariable resolveVisibleVariable(
			@NotNull VisibleVariable variable,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return resolveMethodCallVariable(variable, visibleFiles, currentFile, currentOffset);
	}

	public @NotNull VariableCompletionInfo analyzeVariableCompletion(
			@NotNull VisibleVariable variable,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return withResolveRequestCache(() -> analyzeResolvedVariableInternal(
				variable,
				visibleFiles,
				currentFile,
				currentOffset,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		));
	}

	public @Nullable VariableCompletionInfo resolveEffectiveVariable(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return withResolveRequestCache(() -> resolveEffectiveVariableInternal(
				varKey,
				visibleFiles,
				currentFile,
				currentOffset,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		));
	}

	private @Nullable VariableCompletionInfo resolveEffectiveVariableInternal(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			@NotNull java.util.Set<String> visitedValues,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		String resolveKey = currentFile.getPath() + "|" + currentOffset + "|" + varKey;
		if (!resolvingVarKeys.add(resolveKey)) {
			if (DEBUG_ALWAYS) {
				System.out.println("[P3VarIndex.resolveEffectiveVariable] цикл резолва остановлен: " + resolveKey);
			}
			return null;
		}

		VisibleVariable variable = findRawVisibleVariable(varKey, visibleFiles, currentFile, currentOffset);
		boolean canPreferGetter = variable == null || shouldPreferGetterProperty(variable);
		VariableCompletionInfo getterInfo = canPreferGetter
				? findGetterPropertyCompletionInfo(
						varKey,
						currentFile,
						currentOffset,
						visitedValues,
						resolvingVarKeys
				)
				: null;
		if (getterInfo != null) {
			resolvingVarKeys.remove(resolveKey);
			return getterInfo;
		}
		if (variable == null) {
			resolvingVarKeys.remove(resolveKey);
			return getterInfo;
		}
		try {
			return analyzeResolvedVariableInternal(
					variable,
					visibleFiles,
					currentFile,
					currentOffset,
					visitedValues,
					resolvingVarKeys
			);
		} finally {
			resolvingVarKeys.remove(resolveKey);
		}
	}

	private boolean shouldPreferGetterProperty(@Nullable VisibleVariable variable) {
		if (variable == null) return true;
		if (variable.isMethodParam || variable.isLocal) return false;
		return variable.isAdditive || isTrivialUnknownVariable(variable);
	}

	private @Nullable VariableCompletionInfo findGetterPropertyCompletionInfo(
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			@NotNull java.util.Set<String> visitedValues,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		String propertyName = extractPureVarName(varKey);
		if (propertyName.isEmpty() || propertyName.contains(".")) return null;

		CursorContext ctx = buildCursorContext(currentFile, currentOffset);
		String prefix = extractVarPrefix(varKey);
		java.util.Set<String> ownerCandidates;
		if ("BASE:".equals(prefix)) {
			ownerCandidates = ctx.ownerClass != null
					? buildBaseOnlyHierarchy(ctx.ownerClass)
					: java.util.Collections.emptySet();
		} else if ("MAIN:".equals(prefix)) {
			ownerCandidates = java.util.Collections.singleton(null);
		} else if (ctx.ownerClass != null) {
			ownerCandidates = buildClassHierarchy(ctx.ownerClass);
		} else {
			ownerCandidates = java.util.Collections.singleton(null);
		}
		if (ownerCandidates.isEmpty() && ctx.ownerClass != null) return null;

		List<VirtualFile> classFiles = getVisibleFilesForClassMembers(currentFile, currentOffset);
		P3MethodDeclaration getter = findGetterInOwners(propertyName, ownerCandidates, classFiles);
		if (getter == null) return null;

		P3MethodDocTypeResolver resolver = P3MethodDocTypeResolver.getInstance(project);
		P3ResolvedValue resolved = resolver.getMethodResolvedResult(getter.getName(), getter.getOwnerClass(), classFiles);
		VisibleVariable getterVariable = new VisibleVariable(
				varKey,
				propertyName,
				resolved != null ? resolved.className : P3VariableFileIndex.UNKNOWN_TYPE,
				getter.getOwnerClass(),
				getter.getFile(),
				getter.getOffset(),
				resolved != null ? resolved.columns : null,
				resolved != null ? resolved.sourceVarKey : null,
				resolved != null ? resolved.methodName : null,
				resolved != null ? resolved.targetClassName : null,
				resolved != null ? attachHashEntryFiles(resolved.hashKeys, getter.getFile()) : null,
				resolved != null ? resolved.hashSourceVars : null,
				false,
				resolved != null ? resolved.receiverVarKey : null
		);
		return analyzeResolvedVariableInternal(
				getterVariable,
				classFiles,
				getter.getFile(),
				Math.max(0, getter.getOffset() + 1),
				visitedValues,
				resolvingVarKeys
		);
	}

	private @Nullable P3MethodDeclaration findGetterInOwners(
			@NotNull String propertyName,
			@NotNull java.util.Set<String> ownerCandidates,
			@NotNull List<VirtualFile> classFiles
	) {
		List<P3MethodDeclaration> methods = P3MethodIndex.getInstance(project).findInFiles(propertyName, classFiles);
		for (String ownerClass : ownerCandidates) {
			P3MethodDeclaration fallback = null;
			for (P3MethodDeclaration method : methods) {
				if (!method.isGetter()) continue;
				if (!Objects.equals(ownerClass, method.getOwnerClass())) continue;
				fallback = method;
				if (classFiles.contains(method.getFile())) {
					return method;
				}
			}
			if (fallback != null) return fallback;
		}
		return null;
	}

	private @Nullable VisibleVariable findRawVisibleVariable(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		VisibleVariable fast = findVariableInCurrentFileRaw(varKey, currentFile, currentOffset);
		if (fast != null && !fast.isAdditive
				&& (fast.isMethodParam || !isTrivialUnknownVariable(fast))) {
			return fast;
		}

		VisibleVariable indexed = findVariableByIndexQuery(varKey, visibleFiles, currentFile, currentOffset);
		if (indexed != null && fast != null && fast.isAdditive) {
			return mergeVisibleVariableShapes(indexed, fast);
		}
		if (indexed != null && !indexed.isAdditive) {
			return indexed;
		}

		return findVariable(varKey, visibleFiles, currentFile, currentOffset);
	}

	private static @NotNull VisibleVariable mergeVisibleVariableShapes(
			@NotNull VisibleVariable base,
			@NotNull VisibleVariable overlay
	) {
		Map<String, HashEntryInfo> mergedHashKeys = mergeHashEntryMaps(base.hashKeys, overlay.hashKeys);
		List<String> mergedHashSourceVars = mergeHashSourceVars(base.hashSourceVars, overlay.hashSourceVars);
		List<String> mergedColumns = overlay.columns != null ? overlay.columns : base.columns;
		String mergedClassName = !P3VariableFileIndex.UNKNOWN_TYPE.equals(base.className) && !base.className.isEmpty()
				? base.className
				: overlay.className;
		return new VisibleVariable(
				base.varKey,
				base.cleanName,
				mergedClassName,
				base.ownerClass,
				base.file,
				base.offset,
				mergedColumns,
				overlay.sourceVarKey != null ? overlay.sourceVarKey : base.sourceVarKey,
				base.methodName,
				base.targetClassName,
				mergedHashKeys,
				mergedHashSourceVars,
				false,
				base.isLocal,
				base.receiverVarKey
		);
	}

	private @Nullable VisibleVariable findVariableInCurrentFileRaw(
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return findVariable(varKey, java.util.Collections.singletonList(currentFile), currentFile, currentOffset);
	}

	private static boolean isTrivialUnknownVariable(@NotNull VisibleVariable variable) {
		return P3VariableFileIndex.UNKNOWN_TYPE.equals(variable.className)
				&& (variable.sourceVarKey == null || variable.sourceVarKey.isEmpty())
				&& (variable.hashKeys == null || variable.hashKeys.isEmpty())
				&& (variable.columns == null || variable.columns.isEmpty());
	}

	private @NotNull VariableCompletionInfo analyzeResolvedVariable(
			@NotNull VisibleVariable variable,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return analyzeResolvedVariableInternal(
				variable,
				visibleFiles,
				currentFile,
				currentOffset,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		);
	}

	private @NotNull VariableCompletionInfo analyzeResolvedVariableInternal(
			@NotNull VisibleVariable variable,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			@NotNull java.util.Set<String> visitedValues,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		VisibleVariable resolved = variable;
		if (resolved.needsTypeResolve()) {
			resolved = resolveMethodCallVariableInternal(
					resolved,
					visibleFiles,
					currentFile,
					currentOffset,
					visitedValues,
					resolvingVarKeys
			);
		}
		if (P3VariableFileIndex.UNKNOWN_TYPE.equals(resolved.className)
				&& resolved.sourceVarKey != null
				&& !resolved.sourceVarKey.isEmpty()) {
			resolved = resolveSourceVariableInternal(
					resolved,
					visibleFiles,
					currentFile,
					currentOffset,
					5,
					visitedValues,
					resolvingVarKeys
			);
		}

		List<String> columns = resolved.columns;
		if ("table".equals(resolved.className)
				&& resolved.sourceVarKey != null
				&& !resolved.sourceVarKey.isEmpty()) {
			String sourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(resolved.sourceVarKey);
			int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(resolved.sourceVarKey, currentOffset);
			List<String> sourceColumns = resolveColumnsForChain(
					sourceVarKey,
					visibleFiles,
					currentFile,
					sourceLookupOffset);
			if (sourceColumns != null && !sourceColumns.isEmpty()) {
				columns = sourceColumns;
			}
		}
		if ("table".equals(resolved.className) && (columns == null || columns.isEmpty())) {
			columns = resolveColumnsForVariable(resolved, visibleFiles, currentFile, currentOffset, 5);
		}

		Map<String, HashEntryInfo> hashKeys = resolved.hashKeys;
		boolean hashWithKeys = false;
		if ("hash".equals(resolved.className) || "array".equals(resolved.className)) {
			hashKeys = resolveAllHashKeys(resolved, visibleFiles, currentFile, currentOffset);
			hashWithKeys = hashKeys != null && !hashKeys.isEmpty();
		}

		if (!hashWithKeys && hashKeys != null && !hashKeys.isEmpty()) {
			hashWithKeys = true;
		}

		if (!hashWithKeys
				&& P3VariableFileIndex.UNKNOWN_TYPE.equals(resolved.className)
				&& resolved.sourceVarKey != null
				&& P3VariableEffectiveShape.decodeHashSourceVarKey(resolved.sourceVarKey).contains(".")
				&& !P3VariableEffectiveShape.decodeHashSourceVarKey(resolved.sourceVarKey).startsWith("foreach")
				&& !P3VariableEffectiveShape.decodeHashSourceVarKey(resolved.sourceVarKey).startsWith("self.")
				&& !P3VariableEffectiveShape.decodeHashSourceVarKey(resolved.sourceVarKey).startsWith("MAIN:")) {
			String sourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(resolved.sourceVarKey);
			int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(resolved.sourceVarKey, currentOffset);
			ChainResolveInfo chainInfo =
					resolveEffectiveChainCached(
							sourceVarKey,
							visibleFiles,
							currentFile,
							sourceLookupOffset,
							visitedValues,
							resolvingVarKeys
					);
			hashKeys = chainInfo != null ? chainInfo.hashKeys : null;
			hashWithKeys = hashKeys != null && !hashKeys.isEmpty();
		}

		if (hashWithKeys && isWildcardOnlyHashKeys(hashKeys, resolved)) {
			hashWithKeys = false;
		}
		if ("table".equals(resolved.className)) {
			columns = P3VariableEffectiveShape.applyColumnHashMutations(columns, hashKeys);
		}

		VisibleVariable completionVariable = resolved;
		if (hashWithKeys && (completionVariable.className == null
				|| completionVariable.className.isEmpty()
				|| P3VariableFileIndex.UNKNOWN_TYPE.equals(completionVariable.className)
				|| P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(completionVariable.className))) {
			completionVariable = new VisibleVariable(
					completionVariable.varKey,
					completionVariable.cleanName,
					"hash",
					completionVariable.ownerClass,
					completionVariable.file,
					completionVariable.offset,
					completionVariable.columns,
					completionVariable.sourceVarKey,
					completionVariable.methodName,
					completionVariable.targetClassName,
					hashKeys != null ? hashKeys : completionVariable.hashKeys,
					completionVariable.hashSourceVars,
					completionVariable.isAdditive,
					completionVariable.receiverVarKey
			);
		}

		boolean tableWithColumns = columns != null && !columns.isEmpty();
		boolean dateWithFields = "date".equals(completionVariable.className);
		return new VariableCompletionInfo(completionVariable, columns, hashWithKeys ? hashKeys : null, tableWithColumns || hashWithKeys || dateWithFields);
	}

	private static boolean isWildcardOnlyHashKeys(
			@Nullable Map<String, HashEntryInfo> hashKeys,
			@NotNull VisibleVariable variable
	) {
		return hashKeys != null
				&& hashKeys.size() == 1
				&& hashKeys.containsKey("*")
				&& (variable.hashSourceVars == null || variable.hashSourceVars.isEmpty())
				&& (variable.sourceVarKey == null || variable.sourceVarKey.isEmpty());
	}

	public static @NotNull String decodeSourceVarKey(@NotNull String sourceVarKey) {
		return P3VariableEffectiveShape.decodeHashSourceVarKey(sourceVarKey);
	}

	public static int decodeSourceLookupOffset(@NotNull String sourceVarKey, int fallbackOffset) {
		return P3VariableEffectiveShape.decodeHashSourceLookupOffset(sourceVarKey, fallbackOffset);
	}

	public @NotNull P3ResolvedValue resolveValueRef(
			@NotNull VisibleVariable variable,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return withResolveRequestCache(() -> resolveValueRefInternal(
				toResolvedValue(variable),
				visibleFiles,
				currentFile,
				currentOffset,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		));
	}

	private @NotNull P3ResolvedValue resolveValueRefInternal(
			@NotNull P3ResolvedValue value,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			@NotNull java.util.Set<String> visited,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		String visitKey = value.className + "|" + value.sourceVarKey + "|" + value.methodName + "|"
				+ value.targetClassName + "|" + value.receiverVarKey;
		if (!visited.add(visitKey)) {
			return value;
		}

		if (P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(value.className)
				&& value.methodName != null) {
			String ownerClass = value.targetClassName;
			if ((ownerClass == null || ownerClass.isEmpty()) && value.receiverVarKey != null) {
				ownerClass = resolveReceiverClassName(
						value.receiverVarKey,
						visibleFiles,
						currentFile,
						currentOffset,
						visited,
						resolvingVarKeys
				);
			}

			if (ownerClass != null || value.receiverVarKey == null) {
				P3MethodDocTypeResolver resolver = P3MethodDocTypeResolver.getInstance(project);
				P3ResolvedValue resolvedMethod = resolver.getMethodResolvedResult(value.methodName, ownerClass, visibleFiles);
				if (resolvedMethod != null) {
					return resolveValueRefInternal(
							resolvedMethod,
							visibleFiles,
							currentFile,
							currentOffset,
							visited,
							resolvingVarKeys
					);
				}
			}

			return value;
		}

		if (P3VariableFileIndex.UNKNOWN_TYPE.equals(value.className)
				&& value.sourceVarKey != null
				&& !value.sourceVarKey.isEmpty()) {
			String sourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(value.sourceVarKey);
			int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(value.sourceVarKey, currentOffset);
			ChainResolveInfo chainInfo =
					resolveEffectiveChainCached(
							sourceVarKey,
							visibleFiles,
							currentFile,
							sourceLookupOffset,
							visited,
							resolvingVarKeys
					);
			if (chainInfo != null && chainInfo.className != null) {
				Map<String, HashEntryInfo> hashKeys = ("hash".equals(chainInfo.className) || "array".equals(chainInfo.className))
						? chainInfo.hashKeys
						: null;
				return P3ResolvedValue.of(chainInfo.className, chainInfo.columns, null, hashKeys, null, null, null, null);
			}
		}

		return value;
	}

	private @Nullable String resolveReceiverClassName(
			@NotNull String receiverVarKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			@NotNull java.util.Set<String> visited,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		ChainResolveInfo chainInfo =
				resolveEffectiveChainCached(
						receiverVarKey,
						visibleFiles,
						currentFile,
						currentOffset,
						visited,
						resolvingVarKeys
				);
		String className = chainInfo != null ? chainInfo.className : null;
		if (className == null || className.isEmpty()) {
			return null;
		}
		if (P3VariableFileIndex.UNKNOWN_TYPE.equals(className)) return null;
		if (P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(className)) return null;
		return className;
	}

	private static boolean isDotPathReference(@NotNull String varKey) {
		if (varKey.startsWith("foreach")) return false;
		String normalized = varKey;
		if (normalized.startsWith("self.")) {
			normalized = normalized.substring(5);
		} else if (normalized.startsWith("MAIN:")) {
			normalized = normalized.substring(5);
		}
		return normalized.contains(".");
	}

	private @NotNull java.util.List<VirtualFile> getVisibleFilesForCurrent(
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		return getScopeFiles(currentFile, cursorOffset).variableFiles;
	}

	private @NotNull java.util.List<VirtualFile> getVisibleFilesForClassMembers(
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		return getScopeFiles(currentFile, cursorOffset).classFiles;
	}

	private void putScopeFiles(
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull List<VirtualFile> variableFiles,
			@NotNull List<VirtualFile> classFiles
	) {
		RESOLVE_REQUEST_CACHE.get().scopeFiles.put(
				new ScopeFilesKey(currentFile, cursorOffset),
				new CachedScopeFiles(variableFiles, classFiles));
	}

	private @NotNull CachedScopeFiles getScopeFiles(
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		ResolveRequestCache cache = RESOLVE_REQUEST_CACHE.get();
		ScopeFilesKey key = new ScopeFilesKey(currentFile, cursorOffset);
		CachedScopeFiles cached = cache.scopeFiles.get(key);
		if (cached != null) {
			return cached;
		}

		P3ScopeContext scopeContext = new P3ScopeContext(project, currentFile, cursorOffset);
		CachedScopeFiles computed = new CachedScopeFiles(
				scopeContext.getVariableSearchFiles(),
				scopeContext.getClassSearchFiles());
		cache.scopeFiles.put(key, computed);
		return computed;
	}

	private static @NotNull P3ResolvedValue toResolvedValue(@NotNull VisibleVariable variable) {
		return P3ResolvedValue.of(
				variable.className,
				variable.columns,
				variable.sourceVarKey,
				variable.hashKeys,
				variable.hashSourceVars,
				variable.methodName,
				variable.targetClassName,
				variable.receiverVarKey
		);
	}

	private @NotNull VisibleVariable applyResolvedValue(
			@NotNull VisibleVariable variable,
			@NotNull P3ResolvedValue resolved
	) {
		String resolvedClass = resolved.className;
		if (P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(resolvedClass)) {
			resolvedClass = P3VariableFileIndex.UNKNOWN_TYPE;
		}
		boolean canMergeOwnHashShape = canCarryHashShape(resolvedClass);
		Map<String, HashEntryInfo> mergedHashKeys = canMergeOwnHashShape
				? mergeHashEntryMaps(resolved.hashKeys, variable.hashKeys)
				: resolved.hashKeys;
		List<String> mergedHashSourceVars = canMergeOwnHashShape
				? mergeHashSourceVars(resolved.hashSourceVars, variable.hashSourceVars)
				: resolved.hashSourceVars;

		return new VisibleVariable(
				variable.varKey,
				variable.cleanName,
				resolvedClass,
				variable.ownerClass,
				variable.file,
				variable.offset,
				resolved.columns,
				resolved.sourceVarKey,
				resolved.methodName,
				resolved.targetClassName,
				mergedHashKeys,
				mergedHashSourceVars,
				variable.isAdditive,
				resolved.receiverVarKey
		);
	}

	private static boolean canCarryHashShape(@NotNull String className) {
		return P3VariableFileIndex.UNKNOWN_TYPE.equals(className)
				|| "hash".equals(className)
				|| "array".equals(className)
				|| "table".equals(className);
	}

	private static @Nullable List<String> mergeHashSourceVars(
			@Nullable List<String> oldVars,
			@Nullable List<String> newVars
	) {
		if (oldVars == null || oldVars.isEmpty()) return newVars;
		if (newVars == null || newVars.isEmpty()) return oldVars;

		List<String> result = new ArrayList<>(oldVars);
		for (String var : newVars) {
			if (!result.contains(var)) result.add(var);
		}
		return result;
	}

	private @NotNull VisibleVariable resolveMethodCallVariable(
			@NotNull VisibleVariable variable,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return resolveMethodCallVariableInternal(
				variable,
				visibleFiles,
				currentFile,
				currentOffset,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		);
	}

	private @NotNull VisibleVariable resolveMethodCallVariableInternal(
			@NotNull VisibleVariable variable,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			@NotNull java.util.Set<String> visitedValues,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		if (!variable.needsTypeResolve()) {
			return variable;
		}

		return applyResolvedValue(
				variable,
				resolveValueRefInternal(
						toResolvedValue(variable),
						visibleFiles,
						currentFile,
						currentOffset,
						visitedValues,
						resolvingVarKeys
				)
		);
	}

	/**
	 * Прямой поиск переменной через индекс по ключу.
	 * Вместо загрузки ВСЕХ переменных из ВСЕХ файлов — запрашиваем только нужные ключи.
	 * FileBasedIndex использует BTree — поиск по ключу O(log N), не O(N).
	 *
	 * Ключи для поиска: "list", "self.list", "MAIN:list" (эквивалентные формы).
	 *
	 * Возвращает null если:
	 * - Переменная не найдена в других файлах
	 * - Тип = METHOD_CALL_MARKER (нужен полный поиск для резолва)
	 * - Тип = UNKNOWN_TYPE
	 */
	private @Nullable VisibleVariable findVariableByIndexQuery(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		long t0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		String prefix = extractVarPrefix(varKey);
		String pureName = extractPureVarName(varKey);

		CursorContext ctx = buildCursorContext(currentFile, currentOffset);

		// Эквивалентные ключи для поиска
		String[] searchKeys = { pureName, "self." + pureName, "MAIN:" + pureName };

		// Set для быстрой проверки видимости файла
		java.util.Set<VirtualFile> visibleSet = new java.util.HashSet<>(visibleFiles);

		FileBasedIndex index = FileBasedIndex.getInstance();
		com.intellij.psi.search.GlobalSearchScope scope =
				P3IndexMaintenance.getParser3IndexScope(project);

		// Собираем результаты из всех эквивалентных ключей
		Map<String, VisibleVariable> dedup = new LinkedHashMap<>();
		Map<String, Integer> dedupEffectiveOffset = new HashMap<>();

		P3ScopeContext.VariableSearchData variableSearchData = P3ScopeContext.buildVariableSearchData(
				project, currentFile, currentOffset, visibleFiles);
		visibleSet.addAll(variableSearchData.files);
		P3VariableScopeContext scopeContext = new P3VariableScopeContext(
				project, currentFile, currentOffset, variableSearchData.files, variableSearchData.useOffsetMap);

		try {
			for (String key : searchKeys) {
				index.processValues(P3VariableFileIndex.NAME, key, null,
						(file, infos) -> {
							// Пропускаем текущий файл (уже проверен в fast path)
							if (file.equals(currentFile)) return true;
							// Пропускаем файлы вне области видимости
							if (!visibleSet.contains(file)) return true;
							if (!scopeContext.isSourceVisibleAtCursor(file)) return true;

							boolean inheritedMainLocals = scopeContext.hasInheritedMainLocalsForSourceFile(file);
							String fileText = isTemporaryVariableName(pureName) ? readFileTextSmart(file) : null;
							P3VariableEffectiveShape shape = new P3VariableEffectiveShape(extractPureVarName(key), true);
							for (P3VariableFileIndex.VariableTypeInfo info : infos) {
								if (isInfoLocal(info, inheritedMainLocals)) continue;
								if (isTemporaryVariableName(pureName)
										&& fileText != null
										&& findTemporaryVariableScope(fileText, pureName, info.offset) != null) {
									continue;
								}
								if (info.ownerClass == null) {
									// MAIN переменная — видна
								} else if (!ctx.classHierarchy.isEmpty()
										&& ctx.classHierarchy.contains(info.ownerClass)) {
									// Переменная из @BASE класса — видна
								} else {
									continue;
								}
								shape.add(info);
							}

							VisibleVariable candidate = createVisibleVariableFromShape(key, file, shape, false);
							if (candidate != null) {
								String dedupKey = candidate.cleanName + "|" + candidate.ownerClass;
								int useOffset = scopeContext.getUseOffset(file);
								int existingEffective = dedupEffectiveOffset.getOrDefault(dedupKey, Integer.MIN_VALUE);
								VisibleVariable existing = dedup.get(dedupKey);

								if (existing == null ||
										useOffset > existingEffective ||
										(useOffset == existingEffective && (!file.equals(existing.file) || candidate.offset > existing.offset))) {
									dedup.put(dedupKey, candidate);
									dedupEffectiveOffset.put(dedupKey, useOffset);
								}
							}
							return true;
						}, scope);
			}
		} catch (com.intellij.ide.startup.ServiceNotReadyException ignored) {
			return null;
		}

		if (dedup.isEmpty()) {
			if (DEBUG_PERF) System.out.println("[P3VarIndex.PERF] findVariableByIndexQuery: "
					+ (System.currentTimeMillis() - t0) + "ms, varKey=" + varKey + " → NOT FOUND");
			return null;
		}

		// Фильтрация по контексту
		String contextType;
		if ("MAIN:".equals(prefix)) contextType = "MAIN";
		else if ("self.".equals(prefix)) contextType = "self";
		else if ("BASE:".equals(prefix)) contextType = "BASE";
		else contextType = "normal";

		java.util.Set<String> hierarchyForFilter = ctx.classHierarchy;
		if ("BASE".equals(contextType) && ctx.ownerClass != null) {
			hierarchyForFilter = buildBaseOnlyHierarchy(ctx.ownerClass);
		}

		Map<String, VisibleVariable> filtered = filterByContext(
				new ArrayList<>(dedup.values()), contextType, ctx.ownerClass, hierarchyForFilter);

		VisibleVariable result = filtered.get(pureName);

		if (DEBUG_PERF) System.out.println("[P3VarIndex.PERF] findVariableByIndexQuery: "
				+ (System.currentTimeMillis() - t0) + "ms, varKey=" + varKey
				+ " → " + (result != null ? result.className : "null")
				+ " dedup=" + dedup.size() + " filtered=" + filtered.size());

		if (result == null) return null;

		// METHOD_CALL_MARKER и UNKNOWN_TYPE — нужен полный поиск
		if (P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(result.className)) return null;
		if (P3VariableFileIndex.UNKNOWN_TYPE.equals(result.className)) return null;

		return result;
	}

	private static @Nullable VisibleVariable createVisibleVariableFromShape(
			@NotNull String varKey,
			@NotNull VirtualFile file,
			@NotNull P3VariableEffectiveShape shape,
			boolean isLocal
	) {
		if (!shape.hasVisible()) return null;
		P3VariableFileIndex.VariableTypeInfo structuralVisible = shape.structuralVisible();
		if (structuralVisible == null) return null;

		Map<String, HashEntryInfo> fileHashKeys = attachHashEntryFiles(shape.hashKeys(), file);
		List<String> effectiveColumns = P3VariableEffectiveShape.applyColumnHashMutations(
				structuralVisible.columns, fileHashKeys);
		String cleanName = extractPureVarName(varKey);
		return new VisibleVariable(
				varKey,
				cleanName,
				shape.className(),
				structuralVisible.ownerClass,
				file,
				structuralVisible.offset,
				effectiveColumns,
				shape.effectiveSourceVarKey(),
				structuralVisible.methodName,
				structuralVisible.targetClassName,
				fileHashKeys,
				shape.hashSourceVars(),
				structuralVisible.isAdditive,
				isLocal,
				structuralVisible.receiverVarKey
		);
	}

	/**
	 * Резолвит тип по цепочке точек: "user.u_var2" → "hash".
	 */
	public @Nullable String resolveChainedType(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		ChainResolveInfo info = resolveEffectiveChain(varKey, visibleFiles, currentFile, currentOffset);
		return info != null ? info.className : null;
	}

	public @Nullable ChainResolveInfo resolveEffectiveChain(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return withResolveRequestCache(() -> resolveEffectiveChainCached(
				varKey,
				visibleFiles,
				currentFile,
				currentOffset,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		));
	}

	public @Nullable ChainResolveInfo resolveEffectiveChain(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull List<VirtualFile> classFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return withResolveRequestCache(() -> {
			putScopeFiles(currentFile, currentOffset, visibleFiles, classFiles);
			return resolveEffectiveChainCached(
					varKey,
					visibleFiles,
					currentFile,
					currentOffset,
					new java.util.HashSet<>(),
					new java.util.HashSet<>()
			);
		});
	}

	private @Nullable ChainResolveInfo resolveEffectiveChainCached(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			@NotNull java.util.Set<String> visitedValues,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		ResolveRequestCache cache = RESOLVE_REQUEST_CACHE.get();
		CachedScopeFiles scope = cache.scopeFiles.get(new ScopeFilesKey(currentFile, currentOffset));
		List<VirtualFile> classFiles = scope != null ? scope.classFiles : java.util.Collections.emptyList();
		ChainResolveKey key = new ChainResolveKey(
				varKey,
				visibleFiles,
				classFiles,
				currentFile,
				currentOffset,
				visitedValues,
				resolvingVarKeys
		);
		if (cache.chainResolve.containsKey(key)) {
			return cache.chainResolve.get(key);
		}
		if (cache.chainResolveMisses.contains(key)) {
			return null;
		}

		ChainResolveInfo result = resolveEffectiveChainInternal(
				varKey,
				visibleFiles,
				currentFile,
				currentOffset,
				visitedValues,
				resolvingVarKeys
		);
		if (result == null) {
			cache.chainResolveMisses.add(key);
		} else {
			cache.chainResolve.put(key, result);
		}
		return result;
	}

	private @Nullable ChainResolveInfo resolveEffectiveChainInternal(
			@NotNull String varKey,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			@NotNull java.util.Set<String> visitedValues,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		String chainResolveKey = "chain|" + currentFile.getPath() + "|" + currentOffset + "|" + varKey;
		if (!resolvingVarKeys.add(chainResolveKey)) {
			if (DEBUG_ALWAYS) {
				System.out.println("[P3VarIndex.resolveEffectiveChain] цикл резолва остановлен: " + chainResolveKey);
			}
			return null;
		}
		try {
		String prefix = "";
		String chain = varKey;
		if (chain.startsWith("self.")) {
			prefix = "self.";
			chain = chain.substring(5);
		} else if (chain.startsWith("MAIN:")) {
			prefix = "MAIN:";
			chain = chain.substring(5);
		} else if (chain.startsWith("BASE:")) {
			prefix = "BASE:";
			chain = chain.substring(5);
		}
		String[] segments = Parser3ChainUtils.splitNormalizedSegments(chain);
		if (segments.length == 0) return null;

		String firstKey = prefix + segments[0];
		VariableCompletionInfo rootInfo =
				resolveEffectiveVariableInternal(firstKey, visibleFiles, currentFile, currentOffset, visitedValues, resolvingVarKeys);
		VisibleVariable rootVar = rootInfo != null ? rootInfo.variable : null;
		String currentType = rootVar != null ? rootVar.className : null;
		HashEntryInfo currentEntry = null;
		List<String> currentColumns = rootInfo != null ? rootInfo.columns : null;
		Map<String, HashEntryInfo> currentHashKeys = rootInfo != null ? rootInfo.hashKeys : null;

		if (currentType == null && segments.length <= 1) return null;

		Map<String, HashEntryInfo> foreachAdditiveKeys =
				segments.length > 1 ? findForeachAdditiveKeys(segments[0], currentFile, currentOffset) : null;
		Map<String, HashEntryInfo> currentForeachAdditiveKeys = foreachAdditiveKeys;
		if (currentType == null) {
			if (foreachAdditiveKeys != null && !foreachAdditiveKeys.isEmpty()) {
				currentType = "hash";
			} else {
				return null;
			}
		}

		if (P3VariableFileIndex.UNKNOWN_TYPE.equals(currentType)) {
			if (rootVar != null && rootVar.sourceVarKey != null) {
				VisibleVariable resolved = resolveSourceVariableInternal(
						rootVar,
						visibleFiles,
						currentFile,
						currentOffset,
						5,
						visitedValues,
						resolvingVarKeys
				);
				if (resolved != rootVar) {
					currentType = resolved.className;
					if ("table".equals(currentType) && (currentColumns == null || currentColumns.isEmpty())) {
						currentColumns = resolved.columns;
					}
					if (("hash".equals(currentType) || "array".equals(currentType))
							&& (currentHashKeys == null || currentHashKeys.isEmpty())) {
						currentHashKeys = resolveAllHashKeys(resolved, visibleFiles, currentFile, currentOffset);
					}
				}
			}
		}

		if (segments.length == 1) {
			currentHashKeys = resolveHashEntryValueRefs(currentHashKeys, visibleFiles, currentFile, currentOffset);
			return new ChainResolveInfo(currentType, rootVar, null, currentHashKeys, currentColumns);
		}

		if (("hash".equals(currentType) || "array".equals(currentType))
				&& (currentHashKeys == null || currentHashKeys.isEmpty())) {
			if (rootVar != null) {
				VisibleVariable effectiveVar = resolveSourceVariableInternal(
						rootVar,
						visibleFiles,
						currentFile,
						currentOffset,
						5,
						visitedValues,
						resolvingVarKeys
				);
				currentHashKeys = resolveAllHashKeys(effectiveVar, visibleFiles, currentFile, currentOffset);
			}
		}

		if (foreachAdditiveKeys != null
				&& !"hash".equals(currentType) && !"array".equals(currentType)) {
			currentType = "hash";
		}

		for (int i = 1; i < segments.length; i++) {
			String propName = segments[i];
			if (propName.isEmpty()) return null;

			boolean hashLikeByKeys =
					(P3VariableFileIndex.UNKNOWN_TYPE.equals(currentType) || currentType == null)
							&& ((currentHashKeys != null && !currentHashKeys.isEmpty())
							|| (currentForeachAdditiveKeys != null && !currentForeachAdditiveKeys.isEmpty()));

			if ((("hash".equals(currentType) || "array".equals(currentType)) || hashLikeByKeys)
					&& (currentHashKeys != null || currentForeachAdditiveKeys != null)) {
				HashEntryInfo entry = currentHashKeys != null ? resolveHashEntry(currentHashKeys, propName) : null;
				if (entry == null && currentForeachAdditiveKeys != null) {
					entry = currentForeachAdditiveKeys.get(propName);
				}
				if (entry == null && currentForeachAdditiveKeys != null && !currentForeachAdditiveKeys.isEmpty()) {
					entry = new HashEntryInfo("hash", null, currentForeachAdditiveKeys);
				}
				if (entry != null) {
					if (P3VariableParser.isRenamedHashEntry(entry)) {
						String oldKey = P3VariableParser.getRenamedHashSource(entry);
						if (oldKey != null && !oldKey.isEmpty()) {
							String oldChain = buildFieldPath(segments, 0, i) + "." + oldKey;
							ChainResolveInfo oldInfo = resolveEffectiveChainCached(
									oldChain,
									visibleFiles,
									currentFile,
									currentOffset,
									visitedValues,
									resolvingVarKeys
							);
							if (oldInfo != null) {
								entry = new HashEntryInfo(
										oldInfo.className != null ? oldInfo.className : P3VariableFileIndex.UNKNOWN_TYPE,
										oldInfo.columns,
										oldInfo.hashKeys);
							}
						}
					}
					entry = resolveMethodResultHashEntry(entry, visibleFiles, currentFile, currentOffset);
					String canonicalChain = buildFieldPath(segments, 0, i + 1);
					String fieldPath = buildFieldPath(segments, 1, i + 1);
					Map<String, HashEntryInfo> nextLevelForeachKeys =
							findForeachFieldAdditiveKeys(segments[0], fieldPath, currentFile, currentOffset);
					Map<String, HashEntryInfo> aliasKeys = mergeAliasHashKeys(canonicalChain, currentFile, currentOffset);
					Map<String, HashEntryInfo> levelForeachKeys = currentForeachAdditiveKeys;
					currentEntry = entry;
					currentType = entry.className;
					currentColumns = entry.columns;
					if (i == 1 && foreachAdditiveKeys != null && entry.nestedKeys != null) {
						currentHashKeys = new java.util.LinkedHashMap<>(entry.nestedKeys);
						currentHashKeys = mergeHashEntryMaps(currentHashKeys, foreachAdditiveKeys);
					} else if (i == 1 && foreachAdditiveKeys != null) {
						currentHashKeys = foreachAdditiveKeys;
					} else {
						currentHashKeys = entry.nestedKeys != null
								? new java.util.LinkedHashMap<>(entry.nestedKeys)
								: new java.util.LinkedHashMap<>();
					}
					if (aliasKeys != null && !aliasKeys.isEmpty()) {
						if (currentHashKeys == null) currentHashKeys = new java.util.LinkedHashMap<>();
						currentHashKeys = mergeHashEntryMaps(currentHashKeys, aliasKeys);
						if ("hash".equals(currentType) || P3VariableFileIndex.UNKNOWN_TYPE.equals(currentType)) {
							currentType = "hash";
						}
					}
					if (i == 1 && foreachAdditiveKeys != null
							&& P3VariableFileIndex.UNKNOWN_TYPE.equals(currentType)) {
						currentType = "hash";
					}
					if ((currentHashKeys != null && !currentHashKeys.isEmpty())
							&& (currentType == null || P3VariableFileIndex.UNKNOWN_TYPE.equals(currentType))) {
						currentType = "hash";
					}
					if (i == segments.length - 1 && levelForeachKeys != null && !levelForeachKeys.isEmpty()) {
						if (currentHashKeys == null) currentHashKeys = new java.util.LinkedHashMap<>();
						else currentHashKeys = new java.util.LinkedHashMap<>(currentHashKeys);
						currentHashKeys = mergeHashEntryMaps(currentHashKeys, levelForeachKeys);
					}
					currentForeachAdditiveKeys = nextLevelForeachKeys;
					continue;
				}
				return null;
			}

			if ("table".equals(currentType)) {
				List<String> cols = currentColumns;
				if ((cols == null || cols.isEmpty()) && rootVar != null) {
					cols = resolveColumnsForChain(firstKey, visibleFiles, currentFile, currentOffset);
				}
				if ((cols != null && cols.contains(propName))
						|| ((cols == null || cols.isEmpty()) && isNumericTableColumn(propName))) {
					currentEntry = null;
					currentType = "string";
					currentColumns = null;
					currentHashKeys = null;
					continue;
				}
			}

			if (Parser3BuiltinMethods.isBuiltinClass(currentType)) {
				Parser3BuiltinMethods.BuiltinCallable property = findBuiltinInstanceProperty(currentType, propName);
				if (property != null) {
					currentEntry = null;
					currentType = property.returnType != null ? property.returnType : P3VariableFileIndex.UNKNOWN_TYPE;
					currentColumns = null;
					currentHashKeys = null;
					continue;
				}
				return null;
			}

			List<VirtualFile> classMemberFiles = getVisibleFilesForClassMembers(currentFile, currentOffset);
			VariableCompletionInfo classPropertyInfo = getClassVariableInfo(currentType, propName, classMemberFiles);
			if (classPropertyInfo == null) {
				classPropertyInfo = getClassGetterPropertyInfo(currentType, propName, classMemberFiles);
			}
			if (classPropertyInfo == null) return null;
			VisibleVariable classProperty = classPropertyInfo.variable;
			HashEntryInfo additiveEntry = currentHashKeys != null ? resolveHashEntry(currentHashKeys, propName) : null;
			Map<String, HashEntryInfo> propertyHashKeys = classPropertyInfo.hashKeys;
			List<String> propertyColumns = classPropertyInfo.columns;
			String propertyClassName = classProperty.className;
			if (additiveEntry != null) {
				propertyHashKeys = mergeHashEntryMaps(propertyHashKeys, additiveEntry.nestedKeys);
				if (propertyColumns == null) {
					propertyColumns = additiveEntry.columns;
				}
				if ((propertyClassName == null
						|| propertyClassName.isEmpty()
						|| P3VariableFileIndex.UNKNOWN_TYPE.equals(propertyClassName))
						&& additiveEntry.className != null
						&& !additiveEntry.className.isEmpty()
						&& !P3VariableFileIndex.UNKNOWN_TYPE.equals(additiveEntry.className)) {
					propertyClassName = additiveEntry.className;
				}
			}

			currentEntry = new HashEntryInfo(
					propertyClassName,
					propertyColumns,
					propertyHashKeys,
					classProperty.offset,
					classProperty.sourceVarKey,
					classProperty.methodName,
					classProperty.targetClassName,
					classProperty.receiverVarKey
			).withFile(classProperty.file);
			currentType = propertyClassName;
			currentColumns = propertyColumns;
			currentHashKeys = propertyHashKeys;
		}

		Map<String, HashEntryInfo> resultHashKeys = currentHashKeys;
		if (currentForeachAdditiveKeys != null && !currentForeachAdditiveKeys.isEmpty()) {
			if (resultHashKeys == null) resultHashKeys = new java.util.LinkedHashMap<>();
			else resultHashKeys = new java.util.LinkedHashMap<>(resultHashKeys);
			resultHashKeys = mergeHashEntryMaps(resultHashKeys, currentForeachAdditiveKeys);
		}
		resultHashKeys = resolveHashEntryValueRefs(resultHashKeys, visibleFiles, currentFile, currentOffset);
		return new ChainResolveInfo(currentType, rootVar, currentEntry, resultHashKeys, currentColumns);
		} finally {
			resolvingVarKeys.remove(chainResolveKey);
		}
	}

	private static boolean isNumericTableColumn(@NotNull String propName) {
		if (propName.isEmpty()) return false;
		for (int i = 0; i < propName.length(); i++) {
			if (!Character.isDigit(propName.charAt(i))) return false;
		}
		return true;
	}

	private @Nullable Map<String, HashEntryInfo> resolveHashEntryValueRefs(
			@Nullable Map<String, HashEntryInfo> hashKeys,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		if (hashKeys == null || hashKeys.isEmpty()) return hashKeys;
		Map<String, HashEntryInfo> result = null;
		for (Map.Entry<String, HashEntryInfo> entry : hashKeys.entrySet()) {
			HashEntryInfo oldInfo = entry.getValue();
			HashEntryInfo newInfo = resolveMethodResultHashEntry(oldInfo, visibleFiles, currentFile, currentOffset);
			if (newInfo != oldInfo && !newInfo.equals(oldInfo)) {
				if (result == null) result = new LinkedHashMap<>(hashKeys);
				result.put(entry.getKey(), newInfo);
			}
		}
		return result != null ? result : hashKeys;
	}

	private @NotNull HashEntryInfo resolveMethodResultHashEntry(
			@NotNull HashEntryInfo entry,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		boolean hasMethodRef = P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(entry.className)
				&& entry.methodName != null
				&& !entry.methodName.isEmpty();
		boolean hasSourceRef = P3VariableFileIndex.UNKNOWN_TYPE.equals(entry.className)
				&& entry.sourceVarKey != null
				&& !entry.sourceVarKey.isEmpty();
		if (!hasMethodRef && !hasSourceRef) {
			return entry;
		}

		VirtualFile lookupFile = entry.file != null ? entry.file : currentFile;
		int lookupOffset = entry.offset > 0 ? entry.offset - 1 : currentOffset;
		P3ResolvedValue resolved = resolveValueRefInternal(
				P3ResolvedValue.of(
						entry.className,
						entry.columns,
						entry.sourceVarKey,
						entry.nestedKeys,
						null,
						entry.methodName,
						entry.targetClassName,
						entry.receiverVarKey
				),
				preferEntryFile(visibleFiles, entry.file),
				lookupFile,
				lookupOffset,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		);
		if (resolved == null || resolved.isUnknown()) {
			return entry;
		}

		return new HashEntryInfo(
				resolved.className,
				resolved.columns != null ? resolved.columns : entry.columns,
				mergeHashEntryMaps(resolved.hashKeys, entry.nestedKeys),
				entry.offset,
				entry.file,
				resolved.sourceVarKey != null ? resolved.sourceVarKey : entry.sourceVarKey,
				resolved.methodName != null ? resolved.methodName : entry.methodName,
				resolved.targetClassName != null ? resolved.targetClassName : entry.targetClassName,
				resolved.receiverVarKey != null ? resolved.receiverVarKey : entry.receiverVarKey
		);
	}

	private static @NotNull List<VirtualFile> preferEntryFile(
			@NotNull List<VirtualFile> visibleFiles,
			@Nullable VirtualFile entryFile
	) {
		if (entryFile == null || !visibleFiles.contains(entryFile)) return visibleFiles;
		java.util.ArrayList<VirtualFile> result = new java.util.ArrayList<>(visibleFiles.size());
		result.add(entryFile);
		for (VirtualFile file : visibleFiles) {
			if (!file.equals(entryFile)) result.add(file);
		}
		return result;
	}

	/**
	 * Ленивый резолв типа переменной через sourceVarKey.
	 * $copy[$original] → тип copy = тип original.
	 * Рекурсивно следует по цепочке sourceVarKey (глубина до 5).
	 *
	 * @return переменная-источник с известным типом, или исходная если не удалось резолвить
	 */
	public @NotNull VisibleVariable resolveSourceVariable(
			@NotNull VisibleVariable var,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return withResolveRequestCache(() ->
				resolveSourceVariable(var, getVisibleFilesForCurrent(currentFile, currentOffset), currentFile, currentOffset));
	}

	private @NotNull VisibleVariable resolveSourceVariable(
			@NotNull VisibleVariable var,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return resolveSourceVariableInternal(
				var,
				visibleFiles,
				currentFile,
				currentOffset,
				5,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		);
	}

	private @NotNull VisibleVariable resolveSourceVariableInternal(
			@NotNull VisibleVariable var,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth,
			@NotNull java.util.Set<String> visitedValues,
			@NotNull java.util.Set<String> resolvingVarKeys
	) {
		if (maxDepth <= 0) return var;
		// Резолвим только UNKNOWN переменные с sourceVarKey
		// Для типизированных (array, hash) sourceVarKey используется через resolveAllHashKeys
		if (!P3VariableFileIndex.UNKNOWN_TYPE.equals(var.className)) return var;
		if (var.sourceVarKey == null || var.sourceVarKey.isEmpty()) return var;
		String sourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(var.sourceVarKey);
		boolean snapshotSourceLookup = P3VariableEffectiveShape.isHashSourceAtOffset(var.sourceVarKey)
				|| isDotPathReference(sourceVarKey)
				|| var.varKey.equals(sourceVarKey);
		int decodedSourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(var.sourceVarKey, currentOffset);
		int sourceLookupOffset = snapshotSourceLookup
				? Math.min(decodedSourceLookupOffset, Math.max(0, var.offset - 1))
				: decodedSourceLookupOffset;

		if (sourceVarKey.startsWith("foreach:") || sourceVarKey.startsWith("foreach_field:")) {
			java.util.Map<String, HashEntryInfo> resolvedKeys = resolveAllHashKeys(var, visibleFiles, currentFile, currentOffset);
			if (resolvedKeys != null && !resolvedKeys.isEmpty()) {
				return new VisibleVariable(var.varKey, var.cleanName, "hash", var.ownerClass,
						var.file, var.offset, var.columns, var.sourceVarKey, null, null, resolvedKeys);
			}
		}

		if (sourceVarKey.startsWith("hashop\n")) {
			String[] parts = sourceVarKey.split("\\n", -1);
			String sourceVarName = parts.length >= 3 ? parts[2] : "";
			VisibleVariable effectiveSource = !sourceVarName.isEmpty()
					? resolveLocalVariableForHashExpansion(sourceVarName, visibleFiles, currentFile, sourceLookupOffset, maxDepth - 1)
					: null;
			java.util.Map<String, HashEntryInfo> resolvedKeys = resolveAllHashKeys(var, visibleFiles, currentFile, currentOffset);
			String resolvedClass = effectiveSource != null && "table".equals(effectiveSource.className) ? "table" : "hash";
			java.util.List<String> resolvedColumns = effectiveSource != null && "table".equals(effectiveSource.className)
					? effectiveSource.columns : null;
			return new VisibleVariable(var.varKey, var.cleanName, resolvedClass, var.ownerClass,
					var.file, var.offset, resolvedColumns, null, null, null, resolvedKeys);
		}

		// Dotpath: $var[$data.key1] → резолвим тип через цепочку
		if (isDotPathReference(sourceVarKey)) {
			ChainResolveInfo chainInfo =
					resolveEffectiveChainCached(
							sourceVarKey,
							visibleFiles,
							currentFile,
							sourceLookupOffset,
							visitedValues,
							resolvingVarKeys
					);
			if (chainInfo != null && chainInfo.className != null
					&& !P3VariableFileIndex.UNKNOWN_TYPE.equals(chainInfo.className)) {
				if (DEBUG_ALWAYS) System.out.println("[resolveSourceVariable] dotPath " + var.varKey + " → sourceVarKey=" + sourceVarKey + " → type=" + chainInfo.className);
				// Возвращаем псевдо-переменную с нужным типом и структурой найденного значения.
				return new VisibleVariable(var.varKey, var.cleanName, chainInfo.className, var.ownerClass,
						var.file, var.offset, chainInfo.columns != null ? chainInfo.columns : var.columns,
						null, null, null, chainInfo.hashKeys);
			}
		}

		VisibleVariable resolvedSource =
				resolveLocalVariableForHashExpansion(sourceVarKey, visibleFiles, currentFile, sourceLookupOffset, maxDepth - 1);
		if (resolvedSource == null) return var;

		if (DEBUG_ALWAYS) System.out.println("[resolveSourceVariable] " + var.varKey + " → sourceVarKey=" + sourceVarKey
				+ " → className=" + resolvedSource.className);

		return applyResolvedValue(var, toResolvedValue(resolvedSource));
	}

	/**
	 * Собирает все ключи хеша для переменной, включая ключи из переменных-источников.
	 * Например: $data2[$data $.key2[val]] → мержит ключи $data + собственный key2.
	 * Рекурсивно разрешает вложенные источники (защита глубины = 5).
	 *
	 * @param rootVar переменная с hashKeys и/или hashSourceVars
	 * @param currentFile текущий файл
	 * @param currentOffset offset курсора
	 * @return объединённая карта ключей, или null
	 */
	/** Защита от циклических ссылок при резолве hash-ключей */
	private static final ThreadLocal<java.util.Set<String>> RESOLVE_VISITED =
			ThreadLocal.withInitial(java.util.HashSet::new);

	public @Nullable java.util.Map<String, HashEntryInfo> resolveAllHashKeys(
			@NotNull VisibleVariable rootVar,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return withResolveRequestCache(() ->
				resolveAllHashKeys(rootVar, getVisibleFilesForCurrent(currentFile, currentOffset), currentFile, currentOffset));
	}

	private @Nullable java.util.Map<String, HashEntryInfo> resolveAllHashKeys(
			@NotNull VisibleVariable rootVar,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		java.util.Set<String> visited = RESOLVE_VISITED.get();
		// Ключ: varKey + файл + offset — уникальная переменная
		String visitKey = rootVar.varKey + "|" + rootVar.file.getPath() + "|" + rootVar.offset;
		if (!visited.add(visitKey)) {
			// Уже обрабатываем эту переменную выше по стеку — цикл, останавливаемся
			return rootVar.hashKeys;
		}
		try {
			return resolveAllHashKeysInternal(rootVar, visibleFiles, currentFile, currentOffset, 10);
		} finally {
			visited.remove(visitKey);
		}
	}

	private static boolean getInheritedMainLocalsForSourceFile(
			@NotNull P3EffectiveScopeService effectiveScopeService,
			@NotNull Map<VirtualFile, Boolean> inheritedMainLocalsMap,
			@NotNull VirtualFile sourceFile,
			int cursorOffset
	) {
		Boolean inheritedFromCurrentVisibility = inheritedMainLocalsMap.get(sourceFile);
		if (inheritedFromCurrentVisibility != null) {
			return inheritedFromCurrentVisibility;
		}
		return effectiveScopeService.hasInheritedMainLocals(sourceFile, cursorOffset);
	}

	private @Nullable java.util.Map<String, HashEntryInfo> resolveAllHashKeysInternal(
			@NotNull VisibleVariable rootVar,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth
	) {
		if (maxDepth <= 0) return rootVar.hashKeys;

		boolean hasOwn = rootVar.hashKeys != null && !rootVar.hashKeys.isEmpty();
		boolean hasSources = rootVar.hashSourceVars != null && !rootVar.hashSourceVars.isEmpty();
		boolean hasSourceVarKey = rootVar.sourceVarKey != null && !rootVar.sourceVarKey.isEmpty();
		String rootSourceVarKey = hasSourceVarKey
				? P3VariableEffectiveShape.decodeHashSourceVarKey(rootVar.sourceVarKey) : null;
		int rootSourceLookupOffset = hasSourceVarKey
				? P3VariableEffectiveShape.decodeHashSourceLookupOffset(rootVar.sourceVarKey, currentOffset) : currentOffset;

		if (hasSourceVarKey && rootSourceVarKey.startsWith("hashop\n")) {
			java.util.Map<String, HashEntryInfo> opKeys =
					resolveHashOperationKeys(rootSourceVarKey, visibleFiles, currentFile, rootSourceLookupOffset, maxDepth - 1);
			if (hasOwn && opKeys != null) {
				opKeys = mergeHashEntryMaps(opKeys, rootVar.hashKeys);
			}
			return opKeys;
		}

		// === foreach_field:varName:fieldPath — элемент поля переменной ===
		// ^hash.meets.foreach[;v] → v.sourceVarKey="foreach_field:hash:meets"
		// Возвращаем вложенные ключи ЭЛЕМЕНТОВ поля "meets" у "hash"
		// (аналогично foreach: — берём nestedKeys каждого элемента, не сами ключи поля)
		java.util.Map<String, HashEntryInfo> foreachKeys = null;
		if (hasSourceVarKey && rootSourceVarKey.startsWith("foreach_field:")) {
			String rest = rootSourceVarKey.substring(14); // после "foreach_field:"
			// Правильно определяем позицию разделителя ':' между varName и fieldPath.
			// Для "MAIN:varName:field" первый ':' — часть префикса MAIN:, нужен второй.
			// Для "self.varName:field" первый ':' — разделитель (в self. нет двоеточия).
			int colonIdx;
			if (rest.startsWith("MAIN:")) {
				colonIdx = rest.indexOf(':', 5); // пропускаем "MAIN:" и ищем следующий ':'
			} else {
				colonIdx = rest.indexOf(':');
			}
			if (colonIdx > 0) {
				String srcVarName = rest.substring(0, colonIdx);
				String fieldPath = rest.substring(colonIdx + 1);
				VisibleVariable effectiveSrcVar =
						resolveLocalVariableForHashExpansion(srcVarName, visibleFiles, currentFile, currentOffset, maxDepth - 1);
				if (effectiveSrcVar != null) {
					java.util.Map<String, HashEntryInfo> srcKeys;
					if ("table".equals(effectiveSrcVar.className) && effectiveSrcVar.columns != null && !effectiveSrcVar.columns.isEmpty()) {
						srcKeys = hashKeysFromColumns(effectiveSrcVar.columns);
					} else {
						srcKeys = resolveAllHashKeysInternal(effectiveSrcVar, visibleFiles, currentFile, currentOffset, maxDepth - 1);
					}
					HashEntryInfo fieldEntry = findHashEntryInResolvedChain(srcKeys, fieldPath);
					if (fieldEntry != null && fieldEntry.nestedKeys != null && !fieldEntry.nestedKeys.isEmpty()) {
						foreachKeys = collectForeachElementKeys(fieldEntry.nestedKeys);
					}
				}
				if (foreachKeys == null) {
					// Fallback оставляем только для additive-ключей из тела того же foreach.
				}
				if ((foreachKeys == null || foreachKeys.isEmpty()
						|| (foreachKeys.size() == 1 && foreachKeys.containsKey("*")))
						&& !fieldPath.isEmpty()) {
					java.util.Map<String, HashEntryInfo> directForeachFieldKeys =
							findForeachFieldAdditiveKeys(srcVarName, fieldPath, currentFile, currentOffset);
					if (directForeachFieldKeys != null && !directForeachFieldKeys.isEmpty()) {
						foreachKeys = new java.util.LinkedHashMap<>(unwrapWildcardOnlyKeys(directForeachFieldKeys));
					}
				}
				if (foreachKeys != null && !foreachKeys.isEmpty()) {
					if (DEBUG_ALWAYS) System.out.println("[resolveAllHashKeys] foreach_field:" + srcVarName
							+ ":" + fieldPath + " → fieldKeys="
							+ "local"
							+ " → foreachKeys(nested): " + foreachKeys.keySet());
				} else {
					// Поле существует но без ключей — foreachKeys пустой, чтобы не упасть в обычную логику
					foreachKeys = new java.util.LinkedHashMap<>();
				}
			}
		}

		// === foreach:varName — ключи элементов массива/хеша ===
		// $arr[$.x[];$.y[];$.z[$.inner[]]] → ^arr.for[k;v]{$v.} → x, y, z.
		// v пробегает по ЭЛЕМЕНТАМ массива (0,1,2...), каждый элемент — хеш с ключами x/y/z
		// Берём nestedKeys каждого элемента (индекса) — это и есть ключи v
		// Также: ^data.foreach[;v]{$v.xxx[]} → $v. показывает xxx (additive из тела)
		if (hasSourceVarKey && rootSourceVarKey.startsWith("foreach:")) {
			String srcVarName = rootSourceVarKey.substring(8); // после "foreach:"
			foreachKeys = new java.util.LinkedHashMap<>();
			// 1. nestedKeys элементов источника (ключи каждого элемента массива)
			VisibleVariable effectiveSrcVar =
					resolveLocalVariableForHashExpansion(srcVarName, visibleFiles, currentFile, currentOffset, maxDepth - 1);
			if (effectiveSrcVar != null) {
				java.util.Map<String, HashEntryInfo> elementKeys = null;
				if ("table".equals(effectiveSrcVar.className) && effectiveSrcVar.columns != null && !effectiveSrcVar.columns.isEmpty()) {
					elementKeys = hashKeysFromColumns(effectiveSrcVar.columns);
				} else {
					java.util.Map<String, HashEntryInfo> srcKeys = resolveAllHashKeysInternal(effectiveSrcVar, visibleFiles, currentFile, currentOffset, maxDepth - 1);
					elementKeys = collectForeachElementKeys(srcKeys);
				}
				if (elementKeys != null) {
					foreachKeys.putAll(elementKeys);
				}
			}
			// 2. Additive ключи из тела foreach: ^data.foreach[;v]{$v.xxx[]}
			java.util.Map<String, HashEntryInfo> additiveKeys =
					findForeachAdditiveKeys(srcVarName, currentFile, currentOffset);
			if (additiveKeys != null) {
				foreachKeys = mergeHashEntryMaps(foreachKeys, additiveKeys);
			}
			if (foreachKeys.isEmpty()) foreachKeys = null;
			if (DEBUG_ALWAYS && foreachKeys != null) System.out.println("[resolveAllHashKeys] foreach:" + srcVarName
					+ " → keys: " + foreachKeys.keySet());
		}

		// Мержим foreachKeys + собственные ключи
		if (foreachKeys != null) {
			if (hasOwn) {
				foreachKeys = mergeHashEntryMaps(foreachKeys, rootVar.hashKeys);
			}
			return foreachKeys.isEmpty() ? null : foreachKeys;
		}

		// === Нет foreach — обычная логика ===
		if (!hasOwn && !hasSources) {
			// Пробуем sourceVarKey (для $copy[$original] и ^array::copy[$arr])
			if (hasSourceVarKey) {
				ChainResolveInfo chainInfo =
						resolveEffectiveChain(rootSourceVarKey, visibleFiles, currentFile, rootSourceLookupOffset);
				if (chainInfo != null && chainInfo.hashKeys != null && !chainInfo.hashKeys.isEmpty()) {
					if (DEBUG_ALWAYS) System.out.println("[resolveAllHashKeys] chain sourceVarKey='" + rootSourceVarKey + "'");
					return chainInfo.hashKeys;
				}
				VisibleVariable effectiveSource =
						resolveLocalVariableForHashExpansion(rootSourceVarKey, visibleFiles, currentFile, rootSourceLookupOffset, maxDepth - 1);
				if (effectiveSource != null) {
					if (DEBUG_ALWAYS) System.out.println("[resolveAllHashKeys] following sourceVarKey='" + rootSourceVarKey + "'");
					return resolveAllHashKeysInternal(effectiveSource, visibleFiles, currentFile, rootSourceLookupOffset, maxDepth - 1);
				}
			}
			return null;
		}
		if (hasOwn && !hasSources) return rootVar.hashKeys;

		// Мержим: сначала ключи из источников, потом собственные (перезаписывают)
		java.util.Map<String, HashEntryInfo> merged = new java.util.LinkedHashMap<>();
		java.util.List<String> operationSources = new java.util.ArrayList<>();

		// Ключи из переменных-источников
		for (String sourceVarKey : rootVar.hashSourceVars) {
			int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(sourceVarKey, currentOffset);
			String srcVarName = P3VariableEffectiveShape.decodeHashSourceVarKey(sourceVarKey);
			if (srcVarName.startsWith("tableop\n")) {
				java.util.Map<String, HashEntryInfo> tableKeys =
						resolveTableOperationKeys(srcVarName, visibleFiles, currentFile, sourceLookupOffset, maxDepth - 1);
				if (tableKeys != null) {
					merged = mergeHashEntryMaps(merged, tableKeys);
					if (DEBUG_ALWAYS) System.out.println("[resolveAllHashKeys] merged tableop: " + tableKeys.keySet());
				}
				continue;
			}
			if (srcVarName.startsWith("hashop\n")) {
				operationSources.add(sourceVarKey);
				continue;
			}
			VisibleVariable effectiveSrcVar =
					resolveLocalVariableForHashExpansion(srcVarName, visibleFiles, currentFile, sourceLookupOffset, maxDepth - 1);
			if (effectiveSrcVar == null) {
				if (DEBUG_ALWAYS) System.out.println("[resolveAllHashKeys] source '" + srcVarName + "' not found");
				continue;
			}
			java.util.Map<String, HashEntryInfo> srcKeys = resolveAllHashKeysInternal(effectiveSrcVar, visibleFiles, currentFile, sourceLookupOffset, maxDepth - 1);
			if (srcKeys != null) {
				merged.putAll(srcKeys);
				if (DEBUG_ALWAYS) System.out.println("[resolveAllHashKeys] merged from '" + srcVarName + "': " + srcKeys.keySet());
			}
		}

		// Собственные ключи (перезаписывают унаследованные)
		if (hasOwn) {
			merged.putAll(rootVar.hashKeys);
		}

		for (String sourceVarKey : operationSources) {
			int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(sourceVarKey, currentOffset);
			String decodedSourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(sourceVarKey);
			java.util.Map<String, HashEntryInfo> opKeys =
					resolveHashOperationKeys(decodedSourceVarKey, visibleFiles, currentFile, sourceLookupOffset, maxDepth - 1);
			if (opKeys != null) {
				merged = mergeHashEntryMaps(merged, opKeys);
				if (DEBUG_ALWAYS) System.out.println("[resolveAllHashKeys] merged hashop: " + opKeys.keySet());
			}
		}

		return merged.isEmpty() ? null : merged;
	}

	private @Nullable java.util.Map<String, HashEntryInfo> resolveTableOperationKeys(
			@NotNull String sourceVarKey,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth
	) {
		if (maxDepth <= 0) return null;
		String[] parts = sourceVarKey.split("\\n", -1);
		if (parts.length < 4 || !"tableop".equals(parts[0])) return null;

		String tableVarName = parts[2];
		String valueMode = parts[3];
		VisibleVariable effectiveTable =
				resolveLocalVariableForHashExpansion(tableVarName, visibleFiles, currentFile, currentOffset, maxDepth - 1);
		if (effectiveTable == null) return null;
		if (!"table".equals(effectiveTable.className)
				|| effectiveTable.columns == null
				|| effectiveTable.columns.isEmpty()) {
			return null;
		}

		java.util.Map<String, HashEntryInfo> result = new java.util.LinkedHashMap<>();
		if ("scalar".equals(valueMode)) {
			result.put("*", new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE));
			return result;
		}

		java.util.Map<String, HashEntryInfo> nested = new java.util.LinkedHashMap<>();
		for (String column : effectiveTable.columns) {
			nested.put(column, new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE));
		}
		result.put("*", new HashEntryInfo("hash", null, nested));
		return result;
	}

	/**
	 * Находит ключи хеша для цепочки доступа.
	 * Например, для $data.key → возвращает вложенные ключи data.key
	 * Для $data → возвращает верхнеуровневые ключи data
	 *
	 * @param varKey полная цепочка: "data", "data.key", "self.data.key.sub"
	 * @return Map ключей или null
	 */
	public @Nullable java.util.Map<String, HashEntryInfo> findHashKeysForChain(
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		ChainResolveInfo info =
				resolveEffectiveChain(varKey, getVisibleFilesForCurrent(currentFile, currentOffset), currentFile, currentOffset);
		return info != null ? info.hashKeys : null;
	}

	/**
	 * Находит тип значения хеш-ключа для цепочки доступа.
	 * Например, для $data.key.list → тип "table" (если $.key[$.list[^table::create{...}]])
	 *
	 * @param varKey полная цепочка: "data.key.list"
	 * @return HashEntryInfo для последнего сегмента, или null
	 */
	/**
	 * При fallback на wildcard '*' мержит nestedKeys из '*' + nestedKeys всех явных ключей.
	 * Пример: $data[$.key1[$.xxx[]] $.key2[$.yyy[]]] + ^data.add[^hash::sql{...}]
	 * → $data.x. → {xxx, yyy} (от key1, key2) + {name, value} (от *)
	 */
	private @Nullable HashEntryInfo resolveWildcardEntry(
			@NotNull java.util.Map<String, HashEntryInfo> currentKeys
	) {
		return P3VariableEffectiveShape.resolveWildcardEntry(currentKeys);
	}

	/**
	 * Динамический сегмент .[$key] нормализуется в "*".
	 * Если явного wildcard нет, берём объединённую форму всех известных значений.
	 */
	private @Nullable HashEntryInfo resolveDynamicHashEntry(
			@NotNull java.util.Map<String, HashEntryInfo> currentKeys
	) {
		return P3VariableEffectiveShape.resolveDynamicHashEntry(currentKeys);
	}

	private @Nullable HashEntryInfo resolveHashEntry(
			@NotNull java.util.Map<String, HashEntryInfo> currentKeys,
			@NotNull String key
	) {
		return P3VariableEffectiveShape.resolveHashEntry(currentKeys, key);
	}

	private static @Nullable java.util.Map<String, HashEntryInfo> collectForeachElementKeys(
			@Nullable java.util.Map<String, HashEntryInfo> collectionKeys
	) {
		if (collectionKeys == null || collectionKeys.isEmpty()) return null;

		java.util.Map<String, HashEntryInfo> result = new java.util.LinkedHashMap<>();
		for (java.util.Map.Entry<String, HashEntryInfo> entry : collectionKeys.entrySet()) {
			HashEntryInfo valueInfo = entry.getValue();
			if (valueInfo != null && valueInfo.nestedKeys != null && !valueInfo.nestedKeys.isEmpty()) {
				result.putAll(unwrapWildcardOnlyKeys(valueInfo.nestedKeys));
			}
		}

		return result.isEmpty() ? null : result;
	}

	private @Nullable java.util.Map<String, HashEntryInfo> resolveHashOperationKeys(
			@NotNull String sourceVarKey,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth
	) {
		if (maxDepth <= 0) return null;
		String[] parts = sourceVarKey.split("\\n", -1);
		if (parts.length < 3 || !"hashop".equals(parts[0])) return null;

		String operation = parts[1];
		String selfVarName = parts[2];
		String argVarName = parts.length >= 4 ? parts[3] : "";

		java.util.Map<String, HashEntryInfo> selfKeys = resolveHashOperationVarKeys(selfVarName, visibleFiles, currentFile, currentOffset, maxDepth);
		if ("copy".equals(operation) || "select".equals(operation)) {
			return selfKeys;
		}

		java.util.Map<String, HashEntryInfo> argKeys = !argVarName.isEmpty()
				? resolveHashOperationVarKeys(argVarName, visibleFiles, currentFile, currentOffset, maxDepth)
				: null;

		if ("union".equals(operation)) {
			if (selfKeys == null || selfKeys.isEmpty()) return argKeys;
			if (argKeys == null || argKeys.isEmpty()) return selfKeys;
			java.util.Map<String, HashEntryInfo> result = new java.util.LinkedHashMap<>(selfKeys);
			for (java.util.Map.Entry<String, HashEntryInfo> entry : argKeys.entrySet()) {
				result.putIfAbsent(entry.getKey(), entry.getValue());
			}
			return result;
		}

		if ("intersection".equals(operation)) {
			if (selfKeys == null || selfKeys.isEmpty() || argKeys == null || argKeys.isEmpty()) return null;
			java.util.Map<String, HashEntryInfo> result = new java.util.LinkedHashMap<>();
			for (java.util.Map.Entry<String, HashEntryInfo> entry : selfKeys.entrySet()) {
				if (argKeys.containsKey(entry.getKey())) {
					result.put(entry.getKey(), entry.getValue());
				}
			}
			return result.isEmpty() ? null : result;
		}

		if ("sub_mutation".equals(operation)) {
			if (argKeys == null || argKeys.isEmpty()) return null;
			java.util.Map<String, HashEntryInfo> result = new java.util.LinkedHashMap<>();
			for (String key : argKeys.keySet()) {
				result.put(key, new HashEntryInfo("__deleted__"));
			}
			return result;
		}

		return selfKeys;
	}

	private @Nullable java.util.Map<String, HashEntryInfo> resolveHashOperationVarKeys(
			@NotNull String varName,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth
	) {
		VisibleVariable effectiveVar =
				resolveLocalVariableForHashExpansion(varName, visibleFiles, currentFile, currentOffset, maxDepth - 1);
		if (effectiveVar == null) return null;
		if ("table".equals(effectiveVar.className) && effectiveVar.columns != null && !effectiveVar.columns.isEmpty()) {
			return hashKeysFromColumns(effectiveVar.columns);
		}
		return resolveAllHashKeysInternal(effectiveVar, visibleFiles, currentFile, currentOffset, maxDepth - 1);
	}

	private @Nullable VisibleVariable resolveLocalVariableForHashExpansion(
			@NotNull String varName,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth
	) {
		VisibleVariable var = findVariableInCurrentFileOnly(varName, currentFile, currentOffset);
		if (var == null) {
			// Алиасы вида $local[$MAIN:object] могут ссылаться на объект из auto.p или @USE-файла.
			var = findRawVisibleVariable(varName, visibleFiles, currentFile, currentOffset);
		}
		if (var == null) return null;
		return resolveVariableForHashExpansion(var, visibleFiles, currentFile, currentOffset, maxDepth);
	}

	private @NotNull VisibleVariable resolveVariableForHashExpansion(
			@NotNull VisibleVariable variable,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth
	) {
		VisibleVariable resolved = variable;
		if (resolved.needsTypeResolve()) {
			resolved = resolveMethodCallVariableInternal(
					resolved,
					visibleFiles,
					currentFile,
					currentOffset,
					new java.util.HashSet<>(),
					new java.util.HashSet<>()
			);
		}
		return resolveSourceVariableInternal(
				resolved,
				visibleFiles,
				currentFile,
				currentOffset,
				maxDepth,
				new java.util.HashSet<>(),
				new java.util.HashSet<>()
		);
	}

	private static @Nullable java.util.Map<String, HashEntryInfo> hashKeysFromColumns(
			@Nullable java.util.List<String> columns
	) {
		if (columns == null || columns.isEmpty()) return null;
		java.util.Map<String, HashEntryInfo> result = new java.util.LinkedHashMap<>();
		for (String column : columns) {
			result.put(column, new HashEntryInfo(P3VariableFileIndex.UNKNOWN_TYPE));
		}
		return result;
	}

	private static @Nullable HashEntryInfo findHashEntryInResolvedChain(
			@Nullable java.util.Map<String, HashEntryInfo> rootKeys,
			@NotNull String fieldPath
	) {
		if (rootKeys == null || rootKeys.isEmpty() || fieldPath.isEmpty()) return null;
		java.util.Map<String, HashEntryInfo> currentKeys = unwrapWildcardOnlyKeys(rootKeys);
		HashEntryInfo currentEntry = null;
		for (String segment : fieldPath.split("\\.")) {
			if (currentKeys == null || currentKeys.isEmpty()) return null;
			currentEntry = currentKeys.get(segment);
			if (currentEntry == null) return null;
			currentKeys = currentEntry.nestedKeys != null ? unwrapWildcardOnlyKeys(currentEntry.nestedKeys) : null;
		}
		return currentEntry;
	}

	private static @NotNull java.util.Map<String, HashEntryInfo> unwrapWildcardOnlyKeys(
			@NotNull java.util.Map<String, HashEntryInfo> keys
	) {
		java.util.Map<String, HashEntryInfo> current = keys;
		while (current.size() == 1 && current.containsKey("*")) {
			HashEntryInfo wildcard = current.get("*");
			if (wildcard == null || wildcard.nestedKeys == null || wildcard.nestedKeys.isEmpty()) {
				break;
			}
			current = wildcard.nestedKeys;
		}
		return current;
	}

	private static @NotNull HashEntryInfo mergeHashEntryInfo(
			@NotNull HashEntryInfo oldInfo,
			@NotNull HashEntryInfo newInfo
	) {
		return P3VariableEffectiveShape.mergeHashEntryInfo(oldInfo, newInfo);
	}

	private static @Nullable Parser3BuiltinMethods.BuiltinCallable findBuiltinInstanceProperty(
			@NotNull String className,
			@NotNull String propertyName
	) {
		for (Parser3BuiltinMethods.BuiltinCallable property : Parser3BuiltinMethods.getPropertiesForClass(className)) {
			if (property.name.equals(propertyName)) {
				return property;
			}
		}
		return null;
	}

	private static @Nullable java.util.Map<String, HashEntryInfo> mergeHashEntryMaps(
			@Nullable java.util.Map<String, HashEntryInfo> oldMap,
			@Nullable java.util.Map<String, HashEntryInfo> newMap
	) {
		return P3VariableEffectiveShape.mergeHashEntryMaps(oldMap, newMap);
	}

	/**
	 * Возвращает параметры метода в позиции offset, если @OPTIONS locals активны.
	 * Используется в contributor для фильтрации self.X при normal-контексте.
	 */
	public @Nullable java.util.Set<String> getMethodParamsAtOffset(
			@NotNull VirtualFile file, int offset) {
		CursorContext ctx = buildCursorContext(file, offset);
		if (ctx.method == null || ctx.text == null) return null;
		return getMethodParams(ctx.method, ctx.text);
	}

	public boolean hasLocalsAtOffset(@NotNull VirtualFile file, int offset) {
		return buildCursorContext(file, offset).hasLocals;
	}
	/**
	 * Строит строку пути из segments[from..to) через точку.
	 */
	private static @NotNull String buildFieldPath(@NotNull String[] segments, int from, int to) {
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < to; i++) {
			if (i > from) sb.append('.');
			sb.append(segments[i]);
		}
		return sb.toString();
	}

	/**
	 * Ищет additive ключи из тела foreach/for по переменной.
	 * Например: ^data.foreach[k;v]{$v.key4[...]} → возвращает {key4: ...}
	 * 1. Находим имя foreach-value переменной (у которой sourceVarKey="foreach:rootVarName")
	 * 2. Собираем additive hashKeys этой переменной
	 */
	/**
	 * Обогащает hashKeys алиасными данными: для каждого ключа "keyName" проверяет
	 * есть ли псевдонимы для "varKey.keyName" и добавляет их ключи как nestedKeys.
	 * Используется чтобы $data.key1 показывался с точкой если есть $alias[$data.key1] + $alias.x[].
	 */
	public @NotNull java.util.Map<String, HashEntryInfo> enrichWithAliasKeys(
			@NotNull java.util.Map<String, HashEntryInfo> hashKeys,
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		java.util.Map<String, HashEntryInfo> enriched = null;
		for (java.util.Map.Entry<String, HashEntryInfo> e : hashKeys.entrySet()) {
			String keyName = e.getKey();
			if ("*".equals(keyName)) continue;
			HashEntryInfo info = e.getValue();
			String subChain = varKey + "." + keyName;
			java.util.Map<String, HashEntryInfo> aliasKeys = mergeAliasHashKeys(subChain, currentFile, currentOffset);
			if (DEBUG_ALWAYS) System.out.println("[enrichWithAliasKeys] subChain=" + subChain + " aliasKeys=" + (aliasKeys != null ? aliasKeys.keySet() : "null"));
			if (aliasKeys != null && !aliasKeys.isEmpty()) {
				if (enriched == null) enriched = new java.util.LinkedHashMap<>(hashKeys);
				java.util.Map<String, HashEntryInfo> merged = info.nestedKeys != null
						? new java.util.LinkedHashMap<>(info.nestedKeys) : new java.util.LinkedHashMap<>();
				merged = mergeHashEntryMaps(merged, aliasKeys);
				enriched.put(keyName, info.withNestedKeys(merged));
			}
		}
		return enriched != null ? enriched : hashKeys;
	}

	/**
	 * Ищет все переменные-псевдонимы в текущем файле у которых sourceVarKey = canonicalChain,
	 * и возвращает мерж их hashKeys.
	 * Используется потому что $alias[$data.key1] — ссылка на тот же объект (как в JS),
	 * поэтому $alias.newKey[...] должно быть видно через $data.key1.
	 */
	private @Nullable java.util.Map<String, HashEntryInfo> mergeAliasHashKeys(
			@NotNull String canonicalChain,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		CursorContext ctx = buildCursorContext(currentFile, currentOffset);
		if (ctx.text == null) return null;

		// 1. Текущий файл — с проверкой offset
		var currentFileData = parseCurrentFileVariables(ctx, currentFile, currentOffset);
		if (DEBUG_ALWAYS) System.out.println("[mergeAliasHashKeys] chain=" + canonicalChain + " fileData.keys=" + currentFileData.keySet());
		java.util.Map<String, HashEntryInfo> result = mergeAliasKeysFromCurrentFile(canonicalChain, currentFile, currentFileData, currentOffset, null);

		// 2. Видимые файлы (через use) — с учётом позиции подключения
		FileBasedIndex index = FileBasedIndex.getInstance();
		for (VirtualFile file : getVisibleFilesForCurrent(currentFile, currentOffset)) {
			if (file.equals(currentFile) || !file.isValid()) continue;
			var fileData = index.getFileData(P3VariableFileIndex.NAME, file, project);
			if (fileData.isEmpty()) continue;
			result = mergeAliasKeysFromIndexData(canonicalChain, fileData, file, result);
		}

		if (DEBUG_ALWAYS) System.out.println("[mergeAliasHashKeys] chain=" + canonicalChain + " → " + (result != null ? result.keySet() : "null"));
		return result;
	}

	/**
	 * Ищет псевдонимы в данных текущего файла с проверкой offset.
	 */
	private @Nullable java.util.Map<String, HashEntryInfo> mergeAliasKeysFromCurrentFile(
			@NotNull String canonicalChain,
			@NotNull VirtualFile currentFile,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> fileData,
			int currentOffset,
			@Nullable java.util.Map<String, HashEntryInfo> existing
	) {
		CursorContext ctx = buildCursorContext(currentFile, currentOffset);
		if (ctx.text == null) return existing;

		// Находим псевдонимы: sourceVarKey = canonicalChain, объявленные до курсора
		java.util.Set<String> aliasVarKeys = new java.util.HashSet<>();
		for (var entry : fileData.entrySet()) {
			for (var info : entry.getValue()) {
				if (!isVariableVisible(info, ctx, null)) continue;
				if (DEBUG_ALWAYS) System.out.println("[mergeAliasHashKeys] checking varKey=" + entry.getKey() + " sourceVarKey=" + info.sourceVarKey + " hashKeys=" + (info.hashKeys != null ? info.hashKeys.keySet() : "null"));
				if (canonicalChain.equals(info.sourceVarKey) && info.offset <= currentOffset) {
					aliasVarKeys.add(entry.getKey());
				}
			}
		}
		if (aliasVarKeys.isEmpty()) return existing;
		if (DEBUG_ALWAYS) System.out.println("[mergeAliasHashKeys] aliases=" + aliasVarKeys);

		// Минимальный offset объявления псевдонима — ключи должны быть добавлены ПОСЛЕ него
		java.util.Map<String, Integer> aliasDeclarationOffset = new java.util.HashMap<>();
		for (var entry : fileData.entrySet()) {
			if (!aliasVarKeys.contains(entry.getKey())) continue;
			for (var info : entry.getValue()) {
				if (!isVariableVisible(info, ctx, null)) continue;
				if (canonicalChain.equals(info.sourceVarKey) && info.offset <= currentOffset) {
					aliasDeclarationOffset.merge(entry.getKey(), info.offset, Math::min);
				}
			}
		}

		java.util.Map<String, HashEntryInfo> result = existing;
		for (var entry : fileData.entrySet()) {
			if (!aliasVarKeys.contains(entry.getKey())) continue;
			int aliasOffset = aliasDeclarationOffset.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
			for (var info : entry.getValue()) {
				if (!isVariableVisible(info, ctx, null)) continue;
				if (info.offset <= aliasOffset) continue; // ключ добавлен ДО псевдонима — не считается
				if (info.offset > currentOffset) continue; // ключ после курсора — не виден
				if (info.hashKeys != null && !info.hashKeys.isEmpty()) {
					if (result == null) result = new java.util.LinkedHashMap<>();
					result.putAll(attachHashEntryFiles(info.hashKeys, currentFile));
				}
			}
		}
		return result;
	}

	/**
	 * Ищет псевдонимы в данных индексированного файла (без ограничения offset — файл целиком виден через use).
	 */
	private @Nullable java.util.Map<String, HashEntryInfo> mergeAliasKeysFromIndexData(
			@NotNull String canonicalChain,
			@NotNull java.util.Map<String, java.util.List<P3VariableFileIndex.VariableTypeInfo>> fileData,
			@NotNull VirtualFile file,
			@Nullable java.util.Map<String, HashEntryInfo> existing
	) {
		// Находим псевдонимы
		java.util.Set<String> aliasVarKeys = new java.util.HashSet<>();
		for (var entry : fileData.entrySet()) {
			for (var info : entry.getValue()) {
				if (canonicalChain.equals(info.sourceVarKey)) {
					aliasVarKeys.add(entry.getKey());
				}
			}
		}
		if (aliasVarKeys.isEmpty()) return existing;

		// Минимальный offset объявления псевдонима
		java.util.Map<String, Integer> aliasDeclarationOffset = new java.util.HashMap<>();
		for (var entry : fileData.entrySet()) {
			if (!aliasVarKeys.contains(entry.getKey())) continue;
			for (var info : entry.getValue()) {
				if (canonicalChain.equals(info.sourceVarKey)) {
					aliasDeclarationOffset.merge(entry.getKey(), info.offset, Math::min);
				}
			}
		}

		java.util.Map<String, HashEntryInfo> result = existing;
		for (var entry : fileData.entrySet()) {
			if (!aliasVarKeys.contains(entry.getKey())) continue;
			int aliasOffset = aliasDeclarationOffset.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
			for (var info : entry.getValue()) {
				if (info.offset <= aliasOffset) continue; // ключ до псевдонима — не считается
				if (info.hashKeys != null && !info.hashKeys.isEmpty()) {
					if (result == null) result = new java.util.LinkedHashMap<>();
					result.putAll(attachHashEntryFiles(info.hashKeys, file));
				}
			}
		}
		return result;
	}

	private @Nullable java.util.Map<String, HashEntryInfo> findForeachAdditiveKeys(
			@NotNull String rootVarPureName,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		CursorContext ctx = buildCursorContext(currentFile, currentOffset);
		if (ctx.text == null) return null;

		var fileData = parseCurrentFileVariables(ctx, currentFile, currentOffset);
		String targetSvk = "foreach:" + rootVarPureName;
		int rootDefinitionOffset = findActiveRootVariableOffset(rootVarPureName, currentFile, currentOffset);
		if (DEBUG_ALWAYS) System.out.println("[findForeachAdditiveKeys] looking for svk=" + targetSvk + " in " + fileData.keySet());

		// 1. Находим ВСЕ записи foreach-value переменной с нужным sourceVarKey,
		//    запоминаем их offset чтобы брать только additive записи того же foreach
		java.util.List<Integer> foreachValueOffsets = new java.util.ArrayList<>();
		String foreachValueVarKey = null;
		for (var entry : fileData.entrySet()) {
			for (var info : entry.getValue()) {
				if (!isVariableVisible(info, ctx, null)) continue;
				if (info.offset <= rootDefinitionOffset) continue;
				if (targetSvk.equals(info.sourceVarKey)) {
					foreachValueVarKey = entry.getKey();
					foreachValueOffsets.add(info.offset);
				}
			}
			if (foreachValueVarKey != null) break;
		}
		if (foreachValueVarKey == null) return null;

		// 2. Собираем additive hashKeys только от записей с offset в диапазоне [foff, nextFoff)
		//    где foff — offset объявления foreach-value, nextFoff — offset следующего объявления того же varKey
		java.util.Map<String, HashEntryInfo> result = null;
		java.util.List<P3VariableFileIndex.VariableTypeInfo> infos = fileData.get(foreachValueVarKey);
		if (infos != null) {
			// nextOff = offset следующего foreach-объявления той же переменной (другой foreach)
			// Это позволяет корректно ограничить диапазон additive записей одним foreach
			java.util.List<Integer> foreachDeclOffsets = new java.util.ArrayList<>();
			for (var info : infos) {
				if (!isVariableVisible(info, ctx, null)) continue;
				if (info.offset <= rootDefinitionOffset) continue;
				if (info.sourceVarKey != null && info.sourceVarKey.startsWith("foreach:")) {
					foreachDeclOffsets.add(info.offset);
				}
			}
			java.util.Collections.sort(foreachDeclOffsets);
			if (DEBUG_ALWAYS) System.out.println("[findForeachAdditiveKeys] foreachValueOffsets=" + foreachValueOffsets + " foreachDeclOffsets=" + foreachDeclOffsets);

			for (int foff : foreachValueOffsets) {
				// Следующий foreach-offset после foff
				int nextOff = Integer.MAX_VALUE;
				for (int off : foreachDeclOffsets) {
					if (off > foff && off < nextOff) nextOff = off;
				}
				for (var info : infos) {
					if (!isVariableVisible(info, ctx, null)) continue;
					if (info.offset <= rootDefinitionOffset) continue;
					if (!info.isAdditive || info.hashKeys == null || info.hashKeys.isEmpty()) continue;
					if (info.offset > foff && info.offset < nextOff) {
						if (result == null) result = new java.util.LinkedHashMap<>();
						result.putAll(attachHashEntryFiles(info.hashKeys, currentFile));
						if (DEBUG_ALWAYS) System.out.println("[findForeachAdditiveKeys] " + foreachValueVarKey
								+ " additive keys: " + info.hashKeys.keySet());
					}
				}
			}
		}
		return result;
	}

	/**
	 * Ищет additive ключи из тела foreach/for по полю переменной.
	 * Например: ^hash.meets.foreach[;v]{$v.xxx[...]} → для fieldPath="meets" возвращает {xxx: ...}
	 * Ищет переменную с sourceVarKey="foreach_field:rootVarName:fieldPath"
	 */
	private @Nullable java.util.Map<String, HashEntryInfo> findForeachFieldAdditiveKeys(
			@NotNull String rootVarPureName,
			@NotNull String fieldPath,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		CursorContext ctx = buildCursorContext(currentFile, currentOffset);
		if (ctx.text == null) return null;

		var fileData = parseCurrentFileVariables(ctx, currentFile, currentOffset);
		String targetSvk = "foreach_field:" + rootVarPureName + ":" + fieldPath;
		String collectionVarKey = rootVarPureName + "." + fieldPath;
		int rootDefinitionOffset = findActiveRootVariableOffset(rootVarPureName, currentFile, currentOffset);

		java.util.Map<String, HashEntryInfo> result = null;

		// 1. Собираем ключи самой коллекции root.fieldPath внутри текущего внешнего foreach.
		java.util.List<P3VariableFileIndex.VariableTypeInfo> rootInfos = fileData.get(rootVarPureName);
		java.util.List<P3VariableFileIndex.VariableTypeInfo> collectionInfos = fileData.get(collectionVarKey);
		java.util.List<Integer> matchingRootDeclOffsets = new java.util.ArrayList<>();
		if (rootInfos != null) {
			java.util.List<P3VariableFileIndex.VariableTypeInfo> rootForeachDecls = new java.util.ArrayList<>();
			for (var info : rootInfos) {
				if (!isVariableVisible(info, ctx, null)) continue;
				if (info.offset <= rootDefinitionOffset) continue;
				if (info.sourceVarKey != null && info.sourceVarKey.startsWith("foreach")) {
					rootForeachDecls.add(info);
				}
			}
			rootForeachDecls.sort(java.util.Comparator.comparingInt(info -> info.offset));

			String currentRootSourceVarKey = null;
			for (var decl : rootForeachDecls) {
				if (decl.offset < currentOffset) {
					currentRootSourceVarKey = decl.sourceVarKey;
				} else {
					break;
				}
			}

			if (currentRootSourceVarKey != null) {
				for (var decl : rootForeachDecls) {
					if (currentRootSourceVarKey.equals(decl.sourceVarKey)) {
						matchingRootDeclOffsets.add(decl.offset);
					}
				}
			}
		}

		if (!matchingRootDeclOffsets.isEmpty() && collectionInfos != null) {
			for (int idx = 0; idx < matchingRootDeclOffsets.size(); idx++) {
				int rootDeclOffset = matchingRootDeclOffsets.get(idx);
				int nextRootDeclOffset = idx + 1 < matchingRootDeclOffsets.size()
						? matchingRootDeclOffsets.get(idx + 1)
						: Integer.MAX_VALUE;
				for (var info : collectionInfos) {
					if (!isVariableVisible(info, ctx, null)) continue;
					if (info.offset <= rootDefinitionOffset) continue;
					if (info.offset <= rootDeclOffset || info.offset >= nextRootDeclOffset) continue;
					if (info.hashKeys != null && !info.hashKeys.isEmpty()) {
						if (result == null) result = new java.util.LinkedHashMap<>();
						result.putAll(attachHashEntryFiles(info.hashKeys, currentFile));
					}
				}
			}
		}

		// 1b. Если поле хранится вложенно в hashKeys самой root-переменной
		// ($v.anons.[...][...] индексируется как ключ "anons" у v),
		// поднимаем ключи элемента напрямую по path внутри rootInfo.hashKeys.
		if (rootInfos != null && !matchingRootDeclOffsets.isEmpty()) {
			String[] fieldSegments = fieldPath.split("\\.");
			for (int idx = 0; idx < matchingRootDeclOffsets.size(); idx++) {
				int rootDeclOffset = matchingRootDeclOffsets.get(idx);
				int nextRootDeclOffset = idx + 1 < matchingRootDeclOffsets.size()
						? matchingRootDeclOffsets.get(idx + 1)
						: Integer.MAX_VALUE;
				for (var info : rootInfos) {
					if (!isVariableVisible(info, ctx, null)) continue;
					if (info.offset <= rootDefinitionOffset) continue;
					if (info.offset < rootDeclOffset || info.offset >= nextRootDeclOffset) continue;
					if (info.hashKeys == null || info.hashKeys.isEmpty()) continue;
					java.util.Map<String, HashEntryInfo> nestedKeys = attachHashEntryFiles(info.hashKeys, currentFile);
					if (nestedKeys == null) continue;
					boolean missing = false;
					for (String segment : fieldSegments) {
						HashEntryInfo entry = nestedKeys.get(segment);
						if (entry == null) {
							missing = true;
							break;
						}
						nestedKeys = entry.nestedKeys;
						if (nestedKeys == null || nestedKeys.isEmpty()) {
							missing = true;
							break;
						}
					}
					if (missing) continue;
					java.util.Map<String, HashEntryInfo> elementKeys = collectForeachElementKeys(nestedKeys);
					if (elementKeys != null && !elementKeys.isEmpty()) {
						if (result == null) result = new java.util.LinkedHashMap<>();
						result.putAll(elementKeys);
					}
				}
			}
		}

		// 2. Находим все объявления нужной foreach-value переменной.
		// Одинаковое имя (например, v2) может использоваться в нескольких foreach,
		// поэтому дальше нельзя смешивать их hashKeys между разными диапазонами.
		String foreachValueVarKey = null;
		java.util.List<Integer> foreachValueOffsets = new java.util.ArrayList<>();
		for (var entry : fileData.entrySet()) {
			for (var info : entry.getValue()) {
				if (!isVariableVisible(info, ctx, null)) continue;
				if (info.offset <= rootDefinitionOffset) continue;
				if (targetSvk.equals(info.sourceVarKey)) {
					foreachValueVarKey = entry.getKey();
					foreachValueOffsets.add(info.offset);
				}
			}
			if (foreachValueVarKey != null) break;
		}
		if (foreachValueVarKey == null) return null;

		// 3. Собираем hashKeys только из диапазона конкретного foreach-value.
		java.util.List<P3VariableFileIndex.VariableTypeInfo> infos = fileData.get(foreachValueVarKey);
		if (infos != null) {
			java.util.List<Integer> foreachDeclOffsets = new java.util.ArrayList<>();
			for (var info : infos) {
				if (!isVariableVisible(info, ctx, null)) continue;
				if (info.offset <= rootDefinitionOffset) continue;
				if (info.sourceVarKey != null && info.sourceVarKey.startsWith("foreach")) {
					foreachDeclOffsets.add(info.offset);
				}
			}
			java.util.Collections.sort(foreachDeclOffsets);

			for (int foff : foreachValueOffsets) {
				int nextOff = Integer.MAX_VALUE;
				for (int off : foreachDeclOffsets) {
					if (off > foff && off < nextOff) nextOff = off;
				}
				for (var info : infos) {
					if (!isVariableVisible(info, ctx, null)) continue;
					if (info.offset <= rootDefinitionOffset) continue;
					if (info.offset < foff || info.offset >= nextOff) continue;
					if (info.hashKeys != null && !info.hashKeys.isEmpty()) {
						if (result == null) result = new java.util.LinkedHashMap<>();
						result.putAll(attachHashEntryFiles(info.hashKeys, currentFile));
						if (DEBUG_ALWAYS) System.out.println("[findForeachFieldAdditiveKeys] " + foreachValueVarKey
								+ " fieldPath=" + fieldPath + " range=[" + foff + "," + nextOff + ") keys: " + info.hashKeys.keySet());
					}
				}
			}
		}
		return result;
	}

	private int findActiveRootVariableOffset(
			@NotNull String rootVarPureName,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		VisibleVariable rootVar = findVariableInCurrentFileRaw(rootVarPureName, currentFile, currentOffset);
		if (rootVar != null && rootVar.sourceVarKey != null
				&& (rootVar.sourceVarKey.startsWith("foreach:")
				|| rootVar.sourceVarKey.startsWith("foreach_field:"))) {
			return Integer.MIN_VALUE;
		}
		return rootVar != null ? rootVar.offset : Integer.MIN_VALUE;
	}
	public @Nullable HashEntryInfo findHashEntryForChain(
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		ChainResolveInfo info =
				resolveEffectiveChain(varKey, getVisibleFilesForCurrent(currentFile, currentOffset), currentFile, currentOffset);
		return info != null ? info.hashEntry : null;
	}

	/**
	 * Находит колонки таблицы для переменной (если тип = table и колонки проиндексированы).
	 * Если переменная — копия другой таблицы (sourceVarKey), резолвим колонки рекурсивно.
	 * Используется для автокомплита ^list.name, $list.name.
	 */
	public @Nullable java.util.List<String> findVariableColumns(
			@NotNull String varKey,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		return resolveColumnsForChain(varKey, visibleFiles, currentFile, currentOffset);
	}

	private @Nullable java.util.List<String> resolveColumnsForChain(
			@NotNull String varKey,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset
	) {
		ChainResolveInfo chainInfo =
				resolveEffectiveChain(varKey, visibleFiles, currentFile, currentOffset);
		return chainInfo != null ? chainInfo.columns : null;
	}

	private @Nullable java.util.List<String> resolveColumnsForVariable(
			@NotNull VisibleVariable variable,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth
	) {
		return resolveColumnsForVariable(
				variable,
				visibleFiles,
				currentFile,
				currentOffset,
				maxDepth,
				new java.util.HashSet<>()
		);
	}

	/**
	 * Дорезолв колонок от уже найденной effective-переменной.
	 * Обычный внешний вход для колонок остаётся через resolveEffectiveChain().
	 */
	private @Nullable java.util.List<String> resolveColumnsForVariable(
			@NotNull VisibleVariable variable,
			@NotNull java.util.List<VirtualFile> visibleFiles,
			@NotNull VirtualFile currentFile,
			int currentOffset,
			int maxDepth,
			@NotNull java.util.Set<String> visited
	) {
		if (maxDepth <= 0) return null;

		String visitKey = variable.varKey + "|" + variable.file.getPath() + "|" + variable.offset;
		if (!visited.add(visitKey)) return null;

		try {
			VisibleVariable v = variable;
			if (v.needsTypeResolve()) {
				v = resolveVisibleVariable(v, visibleFiles, currentFile, currentOffset);
			}
			if (P3VariableFileIndex.UNKNOWN_TYPE.equals(v.className)
					&& v.sourceVarKey != null
					&& !v.sourceVarKey.isEmpty()) {
				v = resolveSourceVariable(v, visibleFiles, currentFile, currentOffset);
			}
			if (DEBUG_ALWAYS) System.out.println("[resolveVarCols] varKey=" + v.varKey + " maxDepth=" + maxDepth
					+ " className=" + v.className + " columns=" + v.columns + " sourceVarKey=" + v.sourceVarKey);

			if (v.columns != null && !v.columns.isEmpty()) {
				return v.columns;
			}
			if (v.sourceVarKey != null && !v.sourceVarKey.isEmpty()) {
				String sourceVarKey = P3VariableEffectiveShape.decodeHashSourceVarKey(v.sourceVarKey);
				int sourceLookupOffset = P3VariableEffectiveShape.decodeHashSourceLookupOffset(v.sourceVarKey, currentOffset);
				if (isDotPathReference(sourceVarKey)) {
					ChainResolveInfo chainInfo =
							resolveEffectiveChain(sourceVarKey, visibleFiles, currentFile, sourceLookupOffset);
					if (chainInfo != null && chainInfo.columns != null && !chainInfo.columns.isEmpty()) {
						return chainInfo.columns;
					}
				}
				VisibleVariable source = findRawVisibleVariable(sourceVarKey, visibleFiles, currentFile, sourceLookupOffset);
				if (source != null) {
					return resolveColumnsForVariable(source, visibleFiles, currentFile, sourceLookupOffset, maxDepth - 1, visited);
				}
			}
			return null;
		} finally {
			visited.remove(visitKey);
		}
	}

	private @Nullable VariableCompletionInfo getClassVariableInfo(
			@NotNull String className,
			@NotNull String propertyName,
			@NotNull List<VirtualFile> visibleFiles
	) {
		VariableCompletionInfo best = null;
		FileBasedIndex index = FileBasedIndex.getInstance();

		for (VirtualFile file : visibleFiles) {
			if (!file.isValid()) continue;

			var fileData = index.getFileData(P3VariableFileIndex.NAME, file, project);
			if (fileData.isEmpty()) continue;

			for (Map.Entry<String, List<P3VariableFileIndex.VariableTypeInfo>> entry : fileData.entrySet()) {
				String varKey = entry.getKey();
				String cleanName = varKey;
				if (varKey.startsWith("self.")) {
					cleanName = varKey.substring(5);
				} else if (varKey.startsWith("MAIN:")) {
					continue;
				}
				if (!propertyName.equals(cleanName)) continue;

				P3VariableEffectiveShape shape = new P3VariableEffectiveShape(cleanName, true);
				for (P3VariableFileIndex.VariableTypeInfo info : entry.getValue()) {
					if (!className.equals(info.ownerClass)) continue;
					if (info.isSimpleVar() && info.isLocal) continue;
					shape.add(info);
				}
				if (!shape.hasVisible()) continue;

				P3VariableFileIndex.VariableTypeInfo structuralVisible = shape.structuralVisible();
				if (structuralVisible == null) continue;

				Map<String, HashEntryInfo> fileHashKeys = attachHashEntryFiles(shape.hashKeys(), file);
				VisibleVariable raw = new VisibleVariable(
						varKey,
						cleanName,
						shape.className(),
						structuralVisible.ownerClass,
						file,
						structuralVisible.offset,
						structuralVisible.columns,
						shape.effectiveSourceVarKey(),
						structuralVisible.methodName,
						structuralVisible.targetClassName,
						fileHashKeys,
						shape.hashSourceVars(),
						structuralVisible.isAdditive,
						structuralVisible.receiverVarKey
				);

				VariableCompletionInfo resolved = analyzeResolvedVariable(
						raw,
						visibleFiles,
						file,
						Math.max(0, structuralVisible.offset + 1)
				);
				if (best == null || resolved.variable.offset > best.variable.offset) {
					best = resolved;
				}
			}
		}

		return best;
	}

	private @Nullable VariableCompletionInfo getClassGetterPropertyInfo(
			@NotNull String className,
			@NotNull String propertyName,
			@NotNull List<VirtualFile> visibleFiles
	) {
		java.util.Set<String> ownerCandidates = buildClassHierarchy(className);
		if (ownerCandidates.isEmpty()) {
			ownerCandidates = java.util.Collections.singleton(className);
		}

		P3MethodDeclaration getter = findGetterInOwners(propertyName, ownerCandidates, visibleFiles);
		if (getter == null) return null;

		P3MethodDocTypeResolver resolver = P3MethodDocTypeResolver.getInstance(project);
		P3ResolvedValue resolved = resolver.getMethodResolvedResult(
				getter.getName(),
				getter.getOwnerClass(),
				visibleFiles
		);
		VisibleVariable getterVariable = new VisibleVariable(
				propertyName,
				propertyName,
				resolved != null ? resolved.className : P3VariableFileIndex.UNKNOWN_TYPE,
				getter.getOwnerClass(),
				getter.getFile(),
				getter.getOffset(),
				resolved != null ? resolved.columns : null,
				resolved != null ? resolved.sourceVarKey : null,
				resolved != null ? resolved.methodName : null,
				resolved != null ? resolved.targetClassName : null,
				resolved != null ? attachHashEntryFiles(resolved.hashKeys, getter.getFile()) : null,
				resolved != null ? resolved.hashSourceVars : null,
				false,
				resolved != null ? resolved.receiverVarKey : null
		);
		return analyzeResolvedVariable(
				getterVariable,
				visibleFiles,
				getter.getFile(),
				Math.max(0, getter.getOffset() + 1)
		);
	}

	public @NotNull Map<String, String> getClassVariableTypes(
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles
	) {
		long t0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		Map<String, String> result = new HashMap<>();
		FileBasedIndex index = FileBasedIndex.getInstance();

		for (VirtualFile file : visibleFiles) {
			if (!file.isValid()) continue;

			var fileData = index.getFileData(P3VariableFileIndex.NAME, file, project);
			if (fileData.isEmpty()) continue;

			for (Map.Entry<String, List<P3VariableFileIndex.VariableTypeInfo>> entry : fileData.entrySet()) {
				String varKey = entry.getKey();
				List<P3VariableFileIndex.VariableTypeInfo> infos = entry.getValue();

				P3VariableFileIndex.VariableTypeInfo lastInfo = null;
				for (P3VariableFileIndex.VariableTypeInfo info : infos) {
					if (!className.equals(info.ownerClass)) continue;
					if (info.isSimpleVar() && info.isLocal) continue;
					if (lastInfo == null || info.offset > lastInfo.offset) {
						lastInfo = info;
					}
				}

				if (lastInfo == null) continue;

				String cleanName = varKey;
				if (varKey.startsWith("self.")) {
					cleanName = varKey.substring(5);
				} else if (varKey.startsWith("MAIN:")) {
					continue;
				}

				String typeName;
				if (lastInfo.isMethodCallType()) {
					P3ResolvedValue resolved = resolveIndexedMethodCall(lastInfo, file, visibleFiles);
					typeName = resolved != null ? resolved.className : null;
				} else {
					typeName = lastInfo.className;
				}
				if (typeName != null) {
					result.put(cleanName, typeName);
				}
			}
		}

		return result;
	}

	private static boolean isTemporaryVariableName(@NotNull String pureVarName) {
		return "exception".equals(pureVarName) || "match".equals(pureVarName);
	}

	private static @Nullable TemporaryScope findTemporaryVariableScope(
			@NotNull String text,
			@NotNull String pureVarName,
			int offset
	) {
		if ("exception".equals(pureVarName)) {
			return findTryExceptionScope(text, offset);
		}
		if ("match".equals(pureVarName)) {
			return findStringMatchScope(text, offset);
		}
		return null;
	}

	private static boolean isTemporaryVariableInfoVisibleAtCursor(
			@NotNull String text,
			@NotNull String pureVarName,
			int infoOffset,
			int cursorOffset
	) {
		TemporaryScope infoScope = findTemporaryVariableScope(text, pureVarName, infoOffset);
		if (infoScope == null) {
			return true;
		}

		TemporaryScope cursorScope = findTemporaryVariableScope(text, pureVarName, cursorOffset);
		if (cursorScope == null) {
			return false;
		}

		return infoScope.startOffset == cursorScope.startOffset
				&& infoScope.endOffset == cursorScope.endOffset;
	}

	// ===== Проверка видимости — ЕДИНСТВЕННЫЙ ИСТОЧНИК =====

	/**
	 * Проверяет видимость переменной из позиции курсора.
	 *
	 * Правила (parser3-scope-language.md §10):
	 * - Разные классы → не видна (кроме MAIN переменных)
	 * - Без locals: $var = $self.var, $MAIN:var — отдельно
	 * - С @OPTIONS locals: $var локальна, $self.var глобальна класса, $MAIN:var глобальна MAIN
	 * - С [locals] у метода: $var из этого метода локальна
	 */
	private static boolean isVariableVisible(
			@NotNull P3VariableFileIndex.VariableTypeInfo info,
			@NotNull CursorContext ctx,
			@Nullable String readPrefix
	) {
		String debugId = (info.varPrefix != null ? info.varPrefix : "") + "(" + info.className + ")@" + info.offset
				+ "[owner=" + info.ownerClass + " method=" + info.ownerMethod + "]";

		if (!Objects.equals(info.ownerClass, ctx.ownerClass)) {
			if (info.ownerClass == null) {
				if (readPrefix != null && !readPrefix.isEmpty() && !"MAIN:".equals(readPrefix)) {
					if (DEBUG_ALWAYS) System.out.println("[isVarVisible] " + debugId + " REJECT: MAIN var, readPrefix=" + readPrefix);
					return false;
				}
			} else if (ctx.classHierarchy.contains(info.ownerClass)) {
				if (DEBUG_ALWAYS) System.out.println("[isVarVisible] " + debugId + " ACCEPT: base class in hierarchy");
			} else {
				if (DEBUG_ALWAYS) System.out.println("[isVarVisible] " + debugId + " REJECT: ownerClass mismatch, ctx=" + ctx.ownerClass);
				return false;
			}
		}

		if (readPrefix != null && !readPrefix.isEmpty()) {
			if (!isInfoMatchesReadPrefix(info, readPrefix, ctx)) {
				return false;
			}
		}

		if (isInfoLocal(info, ctx.inheritedMainLocals)) {
			if (ctx.method == null) return false;
			return ctx.method.name.equals(info.ownerMethod)
					&& Objects.equals(ctx.ownerClass, info.ownerClass);
		}

		return true;
	}

	private static boolean isInfoLocal(
			@NotNull P3VariableFileIndex.VariableTypeInfo info,
			boolean inheritedMainLocals
	) {
		if (info.isLocal) return true;
		return inheritedMainLocals
				&& info.ownerClass == null
				&& info.ownerMethod != null
				&& info.isSimpleVar();
	}

	private static boolean isInfoMatchesReadPrefix(
			@NotNull P3VariableFileIndex.VariableTypeInfo info,
			@NotNull String readPrefix,
			@NotNull CursorContext ctx
	) {
		boolean inMain = (ctx.ownerClass == null);
		boolean readingMainVar = "MAIN:".equals(readPrefix);
		boolean readingSelfVar = "self.".equals(readPrefix);

		if (inMain) {
			if (ctx.hasLocals) {
				if (!readingMainVar && !readingSelfVar) {
					if (!info.isSimpleVar()) return false;
				} else {
					if (info.isSimpleVar()) return false;
				}
			}
		} else {
			if (readingMainVar) {
				if (!info.isMainVar()) return false;
			} else if (ctx.hasLocals) {
				if (!readingSelfVar) {
					if (!info.isSimpleVar()) return false;
				} else {
					if (!info.isSelfVar()) return false;
				}
			}
		}
		return true;
	}

	/**
	 * Проверяет, находится ли курсор внутри правой части присваивания переменной.
	 *
	 * Пример: $var[^var.split[,]] — курсор внутри [...], переменная ещё не перезаписана.
	 * assignmentOffset — offset начала $var, cursorOffset — позиция курсора.
	 *
	 * Ищем открывающую скобку ([, ( или {) после имени переменной,
	 * затем парную закрывающую. Если курсор между ними — true.
	 */
	private static boolean isCursorInsideAssignmentRHS(
			@NotNull String text, int assignmentOffset, int cursorOffset
	) {
		int len = text.length();
		int pos = assignmentOffset;

		if (pos >= len || text.charAt(pos) != '$') return false;
		pos++;

		if (pos + 5 <= len && text.substring(pos, pos + 5).equals("self.")) {
			pos += 5;
		} else if (pos + 5 <= len && text.substring(pos, pos + 5).equals("MAIN:")) {
			pos += 5;
		}

		while (pos < len && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) pos++;
		while (pos < len && Character.isWhitespace(text.charAt(pos))) pos++;

		if (pos >= len) return false;
		char openBracket = text.charAt(pos);
		if (openBracket != '[' && openBracket != '(' && openBracket != '{') return false;
		if (cursorOffset <= pos) return false;

		int closePos = P3VariableParser.findMatchingBracket(text, pos, len, openBracket);

		if (closePos == -1) return true;
		return cursorOffset > pos && cursorOffset <= closePos;
	}

	// ===== Вспомогательные =====

	/**
	 * Возвращает все имена параметров метода из его сигнатуры @method[p1;p2;...].
	 */
	private static @NotNull java.util.Set<String> getMethodParams(
			@NotNull Parser3ClassUtils.MethodBoundary method,
			@NotNull String text
	) {
		java.util.Set<String> params = new java.util.HashSet<>();
		int bracketStart = text.indexOf('[', method.start);
		if (bracketStart < 0) return params;
		int bracketEnd = text.indexOf(']', bracketStart);
		if (bracketEnd < 0) return params;
		String paramsStr = text.substring(bracketStart + 1, bracketEnd).trim();
		if (paramsStr.isEmpty()) return params;
		for (String p : paramsStr.split(";")) {
			String trimmed = p.trim();
			if (!trimmed.isEmpty()) params.add(trimmed);
		}
		return params;
	}

	private static @NotNull List<String> getMethodParamsInOrder(
			@NotNull Parser3ClassUtils.MethodBoundary method,
			@NotNull String text
	) {
		List<String> params = new ArrayList<>();
		int bracketStart = text.indexOf('[', method.start);
		if (bracketStart < 0) return params;
		int bracketEnd = text.indexOf(']', bracketStart);
		if (bracketEnd < 0) return params;
		String paramsStr = text.substring(bracketStart + 1, bracketEnd).trim();
		if (paramsStr.isEmpty()) return params;
		for (String p : paramsStr.split(";")) {
			String trimmed = p.trim();
			if (!trimmed.isEmpty()) params.add(trimmed);
		}
		return params;
	}

	private @Nullable String findParameterType(@NotNull String varName, @NotNull VirtualFile file, int offset) {
		String text = readFileTextSmart(file);
		if (text == null) return null;

		List<Parser3ClassUtils.ClassBoundary> cb = Parser3ClassUtils.findClassBoundaries(text);
		List<Parser3ClassUtils.MethodBoundary> mb = Parser3ClassUtils.findMethodBoundaries(text, cb);
		Parser3ClassUtils.MethodBoundary currentMethod = Parser3ClassUtils.findOwnerMethod(offset, mb);
		if (currentMethod == null) return null;

		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<ru.artlebedev.parser3.model.P3MethodDeclaration> methods = methodIndex.findInFile(file);
		for (ru.artlebedev.parser3.model.P3MethodDeclaration method : methods) {
			if (method.getName().equals(currentMethod.name) && method.getOffset() == currentMethod.start) {
				String paramType = method.getParamType(varName);
				if (paramType != null) return paramType;
				break;
			}
		}
		return null;
	}

	private @Nullable String resolveClassName(
			@NotNull P3VariableFileIndex.VariableTypeInfo info,
			@NotNull VirtualFile file,
			@NotNull List<VirtualFile> visibleFiles
	) {
		if (info.isMethodCallType()) {
			P3ResolvedValue resolved = resolveIndexedMethodCall(info, file, visibleFiles);
			return resolved != null ? resolved.className : null;
		}
		return info.className;
	}

	private @Nullable P3ResolvedValue resolveIndexedMethodCall(
			@NotNull P3VariableFileIndex.VariableTypeInfo info,
			@NotNull VirtualFile file,
			@NotNull List<VirtualFile> visibleFiles
	) {
		String ownerClass = info.targetClassName;
		if ((ownerClass == null || ownerClass.isEmpty())
				&& info.receiverVarKey != null
				&& !info.receiverVarKey.isEmpty()) {
			ownerClass = findVariableClassInFiles(info.receiverVarKey, visibleFiles, file, info.offset);
		}
		if (info.receiverVarKey != null && (ownerClass == null || ownerClass.isEmpty())) {
			return null;
		}

		P3MethodDocTypeResolver resolver = P3MethodDocTypeResolver.getInstance(project);
		return resolver.getMethodResolvedResult(info.methodName, ownerClass, visibleFiles);
	}

	public static @Nullable String extractVarPrefix(@NotNull String varKey) {
		if (varKey.startsWith("self.")) return "self.";
		if (varKey.startsWith("MAIN:")) return "MAIN:";
		if (varKey.startsWith("BASE:")) return "BASE:";
		return null;
	}

	public static @NotNull String extractPureVarName(@NotNull String varKey) {
		if (varKey.startsWith("self.")) return varKey.substring(5);
		if (varKey.startsWith("MAIN:")) return varKey.substring(5);
		if (varKey.startsWith("BASE:")) return varKey.substring(5);
		return varKey;
	}

	private static boolean hasLocalsForContext(@NotNull String text, @Nullable String ownerClass,
											   @NotNull List<Parser3ClassUtils.ClassBoundary> classBoundaries) {
		if (ownerClass == null) {
			return Parser3ClassUtils.hasMainLocals(text, classBoundaries);
		}
		for (Parser3ClassUtils.ClassBoundary cb : classBoundaries) {
			if (cb.name.equals(ownerClass)) return Parser3ClassUtils.hasClassLocals(text, cb);
		}
		return false;
	}

	private static boolean hasLocalsForContextBefore(@NotNull String text, @Nullable String ownerClass,
													 @NotNull List<Parser3ClassUtils.ClassBoundary> classBoundaries,
													 int offset) {
		if (ownerClass == null) {
			return Parser3ClassUtils.hasMainLocalsBefore(text, classBoundaries, offset);
		}
		for (Parser3ClassUtils.ClassBoundary cb : classBoundaries) {
			if (cb.name.equals(ownerClass)) return Parser3ClassUtils.hasClassLocalsBefore(text, cb, offset);
		}
		return false;
	}

	private static @Nullable Parser3ClassUtils.MethodBoundary findMethodByNameAndClass(
			@NotNull List<Parser3ClassUtils.MethodBoundary> boundaries,
			@NotNull String methodName, @Nullable String ownerClass, int offset) {
		for (Parser3ClassUtils.MethodBoundary mb : boundaries) {
			if (mb.name.equals(methodName) && Objects.equals(mb.ownerClass, ownerClass)
					&& offset >= mb.start && offset < mb.end) {
				return mb;
			}
		}
		return null;
	}

	/**
	 * Параметр метода с типом из документации.
	 */
	public static final class MethodParameter {
		public final @NotNull String name;
		public final @Nullable String type; // из документации # $param(type)

		MethodParameter(@NotNull String name, @Nullable String type) {
			this.name = name;
			this.type = type;
		}
	}

	/**
	 * Возвращает параметры текущего метода (в котором находится offset) с типами из документации.
	 * Используется для автокомплита переменных — параметры показываются как переменные.
	 */
	public @NotNull List<MethodParameter> getMethodParameters(@NotNull VirtualFile file, int offset) {
		String text = readFileTextSmart(file);
		if (text == null) return java.util.Collections.emptyList();

		List<Parser3ClassUtils.ClassBoundary> cb = Parser3ClassUtils.findClassBoundaries(text);
		List<Parser3ClassUtils.MethodBoundary> mb = Parser3ClassUtils.findMethodBoundaries(text, cb);
		Parser3ClassUtils.MethodBoundary currentMethod = Parser3ClassUtils.findOwnerMethod(offset, mb);
		if (currentMethod == null) return java.util.Collections.emptyList();

		// Парсим параметры из @methodName[param1;param2]
		int bracketStart = text.indexOf('[', currentMethod.start);
		if (bracketStart < 0) return java.util.Collections.emptyList();
		int bracketEnd = text.indexOf(']', bracketStart);
		if (bracketEnd < 0) return java.util.Collections.emptyList();

		String paramsStr = text.substring(bracketStart + 1, bracketEnd).trim();
		if (paramsStr.isEmpty()) return java.util.Collections.emptyList();

		// Получаем типы параметров из документации метода
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<ru.artlebedev.parser3.model.P3MethodDeclaration> methods = methodIndex.findInFile(file);
		ru.artlebedev.parser3.model.P3MethodDeclaration methodDecl = null;
		for (ru.artlebedev.parser3.model.P3MethodDeclaration m : methods) {
			if (m.getName().equals(currentMethod.name) && m.getOffset() == currentMethod.start) {
				methodDecl = m;
				break;
			}
		}

		List<MethodParameter> result = new java.util.ArrayList<>();
		for (String param : paramsStr.split(";")) {
			String paramName = param.trim();
			if (paramName.isEmpty()) continue;
			String paramType = methodDecl != null ? methodDecl.getParamType(paramName) : null;
			result.add(new MethodParameter(paramName, paramType));
		}
		return result;
	}

	private @Nullable String readFileTextSmart(@NotNull VirtualFile file) {
		com.intellij.openapi.editor.Document doc =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(file);
		if (doc != null) return doc.getText();
		return readFileText(file);
	}

	private static @Nullable String readFileText(@NotNull VirtualFile file) {
		try {
			byte[] content = file.contentsToByteArray();
			return new String(content, file.getCharset());
		} catch (Exception e) {
			return null;
		}
	}
}
