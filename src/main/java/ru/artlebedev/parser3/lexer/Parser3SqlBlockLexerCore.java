package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static ru.artlebedev.parser3.lexer.Parser3LexerCore.substringSafely;

/**
 * SQL-лексер для Parser3.
 *
 * Подход:
 * 1. Находим границы sql{...} блока
 * 2. Весь контент между { и } помечаем как SQL_BLOCK (без выкусывания отступов!)
 * 3. Директивы внутри (^if, ^for и т.д.) исключаем — их содержимое остаётся TEMPLATE_DATA,
 *    но содержимое их тел {} рекурсивно помечается как SQL_BLOCK
 *
 * Выкусывание отступов происходит позже, в Parser3SqlIndentPostProcessor,
 * после того как все токены (включая LBRACE/RBRACE) уже размечены.
 */
public class Parser3SqlBlockLexerCore {

	static int findSqlBlockEndForSinglePass(@NotNull CharSequence text, int from, int limit) {
		int closePos = findSqlBlockEnd(text, from, limit);
		if (closePos < 0) {
			closePos = findSqlBlockEndSimple(text, from, limit);
		}
		return closePos;
	}

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
		int i = 0;

		while (i < length) {
			char c = text.charAt(i);
			if (c != '^') {
				i++;
				continue;
			}

			// Проверяем экранирование: ^^ — это не начало метода
			if (isEscaped(text, i)) {
				i++;
				continue;
			}

			// Пробуем пользовательские SQL-инъекции (например ^myMethod{)
			int userLbracePos = matchUserSqlInjection(text, i, length);
			if (userLbracePos >= 0) {
				int closePos = findSqlBlockEnd(text, userLbracePos + 1, length);
				// Fallback если не нашли с учётом кавычек
				if (closePos < 0) {
					closePos = findSqlBlockEndSimple(text, userLbracePos + 1, length);
				}
				if (closePos > userLbracePos + 1) {
					addSqlBlockTokens(result, text, userLbracePos + 1, closePos, templateTokens);
					i = closePos + 1;
					continue;
				}
			}

			// Пробуем стандартные конструкции: ^table::sql{, ^void:sql{ и т.д.
			int lbracePos = matchStandardSqlConstruct(text, i, length);
			if (lbracePos >= 0) {
				int closePos = findSqlBlockEnd(text, lbracePos + 1, length);
				// Если не нашли с учётом кавычек — пробуем простой поиск по балансу скобок
				if (closePos < 0) {
					closePos = findSqlBlockEndSimple(text, lbracePos + 1, length);
				}
				if (closePos > lbracePos + 1) {
					addSqlBlockTokens(result, text, lbracePos + 1, closePos, templateTokens);
					i = closePos + 1;
					continue;
				}
			}

			i++;
		}

		return result;
	}

	/**
	 * Проверяет пользовательские SQL-инъекции (настраиваемые в Settings).
	 * @return позиция '{' или -1 если не совпало
	 */
	private static int matchUserSqlInjection(@NotNull CharSequence text, int caretPos, int length) {
		List<String> prefixes = Parser3LexerCore.getUserSqlInjectionPrefixes();
		if (prefixes.isEmpty()) {
			return -1;
		}

		for (String prefix : prefixes) {
			if (prefix == null || prefix.trim().isEmpty()) {
				continue;
			}
			String p = prefix.trim();
			int pLen = p.length();
			if (caretPos + pLen >= length) {
				continue;
			}
			if (!regionMatches(text, caretPos, p)) {
				continue;
			}
			int lbracePos = caretPos + pLen;
			if (lbracePos < length && text.charAt(lbracePos) == '{' && !isEscapedBrace(text, lbracePos)) {
				return lbracePos;
			}
		}
		return -1;
	}

	/**
	 * Проверяет стандартные SQL-конструкции: ^table::sql{, ^void:sql{, и т.д.
	 * @return позиция '{' или -1 если не совпало
	 */
	private static int matchStandardSqlConstruct(@NotNull CharSequence text, int caretPos, int length) {
		int nameStart = caretPos + 1;
		if (nameStart >= length) {
			return -1;
		}

		// Читаем имя типа (table, void, hash, и т.д.)
		int p = nameStart;
		char ch = text.charAt(p);
		if (!Character.isLetter(ch) && ch != '_') {
			return -1;
		}
		p++;
		while (p < length) {
			ch = text.charAt(p);
			if (Character.isLetterOrDigit(ch) || ch == '_') {
				p++;
			} else {
				break;
			}
		}

		String typeName = text.subSequence(nameStart, p).toString();
		if (!isSqlConstructorName(typeName)) {
			return -1;
		}

		// Ожидаем : или ::
		if (p >= length || text.charAt(p) != ':') {
			return -1;
		}
		p++;
		if (p < length && text.charAt(p) == ':') {
			p++;
		}

		// Ожидаем "sql"
		if (!regionMatches(text, p, "sql")) {
			return -1;
		}
		p += 3;

		// Пропускаем пробелы
		while (p < length && Character.isWhitespace(text.charAt(p))) {
			p++;
		}

		// Ожидаем {
		if (p >= length || text.charAt(p) != '{') {
			return -1;
		}

		if (isEscapedBrace(text, p)) {
			return -1;
		}

		return p;
	}

	/**
	 * Добавляет SQL_BLOCK токены для содержимого между { и }.
	 * Исключает заголовки директив (^if, ^for и т.д.) — там остаётся TEMPLATE_DATA,
	 * но содержимое их тел {} рекурсивно помечается как SQL_BLOCK.
	 */
	private static void addSqlBlockTokens(
			@NotNull List<Parser3LexerCore.CoreToken> result,
			@NotNull CharSequence text,
			int contentStart,
			int contentEnd,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens
	) {
		// Проверяем что контент внутри TEMPLATE_DATA токенов
		if (!isInsideTemplateTokens(contentStart, contentEnd, templateTokens)) {
			return;
		}

		// Находим диапазоны SQL (исключая заголовки директив)
		List<int[]> sqlRanges = findSqlRanges(text, contentStart, contentEnd);

		// Для каждого SQL-диапазона добавляем SQL_BLOCK токены
		for (int[] range : sqlRanges) {
			addSqlRangeTokens(result, text, range[0], range[1], templateTokens);
		}
	}

	/**
	 * Находит диапазоны "чистого" SQL внутри блока.
	 * Для директив ^if(){} рекурсивно обрабатывает содержимое {} как SQL.
	 * Возвращает список [start, end] диапазонов.
	 */
	private static List<int[]> findSqlRanges(@NotNull CharSequence text, int from, int to) {
		List<int[]> ranges = new ArrayList<>();

		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		int bracketDepth = 0; // глубина [] - внутри них ; не является разделителем Parser3
		int sqlStart = from;

		int i = from;
		while (i < to) {
			char c = text.charAt(i);

			// Обработка кавычек
			if (c == '\'' && !inDoubleQuote && !isEscaped(text, i)) {
				inSingleQuote = !inSingleQuote;
				i++;
				continue;
			}
			if (c == '"' && !inSingleQuote && !isEscaped(text, i)) {
				inDoubleQuote = !inDoubleQuote;
				i++;
				continue;
			}

			// ^ прерывает SQL-строку — это Parser3 вставка
			if (c == '^' && !isEscaped(text, i)) {
				inSingleQuote = false;
				inDoubleQuote = false;
				// Продолжаем обработку ^ ниже (не делаем continue)
			}

			// Внутри строки — пропускаем
			if (inSingleQuote || inDoubleQuote) {
				i++;
				continue;
			}

			// Отслеживаем глубину [] скобок
			if (c == '[' && !isEscaped(text, i)) {
				bracketDepth++;
				i++;
				continue;
			}
			if (c == ']' && !isEscaped(text, i)) {
				if (bracketDepth > 0) {
					bracketDepth--;
				}
				i++;
				continue;
			}

			// ; — разделитель Parser3 (не SQL!), НО только вне [] скобок
			// Внутри [k;v] — это разделитель параметров, не альтернатив
			// ^; — экранированный, это просто символ ;
			// ^^; — это ^^ (литерал ^) + ; (разделитель, не SQL)
			if (c == ';' && !isEscaped(text, i) && bracketDepth == 0) {
				// Закрываем текущий SQL-диапазон до ;
				if (sqlStart < i) {
					ranges.add(new int[]{sqlStart, i});
				}
				// ; не включаем в SQL — пропускаем его
				i++;
				sqlStart = i;
				continue;
			}

			// ^ — начало директивы?
			if (c == '^' && !isEscaped(text, i)) {
				// Проверяем что это вложенный sql{} блок
				if (isNestedSqlBlock(text, i, to)) {
					// Это вложенный sql{} — его содержимое тоже SQL
					// Закрываем текущий SQL-диапазон до ^
					if (sqlStart < i) {
						ranges.add(new int[]{sqlStart, i});
					}

					// Находим позицию { и содержимое
					int lbracePos = findNestedSqlLbrace(text, i, to);
					if (lbracePos >= 0) {
						int closePos = findMatchingBrace(text, lbracePos, to);
						if (closePos > lbracePos + 1) {
							// РЕКУРСИВНО обрабатываем содержимое вложенного sql{}
							// Там тоже могут быть директивы и вложенные sql{}
							List<int[]> innerRanges = findSqlRanges(text, lbracePos + 1, closePos);
							ranges.addAll(innerRanges);
							// Продолжаем после вложенного блока
							i = closePos + 1;
							sqlStart = i;
							continue;
						}
					}
					// Не нашли закрывающую скобку — пропускаем ^ и продолжаем
					i++;
					continue;
				}

				// Проверяем что это директива с телом {}
				Parser3LexerUtils.DirectiveInfo info = findDirectiveInfo(text, i, to);
				if (info != null && info.hasBody) {
					// Нашли директиву — закрываем текущий SQL-диапазон до ^
					if (sqlStart < i) {
						ranges.add(new int[]{sqlStart, i});
					}

					// Рекурсивно добавляем SQL из тел директивы
					for (int[] bodyRange : info.bodyRanges) {
						List<int[]> innerRanges = findSqlRanges(text, bodyRange[0], bodyRange[1]);
						ranges.addAll(innerRanges);
					}

					// Продолжаем после директивы
					i = info.endPos;
					sqlStart = i;
					continue;
				}
			}

			i++;
		}

		// Закрываем последний диапазон
		if (sqlStart < to) {
			ranges.add(new int[]{sqlStart, to});
		}

		// ВАЖНО: сортируем диапазоны по начальной позиции
		// Они могут идти не по порядку из-за рекурсивной обработки вложенных директив
		ranges.sort((a, b) -> Integer.compare(a[0], b[0]));

		// Удаляем перекрывающиеся диапазоны — оставляем только непересекающиеся части
		List<int[]> nonOverlapping = new ArrayList<>();
		for (int[] range : ranges) {
			if (range[0] >= range[1]) {
				continue; // Пропускаем пустые диапазоны
			}

			if (nonOverlapping.isEmpty()) {
				nonOverlapping.add(range);
			} else {
				int[] last = nonOverlapping.get(nonOverlapping.size() - 1);
				if (range[0] >= last[1]) {
					// Не пересекается — добавляем
					nonOverlapping.add(range);
				} else if (range[0] > last[0]) {
					// Частично пересекается — берём часть после last
					if (range[1] > last[1]) {
						nonOverlapping.add(new int[]{last[1], range[1]});
					}
					// Если range полностью внутри last — пропускаем
				}
				// Если range[0] <= last[0] — что-то странное, пропускаем
			}
		}

		return nonOverlapping;
	}

	/**
	 * Находит информацию о директиве. Делегирует в общую функцию.
	 */
	private static Parser3LexerUtils.DirectiveInfo findDirectiveInfo(@NotNull CharSequence text, int caretPos, int limit) {
		return Parser3LexerUtils.findDirectiveInfo(text, caretPos, limit);
	}

	/**
	 * Находит парную скобку, учитывая вложенность и строки.
	 */
	private static int findMatchingParen(@NotNull CharSequence text, int openPos, int limit, char openCh, char closeCh) {
		return Parser3LexerUtils.findMatchingBracket(text, openPos, limit, openCh, closeCh);
	}

	/**
	 * Добавляет SQL_BLOCK токены для одного диапазона SQL.
	 * Просто помечает весь диапазон как SQL_BLOCK, без выкусывания отступов.
	 */
	private static void addSqlRangeTokens(
			@NotNull List<Parser3LexerCore.CoreToken> result,
			@NotNull CharSequence text,
			int rangeStart,
			int rangeEnd,
			@NotNull List<Parser3LexerCore.CoreToken> templateTokens
	) {
		// Находим пересечения с TEMPLATE_DATA токенами и помечаем как SQL_BLOCK
		for (Parser3LexerCore.CoreToken t : templateTokens) {
			if (!"TEMPLATE_DATA".equals(t.type)) {
				continue;
			}
			int segmentStart = Math.max(rangeStart, t.start);
			int segmentEnd = Math.min(rangeEnd, t.end);
			if (segmentStart < segmentEnd) {
				String debug = substringSafely(text, segmentStart, segmentEnd);
				result.add(new Parser3LexerCore.CoreToken(segmentStart, segmentEnd, "SQL_BLOCK", debug));
			}
		}
	}

	/**
	 * Находит закрывающую } для SQL-блока.
	 *
	 * Алгоритм:
	 * 1. Отслеживаем SQL-кавычки ' и "
	 * 2. Внутри кавычек скобки { } не считаем
	 * 3. ^X (экранирование) — пропускаем символ после ^
	 * 4. ^буква (Parser3 вставка) — пропускаем всю конструкцию, сохраняя состояние кавычек
	 */
	private static int findSqlBlockEnd(@NotNull CharSequence text, int from, int limit) {
		int length = text.length();
		int depth = 1;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;

		int j = from;
		while (j < limit && j < length) {
			char c = text.charAt(j);

			// ^ — проверяем что после него
			if (c == '^' && j + 1 < length) {
				char next = text.charAt(j + 1);

				// ^X где X — спецсимвол: пропускаем оба символа
				if (next == '{' || next == '}' || next == '\'' || next == '"' ||
						next == '[' || next == ']' || next == '(' || next == ')' ||
						next == '$' || next == ';' || next == '^' || next == '#') {
					j += 2;
					continue;
				}

				// ^буква — Parser3 вставка, пропускаем её целиком
				if (Character.isLetter(next) || next == '_') {
					j = skipParser3Insertion(text, j, limit);
					continue;
				}

				// Просто ^ — пропускаем
				j++;
				continue;
			}

			// $ — тоже Parser3 вставка
			if (c == '$' && j + 1 < length) {
				char next = text.charAt(j + 1);
				if (Character.isLetter(next) || next == '_') {
					j = skipParser3Variable(text, j, limit);
					continue;
				}
			}

			// Обработка кавычек
			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				j++;
				continue;
			}
			if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
				j++;
				continue;
			}

			// Внутри SQL-строки — скобки не считаем
			if (inSingleQuote || inDoubleQuote) {
				j++;
				continue;
			}

			// Считаем скобки
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return j;
				}
			}

			j++;
		}

		return -1;
	}

	/**
	 * Простой поиск закрывающей } по балансу скобок.
	 * Используется как fallback когда основной поиск не нашёл (из-за незакрытых кавычек).
	 * Учитывает кавычки, но сбрасывает состояние на переводе строки
	 * (считаем что незакрытая кавычка заканчивается на конце строки).
	 */
	private static int findSqlBlockEndSimple(@NotNull CharSequence text, int from, int limit) {
		int length = text.length();
		int depth = 1;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;

		int j = from;
		while (j < limit && j < length) {
			char c = text.charAt(j);

			// Перевод строки — сбрасываем состояние кавычек
			if (c == '\n' || c == '\r') {
				inSingleQuote = false;
				inDoubleQuote = false;
				j++;
				continue;
			}

			// Экранирование ^X
			if (c == '^' && j + 1 < length) {
				char next = text.charAt(j + 1);
				if (next == '{' || next == '}' || next == '\'' || next == '"') {
					j += 2;
					continue;
				}
			}

			// Кавычки
			if (c == '\'' && !inDoubleQuote) {
				inSingleQuote = !inSingleQuote;
				j++;
				continue;
			}
			if (c == '"' && !inSingleQuote) {
				inDoubleQuote = !inDoubleQuote;
				j++;
				continue;
			}

			// Внутри кавычек — пропускаем
			if (inSingleQuote || inDoubleQuote) {
				j++;
				continue;
			}

			// Скобки
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return j;
				}
			}

			j++;
		}

		return -1;
	}

	/**
	 * Пропускает Parser3 вставку типа ^method:chain[...]
	 * НЕ пропускает {...} — они должны считаться в основном цикле!
	 * Возвращает позицию ПОСЛЕ вставки.
	 */
	private static int skipParser3Insertion(@NotNull CharSequence text, int from, int limit) {
		int j = from + 1; // Пропускаем ^
		int length = text.length();

		// Пропускаем имя метода и цепочки вызовов
		while (j < limit && j < length) {
			char c = text.charAt(j);

			// Имя метода/переменной
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				j++;
				continue;
			}

			// : или :: — продолжение цепочки
			if (c == ':') {
				j++;
				continue;
			}

			// . — вызов метода объекта
			if (c == '.') {
				j++;
				continue;
			}

			// [...] — аргументы (пропускаем)
			if (c == '[') {
				j = skipBrackets(text, j, limit, '[', ']');
				continue;
			}

			// (...) — условие для ^if и т.д. (пропускаем)
			if (c == '(') {
				j = skipBrackets(text, j, limit, '(', ')');
				continue;
			}

			// {...} — НЕ пропускаем! Они должны считаться в основном цикле
			// Просто выходим, и основной цикл увидит { и увеличит depth

			// Что-то другое — конец вставки
			break;
		}

		return j;
	}

	/**
	 * Пропускает переменную $name или $name.field[...]
	 */
	private static int skipParser3Variable(@NotNull CharSequence text, int from, int limit) {
		int j = from + 1; // Пропускаем $
		int length = text.length();

		while (j < limit && j < length) {
			char c = text.charAt(j);

			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				j++;
				continue;
			}

			if (c == '.') {
				j++;
				continue;
			}

			if (c == '[') {
				j = skipBrackets(text, j, limit, '[', ']');
				continue;
			}

			break;
		}

		return j;
	}

	/**
	 * Пропускает сбалансированные скобки [...] или (...).
	 * НЕ пропускает {...} — возвращает позицию перед { если встретилась.
	 */
	private static int skipBrackets(@NotNull CharSequence text, int from, int limit, char open, char close) {
		int j = from + 1; // Пропускаем открывающую скобку
		int length = text.length();
		int depth = 1;

		while (j < limit && j < length && depth > 0) {
			char c = text.charAt(j);

			// Экранирование
			if (c == '^' && j + 1 < length) {
				char next = text.charAt(j + 1);
				if (next == open || next == close || next == '^' || next == '{' || next == '}') {
					j += 2;
					continue;
				}
			}

			// Если встретили { — выходим, не пропускаем дальше
			// Эта { должна быть посчитана в основном цикле
			if (c == '{') {
				return j;
			}

			if (c == open) {
				depth++;
			} else if (c == close) {
				depth--;
			}

			j++;
		}

		return j;
	}

	/**
	 * Проверяет что диапазон пересекается с TEMPLATE_DATA токенами.
	 */
	private static boolean isInsideTemplateTokens(int start, int end, @NotNull List<Parser3LexerCore.CoreToken> templateTokens) {
		for (Parser3LexerCore.CoreToken t : templateTokens) {
			if (start < t.end && end > t.start) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Проверяет является ли конструкция вложенным sql{} блоком.
	 * Например: ^int:sql{...}, ^table::sql{...}
	 */
	private static boolean isNestedSqlBlock(@NotNull CharSequence text, int caretPos, int limit) {
		int i = caretPos + 1;
		if (i >= limit) {
			return false;
		}

		// Читаем имя типа
		int nameStart = i;
		while (i < limit && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
			i++;
		}
		if (i == nameStart) {
			return false;
		}

		String typeName = text.subSequence(nameStart, i).toString();
		if (!isSqlConstructorName(typeName)) {
			return false;
		}

		// Ожидаем : или ::
		if (i >= limit || text.charAt(i) != ':') {
			return false;
		}
		i++;
		if (i < limit && text.charAt(i) == ':') {
			i++;
		}

		// Ожидаем "sql"
		if (!regionMatches(text, i, "sql")) {
			return false;
		}
		i += 3;

		// Пропускаем пробелы
		while (i < limit && Character.isWhitespace(text.charAt(i))) {
			i++;
		}

		// Ожидаем {
		return i < limit && text.charAt(i) == '{';
	}

	/**
	 * Находит позицию { во вложенном sql{} блоке.
	 */
	private static int findNestedSqlLbrace(@NotNull CharSequence text, int caretPos, int limit) {
		int i = caretPos + 1;

		// Пропускаем имя типа
		while (i < limit && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
			i++;
		}

		// Пропускаем : или ::
		if (i < limit && text.charAt(i) == ':') {
			i++;
		}
		if (i < limit && text.charAt(i) == ':') {
			i++;
		}

		// Пропускаем "sql"
		if (regionMatches(text, i, "sql")) {
			i += 3;
		}

		// Пропускаем пробелы
		while (i < limit && Character.isWhitespace(text.charAt(i))) {
			i++;
		}

		// Теперь на {
		if (i < limit && text.charAt(i) == '{') {
			return i;
		}
		return -1;
	}

	/**
	 * Находит парную закрывающую скобку.
	 */
	private static int findMatchingBrace(@NotNull CharSequence text, int openPos, int limit) {
		int depth = 1;
		boolean inSingle = false;
		boolean inDouble = false;

		for (int i = openPos + 1; i < limit; i++) {
			char c = text.charAt(i);

			if (c == '\'' && !inDouble && !isEscaped(text, i)) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle && !isEscaped(text, i)) {
				inDouble = !inDouble;
			} else if (c == '^' && !isEscaped(text, i)) {
				// ^ прерывает SQL-строку
				inSingle = false;
				inDouble = false;
			}

			if (!inSingle && !inDouble) {
				if (c == '{' && !isEscapedBrace(text, i)) {
					depth++;
				} else if (c == '}' && !isEscapedBrace(text, i)) {
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
	 * Находит конец вложенного sql{} блока.
	 * Возвращает позицию после закрывающей }.
	 */
	private static int findNestedSqlBlockEnd(@NotNull CharSequence text, int caretPos, int limit) {
		int lbracePos = findNestedSqlLbrace(text, caretPos, limit);
		if (lbracePos < 0) {
			return caretPos;
		}
		int closePos = findMatchingBrace(text, lbracePos, limit);
		return closePos >= 0 ? closePos + 1 : caretPos;
	}

	/**
	 * Проверяет является ли имя типа допустимым для SQL-конструкции.
	 */
	private static boolean isSqlConstructorName(@NotNull String name) {
		switch (name) {
			case "table":
			case "string":
			case "void":
			case "hash":
			case "int":
			case "double":
			case "file":
			case "array":
				return true;
			default:
				return false;
		}
	}

	/**
	 * Проверяет экранирование: предыдущий символ — ^
	 */
	private static boolean isEscaped(@NotNull CharSequence text, int pos) {
		return Parser3LexerUtils.isEscapedByCaret(text, pos);
	}

	/**
	 * Проверяет экранирование скобки: ^{ или ^}
	 */
	private static boolean isEscapedBrace(@NotNull CharSequence text, int pos) {
		return Parser3LexerUtils.isEscapedByCaret(text, pos);
	}

	/**
	 * Сравнивает подстроку с ключевым словом.
	 */
	private static boolean regionMatches(@NotNull CharSequence text, int offset, @NotNull String keyword) {
		int length = text.length();
		int kwLen = keyword.length();
		if (offset < 0 || offset + kwLen > length) {
			return false;
		}
		for (int i = 0; i < kwLen; i++) {
			if (text.charAt(offset + i) != keyword.charAt(i)) {
				return false;
			}
		}
		return true;
	}
}
