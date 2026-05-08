package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;


/**
 * Core-лексер без зависимостей от IntelliJ.
 * Работает с простыми структурами данных:
 * - CoreToken (start, end, type, debugText)
 * - type:
 *   - TEMPLATE_DATA
 *   - LINE_COMMENT
 *   - BLOCK_COMMENT
 *   - DEFINE_METHOD
 *   - SPECIAL_METHOD
 *   - VARIABLE
 *   - LOCAL_VARIABLE
 */
public final class Parser3LexerCore {

	public static final class CoreToken {
		public final int start;
		public final int end;
		public final @NotNull String type;
		public final @Nullable String debugText;

		public CoreToken(int start, int end, @NotNull String type, @Nullable String debugText) {
			this.start = start;
			this.end = end;
			this.type = type;
			this.debugText = debugText;
		}
	}

	private Parser3LexerCore() {
	}

	/**
	 * Главная функция core-уровня:
	 * 1) находит комментарии;
	 * 2) заполняет "дыры" TEMPLATE_DATA;
	 * 3) поверх TEMPLATE_DATA размечает определения методов;
	 */
	@NotNull
	public static List<CoreToken> tokenize(@NotNull CharSequence fullText, int startOffset, int endOffset) {
		boolean useSinglePass = !Boolean.getBoolean("parser3.lexer.legacy");
		if (useSinglePass) {
			int offsetLeft = startOffset;
			CharSequence context = getContextText(fullText, startOffset, endOffset);
			List<CoreToken> baseTokens = measure("singlePass", () -> Parser3SinglePassLexer.tokenize(fullText, startOffset, endOffset));

			final List<CoreToken> tokensSinglePassSqlIndent = baseTokens;
			baseTokens = measure("singlePassSqlIndent", () ->
					Parser3SqlIndentPostProcessor.process(context, tokensSinglePassSqlIndent)
			);

			final List<CoreToken> tokensPost = baseTokens;
			baseTokens = measure("postProcess", () ->
					Parser3PostProcessor.process(tokensPost, context)
			);

			final List<CoreToken> tokensHtml = baseTokens;
			baseTokens = measure("htmlData", () ->
					Parser3PostProcessorHtml.markHtmlInMethods(tokensHtml, context)
			);

			final int finalOffsetLeft = offsetLeft;
			final CharSequence finalFullText = fullText;
			baseTokens = splitWhitespaceTokens(baseTokens, context, finalFullText, finalOffsetLeft);

			final List<CoreToken> tokensFixColons = baseTokens;
			baseTokens = measure("fixColonsInHtml", () ->
					Parser3PostProcessorHtml.fixColonsInHtmlTags(tokensFixColons, context)
			);

			final List<CoreToken> tokensCssJs = baseTokens;
			baseTokens = measure("cssJsData", () ->
					Parser3PostProcessorHtml.markStyleScriptContent(tokensCssJs, context)
			);

			final List<CoreToken> tokensDirectives = baseTokens;
			baseTokens = measure("directives", () ->
					Parser3DirectiveLexerCore.postProcess(context, tokensDirectives)
			);

			baseTokens = splitWhitespaceInsideStrings(baseTokens, context);
			baseTokens = mergeIndentedWhitespaceIntoHtmlStrings(baseTokens);
			baseTokens = splitHexEscapes(baseTokens, context);
			baseTokens = fillAnyGaps(baseTokens, context);

			if (offsetLeft != 0) {
				baseTokens = shiftTokens(baseTokens, offsetLeft);
			}

			return mergeSameTypeTokens(baseTokens, context);
		}

		return tokenizeLegacy(fullText, startOffset, endOffset);
	}

	/**
	 * Legacy-конвейер лексера.
	 * Нужен как отдельная точка входа, чтобы single-pass мог локально переиспользовать
	 * старую SQL-логику только для тела конкретного sql{} блока без глобального переключения режима.
	 */
	static @NotNull List<CoreToken> tokenizeLegacy(@NotNull CharSequence fullText, int startOffset, int endOffset) {

		// Смещение для корректировки оффсетов токенов
		int offsetLeft = startOffset;

		// Вырезаем нужный диапазон текста
		CharSequence context = (startOffset == 0 && endOffset >= fullText.length())
				? fullText
				: fullText.subSequence(startOffset, Math.min(endOffset, fullText.length()));
		int length = context.length();

		// 1. Комментарии
		List<CoreToken> commentTokens = measure("comments", () ->
				Parser3CommentLexerCore.lexComments(context)
		);

		// 2. Комментарии + TEMPLATE_DATA — базовый слой
		List<CoreToken> baseTokens = measure("fillTemplateGaps", () ->
				fillTemplateGaps(commentTokens, context)
		);

		// 3. SQL-блоки — ПЕРВЫМ! До методов, чтобы ^void:sql{ был ещё цельным
		//    Помечаем весь контент sql{...} как SQL_BLOCK.
		final List<CoreToken> tokensSql = baseTokens;
		baseTokens = measure("sqlBlocks", () ->
				applyItemsForSql(context, tokensSql, Parser3SqlBlockLexerCore::tokenize)
		);

		// 4. Применяем слой методов: берём только TEMPLATE_DATA,
		//    получаем чистые METHOD_*, снова заполняем дырки с учётом SQL_BLOCK
		final List<CoreToken> tokens1 = baseTokens;
		baseTokens = measure("methods", () ->
				applyItemsPreservingSql(context, tokens1, Parser3MethodLexerCore::tokenize)
		);

		// 5. методы и переменные — работают и в TEMPLATE_DATA, и в SQL_BLOCK
		//    Обработка }(...) для ^if в SQL контексте делается в tokenize()
		final List<CoreToken> tokens2 = baseTokens;
		baseTokens = measure("methodCallsAndVars", () ->
				applyItemsIncludingSql(context, tokens2, Parser3MethodCallAndVariblesLexerCore::tokenize)
		);

		// 5.5 логические операторы — работают в TEMPLATE_DATA и SQL_BLOCK
		//    Нужно передать полный список токенов чтобы ExpressionLexerCore мог найти LPAREN/RPAREN
		//    ВАЖНО: должен быть ДО dotsAndColons, чтобы строки были распознаны
		final List<CoreToken> tokens3 = baseTokens;
		baseTokens = measure("expressions", () ->
				applyExpressionsWithContext(context, tokens3)
		);

		// 5.6 точки и двоеточия между методами/переменными/конструкторами
		//    ВАЖНО: должен быть ПОСЛЕ expressions, чтобы не ломать точки внутри строк
		//    Обрабатываем SQL_BLOCK тоже — там есть Parser3 конструкции ^form:name, $v.id
		final List<CoreToken> tokensDots = baseTokens;
		baseTokens = measure("dotsAndColons", () ->
				applyItemsIncludingSql(context, tokensDots, Parser3DotsAndColonsLexerCore::tokenize)
		);

		// 7. Скобки — квадратные, фигурные, точка с запятой
		//    Круглые скобки уже обработаны в Parser3MethodCallAndVariblesLexerCore
		//    Обрабатываем SQL_BLOCK тоже — там могут быть ^method[...] вызовы
		final List<CoreToken> tokens4 = baseTokens;
		baseTokens = measure("brackets", () ->
				applyItemsIncludingSqlNorestore(context, tokens4, Parser3BracketLexerCore::tokenize)
		);

		// 7.5 Финальное восстановление SQL_BLOCK
		//     Теперь ВСЕ скобки созданы (LPAREN, LBRACKET), можно правильно исключить Parser3-конструкции
		final List<CoreToken> tokensSqlFinal = baseTokens;
		baseTokens = measure("sqlFinal", () ->
				restoreSqlBlockTypeFinal(tokensSqlFinal)
		);

		// 8. SQL постобработка: выкусываем отступы с учётом вложенности {}
		final List<CoreToken> tokensSqlIndent = baseTokens;
		baseTokens = measure("sqlIndent", () ->
				Parser3SqlIndentPostProcessor.process(context, tokensSqlIndent)
		);

		// 8.5 Постобработка: DOT между SQL_BLOCK превращаем в SQL_BLOCK и др.
		final List<CoreToken> tokensPost = baseTokens;
		baseTokens = measure("postProcess", () ->
				Parser3PostProcessor.process(tokensPost, context)
		);

		// 8.6 Помечаем TEMPLATE_DATA как HTML_DATA в методах, содержащих HTML теги
		// ВАЖНО: должно быть ДО splitWhitespaceTokens, чтобы правильно разбить на токены
		final List<CoreToken> tokensHtml = baseTokens;
		baseTokens = measure("htmlData", () ->
				Parser3PostProcessorHtml.markHtmlInMethods(tokensHtml, context)
		);

		// Финальный проход: выделяем WHITE_SPACE только на последнем шаге
		// Передаём fullText и offsetLeft чтобы правильно определять atLineStart
		final int finalOffsetLeft = offsetLeft;
		final CharSequence finalFullText = fullText;
		baseTokens = splitWhitespaceTokens(baseTokens, context, finalFullText, finalOffsetLeft);

		// 8.65 Конвертируем COLON/SEMICOLON внутри HTML тегов обратно в HTML_DATA
		// Это нужно для корректной работы inline CSS (style="color: red;")
		final List<CoreToken> tokensFixColons = baseTokens;
		baseTokens = measure("fixColonsInHtml", () ->
				Parser3PostProcessorHtml.fixColonsInHtmlTags(tokensFixColons, context)
		);

		// 8.7 Помечаем содержимое <style> как CSS_DATA и <script> как JS_DATA
		// ВАЖНО: должно быть ПОСЛЕ splitWhitespaceTokens, т.к. теги уже разбиты
		final List<CoreToken> tokensCssJs = baseTokens;
		baseTokens = measure("cssJsData", () ->
				Parser3PostProcessorHtml.markStyleScriptContent(tokensCssJs, context)
		);

		// 9. Директивы @USE, @CLASS, @BASE (постобработка полного списка)
		final List<CoreToken> tokensDirectives = baseTokens;
		baseTokens = measure("directives", () ->
				Parser3DirectiveLexerCore.postProcess(context, tokensDirectives)
		);

		// Дополнительный проход: делим пробелы/табы/переводы строки внутри STRING
		baseTokens = splitWhitespaceInsideStrings(baseTokens, context);
		baseTokens = mergeIndentedWhitespaceIntoHtmlStrings(baseTokens);
		baseTokens = splitHexEscapes(baseTokens, context);

		// ФИНАЛЬНАЯ ПРОВЕРКА: заполняем любые дыры TEMPLATE_DATA
		// Это защита от ошибок в лексерах — дыр быть не должно
		baseTokens = fillAnyGaps(baseTokens, context);

		// Сдвигаем координаты токенов, если контекст начинался не с нуля
		if (offsetLeft != 0) {
			baseTokens = shiftTokens(baseTokens, offsetLeft);
		}

		// САМЫЙ ПОСЛЕДНИЙ ШАГ: склеиваем подряд идущие токены одного типа (кроме WHITE_SPACE)
		baseTokens = mergeSameTypeTokens(baseTokens, context);

		return baseTokens;
	}

	/**
	 * Разрезает текстовые токены вокруг Parser3 escape-последовательностей вида ^#HH.
	 * В оригинальном Parser3 это байт по hex-коду; диагностику ^#00 здесь не делаем,
	 * потому что лексер подсветки сейчас только выделяет последовательность цветом.
	 */
	@NotNull
	private static List<CoreToken> splitHexEscapes(@NotNull List<CoreToken> tokens, @NotNull CharSequence text) {
		List<CoreToken> result = new ArrayList<>();
		for (CoreToken token : tokens) {
			if (!canContainHexEscape(token.type)) {
				result.add(token);
				continue;
			}
			int chunkStart = token.start;
			int i = token.start;
			while (i < token.end) {
				if (isHexEscapeAt(text, i, token.end)) {
					if (chunkStart < i) {
						result.add(new CoreToken(chunkStart, i, token.type, substringSafely(text, chunkStart, i)));
					}
					result.add(new CoreToken(i, i + 4, "HEX_ESCAPE", substringSafely(text, i, i + 4)));
					i += 4;
					chunkStart = i;
					continue;
				}
				i++;
			}
			if (chunkStart < token.end) {
				result.add(new CoreToken(chunkStart, token.end, token.type, substringSafely(text, chunkStart, token.end)));
			}
		}
		return result;
	}

	private static boolean canContainHexEscape(@NotNull String type) {
		return "TEMPLATE_DATA".equals(type)
				|| "STRING".equals(type)
				|| "SQL_BLOCK".equals(type)
				|| "HTML_DATA".equals(type)
				|| "CSS_DATA".equals(type)
				|| "JS_DATA".equals(type)
				|| "USE_PATH".equals(type)
				|| "OUTER".equals(type);
	}

	private static boolean isHexEscapeAt(@NotNull CharSequence text, int pos, int end) {
		return pos + 3 < end
				&& text.charAt(pos) == '^'
				&& text.charAt(pos + 1) == '#'
				&& isHexDigit(text.charAt(pos + 2))
				&& isHexDigit(text.charAt(pos + 3))
				&& !Parser3LexerUtils.isEscapedByCaret(text, pos);
	}

	private static boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9')
				|| (c >= 'A' && c <= 'F')
				|| (c >= 'a' && c <= 'f');
	}

	/**
	 * Финальная проверка: заполняет любые дыры между токенами как TEMPLATE_DATA.
	 * Это защита от ошибок в лексерах — в нормальной ситуации дыр быть не должно.
	 */
	@NotNull
	private static List<CoreToken> fillAnyGaps(@NotNull List<CoreToken> tokens, @NotNull CharSequence text) {
		if (tokens.isEmpty()) {
			return tokens;
		}

		List<CoreToken> result = new ArrayList<>();
		int lastEnd = 0;

		for (CoreToken token : tokens) {
			if (token.start > lastEnd) {
				// Есть дыра — заполняем TEMPLATE_DATA
				String gapText = substringSafely(text, lastEnd, token.start);
				result.add(new CoreToken(lastEnd, token.start, "TEMPLATE_DATA", gapText));
			}
			result.add(token);
			lastEnd = Math.max(lastEnd, token.end);
		}

		// Проверяем дыру в конце
		int textLength = text.length();
		if (lastEnd < textLength) {
			String gapText = substringSafely(text, lastEnd, textLength);
			result.add(new CoreToken(lastEnd, textLength, "TEMPLATE_DATA", gapText));
		}

		return result;
	}

	// Использует общий флаг логирования из Parser3LexerLog
	private static List<CoreToken> measure(String name, Supplier<List<CoreToken>> supplier) {
		if (!Parser3LexerLog.isEnabled()) {
			return supplier.get();
		}
		long start = System.nanoTime();
		List<CoreToken> result = supplier.get();
		Parser3LexerLog.logTime("    " + name + " (" + result.size() + " tokens)", start);
		return result;
	}


	private static List<CoreToken> shiftTokens(@NotNull List<CoreToken> tokens, int delta) {
		if (delta == 0 || tokens.isEmpty()) {
			return tokens;
		}

		List<CoreToken> result = new ArrayList<>(tokens.size());
		for (CoreToken token : tokens) {
			result.add(new CoreToken(
					token.start + delta,
					token.end + delta,
					token.type,
					token.debugText
			));
		}
		return result;
	}

	@NotNull
	private static List<CoreToken> adjustSinglePassSqlIndent(
			@NotNull List<CoreToken> tokens,
			@NotNull CharSequence text
	) {
		List<CoreToken> result = new ArrayList<>(tokens.size());
		int braceDepth = 0;
		int bracketDepth = 0;
		int parenDepth = 0;
		int commentIndentedSqlRootDepth = -1;
		String previousSignificantType = null;
		String previousSignificantText = null;
		String beforePreviousSignificantType = null;
		String beforePreviousSignificantText = null;

		for (int idx = 0; idx < tokens.size(); idx++) {
			CoreToken token = tokens.get(idx);
			if (!"SQL_BLOCK".equals(token.type)) {
				result.add(token);
				if ("LBRACE".equals(token.type)) {
					braceDepth++;
					boolean sqlDeclarationBrace = ("METHOD".equals(previousSignificantType) || "CONSTRUCTOR".equals(previousSignificantType))
							&& "sql".equals(previousSignificantText);
					if (sqlDeclarationBrace && hasCommentLineBeforeSqlDeclaration(text, token.start)) {
						commentIndentedSqlRootDepth = braceDepth;
					}
				} else if ("RBRACE".equals(token.type) && braceDepth > 0) {
					braceDepth--;
					if (commentIndentedSqlRootDepth > braceDepth) {
						commentIndentedSqlRootDepth = -1;
					}
				} else if ("LBRACKET".equals(token.type)) {
					bracketDepth++;
				} else if ("RBRACKET".equals(token.type) && bracketDepth > 0) {
					bracketDepth--;
				} else if ("LPAREN".equals(token.type)) {
					parenDepth++;
				} else if ("RPAREN".equals(token.type) && parenDepth > 0) {
					parenDepth--;
				}
				if (!"WHITE_SPACE".equals(token.type)) {
					beforePreviousSignificantType = previousSignificantType;
					beforePreviousSignificantText = previousSignificantText;
					previousSignificantType = token.type;
					previousSignificantText = token.debugText;
				}
				continue;
			}

			boolean atLineStart = token.start == 0 || text.charAt(token.start - 1) == '\n' || text.charAt(token.start - 1) == '\r';
			int trimTarget = Math.max(0, braceDepth - 1 + bracketDepth + parenDepth);
			String nextTokenType = idx + 1 < tokens.size() ? tokens.get(idx + 1).type : null;
			boolean sqlRootStart = "LBRACE".equals(previousSignificantType)
					&& ("METHOD".equals(beforePreviousSignificantType) || "CONSTRUCTOR".equals(beforePreviousSignificantType))
					&& "sql".equals(beforePreviousSignificantText);
			if (sqlRootStart && trimTarget > 0 && token.debugText != null) {
				trimTarget = countLeadingIndent(token.debugText);
			}
			if (commentIndentedSqlRootDepth != -1 && braceDepth >= commentIndentedSqlRootDepth
					&& !("RBRACE".equals(previousSignificantType) && startsWithParserConstructAfterIndent(token.debugText))
					&& token.debugText != null && startsWithIndent(token.debugText)) {
				trimTarget++;
			}
			if ("RBRACKET".equals(previousSignificantType)
					&& ("RBRACKET".equals(beforePreviousSignificantType) || "RPAREN".equals(beforePreviousSignificantType))) {
				trimTarget = Math.max(0, trimTarget - 1);
			}
			if ("RBRACE".equals(previousSignificantType) && startsWithParserConstructAfterIndent(token.debugText)) {
				trimTarget = Math.min(trimTarget, 1);
			}
			if ("RBRACE".equals(previousSignificantType) && isIndentOnly(token.debugText) && isParserConstructTokenType(nextTokenType)) {
				trimTarget = Math.min(trimTarget, 1);
			}
			if (!atLineStart || trimTarget == 0 || token.debugText == null || token.debugText.isEmpty()) {
				result.add(token);
				previousSignificantType = token.type;
				continue;
			}

			int trim = 0;
			while (trim < token.debugText.length() && trim < trimTarget) {
				char c = token.debugText.charAt(trim);
				if (c != ' ' && c != '\t') {
					break;
				}
				trim++;
			}

			if (trim == 0) {
				result.add(token);
				previousSignificantType = token.type;
				continue;
			}

			result.add(new CoreToken(token.start, token.start + trim, "WHITE_SPACE", substringSafely(text, token.start, token.start + trim)));
			if (token.start + trim < token.end) {
				result.add(new CoreToken(token.start + trim, token.end, "SQL_BLOCK", substringSafely(text, token.start + trim, token.end)));
			}
			previousSignificantType = token.type;
		}

		return result;
	}

	private static int countLeadingIndent(@NotNull String text) {
		int count = 0;
		while (count < text.length()) {
			char c = text.charAt(count);
			if (c != ' ' && c != '\t') {
				break;
			}
			count++;
		}
		return count;
	}

	private static boolean startsWithIndent(@NotNull String text) {
		return !text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(0) == '\t');
	}

	private static boolean startsWithParserConstructAfterIndent(@Nullable String text) {
		if (text == null) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == ' ' || c == '\t') {
				continue;
			}
			return c == '^' || c == '$';
		}
		return false;
	}

	private static boolean isIndentOnly(@Nullable String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != ' ' && c != '\t') {
				return false;
			}
		}
		return true;
	}

	private static boolean isParserConstructTokenType(@Nullable String type) {
		if (type == null) {
			return false;
		}
		return type.startsWith("KW_")
				|| "METHOD".equals(type)
				|| "CONSTRUCTOR".equals(type)
				|| "VARIABLE".equals(type)
				|| "DOLLAR_VARIABLE".equals(type);
	}

	private static boolean hasCommentLineBeforeSqlDeclaration(@NotNull CharSequence text, int declarationPos) {
		if (declarationPos <= 0) {
			return false;
		}

		int declarationLineStart = declarationPos - 1;
		while (declarationLineStart >= 0
				&& text.charAt(declarationLineStart) != '\n'
				&& text.charAt(declarationLineStart) != '\r') {
			declarationLineStart--;
		}
		declarationLineStart++;
		if (declarationLineStart <= 0) {
			return false;
		}

		int previousLineEnd = declarationLineStart - 1;
		while (previousLineEnd >= 0 && (text.charAt(previousLineEnd) == '\n' || text.charAt(previousLineEnd) == '\r')) {
			previousLineEnd--;
		}
		if (previousLineEnd < 0) {
			return false;
		}

		int previousLineStart = previousLineEnd;
		while (previousLineStart >= 0 && text.charAt(previousLineStart) != '\n' && text.charAt(previousLineStart) != '\r') {
			previousLineStart--;
		}
		previousLineStart++;

		for (int i = previousLineStart; i <= previousLineEnd; i++) {
			char c = text.charAt(i);
			if (c == ' ' || c == '\t') {
				continue;
			}
			return c == '#';
		}
		return false;
	}



	@FunctionalInterface
	public interface CoreLayer {
		@NotNull
		List<CoreToken> tokenize(@NotNull CharSequence text, @NotNull List<CoreToken> templateTokens);
	}

	/**
	 * Склеивает подряд идущие токены типов CSS_DATA, HTML_DATA, JS_DATA.
	 * Только эти типы склеиваем — они могут быть разбиты постпроцессорами.
	 * Остальные токены (METHOD, VARIABLE и т.д.) не трогаем чтобы не сломать навигацию.
	 */
	private static List<CoreToken> mergeSameTypeTokens(@NotNull List<CoreToken> tokens, @NotNull CharSequence text) {
		if (tokens.isEmpty()) {
			return tokens;
		}
		List<CoreToken> result = new ArrayList<>(tokens.size());
		CoreToken current = tokens.get(0);
		for (int i = 1; i < tokens.size(); i++) {
			CoreToken next = tokens.get(i);
			// Склеиваем только если:
			// - одинаковый тип
			// - токены идут подряд (end == start)
			// - это CSS_DATA, HTML_DATA или JS_DATA
			if (current.type.equals(next.type)
					&& current.end == next.start
					&& ("CSS_DATA".equals(current.type)
					|| "HTML_DATA".equals(current.type)
					|| "JS_DATA".equals(current.type))) {
				current = new CoreToken(current.start, next.end, current.type, substringSafely(text, current.start, next.end));
			} else {
				result.add(current);
				current = next;
			}
		}
		result.add(current);
		return result;
	}

	@NotNull
	private static List<CoreToken> applyItems(@NotNull CharSequence text,
											  @NotNull List<CoreToken> baseTokens,
											  @NotNull CoreLayer layer) {

		// Разделяем текущие токены на TEMPLATE_DATA и всё остальное
		List<CoreToken> templateTokens = new ArrayList<>();
		List<CoreToken> nonTemplateTokens = new ArrayList<>();

		for (CoreToken token : baseTokens) {
			if ("TEMPLATE_DATA".equals(token.type)) {
				templateTokens.add(token);
			} else {
				nonTemplateTokens.add(token);
			}
		}

		if (templateTokens.isEmpty()) {
			// Нечего разбирать — просто возвращаем исходный список
			return baseTokens;
		}

		// Новый слой токенов, специфичный для файла (методы, потом переменные и т.д.)
		List<CoreToken> layerTokens = layer.tokenize(text, templateTokens);
		if (layerTokens.isEmpty()) {
			return baseTokens;
		}

		// Склеиваем старые НЕ-template токены (комменты и прочее)
		// с новыми токенами слоя (METHOD_*, VARIABLE, LOCAL_VARIABLE, ...)
		List<CoreToken> merged = new ArrayList<>(nonTemplateTokens.size() + layerTokens.size());
		merged.addAll(nonTemplateTokens);
		merged.addAll(layerTokens);

		merged.sort((a, b) -> {
			if (a.start != b.start) {
				return Integer.compare(a.start, b.start);
			}
			return Integer.compare(a.end, b.end);
		});

		// Заново заполняем дырки TEMPLATE_DATA по всему тексту
		return fillTemplateGaps(merged, text);
	}

	/**
	 * Применяет SQL лексер: SQL_BLOCK токены заменяют части TEMPLATE_DATA.
	 */
	@NotNull
	private static List<CoreToken> applyItemsForSql(@NotNull CharSequence text,
													@NotNull List<CoreToken> baseTokens,
													@NotNull CoreLayer layer) {

		List<CoreToken> templateTokens = new ArrayList<>();
		List<CoreToken> nonTemplateTokens = new ArrayList<>();

		for (CoreToken token : baseTokens) {
			if ("TEMPLATE_DATA".equals(token.type)) {
				templateTokens.add(token);
			} else {
				nonTemplateTokens.add(token);
			}
		}

		if (templateTokens.isEmpty()) {
			return baseTokens;
		}

		// SQL лексер возвращает SQL_BLOCK токены
		List<CoreToken> sqlTokens = layer.tokenize(text, templateTokens);
		if (sqlTokens.isEmpty()) {
			return baseTokens;
		}

		// Разбиваем TEMPLATE_DATA токены по SQL_BLOCK
		List<CoreToken> splitTemplateTokens = splitTemplateBySQL(templateTokens, sqlTokens, text);

		// Склеиваем все токены
		List<CoreToken> merged = new ArrayList<>();
		merged.addAll(nonTemplateTokens);
		merged.addAll(splitTemplateTokens);
		merged.addAll(sqlTokens);

		merged.sort((a, b) -> {
			if (a.start != b.start) {
				return Integer.compare(a.start, b.start);
			}
			return Integer.compare(a.end, b.end);
		});

		return merged;
	}

	/**
	 * Разбивает TEMPLATE_DATA токены, вырезая из них SQL_BLOCK области.
	 */
	private static List<CoreToken> splitTemplateBySQL(
			@NotNull List<CoreToken> templateTokens,
			@NotNull List<CoreToken> sqlTokens,
			@NotNull CharSequence text
	) {
		List<CoreToken> result = new ArrayList<>();

		for (CoreToken template : templateTokens) {
			int current = template.start;
			int end = template.end;

			// Находим SQL токены которые пересекаются с этим TEMPLATE_DATA
			for (CoreToken sql : sqlTokens) {
				if (sql.end <= current || sql.start >= end) {
					continue; // Не пересекается
				}

				// Добавляем часть до SQL
				if (sql.start > current) {
					result.add(new CoreToken(current, sql.start, "TEMPLATE_DATA",
							substringSafely(text, current, sql.start)));
				}

				current = sql.end;
			}

			// Добавляем остаток после последнего SQL
			if (current < end) {
				result.add(new CoreToken(current, end, "TEMPLATE_DATA",
						substringSafely(text, current, end)));
			}
		}

		return result;
	}

	/**
	 * Применяет лексер только к TEMPLATE_DATA токенам, но сохраняет SQL_BLOCK.
	 * Используется для Parser3MethodLexerCore — он не должен разбирать SQL,
	 * но SQL_BLOCK токены должны остаться на месте.
	 */
	@NotNull
	private static List<CoreToken> applyItemsPreservingSql(@NotNull CharSequence text,
														   @NotNull List<CoreToken> baseTokens,
														   @NotNull CoreLayer layer) {

		// Разделяем токены: TEMPLATE_DATA идёт на обработку, SQL_BLOCK и прочее — сохраняем
		List<CoreToken> templateTokens = new ArrayList<>();
		List<CoreToken> otherTokens = new ArrayList<>();

		for (CoreToken token : baseTokens) {
			if ("TEMPLATE_DATA".equals(token.type)) {
				templateTokens.add(token);
			} else {
				otherTokens.add(token);
			}
		}

		if (templateTokens.isEmpty()) {
			return baseTokens;
		}

		// Применяем лексер только к TEMPLATE_DATA
		List<CoreToken> layerTokens = layer.tokenize(text, templateTokens);
		if (layerTokens.isEmpty()) {
			return baseTokens;
		}

		// Склеиваем: старые НЕ-template токены (включая SQL_BLOCK) + новые токены слоя
		List<CoreToken> merged = new ArrayList<>(otherTokens.size() + layerTokens.size());
		merged.addAll(otherTokens);
		merged.addAll(layerTokens);

		merged.sort((a, b) -> {
			if (a.start != b.start) {
				return Integer.compare(a.start, b.start);
			}
			return Integer.compare(a.end, b.end);
		});

		// Заполняем дырки с сохранением типа (TEMPLATE_DATA или SQL_BLOCK)
		// tokensToProcess содержит и TEMPLATE_DATA, и SQL_BLOCK для определения типа дырок
		List<CoreToken> tokensToProcess = new ArrayList<>();
		for (CoreToken token : baseTokens) {
			if ("TEMPLATE_DATA".equals(token.type) || "SQL_BLOCK".equals(token.type)) {
				tokensToProcess.add(token);
			}
		}
		return fillTemplateAndSqlGaps(merged, text, tokensToProcess);
	}

	/**
	 * Применяет лексер к TEMPLATE_DATA и SQL_BLOCK токенам.
	 * Используется для лексеров которые должны работать внутри SQL-блоков
	 * (переменные, методы, скобки и т.д.)
	 */
	@NotNull
	private static List<CoreToken> applyItemsIncludingSql(@NotNull CharSequence text,
														  @NotNull List<CoreToken> baseTokens,
														  @NotNull CoreLayer layer) {

		// Разделяем токены: TEMPLATE_DATA и SQL_BLOCK идут на обработку, остальное — нет
		List<CoreToken> tokensToProcess = new ArrayList<>();
		List<CoreToken> otherTokens = new ArrayList<>();

		for (CoreToken token : baseTokens) {
			if ("TEMPLATE_DATA".equals(token.type) || "SQL_BLOCK".equals(token.type)) {
				tokensToProcess.add(token);
			} else {
				otherTokens.add(token);
			}
		}

		if (tokensToProcess.isEmpty()) {
			return baseTokens;
		}

		// Применяем лексер
		List<CoreToken> layerTokens = layer.tokenize(text, tokensToProcess);
		if (layerTokens.isEmpty()) {
			return baseTokens;
		}

		// Восстанавливаем тип SQL_BLOCK для токенов TEMPLATE_DATA которые были внутри SQL_BLOCK
		// НО: не меняем LPAREN/RPAREN и другие распознанные токены — они нужны для Parser3
		restoreSqlBlockType(layerTokens, tokensToProcess);

		// Склеиваем
		List<CoreToken> merged = new ArrayList<>(otherTokens.size() + layerTokens.size());
		merged.addAll(otherTokens);
		merged.addAll(layerTokens);

		merged.sort((a, b) -> {
			if (a.start != b.start) {
				return Integer.compare(a.start, b.start);
			}
			return Integer.compare(a.end, b.end);
		});

		// Заново заполняем дырки — и TEMPLATE_DATA, и SQL_BLOCK
		return fillTemplateAndSqlGaps(merged, text, tokensToProcess);
	}

	/**
	 * Применяет лексер к TEMPLATE_DATA и SQL_BLOCK токенам БЕЗ восстановления SQL_BLOCK.
	 * Используется для BracketLexerCore — восстановление делается отдельно после.
	 */
	@NotNull
	private static List<CoreToken> applyItemsIncludingSqlNorestore(@NotNull CharSequence text,
																   @NotNull List<CoreToken> baseTokens,
																   @NotNull CoreLayer layer) {

		List<CoreToken> tokensToProcess = new ArrayList<>();
		List<CoreToken> otherTokens = new ArrayList<>();

		for (CoreToken token : baseTokens) {
			if ("TEMPLATE_DATA".equals(token.type) || "SQL_BLOCK".equals(token.type)) {
				tokensToProcess.add(token);
			} else {
				otherTokens.add(token);
			}
		}

		if (tokensToProcess.isEmpty()) {
			return baseTokens;
		}

		List<CoreToken> layerTokens = layer.tokenize(text, tokensToProcess);
		if (layerTokens.isEmpty()) {
			return baseTokens;
		}

		// НЕ вызываем restoreSqlBlockType — это делается в restoreSqlBlockTypeFinal

		List<CoreToken> merged = new ArrayList<>(otherTokens.size() + layerTokens.size());
		merged.addAll(otherTokens);
		merged.addAll(layerTokens);

		merged.sort((a, b) -> {
			if (a.start != b.start) {
				return Integer.compare(a.start, b.start);
			}
			return Integer.compare(a.end, b.end);
		});

		return fillTemplateAndSqlGaps(merged, text, tokensToProcess);
	}

	/**
	 * Применяет ExpressionLexerCore с передачей полного списка токенов.
	 * Это нужно потому что ExpressionLexerCore должен знать позиции LPAREN/RPAREN
	 * для корректного определения контекста выражений.
	 */
	@NotNull
	private static List<CoreToken> applyExpressionsWithContext(@NotNull CharSequence text,
															   @NotNull List<CoreToken> baseTokens) {

		// Разделяем токены: TEMPLATE_DATA и SQL_BLOCK идут на обработку, остальное — нет
		List<CoreToken> tokensToProcess = new ArrayList<>();
		List<CoreToken> otherTokens = new ArrayList<>();

		for (CoreToken token : baseTokens) {
			if ("TEMPLATE_DATA".equals(token.type) || "SQL_BLOCK".equals(token.type)) {
				tokensToProcess.add(token);
			} else {
				otherTokens.add(token);
			}
		}

		if (tokensToProcess.isEmpty()) {
			return baseTokens;
		}

		// Применяем лексер с передачей всех токенов для контекста LPAREN/RPAREN
		List<CoreToken> layerTokens = Parser3ExpressionLexerCore.tokenize(text, tokensToProcess, baseTokens);
		if (layerTokens.isEmpty()) {
			return baseTokens;
		}

		// Восстанавливаем тип SQL_BLOCK
		restoreSqlBlockType(layerTokens, tokensToProcess);

		// Склеиваем
		List<CoreToken> merged = new ArrayList<>(otherTokens.size() + layerTokens.size());
		merged.addAll(otherTokens);
		merged.addAll(layerTokens);

		merged.sort((a, b) -> {
			if (a.start != b.start) {
				return Integer.compare(a.start, b.start);
			}
			return Integer.compare(a.end, b.end);
		});

		// Заново заполняем дырки — и TEMPLATE_DATA, и SQL_BLOCK
		return fillTemplateAndSqlGaps(merged, text, tokensToProcess);
	}

	/**
	 * Делегирует восстановление SQL_BLOCK к Parser3SqlRestoreUtils.
	 * @see Parser3SqlRestoreUtils#restoreSqlBlockType
	 */
	private static void restoreSqlBlockType(
			@NotNull List<CoreToken> layerTokens,
			@NotNull List<CoreToken> tokensToProcess
	) {
		Parser3SqlRestoreUtils.restoreSqlBlockType(layerTokens, tokensToProcess);
	}

	/**
	 * Делегирует финальное восстановление SQL_BLOCK к Parser3SqlRestoreUtils.
	 * @see Parser3SqlRestoreUtils#restoreSqlBlockTypeFinal
	 */
	@NotNull
	private static List<CoreToken> restoreSqlBlockTypeFinal(@NotNull List<CoreToken> tokens) {
		return Parser3SqlRestoreUtils.restoreSqlBlockTypeFinal(tokens);
	}

	/**
	 * Заполняет дырки, сохраняя тип токена (TEMPLATE_DATA или SQL_BLOCK)
	 * в зависимости от того, в каком исходном токене находится дырка.
	 */
	@NotNull
	private static List<CoreToken> fillTemplateAndSqlGaps(
			@NotNull List<CoreToken> tokens,
			@NotNull CharSequence text,
			@NotNull List<CoreToken> originalTokens
	) {
		if (originalTokens.isEmpty()) {
			return tokens;
		}

		List<CoreToken> result = new ArrayList<>();
		int current = 0;

		for (CoreToken token : tokens) {
			if (token.start > current) {
				// Есть дырка — определяем её тип по originalTokens
				fillGapWithCorrectType(result, text, current, token.start, originalTokens);
			}
			result.add(token);
			current = token.end;
		}

		// Дырка в конце
		int textLength = text.length();
		if (current < textLength) {
			fillGapWithCorrectType(result, text, current, textLength, originalTokens);
		}

		return result;
	}

	/**
	 * Заполняет дырку между start и end, определяя тип по originalTokens.
	 */
	private static void fillGapWithCorrectType(
			@NotNull List<CoreToken> result,
			@NotNull CharSequence text,
			int start,
			int end,
			@NotNull List<CoreToken> originalTokens
	) {
		// Для каждого оригинального токена проверяем пересечение с дыркой
		for (CoreToken orig : originalTokens) {
			int overlapStart = Math.max(start, orig.start);
			int overlapEnd = Math.min(end, orig.end);
			if (overlapStart < overlapEnd) {
				String debug = substringSafely(text, overlapStart, overlapEnd);
				result.add(new CoreToken(overlapStart, overlapEnd, orig.type, debug));
			}
		}
	}

	/**
	 * Постобработка: находит паттерн RBRACE + TEMPLATE_DATA(...)
	 * и превращает ( в LPAREN, ) в RPAREN.
	 * Это нужно для корректной обработки ^if(...){}(...){}
	 */

	/**
	 * Находит парную закрывающую скобку, учитывая вложенность, строки и экранирование.
	 */
	private static int findMatchingParen(@NotNull CharSequence text, int openPos, int limit) {
		return Parser3LexerUtils.findMatchingParen(text, openPos, limit);
	}

	/**
	 * Проверяет, экранирован ли символ на позиции pos символом ^.
	 * Экранирование: нечётное количество ^ перед символом.
	 */
	private static boolean isEscapedByCaret(@NotNull CharSequence text, int pos) {
		return Parser3LexerUtils.isEscapedByCaret(text, pos);
	}


	/**
	 * Получение текста с контекстом для разбора.
	 * Пока возвращаем весь fullText, но сигнатура рассчитана на будущее,
	 * когда будем резать по методам/окрестности startOffset/endOffset.
	 */
	@NotNull
	public static CharSequence getContextText(@NotNull CharSequence fullText, int startOffset, int endOffset) {
		// Если запрошен весь текст — возвращаем как есть
		if (startOffset == 0 && endOffset >= fullText.length()) {
			return fullText;
		}
		// Вырезаем нужный диапазон
		return fullText.subSequence(startOffset, Math.min(endOffset, fullText.length()));
	}

	// ------------------------------------------------------------
	// Общие вспомогательные функции (будут нужны и дальше)
	// ------------------------------------------------------------

	/**
	 * Проверка: позиция — начало строки?
	 */
	public static boolean isAtLineStart(@NotNull CharSequence text, int offset) {
		if (offset == 0) {
			return true;
		}
		char prev = text.charAt(offset - 1);
		return prev == '\n' || prev == '\r';
	}

	/**
	 * Ищем конец строки (позицию перевода строки или конца текста).
	 */
	public static int findLineEnd(@NotNull CharSequence text, int offset) {
		int length = text.length();
		int i = offset;
		while (i < length) {
			char c = text.charAt(i);
			if (c == '\n' || c == '\r') {
				break;
			}
			i++;
		}
		return i;
	}

	/**
	 * Безопасный сабстринг для отладочного текста токена.
	 */
	@Nullable
	public static String substringSafely(@NotNull CharSequence text, int start, int end) {
		if (start < 0) {
			start = 0;
		}
		int length = text.length();
		if (end > length) {
			end = length;
		}
		if (start >= end) {
			return null;
		}
		return text.subSequence(start, end).toString();
	}

	// ------------------------------------------------------------
	private static void addGapTokens(@NotNull List<CoreToken> result, @NotNull CharSequence text, int start, int end) {
		if (start >= end) {
			return;
		}


















		String debug = substringSafely(text, start, end);

		result.add(new CoreToken(start, end, "TEMPLATE_DATA", debug));
	}

	private static boolean isStructuralWhitespace(char c) {
		return c == '\n' || c == '\r' || c == '\t';
	}


	@NotNull
	private static List<CoreToken> splitWhitespaceTokens(
			@NotNull List<CoreToken> tokens,
			@NotNull CharSequence text,
			@NotNull CharSequence fullText,
			int globalOffset
	) {
		List<CoreToken> result = new ArrayList<>(tokens.size());
		for (CoreToken token : tokens) {
			// Обрабатываем TEMPLATE_DATA, HTML_DATA, CSS_DATA, JS_DATA
			boolean isTemplateData = "TEMPLATE_DATA".equals(token.type);
			boolean isHtmlData = "HTML_DATA".equals(token.type);
			boolean isCssData = "CSS_DATA".equals(token.type);
			boolean isJsData = "JS_DATA".equals(token.type);
			if (!isTemplateData && !isHtmlData && !isCssData && !isJsData) {
				result.add(token);
				continue;
			}
			// Запоминаем исходный тип для создания токенов контента
			String contentType = token.type;

			int start = token.start;
			int end = token.end;
			if (start >= end) {
				continue;
			}
			int i = start;

			// Определяем, находимся ли мы всё ещё в отступе текущей строки.
			// Это нужно, чтобы пробелы между TAB-ами в начале HTML-строки тоже считались WHITE_SPACE.
			int globalStart = start + globalOffset;
			boolean atLineStart = isLineIndentPosition(fullText, globalStart);

			while (i < end) {
				char c = text.charAt(i);
				if (c == '\n' || c == '\r') {
					int wsStart = i;
					i++;
					String debug = substringSafely(text, wsStart, i);
					result.add(new CoreToken(wsStart, i, "WHITE_SPACE", debug));
					atLineStart = true;
					continue;
				}
				if (c == '\t') {
					int wsStart = i;
					i++;
					String debug = substringSafely(text, wsStart, i);
					result.add(new CoreToken(wsStart, i, "WHITE_SPACE", debug));
					continue;
				}
				if (c == ' ' && atLineStart) {
					int wsStart = i;
					while (i < end && text.charAt(i) == ' ') {
						i++;
					}
					String debug = substringSafely(text, wsStart, i);
					result.add(new CoreToken(wsStart, i, "WHITE_SPACE", debug));
					continue;
				}
				int dataStart = i;
				// Сбрасываем atLineStart сразу при первом непробельном символе,
				// чтобы пробелы внутри строки не считались "пробелами в начале строки"
				atLineStart = false;
				while (i < end) {
					char cc = text.charAt(i);
					if (cc == '\n' || cc == '\r' || cc == '\t') {
						break;
					}
					i++;
				}
				String debug = substringSafely(text, dataStart, i);
				// Сохраняем исходный тип (TEMPLATE_DATA, HTML_DATA, CSS_DATA или JS_DATA)
				result.add(new CoreToken(dataStart, i, contentType, debug));
			}
		}
		return result;
	}

	private static boolean isLineIndentPosition(@NotNull CharSequence fullText, int offset) {
		if (offset <= 0) {
			return true;
		}
		for (int i = offset - 1; i >= 0; i--) {
			char c = fullText.charAt(i);
			if (c == '\n' || c == '\r') {
				return true;
			}
			if (c != ' ' && c != '\t') {
				return false;
			}
		}
		return true;
	}



	/**
	 * Дополнительный проход по STRING: если токен состоит ТОЛЬКО из пробельных символов
	 * (пробелы, табы, переводы строк), превращаем его в WHITE_SPACE.
	 * Если в строке есть непробельные символы — отделяем ведущие и завершающие
	 * пробельные символы как WHITE_SPACE, середину оставляем как STRING.
	 */
	private static List<CoreToken> splitWhitespaceInsideStrings(@NotNull List<CoreToken> tokens, @NotNull CharSequence text) {
		if (tokens.isEmpty()) {
			return tokens;
		}
		List<CoreToken> result = new ArrayList<>(tokens.size());
		for (CoreToken token : tokens) {
			if (!"STRING".equals(token.type)) {
				result.add(token);
				continue;
			}
			int start = token.start;
			int end = token.end;
			if (start >= end) {
				continue;
			}
			CharSequence seq = text.subSequence(start, end);
			int len = seq.length();

			// Ищем первый непробельный символ
			int firstNonWs = -1;
			for (int i = 0; i < len; i++) {
				if (!Character.isWhitespace(seq.charAt(i))) {
					firstNonWs = i;
					break;
				}
			}

			if (firstNonWs == -1) {
				// Токен состоит только из пробелов — превращаем в WHITE_SPACE
				String debug = substringSafely(text, start, end);
				result.add(new CoreToken(start, end, "WHITE_SPACE", debug));
			} else {
				if (shouldKeepMixedStringWhole(seq, firstNonWs)) {
					// HTML-подобные строки сохраняем целиком, иначе ломается форматирование инжекций.
					result.add(token);
					continue;
				}

				// Ищем последний непробельный символ
				int lastNonWs = len - 1;
				while (lastNonWs > firstNonWs && Character.isWhitespace(seq.charAt(lastNonWs))) {
					lastNonWs--;
				}

				// Ведущие пробелы → WHITE_SPACE
				if (firstNonWs > 0) {
					String debug = substringSafely(text, start, start + firstNonWs);
					result.add(new CoreToken(start, start + firstNonWs, "WHITE_SPACE", debug));
				}

				// Середина → STRING
				int strStart = start + firstNonWs;
				int strEnd = start + lastNonWs + 1;
				String debug = substringSafely(text, strStart, strEnd);
				result.add(new CoreToken(strStart, strEnd, "STRING", debug));

				// Завершающие пробелы → WHITE_SPACE
				if (lastNonWs + 1 < len) {
					int wsStart = start + lastNonWs + 1;
					String debugWs = substringSafely(text, wsStart, end);
					result.add(new CoreToken(wsStart, end, "WHITE_SPACE", debugWs));
				}
			}
		}
		return result;
	}

	private static boolean shouldKeepMixedStringWhole(@NotNull CharSequence seq, int firstNonWs) {
		int idx = firstNonWs;
		if (idx >= seq.length()) {
			return false;
		}

		char c = seq.charAt(idx);
		if (c != '<') {
			return false;
		}

		if (idx + 1 >= seq.length()) {
			return false;
		}

		char next = seq.charAt(idx + 1);
		return Character.isLetter(next) || next == '/' || next == '!' || next == '?';
	}

	@NotNull
	private static List<CoreToken> mergeIndentedWhitespaceIntoHtmlStrings(@NotNull List<CoreToken> tokens) {
		if (tokens.isEmpty()) {
			return tokens;
		}

		List<CoreToken> result = new ArrayList<>(tokens.size());
		for (int i = 0; i < tokens.size(); i++) {
			CoreToken token = tokens.get(i);
			if (!"WHITE_SPACE".equals(token.type) || i + 1 >= tokens.size()) {
				result.add(token);
				continue;
			}

			CoreToken next = tokens.get(i + 1);
			if (!"STRING".equals(next.type)
					|| token.debugText == null
					|| next.debugText == null
					|| !containsLineBreak(token.debugText)
					|| !looksLikeHtmlText(next.debugText)) {
				result.add(token);
				continue;
			}

			result.add(new CoreToken(
					token.start,
					next.end,
					"STRING",
					token.debugText + next.debugText
			));
			i++;
		}
		return result;
	}

	private static boolean containsLineBreak(@NotNull String text) {
		return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
	}

	private static boolean looksLikeHtmlText(@NotNull String text) {
		if (text.isEmpty() || text.charAt(0) != '<' || text.length() < 2) {
			return false;
		}
		char next = text.charAt(1);
		return Character.isLetter(next) || next == '/' || next == '!' || next == '?';
	}


	@NotNull
	static List<CoreToken> fillTemplateGaps(@NotNull List<CoreToken> commentTokens, @NotNull CharSequence text) {
		int textLength = text.length();

		List<CoreToken> result = new ArrayList<>();
		int current = 0;

		for (CoreToken comment : commentTokens) {
			if (comment.start > current) {
				addGapTokens(result, text, current, comment.start);
			}
			result.add(comment);
			current = comment.end;
		}

		if (current < textLength) {
			addGapTokens(result, text, current, textLength);
		}

		return result;
	}

	private static volatile Supplier<List<String>> userSqlInjectionPrefixesSupplier = List::of;

	public static void setUserSqlInjectionPrefixesSupplier(@NotNull Supplier<List<String>> supplier) {
		userSqlInjectionPrefixesSupplier = supplier;
	}

	public static @NotNull List<String> getUserSqlInjectionPrefixes() {
		Supplier<List<String>> s = userSqlInjectionPrefixesSupplier;
		if (s == null) {
			return List.of();
		}
		List<String> v = s.get();
		return v != null ? v : List.of();
	}
}
