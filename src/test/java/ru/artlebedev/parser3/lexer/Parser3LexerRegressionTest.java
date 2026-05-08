package ru.artlebedev.parser3.lexer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Parser3LexerRegressionTest {

	@Test
	public void methodStartResetsAllInterMethodState() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@first[]\n" +
				"<script>test()</script>\n" +
				"@subMenu[][isActive;isUnlink]\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "DEFINE_METHOD", "@subMenu");
		assertHasToken(tokens, "LOCAL_VARIABLE", "isActive");
		assertHasToken(tokens, "LOCAL_VARIABLE", "isUnlink");
		assertFalse(hasToken(tokens, "CSS_DATA", "@subMenu"));
		assertFalse(hasToken(tokens, "JS_DATA", "@subMenu"));
	}

	@Test
	public void regexInsideBracketKeepsInnerSquareBracketsInString() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "$IMG^request:uri.match[/[^^/]*^$][]{}";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "STRING", "/[^^/]*^$");
		assertTrue(countTokenType(tokens, "RBRACKET") >= 2);
	}

	@Test
	public void mixedStringKeepsLeadingIndentInsideStringToken() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@test[]\n" +
				"$topContent[\n" +
				"\t\t<div class=\"items-title\">\n" +
				"\t\t\t^if($cond){ok}\n" +
				"\t\t</div>\n" +
				"]\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);
		Parser3LexerCore.CoreToken token = findTokenContaining(tokens, "STRING", "<div class=\"items-title\">");

		assertNotNull(token);
		assertTrue(token.debugText.startsWith("\n\t\t<div class=\"items-title\">"));
	}

	@Test
	public void leadingSpacesBeforePlainHtmlStayWhitespace() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@test[]\n" +
				"    <div class=\"x\">ok</div>\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "WHITE_SPACE", "    ");
		assertHasToken(tokens, "HTML_DATA", "<div class=\"x\">ok</div>");
	}

	@Test
	public void spacesBetweenTabsBeforeHtmlStayWhitespaceInsideHtmlMode() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@test[]\n" +
				"<div>before</div>\n" +
				"\t    \t<link rel=\"image_src\" href=\"https://test.com$imageuri\"/>\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "WHITE_SPACE", "    ");
		assertFalse(hasToken(tokens, "HTML_DATA", "    "));
		assertHasToken(tokens, "HTML_DATA", "<link rel=\"image_src\" href=\"https://test.com");
	}

	@Test
	public void scriptBodyGetsJsDataInsideHtml() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@test[]\n" +
						"<script>TestSpecialPatterns.init(^$('#item-pattern-announce'))</script>\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "JS_DATA", "TestSpecialPatterns.init(");
	}

	@Test
	public void emptyStyleTagDoesNotBreakFollowingHtml() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@test[]\n" +
				"<style id=\"pattern-items\"></style>\n" +
				"<div class=\"after-style\">ok</div>\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "HTML_DATA", "<style id=\"pattern-items\"></style>");
		assertHasToken(tokens, "HTML_DATA", "<div class=\"after-style\">ok</div>");
	}

	@Test
	public void expressionHashStartsCommentUntilEndOfLine() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "^if(\n" +
				"\t$a eq 'b' # комментарий к выражению\n" +
				"\t&& $c\n" +
				"){ok}\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "LINE_COMMENT", "# комментарий к выражению");
		assertHasToken(tokens, "WORD_OPERATOR", "eq");
		assertHasToken(tokens, "TEMPLATE_DATA", "ok");
	}

	@Test
	public void expressionIfBranchesKeepNumbersInsideHtmlMethod() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@subMenu[][isActive;isUnlink]\n" +
				"<table>\n" +
				"\t$isUnlink(^if($tMidlinks.folder eq ^stUriTrail.left(^tMidlinks.folder.length[])){1}{0})\n" +
				"</table>\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "NUMBER", "1");
		assertHasToken(tokens, "NUMBER", "0");
		assertFalse(hasToken(tokens, "HTML_DATA", "1"));
		assertFalse(hasToken(tokens, "HTML_DATA", "0"));
	}

	@Test
	public void sqlIndentTabsStayWhitespace() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "^table::sql{\n" +
				"\t\t\tselect aid, title, title_over, dt_published\n" +
				"\t\t\tfrom article\n" +
				"\t\t\twhere is_published = '1'\n" +
				"\t\t\torder by dt_published desc, aid desc\n" +
				"}\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertTrue(countTokensContaining(tokens, "WHITE_SPACE", "\t") >= 3);
		assertHasToken(tokens, "SQL_BLOCK", "from article");
		assertFalse(hasToken(tokens, "SQL_BLOCK", "\tfrom article"));
	}

	@Test
	public void sqlIndentHashCommentLineDoesNotResetCommonIndent() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@main[]\n" +
				"$data[^hash::create[]]\n" +
				"$list[^table::sql{\n" +
				"\tSELECT\n" +
				"\t\tid, name\n" +
				"\tFROM\n" +
				"\t\titems\n" +
				"\tWHERE\n" +
				"\t\tid = 1\n" +
				"#\t\tAND id = 5\n" +
				"}]\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "SQL_BLOCK", "SELECT");
		assertHasToken(tokens, "SQL_BLOCK", "id, name");
		assertHasToken(tokens, "LINE_COMMENT", "#\t\tAND id = 5");
		assertFalse(hasToken(tokens, "SQL_BLOCK", "\tSELECT"));
		assertFalse(hasToken(tokens, "SQL_BLOCK", "\tFROM"));
		assertFalse(hasToken(tokens, "SQL_BLOCK", "\tWHERE"));
	}

	@Test
	public void configuredUserSqlInjectionGetsSqlBlockTokens() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "$cnt(^self.oSql.int{\n" +
				"\tSELECT\n" +
				"\t\tCOUNT(*)\n" +
				"\tFROM\n" +
				"\t\titems\n" +
				"})\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input, List.of("^self.oSql.int"));

		assertHasToken(tokens, "SQL_BLOCK", "SELECT");
		assertHasToken(tokens, "SQL_BLOCK", "COUNT(*)");
		assertHasToken(tokens, "SQL_BLOCK", "items");
	}

	@Test
	public void sqlBlockKeepsParser3MethodsAndVariablesHighlighted() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "^table::sql{\n" +
				"\tselect * from article\n" +
				"\twhere site_id = $self.site.id\n" +
				"\t\tand slug = ^site:url[$slug]\n" +
				"\t\tand title = ^if($flag){$title}{^fallback[]}\n" +
				"}\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "SQL_BLOCK", "from article");
		assertHasToken(tokens, "DOLLAR_VARIABLE", "$");
		assertHasToken(tokens, "IMPORTANT_VARIABLE", "self");
		assertHasToken(tokens, "VARIABLE", "site");
		assertHasToken(tokens, "VARIABLE", "id");
		assertHasToken(tokens, "METHOD", "^site");
		assertHasToken(tokens, "METHOD", "url");
		assertHasToken(tokens, "VARIABLE", "slug");
		assertHasToken(tokens, "KW_IF", "^if");
		assertHasToken(tokens, "VARIABLE", "flag");
		assertHasToken(tokens, "VARIABLE", "title");
		assertHasToken(tokens, "METHOD", "fallback");
	}

	@Test
	public void unterminatedSquareBracketDoesNotSwallowFollowingLines() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@main[]\n" +
				"$items.[$_items.from[\n" +
				"\t$.id($_items.from)\n" +
				"^if($env:REMOTE_ADDR eq '127.0.0.1'){ok}\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "KW_IF", "^if");
		assertHasToken(tokens, "VARIABLE", "env");
		assertHasToken(tokens, "VARIABLE", "REMOTE_ADDR");
		assertFalse(hasToken(tokens, "STRING", "^if($env:REMOTE_ADDR eq '127.0.0.1'){ok}"));
	}

	@Test
	public void unterminatedSingleQuoteDoesNotSwallowFollowingLines() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@main[]\n" +
				"$items.[$_items.from][\n" +
				"\t^if($env:REMOTE_ADDR eq '127.0.0.1){\n" +
				"\t\t$.login[$_items.login]\n" +
				"\t}\n" +
				"\t$.is_test(def $_items.user_id)\n" +
				"]\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "KW_IF", "^if");
		assertHasToken(tokens, "VARIABLE", "is_test");
		assertFalse(hasToken(tokens, "STRING", "$.login[$_items.login]\n\t}\n\t$.is_test(def $_items.user_id)\n]"));
	}

	@Test
	public void multilineQuotedStringInsideIfExpressionKeepsClosingQuoteAndBody() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@main[]\n" +
				"^if($options is '\n" +
				"hash\n" +
				"'){}\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "WORD_OPERATOR", "is");
		assertHasToken(tokens, "LSINGLE_QUOTE", "'");
		assertHasToken(tokens, "STRING", "hash");
		assertHasToken(tokens, "RSINGLE_QUOTE", "'");
		assertHasToken(tokens, "RPAREN", ")");
		assertHasToken(tokens, "LBRACE", "{");
		assertHasToken(tokens, "RBRACE", "}");
		assertFalse(hasToken(tokens, "STRING", "){}"));
	}

	@Test
	public void entityCacheMatchesDirectTokenizationAcrossMethods() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@USE\n" +
				"Debug.p\n\n" +
				"@auto[]\n" +
				"$STORAGE[\n" +
				"\t$.persons[^hash::create[]]\n" +
				"]\n" +
				"$USER[^getLoggedUser[]]\n" +
				"^if(def $USER){\n" +
				"\t^server{\n" +
				"\t\t$PERSON[^getUser[$USER]]\n" +
				"\t}\n" +
				"}\n\n" +
				"@template[title;code]\n" +
				"<!DOCTYPE html>\n" +
				"<!-- Copyright (c) Test Project | https://test.com/ -->\n" +
				"<html lang=\"RU\">\n" +
				"<head>\n" +
				"\t<title>$title</title>\n" +
				"</head>\n" +
				"<body>\n" +
				"\t$code\n" +
				"</body>\n" +
				"</html>\n";

		System.clearProperty("parser3.lexer.legacy");
		Parser3LexerCore.setUserSqlInjectionPrefixesSupplier(List::of);

		List<Parser3LexerCore.CoreToken> directTokens = Parser3LexerCore.tokenize(input, 0, input.length());
		List<Parser3LexerCore.CoreToken> cachedTokens = new Parser3EntityCache().getTokens(input, 0, input.length());

		assertCoreTokensEqual(directTokens, cachedTokens);
	}

	@Test
	public void quotedStringWithEmbeddedCaretMethodKeepsTokenOrder() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@main[]\n" +
				"\n" +
				"$var[ok]\n" +
				"\n" +
				"$v.hasAnalyticusEmotions(def $v.analyticus_json_uid && -f '/files/json/emotions/^v.analyticus_json_uid.left(1)/${v.analyticus_json_uid}.json.p')\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "METHOD", "^v");
		assertHasToken(tokens, "METHOD", "left");
		assertHasToken(tokens, "STRING", "/files/json/emotions/");
		assertHasToken(tokens, "STRING", ".json.p");
		assertTokenOrderIsMonotonic(tokens);
	}

	@Test
	public void selectAndSortParamsAreVariableTokens() {
		// реальный минимальный regression-кейс Parser3; при расширении сверять с тестами Parser3.
		String input = "@main[]\n" +
				"$res[^hash::create[]]\n" +
				"$selected[^res.select[select_key_5;select_value_5]($select_value_5 >= $limit)]\n" +
				"$sorted[^res.sort[sort_key_5;sort_value_5]($sort_value_5 >= $limit)]\n";

		List<Parser3LexerCore.CoreToken> tokens = tokenize(input);

		assertHasToken(tokens, "VARIABLE", "select_key_5");
		assertHasToken(tokens, "VARIABLE", "select_value_5");
		assertHasToken(tokens, "VARIABLE", "sort_key_5");
		assertHasToken(tokens, "VARIABLE", "sort_value_5");
		assertFalse(hasToken(tokens, "STRING", "select_key_5"));
		assertFalse(hasToken(tokens, "STRING", "sort_key_5"));
	}

	private static List<Parser3LexerCore.CoreToken> tokenize(String input) {
		return tokenize(input, List.of());
	}

	private static List<Parser3LexerCore.CoreToken> tokenize(String input, List<String> sqlPrefixes) {
		System.clearProperty("parser3.lexer.legacy");
		Parser3LexerCore.setUserSqlInjectionPrefixesSupplier(() -> sqlPrefixes);
		return Parser3LexerCore.tokenize(input, 0, input.length());
	}

	private static void assertHasToken(List<Parser3LexerCore.CoreToken> tokens, String type, String textPart) {
		assertNotNull(findTokenContaining(tokens, type, textPart));
	}

	private static boolean hasToken(List<Parser3LexerCore.CoreToken> tokens, String type, String textPart) {
		return findTokenContaining(tokens, type, textPart) != null;
	}

	private static int countTokenType(List<Parser3LexerCore.CoreToken> tokens, String type) {
		int count = 0;
		for (Parser3LexerCore.CoreToken token : tokens) {
			if (type.equals(token.type)) {
				count++;
			}
		}
		return count;
	}

	private static int countTokensContaining(List<Parser3LexerCore.CoreToken> tokens, String type, String textPart) {
		int count = 0;
		for (Parser3LexerCore.CoreToken token : tokens) {
			if (!type.equals(token.type) || token.debugText == null) {
				continue;
			}
			if (token.debugText.contains(textPart)) {
				count++;
			}
		}
		return count;
	}

	private static Parser3LexerCore.CoreToken findTokenContaining(
			List<Parser3LexerCore.CoreToken> tokens,
			String type,
			String textPart
	) {
		for (Parser3LexerCore.CoreToken token : tokens) {
			if (!type.equals(token.type) || token.debugText == null) {
				continue;
			}
			if (token.debugText.contains(textPart)) {
				return token;
			}
		}
		return null;
	}

	private static void assertCoreTokensEqual(
			List<Parser3LexerCore.CoreToken> expected,
			List<Parser3LexerCore.CoreToken> actual
	) {
		List<String> expectedDump = dumpTokens(expected);
		List<String> actualDump = dumpTokens(actual);
		assertEquals(expectedDump, actualDump);
	}

	private static List<String> dumpTokens(List<Parser3LexerCore.CoreToken> tokens) {
		List<String> dump = new ArrayList<>(tokens.size());
		for (Parser3LexerCore.CoreToken token : tokens) {
			dump.add(token.start + ":" + token.end + ":" + token.type + ":" + String.valueOf(token.debugText));
		}
		return dump;
	}

	private static void assertTokenOrderIsMonotonic(List<Parser3LexerCore.CoreToken> tokens) {
		int previousStart = -1;
		int previousEnd = -1;
		for (Parser3LexerCore.CoreToken token : tokens) {
			assertTrue(
					"Токены идут не по порядку: предыдущий=" + previousStart + ":" + previousEnd +
							", текущий=" + token.start + ":" + token.end + ":" + token.type + ":" + token.debugText,
					token.start >= previousStart && token.start >= previousEnd
			);
			previousStart = token.start;
			previousEnd = token.end;
		}
	}

	private static void assertContinuousCoverage(List<Parser3LexerCore.CoreToken> tokens, int textLength) {
		assertFalse("Список токенов пуст", tokens.isEmpty());
		assertEquals("Первый токен должен начинаться с 0", 0, tokens.get(0).start);
		for (int i = 1; i < tokens.size(); i++) {
			Parser3LexerCore.CoreToken prev = tokens.get(i - 1);
			Parser3LexerCore.CoreToken current = tokens.get(i);
			assertEquals(
					"Дыра или перекрытие между токенами: prev=" + prev.start + ":" + prev.end + ":" + prev.type +
							", current=" + current.start + ":" + current.end + ":" + current.type,
					prev.end,
					current.start
			);
		}
		assertEquals("Последний токен должен заканчиваться в конце текста", textLength, tokens.get(tokens.size() - 1).end);
	}

	private static void assertIdeCoverageContinuous(String input, List<String> ideDump) {
		assertFalse("IDE-лексер не вернул токены", ideDump.isEmpty());
		int previousEnd = -1;
		for (int i = 0; i < ideDump.size(); i++) {
			String[] parts = ideDump.get(i).split(":", 3);
			int start = Integer.parseInt(parts[0]);
			int end = Integer.parseInt(parts[1]);
			if (i == 0) {
				assertEquals("Первый IDE-токен должен начинаться с 0", 0, start);
			} else {
				assertEquals("IDE-токены идут с дырой/перекрытием", previousEnd, start);
			}
			previousEnd = end;
		}
		assertEquals("Последний IDE-токен должен заканчиваться в конце текста", input.length(), previousEnd);
	}

	private static List<String> shiftIdeDump(List<String> ideDump, int delta) {
		List<String> shifted = new ArrayList<>(ideDump.size());
		for (String line : ideDump) {
			String[] parts = line.split(":", 3);
			int start = Integer.parseInt(parts[0]) + delta;
			int end = Integer.parseInt(parts[1]) + delta;
			shifted.add(start + ":" + end + ":" + parts[2]);
		}
		return shifted;
	}
}
