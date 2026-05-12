package ru.artlebedev.parser3;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import ru.artlebedev.parser3.settings.Parser3PseudoHashCompletionService;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.completion.P3MethodInsertHandler;
import ru.artlebedev.parser3.completion.P3PseudoHashCompletionRegistry;
import ru.artlebedev.parser3.completion.P3TableColumnArgumentCompletionSupport;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Тесты конфигурируемого автокомплита для аргументов и значений.
 */
public class x14_PseudoHashCompletionTest extends Parser3TestCase {

	private List<String> getCompletions(String content) {
		createParser3File("test.p", content);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) {
			return List.of();
		}
		return Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
	}

	private LookupElement getLookupElement(String content, String lookupString) {
		createParser3File("test_lookup.p", content);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);
		return Arrays.stream(elements)
				.filter(element -> lookupString.equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
	}

	private void assertContainsCompletion(List<String> completions, String expected, String message) {
		assertTrue(message + ": " + completions, completions.contains(expected));
	}

	private void assertNotContainsCompletion(List<String> completions, String unexpected, String message) {
		assertFalse(message + ": " + completions, completions.contains(unexpected));
	}

	private int firstIndexOfAny(List<String> completions, String... lookupStrings) {
		int result = -1;
		for (String lookupString : lookupStrings) {
			int index = completions.indexOf(lookupString);
			if (index >= 0 && (result < 0 || index < result)) {
				result = index;
			}
		}
		return result;
	}

	private String parser223RealCurlLoad(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/223-curl.html\n" +
				"$hForm[^hash::create[]]\n" +
				"$hHeader[^hash::create[]]\n" +
				"$f[^curl:load[\n" +
				"\t$.url[http://www.parser.ru/_/tests/223.pl]\n" +
				"\t$.timeout(5)\n" +
				"\t$.httppost[$hForm]\n" +
				"\t$.httpheader[\n" +
				"\t\t^hash::create[$hHeader]\n" +
				"\t]\n" +
				cursorLine +
				"]]\n";
	}

	private String parser346RealCurlOptions(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/346-curl.html\n" +
				"^curl:options[\n" +
				"\t$.http_version[1.0]\n" +
				cursorLine +
				"]\n";
	}

	private String parser235RealMailSend(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/235.html\n" +
				"$hData[^hash::create[]]\n" +
				"^mail:send[\n" +
				"\t^hash::create[$hData]\n" +
				"\t$.from[from@parser3test]\n" +
				"\t$.to[to@parser3test]\n" +
				cursorLine +
				"]\n";
	}

	private String parser430RealJsonParse(String cursorCall) {
		return "@main[]\n" +
				"# на основе parser3/tests/430.html\n" +
				"$f[^file::load[text;253_json.txt; $.charset[windows-1251] ]]\n" +
				"$s[^taint[as-is][$f.text]]\n" +
				cursorCall + "\n";
	}

	private String parser388RealTableSql(String cursorCall) {
		return "@main[]\n" +
				"# на основе parser3/tests/388-sql.html\n" +
				"$query[select pet from pets]\n" +
				cursorCall + "\n";
	}

	private String parser372RealReflectionCreate(String cursorCall) {
		return "@main[]\n" +
				"# на основе parser3/tests/372.html\n" +
				cursorCall + "\n";
	}

	private String parser426RealTableClone(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/426.html\n" +
				"$t[^table::create{data\n" +
				"data1\n" +
				"data2\n" +
				"data3\n" +
				"data4}]\n" +
				cursorLine + "\n";
	}

	private String parser152RealTypedValues(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/152.html\n" +
				"$dtDate[^date::create(2007;01;02;03;04;05)]\n" +
				"$tTable[^table::create{a\tb\n1\t2}]\n" +
				"$fFile[^file::load[text;152.html]]\n" +
				"$fImage[^image::measure[103paf2001.gif]]\n" +
				"$xDoc[^xdoc::create{<?xml version=\"1.0\"?><root><t/><t/><t/></root>}]\n" +
				cursorLine + "\n";
	}

	private String parser035RealTableCreate(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/035.html\n" +
				"$a-comma-b[a,b\n1,2]\n" +
				"$t[^table::create{$a-comma-b}[$.separator[,]]]\n" +
				cursorLine + "\n";
	}

	private String parser157RealFileCopy(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/157.html\n" +
				"$sSrc[157.html]\n" +
				"$sCopy[newdir1/157.copy]\n" +
				cursorLine + "\n";
	}

	private String parser192RealFileLoad(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/192.html\n" +
				"$sName[192.html]\n" +
				cursorLine + "\n";
	}

	private String parser389RealMathAndFile(String cursorLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/389.html\n" +
				"$s[a,b,c]\n" +
				cursorLine + "\n";
	}

	private void withUserPseudoHashConfig(String json, Runnable action) {
		Parser3PseudoHashCompletionService service = Parser3PseudoHashCompletionService.getInstance();
		Parser3ProjectSettings projectSettings = Parser3ProjectSettings.getInstance(getProject());
		String oldJson = service.getConfigJson();
		String oldProjectJson = projectSettings.getPseudoHashCompletionConfigJson();
		try {
			service.setConfigJson(json);
			projectSettings.setPseudoHashCompletionConfigJson(null);
			P3PseudoHashCompletionRegistry.clearCaches();
			action.run();
		} finally {
			service.setConfigJson(oldJson);
			projectSettings.setPseudoHashCompletionConfigJson(oldProjectJson);
			P3PseudoHashCompletionRegistry.clearCaches();
		}
	}

	public void testCurlLoadRootPseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser223RealCurlLoad("\t$.<caret>\n")
		);

		assertContainsCompletion(completions, "url", "Реальный Parser3 223-curl: должен предлагаться url");
		assertContainsCompletion(completions, "useragent", "Реальный Parser3 223-curl: должен предлагаться useragent");
		assertContainsCompletion(completions, "timeout", "Реальный Parser3 223-curl: должен предлагаться timeout");
		assertContainsCompletion(completions, "httpheader", "Реальный Parser3 223-curl: должен предлагаться httpheader");
	}

	public void testCurlLoadNestedPseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser223RealCurlLoad("\t$.httpheader[\n\t\t$.A<caret>\n\t]\n")
		);

		assertContainsCompletion(completions, "Arhorization",
				"Реальный Parser3 223-curl: во вложенном httpheader должен предлагаться Arhorization");
	}

	public void testCurlOptionsUsesSamePseudoHashCompletionConfig() {
		List<String> completions = getCompletions(
				parser346RealCurlOptions("\t$.<caret>\n")
		);

		assertContainsCompletion(completions, "url", "Реальный Parser3 346-curl: для curl:options должен предлагаться url");
		assertContainsCompletion(completions, "timeout", "Реальный Parser3 346-curl: для curl:options должен предлагаться timeout");
		assertContainsCompletion(completions, "httpheader", "Реальный Parser3 346-curl: для curl:options должен предлагаться httpheader");
	}

	public void testPseudoHashCompletionShowsComment() {
		LookupElement timeoutElement = getLookupElement(
				parser346RealCurlOptions("\t$.ti<caret>\n"),
				"timeout"
		);
		assertNotNull("Должен быть вариант timeout", timeoutElement);

		LookupElementPresentation presentation = new LookupElementPresentation();
		timeoutElement.renderElement(presentation);

		assertEquals("curl:options", presentation.getTypeText());
		assertNotNull("У timeout должен быть комментарий", presentation.getTailText());
		assertTrue("Комментарий timeout должен попадать в tail text: " + presentation.getTailText(),
				presentation.getTailText().contains("Таймаут"));
	}

	public void testCurlLoadPseudoHashInsertBrackets() {
		createParser3File(
				"test_insert.p",
				parser223RealCurlLoad("\t$.u<caret>\n")
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement urlElement = Arrays.stream(elements)
				.filter(element -> "url".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант url", urlElement);

		myFixture.getLookup().setCurrentItem(urlElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должно быть $.url[]: " + text, text.contains("$.url[]"));
	}

	public void testCurlLoadPseudoHashInsertRoundBrackets() {
		createParser3File(
				"test_insert_round.p",
				parser223RealCurlLoad("\t$.ti<caret>\n")
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement timeoutElement = Arrays.stream(elements)
				.filter(element -> "timeout".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант timeout", timeoutElement);

		myFixture.getLookup().setCurrentItem(timeoutElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должно быть $.timeout(): " + text, text.contains("$.timeout()"));
	}

	public void testPseudoHashInsertReplacesExistingSquareBrackets() {
		createParser3File(
				"test_insert_replace_square.p",
				parser223RealCurlLoad("\t$.charse<caret>[]\n")
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement charsetElement = Arrays.stream(elements)
				.filter(element -> "charset".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант charset", charsetElement);

		myFixture.getLookup().setCurrentItem(charsetElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должен остаться один блок $.charset[]: " + text, text.contains("$.charset[]"));
		assertFalse("После вставки не должно быть $.charset[][]: " + text, text.contains("$.charset[][]"));
	}

	public void testPseudoHashInsertKeepsExistingNonEmptyRoundBrackets() {
		createParser3File(
				"test_insert_keep_round.p",
				parser223RealCurlLoad("\t$.charse<caret>(1)\n")
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement charsetElement = Arrays.stream(elements)
				.filter(element -> "charset".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант charset", charsetElement);

		myFixture.getLookup().setCurrentItem(charsetElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должно остаться $.charset(1): " + text, text.contains("$.charset(1)"));
		assertFalse("После вставки не должно быть $.charset[](1): " + text, text.contains("$.charset[](1)"));
	}

	public void testPseudoHashInsertReplacesExistingEmptyRoundBracketsWithConfiguredBrackets() {
		createParser3File(
				"test_insert_replace_round.p",
				parser223RealCurlLoad("\t$.charse<caret>()\n")
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement charsetElement = Arrays.stream(elements)
				.filter(element -> "charset".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант charset", charsetElement);

		myFixture.getLookup().setCurrentItem(charsetElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки пустые () должны замениться на $.charset[]: " + text, text.contains("$.charset[]"));
		assertFalse("После вставки не должно быть $.charset(): " + text, text.contains("$.charset()"));
	}

	public void testCurlLoadPseudoHashInsertNestedBlock() {
		createParser3File(
				"test_insert_nested.p",
				parser223RealCurlLoad("\t$.htt<caret>\n")
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement httpheaderElement = Arrays.stream(elements)
				.filter(element -> "httpheader".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант httpheader", httpheaderElement);

		myFixture.getLookup().setCurrentItem(httpheaderElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должен появиться многострочный блок httpheader: " + text,
				text.contains("$.httpheader[\n\t\t\n\t]"));
	}

	public void testPseudoHashInsertReplacesExistingNestedSquareBrackets() {
		createParser3File(
				"test_insert_replace_nested_square.p",
				parser223RealCurlLoad("\t$.httpheader[\n\t\t$.Arhorizati<caret>[]\n\t]\n")
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement authorizationElement = Arrays.stream(elements)
				.filter(element -> "Arhorization".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант Arhorization", authorizationElement);

		myFixture.getLookup().setCurrentItem(authorizationElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должен остаться один блок $.Arhorization[]: " + text, text.contains("$.Arhorization[]"));
		assertFalse("После вставки не должно быть $.Arhorization[][]: " + text, text.contains("$.Arhorization[][]"));
	}
	public void testPseudoHashDoesNotFallbackToRegularVariables() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"$var[x]\n" +
						"$.<caret>\n"
		);

		assertFalse("После $. не должны предлагаться обычные переменные: " + completions, completions.contains("var"));
	}

	public void testPseudoHashOutsideSpecialCallHasNoRegularVariables() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"$var[]\n" +
						"$.<caret>\n"
		);

		assertFalse("Вне спец-вызова после $. не должны предлагаться обычные переменные: " + completions,
				completions.contains("var"));
	}

	public void testPseudoHashInsideBracesDoesNotFallbackToRegularVariables() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"$var[]\n" +
						"${.<caret>\n"
		);

		assertFalse("В ${. ...} не должны предлагаться обычные переменные: " + completions, completions.contains("var"));
	}

	public void testPseudoHashAfterIndexedVariableDoesNotFallbackInsideBraces() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/010.html\n" +
						"$var[x]\n" +
						"${.<caret>\n"
		);

		assertFalse("После $var[x] и в ${. ...} не должны предлагаться обычные переменные: " + completions,
				completions.contains("var"));
	}
	public void testJsonParsePseudoHashCompletionAfterFirstTopLevelSemicolon() {
		List<String> completions = getCompletions(
				parser430RealJsonParse("$o[^json:parse[^if($a){b;c}; $.<caret>]]")
		);

		assertContainsCompletion(completions, "depth", "Для json:parse после первого верхнеуровневого ; должен предлагаться depth");
		assertContainsCompletion(completions, "distinct", "Для json:parse после первого верхнеуровневого ; должен предлагаться distinct");
		assertContainsCompletion(completions, "taint", "Для json:parse после первого верхнеуровневого ; должен предлагаться taint");
	}

	public void testJsonParsePseudoHashIgnoresSemicolonsInsideNestedBrackets() {
		List<String> completions = getCompletions(
				parser430RealJsonParse("$o[^json:parse[$arr[1;2;3]; $.<caret>]]")
		);

		assertContainsCompletion(completions, "object", "Для json:parse не должны учитываться ; внутри вложенных []");
		assertContainsCompletion(completions, "array", "Для json:parse не должны учитываться ; внутри вложенных []");
	}

	public void testJsonParsePseudoHashCompletionInSecondBracketGroup() {
		List<String> completions = getCompletions(
				parser430RealJsonParse("$json[^json:parse[{\"x\": 1}][$.<caret>]]")
		);

		assertContainsCompletion(completions, "depth", "Для json:parse второй блок скобок должен считаться следующим параметром");
		assertContainsCompletion(completions, "distinct", "Для json:parse второй блок скобок должен считаться следующим параметром");
	}

	public void testFileCreatePseudoHashCompletionForSecondParameter() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/344.html\n" +
						"$file[^file::create[$src; $.<caret>]]"
		);

		assertContainsCompletion(completions, "from-charset", "Для file::create во втором параметре должен предлагаться from-charset");
		assertContainsCompletion(completions, "to-charset", "Для file::create во втором параметре должен предлагаться to-charset");
		assertContainsCompletion(completions, "content-type", "Для file::create во втором параметре должен предлагаться content-type");
		assertContainsCompletion(completions, "name", "Для file::create во втором параметре должен предлагаться name");
		assertContainsCompletion(completions, "mode", "Для file::create во втором параметре должен предлагаться mode");
	}

	public void testFileCreatePseudoHashCompletionForFourthParameter() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/205.html и 344.html\n" +
						"$file[^file::create[text;export.xml;body; $.<caret>]]"
		);

		assertContainsCompletion(completions, "from-charset", "Для file::create в четвертом параметре должен предлагаться from-charset");
		assertContainsCompletion(completions, "content-type", "Для file::create в четвертом параметре должен предлагаться content-type");
		assertNotContainsCompletion(completions, "name", "Для file::create в четвертом параметре не должен предлагаться name");
		assertNotContainsCompletion(completions, "mode", "Для file::create в четвертом параметре не должен предлагаться mode");
	}

	public void testTableSqlPseudoHashCompletionInOptionsBlock() {
		List<String> completions = getCompletions(
				parser388RealTableSql("$t[^table::sql{$query}[$.<caret>]]")
		);

		assertContainsCompletion(completions, "limit", "Для table::sql в блоке опций должен предлагаться limit");
		assertContainsCompletion(completions, "offset", "Для table::sql в блоке опций должен предлагаться offset");
		assertContainsCompletion(completions, "bind", "Для table::sql в блоке опций должен предлагаться bind");
	}

	public void testTableSqlPseudoHashCompletionInsideCurlyBlockAfterSemicolon() {
		List<String> completions = getCompletions(
				parser388RealTableSql("$t[^table::sql{select pet from pets; $.<caret>}]")
		);

		assertContainsCompletion(completions, "limit", "Для table::sql после ; внутри {} должен предлагаться limit");
		assertContainsCompletion(completions, "bind", "Для table::sql после ; внутри {} должен предлагаться bind");
	}

	public void testTableSqlPseudoHashCompletionInSecondBracketGroupAfterSquareBlock() {
		List<String> completions = getCompletions(
				parser388RealTableSql("$t[^table::sql[$query][$.<caret>]]")
		);

		assertContainsCompletion(completions, "limit", "Для table::sql второй блок [] должен считаться следующим параметром");
		assertContainsCompletion(completions, "offset", "Для table::sql второй блок [] должен считаться следующим параметром");
	}

	public void testTableSqlPseudoHashDoesNotWorkInsideSqlBody() {
		List<String> completions = getCompletions(
				parser388RealTableSql("$t[^table::sql{select $.<caret> from pets}[ $.limit(1) ]]")
		);

		assertNotContainsCompletion(completions, "limit", "Внутри SQL-блока table::sql не должны предлагаться опции");
		assertNotContainsCompletion(completions, "bind", "Внутри SQL-блока table::sql не должны предлагаться опции");
	}
	public void testUseOperatorPseudoHashCompletionAfterSemicolon() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/182.html\n" +
						"^use[module.p; $.<caret>]"
		);

		assertContainsCompletion(completions, "replace", "Для ^use после ; должен предлагаться replace");
		assertContainsCompletion(completions, "main", "Для ^use после ; должен предлагаться main");
	}

	public void testProcessOperatorPseudoHashCompletionInThirdParameterBlock() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/176.html\n" +
						"^process[cmd](arg){$.<caret>}"
		);

		assertContainsCompletion(completions, "main", "Для ^process третий блок скобок должен читаться как colon2");
		assertContainsCompletion(completions, "file", "Для ^process третий блок скобок должен читаться как colon2");
		assertContainsCompletion(completions, "lineno", "Для ^process третий блок скобок должен читаться как colon2");
	}

	public void testDateDynamicMethodPseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser152RealTypedValues("^dtDate.iso-string[$.<caret>]")
		);

		assertContainsCompletion(completions, "colon", "Для динамического метода date.iso-string должен предлагаться colon");
		assertContainsCompletion(completions, "ms", "Для динамического метода date.iso-string должен предлагаться ms");
		assertContainsCompletion(completions, "z", "Для динамического метода date.iso-string должен предлагаться z");
	}

	public void testStringDynamicMethodPseudoHashCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/153.html и 398.html\n" +
						"$s[^string:base64[test]]\n" +
						"^s.base64[$.<caret>]"
		);

		assertContainsCompletion(completions, "wrap", "Для динамического метода string.base64 должен предлагаться wrap");
		assertContainsCompletion(completions, "url-safe", "Для динамического метода string.base64 должен предлагаться url-safe");
		assertContainsCompletion(completions, "pad", "Для динамического метода string.base64 должен предлагаться pad");
	}

	public void testImageHtmlDynamicMethodPseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser152RealTypedValues("^fImage.html[$.<caret>]")
		);

		assertContainsCompletion(completions, "border", "Для динамического метода image.html должен предлагаться border");
		assertContainsCompletion(completions, "alt", "Для динамического метода image.html должен предлагаться alt");
	}

	public void testImageFontDynamicMethodPseudoHashCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/098.html\n" +
						"$img[^image::create(100;100;0x00FF00)]\n" +
						"^img.font[0123;digits.gif][$.<caret>]"
		);

		assertContainsCompletion(completions, "space", "Для динамического метода image.font должен предлагаться space");
		assertContainsCompletion(completions, "width", "Для динамического метода image.font должен предлагаться width");
		assertContainsCompletion(completions, "spacing", "Для динамического метода image.font должен предлагаться spacing");
	}

	public void testTableDynamicMethodPseudoHashCompletionForSecondParameter() {
		List<String> completions = getCompletions(
				parser035RealTableCreate("^t.save[path; $.<caret>]")
		);

		assertContainsCompletion(completions, "separator", "Для динамического метода table.save после ; должен предлагаться separator");
		assertContainsCompletion(completions, "encloser", "Для динамического метода table.save после ; должен предлагаться encloser");
	}

	public void testMathConvertPseudoHashCompletionInFourthGroup() {
		List<String> completions = getCompletions(
				parser389RealMathAndFile("^math:convert[1][rad](deg){$.<caret>}")
		);

		assertContainsCompletion(completions, "format", "Для math:convert четвёртая группа аргументов должна читаться как colon3");
	}

	public void testFileExecPseudoHashCompletionForEnvironmentHash() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/264.html\n" +
						"$file[^file::exec[script.pl; $.<caret>]]"
		);

		assertContainsCompletion(completions, "stdin", "Для file::exec в env_hash должен предлагаться stdin");
		assertContainsCompletion(completions, "charset", "Для file::exec в env_hash должен предлагаться charset");
		assertNotContainsCompletion(completions, "QUERY_STRING", "Для file::exec в env_hash не должны показываться недокументированные ключи");
	}

	public void testFileExecPseudoHashCompletionForBinaryOverloadEnvironmentHash() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/286.html и 370.html\n" +
						"$file[^file::exec[binary;script.pl; $.<caret>]]"
		);

		assertContainsCompletion(completions, "stdin", "Для file::exec с форматом env_hash должен читаться как colon2");
		assertContainsCompletion(completions, "charset", "Для file::exec с форматом env_hash должен читаться как colon2");
		assertNotContainsCompletion(completions, "CGI_FILENAME", "Для file::exec с форматом не должны показываться недокументированные ключи");
	}

	public void testReflectionCreatePseudoHashCompletionInFirstParameter() {
		List<String> completions = getCompletions(
				parser372RealReflectionCreate("$obj[^reflection:create[$.<caret>;create]]")
		);

		assertContainsCompletion(completions, "class", "Для reflection:create в первом параметре должен предлагаться class");
		assertContainsCompletion(completions, "constructor", "Для reflection:create в первом параметре должен предлагаться constructor");
		assertContainsCompletion(completions, "arguments", "Для reflection:create в первом параметре должен предлагаться arguments");
	}

	public void testReflectionCreateNestedArgumentsPseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser372RealReflectionCreate("$obj[^reflection:create[$.arguments[\n" +
						"\t$.<caret>\n" +
						"];create]]")
		);

		assertContainsCompletion(completions, "1", "В arguments должен предлагаться первый аргумент");
		assertContainsCompletion(completions, "2", "В arguments должен предлагаться второй аргумент");
	}

	public void testFileCopyPseudoHashCompletionInThirdParameter() {
		List<String> completions = getCompletions(
				parser157RealFileCopy("^file:copy[$sSrc;$sCopy; $.<caret>]")
		);

		assertContainsCompletion(completions, "append", "Реальный Parser3 157: для file:copy в третьем параметре должен предлагаться append");
	}

	public void testFileBase64DynamicMethodPseudoHashCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/152.html и 389.html\n" +
						"$file[^file::load[binary;image.gif]]\n" +
						"^file.base64[$.<caret>]"
		);

		assertContainsCompletion(completions, "wrap", "Для file.base64 должен предлагаться wrap");
		assertContainsCompletion(completions, "url-safe", "Для file.base64 должен предлагаться url-safe");
		assertContainsCompletion(completions, "pad", "Для file.base64 должен предлагаться pad");
	}

	public void testFileConstructorBase64PseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser389RealMathAndFile("$file[^file::base64[text;fname.text;XXXXXX; $.<caret>]]")
		);

		assertContainsCompletion(completions, "strict", "Для file::base64 должен предлагаться strict");
		assertContainsCompletion(completions, "url-safe", "Для file::base64 должен предлагаться url-safe");
		assertContainsCompletion(completions, "pad", "Для file::base64 должен предлагаться pad");
		assertContainsCompletion(completions, "content-type", "Для file::base64 должен предлагаться content-type");
	}

	public void testFileLoadPseudoHashCompletionInThirdParameter() {
		List<String> completions = getCompletions(
				parser192RealFileLoad("$file[^file::load[text;$sName; $.<caret>]]")
		);

		assertContainsCompletion(completions, "timeout", "Для file::load в третьем параметре должен предлагаться timeout");
		assertContainsCompletion(completions, "headers", "Для file::load в третьем параметре должен предлагаться headers");
		assertContainsCompletion(completions, "offset", "Для file::load в третьем параметре должен предлагаться offset");
		assertContainsCompletion(completions, "limit", "Для file::load в третьем параметре должен предлагаться limit");
	}

	public void testFileLoadPseudoHashCompletionInFourthParameter() {
		List<String> completions = getCompletions(
				parser192RealFileLoad("$file[^file::load[text;$sName;overrided.html; $.<caret>]]")
		);

		assertContainsCompletion(completions, "timeout", "Для file::load в четвёртом параметре должен предлагаться timeout");
		assertContainsCompletion(completions, "user", "Для file::load в четвёртом параметре должен предлагаться user");
		assertContainsCompletion(completions, "limit", "Для file::load в четвёртом параметре должен предлагаться limit");
	}

	public void testTableCreateClonePseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser426RealTableClone("$b[^table::create[$t; $.<caret>]]")
		);

		assertContainsCompletion(completions, "offset", "Реальный Parser3 426: для table::create при копировании таблицы должен предлагаться offset");
		assertContainsCompletion(completions, "limit", "Реальный Parser3 426: для table::create при копировании таблицы должен предлагаться limit");
		assertContainsCompletion(completions, "reverse", "Реальный Parser3 426: для table::create при копировании таблицы должен предлагаться reverse");
	}

	public void testXdocStringPseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser152RealTypedValues("^xDoc.string[$.<caret>]")
		);

		assertContainsCompletion(completions, "method", "Для xdoc.string должен предлагаться method");
		assertContainsCompletion(completions, "indent", "Для xdoc.string должен предлагаться indent");
		assertContainsCompletion(completions, "omit-xml-declaration", "Для xdoc.string должен предлагаться omit-xml-declaration");
	}

	public void testXdocFilePseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser152RealTypedValues("$file[^xDoc.file[$.<caret>]]")
		);

		assertContainsCompletion(completions, "method", "Для xdoc.file должен предлагаться method");
		assertContainsCompletion(completions, "media-type", "Для xdoc.file должен предлагаться media-type");
		assertContainsCompletion(completions, "file", "Для xdoc.file должен предлагаться file");
	}

	public void testXdocSavePseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser152RealTypedValues("^xDoc.save[out.xml; $.<caret>]")
		);

		assertContainsCompletion(completions, "method", "Для xdoc.save после ; должен предлагаться method");
		assertContainsCompletion(completions, "encoding", "Для xdoc.save после ; должен предлагаться encoding");
		assertNotContainsCompletion(completions, "file", "Для xdoc.save не должен предлагаться file");
	}

	public void testMemcachedOpenPseudoHashCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/389.html\n" +
						"$m[^memcached::open[$.<caret>]]"
		);

		assertContainsCompletion(completions, "server", "Для memcached::open должен предлагаться server");
		assertContainsCompletion(completions, "binary-protocol", "Для memcached::open должен предлагаться binary-protocol");
		assertContainsCompletion(completions, "connect-timeout", "Для memcached::open должен предлагаться connect-timeout");
		assertContainsCompletion(completions, "tcp-keepalive", "Для memcached::open должен предлагаться tcp-keepalive");
	}

	public void testMailSendPseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser235RealMailSend("\t$.<caret>\n")
		);

		assertContainsCompletion(completions, "text", "Для mail:send должен предлагаться text");
		assertContainsCompletion(completions, "html", "Для mail:send должен предлагаться html");
		assertContainsCompletion(completions, "file", "Для mail:send должен предлагаться file");
		assertContainsCompletion(completions, "charset", "Для mail:send должен предлагаться charset");
		assertContainsCompletion(completions, "options", "Для mail:send должен предлагаться options");
		assertContainsCompletion(completions, "print-debug", "Для mail:send должен предлагаться print-debug");
		assertContainsCompletion(completions, "from", "Для mail:send должен предлагаться from");
		assertContainsCompletion(completions, "to", "Для mail:send должен предлагаться to");
		assertContainsCompletion(completions, "subject", "Для mail:send должен предлагаться subject");
		assertContainsCompletion(completions, "cc", "Для mail:send должен предлагаться cc");
		assertContainsCompletion(completions, "bcc", "Для mail:send должен предлагаться bcc");
	}

	public void testMailSendNestedFilePseudoHashCompletion() {
		List<String> completions = getCompletions(
				parser235RealMailSend("\t$.file[\n\t\t$.<caret>\n\t]\n")
		);

		assertContainsCompletion(completions, "value", "Во вложении mail:send должен предлагаться value");
		assertContainsCompletion(completions, "name", "Во вложении mail:send должен предлагаться name");
		assertContainsCompletion(completions, "content-id", "Во вложении mail:send должен предлагаться content-id");
		assertContainsCompletion(completions, "format", "Во вложении mail:send должен предлагаться format");
	}

	public void testTaintParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/032.html и 441.html\n" +
						"^taint[<caret>][text]"
		);

		assertContainsCompletion(completions, "as-is", "Для taint в первом параметре должен предлагаться as-is");
		assertContainsCompletion(completions, "HTTP-header", "Для taint в первом параметре должен предлагаться HTTP-header");
		assertContainsCompletion(completions, "mail-header", "Для taint в первом параметре должен предлагаться mail-header");
		assertContainsCompletion(completions, "sql", "Для taint в первом параметре должен предлагаться sql");
		assertContainsCompletion(completions, "js", "Для taint в первом параметре должен предлагаться js");
		assertContainsCompletion(completions, "json", "Для taint в первом параметре должен предлагаться json");
		assertContainsCompletion(completions, "parser-code", "Для taint в первом параметре должен предлагаться parser-code");
		assertContainsCompletion(completions, "regex", "Для taint в первом параметре должен предлагаться regex");
		assertContainsCompletion(completions, "xml", "Для taint в первом параметре должен предлагаться xml");
		assertContainsCompletion(completions, "optimized-as-is", "Для taint в первом параметре должен предлагаться optimized-as-is");
		assertContainsCompletion(completions, "optimized-xml", "Для taint в первом параметре должен предлагаться optimized-xml");
		assertContainsCompletion(completions, "html", "Для taint в первом параметре должен предлагаться html");
		assertContainsCompletion(completions, "uri", "Для taint в первом параметре должен предлагаться uri");
	}

	public void testUntaintParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/117.html\n" +
						"^untaint[o<caret>]{text}"
		);

		assertContainsCompletion(completions, "optimized-html", "Для untaint в первом параметре должен предлагаться optimized-html");
		assertContainsCompletion(completions, "optimized-as-is", "Для untaint в первом параметре должен предлагаться optimized-as-is");
		assertContainsCompletion(completions, "optimized-xml", "Для untaint в первом параметре должен предлагаться optimized-xml");
	}

	public void testApplyTaintParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/401.html\n" +
						"^apply-taint[<caret>][text]"
		);

		assertContainsCompletion(completions, "uri", "Для apply-taint в первом параметре должен предлагаться uri");
		assertContainsCompletion(completions, "file-spec", "Для apply-taint в первом параметре должен предлагаться file-spec");
		assertContainsCompletion(completions, "json", "Для apply-taint в первом параметре должен предлагаться json");
		assertContainsCompletion(completions, "parser-code", "Для apply-taint в первом параметре должен предлагаться parser-code");
	}

	public void testFileLoadFormatParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/152.html и 192.html\n" +
						"$file[^file::load[<caret>;data.txt]]"
		);

		assertContainsCompletion(completions, "text", "Для file::load в первом параметре должен предлагаться text");
		assertContainsCompletion(completions, "binary", "Для file::load в первом параметре должен предлагаться binary");
	}

	public void testFileBase64FormatParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/389.html\n" +
						"$file[^file::base64[<caret>;image.gif;$encoded]]"
		);

		assertContainsCompletion(completions, "text", "Для file::base64 в первом параметре должен предлагаться text");
		assertContainsCompletion(completions, "binary", "Для file::base64 в первом параметре должен предлагаться binary");
	}

	public void testFileSaveFormatParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/152.html\n" +
						"$file[^file::load[binary;image.gif]]\n" +
						"^file.save[<caret>;out.gif]"
		);

		assertContainsCompletion(completions, "text", "Для file.save в первом параметре должен предлагаться text");
		assertContainsCompletion(completions, "binary", "Для file.save в первом параметре должен предлагаться binary");
	}

	public void testStringUnescapeModeParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/401.html\n" +
						"^string:unescape[<caret>;value]"
		);

		assertContainsCompletion(completions, "js", "Для string:unescape в первом параметре должен предлагаться js");
		assertContainsCompletion(completions, "uri", "Для string:unescape в первом параметре должен предлагаться uri");
	}

	public void testRegexCreateFlagsParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/212.html\n" +
						"$re[^regex::create[(.+)][<caret>]]"
		);

		assertContainsCompletion(completions, "i", "Для regex::create в опциях поиска должен предлагаться i");
		assertContainsCompletion(completions, "g", "Для regex::create в опциях поиска должен предлагаться g");
		assertContainsCompletion(completions, "'", "Для regex::create в опциях поиска должен предлагаться '");
	}

	public void testStringMatchFlagsParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/188.html и 425.html\n" +
						"$s[^string:base64[dGVzdA==]]\n" +
						"^s.match[(.+)][<caret>]"
		);

		assertContainsCompletion(completions, "x", "Для string.match в опциях поиска должен предлагаться x");
		assertContainsCompletion(completions, "n", "Для string.match в опциях поиска должен предлагаться n");
	}

	public void testTableLoadNamelessParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/158.html и 442.html\n" +
						"$t[^table::load[<caret>;data.txt]]"
		);

		assertContainsCompletion(completions, "nameless", "Для table::load в первом параметре должен предлагаться nameless");
	}

	public void testTableSaveModeParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/035.html\n" +
						"$t[^table::create{name\tvalue\n1\tone}]\n" +
						"^t.save[<caret>;out.txt]"
		);

		assertContainsCompletion(completions, "nameless", "Для table.save в первом параметре должен предлагаться nameless");
		assertContainsCompletion(completions, "append", "Для table.save в первом параметре должен предлагаться append");
	}

	public void testStringSaveAppendParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/153.html\n" +
						"$s[^string:base64[dGVzdA==]]\n" +
						"^s.save[<caret>;log.txt]"
		);

		assertContainsCompletion(completions, "append", "Для string.save в первом параметре должен предлагаться append");
	}

	public void testDateSqlStringFormatParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/274.html\n" +
						"$d[^date::now[]]\n" +
						"^d.sql-string[<caret>]"
		);

		assertContainsCompletion(completions, "datetime", "Для date.sql-string должен предлагаться datetime");
		assertContainsCompletion(completions, "date", "Для date.sql-string должен предлагаться date");
		assertContainsCompletion(completions, "time", "Для date.sql-string должен предлагаться time");
	}

	public void testDateRollParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/274.html\n" +
						"$d[^date::now[]]\n" +
						"^d.roll[<caret>]"
		);

		assertContainsCompletion(completions, "year", "Для date.roll должен предлагаться year");
		assertContainsCompletion(completions, "month", "Для date.roll должен предлагаться month");
		assertContainsCompletion(completions, "day", "Для date.roll должен предлагаться day");
		assertContainsCompletion(completions, "TZ", "Для date.roll должен предлагаться TZ");
	}

	public void testDateStaticRollParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/131.html и 411.html\n" +
						"^date:roll[<caret>]"
		);

		assertContainsCompletion(completions, "TZ", "Для date:roll должен предлагаться TZ");
	}

	public void testDateCalendarLocaleParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/202.html\n" +
						"$week[^date:calendar[<caret>](2024;11;30)]"
		);

		assertContainsCompletion(completions, "rus", "Для date:calendar должен предлагаться rus");
		assertContainsCompletion(completions, "eng", "Для date:calendar должен предлагаться eng");
	}

	public void testTableCountModeParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/009.html и 268.html\n" +
						"$t[^table::create{name\tvalue\n1\tone}]\n" +
						"^t.count[<caret>]"
		);

		assertContainsCompletion(completions, "columns", "Для table.count должен предлагаться columns");
		assertContainsCompletion(completions, "cells", "Для table.count должен предлагаться cells");
		assertContainsCompletion(completions, "rows", "Для table.count должен предлагаться rows");
	}

	public void testArrayAtParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/430.html\n" +
						"$a[^array::create[M;20;N]]\n" +
						"^a.at[<caret>]"
		);

		assertContainsCompletion(completions, "first", "Для array.at должен предлагаться first");
		assertContainsCompletion(completions, "last", "Для array.at должен предлагаться last");
	}

	public void testArrayAtResultKindParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/430.html\n" +
						"$a[^array::create[M;20;N]]\n" +
						"^a.at[first; <caret>]"
		);

		assertContainsCompletion(completions, "key", "Для array.at во втором параметре должен предлагаться key");
		assertContainsCompletion(completions, "value", "Для array.at во втором параметре должен предлагаться value");
		assertContainsCompletion(completions, "hash", "Для array.at во втором параметре должен предлагаться hash");
	}

	public void testTaintParamTextInsertKeepsPlainTextForm() {
		createParser3File(
				"test_taint_text_value_insert.p",
				"@main[]\n" +
						"# на основе parser3/tests/032.html\n" +
						"^taint[as-<caret>][text]"
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement asIsElement = Arrays.stream(elements)
				.filter(element -> "as-is".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант as-is", asIsElement);

		myFixture.getLookup().setCurrentItem(asIsElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должно быть ^taint[as-is][text]: " + text, text.contains("^taint[as-is][text]"));
		assertFalse("После вставки не должно быть $.as-is: " + text, text.contains("$.as-is"));
	}

	public void testResponseContentTypeParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс из документации response:content-type\n" +
						"$response:content-type[<caret>]\n"
		);

		assertContainsCompletion(completions, "application/json", "Для $response:content-type[] должен предлагаться application/json");
		assertContainsCompletion(completions, "text/html", "Для $response:content-type[] должен предлагаться text/html");
		assertContainsCompletion(completions, "text/plain", "Для $response:content-type[] должен предлагаться text/plain");
	}

	public void testResponseContentTypeParamTextInsertKeepsPlainTextForm() {
		createParser3File(
				"test_response_content_type_text_value_insert.p",
				"@main[]\n" +
						"# реальный кейс из документации response:content-type\n" +
						"$response:content-type[app<caret>]\n"
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement jsonElement = Arrays.stream(elements)
				.filter(element -> "application/json".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант application/json", jsonElement);

		myFixture.getLookup().setCurrentItem(jsonElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должно быть $response:content-type[application/json]: " + text,
				text.contains("$response:content-type[application/json]"));
		assertFalse("После вставки не должно быть $.application/json: " + text, text.contains("$.application/json"));
	}

	public void testResponseRefreshPseudoHashCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# системный response:refresh\n" +
						"$response:refresh[\n" +
						"\t$.<caret>\n" +
						"]\n"
		);

		assertContainsCompletion(completions, "value", "Для $response:refresh[] должен предлагаться value");
		assertContainsCompletion(completions, "url", "Для $response:refresh[] должен предлагаться url");
	}

	public void testResponseRefreshValueInsertUsesRoundBrackets() {
		createParser3File(
				"test_response_refresh_value_insert.p",
				"@main[]\n" +
						"# системный response:refresh\n" +
						"$response:refresh[\n" +
						"\t$.val<caret>\n" +
						"]\n"
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement valueElement = Arrays.stream(elements)
				.filter(element -> "value".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант value", valueElement);

		myFixture.getLookup().setCurrentItem(valueElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должно быть $.value(): " + text, text.contains("$.value()"));
	}

	public void testResponseRefreshUrlInsertUsesSquareBrackets() {
		createParser3File(
				"test_response_refresh_url_insert.p",
				"@main[]\n" +
						"# системный response:refresh\n" +
						"$response:refresh[\n" +
						"\t$.ur<caret>\n" +
						"]\n"
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement urlElement = Arrays.stream(elements)
				.filter(element -> "url".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть вариант url", urlElement);

		myFixture.getLookup().setCurrentItem(urlElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки должно быть $.url[]: " + text, text.contains("$.url[]"));
	}

	public void testResponseContentTypeParamTextAutoPopupOnTypedLetter() {
		String source = "@main[]\n# реальный кейс из документации response:content-type\n$response:content-type[<caret>]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_response_content_type_autopopup.p", text);
		assertTrue(
				"Для $response:content-type[] popup должен подниматься сразу при вводе MIME-типа",
				P3PseudoHashCompletionRegistry.shouldAutoPopupParamText(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'a'
				)
		);
	}

	public void testTaintParamTextAutoPopupOnTypedLetter() {
		String source = "@main[]\n# на основе parser3/tests/032.html\n^taint[<caret>][text]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_taint_autopopup.p", text);
		assertTrue(
				"Для taint popup должен подниматься сразу при вводе буквы в первом параметре",
				P3PseudoHashCompletionRegistry.shouldAutoPopupParamText(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'a'
				)
		);
	}

	public void testTaintParamTextAutoPopupOnOpeningBracket() {
		String source = "@main[]\n# на основе parser3/tests/032.html\n^taint<caret>";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_taint_autopopup_open_bracket.p", text);
		assertTrue(
				"Для taint popup должен подниматься сразу после открытия аргумента",
				P3PseudoHashCompletionRegistry.shouldAutoPopupParamText(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'['
				)
		);
	}

	public void testTaintParamTextAutoPopupOnTypedDash() {
		String source = "@main[]\n# на основе parser3/tests/032.html\n^taint[as<caret>][text]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_taint_autopopup_dash.p", text);
		assertTrue(
				"Для taint popup должен подниматься и при вводе '-' внутри вида преобразования",
				P3PseudoHashCompletionRegistry.shouldAutoPopupParamText(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'-'
				)
		);
	}

	public void testUserPseudoHashConfigMergesWithBuiltInConfig() {
		withUserPseudoHashConfig(
				"[\n" +
						"\t{\n" +
						"\t\t\"targets\": [{\"class\": \"curl\", \"staticMethod\": \"load\", \"type\": \"params\"}],\n" +
						"\t\t\"params\": [\n" +
						"\t\t\t{\"name\": \"custom-option\", \"brackets\": \"[]\", \"comment\": \"Пользовательская опция.\"}\n" +
						"\t\t]\n" +
						"\t},\n" +
						"\t{\n" +
						"\t\t\"targets\": [{\"operator\": \"my-op\", \"type\": \"params\"}],\n" +
						"\t\t\"paramText\": [\n" +
						"\t\t\t{\"text\": \"mode-a\", \"comment\": \"Пользовательский режим.\"}\n" +
						"\t\t]\n" +
						"\t}\n" +
						"]",
				() -> {
					List<String> curlCompletions = getCompletions(
							parser223RealCurlLoad("\t$.<caret>\n")
					);
					assertContainsCompletion(curlCompletions, "url", "Встроенный completion для curl:load должен сохраниться");
					assertContainsCompletion(curlCompletions, "custom-option", "Пользовательская опция должна добавляться к curl:load");

					List<String> customOperatorCompletions = getCompletions(
							"@main[]\n" +
									"# на основе parser3/tests/401.html\n" +
									"^my-op[m<caret>][text]"
					);
					assertContainsCompletion(customOperatorCompletions, "mode-a", "Пользовательский operator должен подхватываться из JSON настроек");
				}
		);
	}

	public void testUserPseudoHashConfigMergesBuiltinPropertyParamTextWithBuiltInConfig() {
		withUserPseudoHashConfig(
				"[\n" +
						"\t{\n" +
						"\t\t\"targets\": [{\"class\": \"response\", \"builtinProperty\": \"content-type\", \"type\": \"params\"}],\n" +
						"\t\t\"paramText\": [\n" +
						"\t\t\t{\"text\": \"application/vnd.custom+json\", \"comment\": \"Пользовательский MIME-тип.\"}\n" +
						"\t\t]\n" +
						"\t}\n" +
						"]",
				() -> {
					List<String> completions = getCompletions(
							"@main[]\n" +
									"$response:content-type[<caret>]\n"
					);
					assertContainsCompletion(completions, "application/json", "Встроенный MIME completion должен сохраниться");
					assertContainsCompletion(completions, "application/vnd.custom+json", "Пользовательский MIME-тип должен добавляться к $response:content-type[]");
				}
		);
	}

	public void testFileLoadFormatParamTextCompletionAfterHtmlBlock() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе обезличенного HTML+Parser3 кейса с формой и file::load\n" +
						"^form.print[\n" +
						"\t<label class=\"test-form-checkbox\"><input data-required-message=\"Требуется согласие\" data-left-fill-label=\"Согласие\" type=\"checkbox\" name=\"legal\" value=\"1\" /> <span>Я согласен <a class=\"pseudo_link no_visited\" href=\"javascript:void(0)\" onclick=\"^$('#test-data').slideToggle()\">на обработку</a></span></label>\n" +
						"\t<div style=\"display: none\" id=\"test-data\">\n" +
						"\t\t<p><span class=\"test-serif\">Текст <a href=\"https://test.com\">test.com</a></span></p>\n" +
						"\t</div>\n" +
						"]\n" +
						"^if(-f 'test-items.json'){\n" +
						"\t$f[^file::load[te<caret>]]\n" +
						"}"
		);

		assertContainsCompletion(completions, "text", "После HTML-блока file::load должен продолжать предлагать text");
	}

	public void testPseudoHashCompletionInsideQuotedIndexExpression() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/032.html\n" +
						"$var[\"^taint[a<caret>]]"
		);

		assertContainsCompletion(completions, "as-is", "Внутри конструкции с кавычками completion не должен отключаться");
	}

	public void testTableSortDirectionParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/031.html и 431.html\n" +
						"$list[^table::create{id}]\n" +
						"^list.sort($list.id)[<caret>]"
		);

		assertContainsCompletion(completions, "asc", "Для table.sort в параметре направления должен предлагаться asc");
		assertContainsCompletion(completions, "desc", "Для table.sort в параметре направления должен предлагаться desc");
	}

	public void testTableSortDirectionParamTextCompletionByPrefix() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/031.html и 431.html\n" +
						"$list[^table::create{id}]\n" +
						"^list.sort($list.id)[d<caret>]"
		);

		assertContainsCompletion(completions, "desc", "Для table.sort по префиксу d должен предлагаться desc");
		assertNotContainsCompletion(completions, "asc", "Для table.sort по префиксу d не должен предлагаться asc");
	}

	public void testCurlModeNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				parser223RealCurlLoad("\t$.mode[<caret>]\n")
		);

		assertContainsCompletion(completions, "text", "Для $.mode[] в curl:load должен предлагаться text");
		assertContainsCompletion(completions, "binary", "Для $.mode[] в curl:load должен предлагаться binary");
	}

	public void testCurlHttpVersionNestedParamTextCompletionByPrefix() {
		List<String> completions = getCompletions(
				parser223RealCurlLoad("\t$.http_version[2<caret>]\n")
		);

		assertContainsCompletion(completions, "2", "Для $.http_version[] по префиксу 2 должен предлагаться 2");
		assertContainsCompletion(completions, "2.0", "Для $.http_version[] по префиксу 2 должен предлагаться 2.0");
		assertContainsCompletion(completions, "2TLS", "Для $.http_version[] по префиксу 2 должен предлагаться 2TLS");
		assertContainsCompletion(completions, "2ONLY", "Для $.http_version[] по префиксу 2 должен предлагаться 2ONLY");
		assertNotContainsCompletion(completions, "1.1", "Для $.http_version[] по префиксу 2 не должен предлагаться 1.1");
	}

	public void testNestedParamTextAutoPopupOnOpeningBracket() {
		String source = parser223RealCurlLoad("\t$.mode<caret>\n");
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_nested_param_text_autopopup_open_bracket.p", text);
		assertTrue(
				"Для $.mode[] попап должен подниматься сразу после открытия вложенных []",
				P3PseudoHashCompletionRegistry.shouldAutoPopupParamText(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'['
				)
		);
	}

	public void testMathConvertFormatNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/397.html\n" +
						"^math:convert[1][rad](deg){$.format[<caret>]}"
		);

		assertContainsCompletion(completions, "string", "Для $.format[] в math:convert должен предлагаться string");
		assertContainsCompletion(completions, "file", "Для $.format[] в math:convert должен предлагаться file");
	}

	public void testMathDigestFormatNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/141.html\n" +
						"^math:digest[md5;data;$.format[<caret>]]"
		);

		assertContainsCompletion(completions, "hex", "Для $.format[] в math:digest должен предлагаться hex");
		assertContainsCompletion(completions, "base64", "Для $.format[] в math:digest должен предлагаться base64");
		assertContainsCompletion(completions, "file", "Для $.format[] в math:digest должен предлагаться file");
	}

	public void testTableHashTypeNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/009.html и 129.html\n" +
						"$t[^table::create{name\tvalue\n1\tone}]\n" +
						"^t.hash[name;$.type[<caret>]]"
		);

		assertContainsCompletion(completions, "hash", "Для $.type[] в table.hash должен предлагаться hash");
		assertContainsCompletion(completions, "string", "Для $.type[] в table.hash должен предлагаться string");
		assertContainsCompletion(completions, "table", "Для $.type[] в table.hash должен предлагаться table");
	}

	public void testTableHashColumnArgumentCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/341.html\n" +
						"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
						"$hash[^list.hash[<caret>]]"
		);

		assertContainsCompletion(completions, "user_id", "Для первого аргумента table.hash должен предлагаться user_id");
		assertContainsCompletion(completions, "task_id", "Для первого аргумента table.hash должен предлагаться task_id");
		assertContainsCompletion(completions, "user_id", "Для первого аргумента table.hash должен предлагаться user_id");
	}

	public void testTableArrayColumnArgumentCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/440.html\n" +
						"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
						"$array[^list.array[<caret>]]"
		);

		assertContainsCompletion(completions, "user_id", "Для первого аргумента table.array должен предлагаться user_id");
		assertContainsCompletion(completions, "task_id", "Для первого аргумента table.array должен предлагаться task_id");
		assertContainsCompletion(completions, "user_id", "Для первого аргумента table.array должен предлагаться user_id");
	}

	public void testTableHashSecondBracketColumnArgumentCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс parser3/tests/341.html: ^t.hash[$id][$price]\n" +
						"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
						"$hash[^list.hash[user_id][<caret>]]"
		);

		assertContainsCompletion(completions, "user_id", "Для второго bracket-аргумента table.hash должен предлагаться user_id");
		assertContainsCompletion(completions, "task_id", "Для второго bracket-аргумента table.hash должен предлагаться task_id");
		assertContainsCompletion(completions, "user_id", "Для второго bracket-аргумента table.hash должен предлагаться user_id");
	}

	public void testTableHashSecondSemicolonColumnArgumentCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс parser3/tests/341.html: ^t.hash[$id;ups]\n" +
						"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
						"$hash[^list.hash[user_id;<caret>]]"
		);

		assertContainsCompletion(completions, "user_id", "Для второго semicolon-аргумента table.hash должен предлагаться user_id");
		assertContainsCompletion(completions, "task_id", "Для второго semicolon-аргумента table.hash должен предлагаться task_id");
		assertContainsCompletion(completions, "user_id", "Для второго semicolon-аргумента table.hash должен предлагаться user_id");
	}

	public void testTableLocateColumnArgumentCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/041.html\n" +
						"$viewed[^table::sql{SELECT user_id, total_count, viewed_count, progress_percent FROM url_stats}]\n" +
						"^viewed.locate[<caret>;$val]"
		);

		assertContainsCompletion(completions, "user_id", "Для первого аргумента table.locate должен предлагаться user_id");
		assertContainsCompletion(completions, "total_count", "Для первого аргумента table.locate должен предлагаться total_count");
		assertContainsCompletion(completions, "viewed_count", "Для первого аргумента table.locate должен предлагаться viewed_count");
		assertContainsCompletion(completions, "progress_percent", "Для первого аргумента table.locate должен предлагаться progress_percent");
	}

	public void testTableRenameOldColumnArgumentCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс parser3/tests/406.html: ^t.rename[b;e]\n" +
						"$t[^table::create{a\tb\ts\nva\tvb\tvc}]\n" +
						"^t.rename[<caret>;renamed]"
		);

		assertContainsCompletion(completions, "a", "Для старого имени table.rename должен предлагаться a");
		assertContainsCompletion(completions, "b", "Для старого имени table.rename должен предлагаться b");
		assertContainsCompletion(completions, "s", "Для старого имени table.rename должен предлагаться s");
	}

	public void testTableRenameNewColumnArgumentDoesNotCompleteExistingColumns() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# negative case для parser3/tests/406.html: новое имя не должно подсказывать старые колонки\n" +
						"$t[^table::create{a\tb\ts\nva\tvb\tvc}]\n" +
						"^t.rename[b;<caret>]"
		);

		assertNotContainsCompletion(completions, "a", "Для нового имени table.rename не нужно предлагать существующую колонку a");
		assertNotContainsCompletion(completions, "b", "Для нового имени table.rename не нужно предлагать существующую колонку b");
		assertNotContainsCompletion(completions, "s", "Для нового имени table.rename не нужно предлагать существующую колонку s");
	}

	public void testTableRenamePseudoHashOldColumnKeyCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс parser3/tests/406.html: ^t.rename[ $.e[] $.a[z] $.z[a] ]\n" +
						"$t[^table::create{a\tb\ts\nva\tvb\tvc}]\n" +
						"^t.rename[$.<caret>]"
		);

		assertContainsCompletion(completions, "a", "Для pseudo-hash ключа table.rename должен предлагаться a");
		assertContainsCompletion(completions, "b", "Для pseudo-hash ключа table.rename должен предлагаться b");
		assertContainsCompletion(completions, "s", "Для pseudo-hash ключа table.rename должен предлагаться s");
	}

	public void testTableRenamePseudoHashOldColumnKeyInsertAddsBrackets() {
		createParser3File(
				"test_table_rename_pseudo_hash_key_insert.p",
				"@main[]\n" +
						"# реальный кейс parser3/tests/406.html: ^t.rename[ $.e[] $.a[z] $.z[a] ]\n" +
						"$t[^table::create{a\tb\ts\nva\tvb\tvc}]\n" +
						"^t.rename[$.<caret>]"
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть колонки для pseudo-hash ключей table.rename", elements);
		LookupElement bElement = Arrays.stream(elements)
				.filter(element -> "b".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должна быть колонка b", bElement);

		myFixture.getLookup().setCurrentItem(bElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После выбора ключа table.rename должны добавиться []: " + text,
				text.contains("^t.rename[$.b[]]"));
	}

	public void testTableLocateColumnArgumentCompletionRanksColumnsAboveUserTemplates() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/041.html\n" +
						"$viewed[^table::sql{SELECT user_id, total_count, viewed_count, progress_percent FROM url_stats}]\n" +
						"^viewed.locate[<caret>]"
		);

		int firstTemplateIndex = firstIndexOfAny(completions, "curl:load[]", "foreach[]", "mail:send[]");
		assertTrue("В ручном completion шаблоны присутствуют и проверяют реальный конфликт сортировки: " + completions,
				firstTemplateIndex >= 0);
		assertTrue("Колонка user_id должна быть выше пользовательских шаблонов: " + completions,
				completions.indexOf("user_id") >= 0 && completions.indexOf("user_id") < firstTemplateIndex);
		assertTrue("Колонка total_count должна быть выше пользовательских шаблонов: " + completions,
				completions.indexOf("total_count") >= 0 && completions.indexOf("total_count") < firstTemplateIndex);
		assertTrue("Колонка viewed_count должна быть выше пользовательских шаблонов: " + completions,
				completions.indexOf("viewed_count") >= 0 && completions.indexOf("viewed_count") < firstTemplateIndex);
		assertTrue("Колонка progress_percent должна быть выше пользовательских шаблонов: " + completions,
				completions.indexOf("progress_percent") >= 0 && completions.indexOf("progress_percent") < firstTemplateIndex);
	}

	public void testTableLocateColumnArgumentAutoPopupDoesNotShowUserTemplates() {
		createParser3File(
				"test_table_locate_column_autopopup_no_user_templates.p",
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/041.html\n" +
						"$viewed[^table::sql{SELECT user_id, total_count, viewed_count, progress_percent FROM url_stats}]\n" +
						"^viewed.locate[<caret>]"
		);

		myFixture.complete(CompletionType.BASIC, 0);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Auto-popup должен показать колонки table.locate", elements);
		List<String> completions = Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());

		assertContainsCompletion(completions, "user_id", "Auto-popup должен содержать колонки");
		assertNotContainsCompletion(completions, "curl:load[]", "Auto-popup колонок не должен показывать пользовательские шаблоны");
		assertNotContainsCompletion(completions, "foreach[]", "Auto-popup колонок не должен показывать пользовательские шаблоны");
		assertNotContainsCompletion(completions, "mail:send[]", "Auto-popup колонок не должен показывать пользовательские шаблоны");
	}

	public void testTableHashColumnArgumentAutoPopupOnOpeningBracket() {
		String source = "@main[]\n" +
				"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/341.html\n" +
				"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
				"$hash[^list.hash<caret>]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_table_hash_column_autopopup_open_bracket.p", text);
		assertTrue(
				"Для table.hash popup должен подниматься сразу после открытия первого аргумента",
				P3TableColumnArgumentCompletionSupport.shouldAutoPopupOnTypedChar(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'['
				)
		);
	}

	public void testTableArrayColumnArgumentAutoPopupOnOpeningBracket() {
		String source = "@main[]\n" +
				"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/440.html\n" +
				"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
				"$array[^list.array<caret>]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_table_array_column_autopopup_open_bracket.p", text);
		assertTrue(
				"Для table.array popup должен подниматься сразу после открытия первого аргумента",
				P3TableColumnArgumentCompletionSupport.shouldAutoPopupOnTypedChar(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'['
				)
		);
	}

	public void testTableLocateColumnArgumentAutoPopupOnOpeningBracket() {
		String source = "@main[]\n" +
				"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/041.html\n" +
				"$viewed[^table::sql{SELECT user_id, total_count, viewed_count, progress_percent FROM url_stats}]\n" +
				"^viewed.locate<caret>";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_table_locate_column_autopopup_open_bracket.p", text);
		assertTrue(
				"Для table.locate popup должен подниматься сразу после открытия первого аргумента",
				P3TableColumnArgumentCompletionSupport.shouldAutoPopupOnTypedChar(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'['
				)
		);
	}

	public void testTableRenameColumnArgumentAutoPopupOnOpeningBracket() {
		String source = "@main[]\n" +
				"# реальный кейс parser3/tests/406.html: ^t.rename[b;e]\n" +
				"$t[^table::create{a\tb\ts\nva\tvb\tvc}]\n" +
				"^t.rename<caret>";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_table_rename_column_autopopup_open_bracket.p", text);
		assertTrue(
				"Для table.rename popup должен подниматься сразу после открытия первого аргумента",
				P3TableColumnArgumentCompletionSupport.shouldAutoPopupOnTypedChar(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'['
				)
		);
	}

	public void testTableRenamePseudoHashKeyAutoPopupOnDot() {
		String source = "@main[]\n" +
				"# реальный кейс parser3/tests/406.html: ^t.rename[ $.e[] $.a[z] $.z[a] ]\n" +
				"$t[^table::create{a\tb\ts\nva\tvb\tvc}]\n" +
				"^t.rename[$<caret>]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_table_rename_pseudo_hash_key_autopopup_dot.p", text);
		assertTrue(
				"Для table.rename popup должен подниматься после $. внутри pseudo-hash переименования",
				P3TableColumnArgumentCompletionSupport.shouldAutoPopupOnTypedChar(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'.'
				)
		);
	}

	public void testTableLocateColumnArgumentAutoPopupAfterMethodInsert() {
		createParser3File(
				"test_table_locate_column_autopopup_after_method_insert.p",
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/041.html\n" +
						"$viewed[^table::sql{SELECT user_id, total_count, viewed_count, progress_percent FROM url_stats}]\n" +
						"^viewed.lo<caret>"
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] methodElements = myFixture.getLookupElements();
		assertNotNull("Должны быть методы table для ^viewed.lo", methodElements);
		LookupElement locateElement = Arrays.stream(methodElements)
				.filter(element -> "locate".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть метод locate", locateElement);

		myFixture.getLookup().setCurrentItem(locateElement);
		myFixture.finishLookup('\n');
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После выбора locate должен получиться вызов с курсором внутри аргумента: " + text,
				text.contains("^viewed.locate[]"));
		assertTrue("После выбора locate insert handler должен запланировать completion колонок",
				P3MethodInsertHandler.shouldScheduleTableColumnAutoPopup(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						myFixture.getCaretOffset()
				));
	}

	public void testTableRenameColumnArgumentAutoPopupAfterMethodInsert() {
		createParser3File(
				"test_table_rename_column_autopopup_after_method_insert.p",
				"@main[]\n" +
						"# реальный кейс parser3/tests/406.html: ^t.rename[b;e]\n" +
						"$t[^table::create{a\tb\ts\nva\tvb\tvc}]\n" +
						"^t.re<caret>"
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] methodElements = myFixture.getLookupElements();
		assertNotNull("Должны быть методы table для ^t.re", methodElements);
		LookupElement renameElement = Arrays.stream(methodElements)
				.filter(element -> "rename".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть метод rename", renameElement);

		myFixture.getLookup().setCurrentItem(renameElement);
		myFixture.finishLookup('\n');
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После выбора rename должен получиться вызов с курсором внутри аргумента: " + text,
				text.contains("^t.rename[]"));
		assertTrue("После выбора rename insert handler должен запланировать completion колонок",
				P3MethodInsertHandler.shouldScheduleTableColumnAutoPopup(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						myFixture.getCaretOffset()
				));
	}

	public void testTableHashColumnArgumentAutoPopupAfterMethodInsert() {
		createParser3File(
				"test_table_hash_column_autopopup_after_method_insert.p",
				"@main[]\n" +
						"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/341.html\n" +
						"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
						"$hash[^list.ha<caret>]"
		);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] methodElements = myFixture.getLookupElements();
		assertNotNull("Должны быть методы table для ^list.ha", methodElements);
		LookupElement hashElement = Arrays.stream(methodElements)
				.filter(element -> "hash".equals(element.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть метод hash", hashElement);

		myFixture.getLookup().setCurrentItem(hashElement);
		myFixture.finishLookup('\n');
		com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После выбора hash должен получиться вызов с курсором внутри аргумента: " + text,
				text.contains("^list.hash[]"));
		assertTrue("После выбора hash insert handler должен запланировать completion колонок",
				P3MethodInsertHandler.shouldScheduleTableColumnAutoPopup(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						myFixture.getCaretOffset()
				));
	}

	public void testTableHashColumnArgumentAutoPopupOnTypedLetter() {
		String source = "@main[]\n" +
				"# реальный кейс обезличенного fixture errors/5.p; на основе parser3/tests/341.html\n" +
				"$list[^table::sql{SELECT user_id, task_id, user_id FROM tasks}]\n" +
				"$hash[^list.hash[p<caret>]]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		createParser3File("test_table_hash_column_autopopup_typed_letter.p", text);
		assertTrue(
				"Для table.hash popup должен подниматься при наборе имени колонки",
				P3TableColumnArgumentCompletionSupport.shouldAutoPopupOnTypedChar(
						getProject(),
						myFixture.getFile().getVirtualFile(),
						text,
						offset,
						'e'
				)
		);
	}

	public void testHashIntersectionOrderNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/418.html\n" +
						"$left[$.a[1]$.b[2]]\n" +
						"$right[$.b[3]$.c[4]]\n" +
						"^left.intersection[$right;$.order[<caret>]]"
		);

		assertContainsCompletion(completions, "self", "Для $.order[] в hash.intersection должен предлагаться self");
		assertContainsCompletion(completions, "arg", "Для $.order[] в hash.intersection должен предлагаться arg");
	}

	public void testUserPseudoHashConfigMergesNestedParamTextWithBuiltInConfig() {
		withUserPseudoHashConfig(
				"[\n" +
						"\t{\n" +
						"\t\t\"targets\": [{\"class\": \"curl\", \"staticMethod\": \"load\", \"type\": \"params\"}],\n" +
						"\t\t\"params\": [\n" +
						"\t\t\t{\n" +
						"\t\t\t\t\"name\": \"mode\",\n" +
						"\t\t\t\t\"brackets\": \"[]\",\n" +
						"\t\t\t\t\"paramText\": [\n" +
						"\t\t\t\t\t{\"text\": \"stream\", \"comment\": \"Потоковый режим.\"}\n" +
						"\t\t\t\t]\n" +
						"\t\t\t}\n" +
						"\t\t]\n" +
						"\t}\n" +
						"]",
				() -> {
					List<String> completions = getCompletions(
							"@main[]\n" +
									"# на основе parser3/tests/223-curl.html\n" +
									"^curl:load[\n" +
									"\t$.mode[<caret>]\n" +
									"]"
					);
					assertContainsCompletion(completions, "text", "Встроенное nested-значение должно сохраняться");
					assertContainsCompletion(completions, "binary", "Встроенное nested-значение должно сохраняться");
					assertContainsCompletion(completions, "stream", "Пользовательское nested-значение должно добавляться");
				}
		);
	}

	public void testJsonParseDistinctNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/430.html\n" +
						"^json:parse[{\"x\": 1};$.distinct[<caret>]]"
		);

		assertContainsCompletion(completions, "first", "Для $.distinct[] в json:parse должен предлагаться first");
		assertContainsCompletion(completions, "last", "Для $.distinct[] в json:parse должен предлагаться last");
		assertContainsCompletion(completions, "all", "Для $.distinct[] в json:parse должен предлагаться all");
	}

	public void testJsonStringDateNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/430.html\n" +
						"^json:string[$data;$.date[<caret>]]"
		);

		assertContainsCompletion(completions, "sql-string", "Для $.date[] в json:string должен предлагаться sql-string");
		assertContainsCompletion(completions, "gmt-string", "Для $.date[] в json:string должен предлагаться gmt-string");
		assertContainsCompletion(completions, "iso-string", "Для $.date[] в json:string должен предлагаться iso-string");
		assertContainsCompletion(completions, "unix-timestamp", "Для $.date[] в json:string должен предлагаться unix-timestamp");
	}

	public void testReflectionStackTypeNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/437.html\n" +
						"^reflection:stack[$.type[<caret>]]"
		);

		assertContainsCompletion(completions, "bad.command", "Для $.type[] в reflection:stack должен предлагаться bad.command");
	}

	public void testMailSendFileFormatNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/235.html\n" +
						"^mail:send[\n" +
						"\t$.file[\n" +
						"\t\t$.format[<caret>]\n" +
						"\t]\n" +
						"]"
		);

		assertContainsCompletion(completions, "uue", "Для $.file.$.format[] в mail:send должен предлагаться uue");
		assertContainsCompletion(completions, "base64", "Для $.file.$.format[] в mail:send должен предлагаться base64");
	}

	public void testHashSqlTypeNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/388-sql.html\n" +
						"^hash:sql[select 1;$.type[<caret>]]"
		);

		assertContainsCompletion(completions, "hash", "Для $.type[] в hash:sql должен предлагаться hash");
		assertContainsCompletion(completions, "string", "Для $.type[] в hash:sql должен предлагаться string");
		assertContainsCompletion(completions, "table", "Для $.type[] в hash:sql должен предлагаться table");
	}

	public void testJsonParseArrayNestedParamTextCompletionUsesArrayAndHash() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/430.html\n" +
						"^json:parse[{\"x\": 1};$.array[<caret>]]"
		);

		assertContainsCompletion(completions, "array", "Для $.array[] в json:parse должен предлагаться array");
		assertContainsCompletion(completions, "hash", "Для $.array[] в json:parse должен предлагаться hash");
		assertNotContainsCompletion(completions, "compact", "Для $.array[] в json:parse не должен предлагаться compact");
		assertNotContainsCompletion(completions, "object", "Для $.array[] в json:parse не должен предлагаться object");
	}

	public void testReflectionMethodsCallTypeNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/224.html\n" +
						"^reflection:methods[Parser3Class;$.call_type[<caret>]]"
		);

		assertContainsCompletion(completions, "static", "Для $.call_type[] в reflection:methods должен предлагаться static");
		assertContainsCompletion(completions, "dynamic", "Для $.call_type[] в reflection:methods должен предлагаться dynamic");
		assertContainsCompletion(completions, "any", "Для $.call_type[] в reflection:methods должен предлагаться any");
	}

	public void testInetIp2NameIpvNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/180.html\n" +
						"^inet:ip2name[91.197.112.64;$.ipv[<caret>]]"
		);

		assertContainsCompletion(completions, "4", "Для $.ipv[] в inet:ip2name должен предлагаться 4");
		assertContainsCompletion(completions, "6", "Для $.ipv[] в inet:ip2name должен предлагаться 6");
		assertContainsCompletion(completions, "any", "Для $.ipv[] в inet:ip2name должен предлагаться any");
	}

	public void testInetName2IpTableNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/180.html\n" +
						"^inet:name2ip[test.com;$.table(<caret>)]"
		);

		assertContainsCompletion(completions, "true", "Для $.table() в inet:name2ip должен предлагаться true");
		assertContainsCompletion(completions, "false", "Для $.table() в inet:name2ip должен предлагаться false");
	}

	public void testTableHashDistinctTablesNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/009.html и 129.html\n" +
						"$t[^table::create{name\tvalue\n1\tone}]\n" +
						"^t.hash[name;$.distinct[<caret>]]"
		);

		assertContainsCompletion(completions, "tables", "Для $.distinct[] в table.hash должен предлагаться tables");
	}

	public void testXdocStringMethodNestedParamTextCompletion() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/152.html\n" +
						"$doc[^xdoc::create{<a/>}]\n" +
						"^doc.string[$.method[<caret>]]"
		);

		assertContainsCompletion(completions, "xml", "Для $.method[] в xdoc.string должен предлагаться xml");
		assertContainsCompletion(completions, "html", "Для $.method[] в xdoc.string должен предлагаться html");
		assertContainsCompletion(completions, "text", "Для $.method[] в xdoc.string должен предлагаться text");
	}

	public void testUserPseudoHashConfigValidationReportsError() {
		String error = P3PseudoHashCompletionRegistry.validateUserConfig(
				"[\n" +
						"\t{\n" +
						"\t\t\"targets\": [{\"class\": \"curl\", \"staticMethod\": \"load\", \"type\": \"wat\"}],\n" +
						"\t\t\"params\": []\n" +
						"\t}\n" +
						"]"
		);

		assertNotNull("Невалидный пользовательский JSON должен давать ошибку", error);
		assertTrue("Ошибка должна содержать пояснение про type: " + error, error.contains("type"));
	}
}
