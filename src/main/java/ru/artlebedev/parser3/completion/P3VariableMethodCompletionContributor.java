package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.index.HashEntryInfo;
import ru.artlebedev.parser3.index.P3MethodIndex;
import ru.artlebedev.parser3.index.P3VariableIndex;
import ru.artlebedev.parser3.index.P3VariableFileIndex;
import ru.artlebedev.parser3.lang.P3UserMethodLookupObject;
import ru.artlebedev.parser3.lang.Parser3BuiltinMethods;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.utils.Parser3BuiltinStaticPropertyUsageUtils;
import ru.artlebedev.parser3.utils.Parser3ChainUtils;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Утилиты для автокомплита методов классов после ^var.
 * Добавление элементов вызывается из Parser3CompletionContributor.
 *
 * Поддерживает:
 * - Пользовательские классы (с большой буквы)
 * - Встроенные классы Parser3 (table, hash, date, file, etc.)
 */
public final class P3VariableMethodCompletionContributor {

	private static final boolean DEBUG = false;
	private static final boolean DEBUG_PERF = false;
	private static final java.util.Map<String, ExceptionFieldMeta> EXCEPTION_FIELD_META = createExceptionFieldMeta();

	private P3VariableMethodCompletionContributor() {}

	private static final class ExceptionFieldMeta {
		final @NotNull String typeText;
		final @NotNull String description;

		ExceptionFieldMeta(@NotNull String typeText, @NotNull String description) {
			this.typeText = typeText;
			this.description = description;
		}
	}

	/**
	 * ЕДИНАЯ ТОЧКА ВХОДА для автокомплита после точки (^var. и $var.).
	 *
	 * Режимы:
	 * - caretDot = true:  ^var.  → методы + переменные класса (с точкой для цепочки) + колонки
	 * - caretDot = false: $var.  → только свойства (переменные + @GET_ геттеры) + колонки
	 *
	 * @param project     текущий проект
	 * @param varKey      ключ переменной: "var", "self.var", "MAIN:var", "var.prop" (цепочка)
	 * @param currentFile текущий файл
	 * @param cursorOffset смещение курсора
	 * @param result      куда добавлять результаты
	 * @param caretDot    true = ^var. (методы), false = $var. (свойства)
	 */
	public static void completeVariableDot(
			@NotNull Project project,
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result,
			boolean caretDot
	) {
		completeVariableDot(project, varKey, currentFile, cursorOffset, result, caretDot, null, null);
	}

	/**
	 * Оборачивает InsertHandler, добавляя суффикс после вставки.
	 * Используется для ${...} — добавляет } после выбранного ключа/колонки.
	 */
	public static com.intellij.codeInsight.completion.InsertHandler<com.intellij.codeInsight.lookup.LookupElement>
	wrapInsertHandlerWithSuffix(
			@NotNull com.intellij.codeInsight.completion.InsertHandler<com.intellij.codeInsight.lookup.LookupElement> base,
			@NotNull String suffix
	) {
		return (context, item) -> {
			base.handleInsert(context, item);
			com.intellij.openapi.editor.Document doc = context.getDocument();
			int tail = context.getTailOffset();
			doc.insertString(tail, suffix);
			context.getEditor().getCaretModel().moveToOffset(tail + suffix.length());
		};
	}

	/**
	 * @param closingSuffix если не null — добавляется в InsertHandler ключей хеша и колонок таблицы.
	 *                      Используется для ${data.ke → при выборе ключа добавляет }.
	 */
	public static void completeVariableDot(
			@NotNull Project project,
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result,
			boolean caretDot,
			@Nullable String closingSuffix
	) {
		completeVariableDot(project, varKey, currentFile, cursorOffset, result, caretDot, closingSuffix, null);
	}

	public static void completeVariableDot(
			@NotNull Project project,
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result,
			boolean caretDot,
			@Nullable String closingSuffix,
			@Nullable String currentFileText
	) {
		String typedPrefix = result.getPrefixMatcher().getPrefix();
		int resolveOffset = typedPrefix.isEmpty()
				? cursorOffset
				: Math.max(0, cursorOffset - typedPrefix.length());
		completeVariableDot(project, varKey, currentFile, cursorOffset, result, caretDot, closingSuffix, currentFileText, resolveOffset);
	}

	public static void completeVariableDot(
			@NotNull Project project,
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result,
			boolean caretDot,
			@Nullable String closingSuffix,
			@Nullable String currentFileText,
			int resolveOffset
	) {
		P3VariableIndex varIndex = P3VariableIndex.getInstance(project);
		int finalResolveOffset = Math.max(0, resolveOffset);
		varIndex.withSharedResolveCache(() -> {
			completeVariableDotWithCache(
					project,
					varIndex,
					varKey,
					currentFile,
					cursorOffset,
					result,
					caretDot,
					closingSuffix,
					currentFileText,
					finalResolveOffset
			);
			return null;
		});
	}

	private static void completeVariableDotWithCache(
			@NotNull Project project,
			@NotNull P3VariableIndex varIndex,
			@NotNull String varKey,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result,
			boolean caretDot,
			@Nullable String closingSuffix,
			@Nullable String currentFileText,
			int resolveOffset
	) {
		long t0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		String normalizedVarKey = Parser3ChainUtils.normalizeDynamicSegments(varKey);
		String typedPrefix = result.getPrefixMatcher().getPrefix();

		String className = null;
		java.util.List<String> cols = null;
		java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> resolvedHashKeys = null;
		ru.artlebedev.parser3.index.HashEntryInfo resolvedHashEntry = null;
		java.util.List<VirtualFile> visibleFiles = null;
		java.util.List<VirtualFile> classSearchFiles = null;
		P3VariableIndex.ChainResolveInfo resolvedChainInfo = null;

		String chainPart = normalizedVarKey;
		if (chainPart.startsWith("self.")) chainPart = chainPart.substring(5);
		else if (chainPart.startsWith("MAIN:")) chainPart = chainPart.substring(5);

		long visibleFilesStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		P3ScopeContext scopeContext = new P3ScopeContext(project, currentFile, resolveOffset);
		visibleFiles = scopeContext.getVariableSearchFiles();
		if (DEBUG_PERF) {
			System.out.println("[VarMethodCompl.PERF] completeVariableDot visibleFiles: "
					+ (System.currentTimeMillis() - visibleFilesStart) + "ms"
					+ " count=" + visibleFiles.size()
					+ " varKey=" + varKey
					+ " file=" + currentFile.getName()
					+ " offset=" + resolveOffset);
		}
		long t1 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		resolvedChainInfo =
				varIndex.resolveEffectiveChain(normalizedVarKey, visibleFiles, currentFile, resolveOffset);
		if (resolvedChainInfo != null) {
			className = resolvedChainInfo.className;
			cols = resolvedChainInfo.columns;
			resolvedHashKeys = resolvedChainInfo.hashKeys;
			resolvedHashEntry = resolvedChainInfo.hashEntry;
		}

		if (shouldRetryClassAwareResolve(resolvedChainInfo, className, chainPart)) {
			classSearchFiles = ensureClassSearchFiles(scopeContext, classSearchFiles);
			P3VariableIndex.ChainResolveInfo classAwareChainInfo =
					varIndex.resolveEffectiveChain(normalizedVarKey, visibleFiles, classSearchFiles, currentFile, resolveOffset);
			if (classAwareChainInfo != null || resolvedChainInfo == null) {
				resolvedChainInfo = classAwareChainInfo;
				className = classAwareChainInfo != null ? classAwareChainInfo.className : null;
				cols = classAwareChainInfo != null ? classAwareChainInfo.columns : null;
				resolvedHashKeys = classAwareChainInfo != null ? classAwareChainInfo.hashKeys : null;
				resolvedHashEntry = classAwareChainInfo != null ? classAwareChainInfo.hashEntry : null;
			}
		}
		if (DEBUG_PERF) System.out.println("[VarMethodCompl.PERF] completeVariableDot resolveEffectiveChain: "
				+ (System.currentTimeMillis() - t1) + "ms, varKey=" + varKey + " → " + className);

		if (isBuiltinStaticPropertyKey(normalizedVarKey)) {
			// builtinClass:field — это обращение к статическому свойству, а не обычная переменная класса.
			// Если тип свойства неизвестен, ниже должен сработать общий fallback для неизвестного типа.
			className = resolveBuiltinStaticPropertyType(normalizedVarKey, currentFileText, resolveOffset);
			if (className != null && DEBUG) {
				System.out.println("[completeVariableDot] builtinProp fallback: " + normalizedVarKey + " → " + className);
			}
		}

		if (DEBUG) System.out.println("[completeVariableDot] varKey=" + varKey + " caretDot=" + caretDot
				+ " className=" + className + " file=" + currentFile.getName() + " offset=" + cursorOffset
				+ " resolveOffset=" + resolveOffset);

		if (className != null && !className.isEmpty()) {
			boolean userClassName = isUserClassName(className);
			java.util.List<VirtualFile> memberSearchFiles = userClassName
					? ensureClassSearchFiles(scopeContext, classSearchFiles)
					: java.util.Collections.emptyList();
			// Дополнительные ключи собираем до свойств класса, чтобы одинаковое поле не показывалось дважды:
			// $obj.user из класса и $obj.user.x[...] как additive-цепочка должны дать один вариант user.
			java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> hashKeys = resolvedHashKeys;
			// Для цепочек ($data.key.) — ищем вложенные ключи.
			// Обогащаем каждый ключ: если для "varKey.keyName" есть псевдонимы — добавляем их ключи как nestedKeys.
			if (hashKeys != null && !hashKeys.isEmpty()) {
				hashKeys = varIndex.enrichWithAliasKeys(hashKeys, normalizedVarKey, currentFile, resolveOffset);
			}
			java.util.Set<String> getterPropertyNames = userClassName
					? collectGetterPropertyNames(project, className, memberSearchFiles)
					: java.util.Collections.emptySet();
			if (hashKeys != null && !getterPropertyNames.isEmpty()) {
				hashKeys = new java.util.LinkedHashMap<>(hashKeys);
				for (String getterName : getterPropertyNames) {
					hashKeys.remove(getterName);
				}
			}
			java.util.Set<String> additivePropertyNames = new java.util.HashSet<>();
			if (hashKeys != null && !hashKeys.isEmpty()) {
				for (String keyName : hashKeys.keySet()) {
					if (!"*".equals(keyName)) {
						additivePropertyNames.add(keyName);
					}
				}
			}

			if (caretDot) {
				// ^var. — методы + переменные класса
				addClassMethods(project, className, currentFile, resolveOffset, result, memberSearchFiles);
			} else {
				// $var. — только свойства (переменные + @GET_ геттеры)
				addClassProperties(project, className, memberSearchFiles, result, additivePropertyNames);
			}

			// Fallback: колонки из хеш-цепочки ($data.key.list. → columns из HashEntryInfo)
			if ("table".equals(className) && cols == null && resolvedHashEntry != null) {
				if (resolvedHashEntry.columns != null) {
					cols = resolvedHashEntry.columns;
					if (DEBUG) System.out.println("[completeVariableDot] table columns from hash chain: " + cols);
				}
			}
			if ("table".equals(className)) {
				if (DEBUG) System.out.println("[completeVariableDot] table columns for varKey=" + varKey + ": " + cols);
				addTableColumns(cols, result, caretDot, closingSuffix);
			}

			if (hashKeys != null && !hashKeys.isEmpty()) {
				if (DEBUG) System.out.println("[completeVariableDot] hash/additive keys for varKey=" + varKey + ": " + hashKeys.keySet());
				addHashKeys(hashKeys, result, caretDot, className, closingSuffix, normalizedVarKey, currentFileText, cursorOffset);
			}
		} else if (caretDot) {
			// ^var. неизвестного типа — встроенные методы объектов (fallback)
			addBuiltinObjectMethods(result);
		}

		// Fallback для цепочек с неизвестным типом: если findHashKeysForChain нашёл ключи
		// (например, тип из ^some_method[] неизвестен, но есть foreach с additive ключами)
		if ((className == null || className.isEmpty()) && chainPart.contains(".")) {
			java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> hashKeys =
					resolvedChainInfo != null ? resolvedChainInfo.hashKeys : null;
			if (hashKeys != null && !hashKeys.isEmpty()) {
				if (DEBUG) System.out.println("[completeVariableDot] fallback hash keys for unknown type varKey=" + varKey + ": " + hashKeys.keySet());
				addHashKeys(hashKeys, result, caretDot, "hash", closingSuffix, normalizedVarKey, currentFileText, cursorOffset);
			}
		}
		// $var. неизвестного типа — ничего не добавляем

		// Пользовательские шаблоны с prefix="." — показываем для ^var. контекста
		if (caretDot) {
			ru.artlebedev.parser3.lang.Parser3CompletionContributor.fillUserTemplates(result, ".");
		}
		if (DEBUG_PERF) System.out.println("[VarMethodCompl.PERF] completeVariableDot TOTAL: " + (System.currentTimeMillis() - t0) + "ms"
				+ " visibleFiles=" + (visibleFiles != null ? visibleFiles.size() : 0)
				+ " classSearchFiles=" + (classSearchFiles != null ? classSearchFiles.size() : 0));
	}

	private static @Nullable String resolveBuiltinStaticPropertyType(
			@NotNull String normalizedVarKey,
			@Nullable String currentFileText,
			int cursorOffset
	) {
		int colonPos = normalizedVarKey.indexOf(':');
		if (colonPos <= 0 || colonPos >= normalizedVarKey.length() - 1) {
			return null;
		}

		String clsName = normalizedVarKey.substring(0, colonPos);
		String fieldName = normalizedVarKey.substring(colonPos + 1);
		for (Parser3BuiltinMethods.BuiltinCallable prop : Parser3BuiltinMethods.getStaticPropertiesForClass(clsName)) {
			if (prop.name.equals(fieldName) && prop.returnType != null) {
				return prop.returnType;
			}
		}

		if (currentFileText != null
				&& Parser3BuiltinStaticPropertyUsageUtils.collectPropertyNames(currentFileText, clsName, cursorOffset).contains(fieldName)) {
			String localTypeText = Parser3BuiltinStaticPropertyUsageUtils.getLocalPropertyTypeText(clsName);
			if (Parser3BuiltinMethods.isBuiltinClass(localTypeText)) {
				return localTypeText;
			}
		}
		return null;
	}

	private static boolean isBuiltinStaticPropertyKey(@NotNull String normalizedVarKey) {
		int colonPos = normalizedVarKey.indexOf(':');
		if (colonPos <= 0 || colonPos >= normalizedVarKey.length() - 1) {
			return false;
		}
		String clsName = normalizedVarKey.substring(0, colonPos);
		return Parser3BuiltinMethods.supportsStaticPropertyAccess(clsName);
	}

	private static boolean shouldRetryClassAwareResolve(
			@Nullable P3VariableIndex.ChainResolveInfo resolvedChainInfo,
			@Nullable String className,
			@NotNull String chainPart
	) {
		if (resolvedChainInfo == null) {
			return chainPart.contains(".");
		}
		if (isUserClassName(className)) {
			return true;
		}
		return hashKeysNeedClassSearch(resolvedChainInfo.hashKeys);
	}

	private static boolean isUserClassName(@Nullable String className) {
		return className != null
				&& !className.isEmpty()
				&& !Parser3BuiltinMethods.isBuiltinClass(className)
				&& !P3VariableFileIndex.UNKNOWN_TYPE.equals(className)
				&& !P3VariableFileIndex.VariableTypeInfo.METHOD_CALL_MARKER.equals(className);
	}

	private static boolean hashKeysNeedClassSearch(@Nullable java.util.Map<String, HashEntryInfo> hashKeys) {
		if (hashKeys == null || hashKeys.isEmpty()) {
			return false;
		}
		for (HashEntryInfo entry : hashKeys.values()) {
			if (entry == null) continue;
			if (entry.methodName != null || entry.targetClassName != null || entry.receiverVarKey != null) {
				return true;
			}
			if (hashKeysNeedClassSearch(entry.nestedKeys)) {
				return true;
			}
		}
		return false;
	}

	private static @NotNull java.util.List<VirtualFile> ensureClassSearchFiles(
			@NotNull P3ScopeContext scopeContext,
			@Nullable java.util.List<VirtualFile> classSearchFiles
	) {
		return classSearchFiles != null ? classSearchFiles : scopeContext.getClassSearchFiles();
	}

	/**
	 * Автокомплит для известного типа — без резолвинга переменной.
	 * Используется для ^form:fields. где тип fields=hash уже известен из справочника.
	 */
	public static void completeForKnownType(
			@NotNull Project project,
			@NotNull String className,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result
	) {
		if (DEBUG) System.out.println("[completeForKnownType] className=" + className);

		// Методы класса (^var.method[])
		addClassMethods(project, className, currentFile, cursorOffset, result);
		// Свойства встроенного класса
		addBuiltinClassProperties(className, result);
		// Пользовательские шаблоны с "."
		ru.artlebedev.parser3.lang.Parser3CompletionContributor.fillUserTemplates(result, ".");
	}

	/**
	 * Добавляет свойства (поля) встроенного класса в автокомплит.
	 * Например: $form:fields, $date:year, $env:PARSER_VERSION
	 */
	private static void addBuiltinClassProperties(
			@NotNull String className,
			@NotNull CompletionResultSet result
	) {
		java.util.List<Parser3BuiltinMethods.BuiltinCallable> properties =
				Parser3BuiltinMethods.getPropertiesForClass(className);

		for (Parser3BuiltinMethods.BuiltinCallable prop : properties) {
			ru.artlebedev.parser3.lang.Parser3DocLookupObject docObject =
					new ru.artlebedev.parser3.lang.Parser3DocLookupObject(
							className + "." + prop.name, prop.url, prop.description);

			String typeText = prop.returnType != null ? prop.returnType : className;

			LookupElementBuilder element = LookupElementBuilder
					.create(docObject, prop.name)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(typeText, true)
					.withTailText(prop.description != null ? " " + prop.description : null, true)
					.withInsertHandler(P3VariableInsertHandler.INSTANCE);

			result.addElement(PrioritizedLookupElement.withPriority(element, 95));
		}
	}

	/**
	 * Добавляет встроенные методы объектов (fallback когда тип переменной неизвестен).
	 * Используется для ^var. когда тип не удалось определить.
	 */
	public static void addBuiltinObjectMethods(@NotNull CompletionResultSet result) {
		for (Parser3BuiltinMethods.CaretConstructor item : Parser3BuiltinMethods.getAllMethodsMeta()) {
			String name = item.callable.name;
			String description = item.callable.description;
			String suffix = item.callable.suffix;
			String url = item.callable.url;

			ru.artlebedev.parser3.lang.Parser3DocLookupObject docObject =
					new ru.artlebedev.parser3.lang.Parser3DocLookupObject("." + name, url, description);

			result.addElement(
					LookupElementBuilder.create(docObject, name)
							.withIcon(Parser3Icons.File)
							.withPresentableText("." + name + suffix)
							.withTailText(" " + description, true)
							.withInsertHandler(P3MethodInsertHandler.withSuffix(suffix))
			);
		}
	}

	/**
	 * Добавляет только свойства класса (переменные + @GET_ геттеры) в автокомплит.
	 * Используется для контекста $var. — где доступны только свойства, не методы.
	 */
	public static void addClassProperties(
			@NotNull Project project,
			@NotNull String className,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result,
			@NotNull java.util.Set<String> hiddenPropertyNames
	) {
		addClassProperties(
				project,
				className,
				P3CompletionUtils.getVisibleFilesForClasses(project, currentFile, cursorOffset),
				result,
				hiddenPropertyNames);
	}

	public static void addClassProperties(
			@NotNull Project project,
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull CompletionResultSet result,
			@NotNull java.util.Set<String> hiddenPropertyNames
	) {
		long t0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		// Для встроенных классов — показываем свойства из справочника
		if (Parser3BuiltinMethods.isBuiltinClass(className)) {
			addBuiltinClassProperties(className, result);
			return;
		}

		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		// Собираем методы класса (для @GET_ геттеров и переменных)
		List<P3MethodDeclaration> methods = new ArrayList<>();
		collectClassMethods(className, methodIndex, classIndex, visibleFiles, methods, new ArrayList<>());

		// Добавляем @GET_ свойства
		for (P3MethodDeclaration method : methods) {
			if (!method.isGetter()) continue;

			String propName = method.getName();
			if (hiddenPropertyNames.contains(propName)) continue;

			String tailText = "";
			if (method.getDocText() != null) {
				String firstLine = getFirstDocLine(method.getDocText());
				if (firstLine != null && !firstLine.isEmpty()) {
					tailText = " " + firstLine;
				}
			}

			// Тип из $result если есть
			String typeText = method.getResultType();
			boolean needsDot = shouldAppendDotForPropertyType(typeText);
			String insertText = needsDot ? propName + "." : propName;

			LookupElementBuilder element = LookupElementBuilder
					.create(insertText)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(typeText != null ? typeText : className, true)
					.withTailText(tailText.isEmpty() ? null : tailText, true)
					.withPresentableText(insertText)
					.withInsertHandler(needsDot ? createDotInsertHandler() : P3VariableInsertHandler.INSTANCE);

			result.addElement(PrioritizedLookupElement.withPriority(element, 95));
		}

		// Добавляем переменные класса ($self.var); структурные типы продолжаются точкой.
		addClassVariables(project, className, classIndex, visibleFiles, result, methods, false, hiddenPropertyNames);
		if (DEBUG_PERF) System.out.println("[VarMethodCompl.PERF] addClassProperties('" + className + "'): " + (System.currentTimeMillis() - t0) + "ms");
	}

	/**
	 * Добавляет только @GET_ свойства класса (без переменных).
	 * Используется в $self. и $var контексте, где переменные уже добавлены отдельно.
	 */
	public static void addGetterProperties(
			@NotNull Project project,
			@NotNull String className,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result
	) {
		addGetterProperties(
				project,
				className,
				P3CompletionUtils.getVisibleFilesForClasses(project, currentFile, cursorOffset),
				result);
	}

	public static void addGetterProperties(
			@NotNull Project project,
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull CompletionResultSet result
	) {
		long t0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		if (Parser3BuiltinMethods.isBuiltinClass(className)) {
			return;
		}

		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		List<P3MethodDeclaration> methods = new ArrayList<>();
		collectClassMethods(className, methodIndex, classIndex, visibleFiles, methods, new ArrayList<>());

		for (P3MethodDeclaration method : methods) {
			if (!method.isGetter()) continue;

			String propName = method.getName();
			String tailText = "";
			if (method.getDocText() != null) {
				String firstLine = getFirstDocLine(method.getDocText());
				if (firstLine != null && !firstLine.isEmpty()) {
					tailText = " " + firstLine;
				}
			}

			String typeText = method.getResultType();
			boolean needsDot = shouldAppendDotForPropertyType(typeText);
			String insertText = needsDot ? propName + "." : propName;

			LookupElementBuilder element = LookupElementBuilder
					.create(insertText)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(typeText != null ? typeText : className, true)
					.withTailText(tailText.isEmpty() ? null : tailText, true)
					.withPresentableText(insertText)
					.withInsertHandler(needsDot ? createDotInsertHandler() : P3VariableInsertHandler.INSTANCE);

			result.addElement(PrioritizedLookupElement.withPriority(element, 95));
		}
		if (DEBUG_PERF) System.out.println("[VarMethodCompl.PERF] addGetterProperties('" + className + "'): " + (System.currentTimeMillis() - t0) + "ms");
	}

	/**
	 * Собирает имена свойств, которые Parser3 отдаёт через @GET_.
	 * Нужен вызывающим контрибьюторам, чтобы синтетическая read-chain не дублировала getter.
	 */
	public static @NotNull java.util.Set<String> collectGetterPropertyNames(
			@NotNull Project project,
			@NotNull String className,
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		return collectGetterPropertyNames(
				project,
				className,
				P3CompletionUtils.getVisibleFilesForClasses(project, currentFile, cursorOffset));
	}

	public static @NotNull java.util.Set<String> collectGetterPropertyNames(
			@NotNull Project project,
			@NotNull String className,
			@NotNull List<VirtualFile> visibleFiles
	) {
		if (Parser3BuiltinMethods.isBuiltinClass(className)) {
			return java.util.Collections.emptySet();
		}

		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		List<P3MethodDeclaration> methods = new ArrayList<>();
		collectClassMethods(className, methodIndex, classIndex, visibleFiles, methods, new ArrayList<>());

		java.util.Set<String> result = new java.util.LinkedHashSet<>();
		for (P3MethodDeclaration method : methods) {
			if (method.isGetter()) {
				result.add(method.getName());
			}
		}
		return result;
	}

	/**
	 * Добавляет методы класса в автокомплит.
	 * Определяет тип класса (встроенный или пользовательский) и вызывает соответствующий метод.
	 */
	public static void addClassMethods(
			@NotNull Project project,
			@NotNull String className,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result
	) {
		addClassMethods(
				project,
				className,
				currentFile,
				cursorOffset,
				result,
				P3CompletionUtils.getVisibleFilesForClasses(project, currentFile, cursorOffset));
	}

	private static void addClassMethods(
			@NotNull Project project,
			@NotNull String className,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result,
			@NotNull List<VirtualFile> visibleFiles
	) {
		// Проверяем, встроенный ли это класс
		if (Parser3BuiltinMethods.isBuiltinClass(className)) {
			addBuiltinClassMethods(className, result);
		} else {
			addUserClassMethods(project, className, currentFile, cursorOffset, result, visibleFiles);
		}
	}

	/**
	 * Добавляет методы встроенного класса Parser3 в автокомплит.
	 */
	private static void addBuiltinClassMethods(
			@NotNull String className,
			@NotNull CompletionResultSet result
	) {
		List<Parser3BuiltinMethods.BuiltinCallable> methods = Parser3BuiltinMethods.getMethodsForClass(className);

		for (Parser3BuiltinMethods.BuiltinCallable method : methods) {
			String methodName = method.name;
			String suffix = method.suffix != null ? method.suffix : "[]";

			// Формируем tailText с описанием
			String tailText = suffix;
			if (method.description != null && !method.description.isEmpty()) {
				tailText += " " + method.description;
			}

			LookupElementBuilder element = LookupElementBuilder
					.create(methodName)
					.withIcon(Parser3Icons.FileMethod)
					.withTypeText(className, true)
					.withTailText(tailText.isEmpty() ? null : " " + tailText, true)
					.withInsertHandler(P3MethodInsertHandler.withSuffix(suffix));

			// Высокий приоритет для встроенных методов
			result.addElement(PrioritizedLookupElement.withPriority(element, 100));
		}

		// Свойства встроенного класса — также видны через ^obj.field
		addBuiltinClassProperties(className, result);
	}

	/**
	 * Добавляет методы пользовательского класса в автокомплит.
	 */
	private static void addUserClassMethods(
			@NotNull Project project,
			@NotNull String className,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result
	) {
		addUserClassMethods(
				project,
				className,
				currentFile,
				cursorOffset,
				result,
				P3CompletionUtils.getVisibleFilesForClasses(project, currentFile, cursorOffset));
	}

	private static void addUserClassMethods(
			@NotNull Project project,
			@NotNull String className,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull CompletionResultSet result,
			@NotNull List<VirtualFile> visibleFiles
	) {
		long t0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		// Ищем класс
		List<P3ClassDeclaration> classes = classIndex.findInFiles(className, visibleFiles);
		if (classes.isEmpty()) {
			return;
		}

		// Берём последний класс (может быть несколько partial)
		P3ClassDeclaration classDecl = classes.get(classes.size() - 1);

		// Собираем методы класса и его базовых классов
		List<P3MethodDeclaration> methods = new ArrayList<>();
		collectClassMethods(classDecl.getName(), methodIndex, classIndex, visibleFiles, methods, new ArrayList<>());

		// Добавляем методы в результат
		for (P3MethodDeclaration method : methods) {
			if (!method.getCallType().allowsDynamicCall()) {
				continue;
			}
			String methodName = method.getName();

			// Формируем tailText с параметрами и описанием
			String tailText = "";
			if (!method.getParameterNames().isEmpty()) {
				tailText = "[" + String.join(";", method.getParameterNames()) + "]";
			}
			if (method.getDocText() != null) {
				String firstLine = getFirstDocLine(method.getDocText());
				if (firstLine != null && !firstLine.isEmpty()) {
					tailText += " " + firstLine;
				}
			}

			// Создаём LookupElement с документацией
			LookupElementBuilder element = LookupElementBuilder
					.create(new P3UserMethodLookupObject(methodName, method), methodName)
					.withIcon(Parser3Icons.FileMethod)
					.withTypeText(className, true)
					.withTailText(tailText.isEmpty() ? null : " " + tailText, true)
					.withInsertHandler(P3MethodInsertHandler.INSTANCE);

			// Добавляем с высоким приоритетом
			result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(element, 100));
		}

		// Добавляем переменные класса с типами (varName.)
		// Переменные класса ($self.var[^Type::...]) доступны как ^obj.varName.
		addClassVariables(project, className, classIndex, visibleFiles, result, methods, true, java.util.Collections.emptySet());
		if (DEBUG_PERF) System.out.println("[VarMethodCompl.PERF] addUserClassMethods('" + className + "'): " + (System.currentTimeMillis() - t0) + "ms");
	}

	/**
	 * Получает первую строку из текста документации.
	 */
	private static @Nullable String getFirstDocLine(@Nullable String docText) {
		if (docText == null || docText.isEmpty()) {
			return null;
		}
		int newlinePos = docText.indexOf('\n');
		if (newlinePos >= 0) {
			return docText.substring(0, newlinePos).trim();
		}
		return docText.trim();
	}

	/**
	 * Добавляет глобальные переменные класса (и его @BASE иерархии) с типами в автокомплит.
	 *
	 * @param appendDot true — добавляет "varName." (для ^var. контекста), false — "varName" (для $var. контекста)
	 */
	private static void addClassVariables(
			@NotNull Project project,
			@NotNull String className,
			@NotNull P3ClassIndex classIndex,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull CompletionResultSet result,
			@NotNull List<P3MethodDeclaration> existingMethods,
			boolean appendDot,
			@NotNull java.util.Set<String> hiddenVariableNames
	) {
		P3VariableIndex varTypeIndex = P3VariableIndex.getInstance(project);

		// Собираем имена методов — не добавляем переменные с такими же именами
		java.util.Set<String> methodNames = new java.util.HashSet<>();
		for (P3MethodDeclaration m : existingMethods) {
			methodNames.add(m.getName());
		}

		// Собираем переменные по всей иерархии классов
		java.util.Map<String, String> allVars = new java.util.LinkedHashMap<>();
		java.util.Set<String> visitedClasses = new java.util.HashSet<>();
		String currentClassName = className;

		while (currentClassName != null && !visitedClasses.contains(currentClassName)) {
			visitedClasses.add(currentClassName);

			java.util.Map<String, String> classVars = varTypeIndex.getClassVariableTypes(currentClassName, visibleFiles);
			for (java.util.Map.Entry<String, String> entry : classVars.entrySet()) {
				// Не перезаписываем — приоритет у дочернего класса
				if (!allVars.containsKey(entry.getKey())) {
					allVars.put(entry.getKey(), entry.getValue());
				}
			}

			// Переходим к базовому классу
			List<P3ClassDeclaration> classes = classIndex.findInFiles(currentClassName, visibleFiles);
			if (!classes.isEmpty()) {
				currentClassName = classes.get(classes.size() - 1).getBaseClassName();
			} else {
				currentClassName = null;
			}
		}

		// Добавляем переменные в результат
		for (java.util.Map.Entry<String, String> entry : allVars.entrySet()) {
			String varName = entry.getKey();
			String typeName = entry.getValue();

			if (hiddenVariableNames.contains(varName)) continue;
			// Пропускаем если есть метод с таким именем
			if (methodNames.contains(varName)) continue;

			boolean needsDot = appendDot || shouldAppendDotForPropertyType(typeName);
			String insertText = needsDot ? varName + "." : varName;

			LookupElementBuilder element = LookupElementBuilder
					.create(insertText)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(typeName != null && !typeName.isEmpty() ? typeName : null, true)
					.withPresentableText(insertText);

			if (needsDot) {
				element = element.withInsertHandler(createDotInsertHandler());
			} else {
				element = element.withInsertHandler(P3VariableInsertHandler.INSTANCE);
			}

			result.addElement(PrioritizedLookupElement.withPriority(element, 90));
		}
	}

	private static boolean shouldAppendDotForPropertyType(@Nullable String typeName) {
		if (typeName == null || typeName.isEmpty()) return false;
		if ("hash".equals(typeName) || "array".equals(typeName) || "table".equals(typeName) || "date".equals(typeName)) {
			return true;
		}
		return Parser3BuiltinMethods.isBuiltinClass(typeName);
	}

	private static com.intellij.codeInsight.completion.InsertHandler<com.intellij.codeInsight.lookup.LookupElement> createDotInsertHandler() {
		return (context, item) -> {
			// Удаляем остаток старого имени после вставки (только символы идентификатора).
			Document doc = context.getDocument();
			int tailOff = context.getTailOffset();
			CharSequence txt = doc.getCharsSequence();
			int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
			if (endOff > tailOff) {
				doc.deleteString(tailOff, endOff);
			}
			// Удаляем дублирующую точку: lookup string "var." + существующая ".".
			int newTail = context.getTailOffset();
			CharSequence newTxt = doc.getCharsSequence();
			if (newTail > 0 && newTail < newTxt.length()
					&& newTxt.charAt(newTail - 1) == '.' && newTxt.charAt(newTail) == '.') {
				doc.deleteString(newTail, newTail + 1);
			}
			// После вставки автоматически открываем popup с полями следующего уровня.
			com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
					.scheduleAutoPopup(context.getEditor());
		};
	}

	/**
	 * Рекурсивно собирает методы класса и его базовых классов
	 */
	private static void collectClassMethods(
			@NotNull String className,
			@NotNull P3MethodIndex methodIndex,
			@NotNull P3ClassIndex classIndex,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull List<P3MethodDeclaration> result,
			@NotNull List<String> visitedClasses
	) {
		// Защита от циклических зависимостей
		if (visitedClasses.contains(className)) {
			return;
		}
		visitedClasses.add(className);

		// Находим все методы класса в видимых файлах
		for (VirtualFile file : visibleFiles) {
			if (!file.isValid()) continue;

			List<P3MethodDeclaration> fileMethods = methodIndex.findInFile(file);
			for (P3MethodDeclaration method : fileMethods) {
				// Проверяем что метод принадлежит нужному классу
				String ownerClass = getMethodOwnerClass(method, file, classIndex);
				if (className.equals(ownerClass)) {
					if (method.getCallType().allowsDynamicCall()) {
						result.add(method);
					}
				}
			}
		}

		// Находим базовый класс
		List<P3ClassDeclaration> classes = classIndex.findInFiles(className, visibleFiles);
		if (!classes.isEmpty()) {
			P3ClassDeclaration classDecl = classes.get(classes.size() - 1);
			String baseClassName = classDecl.getBaseClassName();

			if (baseClassName != null) {
				// Рекурсивно собираем методы базового класса
				collectClassMethods(baseClassName, methodIndex, classIndex, visibleFiles, result, visitedClasses);
			}
		}
	}

	/**
	 * Определяет класс-владельца метода
	 */
	private static @Nullable String getMethodOwnerClass(
			@NotNull P3MethodDeclaration method,
			@NotNull VirtualFile file,
			@NotNull P3ClassIndex classIndex
	) {
		// Ищем класс на позиции метода
		P3ClassDeclaration classDecl = classIndex.findClassAtOffset(file, method.getOffset());
		return classDecl != null ? classDecl.getName() : null;
	}

	/**
	 * Добавляет ключи хеша в автокомплит.
	 * Ключи с вложенными подключами показываются с точкой на конце (и триггерят autoPopup).
	 * Ключи с известным типом (table, hash, etc.) показывают тип в typeText.
	 *
	 * @param hashKeys карта ключей хеша
	 * @param result результат автокомплита
	 * @param appendDot true для ^var. контекста, false для $var.
	 * @param parentClassName тип родителя: "hash" или "array" — для отображения в tailText
	 */
	public static void addHashKeys(
			@NotNull java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> hashKeys,
			@NotNull CompletionResultSet result,
			boolean appendDot,
			@Nullable String parentClassName
	) {
		addHashKeys(hashKeys, result, appendDot, parentClassName, null);
	}

	public static void addHashKeys(
			@NotNull java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> hashKeys,
			@NotNull CompletionResultSet result,
			boolean appendDot,
			@Nullable String parentClassName,
			@Nullable String closingSuffix
	) {
		addHashKeys(hashKeys, result, appendDot, parentClassName, closingSuffix, null, null, -1);
	}

	public static void addHashKeys(
			@NotNull java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> hashKeys,
			@NotNull CompletionResultSet result,
			boolean appendDot,
			@Nullable String parentClassName,
			@Nullable String closingSuffix,
			@Nullable String normalizedVarKey,
			@Nullable String currentFileText,
			int cursorOffset
	) {
		String tailLabel = "array".equals(parentClassName) ? " array element" : " hash key";
		String typedPrefix = result.getPrefixMatcher().getPrefix();
		int currentSegmentStart = cursorOffset >= 0 ? Math.max(0, cursorOffset - typedPrefix.length()) : -1;
		java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> completionHashKeys = new java.util.LinkedHashMap<>(hashKeys);
		String currentWeakCandidate = extractCurrentWeakCandidate(typedPrefix, currentFileText, cursorOffset);
		if (currentWeakCandidate != null && !completionHashKeys.containsKey(currentWeakCandidate)) {
			completionHashKeys.put(currentWeakCandidate,
					new ru.artlebedev.parser3.index.HashEntryInfo(
							ru.artlebedev.parser3.index.P3VariableFileIndex.UNKNOWN_TYPE,
							null,
							null,
							currentSegmentStart
					));
		}
		for (java.util.Map.Entry<String, ru.artlebedev.parser3.index.HashEntryInfo> entry : completionHashKeys.entrySet()) {
			String keyName = entry.getKey();
			// Пропускаем wildcard ключ "*" — он служебный (для hash::sql, array::sql)
			if ("*".equals(keyName)) continue;
			ru.artlebedev.parser3.index.HashEntryInfo info = entry.getValue();
			if (isHiddenHashMutationEntry(info)) continue;
			if (shouldHideCurrentWeakCandidate(
					appendDot, keyName, typedPrefix, normalizedVarKey, currentFileText, currentSegmentStart)) {
				continue;
			}

			// Определяем, нужна ли точка после ключа.
			// В контексте ^var. (appendDot=true) — всегда точка (у значения всегда есть методы).
			// В контексте $var. (appendDot=false) — только если есть вложенные ключи или известный тип.
			boolean needsDot = appendDot || info.hasNestedKeys()
					|| info.nestedKeys != null  // пустой nestedKeys = есть ключи (например через foreach_field)
					|| ("hash".equals(info.className) && info.nestedKeys != null)
					|| "table".equals(info.className)
					|| (!"hash".equals(info.className)
					&& !ru.artlebedev.parser3.index.P3VariableFileIndex.UNKNOWN_TYPE.equals(info.className)
					&& ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(info.className));
			if (DEBUG) System.out.println("[addHashKeys] key=" + keyName + " needsDot=" + needsDot + " className=" + info.className + " nestedKeys=" + (info.nestedKeys != null ? info.nestedKeys.keySet() : "null") + " hasNestedKeys=" + info.hasNestedKeys());
			String displayType = ru.artlebedev.parser3.index.P3VariableFileIndex.UNKNOWN_TYPE.equals(info.className)
					? null : info.className;
			ExceptionFieldMeta exceptionMeta = getExceptionFieldMeta(hashKeys, keyName);
			if (exceptionMeta != null) {
				displayType = exceptionMeta.typeText;
			}
			String displayTailText = exceptionMeta != null ? " " + exceptionMeta.description : tailLabel;

			// Ключи с пробелами/спецсимволами — оборачиваем в скобки: [key name]
			boolean needsBrackets = keyName.indexOf(' ') >= 0 || keyName.indexOf('\t') >= 0;

			String insertText;
			String displayText;
			if (needsBrackets) {
				// Для table: скобки уже добавляются в addTableColumns, для hash — добавляем здесь
				insertText = needsDot ? "[" + keyName + "]." : "[" + keyName + "]";
				displayText = "[" + keyName + "]" + (needsDot ? "." : "");
			} else {
				insertText = needsDot ? keyName + "." : keyName;
				displayText = insertText;
			}

			LookupElementBuilder element = LookupElementBuilder
					.create(insertText)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(displayType, true)
					.withPresentableText(displayText)
					.withTailText(displayTailText, true);

			com.intellij.codeInsight.completion.InsertHandler<com.intellij.codeInsight.lookup.LookupElement> handler;
			if (needsBrackets) {
				// Ключ с пробелами: [key name] или [key name].
				final boolean finalNeedsDot = needsDot;
				handler = (context, item) -> {
					Document doc = context.getDocument();
					int tailOff = context.getTailOffset();
					CharSequence txt = doc.getCharsSequence();
					// Удаляем остаток старого идентификатора
					int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
					if (endOff > tailOff) {
						doc.deleteString(tailOff, endOff);
					}
					if (finalNeedsDot) {
						// Удаляем дублирующую точку
						int newTail = context.getTailOffset();
						CharSequence newTxt = doc.getCharsSequence();
						if (newTail > 0 && newTail < newTxt.length()
								&& newTxt.charAt(newTail - 1) == '.' && newTxt.charAt(newTail) == '.') {
							doc.deleteString(newTail, newTail + 1);
						}
						com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
								.scheduleAutoPopup(context.getEditor());
					}
				};
			} else if (needsDot) {
				handler = (context, item) -> {
					// Удаляем остаток старого имени
					Document doc = context.getDocument();
					int tailOff = context.getTailOffset();
					CharSequence txt = doc.getCharsSequence();
					int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
					if (endOff > tailOff) {
						doc.deleteString(tailOff, endOff);
					}
					// Удаляем дублирующую точку
					int newTail = context.getTailOffset();
					CharSequence newTxt = doc.getCharsSequence();
					if (newTail > 0 && newTail < newTxt.length()
							&& newTxt.charAt(newTail - 1) == '.' && newTxt.charAt(newTail) == '.') {
						doc.deleteString(newTail, newTail + 1);
					}
					// Автоматически открываем popup с подключами/методами
					com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
							.scheduleAutoPopup(context.getEditor());
				};
			} else {
				handler = P3VariableInsertHandler.INSTANCE;
			}

			if (closingSuffix != null) {
				handler = wrapInsertHandlerWithSuffix(handler, closingSuffix);
			}
			element = element.withInsertHandler(handler);

			// Хеш-ключи — выше методов (110 > 100), т.к. это конкретные данные объекта
			result.addElement(PrioritizedLookupElement.withPriority(element, 110));
		}
	}

	/**
	 * Добавляет ключи хеша как значения аргумента ^хеш.delete[ключ].
	 * В отличие от dotpath completion здесь нельзя дописывать точку после вложенных ключей.
	 */
	public static void addHashDeleteKeys(
			@NotNull java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> hashKeys,
			@NotNull CompletionResultSet result
	) {
		for (java.util.Map.Entry<String, ru.artlebedev.parser3.index.HashEntryInfo> entry : hashKeys.entrySet()) {
			String keyName = entry.getKey();
			if ("*".equals(keyName)) continue;

			ru.artlebedev.parser3.index.HashEntryInfo info = entry.getValue();
			if (isHiddenHashMutationEntry(info)) continue;
			String displayType = info != null
					&& info.className != null
					&& !ru.artlebedev.parser3.index.P3VariableFileIndex.UNKNOWN_TYPE.equals(info.className)
					? info.className : null;

			LookupElementBuilder element = LookupElementBuilder
					.create(keyName)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(displayType, true)
					.withPresentableText(keyName)
					.withTailText(" hash key", true)
					.withInsertHandler(P3VariableInsertHandler.INSTANCE);

			result.addElement(PrioritizedLookupElement.withPriority(element, 110));
		}
	}

	private static boolean isHiddenHashMutationEntry(@Nullable ru.artlebedev.parser3.index.HashEntryInfo info) {
		return info != null && "__deleted__".equals(info.className);
	}

	private static @Nullable String extractCurrentWeakCandidate(
			@NotNull String typedPrefix,
			@Nullable String currentFileText,
			int cursorOffset
	) {
		if (typedPrefix.isEmpty()) return null;
		if (currentFileText == null || currentFileText.isEmpty()) return null;
		if (cursorOffset < 0 || cursorOffset >= currentFileText.length()) return null;

		int rightEnd = ru.artlebedev.parser3.utils.Parser3VariableTailUtils.skipIdentifierChars(
				currentFileText,
				cursorOffset,
				currentFileText.length()
		);
		if (rightEnd <= cursorOffset) return null;

		String rightSuffix = currentFileText.substring(cursorOffset, rightEnd);
		if (rightSuffix.isEmpty()) return null;

		return typedPrefix + rightSuffix;
	}

	private static boolean shouldHideCurrentWeakCandidate(
			boolean appendDot,
			@NotNull String keyName,
			@NotNull String typedPrefix,
			@Nullable String normalizedVarKey,
			@Nullable String currentFileText,
			int currentSegmentStart
	) {
		if (appendDot) return false;
		if (typedPrefix.isEmpty()) return false;
		if (!keyName.equals(typedPrefix)) return false;
		if (normalizedVarKey == null || normalizedVarKey.isEmpty()) return false;
		if (currentFileText == null || currentFileText.isEmpty()) return false;
		if (currentSegmentStart < 0) return false;

		String expectedChain = normalizedVarKey + "." + keyName;
		return !Parser3ChainUtils.hasCompletedChainOccurrenceElsewhere(
				currentFileText,
				expectedChain,
				currentSegmentStart
		);
	}

	/**
	 * Добавляет колонки таблицы в автокомплит.
	 * Колонки показываются как свойства с типом string.
	 *
	 * @param columns список колонок таблицы
	 * @param result результат автокомплита
	 * @param appendDot true для ^var. контекста (добавляет точку), false для $var.
	 */
	public static void addTableColumns(
			@Nullable java.util.List<String> columns,
			@NotNull CompletionResultSet result,
			boolean appendDot
	) {
		addTableColumns(columns, result, appendDot, null);
	}

	public static void addTableColumns(
			@Nullable java.util.List<String> columns,
			@NotNull CompletionResultSet result,
			boolean appendDot,
			@Nullable String closingSuffix
	) {
		if (columns == null || columns.isEmpty()) return;

		for (String colName : columns) {
			boolean hasSpace = colName.contains(" ") || colName.contains("(") || colName.contains(")") || colName.contains("*");

			// Для колонок с пробелами/спецсимволами: вставляем [col name]
			// Для обычных: вставляем colName (или colName. если appendDot)
			String displayText;
			String insertText;
			if (hasSpace) {
				displayText = "[" + colName + "]";
				insertText = appendDot ? "[" + colName + "]." : "[" + colName + "]";
			} else {
				displayText = appendDot ? colName + "." : colName;
				insertText = displayText;
			}

			LookupElementBuilder element = LookupElementBuilder
					.create(insertText)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText("string", true)
					.withPresentableText(displayText)
					.withTailText(" column", true);

			com.intellij.codeInsight.completion.InsertHandler<com.intellij.codeInsight.lookup.LookupElement> handler;
			if (hasSpace) {
				// Для [col name] — специальный InsertHandler
				handler = (context, item) -> {
					Document doc = context.getDocument();
					int tailOff = context.getTailOffset();
					CharSequence txt = doc.getCharsSequence();
					// Удаляем остаток (только символы идентификатора)
					int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
					if (endOff > tailOff) {
						doc.deleteString(tailOff, endOff);
					}
				};
			} else if (appendDot) {
				handler = (context, item) -> {
					// Удаляем остаток старого имени после вставки (только символы идентификатора)
					Document doc = context.getDocument();
					int tailOff = context.getTailOffset();
					CharSequence txt = doc.getCharsSequence();
					int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
					if (endOff > tailOff) {
						doc.deleteString(tailOff, endOff);
					}
					// Удаляем дублирующую точку
					int newTail = context.getTailOffset();
					CharSequence newTxt = doc.getCharsSequence();
					if (newTail > 0 && newTail < newTxt.length()
							&& newTxt.charAt(newTail - 1) == '.' && newTxt.charAt(newTail) == '.') {
						doc.deleteString(newTail, newTail + 1);
					}
					// Автоматически открываем popup с методами string
					com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
							.scheduleAutoPopup(context.getEditor());
				};
			} else {
				handler = P3VariableInsertHandler.INSTANCE;
			}

			if (closingSuffix != null) {
				handler = wrapInsertHandlerWithSuffix(handler, closingSuffix);
			}
			element = element.withInsertHandler(handler);

			result.addElement(PrioritizedLookupElement.withPriority(element, 95));
		}
	}

	private static @Nullable ExceptionFieldMeta getExceptionFieldMeta(
			@NotNull java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> hashKeys,
			@NotNull String keyName
	) {
		if (!EXCEPTION_FIELD_META.containsKey(keyName)) return null;
		if (!hashKeys.keySet().containsAll(EXCEPTION_FIELD_META.keySet())) return null;
		return EXCEPTION_FIELD_META.get(keyName);
	}

	private static @NotNull java.util.Map<String, ExceptionFieldMeta> createExceptionFieldMeta() {
		java.util.Map<String, ExceptionFieldMeta> result = new java.util.LinkedHashMap<>();
		result.put("type", new ExceptionFieldMeta("string", "тип ошибки"));
		result.put("source", new ExceptionFieldMeta("string", "источник ошибки"));
		result.put("comment", new ExceptionFieldMeta("string", "текст ошибки"));
		result.put("file", new ExceptionFieldMeta("string", "файл, где произошла ошибка"));
		result.put("lineno", new ExceptionFieldMeta("int", "номер строки, где произошла ошибка"));
		result.put("colno", new ExceptionFieldMeta("int", "номер колонки, где произошла ошибка"));
		result.put("handled", new ExceptionFieldMeta("bool", "флаг, что ошибка обработана"));
		return java.util.Collections.unmodifiableMap(result);
	}
}
