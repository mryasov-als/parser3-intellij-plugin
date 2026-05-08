package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Лексер для обработки директив @USE, @CLASS, @BASE.
 *
 * После @USE: пути к файлам (TEMPLATE_DATA + DOT + TEMPLATE_DATA) → USE_PATH
 * После @CLASS и @BASE: имя класса (TEMPLATE_DATA) → CLASS_NAME
 *
 * Этот лексер работает в конце пайплайна, когда уже есть токены SPECIAL_METHOD.
 */
public final class Parser3DirectiveLexerCore {

	private Parser3DirectiveLexerCore() {
	}

	/**
	 * Обрабатывает токены после директив @USE, @CLASS, @BASE.
	 */
	@NotNull
	public static List<Parser3LexerCore.CoreToken> tokenize(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens) {

		// Этот метод получает только TEMPLATE_DATA токены
		// Нам нужен полный список токенов для анализа контекста
		// Поэтому используем другой подход - обрабатываем полный список
		return templateTokens;
	}

	/**
	 * Постобработка полного списка токенов.
	 * Вызывается после всех остальных лексеров.
	 */
	@NotNull
	public static List<Parser3LexerCore.CoreToken> postProcess(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> tokens) {

		if (tokens.isEmpty()) {
			return tokens;
		}

		List<Parser3LexerCore.CoreToken> result = new ArrayList<>(tokens.size());

		// Состояние: какую директиву мы сейчас обрабатываем
		DirectiveState state = DirectiveState.NONE;
		int directiveLineEnd = -1; // Конец строки с директивой

		// Флаг: мы внутри ^use[...]
		boolean inUseMethod = false;

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);

			// Проверяем SPECIAL_METHOD директивы
			if ("SPECIAL_METHOD".equals(token.type)) {
				String tokenText = Parser3LexerCore.substringSafely(text, token.start, token.end);
				if (tokenText != null) {
					if (tokenText.equals("@USE")) {
						state = DirectiveState.USE;
						directiveLineEnd = findNextDirectiveOrEnd(text, tokens, i);
					} else if (tokenText.equals("@CLASS")) {
						state = DirectiveState.CLASS;
						directiveLineEnd = findEndOfNextNonEmptyLine(text, token.end);
					} else if (tokenText.equals("@BASE")) {
						state = DirectiveState.BASE;
						directiveLineEnd = findEndOfNextNonEmptyLine(text, token.end);
					} else if (tokenText.equals("@OPTIONS")) {
						state = DirectiveState.OPTIONS;
						directiveLineEnd = findNextDirectiveOrEnd(text, tokens, i);
					} else if (tokenText.startsWith("@")) {
						// Любая другая директива завершает @USE/@OPTIONS блок
						state = DirectiveState.NONE;
					}
				}
				result.add(token);
				continue;
			}

			// Проверяем KW_USE (^use)
			if ("KW_USE".equals(token.type)) {
				inUseMethod = true;
				result.add(token);
				continue;
			}

			// Если внутри ^use[] и видим LBRACKET — продолжаем
			if (inUseMethod && "LBRACKET".equals(token.type)) {
				result.add(token);
				continue;
			}

			// Если внутри ^use[] и видим STRING — меняем на USE_PATH
			if (inUseMethod && "STRING".equals(token.type)) {
				String debug = Parser3LexerCore.substringSafely(text, token.start, token.end);
				result.add(new Parser3LexerCore.CoreToken(token.start, token.end, "USE_PATH", debug));
				continue;
			}

			// Если внутри ^use[] и видим RBRACKET — сбрасываем флаг
			if (inUseMethod && "RBRACKET".equals(token.type)) {
				inUseMethod = false;
				result.add(token);
				continue;
			}

			// Любой другой токен сбрасывает inUseMethod (если это не пробел)
			if (inUseMethod && !"WHITE_SPACE".equals(token.type) && !"LBRACKET".equals(token.type)) {
				inUseMethod = false;
			}

			// Обработка токенов в зависимости от состояния
			if (state == DirectiveState.USE && token.start < directiveLineEnd) {
				// После @USE: объединяем TEMPLATE_DATA и DOT в USE_PATH
				if ("TEMPLATE_DATA".equals(token.type) || "DOT".equals(token.type)) {
					// Проверяем, можно ли объединить с предыдущим USE_PATH
					if (!result.isEmpty()) {
						Parser3LexerCore.CoreToken last = result.get(result.size() - 1);
						if ("USE_PATH".equals(last.type) && last.end == token.start) {
							// Объединяем с предыдущим
							result.remove(result.size() - 1);
							String debug = Parser3LexerCore.substringSafely(text, last.start, token.end);
							result.add(new Parser3LexerCore.CoreToken(last.start, token.end, "USE_PATH", debug));
							continue;
						}
					}
					// Создаём новый USE_PATH токен
					String debug = Parser3LexerCore.substringSafely(text, token.start, token.end);
					result.add(new Parser3LexerCore.CoreToken(token.start, token.end, "USE_PATH", debug));
					continue;
				}
				// WHITE_SPACE с переводом строки - оставляем, но продолжаем в том же состоянии
				if ("WHITE_SPACE".equals(token.type)) {
					result.add(token);
					continue;
				}
			}

			if ((state == DirectiveState.CLASS || state == DirectiveState.BASE)
					&& token.start < directiveLineEnd) {
				// После @CLASS или @BASE: первый TEMPLATE_DATA → VARIABLE
				if ("TEMPLATE_DATA".equals(token.type)) {
					String debug = Parser3LexerCore.substringSafely(text, token.start, token.end);
					result.add(new Parser3LexerCore.CoreToken(token.start, token.end, "VARIABLE", debug));
					// После имени класса сбрасываем состояние
					state = DirectiveState.NONE;
					continue;
				}
				// WHITE_SPACE - оставляем
				if ("WHITE_SPACE".equals(token.type)) {
					result.add(token);
					continue;
				}
			}

			// Обработка @OPTIONS — только конкретные опции помечаем как KEYWORD
			if (state == DirectiveState.OPTIONS && token.start < directiveLineEnd) {
				if ("TEMPLATE_DATA".equals(token.type)) {
					String tokenText = Parser3LexerCore.substringSafely(text, token.start, token.end);
					// Только эти 4 слова (с учётом регистра) — KEYWORD, остальное — TEMPLATE_DATA
					if ("locals".equals(tokenText) || "partial".equals(tokenText) ||
							"dynamic".equals(tokenText) || "static".equals(tokenText)) {
						result.add(new Parser3LexerCore.CoreToken(token.start, token.end, "KEYWORD", tokenText));
					} else {
						result.add(token);
					}
					continue;
				}
				// WHITE_SPACE — оставляем
				if ("WHITE_SPACE".equals(token.type)) {
					result.add(token);
					continue;
				}
			}

			// Все остальные токены - без изменений
			result.add(token);
		}

		return result;
	}

	/**
	 * Находит конец следующей непустой строки после offset.
	 * Используется для @CLASS и @BASE (имя класса на следующей строке).
	 */
	private static int findEndOfNextNonEmptyLine(@NotNull CharSequence text, int offset) {
		int length = text.length();
		int i = offset;

		// Пропускаем текущую строку (до \n)
		while (i < length && text.charAt(i) != '\n') {
			i++;
		}
		// Пропускаем \n
		if (i < length && text.charAt(i) == '\n') {
			i++;
		}
		// Пропускаем \r если есть
		if (i < length && text.charAt(i) == '\r') {
			i++;
		}

		// Пропускаем пустые строки
		while (i < length) {
			int lineStart = i;
			// Находим конец строки
			while (i < length && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
				i++;
			}
			// Проверяем, не пустая ли строка
			String line = text.subSequence(lineStart, i).toString().trim();
			if (!line.isEmpty()) {
				// Нашли непустую строку, возвращаем её конец
				return i;
			}
			// Пропускаем перевод строки
			if (i < length && text.charAt(i) == '\r') {
				i++;
			}
			if (i < length && text.charAt(i) == '\n') {
				i++;
			}
		}

		return length;
	}

	/**
	 * Находит позицию следующей директивы (@...) или конец текста.
	 * Используется для @USE (пути идут до следующей директивы).
	 */
	private static int findNextDirectiveOrEnd(
			@NotNull CharSequence text,
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			int currentIndex) {

		// Ищем следующий SPECIAL_METHOD или DEFINE_METHOD после текущего
		for (int i = currentIndex + 1; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);
			if ("SPECIAL_METHOD".equals(token.type) || "DEFINE_METHOD".equals(token.type)) {
				return token.start;
			}
		}

		return text.length();
	}

	private enum DirectiveState {
		NONE,
		USE,
		CLASS,
		BASE,
		OPTIONS
	}
}