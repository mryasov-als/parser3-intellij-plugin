package ru.artlebedev.parser3.lang;

import com.intellij.codeInsight.completion.CompletionConfidence;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.completion.P3CompletionUtils;
import ru.artlebedev.parser3.completion.P3PathCompletionSupport;
import ru.artlebedev.parser3.completion.P3PseudoHashCompletionRegistry;
import ru.artlebedev.parser3.completion.P3TableColumnArgumentCompletionSupport;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

public class Parser3CompletionConfidence extends CompletionConfidence {

	// Включить для отладки автопопапа
	private static final boolean DEBUG_LOG = false;

	@Override
	public @NotNull ThreeState shouldSkipAutopopup(@NotNull PsiElement contextElement,
												   @NotNull PsiFile psiFile,
												   int offset) {
		if (!Parser3PsiUtils.isParser3File(psiFile)) {
			return ThreeState.UNSURE;
		}

		// Для injected-языков внутри Parser3 не вмешиваемся.
		com.intellij.lang.Language contextLang = contextElement.getLanguage();
		if (contextLang != ru.artlebedev.parser3.Parser3Language.INSTANCE) {
			return ThreeState.UNSURE;
		}

		Document document = PsiDocumentManager.getInstance(psiFile.getProject()).getDocument(psiFile);
		if (document == null) {
			return ThreeState.UNSURE;
		}

		CharSequence text = document.getCharsSequence();
		if (offset <= 0 || offset > text.length()) {
			return ThreeState.UNSURE;
		}

		// Разрешаем автопопап во всех файловых path-контекстах:
		// ^use[], @USE, CLASS_PATH и методы с файловыми аргументами.
		if (P3PathCompletionSupport.detectContext(text, offset) != null) {
			return ThreeState.NO;
		}

		if (isBooleanLiteralAutoPopupContext(text, offset)) {
			return ThreeState.NO;
		}

		if (P3CompletionUtils.isHashDeleteKeyContext(text, offset)) {
			return ThreeState.NO;
		}

		if (isInBaseContext(text, offset)) {
			return ThreeState.NO;
		}

		if (isInOptionsContext(text, offset)) {
			return ThreeState.NO;
		}

		if (psiFile.getVirtualFile() != null
				&& P3PseudoHashCompletionRegistry.isParamTextContext(
				psiFile.getProject(),
				psiFile.getVirtualFile(),
				text.toString(),
				offset
		)) {
			if (DEBUG_LOG) {
				System.out.println("[P3Confidence] allow autopopup in paramText context at offset=" + offset);
			}
			return ThreeState.NO;
		}

		if (psiFile.getVirtualFile() != null
				&& P3TableColumnArgumentCompletionSupport.isColumnArgumentContext(
				psiFile.getProject(),
				psiFile.getVirtualFile(),
				text.toString(),
				offset
		)) {
			if (DEBUG_LOG) {
				System.out.println("[P3Confidence] allow autopopup in table column argument at offset=" + offset);
			}
			return ThreeState.NO;
		}

		int i = offset - 1;
		while (i >= 0) {
			char c = text.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':') {
				i--;
				continue;
			}

			if (c == '^' || c == '.' || c == '$') {
				return ThreeState.NO;
			}
			if (c == '{' && i > 0 && text.charAt(i - 1) == '$') {
				return ThreeState.NO;
			}
			if (c == '@') {
				if (i == 0 || text.charAt(i - 1) == '\n' || text.charAt(i - 1) == '\r') {
					return ThreeState.NO;
				}
			}
			if (c == '[') {
				if (i > 0 && text.charAt(i - 1) == ']') {
					return ThreeState.NO;
				}
			}

			if (DEBUG_LOG) System.out.println("[P3Confidence] skip: delimiter='" + c
					+ "' offset=" + offset + " inQuotes=" + isInsideQuotedString(text, offset));
			return ThreeState.YES;
		}

		return ThreeState.YES;
	}

	private boolean isBooleanLiteralAutoPopupContext(@NotNull CharSequence text, int offset) {
		if (offset <= 0 || offset > text.length()) {
			return false;
		}
		return P3CompletionUtils.isBooleanLiteralContext(text, offset);
	}

	/**
	 * Проверяет находимся ли мы после @OPTIONS.
	 */
	private boolean isInOptionsContext(CharSequence text, int offset) {
		int searchStart = Math.max(0, offset - 100);
		String before = text.subSequence(searchStart, offset).toString();

		int optionsIndex = before.lastIndexOf("@OPTIONS");
		if (optionsIndex >= 0) {
			String afterOptions = before.substring(optionsIndex + 8);
			if (!afterOptions.contains("@") && !afterOptions.contains("^")) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Проверяет находимся ли мы после @BASE.
	 */
	private boolean isInBaseContext(CharSequence text, int offset) {
		int searchStart = Math.max(0, offset - 100);
		String before = text.subSequence(searchStart, offset).toString();

		int baseIndex = before.lastIndexOf("@BASE");
		if (baseIndex >= 0) {
			String afterBase = before.substring(baseIndex + 5);
			if (!afterBase.contains("@") && !afterBase.contains("^")) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Проверяет находится ли курсор внутри строки в кавычках.
	 */
	private boolean isInsideQuotedString(CharSequence text, int offset) {
		int lineStart = offset - 1;
		while (lineStart >= 0 && text.charAt(lineStart) != '\n' && text.charAt(lineStart) != '\r') {
			lineStart--;
		}
		lineStart++;

		boolean inSingle = false;
		boolean inDouble = false;
		for (int i = lineStart; i < offset; i++) {
			char c = text.charAt(i);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			} else if (c == '^' && i + 1 < offset) {
				char next = text.charAt(i + 1);
				if (next == '\'' || next == '"') {
					i++;
				}
			}
		}
		return inSingle || inDouble;
	}
}
