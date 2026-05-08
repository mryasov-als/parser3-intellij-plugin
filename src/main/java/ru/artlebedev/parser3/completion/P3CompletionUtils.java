package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.utils.Parser3VariableTailUtils;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

/**
 * Общие утилиты для completion в Parser3.
 */
public final class P3CompletionUtils {

	private P3CompletionUtils() {}

	public static final class HashDeleteKeyContext {
		public final @NotNull String receiverChain;
		public final @NotNull String prefix;

		private HashDeleteKeyContext(@NotNull String receiverChain, @NotNull String prefix) {
			this.receiverChain = receiverChain;
			this.prefix = prefix;
		}
	}

	/**
	 * Методы, которые не вызываются явно и не должны показываться в автокомплите.
	 */
	private static final java.util.Set<String> UNCALLABLE_METHODS = java.util.Set.of(
			"auto", "conf", "main", "unhandled_exception"
	);

	/**
	 * Проверяет, что метод не должен показываться в автокомплите вызовов.
	 */
	public static boolean isUncallableMethod(@NotNull String methodName) {
		return UNCALLABLE_METHODS.contains(methodName);
	}

	/**
	 * Делает CompletionResultSet регистронезависимым.
	 */
	public static @NotNull CompletionResultSet makeCaseInsensitive(@NotNull CompletionResultSet result) {
		PrefixMatcher original = result.getPrefixMatcher();
		return result.withPrefixMatcher(createCaseInsensitiveMatcher(original.getPrefix()));
	}

	private static @NotNull PrefixMatcher createCaseInsensitiveMatcher(@NotNull String prefix) {
		return new PrefixMatcher(prefix) {
			@Override
			public boolean prefixMatches(@NotNull String name) {
				String p = getPrefix().toLowerCase(java.util.Locale.ROOT);
				String n = name.toLowerCase(java.util.Locale.ROOT);
				if (p.isEmpty()) return true;
				return n.startsWith(p);
			}

			@Override
			public @NotNull PrefixMatcher cloneWithPrefix(@NotNull String newPrefix) {
				return createCaseInsensitiveMatcher(newPrefix);
			}
		};
	}

	/**
	 * Проверяет, является ли символ частью идентификатора переменной Parser3.
	 * Идентификатор: буквы, цифры, '_', '-'.
	 */
	public static boolean isVarIdentChar(char c) {
		return Parser3VariableTailUtils.isVariableIdentifierChar(c);
	}

	/**
	 * Пропускает символы идентификатора начиная с позиции from.
	 * Возвращает позицию первого не-идентификаторного символа.
	 */
	public static int skipIdentifierChars(@NotNull CharSequence text, int from, int limit) {
		return Parser3VariableTailUtils.skipIdentifierChars(text, from, limit);
	}

	/**
	 * Проверяет, что символ обрывает префикс метода при обратном поиске ^.
	 */
	public static boolean isMethodPrefixStopChar(char ch) {
		if (Character.isWhitespace(ch)) return true;
		return switch (ch) {
			case '$', '/', '\\', '&', '|', '=', '+', '*', ',', ';', '<', '>', '!', '?', '"', '\'', '`' -> true;
			default -> false;
		};
	}

	/**
	 * Проверяет, есть ли в диапазоне символ, обрывающий префикс метода.
	 */
	public static boolean hasMethodPrefixStopChar(@NotNull CharSequence text, int from, int to) {
		int start = Math.max(0, from);
		int end = Math.min(text.length(), to);
		for (int i = start; i < end; i++) {
			if (isMethodPrefixStopChar(text.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Определяет контекст аргумента ключа в ^хеш.delete[...].
	 * В этом месте нужны ключи receiver-цепочки, а не методы или переменные.
	 */
	public static @Nullable HashDeleteKeyContext detectHashDeleteKeyContext(@NotNull CharSequence text, int offset) {
		int safeOffset = Math.max(0, Math.min(offset, text.length()));
		int openOffset = findCurrentArgumentOpeningBracket(text, safeOffset);
		if (openOffset < 0) return null;

		int methodEnd = openOffset;
		while (methodEnd > 0 && Character.isWhitespace(text.charAt(methodEnd - 1))) {
			methodEnd--;
		}
		int methodStart = methodEnd;
		while (methodStart > 0 && isVarIdentChar(text.charAt(methodStart - 1))) {
			methodStart--;
		}
		if (methodStart == methodEnd) return null;
		if (!"delete".contentEquals(text.subSequence(methodStart, methodEnd))) return null;

		int dotOffset = methodStart - 1;
		if (dotOffset < 0 || text.charAt(dotOffset) != '.') return null;

		int caretOffset = findReceiverCaret(text, dotOffset);
		if (caretOffset < 0) return null;

		String receiverChain = text.subSequence(caretOffset + 1, dotOffset).toString().trim();
		if (receiverChain.isEmpty()) return null;
		if (receiverChain.contains("IntellijIdeaRulezzz")) return null;

		String prefix = extractHashDeleteKeyPrefix(text, openOffset + 1, safeOffset);
		if (prefix == null) return null;

		return new HashDeleteKeyContext(receiverChain, prefix);
	}

	public static boolean isHashDeleteKeyContext(@NotNull CharSequence text, int offset) {
		return detectHashDeleteKeyContext(text, offset) != null;
	}

	private static int findCurrentArgumentOpeningBracket(@NotNull CharSequence text, int offset) {
		int depth = 0;
		for (int i = offset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == '\n' || ch == '\r') {
				return -1;
			}
			if (isClosingBracket(ch)) {
				depth++;
				continue;
			}
			if (isOpeningBracket(ch)) {
				if (depth == 0) {
					return i;
				}
				depth--;
			}
		}
		return -1;
	}

	private static int findReceiverCaret(@NotNull CharSequence text, int receiverEnd) {
		int depth = 0;
		for (int i = receiverEnd - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (isClosingBracket(ch)) {
				depth++;
				continue;
			}
			if (isOpeningBracket(ch)) {
				depth--;
				if (depth < 0) return -1;
				continue;
			}
			if (depth == 0 && ch == '^') {
				return i;
			}
			if (depth == 0 && (ch == '\n' || ch == '\r' || isMethodPrefixStopChar(ch))) {
				return -1;
			}
		}
		return -1;
	}

	private static @Nullable String extractHashDeleteKeyPrefix(@NotNull CharSequence text, int argumentStart, int offset) {
		int depth = 0;
		for (int i = argumentStart; i < offset; i++) {
			char ch = text.charAt(i);
			if (isOpeningBracket(ch)) {
				depth++;
			} else if (isClosingBracket(ch)) {
				if (depth == 0) return null;
				depth--;
			} else if ((ch == ';' || ch == '.') && depth == 0) {
				return null;
			}
		}

		int prefixStart = offset;
		while (prefixStart > argumentStart && isVarIdentChar(text.charAt(prefixStart - 1))) {
			prefixStart--;
		}
		if (prefixStart > argumentStart && text.charAt(prefixStart - 1) == '$') {
			return null;
		}
		return text.subSequence(prefixStart, offset).toString();
	}

	private static boolean isOpeningBracket(char ch) {
		return ch == '[' || ch == '(' || ch == '{';
	}

	private static boolean isClosingBracket(char ch) {
		return ch == ']' || ch == ')' || ch == '}';
	}

	/**
	 * Добавляет литералы true/false внутри Parser3-выражений в круглых скобках.
	 */
	public static boolean addBooleanLiteralCompletionsIfNeeded(
			@NotNull CharSequence text,
			int offset,
			@NotNull CompletionResultSet result
	) {
		if (!isBooleanLiteralContext(text, offset)) {
			return false;
		}

		CompletionResultSet boolResult = result.withPrefixMatcher(extractIdentifierPrefix(text, offset));
		boolResult.addElement(LookupElementBuilder.create("true").withTypeText("boolean", true));
		boolResult.addElement(LookupElementBuilder.create("false").withTypeText("boolean", true));
		return true;
	}

	public static boolean isBooleanLiteralContext(@NotNull CharSequence text, int offset) {
		int safeOffset = Math.max(0, Math.min(offset, text.length()));
		if (!hasOpeningParenBeforeBooleanPrefix(text, safeOffset)) {
			return false;
		}

		int openParen = findExpressionOpenParen(text, safeOffset);
		if (openParen < 0) {
			return false;
		}

		int nameEnd = openParen;
		int nameStart = nameEnd;
		while (nameStart > 0 && isVarIdentChar(text.charAt(nameStart - 1))) {
			nameStart--;
		}
		if (nameStart == nameEnd) {
			return false;
		}

		String name = text.subSequence(nameStart, nameEnd).toString();
		int prefixPos = nameStart - 1;
		if (prefixPos >= 0 && text.charAt(prefixPos) == '^') {
			return "if".equals(name) || "while".equals(name);
		}
		if (prefixPos >= 0 && text.charAt(prefixPos) == '$') {
			return prefixPos == 0 || text.charAt(prefixPos - 1) != '^';
		}
		return prefixPos >= 1 && text.charAt(prefixPos) == '.' && text.charAt(prefixPos - 1) == '$';
	}

	private static boolean hasOpeningParenBeforeBooleanPrefix(@NotNull CharSequence text, int offset) {
		int safeOffset = Math.max(0, Math.min(offset, text.length()));
		int prefixStart = safeOffset;
		while (prefixStart > 0 && isVarIdentChar(text.charAt(prefixStart - 1))) {
			prefixStart--;
		}
		return prefixStart > 0 && text.charAt(prefixStart - 1) == '(';
	}

	public static boolean shouldAutoPopupBooleanLiteral(@NotNull CharSequence text, int offset, char typedChar) {
		if (typedChar != 't' && typedChar != 'T' && typedChar != 'f' && typedChar != 'F') {
			return false;
		}
		int safeOffset = Math.max(0, Math.min(offset, text.length()));
		return isBooleanLiteralContext(text, safeOffset);
	}

	private static int findExpressionOpenParen(@NotNull CharSequence text, int offset) {
		int roundDepth = 0;
		int squareDepth = 0;
		int braceDepth = 0;
		for (int i = offset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == '\n' || ch == '\r') {
				return -1;
			}
			if (ch == ')' && squareDepth == 0 && braceDepth == 0) {
				roundDepth++;
				continue;
			}
			if (ch == ']' && roundDepth == 0 && braceDepth == 0) {
				squareDepth++;
				continue;
			}
			if (ch == '}' && roundDepth == 0 && squareDepth == 0) {
				braceDepth++;
				continue;
			}
			if (ch == '(' && squareDepth == 0 && braceDepth == 0) {
				if (roundDepth == 0) {
					return i;
				}
				roundDepth--;
				continue;
			}
			if (ch == '[' && roundDepth == 0 && braceDepth == 0) {
				squareDepth--;
				continue;
			}
			if (ch == '{' && roundDepth == 0 && squareDepth == 0) {
				if (braceDepth == 0) {
					return -1;
				}
				braceDepth--;
			}
		}
		return -1;
	}

	private static @NotNull String extractIdentifierPrefix(@NotNull CharSequence text, int offset) {
		int safeOffset = Math.max(0, Math.min(offset, text.length()));
		int start = safeOffset;
		while (start > 0 && isVarIdentChar(text.charAt(start - 1))) {
			start--;
		}
		return text.subSequence(start, safeOffset).toString();
	}

	public static @NotNull LookupElement createReceiverCompletion(
			@NotNull String insertText,
			@NotNull String lookupText,
			@Nullable String typeText
	) {
		return LookupElementBuilder.create(insertText)
				.withLookupString(lookupText)
				.withTypeText(typeText, true)
				.withPresentableText(insertText)
				.withInsertHandler((context, item) -> {
					Document doc = context.getDocument();
					int tailOff = context.getTailOffset();
					CharSequence txt = doc.getCharsSequence();
					int endOff = skipIdentifierChars(txt, tailOff, txt.length());
					if (endOff > tailOff) {
						doc.deleteString(tailOff, endOff);
					}
					int newTail = context.getTailOffset();
					CharSequence newTxt = doc.getCharsSequence();
					char suffix = insertText.charAt(insertText.length() - 1);
					if (newTail > 0 && newTail < newTxt.length()
							&& newTxt.charAt(newTail - 1) == suffix && newTxt.charAt(newTail) == suffix) {
						doc.deleteString(newTail, newTail + 1);
					}
					AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor());
				});
	}

	/**
	 * Возвращает видимость для классов в completion/resolve.
	 * Если из файла виден @autouse — считаем, что доступны все классы проекта.
	 */
	public static @NotNull java.util.List<VirtualFile> getVisibleFilesForClasses(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		return new P3ScopeContext(project, currentFile, cursorOffset).getClassSearchFiles();
	}

	public static @NotNull java.util.List<VirtualFile> getVisibleFilesForMethods(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		return new P3ScopeContext(project, currentFile, cursorOffset).getMethodSearchFiles();
	}
}
