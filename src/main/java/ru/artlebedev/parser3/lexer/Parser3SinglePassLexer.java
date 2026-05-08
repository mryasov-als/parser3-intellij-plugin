package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.Parser3IdentifierUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Parser3SinglePassLexer {

	private static final Set<String> IMPORTANT_NAMES = new HashSet<>(Arrays.asList(
			"result", "self", "caller", "MAIN", "BASE"
	));

	private static final Set<String> SPECIAL_METHOD_NAMES = new HashSet<>(Arrays.asList(
			"@USE", "@BASE", "@OPTIONS", "@CLASS", "@auto", "@conf", "@main"
	));

	private static final Set<String> OPTIONS_KEYWORDS = new HashSet<>(Arrays.asList(
			"locals", "partial", "dynamic", "static"
	));

	private Parser3SinglePassLexer() {
	}

	@NotNull
	static List<Parser3LexerCore.CoreToken> tokenize(@NotNull CharSequence fullText, int startOffset, int endOffset) {
		CharSequence text = Parser3LexerCore.getContextText(fullText, startOffset, endOffset);
		return new Scanner(text).scan();
	}

	private enum DirectiveMode {
		NONE,
		USE,
		CLASS,
		BASE,
		OPTIONS
	}

	private enum LiteralKind {
		TEMPLATE_DATA,
		STRING,
		SQL_BLOCK,
		HTML_DATA
	}

	private static final class Scanner {
		private static final class SqlQuoteState {
			private boolean inSingleQuote;
			private boolean inDoubleQuote;
		}

		private final CharSequence text;
		private final int length;
		private final java.util.List<Parser3LexerCore.CoreToken> tokens = new java.util.ArrayList<>();
		private final java.util.ArrayDeque<SqlQuoteState> sqlQuoteStates = new java.util.ArrayDeque<>();
		private int pos;
		private boolean lineStart = true;
		private DirectiveMode directiveMode = DirectiveMode.NONE;
		private boolean methodHtmlArmed = false;
		private boolean methodHtmlActive = false;

		private Scanner(@NotNull CharSequence text) {
			this.text = text;
			this.length = text.length();
		}

		@NotNull
		private List<Parser3LexerCore.CoreToken> scan() {
			while (pos < length) {
				if (matchLineComment() || matchRemComment() || matchDirectiveOrMethodDefinition() || matchWhitespace()) {
					continue;
				}
				matchCode(false, false, false, LiteralKind.TEMPLATE_DATA, '\0');
			}
			return tokens;
		}

		private boolean matchLineComment() {
			if (pos >= length || text.charAt(pos) != '#' || !isStrictLineStart(pos)) {
				return false;
			}
			int start = pos;
			pos = Parser3LexerCore.findLineEnd(text, pos);
			add(start, pos, "LINE_COMMENT");
			lineStart = false;
			directiveMode = DirectiveMode.NONE;
			return true;
		}

		private boolean matchInlineComment() {
			if (pos >= length || text.charAt(pos) != '#' || !isStrictLineStart(pos)) {
				return false;
			}
			int start = pos;
			pos = Parser3LexerCore.findLineEnd(text, pos);
			add(start, pos, "LINE_COMMENT");
			lineStart = false;
			return true;
		}

		private boolean matchExpressionComment() {
			if (pos >= length || text.charAt(pos) != '#' || Parser3LexerUtils.isEscapedByCaret(text, pos)) {
				return false;
			}
			int start = pos;
			while (pos < length) {
				char c = text.charAt(pos);
				if (c == '\n' || c == '\r') {
					break;
				}
				if (c == ')' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				pos++;
			}
			add(start, pos, "LINE_COMMENT");
			lineStart = false;
			return true;
		}

		private boolean matchRemComment() {
			if (!regionMatches(pos, "^rem{")) {
				return false;
			}
			int start = pos;
			add(start, start + 4, "BLOCK_COMMENT");
			add(start + 4, start + 5, "REM_LBRACE");
			pos = start + 5;
			int chunkStart = pos;
			int depth = 1;
			while (pos < length) {
				char c = text.charAt(pos);
				if (c == '^' && pos + 1 < length) {
					char next = text.charAt(pos + 1);
					if (next == '{' || next == '}' || next == '[' || next == ']' || next == '(' || next == ')') {
						pos += 2;
						continue;
					}
				}
				if (c == '{') {
					addCommentChunk(chunkStart, pos);
					add(pos, pos + 1, "REM_LBRACE");
					depth++;
					pos++;
					chunkStart = pos;
					continue;
				}
				if (c == '}') {
					addCommentChunk(chunkStart, pos);
					add(pos, pos + 1, "REM_RBRACE");
					depth--;
					pos++;
					chunkStart = pos;
					if (depth == 0) {
						lineStart = false;
						return true;
					}
					continue;
				}
				if (c == '[') {
					addCommentChunk(chunkStart, pos);
					add(pos, pos + 1, "REM_LBRACKET");
					pos++;
					chunkStart = pos;
					continue;
				}
				if (c == ']') {
					addCommentChunk(chunkStart, pos);
					add(pos, pos + 1, "REM_RBRACKET");
					pos++;
					chunkStart = pos;
					continue;
				}
				if (c == '(') {
					addCommentChunk(chunkStart, pos);
					add(pos, pos + 1, "REM_LPAREN");
					pos++;
					chunkStart = pos;
					continue;
				}
				if (c == ')') {
					addCommentChunk(chunkStart, pos);
					add(pos, pos + 1, "REM_RPAREN");
					pos++;
					chunkStart = pos;
					continue;
				}
				pos++;
			}
			addCommentChunk(chunkStart, pos);
			lineStart = false;
			return true;
		}

		private void addCommentChunk(int start, int end) {
			int i = start;
			while (i < end) {
				char c = text.charAt(i);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
					add(i, i + 1, "WHITE_SPACE");
					i++;
					continue;
				}
				int chunkStart = i;
				while (i < end) {
					char ch = text.charAt(i);
					if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
						break;
					}
					i++;
				}
				add(chunkStart, i, "BLOCK_COMMENT");
			}
		}

		private boolean matchDirectiveOrMethodDefinition() {
			if (!lineStart || pos >= length || text.charAt(pos) != '@') {
				return false;
			}
			resetMethodState();
			int lineEnd = Parser3LexerCore.findLineEnd(text, pos);
			int nameEnd = pos + 1;
			while (nameEnd < lineEnd) {
				char c = text.charAt(nameEnd);
				if (c == '[' || c == ' ' || c == '\t') {
					break;
				}
				nameEnd++;
			}
			if (nameEnd <= pos + 1) {
				return false;
			}
			String name = substring(pos, nameEnd);
			add(pos, nameEnd, isSpecialMethod(name) ? "SPECIAL_METHOD" : "DEFINE_METHOD");
			methodHtmlArmed = true;
			methodHtmlActive = false;
			directiveMode = directiveModeFor(name);
			pos = nameEnd;
			boolean firstBracket = true;
			while (pos < lineEnd) {
				char c = text.charAt(pos);
				if (c == '[') {
					parseMethodDefinitionBracket(lineEnd, firstBracket);
					firstBracket = false;
					continue;
				}
				if (c == '\t') {
					add(pos, pos + 1, "WHITE_SPACE");
					pos++;
					continue;
				}
				if (c == ' ') {
					int start = pos;
					while (pos < lineEnd && text.charAt(pos) == ' ') {
						pos++;
					}
					add(start, pos, "TEMPLATE_DATA");
					continue;
				}
				int start = pos;
				while (pos < lineEnd && text.charAt(pos) != '[' && text.charAt(pos) != ' ' && text.charAt(pos) != '\t') {
					pos++;
				}
				add(start, pos, "TEMPLATE_DATA");
			}
			lineStart = false;
			return true;
		}

		private void resetMethodState() {
			directiveMode = DirectiveMode.NONE;
			methodHtmlArmed = false;
			methodHtmlActive = false;
			sqlQuoteStates.clear();
		}

		private void parseMethodDefinitionBracket(int limit, boolean first) {
			add(pos, pos + 1, "LBRACKET");
			pos++;
			int itemStart = pos;
			while (pos < limit) {
				char c = text.charAt(pos);
				if (c == ';' || c == ']') {
					if (itemStart < pos) {
						add(itemStart, pos, first ? "VARIABLE" : "LOCAL_VARIABLE");
					}
					if (c == ';') {
						add(pos, pos + 1, "SEMICOLON");
						pos++;
						itemStart = pos;
						continue;
					}
					add(pos, pos + 1, "RBRACKET");
					pos++;
					return;
				}
				pos++;
			}
		}

		private void parseForeachBracket() {
			add(pos, pos + 1, "LBRACKET");
			pos++;
			int itemStart = pos;
			while (pos < length) {
				char c = text.charAt(pos);
				if (c == ';' || c == ']') {
					if (itemStart < pos) {
						add(itemStart, pos, "VARIABLE");
					}
					if (c == ';') {
						add(pos, pos + 1, "SEMICOLON");
						pos++;
						itemStart = pos;
						continue;
					}
					add(pos, pos + 1, "RBRACKET");
					pos++;
					return;
				}
				pos++;
			}
		}

		private boolean matchWhitespace() {
			if (pos >= length) {
				return false;
			}
			char c = text.charAt(pos);
			if (c == '\n' || c == '\r' || c == '\t') {
				add(pos, pos + 1, "WHITE_SPACE");
				pos++;
				lineStart = c == '\n' || c == '\r';
				if (lineStart && directiveMode != DirectiveMode.USE && directiveMode != DirectiveMode.CLASS
						&& directiveMode != DirectiveMode.BASE && directiveMode != DirectiveMode.OPTIONS) {
					directiveMode = DirectiveMode.NONE;
				}
				return true;
			}
			if (c == ' ' && lineStart) {
				int start = pos;
				while (pos < length && text.charAt(pos) == ' ') {
					pos++;
				}
				add(start, pos, "WHITE_SPACE");
				return true;
			}
			return false;
		}

		private boolean matchBracketWhitespace() {
			if (pos >= length) {
				return false;
			}
			char c = text.charAt(pos);
			if (c != '\n' && c != '\r') {
				return false;
			}
			int start = pos;
			pos++;
			while (pos < length) {
				char next = text.charAt(pos);
				if (next != '\t' && next != ' ') {
					break;
				}
				pos++;
			}
			add(start, pos, "WHITE_SPACE");
			lineStart = true;
			if (directiveMode != DirectiveMode.USE && directiveMode != DirectiveMode.CLASS
					&& directiveMode != DirectiveMode.BASE && directiveMode != DirectiveMode.OPTIONS) {
				directiveMode = DirectiveMode.NONE;
			}
			return true;
		}

		private boolean matchCode(boolean inExpression, boolean inBracket, boolean inSql, @NotNull LiteralKind literalKind, char closingChar) {
			if (!inExpression && !inBracket && !inSql && applyDirectiveContent()) {
				return true;
			}
			if (pos >= length) {
				return false;
			}
			char c = text.charAt(pos);
			if (!inExpression && !inBracket && !inSql && c == '}') {
				add(pos, pos + 1, "RBRACE");
				pos++;
				lineStart = false;
				return true;
			}
			if (!inExpression && !inBracket && !inSql && c == '{' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
				parseBrace(literalKind, false, false);
				lineStart = false;
				return true;
			}
			if (!inExpression && !inBracket && !inSql && c == ']') {
				add(pos, pos + 1, "RBRACKET");
				pos++;
				lineStart = false;
				return true;
			}
			if (c == '$' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
				parseDollar(inSql, literalKind);
				lineStart = false;
				return true;
			}
			if (c == '^' && !Parser3LexerUtils.isEscapedByCaret(text, pos) && parseCaret(inSql, literalKind, inExpression)) {
				lineStart = false;
				return true;
			}
			if (inExpression && (c == '\'' || c == '"') && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
				parseQuoted(c, inSql);
				lineStart = false;
				return true;
			}
			if (inExpression && parseExpressionAtom()) {
				lineStart = false;
				return true;
			}
			parseLiteral(inExpression, inBracket, inSql, literalKind, closingChar, !inExpression);
			lineStart = false;
			return true;
		}

		private boolean applyDirectiveContent() {
			if (directiveMode == DirectiveMode.NONE) {
				return false;
			}
			if (directiveMode == DirectiveMode.CLASS || directiveMode == DirectiveMode.BASE) {
				if (!lineStart || pos >= length || text.charAt(pos) == '\n' || text.charAt(pos) == '\r') {
					return false;
				}
				int lineEnd = Parser3LexerCore.findLineEnd(text, pos);
				int start = pos;
				while (pos < lineEnd && !Character.isWhitespace(text.charAt(pos))) {
					pos++;
				}
				add(start, pos, "VARIABLE");
				directiveMode = DirectiveMode.NONE;
				return true;
			}
			if (directiveMode == DirectiveMode.USE) {
				if (lineStart && pos < length && text.charAt(pos) == '@') {
					directiveMode = DirectiveMode.NONE;
					return false;
				}
				if (!lineStart || pos >= length || text.charAt(pos) == '\n' || text.charAt(pos) == '\r') {
					return false;
				}
				int lineEnd = Parser3LexerCore.findLineEnd(text, pos);
				add(pos, lineEnd, "USE_PATH");
				pos = lineEnd;
				return true;
			}
			if (directiveMode == DirectiveMode.OPTIONS) {
				if (lineStart && pos < length && text.charAt(pos) == '@') {
					directiveMode = DirectiveMode.NONE;
					return false;
				}
				if (!lineStart || pos >= length || text.charAt(pos) == '\n' || text.charAt(pos) == '\r') {
					return false;
				}
				int lineEnd = Parser3LexerCore.findLineEnd(text, pos);
				int start = pos;
				while (pos < lineEnd && !Character.isWhitespace(text.charAt(pos))) {
					pos++;
				}
				String word = substring(start, pos);
				add(start, pos, OPTIONS_KEYWORDS.contains(word) ? "KEYWORD" : "TEMPLATE_DATA");
				return true;
			}
			return false;
		}

		private void parseDollar(boolean inSql, @NotNull LiteralKind literalKind) {
			if (pos + 1 < length && text.charAt(pos + 1) == '.') {
				add(pos, pos + 1, "VARIABLE");
				pos++;
				parseVariableChain(inSql, literalKind);
				return;
			}
			add(pos, pos + 1, "DOLLAR_VARIABLE");
			pos++;
			int start = pos;
			if (pos < length && text.charAt(pos) == '{') {
				int close = Parser3LexerUtils.findMatchingBrace(text, pos, length);
				if (close > pos) {
					add(pos, pos + 1, "LBRACE");
					addBracedVariableContent(pos + 1, close);
					add(close, close + 1, "RBRACE");
					pos = close + 1;
					return;
				}
			} else {
				while (pos < length && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) {
					pos++;
				}
				while (pos + 1 < length && text.charAt(pos) == '$' && text.charAt(pos + 1) == '{') {
					int close = Parser3LexerUtils.findMatchingBrace(text, pos + 1, length);
					if (close < 0) {
						break;
					}
					pos = close + 1;
				}
			}
			if (start < pos) {
				String name = substring(start, pos);
				add(start, pos, IMPORTANT_NAMES.contains(name) ? "IMPORTANT_VARIABLE" : "VARIABLE");
			}
			parseVariableChain(inSql, literalKind);
		}

		private void addBracedVariableContent(int start, int end) {
			int i = start;
			while (i < end) {
				char c = text.charAt(i);
				if (c == '.') {
					add(i, i + 1, "DOT");
					i++;
					continue;
				}
				if (c == ':') {
					if (i + 1 < end && text.charAt(i + 1) == ':') {
						add(i, i + 2, "COLON");
						i += 2;
					} else {
						add(i, i + 1, "COLON");
						i++;
					}
					continue;
				}
				int segStart = i;
				while (i < end) {
					char seg = text.charAt(i);
					if (seg == '.' || seg == ':') {
						break;
					}
					i++;
				}
				if (segStart < i) {
					String name = substring(segStart, i);
					add(segStart, i, IMPORTANT_NAMES.contains(name) ? "IMPORTANT_VARIABLE" : "VARIABLE");
				}
			}
		}

		private void parseVariableChain(boolean inSql, @NotNull LiteralKind literalKind) {
			while (pos < length) {
				if (pos + 1 < length && text.charAt(pos) == ':' && text.charAt(pos + 1) == ':') {
					add(pos, pos + 2, "COLON");
					pos += 2;
					int start = pos;
					while (pos < length && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) {
						pos++;
					}
					add(start, pos, "VARIABLE");
					continue;
				}
				if (text.charAt(pos) == ':') {
					add(pos, pos + 1, "COLON");
					pos++;
					int start = pos;
					while (pos < length && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) {
						pos++;
					}
					add(start, pos, "VARIABLE");
					continue;
				}
				if (text.charAt(pos) == '.') {
					add(pos, pos + 1, "DOT");
					pos++;
					int start = pos;
					if (pos < length && text.charAt(pos) == '$') {
						add(pos, pos + 1, "DOLLAR_VARIABLE");
						pos++;
						start = pos;
					}
					while (pos < length && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) {
						pos++;
					}
					add(start, pos, "VARIABLE");
					continue;
				}
				if (text.charAt(pos) == '[' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseBracket(LiteralKind.STRING, false, false);
					continue;
				}
				if (text.charAt(pos) == '(' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseParen(inSql);
					continue;
				}
				if (text.charAt(pos) == '{' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseBrace(inSql ? LiteralKind.SQL_BLOCK : literalKind, inSql, false);
					continue;
				}
				break;
			}
		}

		private boolean parseCaret(boolean inSql, @NotNull LiteralKind literalKind, boolean inExpression) {
			if (regionMatches(pos, "^rem{")) {
				return false;
			}
			int caretPos = pos;
			pos++;
			int start = pos;
			if (pos >= length || !Parser3IdentifierUtils.isIdentifierStart(text.charAt(pos))) {
				pos = caretPos;
				return false;
			}
			while (pos < length && (Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos)) || text.charAt(pos) == '-')) {
				pos++;
			}
			String name = substring(start, pos);
			String keywordType = keywordType(name);
			boolean constructor = hasDoubleColonAhead(pos);
			String currentCallableName = name;
			boolean iteratorBracketPending = isIteratorCallable(currentCallableName);
			add(caretPos, pos, initialCaretType(name, keywordType, constructor));
			while (pos < length) {
				if (pos + 1 < length && text.charAt(pos) == ':' && text.charAt(pos + 1) == ':') {
					add(pos, pos + 1, "COLON");
					add(pos + 1, pos + 2, "COLON");
					pos += 2;
					int segStart = pos;
					while (pos < length && (Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos)) || text.charAt(pos) == '-')) {
						pos++;
					}
					currentCallableName = substring(segStart, pos);
					iteratorBracketPending = isIteratorCallable(currentCallableName);
					add(segStart, pos, "CONSTRUCTOR");
					constructor = true;
					continue;
				}
				if (text.charAt(pos) == ':') {
					add(pos, pos + 1, "COLON");
					pos++;
					int segStart = pos;
					while (pos < length && (Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos)) || text.charAt(pos) == '-')) {
						pos++;
					}
					currentCallableName = substring(segStart, pos);
					iteratorBracketPending = isIteratorCallable(currentCallableName);
					add(segStart, pos, constructor ? "CONSTRUCTOR" : "METHOD");
					continue;
				}
				if (text.charAt(pos) == '.') {
					add(pos, pos + 1, "DOT");
					pos++;
					int segStart = pos;
					while (pos < length && (Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos)) || text.charAt(pos) == '-')) {
						pos++;
					}
					currentCallableName = substring(segStart, pos);
					iteratorBracketPending = isIteratorCallable(currentCallableName);
					add(segStart, pos, constructor ? "CONSTRUCTOR" : "METHOD");
					continue;
				}
				if (text.charAt(pos) == '[' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					if (iteratorBracketPending) {
						parseForeachBracket();
						iteratorBracketPending = false;
					} else {
						parseBracket(LiteralKind.STRING, false, true);
					}
					continue;
				}
				if (text.charAt(pos) == '(' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseParen(inSql);
					continue;
				}
				if (text.charAt(pos) == '{' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					if (looksLikeSqlRoot(caretPos, pos)) {
						if (!parseSqlBlockFromRoot(caretPos)) {
							parseSqlBlock();
						}
					} else {
						parseBrace(
								inSql ? LiteralKind.SQL_BLOCK : LiteralKind.TEMPLATE_DATA,
								inSql,
								inExpression && keywordType != null
						);
					}
					continue;
				}
				break;
			}
			return true;
		}

		private boolean parseSqlBlockFromRoot(int rootStart) {
			int bodyEnd = findSqlBlockBodyEnd(pos + 1);
			if (bodyEnd < pos + 1) {
				return false;
			}

			String legacySlice = substring(rootStart, bodyEnd + 1);
			List<Parser3LexerCore.CoreToken> legacyTokens = Parser3LexerCore.tokenizeLegacy(legacySlice, 0, legacySlice.length());
			if (legacyTokens.isEmpty()) {
				return false;
			}

			rollbackTokensTo(rootStart);
			for (Parser3LexerCore.CoreToken token : legacyTokens) {
				addActual(rootStart + token.start, rootStart + token.end, token.type);
			}
			pos = bodyEnd + 1;
			lineStart = false;
			return true;
		}

		private void rollbackTokensTo(int startOffset) {
			while (!tokens.isEmpty()) {
				Parser3LexerCore.CoreToken last = tokens.get(tokens.size() - 1);
				if (last.start < startOffset) {
					break;
				}
				tokens.remove(tokens.size() - 1);
			}
		}

		@NotNull
		private String initialCaretType(@NotNull String name, String keywordType, boolean constructor) {
			if (constructor) {
				return "CONSTRUCTOR";
			}
			if (keywordType != null) {
				return keywordType;
			}
			if (IMPORTANT_NAMES.contains(name)) {
				return "IMPORTANT_METHOD";
			}
			return "METHOD";
		}

		private boolean hasDoubleColonAhead(int offset) {
			int i = offset;
			while (i + 1 < length) {
				char c = text.charAt(i);
				if (c == ':' && text.charAt(i + 1) == ':') {
					return true;
				}
				if (c != ':' && c != '.' && c != '-' && !Parser3IdentifierUtils.isVariableIdentifierChar(c)) {
					return false;
				}
				i++;
			}
			return false;
		}

		private boolean isIteratorCallable(String name) {
			return "foreach".equals(name)
					|| "for".equals(name)
					|| "select".equals(name)
					|| "sort".equals(name);
		}

		private void parseBracket(@NotNull LiteralKind innerLiteral, boolean inSql, boolean mergeIndentedNewline) {
			int closePos = Parser3LexerUtils.findMatchingSquareBracket(text, pos, length);
			int limit = closePos >= 0 ? closePos : findUnterminatedRecoveryLimit(pos + 1);
			add(pos, pos + 1, "LBRACKET");
			pos++;
			String previousSignificantType = null;
			while (pos < limit) {
				if (pos < length && text.charAt(pos) == ']' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				if (!inSql && matchRemComment()) {
					previousSignificantType = "BLOCK_COMMENT";
					continue;
				}
				if (!inSql && matchInlineComment()) {
					previousSignificantType = "LINE_COMMENT";
					continue;
				}
				boolean shouldMergeIndentedNewline = mergeIndentedNewline || !"LINE_COMMENT".equals(previousSignificantType);
				if ((shouldMergeIndentedNewline && matchBracketWhitespace()) || matchWhitespace()) {
					continue;
				}
				if (text.charAt(pos) == ';' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					add(pos, pos + 1, "SEMICOLON");
					pos++;
					previousSignificantType = "SEMICOLON";
					continue;
				}
				int before = tokens.size();
				matchCode(false, true, inSql, innerLiteral, ']');
				if (tokens.size() > before) {
					previousSignificantType = tokens.get(tokens.size() - 1).type;
				}
			}
			if (closePos >= 0 && pos < length && text.charAt(pos) == ']') {
				add(pos, pos + 1, "RBRACKET");
				pos++;
			}
		}

		private void parseParen(boolean inSql) {
			int closePos = Parser3LexerUtils.findMatchingParen(text, pos, length);
			int limit = closePos >= 0 ? closePos : findUnterminatedRecoveryLimit(pos + 1);
			add(pos, pos + 1, "LPAREN");
			pos++;
			while (pos < limit) {
				if (pos < length && text.charAt(pos) == ')' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				if (!inSql && matchRemComment()) {
					continue;
				}
				if (!inSql && text.charAt(pos) == '{' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					add(pos, pos + 1, "LBRACE");
					pos++;
					continue;
				}
				if (!inSql && text.charAt(pos) == '}' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					add(pos, pos + 1, "RBRACE");
					pos++;
					continue;
				}
				if (text.charAt(pos) == ';' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					add(pos, pos + 1, "SEMICOLON");
					pos++;
					continue;
				}
				if (text.charAt(pos) == '(' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseParen(inSql);
					continue;
				}
				if (!inSql && matchInlineComment()) {
					continue;
				}
				if (!inSql && matchExpressionComment()) {
					continue;
				}
				if (matchWhitespace()) {
					continue;
				}
				matchCode(true, false, inSql, LiteralKind.TEMPLATE_DATA, ')');
			}
			if (closePos >= 0 && pos < length && text.charAt(pos) == ')') {
				add(pos, pos + 1, "RPAREN");
				pos++;
			}
		}

		private void parseBrace(@NotNull LiteralKind kind, boolean inSql, boolean expressionContent) {
			int closePos = Parser3LexerUtils.findMatchingBrace(text, pos, length);
			int limit = closePos >= 0 ? closePos : findUnterminatedRecoveryLimit(pos + 1);
			add(pos, pos + 1, "LBRACE");
			pos++;
			pushSqlQuoteStateIfNeeded(inSql);
			try {
				while (pos < limit) {
					if (pos < length && text.charAt(pos) == '}' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						break;
					}
					if (!inSql && matchRemComment()) {
						continue;
					}
					if (!inSql && matchLineComment()) {
						continue;
					}
					if (inSql ? matchLineBreakWhitespace() : matchWhitespace()) {
						continue;
					}
					if (text.charAt(pos) == ';' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						add(pos, pos + 1, "SEMICOLON");
						pos++;
						continue;
					}
					matchCode(expressionContent, false, inSql, kind, '}');
				}
				if (closePos >= 0 && pos < length && text.charAt(pos) == '}') {
					add(pos, pos + 1, "RBRACE");
					pos++;
				}
			} finally {
				popSqlQuoteStateIfNeeded(inSql);
			}
		}

		private void parseSqlBlock() {
			add(pos, pos + 1, "LBRACE");
			pos++;
			parseSqlBlockShallowBody();
			if (pos < length && text.charAt(pos) == '}') {
				add(pos, pos + 1, "RBRACE");
				pos++;
			}
		}

		private int findSqlBlockBodyEnd(int start) {
			return Parser3SqlBlockLexerCore.findSqlBlockEndForSinglePass(text, start, length);
		}

		private int findUnterminatedRecoveryLimit(int start) {
			int i = start;
			while (i < length) {
				char c = text.charAt(i);
				if (c == '\n' || c == '\r') {
					return i;
				}
				i++;
			}
			return length;
		}

		private void parseSqlBlockShallowBody() {
			int chunkStart = pos;
			boolean inSingleQuote = false;
			boolean inDoubleQuote = false;
			int braceDepth = 1;
			while (pos < length) {
				char c = text.charAt(pos);
				if (!inSingleQuote && !inDoubleQuote && c == '}' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					braceDepth--;
					if (braceDepth == 0) {
						break;
					}
				}
				if (c == '\n' || c == '\r') {
					addSqlChunk(chunkStart, pos);
					add(pos, pos + 1, "WHITE_SPACE");
					pos++;
					lineStart = true;
					chunkStart = pos;
					continue;
				}
				if (lineStart && (c == ' ' || c == '\t')) {
					addSqlChunk(chunkStart, pos);
					int wsStart = pos;
					while (pos < length) {
						char ws = text.charAt(pos);
						if (ws != ' ' && ws != '\t') {
							break;
						}
						pos++;
					}
					add(wsStart, pos, "WHITE_SPACE");
					chunkStart = pos;
					lineStart = true;
					continue;
				}
				if (c == '^' && pos + 1 < length) {
					char next = text.charAt(pos + 1);
					if (isSqlEscapedChar(next)) {
						pos += 2;
						lineStart = false;
						continue;
					}
					if (!Parser3LexerUtils.isEscapedByCaret(text, pos) && Parser3IdentifierUtils.isIdentifierStart(next)) {
						addSqlChunk(chunkStart, pos);
						parseSqlCaretShallow();
						chunkStart = pos;
						lineStart = false;
						continue;
					}
				}
				if (c == '$' && !Parser3LexerUtils.isEscapedByCaret(text, pos) && isSqlVariableStart(pos + 1)) {
					addSqlChunk(chunkStart, pos);
					parseSqlDollarShallow();
					chunkStart = pos;
					lineStart = false;
					continue;
				}
				if (!Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					if (c == '\'' && !inDoubleQuote) {
						inSingleQuote = !inSingleQuote;
					} else if (c == '"' && !inSingleQuote) {
						inDoubleQuote = !inDoubleQuote;
					} else if (!inSingleQuote && !inDoubleQuote) {
						if (c == '{') {
							braceDepth++;
						}
					}
				}
				pos++;
				if (c != ' ' && c != '\t') {
					lineStart = false;
				}
			}
			addSqlChunk(chunkStart, pos);
		}

		private void addSqlChunk(int start, int end) {
			if (start >= end) {
				return;
			}
			add(start, end, "SQL_BLOCK");
		}

		private boolean isSqlEscapedChar(char c) {
			return c == '{' || c == '}' || c == '\'' || c == '"' ||
					c == '[' || c == ']' || c == '(' || c == ')' ||
					c == '$' || c == ';' || c == '^' || c == '#';
		}

		private boolean isSqlVariableStart(int offset) {
			if (offset >= length) {
				return false;
			}
			char c = text.charAt(offset);
			return Character.isLetter(c) || c == '_' || c == '.' || c == '{';
		}

		private void parseSqlCaretShallow() {
			int caretPos = pos;
			pos++;
			int start = pos;
			while (pos < length && (Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos)) || text.charAt(pos) == '-')) {
				pos++;
			}
			String name = substring(start, pos);
			boolean constructor = pos + 1 < length && text.charAt(pos) == ':' && text.charAt(pos + 1) == ':';
			add(caretPos, pos, initialCaretType(name, keywordType(name), constructor));
			while (pos < length) {
				if (pos + 1 < length && text.charAt(pos) == ':' && text.charAt(pos + 1) == ':') {
					add(pos, pos + 1, "COLON");
					add(pos + 1, pos + 2, "COLON");
					pos += 2;
					int segmentStart = pos;
					while (pos < length && (Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos)) || text.charAt(pos) == '-')) {
						pos++;
					}
					if (segmentStart < pos) {
						add(segmentStart, pos, "CONSTRUCTOR");
						constructor = true;
						continue;
					}
					break;
				}
				if (text.charAt(pos) == ':') {
					add(pos, pos + 1, "COLON");
					pos++;
					int segmentStart = pos;
					while (pos < length && (Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos)) || text.charAt(pos) == '-')) {
						pos++;
					}
					if (segmentStart < pos) {
						add(segmentStart, pos, constructor ? "CONSTRUCTOR" : "METHOD");
						continue;
					}
					break;
				}
				if (text.charAt(pos) == '.') {
					add(pos, pos + 1, "DOT");
					pos++;
					int segmentStart = pos;
					while (pos < length && (Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos)) || text.charAt(pos) == '-')) {
						pos++;
					}
					if (segmentStart < pos) {
						add(segmentStart, pos, constructor ? "CONSTRUCTOR" : "METHOD");
						continue;
					}
					break;
				}
				if (text.charAt(pos) == '[' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseSqlInsertionGroup('[', ']', "LBRACKET", "RBRACKET");
					continue;
				}
				if (text.charAt(pos) == '(' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseSqlInsertionGroup('(', ')', "LPAREN", "RPAREN");
					continue;
				}
				if (text.charAt(pos) == '{' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseSqlInsertionGroup('{', '}', "LBRACE", "RBRACE");
					continue;
				}
				break;
			}
		}

		private void parseSqlDollarShallow() {
			if (pos + 1 < length && text.charAt(pos + 1) == '.') {
				add(pos, pos + 1, "VARIABLE");
				pos++;
				parseSqlVariableChainShallow();
				return;
			}
			add(pos, pos + 1, "DOLLAR_VARIABLE");
			pos++;
			int start = pos;
			if (pos < length && text.charAt(pos) == '{') {
				int close = Parser3LexerUtils.findMatchingBrace(text, pos, length);
				if (close > pos) {
					add(pos, pos + 1, "LBRACE");
					addBracedVariableContent(pos + 1, close);
					add(close, close + 1, "RBRACE");
					pos = close + 1;
					parseSqlVariableChainShallow();
					return;
				}
			}
			while (pos < length && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) {
				pos++;
			}
			if (start < pos) {
				String name = substring(start, pos);
				add(start, pos, IMPORTANT_NAMES.contains(name) ? "IMPORTANT_VARIABLE" : "VARIABLE");
			}
			parseSqlVariableChainShallow();
		}

		private void parseSqlVariableChainShallow() {
			while (pos < length) {
				if (pos + 1 < length && text.charAt(pos) == ':' && text.charAt(pos + 1) == ':') {
					add(pos, pos + 2, "COLON");
					pos += 2;
					int start = pos;
					while (pos < length && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) {
						pos++;
					}
					if (start < pos) {
						add(start, pos, "VARIABLE");
						continue;
					}
					break;
				}
				if (text.charAt(pos) == ':') {
					add(pos, pos + 1, "COLON");
					pos++;
					int start = pos;
					while (pos < length && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) {
						pos++;
					}
					if (start < pos) {
						add(start, pos, "VARIABLE");
						continue;
					}
					break;
				}
				if (text.charAt(pos) == '.') {
					add(pos, pos + 1, "DOT");
					pos++;
					int start = pos;
					if (pos < length && text.charAt(pos) == '$') {
						add(pos, pos + 1, "DOLLAR_VARIABLE");
						pos++;
						start = pos;
					}
					while (pos < length && Parser3IdentifierUtils.isVariableIdentifierChar(text.charAt(pos))) {
						pos++;
					}
					if (start < pos) {
						add(start, pos, "VARIABLE");
						continue;
					}
					break;
				}
				if (text.charAt(pos) == '[' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseSqlInsertionGroup('[', ']', "LBRACKET", "RBRACKET");
					continue;
				}
				if (text.charAt(pos) == '(' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseSqlInsertionGroup('(', ')', "LPAREN", "RPAREN");
					continue;
				}
				if (text.charAt(pos) == '{' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					parseSqlInsertionGroup('{', '}', "LBRACE", "RBRACE");
					continue;
				}
				break;
			}
		}

		private void parseSqlInsertionGroup(char openChar, char closeChar, @NotNull String openType, @NotNull String closeType) {
			add(pos, pos + 1, openType);
			pos++;
			int chunkStart = pos;
			boolean inSingleQuote = false;
			boolean inDoubleQuote = false;
			while (pos < length) {
				char c = text.charAt(pos);
				if (c == '^' && pos + 1 < length && isSqlEscapedChar(text.charAt(pos + 1))) {
					pos += 2;
					lineStart = false;
					continue;
				}
				if (!Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					if (c == '\'' && !inDoubleQuote) {
						inSingleQuote = !inSingleQuote;
					} else if (c == '"' && !inSingleQuote) {
						inDoubleQuote = !inDoubleQuote;
					}
				}
				if (!inSingleQuote && !inDoubleQuote) {
					if (c == closeChar && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						break;
					}
					if (c == '\n' || c == '\r') {
						addSqlInsertionChunk(chunkStart, pos);
						add(pos, pos + 1, "WHITE_SPACE");
						pos++;
						lineStart = true;
						chunkStart = pos;
						continue;
					}
					if (lineStart && (c == ' ' || c == '\t')) {
						addSqlInsertionChunk(chunkStart, pos);
						int wsStart = pos;
						while (pos < length) {
							char ws = text.charAt(pos);
							if (ws != ' ' && ws != '\t') {
								break;
							}
							pos++;
						}
						add(wsStart, pos, "WHITE_SPACE");
						chunkStart = pos;
						lineStart = true;
						continue;
					}
					if (c == ';' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						addSqlInsertionChunk(chunkStart, pos);
						add(pos, pos + 1, "SEMICOLON");
						pos++;
						lineStart = false;
						chunkStart = pos;
						continue;
					}
					if (c == '^' && pos + 1 < length && Parser3IdentifierUtils.isIdentifierStart(text.charAt(pos + 1))) {
						addSqlInsertionChunk(chunkStart, pos);
						parseSqlCaretShallow();
						lineStart = false;
						chunkStart = pos;
						continue;
					}
					if (c == '$' && !Parser3LexerUtils.isEscapedByCaret(text, pos) && isSqlVariableStart(pos + 1)) {
						addSqlInsertionChunk(chunkStart, pos);
						parseSqlDollarShallow();
						lineStart = false;
						chunkStart = pos;
						continue;
					}
					if (c == '[' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						addSqlInsertionChunk(chunkStart, pos);
						parseSqlInsertionGroup('[', ']', "LBRACKET", "RBRACKET");
						lineStart = false;
						chunkStart = pos;
						continue;
					}
					if (c == '(' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						addSqlInsertionChunk(chunkStart, pos);
						parseSqlInsertionGroup('(', ')', "LPAREN", "RPAREN");
						lineStart = false;
						chunkStart = pos;
						continue;
					}
					if (c == '{' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						addSqlInsertionChunk(chunkStart, pos);
						parseSqlInsertionGroup('{', '}', "LBRACE", "RBRACE");
						lineStart = false;
						chunkStart = pos;
						continue;
					}
				}
				pos++;
				if (c != ' ' && c != '\t') {
					lineStart = false;
				}
			}
			addSqlInsertionChunk(chunkStart, pos);
			if (pos < length && text.charAt(pos) == closeChar) {
				add(pos, pos + 1, closeType);
				pos++;
			}
		}

		private void addSqlInsertionChunk(int start, int end) {
			if (start >= end) {
				return;
			}
			add(start, end, "TEMPLATE_DATA");
		}

		private void parseQuoted(char quote, boolean inSql) {
			String contentType = "STRING";
			int quoteStart = pos;
			add(pos, pos + 1, quote == '\'' ? "LSINGLE_QUOTE" : "LDOUBLE_QUOTE");
			pos++;
			int chunkStart = pos;
			while (pos < length) {
				char c = text.charAt(pos);
				if (!inSql && (c == '\n' || c == '\r') && !hasClosingQuoteAhead(pos + 1, quote)) {
					break;
				}
				if (c == quote && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					if (chunkStart < pos) {
						add(chunkStart, pos, contentType);
					}
					add(pos, pos + 1, quote == '\'' ? "RSINGLE_QUOTE" : "RDOUBLE_QUOTE");
					pos++;
					return;
				}
				if (c == '$' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					if (chunkStart < pos) {
						add(chunkStart, pos, contentType);
					}
					parseDollar(inSql, LiteralKind.STRING);
					chunkStart = pos;
					continue;
				}
				if (c == '^' && !Parser3LexerUtils.isEscapedByCaret(text, pos)
						&& pos + 1 < length
						&& Parser3IdentifierUtils.isIdentifierStart(text.charAt(pos + 1))) {
					int savedPos = pos;
					if (chunkStart < savedPos) {
						add(chunkStart, savedPos, contentType);
					}
					if (parseCaret(inSql, LiteralKind.STRING, false)) {
						chunkStart = pos;
						continue;
					}
					chunkStart = savedPos;
				}
				pos++;
			}
			if (pos >= length) {
				pos = findUnterminatedRecoveryLimit(quoteStart + 1);
			}
			if (chunkStart < pos) {
				add(chunkStart, pos, contentType);
			}
		}

		private boolean hasClosingQuoteAhead(int start, char quote) {
			int i = start;
			while (i < length) {
				char c = text.charAt(i);
				if (c == quote && !Parser3LexerUtils.isEscapedByCaret(text, i)) {
					return true;
				}
				i++;
			}
			return false;
		}

		private boolean parseExpressionAtom() {
			if (pos >= length) {
				return false;
			}
			char c = text.charAt(pos);
			if (c == '(' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
				parseParen(false);
				return true;
			}
			if (Character.isDigit(c) || (c == '.' && pos + 1 < length && Character.isDigit(text.charAt(pos + 1)))) {
				int start = pos;
				pos++;
				while (pos < length && Character.isDigit(text.charAt(pos))) {
					pos++;
				}
				if (pos < length && text.charAt(pos) == '.' && pos + 1 < length && Character.isDigit(text.charAt(pos + 1))) {
					pos++;
					while (pos < length && Character.isDigit(text.charAt(pos))) {
						pos++;
					}
				}
				add(start, pos, "NUMBER");
				return true;
			}
			int wordOp = matchWordOperator();
			if (wordOp > 0) {
				add(pos, pos + wordOp, "WORD_OPERATOR");
				pos += wordOp;
				return true;
			}
			int boolLen = matchBoolean();
			if (boolLen > 0) {
				add(pos, pos + boolLen, "BOOLEAN");
				pos += boolLen;
				return true;
			}
			int symbolOp = matchSymbolOperator();
			if (symbolOp > 0) {
				add(pos, pos + symbolOp, "OP");
				pos += symbolOp;
				return true;
			}
			return false;
		}

		private void parseLiteral(
				boolean inExpression,
				boolean inBracket,
				boolean inSql,
				@NotNull LiteralKind literalKind,
				char closingChar,
				boolean allowHtmlLiteral
		) {
			LiteralKind actualKind = effectiveLiteral(literalKind, allowHtmlLiteral);
			int start = pos;
			SqlQuoteState sqlState = inSql ? currentSqlQuoteState() : null;
			boolean inSingleSqlQuote = sqlState != null && sqlState.inSingleQuote;
			boolean inDoubleSqlQuote = sqlState != null && sqlState.inDoubleQuote;
			boolean literalLineStart = lineStart;
			int nestedBracketDepth = 0;
			while (pos < length) {
				char c = text.charAt(pos);
				if (c == '\n' || c == '\r') {
					break;
				}
				if (actualKind != LiteralKind.SQL_BLOCK && c == '\t') {
					break;
				}
				if (actualKind != LiteralKind.SQL_BLOCK && literalLineStart && c == ' ') {
					break;
				}
				if (!inExpression && inSql && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					if (c == '\'' && !inDoubleSqlQuote) {
						inSingleSqlQuote = !inSingleSqlQuote;
						pos++;
						continue;
					}
					if (c == '"' && !inSingleSqlQuote) {
						inDoubleSqlQuote = !inDoubleSqlQuote;
						pos++;
						continue;
					}
				}
				if (inBracket && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					if (c == '[') {
						nestedBracketDepth++;
						pos++;
						literalLineStart = false;
						continue;
					}
					if (c == ']' && nestedBracketDepth > 0) {
						nestedBracketDepth--;
						pos++;
						literalLineStart = false;
						continue;
					}
				}
				if (closingChar != '\0' && c == closingChar && !Parser3LexerUtils.isEscapedByCaret(text, pos)
						&& !inSingleSqlQuote && !inDoubleSqlQuote) {
					break;
				}
				if (!inExpression && !inBracket && !inSql
						&& c == '{'
						&& !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				if (!inExpression && !inBracket && !inSql && closingChar == '\0'
						&& (c == '}' || c == ']')
						&& !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				if (!inExpression && closingChar == '}' && c == ';' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				if (!inSql && inExpression && c == '#'
						&& !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				if (c == '$' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				if (c == '^' && !Parser3LexerUtils.isEscapedByCaret(text, pos)
						&& pos + 1 < length
						&& Parser3IdentifierUtils.isIdentifierStart(text.charAt(pos + 1))) {
					break;
				}
				if (inExpression) {
					if (c == '(' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						break;
					}
					if ((c == '\'' || c == '"') && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
						break;
					}
					if (Character.isDigit(c) || (c == '.' && pos + 1 < length && Character.isDigit(text.charAt(pos + 1)))) {
						break;
					}
					if (matchWordOperator() > 0 || matchBoolean() > 0 || matchSymbolOperator() > 0) {
						break;
					}
				}
				if (inBracket && c == ';' && !Parser3LexerUtils.isEscapedByCaret(text, pos)) {
					break;
				}
				if (!inSql && !inExpression && !methodHtmlActive && methodHtmlArmed && c == '<' && looksLikeHtmlTag(pos)) {
					methodHtmlActive = true;
				}
				pos++;
				if (c != ' ' && c != '\t') {
					literalLineStart = false;
				}
			}
			if (sqlState != null) {
				sqlState.inSingleQuote = inSingleSqlQuote;
				sqlState.inDoubleQuote = inDoubleSqlQuote;
			}
			if (start < pos) {
				add(start, pos, literalTypeName(normalizeLiteralKind(actualKind, start, pos)));
				return;
			}
			if (pos < length) {
				add(pos, pos + 1, literalTypeName(normalizeLiteralKind(actualKind, pos, pos + 1)));
				pos++;
			}
		}

		private void pushSqlQuoteStateIfNeeded(boolean inSql) {
			if (inSql) {
				sqlQuoteStates.push(new SqlQuoteState());
			}
		}

		private void popSqlQuoteStateIfNeeded(boolean inSql) {
			if (inSql && !sqlQuoteStates.isEmpty()) {
				sqlQuoteStates.pop();
			}
		}

		private SqlQuoteState currentSqlQuoteState() {
			return sqlQuoteStates.peek();
		}

		private boolean matchLineBreakWhitespace() {
			if (pos >= length) {
				return false;
			}
			char c = text.charAt(pos);
			if (c == '\n' || c == '\r') {
				add(pos, pos + 1, "WHITE_SPACE");
				pos++;
				lineStart = true;
				return true;
			}
			return false;
		}

		private LiteralKind effectiveLiteral(@NotNull LiteralKind literalKind, boolean allowHtmlLiteral) {
			if (allowHtmlLiteral && literalKind == LiteralKind.TEMPLATE_DATA && methodHtmlActive) {
				return LiteralKind.HTML_DATA;
			}
			return literalKind;
		}

		@NotNull
		private LiteralKind normalizeLiteralKind(@NotNull LiteralKind literalKind, int start, int end) {
			if (literalKind == LiteralKind.HTML_DATA && isWhitespaceOnly(start, end)) {
				return LiteralKind.TEMPLATE_DATA;
			}
			return literalKind;
		}

		private boolean isWhitespaceOnly(int start, int end) {
			if (start >= end) {
				return false;
			}
			for (int i = start; i < end; i++) {
				char c = text.charAt(i);
				if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
					return false;
				}
			}
			return true;
		}

		private boolean looksLikeSqlRoot(int start, int currentPos) {
			String root = substring(start, currentPos);
			if (root.endsWith(":sql") || root.endsWith("::sql")) {
				return true;
			}

			for (String prefix : Parser3LexerCore.getUserSqlInjectionPrefixes()) {
				if (root.equals(prefix)) {
					return true;
				}
			}

			return false;
		}

		private boolean looksLikeHtmlTag(int offset) {
			if (offset + 1 >= length || text.charAt(offset) != '<') {
				return false;
			}
			char next = text.charAt(offset + 1);
			return Character.isLetter(next) || next == '/' || next == '!' || next == '?';
		}

		private boolean isSpecialMethod(@NotNull String name) {
			return SPECIAL_METHOD_NAMES.contains(name) || name.startsWith("@GET_") || name.startsWith("@SET_");
		}

		@NotNull
		private DirectiveMode directiveModeFor(@NotNull String name) {
			switch (name) {
				case "@USE":
					return DirectiveMode.USE;
				case "@CLASS":
					return DirectiveMode.CLASS;
				case "@BASE":
					return DirectiveMode.BASE;
				case "@OPTIONS":
					return DirectiveMode.OPTIONS;
				default:
					return DirectiveMode.NONE;
			}
		}

		private String keywordType(@NotNull String name) {
			switch (name) {
				case "if": return "KW_IF";
				case "while": return "KW_WHILE";
				case "for": return "KW_FOR";
				case "switch": return "KW_SWITCH";
				case "case": return "KW_CASE";
				case "eval": return "KW_EVAL";
				case "cache": return "KW_CACHE";
				case "connect": return "KW_CONNECT";
				case "process": return "KW_PROCESS";
				case "return": return "KW_RETURN";
				case "sleep": return "KW_SLEEP";
				case "use": return "KW_USE";
				case "try": return "KW_TRY";
				case "throw": return "KW_THROW";
				case "break": return "KW_BREAK";
				case "continue": return "KW_CONTINUE";
				default: return null;
			}
		}

		private int matchWordOperator() {
			String[] ops = { "def", "is", "eq", "ne", "lt", "gt", "le", "ge", "in", "-f", "-d" };
			for (String op : ops) {
				if (regionMatches(pos, op) && isWordBoundary(pos + op.length())) {
					return op.length();
				}
			}
			return 0;
		}

		private int matchBoolean() {
			if (regionMatches(pos, "true") && isWordBoundary(pos + 4)) {
				return 4;
			}
			if (regionMatches(pos, "false") && isWordBoundary(pos + 5)) {
				return 5;
			}
			return 0;
		}

		private int matchSymbolOperator() {
			String[] ops = { "!||", "==", "!=", "<=", ">=", "&&", "||", "<<", ">>", "!|", "!", "~", "+", "-", "*", "/", "%", "\\", "<", ">", "&", "|" };
			for (String op : ops) {
				if (regionMatches(pos, op)) {
					return op.length();
				}
			}
			return 0;
		}

		private boolean isWordBoundary(int end) {
			return end >= length || !Character.isLetterOrDigit(text.charAt(end));
		}

		private boolean isStrictLineStart(int offset) {
			if (offset <= 0) {
				return true;
			}
			char prev = text.charAt(offset - 1);
			return prev == '\n' || prev == '\r';
		}

		private String literalTypeName(@NotNull LiteralKind kind) {
			switch (kind) {
				case STRING:
					return "STRING";
				case SQL_BLOCK:
					return "SQL_BLOCK";
				case HTML_DATA:
					return "HTML_DATA";
				default:
					return "TEMPLATE_DATA";
			}
		}

		private boolean regionMatches(int offset, @NotNull String value) {
			if (offset < 0 || offset + value.length() > length) {
				return false;
			}
			for (int i = 0; i < value.length(); i++) {
				if (text.charAt(offset + i) != value.charAt(i)) {
					return false;
				}
			}
			return true;
		}

		private String substring(int start, int end) {
			return Parser3LexerCore.substringSafely(text, start, end);
		}

		private void add(int start, int end, @NotNull String type) {
			if (start >= end) {
				return;
			}
			tokens.add(new Parser3LexerCore.CoreToken(start, end, type, Parser3LexerCore.substringSafely(text, start, end)));
		}

		private void addActual(int start, int end, @NotNull String type) {
			if (start >= end) {
				return;
			}
			tokens.add(new Parser3LexerCore.CoreToken(start, end, type, Parser3LexerCore.substringSafely(text, start, end)));
		}
	}
}
