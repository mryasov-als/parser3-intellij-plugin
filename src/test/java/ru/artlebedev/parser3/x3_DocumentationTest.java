package ru.artlebedev.parser3;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import ru.artlebedev.parser3.lang.Parser3DocumentationProvider;

/**
 * Тесты для документации Parser3.
 * Проверяет что при наведении на вызов метода реально генерируется документация
 * через полный pipeline: PSI → getCustomDocumentationElement → generateDoc → HTML.
 */
public class x3_DocumentationTest extends Parser3TestCase {

	private final Parser3DocumentationProvider docProvider = new Parser3DocumentationProvider();

	// === Системные методы ===

	public void testSystemMethod_if() {
		assertDocContains("^i<caret>f(true){ok}", "if");
	}

	public void testSystemMethod_eval() {
		assertDocContains("^ev<caret>al(1+2)", "eval");
	}

	public void testSystemMethod_for() {
		assertDocContains("^fo<caret>r[i](1;10){$i}", "for");
	}

	public void testSystemMethod_while() {
		assertDocContains("^whi<caret>le(true){break}", "while");
	}

	public void testSystemMethod_switch() {
		assertDocContains("^swit<caret>ch[$x]{case1{}}", "switch");
	}

	public void testSystemMethod_break() {
		assertDocContains("^bre<caret>ak[]", "break");
	}

	public void testSystemMethod_continue() {
		assertDocContains("^conti<caret>nue[]", "continue");
	}

	public void testSystemMethod_throw() {
		assertDocContains("^thr<caret>ow[error]", "throw");
	}

	public void testSystemMethod_try() {
		assertDocContains("^tr<caret>y{}{}", "try");
	}

	public void testSystemMethod_return() {
		assertDocContains("^retu<caret>rn[]", "return");
	}

	// === Конструкторы ===

	public void testConstructor_hashCreate() {
		assertDocContains("^hash::cre<caret>ate[]", "create");
	}

	public void testConstructor_hashCreate_onClass() {
		assertDocContains("^ha<caret>sh::create[]", "hash");
	}

	public void testConstructor_tableCreate() {
		assertDocContains("^table::cre<caret>ate{col}", "create");
	}

	public void testConstructor_fileLoad() {
		assertDocContains("^file::lo<caret>ad[path]", "load");
	}

	public void testConstructor_dateNow() {
		assertDocContains("^date::no<caret>w[]", "now");
	}

	public void testConstructor_stringSql() {
		assertDocContains("^string:sq<caret>l{SELECT 1}", "sql");
	}

	// === Статические методы ===

	public void testStaticMethod_fileDelete() {
		assertDocContains("^file:dele<caret>te[path]", "delete");
	}

	public void testStaticMethod_mathAbs() {
		assertDocContains("^math:ab<caret>s(-5)", "abs");
	}

	public void testStaticMethod_fileList() {
		assertDocContains("^file:li<caret>st[dir]", "list");
	}

	// === Вложенные вызовы ===

	public void testNestedCalls_if() {
		assertDocContains("^i<caret>f(^data.count[] > 0){^data.foreach[k;v]{}}", "if");
	}

	// === Нет документации вне вызова ===

	public void testNoDoc_beforeCaret() {
		assertNoDoc("<caret>^if[]");
	}

	public void testNoDoc_insideLiteral() {
		assertNoDoc("^if(<caret>true)");
	}

	public void testNoDoc_plainText() {
		assertNoDoc("hello <caret>world");
	}

	// === Комплексная строка ===

	public void testComplexLine_if() {
		assertDocContains("^i<caret>f(^data.mid(^data.pos[str]){$x[^hash::create[]]})", "if");
	}

	public void testComplexLine_hashCreate() {
		assertDocContains("^if(^data.mid(^data.pos[str]){$x[^hash::cre<caret>ate[]]})", "create");
	}

	// === Пользовательские методы ===

	public void testUserMethod_simple() {
		createParser3FileInDir("lib.p",
				"########################################\n" +
						"# Обрабатывает данные\n" +
						"# $param1 — входные данные\n" +
						"########################################\n" +
						"@process[param1]\n" +
						"$result[$param1]\n"
		);
		assertDocContains("^use[lib.p]\n^proc<caret>ess[data]", "process");
	}

	public void testUserMethodDoc_useBelowCursorDoesNotExposeMethod() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("future_doc_lib.p",
				"########################################\n" +
						"# Документация будущего метода\n" +
						"########################################\n" +
						"@futureDoc[]\n"
		);

		assertNoDoc(
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^future<caret>Doc[]\n" +
						"^use[future_doc_lib.p]\n"
		);
	}

	public void testUserMethodDoc_useAboveCursorExposesMethod() {
		setMethodCompletionMode(ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);
		createParser3FileInDir("past_doc_lib.p",
				"########################################\n" +
						"# Документация прошлого метода\n" +
						"########################################\n" +
						"@pastDoc[]\n"
		);

		assertDocContains(
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^use[past_doc_lib.p]\n" +
						"^past<caret>Doc[]\n",
				"прошлого"
		);
	}

	// === Глубокая вложенность ===

	public void testDeepNested_left() {
		assertDocContains("^if(^data.left(^txt.pos(^text.le<caret>ft(5))))", "left");
	}

	public void testDeepNested_mid() {
		assertDocContains("$data.x.y[^process[(^txt.m<caret>id()]]", "mid");
	}

	// === Документация метода класса через переменную ===

	public void testUserMethod_varMethodDoc() {
		assertDocContains(
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$var[^User::create[]]\n" +
						"$x[^var.met<caret>hod[]]\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"User\n" +
						"\n" +
						"########################################\n" +
						"# создание пользователя\n" +
						"########################################\n" +
						"@create[]\n" +
						"\n" +
						"########################################\n" +
						"# другой метод\n" +
						"########################################\n" +
						"@method[]\n",
				"другой"
		);
	}

	// === $class:field свойства встроенных классов ===

	public void testUserMethod_selfMethodShadowsBuiltinDoc() {
		assertDocContains(
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^added.inc(^self.ad<caret>d[])\n" +
						"\n" +
						"########################################\n" +
						"# добавление\n" +
						"########################################\n" +
						"@add[]\n",
				"добавление"
		);
	}

	public void testBuiltinProp_envSimple() {
		assertDocContains("$env:HTTP_USER_AG<caret>ENT", "HTTP_USER_AGENT");
	}

	public void testBuiltinMethod_dataAddDoesNotUseLocalMainMethodDoc() {
		assertDocContains(
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$data[^hash::create[]]\n" +
						"^data.ad<caret>d[]\n" +
						"\n" +
						"########################################\n" +
						"# добавление\n" +
						"########################################\n" +
						"@add[]\n",
				"Добавление элементов из другого массива или хеша"
		);
	}

	public void testBuiltinMethod_tableSaveUsesVariableTypeDoc() {
		String doc = getDocumentation(
				"@code[]\n" +
						"$list[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\t*\n" +
						"\tFROM\n" +
						"\t\tsearch_query\n" +
						"}]\n" +
						"^list.sa<caret>ve[]\n"
		);
		assertNotNull("Документация не найдена для table.save", doc);
		assertTrue(
				"Для ^list.save[] с типом table должна открываться документация таблицы. Получено: " + doc,
				doc.contains("<strong>Сохранение таблицы")
		);
		assertFalse(
				"Для ^list.save[] с типом table не должна открываться документация file.save. Получено: " + doc,
				doc.contains("<strong>Сохранение файла")
		);
	}

	public void testBuiltinProp_envInIfWithUse() {
		// Баг: ^use[file.p] содержит точку, DOT_CALL_PATTERN матчил "file.p"
		// и возвращал раньше чем DOLLAR_PROP_PATTERN мог найти $env:HTTP_USER_AGENT
		assertDocContains("^if(!def $env:HTTP_USER_AG<caret>ENT){^use[file.p]}", "HTTP_USER_AGENT");
	}

	public void testBuiltinProp_envInIfEmpty() {
		assertDocContains("^if(!def $env:HTTP_USER_AG<caret>ENT){}", "HTTP_USER_AGENT");
	}

	public void testBuiltinProp_envInIfWithMethod() {
		assertDocContains("^if(!def $env:HTTP_USER_AG<caret>ENT){^method[]}", "HTTP_USER_AGENT");
	}

	// === Вспомогательные методы ===

	/**
	 * Проверяет что документация содержит ожидаемую подстроку.
	 * Использует полный pipeline: PSI → DocumentationProvider → HTML.
	 */
	private void assertDocContains(String code, String expectedSubstring) {
		String doc = getDocumentation(code);
		assertNotNull("Документация не найдена для кода: " + code, doc);
		assertTrue(
				"Документация не содержит '" + expectedSubstring + "'. Получено: " + doc,
				doc.contains(expectedSubstring)
		);
	}

	/**
	 * Проверяет что документация НЕ генерируется.
	 */
	private void assertNoDoc(String code) {
		String doc = getDocumentation(code);
		// null или пустая строка — документации нет
		assertTrue(
				"Ожидалось отсутствие документации для: " + code + ", но получено: " + doc,
				doc == null || doc.isEmpty()
		);
	}

	/**
	 * Получает документацию через полный pipeline DocumentationProvider.
	 */
	private String getDocumentation(String codeWithCaret) {
		PsiFile file = createParser3File("test.p", codeWithCaret);
		int offset = myFixture.getCaretOffset();

		PsiElement elementAtCaret = file.findElementAt(offset);
		if (elementAtCaret == null) {
			return null;
		}

		// Шаг 1: getCustomDocumentationElement (как при наведении мышки)
		PsiElement docElement = docProvider.getCustomDocumentationElement(
				myFixture.getEditor(), file, elementAtCaret, offset
		);

		if (docElement == null) {
			return null;
		}

		// Шаг 2: generateDoc (генерация HTML)
		return docProvider.generateDoc(docElement, elementAtCaret);
	}
}
