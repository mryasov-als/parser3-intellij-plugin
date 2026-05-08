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
import ru.artlebedev.parser3.psi.Parser3PsiFile;

public final class Parser3UnindentHandler extends EditorWriteActionHandler {

	private final EditorActionHandler myOriginalHandler;

	public Parser3UnindentHandler(EditorActionHandler originalHandler) {
		super(false);
		this.myOriginalHandler = originalHandler;
	}

	@Override
	public void executeWriteAction(@NotNull Editor editor, Caret caret, @NotNull DataContext dataContext) {
		if (myOriginalHandler == null) {
			return;
		}

		Project project = editor.getProject();
		if (project == null) {
			myOriginalHandler.execute(editor, caret, dataContext);
			return;
		}

		if (!isParser3File(project, editor)) {
			myOriginalHandler.execute(editor, caret, dataContext);
			return;
		}

		Document document = editor.getDocument();
		SelectionModel selectionModel = editor.getSelectionModel();
		boolean hasSelection = selectionModel.hasSelection();

		if (!hasSelection && caret == null) {
			myOriginalHandler.execute(editor, null, dataContext);
			return;
		}

		int startOffset;
		int endOffset;

		if (hasSelection) {
			startOffset = selectionModel.getSelectionStart();
			endOffset = selectionModel.getSelectionEnd();
		} else {
			int caretOffset = caret.getOffset();
			if (caretOffset < 0 || caretOffset > document.getTextLength()) {
				myOriginalHandler.execute(editor, caret, dataContext);
				return;
			}
			int line = document.getLineNumber(caretOffset);
			startOffset = document.getLineStartOffset(line);
			endOffset = document.getLineEndOffset(line);
		}

		if (startOffset == endOffset) {
			myOriginalHandler.execute(editor, caret, dataContext);
			return;
		}

		int startLine = document.getLineNumber(startOffset);
		int endLine = document.getLineNumber(Math.max(startOffset, endOffset - 1));

		// Если среди строк нет ни одной, начинающейся с '#', полностью делегируем стандартному обработчику.
		if (!hasAnyLineStartingWithHash(document, startLine, endLine)) {
			myOriginalHandler.execute(editor, caret, dataContext);
			return;
		}

		int unindentSize = getUnindentSize(project, document);
		if (unindentSize <= 0) {
			myOriginalHandler.execute(editor, caret, dataContext);
			return;
		}

		int textLength = document.getTextLength();
		CharSequence chars = document.getCharsSequence();

		for (int line = startLine; line <= endLine; line++) {
			int lineStart = document.getLineStartOffset(line);
			int lineEnd = document.getLineEndOffset(line);
			if (lineStart >= textLength) {
				continue;
			}

			// Строка считается комментарием только если '#' стоит строго в колонке 0.
			if (chars.charAt(lineStart) == '#') {
				// Строка-комментарий: отступ после '#'
				int suffixStart = lineStart + 1;
				if (suffixStart >= lineEnd) {
					continue;
				}

				int whitespaceEnd = suffixStart;
				while (whitespaceEnd < lineEnd) {
					char c = chars.charAt(whitespaceEnd);
					if (c != ' ' && c != '\t') {
						break;
					}
					whitespaceEnd++;
				}

				if (whitespaceEnd == suffixStart) {
					continue;
				}

				int toRemove = Math.min(unindentSize, whitespaceEnd - suffixStart);
				int removeEnd = suffixStart + toRemove;
				document.deleteString(suffixStart, removeEnd);
			} else {
				// Обычная строка: убираем отступ в начале строки.
				int whitespaceEnd = lineStart;
				while (whitespaceEnd < lineEnd) {
					char c = chars.charAt(whitespaceEnd);
					if (c != ' ' && c != '\t') {
						break;
					}
					whitespaceEnd++;
				}

				if (whitespaceEnd == lineStart) {
					continue;
				}

				int toRemove = Math.min(unindentSize, whitespaceEnd - lineStart);
				int removeEnd = lineStart + toRemove;
				document.deleteString(lineStart, removeEnd);
			}
		}

	}
	private static boolean hasAnyLineStartingWithHash(@NotNull Document document, int startLine, int endLine) {
		CharSequence chars = document.getCharsSequence();
		int textLength = document.getTextLength();
		for (int line = startLine; line <= endLine; line++) {
			int lineStart = document.getLineStartOffset(line);
			if (lineStart >= textLength) {
				continue;
			}
			char first = chars.charAt(lineStart);
			if (first == '#') {
				return true;
			}
		}
		return false;
	}

	private static boolean allLinesStartWithHash(@NotNull Document document, int startLine, int endLine) {
		CharSequence chars = document.getCharsSequence();
		int textLength = document.getTextLength();
		for (int line = startLine; line <= endLine; line++) {
			int lineStart = document.getLineStartOffset(line);
			if (lineStart >= textLength) {
				continue;
			}
			char first = chars.charAt(lineStart);
			if (first != '#') {
				return false;
			}
		}
		return true;
	}

	private static boolean isParser3File(@NotNull Project project, @NotNull Editor editor) {
		PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
		return psiFile instanceof Parser3PsiFile;
	}

	private static int getUnindentSize(@NotNull Project project, @NotNull Document document) {
		PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
		if (psiFile == null) {
			return 0;
		}

		CodeStyleSettings settings = CodeStyle.getSettings(psiFile);
		CommonCodeStyleSettings.IndentOptions indentOptions = settings.getIndentOptions(psiFile.getFileType());
		if (indentOptions == null) {
			return 0;
		}

		if (indentOptions.USE_TAB_CHARACTER) {
			return 1;
		}

		return indentOptions.INDENT_SIZE > 0 ? indentOptions.INDENT_SIZE : 1;
	}

}