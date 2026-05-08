package ru.artlebedev.parser3;

import com.intellij.codeInsight.generation.CommenterDataHolder;
import com.intellij.codeInsight.generation.SelfManagingCommenter;
import com.intellij.lang.Commenter;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Parser3Commenter implements Commenter, SelfManagingCommenter<CommenterDataHolder> {

	// ======== Commenter (basic prefixes) ========

	@Override
	public String getLineCommentPrefix() {
		return "#";
	}

	@Override
	public String getBlockCommentPrefix() {
		return "^rem{";
	}

	@Override
	public String getBlockCommentSuffix() {
		return "}";
	}

	@Override
	public String getCommentedBlockCommentPrefix() {
		return null;
	}

	@Override
	public String getCommentedBlockCommentSuffix() {
		return null;
	}

	// ======== SelfManagingCommenter: line comments ========

	@Override
	public @Nullable CommenterDataHolder createLineCommentingState(int startLine,
																   int endLine,
																   @NotNull Document document,
																   @NotNull PsiFile file) {
		return (CommenterDataHolder) SelfManagingCommenter.EMPTY_STATE;
	}

	@Override
	public void commentLine(int line,
							int offset,
							@NotNull Document document,
							@NotNull CommenterDataHolder data) {
		if (line < 0 || line >= document.getLineCount()) {
			return;
		}
		int lineStart = document.getLineStartOffset(line);
		CharSequence chars = document.getCharsSequence();
		if (lineStart < document.getTextLength()) {
			if (chars.charAt(lineStart) == '#') {
				return;
			}
		}
		document.insertString(lineStart, "#");
	}

	@Override
	public void uncommentLine(int line,
							  int offset,
							  @NotNull Document document,
							  @NotNull CommenterDataHolder data) {
		if (line < 0 || line >= document.getLineCount()) {
			return;
		}
		int lineStart = document.getLineStartOffset(line);
		if (lineStart >= document.getTextLength()) {
			return;
		}
		CharSequence chars = document.getCharsSequence();
		if (chars.charAt(lineStart) == '#') {
			document.deleteString(lineStart, lineStart + 1);
		}
	}

	@Override
	public boolean isLineCommented(int line,
								   int offset,
								   @NotNull Document document,
								   @NotNull CommenterDataHolder data) {
		if (line < 0 || line >= document.getLineCount()) {
			return false;
		}
		int lineStart = document.getLineStartOffset(line);
		if (lineStart >= document.getTextLength()) {
			return false;
		}
		return document.getCharsSequence().charAt(lineStart) == '#';
	}

	@Override
	public @Nullable String getCommentPrefix(int line,
											 @NotNull Document document,
											 @NotNull CommenterDataHolder data) {
		return "#";
	}

	// ======== SelfManagingCommenter: block comments (^rem{...}) ========

	@Override
	public @Nullable CommenterDataHolder createBlockCommentingState(int selectionStart,
																	int selectionEnd,
																	@NotNull Document document,
																	@NotNull PsiFile file) {
		return (CommenterDataHolder) SelfManagingCommenter.EMPTY_STATE;
	}

	@Override
	public @Nullable TextRange getBlockCommentRange(int selectionStart,
													int selectionEnd,
													@NotNull Document document,
													@NotNull CommenterDataHolder data) {
		int textLength = document.getTextLength();
		if (textLength == 0) {
			return null;
		}

		if (selectionStart < 0) selectionStart = 0;
		if (selectionEnd < 0) selectionEnd = 0;
		if (selectionStart > textLength) selectionStart = textLength;
		if (selectionEnd > textLength) selectionEnd = textLength;
		if (selectionStart > selectionEnd) {
			int tmp = selectionStart;
			selectionStart = selectionEnd;
			selectionEnd = tmp;
		}

		CharSequence chars = document.getCharsSequence();

		// Берём точку, относительно которой ищем блок: опираемся на конец выделения,
		// чтобы учитывать случаи, когда ^rem{ находится правее начала выделения на той же строке.
		int anchor = selectionEnd > 0 ? selectionEnd - 1 : 0;

		// Ищем ближайший слева "^rem{"
		int prefix = lastIndexOf(chars, "^rem{", anchor);
		if (prefix < 0) {
			return null;
		}

		int prefixEnd = prefix + "^rem{".length();

		// Находим парную фигурную скобку для этого ^rem{.
		int bracePos = prefixEnd - 1;
		int suffixPos = findMatchingClosingBrace(chars, bracePos);
		if (suffixPos < 0) {
			return null;
		}

		int prefixLine = document.getLineNumber(prefix);
		int suffixLine = document.getLineNumber(suffixPos);

		int prefixLineStart = document.getLineStartOffset(prefixLine);
		int prefixLineEnd = document.getLineEndOffset(prefixLine);
		int suffixLineStart = document.getLineStartOffset(suffixLine);
		int suffixLineEnd = document.getLineEndOffset(suffixLine);

		int firstNonWsPrefix = findNonWhitespace(chars, prefixLineStart, prefixLineEnd);
		if (firstNonWsPrefix < 0) {
			firstNonWsPrefix = prefixLineStart;
		}

		int lastNonWsSuffix = findLastNonWhitespace(chars, suffixLineStart, suffixLineEnd);
		if (lastNonWsSuffix < 0) {
			lastNonWsSuffix = suffixLineStart;
		}

		int blockStart = firstNonWsPrefix;
		int blockEnd = lastNonWsSuffix + 1;

		// Считаем блок найденным, если курсор или выделение пересекаются с ним хотя бы частично.
		// Это позволяет повторно жать Ctrl+Shift+/ по тому же выделению,
		// как в стандартных языках: сначала комментирование, потом раскомментирование.
		if (selectionEnd >= blockStart && selectionStart <= blockEnd) {
			return new TextRange(blockStart, blockEnd);
		}

		return null;
	}

	@Override
	public @Nullable String getBlockCommentPrefix(int selectionStart,
												  @NotNull Document document,
												  @NotNull CommenterDataHolder data) {
		return "^rem{";
	}

	@Override
	public @Nullable String getBlockCommentSuffix(int selectionEnd,
												  @NotNull Document document,
												  @NotNull CommenterDataHolder data) {
		return "}";
	}

	@Override
	public void uncommentBlockComment(int startOffset,
									  int endOffset,
									  Document document,
									  CommenterDataHolder data) {
		if (startOffset < 0 || endOffset > document.getTextLength() || startOffset >= endOffset) {
			return;
		}

		CharSequence chars = document.getCharsSequence();

		int prefixLine = document.getLineNumber(startOffset);
		int prefixLineStart = document.getLineStartOffset(prefixLine);
		int prefixLineEnd = document.getLineEndOffset(prefixLine);
		int prefixTokenStart = findNonWhitespace(chars, prefixLineStart, prefixLineEnd);
		if (prefixTokenStart < 0 || !matches(chars, prefixTokenStart, "^rem{")) {
			return;
		}
		int prefixTokenEnd = prefixTokenStart + "^rem{".length();

		int bracePos = -1;
		for (int i = prefixTokenStart; i < chars.length(); i++) {
			if (chars.charAt(i) == '{') {
				bracePos = i;
				break;
			}
		}
		if (bracePos < 0) {
			return;
		}
		int suffixPos = findMatchingClosingBrace(chars, bracePos);
		if (suffixPos < 0) {
			return;
		}
		int suffixLine = document.getLineNumber(suffixPos);
		int suffixLineStart = document.getLineStartOffset(suffixLine);
		int suffixLineEnd = document.getLineEndOffset(suffixLine);

		// Проверяем, что '}' действительно отдельным токеном на своей строке (для многострочного варианта).
		int suffixTokenStart = findNonWhitespace(chars, suffixLineStart, suffixLineEnd);
		int suffixTokenEnd = suffixTokenStart >= 0 ? suffixTokenStart + 1 : suffixLineStart;

		boolean prefixIsAlone = isTokenAloneOnLine(chars, prefixLineStart, prefixLineEnd, prefixTokenStart, prefixTokenEnd);
		boolean suffixIsAlone = suffixTokenStart >= 0
				&& chars.charAt(suffixTokenStart) == '}'
				&& isTokenAloneOnLine(chars, suffixLineStart, suffixLineEnd, suffixTokenStart, suffixTokenEnd);

		if (prefixIsAlone && suffixIsAlone && suffixLine > prefixLine) {
			// Многострочный вариант:
			// <indent>^rem{
			// <indent><indent>...
			// <indent>}
			// 1) Сдвигаем внутренние строки на один уровень влево (если их отступ >= indentRem).
			// 2) Удаляем полностью строки с ^rem{ и }.

			String indentRem = leadingWhitespace(chars, prefixLineStart, prefixTokenStart);

// Определяем шаг отступа внутри блока, который нужно убрать при раскомментировании.
// Берём первую непустую строку блока (не '#'), у которой отступ больше indentRem.
			String indentStep = null;
			for (int line = prefixLine + 1; line < suffixLine; line++) {
				int lineStart = document.getLineStartOffset(line);
				int lineEnd = document.getLineEndOffset(line);
				if (lineStart >= lineEnd) {
					continue;
				}
				int p = lineStart;
				while (p < lineEnd && Character.isWhitespace(chars.charAt(p))) {
					p++;
				}
				if (p >= lineEnd) {
					continue;
				}
				if (chars.charAt(p) == '#') {
					continue;
				}
				String lineIndent = leadingWhitespace(chars, lineStart, lineEnd);
				if (lineIndent.length() > indentRem.length()) {
					indentStep = lineIndent.substring(indentRem.length());
					if (!indentStep.isEmpty()) {
						break;
					}
				}
			}
// Если блок начинался без отступа или не удалось вычислить шаг — используем один уровень табуляции.
			if (indentStep == null || indentStep.isEmpty()) {
				indentStep = "\t";
			}

			for (int line = suffixLine - 1; line > prefixLine; line--) {
				int lineStart = document.getLineStartOffset(line);
				int lineEnd = document.getLineEndOffset(line);
				int base = lineStart + indentRem.length();
				if (base >= lineEnd) {
					continue;
				}
				int len = Math.min(indentStep.length(), lineEnd - base);
				if (len > 0 && matches(chars, base, indentStep.substring(0, len))) {
					document.deleteString(base, base + len);
				}
			}

// Удаляем строку с '}' целиком (включая перевод строки).
			document.deleteString(lineStartWithEol(document, suffixLine), lineEndWithEol(document, suffixLine));

			// После удаления нижней строки пересчитываем позиции верхней.
			chars = document.getCharsSequence();
			prefixLineStart = document.getLineStartOffset(prefixLine);
			prefixLineEnd = document.getLineEndOffset(prefixLine);

			// Удаляем строку с ^rem{ целиком (включая перевод строки).
			document.deleteString(lineStartWithEol(document, prefixLine), lineEndWithEol(document, prefixLine));
		} else {
			// Инлайновый случай: просто удаляем ^rem{ и парную '}'.
			document.deleteString(suffixPos, suffixPos + 1);
			document.deleteString(prefixTokenStart, prefixTokenStart + "^rem{".length());
		}
	}

	@Override
	public @NotNull TextRange insertBlockComment(int startOffset,
												 int endOffset,
												 Document document,
												 CommenterDataHolder data) {
		int textLength = document.getTextLength();
		if (textLength == 0) {
			document.insertString(0, "^rem{}\n");
			return new TextRange(0, "^rem{}".length());
		}

		if (startOffset < 0) startOffset = 0;
		if (endOffset < 0) endOffset = 0;
		if (startOffset > textLength) startOffset = textLength;
		if (endOffset > textLength) endOffset = textLength;
		if (startOffset > endOffset) {
			int tmp = startOffset;
			startOffset = endOffset;
			endOffset = tmp;
		}

		if (coversWholeLines(startOffset, endOffset, document)) {
			int startLine = document.getLineNumber(startOffset);
			int endLine = document.getLineNumber(endOffset);
			if (endLine > startLine) {
				return insertLineBlockComment(startOffset, endOffset, document);
			}
		}

		document.insertString(endOffset, "}");
		document.insertString(startOffset, "^rem{");
		return new TextRange(startOffset, endOffset + "^rem{".length() + "}".length());
	}

	// ======== Helper methods ========

	private static boolean coversWholeLines(int startOffset, int endOffset, @NotNull Document document) {
		int startLine = document.getLineNumber(startOffset);
		int endLine = document.getLineNumber(endOffset);

		int startLineStart = document.getLineStartOffset(startLine);
		int endLineEnd = document.getLineEndOffset(endLine);

		CharSequence chars = document.getCharsSequence();

		for (int i = startLineStart; i < startOffset; i++) {
			if (!Character.isWhitespace(chars.charAt(i))) {
				return false;
			}
		}
		for (int i = endOffset; i < endLineEnd && i < chars.length(); i++) {
			if (!Character.isWhitespace(chars.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static TextRange insertLineBlockComment(int startOffset,
													int endOffset,
													@NotNull Document document) {
		CharSequence chars = document.getCharsSequence();
		int startLine = document.getLineNumber(startOffset);
		int endLine = document.getLineNumber(endOffset);

		int startLineStart = document.getLineStartOffset(startLine);
		int endLineEnd = document.getLineEndOffset(endLine);

		String minIndent = null;
		boolean hasContentLine = false;

		// Определяем минимальный отступ, игнорируя строки, где первым непробельным символом является '#'
		for (int line = startLine; line <= endLine; line++) {
			int lineStart = document.getLineStartOffset(line);
			int lineEnd = document.getLineEndOffset(line);
			if (lineStart >= lineEnd) {
				continue;
			}
			int p = lineStart;
			while (p < lineEnd && Character.isWhitespace(chars.charAt(p))) {
				p++;
			}
			// Игнорируем строки-комментарии на '#'
			if (p < lineEnd && chars.charAt(p) == '#') {
				continue;
			}
			hasContentLine = true;
			String indent = leadingWhitespace(chars, lineStart, lineEnd);
			if (minIndent == null || indent.length() < minIndent.length()) {
				minIndent = indent;
			}
		}

		if (!hasContentLine || minIndent == null) {
			minIndent = "";
		}

		// Пытаемся определить шаг отступа (indentStep) по строкам,
		// которые глубже минимального отступа. Если не получилось — используем один таб.
		String indentStep = null;
		if (hasContentLine) {
			for (int line = startLine; line <= endLine; line++) {
				int lineStart = document.getLineStartOffset(line);
				int lineEnd = document.getLineEndOffset(line);
				if (lineStart >= lineEnd) {
					continue;
				}
				String indent = leadingWhitespace(chars, lineStart, lineEnd);
				if (indent.length() > minIndent.length()) {
					indentStep = indent.substring(minIndent.length());
					break;
				}
			}
		}
		if (indentStep == null) {
			indentStep = "\t";
		}

		String openingLine = minIndent + "^rem{\n";
		String closingLine = "\n" + minIndent + "}";

		int linesCount = endLine - startLine + 1;
		boolean shiftInnerLines = linesCount > 1;

		// Сначала вставляем закрывающую строку в конец блока
		document.insertString(endLineEnd, closingLine);

		// Затем увеличиваем отступ у всех исходных строк блока на один уровень indentStep
		// ТОЛЬКО если выделено больше одной строки.
		if (shiftInnerLines) {
			for (int line = endLine; line >= startLine; line--) {
				int lineStart = document.getLineStartOffset(line);
				document.insertString(lineStart, indentStep);
			}
		}

		// И только после этого вставляем строку с ^rem{ перед первой строкой блока
		document.insertString(startLineStart, openingLine);

		int newStart = startLineStart;
		int indentAdded = shiftInnerLines ? indentStep.length() * linesCount : 0;
		int newEnd = endLineEnd + openingLine.length() + closingLine.length() + indentAdded;
		return new TextRange(newStart, newEnd);
	}
	private static boolean matches(CharSequence chars, int startOffset, String what) {
		if (startOffset < 0 || startOffset + what.length() > chars.length()) {
			return false;
		}
		for (int i = 0; i < what.length(); i++) {
			if (chars.charAt(startOffset + i) != what.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static int lastIndexOf(CharSequence seq, String what, int fromExclusive) {
		if (what.isEmpty() || seq.length() < what.length()) {
			return -1;
		}
		int start = fromExclusive - what.length();
		if (start > seq.length() - what.length()) {
			start = seq.length() - what.length();
		}
		if (start < 0) {
			start = 0;
		}
		for (int i = start; i >= 0; i--) {
			if (matches(seq, i, what)) {
				return i;
			}
		}
		return -1;
	}

	private static int findMatchingClosingBrace(CharSequence chars, int openBracePos) {
		int len = chars.length();
		if (openBracePos < 0 || openBracePos >= len) {
			return -1;
		}
		if (chars.charAt(openBracePos) != '{') {
			for (int i = openBracePos + 1; i < len; i++) {
				if (chars.charAt(i) == '{') {
					openBracePos = i;
					break;
				}
			}
			if (openBracePos < 0 || openBracePos >= len || chars.charAt(openBracePos) != '{') {
				return -1;
			}
		}
		int depth = 0;
		for (int i = openBracePos; i < len; i++) {
			char c = chars.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static boolean isTokenAloneOnLine(CharSequence chars,
											  int lineStart,
											  int lineEnd,
											  int tokenStart,
											  int tokenEnd) {
		for (int i = lineStart; i < tokenStart; i++) {
			if (!Character.isWhitespace(chars.charAt(i))) {
				return false;
			}
		}
		for (int i = tokenEnd; i < lineEnd; i++) {
			if (!Character.isWhitespace(chars.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static String leadingWhitespace(CharSequence chars, int from, int to) {
		int i = from;
		while (i < to && Character.isWhitespace(chars.charAt(i))) {
			i++;
		}
		if (i == from) {
			return "";
		}
		return chars.subSequence(from, i).toString();
	}

	private static int findNonWhitespace(CharSequence chars, int from, int to) {
		for (int i = from; i < to; i++) {
			if (!Character.isWhitespace(chars.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	private static int findLastNonWhitespace(CharSequence chars, int from, int to) {
		for (int i = to - 1; i >= from; i--) {
			if (!Character.isWhitespace(chars.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	private static int lineStartWithEol(Document document, int line) {
		return document.getLineStartOffset(line);
	}

	private static int lineEndWithEol(Document document, int line) {
		int lineEnd = document.getLineEndOffset(line);
		int textLength = document.getTextLength();
		if (lineEnd < textLength) {
			char c = document.getCharsSequence().charAt(lineEnd);
			if (c == '\n') {
				return lineEnd + 1;
			}
		}
		return lineEnd;
	}
}