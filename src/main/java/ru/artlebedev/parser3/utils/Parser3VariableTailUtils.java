package ru.artlebedev.parser3.utils;

import org.jetbrains.annotations.NotNull;

/**
 * Общие правила для хвоста переменной в completion и индексировании цепочек.
 *
 * Под "хвостом" понимается часть после курсора или после уже распознанной цепочки:
 * - для InsertHandler это остаток старого имени, который нужно удалить;
 * - для парсера это символы, которыми считается завершённым чтение $var.a.b.
 */
public final class Parser3VariableTailUtils {

	private Parser3VariableTailUtils() {
	}

	public static boolean isVariableIdentifierChar(char ch) {
		return Parser3IdentifierUtils.isVariableIdentifierChar(ch);
	}

	public static int skipIdentifierChars(@NotNull CharSequence text, int from, int limit) {
		int i = from;
		while (i < limit && isVariableIdentifierChar(text.charAt(i))) {
			i++;
		}
		return i;
	}

	public static boolean isTailStopChar(char ch) {
		return !isVariableIdentifierChar(ch);
	}

	public static boolean hasCompletedReadChainTerminator(
			@NotNull CharSequence text,
			int from,
			int limit,
			int dotChainSize
	) {
		int pos = from;
		while (pos < limit && Character.isWhitespace(text.charAt(pos))) {
			pos++;
		}

		if (pos >= limit) {
			// Без явного завершающего символа цепочку не фиксируем:
			// иначе недописанный хвост на последней строке попадает в completion сам на себя.
			return false;
		}

		return isTailStopChar(text.charAt(pos));
	}
}
