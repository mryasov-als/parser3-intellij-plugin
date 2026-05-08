package ru.artlebedev.parser3.injector;

import ru.artlebedev.parser3.lexer.Parser3LexerCore;
import ru.artlebedev.parser3.lexer.Parser3LexerCore.CoreToken;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Единственный источник истины для очистки текста от Parser3 конструкций.
 *
 * Используется во всех инжекторах (HTML, CSS, JS, SQL) для подготовки
 * виртуального документа целевого языка.
 *
 * Логика:
 * - Токены целевого языка (HTML_DATA, CSS_DATA, JS_DATA, SQL_BLOCK) сохраняются как есть
 * - WHITE_SPACE сохраняется как есть
 * - Parser3 конструкции заменяются на ОДИН пробел (группа подряд идущих → один пробел)
 *
 * ВАЖНО: Это единственное место где реализована логика очистки!
 * Все инжекторы должны использовать этот класс.
 */
public final class Parser3TextCleaner {

	public enum SqlCleanMode {
		FULL_LEXER,
		FAST_INDEXING
	}

	// Типы токенов которые нужно сохранять (токены целевых языков)
	private static final Set<String> PRESERVE_TYPES = Set.of(
			"HTML_DATA",
			"CSS_DATA",
			"JS_DATA",
			"SQL_BLOCK",
			"WHITE_SPACE"
	);

	// Типы токенов для SQL где DOT между SQL_BLOCK сохраняется (pp.person_id)
	private static final String SQL_BLOCK_TYPE = "SQL_BLOCK";
	private static final String DOT_TYPE = "DOT";
	private static final String WHITE_SPACE_TYPE = "WHITE_SPACE";

	/**
	 * Очищает текст от Parser3 конструкций.
	 *
	 * @param text исходный текст
	 * @return очищенный текст где Parser3 конструкции заменены пробелами
	 */
	@NotNull
	public static String clean(@NotNull String text) {
		return clean(text, null);
	}

	/**
	 * Очищает текст от Parser3 конструкций, сохраняя токены указанного типа.
	 *
	 * @param text исходный текст
	 * @param targetType тип токенов целевого языка (HTML_DATA, CSS_DATA, JS_DATA, SQL_BLOCK)
	 *                   или null для сохранения всех известных типов
	 * @return очищенный текст
	 */
	@NotNull
	public static String clean(@NotNull String text, String targetType) {
		if (text.isEmpty()) {
			return "";
		}

		List<CoreToken> tokens = Parser3LexerCore.tokenize(text, 0, text.length());
		return buildCleanedText(text, tokens, targetType);
	}

	/**
	 * Строит очищенный текст из списка токенов.
	 *
	 * @param originalText исходный текст
	 * @param tokens список токенов
	 * @param targetType тип токенов целевого языка или null
	 * @return очищенный текст
	 */
	@NotNull
	private static String buildCleanedText(
			@NotNull String originalText,
			@NotNull List<CoreToken> tokens,
			String targetType
	) {
		StringBuilder result = new StringBuilder();
		boolean lastWasParser3 = false;

		for (int i = 0; i < tokens.size(); i++) {
			CoreToken token = tokens.get(i);
			String type = token.type;
			String tokenText = originalText.substring(token.start, token.end);

			// WHITE_SPACE — всегда сохраняем как есть
			if (WHITE_SPACE_TYPE.equals(type)) {
				result.append(tokenText);
				lastWasParser3 = false;
				continue;
			}

			// Проверяем, нужно ли сохранять этот токен
			boolean shouldPreserve = false;

			if (targetType != null) {
				// Режим для конкретного типа
				shouldPreserve = targetType.equals(type);
			} else {
				// Режим для всех известных типов
				shouldPreserve = PRESERVE_TYPES.contains(type);
			}

			// Специальная обработка DOT для SQL: сохраняем только если между SQL_BLOCK
			if (DOT_TYPE.equals(type) && (targetType == null || SQL_BLOCK_TYPE.equals(targetType))) {
				boolean prevIsSqlBlock = i > 0 && SQL_BLOCK_TYPE.equals(tokens.get(i - 1).type);
				boolean nextIsSqlBlock = i < tokens.size() - 1 && SQL_BLOCK_TYPE.equals(tokens.get(i + 1).type);

				if (prevIsSqlBlock && nextIsSqlBlock) {
					// SQL точка (pp.person_id) — сохраняем
					result.append(tokenText);
					lastWasParser3 = false;
					continue;
				}
				// Иначе — это Parser3 точка, заменяем на пробел (ниже)
			}

			if (shouldPreserve) {
				// Токен целевого языка — сохраняем как есть
				result.append(tokenText);
				lastWasParser3 = false;
			} else {
				// Parser3 конструкция — группу заменяем на ОДИН пробел
				if (!lastWasParser3) {
					result.append(" ");
					lastWasParser3 = true;
				}
			}
		}

		return result.toString();
	}

	/**
	 * Очищает текст для HTML инжекции.
	 * Сохраняет HTML_DATA токены.
	 */
	@NotNull
	public static String cleanForHtml(@NotNull String text) {
		return clean(text, "HTML_DATA");
	}

	/**
	 * Очищает текст для CSS инжекции.
	 * Сохраняет CSS_DATA токены.
	 */
	@NotNull
	public static String cleanForCss(@NotNull String text) {
		return clean(text, "CSS_DATA");
	}

	/**
	 * Очищает текст для JS инжекции.
	 * Сохраняет JS_DATA токены.
	 */
	@NotNull
	public static String cleanForJs(@NotNull String text) {
		return clean(text, "JS_DATA");
	}

	/**
	 * Очищает текст для SQL инжекции.
	 * Сохраняет SQL_BLOCK токены и DOT между ними.
	 */
	@NotNull
	public static String cleanForSql(@NotNull String text) {
		return clean(text, "SQL_BLOCK");
	}

	/**
	 * Очищает ПЛОСКОЕ SQL-содержимое (без внешнего ^...sql{...}) в двух режимах:
	 * - FULL_LEXER: через полный lexer, как в инжекторах
	 * - FAST_INDEXING: через быстрый scrubber для индексации
	 */
	@NotNull
	public static String cleanSqlContent(@NotNull String sqlContent, @NotNull SqlCleanMode mode) {
		if (sqlContent.isEmpty()) {
			return "";
		}

		if (mode == SqlCleanMode.FAST_INDEXING) {
			return Parser3SqlTextCleanerCore.cleanSqlContent(sqlContent);
		}

		String startMarker = "XSQLSTART ";
		String endMarker = " XSQLEND";
		String wrappedSql = "^void:sql{" + startMarker + sqlContent + endMarker + "}";
		String cleanedWrapped = cleanForSql(wrappedSql);
		int startPos = cleanedWrapped.indexOf(startMarker);
		int endPos = cleanedWrapped.indexOf(endMarker);
		if (startPos >= 0 && endPos > startPos) {
			return cleanedWrapped.substring(startPos + startMarker.length(), endPos);
		}

		return sqlContent;
	}

	/**
	 * Возвращает детальную информацию о токенах для отладки.
	 *
	 * @param text исходный текст
	 * @return строка с информацией о каждом токене
	 */
	@NotNull
	public static String debugTokens(@NotNull String text) {
		if (text.isEmpty()) {
			return "(empty text)";
		}

		List<CoreToken> tokens = Parser3LexerCore.tokenize(text, 0, text.length());
		StringBuilder sb = new StringBuilder();

		sb.append("=== TOKENS (").append(tokens.size()).append(") ===\n");

		for (CoreToken token : tokens) {
			String fragment = text.substring(token.start, token.end)
					.replace("\n", "\\n")
					.replace("\r", "\\r")
					.replace("\t", "\\t");

			sb.append(String.format(
					"[%5d, %5d] %-15s '%s'%n",
					token.start,
					token.end,
					token.type,
					fragment
			));
		}

		return sb.toString();
	}
}
