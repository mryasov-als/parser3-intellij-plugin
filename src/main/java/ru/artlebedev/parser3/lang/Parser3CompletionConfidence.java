package ru.artlebedev.parser3.lang;

import com.intellij.codeInsight.completion.CompletionConfidence;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

		// Для injected-языков внутри Parser3 гасим только чужой popup внутри Parser3-аргументов.
		com.intellij.lang.Language contextLang = contextElement.getLanguage();
		if (contextLang != ru.artlebedev.parser3.Parser3Language.INSTANCE) {
			return shouldSkipInjectedAutopopup(contextElement, psiFile, offset);
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

		if (P3TableColumnArgumentCompletionSupport.isColumnArgumentSyntaxContext(text, offset)) {
			if (DEBUG_LOG) {
				System.out.println("[P3Confidence] allow autopopup in table column argument at offset=" + offset);
			}
			return ThreeState.NO;
		}

		if (P3CompletionUtils.isParser3AutoPopupPrefixContext(text, offset)) {
			return ThreeState.NO;
		}

		if (P3CompletionUtils.isParser3CaretCallArgumentContext(text, offset)) {
			return ThreeState.YES;
		}

		if (DEBUG_LOG) System.out.println("[P3Confidence] skip: offset=" + offset
				+ " inQuotes=" + isInsideQuotedString(text, offset));
		return ThreeState.YES;
	}

	private @NotNull ThreeState shouldSkipInjectedAutopopup(
			@NotNull PsiElement contextElement,
			@NotNull PsiFile psiFile,
			int offset
	) {
		HostTextContext hostContext = getHostTextContext(contextElement, psiFile, offset);
		if (hostContext == null) {
			return ThreeState.UNSURE;
		}
		if (isAllowedParser3AutoPopupContext(hostContext.file, hostContext.text, hostContext.offset)) {
			return ThreeState.NO;
		}
		if (P3CompletionUtils.isParser3CaretCallArgumentContext(hostContext.text, hostContext.offset)) {
			return ThreeState.YES;
		}
		return ThreeState.UNSURE;
	}

	private boolean isAllowedParser3AutoPopupContext(
			@NotNull PsiFile file,
			@NotNull CharSequence text,
			int offset
	) {
		if (P3PathCompletionSupport.detectContext(text, offset) != null) {
			return true;
		}
		if (isBooleanLiteralAutoPopupContext(text, offset)) {
			return true;
		}
		if (P3CompletionUtils.isHashDeleteKeyContext(text, offset)) {
			return true;
		}
		if (P3CompletionUtils.isParser3AutoPopupPrefixContext(text, offset)) {
			return true;
		}
		if (file.getVirtualFile() != null
				&& P3PseudoHashCompletionRegistry.isParamTextContext(
				file.getProject(),
				file.getVirtualFile(),
				text.toString(),
				offset
		)) {
			return true;
		}
		return P3TableColumnArgumentCompletionSupport.isColumnArgumentSyntaxContext(text, offset);
	}

	private @Nullable HostTextContext getHostTextContext(
			@NotNull PsiElement contextElement,
			@NotNull PsiFile psiFile,
			int offset
	) {
		PsiFile hostFile = findParser3HostFile(psiFile);
		if (hostFile == null) {
			return null;
		}
		Project project = hostFile.getProject();
		Document hostDocument = PsiDocumentManager.getInstance(project).getDocument(hostFile);
		if (hostDocument == null) {
			return null;
		}

		Document injectedDocument = PsiDocumentManager.getInstance(project).getDocument(psiFile);
		int hostOffset = offset;
		if (injectedDocument instanceof DocumentWindow) {
			CharSequence injectedText = injectedDocument.getCharsSequence();
			hostOffset = ((DocumentWindow) injectedDocument).injectedToHost(clampOffset(injectedText, offset));
		} else if (Parser3PsiUtils.isInjectedFragment(psiFile)) {
			hostOffset = InjectedLanguageManager.getInstance(project).injectedToHost(contextElement, offset);
		}
		return new HostTextContext(hostFile, hostDocument.getCharsSequence(),
				clampOffset(hostDocument.getCharsSequence(), hostOffset));
	}

	private static @Nullable PsiFile findParser3HostFile(@Nullable PsiFile file) {
		PsiFile cur = file;
		while (cur != null) {
			if (Parser3PsiUtils.isParser3File(cur) && !Parser3PsiUtils.isInjectedFragment(cur)) {
				return cur;
			}
			if (!Parser3PsiUtils.isInjectedFragment(cur)) {
				return Parser3PsiUtils.isParser3File(cur) ? cur : null;
			}
			PsiElement context = cur.getContext();
			if (context == null) {
				return null;
			}
			PsiFile host = context.getContainingFile();
			if (host == cur) {
				return null;
			}
			cur = host;
		}
		return null;
	}

	private static int clampOffset(@NotNull CharSequence text, int offset) {
		if (offset < 0) {
			return 0;
		}
		return Math.min(offset, text.length());
	}

	private static final class HostTextContext {
		private final @NotNull PsiFile file;
		private final @NotNull CharSequence text;
		private final int offset;

		private HostTextContext(@NotNull PsiFile file, @NotNull CharSequence text, int offset) {
			this.file = file;
			this.text = text;
			this.offset = offset;
		}
	}

	private boolean isBooleanLiteralAutoPopupContext(@NotNull CharSequence text, int offset) {
		if (offset <= 0 || offset > text.length()) {
			return false;
		}
		if (!P3CompletionUtils.isBooleanLiteralContext(text, offset)) {
			return false;
		}
		String prefix = extractIdentifierPrefix(text, offset).toLowerCase(java.util.Locale.ROOT);
		return prefix.isEmpty() || "true".startsWith(prefix) || "false".startsWith(prefix);
	}

	private static @NotNull String extractIdentifierPrefix(@NotNull CharSequence text, int offset) {
		int safeOffset = Math.max(0, Math.min(offset, text.length()));
		int start = safeOffset;
		while (start > 0 && P3CompletionUtils.isVarIdentChar(text.charAt(start - 1))) {
			start--;
		}
		return text.subSequence(start, safeOffset).toString();
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
