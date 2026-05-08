package ru.artlebedev.parser3.utils;

import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.lexer.Parser3LexerUtils;

public final class Parser3ChainUtils {

	private Parser3ChainUtils() {}

	public static int findMatchingBracket(@NotNull String text, int openPos, int limit, char openCh) {
		char closeCh;
		if (openCh == '[') {
			closeCh = ']';
		} else if (openCh == '(') {
			closeCh = ')';
		} else if (openCh == '{') {
			closeCh = '}';
		} else {
			return -1;
		}
		int depth = 1;
		for (int i = openPos + 1; i < limit && i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == openCh) {
				depth++;
			} else if (ch == closeCh) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	public static int findLastTopLevelDot(@NotNull String text) {
		int lastDot = -1;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '[' || ch == '(' || ch == '{') {
				int closePos = findMatchingBracket(text, i, text.length(), ch);
				if (closePos < 0) {
					break;
				}
				i = closePos;
				continue;
			}
			if (ch == '.') {
				lastDot = i;
			}
		}
		return lastDot;
	}

	public static @NotNull String normalizeDynamicSegments(@NotNull String text) {
		StringBuilder result = new StringBuilder(text.length());
		int segmentStart = 0;

		for (int i = 0; i <= text.length(); i++) {
			boolean isBoundary = i == text.length();
			if (!isBoundary) {
				char ch = text.charAt(i);
				if (ch == '[' || ch == '(' || ch == '{') {
					int closePos = findMatchingBracket(text, i, text.length(), ch);
					if (closePos < 0) {
						break;
					}
					i = closePos;
					continue;
				}
				isBoundary = ch == '.';
			}

			if (!isBoundary) {
				continue;
			}

			String segment = text.substring(segmentStart, i);
			if (!segment.isEmpty()) {
				char first = segment.charAt(0);
				if (first == '$' || first == '(') {
					result.append('*');
				} else if (first == '[') {
					String literalKey = tryExtractLiteralBracketKey(segment);
					result.append(literalKey != null ? literalKey : "*");
				} else {
					result.append(segment);
				}
			}

			if (i < text.length()) {
				result.append('.');
			}
			segmentStart = i + 1;
		}

		return result.toString();
	}

	public static boolean hasCompletedChainOccurrenceElsewhere(
			@NotNull String text,
			@NotNull String expectedChain,
			int excludedSegmentStartOffset
	) {
		int len = text.length();
		for (int i = 0; i < len; i++) {
			if (text.charAt(i) != '$') continue;
			if (Parser3LexerUtils.isEscapedByCaret(text, i)) continue;
			ChainOccurrence occurrence = parseChainOccurrence(text, i, len);
			if (occurrence == null) continue;
			if (!occurrence.completed) continue;
			if (!expectedChain.equals(occurrence.normalizedChain)) continue;
			if (occurrence.lastSegmentOffset == excludedSegmentStartOffset) continue;
			return true;
		}
		return false;
	}

	private static String tryExtractLiteralBracketKey(@NotNull String segment) {
		if (segment.length() < 2 || segment.charAt(0) != '[' || segment.charAt(segment.length() - 1) != ']') {
			return null;
		}

		String key = segment.substring(1, segment.length() - 1).trim();
		if (key.isEmpty()) return null;

		for (int i = 0; i < key.length(); i++) {
			char ch = key.charAt(i);
			if (ch == '$' || ch == '^'
					|| ch == '[' || ch == ']'
					|| ch == '(' || ch == ')'
					|| ch == '{' || ch == '}'
					|| ch == ';'
					|| ch == '\n' || ch == '\r') {
				return null;
			}
		}

		return key;
	}

	private static ChainOccurrence parseChainOccurrence(@NotNull String text, int dollarPos, int textLen) {
		int pos = dollarPos + 1;
		int chainLimit = textLen;
		int terminatorLimit = textLen;

		if (pos < textLen && text.charAt(pos) == '{') {
			int closeBrace = findMatchingBracket(text, pos, textLen, '{');
			if (closeBrace < 0) return null;
			pos++;
			chainLimit = closeBrace;
			terminatorLimit = closeBrace + 1;
		}

		if (pos + 5 <= chainLimit) {
			String prefix = text.substring(pos, pos + 5);
			if ("self.".equals(prefix) || "MAIN:".equals(prefix)) {
				pos += 5;
			}
		}

		if (pos >= chainLimit || !Parser3IdentifierUtils.isIdentifierStart(text.charAt(pos))) {
			return null;
		}

		int rootStart = pos;
		pos++;
		while (pos < chainLimit && Parser3IdentifierUtils.isIdentifierChar(text.charAt(pos))) {
			pos++;
		}

		StringBuilder normalized = new StringBuilder(text.substring(rootStart, pos));
		int lastSegmentOffset = rootStart;
		int dotChainSize = 0;

		while (true) {
			while (pos < chainLimit && Character.isWhitespace(text.charAt(pos))) {
				pos++;
			}
			if (pos >= chainLimit || text.charAt(pos) != '.') {
				break;
			}

			pos++;
			while (pos < chainLimit && Character.isWhitespace(text.charAt(pos))) {
				pos++;
			}
			if (pos >= chainLimit) {
				break;
			}

			char ch = text.charAt(pos);
			String segment;
			int segmentOffset = pos;
			if (ch == '[') {
				int close = findMatchingBracket(text, pos, textLen, '[');
				if (close < 0 || close > chainLimit) break;
				String literal = text.substring(pos, close + 1);
				String normalizedLiteral = tryExtractLiteralBracketKey(literal);
				segment = normalizedLiteral != null ? normalizedLiteral : "*";
				segmentOffset = normalizedLiteral != null ? pos + 1 : pos;
				pos = close + 1;
			} else if (ch == '(' || ch == '{') {
				int close = findMatchingBracket(text, pos, textLen, ch);
				if (close < 0 || close > chainLimit) break;
				segment = "*";
				pos = close + 1;
			} else {
				if (!Parser3IdentifierUtils.isIdentifierStart(ch)) break;
				int segStart = pos;
				pos++;
				while (pos < chainLimit && Parser3IdentifierUtils.isIdentifierChar(text.charAt(pos))) {
					pos++;
				}
				segment = text.substring(segStart, pos);
				segmentOffset = segStart;
			}

			normalized.append('.').append(segment);
			lastSegmentOffset = segmentOffset;
			dotChainSize++;
		}

		boolean completed = Parser3VariableTailUtils.hasCompletedReadChainTerminator(text, pos, terminatorLimit, dotChainSize);
		return new ChainOccurrence(normalized.toString(), lastSegmentOffset, completed);
	}

	private static final class ChainOccurrence {
		final @NotNull String normalizedChain;
		final int lastSegmentOffset;
		final boolean completed;

		ChainOccurrence(@NotNull String normalizedChain, int lastSegmentOffset, boolean completed) {
			this.normalizedChain = normalizedChain;
			this.lastSegmentOffset = lastSegmentOffset;
			this.completed = completed;
		}
	}
}
