package ru.artlebedev.parser3.editor;

import com.intellij.codeInsight.lookup.LookupEvent;
import com.intellij.codeInsight.lookup.LookupListener;
import com.intellij.codeInsight.lookup.LookupManagerListener;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;
import com.intellij.util.Alarm;
import ru.artlebedev.parser3.completion.P3VariableInsertHandler;

/**
 * Отслеживает completion lookup и восстанавливает отступ закрывающего HTML тега
 * после вставки через autocomplete.
 *
 * Проблема: при вводе "</" IDE показывает autocomplete с именем тега.
 * При нажатии Enter IDE вставляет имя тега и переформатирует строку,
 * сбрасывая отступ в колонку 0.
 */
public class Parser3HtmlTagCompletionListener implements LookupManagerListener {

	private static final boolean DEBUG = false;

	@Override
	public void activeLookupChanged(@Nullable Lookup oldLookup, @Nullable Lookup newLookup) {
		if (DEBUG) {
			System.out.println("[P3HtmlTagCompletion] activeLookupChanged: old=" +
					(oldLookup != null ? "present" : "null") +
					", new=" + (newLookup != null ? "present" : "null"));
		}

		if (newLookup == null) {
			return;
		}

		Editor editor = newLookup.getEditor();
		if (editor == null) {
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] editor is null");
			return;
		}

		Project project = newLookup.getProject();
		if (project == null || project.isDisposed()) {
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] project is null/disposed");
			return;
		}

		// Проверяем что это Parser3 файл (или injected внутри Parser3)
		com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project)
				.getPsiFile(editor.getDocument());
		boolean isParser3 = false;
		if (psiFile != null) {
			if (Parser3PsiUtils.isParser3File(psiFile)) {
				isParser3 = true;
			} else {
				// Проверяем host файл (для injected HTML/CSS/JS внутри Parser3)
				com.intellij.psi.PsiElement ctx = psiFile.getContext();
				if (ctx != null) {
					com.intellij.psi.PsiFile hostFile = ctx.getContainingFile();
					if (hostFile != null && Parser3PsiUtils.isParser3File(hostFile)) {
						isParser3 = true;
						if (DEBUG) System.out.println("[P3HtmlTagCompletion] injected inside Parser3 file");
					}
				}
			}
		}
		if (!isParser3) {
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] not Parser3 file: " +
					(psiFile != null ? psiFile.getFileType().getName() : "null"));
			return;
		}

		// Для injected-контекстов (HTML/CSS/JS/SQL внутри Parser3) принудительно
		// выбираем первый пункт lookup, если по умолчанию ничего не выбрано.
		ensureDefaultLookupSelection(newLookup, project);

		// Для работы с indent нужен host документ и host editor
		Document hostDocument;
		Editor hostEditor;
		if (editor.getDocument() instanceof DocumentWindow) {
			// Ищем host document
			com.intellij.psi.PsiElement ctx = psiFile.getContext();
			if (ctx == null) {
				if (DEBUG) System.out.println("[P3HtmlTagCompletion] no context for injected file");
				return;
			}
			com.intellij.psi.PsiFile hostFile = ctx.getContainingFile();
			if (hostFile == null) {
				return;
			}
			hostDocument = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(hostFile);
			if (hostDocument == null) {
				return;
			}
			// Ищем host editor
			hostEditor = null;
			com.intellij.openapi.fileEditor.FileEditorManager fem =
					com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
			for (com.intellij.openapi.fileEditor.FileEditor fe : fem.getAllEditors()) {
				if (fe instanceof com.intellij.openapi.fileEditor.TextEditor) {
					Editor e = ((com.intellij.openapi.fileEditor.TextEditor) fe).getEditor();
					if (e.getDocument() == hostDocument) {
						hostEditor = e;
						break;
					}
				}
			}
			if (hostEditor == null) {
				if (DEBUG) System.out.println("[P3HtmlTagCompletion] host editor not found");
				return;
			}
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] using host document/editor");
		} else {
			hostDocument = editor.getDocument();
			hostEditor = editor;
		}

		// Сохраняем текущий отступ строки с курсором ДО вставки из autocomplete
		int caretOffset = hostEditor.getCaretModel().getOffset();
		int line = hostDocument.getLineNumber(caretOffset);
		int lineStart = hostDocument.getLineStartOffset(line);
		int lineEnd = hostDocument.getLineEndOffset(line);
		String lineText = hostDocument.getCharsSequence().subSequence(lineStart, lineEnd).toString();
		String currentIndent = leadingWhitespace(lineText);

		if (DEBUG) {
			System.out.println("[P3HtmlTagCompletion] lookup activated, line=" + line +
					", lineText='" + lineText + "', currentIndent='" + escapeForLog(currentIndent) + "'");
		}

		final Editor finalHostEditor = hostEditor;
		final Document finalHostDocument = hostDocument;
		newLookup.addLookupListener(new LookupListener() {
			@Override
			public void currentItemChanged(@NotNull LookupEvent event) {
				// При обновлении lookup IntelliJ может сбросить currentItem в null —
				// возвращаем выбор на первый пункт.
				if (event.getItem() == null) {
					ensureDefaultLookupSelection(event.getLookup(), project);
				}
			}

			@Override
			public void focusDegreeChanged() {
				// Auto-popup может позднее вернуться в UNFOCUSED: Enter уже работает
				// через currentItem, но строка не подсвечивается синим.
				ensureDefaultLookupSelection(newLookup, project);
			}

			@Override
			public void itemSelected(@NotNull LookupEvent event) {
				if (DEBUG) {
					System.out.println("[P3HtmlTagCompletion] itemSelected! item=" +
							(event.getItem() != null ? event.getItem().getLookupString() : "null"));
				}

				ApplicationManager.getApplication().invokeLater(() -> {
					if (project.isDisposed()) {
						return;
					}
					restoreClosingTagIndent(project, finalHostEditor, finalHostDocument, currentIndent);
				});
			}

			@Override
			public void lookupCanceled(@NotNull LookupEvent event) {
				if (DEBUG) System.out.println("[P3HtmlTagCompletion] lookupCanceled");
			}
		});
		ensureDefaultLookupSelection(newLookup, project);
	}

	private static void ensureDefaultLookupSelection(@NotNull Lookup lookup, @NotNull Project project) {
		// Lookup наполняется асинхронно — делаем серию delayed-попыток.
		Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, project);
		final int[] attemptsLeft = {12};
		Runnable trySelect = new Runnable() {
			@Override
			public void run() {
				if (project.isDisposed()) {
					return;
				}
				P3VariableInsertHandler.ensureFirstLookupItemSelected(lookup);
				attemptsLeft[0]--;
				if (attemptsLeft[0] > 0) {
					alarm.addRequest(this, 30);
				}
			}
		};
		alarm.addRequest(trySelect, 0);
	}

	/**
	 * Восстанавливает отступ строки после подтверждения HTML completion.
	 * Срабатывает как для строки вида "</tag>", так и для "<tag></tag>".
	 */
	private static void restoreClosingTagIndent(@NotNull Project project, @NotNull Editor editor,
												@NotNull Document document, @NotNull String savedIndent) {
		int caretOffset = editor.getCaretModel().getOffset();

		if (DEBUG) {
			System.out.println("[P3HtmlTagCompletion] restoreClosingTagIndent: caretOffset=" + caretOffset +
					", docLength=" + document.getTextLength());
		}

		if (caretOffset <= 0 || caretOffset > document.getTextLength()) {
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] restore: caret out of bounds");
			return;
		}

		int line = document.getLineNumber(caretOffset);
		int lineStart = document.getLineStartOffset(line);
		int lineEnd = document.getLineEndOffset(line);
		CharSequence text = document.getCharsSequence();
		String lineText = text.subSequence(lineStart, lineEnd).toString();
		String trimmed = lineText.stripLeading();
		String currentIndent = lineText.substring(0, lineText.length() - trimmed.length());

		if (DEBUG) {
			System.out.println("[P3HtmlTagCompletion] restore: line=" + line +
					", lineText='" + lineText + "'" +
					", trimmed='" + trimmed + "'" +
					", currentIndent='" + escapeForLog(currentIndent) + "'" +
					", savedIndent='" + escapeForLog(savedIndent) + "'");
		}

		// В строке должен появиться закрывающий тег, иначе не трогаем.
		// Поддерживаем оба случая: "</tag>" и "<tag></tag>".
		if (!trimmed.contains("</")) {
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] restore: no closing tag in line, skip");
			return;
		}

		// Если отступ уже совпадает с сохранённым — всё ОК
		if (currentIndent.equals(savedIndent)) {
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] restore: indent already correct, skip");
			return;
		}

		// Если текущий отступ больше сохранённого — не трогаем
		if (currentIndent.length() >= savedIndent.length() && !currentIndent.isEmpty()) {
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] restore: current indent >= saved, skip");
			return;
		}

		// Определяем правильный отступ
		String expectedIndent;
		if (!savedIndent.isEmpty()) {
			expectedIndent = savedIndent;
		} else {
			expectedIndent = findExpectedIndentForClosingTag(document, line);
		}

		if (expectedIndent == null || expectedIndent.isEmpty()) {
			if (DEBUG) System.out.println("[P3HtmlTagCompletion] restore: no expected indent found, skip");
			return;
		}

		int currentIndentLen = currentIndent.length();
		if (DEBUG) {
			System.out.println("[P3HtmlTagCompletion] RESTORING indent: " +
					"replacing '" + escapeForLog(currentIndent) + "' with '" + escapeForLog(expectedIndent) + "'");
		}

		final String indentToInsert = expectedIndent;
		final int restoreLine = line;
		// Запоминаем позицию каретки до restore — она стоит сразу после вставленного тега
		final int caretBeforeRestore = editor.getCaretModel().getOffset();
		CommandProcessor.getInstance().executeCommand(project, () -> {
			CommandProcessor.getInstance().runUndoTransparentAction(() ->
					ApplicationManager.getApplication().runWriteAction(() -> {
						document.replaceString(lineStart, lineStart + currentIndentLen, indentToInsert);
						// Сдвигаем сохранённую позицию каретки на дельту indent
						int delta = indentToInsert.length() - currentIndentLen;
						int newCaret = caretBeforeRestore + delta;
						if (newCaret >= 0 && newCaret <= document.getTextLength()) {
							editor.getCaretModel().moveToOffset(newCaret);
						}
						if (DEBUG) System.out.println("[P3HtmlTagCompletion] indent restored, caret at " + newCaret);
					}));
		}, "Parser3 Restore Closing Tag Indent", null);
	}

	/**
	 * Ищет ожидаемый отступ для закрывающего тега по предыдущим строкам.
	 */
	@Nullable
	private static String findExpectedIndentForClosingTag(@NotNull Document document, int closingTagLine) {
		CharSequence text = document.getCharsSequence();

		for (int prevLine = closingTagLine - 1; prevLine >= 0; prevLine--) {
			int prevStart = document.getLineStartOffset(prevLine);
			int prevEnd = document.getLineEndOffset(prevLine);
			String prevText = text.subSequence(prevStart, prevEnd).toString();
			String prevTrimmed = prevText.stripLeading();

			if (prevTrimmed.isEmpty()) {
				continue;
			}

			String prevIndent = prevText.substring(0, prevText.length() - prevTrimmed.length());

			if (DEBUG) {
				System.out.println("[P3HtmlTagCompletion] findIndent: prevLine=" + prevLine +
						", text='" + prevText + "', indent='" + escapeForLog(prevIndent) + "'");
			}

			// Предыдущая строка — открывающий тег: берём тот же отступ
			if (prevTrimmed.startsWith("<") && !prevTrimmed.startsWith("</") && !prevTrimmed.startsWith("<!")) {
				return prevIndent;
			}

			// Любая другая строка с отступом
			if (!prevIndent.isEmpty()) {
				return prevIndent;
			}

			break;
		}

		return null;
	}

	private static @NotNull String leadingWhitespace(@NotNull String text) {
		int i = 0;
		while (i < text.length()) {
			char ch = text.charAt(i);
			if (ch != ' ' && ch != '\t') break;
			i++;
		}
		return text.substring(0, i);
	}

	private static String escapeForLog(String s) {
		return s.replace("\t", "\\t").replace(" ", "·");
	}
}
