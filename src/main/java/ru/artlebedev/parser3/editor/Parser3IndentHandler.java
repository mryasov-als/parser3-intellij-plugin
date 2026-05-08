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
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.psi.Parser3PsiFile;

public final class Parser3IndentHandler extends EditorWriteActionHandler {

	private final EditorActionHandler originalHandler;

	public Parser3IndentHandler(@NotNull EditorActionHandler originalHandler) {
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

		if (!isParser3File(project, editor)) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}

		Document document = editor.getDocument();
		SelectionModel selectionModel = editor.getSelectionModel();

		if (selectionModel.hasSelection()) {
			int selectionStart = selectionModel.getSelectionStart();
			int selectionEnd = selectionModel.getSelectionEnd();
			int startLine = document.getLineNumber(selectionStart);
			int endLine = document.getLineNumber(Math.max(selectionStart, selectionEnd - 1));

			boolean[] hashAtLineStart = captureHashAtLineStart(document, startLine, endLine);

			originalHandler.execute(editor, caret, dataContext);

			fixCommentLinesIndent(document, startLine, endLine, hashAtLineStart);
			return;
		}

		if (caret == null) {
			originalHandler.execute(editor, null, dataContext);
			return;
		}

		int caretOffset = caret.getOffset();
		if (caretOffset < 0 || caretOffset > document.getTextLength()) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}

		int line = document.getLineNumber(caretOffset);
		int lineStart = document.getLineStartOffset(line);
		int lineEnd = document.getLineEndOffset(line);

		if (lineStart >= document.getTextLength()) {
			originalHandler.execute(editor, caret, dataContext);
			return;
		}

		CharSequence chars = document.getCharsSequence();
		// 1) Lines starting with '#' — keep old behavior for hash comments
		if (chars.charAt(lineStart) == '#') {
			String indentString = getIndentString(project, document);
			if (indentString.isEmpty()) {
				originalHandler.execute(editor, caret, dataContext);
				return;
			}
			int insertOffset = lineStart + 1;
			if (insertOffset > lineEnd) {
				insertOffset = lineEnd;
			}
			document.insertString(insertOffset, indentString);
			if (caretOffset >= insertOffset) {
				caret.moveToOffset(caretOffset + indentString.length());
			}
			return;
		}

		// 2) HTML opening tag (non-void) — add one indent level on new line
		if (shouldIndentHtmlOnEnter(chars, lineStart, lineEnd)) {
			String indentString = getIndentString(project, document);
			if (!indentString.isEmpty()) {
				// Let original handler insert newline and basic indent (if any)
				originalHandler.execute(editor, caret, dataContext);

				int newOffset = caret.getOffset();
				int newLine = document.getLineNumber(newOffset);
				int newLineStart = document.getLineStartOffset(newLine);

				// Insert one indent unit at the start of the new line
				document.insertString(newLineStart, indentString);

				if (newOffset >= newLineStart) {
					caret.moveToOffset(newOffset + indentString.length());
				}
				return;
			}
		}

		// 3) Parser3 block open (line ending with '{') — add extra indent level
		if (shouldIndentAfterBrace(chars, lineStart, lineEnd, caretOffset)) {
			String indentString = getIndentString(project, document);
			if (!indentString.isEmpty()) {
				// Let original handler insert newline first
				originalHandler.execute(editor, caret, dataContext);

				int newOffset = caret.getOffset();
				int newLine = document.getLineNumber(newOffset);
				int newLineStart = document.getLineStartOffset(newLine);
				int newLineEnd = document.getLineEndOffset(newLine);

				CharSequence updated = document.getCharsSequence();
				int firstNonWs = findFirstNonWhitespace(updated, newLineStart, newLineEnd);
				int indentEnd = firstNonWs == -1 ? newLineEnd : firstNonWs;

				// Base indent = indent of previous line
				int prevFirstNonWs = findFirstNonWhitespace(chars, lineStart, lineEnd);
				String baseIndent = prevFirstNonWs == -1 ? "" : chars.subSequence(lineStart, prevFirstNonWs).toString();
				String desiredIndent = baseIndent + indentString;
				String currentIndent = updated.subSequence(newLineStart, indentEnd).toString();

				if (!currentIndent.equals(desiredIndent)) {
					// replace current indent with desired
					if (indentEnd > newLineStart) {
						document.deleteString(newLineStart, indentEnd);
					}
					document.insertString(newLineStart, desiredIndent);
					int delta = desiredIndent.length() - currentIndent.length();
					caret.moveToOffset(newOffset + delta);
				}
				return;
			}
		}

		// 4) Default behavior for all other lines
		originalHandler.execute(editor, caret, dataContext);
	}

	private static boolean isParser3File(@NotNull Project project, @NotNull Editor editor) {
		PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
		return psiFile instanceof Parser3PsiFile;
	}


	private static boolean shouldIndentHtmlOnEnter(@NotNull CharSequence chars, int lineStart, int lineEnd) {
		if (lineStart >= lineEnd) {
			return false;
		}
		String line = chars.subSequence(lineStart, lineEnd).toString().trim();
		if (line.isEmpty()) {
			return false;
		}
		// Must start with '<'
		if (line.charAt(0) != '<') {
			return false;
		}
		// Ignore closing/special tags
		if (line.startsWith("</") || line.startsWith("<!") || line.startsWith("<?")) {
			return false;
		}
		// Parse tag name
		int i = 1;
		int len = line.length();
		while (i < len && Character.isWhitespace(line.charAt(i))) {
			i++;
		}
		int nameStart = i;
		while (i < len && Character.isLetterOrDigit(line.charAt(i))) {
			i++;
		}
		if (nameStart == i) {
			return false;
		}
		String tagName = line.substring(nameStart, i).toLowerCase(java.util.Locale.ROOT);
		// Ignore void tags like <br>, <hr>, <img>
		if (isVoidHtmlTag(tagName)) {
			return false;
		}
		return true;
	}

	private static boolean isVoidHtmlTag(@NotNull String tagName) {
		String t = tagName.toLowerCase(java.util.Locale.ROOT);
		return "br".equals(t) || "hr".equals(t) || "img".equals(t);
	}

	private static boolean shouldIndentAfterBrace(@NotNull CharSequence chars,
												  int lineStart,
												  int lineEnd,
												  int caretOffset) {
		// Look at the part of the line before the caret
		int limit = Math.min(caretOffset, lineEnd);
		if (lineStart >= limit) {
			return false;
		}
		// Find last non-whitespace character before limit
		int pos = limit - 1;
		while (pos >= lineStart) {
			char c = chars.charAt(pos);
			if (c != ' ' && c != '\t') {
				break;
			}
			pos--;
		}
		if (pos < lineStart) {
			return false;
		}
		return chars.charAt(pos) == '{';
	}


	@NotNull
	private static boolean[] captureHashAtLineStart(@NotNull Document document, int startLine, int endLine) {
		boolean[] result = new boolean[endLine - startLine + 1];
		CharSequence chars = document.getCharsSequence();
		int textLength = document.getTextLength();

		for (int line = startLine; line <= endLine; line++) {
			int lineStart = document.getLineStartOffset(line);
			if (lineStart < textLength && chars.charAt(lineStart) == '#') {
				result[line - startLine] = true;
			}
		}

		return result;
	}

	private static void fixCommentLinesIndent(@NotNull Document document,
											  int startLine,
											  int endLine,
											  @NotNull boolean[] hashAtLineStart) {
		if (hashAtLineStart.length != endLine - startLine + 1) {
			return;
		}

		for (int line = startLine; line <= endLine; line++) {
			if (!hashAtLineStart[line - startLine]) {
				continue;
			}

			int lineStart = document.getLineStartOffset(line);
			int lineEnd = document.getLineEndOffset(line);
			if (lineStart >= document.getTextLength()) {
				continue;
			}

			CharSequence chars = document.getCharsSequence();
			int firstNonWs = findFirstNonWhitespace(chars, lineStart, lineEnd);
			if (firstNonWs < 0) {
				continue;
			}

			if (chars.charAt(firstNonWs) != '#') {
				continue;
			}

			if (lineStart == firstNonWs) {
				continue;
			}

			TextRange indentRange = new TextRange(lineStart, firstNonWs);
			String indentText = document.getText(indentRange);
			if (indentText.isEmpty()) {
				continue;
			}

			int indentLength = indentRange.getLength();
			document.deleteString(indentRange.getStartOffset(), indentRange.getEndOffset());

			int hashOffsetAfterDelete = lineStart;
			CharSequence updatedChars = document.getCharsSequence();
			if (hashOffsetAfterDelete >= document.getTextLength() || updatedChars.charAt(hashOffsetAfterDelete) != '#') {
				continue;
			}

			int insertOffset = hashOffsetAfterDelete + 1;
			document.insertString(insertOffset, indentText);
		}
	}

	private static int findFirstNonWhitespace(@NotNull CharSequence chars, int startOffset, int endOffset) {
		int offset = startOffset;
		while (offset < endOffset) {
			char c = chars.charAt(offset);
			if (c != ' ' && c != '\t') {
				return offset;
			}
			offset++;
		}
		return -1;
	}

	@NotNull
	private static String getIndentString(@NotNull Project project, @NotNull Document document) {
		PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
		if (psiFile == null) {
			return "";
		}

		CodeStyleSettings settings = CodeStyle.getSettings(psiFile);
		CommonCodeStyleSettings.IndentOptions indentOptions = settings.getIndentOptions(psiFile.getFileType());
		if (indentOptions == null) {
			return "";
		}

		if (indentOptions.USE_TAB_CHARACTER) {
			return "\t";
		}

		int size = indentOptions.INDENT_SIZE > 0 ? indentOptions.INDENT_SIZE : 1;
		return StringUtil.repeat(" ", size);
	}

	public void tokenize() {
		// no-op, project convention
	}
}