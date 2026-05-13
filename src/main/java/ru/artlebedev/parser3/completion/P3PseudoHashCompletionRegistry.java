package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.index.P3VariableIndex;
import ru.artlebedev.parser3.settings.Parser3PseudoHashCompletionService;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.utils.Parser3ChainUtils;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Реестр конфигурируемого автокомплита для аргументов и значений Parser3.
 *
 * Конфиг хранится в JSON-файле ресурсов и не требует изменений Java-кода при добавлении новых схем.
 */
public final class P3PseudoHashCompletionRegistry {

	private static final boolean DEBUG = false;
	private static final boolean DEBUG_PERF = false;
	private static final String CONFIG_RESOURCE_PATH = "ru/artlebedev/parser3/completion/pseudo-hash-completion.json";
	private static final String ANY_CLASS = "*";

	private static volatile RegistryData cachedBuiltinData;
	private static final Map<String, RegistryData> userDataCache = new LinkedHashMap<>();

	private P3PseudoHashCompletionRegistry() {
	}

	public static void clearCaches() {
		cachedBuiltinData = null;
		synchronized (userDataCache) {
			userDataCache.clear();
		}
	}

	public static @Nullable String validateUserConfig(@Nullable String userJson) {
		if (userJson == null || userJson.isBlank()) {
			return null;
		}
		try {
			List<Object> root = JsonParser.parseArray(userJson);
			validateConfigEntries(root, true);
			return null;
		} catch (IllegalArgumentException e) {
			return "Ошибка в пользовательском JSON contextual argument completion: " + e.getMessage();
		} catch (Exception e) {
			return "Ошибка в пользовательском JSON contextual argument completion: " + e.getMessage();
		}
	}

	public static boolean addCompletions(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull String editorText,
			int cursorOffset,
			@NotNull P3VariableCompletionContributor.VarPrefix varCtx,
			@NotNull CompletionResultSet result
	) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		long findScopeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		ScopeContext rawScope = findScope(editorText, cursorOffset);
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addCompletions findScope: "
					+ (System.currentTimeMillis() - findScopeStart) + "ms"
					+ " file=" + currentFile.getName()
					+ " offset=" + cursorOffset
					+ " found=" + (rawScope != null));
		}
		long resolveScopeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		ScopeContext scope = resolveScopeContext(project, currentFile, cursorOffset, rawScope);
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addCompletions resolveScopeContext: "
					+ (System.currentTimeMillis() - resolveScopeStart) + "ms"
					+ " file=" + currentFile.getName()
					+ " resolved=" + (scope != null));
		}
		if (scope == null) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] scope not found at offset=" + cursorOffset);
			}
			if (DEBUG_PERF) {
				System.out.println("[P3PseudoHashCompletion.PERF] addCompletions TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " route=noScope"
						+ " file=" + currentFile.getName());
			}
			return false;
		}

		List<String> fullPath = new ArrayList<>(scope.path);
		if (varCtx.varKey != null && !varCtx.varKey.isEmpty()) {
			fullPath.addAll(splitPath(varCtx.varKey));
		}

		long paramsStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		List<ParamSpec> params = getParams(project, scope.className, scope.callableKind, scope.callableName, scope.targetType, fullPath);
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addCompletions getParams: "
					+ (System.currentTimeMillis() - paramsStart) + "ms"
					+ " target=" + scope.getPresentableTarget()
					+ " type=" + scope.targetType
					+ " path=" + fullPath
					+ " params=" + params.size());
		}
		if (params.isEmpty()) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] no params for "
						+ scope.getPresentableTarget() + " type=" + scope.targetType + " path=" + fullPath);
			}
			if (DEBUG_PERF) {
				System.out.println("[P3PseudoHashCompletion.PERF] addCompletions TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " route=noParams"
						+ " file=" + currentFile.getName());
			}
			return false;
		}

		CompletionResultSet completionResult = result.withPrefixMatcher(varCtx.typedPrefix);
		Set<String> added = new LinkedHashSet<>();
		for (ParamSpec param : params) {
			String dedupKey = param.name.toLowerCase() + "|" + param.brackets;
			if (!added.add(dedupKey)) {
				continue;
			}
			LookupElementBuilder element = LookupElementBuilder
					.create(param.name)
					.withLookupString(param.name)
					.withPresentableText("$." + param.name + param.brackets)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(scope.getPresentableTarget(), true)
					.withTailText(param.comment == null || param.comment.isBlank() ? null : " " + param.comment, true)
					.withInsertHandler(createInsertHandler(param, varCtx.needsClosingBrace));

			completionResult.addElement(PrioritizedLookupElement.withPriority(element, 130));
		}
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addCompletions TOTAL: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " route=params"
					+ " target=" + scope.getPresentableTarget()
					+ " added=" + added.size()
					+ " file=" + currentFile.getName());
		}
		return true;
	}

	public static boolean addParamTextCompletions(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull String editorText,
			int cursorOffset,
			@NotNull CompletionResultSet result
	) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		long textContextStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		TextValueContext textValueContext = findTextValueContext(editorText, cursorOffset);
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addParamTextCompletions findTextValueContext: "
					+ (System.currentTimeMillis() - textContextStart) + "ms"
					+ " file=" + currentFile.getName()
					+ " offset=" + cursorOffset
					+ " found=" + (textValueContext != null)
					+ " prefix='" + (textValueContext != null ? textValueContext.typedPrefix : "") + "'");
		}
		if (textValueContext == null) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] addParamTextCompletions: no text value context at offset=" + cursorOffset);
			}
			if (DEBUG_PERF) {
				System.out.println("[P3PseudoHashCompletion.PERF] addParamTextCompletions TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " route=noTextContext"
						+ " file=" + currentFile.getName());
			}
			return false;
		}

		long findScopeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		ScopeContext rawScope = findScope(editorText, textValueContext.tokenStartOffset);
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addParamTextCompletions findScope: "
					+ (System.currentTimeMillis() - findScopeStart) + "ms"
					+ " file=" + currentFile.getName()
					+ " tokenStart=" + textValueContext.tokenStartOffset
					+ " found=" + (rawScope != null));
		}
		long resolveScopeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		ScopeContext scope = resolveScopeContext(project, currentFile, cursorOffset, rawScope);
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addParamTextCompletions resolveScopeContext: "
					+ (System.currentTimeMillis() - resolveScopeStart) + "ms"
					+ " file=" + currentFile.getName()
					+ " resolved=" + (scope != null));
		}
		if (scope == null) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] addParamTextCompletions: no scope for prefix='"
						+ textValueContext.typedPrefix + "' tokenStart=" + textValueContext.tokenStartOffset);
			}
			if (DEBUG_PERF) {
				System.out.println("[P3PseudoHashCompletion.PERF] addParamTextCompletions TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " route=noScope"
						+ " file=" + currentFile.getName());
			}
			return false;
		}

		long valuesStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		List<TextValueSpec> values = getTextValues(project, scope.className, scope.callableKind, scope.callableName, scope.targetType, scope.path);
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addParamTextCompletions getTextValues: "
					+ (System.currentTimeMillis() - valuesStart) + "ms"
					+ " target=" + scope.getPresentableTarget()
					+ " type=" + scope.targetType
					+ " path=" + scope.path
					+ " values=" + values.size());
		}
		if (values.isEmpty()) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] addParamTextCompletions: no values for "
						+ scope.getPresentableTarget() + " type=" + scope.targetType);
			}
			if (DEBUG_PERF) {
				System.out.println("[P3PseudoHashCompletion.PERF] addParamTextCompletions TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " route=noValues"
						+ " file=" + currentFile.getName());
			}
			return false;
		}

		if (DEBUG) {
			System.out.println("[P3PseudoHashCompletion] addParamTextCompletions: target="
					+ scope.getPresentableTarget() + " type=" + scope.targetType
					+ " prefix='" + textValueContext.typedPrefix + "' values=" + values.size());
		}

		CompletionResultSet completionResult = result.withPrefixMatcher(textValueContext.typedPrefix);
		Set<String> added = new LinkedHashSet<>();
		for (TextValueSpec value : values) {
			String dedupKey = value.text.toLowerCase();
			if (!added.add(dedupKey)) {
				continue;
			}

			LookupElementBuilder element = LookupElementBuilder
					.create(value.text)
					.withLookupString(value.text)
					.withPresentableText(value.text)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(scope.getPresentableTarget(), true)
					.withTailText(value.comment == null || value.comment.isBlank() ? null : " " + value.comment, true)
					.withInsertHandler(createTextValueInsertHandler());

			completionResult.addElement(PrioritizedLookupElement.withPriority(element, 125));
		}
		if (DEBUG_PERF) {
			System.out.println("[P3PseudoHashCompletion.PERF] addParamTextCompletions TOTAL: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " route=values"
					+ " target=" + scope.getPresentableTarget()
					+ " added=" + added.size()
					+ " file=" + currentFile.getName());
		}
		return true;
	}

	public static boolean shouldAutoPopupParamText(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull String editorText,
			int cursorOffset,
			char typeChar
	) {
		if (!(Character.isLetterOrDigit(typeChar)
				|| typeChar == '_'
				|| typeChar == '-'
				|| typeChar == '['
				|| typeChar == '('
				|| typeChar == '{'
				|| typeChar == ';')) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] shouldAutoPopupParamText: skip char='" + typeChar + "'");
			}
			return false;
		}

		String textForAnalysis = buildTextWithTypedChar(editorText, cursorOffset, typeChar);
		int analysisOffset = Math.min(textForAnalysis.length(), Math.max(0, cursorOffset + 1));
		if (DEBUG) {
			int from = Math.max(0, analysisOffset - 20);
			int to = Math.min(textForAnalysis.length(), analysisOffset + 20);
			System.out.println("[P3PseudoHashCompletion] shouldAutoPopupParamText: char='" + typeChar
					+ "' rawOffset=" + cursorOffset
					+ " analysisOffset=" + analysisOffset
					+ " around='" + textForAnalysis.substring(from, to).replace("\n", "\\n").replace("\r", "\\r") + "'");
		}

		TextValueContext textValueContext = findTextValueContext(textForAnalysis, analysisOffset);
		if (textValueContext == null) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] shouldAutoPopupParamText: no text context, char='"
						+ typeChar + "' offset=" + analysisOffset);
			}
			return false;
		}

		ScopeContext scope = resolveScopeContext(project, currentFile, analysisOffset, findScope(textForAnalysis, textValueContext.tokenStartOffset));
		if (scope == null) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] shouldAutoPopupParamText: no scope for prefix='"
						+ textValueContext.typedPrefix + "' tokenStart=" + textValueContext.tokenStartOffset);
			}
			return false;
		}

		List<TextValueSpec> values = getTextValues(project, scope.className, scope.callableKind, scope.callableName, scope.targetType, scope.path);
		if (DEBUG) {
			System.out.println("[P3PseudoHashCompletion] shouldAutoPopupParamText: target="
					+ scope.getPresentableTarget() + " type=" + scope.targetType
					+ " prefix='" + textValueContext.typedPrefix + "' values=" + values.size()
					+ " char='" + typeChar + "'");
		}
		return !values.isEmpty();
	}

	public static boolean isParamTextContext(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull String editorText,
			int cursorOffset
	) {
		TextValueContext textValueContext = findTextValueContext(editorText, cursorOffset);
		if (textValueContext == null) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] isParamTextContext: no text context at offset=" + cursorOffset);
			}
			return false;
		}

		ScopeContext scope = resolveScopeContext(project, currentFile, cursorOffset, findScope(editorText, textValueContext.tokenStartOffset));
		if (scope == null) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] isParamTextContext: no scope for prefix='"
						+ textValueContext.typedPrefix + "' tokenStart=" + textValueContext.tokenStartOffset);
			}
			return false;
		}

		List<TextValueSpec> values = getTextValues(project, scope.className, scope.callableKind, scope.callableName, scope.targetType, scope.path);
		if (DEBUG) {
			System.out.println("[P3PseudoHashCompletion] isParamTextContext: target="
					+ scope.getPresentableTarget() + " type=" + scope.targetType
					+ " prefix='" + textValueContext.typedPrefix + "' values=" + values.size());
		}
		return !values.isEmpty();
	}

	private static @NotNull String buildTextWithTypedChar(@NotNull String editorText, int cursorOffset, char typeChar) {
		int safeOffset = Math.max(0, Math.min(cursorOffset, editorText.length()));
		return editorText.substring(0, safeOffset) + typeChar + editorText.substring(safeOffset);
	}

	private static @Nullable ScopeContext resolveScopeContext(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@Nullable ScopeContext scope
	) {
		if (scope == null) {
			return null;
		}

		if (scope.callableKind != CallableKind.DYNAMIC_METHOD) {
			return scope;
		}

		if (scope.receiverVarKey == null || scope.receiverVarKey.isBlank()) {
			return null;
		}

		if (hasMethodSpec(project, ANY_CLASS, scope.callableKind, scope.callableName, scope.targetType)) {
			return scope.withResolvedClassName(ANY_CLASS);
		}

		String resolvedClassName = resolveReceiverClass(project, currentFile, cursorOffset, scope.receiverVarKey);
		if (resolvedClassName == null || resolvedClassName.isBlank()) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] receiver class not resolved for " + scope.receiverVarKey);
			}
			return null;
		}

		return scope.withResolvedClassName(resolvedClassName);
	}

	private static @Nullable String resolveReceiverClass(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull String receiverVarKey
	) {
		P3VariableIndex variableIndex = P3VariableIndex.getInstance(project);
		List<VirtualFile> visibleFiles = getVisibleFiles(project, currentFile, cursorOffset);
		String normalizedVarKey = Parser3ChainUtils.normalizeDynamicSegments(receiverVarKey);

		P3VariableIndex.ChainResolveInfo resolved = variableIndex.resolveEffectiveChain(
				normalizedVarKey,
				visibleFiles,
				currentFile,
				cursorOffset
		);
		if (resolved == null) {
			return null;
		}
		if (resolved.className != null && !resolved.className.isBlank()) {
			return resolved.className;
		}
		if (resolved.hashKeys != null && !resolved.hashKeys.isEmpty()) {
			return "hash";
		}
		if (resolved.columns != null && !resolved.columns.isEmpty()) {
			return "table";
		}
		return resolved.className;
	}

	private static @NotNull List<VirtualFile> getVisibleFiles(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		return new P3ScopeContext(project, currentFile, cursorOffset).getVariableSearchFiles();
	}

	private static @NotNull List<String> splitPath(@NotNull String path) {
		List<String> result = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < path.length(); i++) {
			if (path.charAt(i) == '.') {
				String part = path.substring(start, i).trim();
				if (!part.isEmpty()) {
					result.add(part);
				}
				start = i + 1;
			}
		}
		String tail = path.substring(start).trim();
		if (!tail.isEmpty()) {
			result.add(tail);
		}
		return result;
	}

	private static @NotNull InsertHandler<LookupElement> createInsertHandler(
			@NotNull ParamSpec param,
			boolean needsClosingBrace
	) {
		return (context, item) -> {
			Document doc = context.getDocument();
			int insertOffset = context.getTailOffset();
			CharSequence chars = doc.getCharsSequence();
			int end = P3VariableInsertHandler.skipIdentifierChars(chars, insertOffset, chars.length());
			if (end > insertOffset) {
				doc.deleteString(insertOffset, end);
			}

			String brackets = param.brackets;
			if (brackets == null || brackets.length() != 2) {
				brackets = "[]";
			}

			BracketTailInfo existingTail = findBracketTail(doc.getCharsSequence(), insertOffset, doc.getTextLength());
			if (existingTail != null) {
				int tailEndOffset = existingTail.endOffset;
				if (existingTail.openBracket == brackets.charAt(0)) {
					context.getEditor().getCaretModel().moveToOffset(insertOffset + 1);
				} else if (existingTail.isEmpty) {
					doc.deleteString(insertOffset, existingTail.endOffset);
					doc.insertString(insertOffset, brackets);
					tailEndOffset = insertOffset + brackets.length();
					context.getEditor().getCaretModel().moveToOffset(insertOffset + 1);
				} else {
					context.getEditor().getCaretModel().moveToOffset(insertOffset + 1);
				}

				if (needsClosingBrace) {
					ensureClosingBrace(doc, tailEndOffset);
				}
				return;
			}

			String insertedText = brackets;
			int caretTarget;
			if (!param.params.isEmpty() && "[]".equals(brackets)) {
				String baseIndent = getLineIndent(doc, insertOffset);
				insertedText = "[\n" + baseIndent + "\t" + "\n" + baseIndent + "]";
				caretTarget = insertOffset + 2 + baseIndent.length() + 1;
			} else {
				caretTarget = insertOffset + 1;
			}
			doc.insertString(insertOffset, insertedText);

			int suffixTail = insertOffset + insertedText.length();
			if (needsClosingBrace) {
				ensureClosingBrace(doc, suffixTail);
			}

			context.getEditor().getCaretModel().moveToOffset(caretTarget);

			if (!param.params.isEmpty() || !param.textValues.isEmpty()) {
				com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
						.scheduleAutoPopup(context.getEditor());
			}
		};
	}

	private static @NotNull InsertHandler<LookupElement> createTextValueInsertHandler() {
		return (context, item) -> {
			Document doc = context.getDocument();
			int insertOffset = context.getTailOffset();
			CharSequence chars = doc.getCharsSequence();
			int end = P3VariableInsertHandler.skipIdentifierChars(chars, insertOffset, chars.length());
			if (end > insertOffset) {
				doc.deleteString(insertOffset, end);
			}
		};
	}

	private static void ensureClosingBrace(@NotNull Document doc, int offset) {
		int safeOffset = Math.max(0, Math.min(offset, doc.getTextLength()));
		if (safeOffset < doc.getTextLength() && doc.getCharsSequence().charAt(safeOffset) == '}') {
			return;
		}
		doc.insertString(safeOffset, "}");
	}

	private static @Nullable BracketTailInfo findBracketTail(@NotNull CharSequence text, int offset, int limit) {
		if (offset < 0 || offset >= limit) {
			return null;
		}

		char open = text.charAt(offset);
		char close = switch (open) {
			case '[' -> ']';
			case '(' -> ')';
			case '{' -> '}';
			default -> '\0';
		};
		if (close == '\0') {
			return null;
		}

		int endOffset = findMatchingBracket(text, offset, limit, open, close);
		if (endOffset < 0) {
			return null;
		}
		return new BracketTailInfo(open, endOffset + 1, endOffset == offset + 1);
	}

	private static int findMatchingBracket(@NotNull CharSequence text, int openPos, int limit, char openCh, char closeCh) {
		int depth = 0;
		for (int i = openPos; i < limit; i++) {
			char ch = text.charAt(i);
			if (ch == openCh) {
				depth++;
			} else if (ch == closeCh) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static @NotNull String getLineIndent(@NotNull Document doc, int offset) {
		int lineNumber = doc.getLineNumber(Math.max(0, Math.min(offset, doc.getTextLength())));
		int lineStart = doc.getLineStartOffset(lineNumber);
		int lineEnd = doc.getLineEndOffset(lineNumber);
		CharSequence chars = doc.getCharsSequence();
		StringBuilder indent = new StringBuilder();
		for (int i = lineStart; i < lineEnd; i++) {
			char ch = chars.charAt(i);
			if (ch == ' ' || ch == '\t') {
				indent.append(ch);
				continue;
			}
			break;
		}
		return indent.toString();
	}

	private static @Nullable TextValueContext findTextValueContext(@NotNull CharSequence text, int cursorOffset) {
		if (cursorOffset < 0 || cursorOffset > text.length()) {
			if (DEBUG) {
				System.out.println("[P3PseudoHashCompletion] findTextValueContext: offset out of range " + cursorOffset);
			}
			return null;
		}

		int tokenStart = cursorOffset;
		while (tokenStart > 0) {
			char ch = text.charAt(tokenStart - 1);
			if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
				tokenStart--;
				continue;
			}
			break;
		}

		if (tokenStart > 0) {
			char before = text.charAt(tokenStart - 1);
			if (before == '$' || before == '.') {
				if (DEBUG) {
					System.out.println("[P3PseudoHashCompletion] findTextValueContext: blocked by before='" + before + "'");
				}
				return null;
			}
		}

		for (int i = cursorOffset; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
				continue;
			}
			break;
		}

		TextValueContext context = new TextValueContext(text.subSequence(tokenStart, cursorOffset).toString(), tokenStart);
		if (DEBUG) {
			int from = Math.max(0, tokenStart - 20);
			int to = Math.min(text.length(), cursorOffset + 20);
			System.out.println("[P3PseudoHashCompletion] findTextValueContext: prefix='" + context.typedPrefix
					+ "' tokenStart=" + tokenStart
					+ " around='" + text.subSequence(from, to).toString().replace("\n", "\\n").replace("\r", "\\r") + "'");
		}
		return context;
	}

	private static @NotNull List<ParamSpec> getParams(
			@NotNull Project project,
			@Nullable String className,
			@NotNull CallableKind callableKind,
			@NotNull String callableName,
			@NotNull TargetType targetType,
			@NotNull List<String> path
	) {
		RegistryData data = getRegistryData(project);
		MethodSpec method = data.methodsByKey.get(key(className, callableKind, callableName, targetType));
		if (method == null) {
			return Collections.emptyList();
		}

		List<ParamSpec> current = method.params;
		for (String segment : path) {
			ParamSpec found = findMatchingParam(current, segment, false);
			if (found == null) {
				return Collections.emptyList();
			}
			current = found.params;
		}
		return current;
	}

	private static boolean hasMethodSpec(
			@NotNull Project project,
			@Nullable String className,
			@NotNull CallableKind callableKind,
			@NotNull String callableName,
			@NotNull TargetType targetType
	) {
		return getRegistryData(project).methodsByKey.containsKey(key(className, callableKind, callableName, targetType));
	}

	private static @NotNull List<TextValueSpec> getTextValues(
			@NotNull Project project,
			@Nullable String className,
			@NotNull CallableKind callableKind,
			@NotNull String callableName,
			@NotNull TargetType targetType,
			@NotNull List<String> path
	) {
		RegistryData data = getRegistryData(project);
		MethodSpec method = data.methodsByKey.get(key(className, callableKind, callableName, targetType));
		if (method == null) {
			return Collections.emptyList();
		}
		if (path.isEmpty()) {
			return method.textValues;
		}

		List<ParamSpec> current = method.params;
		ParamSpec found = null;
		for (int i = 0; i < path.size(); i++) {
			String segment = path.get(i);
			boolean preferTextValues = i == path.size() - 1;
			found = findMatchingParam(current, segment, preferTextValues);
			if (found == null) {
				return Collections.emptyList();
			}
			current = found.params;
		}

		return found != null ? found.textValues : Collections.emptyList();
	}

	private static @Nullable ParamSpec findMatchingParam(
			@NotNull List<ParamSpec> params,
			@NotNull String name,
			boolean preferTextValues
	) {
		ParamSpec firstMatch = null;
		for (ParamSpec param : params) {
			if (!param.name.equalsIgnoreCase(name)) {
				continue;
			}
			if (firstMatch == null) {
				firstMatch = param;
			}
			if (preferTextValues) {
				if (!param.textValues.isEmpty()) {
					return param;
				}
			} else if (!param.params.isEmpty()) {
				return param;
			}
		}
		return firstMatch;
	}

	private static @Nullable ScopeContext findScope(@NotNull CharSequence text, int cursorOffset) {
		ArrayDeque<ScopeItem> stack = new ArrayDeque<>();
		PendingCallSegment pendingCallSegment = null;

		for (int i = 0; i < cursorOffset && i < text.length(); i++) {
			char ch = text.charAt(i);
			if (isEscaped(text, i)) {
				continue;
			}

			if (pendingCallSegment != null && !Character.isWhitespace(ch) && !isOpenBracket(ch)) {
				pendingCallSegment = null;
			}

			if (ch == '^') {
				ParsedCall call = parseCall(text, i, cursorOffset);
				if (call != null) {
					stack.push(ScopeItem.callSegment(call, 0));
					pendingCallSegment = null;
					i = call.openBracket;
					continue;
				}
			}

			if (ch == '$' && i + 1 < cursorOffset && text.charAt(i + 1) == '.') {
				ParsedPseudo pseudo = parsePseudo(text, i, cursorOffset);
				if (pseudo != null) {
					stack.push(ScopeItem.pseudo(pseudo));
					pendingCallSegment = null;
					i = pseudo.openBracket;
					continue;
				}
			}

			if (ch == '$') {
				ParsedBuiltinProperty property = parseBuiltinProperty(text, i, cursorOffset);
				if (property != null) {
					stack.push(ScopeItem.builtinProperty(property));
					pendingCallSegment = null;
					i = property.openBracket;
					continue;
				}
			}

			if (isOpenBracket(ch)) {
				if (pendingCallSegment != null) {
					stack.push(ScopeItem.callSegment(i, ch, pendingCallSegment));
					pendingCallSegment = null;
				} else {
					stack.push(ScopeItem.generic(i, ch));
				}
				continue;
			}

			if (ch == ';' && !isEscaped(text, i) && !stack.isEmpty()) {
				ScopeItem top = stack.peek();
				if (top != null && top.type == ItemType.CALL_SEGMENT) {
					top.semicolonCount++;
				}
				continue;
			}

			if (isCloseBracket(ch)) {
				ScopeItem closed = popMatching(stack, ch);
				if (closed != null && closed.type == ItemType.CALL_SEGMENT) {
					pendingCallSegment = new PendingCallSegment(
							closed.className,
							closed.receiverVarKey,
							closed.callableKind,
							closed.callableName,
							closed.groupIndex + 1
					);
				} else if (closed != null && closed.type != ItemType.GENERIC) {
					pendingCallSegment = null;
				}
			}
		}

		List<ScopeItem> items = new ArrayList<>(stack);
		for (int i = 0; i < items.size(); i++) {
			ScopeItem item = items.get(i);
			if (!isContainerItem(item)) {
				continue;
			}
			if (!hasOnlyPseudoItemsAbove(items, i)) {
				continue;
			}
			TargetType targetType = resolveTargetType(item);
			if (targetType == TargetType.UNSUPPORTED) {
				continue;
			}
			return new ScopeContext(
					item.className,
					item.receiverVarKey,
					item.callableKind,
					item.callableName,
					targetType,
					collectPath(items, i)
			);
		}
		return null;
	}

	private static boolean hasOnlyPseudoItemsAbove(@NotNull List<ScopeItem> items, int containerIndex) {
		for (int i = 0; i < containerIndex; i++) {
			if (items.get(i).type != ItemType.PSEUDO) {
				return false;
			}
		}
		return true;
	}

	private static boolean isContainerItem(@NotNull ScopeItem item) {
		return item.type == ItemType.CALL_SEGMENT || item.type == ItemType.BUILTIN_PROPERTY;
	}

	private static @NotNull List<String> collectPath(@NotNull List<ScopeItem> items, int containerIndex) {
		List<String> path = new ArrayList<>();
		for (int i = containerIndex - 1; i >= 0; i--) {
			path.add(items.get(i).pseudoName);
		}
		return path;
	}

	private static @NotNull TargetType resolveTargetType(@NotNull ScopeItem item) {
		int separatorIndex = item.groupIndex + item.semicolonCount;
		return switch (separatorIndex) {
			case 0 -> TargetType.PARAMS;
			case 1 -> TargetType.COLON;
			case 2 -> TargetType.COLON2;
			case 3 -> TargetType.COLON3;
			default -> TargetType.UNSUPPORTED;
		};
	}

	private static @Nullable ScopeItem popMatching(@NotNull ArrayDeque<ScopeItem> stack, char closeBracket) {
		while (!stack.isEmpty()) {
			ScopeItem top = stack.pop();
			if (top.closeBracket == closeBracket) {
				return top;
			}
		}
		return null;
	}

	private static boolean isOpenBracket(char ch) {
		return ch == '[' || ch == '(' || ch == '{';
	}

	private static boolean isCloseBracket(char ch) {
		return ch == ']' || ch == ')' || ch == '}';
	}

	private static char getCloseBracket(char openBracket) {
		return switch (openBracket) {
			case '[' -> ']';
			case '(' -> ')';
			case '{' -> '}';
			default -> '\0';
		};
	}

	private static boolean isEscaped(@NotNull CharSequence text, int pos) {
		int count = 0;
		for (int i = pos - 1; i >= 0 && text.charAt(i) == '^'; i--) {
			count++;
		}
		return (count % 2) != 0;
	}

	private static @Nullable ParsedCall parseCall(@NotNull CharSequence text, int caretPos, int limit) {
		int i = caretPos + 1;
		if (i >= limit) {
			return null;
		}

		int nameStart = i;
		while (i < limit) {
			char ch = text.charAt(i);
			if (Character.isLetterOrDigit(ch) || ch == '_' || ch == ':' || ch == '-' || ch == '.') {
				i++;
			} else {
				break;
			}
		}
		if (i == nameStart) {
			return null;
		}

		String fullName = text.subSequence(nameStart, i).toString();
		CallableKind callableKind;
		String className = null;
		String receiverVarKey = null;
		String callableName;
		int doubleColonPos = fullName.indexOf("::");
		if (doubleColonPos > 0 && doubleColonPos < fullName.length() - 2) {
			callableKind = CallableKind.CONSTRUCTOR;
			className = fullName.substring(0, doubleColonPos);
			callableName = fullName.substring(doubleColonPos + 2);
		} else if (fullName.contains(".")) {
			int lastDotPos = fullName.lastIndexOf('.');
			if (lastDotPos <= 0 || lastDotPos == fullName.length() - 1) {
				return null;
			}
			callableKind = CallableKind.DYNAMIC_METHOD;
			receiverVarKey = fullName.substring(0, lastDotPos);
			callableName = fullName.substring(lastDotPos + 1);
		} else {
			int colonPos = fullName.indexOf(':');
			if (colonPos > 0 && colonPos < fullName.length() - 1) {
				callableKind = CallableKind.STATIC_METHOD;
				className = fullName.substring(0, colonPos);
				callableName = fullName.substring(colonPos + 1);
			} else {
				callableKind = CallableKind.OPERATOR;
				callableName = fullName;
			}
		}

		while (i < limit && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		if (i >= limit || !isOpenBracket(text.charAt(i))) {
			return null;
		}

		return new ParsedCall(className, receiverVarKey, callableKind, callableName, i, text.charAt(i));
	}

	private static @Nullable ParsedPseudo parsePseudo(@NotNull CharSequence text, int dollarPos, int limit) {
		int i = dollarPos + 2;
		if (i >= limit) {
			return null;
		}

		int nameStart = i;
		while (i < limit) {
			char ch = text.charAt(i);
			if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
				i++;
			} else {
				break;
			}
		}
		if (i == nameStart) {
			return null;
		}

		String name = text.subSequence(nameStart, i).toString();
		while (i < limit && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		if (i >= limit || !isOpenBracket(text.charAt(i))) {
			return null;
		}

		return new ParsedPseudo(name, i, text.charAt(i));
	}

	private static @Nullable ParsedBuiltinProperty parseBuiltinProperty(@NotNull CharSequence text, int dollarPos, int limit) {
		int i = dollarPos + 1;
		if (i >= limit) {
			return null;
		}

		int classStart = i;
		while (i < limit) {
			char ch = text.charAt(i);
			if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
				i++;
				continue;
			}
			break;
		}
		if (i == classStart || i >= limit || text.charAt(i) != ':') {
			return null;
		}
		String className = text.subSequence(classStart, i).toString();
		i++;

		int propertyStart = i;
		while (i < limit) {
			char ch = text.charAt(i);
			if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
				i++;
				continue;
			}
			break;
		}
		if (i == propertyStart) {
			return null;
		}
		String propertyName = text.subSequence(propertyStart, i).toString();

		while (i < limit && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		if (i >= limit || !isOpenBracket(text.charAt(i))) {
			return null;
		}

		return new ParsedBuiltinProperty(className, propertyName, i, text.charAt(i));
	}

	private static @NotNull RegistryData getRegistryData(@NotNull Project project) {
		RegistryData builtInData = getBuiltinRegistryData();
		String userJson = getUserConfigJson(project);
		if (userJson == null || userJson.isBlank()) {
			return builtInData;
		}

		synchronized (userDataCache) {
			RegistryData cachedUserData = userDataCache.get(userJson);
			if (cachedUserData != null) {
				return mergeRegistryData(builtInData, cachedUserData);
			}

			RegistryData parsedUserData = loadRegistryDataFromJson(userJson, true);
			userDataCache.put(userJson, parsedUserData);
			return mergeRegistryData(builtInData, parsedUserData);
		}
	}

	private static @Nullable String getUserConfigJson(@NotNull Project project) {
		Parser3PseudoHashCompletionService service = Parser3PseudoHashCompletionService.getInstance();
		String userJson = service.getConfigJson();
		if (userJson != null && !userJson.isBlank()) {
			return userJson;
		}

		String oldProjectJson = Parser3ProjectSettings.getInstance(project).getPseudoHashCompletionConfigJson();
		if (oldProjectJson != null && !oldProjectJson.isBlank()) {
			service.setConfigJson(oldProjectJson);
			return oldProjectJson;
		}
		return null;
	}

	private static @NotNull RegistryData getBuiltinRegistryData() {
		RegistryData data = cachedBuiltinData;
		if (data != null) {
			return data;
		}
		synchronized (P3PseudoHashCompletionRegistry.class) {
			data = cachedBuiltinData;
			if (data != null) {
				return data;
			}
			cachedBuiltinData = loadRegistryData();
			return cachedBuiltinData;
		}
	}

	private static @NotNull RegistryData loadRegistryData() {
		InputStream stream = P3PseudoHashCompletionRegistry.class.getClassLoader()
				.getResourceAsStream(CONFIG_RESOURCE_PATH);
		if (stream == null) {
			System.out.println("[P3PseudoHashCompletion] config not found: " + CONFIG_RESOURCE_PATH);
			return new RegistryData(Collections.emptyMap());
		}

		try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			input.transferTo(out);
			String json = out.toString(StandardCharsets.UTF_8);
			return loadRegistryDataFromJson(json, false);
		} catch (Exception e) {
			System.out.println("[P3PseudoHashCompletion] failed to load config: " + e.getMessage());
			return new RegistryData(Collections.emptyMap());
		}
	}

	private static @NotNull RegistryData loadRegistryDataFromJson(@NotNull String json, boolean userConfig) {
		List<Object> root = JsonParser.parseArray(json);
		validateConfigEntries(root, userConfig);

		Map<String, MethodSpec> methods = new LinkedHashMap<>();
		for (Object item : root) {
			if (!(item instanceof Map<?, ?> rawMap)) {
				continue;
			}
			for (MethodSpec method : parseMethodSpecs(rawMap)) {
				String key = key(method.className, method.callableKind, method.callableName, method.targetType);
				MethodSpec existing = methods.get(key);
				methods.put(key, existing == null ? method : mergeMethodSpec(existing, method));
			}
		}
		if (DEBUG) {
			System.out.println("[P3PseudoHashCompletion] loaded methods=" + methods.keySet());
		}
		return new RegistryData(Collections.unmodifiableMap(methods));
	}

	private static @NotNull RegistryData mergeRegistryData(@NotNull RegistryData builtInData, @NotNull RegistryData userData) {
		Map<String, MethodSpec> merged = new LinkedHashMap<>(builtInData.methodsByKey);
		for (Map.Entry<String, MethodSpec> entry : userData.methodsByKey.entrySet()) {
			MethodSpec existing = merged.get(entry.getKey());
			merged.put(entry.getKey(), existing == null ? entry.getValue() : mergeMethodSpec(existing, entry.getValue()));
		}
		return new RegistryData(Collections.unmodifiableMap(merged));
	}

	private static @NotNull MethodSpec mergeMethodSpec(@NotNull MethodSpec base, @NotNull MethodSpec extra) {
		return new MethodSpec(
				base.className,
				base.callableKind,
				base.callableName,
				base.targetType,
				mergeParams(base.params, extra.params),
				mergeTextValues(base.textValues, extra.textValues)
		);
	}

	private static @NotNull List<ParamSpec> mergeParams(@NotNull List<ParamSpec> base, @NotNull List<ParamSpec> extra) {
		Map<String, ParamSpec> merged = new LinkedHashMap<>();
		for (ParamSpec param : base) {
			merged.put(paramKey(param), param);
		}
		for (ParamSpec param : extra) {
			String key = paramKey(param);
			ParamSpec existing = merged.get(key);
			merged.put(key, existing == null ? param : mergeParamSpec(existing, param));
		}
		return Collections.unmodifiableList(new ArrayList<>(merged.values()));
	}

	private static @NotNull ParamSpec mergeParamSpec(@NotNull ParamSpec base, @NotNull ParamSpec extra) {
		String comment = extra.comment != null && !extra.comment.isBlank() ? extra.comment : base.comment;
		return new ParamSpec(
				base.name,
				base.brackets,
				comment,
				mergeParams(base.params, extra.params),
				mergeTextValues(base.textValues, extra.textValues)
		);
	}

	private static @NotNull String paramKey(@NotNull ParamSpec param) {
		return param.name.toLowerCase() + "|" + param.brackets;
	}

	private static @NotNull List<TextValueSpec> mergeTextValues(@NotNull List<TextValueSpec> base, @NotNull List<TextValueSpec> extra) {
		Map<String, TextValueSpec> merged = new LinkedHashMap<>();
		for (TextValueSpec value : base) {
			merged.put(value.text.toLowerCase(), value);
		}
		for (TextValueSpec value : extra) {
			String key = value.text.toLowerCase();
			TextValueSpec existing = merged.get(key);
			if (existing == null) {
				merged.put(key, value);
			} else {
				String comment = value.comment != null && !value.comment.isBlank() ? value.comment : existing.comment;
				merged.put(key, new TextValueSpec(existing.text, comment));
			}
		}
		return Collections.unmodifiableList(new ArrayList<>(merged.values()));
	}

	private static void validateConfigEntries(@NotNull List<Object> root, boolean userConfig) {
		for (int i = 0; i < root.size(); i++) {
			Object entry = root.get(i);
			if (!(entry instanceof Map<?, ?> rawMap)) {
				throw new IllegalArgumentException("элемент #" + (i + 1) + " должен быть JSON-объектом");
			}
			validateConfigEntry(rawMap, i + 1, userConfig);
		}
	}

	private static void validateConfigEntry(@NotNull Map<?, ?> rawMap, int index, boolean userConfig) {
		Object targetsValue = rawMap.get("targets");
		if (!(targetsValue instanceof List<?> targets) || targets.isEmpty()) {
			throw new IllegalArgumentException("элемент #" + index + ": нужно указать непустой массив targets");
		}

		for (Object targetValue : targets) {
			if (!(targetValue instanceof Map<?, ?> targetMap)) {
				throw new IllegalArgumentException("элемент #" + index + ": target должен быть JSON-объектом");
			}
			validateTarget(index, targetMap);
		}

		validateParams(index, rawMap.get("params"));
		validateParamText(index, rawMap.get("paramText"));
		if (userConfig && rawMap.get("params") == null && rawMap.get("paramText") == null) {
			throw new IllegalArgumentException("элемент #" + index + ": нужно указать params или paramText");
		}
	}

	private static void validateTarget(int index, @NotNull Map<?, ?> rawMap) {
		String staticMethod = asString(rawMap.get("staticMethod"));
		String constructor = asString(rawMap.get("constructor"));
		String dynamicMethod = asString(rawMap.get("dynamicMethod"));
		String operator = asString(rawMap.get("operator"));
		String builtinProperty = asString(rawMap.get("builtinProperty"));
		int callableCount = countNotBlank(staticMethod) + countNotBlank(constructor) + countNotBlank(dynamicMethod) + countNotBlank(operator) + countNotBlank(builtinProperty);
		if (callableCount != 1) {
			throw new IllegalArgumentException("элемент #" + index + ": в target нужно указать ровно одно из staticMethod, constructor, dynamicMethod, operator, builtinProperty");
		}
		if (operator == null || operator.isBlank()) {
			String className = asString(rawMap.get("class"));
			if (className == null || className.isBlank()) {
				throw new IllegalArgumentException("элемент #" + index + ": для target с классом нужно указать поле class");
			}
		}
		TargetType type = TargetType.fromConfigValue(asString(rawMap.get("type")));
		if (type == TargetType.UNSUPPORTED) {
			throw new IllegalArgumentException("элемент #" + index + ": неизвестный type");
		}
	}

	private static void validateParams(int index, @Nullable Object paramsValue) {
		if (paramsValue == null) {
			return;
		}
		if (!(paramsValue instanceof List<?> params)) {
			throw new IllegalArgumentException("элемент #" + index + ": params должен быть массивом");
		}
		for (Object paramValue : params) {
			if (!(paramValue instanceof Map<?, ?> paramMap)) {
				throw new IllegalArgumentException("элемент #" + index + ": каждый param должен быть JSON-объектом");
			}
			String name = asString(paramMap.get("name"));
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("элемент #" + index + ": у param должно быть непустое name");
			}
			String brackets = asString(paramMap.get("brackets"));
			if (brackets != null && brackets.length() != 2) {
				throw new IllegalArgumentException("элемент #" + index + ": brackets у param должны состоять из двух символов");
			}
			validateParams(index, paramMap.get("params"));
			validateParamText(index, paramMap.get("paramText"));
		}
	}

	private static void validateParamText(int index, @Nullable Object valuesValue) {
		if (valuesValue == null) {
			return;
		}
		if (!(valuesValue instanceof List<?> values)) {
			throw new IllegalArgumentException("элемент #" + index + ": paramText должен быть массивом");
		}
		for (Object value : values) {
			if (value instanceof String textValue) {
				if (textValue.isBlank()) {
					throw new IllegalArgumentException("элемент #" + index + ": в paramText не должно быть пустых строк");
				}
				continue;
			}
			if (!(value instanceof Map<?, ?> valueMap)) {
				throw new IllegalArgumentException("элемент #" + index + ": каждый элемент paramText должен быть строкой или JSON-объектом");
			}
			String text = asString(valueMap.get("text"));
			if (text == null || text.isBlank()) {
				throw new IllegalArgumentException("элемент #" + index + ": у paramText должно быть непустое text");
			}
		}
	}

	private static @NotNull List<MethodSpec> parseMethodSpecs(@NotNull Map<?, ?> rawMap) {
		List<ParamSpec> params = parseParams(rawMap.get("params"));
		List<TextValueSpec> textValues = parseTextValues(rawMap.get("paramText"));

		List<MethodTarget> targets = parseTargets(rawMap.get("targets"));
		if (targets.isEmpty()) {
			MethodTarget singleTarget = parseTarget(rawMap);
			if (singleTarget != null) {
				targets = List.of(singleTarget);
			}
		}

		if (targets.isEmpty()) {
			return Collections.emptyList();
		}

		List<MethodSpec> result = new ArrayList<>();
		for (MethodTarget target : targets) {
			result.add(new MethodSpec(target.className, target.callableKind, target.callableName, target.targetType, params, textValues));
		}
		return Collections.unmodifiableList(result);
	}

	private static @NotNull List<MethodTarget> parseTargets(@Nullable Object value) {
		if (!(value instanceof List<?> rawList)) {
			return Collections.emptyList();
		}

		List<MethodTarget> result = new ArrayList<>();
		for (Object item : rawList) {
			if (!(item instanceof Map<?, ?> rawMap)) {
				continue;
			}
			MethodTarget target = parseTarget(rawMap);
			if (target != null) {
				result.add(target);
			}
		}
		return Collections.unmodifiableList(result);
	}

	private static @Nullable MethodTarget parseTarget(@NotNull Map<?, ?> rawMap) {
		String className = asString(rawMap.get("class"));
		String staticMethod = asString(rawMap.get("staticMethod"));
		String constructor = asString(rawMap.get("constructor"));
		String dynamicMethod = asString(rawMap.get("dynamicMethod"));
		String operator = asString(rawMap.get("operator"));
		String builtinProperty = asString(rawMap.get("builtinProperty"));
		int kindsCount = countNotBlank(staticMethod) + countNotBlank(constructor) + countNotBlank(dynamicMethod) + countNotBlank(operator) + countNotBlank(builtinProperty);
		if (kindsCount != 1) {
			return null;
		}

		CallableKind callableKind;
		String callableName;
		if (operator != null && !operator.isEmpty()) {
			callableKind = CallableKind.OPERATOR;
			callableName = operator;
			className = null;
		} else {
			if (className == null || className.isEmpty()) {
				return null;
			}
			if (staticMethod != null && !staticMethod.isEmpty()) {
				callableKind = CallableKind.STATIC_METHOD;
				callableName = staticMethod;
			} else if (constructor != null && !constructor.isEmpty()) {
				callableKind = CallableKind.CONSTRUCTOR;
				callableName = constructor;
			} else if (builtinProperty != null && !builtinProperty.isEmpty()) {
				callableKind = CallableKind.BUILTIN_PROPERTY;
				callableName = builtinProperty;
			} else {
				callableKind = CallableKind.DYNAMIC_METHOD;
				callableName = dynamicMethod;
			}
		}
		TargetType targetType = TargetType.fromConfigValue(asString(rawMap.get("type")));
		return new MethodTarget(className, callableKind, callableName, targetType);
	}

	private static int countNotBlank(@Nullable String value) {
		return value != null && !value.isBlank() ? 1 : 0;
	}

	private static @NotNull List<ParamSpec> parseParams(@Nullable Object value) {
		if (!(value instanceof List<?> rawList)) {
			return Collections.emptyList();
		}

		List<ParamSpec> result = new ArrayList<>();
		for (Object item : rawList) {
			if (!(item instanceof Map<?, ?> rawMap)) {
				continue;
			}
			String name = asString(rawMap.get("name"));
			if (name == null || name.isEmpty()) {
				continue;
			}
			String brackets = asString(rawMap.get("brackets"));
			if (brackets == null || brackets.length() != 2) {
				brackets = "[]";
			}
			String comment = asString(rawMap.get("comment"));
			result.add(new ParamSpec(
					name,
					brackets,
					comment,
					parseParams(rawMap.get("params")),
					parseTextValues(rawMap.get("paramText"))
			));
		}
		return Collections.unmodifiableList(result);
	}

	private static @NotNull List<TextValueSpec> parseTextValues(@Nullable Object value) {
		if (!(value instanceof List<?> rawList)) {
			return Collections.emptyList();
		}

		List<TextValueSpec> result = new ArrayList<>();
		for (Object item : rawList) {
			if (item instanceof String text && !text.isBlank()) {
				result.add(new TextValueSpec(text, null));
				continue;
			}
			if (!(item instanceof Map<?, ?> rawMap)) {
				continue;
			}
			String text = asString(rawMap.get("text"));
			if (text == null || text.isBlank()) {
				continue;
			}
			String comment = asString(rawMap.get("comment"));
			result.add(new TextValueSpec(text, comment));
		}
		return Collections.unmodifiableList(result);
	}

	private static @Nullable String asString(@Nullable Object value) {
		return value instanceof String ? (String) value : null;
	}

	private static @NotNull String key(
			@Nullable String className,
			@NotNull CallableKind callableKind,
			@NotNull String callableName,
			@NotNull TargetType targetType
	) {
		String baseKey = switch (callableKind) {
			case STATIC_METHOD -> className.toLowerCase() + ":" + callableName.toLowerCase();
			case CONSTRUCTOR -> className.toLowerCase() + "::" + callableName.toLowerCase();
			case DYNAMIC_METHOD -> className.toLowerCase() + "." + callableName.toLowerCase();
			case OPERATOR -> "^" + callableName.toLowerCase();
			case BUILTIN_PROPERTY -> "$" + className.toLowerCase() + ":" + callableName.toLowerCase();
		};
		return baseKey + "#" + targetType.configValue;
	}

	private enum ItemType {
		CALL_SEGMENT,
		BUILTIN_PROPERTY,
		PSEUDO,
		GENERIC
	}

	private enum CallableKind {
		STATIC_METHOD,
		CONSTRUCTOR,
		DYNAMIC_METHOD,
		BUILTIN_PROPERTY,
		OPERATOR
	}

	private enum TargetType {
		PARAMS("params"),
		COLON("colon"),
		COLON2("colon2"),
		COLON3("colon3"),
		OPTIONS("options"),
		UNSUPPORTED("unsupported");

		final @NotNull String configValue;

		TargetType(@NotNull String configValue) {
			this.configValue = configValue;
		}

		static @NotNull TargetType fromConfigValue(@Nullable String value) {
			if (value == null || value.isBlank() || "params".equalsIgnoreCase(value)) {
				return PARAMS;
			}
			for (TargetType type : values()) {
				if (type.configValue.equalsIgnoreCase(value)) {
					return type;
				}
			}
			return UNSUPPORTED;
		}
	}

	private static final class ScopeItem {
		final @NotNull ItemType type;
		final int openBracket;
		final char openBracketChar;
		final char closeBracket;
		final @Nullable String className;
		final @Nullable String receiverVarKey;
		final @Nullable CallableKind callableKind;
		final @Nullable String callableName;
		final @Nullable String pseudoName;
		final int groupIndex;
		int semicolonCount;

		private ScopeItem(
				@NotNull ItemType type,
				int openBracket,
				char openBracketChar,
				char closeBracket,
				@Nullable String className,
				@Nullable String receiverVarKey,
				@Nullable CallableKind callableKind,
				@Nullable String callableName,
				@Nullable String pseudoName,
				int groupIndex
		) {
			this.type = type;
			this.openBracket = openBracket;
			this.openBracketChar = openBracketChar;
			this.closeBracket = closeBracket;
			this.className = className;
			this.receiverVarKey = receiverVarKey;
			this.callableKind = callableKind;
			this.callableName = callableName;
			this.pseudoName = pseudoName;
			this.groupIndex = groupIndex;
		}

		static @NotNull ScopeItem callSegment(@NotNull ParsedCall call, int groupIndex) {
			return new ScopeItem(
					ItemType.CALL_SEGMENT,
					call.openBracket,
					call.openBracketChar,
					getCloseBracket(call.openBracketChar),
					call.className,
					call.receiverVarKey,
					call.callableKind,
					call.callableName,
					null,
					groupIndex
			);
		}

		static @NotNull ScopeItem callSegment(int openBracket, char openBracketChar, @NotNull PendingCallSegment pendingCallSegment) {
			return new ScopeItem(
					ItemType.CALL_SEGMENT,
					openBracket,
					openBracketChar,
					getCloseBracket(openBracketChar),
					pendingCallSegment.className,
					pendingCallSegment.receiverVarKey,
					pendingCallSegment.callableKind,
					pendingCallSegment.callableName,
					null,
					pendingCallSegment.groupIndex
			);
		}

		static @NotNull ScopeItem pseudo(@NotNull ParsedPseudo pseudo) {
			return new ScopeItem(ItemType.PSEUDO, pseudo.openBracket, pseudo.openBracketChar, getCloseBracket(pseudo.openBracketChar), null, null, null, null, pseudo.name, -1);
		}

		static @NotNull ScopeItem builtinProperty(@NotNull ParsedBuiltinProperty property) {
			return new ScopeItem(
					ItemType.BUILTIN_PROPERTY,
					property.openBracket,
					property.openBracketChar,
					getCloseBracket(property.openBracketChar),
					property.className,
					null,
					CallableKind.BUILTIN_PROPERTY,
					property.propertyName,
					null,
					0
			);
		}

		static @NotNull ScopeItem generic(int openBracket, char openBracketChar) {
			return new ScopeItem(ItemType.GENERIC, openBracket, openBracketChar, getCloseBracket(openBracketChar), null, null, null, null, null, -1);
		}
	}

	private static final class ParsedCall {
		final @Nullable String className;
		final @Nullable String receiverVarKey;
		final @NotNull CallableKind callableKind;
		final @NotNull String callableName;
		final int openBracket;
		final char openBracketChar;

		ParsedCall(
				@Nullable String className,
				@Nullable String receiverVarKey,
				@NotNull CallableKind callableKind,
				@NotNull String callableName,
				int openBracket,
				char openBracketChar
		) {
			this.className = className;
			this.receiverVarKey = receiverVarKey;
			this.callableKind = callableKind;
			this.callableName = callableName;
			this.openBracket = openBracket;
			this.openBracketChar = openBracketChar;
		}
	}

	private static final class ParsedPseudo {
		final @NotNull String name;
		final int openBracket;
		final char openBracketChar;

		ParsedPseudo(@NotNull String name, int openBracket, char openBracketChar) {
			this.name = name;
			this.openBracket = openBracket;
			this.openBracketChar = openBracketChar;
		}
	}

	private static final class ParsedBuiltinProperty {
		final @NotNull String className;
		final @NotNull String propertyName;
		final int openBracket;
		final char openBracketChar;

		ParsedBuiltinProperty(
				@NotNull String className,
				@NotNull String propertyName,
				int openBracket,
				char openBracketChar
		) {
			this.className = className;
			this.propertyName = propertyName;
			this.openBracket = openBracket;
			this.openBracketChar = openBracketChar;
		}
	}

	private static final class ScopeContext {
		final @Nullable String className;
		final @Nullable String receiverVarKey;
		final @NotNull CallableKind callableKind;
		final @NotNull String callableName;
		final @NotNull TargetType targetType;
		final @NotNull List<String> path;

		ScopeContext(
				@Nullable String className,
				@Nullable String receiverVarKey,
				@NotNull CallableKind callableKind,
				@NotNull String callableName,
				@NotNull TargetType targetType,
				@NotNull List<String> path
		) {
			this.className = className;
			this.receiverVarKey = receiverVarKey;
			this.callableKind = callableKind;
			this.callableName = callableName;
			this.targetType = targetType;
			this.path = path;
		}

		@NotNull ScopeContext withResolvedClassName(@NotNull String resolvedClassName) {
			return new ScopeContext(resolvedClassName, receiverVarKey, callableKind, callableName, targetType, path);
		}

		@NotNull String getPresentableTarget() {
			return switch (callableKind) {
				case STATIC_METHOD -> className + ":" + callableName;
				case CONSTRUCTOR -> className + "::" + callableName;
				case DYNAMIC_METHOD -> className + "." + callableName;
				case OPERATOR -> callableName;
				case BUILTIN_PROPERTY -> "$" + className + ":" + callableName;
			};
		}
	}

	private static final class RegistryData {
		final @NotNull Map<String, MethodSpec> methodsByKey;

		RegistryData(@NotNull Map<String, MethodSpec> methodsByKey) {
			this.methodsByKey = methodsByKey;
		}
	}

	private static final class MethodSpec {
		final @Nullable String className;
		final @NotNull CallableKind callableKind;
		final @NotNull String callableName;
		final @NotNull TargetType targetType;
		final @NotNull List<ParamSpec> params;
		final @NotNull List<TextValueSpec> textValues;

		MethodSpec(
				@Nullable String className,
				@NotNull CallableKind callableKind,
				@NotNull String callableName,
				@NotNull TargetType targetType,
				@NotNull List<ParamSpec> params,
				@NotNull List<TextValueSpec> textValues
		) {
			this.className = className;
			this.callableKind = callableKind;
			this.callableName = callableName;
			this.targetType = targetType;
			this.params = params;
			this.textValues = textValues;
		}
	}

	private static final class MethodTarget {
		final @Nullable String className;
		final @NotNull CallableKind callableKind;
		final @NotNull String callableName;
		final @NotNull TargetType targetType;

		MethodTarget(
				@Nullable String className,
				@NotNull CallableKind callableKind,
				@NotNull String callableName,
				@NotNull TargetType targetType
		) {
			this.className = className;
			this.callableKind = callableKind;
			this.callableName = callableName;
			this.targetType = targetType;
		}
	}

	private static final class PendingCallSegment {
		final @Nullable String className;
		final @Nullable String receiverVarKey;
		final @NotNull CallableKind callableKind;
		final @NotNull String callableName;
		final int groupIndex;

		PendingCallSegment(
				@Nullable String className,
				@Nullable String receiverVarKey,
				@NotNull CallableKind callableKind,
				@NotNull String callableName,
				int groupIndex
		) {
			this.className = className;
			this.receiverVarKey = receiverVarKey;
			this.callableKind = callableKind;
			this.callableName = callableName;
			this.groupIndex = groupIndex;
		}
	}

	private static final class ParamSpec {
		final @NotNull String name;
		final @NotNull String brackets;
		final @Nullable String comment;
		final @NotNull List<ParamSpec> params;
		final @NotNull List<TextValueSpec> textValues;

		ParamSpec(
				@NotNull String name,
				@NotNull String brackets,
				@Nullable String comment,
				@NotNull List<ParamSpec> params,
				@NotNull List<TextValueSpec> textValues
		) {
			this.name = name;
			this.brackets = brackets;
			this.comment = comment;
			this.params = params;
			this.textValues = textValues;
		}
	}

	private static final class TextValueSpec {
		final @NotNull String text;
		final @Nullable String comment;

		TextValueSpec(@NotNull String text, @Nullable String comment) {
			this.text = text;
			this.comment = comment;
		}
	}

	private static final class BracketTailInfo {
		final char openBracket;
		final int endOffset;
		final boolean isEmpty;

		BracketTailInfo(char openBracket, int endOffset, boolean isEmpty) {
			this.openBracket = openBracket;
			this.endOffset = endOffset;
			this.isEmpty = isEmpty;
		}
	}

	private static final class TextValueContext {
		final @NotNull String typedPrefix;
		final int tokenStartOffset;

		TextValueContext(@NotNull String typedPrefix, int tokenStartOffset) {
			this.typedPrefix = typedPrefix;
			this.tokenStartOffset = tokenStartOffset;
		}
	}

	/**
	 * Минимальный JSON-парсер под узкую схему конфига.
	 */
	private static final class JsonParser {
		private final @NotNull String text;
		private int pos;

		private JsonParser(@NotNull String text) {
			this.text = text;
		}

		static @NotNull List<Object> parseArray(@NotNull String text) {
			JsonParser parser = new JsonParser(text);
			parser.skipWhitespace();
			if (parser.pos >= parser.text.length() || parser.text.charAt(parser.pos) != '[') {
				throw new IllegalArgumentException("корень должен быть JSON-массивом");
			}
			Object value = parser.parseValue();
			if (!(value instanceof List<?> list)) {
				throw new IllegalArgumentException("корень должен быть JSON-массивом");
			}
			parser.skipWhitespace();
			if (parser.pos != parser.text.length()) {
				throw new IllegalArgumentException("лишние символы после конца JSON");
			}
			@SuppressWarnings("unchecked")
			List<Object> result = (List<Object>) list;
			return result;
		}

		private @Nullable Object parseValue() {
			skipWhitespace();
			if (pos >= text.length()) {
				return null;
			}

			char ch = text.charAt(pos);
			return switch (ch) {
				case '[' -> parseArrayValue();
				case '{' -> parseObjectValue();
				case '"' -> parseString();
				case 't' -> parseLiteral("true", Boolean.TRUE);
				case 'f' -> parseLiteral("false", Boolean.FALSE);
				case 'n' -> parseLiteral("null", null);
				default -> parseBareValue();
			};
		}

		private @NotNull List<Object> parseArrayValue() {
			pos++;
			List<Object> result = new ArrayList<>();
			while (true) {
				skipWhitespace();
				if (pos >= text.length()) {
					return result;
				}
				if (text.charAt(pos) == ']') {
					pos++;
					return result;
				}
				result.add(parseValue());
				skipWhitespace();
				if (pos < text.length() && text.charAt(pos) == ',') {
					pos++;
				}
			}
		}

		private @NotNull Map<String, Object> parseObjectValue() {
			pos++;
			Map<String, Object> result = new LinkedHashMap<>();
			while (true) {
				skipWhitespace();
				if (pos >= text.length()) {
					return result;
				}
				if (text.charAt(pos) == '}') {
					pos++;
					return result;
				}
				String key = parseString();
				skipWhitespace();
				if (pos < text.length() && text.charAt(pos) == ':') {
					pos++;
				}
				Object value = parseValue();
				result.put(key, value);
				skipWhitespace();
				if (pos < text.length() && text.charAt(pos) == ',') {
					pos++;
				}
			}
		}

		private @NotNull String parseString() {
			if (pos >= text.length() || text.charAt(pos) != '"') {
				return "";
			}
			pos++;
			StringBuilder result = new StringBuilder();
			while (pos < text.length()) {
				char ch = text.charAt(pos++);
				if (ch == '"') {
					return result.toString();
				}
				if (ch == '\\' && pos < text.length()) {
					char escaped = text.charAt(pos++);
					switch (escaped) {
						case '"', '\\', '/' -> result.append(escaped);
						case 'b' -> result.append('\b');
						case 'f' -> result.append('\f');
						case 'n' -> result.append('\n');
						case 'r' -> result.append('\r');
						case 't' -> result.append('\t');
						case 'u' -> {
							if (pos + 4 <= text.length()) {
								String hex = text.substring(pos, pos + 4);
								pos += 4;
								try {
									result.append((char) Integer.parseInt(hex, 16));
								} catch (NumberFormatException e) {
									System.out.println("[P3PseudoHashCompletion] invalid unicode escape: " + hex);
								}
							}
						}
						default -> result.append(escaped);
					}
				} else {
					result.append(ch);
				}
			}
			return result.toString();
		}

		private @Nullable Object parseLiteral(@NotNull String literal, @Nullable Object value) {
			if (text.startsWith(literal, pos)) {
				pos += literal.length();
				return value;
			}
			throw new IllegalArgumentException("неожиданное значение в позиции " + pos);
		}

		private @Nullable Object parseBareValue() {
			int start = pos;
			while (pos < text.length()) {
				char ch = text.charAt(pos);
				if (ch == ',' || ch == ']' || ch == '}' || Character.isWhitespace(ch)) {
					break;
				}
				pos++;
			}
			String token = text.substring(start, pos);
			if (token.isEmpty()) {
				throw new IllegalArgumentException("неожиданный символ в позиции " + pos);
			}
			try {
				if (token.contains(".")) {
					return Double.parseDouble(token);
				}
				return Long.parseLong(token);
			} catch (NumberFormatException e) {
				return token;
			}
		}

		private void skipWhitespace() {
			while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
				pos++;
			}
		}
	}
}
