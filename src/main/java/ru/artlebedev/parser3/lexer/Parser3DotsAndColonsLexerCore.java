package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * Core-слой для подсветки точек и двоеточий между идентификаторами.
 *
 * Работает поверх TEMPLATE_DATA после Parser3MethodCallAndVariblesLexerCore.
 * Ищет в TEMPLATE_DATA символы '.', ':' и '::' и, если они находятся
 * между «похожими на идентификаторы» кусками (имена, скобки и т.п.),
 * помечает их как DOT или COLON.
 */
public final class Parser3DotsAndColonsLexerCore {

	@NotNull
	public static List<Parser3LexerCore.CoreToken> tokenize(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens
	) {
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>();
		if (templateTokens.isEmpty()) {
			return result;
		}

		int length = text.length();

		for (Parser3LexerCore.CoreToken template : templateTokens) {
			int rangeStart = template.start;
			int rangeEnd = Math.min(template.end, length);
			int i = rangeStart;

			while (i < rangeEnd && i < length) {
				char c = text.charAt(i);

				if (c == '.' || c == ':') {
					int runStart = i;
					int runEnd = i + 1;

					// Склеиваем '::' в один диапазон, чтобы можно было покрасить обе двоеточия
					if (c == ':' && runEnd < rangeEnd && runEnd < length && text.charAt(runEnd) == ':') {
						runEnd++;
					}

					if (isBetweenIdLike(text, runStart, runEnd)) {
						String type = (c == '.') ? "DOT" : "COLON";
						result.add(new Parser3LexerCore.CoreToken(
								runStart,
								runEnd,
								type,
								substringSafely(text, runStart, runEnd)
						));
					}

					i = runEnd;
					continue;
				}

				i++;
			}
		}

		return result;
	}

	/**
	 * Проверяет, что диапазон с точкой/двоеточиями находится МЕЖДУ
	 * «похожими на идентификаторы» символами. Для '::' ищем ближайшие
	 * не-':' и не-пробельные символы слева и справа.
	 *
	 * ОПТИМИЗАЦИЯ: ограничиваем поиск 50 символами в каждую сторону.
	 */
	private static boolean isBetweenIdLike(@NotNull CharSequence text, int start, int end) {
		int length = text.length();

		// Ищем слева ближайший не-пробельный и не ':' / '.' в пределах строки
		// ОПТИМИЗАЦИЯ: не ищем дальше 50 символов
		int left = start - 1;
		int leftLimit = Math.max(0, start - 50);
		while (left >= leftLimit) {
			char ch = text.charAt(left);
			if (ch == '\n' || ch == '\r') {
				return false;
			}
			if (Character.isWhitespace(ch) || ch == ':' || ch == '.') {
				left--;
				continue;
			}
			break;
		}
		if (left < leftLimit) {
			return false;
		}

		// Ищем справа ближайший не-пробельный и не ':' / '.' в пределах строки
		// ОПТИМИЗАЦИЯ: не ищем дальше 50 символов
		int right = end;
		int rightLimit = Math.min(length, end + 50);
		while (right < rightLimit) {
			char ch = text.charAt(right);
			if (ch == '\n' || ch == '\r') {
				return false;
			}
			if (Character.isWhitespace(ch) || ch == ':' || ch == '.') {
				right++;
				continue;
			}
			break;
		}
		if (right >= rightLimit) {
			return false;
		}

		char lc = text.charAt(left);
		char rc = text.charAt(right);

		// слева и справа должен быть идентификатороподобный символ:
		// буква/цифра/подчёркивание или «ограничители» переменных/методов
		return isIdentLikeLeft(lc) && isIdentLikeRight(rc);
	}

	private static boolean isIdentLikeLeft(char c) {
		return Character.isLetterOrDigit(c)
				|| c == '_'
				|| c == ']'
				|| c == ')'
				|| c == '$'
				|| c == '^';
	}

	private static boolean isIdentLikeRight(char c) {
		return Character.isLetterOrDigit(c)
				|| c == '_'
				|| c == '['
				|| c == '('
				|| c == '$'
				|| c == '^';
	}
}