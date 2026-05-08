package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

/**
 * TypedHandlerDelegate для автоматического вызова автокомплита
 * в файловых path-контекстах Parser3.
 */
public class P3UseTypedHandler extends TypedHandlerDelegate {

	private static final boolean DEBUG_PARAM_TEXT = false;

	@Override
	public @NotNull Result beforeCharTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file, @NotNull FileType fileType) {
		if (!Parser3PsiUtils.isParser3File(file)) {
			return Result.CONTINUE;
		}

		int offset = editor.getCaretModel().getOffset();
		CharSequence text = editor.getDocument().getCharsSequence();
		offset = P3PathCompletionSupport.clampOffset(text, offset);
		if (P3CompletionUtils.shouldAutoPopupBooleanLiteral(text, offset, c)) {
			ApplicationManager.getApplication().invokeLater(
					() -> AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
			);
		}
		if (P3ClassCompletionContributor.shouldAutoPopupBeforeClassMethodSeparator(text, offset, c)) {
			ApplicationManager.getApplication().invokeLater(
					() -> AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
			);
		}
		return Result.CONTINUE;
	}

	@Override
	public @NotNull Result checkAutoPopup(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
		if (!Parser3PsiUtils.isParser3File(file)) {
			return Result.CONTINUE;
		}

		int offset = editor.getCaretModel().getOffset();
		CharSequence text = editor.getDocument().getCharsSequence();
		offset = P3PathCompletionSupport.clampOffset(text, offset);
		if (DEBUG_PARAM_TEXT) {
			int from = Math.max(0, offset - 40);
			int to = Math.min(text.length(), offset + 20);
			System.out.println("[P3UseTypedHandler] char='" + c + "' offset=" + offset
					+ " text='" + text.subSequence(from, to).toString().replace("\n", "\\n").replace("\r", "\\r") + "'");
		}

		if (file.getVirtualFile() != null) {
			boolean shouldPopup = P3PseudoHashCompletionRegistry.shouldAutoPopupParamText(
					project,
					file.getVirtualFile(),
					text.toString(),
					offset,
					c
			);
			if (DEBUG_PARAM_TEXT) {
				System.out.println("[P3UseTypedHandler] shouldAutoPopupParamText=" + shouldPopup);
			}
			if (shouldPopup) {
				AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
				if (DEBUG_PARAM_TEXT) {
					System.out.println("[P3UseTypedHandler] scheduleAutoPopup for paramText");
				}
				return Result.STOP;
			}

			if (P3TableColumnArgumentCompletionSupport.shouldAutoPopupOnTypedChar(
					project,
					file.getVirtualFile(),
					text.toString(),
					offset,
					c
			)) {
				AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
				return Result.STOP;
			}
		}

		if (c == ':') {
			if (P3ClassCompletionContributor.shouldAutoPopupAfterClassMethodSeparator(text, offset, c)) {
				AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
				return Result.STOP;
			}
			return Result.CONTINUE;
		}

		if (c != '/' && c != '.' && c != '[' && c != '{' && c != ';') {
			return Result.CONTINUE;
		}

		if (P3PathCompletionSupport.shouldAutoPopupOnTypedChar(text, offset, c)) {
			AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
			return Result.STOP;
		}

		return Result.CONTINUE;
	}
}
