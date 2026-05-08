package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import ru.artlebedev.parser3.utils.Parser3TextUtils;
import static ru.artlebedev.parser3.lexer.Parser3LexerCore.findLineEnd;
import static ru.artlebedev.parser3.lexer.Parser3LexerCore.isAtLineStart;
import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * Разбор только комментариев core-уровня:
 * - LINE_COMMENT: строка, начинающаяся с '#'
 * - BLOCK_COMMENT: ^rem{ ... } с учётом вложенных {}
 *
 * Возвращает только комментарии, без TEMPLATE_DATA.
 */
public final class Parser3CommentLexerCore {

	private Parser3CommentLexerCore() {
	}

	@NotNull
	public static List<Parser3LexerCore.CoreToken> lexComments(@NotNull CharSequence text) {
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();
		int length = text.length();
		int i = 0;
		int parenDepth = 0;
		int bracketDepth = 0; // Счётчик квадратных скобок — внутри них # не комментарий
		int braceDepth = 0;   // Счётчик фигурных скобок
		int sqlBraceDepth = 0; // Счётчик SQL блоков — внутри них # не комментарий
		java.util.Deque<Integer> braceParenStack = new java.util.ArrayDeque<>(); // Стек parenDepth при входе в {}
		boolean inSingleQuote = false; // Внутри одинарных кавычек
		boolean inDoubleQuote = false; // Внутри двойных кавычек

		boolean DEBUG = false; // Включить для отладки

		while (i < length) {
			char c = text.charAt(i);

			// 0) Сброс всех счётчиков при встрече '@' в начале строки
			//    Это начало новой директивы (@CLASS, @USE, @METHOD и т.д.)
			//    Все незакрытые скобки/кавычки от предыдущего кода сбрасываются
			//    НО только если мы не внутри ^rem{...} блока (braceDepth == 0)
			if (c == '@' && isAtLineStart(text, i) && braceDepth == 0) {
				parenDepth = 0;
				bracketDepth = 0;
				braceParenStack.clear();
				inSingleQuote = false;
				inDoubleQuote = false;
				i++;
				continue;
			}

			// 1) Блочный комментарий ^rem{ ... }
			if (!inSingleQuote && !inDoubleQuote && isRemOpen(text, i)) {
				i = lexRemBlock(text, i, result);
				continue;
			}

			// 2) Однострочный комментарий в начале строки: '#' в колонке 0
			//    Проверяем ДО отслеживания кавычек, т.к. это безусловный комментарий
			//    # в колонке 0 — это ВСЕГДА комментарий, независимо от любых скобок
			if (c == '#' && isAtLineStart(text, i)) {
				int start = i;
				int end = findLineEnd(text, i);
				String debug = substringSafely(text, start, end);
				result.add(new Parser3LexerCore.CoreToken(start, end, "LINE_COMMENT", debug));
				i = end;
				continue;
			}

			// Отслеживание кавычек (только внутри круглых скобок — вне скобок кавычки это просто текст)
			// Кавычки отслеживаются ВСЕГДА когда parenDepth > 0, независимо от braceDepth
			if (parenDepth > 0) {
				if (c == '\'' && !Parser3TextUtils.isEscaped(text, i)) {
					inSingleQuote = !inSingleQuote;
					if (DEBUG) {
						System.out.println(String.format("[lexComments] pos=%d char='\\'' inSingleQuote=%s→%s",
								i, !inSingleQuote, inSingleQuote));
					}
					i++;
					continue;
				}
				if (c == '"' && !Parser3TextUtils.isEscaped(text, i)) {
					// ^" не считается кавычкой
					if (i > 0 && text.charAt(i - 1) == '^') {
						i++;
						continue;
					}
					inDoubleQuote = !inDoubleQuote;
					if (DEBUG) {
						System.out.println(String.format("[lexComments] pos=%d char='\"' inDoubleQuote=%s→%s",
								i, !inDoubleQuote, inDoubleQuote));
					}
					i++;
					continue;
				}
			}

			// Пропускаем всё внутри кавычек (кроме самих кавычек)
			if (inSingleQuote || inDoubleQuote) {
				i++;
				continue;
			}

			// Учёт круглых скобок для комментариев внутри выражения
			// Считаем круглые скобки везде — важен относительный уровень внутри текущего {} блока
			if (c == '(') {
				parenDepth++;
				if (DEBUG) {
					System.out.println(String.format("[lexComments] pos=%d char='(' parenDepth=%d→%d braceDepth=%d",
							i, parenDepth - 1, parenDepth, braceDepth));
				}
				i++;
				continue;
			}
			if (c == ')' && parenDepth > 0) {
				if (DEBUG) {
					System.out.println(String.format("[lexComments] pos=%d char=')' parenDepth=%d→%d braceDepth=%d",
							i, parenDepth, parenDepth - 1, braceDepth));
				}
				parenDepth--;
				// Сбрасываем состояние кавычек при выходе из скобок
				if (parenDepth == 0) {
					inSingleQuote = false;
					inDoubleQuote = false;
				}
				i++;
				continue;
			}

			// Учёт квадратных скобок — внутри них '#' не является комментарием
			if (c == '[') {
				bracketDepth++;
				i++;
				continue;
			}
			if (c == ']' && bracketDepth > 0) {
				bracketDepth--;
				i++;
				continue;
			}

			// Учёт фигурных скобок — внутри них '#' не является комментарием,
			// КРОМЕ случая когда мы внутри () после входа в {}
			// Сохраняем parenDepth при входе в {} ТОЛЬКО если { открывается ВНЕ ()
			// ВАЖНО: SQL блоки :sql{...} и ::sql{...} отслеживаются отдельно
			if (c == '{') {
				// Внутри SQL блока отслеживаем вложенные скобки
				if (sqlBraceDepth > 0) {
					sqlBraceDepth++;
					if (DEBUG) {
						System.out.println(String.format("[lexComments] pos=%d char='{' SQL nested sqlBraceDepth=%d", i, sqlBraceDepth));
					}
					i++;
					continue;
				}

				// Проверяем не является ли это SQL-блоком: :sql{ или ::sql{
				boolean isSqlBlock = false;
				if (i >= 4) {
					String before = text.subSequence(Math.max(0, i - 5), i).toString();
					if (before.endsWith(":sql") || before.endsWith("::sql")) {
						isSqlBlock = true;
						sqlBraceDepth = 1; // Начало SQL блока
					}
				}

				if (!isSqlBlock) {
					braceDepth++;
					// Если { открывается внутри () — НЕ сбрасываем parenDepth
					// Если { открывается вне () — сохраняем 0 и сбрасываем
					if (parenDepth == 0) {
						braceParenStack.push(0);
						if (DEBUG) {
							System.out.println(String.format("[lexComments] pos=%d char='{' parenDepth=%d braceDepth=%d→%d push(0)",
									i, parenDepth, braceDepth - 1, braceDepth));
						}
					} else {
						braceParenStack.push(parenDepth);
						if (DEBUG) {
							System.out.println(String.format("[lexComments] pos=%d char='{' parenDepth=%d→0 braceDepth=%d→%d push(%d)",
									i, parenDepth, braceDepth - 1, braceDepth, parenDepth));
						}
						parenDepth = 0; // Сбрасываем для нового контекста внутри {}
					}
				} else {
					if (DEBUG) {
						System.out.println(String.format("[lexComments] pos=%d char='{' SQL block start sqlBraceDepth=1", i));
					}
				}
				i++;
				continue;
			}
			if (c == '}') {
				// Проверяем не закрывается ли SQL блок
				if (sqlBraceDepth > 0) {
					sqlBraceDepth--;
					if (DEBUG) {
						System.out.println(String.format("[lexComments] pos=%d char='}' SQL block sqlBraceDepth→%d", i, sqlBraceDepth));
					}
					i++;
					continue;
				}

				// Обычная фигурная скобка
				if (braceDepth > 0) {
					braceDepth--;
					// Восстанавливаем parenDepth
					if (!braceParenStack.isEmpty()) {
						int saved = braceParenStack.pop();
						// Восстанавливаем только если было сброшено (saved != 0 или текущий parenDepth == 0)
						if (parenDepth == 0) {
							if (DEBUG) {
								System.out.println(String.format("[lexComments] pos=%d char='}' parenDepth=%d→%d braceDepth=%d→%d pop(%d)",
										i, parenDepth, saved, braceDepth + 1, braceDepth, saved));
							}
							parenDepth = saved;
						} else {
							if (DEBUG) {
								System.out.println(String.format("[lexComments] pos=%d char='}' parenDepth=%d (no restore) braceDepth=%d→%d pop(%d)",
										i, parenDepth, braceDepth + 1, braceDepth, saved));
							}
						}
					}
				}
				i++;
				continue;
			}

			// 3) Комментарий внутри круглых скобок: '#' до конца строки или до закрывающей ')',
			//    НО только если мы не находимся внутри строковых кавычек '...' или "..."
			//    и НЕ внутри квадратных скобок [...].
			//    и НЕ внутри SQL блоков (sqlBraceDepth > 0).
			//    и НЕ после '^' (это escape-последовательность ^#XX).
			//    Комментарий в начале строки уже обработан выше.
			//    Проверяем parenDepth > 0 — это означает что мы внутри () в ТЕКУЩЕМ контексте
			if (c == '#' && parenDepth > 0 && bracketDepth == 0 && sqlBraceDepth == 0) {
				if (DEBUG) {
					System.out.println(String.format("[lexComments] pos=%d char='#' parenDepth=%d bracketDepth=%d braceDepth=%d stack=%s inQuote=%s/%s",
							i, parenDepth, bracketDepth, braceDepth, braceParenStack, inSingleQuote, inDoubleQuote));
				}
				// ^# — это escape-последовательность, не комментарий
				if (i > 0 && text.charAt(i - 1) == '^') {
					i++;
					continue;
				}
				// Находим соответствующую открывающую '(' для текущей позиции.
				// Учитываем фигурные скобки — скобки внутри {...} не считаем.
				// Также учитываем кавычки — скобки внутри '...' и "..." не считаем.
				int openParenOffset = -1;
				int depthBack = 0;
				int braceDepthBack = 0;
				// Для кавычек идём вперёд от начала, чтобы правильно определить состояние
				// Но это дорого, поэтому просто игнорируем скобки после кавычек
				// Лучший подход: идём назад и отслеживаем кавычки в обратном порядке
				boolean inSingleBack = false;
				boolean inDoubleBack = false;
				for (int k = i - 1; k >= 0; k--) {
					char pk = text.charAt(k);
					// Отслеживаем фигурные скобки (в обратном порядке: } открывает, { закрывает)
					if (pk == '}') {
						braceDepthBack++;
						continue;
					}
					if (pk == '{' && braceDepthBack > 0) {
						braceDepthBack--;
						continue;
					}
					// Внутри фигурных скобок пропускаем всё
					if (braceDepthBack > 0) {
						continue;
					}
					// Отслеживаем кавычки (в обратном порядке)
					if (pk == '\'' && !Parser3TextUtils.isEscaped(text, k)) {
						inSingleBack = !inSingleBack;
						continue;
					}
					if (pk == '"' && !Parser3TextUtils.isEscaped(text, k)) {
						if (k > 0 && text.charAt(k - 1) == '^') {
							continue; // ^" не считается кавычкой
						}
						inDoubleBack = !inDoubleBack;
						continue;
					}
					// Внутри кавычек пропускаем скобки
					if (inSingleBack || inDoubleBack) {
						continue;
					}
					if (pk == ')') {
						depthBack++;
					} else if (pk == '(') {
						if (depthBack == 0) {
							openParenOffset = k;
							break;
						}
						depthBack--;
					}
				}
				boolean insideString = false;
				if (openParenOffset >= 0 && openParenOffset + 1 < i) {
					boolean inSingle = false;
					boolean inDouble = false;
					int braceDepthLocal = 0;
					for (int k = openParenOffset + 1; k < i; k++) {
						char ch = text.charAt(k);
						// Отслеживаем фигурные скобки — внутри них кавычки не считаем
						if (ch == '{') {
							braceDepthLocal++;
							continue;
						}
						if (ch == '}' && braceDepthLocal > 0) {
							braceDepthLocal--;
							continue;
						}
						// Внутри фигурных скобок пропускаем всё
						if (braceDepthLocal > 0) {
							continue;
						}
						if (ch == '\'' && !Parser3TextUtils.isEscaped(text, k)) {
							inSingle = !inSingle;
							continue;
						}
						if (ch == '"' && !Parser3TextUtils.isEscaped(text, k)) {
							// ^" не считается открывающей/закрывающей кавычкой Parser3.
							if (k > 0 && text.charAt(k - 1) == '^') {
								continue;
							}
							inDouble = !inDouble;
						}
					}
					insideString = inSingle || inDouble;
				}
				if (insideString) {
					// Внутри кавычек '#' не открывает комментарий.
					i++;
					continue;
				}
				int start = i;
				int j = i + 1;
				while (j < length) {
					char ch = text.charAt(j);
					if (ch == '\n' || ch == '\r') {
						break;
					}
					if (ch == ')' && !Parser3TextUtils.isEscaped(text, j)) {
						break;
					}
					j++;
				}
				int end = j;
				String debug = substringSafely(text, start, end);
				result.add(new Parser3LexerCore.CoreToken(start, end, "LINE_COMMENT", debug));
				i = end;
				continue;
			}

			i++;
		}

		return result;
	}


	private static boolean isRemOpen(@NotNull CharSequence text, int offset) {
		int length = text.length();
		if (offset + 5 > length) {
			return false;
		}
		return text.charAt(offset) == '^'
				&& text.charAt(offset + 1) == 'r'
				&& text.charAt(offset + 2) == 'e'
				&& text.charAt(offset + 3) == 'm'
				&& text.charAt(offset + 4) == '{';
	}

	/**
	 * Ищет конец блочного комментария ^rem{ ... }.
	 * Внутри учитываем вложенные фигурные скобки { }.
	 * Если закрывающая скобка не найдена — комментарий идёт до конца текста.
	 */

	/**
	 * Лексинг блочного комментария ^rem{ ... } с REM_* токенами для скобок.
	 * Возвращает позицию сразу после закрывающей '}' или конец текста.
	 */
	private static int lexRemBlock(@NotNull CharSequence text,
								   int remStartOffset,
								   @NotNull List<Parser3LexerCore.CoreToken> result) {
		int length = text.length();

		// remStartOffset указывает на '^', '{' на позиции remStartOffset + 4
		int braceOffset = remStartOffset + 4;
		if (braceOffset >= length || text.charAt(braceOffset) != '{') {
			// Теоретически не должно произойти, но на всякий случай — до конца текста
			int end = length;
			String debug = substringSafely(text, remStartOffset, end);
			result.add(new Parser3LexerCore.CoreToken(remStartOffset, end, "BLOCK_COMMENT", debug));
			return end;
		}

		int depth = 1;

		// Сначала добавляем префикс "^rem" как комментарий
		if (braceOffset > remStartOffset) {
			addCommentChunk(result, text, remStartOffset, braceOffset);
		}

		// Первая '{' как REM_LBRACE
		result.add(new Parser3LexerCore.CoreToken(
				braceOffset,
				braceOffset + 1,
				"REM_LBRACE",
				substringSafely(text, braceOffset, braceOffset + 1)
		));

		int i = braceOffset + 1;
		int chunkStart = i;

		while (i < length) {
			char c = text.charAt(i);

			// Экранирование скобок внутри ^rem: ^{}, ^[], ^()
			if (c == '^' && i + 1 < length) {
				char next = text.charAt(i + 1);
				if (next == '{' || next == '}' ||
						next == '[' || next == ']' ||
						next == '(' || next == ')') {
					// Оба символа остаются частью BLOCK_COMMENT
					// Не создаём REM_* и не изменяем depth
					i += 2;
					continue;
				}
			}

			switch (c) {
				case '{': {
					addCommentChunk(result, text, chunkStart, i);
					result.add(new Parser3LexerCore.CoreToken(
							i,
							i + 1,
							"REM_LBRACE",
							substringSafely(text, i, i + 1)
					));
					depth++;
					chunkStart = i + 1;
					i++;
					break;
				}
				case '}': {
					addCommentChunk(result, text, chunkStart, i);
					result.add(new Parser3LexerCore.CoreToken(
							i,
							i + 1,
							"REM_RBRACE",
							substringSafely(text, i, i + 1)
					));
					depth--;
					i++;
					if (depth == 0) {
						// ^rem{...} закончился сразу после текущей '}'
						return i;
					}
					chunkStart = i;
					break;
				}
				case '[': {
					addCommentChunk(result, text, chunkStart, i);
					result.add(new Parser3LexerCore.CoreToken(
							i,
							i + 1,
							"REM_LBRACKET",
							substringSafely(text, i, i + 1)
					));
					chunkStart = i + 1;
					i++;
					break;
				}
				case ']': {
					addCommentChunk(result, text, chunkStart, i);
					result.add(new Parser3LexerCore.CoreToken(
							i,
							i + 1,
							"REM_RBRACKET",
							substringSafely(text, i, i + 1)
					));
					chunkStart = i + 1;
					i++;
					break;
				}
				case '(': {
					addCommentChunk(result, text, chunkStart, i);
					result.add(new Parser3LexerCore.CoreToken(
							i,
							i + 1,
							"REM_LPAREN",
							substringSafely(text, i, i + 1)
					));
					chunkStart = i + 1;
					i++;
					break;
				}
				case ')': {
					addCommentChunk(result, text, chunkStart, i);
					result.add(new Parser3LexerCore.CoreToken(
							i,
							i + 1,
							"REM_RPAREN",
							substringSafely(text, i, i + 1)
					));
					chunkStart = i + 1;
					i++;
					break;
				}
				default: {
					i++;
					break;
				}
			}
		}

		// Не нашли закрывающую '}' — всё до конца текста считаем комментарием
		addCommentChunk(result, text, chunkStart, length);
		return length;
	}

	/**
	 * Добавляет содержимое комментария, разбивая на WHITE_SPACE и BLOCK_COMMENT токены.
	 * КАЖДЫЙ символ whitespace — отдельный токен для корректной работы форматтера.
	 */
	private static void addCommentChunk(@NotNull List<Parser3LexerCore.CoreToken> result,
										@NotNull CharSequence text,
										int start,
										int end) {
		if (end <= start) {
			return;
		}

		int i = start;
		while (i < end) {
			char c = text.charAt(i);

			// Whitespace: каждый символ отдельным токеном
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				String debug = String.valueOf(c);
				result.add(new Parser3LexerCore.CoreToken(i, i + 1, "WHITE_SPACE", debug));
				i++;
			} else {
				// Текст комментария до следующего whitespace или конца
				int textStart = i;
				while (i < end) {
					char ch = text.charAt(i);
					if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
						break;
					}
					i++;
				}
				String debug = substringSafely(text, textStart, i);
				result.add(new Parser3LexerCore.CoreToken(textStart, i, "BLOCK_COMMENT", debug));
			}
		}
	}
}