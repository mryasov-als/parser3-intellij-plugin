package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * Core-слой для выделения скобок:
 * - LBRACE / RBRACE
 * - LBRACKET / RBRACKET
 * - SEMICOLON
 *
 * На вход принимает TEMPLATE_DATA и SQL_BLOCK токены.
 * Для SQL_BLOCK учитывает что скобки внутри строк '...' и "..." не разбираются.
 *
 * ВАЖНО: Круглые скобки (LPAREN/RPAREN) НЕ обрабатываются здесь!
 * Они обрабатываются в Parser3MethodCallAndVariblesLexerCore.lexParensContent(),
 * потому что являются Parser3-выражениями только после $var или ^method.
 */
public final class Parser3BracketLexerCore {

	private Parser3BracketLexerCore() {
	}

	/**
	 * Основной метод токенизации.
	 * @param text исходный текст
	 * @param templateTokens токены TEMPLATE_DATA и SQL_BLOCK для обработки
	 * @return список токенов скобок
	 */
	public static @NotNull List<Parser3LexerCore.CoreToken> tokenize(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens
	) {
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();

		for (Parser3LexerCore.CoreToken template : templateTokens) {
			boolean isSqlBlock = "SQL_BLOCK".equals(template.type);
			lexBracketsInRange(text, template.start, template.end, result, isSqlBlock);
		}

		return result;
	}

	private static void lexBracketsInRange(
			@NotNull CharSequence text,
			int rangeStart,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> result,
			boolean isSqlBlock
	) {
		int length = text.length();
		if (rangeStart < 0) {
			rangeStart = 0;
		}
		if (rangeEnd > length) {
			rangeEnd = length;
		}

		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;

		for (int i = rangeStart; i < rangeEnd; i++) {
			char c = text.charAt(i);

			// Для SQL_BLOCK отслеживаем кавычки
			if (isSqlBlock) {
				if (c == '\'' && !inDoubleQuote && !isEscaped(text, i, rangeStart)) {
					inSingleQuote = !inSingleQuote;
					continue;
				}
				if (c == '"' && !inSingleQuote && !isEscaped(text, i, rangeStart)) {
					inDoubleQuote = !inDoubleQuote;
					continue;
				}
				// Внутри SQL-строки скобки не разбираем
				if (inSingleQuote || inDoubleQuote) {
					continue;
				}
			}

			String type = null;

			// Экранирование скобок: если перед скобкой стоит '^',
			// то эта скобка считается частью TEMPLATE_DATA и
			// не превращается в LBRACE/RBRACE/LBRACKET/RBRACKET.
			// То же самое для ; — ^; экранирован, но ^^; — это ^^ + неэкранированный ;
			if (i > rangeStart) {
				char prev = text.charAt(i - 1);
				if (prev == '^' && (c == '{' || c == '}' || c == '[' || c == ']' || c == ';')) {
					// Но проверяем двойное экранирование: ^^; — ; не экранирован
					if (c == ';' && i > rangeStart + 1 && text.charAt(i - 2) == '^') {
						// ^^; — не экранирован, обрабатываем ; ниже
					} else {
						continue;
					}
				}
			}

			switch (c) {
				// Круглые скобки НЕ обрабатываем — они в Parser3MethodCallAndVariblesLexerCore
				case '{':
					type = "LBRACE";
					break;
				case '}':
					type = "RBRACE";
					break;
				case '[':
					type = "LBRACKET";
					break;
				case ']':
					type = "RBRACKET";
					break;
				case ';':
					type = "SEMICOLON";
					break;
				default:
					break;
			}

			if (type != null) {
				String debug = substringSafely(text, i, i + 1);
				result.add(new Parser3LexerCore.CoreToken(i, i + 1, type, debug));
			}
		}
	}

	/**
	 * Проверяет экранирование: предыдущий символ — ^
	 */
	private static boolean isEscaped(@NotNull CharSequence text, int pos, int rangeStart) {
		return pos > rangeStart && text.charAt(pos - 1) == '^';
	}
}