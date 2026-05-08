package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.P3MethodDeclarationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Постобработка токенов для HTML.
 *
 * Помечает TEMPLATE_DATA как HTML_DATA в методах, содержащих HTML теги.
 * Также выделяет CSS_DATA и JS_DATA для содержимого <style> и <script>.
 */
public class Parser3PostProcessorHtml {

	/**
	 * Помечает TEMPLATE_DATA как HTML_DATA в методах, содержащих HTML теги.
	 * Вызывается ДО splitWhitespaceTokens.
	 */
	@NotNull
	public static List<Parser3LexerCore.CoreToken> markHtmlInMethods(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull CharSequence text
	) {
		if (tokens.isEmpty()) {
			return tokens;
		}

		List<Parser3LexerCore.CoreToken> result = new ArrayList<>(tokens.size());

		// Индексы начала каждого метода (включая неявный MAIN в начале)
		List<Integer> methodBoundaries = findMethodBoundaries(tokens);

		int currentMethodIdx = 0;
		int nextMethodStart = methodBoundaries.size() > 1 ? methodBoundaries.get(1) : tokens.size();

		boolean htmlModeActive = false;
		int firstHtmlTagIdx = findFirstHtmlTagInMethod(tokens, text, 0, nextMethodStart);

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);

			// Проверяем переход к следующему методу
			if (currentMethodIdx + 1 < methodBoundaries.size() && i >= methodBoundaries.get(currentMethodIdx + 1)) {
				currentMethodIdx++;
				nextMethodStart = currentMethodIdx + 1 < methodBoundaries.size()
						? methodBoundaries.get(currentMethodIdx + 1)
						: tokens.size();
				htmlModeActive = false;
				firstHtmlTagIdx = findFirstHtmlTagInMethod(tokens, text, i, nextMethodStart);
			}

			// Активируем HTML режим если дошли до первого тега
			if (!htmlModeActive && firstHtmlTagIdx >= 0 && i >= firstHtmlTagIdx) {
				htmlModeActive = true;
			}

			// Преобразуем TEMPLATE_DATA в HTML_DATA если режим активен
			if (htmlModeActive && "TEMPLATE_DATA".equals(token.type)) {
				result.add(new Parser3LexerCore.CoreToken(
						token.start, token.end, "HTML_DATA", token.debugText
				));
			} else {
				result.add(token);
			}
		}

		return result;
	}

	/**
	 * Конвертирует COLON и SEMICOLON внутри HTML тегов обратно в HTML_DATA.
	 * Вызывается ПОСЛЕ splitWhitespaceTokens.
	 *
	 * Проблема: лексер разбивает style="color: red; font" на части по : и ;
	 * Это ломает автокомплит в inline CSS. Здесь мы исправляем это.
	 */
	@NotNull
	public static List<Parser3LexerCore.CoreToken> fixColonsInHtmlTags(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull CharSequence text
	) {
		if (tokens.isEmpty()) {
			return tokens;
		}

		List<Parser3LexerCore.CoreToken> result = new ArrayList<>(tokens.size());

		// Флаг: мы внутри HTML тега (между < и >)
		boolean insideHtmlTag = false;

		for (Parser3LexerCore.CoreToken token : tokens) {
			String tokenType = token.type;
			String tokenText = token.debugText;

			// Проверяем HTML_DATA на открывающие/закрывающие теги
			if ("HTML_DATA".equals(tokenType) && tokenText != null) {
				// Если токен содержит < — мы входим в тег
				// Если токен содержит > — мы выходим из тега
				// Если токен содержит и < и > — зависит от порядка
				int ltPos = tokenText.lastIndexOf('<');
				int gtPos = tokenText.lastIndexOf('>');

				if (ltPos >= 0 && gtPos >= 0) {
					// Есть оба — смотрим что последнее
					insideHtmlTag = ltPos > gtPos;
				} else if (ltPos >= 0) {
					// Только < — входим в тег
					insideHtmlTag = true;
				} else if (gtPos >= 0) {
					// Только > — выходим из тега
					insideHtmlTag = false;
				}

				result.add(token);
				continue;
			}

			// Если внутри HTML тега — конвертируем COLON и SEMICOLON в HTML_DATA
			if (insideHtmlTag) {
				if ("COLON".equals(tokenType) || "SEMICOLON".equals(tokenType)) {
					result.add(new Parser3LexerCore.CoreToken(
							token.start, token.end, "HTML_DATA", token.debugText
					));
					continue;
				}
			}

			result.add(token);
		}

		return result;
	}

	/**
	 * Помечает содержимое <style> как CSS_DATA и <script> как JS_DATA.
	 * Работает с потоком токенов. Вызывается ПОСЛЕ splitWhitespaceTokens.
	 *
	 * Правила фильтрации Parser3 конструкций:
	 *
	 * ОСТАВЛЯЕМ (конвертируем в CSS_DATA/JS_DATA):
	 * - Содержимое {} после ] или ) — это вывод: ^method[]{ВЫВОД}
	 * - Содержимое {} после METHOD без [] — это вывод: ^method{ВЫВОД}
	 *
	 * ВЫРЕЗАЕМ (НЕ конвертируем):
	 * - Содержимое [] — это параметры: ^method[параметры]
	 * - Содержимое () — это параметры: ^method(параметры)
	 * - Переменные полностью: $var, $var{}, $var[], $var(), $var.field
	 * - Сами вызовы методов: ^method, ^obj.method
	 */
	@NotNull
	public static List<Parser3LexerCore.CoreToken> markStyleScriptContent(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull CharSequence text
	) {
		List<Parser3LexerCore.CoreToken> result = new ArrayList<>(tokens.size());

		// Текущий режим: null = HTML, "CSS_DATA" = внутри style, "JS_DATA" = внутри script
		String currentMode = null;

		// Стек типов скобок:
		// "PARAM" — [] или () — параметры, всё внутри вырезаем
		// "VAR_BRACE" — $var{} — присваивание, всё внутри вырезаем
		// "OUTPUT_BRACE" — ^method[]{} — блок вывода, содержимое CSS/JS
		// "CSS_BRACE" — CSS/JS фигурная скобка .item {}
		java.util.Deque<String> braceStack = new java.util.ArrayDeque<>();

		// Предыдущий значимый тип токена (не WHITE_SPACE)
		String prevSignificant = null;

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);
			String tokenText = token.debugText;
			String tokenType = token.type;

			if ("DEFINE_METHOD".equals(tokenType) || "SPECIAL_METHOD".equals(tokenType)) {
				currentMode = null;
				braceStack.clear();
				result.add(token);
				prevSignificant = tokenType;
				continue;
			}

			// === Проверяем открывающие/закрывающие теги style/script ===
			if ("HTML_DATA".equals(tokenType) && tokenText != null) {
				String lower = tokenText.toLowerCase();

				if (lower.startsWith("<style")) {
					if (splitOpeningTagToken(result, token, "CSS_DATA", "</style")) {
						currentMode = null;
					} else {
						currentMode = "CSS_DATA";
					}
					braceStack.clear();
					prevSignificant = null;
					continue;
				}

				if (lower.startsWith("</style")) {
					currentMode = null;
					result.add(token);
					continue;
				}

				if (lower.startsWith("<script") && !lower.contains("src=")) {
					if (splitOpeningTagToken(result, token, "JS_DATA", "</script")) {
						currentMode = null;
					} else {
						currentMode = "JS_DATA";
					}
					braceStack.clear();
					prevSignificant = null;
					continue;
				}

				if (lower.startsWith("</script")) {
					currentMode = null;
					result.add(token);
					continue;
				}
			}

			// Если не внутри style/script — просто добавляем
			if (currentMode == null) {
				result.add(token);
				continue;
			}

			// === Отслеживаем скобки с помощью стека ===

			// [ — открытие параметров только если после METHOD/VARIABLE/KEYWORD
			if ("LBRACKET".equals(tokenType)) {
				if (isParser3CallContext(prevSignificant)) {
					// После ^method или $var — это Parser3 параметры
					braceStack.push("PARAM");
					result.add(token);
				} else {
					// Иначе это JS/CSS скобка — конвертируем
					result.add(new Parser3LexerCore.CoreToken(
							token.start, token.end, currentMode, token.debugText
					));
				}
				prevSignificant = tokenType;
				continue;
			}

			// ] — закрытие параметров или JS/CSS скобка
			if ("RBRACKET".equals(tokenType)) {
				if (!braceStack.isEmpty() && "PARAM".equals(braceStack.peek())) {
					braceStack.pop();
					result.add(token);
				} else {
					// Не было открывающей [ для PARAM — это JS/CSS
					result.add(new Parser3LexerCore.CoreToken(
							token.start, token.end, currentMode, token.debugText
					));
				}
				prevSignificant = tokenType;
				continue;
			}

			// ( — открытие параметров только если после METHOD/VARIABLE/KEYWORD
			if ("LPAREN".equals(tokenType)) {
				if (isParser3CallContext(prevSignificant)) {
					// После ^method или $var — это Parser3 параметры
					braceStack.push("PARAM");
					result.add(token);
				} else {
					// Иначе это JS/CSS скобка — конвертируем
					result.add(new Parser3LexerCore.CoreToken(
							token.start, token.end, currentMode, token.debugText
					));
				}
				prevSignificant = tokenType;
				continue;
			}

			// ) — закрытие параметров или JS/CSS скобка
			if ("RPAREN".equals(tokenType)) {
				if (!braceStack.isEmpty() && "PARAM".equals(braceStack.peek())) {
					braceStack.pop();
					result.add(token);
				} else {
					// Не было открывающей ( для PARAM — это JS/CSS
					result.add(new Parser3LexerCore.CoreToken(
							token.start, token.end, currentMode, token.debugText
					));
				}
				prevSignificant = tokenType;
				continue;
			}

			// { — может быть разных типов
			if ("LBRACE".equals(tokenType)) {
				// { после $var — присваивание. Нужна последовательность DOLLAR_VARIABLE, VARIABLE, {
				// или DOLLAR_VARIABLE, VARIABLE, DOT, VARIABLE, ... , {
				if (isPrecededByVariable(tokens, i)) {
					braceStack.push("VAR_BRACE");
					result.add(token);
					prevSignificant = tokenType;
					continue;
				}

				// { после ] или ) — Parser3 блок вывода, содержимое CSS/JS
				if ("RBRACKET".equals(prevSignificant) || "RPAREN".equals(prevSignificant)) {
					braceStack.push("OUTPUT_BRACE");
					result.add(token);
					prevSignificant = tokenType;
					continue;
				}

				// { внутри параметров или присваивания — Parser3 скобка
				if (isInsideParser3(braceStack)) {
					braceStack.push("PARAM"); // вложенная Parser3 скобка
					result.add(token);
					prevSignificant = tokenType;
					continue;
				}

				// Иначе это CSS/JS скобка — конвертируем
				braceStack.push("CSS_BRACE");
				result.add(new Parser3LexerCore.CoreToken(
						token.start, token.end, currentMode, token.debugText
				));
				prevSignificant = tokenType;
				continue;
			}

			// } — закрытие скобки соответствующего типа
			if ("RBRACE".equals(tokenType)) {
				if (!braceStack.isEmpty()) {
					String braceType = braceStack.pop();

					if ("CSS_BRACE".equals(braceType)) {
						// Закрытие CSS/JS скобки — конвертируем
						result.add(new Parser3LexerCore.CoreToken(
								token.start, token.end, currentMode, token.debugText
						));
					} else {
						// Закрытие Parser3 скобки — не конвертируем
						result.add(token);
					}
				} else {
					// Стек пустой — это должна быть CSS/JS скобка
					result.add(new Parser3LexerCore.CoreToken(
							token.start, token.end, currentMode, token.debugText
					));
				}
				prevSignificant = tokenType;
				continue;
			}

			// WHITE_SPACE — не меняем prevSignificant
			// WHITE_SPACE всегда остаётся WHITE_SPACE для правильного форматирования
			if ("WHITE_SPACE".equals(tokenType)) {
				result.add(token);
				continue;
			}

			// === Конвертация остальных токенов ===

			// Внутри параметров или присваивания — не конвертируем
			if (isInsideParser3(braceStack)) {
				result.add(token);
				prevSignificant = tokenType;
				continue;
			}

			// HTML_DATA — всегда конвертируем (это текст CSS/JS)
			if ("HTML_DATA".equals(tokenType)) {
				if (splitClosingTagToken(result, token, currentMode)) {
					currentMode = null;
					continue;
				}
				result.add(new Parser3LexerCore.CoreToken(
						token.start, token.end, currentMode, token.debugText
				));
				// HTML_DATA не обновляет prevSignificant
				continue;
			}

			// COLON, SEMICOLON, COMMA — конвертируем
			if ("COLON".equals(tokenType) || "SEMICOLON".equals(tokenType) || "COMMA".equals(tokenType)) {
				result.add(new Parser3LexerCore.CoreToken(
						token.start, token.end, currentMode, token.debugText
				));
				// Эти токены не обновляют prevSignificant
				continue;
			}

			// DOT — конвертируем если не после METHOD/VARIABLE (иначе это ^obj.method или $var.field)
			if ("DOT".equals(tokenType)) {
				if (isParser3CallContext(prevSignificant)) {
					// После METHOD/VARIABLE — это Parser3 цепочка
					result.add(token);
				} else {
					// Иначе это JS/CSS точка — конвертируем
					result.add(new Parser3LexerCore.CoreToken(
							token.start, token.end, currentMode, token.debugText
					));
				}
				prevSignificant = tokenType;
				continue;
			}

			// Всё остальное (METHOD, VARIABLE и т.д.) — оставляем как есть
			result.add(token);
			prevSignificant = tokenType;
		}

		return result;
	}

	/**
	 * Проверяет, является ли предыдущий токен контекстом вызова Parser3.
	 * Если да — следующие [], (), . являются частью Parser3 конструкции.
	 */
	private static boolean isParser3CallContext(String prevSignificant) {
		if (prevSignificant == null) return false;
		return "METHOD".equals(prevSignificant) ||
				"VARIABLE".equals(prevSignificant) ||
				"DOLLAR_VARIABLE".equals(prevSignificant) ||
				"LOCAL_VARIABLE".equals(prevSignificant) ||
				"IMPORTANT_VARIABLE".equals(prevSignificant) ||
				"IMPORTANT_METHOD".equals(prevSignificant) ||
				"KEYWORD".equals(prevSignificant) ||
				"CONSTRUCTOR".equals(prevSignificant) ||
				// Ключевые слова Parser3 — после них скобки () это параметры, а не CSS
				"KW_IF".equals(prevSignificant) ||
				"KW_WHILE".equals(prevSignificant) ||
				"KW_FOR".equals(prevSignificant) ||
				"KW_SWITCH".equals(prevSignificant) ||
				"KW_CASE".equals(prevSignificant) ||
				"KW_EVAL".equals(prevSignificant) ||
				"KW_CACHE".equals(prevSignificant) ||
				"KW_CONNECT".equals(prevSignificant) ||
				"KW_PROCESS".equals(prevSignificant) ||
				"KW_TRY".equals(prevSignificant) ||
				"KW_THROW".equals(prevSignificant) ||
				"KW_USE".equals(prevSignificant) ||
				"KW_SLEEP".equals(prevSignificant) ||
				"KW_RETURN".equals(prevSignificant) ||
				"KW_BREAK".equals(prevSignificant) ||
				"KW_CONTINUE".equals(prevSignificant);
	}

	/**
	 * Проверяет, находимся ли мы внутри Parser3 конструкции где контент НЕ выводится.
	 * Это PARAM (параметры) или VAR_BRACE (присваивание), но НЕ OUTPUT_BRACE или CSS_BRACE.
	 */
	private static boolean isInsideParser3(java.util.Deque<String> braceStack) {
		for (String type : braceStack) {
			if ("PARAM".equals(type) || "VAR_BRACE".equals(type)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Проверяет, предшествует ли { переменная ($var или $var.field).
	 *
	 * ВАЖНО: Пробелов быть НЕ должно!
	 * - $var{...} — присваивание
	 * - $var {...} — НЕ присваивание (вывод $var + CSS скобка)
	 *
	 * Паттерны присваивания (без пробелов):
	 * - $var{...} — DOLLAR_VARIABLE VARIABLE LBRACE
	 * - $var.field{...} — DOLLAR_VARIABLE VARIABLE DOT VARIABLE LBRACE
	 * - $var.x.y.z{...} — ... DOT VARIABLE LBRACE
	 */
	private static boolean isPrecededByVariable(List<Parser3LexerCore.CoreToken> tokens, int braceIndex) {
		// Проверяем что НЕПОСРЕДСТВЕННО перед { стоит VARIABLE (без пробелов!)
		if (braceIndex < 2) return false;

		// Токен прямо перед { должен быть VARIABLE
		String prevType = tokens.get(braceIndex - 1).type;
		if (!"VARIABLE".equals(prevType)) {
			return false;
		}

		// Токен перед VARIABLE должен быть DOLLAR_VARIABLE или DOT
		String prev2Type = tokens.get(braceIndex - 2).type;

		// $var{ — это присваивание
		if ("DOLLAR_VARIABLE".equals(prev2Type)) {
			return true;
		}

		// .field{ — проверяем дальше, ищем $ в начале цепочки
		if ("DOT".equals(prev2Type)) {
			// Идём назад по цепочке VARIABLE DOT VARIABLE DOT... ищем DOLLAR_VARIABLE
			int i = braceIndex - 3;
			while (i >= 1) {
				String t = tokens.get(i).type;

				if ("VARIABLE".equals(t)) {
					String tPrev = tokens.get(i - 1).type;
					if ("DOLLAR_VARIABLE".equals(tPrev)) {
						return true; // Нашли $var.x.y.z{
					}
					if ("DOT".equals(tPrev)) {
						i -= 2; // Пропускаем VARIABLE и DOT, продолжаем
						continue;
					}
					return false; // Перед VARIABLE что-то другое
				}

				return false; // Не VARIABLE — не цепочка переменной
			}
		}

		return false;
	}

	/**
	 * Возвращает тип предыдущего не-whitespace токена.
	 */
	private static String getPrevNonWhitespaceType(List<Parser3LexerCore.CoreToken> tokens, int currentIndex) {
		for (int i = currentIndex - 1; i >= 0; i--) {
			String type = tokens.get(i).type;
			if (!"WHITE_SPACE".equals(type)) {
				return type;
			}
		}
		return null;
	}

	/**
	 * Находит индексы токенов, с которых начинаются методы.
	 */
	@NotNull
	private static List<Integer> findMethodBoundaries(@NotNull List<Parser3LexerCore.CoreToken> tokens) {
		List<Integer> boundaries = new ArrayList<>();
		boundaries.add(0);

		for (int i = 0; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);
			if (i > 0 && P3MethodDeclarationUtils.isMethodDeclarationToken(token.type)) {
				boundaries.add(i);
			}
		}

		return boundaries;
	}

	/**
	 * Ищет индекс первого TEMPLATE_DATA токена с HTML тегом.
	 */
	private static int findFirstHtmlTagInMethod(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			@NotNull CharSequence text,
			int startIdx,
			int endIdx
	) {
		for (int i = startIdx; i < endIdx && i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken token = tokens.get(i);
			if ("TEMPLATE_DATA".equals(token.type) && hasHtmlTag(token.debugText)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Проверяет, содержит ли текст HTML тег.
	 */
	private static boolean hasHtmlTag(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		int idx = text.indexOf('<');
		while (idx >= 0 && idx < text.length() - 1) {
			char next = text.charAt(idx + 1);
			if (Character.isLetter(next) || next == '/' || next == '!' || next == '?') {
				return true;
			}
			idx = text.indexOf('<', idx + 1);
		}
		return false;
	}

	/**
	 * Делит токен с открывающим style/script тегом на HTML_DATA тега и CSS/JS содержимое после него.
	 *
	 * @return true если внутри того же токена уже встретился закрывающий тег и режим можно сразу завершить.
	 */
	private static boolean splitOpeningTagToken(
			@NotNull List<Parser3LexerCore.CoreToken> result,
			@NotNull Parser3LexerCore.CoreToken token,
			@NotNull String contentType,
			@NotNull String closingTag
	) {
		String text = token.debugText;
		if (text == null) {
			result.add(token);
			return false;
		}

		int openEnd = text.indexOf('>');
		if (openEnd < 0) {
			result.add(token);
			return false;
		}

		addSplitToken(result, token, 0, openEnd + 1, "HTML_DATA");
		if (openEnd + 1 >= text.length()) {
			return false;
		}

		String tail = text.substring(openEnd + 1);
		String tailLower = tail.toLowerCase();
		int closeStart = tailLower.indexOf(closingTag);
		if (closeStart < 0) {
			addSplitToken(result, token, openEnd + 1, text.length(), contentType);
			return false;
		}

		if (closeStart > 0) {
			addSplitToken(result, token, openEnd + 1, openEnd + 1 + closeStart, contentType);
		}
		addSplitToken(result, token, openEnd + 1 + closeStart, text.length(), "HTML_DATA");
		return true;
	}

	/**
	 * Делит токен с закрывающим style/script тегом на CSS/JS содержимое до тега и HTML_DATA самого тега.
	 */
	private static boolean splitClosingTagToken(
			@NotNull List<Parser3LexerCore.CoreToken> result,
			@NotNull Parser3LexerCore.CoreToken token,
			@NotNull String currentMode
	) {
		String text = token.debugText;
		if (text == null) {
			return false;
		}

		String lower = text.toLowerCase();
		String closingTag = "CSS_DATA".equals(currentMode) ? "</style" : "</script";
		int closeStart = lower.indexOf(closingTag);
		if (closeStart < 0) {
			return false;
		}

		if (closeStart > 0) {
			addSplitToken(result, token, 0, closeStart, currentMode);
		}
		addSplitToken(result, token, closeStart, text.length(), "HTML_DATA");
		return true;
	}

	private static void addSplitToken(
			@NotNull List<Parser3LexerCore.CoreToken> result,
			@NotNull Parser3LexerCore.CoreToken source,
			int localStart,
			int localEnd,
			@NotNull String type
	) {
		if (localStart >= localEnd) {
			return;
		}
		result.add(new Parser3LexerCore.CoreToken(
				source.start + localStart,
				source.start + localEnd,
				type,
				source.debugText.substring(localStart, localEnd)
		));
	}
}
