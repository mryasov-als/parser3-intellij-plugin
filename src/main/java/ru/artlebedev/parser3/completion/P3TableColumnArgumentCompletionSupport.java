package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertHandler;
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
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.List;

/**
 * Completion имён колонок таблицы в аргументах методов table.hash/table.array/table.locate/table.rename.
 */
public final class P3TableColumnArgumentCompletionSupport {

	private static final double COLUMN_ARGUMENT_PRIORITY = 140;
	private static final boolean DEBUG_PERF = false;

	private P3TableColumnArgumentCompletionSupport() {
	}

	static boolean addCompletions(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull String text,
			int cursorOffset,
			@NotNull CompletionResultSet result
	) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		long contextStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		TableColumnArgumentContext context = findContext(text, cursorOffset);
		if (DEBUG_PERF) {
			System.out.println("[P3TableColumnCompletion.PERF] findContext: "
					+ (System.currentTimeMillis() - contextStart) + "ms"
					+ " file=" + currentFile.getName()
					+ " offset=" + cursorOffset
					+ " matched=" + (context != null));
		}
		if (context == null) {
			if (DEBUG_PERF) {
				System.out.println("[P3TableColumnCompletion.PERF] addCompletions TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " route=noContext"
						+ " file=" + currentFile.getName());
			}
			return false;
		}

		long resolveStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		List<String> columns = resolveColumns(project, currentFile, cursorOffset, context);
		if (DEBUG_PERF) {
			System.out.println("[P3TableColumnCompletion.PERF] resolveColumns: "
					+ (System.currentTimeMillis() - resolveStart) + "ms"
					+ " receiver=" + context.receiverVarKey
					+ " columns=" + columns.size()
					+ " file=" + currentFile.getName());
		}
		if (columns.isEmpty()) {
			if (DEBUG_PERF) {
				System.out.println("[P3TableColumnCompletion.PERF] addCompletions TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " route=noColumns"
						+ " file=" + currentFile.getName());
			}
			return false;
		}

		CompletionResultSet columnResult = result.withPrefixMatcher(context.typedPrefix);
		for (String column : columns) {
			LookupElementBuilder element = LookupElementBuilder
					.create(column)
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText("колонка table", true);
			if (context.pseudoHashKey) {
				element = element
						.withPresentableText("$." + column + "[]")
						.withInsertHandler(createPseudoHashKeyInsertHandler());
			}
			columnResult.addElement(PrioritizedLookupElement.withPriority(element, COLUMN_ARGUMENT_PRIORITY));
		}
		if (DEBUG_PERF) {
			System.out.println("[P3TableColumnCompletion.PERF] addCompletions TOTAL: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " route=columns"
					+ " added=" + columns.size()
					+ " prefix='" + context.typedPrefix + "'"
					+ " file=" + currentFile.getName());
		}
		return true;
	}

	public static boolean shouldAutoPopupOnTypedChar(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull String editorText,
			int cursorOffset,
			char typeChar
	) {
		if (!isAutoPopupChar(typeChar)) return false;

		String textForAnalysis = buildTextWithTypedChar(editorText, cursorOffset, typeChar);
		int analysisOffset = Math.min(textForAnalysis.length(), Math.max(0, cursorOffset + 1));
		return isColumnArgumentContext(project, currentFile, textForAnalysis, analysisOffset);
	}

	public static boolean isColumnArgumentContext(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull String text,
			int cursorOffset
	) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		TableColumnArgumentContext context = findContext(text, cursorOffset);
		boolean result = context != null && !resolveColumns(project, currentFile, cursorOffset, context).isEmpty();
		if (DEBUG_PERF) {
			System.out.println("[P3TableColumnCompletion.PERF] isColumnArgumentContext: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " file=" + currentFile.getName()
					+ " offset=" + cursorOffset
					+ " matched=" + result);
		}
		return result;
	}

	private static @NotNull List<String> resolveColumns(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull TableColumnArgumentContext context
	) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		P3VariableIndex variableIndex = P3VariableIndex.getInstance(project);
		long scopeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		List<VirtualFile> visibleFiles = new P3ScopeContext(project, currentFile, cursorOffset).getVariableSearchFiles();
		if (DEBUG_PERF) {
			System.out.println("[P3TableColumnCompletion.PERF] resolveColumns scopeContext: "
					+ (System.currentTimeMillis() - scopeStart) + "ms"
					+ " visibleFiles=" + visibleFiles.size()
					+ " receiver=" + context.receiverVarKey
					+ " file=" + currentFile.getName());
		}
		long chainStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		P3VariableIndex.ChainResolveInfo receiver =
				variableIndex.resolveEffectiveChain(context.receiverVarKey, visibleFiles, currentFile, cursorOffset);
		if (DEBUG_PERF) {
			System.out.println("[P3TableColumnCompletion.PERF] resolveColumns resolveEffectiveChain: "
					+ (System.currentTimeMillis() - chainStart) + "ms"
					+ " receiver=" + context.receiverVarKey
					+ " resolved=" + (receiver != null)
					+ " columns=" + (receiver != null && receiver.columns != null ? receiver.columns.size() : 0)
					+ " file=" + currentFile.getName());
		}
		if (receiver == null || receiver.columns == null || receiver.columns.isEmpty()) {
			if (DEBUG_PERF) {
				System.out.println("[P3TableColumnCompletion.PERF] resolveColumns TOTAL: "
						+ (System.currentTimeMillis() - startTime) + "ms"
						+ " result=0"
						+ " receiver=" + context.receiverVarKey
						+ " file=" + currentFile.getName());
			}
			return List.of();
		}
		if (DEBUG_PERF) {
			System.out.println("[P3TableColumnCompletion.PERF] resolveColumns TOTAL: "
					+ (System.currentTimeMillis() - startTime) + "ms"
					+ " result=" + receiver.columns.size()
					+ " receiver=" + context.receiverVarKey
					+ " file=" + currentFile.getName());
		}
		return receiver.columns;
	}

	private static boolean isAutoPopupChar(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '_' || ch == '[' || ch == '(' || ch == '$' || ch == '.' || ch == ';';
	}

	private static @NotNull String buildTextWithTypedChar(@NotNull String editorText, int cursorOffset, char typeChar) {
		int safeOffset = Math.max(0, Math.min(cursorOffset, editorText.length()));
		return editorText.substring(0, safeOffset) + typeChar + editorText.substring(safeOffset);
	}

	private static @Nullable TableColumnArgumentContext findContext(@NotNull String text, int cursorOffset) {
		if (cursorOffset < 0 || cursorOffset > text.length()) return null;

		int openPos = findCurrentOpenBracket(text, cursorOffset);
		if (openPos < 0) return null;
		char open = text.charAt(openPos);
		if (open != '[' && open != '(') return null;

		CallContext callContext = resolveCallContext(text, openPos, cursorOffset);
		if (callContext == null) return null;
		if (!isColumnArgument(callContext.methodName, callContext.argumentIndex)) {
			return null;
		}

		int prefixStart = findTypedPrefixStart(text, openPos + 1, cursorOffset);
		boolean pseudoHashKey = "rename".equals(callContext.methodName) && isAfterPseudoHashKeyPrefix(text, openPos + 1, prefixStart);
		String typedPrefix = text.substring(prefixStart, cursorOffset);
		return new TableColumnArgumentContext(callContext.receiverVarKey, typedPrefix, pseudoHashKey);
	}

	private static boolean isColumnArgument(@NotNull String methodName, int argumentIndex) {
		if ("hash".equals(methodName)) {
			return argumentIndex == 0 || argumentIndex == 1;
		}
		if ("array".equals(methodName)
				|| "locate".equals(methodName)
				|| "rename".equals(methodName)) {
			return argumentIndex == 0;
		}
		return false;
	}

	private static @Nullable CallContext resolveCallContext(@NotNull String text, int currentOpenPos, int cursorOffset) {
		int currentArgumentIndex = currentArgumentIndex(text, currentOpenPos + 1, cursorOffset);
		int previousArguments = 0;
		int groupOpenPos = currentOpenPos;
		while (groupOpenPos >= 0) {
			CallInfo call = parseCallBeforeOpenBracket(text, groupOpenPos);
			if (call != null) {
				return new CallContext(call.receiverVarKey, call.methodName, previousArguments + currentArgumentIndex);
			}

			int previousClosePos = previousNonWhitespace(text, groupOpenPos - 1);
			if (previousClosePos < 0) return null;
			char close = text.charAt(previousClosePos);
			int previousOpenPos = findMatchingOpenForClose(text, previousClosePos);
			if (previousOpenPos < 0) return null;

			previousArguments += countArgumentsInFinishedGroup(text, previousOpenPos + 1, previousClosePos, close);
			groupOpenPos = previousOpenPos;
		}
		return null;
	}

	private static int previousNonWhitespace(@NotNull String text, int pos) {
		for (int i = Math.min(pos, text.length() - 1); i >= 0; i--) {
			if (!Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	private static int findMatchingOpenForClose(@NotNull String text, int closePos) {
		if (closePos < 0 || closePos >= text.length()) return -1;
		char close = text.charAt(closePos);
		char open;
		if (close == ']') open = '[';
		else if (close == ')') open = '(';
		else if (close == '}') open = '{';
		else return -1;

		int depth = 0;
		for (int pos = closePos; pos >= 0; pos--) {
			char ch = text.charAt(pos);
			if (ch == close) {
				depth++;
			} else if (ch == open) {
				depth--;
				if (depth == 0) {
					return pos;
				}
			}
		}
		return -1;
	}

	private static int countArgumentsInFinishedGroup(@NotNull String text, int from, int to, char close) {
		boolean hasContent = false;
		for (int pos = from; pos < to; pos++) {
			if (!Character.isWhitespace(text.charAt(pos))) {
				hasContent = true;
				break;
			}
		}
		if (!hasContent) return 0;
		if (close == '}') return 1;
		return currentArgumentIndex(text, from, to) + 1;
	}

	private static int findCurrentOpenBracket(@NotNull String text, int cursorOffset) {
		int depthSquare = 0;
		int depthRound = 0;
		int depthCurly = 0;
		for (int pos = cursorOffset - 1; pos >= 0; pos--) {
			char ch = text.charAt(pos);
			if (ch == ']') {
				depthSquare++;
			} else if (ch == '[') {
				if (depthSquare == 0 && depthRound == 0 && depthCurly == 0) return pos;
				if (depthSquare > 0) depthSquare--;
			} else if (ch == ')') {
				depthRound++;
			} else if (ch == '(') {
				if (depthSquare == 0 && depthRound == 0 && depthCurly == 0) return pos;
				if (depthRound > 0) depthRound--;
			} else if (ch == '}') {
				depthCurly++;
			} else if (ch == '{') {
				if (depthCurly > 0) depthCurly--;
			} else if ((ch == '\n' || ch == '\r') && depthSquare == 0 && depthRound == 0 && depthCurly == 0) {
				break;
			}
		}
		return -1;
	}

	private static int currentArgumentIndex(@NotNull String text, int from, int to) {
		int argIndex = 0;
		int depthSquare = 0;
		int depthRound = 0;
		int depthCurly = 0;
		for (int pos = from; pos < to; pos++) {
			char ch = text.charAt(pos);
			if (ch == '[') depthSquare++;
			else if (ch == ']' && depthSquare > 0) depthSquare--;
			else if (ch == '(') depthRound++;
			else if (ch == ')' && depthRound > 0) depthRound--;
			else if (ch == '{') depthCurly++;
			else if (ch == '}' && depthCurly > 0) depthCurly--;
			else if (ch == ';' && depthSquare == 0 && depthRound == 0 && depthCurly == 0) argIndex++;
		}
		return argIndex;
	}

	private static @Nullable CallInfo parseCallBeforeOpenBracket(@NotNull String text, int openPos) {
		int pos = openPos - 1;
		while (pos >= 0 && Character.isWhitespace(text.charAt(pos))) pos--;
		int methodEnd = pos + 1;
		while (pos >= 0 && isIdentifierChar(text.charAt(pos))) pos--;
		int methodStart = pos + 1;
		if (methodStart >= methodEnd || pos < 0 || text.charAt(pos) != '.') return null;

		String methodName = text.substring(methodStart, methodEnd);
		int receiverEnd = pos;
		pos--;
		while (pos >= 0) {
			char ch = text.charAt(pos);
			if (isIdentifierChar(ch) || ch == '.' || ch == ':') {
				pos--;
				continue;
			}
			break;
		}
		int receiverStart = pos + 1;
		if (receiverStart >= receiverEnd) return null;
		if (pos < 0 || text.charAt(pos) != '^') return null;

		return new CallInfo(text.substring(receiverStart, receiverEnd), methodName);
	}

	private static int findTypedPrefixStart(@NotNull String text, int from, int to) {
		int start = to;
		while (start > from && isIdentifierChar(text.charAt(start - 1))) {
			start--;
		}
		return start;
	}

	private static boolean isAfterPseudoHashKeyPrefix(@NotNull String text, int from, int prefixStart) {
		int pos = prefixStart - 1;
		while (pos >= from && Character.isWhitespace(text.charAt(pos))) {
			pos--;
		}
		return pos >= from + 1 && text.charAt(pos) == '.' && text.charAt(pos - 1) == '$';
	}

	private static @NotNull InsertHandler<LookupElement> createPseudoHashKeyInsertHandler() {
		return (context, item) -> {
			Document doc = context.getDocument();
			int insertOffset = context.getTailOffset();
			CharSequence chars = doc.getCharsSequence();
			int endOffset = P3VariableInsertHandler.skipIdentifierChars(chars, insertOffset, chars.length());
			if (endOffset > insertOffset) {
				doc.deleteString(insertOffset, endOffset);
			}

			if (insertOffset < doc.getTextLength()) {
				char next = doc.getCharsSequence().charAt(insertOffset);
				if (next == '[' || next == '(' || next == '{') {
					context.getEditor().getCaretModel().moveToOffset(insertOffset + 1);
					return;
				}
			}

			doc.insertString(insertOffset, "[]");
			context.getEditor().getCaretModel().moveToOffset(insertOffset + 1);
		};
	}

	private static boolean isIdentifierChar(char ch) {
		return Character.isLetterOrDigit(ch) || ch == '_';
	}

	private static final class CallInfo {
		final @NotNull String receiverVarKey;
		final @NotNull String methodName;

		private CallInfo(@NotNull String receiverVarKey, @NotNull String methodName) {
			this.receiverVarKey = receiverVarKey;
			this.methodName = methodName;
		}
	}

	private static final class TableColumnArgumentContext {
		final @NotNull String receiverVarKey;
		final @NotNull String typedPrefix;
		final boolean pseudoHashKey;

		private TableColumnArgumentContext(
				@NotNull String receiverVarKey,
				@NotNull String typedPrefix,
				boolean pseudoHashKey
		) {
			this.receiverVarKey = receiverVarKey;
			this.typedPrefix = typedPrefix;
			this.pseudoHashKey = pseudoHashKey;
		}
	}

	private static final class CallContext {
		final @NotNull String receiverVarKey;
		final @NotNull String methodName;
		final int argumentIndex;

		private CallContext(
				@NotNull String receiverVarKey,
				@NotNull String methodName,
				int argumentIndex
		) {
			this.receiverVarKey = receiverVarKey;
			this.methodName = methodName;
			this.argumentIndex = argumentIndex;
		}
	}
}
