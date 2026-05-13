package ru.artlebedev.parser3;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Тесты автокомплита свойств встроенных классов Parser3:
 * $form:fields, $env:REMOTE_ADDR, ^form:fields., и т.д.
 */
public class x11_BuiltinPropertiesCompletionTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();
	}

	// ==================== HELPERS ====================

	private List<String> getCompletions(String content) {
		createParser3File("test.p", content);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) return List.of();
		return Arrays.stream(elements)
				.flatMap(element -> {
					String lookup = element.getLookupString();
					String suffix = lookup;
					if (lookup.contains("::")) {
						suffix = lookup.substring(lookup.indexOf("::") + 2);
					} else if (lookup.contains(":")) {
						suffix = lookup.substring(lookup.indexOf(':') + 1);
					}
					if (lookup.equals(suffix)) {
						return java.util.stream.Stream.of(lookup);
					}
					return java.util.stream.Stream.of(lookup, suffix);
				})
				.collect(Collectors.toList());
	}

	private List<String> getPresentableTexts(String content) {
		createParser3File("test.p", content);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) return List.of();
		return Arrays.stream(elements)
				.map(e -> e.getLookupString())
				.collect(Collectors.toList());
	}

	private void assertContains(List<String> list, String item, String message) {
		assertTrue(message + ", есть: " + list, list.contains(item));
	}

	private void assertBuiltinPropertyNotDuplicated(String message, String content, String propertyName) {
		List<String> completions = getCompletions(content);
		assertContains(completions, propertyName, message);
		assertEquals(message + " не должен дублироваться из read-chain: " + completions,
				1, Collections.frequency(completions, propertyName));
	}

	private void assertContainsAny(List<String> list, String substring, String message) {
		assertTrue(message + ", есть: " + list,
				list.stream().anyMatch(s -> s.contains(substring)));
	}

	private void assertNotContainsAny(List<String> list, String substring, String message) {
		assertFalse(message + ", есть: " + list,
				list.stream().anyMatch(s -> s.contains(substring)));
	}

	private LookupElement findLookupElement(String content, String lookupString) {
		createParser3File("test.p", content);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);
		for (LookupElement element : elements) {
			if (lookupString.equals(element.getLookupString())) {
				return element;
			}
		}
		fail("Не найден элемент '" + lookupString + "'");
		return null;
	}

	private LookupElementPresentation findLookupElementPresentation(String content, String lookupString) {
		return LookupElementPresentation.renderElement(findLookupElement(content, lookupString));
	}

	private String parser274RealDateBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/274.html\n" +
				"$now[^date::now[]]\n" +
				"$date[^date::today[]]\n" +
				"$d2[^date::create(2000;02;29;01;23;45)]\n" +
				"^d2.sql-string[]\n" +
				completionLine;
	}

	private String parser202RealDateCalendarBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/202.html\n" +
				"$d[^date::create[2008-05-09T00:00:00+3:00]]\n" +
				"$calendar[^date:calendar[rus](2008;2;29)]\n" +
				"$last[^date:last-day(2008;02)]\n" +
				completionLine;
	}

	private String parser152RealFileBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/152.html\n" +
				"$file[^file::load[text;152.html]]\n" +
				"$stat[^file::stat[152.html]]\n" +
				completionLine;
	}

	private String parser223RealCurlFileBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/223-curl.html\n" +
				"$file[^curl:load[\n" +
				"\t$.url[http://www.parser.ru/_/tests/223.pl]\n" +
				"\t$.timeout(5)\n" +
				"]]\n" +
				completionLine;
	}

	private String parser270RealFileFindBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/270.html\n" +
				"$path[^file:find[$sFileSpec]{$hParams.0}]\n" +
				completionLine;
	}

	private String parser152RealXdocBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/152.html\n" +
				"$xDoc[^xdoc::create{<?xml version=\"1.0\"?><root><t/><t/><t/></root>}]\n" +
				"$h[^xDoc.select[/root/t]]\n" +
				"$xNode[$xDoc]\n" +
				completionLine;
	}

	private String parser097RealHttpGlobalsBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/097.html\n" +
				"$response:charset[UTF-8]\n" +
				"$request:charset[UTF-8]\n" +
				"$file[^file::load[binary;http://www.parser.ru/_/tests/none/;\n" +
				"\t$.headers[\n" +
				"\t\t$.USER-AGENT[paf]\n" +
				"\t]\n" +
				"\t$.timeout(10)\n" +
				"\t$.any-status(1)\n" +
				"]]\n" +
				completionLine;
	}

	private String parser102RealFormBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/102.html\n" +
				"$form:fields.foo[bar]\n" +
				"$form:foo[bar]\n" +
				completionLine;
	}

	private String parser005RealCookieBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/005.html\n" +
				"$cookie:name[\n" +
				"\t$.value[value]\n" +
				"\t$.expires[^date::create(2000;01;01)]\n" +
				"]\n" +
				completionLine;
	}

	private String parser075RealResponseBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/075.html\n" +
				"$response:xxxx[^date::create(2023;3;4)]\n" +
				"$response:yyy[\n" +
				"\t$.value[^date::create(2022;2;28)]\n" +
				"\t$.aaaa[^date::create(2025;7;1)]\n" +
				"]\n" +
				"$response:zzz[value]\n" +
				completionLine;
	}

	private String parser208RealStatusBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/208.html\n" +
				"$r0[$status:rusage]\n" +
				"$m0[$status:memory]\n" +
				completionLine;
	}

	public void testDateConstructorCompletion_showsPdfConstructors() {
		List<String> completions = getCompletions(parser274RealDateBase("^date::<caret>"));
		assertContains(completions, "create", "^date:: должен содержать create");
		assertContains(completions, "now", "^date:: должен содержать now");
		assertContains(completions, "today", "^date:: должен содержать today");
		assertContains(completions, "unix-timestamp", "^date:: должен содержать unix-timestamp");
	}

	public void testDateStaticMethodCompletion_showsPdfStaticMethods() {
		List<String> completions = getCompletions(parser202RealDateCalendarBase("^date:<caret>"));
		assertContains(completions, "calendar", "^date: должен содержать calendar");
		assertContains(completions, "last-day", "^date: должен содержать last-day");
		assertContains(completions, "roll", "^date: должен содержать roll");
	}

	public void testDateVariableDollarDot_showsDateFields() {
		List<String> completions = getCompletions(
				parser274RealDateBase("$date.<caret>")
		);

		assertContains(completions, "year", "$date. должен содержать year");
		assertContains(completions, "month", "$date. должен содержать month");
		assertContains(completions, "day", "$date. должен содержать day");
		assertContains(completions, "weekday", "$date. должен содержать weekday");
		assertContains(completions, "weekyear", "$date. должен содержать weekyear");
		assertContains(completions, "daylightsaving", "$date. должен содержать daylightsaving");
		assertContains(completions, "TZ", "$date. должен содержать TZ");
	}

	public void testDateVariableDollarDot_deduplicatesBuiltinFieldAndReadChain() {
		assertBuiltinPropertyNotDuplicated("$dt.mo должен содержать date.month",
				"@main[]\n" +
						"$dt[^date::now[]]\n" +
						"$month[$dt.month]\n" +
						"^if($dt.month < 10){\n" +
						"\t$month[0$dt.month]\n" +
						"}\n" +
						"$dt.mo<caret>\n",
				"month");
	}

	public void testBuiltinVariableDollarDot_deduplicatesOtherBuiltinFieldsAndReadChain() {
		assertBuiltinPropertyNotDuplicated("$file.na должен содержать file.name",
				parser152RealFileBase(
						"$fileName[$file.name]\n" +
								"$file.na<caret>"),
				"name");
		assertBuiltinPropertyNotDuplicated("$stat.md должен содержать file-local.mdate",
				parser152RealFileBase(
						"$statDate[$stat.mdate]\n" +
								"$stat.md<caret>"),
				"mdate");
		assertBuiltinPropertyNotDuplicated("$file.st должен содержать file-http.status",
				parser223RealCurlFileBase(
						"$status[$file.status]\n" +
								"$file.st<caret>"),
				"status");
		assertBuiltinPropertyNotDuplicated("$xNode.node должен содержать xnode.nodeName",
				parser152RealXdocBase(
						"$nodeName[$xNode.nodeName]\n" +
								"$xNode.node<caret>"),
				"nodeName");
	}

	public void testDateVariableCompletion_insertsDotAfterVariableName() {
		createParser3File(
				"test_date_insert_dot.p",
				parser274RealDateBase("$no<caret>")
		);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты completion", elements);

		LookupElement nowElement = null;
		for (LookupElement element : elements) {
			if ("now.".equals(element.getLookupString())) {
				nowElement = element;
				break;
			}
		}
		assertNotNull("Должен быть вариант now. для переменной date", nowElement);

		myFixture.getLookup().setCurrentItem(nowElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("Переменная date должна вставляться с точкой: " + text, text.contains("$now."));
	}

	public void testDateVariableCaretDot_showsDateMethods() {
		List<String> completions = getCompletions(
				parser274RealDateBase("^date.<caret>")
		);

		assertContains(completions, "iso-string", "^date. должен содержать iso-string");
		assertContains(completions, "sql-string", "^date. должен содержать sql-string");
		assertContains(completions, "roll", "^date. должен содержать roll");
		assertContains(completions, "last-day", "^date. должен содержать last-day");
		assertContains(completions, "unix-timestamp", "^date. должен содержать unix-timestamp");
		assertContains(completions, "int", "^date. должен содержать int");
		assertContains(completions, "double", "^date. должен содержать double");
		assertContains(completions, "gmt-string", "^date. должен содержать gmt-string");
	}

	public void testDateDoesNotExposeColonProperties() {
		List<String> dollarCompletions = getCompletions(parser274RealDateBase("$date:<caret>"));
		assertFalse("$date: не должен показывать instance fields: " + dollarCompletions,
				dollarCompletions.contains("year"));

		List<String> caretCompletions = getCompletions(parser274RealDateBase("^date:<caret>"));
		assertFalse("^date: не должен показывать instance fields: " + caretCompletions,
				caretCompletions.contains("year"));
	}

	public void testDateDoesNotAppearAsBuiltinClassAfterDollar() {
		List<String> completions = getPresentableTexts(parser274RealDateBase("$date<caret>"));
		assertFalse("$date не должен предлагать builtin-class date:: " + completions,
				completions.contains("date:"));
	}

	public void testDateAppearsAsBuiltinClassAfterCaret() {
		List<String> completions = getPresentableTexts(parser274RealDateBase("^date<caret>"));
		assertTrue("^date должен предлагать builtin-class date: " + completions,
				completions.contains("date:"));

		List<String> lookupStrings = getCompletions(parser274RealDateBase("^date<caret>"));
		assertContains(lookupStrings, "now", "^date должен продолжать показывать constructor/static methods");
	}

	public void testDateStaticCalendarResultInferredAsTable() {
		List<String> completions = getCompletions(
				parser202RealDateCalendarBase("^calendar.<caret>")
		);

		assertContains(completions, "count", "^calendar. должен видеть table-методы");
		assertContains(completions, "menu", "^calendar. должен видеть table-методы");
	}

	public void testDateStaticLastDayResultInferredAsInt() {
		List<String> completions = getCompletions(
				parser202RealDateCalendarBase("^last.<caret>")
		);

		assertContains(completions, "format", "^last. должен видеть int-методы");
		assertContains(completions, "inc", "^last. должен видеть int-методы");
	}

	public void testFileConstructorCompletion_showsPdfConstructors() {
		List<String> completions = getCompletions(parser152RealFileBase("^file::<caret>"));
		assertContains(completions, "base64", "^file:: должен содержать base64");
		assertContains(completions, "cgi", "^file:: должен содержать cgi");
		assertContains(completions, "exec", "^file:: должен содержать exec");
		assertContains(completions, "create", "^file:: должен содержать create");
		assertContains(completions, "load", "^file:: должен содержать load");
		assertContains(completions, "sql", "^file:: должен содержать sql");
		assertContains(completions, "stat", "^file:: должен содержать stat");
	}

	public void testCaretBuiltinClassReceiverTailShowsMethodsNotFields() {
		LookupElementPresentation presentation = findLookupElementPresentation(
				parser152RealFileBase("^file<caret>"),
				"file:"
		);
		String tailText = presentation.getTailText();
		assertNotNull("^file receiver должен иметь описание", tailText);
		assertTrue("^file receiver должен описывать методы, а не поля: " + tailText,
				tailText.contains("методы:"));
		assertTrue("^file receiver должен перечислять методы класса: " + tailText,
				tailText.contains("base64") || tailText.contains("load") || tailText.contains("stat"));
		assertFalse("^file receiver не должен показывать поля name/size: " + tailText,
				tailText.contains("name, size"));
		assertFalse("^file receiver не должен показывать поля text/mode: " + tailText,
				tailText.contains("text, mode"));
	}

	public void testFileVariableDot_showsDocumentedFields() {
		List<String> completions = getCompletions(
				parser152RealFileBase("$file.<caret>")
		);

		assertContains(completions, "name", "$file. должен содержать поле name");
		assertContains(completions, "size", "$file. должен содержать поле size");
		assertContains(completions, "text", "$file. должен содержать поле text");
		assertContains(completions, "mode", "$file. должен содержать поле mode");
		assertContains(completions, "content-type", "$file. должен содержать поле content-type");
		assertFalse("$file. после обычного file::load не должен содержать поле adate: " + completions, completions.contains("adate"));
		assertFalse("$file. после обычного file::load не должен содержать поле stderr: " + completions, completions.contains("stderr"));
		assertFalse("$file. после обычного file::load не должен содержать поле status: " + completions, completions.contains("status"));
		assertFalse("$file. после обычного file::load не должен содержать поле cookies: " + completions, completions.contains("cookies"));
	}

	public void testFileVariableCaretDot_showsDocumentedMethods() {
		List<String> completions = getCompletions(
				parser152RealFileBase("^file.<caret>")
		);

		assertContains(completions, "base64", "^file. должен содержать метод base64");
		assertContains(completions, "crc32", "^file. должен содержать метод crc32");
		assertContains(completions, "md5", "^file. должен содержать метод md5");
		assertContains(completions, "save", "^file. должен содержать метод save");
		assertContains(completions, "sql-string", "^file. должен содержать метод sql-string");
	}

	public void testFileDoesNotExposeColonProperties() {
		List<String> dollarCompletions = getCompletions(parser152RealFileBase("$file:<caret>"));
		assertFalse("$file: не должен показывать instance fields: " + dollarCompletions,
				dollarCompletions.contains("name"));

		List<String> caretCompletions = getCompletions(parser152RealFileBase("^file:<caret>"));
		assertFalse("^file: не должен показывать instance fields: " + caretCompletions,
				caretCompletions.contains("name"));
	}

	public void testInternalFileSubtypesDoNotAppearAsBuiltinClassAfterCaret() {
		List<String> completions = getPresentableTexts(parser152RealFileBase("^file-<caret>"));
		assertFalse("^file- не должен показывать внутренний subtype file-exec: " + completions,
				completions.contains("file-exec:"));
		assertFalse("^file- не должен показывать внутренний subtype file-http: " + completions,
				completions.contains("file-http:"));
		assertFalse("^file- не должен показывать внутренний subtype file-local: " + completions,
				completions.contains("file-local:"));
		assertFalse("^file- не должен показывать внутренний subtype file-sql: " + completions,
				completions.contains("file-sql:"));
	}

	public void testFileVariableTypeInferredFromExec() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/264.html\n" +
						"$file[^file::exec[script.pl]]\n" +
						"$file.<caret>"
		);

		assertContains(completions, "stderr", "$file. после file::exec должен содержать stderr");
		assertContains(completions, "status", "$file. после file::exec должен содержать status");
		assertFalse("$file. после file::exec не должен содержать adate: " + completions, completions.contains("adate"));
	}

	public void testFileVariableTypeInferredFromSqlConstructor() {
		List<String> completions = getCompletions(
				"@main[]\n" +
						"# на основе parser3/tests/388-sql.html\n" +
						"$file[^file::sql{select bytes, name, content_type from files}]\n" +
						"$file.<caret>"
		);

		assertContains(completions, "name", "$file. после file::sql должен содержать базовые поля file");
		assertContains(completions, "content-type", "$file. после file::sql должен содержать базовые поля file");
		assertFalse("$file. после file::sql не должен содержать stderr: " + completions, completions.contains("stderr"));
		assertFalse("$file. после file::sql не должен содержать adate: " + completions, completions.contains("adate"));
	}

	public void testFileVariableTypeInferredFromCurlLoad() {
		List<String> completions = getCompletions(
				parser223RealCurlFileBase("$file.<caret>")
		);

		assertContains(completions, "status", "$file. после curl:load должен содержать поля file");
		assertContains(completions, "cookies", "$file. после curl:load должен содержать cookies");
		assertContains(completions, "tables", "$file. после curl:load должен содержать tables");
		assertFalse("$file. после curl:load не должен содержать adate: " + completions, completions.contains("adate"));
		assertFalse("$file. после curl:load не должен содержать stderr: " + completions, completions.contains("stderr"));
	}

	public void testFileVariableTypeInferredFromStat() {
		List<String> completions = getCompletions(
				parser152RealFileBase("$stat.<caret>")
		);

		assertContains(completions, "cdate", "$file. после file::stat должен содержать cdate");
		assertContains(completions, "mdate", "$file. после file::stat должен содержать mdate");
		assertContains(completions, "adate", "$file. после file::stat должен содержать adate");
		assertFalse("$file. после file::stat не должен содержать stderr: " + completions, completions.contains("stderr"));
		assertFalse("$file. после file::stat не должен содержать cookies: " + completions, completions.contains("cookies"));
	}

	public void testFileFindResultInferredAsString() {
		List<String> completions = getCompletions(
				parser270RealFileFindBase("^path.<caret>")
		);

		assertContains(completions, "split", "^path. после file:find должен видеть string-методы");
		assertContains(completions, "match", "^path. после file:find должен видеть string-методы");
		assertFalse("^path. после file:find не должен видеть методы file: " + completions,
				completions.contains("sql-string"));
	}

	public void testXdocConstructorCompletion_showsCreate() {
		List<String> completions = getCompletions(parser152RealXdocBase("^xdoc::<caret>"));
		assertContains(completions, "create", "^xdoc:: должен содержать create");
	}

	public void testXdocVariableTypeInferredFromEmptyCreate() {
		List<String> completions = getCompletions(
				parser152RealXdocBase("^xDoc.<caret>")
		);

		assertContains(completions, "string", "^doc. должен видеть методы xdoc");
		assertContains(completions, "file", "^doc. должен видеть методы xdoc");
		assertContains(completions, "transform", "^doc. должен видеть методы xdoc");
	}

	public void testXdocVariableTypeInferredFromFileCreate() {
		List<String> completions = getCompletions(
				parser097RealHttpGlobalsBase("$doc[^xdoc::create[$file]]\n^doc.<caret>")
		);

		assertContains(completions, "string", "^doc. после xdoc::create[file] должен видеть методы xdoc");
		assertContains(completions, "save", "^doc. после xdoc::create[file] должен видеть методы xdoc");
	}

	public void testXdocVariableDot_showsSearchNamespacesProperty() {
		List<String> completions = getCompletions(
				parser152RealXdocBase("$xDoc.<caret>")
		);

		assertContains(completions, "search-namespaces", "$doc. должен содержать поле search-namespaces");
	}

	public void testXdocCaretDot_showsXnodeInheritedMethods() {
		List<String> completions = getCompletions(
				parser152RealXdocBase("^xDoc.<caret>")
		);

		assertContains(completions, "select", "^doc. должен видеть унаследованный метод xnode select");
		assertContains(completions, "selectString", "^doc. должен видеть унаследованный метод xnode selectString");
		assertContains(completions, "selectSingle", "^doc. должен видеть унаследованный метод xnode selectSingle");
	}

	public void testXdocCaretDot_showsDocumentDomMethods() {
		List<String> completions = getCompletions(
				parser152RealXdocBase("^xDoc.<caret>")
		);

		assertContains(completions, "createElement", "^doc. должен видеть DOM-метод createElement");
		assertContains(completions, "createDocumentFragment", "^doc. должен видеть DOM-метод createDocumentFragment");
		assertContains(completions, "createTextNode", "^doc. должен видеть DOM-метод createTextNode");
		assertContains(completions, "importNode", "^doc. должен видеть DOM-метод importNode");
		assertContains(completions, "getElementById", "^doc. должен видеть DOM-метод getElementById");
	}

	public void testXnodeVariableDot_showsDomFields() {
		List<String> completions = getCompletions(
				parser152RealXdocBase("$xNode.<caret>")
		);

		assertContains(completions, "nodeName", "$node. должен содержать nodeName");
		assertContains(completions, "nodeType", "$node. должен содержать nodeType");
		assertContains(completions, "childNodes", "$node. должен содержать childNodes");
		assertContains(completions, "ownerDocument", "$node. должен содержать ownerDocument");
	}

	public void testXnodeCaretDot_showsDomMethods() {
		List<String> completions = getCompletions(
				parser152RealXdocBase("^xNode.<caret>")
		);

		assertContains(completions, "appendChild", "^node. должен содержать appendChild");
		assertContains(completions, "cloneNode", "^node. должен содержать cloneNode");
		assertContains(completions, "getAttribute", "^node. должен содержать getAttribute");
		assertContains(completions, "hasAttributes", "^node. должен содержать hasAttributes");
	}

	public void testXnodeColon_showsDomConstants() {
		List<String> completions = getCompletions(parser152RealXdocBase("$xnode:<caret>"));
		assertContains(completions, "ELEMENT_NODE", "$xnode: должен содержать ELEMENT_NODE");
		assertContains(completions, "DOCUMENT_NODE", "$xnode: должен содержать DOCUMENT_NODE");
	}

	public void testXdocColon_showsDomConstants() {
		List<String> completions = getCompletions(parser152RealXdocBase("$xdoc:<caret>"));
		assertContains(completions, "ELEMENT_NODE", "$xdoc: должен содержать ELEMENT_NODE");
		assertContains(completions, "DOCUMENT_NODE", "$xdoc: должен содержать DOCUMENT_NODE");
	}

	// =============================================================================
	// РАЗДЕЛ 1: $builtinClass: — автокомплит свойств встроенных классов
	// =============================================================================

	/**
	 * $form:<caret> — показывает свойства form: fields, tables, files, imap
	 */
	public void testDollarFormColon_showsProperties() {
		List<String> completions = getCompletions(parser102RealFormBase("$form:<caret>"));
		assertContains(completions, "fields", "$form: должен содержать fields");
		assertContains(completions, "tables", "$form: должен содержать tables");
		assertContains(completions, "files", "$form: должен содержать files");
		assertContains(completions, "imap", "$form: должен содержать imap");
	}

	public void testDollarFormColon_addsLocalPropertiesFromCurrentFile() {
		List<String> completions = getCompletions(
				parser102RealFormBase("$str[$form:custom_login]\n" +
						"^if(^form:custom_flag.bool(false)){}\n" +
						"$form:cu<caret>")
		);
		assertContains(completions, "custom_login", "$form: должен содержать локальное поле custom_login");
		assertContains(completions, "custom_flag", "$form: должен содержать локальное поле custom_flag из ^form:");
	}

	public void testDollarFormColon_localPropertyHasStringTypeText() {
		LookupElement customLogin = findLookupElement(
				parser102RealFormBase("$str[$form:custom_login]\n" +
						"$form:cu<caret>"),
				"custom_login"
		);
		LookupElementPresentation presentation = LookupElementPresentation.renderElement(customLogin);
		assertEquals("string", presentation.getTypeText());
	}

	public void testDollarFormColon_doesNotLeakCurrentIncompleteProperty() {
		List<String> completions = getCompletions(
				"@update_before_start[]\n" +
						"# на основе parser3/tests/102.html\n" +
						"^if(def $form:da<caret>)\n"
		);
		assertFalse("Текущий недописанный $form:da не должен попадать в автокомплит сам на себя: " + completions,
				completions.contains("da"));
	}

	public void testDollarFormColon_addsLocalBracketPropertyFromCurrentFile() {
		List<String> completions = getCompletions(
				parser102RealFormBase("$str[$form:[action.x]]\n" +
						"$form:<caret>")
		);
		assertContains(completions, "[action.x]", "$form: должен содержать локальный bracket-ключ");
	}

	/**
	 * $env:<caret> — показывает свойства env
	 */
	public void testDollarEnvColon_showsProperties() {
		List<String> completions = getCompletions(parser097RealHttpGlobalsBase("$env:<caret>"));
		assertContainsAny(completions, "PARSER_VERSION", "$env: должен содержать PARSER_VERSION");
		assertContainsAny(completions, "REMOTE_ADDR", "$env: должен содержать REMOTE_ADDR");
	}

	/**
	 * $request:<caret> — показывает свойства request
	 */
	public void testDollarRequestColon_showsProperties() {
		List<String> completions = getCompletions(parser097RealHttpGlobalsBase("$request:<caret>"));
		assertContains(completions, "uri", "$request: должен содержать uri");
		assertContains(completions, "query", "$request: должен содержать query");
		assertContains(completions, "body", "$request: должен содержать body");
		assertContains(completions, "charset", "$request: должен содержать charset");
	}

	/**
	 * $response:<caret> — показывает свойства response, включая присваиваемые
	 */
	public void testDollarResponseColon_showsProperties() {
		List<String> completions = getCompletions(parser075RealResponseBase("$response:<caret>"));
		assertContainsAny(completions, "body", "$response: должен содержать body");
		assertContainsAny(completions, "headers", "$response: должен содержать headers");
		assertContainsAny(completions, "charset", "$response: должен содержать charset");
		assertContainsAny(completions, "status", "$response: должен содержать status");
	}

	/**
	 * $status:<caret> — показывает свойства status
	 */
	public void testDollarStatusColon_showsProperties() {
		List<String> completions = getCompletions(parser208RealStatusBase("$status:<caret>"));
		assertContains(completions, "pid", "$status: должен содержать pid");
		assertContains(completions, "tid", "$status: должен содержать tid");
		assertContains(completions, "memory", "$status: должен содержать memory");
		assertContains(completions, "rusage", "$status: должен содержать rusage");
	}

	/**
	 * $cookie:<caret> — показывает свойства cookie
	 */
	public void testDollarCookieColon_showsProperties() {
		List<String> completions = getCompletions(parser005RealCookieBase("$cookie:<caret>"));
		assertContains(completions, "fields", "$cookie: должен содержать fields");
	}

	// =============================================================================
	// РАЗДЕЛ 2: $fo — встроенные классы в normal контексте
	// =============================================================================

	/**
	 * $fo<caret> — предлагает form:fields, form:tables и т.д.
	 */
	public void testDollarFo_showsFormProperties() {
		List<String> completions = getCompletions(parser102RealFormBase("$fo<caret>"));
		assertContainsAny(completions, "form:", "$fo должен содержать form:");
	}

	/**
	 * $en<caret> — предлагает env:*
	 */
	public void testDollarEn_showsEnvProperties() {
		List<String> completions = getCompletions(parser097RealHttpGlobalsBase("$en<caret>"));
		assertContainsAny(completions, "env:", "$en должен содержать env:");
	}

	// =============================================================================
	// РАЗДЕЛ 3: Case-insensitive автокомплит
	// =============================================================================

	/**
	 * $env:remote<caret> — регистронезависимый, показывает REMOTE_ADDR, REMOTE_PORT
	 */
	public void testCaseInsensitive_envRemote() {
		List<String> completions = getCompletions(parser097RealHttpGlobalsBase("$env:remote<caret>"));
		assertContainsAny(completions, "REMOTE_ADDR",
				"$env:remote должен показать REMOTE_ADDR (case-insensitive)");
	}

	/**
	 * $env:parser<caret> — регистронезависимый, показывает PARSER_VERSION
	 */
	public void testCaseInsensitive_envParser() {
		List<String> completions = getCompletions(parser097RealHttpGlobalsBase("$env:parser<caret>"));
		assertContainsAny(completions, "PARSER_VERSION",
				"$env:parser должен показать PARSER_VERSION (case-insensitive)");
	}

	// =============================================================================
	// РАЗДЕЛ 4: ^builtinClass: — автокомплит свойств в методном контексте
	// =============================================================================

	/**
	 * ^form:<caret> — показывает свойства form с точкой
	 */
	public void testCaretFormColon_showsProperties() {
		List<String> completions = getCompletions(parser102RealFormBase("^form:<caret>"));
		assertContainsAny(completions, "fields", "^form: должен содержать fields");
		assertContainsAny(completions, "tables", "^form: должен содержать tables");
	}

	public void testCaretFormColon_addsLocalPropertiesFromCurrentFile() {
		List<String> completions = getCompletions(
				parser102RealFormBase("$str[$form:custom_login]\n" +
						"^if(^form:custom_flag.bool(false)){}\n" +
						"^form:cu<caret>")
		);
		assertContainsAny(completions, "custom_login", "^form: должен содержать локальное поле custom_login");
		assertContainsAny(completions, "custom_flag", "^form: должен содержать локальное поле custom_flag");
	}

	public void testCaretFormLocalPropertyDot_usesStringMethodsOnly() {
		List<String> completions = getCompletions(
				parser102RealFormBase("$str[$form:custom_login]\n" +
						"^form:custom_login.<caret>")
		);
		assertContainsAny(completions, "split", "^form:custom_login. должен содержать string-методы");
		assertNotContainsAny(completions, "menu", "^form:custom_login. не должен содержать table-метод menu");
	}

	/**
	 * ^fo<caret> — предлагает form: среди методов
	 */
	public void testCaretFo_showsFormClass() {
		List<String> completions = getCompletions(parser102RealFormBase("^fo<caret>"));
		assertContainsAny(completions, "form:", "^fo должен содержать form:");
	}

	// =============================================================================
	// РАЗДЕЛ 5: ^form:fields. — автокомплит методов по типу свойства
	// =============================================================================

	/**
	 * ^form:fields.<caret> — fields это hash, показываем методы хеша
	 */
	public void testCaretFormFieldsDot_showsHashMethods() {
		List<String> completions = getCompletions(parser102RealFormBase("^form:fields.<caret>"));
		// hash должен иметь встроенные методы
		assertFalse("^form:fields. должен показать методы хеша", completions.isEmpty());
	}

	public void testCaretFormIdsDot_showsStringMethods() {
		List<String> completions = getCompletions(parser102RealFormBase("^form:ids.<caret>"));
		assertContainsAny(completions, "split", "^form:ids. должен содержать split");
	}

	public void testCaretEnvParserVersionDot_showsStringMethods() {
		List<String> completions = getCompletions(parser097RealHttpGlobalsBase("^env:PARSER_VERSION.<caret>"));
		assertContainsAny(completions, "split", "^env:PARSER_VERSION. должен содержать string-метод split");
		assertContainsAny(completions, "trim", "^env:PARSER_VERSION. должен содержать string-метод trim");
	}

	public void testCaretEnvUnknownPropertyDot_showsUnknownTypeMethods() {
		List<String> completions = getCompletions(parser097RealHttpGlobalsBase("^env:UNKNOWN_PROPERTY.<caret>"));
		assertContainsAny(completions, "split", "^env:UNKNOWN_PROPERTY. должен содержать методы неизвестного типа");
		assertContainsAny(completions, "append", "^env:UNKNOWN_PROPERTY. должен содержать методы разных встроенных типов");
	}

	public void testEnvParserVersion_hasStringType() {
		LookupElement element = findLookupElement(parser097RealHttpGlobalsBase("$env:parser<caret>"), "PARSER_VERSION");
		LookupElementPresentation presentation = LookupElementPresentation.renderElement(element);
		assertEquals("$env:PARSER_VERSION должен иметь тип string", "string", presentation.getTypeText());
	}

	/**
	 * $form:fields.<caret> — в $ контексте не показываем методы hash,
	 * но явный Ctrl+Space всё равно показывает пользовательские шаблоны.
	 */
	public void testDollarFormFieldsDot_noCompletions() {
		List<String> completions = getCompletions(parser102RealFormBase("$form:fields.<caret>"));
		assertContains(completions, "curl:load[]",
				"Ctrl+Space должен показывать пользовательские шаблоны");
		assertNotContainsAny(completions, "contains",
				"$form:fields. не должен показывать методы");
	}

	public void testMethodResultInference_resultDateMethods() {
		List<String> completions = getCompletions(
				parser274RealDateBase("@makeDate[]\n" +
						"$result[^date::now[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/274.html: минимальный кейс результата метода\n" +
						"$res[^makeDate[]]\n" +
						"^res.<caret>")
		);

		assertContains(completions, "iso-string", "^res. должен видеть методы date из $result");
		assertContains(completions, "sql-string", "^res. должен видеть sql-string из date");
	}

	public void testMethodResultInference_returnAliasDateMethods() {
		List<String> completions = getCompletions(
				parser274RealDateBase("@makeDate[]\n" +
						"$tmp[^date::today[]]\n" +
						"^return[$tmp]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/274.html: минимальный кейс ^return[$tmp]\n" +
						"$res[^makeDate[]]\n" +
						"^res.<caret>")
		);

		assertContains(completions, "iso-string", "^return[$tmp] должен сохранять тип date");
		assertContains(completions, "last-day", "^return[$tmp] должен отдавать методы date");
	}

	public void testMethodResultInference_bodyOverridesDocType() {
		List<String> completions = getCompletions(
				parser274RealDateBase("####################\n" +
						"# $result(table)\n" +
						"####################\n" +
						"@makeValue[]\n" +
						"^return[^date::now[]]\n" +
						"\n" +
						"@main[]\n" +
						"# на основе parser3/tests/274.html: минимальный кейс приоритета inferred-типа\n" +
						"$res[^makeValue[]]\n" +
						"^res.<caret>")
		);

		assertContains(completions, "sql-string", "inferred type из тела должен быть важнее doc-type");
		assertFalse("table-метод не должен побеждать inferred date: " + completions, completions.contains("menu"));
	}

	// =============================================================================
	// РАЗДЕЛ 6: assignSuffix — вставка скобок
	// =============================================================================

	// assignSuffix тесты пока пропущены — нужна доработка lookup для body

	/**
	 * $response:headers выбор из автокомплита НЕ вставляет скобки (только чтение)
	 */
	public void testNoAssignSuffix_responseHeaders() {
		createParser3File("test_no_assign.p", parser075RealResponseBase("$response:<caret>"));
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты", elements);

		LookupElement headersElement = null;
		for (LookupElement e : elements) {
			if (e.getLookupString().equals("headers")) {
				headersElement = e;
				break;
			}
		}
		assertNotNull("Должен быть элемент headers", headersElement);

		myFixture.getLookup().setCurrentItem(headersElement);
		myFixture.finishLookup('\n');

		String text = myFixture.getEditor().getDocument().getText();
		// headers без скобок
		assertFalse("headers НЕ должен иметь скобок: " + text, text.contains("headers["));
	}

	// =============================================================================
	// РАЗДЕЛ 7: Негативные тесты
	// =============================================================================

	/**
	 * $form:fields. — после точки НЕ показывать свойства form (fields, tables...)
	 * а показывать методы/свойства hash
	 */
	public void testDollarFormFieldsDot_noFormProps() {
		List<String> completions = getCompletions(parser102RealFormBase("$form:fields.<caret>"));
		assertNotContainsAny(completions, "tables",
				"$form:fields. НЕ должен содержать tables (это свойства form, а не hash)");
		assertNotContainsAny(completions, "imap",
				"$form:fields. НЕ должен содержать imap");
	}

	/**
	 * $nonexistent:<caret> — несуществующий класс, нет свойств встроенных классов
	 */
	public void testDollarNonexistentColon_noBuiltinProps() {
		List<String> completions = getCompletions(parser097RealHttpGlobalsBase("$nonexistent:<caret>"));
		assertNotContainsAny(completions, "fields",
				"$nonexistent: не должен показывать fields");
		assertNotContainsAny(completions, "PARSER_VERSION",
				"$nonexistent: не должен показывать PARSER_VERSION");
	}
}
