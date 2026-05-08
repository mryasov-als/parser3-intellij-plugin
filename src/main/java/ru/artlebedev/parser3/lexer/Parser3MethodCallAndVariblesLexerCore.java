package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.Parser3IdentifierUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * Единое ядро для подсветки ПЕРЕМЕННЫХ и ВЫЗОВОВ МЕТОДОВ/КОНСТРУКТОРОВ.
 *
 * Поддерживает конструкции:
 *   $var
 *   $var.x.y[z]
 *   $.field[1]
 *   $data[
 *   	$.x[1]
 *   	$.y(2)
 *   	$.inner[
 *   		$.array[x;y;z]
 *   	]
 *   ]
 *
 *   ^method[]
 *   ^method.x.y[z]
 *   $data[^hash::create[
 *   	$.x[1]
 *   	$.y(2)
 *   	$.inner[
 *   		$.array[x;y;z]
 *   	]
 *   ]]
 *
 * Специальные "важные" имена:
 *   $result, $self, $MAIN, $BASE      → IMPORTANT_VARIABLE
 *   ^result, ^self, ^MAIN, ^BASE      → IMPORTANT_METHOD
 *
 * В шаблонах вида $MAIN:var[123], ^MAIN:method[123]:
 *   - ':' подсвечивается как TEMPLATE_DATA;
 *   - $MAIN / ^MAIN → IMPORTANT_*;
 *   - var / method  → VARIABLE / METHOD.
 *
 * Логика:
 *   - Работает поверх TEMPLATE-слоя: на вход подаются только токены TEMPLATE_DATA.
 *   - В одном проходе по диапазону ищутся как '$', так и '^' (с учётом экранирования '^').
 *   - Для '$' строится цепочка переменной, для '^' — цепочка метода/конструктора.
 *   - Точки, скобки [], () и {} внутри цепочек подсвечиваются как TEMPLATE_DATA.
 */
public final class Parser3MethodCallAndVariblesLexerCore {
	private static final Set<String> IMPORTANT_NAMES = new HashSet<>();
	private static final Set<String> SPECIAL_METHODS = new HashSet<>();
	private static final String[] KEYWORD_METHODS = {
			"eval",
			"if",
			"case",
			"switch",
			"break",
			"continue",
			"for",
			"while",
			"cache",
			"connect",
			"process",
			"return",
			"sleep",
			"use",
			"try",
			"throw",
			"taint",
			"untaint",
			"apply-taint",
			"rem"
	};

	/**
	 * ThreadLocal контекст для хранения всех TEMPLATE_DATA токенов.
	 * Это нужно чтобы findMatchingParen мог искать ) за пределами текущего rangeEnd.
	 */
	private static final ThreadLocal<List<Parser3LexerCore.CoreToken>> TEMPLATE_TOKENS_CONTEXT =
			ThreadLocal.withInitial(Collections::emptyList);

	static {
		SPECIAL_METHODS.add("for");
		SPECIAL_METHODS.add("foreach");
		SPECIAL_METHODS.add("select");
		SPECIAL_METHODS.add("sort");
	}


	static {
		IMPORTANT_NAMES.add("result");
		IMPORTANT_NAMES.add("self");
		IMPORTANT_NAMES.add("caller");
		IMPORTANT_NAMES.add("MAIN");
		IMPORTANT_NAMES.add("BASE");
	}

	private static String getKeywordMethodType(@NotNull String debugName) {
		switch (debugName) {
			case "eval":
				return "KW_EVAL";
			case "if":
				return "KW_IF";
			case "case":
				return "KW_CASE";
			case "switch":
				return "KW_SWITCH";
			case "break":
				return "KW_BREAK";
			case "continue":
				return "KW_CONTINUE";
			case "for":
				return "KW_FOR";
			case "while":
				return "KW_WHILE";
			case "cache":
				return "KW_CACHE";
			case "connect":
				return "KW_CONNECT";
			case "process":
				return "KW_PROCESS";
			case "return":
				return "KW_RETURN";
			case "sleep":
				return "KW_SLEEP";
			case "use":
				return "KW_USE";
			case "try":
				return "KW_TRY";
			case "throw":
				return "KW_THROW";
			default:
				return null;
		}
	}

	private Parser3MethodCallAndVariblesLexerCore() {
	}

	public static @NotNull List<Parser3LexerCore.CoreToken> tokenize(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens
	) {
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();

		// Состояние для продолжения обработки содержимого скобок между токенами
		// (когда комментарий разбивает выражение на части)
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;

		// Устанавливаем контекст для findMatchingParen
		TEMPLATE_TOKENS_CONTEXT.set(templateTokens);
		try {
			for (Parser3LexerCore.CoreToken template : templateTokens) {
				// Проверяем: есть ли }( внутри токена или токен начинается с ( после }?
				// Это продолжение ^if{}(...){}
				boolean isElseIfContinuation = false;
				int elseIfStart = -1;

				// Случай 1: токен начинается с ( и перед ним }
				if (template.start < text.length() && text.charAt(template.start) == '(') {
					int checkPos = template.start - 1;
					while (checkPos >= 0 && Character.isWhitespace(text.charAt(checkPos))) {
						checkPos--;
					}
					if (checkPos >= 0 && text.charAt(checkPos) == '}') {
						isElseIfContinuation = true;
						elseIfStart = template.start;
					}
				}

				// Случай 2: токен начинается с } (возможно после whitespace) и содержит (
				// Это }($cond){...} — нужно разбить на } + ($cond) + {...}
				// Whitespace может появиться когда ^rem{...} или # комментарий разбивает TEMPLATE_DATA
				if (!isElseIfContinuation && template.start < text.length()) {
					int bracePos = template.start;
					while (bracePos < template.end && Character.isWhitespace(text.charAt(bracePos))) {
						bracePos++;
					}
					if (bracePos < template.end && text.charAt(bracePos) == '}') {
						// Ищем ( после }
						int parenPos = bracePos + 1;
						while (parenPos < template.end && Character.isWhitespace(text.charAt(parenPos))) {
							parenPos++;
						}
						if (parenPos < template.end && text.charAt(parenPos) == '(') {
							// Нашли }( — добавляем } как RBRACE
							result.add(new Parser3LexerCore.CoreToken(bracePos, bracePos + 1, "RBRACE", "}"));
							// Затем обрабатываем остаток как elseif
							isElseIfContinuation = true;
							elseIfStart = parenPos;
						}
					}
				}

				// Проверяем ПЕРЕД обработкой: есть ли незакрытые скобки?
				boolean inParensContinuation = hasUnclosedParen(result);

				if (inParensContinuation) {
					// Вычисляем состояние кавычек
					boolean[] qs = computeQuoteStateAfterLastLParen(result, text);
					inSingleQuote = qs[0];
					inDoubleQuote = qs[1];
				}

				if (isElseIfContinuation && elseIfStart >= 0) {
					lexElseIfContinuation(text, elseIfStart, template.end, result);
				} else if (inParensContinuation) {
					// Продолжаем обработку содержимого скобок (после комментария)
					boolean[] quoteState = new boolean[] { inSingleQuote, inDoubleQuote };
					lexParensContinuation(text, template.start, template.end, result, quoteState);
					inSingleQuote = quoteState[0];
					inDoubleQuote = quoteState[1];
				} else {
					lexInRange(text, template.start, template.end, result);
				}
			}
		} finally {
			TEMPLATE_TOKENS_CONTEXT.remove();
		}

		return result;
	}

	/**
	 * Проверяет, есть ли незакрытые круглые скобки в результате.
	 */
	private static boolean hasUnclosedParen(@NotNull List<Parser3LexerCore.CoreToken> tokens) {
		int depth = 0;
		for (Parser3LexerCore.CoreToken token : tokens) {
			if ("LPAREN".equals(token.type)) {
				depth++;
			} else if ("RPAREN".equals(token.type)) {
				depth--;
			}
		}
		return depth > 0;
	}

	/**
	 * Вычисляет состояние кавычек после последнего незакрытого LPAREN.
	 */
	private static boolean[] computeQuoteStateAfterLastLParen(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull CharSequence text
	) {
		// Находим позицию последнего незакрытого LPAREN
		int depth = 0;
		int lastUnmatchedLParenIdx = -1;
		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);
			if ("LPAREN".equals(token.type)) {
				if (depth == 0) {
					lastUnmatchedLParenIdx = i;
				}
				depth++;
			} else if ("RPAREN".equals(token.type)) {
				depth--;
				if (depth == 0) {
					lastUnmatchedLParenIdx = -1;
				}
			}
		}

		boolean inSingle = false;
		boolean inDouble = false;

		if (lastUnmatchedLParenIdx >= 0) {
			// Считаем кавычки от последнего незакрытого LPAREN до конца
			for (int i = lastUnmatchedLParenIdx + 1; i < tokens.size(); i++) {
				Parser3LexerCore.CoreToken token = tokens.get(i);
				if ("LSINGLE_QUOTE".equals(token.type)) {
					inSingle = true;
				} else if ("RSINGLE_QUOTE".equals(token.type)) {
					inSingle = false;
				} else if ("LDOUBLE_QUOTE".equals(token.type)) {
					inDouble = true;
				} else if ("RDOUBLE_QUOTE".equals(token.type)) {
					inDouble = false;
				} else if ("STRING".equals(token.type) || "TEMPLATE_DATA".equals(token.type)) {
					// Считаем кавычки внутри текста токена
					for (int j = token.start; j < token.end && j < text.length(); j++) {
						char c = text.charAt(j);
						if (!isEscapedByCaret(text, j)) {
							if (c == '\'' && !inDouble) {
								inSingle = !inSingle;
							} else if (c == '"' && !inSingle) {
								inDouble = !inDouble;
							}
						}
					}
				}
			}
		}

		return new boolean[] { inSingle, inDouble };
	}

	/**
	 * Продолжает обработку содержимого скобок (после комментария, разбившего токен).
	 * Аналогично внутренности lexParensContent, но без открывающей скобки.
	 */
	private static void lexParensContinuation(
			@NotNull CharSequence text,
			int rangeStart,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result,
			boolean[] quoteState
	) {
		int length = text.length();
		int i = rangeStart;
		boolean inSingleQuote = quoteState[0];
		boolean inDoubleQuote = quoteState[1];

		// Ищем где закрывается скобка (может быть в этом токене или позже)
		// Используем контекст всех токенов для поиска
		int close = findClosingParenFromResult(result, text, rangeStart);
		int contentEnd = (close >= 0 && close <= rangeEnd) ? close : rangeEnd;

		while (i < contentEnd && i < length) {
			char c = text.charAt(i);

			// Отслеживаем кавычки с учётом экранирования ^' и ^"
			if (!isEscapedByCaret(text, i)) {
				if (c == '\'' && !inDoubleQuote) {
					inSingleQuote = !inSingleQuote;
				} else if (c == '"' && !inSingleQuote) {
					inDoubleQuote = !inDoubleQuote;
				}
			}

			// $ и ^ обрабатываются даже внутри строк (Parser3 интерполирует их)
			if (c == '$') {
				if (isEscapedDollar(text, i, rangeStart)) {
					i++;
					continue;
				}
				int next = lexVariableObject(text, i, contentEnd, result);
				if (next <= i) {
					i++;
				} else {
					i = next;
				}
				continue;
			} else if (c == '^') {
				// Логика чёт/нечёт '^'
				int runStart = i;
				int j = i;
				while (j < contentEnd && j < length && text.charAt(j) == '^') {
					j++;
				}
				int runLen = j - runStart;
				if (runLen % 2 == 0) {
					// Чётное — экранирование, пропускаем все ^
					i = j;
					continue;
				} else {
					int caretPos = j - 1;
					if (caretPos + 1 < contentEnd) {
						char nextChar = text.charAt(caretPos + 1);
						if (nextChar == '$') {
							// ^$ — экранирование, пропускаем
							i = j;
							continue;
						} else if (Parser3IdentifierUtils.isIdentifierStart(nextChar)) {
							int next = lexCaretObject(text, caretPos, contentEnd, result);
							if (next > caretPos) {
								i = next;
								continue;
							}
						}
					}
				}
			}

			// NUMBER / BOOLEAN — только вне строк
			if (Character.isDigit(c) && !inSingleQuote && !inDoubleQuote) {
				int numStart = i;
				int j = i + 1;
				while (j < contentEnd && Character.isDigit(text.charAt(j))) {
					j++;
				}
				if (j < contentEnd && text.charAt(j) == '.' && j + 1 < contentEnd && Character.isDigit(text.charAt(j + 1))) {
					j++;
					while (j < contentEnd && Character.isDigit(text.charAt(j))) {
						j++;
					}
				}
				result.add(new Parser3LexerCore.CoreToken(numStart, j, "NUMBER", substringSafely(text, numStart, j)));
				i = j;
				continue;
			}

			if (c == '.' && !inSingleQuote && !inDoubleQuote && i + 1 < contentEnd && Character.isDigit(text.charAt(i + 1))) {
				int numStart = i;
				int j = i + 1;
				while (j < contentEnd && Character.isDigit(text.charAt(j))) {
					j++;
				}
				result.add(new Parser3LexerCore.CoreToken(numStart, j, "NUMBER", substringSafely(text, numStart, j)));
				i = j;
				continue;
			}

			if (!inSingleQuote && !inDoubleQuote) {
				int boolLen = matchBoolean(text, i, contentEnd);
				if (boolLen > 0) {
					result.add(new Parser3LexerCore.CoreToken(i, i + boolLen, "BOOLEAN", substringSafely(text, i, i + boolLen)));
					i += boolLen;
					continue;
				}
			}

			// Обрабатываем chunk
			int chunkStart = i;
			boolean chunkInString = inSingleQuote || inDoubleQuote;
			while (i < contentEnd) {
				char ch = text.charAt(i);
				if (ch == '$' && !isEscapedByCaret(text, i)) {
					break;
				}
				if (ch == '^' && !isEscapedByCaret(text, i) && i + 1 < contentEnd) {
					char next = text.charAt(i + 1);
					if (Parser3IdentifierUtils.isIdentifierStart(next)) {
						break;
					}
				}
				if (!inSingleQuote && !inDoubleQuote) {
					if (Character.isDigit(ch)) break;
					if (ch == '.' && i + 1 < contentEnd && Character.isDigit(text.charAt(i + 1))) break;
				}
				if (!chunkInString && matchBoolean(text, i, contentEnd) > 0) {
					break;
				}
				if (!isEscapedByCaret(text, i)) {
					if ((ch == '\'' && !inDoubleQuote) || (ch == '"' && !inSingleQuote)) {
						break;
					}
				}
				i++;
			}
			if (i > chunkStart) {
				String tokenType = chunkInString ? "STRING" : "TEMPLATE_DATA";
				result.add(new Parser3LexerCore.CoreToken(chunkStart, i, tokenType, substringSafely(text, chunkStart, i)));
			}
			// Пропускаем кавычку
			if (i < contentEnd && !isEscapedByCaret(text, i)) {
				char ch = text.charAt(i);
				if (ch == '\'' && !inDoubleQuote) {
					inSingleQuote = !inSingleQuote;
					i++;
				} else if (ch == '"' && !inSingleQuote) {
					inDoubleQuote = !inDoubleQuote;
					i++;
				}
			}
		}

		// Если закрывающая скобка в этом диапазоне — добавляем RPAREN
		if (close >= 0 && close <= rangeEnd) {
			result.add(new Parser3LexerCore.CoreToken(close, close + 1, "RPAREN", substringSafely(text, close, close + 1)));
			// Обрабатываем остаток после ) обычным способом
			if (close + 1 < rangeEnd) {
				lexInRange(text, close + 1, rangeEnd, result);
			}
		}

		// Сохраняем состояние кавычек
		quoteState[0] = inSingleQuote;
		quoteState[1] = inDoubleQuote;
	}

	/**
	 * Ищет закрывающую скобку для последнего незакрытого LPAREN.
	 * Использует Parser3LexerUtils.findMatchingParen() — единый источник истины.
	 */
	private static int findClosingParenFromResult(
			@NotNull List<Parser3LexerCore.CoreToken> result,
			@NotNull CharSequence text,
			int searchFrom
	) {
		// Находим позицию последнего незакрытого LPAREN
		int depth = 0;
		int lastUnmatchedLParenPos = -1;

		for (Parser3LexerCore.CoreToken token : result) {
			if ("LPAREN".equals(token.type)) {
				if (depth == 0) {
					lastUnmatchedLParenPos = token.start;
				}
				depth++;
			} else if ("RPAREN".equals(token.type)) {
				depth--;
				if (depth == 0) {
					lastUnmatchedLParenPos = -1;
				}
			}
		}

		if (depth <= 0 || lastUnmatchedLParenPos < 0) {
			return -1;
		}

		// Используем единую функцию поиска парной скобки
		return Parser3LexerUtils.findMatchingParen(text, lastUnmatchedLParenPos, text.length());
	}

	/**
	 * Обрабатывает продолжение ^if: (условие){тело}(условие2){тело2}...
	 * Вызывается когда токен начинается с ( после }
	 */
	private static void lexElseIfContinuation(
			@NotNull CharSequence text,
			int rangeStart,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		int length = text.length();
		int i = rangeStart;

		// Обрабатываем цепочку (...){}(...){}...
		// ВАЖНО: используем rangeEnd чтобы не выходить за пределы токена
		while (i < rangeEnd && i < length && text.charAt(i) == '(') {
			// Обрабатываем (...) как LPAREN + содержимое + RPAREN
			i = lexParensContent(text, i, rangeEnd, result);

			// После ) пропускаем пробелы
			while (i < rangeEnd && i < length && Character.isWhitespace(text.charAt(i))) {
				i++;
			}

			// Ожидаем { — но он может быть за пределами rangeEnd или в другом токене
			// В этом случае просто выходим, { обработается отдельно
			if (i < rangeEnd && i < length && text.charAt(i) == '{') {
				// { внутри нашего токена — обрабатываем содержимое
				i = lexCurlyContent(text, i, rangeEnd, result);
			} else {
				break;
			}
		}

		// Остаток токена обрабатываем обычным способом
		if (i < rangeEnd) {
			lexInRange(text, i, rangeEnd, result);
		}
	}

	private static void lexInRange(
			@NotNull CharSequence text,
			int rangeStart,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		int i = rangeStart;
		int length = text.length();

		while (i < rangeEnd && i < length) {
			char c = text.charAt(i);

			if (c == '$') {
				if (isEscapedDollar(text, i, rangeStart)) {
					i++;
					continue;
				}
				int next = lexVariableObject(text, i, rangeEnd, result);
				if (next <= i) {
					i++;
				} else {
					i = next;
				}
				continue;
			}

			if (c == '^') {
				// Сначала считаем подряд идущие '^'
				int runStart = i;
				int j = i;
				while (j < rangeEnd && j < length && text.charAt(j) == '^') {
					j++;
				}
				int runLen = j - runStart;

				if (runLen % 2 == 0) {
					// чётное количество: это просто экранированные '^', не начало метода
					i = j;
					continue;
				}

				// нечётное количество: последняя '^' — кандидат на начало caret-объекта
				int caretPos = j - 1;

				// Специальное правило: ^$ — экранирование '$', не метод.
				if (caretPos + 1 < rangeEnd && text.charAt(caretPos + 1) == '$') {
					// пропускаем весь ран caret'ов, '$' останется обычным текстом
					i = caretPos + 1;
					continue;
				}

				int next = lexCaretObject(text, caretPos, rangeEnd, result);
				if (next <= caretPos) {
					i = caretPos + 1;
				} else {
					i = next;
				}
				continue;
			}

			i++;
		}
	}

	// --------------------------------------------------------------------
	// Разбор объектов, начинающихся с '$'
	// --------------------------------------------------------------------

	private static int lexVariableObject(
			@NotNull CharSequence text,
			int start,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		int length = text.length();
		int afterDollar = start + 1;
		if (afterDollar >= rangeEnd || afterDollar >= length) {
			return start + 1;
		}

		char first = text.charAt(afterDollar);
		int pos;
		boolean isBraceVar = false; // ${...} — после него не продолжаем цепочку

		// Случай "$.x" — "анонимная" переменная текущего контекста.
		if (first == '.') {
			// Подсвечиваем только сам '$' как VARIABLE.
			result.add(new Parser3LexerCore.CoreToken(start, start + 1, "VARIABLE", substringSafely(text, start, start + 1)));
			pos = afterDollar;
		} else {
			// Проверяем, начинается ли с {
			isBraceVar = (first == '{');

			if (isBraceVar) {
				// ${data.key1.key2} — разбиваем: DOLLAR_VARIABLE($) + LBRACE({) + цепочка + RBRACE(})
				int braceStart = afterDollar;
				int braceEnd = findMatching(text, braceStart, rangeEnd, '{', '}');
				if (braceEnd < 0) {
					return start + 1;
				}
				result.add(new Parser3LexerCore.CoreToken(start, start + 1, "DOLLAR_VARIABLE", substringSafely(text, start, start + 1)));
				result.add(new Parser3LexerCore.CoreToken(braceStart, braceStart + 1, "LBRACE", "{"));
				// Эмитируем содержимое: имя + точечную цепочку
				int cur = braceStart + 1;
				// Первый идентификатор
				int identEnd = scanIdentifier(text, cur, braceEnd);
				if (identEnd > cur) {
					String ident = substringSafely(text, cur, identEnd);
					String identType = IMPORTANT_NAMES.contains(ident) ? "IMPORTANT_VARIABLE" : "VARIABLE";
					result.add(new Parser3LexerCore.CoreToken(cur, identEnd, identType, ident));
					cur = identEnd;
				}
				// Точечная цепочка внутри {}
				while (cur < braceEnd) {
					char c = text.charAt(cur);
					if (c == '.') {
						result.add(new Parser3LexerCore.CoreToken(cur, cur + 1, "TEMPLATE_DATA", "."));
						cur++;
						int segEnd = scanIdentifier(text, cur, braceEnd);
						if (segEnd > cur) {
							result.add(new Parser3LexerCore.CoreToken(cur, segEnd, "VARIABLE", substringSafely(text, cur, segEnd)));
							cur = segEnd;
						}
					} else {
						cur++;
					}
				}
				result.add(new Parser3LexerCore.CoreToken(braceEnd, braceEnd + 1, "RBRACE", "}"));
				pos = braceEnd + 1;
			} else {
				int nameEnd = parseVariableName(text, afterDollar, rangeEnd);
				if (nameEnd <= afterDollar) {
					return start + 1;
				}

				String baseName = substringSafely(text, afterDollar, nameEnd);
				String type = IMPORTANT_NAMES.contains(baseName) ? "IMPORTANT_VARIABLE" : "VARIABLE";
				// Разбиваем $var на два токена: '$' и 'var', чтобы их можно было подсвечивать отдельно.
				// Сам знак '$' всегда обычная переменная, а имя может быть IMPORTANT_VARIABLE.
				result.add(new Parser3LexerCore.CoreToken(start, start + 1, "DOLLAR_VARIABLE", substringSafely(text, start, start + 1)));
				result.add(new Parser3LexerCore.CoreToken(afterDollar, nameEnd, type, substringSafely(text, afterDollar, nameEnd)));
				pos = nameEnd;
			}
		}

		// Если переменная была ${...}, не продолжаем цепочку — .xxx это просто текст
		if (isBraceVar) {
			return pos;
		}

		while (pos < rangeEnd && pos < length) {
			char c = text.charAt(pos);

			if (c == '.') {
				// точечная цепочка: $.var.x.y
				result.add(new Parser3LexerCore.CoreToken(pos, pos + 1, "TEMPLATE_DATA", substringSafely(text, pos, pos + 1)));
				int identStart = pos + 1;
				int identEnd = scanIdentifier(text, identStart, rangeEnd);
				if (identEnd > identStart) {
					result.add(new Parser3LexerCore.CoreToken(identStart, identEnd, "VARIABLE", substringSafely(text, identStart, identEnd)));
					pos = identEnd;
					continue;
				}
				pos++;
				continue;
			}

			if (c == '[') {
				pos = lexBracketContent(text, pos, rangeEnd, result);
				continue;
			}

			if (c == '(') {
				pos = lexParensContent(text, pos, rangeEnd, result);
				continue;
			}

			if (c == '{') {
				pos = lexCurlyContent(text, pos, rangeEnd, result);
				continue;
			}

			if (c == ':') {
				// Специальный случай: $MAIN:var[x]
				result.add(new Parser3LexerCore.CoreToken(pos, pos + 1, "TEMPLATE_DATA", substringSafely(text, pos, pos + 1)));
				int identStart = pos + 1;
				int identEnd = scanIdentifier(text, identStart, rangeEnd);
				if (identEnd > identStart) {
					result.add(new Parser3LexerCore.CoreToken(identStart, identEnd, "VARIABLE", substringSafely(text, identStart, identEnd)));
					pos = identEnd;
					continue;
				}
				pos++;
				continue;
			}

			break;
		}

		return pos;
	}

	// --------------------------------------------------------------------
	// Разбор объектов, начинающихся с '^'
	// --------------------------------------------------------------------

	private static int lexCaretObject(
			@NotNull CharSequence text,
			int start,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		int length = text.length();
		int nameStart = start + 1;
		if (nameStart >= rangeEnd || nameStart >= length) {
			return start + 1;
		}
		int nameEnd = parseVariableName(text, nameStart, rangeEnd);
		if (nameEnd <= nameStart) {
			return start + 1;
		}
		String debugName = substringSafely(text, nameStart, nameEnd);
		if (debugName == null) debugName = "";
		if (debugName.isEmpty()) {
			return start + 1;
		}
		boolean hasIdentifier = false;
		for (int k = 0; k < debugName.length(); k++) {
			char ch = debugName.charAt(k);
			if (Parser3IdentifierUtils.isIdentifierStart(ch)) {
				hasIdentifier = true;
				break;
			}
		}
		if (!hasIdentifier) {
			return start + 1;
		}
		// Конструктор: в имени есть "::" — всё имя считается CONSTRUCTOR.
		boolean isConstructor = hasDoubleColon(text, nameEnd, rangeEnd);
		String keywordType = getKeywordMethodType(debugName);
		// Для IMPORTANT_NAMES учитываем базовую часть до ':' или '.',
		// чтобы ^BASE:method[] и ^self.method[] подсвечивались так же,
		// как $BASE:var[] и $self.var[].
		String importanceKey = debugName;
		for (int idx = 0; idx < debugName.length(); idx++) {
			char ch = debugName.charAt(idx);
			if (ch == ':' || ch == '.') {
				importanceKey = debugName.substring(0, idx);
				break;
			}
		}
		String type;
		if (isConstructor) {
			type = "CONSTRUCTOR";
		} else if (IMPORTANT_NAMES.contains(importanceKey)) {
			type = "IMPORTANT_METHOD";
		} else if (keywordType != null) {
			type = keywordType;
		} else {
			type = "METHOD";
		}
		result.add(new Parser3LexerCore.CoreToken(start, nameEnd, type, substringSafely(text, start, nameEnd)));

		// Для ключевых слов с {} (^try, ^throw) содержимое — обычный код, не выражение
		boolean isKeywordWithBrace = keywordType != null &&
				(keywordType.equals("KW_TRY") || keywordType.equals("KW_THROW"));

		int pos = nameEnd;
		while (pos < rangeEnd && pos < length) {
			char c = text.charAt(pos);
			if (c == '.') {
				// Цепочка после метода/конструктора: ^method.x.y
				result.add(new Parser3LexerCore.CoreToken(pos, pos + 1, "DOT", substringSafely(text, pos, pos + 1)));
				int identStart = pos + 1;
				int identEnd = scanIdentifier(text, identStart, rangeEnd);
				if (identEnd > identStart) {
					result.add(new Parser3LexerCore.CoreToken(identStart, identEnd, "METHOD", substringSafely(text, identStart, identEnd)));
					pos = identEnd;
					continue;
				}
				pos++;
				continue;
			}
			if (c == '[') {
				pos = lexBracketContent(text, pos, rangeEnd, result);
				continue;
			}
			if (c == '(') {
				pos = lexParensContent(text, pos, rangeEnd, result);
				continue;
			}
			if (c == '{') {
				if (isKeywordWithBrace) {
					// Для ^try, ^throw: скобки как LBRACE/RBRACE, содержимое — обычный код
					pos = lexKeywordBraceContent(text, pos, rangeEnd, result);
				} else {
					pos = lexCurlyContent(text, pos, rangeEnd, result);
				}
				continue;
			}
			if (c == ':') {
				// Специальный случай: ^MAIN:method[x] и конструкторы вида ^hash::create
				result.add(new Parser3LexerCore.CoreToken(pos, pos + 1, "TEMPLATE_DATA", substringSafely(text, pos, pos + 1)));
				int identStart = pos + 1;
				int identEnd = scanIdentifier(text, identStart, rangeEnd);
				if (identEnd > identStart) {
					String chainedType = (pos > 0 && text.charAt(pos - 1) == ':') ? "CONSTRUCTOR" : "METHOD";
					result.add(new Parser3LexerCore.CoreToken(identStart, identEnd, chainedType, substringSafely(text, identStart, identEnd)));
					pos = identEnd;
					continue;
				}
				pos++;
				continue;
			}
			break;
		}
		return pos;
	}

	// --------------------------------------------------------------------
	// Разбор содержимого скобок
	// --------------------------------------------------------------------

	private static String findMethodNameBeforeBracket(@NotNull CharSequence text, int openPos) {
		int i = openPos - 1;
		// пропускаем пробелы слева от [
		while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
			i--;
		}
		int end = i + 1;
		// набираем идентификатор назад
		while (i >= 0) {
			char c = text.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				i--;
				continue;
			}
			break;
		}
		int start = i + 1;
		if (start >= end) {
			return null;
		}
		String name = substringSafely(text, start, end);
		if (name == null) {
			return null;
		}
		// Проверяем, что перед именем стоит ^ или . (после возможных пробелов)
		int j = start - 1;
		while (j >= 0 && Character.isWhitespace(text.charAt(j))) {
			j--;
		}
		if (j < 0) {
			return null;
		}
		char prefix = text.charAt(j);
		if (prefix != '^' && prefix != '.') {
			return null;
		}
		return name;
	}

	private static int lexBracketContent(
			@NotNull CharSequence text,
			int openPos,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		int length = text.length();
		int close = findMatching(text, openPos, rangeEnd, '[', ']');
		boolean hasRealClose = true;
		if (close < 0 || close > rangeEnd) {
			hasRealClose = false;
			close = rangeEnd;
		}

		// Определяем имя метода и префикс (^ или .) перед [
		String methodName = null;
		char prefix = 0;
		int iScan = openPos - 1;
		while (iScan >= 0 && Character.isWhitespace(text.charAt(iScan))) {
			iScan--;
		}
		int end = iScan + 1;
		while (iScan >= 0) {
			char c = text.charAt(iScan);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				iScan--;
				continue;
			}
			break;
		}
		int start = iScan + 1;
		if (start < end) {
			methodName = substringSafely(text, start, end);
			if (methodName != null && !methodName.isEmpty()) {
				int j = start - 1;
				while (j >= 0 && Character.isWhitespace(text.charAt(j))) {
					j--;
				}
				if (j >= 0) {
					prefix = text.charAt(j);
				}
			}
		}

		boolean isForeachLike = methodName != null
//				&& !"for".equals(methodName)
				&& prefix == '.'
				&& SPECIAL_METHODS.contains(methodName);
		boolean isFor = methodName != null
				&& "for".equals(methodName)
				&& prefix == '^';
		boolean isSpecial = isForeachLike || isFor;

		// Сами скобки — TEMPLATE_DATA
		result.add(new Parser3LexerCore.CoreToken(openPos, openPos + 1, "TEMPLATE_DATA", substringSafely(text, openPos, openPos + 1)));

		int i = openPos + 1;

		if (isSpecial) {
			// Особые [] после .foreach/.select/.sort и ^for[]
			while (i < close && i < length) {
				char c = text.charAt(i);

				if (c == '$') {
					if (isEscapedDollar(text, i, openPos + 1)) {
						i++;
						continue;
					}
					int next = lexVariableObject(text, i, close, result);
					if (next <= i) {
						i++;
					} else {
						i = next;
					}
					continue;
				}

				if (c == '^') {
					int runStart = i;
					int j = i;
					while (j < close && j < length && text.charAt(j) == '^') {
						j++;
					}
					int runLen = j - runStart;
					if (runLen % 2 == 0) {
						i = j;
						continue;
					}
					int caretPos = j - 1;
					if (caretPos + 1 < close && text.charAt(caretPos + 1) == '$') {
						i = caretPos + 1;
						continue;
					}
					int next = lexCaretObject(text, caretPos, close, result);
					if (next <= caretPos) {
						i = caretPos + 1;
					} else {
						i = next;
					}
					continue;
				}

				if (c == ';') {
					result.add(new Parser3LexerCore.CoreToken(i, i + 1, "TEMPLATE_DATA", substringSafely(text, i, i + 1)));
					i++;
					continue;
				}

				if (c == ' ') {
					result.add(new Parser3LexerCore.CoreToken(i, i+1, "STRING", substringSafely(text, i, i+1)));
					i++;
					continue;
				}
				if (Character.isWhitespace(c)) {
					i++;
					continue;
				}

				// Остальное внутри специальных [] считаем именами переменных
				int chunkStart = i;
				while (i < close) {
					char ch = text.charAt(i);
					if (ch == ';' || Character.isWhitespace(ch) || ch == '$' || ch == '^') {
						break;
					}
					i++;
				}
				if (i > chunkStart) {
					result.add(new Parser3LexerCore.CoreToken(chunkStart, i, "VARIABLE", substringSafely(text, chunkStart, i)));
				}
			}
		} else {
			// Обычное поведение для []: ; всегда TEMPLATE, остальное — STRING/объекты
			while (i < close && i < length) {
				char c = text.charAt(i);

				if (c == '$') {
					if (isEscapedDollar(text, i, openPos + 1)) {
						i++;
						continue;
					}
					int next = lexVariableObject(text, i, close, result);
					if (next <= i) {
						i++;
					} else {
						i = next;
					}
					continue;
				}

				if (c == '^') {
					int runStart = i;
					int j = i;
					while (j < close && j < length && text.charAt(j) == '^') {
						j++;
					}
					int runLen = j - runStart;
					if (runLen % 2 == 0) {
						// Чётное количество ^ — это экранированные каретки (^^ -> ^)
						// Создаём STRING и продолжаем собирать до неэкранированного $ или ; или ^method
						int ci = j;
						while (ci < close) {
							char ch = text.charAt(ci);
							if (ch == ';') {
								break;
							}
							if (ch == '$' && !isEscapedByCaret(text, ci)) {
								break;
							}
							if (ch == '^' && ci + 1 < close) {
								char nx = text.charAt(ci + 1);
								// ^method — прерываем
								if (Parser3IdentifierUtils.isIdentifierStart(nx)) {
									break;
								}
								// Экранируемые символы — продолжаем
								if (nx == '$' || nx == '^' || nx == '{' || nx == '}' ||
										nx == '[' || nx == ']' || nx == '(' || nx == ')' ||
										nx == ';' || nx == '"' || nx == ':' || nx == '#' ||
										nx == '\'') {
									ci += 2;
									if (nx == '#') {
										while (ci < close && isHexChar(text.charAt(ci))) {
											ci++;
										}
									}
									continue;
								}
							}
							ci++;
						}
						if (ci > runStart) {
							result.add(new Parser3LexerCore.CoreToken(runStart, ci, "STRING", substringSafely(text, runStart, ci)));
						}
						i = ci;
						continue;
					}
					int caretPos = j - 1;
					char nextChar = (caretPos + 1 < close) ? text.charAt(caretPos + 1) : '\0';

					// Проверяем, является ли следующий символ экранируемым служебным символом
					// По документации: $ ^ { } [ ] ( ) ; " : #
					boolean isEscapedSpecial = (nextChar == '$' || nextChar == '^' ||
							nextChar == '{' || nextChar == '}' ||
							nextChar == '[' || nextChar == ']' ||
							nextChar == '(' || nextChar == ')' ||
							nextChar == ';' || nextChar == '"' ||
							nextChar == ':' || nextChar == '#' ||
							nextChar == '\'');

					if (isEscapedSpecial) {
						int chunkStart = caretPos;
						int ci = caretPos + 2; // после ^X

						// Для ^# пропускаем hex-цифры
						if (nextChar == '#') {
							while (ci < close && isHexChar(text.charAt(ci))) {
								ci++;
							}
						}

						// Продолжаем собирать STRING до неэкранированного $ или ; или ^method
						while (ci < close) {
							char ch = text.charAt(ci);
							if (ch == ';') {
								break;
							}
							if (ch == '$' && !isEscapedByCaret(text, ci)) {
								break;
							}
							if (ch == '^' && ci + 1 < close) {
								char nx = text.charAt(ci + 1);
								// ^method — прерываем
								if (Parser3IdentifierUtils.isIdentifierStart(nx)) {
									break;
								}
								// Другие экранируемые символы — продолжаем
								if (nx == '$' || nx == '^' || nx == '{' || nx == '}' ||
										nx == '[' || nx == ']' || nx == '(' || nx == ')' ||
										nx == ';' || nx == '"' || nx == ':' || nx == '#' ||
										nx == '\'') {
									ci += 2;
									if (nx == '#') {
										// Пропускаем hex-цифры после ^#
										while (ci < close && isHexChar(text.charAt(ci))) {
											ci++;
										}
									}
									continue;
								}
							}
							ci++;
						}
						if (ci > chunkStart) {
							result.add(new Parser3LexerCore.CoreToken(chunkStart, ci, "STRING", substringSafely(text, chunkStart, ci)));
						}
						i = ci;
						continue;
					}

					// ^X где X — буква или _ — это вызов метода
					if (caretPos + 1 < close && Parser3IdentifierUtils.isIdentifierStart(text.charAt(caretPos + 1))) {
						int next = lexCaretObject(text, caretPos, close, result);
						if (next <= caretPos) {
							i = caretPos + 1;
						} else {
							i = next;
						}
						continue;
					}

					// Непонятный ^X — пропускаем
					i = caretPos + 1;
					continue;
				}

				if (c == ';') {
					result.add(new Parser3LexerCore.CoreToken(i, i + 1, "TEMPLATE_DATA", substringSafely(text, i, i + 1)));
					i++;
					continue;
				}

				int chunkStart = i;
				while (i < close) {
					char ch = text.charAt(i);
					if (ch == '$' || ch == '^' || ch == ';') {
						break;
					}
					i++;
				}
				if (i > chunkStart) {
					result.add(new Parser3LexerCore.CoreToken(chunkStart, i, "STRING", substringSafely(text, chunkStart, i)));
				}
			}
		}

		// Закрывающая скобка (если она реально попадает в диапазон)
		if (hasRealClose) {
			result.add(new Parser3LexerCore.CoreToken(close, close + 1, "TEMPLATE_DATA", substringSafely(text, close, close + 1)));
			return close + 1;
		}
		return close;
	}

	private static int lexParensContent(
			@NotNull CharSequence text,
			int openPos,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		int length = text.length();
		// Используем findMatchingParen который может искать за пределами rangeEnd
		int close = findMatchingParen(text, openPos, rangeEnd);
		boolean hasRealClose = true;
		boolean closeOutsideRange = false;
		if (close < 0) {
			hasRealClose = false;
			close = rangeEnd;
		} else if (close > rangeEnd) {
			// ) найдена за пределами текущего TEMPLATE_DATA куска
			closeOutsideRange = true;
		}

		// Открывающая скобка — LPAREN (это Parser3-выражение, т.к. вызвано после $var или ^method)
		result.add(new Parser3LexerCore.CoreToken(openPos, openPos + 1, "LPAREN", substringSafely(text, openPos, openPos + 1)));

		// Обрабатываем содержимое до rangeEnd или до close (что меньше)
		int contentEnd = Math.min(close, rangeEnd);
		int i = openPos + 1;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;

		while (i < contentEnd && i < length) {
			char c = text.charAt(i);

			// Отслеживаем кавычки с учётом экранирования ^' и ^"
			if (!isEscapedByCaret(text, i)) {
				if (c == '\'' && !inDoubleQuote) {
					inSingleQuote = !inSingleQuote;
				} else if (c == '"' && !inSingleQuote) {
					inDoubleQuote = !inDoubleQuote;
				}
			}

			// $ и ^ обрабатываются даже внутри строк (Parser3 интерполирует их)
			if (c == '$') {
				if (isEscapedDollar(text, i, openPos + 1)) {
					i++;
					continue;
				}
				int next = lexVariableObject(text, i, close, result);
				if (next <= i) {
					i++;
				} else {
					i = next;
				}
				continue;
			} else if (c == '^') {
				// Логика чёт/нечёт '^' и внутри скобок
				int runStart = i;
				int j = i;
				while (j < close && j < length && text.charAt(j) == '^') {
					j++;
				}
				int runLen = j - runStart;
				if (runLen % 2 == 0) {
					// Чётное число ^ — это экранирование, всё поглощается в chunk ниже
					// НЕ делаем continue, пусть идёт в chunk loop
				} else {
					int caretPos = j - 1;
					// Проверяем что после ^ идёт буква (начало метода)
					if (caretPos + 1 < contentEnd) {
						char nextChar = text.charAt(caretPos + 1);
						if (nextChar == '$') {
							// ^$ — экранированный $, НЕ переменная, идёт в chunk как текст
							// НЕ делаем continue, пусть идёт в chunk loop
						} else if (Parser3IdentifierUtils.isIdentifierStart(nextChar)) {
							// ^method — это вызов метода
							int next = lexCaretObject(text, caretPos, contentEnd, result);
							if (next > caretPos) {
								i = next;
								continue;
							}
						}
					}
					// ^) ^" ^$ и т.д. — экранирование специального символа, идёт в chunk
				}
			}

			// NUMBER / BOOLEAN / остальное - НО НЕ ВНУТРИ СТРОК!
			if (Character.isDigit(c) && !inSingleQuote && !inDoubleQuote) {
				int numStart = i;
				int j = i + 1;
				while (j < contentEnd && Character.isDigit(text.charAt(j))) {
					j++;
				}
				// Проверяем дробную часть: точка + цифры
				if (j < contentEnd && text.charAt(j) == '.' && j + 1 < contentEnd && Character.isDigit(text.charAt(j + 1))) {
					j++; // пропускаем точку
					while (j < contentEnd && Character.isDigit(text.charAt(j))) {
						j++;
					}
				}
				result.add(new Parser3LexerCore.CoreToken(numStart, j, "NUMBER", substringSafely(text, numStart, j)));
				i = j;
				continue;
			}

			// Числа начинающиеся с точки (.5 = 0.5) — только вне строк
			if (c == '.' && !inSingleQuote && !inDoubleQuote && i + 1 < contentEnd && Character.isDigit(text.charAt(i + 1))) {
				int numStart = i;
				int j = i + 1;
				while (j < contentEnd && Character.isDigit(text.charAt(j))) {
					j++;
				}
				result.add(new Parser3LexerCore.CoreToken(numStart, j, "NUMBER", substringSafely(text, numStart, j)));
				i = j;
				continue;
			}

			// BOOLEAN: true/false — только вне строк и как отдельное слово
			if (!inSingleQuote && !inDoubleQuote) {
				int boolLen = matchBoolean(text, i, close);
				if (boolLen > 0) {
					result.add(new Parser3LexerCore.CoreToken(i, i + boolLen, "BOOLEAN", substringSafely(text, i, i + boolLen)));
					i += boolLen;
					continue;
				}
			}

			int chunkStart = i;
			// Запоминаем состояние на начало chunk — если мы УЖЕ внутри строки, это STRING
			boolean chunkInString = inSingleQuote || inDoubleQuote;
			while (i < contentEnd) {
				char ch = text.charAt(i);
				// $ прерывает только если НЕ экранирован (^$)
				if (ch == '$' && !isEscapedByCaret(text, i)) {
					break;
				}
				// ^ прерывает только если после него буква (начало метода),
				// а не специальный символ (экранирование типа ^) ^" ^$ и т.д.)
				// Parser3 интерполирует методы внутри строк тоже
				if (ch == '^' && i + 1 < contentEnd) {
					char next = text.charAt(i + 1);
					if (Parser3IdentifierUtils.isIdentifierStart(next)) {
						break;
					}
				}
				// Числа прерывают только вне строк
				if (!inSingleQuote && !inDoubleQuote) {
					if (Character.isDigit(ch)) {
						break;
					}
					// Числа начинающиеся с точки (.5)
					if (ch == '.' && i + 1 < contentEnd && Character.isDigit(text.charAt(i + 1))) {
						break;
					}
				}
				// Проверяем true/false только вне строк
				if (!inSingleQuote && !inDoubleQuote && matchBoolean(text, i, close) > 0) {
					break;
				}
				// Кавычки прерывают chunk — чтобы текст до и после кавычки был разными токенами
				if (!isEscapedByCaret(text, i)) {
					if ((ch == '\'' && !inDoubleQuote) || (ch == '"' && !inSingleQuote)) {
						break;
					}
				}
				i++;
			}
			if (i > chunkStart) {
				// Если chunk начинался внутри строки — это STRING, иначе TEMPLATE_DATA
				String tokenType = chunkInString ? "STRING" : "TEMPLATE_DATA";
				result.add(new Parser3LexerCore.CoreToken(chunkStart, i, tokenType, substringSafely(text, chunkStart, i)));
			}
			// Если остановились на кавычке — обновляем состояние и пропускаем её
			// (сама кавычка будет токенизирована в ExpressionLexerCore)
			if (i < contentEnd && !isEscapedByCaret(text, i)) {
				char ch = text.charAt(i);
				if (ch == '\'' && !inDoubleQuote) {
					inSingleQuote = !inSingleQuote;
					i++; // пропускаем кавычку
				} else if (ch == '"' && !inSingleQuote) {
					inDoubleQuote = !inDoubleQuote;
					i++; // пропускаем кавычку
				}
			}
		}

		if (hasRealClose && !closeOutsideRange) {
			// Закрывающая скобка внутри текущего диапазона — RPAREN
			result.add(new Parser3LexerCore.CoreToken(close, close + 1, "RPAREN", substringSafely(text, close, close + 1)));
			return close + 1;
		} else if (closeOutsideRange) {
			// ) за пределами rangeEnd — НЕ создаём RPAREN здесь,
			// он будет создан при обработке следующего TEMPLATE_DATA куска через lexParensContinuation
			return rangeEnd;
		}
		return rangeEnd;
	}

	private static int lexCurlyContent(
			@NotNull CharSequence text,
			int openPos,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		int length = text.length();
		int close = findMatching(text, openPos, rangeEnd, '{', '}');
		boolean hasRealClose = true;
		if (close < 0 || close > rangeEnd) {
			hasRealClose = false;
			close = rangeEnd;
		}

		// Скобки как TEMPLATE_DATA
		result.add(new Parser3LexerCore.CoreToken(openPos, openPos + 1, "TEMPLATE_DATA", substringSafely(text, openPos, openPos + 1)));

		// Содержимое обрабатываем той же логикой объектов $ / ^, что и снаружи.
		lexInRange(text, openPos + 1, close, result);

		if (hasRealClose) {
			result.add(new Parser3LexerCore.CoreToken(close, close + 1, "TEMPLATE_DATA", substringSafely(text, close, close + 1)));
			return close + 1;
		}
		return close;
	}

	/**
	 * Обрабатывает содержимое {} для ключевых слов типа ^try, ^throw.
	 * Скобки — LBRACE/RBRACE, содержимое — обычный код (не выражение).
	 */
	private static int lexKeywordBraceContent(
			@NotNull CharSequence text,
			int openPos,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result
	) {
		int length = text.length();
		int close = findMatching(text, openPos, rangeEnd, '{', '}');
		boolean hasRealClose = true;
		if (close < 0 || close > rangeEnd) {
			hasRealClose = false;
			close = rangeEnd;
		}

		// Скобки как LBRACE/RBRACE
		result.add(new Parser3LexerCore.CoreToken(openPos, openPos + 1, "LBRACE", substringSafely(text, openPos, openPos + 1)));

		// Содержимое — обычный код, обрабатываем $ / ^
		lexInRange(text, openPos + 1, close, result);

		if (hasRealClose) {
			result.add(new Parser3LexerCore.CoreToken(close, close + 1, "RBRACE", substringSafely(text, close, close + 1)));
			return close + 1;
		}
		return close;
	}


	private static boolean hasDoubleColon(@NotNull CharSequence text, int nameEnd, int rangeEnd) {
		int length = text.length();
		int i = nameEnd;
		if (i < 0) {
			return false;
		}
		while (i < rangeEnd && i < length && Character.isWhitespace(text.charAt(i))) {
			char ch = text.charAt(i);
			if (!Character.isWhitespace(ch)) {
				break;
			}
			i++;
		}
		if (i + 1 < rangeEnd && i + 1 < length && text.charAt(i) == ':' && text.charAt(i + 1) == ':') {
			return true;
		}
		return false;
	}
	// --------------------------------------------------------------------
	// Вспомогательные функции
	// --------------------------------------------------------------------

	private static int parseVariableName(
			@NotNull CharSequence text,
			int start,
			int limit
	) {
		int i = start;
		int length = text.length();
		boolean hasAny = false;

		// Вариант 1: имя начинается с плейсхолдера ${var}
		if (i < limit && i < length && text.charAt(i) == '{') {
			int braceEnd = findMatching(text, i, limit, '{', '}');
			if (braceEnd < 0) {
				return start;
			}
			hasAny = true;
			i = braceEnd + 1;
		} else {
			// Вариант 2: обычный префикс: $var
			while (i < limit && i < length) {
				char c = text.charAt(i);
				if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
					hasAny = true;
					i++;
					continue;
				}
				break;
			}
		}

		// После основного имени допускаем хвосты вида ${name}: $var${name}
		while (i + 1 < limit && i + 1 < length && text.charAt(i) == '$' && text.charAt(i + 1) == '{') {
			int braceEnd = findMatching(text, i + 1, limit, '{', '}');
			if (braceEnd < 0) {
				break;
			}
			hasAny = true;
			i = braceEnd + 1;
		}

		return hasAny ? i : start;
	}

	private static int scanIdentifier(
			@NotNull CharSequence text,
			int start,
			int limit
	) {
		int i = start;
		int length = text.length();
		while (i < limit && i < length) {
			char c = text.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				i++;
				continue;
			}
			break;
		}
		return i;
	}

	private static boolean startsWith(
			@NotNull CharSequence text,
			int start,
			int end,
			@NotNull String keyword
	) {
		int len = keyword.length();
		if (start + len > end) {
			return false;
		}
		for (int i = 0; i < len; i++) {
			if (text.charAt(start + i) != keyword.charAt(i)) {
				return false;
			}
		}
		return true;
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
		if (startsWith(text, start, end, "true")) {
			int afterTrue = start + 4;
			// Проверяем что после "true" нет буквы/цифры (иначе это часть слова)
			if (afterTrue >= end || afterTrue >= length || !Character.isLetterOrDigit(text.charAt(afterTrue))) {
				return 4;
			}
		}

		// Проверяем "false"
		if (startsWith(text, start, end, "false")) {
			int afterFalse = start + 5;
			// Проверяем что после "false" нет буквы/цифры
			if (afterFalse >= end || afterFalse >= length || !Character.isLetterOrDigit(text.charAt(afterFalse))) {
				return 5;
			}
		}

		return 0;
	}

	private static boolean isEscapedDollar(
			@NotNull CharSequence text,
			int pos,
			int rangeStart
	) {
		int count = 0;
		int i = pos - 1;
		while (i >= rangeStart && text.charAt(i) == '^') {
			count++;
			i--;
		}
		return (count % 2) != 0;
	}

	/**
	 * Проверяет, экранирован ли символ на позиции pos символом ^.
	 * Экранирование: нечётное количество ^ перед символом.
	 * Применимо для: ' " ( ) [ ] { }
	 */
	private static boolean isEscapedByCaret(@NotNull CharSequence text, int pos) {
		return Parser3LexerUtils.isEscapedByCaret(text, pos);
	}

	private static int findMatching(
			@NotNull CharSequence text,
			int openPos,
			int rangeEnd,
			char openCh,
			char closeCh
	) {
		return Parser3LexerUtils.findMatchingBracket(text, openPos, rangeEnd, openCh, closeCh);
	}

	/**
	 * Находит парную закрывающую скобку для круглых скобок.
	 * В отличие от findMatching, эта версия может искать за пределами rangeEnd,
	 * используя TEMPLATE_TOKENS_CONTEXT для определения где искать.
	 * Это нужно когда LINE_COMMENT внутри ^if(...) разбивает выражение на части.
	 */
	private static int findMatchingParen(
			@NotNull CharSequence text,
			int openPos,
			int rangeEnd
	) {
		int length = text.length();
		if (openPos < 0 || openPos >= length) {
			return -1;
		}

		// Сначала пробуем найти в текущем диапазоне
		int result = findMatching(text, openPos, rangeEnd, '(', ')');
		if (result >= 0) {
			return result;
		}

		// Не нашли — ищем в следующих TEMPLATE_DATA кусках
		List<Parser3LexerCore.CoreToken> allTemplateTokens = TEMPLATE_TOKENS_CONTEXT.get();
		if (allTemplateTokens.isEmpty()) {
			return -1;
		}

		int depth = 1; // Уже посчитали открывающую (
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;

		// Считаем глубину до конца текущего диапазона
		for (int i = openPos + 1; i < rangeEnd && i < length; i++) {
			char c = text.charAt(i);

			// Проверяем экранирование
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

			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}

		// Ищем в следующих кусках TEMPLATE_DATA
		for (Parser3LexerCore.CoreToken token : allTemplateTokens) {
			if (token.start <= rangeEnd) {
				continue; // Пропускаем текущий и предыдущие куски
			}

			for (int i = token.start; i < token.end && i < length; i++) {
				char c = text.charAt(i);

				// Проверяем экранирование
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

				if (c == '(') {
					depth++;
				} else if (c == ')') {
					depth--;
					if (depth == 0) {
						return i;
					}
				}
			}
		}

		return -1;
	}

	/**
	 * Проверяет является ли символ hex-цифрой (0-9, a-f, A-F).
	 */
	private static boolean isHexChar(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}
}
