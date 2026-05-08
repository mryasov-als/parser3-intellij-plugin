package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * InsertHandler для методов Parser3.
 * Удаляет остаток старого метода и добавляет скобки если нужно.
 *
 * Удаляет символы идентификатора + цепочки :method / ::constructor + старые скобки.
 * Любой другой символ (&, =, пробел и т.д.) — стоп.
 *
 * Примеры:
 * - ^test|_method[] + выбор test_new → ^test_new[]
 * - ^d|ate::create[] + выбор file → ^file[] (удаляет ate::create[])
 * - ^file:d|elete[] + выбор list → ^file:list[] (удаляет elete[])
 * - ^test|_method&next + выбор test_new → ^test_new[]&next (НЕ удаляет &next)
 */
public final class P3MethodInsertHandler implements InsertHandler<LookupElement> {

	/** Стандартный инстанс с [] */
	public static final P3MethodInsertHandler INSTANCE = new P3MethodInsertHandler("[]");

	/** Инстанс с () */
	public static final P3MethodInsertHandler PARENS = new P3MethodInsertHandler("()");

	/** Инстанс с {} */
	public static final P3MethodInsertHandler BRACES = new P3MethodInsertHandler("{}");

	private final String suffix;

	private P3MethodInsertHandler(String suffix) {
		this.suffix = suffix;
	}

	/**
	 * Создаёт InsertHandler с указанным суффиксом
	 */
	public static P3MethodInsertHandler withSuffix(String suffix) {
		if (suffix == null || suffix.isEmpty() || "[]".equals(suffix)) {
			return INSTANCE;
		}
		if ("()".equals(suffix)) {
			return PARENS;
		}
		if ("{}".equals(suffix)) {
			return BRACES;
		}
		return new P3MethodInsertHandler(suffix);
	}

	@Override
	public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
		char completionChar = context.getCompletionChar();
		if (completionChar == '.' || completionChar == ' ') {
			return;
		}

		Document doc = context.getDocument();
		int tailOffset = context.getTailOffset();
		CharSequence text = doc.getCharsSequence();
		int textLength = text.length();

		// Проверяем, заканчивается ли вставленный текст скобками ([], {}, ())
		// Это случается когда lookup string уже содержит скобки
		boolean insertedWithBrackets = false;
		if (tailOffset >= 2) {
			char beforeLast = text.charAt(tailOffset - 2);
			char last = text.charAt(tailOffset - 1);
			if ((beforeLast == '[' && last == ']') ||
					(beforeLast == '(' && last == ')') ||
					(beforeLast == '{' && last == '}')) {
				insertedWithBrackets = true;
			}
		}

		// Ищем конец старого метода: идентификатор + цепочки :identifier / ::identifier + скобки
		int endOffset = deleteOldMethodTail(text, tailOffset, textLength);

		// Удаляем остаток старого метода
		if (endOffset > tailOffset) {
			doc.deleteString(tailOffset, endOffset);
		}

		// Если вставленный текст уже содержит скобки — просто ставим курсор внутрь
		if (insertedWithBrackets) {
			context.getEditor().getCaretModel().moveToOffset(tailOffset - 1);
			scheduleTableColumnAutoPopup(context);
			return;
		}

		// Проверяем есть ли уже скобки после (от старого кода)
		int newTailOffset = context.getTailOffset();
		CharSequence newText = doc.getCharsSequence();

		// Проверяем какие скобки стоят после курсора
		if (newTailOffset < doc.getTextLength()) {
			char open = newText.charAt(newTailOffset);
			if (open == '[' || open == '(' || open == '{') {
				char expectedClose = (open == '[') ? ']' : (open == '(') ? ')' : '}';
				char expectedOpen = suffix.charAt(0);

				if (open == expectedOpen) {
					// Скобки совпадают с ожидаемыми — двигаем курсор внутрь
					context.getEditor().getCaretModel().moveToOffset(newTailOffset + 1);
				} else if (newTailOffset + 1 < doc.getTextLength()
						&& newText.charAt(newTailOffset + 1) == expectedClose) {
					// Пустые скобки не того типа — заменяем
					doc.deleteString(newTailOffset, newTailOffset + 2);
					doc.insertString(newTailOffset, suffix);
					context.getEditor().getCaretModel().moveToOffset(newTailOffset + 1);
				} else {
					// Скобки с содержимым не того типа — двигаем курсор внутрь (сохраняем содержимое)
					context.getEditor().getCaretModel().moveToOffset(newTailOffset + 1);
				}
				scheduleTableColumnAutoPopup(context);
				return;
			}
		}

		// Скобок нет — вставляем
		doc.insertString(newTailOffset, suffix);
		context.getEditor().getCaretModel().moveToOffset(newTailOffset + 1);
		scheduleTableColumnAutoPopup(context);
	}

	private static void scheduleTableColumnAutoPopup(@NotNull InsertionContext context) {
		VirtualFile virtualFile = context.getFile().getVirtualFile();
		if (virtualFile == null) return;

		Document doc = context.getDocument();
		int offset = context.getEditor().getCaretModel().getOffset();
		if (shouldScheduleTableColumnAutoPopup(context.getProject(), virtualFile, doc.getCharsSequence().toString(), offset)) {
			ApplicationManager.getApplication().invokeLater(
					() -> AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor())
			);
		}
	}

	public static boolean shouldScheduleTableColumnAutoPopup(
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull VirtualFile virtualFile,
			@NotNull String text,
			int offset
	) {
		return P3TableColumnArgumentCompletionSupport.isColumnArgumentContext(project, virtualFile, text, offset);
	}

	/**
	 * Вычисляет позицию конца старого метода для удаления.
	 *
	 * Логика:
	 * 1. Удаляем символы идентификатора (буквы, цифры, '_', '-')
	 * 2. Если дальше ':' или '::' + идентификатор — удаляем цепочку (старый :method / ::constructor)
	 * 3. Повторяем с шага 1
	 * 4. Если дальше скобки [...]/(...)/{...} — удаляем их (старые скобки метода)
	 * 5. Повторяем с шага 4 (для цепочек скобок []{})
	 *
	 * Примеры:
	 * - "ate::create[]:" → удаляет "ate::create[]", оставляет ":"
	 * - "elete[]:" → удаляет "elete[]", оставляет ":"
	 * - "[data]" → НЕ удаляет (скобки с содержимым, нет остатка идентификатора)
	 * - "thod[data]" → удаляет "thod", сохраняет "[data]"
	 * - "ate[]{body}" → удаляет "ate[]", сохраняет "{body}"
	 * - "&field2=15" → ничего не удаляет (& — не идентификатор)
	 */
	static int deleteOldMethodTail(@NotNull CharSequence text, int from, int limit) {
		int i = from;

		// Шаг 1-3: удаляем идентификатор + цепочки :identifier / ::identifier
		while (true) {
			// Удаляем символы идентификатора
			int afterIdent = P3VariableInsertHandler.skipIdentifierChars(text, i, limit);
			if (afterIdent > i) {
				i = afterIdent;
			}

			// Проверяем ':' или '::' + идентификатор
			if (i < limit && text.charAt(i) == ':') {
				int colonEnd = i + 1;
				// '::' — двойное двоеточие
				if (colonEnd < limit && text.charAt(colonEnd) == ':') {
					colonEnd++;
				}
				// После двоеточия(й) должен быть идентификатор
				int identAfterColon = P3VariableInsertHandler.skipIdentifierChars(text, colonEnd, limit);
				if (identAfterColon > colonEnd) {
					// Есть идентификатор после ':' / '::' — удаляем всю цепочку
					i = identAfterColon;
					continue; // повторяем — может быть ещё одна цепочка
				}
			}

			break;
		}

		// Шаг 4-5: удаляем только ПУСТЫЕ скобки [...] / (...) / {...}
		// Скобки с содержимым сохраняем — это параметры, которые пользователь хочет оставить
		while (i < limit) {
			char c = text.charAt(i);
			if (c == '[' || c == '(' || c == '{') {
				char closeCh = (c == '[') ? ']' : (c == '(') ? ')' : '}';
				// Пустые скобки: open сразу перед close
				if (i + 1 < limit && text.charAt(i + 1) == closeCh) {
					i += 2; // удаляем пустые скобки
					continue;
				}
				// Скобки с содержимым — стоп, не удаляем
				break;
			}
			break;
		}

		return i;
	}

	/**
	 * Простой поиск парной скобки с учётом вложенности.
	 * Не учитывает строки и экранирование — достаточно для контекста автокомплита.
	 */
	private static int findSimpleMatchingBracket(@NotNull CharSequence text, int openPos, int limit,
												 char openCh, char closeCh) {
		int depth = 0;
		for (int i = openPos; i < limit; i++) {
			char c = text.charAt(i);
			if (c == openCh) {
				depth++;
			} else if (c == closeCh) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}
}
