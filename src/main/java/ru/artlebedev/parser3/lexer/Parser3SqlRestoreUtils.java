package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Утилиты для восстановления типа SQL_BLOCK после обработки лексерами.
 *
 * Проблема: SQL_BLOCK создаётся на раннем этапе, но потом другие лексеры
 * (expressions, brackets) разбивают его на TEMPLATE_DATA, STRING, LPAREN и т.д.
 * Эти утилиты восстанавливают SQL_BLOCK для токенов которые должны быть SQL.
 *
 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
 * ВАЖНО: Эта логика работает на уровне ТОКЕНОВ (лексер).
 * Аналогичная логика для PSI находится в InjectorUtils.collectPartsInternal().
 * При изменении правил определения Parser3-конструкций нужно менять ОБЕ!
 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
 */
public final class Parser3SqlRestoreUtils {

	private Parser3SqlRestoreUtils() {
	}

	/**
	 * Восстанавливает тип SQL_BLOCK для токенов которые были внутри SQL_BLOCK.
	 * Вызывается после каждого лексера который обрабатывает SQL_BLOCK.
	 *
	 * Правило: TEMPLATE_DATA и STRING внутри SQL_BLOCK меняем на SQL_BLOCK,
	 * НО НЕ внутри Parser3 скобок (круглых и квадратных) — там Parser3-выражения.
	 * Parser3 скобки — это скобки после METHOD, VARIABLE, KW_* токенов.
	 * SQL скобки — это все остальные скобки внутри SQL.
	 *
	 * @param layerTokens токены после обработки лексером (будут модифицированы)
	 * @param tokensToProcess исходные токены (содержат информацию о SQL_BLOCK диапазонах)
	 */
	public static void restoreSqlBlockType(
			@NotNull List<Parser3LexerCore.CoreToken> layerTokens,
			@NotNull List<Parser3LexerCore.CoreToken> tokensToProcess
	) {
		// Собираем диапазоны SQL_BLOCK
		List<int[]> sqlRanges = new ArrayList<>();
		for (Parser3LexerCore.CoreToken t : tokensToProcess) {
			if ("SQL_BLOCK".equals(t.type)) {
				sqlRanges.add(new int[]{t.start, t.end});
			}
		}
		if (sqlRanges.isEmpty()) {
			return;
		}

		// Собираем диапазоны Parser3-скобок (их содержимое НЕ меняем на SQL_BLOCK)
		// Parser3 скобки — это скобки которые следуют за METHOD, VARIABLE, KW_*
		List<int[]> excludeRanges = new ArrayList<>();
		collectParser3BracketRanges(layerTokens, sqlRanges, excludeRanges);

		// Типы токенов которые нужно менять на SQL_BLOCK
		for (int i = 0; i < layerTokens.size(); i++) {
			Parser3LexerCore.CoreToken token = layerTokens.get(i);
			String type = token.type;

			// Скобки внутри SQL — если они НЕ Parser3 скобки, меняем на SQL_BLOCK
			if ("LPAREN".equals(type) || "RPAREN".equals(type) || "OP".equals(type)) {
				boolean inSql = isInsideRanges(token, sqlRanges);
				if (inSql) {
					boolean inParser3Brackets = isInsideRanges(token, excludeRanges);
					if (!inParser3Brackets) {
						// Это SQL скобка/оператор, не Parser3
						layerTokens.set(i, new Parser3LexerCore.CoreToken(token.start, token.end, "SQL_BLOCK", token.debugText));
						continue;
					}
				}
			}

			// STRING внутри SQL — меняем на SQL_BLOCK, НО только если не внутри LBRACKET
			// STRING внутри $var[...] или ^method[...] должен оставаться STRING
			if ("STRING".equals(type)) {
				boolean inSql = isInsideRanges(token, sqlRanges);
				if (!inSql) {
					continue;
				}
				// Проверяем что токен НЕ внутри Parser3-скобок (круглых И квадратных)
				boolean inParser3Brackets = isInsideRanges(token, excludeRanges);
				if (inParser3Brackets) {
					continue;
				}
				// Дополнительно проверяем что не внутри LBRACKET
				// excludeRanges содержит только круглые скобки на этом этапе,
				// поэтому нужна отдельная проверка для квадратных
				boolean inSquareBrackets = isInsideSquareBrackets(layerTokens, i);
				if (inSquareBrackets) {
					continue;
				}
				layerTokens.set(i, new Parser3LexerCore.CoreToken(token.start, token.end, "SQL_BLOCK", token.debugText));
				continue;
			}

			if ("TEMPLATE_DATA".equals(type) ||
					"LSINGLE_QUOTE".equals(type) || "RSINGLE_QUOTE".equals(type) ||
					"LDOUBLE_QUOTE".equals(type) || "RDOUBLE_QUOTE".equals(type) ||
					"NUMBER".equals(type) || "BOOLEAN".equals(type)) {

				// Проверяем что токен внутри SQL_BLOCK
				boolean inSql = isInsideRanges(token, sqlRanges);
				if (!inSql) {
					continue;
				}

				// Проверяем что токен НЕ внутри Parser3-скобок
				boolean inParserBrackets = isInsideRanges(token, excludeRanges);
				if (inParserBrackets) {
					continue;
				}

				layerTokens.set(i, new Parser3LexerCore.CoreToken(token.start, token.end, "SQL_BLOCK", token.debugText));
			}
		}
	}

	/**
	 * Финальное восстановление типов после того как ВСЕ скобки созданы.
	 * Меняет SQL_BLOCK на TEMPLATE_DATA внутри Parser3-скобок которые ВНУТРИ SQL.
	 * Также восстанавливает STRING для контента внутри LBRACKET.
	 *
	 * @param tokens токены после всех лексеров
	 * @return новый список токенов с исправленными типами
	 */
	@NotNull
	public static List<Parser3LexerCore.CoreToken> restoreSqlBlockTypeFinal(
			@NotNull List<Parser3LexerCore.CoreToken> tokens
	) {
		// Находим диапазоны SQL — ищем LBRACE после :sql
		List<int[]> sqlRanges = findSqlRanges(tokens);
		if (sqlRanges.isEmpty()) {
			return tokens;
		}

		// Собираем диапазоны Parser3-скобок ТОЛЬКО внутри SQL диапазонов
		List<int[]> excludeRanges = new ArrayList<>();
		collectBracketRangesInsideSql(tokens, "LPAREN", "RPAREN", sqlRanges, excludeRanges);
		collectBracketRangesInsideSql(tokens, "LBRACKET", "RBRACKET", sqlRanges, excludeRanges);

		// Собираем диапазоны LBRACKET (для восстановления STRING)
		List<int[]> bracketRanges = new ArrayList<>();
		collectAllBracketRanges(tokens, "LBRACKET", "RBRACKET", bracketRanges);

		// Меняем SQL_BLOCK на TEMPLATE_DATA/STRING внутри Parser3-скобок
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>(tokens.size());
		for (Parser3LexerCore.CoreToken token : tokens) {
			if ("SQL_BLOCK".equals(token.type)) {
				// Проверяем что токен внутри SQL диапазона
				boolean inSql = isInsideRanges(token, sqlRanges);

				if (inSql) {
					// Проверяем что токен внутри Parser3-скобок (которые внутри SQL)
					boolean inParserBrackets = isInsideRanges(token, excludeRanges);

					if (inParserBrackets) {
						// Внутри скобок — это Parser3 контент, не SQL
						// Если внутри LBRACKET — это STRING, иначе TEMPLATE_DATA
						boolean inSquareBrackets = isInsideRanges(token, bracketRanges);
						String newType = inSquareBrackets ? "STRING" : "TEMPLATE_DATA";
						result.add(new Parser3LexerCore.CoreToken(token.start, token.end, newType, token.debugText));
						continue;
					}
				}
			}
			result.add(token);
		}

		return result;
	}

	/**
	 * Собирает ВСЕ диапазоны парных скобок (не только внутри SQL).
	 */
	private static void collectAllBracketRanges(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull String openType,
			@NotNull String closeType,
			@NotNull List<int[]> ranges
	) {
		java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
		for (Parser3LexerCore.CoreToken t : tokens) {
			if (openType.equals(t.type)) {
				stack.push(t.start);
			} else if (closeType.equals(t.type) && !stack.isEmpty()) {
				int start = stack.pop();
				ranges.add(new int[]{start, t.end});
			}
		}
	}

	/**
	 * Находит диапазоны SQL блоков (от LBRACE после :sql или ::sql до соответствующей RBRACE).
	 * ВАЖНО: игнорирует LBRACE/RBRACE которые являются частью SQL текста
	 * (например field = '}' — здесь } это часть SQL строки).
	 */
	@NotNull
	public static List<int[]> findSqlRanges(@NotNull List<Parser3LexerCore.CoreToken> tokens) {
		List<int[]> result = new ArrayList<>();

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken t = tokens.get(i);
			// Ищем паттерн: METHOD(sql) или CONSTRUCTOR(sql) + LBRACE
			boolean isSql = ("METHOD".equals(t.type) || "CONSTRUCTOR".equals(t.type)) && "sql".equals(t.debugText);
			if (isSql) {
				// Ищем LBRACE после
				for (int j = i + 1; j < tokens.size(); j++) {
					Parser3LexerCore.CoreToken next = tokens.get(j);
					if ("LBRACE".equals(next.type)) {
						// Ищем парную RBRACE
						int depth = 1;
						int start = next.end;
						for (int k = j + 1; k < tokens.size(); k++) {
							Parser3LexerCore.CoreToken inner = tokens.get(k);

							// Пропускаем скобки которые являются частью SQL строки
							// (предыдущий токен - SQL_BLOCK который заканчивается на кавычку)
							if (isInsideSqlString(tokens, k)) {
								continue;
							}

							if ("LBRACE".equals(inner.type)) {
								depth++;
							} else if ("RBRACE".equals(inner.type)) {
								depth--;
								if (depth == 0) {
									result.add(new int[]{start, inner.start});
									break;
								}
							}
						}
						break;
					}
					if (!"COLON".equals(next.type) && !"WHITE_SPACE".equals(next.type)) {
						break;
					}
				}
			}
		}

		return result;
	}

	/**
	 * Проверяет находится ли скобка внутри SQL строки.
	 * Скобка считается частью SQL строки если:
	 * 1. Предыдущий токен — SQL_BLOCK который заканчивается на открытую кавычку
	 * 2. Следующий токен — SQL_BLOCK который начинается с закрытой кавычки
	 */
	private static boolean isInsideSqlString(List<Parser3LexerCore.CoreToken> tokens, int braceIndex) {
		if (braceIndex <= 0 || braceIndex >= tokens.size() - 1) {
			return false;
		}

		Parser3LexerCore.CoreToken prev = tokens.get(braceIndex - 1);
		Parser3LexerCore.CoreToken next = tokens.get(braceIndex + 1);

		// Оба соседа должны быть SQL_BLOCK
		if (!"SQL_BLOCK".equals(prev.type) || !"SQL_BLOCK".equals(next.type)) {
			return false;
		}

		// Предыдущий SQL_BLOCK должен заканчиваться на открытую кавычку
		String prevText = prev.debugText;
		if (prevText == null || prevText.isEmpty()) {
			return false;
		}
		char lastChar = prevText.charAt(prevText.length() - 1);
		if (lastChar != '\'' && lastChar != '"') {
			return false;
		}

		// Следующий SQL_BLOCK должен начинаться с закрытой кавычки (той же)
		String nextText = next.debugText;
		if (nextText == null || nextText.isEmpty()) {
			return false;
		}
		char firstChar = nextText.charAt(0);

		return firstChar == lastChar;
	}

	/**
	 * Проверяет находится ли токен внутри одного из диапазонов.
	 */
	private static boolean isInsideRanges(Parser3LexerCore.CoreToken token, List<int[]> ranges) {
		for (int[] range : ranges) {
			if (token.start >= range[0] && token.end <= range[1]) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Проверяет находится ли токен внутри квадратных скобок LBRACKET/RBRACKET.
	 */
	private static boolean isInsideSquareBrackets(List<Parser3LexerCore.CoreToken> tokens, int tokenIndex) {
		int depth = 0;
		for (int i = 0; i < tokenIndex; i++) {
			String type = tokens.get(i).type;
			if ("LBRACKET".equals(type)) {
				depth++;
			} else if ("RBRACKET".equals(type)) {
				depth--;
			}
		}
		return depth > 0;
	}

	/**
	 * Собирает диапазоны Parser3-скобок (после METHOD, VARIABLE, KW_*) внутри SQL.
	 */
	private static void collectParser3BracketRanges(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull List<int[]> sqlRanges,
			@NotNull List<int[]> ranges
	) {
		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken t = tokens.get(i);

			// Ищем открывающую скобку
			if (!"LPAREN".equals(t.type) && !"LBRACKET".equals(t.type)) {
				continue;
			}

			// Проверяем что скобка внутри SQL
			if (!isInsideRanges(t, sqlRanges)) {
				continue;
			}

			// Проверяем что перед скобкой есть Parser3 конструкция
			Parser3LexerCore.CoreToken prev = findPrevNonWhitespace(tokens, i);
			if (prev == null) {
				continue;
			}

			String prevType = prev.type;
			boolean isParser3 = "METHOD".equals(prevType) || "VARIABLE".equals(prevType) ||
					"IMPORTANT_VARIABLE".equals(prevType) || prevType.startsWith("KW_");

			if (!isParser3) {
				continue;
			}

			// Ищем парную закрывающую скобку
			String closeType = "LPAREN".equals(t.type) ? "RPAREN" : "RBRACKET";
			int depth = 1;
			for (int j = i + 1; j < tokens.size(); j++) {
				Parser3LexerCore.CoreToken inner = tokens.get(j);
				if (t.type.equals(inner.type)) {
					depth++;
				} else if (closeType.equals(inner.type)) {
					depth--;
					if (depth == 0) {
						ranges.add(new int[]{t.start, inner.end});
						break;
					}
				}
			}
		}
	}

	/**
	 * Собирает диапазоны парных скобок ТОЛЬКО если открывающая скобка внутри SQL диапазона
	 * И внутри скобок НЕТ вложенного SQL блока (CONSTRUCTOR/METHOD sql).
	 */
	private static void collectBracketRangesInsideSql(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull String openType,
			@NotNull String closeType,
			@NotNull List<int[]> sqlRanges,
			@NotNull List<int[]> ranges
	) {
		java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
		java.util.Deque<Integer> indexStack = new java.util.ArrayDeque<>();
		java.util.Deque<Boolean> inSqlStack = new java.util.ArrayDeque<>();

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken t = tokens.get(i);
			if (openType.equals(t.type)) {
				// Проверяем что открывающая скобка внутри SQL
				boolean inSql = false;
				for (int[] sqlRange : sqlRanges) {
					if (t.start >= sqlRange[0] && t.start < sqlRange[1]) {
						inSql = true;
						break;
					}
				}
				stack.push(t.start);
				indexStack.push(i);
				inSqlStack.push(inSql);
			} else if (closeType.equals(t.type) && !stack.isEmpty()) {
				int start = stack.pop();
				int startIndex = indexStack.pop();
				boolean wasInSql = inSqlStack.pop();
				if (wasInSql) {
					// Проверяем что внутри скобок нет вложенного SQL (::sql или :sql)
					boolean hasNestedSql = hasNestedSqlBlock(tokens, startIndex, i);
					if (!hasNestedSql) {
						ranges.add(new int[]{start, t.end});
					}
				}
			}
		}
	}

	/**
	 * Проверяет есть ли вложенный SQL блок между startIndex и endIndex.
	 */
	private static boolean hasNestedSqlBlock(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			int startIndex,
			int endIndex
	) {
		for (int i = startIndex + 1; i < endIndex; i++) {
			Parser3LexerCore.CoreToken t = tokens.get(i);
			if (("METHOD".equals(t.type) || "CONSTRUCTOR".equals(t.type)) && "sql".equals(t.debugText)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Ищет предыдущий токен который не WHITE_SPACE и не пустой TEMPLATE_DATA.
	 */
	private static Parser3LexerCore.CoreToken findPrevNonWhitespace(
			List<Parser3LexerCore.CoreToken> tokens,
			int index
	) {
		for (int i = index - 1; i >= 0; i--) {
			Parser3LexerCore.CoreToken t = tokens.get(i);
			if (!"WHITE_SPACE".equals(t.type) && !"TEMPLATE_DATA".equals(t.type)) {
				return t;
			}
			// Если TEMPLATE_DATA непустой (не пробел), прекращаем
			if ("TEMPLATE_DATA".equals(t.type) && t.debugText != null && !t.debugText.trim().isEmpty()) {
				return null;
			}
		}
		return null;
	}
}