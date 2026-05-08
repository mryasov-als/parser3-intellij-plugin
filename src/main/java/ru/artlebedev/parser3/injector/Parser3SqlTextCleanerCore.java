package ru.artlebedev.parser3.injector;

import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.lexer.Parser3LexerUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Быстрая очистка SQL-контента от Parser3-конструкций без полного лексера.
 *
 * Нужна для индексации, где важно быстро получить "почти чистый SQL":
 * - SQL-текст и whitespace сохраняются как есть
 * - Parser3-вставки (^method[], $var, ^if(...){...}) вырезаются
 * - тела директив рекурсивно сохраняются как SQL
 */
final class Parser3SqlTextCleanerCore {

	private Parser3SqlTextCleanerCore() {
	}

	@NotNull
	static String cleanSqlContent(@NotNull CharSequence text) {
		if (text.isEmpty()) {
			return "";
		}

		List<int[]> ranges = findSqlRanges(text, 0, text.length());
		if (ranges.isEmpty()) {
			return "";
		}

		StringBuilder result = new StringBuilder(text.length());
		int cursor = 0;
		boolean lastWasParser3 = false;

		for (int[] range : ranges) {
			int start = range[0];
			int end = range[1];
			if (start >= end) {
				continue;
			}

			if (cursor < start && !lastWasParser3) {
				result.append(' ');
				lastWasParser3 = true;
			}

			result.append(text, start, end);
			cursor = end;
			lastWasParser3 = false;
		}

		if (cursor < text.length() && !lastWasParser3) {
			result.append(' ');
		}

		return result.toString();
	}

	@NotNull
	private static List<int[]> findSqlRanges(@NotNull CharSequence text, int from, int to) {
		List<int[]> ranges = new ArrayList<>();

		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		int bracketDepth = 0;
		int sqlStart = from;
		int i = from;

		while (i < to) {
			char c = text.charAt(i);

			if (c == '^' && !Parser3LexerUtils.isEscapedByCaret(text, i) && i + 1 < to) {
				char next = text.charAt(i + 1);

				if (isEscapedLiteral(next)) {
					i += 2;
					continue;
				}

				if (Character.isLetter(next) || next == '_') {
					if (sqlStart < i) {
						ranges.add(new int[]{sqlStart, i});
					}

					Parser3LexerUtils.DirectiveInfo info = Parser3LexerUtils.findDirectiveInfo(text, i, to);
					if (info != null && info.hasBody) {
						for (int[] bodyRange : info.bodyRanges) {
							ranges.addAll(findSqlRanges(text, bodyRange[0], bodyRange[1]));
						}
						i = info.endPos;
					} else {
						i = skipParser3Insertion(text, i, to);
					}

					sqlStart = i;
					inSingleQuote = false;
					inDoubleQuote = false;
					continue;
				}
			}

			if (c == '$' && !Parser3LexerUtils.isEscapedByCaret(text, i) && looksLikeVariableStart(text, i, to)) {
				if (sqlStart < i) {
					ranges.add(new int[]{sqlStart, i});
				}
				i = skipParser3Variable(text, i, to);
				sqlStart = i;
				continue;
			}

			if (c == '\'' && !inDoubleQuote && !Parser3LexerUtils.isEscapedByCaret(text, i)) {
				inSingleQuote = !inSingleQuote;
				i++;
				continue;
			}
			if (c == '"' && !inSingleQuote && !Parser3LexerUtils.isEscapedByCaret(text, i)) {
				inDoubleQuote = !inDoubleQuote;
				i++;
				continue;
			}

			if (inSingleQuote || inDoubleQuote) {
				i++;
				continue;
			}

			if (c == '[' && !Parser3LexerUtils.isEscapedByCaret(text, i)) {
				bracketDepth++;
				i++;
				continue;
			}
			if (c == ']' && !Parser3LexerUtils.isEscapedByCaret(text, i)) {
				if (bracketDepth > 0) {
					bracketDepth--;
				}
				i++;
				continue;
			}

			if (c == ';' && !Parser3LexerUtils.isEscapedByCaret(text, i) && bracketDepth == 0) {
				if (sqlStart < i) {
					ranges.add(new int[]{sqlStart, i});
				}
				i++;
				sqlStart = i;
				continue;
			}

			i++;
		}

		if (sqlStart < to) {
			ranges.add(new int[]{sqlStart, to});
		}

		return normalizeRanges(ranges);
	}

	@NotNull
	private static List<int[]> normalizeRanges(@NotNull List<int[]> ranges) {
		if (ranges.isEmpty()) {
			return ranges;
		}

		ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
		List<int[]> result = new ArrayList<>();

		for (int[] range : ranges) {
			if (range[0] >= range[1]) {
				continue;
			}

			if (result.isEmpty()) {
				result.add(range);
				continue;
			}

			int[] last = result.get(result.size() - 1);
			if (range[0] >= last[1]) {
				result.add(range);
				continue;
			}

			if (range[1] > last[1]) {
				result.add(new int[]{last[1], range[1]});
			}
		}

		return result;
	}

	private static boolean isEscapedLiteral(char ch) {
		return ch == '{' || ch == '}' || ch == '\'' || ch == '"' ||
				ch == '[' || ch == ']' || ch == '(' || ch == ')' ||
				ch == '$' || ch == ';' || ch == '^' || ch == '#';
	}

	private static boolean looksLikeVariableStart(@NotNull CharSequence text, int offset, int limit) {
		if (offset + 1 >= limit) {
			return false;
		}
		char next = text.charAt(offset + 1);
		return Character.isLetter(next) || next == '_' || next == '{';
	}

	private static int skipParser3Insertion(@NotNull CharSequence text, int from, int limit) {
		int j = from + 1;
		int length = text.length();

		while (j < limit && j < length) {
			char c = text.charAt(j);

			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				j++;
				continue;
			}

			if (c == ':' || c == '.') {
				j++;
				continue;
			}

			if (c == '[') {
				j = skipBrackets(text, j, limit, '[', ']');
				continue;
			}

			if (c == '(') {
				j = skipBrackets(text, j, limit, '(', ')');
				continue;
			}

			break;
		}

		return j;
	}

	private static int skipParser3Variable(@NotNull CharSequence text, int from, int limit) {
		int j = from + 1;
		int length = text.length();

		if (j < limit && j < length && text.charAt(j) == '{') {
			int close = Parser3LexerUtils.findMatchingBrace(text, j, limit);
			return close >= 0 ? close + 1 : limit;
		}

		while (j < limit && j < length) {
			char c = text.charAt(j);

			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				j++;
				continue;
			}

			if (c == ':' || c == '.') {
				j++;
				continue;
			}

			if (c == '[') {
				j = skipBrackets(text, j, limit, '[', ']');
				continue;
			}

			if (c == '(') {
				j = skipBrackets(text, j, limit, '(', ')');
				continue;
			}

			break;
		}

		return j;
	}

	private static int skipBrackets(@NotNull CharSequence text, int from, int limit, char open, char close) {
		int closePos = Parser3LexerUtils.findMatchingBracket(text, from, limit, open, close);
		return closePos >= 0 ? closePos + 1 : limit;
	}
}
