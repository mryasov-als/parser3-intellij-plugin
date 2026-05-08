package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * Оптимизированный лексер выражений.
 *
 * Вместо создания массивов boolean[fileLength] и int[fileLength],
 * работаем только с TEMPLATE_DATA токенами и вычисляем состояние на лету.
 */
public final class Parser3ExpressionLexerCore {

	private static final String TYPE_OP = "OP";
	private static final String TYPE_WORD_OP = "WORD_OPERATOR";

	// Операторы по убыванию длины для правильного матчинга
	private static final String[] SYMBOL3 = { "!||" };
	private static final String[] SYMBOL2 = { "==", "!=", "<=", ">=", "&&", "||", "<<", ">>", "!|" };
	private static final char[] SYMBOL1 = { '!', '~', '+', '-', '*', '/', '%', '\\', '<', '>', '&', '|' };

	private static final String[] WORD_OPS = {
			"def","is","eq","ne","lt","gt","le","ge","in","-f","-d"
	};

	private Parser3ExpressionLexerCore(){}

	public static @NotNull List<Parser3LexerCore.CoreToken> tokenize(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens
	){
		return tokenize(text, templateTokens, null);
	}

	/**
	 * Токенизация выражений.
	 * Оптимизировано: работаем только с переданными токенами, без создания больших массивов.
	 */
	public static @NotNull List<Parser3LexerCore.CoreToken> tokenize(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens,
			@Nullable List<Parser3LexerCore.CoreToken> allTokens
	){
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();
		int length = text.length();
		if (length == 0 || templateTokens.isEmpty()) {
			return result;
		}

		// Строим карту глубины скобок только для позиций LPAREN/RPAREN
		// Это гораздо эффективнее чем массив int[length]
		int[] parenPositions = null;
		int[] parenDepths = null;
		int parenCount = 0;

		if (allTokens != null) {
			// Считаем количество скобок
			for (Parser3LexerCore.CoreToken t : allTokens) {
				if ("LPAREN".equals(t.type) || "RPAREN".equals(t.type)) {
					parenCount++;
				}
			}

			if (parenCount > 0) {
				parenPositions = new int[parenCount];
				parenDepths = new int[parenCount];
				int idx = 0;
				int currentDepth = 0;

				for (Parser3LexerCore.CoreToken t : allTokens) {
					if ("LPAREN".equals(t.type)) {
						currentDepth++;
						parenPositions[idx] = t.start;
						parenDepths[idx] = currentDepth;
						idx++;
					} else if ("RPAREN".equals(t.type)) {
						currentDepth--;
						if (currentDepth < 0) currentDepth = 0;
						parenPositions[idx] = t.start;
						parenDepths[idx] = currentDepth;
						idx++;
					}
				}
			}
		}

		// Состояние между вызовами processTemplateToken
		// [0] = inSingleQuote, [1] = inDoubleQuote, [2] = currentDepth, [3] = singleQuoteStart, [4] = doubleQuoteStart
		int[] state = new int[]{0, 0, 0, -1, -1};

		// Дополнительное смещение глубины от скобок созданных в ExpressionLexerCore
		int depthDelta = 0;

		// Обрабатываем только TEMPLATE_DATA токены (не SQL_BLOCK)
		for (Parser3LexerCore.CoreToken template : templateTokens) {
			if (!"TEMPLATE_DATA".equals(template.type)) {
				continue;
			}

			// Вычисляем глубину для этого TEMPLATE_DATA:
			// базовая глубина из allTokens + дельта от скобок созданных в предыдущих TEMPLATE_DATA
			int baseDepth = getDepthAt(template.start, parenPositions, parenDepths, parenCount);
			state[2] = baseDepth + depthDelta;

			int depthBefore = state[2];
			processTemplateToken(text, template.start, template.end, result,
					parenPositions, parenDepths, parenCount, state);
			// Обновляем дельту: разница между глубиной после и до обработки
			depthDelta += (state[2] - depthBefore);
		}

		return result;
	}

	/**
	 * Обрабатывает один TEMPLATE_DATA токен.
	 * @param state состояние [inSingleQuote, inDoubleQuote, currentDepth], обновляется в процессе
	 */
	private static void processTemplateToken(
			@NotNull CharSequence text,
			int start,
			int end,
			@NotNull List<Parser3LexerCore.CoreToken> result,
			@Nullable int[] parenPositions,
			@Nullable int[] parenDepths,
			int parenCount,
			int[] state
	) {
		int length = text.length();
		if (start >= end || start >= length) {
			return;
		}

		// Состояние для отслеживания кавычек и глубины — берём из переданного состояния
		boolean inSingleQuote = state[0] != 0;
		boolean inDoubleQuote = state[1] != 0;
		int currentDepth = state[2]; // Динамическая глубина скобок
		int braceDepth = 0;
		int singleQuoteStart = state[3]; // Позиция открывающей одинарной кавычки
		int doubleQuoteStart = state[4]; // Позиция открывающей двойной кавычки

		int i = start;
		while (i < end && i < length) {
			char c = text.charAt(i);

			// Используем currentDepth (динамическую глубину)
			int depth = currentDepth;

			// Внутри круглых скобок обрабатываем кавычки и строки
			if (depth > 0) {
				if (!esc(text, i)) {
					if (c == '\'' && !inDoubleQuote) {
						if (inSingleQuote) {
							// Закрываем строку — создаём STRING только если открывающая кавычка в этом же диапазоне
							if (singleQuoteStart >= start && i > singleQuoteStart + 1) {
								result.add(new Parser3LexerCore.CoreToken(singleQuoteStart + 1, i, "STRING",
										substringSafely(text, singleQuoteStart + 1, i)));
							}
							result.add(new Parser3LexerCore.CoreToken(i, i + 1, "RSINGLE_QUOTE",
									substringSafely(text, i, i + 1)));
						} else {
							// Открываем строку
							singleQuoteStart = i;
							result.add(new Parser3LexerCore.CoreToken(i, i + 1, "LSINGLE_QUOTE",
									substringSafely(text, i, i + 1)));
						}
						inSingleQuote = !inSingleQuote;
						i++;
						continue;
					}
					if (c == '"' && !inSingleQuote) {
						if (inDoubleQuote) {
							// Закрываем строку — создаём STRING только если открывающая кавычка в этом же диапазоне
							if (doubleQuoteStart >= start && i > doubleQuoteStart + 1) {
								result.add(new Parser3LexerCore.CoreToken(doubleQuoteStart + 1, i, "STRING",
										substringSafely(text, doubleQuoteStart + 1, i)));
							}
							result.add(new Parser3LexerCore.CoreToken(i, i + 1, "RDOUBLE_QUOTE",
									substringSafely(text, i, i + 1)));
						} else {
							// Открываем строку
							doubleQuoteStart = i;
							result.add(new Parser3LexerCore.CoreToken(i, i + 1, "LDOUBLE_QUOTE",
									substringSafely(text, i, i + 1)));
						}
						inDoubleQuote = !inDoubleQuote;
						i++;
						continue;
					}
				}

				// Внутри строки — пропускаем
				if (inSingleQuote || inDoubleQuote) {
					i++;
					continue;
				}

				// Отслеживаем фигурные скобки
				if (!esc(text, i)) {
					if (c == '{') {
						braceDepth++;
						i++;
						continue;
					} else if (c == '}' && braceDepth > 0) {
						braceDepth--;
						i++;
						continue;
					}
				}

				// Вне фигурных скобок — ищем операторы
				if (braceDepth == 0) {
					// Вложенные круглые скобки внутри выражения
					if (c == '(' && !esc(text, i)) {
						result.add(new Parser3LexerCore.CoreToken(i, i + 1, "LPAREN",
								substringSafely(text, i, i + 1)));
						currentDepth++; // Увеличиваем глубину
						i++;
						continue;
					}
					if (c == ')' && !esc(text, i)) {
						result.add(new Parser3LexerCore.CoreToken(i, i + 1, "RPAREN",
								substringSafely(text, i, i + 1)));
						currentDepth--; // Уменьшаем глубину
						if (currentDepth < 0) currentDepth = 0;
						i++;
						continue;
					}

					// Словесные операторы
					if (Character.isLetter(c) || c == '-') {
						int opEnd = tryMatchWordOperator(text, i, end);
						if (opEnd > i) {
							result.add(new Parser3LexerCore.CoreToken(i, opEnd, TYPE_WORD_OP,
									substringSafely(text, i, opEnd)));
							i = opEnd;
							continue;
						}
					}

					// Символьные операторы
					int opLen = tryMatchSymbolOperator(text, i, end);
					if (opLen > 0) {
						result.add(new Parser3LexerCore.CoreToken(i, i + opLen, TYPE_OP,
								substringSafely(text, i, i + opLen)));
						i += opLen;
						continue;
					}

					// Числа (включая числа с плавающей точкой)
					if (c >= '0' && c <= '9') {
						int numEnd = i + 1;
						while (numEnd < end && numEnd < length &&
								text.charAt(numEnd) >= '0' && text.charAt(numEnd) <= '9') {
							numEnd++;
						}
						// Проверяем дробную часть: точка + цифры
						if (numEnd < end && numEnd < length && text.charAt(numEnd) == '.' &&
								numEnd + 1 < length && text.charAt(numEnd + 1) >= '0' && text.charAt(numEnd + 1) <= '9') {
							numEnd++; // пропускаем точку
							while (numEnd < end && numEnd < length &&
									text.charAt(numEnd) >= '0' && text.charAt(numEnd) <= '9') {
								numEnd++;
							}
						}
						result.add(new Parser3LexerCore.CoreToken(i, numEnd, "NUMBER",
								substringSafely(text, i, numEnd)));
						i = numEnd;
						continue;
					}

					// Числа начинающиеся с точки (.5 = 0.5)
					if (c == '.' && i + 1 < end && i + 1 < length &&
							text.charAt(i + 1) >= '0' && text.charAt(i + 1) <= '9') {
						int numEnd = i + 1;
						while (numEnd < end && numEnd < length &&
								text.charAt(numEnd) >= '0' && text.charAt(numEnd) <= '9') {
							numEnd++;
						}
						result.add(new Parser3LexerCore.CoreToken(i, numEnd, "NUMBER",
								substringSafely(text, i, numEnd)));
						i = numEnd;
						continue;
					}

					// Булевы значения true/false
					int boolLen = matchBoolean(text, i, end);
					if (boolLen > 0) {
						result.add(new Parser3LexerCore.CoreToken(i, i + boolLen, "BOOLEAN",
								substringSafely(text, i, i + boolLen)));
						i += boolLen;
						continue;
					}
				}
			}

			// При выходе из скобок сбрасываем состояние строк
			if (depth == 0) {
				inSingleQuote = false;
				inDoubleQuote = false;
				braceDepth = 0;
			}

			i++;
		}

		// Сохраняем состояние для следующего вызова
		state[0] = inSingleQuote ? 1 : 0;
		state[1] = inDoubleQuote ? 1 : 0;
		state[2] = currentDepth;
		state[3] = singleQuoteStart;
		state[4] = doubleQuoteStart;
	}

	/**
	 * Получает глубину вложенности скобок для позиции.
	 * Использует бинарный поиск по отсортированному массиву позиций скобок.
	 */
	private static int getDepthAt(int pos, @Nullable int[] positions, @Nullable int[] depths, int count) {
		if (positions == null || depths == null || count == 0) {
			return 0;
		}

		// Бинарный поиск последней скобки до pos
		int lo = 0;
		int hi = count - 1;
		int foundIdx = -1;

		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (positions[mid] <= pos) {
				foundIdx = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}

		if (foundIdx < 0) {
			return 0;
		}

		return depths[foundIdx];
	}

	/**
	 * Пытается сопоставить словесный оператор.
	 * Возвращает позицию конца оператора или исходную позицию если не совпало.
	 */
	private static int tryMatchWordOperator(@NotNull CharSequence text, int start, int end) {
		int length = text.length();
		int p = start;

		// Собираем слово
		while (p < end && p < length) {
			char ch = text.charAt(p);
			if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
				p++;
			} else {
				break;
			}
		}

		if (p == start) {
			return start;
		}

		String word = text.subSequence(start, p).toString();
		for (String op : WORD_OPS) {
			if (op.equals(word)) {
				return p;
			}
		}

		return start;
	}

	/**
	 * Пытается сопоставить символьный оператор.
	 * Возвращает длину оператора или 0 если не совпало.
	 */
	private static int tryMatchSymbolOperator(@NotNull CharSequence text, int start, int end) {
		int length = text.length();

		// 3-символьные операторы
		if (start + 3 <= end && start + 3 <= length) {
			String v = text.subSequence(start, start + 3).toString();
			for (String op : SYMBOL3) {
				if (op.equals(v)) {
					return 3;
				}
			}
		}

		// 2-символьные операторы
		if (start + 2 <= end && start + 2 <= length) {
			String v = text.subSequence(start, start + 2).toString();
			for (String op : SYMBOL2) {
				if (op.equals(v)) {
					return 2;
				}
			}
		}

		// 1-символьные операторы
		char c = text.charAt(start);
		for (char op : SYMBOL1) {
			if (op == c) {
				return 1;
			}
		}

		return 0;
	}

	private static boolean esc(CharSequence t, int p) {
		int c = 0, i = p - 1;
		while (i >= 0 && t.charAt(i) == '^') {
			c++;
			i--;
		}
		return (c % 2) != 0;
	}

	/**
	 * Проверяет, начинается ли с позиции start слово "true" или "false".
	 * Возвращает длину слова (4 или 5) или 0 если не совпало.
	 * Слово должно быть отдельным — после него не должно быть буквы/цифры.
	 */
	private static int matchBoolean(@NotNull CharSequence text, int start, int end) {
		int length = text.length();
		if (start >= end || start >= length) {
			return 0;
		}

		// Проверяем "true"
		if (start + 4 <= end && start + 4 <= length) {
			if (text.charAt(start) == 't' && text.charAt(start + 1) == 'r' &&
					text.charAt(start + 2) == 'u' && text.charAt(start + 3) == 'e') {
				int afterTrue = start + 4;
				if (afterTrue >= end || afterTrue >= length || !Character.isLetterOrDigit(text.charAt(afterTrue))) {
					return 4;
				}
			}
		}

		// Проверяем "false"
		if (start + 5 <= end && start + 5 <= length) {
			if (text.charAt(start) == 'f' && text.charAt(start + 1) == 'a' &&
					text.charAt(start + 2) == 'l' && text.charAt(start + 3) == 's' &&
					text.charAt(start + 4) == 'e') {
				int afterFalse = start + 5;
				if (afterFalse >= end || afterFalse >= length || !Character.isLetterOrDigit(text.charAt(afterFalse))) {
					return 5;
				}
			}
		}

		return 0;
	}
}