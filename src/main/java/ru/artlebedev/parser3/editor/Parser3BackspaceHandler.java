package ru.artlebedev.parser3.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;

class Parser3BackspaceHandler extends EditorWriteActionHandler {

	private final EditorActionHandler originalHandler;

	public Parser3BackspaceHandler(@NotNull EditorActionHandler originalHandler) {
		super(false);
		this.originalHandler = originalHandler;
	}

	@Override
	public void executeWriteAction(@NotNull Editor editor, @Nullable Caret caret, @NotNull DataContext dataContext) {
		if (originalHandler == null) {
			return;
		}

		Project project = editor.getProject();
		if (project == null) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}

		PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
		if (!Parser3PsiUtils.isParser3File(psiFile)) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}


		SelectionModel selectionModel = editor.getSelectionModel();
		boolean hasSelection = selectionModel.hasSelection();

		Caret targetCaret = caret != null ? caret : editor.getCaretModel().getPrimaryCaret();

		if (hasSelection || targetCaret == null) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}

		Document document = editor.getDocument();
		int caretOffset = targetCaret.getOffset();
		if (caretOffset <= 0 || caretOffset > document.getTextLength()) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}

		int line = document.getLineNumber(caretOffset);
		int lineStart = document.getLineStartOffset(line);
		if (caretOffset <= lineStart) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}

		CharSequence chars = document.getCharsSequence();

		// Проверяем, что слева только пробелы/табы.
		boolean onlyWhitespaceBeforeCaret = true;
		for (int i = lineStart; i < caretOffset; i++) {
			char ch = chars.charAt(i);
			if (ch != ' ' && ch != '\t') {
				onlyWhitespaceBeforeCaret = false;
				break;
			}
		}

		if (!onlyWhitespaceBeforeCaret) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}

		int unindentSize = getIndentSize(project, psiFile);
		if (unindentSize <= 0) {
			unindentSize = 1;
		}

		int deleteFrom = Math.max(lineStart, caretOffset - unindentSize);

		// Убедимся, что удаляем только пробелы/табы.
		for (int i = deleteFrom; i < caretOffset; i++) {
			char ch = chars.charAt(i);
			if (ch != ' ' && ch != '\t') {
				originalHandler.execute(editor, caret, dataContext);
				return;
			}
		}

		document.deleteString(deleteFrom, caretOffset);
		return;
	}

	private static int getIndentSize(@NotNull Project project, @Nullable PsiFile psiFile) {
		if (psiFile == null) {
			return 1;
		}
		CodeStyleSettings settings = CodeStyle.getSettings(psiFile);
		CommonCodeStyleSettings.IndentOptions indentOptions = settings.getIndentOptions(psiFile.getFileType());
		if (indentOptions == null) {
			return 1;
		}
		if (indentOptions.USE_TAB_CHARACTER) {
			return 1;
		}
		return indentOptions.INDENT_SIZE > 0 ? indentOptions.INDENT_SIZE : 1;
	}
}