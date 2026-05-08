package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Общие утилиты для лексера Parser3.
 * Содержит методы для работы с экранированием, поиском парных скобок и т.д.
 */
public final class Parser3LexerUtils {

	private Parser3LexerUtils() {
	}

	/**
	 * Информация о директиве: ^name(...){}(...){}...
	 */
	public static class DirectiveInfo {
		/** Позиция после последнего } директивы */
		public final int endPos;
		/** Есть ли тело {} у директивы */
		public final boolean hasBody;
		/** Диапазоны содержимого тел [start, end] */
		public final List<int[]> bodyRanges;
		/** Диапазоны условий (...) [start, end] — включая скобки */
		public final List<int[]> conditionRanges;

		public DirectiveInfo(int endPos, boolean hasBody, List<int[]> bodyRanges, List<int[]> conditionRanges) {
			this.endPos = endPos;
			this.hasBody = hasBody;
			this.bodyRanges = bodyRanges;
			this.conditionRanges = conditionRanges;
		}
	}

	/**
	 * Проверяет, экранирован ли символ на позиции pos символом ^.
	 * Экранирование: нечётное количество ^ перед символом.
	 * Применимо для: ' " ( ) [ ] { } $
	 */
	public static boolean isEscapedByCaret(@NotNull CharSequence text, int pos) {
		if (pos <= 0) {
			return false;
		}
		int count = 0;
		int i = pos - 1;
		while (i >= 0 && text.charAt(i) == '^') {
			count++;
			i--;
		}
		return (count % 2) != 0;
	}

	/**
	 * Находит парную закрывающую скобку, учитывая вложенность, строки и экранирование.
	 *
	 * @param text исходный текст
	 * @param openPos позиция открывающей скобки
	 * @param limit граница поиска
	 * @param openCh открывающий символ (например '(')
	 * @param closeCh закрывающий символ (например ')')
	 * @return позиция закрывающей скобки или -1 если не найдена
	 */
	public static int findMatchingBracket(
			@NotNull CharSequence text,
			int openPos,
			int limit,
			char openCh,
			char closeCh
	) {
		int length = text.length();
		if (openPos < 0 || openPos >= length) {
			return -1;
		}
		int depth = 0;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;

		for (int i = openPos; i < limit && i < length; i++) {
			char c = text.charAt(i);

			// Проверяем экранирование: ^' ^" ^( ^) и т.д.
			if (isEscapedByCaret(text, i)) {
				continue;
			}

			// Отслеживаем кавычки
			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				continue;
			}
			if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
				continue;
			}

			// Внутри строки скобки не считаем
			if (inSingleQuote || inDoubleQuote) {
				continue;
			}

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

	/**
	 * Находит парную закрывающую круглую скобку.
	 */
	public static int findMatchingParen(@NotNull CharSequence text, int openPos, int limit) {
		return findMatchingBracket(text, openPos, limit, '(', ')');
	}

	/**
	 * Находит парную закрывающую фигурную скобку.
	 */
	public static int findMatchingBrace(@NotNull CharSequence text, int openPos, int limit) {
		return findMatchingBracket(text, openPos, limit, '{', '}');
	}

	/**
	 * Находит парную закрывающую квадратную скобку.
	 */
	public static int findMatchingSquareBracket(@NotNull CharSequence text, int openPos, int limit) {
		return findMatchingBracket(text, openPos, limit, '[', ']');
	}

	/**
	 * Находит информацию о директиве ^name(...){}(...){}...
	 * Это ЕДИНАЯ функция для всех лексеров.
	 *
	 * Обрабатывает:
	 * - ^if($a){...}
	 * - ^if($a){...}($b){...}
	 * - ^if($a){...}{...}
	 * - ^for[...](...){}
	 * - ^switch[...]{^case[...]{...}}
	 *
	 * @param text исходный текст
	 * @param caretPos позиция ^ (начало директивы)
	 * @param limit граница поиска
	 * @return информация о директиве или null если это не директива с телом
	 */
	@Nullable
	public static DirectiveInfo findDirectiveInfo(@NotNull CharSequence text, int caretPos, int limit) {
		int length = text.length();
		int i = caretPos + 1;
		if (i >= limit || i >= length) {
			return null;
		}

		// Пропускаем имя директивы: буквы, цифры, _, :, .
		char firstChar = text.charAt(i);
		if (!Character.isLetter(firstChar) && firstChar != '_') {
			return null;
		}

		while (i < limit && i < length) {
			char c = text.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '.') {
				i++;
			} else {
				break;
			}
		}

		if (i == caretPos + 1) {
			return null; // Нет имени
		}

		// Пропускаем возможные () и []
		while (i < limit && i < length) {
			char c = text.charAt(i);
			if (c == '(') {
				int close = findMatchingBracket(text, i, limit, '(', ')');
				if (close < 0) {
					return null;
				}
				i = close + 1;
			} else if (c == '[') {
				int close = findMatchingBracket(text, i, limit, '[', ']');
				if (close < 0) {
					return null;
				}
				i = close + 1;
			} else if (Character.isWhitespace(c)) {
				i++;
			} else {
				break;
			}
		}

		// Ожидаем {
		if (i >= limit || i >= length || text.charAt(i) != '{') {
			return null;
		}
		if (isEscapedByCaret(text, i)) {
			return null;
		}

		// Найдено { — это директива с телом
		List<int[]> bodyRanges = new ArrayList<>();
		List<int[]> conditionRanges = new ArrayList<>();

		while (i < limit && i < length && text.charAt(i) == '{' && !isEscapedByCaret(text, i)) {
			int openBrace = i;
			int close = findMatchingBracket(text, i, limit, '{', '}');
			if (close < 0) {
				// Нет закрывающей } — возвращаем что есть
				return new DirectiveInfo(limit, true, bodyRanges, conditionRanges);
			}

			// Содержимое тела: от openBrace+1 до close
			if (openBrace + 1 < close) {
				bodyRanges.add(new int[]{openBrace + 1, close});
			}

			i = close + 1;

			// Запоминаем позицию после }
			int posAfterBrace = i;

			// После } может быть (...){}  — проверяем есть ли продолжение (elseif/else)
			while (i < limit && i < length) {
				char c = text.charAt(i);
				if (c == '(') {
					// elseif: }($condition){...}
					int closeParen = findMatchingBracket(text, i, limit, '(', ')');
					if (closeParen < 0) {
						// Нет закрывающей ) — конец директивы
						return new DirectiveInfo(posAfterBrace, true, bodyRanges, conditionRanges);
					}
					// Сохраняем диапазон условия (включая скобки)
					conditionRanges.add(new int[]{i, closeParen + 1});
					i = closeParen + 1;
				} else if (Character.isWhitespace(c)) {
					i++;
				} else if (c == '{' && !isEscapedByCaret(text, i)) {
					// Есть следующая ветка — продолжаем внешний while
					break;
				} else {
					// Нет продолжения — возвращаем позицию сразу после }
					return new DirectiveInfo(posAfterBrace, true, bodyRanges, conditionRanges);
				}
			}

			// Если дошли до конца — нет продолжения
			if (i >= limit || i >= length) {
				return new DirectiveInfo(posAfterBrace, true, bodyRanges, conditionRanges);
			}
		}

		return new DirectiveInfo(i, true, bodyRanges, conditionRanges);
	}

	/**
	 * Безопасное извлечение подстроки (без исключений при выходе за границы).
	 */
	public static String substringSafely(@NotNull CharSequence text, int start, int end) {
		int len = text.length();
		if (start < 0) start = 0;
		if (end > len) end = len;
		if (start >= end) return "";
		return text.subSequence(start, end).toString();
	}

	/**
	 * Результат анализа баланса скобок в тексте.
	 */
	public static class BracketBalance {
		public final int parenDelta;    // баланс ()
		public final int bracketDelta;  // баланс []
		public final int braceDelta;    // баланс {}
		public final int leadingCloses; // кол-во ведущих закрывающих скобок в начале строки

		public BracketBalance(int parenDelta, int bracketDelta, int braceDelta, int leadingCloses) {
			this.parenDelta = parenDelta;
			this.bracketDelta = bracketDelta;
			this.braceDelta = braceDelta;
			this.leadingCloses = leadingCloses;
		}

		public int totalDelta() {
			return parenDelta + bracketDelta + braceDelta;
		}
	}

	/**
	 * Подсчитывает баланс скобок в тексте с учётом:
	 * - Экранирования ^( ^) ^{ ^} и т.д.
	 * - Строковых литералов '...' и "..."
	 * - SQL-блоков (после :sql{ или ::sql{ скобки внутри {...} не считаются)
	 * - Комментариев # внутри выражений (после # до конца строки игнорируется)
	 *
	 * @param text анализируемый текст (обычно одна строка)
	 * @return баланс скобок
	 */
	public static BracketBalance countBracketBalance(@NotNull CharSequence text) {
		return countBracketBalance(text, 0);
	}

	/**
	 * Подсчитывает баланс скобок в тексте с учётом:
	 * - Экранирования ^( ^) ^{ ^} и т.д.
	 * - Строковых литералов '...' и "..."
	 * - SQL-блоков (после :sql{ или ::sql{ скобки внутри {...} не считаются)
	 * - Комментариев # внутри выражений (после # до конца строки игнорируется)
	 *
	 * @param text анализируемый текст (обычно одна строка)
	 * @param initialParenDepth начальная глубина вложенности () (для многострочных выражений)
	 * @return баланс скобок
	 */
	public static BracketBalance countBracketBalance(@NotNull CharSequence text, int initialParenDepth) {
		int length = text.length();
		int parenDelta = 0;
		int bracketDelta = 0;
		int braceDelta = 0;
		int leadingCloses = 0;
		boolean inLeading = true;  // ещё ищем ведущие закрывающие скобки

		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		int sqlBraceDepth = 0;  // глубина вложенности в sql{...} блоках
		int parenDepth = initialParenDepth;  // глубина () для определения комментариев внутри выражений

		String textStr = text.toString().replace("\n", "\\n").replace("\t", "\\t");
		System.out.println("[BracketBalance] text='" + textStr + "', initialParenDepth=" + initialParenDepth);

		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);

			// Пробелы в начале — продолжаем искать ведущие скобки
			if (inLeading && (c == ' ' || c == '\t')) {
				continue;
			}

			// Проверяем экранирование
			if (isEscapedByCaret(text, i)) {
				if (inLeading && c != ' ' && c != '\t') {
					inLeading = false;
				}
				continue;
			}

			// Комментарий # внутри выражения () — игнорируем до конца строки
			// Согласно документации: "Внутри круглых скобок (в выражениях) можно использовать # для комментариев"
			if (c == '#' && parenDepth > 0 && !inSingleQuote && !inDoubleQuote && sqlBraceDepth == 0) {
				// Ищем конец строки
				while (i < length && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
					i++;
				}
				continue;
			}

			// Внутри SQL-блока — ищем только закрывающую }
			if (sqlBraceDepth > 0) {
				if (c == '{') {
					sqlBraceDepth++;
				} else if (c == '}') {
					sqlBraceDepth--;
					if (sqlBraceDepth == 0) {
						// Выходим из SQL-блока, эта } учитывается как обычная
						braceDelta--;
						if (inLeading) {
							leadingCloses++;
						}
					}
				}
				// Внутри SQL скобки () и [] не считаем
				continue;
			}

			// Проверяем начало SQL-блока: :sql{ или ::sql{
			if (c == '{' && i >= 4) {
				// Проверяем :sql{ или ::sql{
				String before = text.subSequence(Math.max(0, i - 5), i).toString();
				if (before.endsWith(":sql") || before.endsWith("::sql")) {
					sqlBraceDepth = 1;
					braceDelta++;
					inLeading = false;
					continue;
				}
			}

			// Отслеживаем кавычки (вне SQL)
			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				if (inLeading) inLeading = false;
				continue;
			}
			if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
				if (inLeading) inLeading = false;
				continue;
			}

			// Внутри строки скобки не считаем
			if (inSingleQuote || inDoubleQuote) {
				continue;
			}

			// Считаем скобки
			if (c == '(') {
				parenDelta++;
				parenDepth++;
				inLeading = false;
			} else if (c == ')') {
				parenDelta--;
				parenDepth--;
				if (parenDepth < 0) parenDepth = 0;
				if (inLeading) {
					leadingCloses++;
				} else {
					inLeading = false;
				}
			} else if (c == '[') {
				bracketDelta++;
				inLeading = false;
			} else if (c == ']') {
				bracketDelta--;
				if (inLeading) {
					leadingCloses++;
				} else {
					inLeading = false;
				}
			} else if (c == '{') {
				braceDelta++;
				inLeading = false;
			} else if (c == '}') {
				braceDelta--;
				if (inLeading) {
					leadingCloses++;
				} else {
					inLeading = false;
				}
			} else if (c != ' ' && c != '\t') {
				// Любой другой символ — конец ведущих скобок
				inLeading = false;
			}
		}

		System.out.println("[BracketBalance] RESULT: parenDelta=" + parenDelta + ", bracketDelta=" + bracketDelta +
				", braceDelta=" + braceDelta + ", leadingCloses=" + leadingCloses);
		return new BracketBalance(parenDelta, bracketDelta, braceDelta, leadingCloses);
	}
}