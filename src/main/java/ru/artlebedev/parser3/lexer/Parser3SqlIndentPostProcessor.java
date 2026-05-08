package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * Постпроцессор для выкусывания отступов в SQL-блоках.
 *
 * Работает после того как все токены (включая LBRACE/RBRACE) уже размечены.
 *
 * Логика:
 * 1. Находим каждый sql{...} блок отдельно
 * 2. Вычисляем базовый globalMinIndent для этого блока
 * 3. Проходим по токенам и отслеживаем LBRACE/RBRACE
 * 4. Когда встречаем LBRACE и её RBRACE не на той же строке → увеличиваем порог на +1
 * 5. Когда встречаем RBRACE → уменьшаем порог на -1
 * 6. Для каждой строки откусываем currentIndent символов как WHITE_SPACE
 *
 * ОПТИМИЗАЦИЯ v2: Предвычисляем номера строк и currentIndent за O(n),
 * вместо O(n²) прохода для каждого токена.
 */
public class Parser3SqlIndentPostProcessor {

	/**
	 * Обрабатывает список токенов, выкусывая отступы в SQL_BLOCK.
	 */
	@NotNull
	public static List<Parser3LexerCore.CoreToken> process(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> tokens
	) {
		// Находим границы каждого sql{} блока
		List<int[]> sqlBlockBounds = findSqlBlockBounds(text, tokens);

		if (sqlBlockBounds.isEmpty()) {
			return tokens;
		}

		// Предвычисляем номера строк для всего текста — O(n)
		int[] lineNumbers = buildLineNumberArray(text);

		// Предвычисляем currentIndent для каждого токена внутри sql-блоков — O(n)
		int[] tokenIndents = computeAllIndents(text, tokens, sqlBlockBounds, lineNumbers);

		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);

			// Проверяем в каком sql{} блоке находится этот токен
			int[] bounds = findContainingBlock(token.start, token.end, sqlBlockBounds);

			if (bounds == null || !"SQL_BLOCK".equals(token.type)) {
				// Токен не в sql{} блоке или не SQL_BLOCK — без изменений
				result.add(token);
				continue;
			}

			// Берём предвычисленный currentIndent
			int currentIndent = tokenIndents[i];

			// Разбиваем и выкусываем отступы
			List<Parser3LexerCore.CoreToken> split = splitAndTrimSqlToken(text, token, currentIndent);
			result.addAll(split);
		}

		return result;
	}

	/**
	 * Строит массив номеров строк: lineNumbers[pos] = номер строки для позиции pos.
	 * Однократный проход O(n).
	 */
	private static int[] buildLineNumberArray(@NotNull CharSequence text) {
		int length = text.length();
		int[] lineNumbers = new int[length + 1];
		int line = 0;
		for (int i = 0; i < length; i++) {
			lineNumbers[i] = line;
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
		lineNumbers[length] = line;
		return lineNumbers;
	}

	/**
	 * Вычисляет currentIndent для всех токенов за O(n).
	 * Для токенов вне sql-блоков значение будет 0 (не используется).
	 */
	private static int[] computeAllIndents(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull List<int[]> sqlBlockBounds,
			@NotNull int[] lineNumbers
	) {
		int[] indents = new int[tokens.size()];

		// Для каждого sql-блока вычисляем globalMinIndent и проходим один раз
		for (int[] bounds : sqlBlockBounds) {
			int globalMinIndent = computeGlobalMinIndent(text, bounds[0], bounds[1]);
			int currentIndent = globalMinIndent;

			// Проходим по всем токенам один раз, отслеживая currentIndent
			for (int i = 0; i < tokens.size(); i++) {
				Parser3LexerCore.CoreToken t = tokens.get(i);

				// Токен вне этого блока — пропускаем
				if (t.start < bounds[0] || t.end > bounds[1]) {
					continue;
				}

				// Сначала сохраняем текущий indent для SQL_BLOCK токенов
				if ("SQL_BLOCK".equals(t.type)) {
					indents[i] = currentIndent;
				}

				// Затем обновляем indent на основе скобок
				if ("LBRACE".equals(t.type)) {
					if (isMultilineBraceFast(tokens, i, lineNumbers)) {
						currentIndent++;
					}
				} else if ("RBRACE".equals(t.type)) {
					if (currentIndent > globalMinIndent) {
						currentIndent--;
					}
				}
			}
		}

		return indents;
	}

	/**
	 * Быстрая проверка многострочности скобки с использованием предвычисленных номеров строк.
	 */
	private static boolean isMultilineBraceFast(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			int lbraceIndex,
			@NotNull int[] lineNumbers
	) {
		Parser3LexerCore.CoreToken lbrace = tokens.get(lbraceIndex);
		int lbraceLine = lineNumbers[Math.min(lbrace.start, lineNumbers.length - 1)];

		// Ищем парную RBRACE
		int depth = 1;
		for (int i = lbraceIndex + 1; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken t = tokens.get(i);
			if ("LBRACE".equals(t.type)) {
				depth++;
			} else if ("RBRACE".equals(t.type)) {
				depth--;
				if (depth == 0) {
					int rbraceLine = lineNumbers[Math.min(t.start, lineNumbers.length - 1)];
					return rbraceLine != lbraceLine;
				}
			}
		}

		// Не нашли парную скобку — считаем многострочной
		return true;
	}

	/**
	 * Находит границы всех sql{} блоков по токенам.
	 * Ищет паттерн (METHOD|CONSTRUCTOR) "sql" -> LBRACE и парную RBRACE.
	 * Также поддерживает пользовательские SQL-инъекции из настроек.
	 * Возвращает список [contentStart, contentEnd] — позиции содержимого (без скобок).
	 */
	private static List<int[]> findSqlBlockBounds(@NotNull CharSequence text, @NotNull List<Parser3LexerCore.CoreToken> tokens) {
		List<int[]> bounds = new ArrayList<>();

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);

			// Ищем METHOD "sql" или CONSTRUCTOR "sql"
			boolean isSqlToken = ("METHOD".equals(token.type) || "CONSTRUCTOR".equals(token.type))
					&& "sql".equals(token.debugText);

			// Также проверяем пользовательские SQL-инъекции
			// Ищем LBRACE после токена и проверяем что перед { стоит пользовательский префикс
			if (!isSqlToken) {
				// Ищем LBRACE после текущего токена
				int lbraceIndex = -1;
				for (int j = i + 1; j < tokens.size() && j < i + 5; j++) {
					if ("LBRACE".equals(tokens.get(j).type)) {
						lbraceIndex = j;
						break;
					}
				}

				if (lbraceIndex >= 0) {
					Parser3LexerCore.CoreToken lbrace = tokens.get(lbraceIndex);
					// Проверяем пользовательские префиксы — ищем ^ перед текущей позицией
					// и проверяем что от ^ до { совпадает с одним из префиксов
					isSqlToken = matchUserSqlInjectionByLbrace(text, lbrace.start);
				}
			}

			if (isSqlToken) {
				// Ищем LBRACE после него
				int lbraceIndex = -1;
				for (int j = i + 1; j < tokens.size() && j < i + 5; j++) {
					if ("LBRACE".equals(tokens.get(j).type)) {
						lbraceIndex = j;
						break;
					}
				}

				if (lbraceIndex >= 0) {
					Parser3LexerCore.CoreToken lbrace = tokens.get(lbraceIndex);

					// Ищем парную RBRACE
					int rbraceIndex = findMatchingRbrace(tokens, lbraceIndex);
					if (rbraceIndex > lbraceIndex) {
						Parser3LexerCore.CoreToken rbrace = tokens.get(rbraceIndex);
						// Границы содержимого: после { и до }
						bounds.add(new int[]{lbrace.end, rbrace.start});
					}
				}
			}
		}

		return bounds;
	}

	/**
	 * Проверяет, есть ли пользовательский SQL-префикс перед позицией lbracePos.
	 * Ищет ^ перед lbracePos и проверяет совпадение с пользовательскими префиксами.
	 * Использует ту же логику что и Parser3SqlBlockLexerCore.matchUserSqlInjection().
	 */
	private static boolean matchUserSqlInjectionByLbrace(@NotNull CharSequence text, int lbracePos) {
		List<String> prefixes = Parser3LexerCore.getUserSqlInjectionPrefixes();
		if (prefixes.isEmpty()) {
			return false;
		}

		for (String prefix : prefixes) {
			if (prefix == null || prefix.trim().isEmpty()) {
				continue;
			}
			String p = prefix.trim();
			int pLen = p.length();

			// Проверяем что перед { стоит именно этот префикс
			int startPos = lbracePos - pLen;
			if (startPos < 0) {
				continue;
			}

			// Сравниваем текст
			boolean matches = true;
			for (int j = 0; j < pLen; j++) {
				if (text.charAt(startPos + j) != p.charAt(j)) {
					matches = false;
					break;
				}
			}

			if (matches) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Находит индекс парной RBRACE.
	 */
	private static int findMatchingRbrace(@NotNull List<Parser3LexerCore.CoreToken> tokens, int lbraceIndex) {
		int depth = 1;
		for (int i = lbraceIndex + 1; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken t = tokens.get(i);
			if ("LBRACE".equals(t.type)) {
				depth++;
			} else if ("RBRACE".equals(t.type)) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Находит самый вложенный блок, содержащий данный диапазон.
	 * Если токен находится в нескольких вложенных блоках, возвращает самый маленький (внутренний).
	 */
	private static int[] findContainingBlock(int start, int end, @NotNull List<int[]> bounds) {
		int[] bestMatch = null;
		int bestSize = Integer.MAX_VALUE;

		for (int[] b : bounds) {
			if (start >= b[0] && end <= b[1]) {
				int size = b[1] - b[0];
				if (size < bestSize) {
					bestSize = size;
					bestMatch = b;
				}
			}
		}
		return bestMatch;
	}

	/**
	 * Разбивает SQL_BLOCK токен по строкам и выкусывает отступы.
	 */
	private static List<Parser3LexerCore.CoreToken> splitAndTrimSqlToken(
			@NotNull CharSequence text,
			@NotNull Parser3LexerCore.CoreToken token,
			int currentIndent
	) {
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();

		int start = token.start;
		int end = token.end;

		if (start >= end) {
			return result;
		}

		int i = start;

		// Проверяем: токен начинается в начале строки?
		boolean isAtLineStart = (start == 0) || (start > 0 && text.charAt(start - 1) == '\n');

		// Если в начале строки и начинается с whitespace — обрабатываем отступ
		if (isAtLineStart) {
			char c = text.charAt(i);
			if (c == ' ' || c == '\t') {
				i = processLineStart(text, i, end, currentIndent, result);
			}
		}

		// Обрабатываем остальное
		while (i < end) {
			char c = text.charAt(i);

			// Перевод строки — отдельный WHITE_SPACE токен
			if (c == '\n') {
				result.add(new Parser3LexerCore.CoreToken(i, i + 1, "WHITE_SPACE", "\n"));
				i++;

				// После \n — обрабатываем отступ следующей строки
				if (i < end) {
					char nextC = text.charAt(i);
					if (nextC == ' ' || nextC == '\t') {
						i = processLineStart(text, i, end, currentIndent, result);
					}
				}
				continue;
			}

			// Обычный контент — ищем до следующего \n или конца
			int contentStart = i;
			while (i < end && text.charAt(i) != '\n') {
				i++;
			}

			if (contentStart < i) {
				String debug = substringSafely(text, contentStart, i);
				result.add(new Parser3LexerCore.CoreToken(contentStart, i, "SQL_BLOCK", debug));
			}
		}

		// Защита: если ничего не добавили, возвращаем исходный токен
		if (result.isEmpty()) {
			result.add(token);
		}

		return result;
	}

	/**
	 * Обрабатывает начало строки: откусывает currentIndent, остаток — SQL_BLOCK.
	 * Возвращает позицию после обработанных отступов.
	 */
	private static int processLineStart(
			@NotNull CharSequence text,
			int start,
			int end,
			int currentIndent,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		// Считаем whitespace
		int wsCount = 0;
		int i = start;
		while (i < end) {
			char c = text.charAt(i);
			if (c == ' ' || c == '\t') {
				wsCount++;
				i++;
			} else {
				break;
			}
		}

		if (wsCount == 0) {
			return start;
		}

		// Откусываем currentIndent
		int trimmed = Math.min(wsCount, currentIndent);

		if (trimmed > 0) {
			String wsDebug = substringSafely(text, start, start + trimmed);
			result.add(new Parser3LexerCore.CoreToken(start, start + trimmed, "WHITE_SPACE", wsDebug));
		}

		// Оставшийся отступ (больше currentIndent) — SQL_BLOCK
		int extra = wsCount - trimmed;
		if (extra > 0) {
			int extraStart = start + trimmed;
			int extraEnd = start + wsCount;
			String extraDebug = substringSafely(text, extraStart, extraEnd);
			result.add(new Parser3LexerCore.CoreToken(extraStart, extraEnd, "SQL_BLOCK", extraDebug));
		}

		return start + wsCount;
	}

	/**
	 * Вычисляет минимальный отступ среди непустых строк в диапазоне.
	 */
	private static int computeGlobalMinIndent(@NotNull CharSequence text, int start, int end) {
		int minIndent = Integer.MAX_VALUE;
		int i = start;

		// Находим начало первой полной строки
		if (i > 0 && text.charAt(i - 1) != '\n') {
			// Мы не в начале строки — ищем следующую строку
			while (i < end && text.charAt(i) != '\n') {
				i++;
			}
			if (i < end) {
				i++; // Пропускаем \n
			}
		}

		while (i < end) {
			int lineStart = i;

			// Находим конец строки
			while (i < end && text.charAt(i) != '\n') {
				i++;
			}
			int lineEnd = i;

			// Считаем отступ
			int ws = 0;
			while (lineStart + ws < lineEnd) {
				char c = text.charAt(lineStart + ws);
				if (c == ' ' || c == '\t') {
					ws++;
				} else {
					break;
				}
			}

			// Если строка не пустая и это не Parser3-комментарий внутри SQL
			if (ws < (lineEnd - lineStart) && !isSqlHashCommentLine(text, lineStart + ws, lineEnd)) {
				if (ws < minIndent) {
					minIndent = ws;
				}
			}

			// Пропускаем \n
			if (i < end && text.charAt(i) == '\n') {
				i++;
			}
		}

		return minIndent == Integer.MAX_VALUE ? 0 : minIndent;
	}

	private static boolean isSqlHashCommentLine(@NotNull CharSequence text, int firstNonWs, int lineEnd) {
		return firstNonWs < lineEnd && text.charAt(firstNonWs) == '#';
	}
}
