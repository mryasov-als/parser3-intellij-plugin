package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ru.artlebedev.parser3.lexer.Parser3LexerCore.findLineEnd;
import static ru.artlebedev.parser3.lexer.Parser3LexerCore.isAtLineStart;
import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * Разбор определений методов:
 *
 * Допустимые формы:
 *   @method[]
 *   @method[var1]
 *   @method[*var1]
 *   @method[var1;var2]
 *   @method[var1;var2][local_var1]
 *   @method[var1;var2][local_var1;local_var2]
 *   @static:имя[параметры]
 *
 * Требования:
 * - Определение метода строго на одной строке.
 * - Символ '@' — строго первый в строке (колонка 0).
 *
 * Типы:
 * - DEFINE_METHOD
 * - SPECIAL_METHOD
 * - VARIABLE
 * - LOCAL_VARIABLE
 *
 * SPECIAL_METHOD:
 * - @auto[], @conf[] (любые вариации с параметрами)
 * - имя начинается с "@GET_" или "@SET_" (регистрозависимо)
 * - точное совпадение имени с одним из: @USE, @BASE, @OPTIONS, @CLASS
 */
public final class Parser3MethodLexerCore {

	private static final List<String> SPECIAL_METHOD_NAMES =
			Arrays.asList("@USE", "@BASE", "@OPTIONS", "@CLASS");

	private Parser3MethodLexerCore() {
	}

	public static @NotNull List<Parser3LexerCore.CoreToken> tokenize(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens
	) {
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();

		for (Parser3LexerCore.CoreToken template : templateTokens) {
			// Ищем определения методов только внутри TEMPLATE_DATA-участков
			result.addAll(lexMethodsInRange(text, template.start, template.end));
		}

		return result;
	}


	/**
	 * Ищет определения методов в диапазоне [rangeStart, rangeEnd).
	 * Учитываются только строки, где '@' стоит в первой колонке строки.
	 */
	@NotNull
	private static List<Parser3LexerCore.CoreToken> lexMethodsInRange(
			@NotNull CharSequence text,
			int rangeStart,
			int rangeEnd
	) {
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();

		int length = text.length();
		if (rangeStart < 0) {
			rangeStart = 0;
		}
		if (rangeEnd > length) {
			rangeEnd = length;
		}
		int i = rangeStart;

		while (i < rangeEnd) {
			int lineStart = i;

			// Проверяем, что lineStart действительно начало строки
			boolean atLineStart = isAtLineStart(text, lineStart);
			int lineEnd = findLineEnd(text, lineStart);
			if (lineEnd > rangeEnd) {
				lineEnd = rangeEnd;
			}

			if (atLineStart && lineEnd > lineStart && text.charAt(lineStart) == '@') {
				parseMethodLine(text, lineStart, lineEnd, result);
			}

			// Переходим на следующую строку
			if (lineEnd == length) {
				break;
			}
			i = lineEnd + 1;
		}

		return result;
	}

	/**
	 * Разбирает одну строку, которая начинается с '@'.
	 * Если удаётся распознать определение метода — добавляет токены в result.
	 */
	private static void parseMethodLine(@NotNull CharSequence text,
										int lineStart,
										int lineEnd,
										@NotNull List<Parser3LexerCore.CoreToken> result) {

		// На входе гарантировано: text.charAt(lineStart) == '@'
		int length = text.length();
		if (lineEnd > length) {
			lineEnd = length;
		}

		// 1. Находим конец имени метода: от '@' до первого '[' или пробела/табуляции
		int pos = lineStart + 1;
		while (pos < lineEnd) {
			char c = text.charAt(pos);
			if (c == '[' || c == ' ' || c == '\t') {
				break;
			}
			pos++;
		}
		int methodNameEnd = pos;
		if (methodNameEnd <= lineStart + 1) {
			// Имя метода пустое — странно, пропускаем
			return;
		}

		String methodName = substringSafely(text, lineStart, methodNameEnd);
		if (methodName == null) {
			return;
		}

		// 2. Определяем тип метода: DEFINE_METHOD или SPECIAL_METHOD
		String methodType = isSpecialMethodName(methodName) ? "SPECIAL_METHOD" : "DEFINE_METHOD";

		// Добавляем токен для имени метода
		String debugName = methodName;
		result.add(new Parser3LexerCore.CoreToken(
				lineStart,
				methodNameEnd,
				methodType,
				debugName
		));

		// 3. Разбор параметров
		// Ищем первый блок [..] — параметры (VARIABLE)
		int searchPos = methodNameEnd;
		int firstOpen = indexOf(text, '[', searchPos, lineEnd);
		if (firstOpen >= 0) {
			int firstClose = indexOf(text, ']', firstOpen + 1, lineEnd);
			if (firstClose > firstOpen) {
				parseParamsInRange(text, firstOpen + 1, firstClose, "VARIABLE", result);
				searchPos = firstClose + 1;
			}
		}

		// 4. Второй блок [..] — локальные переменные (LOCAL_VARIABLE)
		if (searchPos < lineEnd) {
			int secondOpen = indexOf(text, '[', searchPos, lineEnd);
			if (secondOpen >= 0) {
				int secondClose = indexOf(text, ']', secondOpen + 1, lineEnd);
				if (secondClose > secondOpen) {
					parseParamsInRange(text, secondOpen + 1, secondClose, "LOCAL_VARIABLE", result);
				}
			}
		}
	}

	/**
	 * Проверяет, является ли имя специальным методом.
	 */
	private static boolean isSpecialMethodName(@NotNull String methodName) {
		if ("@auto".equals(methodName) || "@conf".equals(methodName) || "@main".equals(methodName)) {
			return true;
		}
		if (methodName.startsWith("@GET_") || methodName.startsWith("@SET_")) {
			return true;
		}
		return SPECIAL_METHOD_NAMES.contains(methodName);
	}

	/**
	 * Поиск символа c в диапазоне [from, to).
	 */
	private static int indexOf(@NotNull CharSequence text, char c, int from, int to) {
		int length = text.length();
		if (from < 0) {
			from = 0;
		}
		if (to > length) {
			to = length;
		}
		for (int i = from; i < to; i++) {
			if (text.charAt(i) == c) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Разбирает список параметров в диапазоне [start, end),
	 * создавая токены типа VARIABLE или LOCAL_VARIABLE.
	 *
	 * Формат: var1;var2
	 * Можно с '*' перед именем: *var1
	 */
	private static void parseParamsInRange(@NotNull CharSequence text,
										   int start,
										   int end,
										   @NotNull String paramType,
										   @NotNull List<Parser3LexerCore.CoreToken> result) {

		int length = text.length();
		if (start < 0) {
			start = 0;
		}
		if (end > length) {
			end = length;
		}

		int pos = start;
		while (pos < end) {
			// Пропускаем пробелы и табы
			while (pos < end) {
				char c = text.charAt(pos);
				if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
					break;
				}
				pos++;
			}
			if (pos >= end) {
				break;
			}

			int nameStart = pos;

			// Допускаем ведущую '*', включаем её в токен
			if (text.charAt(pos) == '*') {
				pos++;
			}

			// Читаем до ';' или пробела/табуляции/закрывающей скобки
			while (pos < end) {
				char c = text.charAt(pos);
				if (c == ';' || c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ']') {
					break;
				}
				pos++;
			}
			int nameEnd = pos;

			if (nameEnd > nameStart) {
				String debug = substringSafely(text, nameStart, nameEnd);
				result.add(new Parser3LexerCore.CoreToken(
						nameStart,
						nameEnd,
						paramType,
						debug
				));
			}

			// Пропускаем до следующего параметра или конца
			while (pos < end) {
				char c = text.charAt(pos);
				if (c == ';') {
					pos++;
					break;
				}
				if (c == ']') {
					return;
				}
				pos++;
			}
		}
	}
}