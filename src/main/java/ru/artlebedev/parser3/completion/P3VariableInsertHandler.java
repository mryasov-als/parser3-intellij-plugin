package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.CompletionInitializationContext;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;
import ru.artlebedev.parser3.utils.Parser3VariableTailUtils;

/**
 * InsertHandler для переменных Parser3.
 * Удаляет остаток старого имени переменной после курсора.
 *
 * Удаляет ТОЛЬКО символы идентификатора: буквы, цифры, '_', '-'.
 * Любой другой символ — стоп (не удаляется).
 *
 * Примеры:
 * - $self.v|ar + выбор var → $self.var (удаляет "ar")
 * - $v|ar&field + выбор var → $var&field (НЕ удаляет "&field")
 * - $s[field=$v|&field2=15] + выбор var → $s[field=$var&field2=15]
 */
public final class P3VariableInsertHandler implements InsertHandler<LookupElement> {

	public static final P3VariableInsertHandler INSTANCE = new P3VariableInsertHandler();

	@Override
	public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
		Document doc = resolveHostDocument(context);
		int tailOffset = context.getTailOffset();
		CharSequence text = doc.getCharsSequence();
		int textLength = text.length();

		// Удаляем только символы идентификатора (остаток старого имени)
		int endOffset = skipIdentifierChars(text, tailOffset, textLength);

		// Удаляем остаток старого имени
		if (endOffset > tailOffset) {
			doc.deleteString(tailOffset, endOffset);
		}
	}

	public static @NotNull InsertHandler<LookupElement> createNormalContextHandler(
			@NotNull String originalText,
			int caretOffset,
			@Nullable String suffix,
			boolean openPopupAfterInsert
	) {
		int safeCaretOffset = Math.max(0, Math.min(caretOffset, originalText.length()));
		int lineStart = safeCaretOffset;
		while (lineStart > 0 && originalText.charAt(lineStart - 1) != '\n' && originalText.charAt(lineStart - 1) != '\r') {
			lineStart--;
		}

		int lineEnd = safeCaretOffset;
		while (lineEnd < originalText.length() && originalText.charAt(lineEnd) != '\n' && originalText.charAt(lineEnd) != '\r') {
			lineEnd++;
		}

		int dollarPos = safeCaretOffset - 1;
		while (dollarPos >= lineStart) {
			if (originalText.charAt(dollarPos) == '$') {
				break;
			}
			dollarPos--;
		}
		if (dollarPos < lineStart) {
			return (context, item) -> {
				INSTANCE.handleInsert(context, item);
				if (suffix != null && !suffix.isEmpty()) {
					Document doc = resolveHostDocument(context);
					int tail = context.getTailOffset();
					doc.insertString(tail, suffix);
					context.getEditor().getCaretModel().moveToOffset(tail + suffix.length());
				}
				if (openPopupAfterInsert) {
					AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor());
				}
			};
		}

		int varEnd = skipIdentifierChars(originalText, safeCaretOffset, originalText.length());
		final int restoreLineStart = lineStart;
		String linePrefix = originalText.substring(lineStart, dollarPos + 1);
		String lineSuffix = originalText.substring(varEnd, lineEnd);
		String insertSuffix = suffix != null ? suffix : "";
		final boolean needsDeferredRestore = linePrefix.indexOf('<') != -1 || lineSuffix.indexOf('>') != -1;

		return (context, item) -> {
			Document doc = resolveHostDocument(context);
			Editor editor = resolveCompletionEditor(context, doc);
			String expectedLine = linePrefix + item.getLookupString() + insertSuffix + lineSuffix;
			boolean hasInjectedDocument = doc != context.getDocument();
			int caretTarget = Math.min(doc.getTextLength(), restoreLineStart + linePrefix.length() + item.getLookupString().length() + insertSuffix.length());
			Runnable finishCaretAndPopup = () -> {
				moveCaretToHostOffset(editor, doc, caretTarget);
				if (openPopupAfterInsert && !needsDeferredRestore) {
					AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(editor);
				}
			};
			Runnable restoreHostLine = () -> {
				replaceCurrentLine(doc, restoreLineStart, expectedLine);
				context.commitDocument();
				context.setTailOffset(caretTarget);
				context.getOffsetMap().addOffset(InsertionContext.TAIL_OFFSET, caretTarget);
				context.getOffsetMap().addOffset(CompletionInitializationContext.SELECTION_END_OFFSET, caretTarget);
				context.getOffsetMap().addOffset(CompletionInitializationContext.IDENTIFIER_END_OFFSET, caretTarget);
				finishCaretAndPopup.run();
			};
			Runnable restoreHostLineFinally = () -> {
				replaceCurrentLine(doc, restoreLineStart, expectedLine);
				moveCaretToHostOffset(editor, doc, caretTarget);
			};

			if (hasInjectedDocument) {
				Runnable previous = context.getLaterRunnable();
				context.setLaterRunnable(() -> {
					if (previous != null) {
						previous.run();
					}
					restoreHostLine.run();
				});
			} else {
				restoreHostLine.run();
			}

			if (needsDeferredRestore) {
				ApplicationManager.getApplication().invokeLater(() -> {
					if (context.getProject().isDisposed()) {
						return;
					}
					CommandProcessor.getInstance().executeCommand(context.getProject(), () ->
							CommandProcessor.getInstance().runUndoTransparentAction(() ->
									ApplicationManager.getApplication().runWriteAction(restoreHostLineFinally)),
							"Parser3 Restore HTML Variable Completion",
							null);
					PsiDocumentManager.getInstance(context.getProject()).commitDocument(doc);
					moveCaretToHostOffset(editor, doc, caretTarget);
					if (openPopupAfterInsert) {
						ApplicationManager.getApplication().invokeLater(() -> {
							if (context.getProject().isDisposed()) {
								return;
							}
							PsiDocumentManager.getInstance(context.getProject()).commitDocument(doc);
							moveCaretToHostOffset(editor, doc, Math.min(caretTarget, doc.getTextLength()));
							showBasicCompletion(context.getProject(), editor);
						});
					}
				});
			}
		};
	}

	private static void replaceCurrentLine(@NotNull Document doc, int lineAnchorOffset, @NotNull String expectedLine) {
		int safeAnchor = Math.max(0, Math.min(lineAnchorOffset, doc.getTextLength()));
		int currentLine = doc.getLineNumber(safeAnchor);
		int currentLineStart = doc.getLineStartOffset(currentLine);
		int currentLineEnd = doc.getLineEndOffset(currentLine);
		doc.replaceString(currentLineStart, currentLineEnd, expectedLine);
	}

	static @NotNull Document resolveHostDocument(@NotNull InsertionContext context) {
		PsiFile currentFile = context.getFile();
		if (currentFile == null) {
			return context.getDocument();
		}

		PsiFile topLevelFile = InjectedLanguageManager.getInstance(context.getProject()).getTopLevelFile(currentFile);
		if (topLevelFile != null && topLevelFile != currentFile && Parser3PsiUtils.isParser3File(topLevelFile)) {
			Document topLevelDocument = PsiDocumentManager.getInstance(context.getProject()).getDocument(topLevelFile);
			if (topLevelDocument != null) {
				return topLevelDocument;
			}
		}

		return context.getDocument();
	}

	static @NotNull Editor resolveCompletionEditor(@NotNull InsertionContext context, @NotNull Document hostDocument) {
		Editor currentEditor = context.getEditor();
		Document currentDocument = currentEditor.getDocument();
		if (currentDocument == hostDocument) {
			return currentEditor;
		}
		if (currentDocument instanceof DocumentWindow
				&& ((DocumentWindow) currentDocument).getDelegate() == hostDocument) {
			return currentEditor;
		}

		Editor[] editors = EditorFactory.getInstance().getEditors(hostDocument, context.getProject());
		if (editors.length > 0) {
			return editors[0];
		}

		return currentEditor;
	}

	static void moveCaretToHostOffset(
			@NotNull Editor editor,
			@NotNull Document hostDocument,
			int hostOffset
	) {
		int safeHostOffset = Math.max(0, Math.min(hostOffset, hostDocument.getTextLength()));
		Document editorDocument = editor.getDocument();
		if (editorDocument instanceof DocumentWindow
				&& ((DocumentWindow) editorDocument).getDelegate() == hostDocument) {
			int injectedOffset = ((DocumentWindow) editorDocument).hostToInjected(safeHostOffset);
			if (injectedOffset >= 0) {
				editor.getCaretModel().moveToOffset(injectedOffset);
			}
			return;
		}
		editor.getCaretModel().moveToOffset(safeHostOffset);
	}

	static void showBasicCompletion(@NotNull Project project, @NotNull Editor editor) {
		new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
				.invokeCompletion(project, editor);
	}

	static int findCaretAfterInsertedVariable(@NotNull Document doc, int preferredOffset, @NotNull String lookupString) {
		CharSequence text = doc.getCharsSequence();
		String fullText = text.toString();
		String needle = "$" + lookupString;
		int safePreferredOffset = Math.max(0, Math.min(preferredOffset, text.length()));
		int bestOffset = -1;
		int bestDistance = Integer.MAX_VALUE;
		int index = fullText.indexOf(needle);
		while (index >= 0) {
			int candidate = index + needle.length();
			int distance = Math.abs(candidate - safePreferredOffset);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestOffset = candidate;
			}
			index = fullText.indexOf(needle, index + 1);
		}
		if (bestOffset >= 0) {
			return bestOffset;
		}
		return safePreferredOffset;
	}

	/**
	 * Пропускает символы идентификатора: буквы, цифры, '_', '-'.
	 * Используется для определения конца старого имени переменной.
	 */
	static int skipIdentifierChars(@NotNull CharSequence text, int from, int limit) {
		return Parser3VariableTailUtils.skipIdentifierChars(text, from, limit);
	}
}
