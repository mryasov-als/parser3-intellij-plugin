package ru.artlebedev.parser3.formatting;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

/**
 * Хеш-комментарии (# ...) в Parser3 в некоторых кейсах должны оставаться в колонке 0,
 * даже если IDE при paste временно добавляет отступ всему вставленному фрагменту.
 *
 * Здесь мы ничего не меняем в документе: только собираем диагностику и помечаем строки,
 * которые нужно будет восстановить в post-процессоре.
 */
public class Parser3HashCommentPreFormatProcessor implements PreFormatProcessor {


	private static void dumpRange(@NotNull Document document, @NotNull TextRange range, @NotNull String label) {
		int start = Math.max(0, Math.min(range.getStartOffset(), document.getTextLength()));
		int end = Math.max(0, Math.min(range.getEndOffset(), document.getTextLength()));
		if (start >= end) {
			return;
		}

		int startLine = document.getLineNumber(start);
		int endLine = document.getLineNumber(Math.max(start, end - 1));

		for (int line = startLine; line <= endLine; line++) {
			int ls = document.getLineStartOffset(line);
			int le = document.getLineEndOffset(line);
			int a = Math.max(ls, start);
			int b = Math.min(le, end);
			String lineText = document.getText(new TextRange(a, b));
		}
	}

	private static void dumpWholeDocument(@NotNull Document document) {
		int lines = document.getLineCount();
		for (int line = 0; line < lines; line++) {
			int ls = document.getLineStartOffset(line);
			int le = document.getLineEndOffset(line);
			String lineText = "";
			if (le >= ls) {
				lineText = document.getText(new TextRange(ls, le));
			}
		}
	}

	@NotNull
	@Override
	public TextRange process(@NotNull ASTNode element, @NotNull TextRange range) {

		PsiElement psi = element.getPsi();
		if (!(psi instanceof PsiFile)) {
			return range;
		}
		PsiFile file = (PsiFile) psi;
		if (!Parser3PsiUtils.isParser3File(file)) {
			return range;
		}

		Project project = file.getProject();
		Document document = PsiDocumentManager.getInstance(project).getDocument(file);
		if (document == null) {
			return range;
		}

		// 0) Полный снимок документа ДО форматирования (чтобы исключить "не видит #" / диапазон / стадии)
		dumpWholeDocument(document);
		if (range.isEmpty()) {
			return range;
		}

		// 1) Снимок текста ДО форматирования (именно того range, который реально прислала IDE)
		dumpRange(document, range, "BEFORE");

		int startOffset = range.getStartOffset();
		int endOffset = range.getEndOffset();
		int startLine = document.getLineNumber(startOffset);
		int endLine = document.getLineNumber(Math.max(startOffset, endOffset - 1));


		int found = 0;
		CharSequence text = document.getCharsSequence();

		// Определяем сколько символов отступа добавила IDE к первой строке при paste
		int firstLineStart = document.getLineStartOffset(startLine);
		int addedByIDE = startOffset - firstLineStart; // символы от начала строки до начала range
		boolean isPaste = addedByIDE > 0;

		for (int line = startLine; line <= endLine; line++) {
			if (line < 0 || line >= document.getLineCount()) {
				continue;
			}
			int lineStart = document.getLineStartOffset(line);
			int lineEnd = document.getLineEndOffset(line);
			if (lineStart >= endOffset) {
				break;
			}
			if (lineEnd <= lineStart) {
				continue;
			}

			int firstNonWs = lineStart;
			while (firstNonWs < lineEnd) {
				char c = text.charAt(firstNonWs);
				if (c == ' ' || c == '\t') {
					firstNonWs++;
					continue;
				}
				break;
			}
			if (firstNonWs >= lineEnd) {
				continue;
			}
			char firstCh = text.charAt(firstNonWs);
			int indentLen = firstNonWs - lineStart;

			// Hash-комментарий только если # СТРОГО в колонке 0
			if (firstCh != '#') {
				continue;
			}

			// Если перед # есть пробелы/табы — это НЕ комментарий
			if (indentLen > 0) {
				continue;
			}

			found++;

			// Hash-комментарии всегда в колонке 0, отступа нет
			String originalIndent = "";

			Parser3HashCommentFormatUtil.markHashCommentWithIndent(document, line, originalIndent);
		}

		return range;
	}
}