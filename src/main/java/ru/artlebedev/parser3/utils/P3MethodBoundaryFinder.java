package ru.artlebedev.parser3.utils;

import org.jetbrains.annotations.NotNull;

/**
 * Утилитарный класс для поиска границ методов в тексте Parser3.
 *
 * Используется в лексере и форматтере для оптимизации —
 * чтобы не обрабатывать весь файл, а только текущий метод.
 *
 * Метод в Parser3 начинается с @ в начале строки (после опциональных пробелов),
 * например: @methodName[] или @methodName[params]
 *
 * Исключения (не являются методами):
 * - @USE, @CLASS, @BASE — это директивы
 * - @ внутри ^rem{} — это закомментированный код
 */
public final class P3MethodBoundaryFinder {

	private P3MethodBoundaryFinder() {
	}

	/**
	 * Ищет позицию начала строки с ближайшим @method выше указанного offset.
	 * Возвращает 0 если не найдено.
	 *
	 * @param text полный текст файла
	 * @param offset позиция от которой искать вверх
	 * @return offset начала строки с @method, или 0
	 */
	public static int findMethodStartAbove(@NotNull CharSequence text, int offset) {
		// Сначала находим все открытые ^rem{ блоки чтобы знать какие @ игнорировать
		int pos = Math.min(offset, text.length());

		while (pos > 0) {
			// Ищем начало текущей строки
			int lineStart = findLineStart(text, pos - 1);
			int lineEnd = findLineEnd(text, lineStart);

			// Проверяем начинается ли строка с @ (пропуская пробелы)
			int firstNonSpace = skipWhitespace(text, lineStart, lineEnd);

			if (firstNonSpace < lineEnd && text.charAt(firstNonSpace) == '@') {
				// Проверяем что это не директива @USE, @CLASS, @BASE
				if (!isDirective(text, firstNonSpace, lineEnd)) {
					// Проверяем что @ не внутри ^rem{}
					if (!isInsideRemBlock(text, lineStart)) {
						return lineStart;
					}
				}
			}

			// Переходим к предыдущей строке
			pos = lineStart - 1;
		}

		return 0;
	}

	/**
	 * Ищет позицию начала строки с ближайшим @method ниже указанного offset.
	 * Возвращает length текста если не найдено.
	 *
	 * @param text полный текст файла
	 * @param offset позиция от которой искать вниз
	 * @return offset начала строки с @method, или length текста
	 */
	public static int findMethodStartBelow(@NotNull CharSequence text, int offset) {
		int pos = offset;
		int length = text.length();

		// Переходим к следующей строке
		pos = findLineEnd(text, pos);
		if (pos < length && text.charAt(pos) == '\n') {
			pos++;
		}

		while (pos < length) {
			int lineStart = pos;
			int lineEnd = findLineEnd(text, lineStart);

			// Проверяем начинается ли строка с @ (пропуская пробелы)
			int firstNonSpace = skipWhitespace(text, lineStart, lineEnd);

			if (firstNonSpace < lineEnd && text.charAt(firstNonSpace) == '@') {
				// Проверяем что это не директива @USE, @CLASS, @BASE
				if (!isDirective(text, firstNonSpace, lineEnd)) {
					// Проверяем что @ не внутри ^rem{}
					if (!isInsideRemBlock(text, lineStart)) {
						return lineStart;
					}
				}
			}

			// Переходим к следующей строке
			pos = lineEnd;
			if (pos < length && text.charAt(pos) == '\n') {
				pos++;
			}
		}

		return length;
	}

	/**
	 * Находит начало строки (после предыдущего \n или начало текста)
	 */
	private static int findLineStart(@NotNull CharSequence text, int pos) {
		while (pos > 0 && text.charAt(pos) != '\n') {
			pos--;
		}
		if (pos > 0 || (pos == 0 && text.length() > 0 && text.charAt(0) == '\n')) {
			pos++; // пропускаем сам \n
		}
		return pos;
	}

	/**
	 * Находит конец строки (позиция \n или конец текста)
	 */
	private static int findLineEnd(@NotNull CharSequence text, int pos) {
		int length = text.length();
		while (pos < length && text.charAt(pos) != '\n') {
			pos++;
		}
		return pos;
	}

	/**
	 * Пропускает пробелы и табы
	 */
	private static int skipWhitespace(@NotNull CharSequence text, int start, int end) {
		int pos = start;
		while (pos < end) {
			char c = text.charAt(pos);
			if (c != ' ' && c != '\t') {
				break;
			}
			pos++;
		}
		return pos;
	}

	/**
	 * Проверяет что это директива @USE, @CLASS или @BASE
	 */
	private static boolean isDirective(@NotNull CharSequence text, int atPos, int lineEnd) {
		// Собираем слово после @
		int start = atPos + 1;
		int end = start;
		while (end < lineEnd && Character.isLetter(text.charAt(end))) {
			end++;
		}

		if (end > start) {
			String word = text.subSequence(start, end).toString();
			return "USE".equals(word) || "CLASS".equals(word) || "BASE".equals(word);
		}

		return false;
	}

	/**
	 * Проверяет находится ли позиция внутри блока ^rem{}
	 *
	 * Простая эвристика: считаем количество ^rem{ и } выше позиции.
	 * Если ^rem{ больше чем закрывающих } — мы внутри rem.
	 */
	private static boolean isInsideRemBlock(@NotNull CharSequence text, int pos) {
		int remDepth = 0;
		int i = 0;

		while (i < pos) {
			// Ищем ^rem{
			if (i + 5 <= pos && text.charAt(i) == '^' &&
					text.charAt(i + 1) == 'r' &&
					text.charAt(i + 2) == 'e' &&
					text.charAt(i + 3) == 'm' &&
					text.charAt(i + 4) == '{') {
				remDepth++;
				i += 5;
				continue;
			}

			// Ищем закрывающую } (только если мы внутри rem)
			if (remDepth > 0 && text.charAt(i) == '}') {
				remDepth--;
				i++;
				continue;
			}

			i++;
		}

		return remDepth > 0;
	}
}