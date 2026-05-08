package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * Постобработка токенов.
 *
 * Здесь собраны функции финальной обработки токенов,
 * которые нужно выполнить после основной разметки.
 */
public class Parser3PostProcessor {

	/**
	 * Применяет все постобработки.
	 */
	@NotNull
	public static List<Parser3LexerCore.CoreToken> process(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull CharSequence text
	) {
		// DOT между SQL_BLOCK превращаем в SQL_BLOCK
		tokens = mergeSqlDots(tokens, text);

		// Объединяем смежные SQL_BLOCK в один
		tokens = mergeAdjacentSqlBlocks(tokens, text);

		// HTML_DATA обработка вынесена в Parser3LexerCore.tokenize()
		// и вызывается отдельно через Parser3PostProcessorHtml.markHtmlData()

		return tokens;
	}

	/**
	 * Объединяет смежные SQL_BLOCK токены в один.
	 * Например: SQL_BLOCK('p') + SQL_BLOCK('.') + SQL_BLOCK('name') → SQL_BLOCK('p.name')
	 */
	@NotNull
	private static List<Parser3LexerCore.CoreToken> mergeAdjacentSqlBlocks(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull CharSequence text
	) {
		if (tokens.size() < 2) {
			return tokens;
		}

		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();

		int i = 0;
		while (i < tokens.size()) {
			Parser3LexerCore.CoreToken token = tokens.get(i);

			if (!"SQL_BLOCK".equals(token.type)) {
				result.add(token);
				i++;
				continue;
			}

			// Нашли SQL_BLOCK — собираем все смежные
			int start = token.start;
			int end = token.end;

			while (i + 1 < tokens.size()) {
				Parser3LexerCore.CoreToken next = tokens.get(i + 1);
				// Смежные = конец текущего совпадает с началом следующего
				if ("SQL_BLOCK".equals(next.type) && end == next.start) {
					end = next.end;
					i++;
				} else {
					break;
				}
			}

			// Создаём объединённый токен
			String mergedText = substringSafely(text, start, end);
			result.add(new Parser3LexerCore.CoreToken(start, end, "SQL_BLOCK", mergedText));
			i++;
		}

		return result;
	}

	/**
	 * Объединяет DOT между SQL_BLOCK в SQL_BLOCK.
	 * Нужно чтобы pp.person_id было цельным идентификатором для SQL-плагина.
	 *
	 * Логика: если видим паттерн SQL_BLOCK + DOT + SQL_BLOCK, где DOT это точка "." —
	 * заменяем DOT на SQL_BLOCK.
	 */
	@NotNull
	private static List<Parser3LexerCore.CoreToken> mergeSqlDots(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull CharSequence text
	) {
		if (tokens.size() < 3) {
			return tokens;
		}

		List<Parser3LexerCore.CoreToken> result = new ArrayList<>(tokens.size());

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);

			// Проверяем паттерн: SQL_BLOCK + DOT + SQL_BLOCK
			if ("DOT".equals(token.type) && i > 0 && i < tokens.size() - 1) {
				Parser3LexerCore.CoreToken prev = tokens.get(i - 1);
				Parser3LexerCore.CoreToken next = tokens.get(i + 1);

				// DOT должен быть смежным с обоими SQL_BLOCK (без пробелов между ними)
				if ("SQL_BLOCK".equals(prev.type) && "SQL_BLOCK".equals(next.type)
						&& prev.end == token.start && token.end == next.start) {
					// Заменяем DOT на SQL_BLOCK
					String dotText = substringSafely(text, token.start, token.end);
					result.add(new Parser3LexerCore.CoreToken(token.start, token.end, "SQL_BLOCK", dotText));
					continue;
				}
			}

			result.add(token);
		}

		return result;
	}
}