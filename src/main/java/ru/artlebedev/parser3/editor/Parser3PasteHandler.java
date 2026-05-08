package ru.artlebedev.parser3.editor;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorTextInsertHandler;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.util.Producer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.psi.Parser3PsiFile;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;

/**
 * Перехватчик paste для Parser3 файлов.
 *
 * Вставляет текст напрямую, затем форматирует вставленный фрагмент ОДИН раз асинхронно.
 * Это обходит проблему IntelliJ, который при "Reformat on paste: Indent each line"
 * вызывает PSI commit для КАЖДОЙ строки (~300ms на строку).
 *
 * Алгоритм:
 * 1. Вставляем текст напрямую через document.insertString() — мгновенно
 * 2. Если текст многострочный и содержит спецсимволы — форматируем через invokeLater()
 *
 * Форматирование НЕ вызывается если:
 * - Текст однострочный (нет \n)
 * - Текст не содержит символов <>(){}[]#
 */
public class Parser3PasteHandler extends EditorActionHandler implements EditorTextInsertHandler {

	private final EditorActionHandler originalHandler;

	// Символы, наличие которых требует форматирования
	private static final String FORMAT_TRIGGER_CHARS = "<>(){}[]#";

	public Parser3PasteHandler(EditorActionHandler originalHandler) {
		this.originalHandler = originalHandler;
	}

	@Override
	protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
		return true;
	}

	@Override
	protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
		Project project = editor.getProject();
		if (project == null) {
			executeOriginal(editor, caret, dataContext);
			return;
		}

		PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());

		// Только для Parser3 файлов — остальные используют стандартный обработчик
		if (!(psiFile instanceof Parser3PsiFile)) {
			executeOriginal(editor, caret, dataContext);
			return;
		}

		// Получаем текст из буфера обмена
		Transferable content = CopyPasteManager.getInstance().getContents();
		if (content == null) {
			executeOriginal(editor, caret, dataContext);
			return;
		}

		String text;
		try {
			text = (String) content.getTransferData(DataFlavor.stringFlavor);
		} catch (Exception e) {
			executeOriginal(editor, caret, dataContext);
			return;
		}

		if (text == null || text.isEmpty()) {
			executeOriginal(editor, caret, dataContext);
			return;
		}

		// В новых версиях IDEA paste action приходит под write-intent, но без настоящего write access.
		// Синхронный апгрейд до write action может надолго повесить EDT, если в фоне идет подсветка.
		if (!canUseCustomPasteNow()) {
			executeOriginal(editor, caret, dataContext);
			return;
		}

		// Проверяем, нужно ли форматирование
		boolean needsFormatting = needsFormatting(text);

		// Массив для передачи значений из WriteCommandAction в invokeLater
		final int[] range = new int[2];

		// Вставляем текст напрямую — быстро!
		WriteCommandAction.runWriteCommandAction(project, () -> {
			int startOffset = editor.getCaretModel().getOffset();

			// Если есть выделение — удаляем его
			if (editor.getSelectionModel().hasSelection()) {
				int selStart = editor.getSelectionModel().getSelectionStart();
				int selEnd = editor.getSelectionModel().getSelectionEnd();
				editor.getDocument().deleteString(selStart, selEnd);
				startOffset = selStart;
			}

			// Если после каретки до конца строки только пробельные символы — удаляем их
			CharSequence docText = editor.getDocument().getCharsSequence();
			int lineEnd = startOffset;

			// Находим конец строки
			while (lineEnd < docText.length() && docText.charAt(lineEnd) != '\n') {
				lineEnd++;
			}

			// Проверяем, состоит ли остаток строки (после каретки) только из пробельных символов
			boolean restIsWhitespaceOnly = true;
			for (int i = startOffset; i < lineEnd; i++) {
				char c = docText.charAt(i);
				if (!Character.isWhitespace(c)) {
					restIsWhitespaceOnly = false;
					break;
				}
			}

			// Если после каретки только whitespace — удаляем его
			if (restIsWhitespaceOnly && lineEnd > startOffset) {
				editor.getDocument().deleteString(startOffset, lineEnd);
			}

			// Если вставляемый текст начинается с # (hash-комментарий) — удаляем
			// whitespace ПЕРЕД курсором до начала строки, чтобы # попал в колонку 0
			if (!text.isEmpty() && text.charAt(0) == '#') {
				int lineStart = startOffset;
				while (lineStart > 0 && docText.charAt(lineStart - 1) != '\n') {
					lineStart--;
				}
				if (lineStart < startOffset) {
					// Перед курсором есть символы — удаляем только если все whitespace
					boolean prefixIsWhitespace = true;
					for (int i = lineStart; i < startOffset; i++) {
						if (!Character.isWhitespace(docText.charAt(i))) {
							prefixIsWhitespace = false;
							break;
						}
					}
					if (prefixIsWhitespace) {
						editor.getDocument().deleteString(lineStart, startOffset);
						startOffset = lineStart;
					}
				}
			}

			// Вставляем текст
			editor.getDocument().insertString(startOffset, text);

			int endOffset = startOffset + text.length();

			// Перемещаем каретку в конец вставленного текста
			editor.getCaretModel().moveToOffset(endOffset);
			editor.getSelectionModel().removeSelection();

			// Запоминаем диапазон для форматирования
			range[0] = startOffset;
			range[1] = endOffset;
		});

		// Форматируем только если нужно
		if (!needsFormatting) {
			return;
		}

		// Асинхронное форматирование — UI не блокируется
		ApplicationManager.getApplication().invokeLater(() -> {
			if (project.isDisposed()) {
				return;
			}

			// Проверяем что диапазон валиден
			int docLength = editor.getDocument().getTextLength();
			int startOffset = Math.min(range[0], docLength);
			int endOffset = Math.min(range[1], docLength);

			if (startOffset >= endOffset) {
				return;
			}

			WriteCommandAction.runWriteCommandAction(project, "Format Pasted Code", null, () -> {
				// Синхронизируем PSI с документом
				PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());

				PsiFile currentPsiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
				if (currentPsiFile == null) {
					return;
				}

				try {
					// Форматируем вставленный фрагмент ОДИН раз
					CodeStyleManager.getInstance(project).reformatText(currentPsiFile, startOffset, endOffset);
				} catch (Exception e) {
					// Игнорируем ошибки форматирования — текст уже вставлен
				}
			});
		});
	}

	/**
	 * Проверяет, нужно ли форматирование для вставляемого текста.
	 *
	 * Форматирование НЕ нужно если:
	 * 1. Текст однострочный (нет \n)
	 * 2. Текст не содержит символов, влияющих на форматирование: <>(){}[]#
	 */
	private boolean needsFormatting(String text) {
		// Однострочный текст — не форматируем
		if (!text.contains("\n")) {
			return false;
		}

		// Многострочный, но без спецсимволов — не форматируем
		for (int i = 0; i < text.length(); i++) {
			if (FORMAT_TRIGGER_CHARS.indexOf(text.charAt(i)) >= 0) {
				return true;
			}
		}

		return false;
	}

	static boolean canUseCustomPasteNow() {
		return ApplicationManager.getApplication().isWriteAccessAllowed();
	}

	private void executeOriginal(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
		if (originalHandler != null) {
			originalHandler.execute(editor, caret, dataContext);
		}
	}

	@Override
	public void execute(Editor editor, DataContext dataContext, Producer<? extends Transferable> producer) {
		doExecute(editor, null, dataContext);
	}
}
