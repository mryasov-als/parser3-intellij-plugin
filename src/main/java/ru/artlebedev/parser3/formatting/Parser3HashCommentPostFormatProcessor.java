package ru.artlebedev.parser3.formatting;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Восстанавливает хеш-комментарии (# ...) в колонку 0 после форматирования.
 *
 * Важно: не вмешиваемся в paste напрямую (никаких PasteHandler) — только корректируем результат,
 * если IDE/форматтер добавили лишний отступ строкам с '#'.
 */
public class Parser3HashCommentPostFormatProcessor implements PostFormatProcessor {

	@NotNull
	@Override
	public PsiElement processElement(@NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
		return source;
	}

	@NotNull
	@Override
	public TextRange processText(@NotNull PsiFile source, @NotNull TextRange rangeToReformat, @NotNull CodeStyleSettings settings) {

		if (!Parser3PsiUtils.isParser3File(source)) {
			return rangeToReformat;
		}

		Project project = source.getProject();
		Document document = PsiDocumentManager.getInstance(project).getDocument(source);
		if (document == null) {
			return rangeToReformat;
		}

		// Восстанавливаем placeholder '.' если он был вставлен pre-процессором (старый экспериментальный кейс)
		Integer dotOffset = Parser3HashCommentFormatUtil.consumeInsertedDot(document);
		if (dotOffset != null) {
			CharSequence fullText = document.getCharsSequence();
			if (dotOffset >= 0 && dotOffset < fullText.length() && fullText.charAt(dotOffset) == '.') {
				document.deleteString(dotOffset, dotOffset + 1);
			}
		}

		Map<Integer, Boolean> linesMap = Parser3HashCommentFormatUtil.getAndClearHashCommentColumn0(document);
		Map<Integer, String> indentsMap = Parser3HashCommentFormatUtil.getAndClearHashCommentIndents(document);

		// Используем новый механизм с сохранением отступов если он есть
		if (indentsMap != null && !indentsMap.isEmpty()) {
			List<Integer> lines = new ArrayList<>(indentsMap.keySet());
			Collections.sort(lines);

			int startPos = rangeToReformat.getStartOffset();
			int end = rangeToReformat.getEndOffset();
			int delta = 0;

			CharSequence text = document.getCharsSequence();
			for (Integer lineObj : lines) {
				if (lineObj == null) continue;
				int line = lineObj.intValue();
				if (line < 0 || line >= document.getLineCount()) continue;

				int lineStart = document.getLineStartOffset(line);
				int lineEnd = document.getLineEndOffset(line);
				if (lineEnd <= lineStart) continue;

				text = document.getCharsSequence();
				int firstNonWs = lineStart;
				while (firstNonWs < lineEnd) {
					char c = text.charAt(firstNonWs);
					if (c == ' ' || c == '\t') {
						firstNonWs++;
						continue;
					}
					break;
				}
				if (firstNonWs >= lineEnd) continue;
				if (text.charAt(firstNonWs) != '#') continue;

				String originalIndent = indentsMap.get(lineObj);
				if (originalIndent == null) originalIndent = "";

				int currentIndentLen = firstNonWs - lineStart;
				String currentIndent = currentIndentLen > 0 ? document.getText(new TextRange(lineStart, firstNonWs)) : "";

				// Если текущий отступ отличается от оригинального - восстанавливаем
				if (!currentIndent.equals(originalIndent)) {
					// Удаляем текущий отступ и вставляем оригинальный
					document.replaceString(lineStart, firstNonWs, originalIndent);
					int diff = originalIndent.length() - currentIndentLen;
					if (lineStart >= startPos && lineStart <= end + delta) {
						delta += diff;
					}
				}
			}

			if (delta == 0) {
				return rangeToReformat;
			}
			return new TextRange(startPos, Math.max(startPos, end + delta));
		}

		// Fallback на старый механизм (колонка 0)
		if (linesMap == null || linesMap.isEmpty()) {
			return rangeToReformat;
		}

		List<Integer> lines = new ArrayList<>(linesMap.keySet());
		Collections.sort(lines);

		int startPos = rangeToReformat.getStartOffset();
		int end = rangeToReformat.getEndOffset();
		int delta = 0;

		CharSequence text = document.getCharsSequence();
		for (Integer lineObj : lines) {
			if (lineObj == null) continue;
			int line = lineObj.intValue();
			if (line < 0 || line >= document.getLineCount()) continue;

			int lineStart = document.getLineStartOffset(line);
			int lineEnd = document.getLineEndOffset(line);
			if (lineEnd <= lineStart) continue;

			// Перечитываем текст на каждом шаге, т.к. документ может измениться
			text = document.getCharsSequence();
			int firstNonWs = lineStart;
			while (firstNonWs < lineEnd) {
				char c = text.charAt(firstNonWs);
				if (c == ' ' || c == '\t') {
					firstNonWs++;
					continue;
				}
				break;
			}
			if (firstNonWs >= lineEnd) continue;
			if (text.charAt(firstNonWs) != '#') {
				// строка изменилась так, что уже не '#': просто логируем
				continue;
			}

			int indentLen = firstNonWs - lineStart;
			if (indentLen <= 0) {
				continue;
			}

			document.deleteString(lineStart, firstNonWs);

			// корректируем итоговый range
			int diff = -indentLen;
			if (lineStart >= startPos && lineStart <= end + delta) {
				delta += diff;
			}
		}

		if (delta == 0) {
			return rangeToReformat;
		}
		return new TextRange(startPos, Math.max(startPos, end + delta));
	}
}