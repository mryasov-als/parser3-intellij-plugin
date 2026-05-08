package ru.artlebedev.parser3.editor;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3FileType;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;
import ru.artlebedev.parser3.utils.Parser3TextUtils;

import java.util.Collections;

public final class Parser3TypedHandler extends TypedHandlerDelegate {


	private static final Key<HtmlIndentRestoreContext> HTML_INDENT_RESTORE_CONTEXT_KEY = Key.create("parser3.html.indent.restore.context");

	private static final class HtmlIndentRestoreContext {
		public final String indent;
		public final Editor hostEditor;

		public HtmlIndentRestoreContext(String indent, Editor hostEditor) {
			this.indent = indent;
			this.hostEditor = hostEditor;
		}
	}


	@Override
	public @NotNull Result beforeCharTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file, @NotNull FileType fileType) {

// Обрабатываем только Parser3-файлы (включая injected HTML внутри .p файлов)
		if (!Parser3PsiUtils.isParser3File(file)) {
			return Result.CONTINUE;
		}

		if (c != '/') {
			return Result.CONTINUE;
		}

		// Получаем host document (для injected HTML нужен Parser3 документ)
		Document document = editor.getDocument();
		Editor hostEditor = editor;
		if (document instanceof com.intellij.injected.editor.DocumentWindow) {
			com.intellij.injected.editor.DocumentWindow injectedDoc = (com.intellij.injected.editor.DocumentWindow) document;
			document = injectedDoc.getDelegate();
			// Ищем host editor
			com.intellij.openapi.fileEditor.FileEditorManager fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
			for (com.intellij.openapi.fileEditor.FileEditor fe : fem.getAllEditors()) {
				if (fe instanceof com.intellij.openapi.fileEditor.TextEditor) {
					Editor e = ((com.intellij.openapi.fileEditor.TextEditor) fe).getEditor();
					if (e.getDocument() == document) {
						hostEditor = e;
						break;
					}
				}
			}
		}

		int offset = hostEditor.getCaretModel().getOffset();
		if (offset <= 0 || offset > document.getTextLength()) {
			return Result.CONTINUE;
		}

		CharSequence seq = document.getCharsSequence();
		if (seq.charAt(offset - 1) != '<') {
			return Result.CONTINUE;
		}

		int line = document.getLineNumber(offset);
		int lineStart = document.getLineStartOffset(line);

		int lineEnd = document.getLineEndOffset(line);
		String lineText = document.getText(new TextRange(lineStart, lineEnd));

		String indent = leadingWhitespace(lineText);
		if (indent.isEmpty()) {
			return Result.CONTINUE;
		}
		editor.putUserData(HTML_INDENT_RESTORE_CONTEXT_KEY, new HtmlIndentRestoreContext(indent, hostEditor));

		// Some delegates in the chain can stop processing for '/', so charTyped() may not fire for us.
		// Restore indent from invokeLater to run after HTML auto-close / formatter.
		final Editor savedHostEditor = hostEditor;
		ApplicationManager.getApplication().invokeLater(() -> {
			HtmlIndentRestoreContext ctx = editor.getUserData(HTML_INDENT_RESTORE_CONTEXT_KEY);
			if (ctx == null) {
				return;
			}
			editor.putUserData(HTML_INDENT_RESTORE_CONTEXT_KEY, null);
			restoreHtmlIndentAfterTagAutoClose(project, savedHostEditor, ctx);
		});

		return Result.CONTINUE;
	}

	public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file, @NotNull FileType fileType) {
		return charTyped(c, project, editor, file);
	}

	@Override
	public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {

// Обрабатываем только Parser3-файлы (включая injected HTML внутри .p файлов)
		if (!Parser3PsiUtils.isParser3File(file)) {
			return Result.CONTINUE;
		}


		if (c == '/') {
			HtmlIndentRestoreContext ctx = editor.getUserData(HTML_INDENT_RESTORE_CONTEXT_KEY);
			if (ctx != null) {
				editor.putUserData(HTML_INDENT_RESTORE_CONTEXT_KEY, null);
				final Editor hostEditor = ctx.hostEditor;
				ApplicationManager.getApplication().invokeLater(() -> {
					restoreHtmlIndentAfterTagAutoClose(project, hostEditor, ctx);
				});
			}
			return Result.CONTINUE;
		}
		if (c == '(') {
			if (handleOpeningParen(editor)) {
				return Result.STOP;
			}
			return Result.CONTINUE;
		}

		if (c != ']' && c != ')' && c != '}') {
			return Result.CONTINUE;
		}

		int offset = editor.getCaretModel().getOffset();
		int closeOffset = offset - 1;
		if (closeOffset < 0) {
			return Result.CONTINUE;
		}

		BlockRange block = findBlockRange(editor, closeOffset, c);
		if (block == null) {
			return Result.CONTINUE;
		}

		PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
		PsiFile formatTarget = resolveFormatTarget(file);
		if (formatTarget == null) {
			return Result.CONTINUE;
		}
		CodeStyleManager styleManager = CodeStyleManager.getInstance(project);
		styleManager.reformatText(formatTarget, Collections.singletonList(new TextRange(block.startOffset, block.endOffset)));


		return Result.CONTINUE;
	}


	private static boolean handleOpeningParen(@NotNull Editor editor) {
		Document document = editor.getDocument();
		CharSequence text = document.getCharsSequence();
		int offset = editor.getCaretModel().getOffset();
		if (offset <= 0 || offset >= text.length()) {
			return false;
		}
		if (text.charAt(offset - 1) != '(') {
			return false;
		}
		if (text.charAt(offset) != ')') {
			return false;
		}

		int line = document.getLineNumber(offset);
		int lineStart = document.getLineStartOffset(line);
		int lineEnd = document.getLineEndOffset(line);
		if (lineStart >= lineEnd) {
			return false;
		}

		int openOffset = offset - 1;
		int openInLine = openOffset - lineStart;
		// prevent jumping when line has only indent
		if (openInLine == 0) {
			return false;
		}
		int closeInLine = openInLine + 1;

		String lineText = text.subSequence(lineStart, lineEnd).toString();
		if (!Parser3TextUtils.isLeftWhitespace(lineText, openInLine - 1)) {
			return false;
		}
		int len = lineText.length();
		if (closeInLine + 1 >= len) {
			return true;
		}

		int idx = closeInLine + 1;
		while (idx < len) {
			char ch = lineText.charAt(idx);
			if (ch != ' ' && ch != '\t') {
				break;
			}
			idx++;
		}
		if (idx >= len) {
			return false;
		}

		int exprStart = idx;
		int commentIndex = lineText.indexOf('#', exprStart);
		int exprEndCandidate = commentIndex >= 0 ? commentIndex : len;
		int exprEnd = exprEndCandidate;
		while (exprEnd > exprStart && Character.isWhitespace(lineText.charAt(exprEnd - 1))) {
			exprEnd--;
		}
		if (exprEnd <= exprStart) {
			return true;
		}

		String prefixBeforeOpen = lineText.substring(0, openInLine);
		String exprPart = lineText.substring(exprStart, exprEnd);
		String commentPart = lineText.substring(exprEnd);

		String newLine = prefixBeforeOpen + "(" + exprPart + ")" + commentPart;
		document.replaceString(lineStart, lineEnd, newLine);

		int newCaretOffset = lineStart + prefixBeforeOpen.length() + 1 + exprPart.length() + 1;
		if (newCaretOffset > document.getTextLength()) {
			newCaretOffset = document.getTextLength();
		}
		editor.getCaretModel().moveToOffset(newCaretOffset);

		return true;
	}

	private static final class BlockRange {
		final int startOffset;
		final int endOffset;

		BlockRange(int startOffset, int endOffset) {
			this.startOffset = startOffset;
			this.endOffset = endOffset;
		}
	}

	private static @Nullable BlockRange findBlockRange(@NotNull Editor editor, int closeOffset, char typedChar) {
		Document document = editor.getDocument();
		CharSequence text = document.getCharsSequence();
		if (closeOffset < 0 || closeOffset >= text.length()) {
			return null;
		}

		char closeChar = text.charAt(closeOffset);
		if (closeChar != typedChar) {
			// На всякий случай: текст уже мог поменяться.
			return null;
		}

		char openChar;
		if (closeChar == ']') {
			openChar = '[';
		} else if (closeChar == ')') {
			openChar = '(';
		} else if (closeChar == '}') {
			openChar = '{';
		} else {
			return null;
		}

		int depth = 0;
		for (int i = closeOffset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == closeChar) {
				depth++;
			} else if (ch == openChar) {
				if (depth == 0) {
					int openOffset = i;
					int openLine = document.getLineNumber(openOffset);
					int closeLine = document.getLineNumber(closeOffset);
					if (openLine > closeLine) {
						return null;
					}
					// Ведём себя как при частичном выделении:
					// старт с позиции открывающей скобки, конец — конец строки с закрывающей.
					int start = openOffset;
					int end = document.getLineEndOffset(closeLine);
					if (start >= end) {
						return null;
					}
					return new BlockRange(start, end);
				}
				depth--;
			}
		}

		return null;
	}

	private static @Nullable PsiFile resolveFormatTarget(@NotNull PsiFile file) {
		if (file.getFileType() == Parser3FileType.INSTANCE) {
			return file;
		}

		PsiElement context = file.getContext();
		if (context == null) {
			return null;
		}

		PsiFile hostFile = context.getContainingFile();
		if (hostFile == null || hostFile == file) {
			return null;
		}

		return hostFile.getFileType() == Parser3FileType.INSTANCE ? hostFile : null;
	}

	private static void restoreHtmlIndentAfterTagAutoClose(@NotNull Project project, @NotNull Editor editor, @NotNull HtmlIndentRestoreContext ctx) {
		if (project.isDisposed()) {
			return;
		}

		Document document = editor.getDocument();
		int caretOffset = editor.getCaretModel().getOffset();
		if (caretOffset < 0 || caretOffset > document.getTextLength()) {
			return;
		}

		int line = document.getLineNumber(caretOffset);
		int lineStart = document.getLineStartOffset(line);
		int lineEnd = document.getLineEndOffset(line);

		if (lineStart < 0 || lineEnd < lineStart || lineEnd > document.getTextLength()) {
			return;
		}

		CharSequence seq = document.getCharsSequence();
		if (lineStart >= seq.length()) {
			return;
		}

		// Если HTML-автозакрытие изменило отступ строки, возвращаем исходный.
		String currentLineText = seq.subSequence(lineStart, lineEnd).toString();
		String currentIndent = leadingWhitespace(currentLineText);
		int indentLen = ctx.indent.length();

		// Отступ не пропал — ничего не делаем
		if (currentIndent.length() >= indentLen) {
			return;
		}

		// Вставляем недостающую часть отступа
		int missingLen = indentLen - currentIndent.length();
		String missingIndent = ctx.indent.substring(0, missingLen);

		CommandProcessor.getInstance().executeCommand(project, () -> {
			CommandProcessor.getInstance().runUndoTransparentAction(() -> ApplicationManager.getApplication().runWriteAction(() -> {
				// Запоминаем позицию курсора до вставки отступа
				int caretBeforeInsert = editor.getCaretModel().getOffset();

				document.insertString(lineStart, missingIndent);

				// Курсор сдвигаем на длину вставленного отступа (он вставлен перед курсором)
				editor.getCaretModel().moveToOffset(caretBeforeInsert + missingLen);
			}));
		}, "Parser3 Restore Indent", null);
	}


	private static @NotNull String leadingWhitespace(@NotNull String text) {
		int i = 0;
		while (i < text.length()) {
			char ch = text.charAt(i);
			if (ch == ' ' || ch == '\t') {
				i++;
				continue;
			}
			break;
		}
		return text.substring(0, i);
	}

	private static char firstNonWhitespaceChar(@NotNull String text) {
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') continue;
			return ch;
		}
		return 0;
	}
}
