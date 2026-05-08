package ru.artlebedev.parser3;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import ru.artlebedev.parser3.settings.Parser3SqlInjectionsService;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Тесты колонок таблицы: индексация ^table::create{col1\tcol2},
 * автокомплит ^list.col и $list.col, nameless таблицы.
 */
public class x10_var_TableTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		setSqlInjections();

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			setSqlInjections();
		} finally {
			super.tearDown();
		}
	}

	// ==================== HELPERS ====================

	/**
	 * Выполняет автокомплит и возвращает список lookupString'ов.
	 */
	private List<String> doComplete(String path, String content) {
		createParser3FileInDir(path, content);
		VirtualFile vf = myFixture.findFileInTempDir(path);
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) return List.of();
		return Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
	}

	private void setSqlInjections(String... prefixes) {
		Parser3SqlInjectionsService service = ApplicationManager.getApplication().getService(Parser3SqlInjectionsService.class);
		assertNotNull(service);
		service.getPrefixes().clear();
		service.getPrefixes().addAll(Arrays.asList(prefixes));
	}

	private String parser312RealTableCopyBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/312.html\n" +
				"$t[^table::create{c1\tc2\tc3}]\n" +
				"$copy[^table::create[$t]]\n" +
				"$t.c4[v]\n" +
				completionLine;
	}

	private String parser312RealTableBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/312.html\n" +
				"$t[^table::create{c1\tc2\tc3}]\n" +
				completionLine;
	}

	private String parser312RealNamelessTableBase(String completionLine) {
		return "@main[]\n" +
				"# на основе parser3/tests/312.html\n" +
				"$t[^table::create[nameless]{}]\n" +
				"^t.insert{v0}\n" +
				completionLine;
	}

	private String parser388RealTableSqlBase(String body) {
		return "@main[]\n" +
				"# на основе parser3/tests/388-sql.html\n" +
				body;
	}

	private String parser429RealTableSqlBase(String body) {
		return "@main[]\n" +
				"# на основе parser3/tests/429-sql.html\n" +
				body;
	}

	private String parser158RealTableLoadBase(String body) {
		return "@main[]\n" +
				"# на основе parser3/tests/158.html\n" +
				"$sFileNamed[158.named]\n" +
				"$hOption[$.encloser[']]\n" +
				"$tOrigin[^table::create{name\tage\n" +
				"Vasya\t12\n" +
				"O'Neil\t33\n" +
				"}]\n" +
				"^tOrigin.save[$sFileNamed;$hOption]\n" +
				body;
	}

	private String parser407RealTableSelectBase(String body) {
		return "@main[]\n" +
				"# на основе parser3/tests/407.html\n" +
				"$log[^table::create{cpu\turl\n" +
				"0.2200\t/items/list/\n" +
				"0.1600\t/items/detail/\n" +
				"0.0200\t/feed/rss.html\n" +
				"0.2233\t/items/archive/.../\n" +
				"0.0000\t/redirect.html\n" +
				"0.1500\t/accounts/software/\n" +
				"0.1700\t/locations/main/\n" +
				"0.0100\t/contacts/\n" +
				"0.1367\t/services/status/.../\n" +
				"0.1833\t/items/cards/.../\n" +
				"0.0033\t/\n" +
				"0.1533\t/company/news/\n" +
				"0.0100\t/items/\n" +
				"0.0033\t/locations/branch/\n" +
				"0.1533\t/locations/other/\n" +
				"}]\n" +
				body;
	}

	private String parser169RealStringSplitBase(String body) {
		return "@main[][sText;tVertical]\n" +
				"# на основе parser3/tests/169.html\n" +
				"$sText[/a/b/c/]\n" +
				"$sChar[/]\n" +
				"$sColumnName[zigi]\n" +
				body;
	}

	public void testCustomSqlInjection_notConfigured_doesNotInferTableColumns() {
		List<String> completions = doComplete("test_tbl_custom_sql_off.p",
				parser388RealTableSqlBase(
						"$data[^self.oSql.table{select pet, food from pets}]\n" +
								"$data.<caret>"
				)
		);

		assertFalse("$data. НЕ должен содержать pet без SQL injection prefix: " + completions,
				completions.contains("pet"));
		assertFalse("$data. НЕ должен содержать food без SQL injection prefix: " + completions,
				completions.contains("food"));
	}

	public void testCustomSqlInjection_configured_infersTableColumns() {
		setSqlInjections("^self.oSql.table");

		List<String> completions = doComplete("test_tbl_custom_sql_on.p",
				parser388RealTableSqlBase(
						"$data[^self.oSql.table{select pet, food from pets}]\n" +
								"$data.<caret>"
				)
		);

		assertTrue("$data. должен содержать pet при включённом SQL injection prefix из Parser3 388-sql: " + completions,
				completions.contains("pet"));
		assertTrue("$data. должен содержать food при включённом SQL injection prefix из Parser3 388-sql: " + completions,
				completions.contains("food"));
	}

	// =============================================================================
	// РАЗДЕЛ 1: Индексация колонок — ^table::create{col1\tcol2}
	// =============================================================================

	/**
	 * $t[^table::create{c1\tc2\tc3}] — автокомплит ^t. содержит c1 и c3.
	 */
	public void testTableColumns_caretDot_basic() {
		List<String> completions = doComplete("test_tbl1.p",
				parser312RealTableBase("^t.<caret>")
		);

		assertTrue("Реальный Parser3 312: ^t. должен содержать 'c1.', есть: " + completions,
				completions.contains("c1."));
		assertTrue("Реальный Parser3 312: ^t. должен содержать 'c3.', есть: " + completions,
				completions.contains("c3."));
	}

	/**
	 * $t[^table::create{c1\tc2\tc3}] — автокомплит $t. содержит c1 и c3.
	 */
	public void testTableColumns_dollarDot_basic() {
		List<String> completions = doComplete("test_tbl2.p",
				parser312RealTableBase("$t.<caret>")
		);

		assertTrue("Реальный Parser3 312: $t. должен содержать 'c1', есть: " + completions,
				completions.contains("c1"));
		assertTrue("Реальный Parser3 312: $t. должен содержать 'c3', есть: " + completions,
				completions.contains("c3"));
	}

	/**
	 * $t[^table::create{c1\tc2\tc3}] — также должны быть встроенные методы table.
	 */
	public void testTableColumns_caretDot_builtinMethodsToo() {
		List<String> completions = doComplete("test_tbl3.p",
				parser312RealTableBase("^t.<caret>")
		);

		assertTrue("Реальный Parser3 312: ^t. должен содержать 'c1.' (колонка), есть: " + completions,
				completions.contains("c1."));
		// Встроенные методы table тоже должны быть
		assertTrue("Реальный Parser3 312: ^t. должен содержать 'menu' (встроенный метод table), есть: " + completions,
				completions.contains("menu"));
	}

	// =============================================================================
	// РАЗДЕЛ 2: Nameless таблицы — колонок нет
	// =============================================================================

	/**
	 * $t[^table::create[nameless]{}] — НЕТ колонок, только встроенные методы.
	 */
	public void testTableColumns_nameless_noColumns() {
		List<String> completions = doComplete("test_tbl4.p",
				parser312RealNamelessTableBase("^t.<caret>")
		);

		// Встроенные методы table должны быть
		assertTrue("Реальный Parser3 312 nameless: ^t. должен содержать 'menu', есть: " + completions,
				completions.contains("menu"));
		assertFalse("Реальный Parser3 312 nameless: ^t. не должен содержать вставленное значение как колонку: " + completions,
				completions.contains("v0"));
	}

	public void testMethodResultInference_tableColumnsViaResult() {
		List<String> completions = doComplete("test_tbl_result_method.p",
				"@buildTable[]\n" +
						"# на основе parser3/tests/312.html\n" +
						"$result[^table::create{c1\tc2\tc3}]\n" +
						"\n" +
						"@main[]\n" +
						"$list[^buildTable[]]\n" +
						"$list.<caret>"
		);

		assertTrue("$list. должен содержать c1 из table, возвращённой через $result: " + completions,
				completions.contains("c1"));
		assertTrue("$list. должен содержать c3 из table, возвращённой через $result: " + completions,
				completions.contains("c3"));
	}

	public void testMethodResultInference_tableResultWeakOverrideKeepsColumns() {
		List<String> completions = doComplete("test_tbl_result_weak_override_method.p",
				"@buildTable[]\n" +
						"# на основе parser3/tests/312.html\n" +
						"$result[^table::create{c1\tc2\tc3}]\n" +
						"^if(1){\n" +
						"\t$result(1)\n" +
						"}\n" +
						"\n" +
						"@main[]\n" +
						"$list[^buildTable[]]\n" +
						"$list.<caret>"
		);

		assertTrue("Слабое $result(1) не должно затирать колонку c1 table-result: " + completions,
				completions.contains("c1"));
		assertTrue("Слабое $result(1) не должно затирать колонку c3 table-result: " + completions,
				completions.contains("c3"));
	}

	public void testMethodResultInference_tableColumnsViaReturnAlias() {
		List<String> completions = doComplete("test_tbl_return_method.p",
				"@buildTable[]\n" +
						"# на основе parser3/tests/312.html\n" +
						"$tmp[^table::create{c1\tc2\tc3}]\n" +
						"^return[$tmp]\n" +
						"\n" +
						"@main[]\n" +
						"$list[^buildTable[]]\n" +
						"^list.<caret>"
		);

		assertTrue("^list. должен содержать c1 из alias-таблицы: " + completions,
				completions.contains("c1."));
		assertTrue("^list. должен содержать c3 из alias-таблицы: " + completions,
				completions.contains("c3."));
		assertTrue("^list. должен сохранять builtin-методы table: " + completions,
				completions.contains("menu"));
	}

	// =============================================================================
	// РАЗДЕЛ 3: Многострочные таблицы — колонки из первой строки
	// =============================================================================

	/**
	 * Многострочная таблица — колонки из первой строки.
	 */
	public void testTableColumns_multiline() {
		List<String> completions = doComplete("test_tbl5.p",
				parser407RealTableSelectBase("^log.<caret>")
		);

		assertTrue("Реальный Parser3 407: ^log. должен содержать 'cpu.', есть: " + completions,
				completions.contains("cpu."));
		assertTrue("Реальный Parser3 407: ^log. должен содержать 'url.', есть: " + completions,
				completions.contains("url."));
		// Данные не должны быть колонками
		assertFalse("Реальный Parser3 407: ^log. не должен содержать значение URL как колонку, есть: " + completions,
				completions.contains("/novosibirsk/retail/"));
	}

	// =============================================================================
	// РАЗДЕЛ 4: $var автокомплит — таблица с колонками добавляет точку
	// =============================================================================

	/**
	 * $list с колонками — в автокомплите $<caret> вставляется как "list." (с точкой).
	 */
	public void testTableColumns_dollarAutocompleteDot() {
		List<String> completions = doComplete("test_tbl6.p",
				parser312RealTableBase("$<caret>")
		);

		assertTrue("Реальный Parser3 312: $ должен содержать 't.' (с точкой), есть: " + completions,
				completions.contains("t."));
	}

	/**
	 * $list0 без колонок — в автокомплите $<caret> вставляется как "list0" (без точки).
	 */
	public void testTableColumns_dollarAutocomplete_nameless_noDot() {
		List<String> completions = doComplete("test_tbl7.p",
				parser312RealNamelessTableBase("$<caret>")
		);

		assertTrue("Реальный Parser3 312 nameless: $ должен содержать 't' (без точки), есть: " + completions,
				completions.contains("t"));
		assertFalse("Реальный Parser3 312 nameless: $ не должен содержать 't.' (с точкой), есть: " + completions,
				completions.contains("t."));
	}

	// =============================================================================
	// РАЗДЕЛ 5: $self.var и $MAIN:var — тоже работают
	// =============================================================================

	/**
	 * $self.list[^table::create{c1\tc2\tc3}] — колонки через $self.list.
	 */
	public void testTableColumns_selfVar() {
		List<String> completions = doComplete("test_tbl8.p",
				"@main[]\n" +
						"# на основе parser3/tests/312.html\n" +
						"$self.list[^table::create{c1\tc2\tc3}]\n" +
						"$self.list.<caret>"
		);

		assertTrue("$self.list. должен содержать 'c1', есть: " + completions,
				completions.contains("c1"));
		assertTrue("$self.list. должен содержать 'c3', есть: " + completions,
				completions.contains("c3"));
	}

	/**
	 * $MAIN:list[^table::create{c1\tc2\tc3}] — колонки через ^MAIN:list.
	 */
	public void testTableColumns_mainVar() {
		List<String> completions = doComplete("test_tbl9.p",
				"@main[]\n" +
						"# на основе parser3/tests/312.html\n" +
						"$MAIN:list[^table::create{c1\tc2\tc3}]\n" +
						"^MAIN:list.<caret>"
		);

		assertTrue("^MAIN:list. должен содержать 'c1.', есть: " + completions,
				completions.contains("c1."));
		assertTrue("^MAIN:list. должен содержать 'c3.', есть: " + completions,
				completions.contains("c3."));
	}

	// =============================================================================
	// РАЗДЕЛ 6: Таблица без {} — обычная table без колонок
	// =============================================================================

	/**
	 * $tOriginNew[^table::load[$sFileNamed;$hOption]] — файл динамический, колонок нет.
	 */
	public void testTableColumns_load_noColumns() {
		List<String> completions = doComplete("test_tbl10.p",
				parser158RealTableLoadBase(
						"$tOriginNew[^table::load[$sFileNamed;$hOption]]\n" +
								"^tOriginNew.<caret>"
				)
		);

		// Встроенные методы table
		assertTrue("Реальный Parser3 158: ^tOriginNew. должен содержать 'menu', есть: " + completions,
				completions.contains("menu"));
		assertFalse("Реальный Parser3 158: ^tOriginNew. не должен выводить колонки динамически загруженного файла, есть: " + completions,
				completions.contains("name."));
		assertFalse("Реальный Parser3 158: ^tOriginNew. не должен выводить колонки динамически загруженного файла, есть: " + completions,
				completions.contains("age."));
	}

	// =============================================================================
	// РАЗДЕЛ 7: SQL SELECT — парсинг колонок
	// =============================================================================

	/**
	 * $t[^table::sql{select pet, food from pets}] — колонки pet, food.
	 */
	public void testTableColumns_sql_basic() {
		List<String> completions = doComplete("test_sql1.p",
				parser388RealTableSqlBase(
						"$t[^table::sql{select pet, food from pets}]\n" +
								"^t.<caret>"
				)
		);

		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'pet.', есть: " + completions,
				completions.contains("pet."));
		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'food.', есть: " + completions,
				completions.contains("food."));
	}

	/**
	 * SQL с alias: select food as id, pet from pets → id, pet.
	 */
	public void testTableColumns_sql_aliases() {
		List<String> completions = doComplete("test_sql2.p",
				parser388RealTableSqlBase(
						"$h[^table::sql{select food as id, pet from pets}]\n" +
								"^h.<caret>"
				)
		);

		assertTrue("Реальный Parser3 388-sql: ^h. должен содержать alias 'id.', есть: " + completions,
				completions.contains("id."));
		assertTrue("Реальный Parser3 388-sql: ^h. должен содержать 'pet.', есть: " + completions,
				completions.contains("pet."));
		// Оригинальные имена НЕ должны быть (есть alias)
		assertFalse("Реальный Parser3 388-sql: ^h. не должен содержать исходную колонку food. при alias id, есть: " + completions,
				completions.contains("food."));
	}

	public void testTableColumns_sql_alias_fromQualifiedColumn() {
		List<String> completions = doComplete("test_sql_alias_id.p",
				parser429RealTableSqlBase(
						"$a[^table::sql{select weigth as id, pet from pets}]\n" +
								"^a.<caret>"
				)
		);

		assertTrue("Реальный Parser3 429-sql: ^a. должен содержать 'id.', есть: " + completions,
				completions.contains("id."));
	}

	public void testTableColumns_sql_distinctAlias_fromQualifiedColumn() {
		List<String> completions = doComplete("test_sql_distinct_alias_id.p",
				parser388RealTableSqlBase(
						"$h[^table::sql{select distinct food as id, pet from pets}]\n" +
								"^h.<caret>"
				)
		);

		assertTrue("Реальный Parser3 388-sql: ^h. должен содержать 'id.', есть: " + completions,
				completions.contains("id."));
	}

	/**
	 * SQL с подзапросом: SELECT (SELECT count(*) FROM pages) AS cnt → cnt.
	 */
	public void testTableColumns_sql_subquery() {
		List<String> completions = doComplete("test_sql3.p",
				parser388RealTableSqlBase(
						"$t[^table::sql{select pet, (select count(*) from pets) AS cnt from pets}]\n" +
								"^t.<caret>"
				)
		);

		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'pet.', есть: " + completions,
				completions.contains("pet."));
		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'cnt.', есть: " + completions,
				completions.contains("cnt."));
	}

	/**
	 * Многострочный SQL.
	 */
	public void testTableColumns_sql_multiline() {
		List<String> completions = doComplete("test_sql4.p",
				parser388RealTableSqlBase(
						"$t[^table::sql{\n" +
						"\tSELECT\n" +
						"\t\tpet, food\n" +
						"\tFROM\n" +
						"\t\tpets\n" +
						"\tORDER BY pet\n" +
						"}]\n" +
								"^t.<caret>"
				)
		);

		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'pet.', есть: " + completions,
				completions.contains("pet."));
		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'food.', есть: " + completions,
				completions.contains("food."));
	}

	/**
	 * SQL с Parser3 переменными: SELECT $columns FROM $table_name — переменные не становятся колонками.
	 */
	public void testTableColumns_sql_withParser3Vars() {
		List<String> completions = doComplete("test_sql5.p",
				parser388RealTableSqlBase(
						"$t[^table::sql{SELECT pet, $extra_col FROM pets WHERE food=$food}]\n" +
								"^t.<caret>"
				)
		);

		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'pet.', есть: " + completions,
				completions.contains("pet."));
		// $extra_col и $id не должны быть колонками
		assertFalse("Реальный Parser3 388-sql: ^t. не должен считать Parser3-переменную SQL-колонкой, есть: " + completions,
				completions.contains("extra_col"));
	}

	/**
	 * $list. — колонки из SQL доступны и в $ контексте.
	 */
	public void testTableColumns_sql_dollarDot() {
		List<String> completions = doComplete("test_sql6.p",
				parser388RealTableSqlBase(
						"$t[^table::sql{select pet, food from pets}]\n" +
								"$t.<caret>"
				)
		);

		assertTrue("Реальный Parser3 388-sql: $t. должен содержать 'pet', есть: " + completions,
				completions.contains("pet"));
		assertTrue("Реальный Parser3 388-sql: $t. должен содержать 'food', есть: " + completions,
				completions.contains("food"));
	}

	public void testTableColumns_sql_backtickQuotedIdentifier_dollarDot() {
		List<String> completions = doComplete("test_sql_backtick_identifier.p",
				"@main[]\n" +
						"# на основе parser3/tests/429-sql.html\n" +
						"$list[^table::sql{\n" +
						"SELECT\n" +
						"    `from`\n" +
						"FROM\n" +
						"    list\n" +
						"}]\n" +
						"$list.<caret>"
		);

		assertTrue("$list. должен содержать 'from', есть: " + completions,
				completions.contains("from"));
		assertFalse("$list. не должен содержать '`', есть: " + completions,
				completions.contains("`"));
	}

	public void testTableColumns_sql_keywordAliasInQuotes_dollarDot() {
		List<String> completions = doComplete("test_sql_keyword_alias_in_quotes.p",
				"@main[]\n" +
						"# на основе parser3/tests/429-sql.html\n" +
						"$list[^table::sql{\n" +
						"SELECT\n" +
						"    `from` AS 'limit', id\n" +
						"FROM\n" +
						"    pets\n" +
						"}]\n" +
						"$list.<caret>"
		);

		assertTrue("$list. должен содержать 'limit', есть: " + completions,
				completions.contains("limit"));
		assertTrue("$list. должен содержать 'id', есть: " + completions,
				completions.contains("id"));
		assertFalse("$list. не должен содержать '`', есть: " + completions,
				completions.contains("`"));
	}

	/**
	 * ^table:sql (одно двоеточие) — тоже работает.
	 */
	public void testTableColumns_sql_singleColon() {
		List<String> completions = doComplete("test_sql7.p",
				parser388RealTableSqlBase(
						"$t[^table:sql{select pet, food from pets}]\n" +
								"^t.<caret>"
				)
		);

		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'pet.', есть: " + completions,
				completions.contains("pet."));
		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'food.', есть: " + completions,
				completions.contains("food."));
	}

	/**
	 * SELECT * — колонки неизвестны, показываем только встроенные методы.
	 */
	public void testTableColumns_sql_selectStar() {
		List<String> completions = doComplete("test_sql8.p",
				parser388RealTableSqlBase(
						"$t[^table::sql{select * from pets}]\n" +
								"^t.<caret>"
				)
		);

		// Встроенные методы table
		assertTrue("Реальный Parser3 388-sql: ^t. должен содержать 'menu' (встроенный метод), есть: " + completions,
				completions.contains("menu"));
		assertFalse("Реальный Parser3 388-sql: SELECT * не должен превращать имя таблицы в колонку, есть: " + completions,
				completions.contains("pets."));
	}

	// =============================================================================
	// РАЗДЕЛ 8: Юнит-тест parseSqlSelectColumns напрямую
	// =============================================================================

	/**
	 * Прямой тест парсера SQL SELECT колонок.
	 */
	public void testParseSqlSelectColumns_direct() {
		// на основе parser3/tests/388-sql.html
		List<String> cols1 = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"select pet, food from pets");
		assertNotNull("Должны быть колонки", cols1);
		assertEquals("Должно быть 2 колонки", 2, cols1.size());
		assertTrue("Колонка pet", cols1.contains("pet"));
		assertTrue("Колонка food", cols1.contains("food"));

		// на основе parser3/tests/388-sql.html
		List<String> cols2 = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"select food as id, pet from pets");
		assertNotNull("Должны быть колонки с alias", cols2);
		assertEquals("Должно быть 2 колонки", 2, cols2.size());
		assertTrue("Колонка id", cols2.contains("id"));
		assertTrue("Колонка pet", cols2.contains("pet"));

		// на основе parser3/tests/388-sql.html
		List<String> cols3 = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"select aggressive,'test.txt' from pets");
		assertNotNull("Должны быть колонки с выражением", cols3);
		assertEquals("Должно быть 2 колонки", 2, cols3.size());
		assertTrue("Колонка aggressive", cols3.contains("aggressive"));
		assertTrue("Строковое выражение", cols3.contains("'test.txt'"));

		// на основе parser3/tests/388-sql.html
		List<String> cols4 = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"select * from pets");
		assertNull("SELECT * — колонки неизвестны", cols4);

		// на основе parser3/tests/429-sql.html
		List<String> cols5 = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"select weigth, pets.* from pets");
		assertNotNull("Должны быть колонки через точку", cols5);
		assertTrue("Колонка weigth", cols5.contains("weigth"));
		assertTrue("Qualified star сохраняется как выражение", cols5.contains("pets.*"));

		// на основе parser3/tests/429-sql.html
		List<String> cols6 = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"select weigth as id, pet from pets");
		assertNotNull("Должны быть колонки с alias от qualified column", cols6);
		assertTrue("Колонка id", cols6.contains("id"));

		// на основе parser3/tests/388-sql.html
		List<String> cols7 = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"select distinct food as id, pet from pets");
		assertNotNull("Должны быть колонки с distinct alias", cols7);
		assertTrue("Колонка id", cols7.contains("id"));
	}

	// =============================================================================
	// РАЗДЕЛ 9: SQL в скобках (SELECT ... UNION ...)
	// =============================================================================

	/**
	 * (SELECT ... FROM data) UNION ... — парсит внутри скобок.
	 */
	public void testParseSql_parenthesized() {
		// на основе parser3/tests/388-sql.html и 429-sql.html
		List<String> cols = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"(SELECT\n" +
						"\t\tname, value, 'some' AS 'some column table name',\n" +
						"\t\tx.xxx,\n" +
						"\t\tlower(x),\n" +
						"\t\t(select count(*) from users),\n" +
						"\t\tcount(*)\n" +
						"\tFROM\n" +
						"\t\tdata)\n" +
						"\tUNION ...");
		assertNotNull("Должны быть колонки", cols);
		assertTrue("Колонка name", cols.contains("name"));
		assertTrue("Колонка value", cols.contains("value"));
		assertTrue("Колонка 'some column table name' (без кавычек)", cols.contains("some column table name"));
		assertTrue("Колонка xxx (из x.xxx)", cols.contains("xxx"));
		assertTrue("Выражение lower(x)", cols.contains("lower(x)"));
		assertTrue("Подзапрос (select count(*) from users)", cols.contains("(select count(*) from users)"));
		assertTrue("Выражение count(*)", cols.contains("count(*)"));
		assertEquals("Всего 7 колонок", 7, cols.size());
	}

	public void testParseSql_backtickQuotedIdentifier() {
		// на основе parser3/tests/429-sql.html
		List<String> cols = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"SELECT `from` FROM list");
		assertNotNull("Должны быть колонки с backtick-quoted identifier", cols);
		assertEquals("Должна быть 1 колонка", 1, cols.size());
		assertTrue("Колонка from без backticks", cols.contains("from"));
	}

	public void testParseSql_keywordAliasInQuotes() {
		// на основе parser3/tests/429-sql.html
		List<String> cols = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"SELECT `from` AS 'limit', id, value AS \"order\" FROM list");
		assertNotNull("Должны быть колонки с alias-ключевыми словами в кавычках", cols);
		assertEquals("Должно быть 3 колонки", 3, cols.size());
		assertTrue("Колонка limit", cols.contains("limit"));
		assertTrue("Колонка id", cols.contains("id"));
		assertTrue("Колонка order", cols.contains("order"));
	}

	/**
	 * SQL с кавычками в AS — одинарные, двойные, backtick.
	 */
	public void testParseSql_quotedAlias() {
		// на основе parser3/tests/388-sql.html и 429-sql.html
		List<String> cols = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"SELECT name, 'text' AS 'col with spaces', value AS \"quoted col\" FROM t");
		assertNotNull("Должны быть колонки", cols);
		assertTrue("Колонка name", cols.contains("name"));
		assertTrue("Колонка 'col with spaces' без кавычек", cols.contains("col with spaces"));
		assertTrue("Колонка 'quoted col' без кавычек", cols.contains("quoted col"));
	}

	/**
	 * SQL выражения без AS — сохраняются как есть.
	 */
	public void testParseSql_expressionsNoAs() {
		// на основе parser3/tests/388-sql.html
		List<String> cols = ru.artlebedev.parser3.index.P3VariableFileIndex.parseSqlSelectColumns(
				"SELECT lower(name), count(*), (select max(id) from t2) FROM t");
		assertNotNull("Должны быть колонки", cols);
		assertTrue("lower(name)", cols.contains("lower(name)"));
		assertTrue("count(*)", cols.contains("count(*)"));
		assertTrue("(select max(id) from t2)", cols.contains("(select max(id) from t2)"));
	}

	// =============================================================================
	// РАЗДЕЛ 10: Копирование таблицы — $copy[^table::create[$list]]
	// =============================================================================

	/**
	 * $copy[^table::create[$list]] — колонки наследуются от $list.
	 */
	public void testTableColumns_copy_dollarDot() {
		List<String> completions = doComplete("test_copy1.p",
				parser312RealTableCopyBase("$copy.<caret>")
		);

		assertTrue("Реальный Parser3 312: $copy. должен содержать исходную колонку c1, есть: " + completions,
				completions.contains("c1"));
		assertTrue("Реальный Parser3 312: $copy. должен содержать исходную колонку c3, есть: " + completions,
				completions.contains("c3"));
		assertFalse("Реальный Parser3 312: $copy. не должен видеть позднюю колонку c4 из $t, есть: " + completions,
				completions.contains("c4"));
	}

	/**
	 * $copy[^table::create[$list]] — ^copy. тоже показывает колонки.
	 */
	public void testTableColumns_copy_caretDot() {
		List<String> completions = doComplete("test_copy2.p",
				parser312RealTableCopyBase("^copy.<caret>")
		);

		assertTrue("Реальный Parser3 312: ^copy. должен содержать исходную колонку c1., есть: " + completions,
				completions.contains("c1."));
		assertTrue("Реальный Parser3 312: ^copy. должен содержать исходную колонку c3., есть: " + completions,
				completions.contains("c3."));
		assertFalse("Реальный Parser3 312: ^copy. не должен видеть позднюю колонку c4. из $t, есть: " + completions,
				completions.contains("c4."));
	}

	/**
	 * $<caret> — copy вставляется с точкой (колонки от источника).
	 */
	public void testTableColumns_copy_dollarAutocompleteDot() {
		List<String> completions = doComplete("test_copy3.p",
				parser312RealTableCopyBase("$<caret>")
		);

		assertTrue("$ должен содержать 'copy.' (с точкой), есть: " + completions,
				completions.contains("copy."));
		assertTrue("$ должен содержать 't.' (с точкой), есть: " + completions,
				completions.contains("t."));
	}

	/**
	 * Автокомплит ^list. для SQL со сложными колонками.
	 */
	public void testTableColumns_sql_complex_completion() {
		List<String> completions = doComplete("test_sqlcplx.p",
				parser429RealTableSqlBase(
						"$a[^table::sql{select weigth as id, pet from pets}]\n" +
								"^a.<caret>"
				)
		);

		assertTrue("Реальный Parser3 429-sql: ^a. должен содержать alias 'id.', есть: " + completions,
				completions.contains("id."));
		assertTrue("Реальный Parser3 429-sql: ^a. должен содержать 'pet.', есть: " + completions,
				completions.contains("pet."));
	}

	// =============================================================================
	// РАЗДЕЛ 11: ^var.select() / ^var.sort{} — сохранение колонок
	// =============================================================================

	/**
	 * $subList[^list.select(...)] — колонки наследуются от $list.
	 */
	public void testTableColumns_select_dollarDot() {
		List<String> completions = doComplete("test_select1.p",
				parser407RealTableSelectBase(
						"$hit[^log.select[;cpu]($cpu<0.05)]\n" +
								"$hit.<caret>"
				)
		);

		assertTrue("$hit. должен содержать 'cpu', есть: " + completions,
				completions.contains("cpu"));
		assertTrue("$hit. должен содержать 'url', есть: " + completions,
				completions.contains("url"));
	}

	/**
	 * $subList[^list.select(...)] — ^subList. тоже показывает колонки.
	 */
	public void testTableColumns_select_caretDot() {
		List<String> completions = doComplete("test_select2.p",
				parser407RealTableSelectBase(
						"$hit[^log.select[;cpu]($cpu<0.05)]\n" +
								"^hit.<caret>"
				)
		);

		assertTrue("^hit. должен содержать 'cpu.', есть: " + completions,
				completions.contains("cpu."));
		assertTrue("^hit. должен содержать 'url.', есть: " + completions,
				completions.contains("url."));
	}

	/**
	 * Цепочка: $list → select → select — колонки сохраняются.
	 */
	public void testTableColumns_selectThenSelect() {
		List<String> completions = doComplete("test_chain1.p",
				parser407RealTableSelectBase(
						"$hit[^log.select[;cpu]($cpu<0.05)]\n" +
								"$short[^hit.select[url;](^url.length[]<7)]\n" +
								"$short.<caret>"
				)
		);

		assertTrue("$short. должен содержать 'cpu', есть: " + completions,
				completions.contains("cpu"));
		assertTrue("$short. должен содержать 'url', есть: " + completions,
				completions.contains("url"));
	}

	// =============================================================================
	// РАЗДЕЛ 12: ^var.split[] — table с колонкой "piece"
	// =============================================================================

	/**
	 * $list[^var.split[,]] — простой split, колонка "piece" по умолчанию.
	 */
	public void testTableColumns_split_simple_dollarDot() {
		List<String> completions = doComplete("test_split1.p",
				parser169RealStringSplitBase(
						"$tVertical[^sText.split[$sChar;v]]\n" +
								"$tVertical.<caret>"
				)
		);

		assertTrue("$tVertical. должен содержать 'piece', есть: " + completions,
				completions.contains("piece"));
	}

	/**
	 * $list[^var.split[,]] — ^list. содержит "piece." с точкой.
	 */
	public void testTableColumns_split_simple_caretDot() {
		List<String> completions = doComplete("test_split2.p",
				parser169RealStringSplitBase(
						"$tVertical[^sText.split[$sChar;v]]\n" +
								"^tVertical.<caret>"
				)
		);

		assertTrue("^tVertical. должен содержать 'piece.', есть: " + completions,
				completions.contains("piece."));
	}

	/**
	 * ^var.split[,;опции;xnamex] — кастомное имя столбца из 3-го параметра.
	 * Второй параметр содержит вложенные конструкции $.x[y].
	 */
	public void testTableColumns_split_fromStructuredField_dollarDot() {
		List<String> completions = doComplete("test_split_structured_field_1.p",
				parser169RealStringSplitBase(
						"$data[\n" +
								"\t$.sText[$sText]\n" +
								"]\n" +
								"$tVertical[^data.sText.split[$sChar;v]]\n" +
								"$tVertical.<caret>"
				)
		);

		assertTrue("$tVertical. должен содержать 'piece', есть: " + completions,
				completions.contains("piece"));
	}

	public void testTableColumns_split_customColumn_dollarDot() {
		List<String> completions = doComplete("test_split3.p",
				parser169RealStringSplitBase(
						"$tVertical[^sText.split[$sChar;v;$sColumnName]]\n" +
								"$tVertical.<caret>"
				)
		);

		assertTrue("$tVertical. должен содержать 'zigi', есть: " + completions,
				completions.contains("zigi"));
		assertFalse("$tVertical. НЕ должен содержать 'piece', есть: " + completions,
				completions.contains("piece"));
	}

	/**
	 * ^var.split[,;опции;xnamex] — ^list. содержит "xnamex." с точкой.
	 */
	public void testTableColumns_split_customColumn_caretDot() {
		List<String> completions = doComplete("test_split4.p",
				parser169RealStringSplitBase(
						"$tVertical[^sText.split[$sChar;v;$sColumnName]]\n" +
								"^tVertical.<caret>"
				)
		);

		assertTrue("^tVertical. должен содержать 'zigi.', есть: " + completions,
				completions.contains("zigi."));
		assertFalse("^tVertical. НЕ должен содержать 'piece.', есть: " + completions,
				completions.contains("piece."));
	}
	/**
	 * Переопределение $list через ^list.select(...) должно наследовать
	 * колонки от предыдущего определения этой же переменной.
	 */
	public void testTableColumns_select_sameNameUsesPreviousDefinition() {
		List<String> completions = doComplete("test_select_same_name_prev_scope.p",
				parser407RealTableSelectBase(
						"$log[^log.select[;cpu]($cpu<0.05)]\n" +
								"$log.<caret>"
				)
		);

		assertTrue("$log. должен сохранять 'cpu', есть: " + completions,
				completions.contains("cpu"));
		assertTrue("$log. должен сохранять 'url', есть: " + completions,
				completions.contains("url"));
	}

	/**
	 * Уже созданная копия $list2 должна оставаться привязанной
	 * к тому состоянию $list, из которого она была создана.
	 */
	public void testTableColumns_select_sourceSnapshotIsIndependent() {
		List<String> completions = doComplete("test_select_source_snapshot.p",
				parser407RealTableSelectBase(
						"$hit[^log.select[;cpu]($cpu<0.05)]\n" +
								"$log[^table::create{other}]\n" +
								"$hit.<caret>"
				)
		);

		assertTrue("$hit. должен сохранять 'cpu' после переопределения $log, есть: " + completions,
				completions.contains("cpu"));
		assertTrue("$hit. должен сохранять 'url' после переопределения $log, есть: " + completions,
				completions.contains("url"));
		assertFalse("$hit. не должен брать новую колонку 'other' из позднего $log, есть: " + completions,
				completions.contains("other"));
	}
}
