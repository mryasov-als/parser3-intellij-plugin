package ru.artlebedev.parser3;

import junit.framework.TestCase;
import ru.artlebedev.parser3.index.P3VariableFileIndex;
import ru.artlebedev.parser3.injector.Parser3TextCleaner;

import java.util.List;

public class SqlCleaningFastModeTest extends TestCase {

	public void testCleanSqlContent_fastIndexing_stripsParser3AndKeepsSql() {
		String sql = "select item_price_rur, item_price_usd, item_uri, " +
				"concat('https://test.com/^self.translate[path;]',item_uri) AS url " +
				"from items_text where item_id = $self.work.id and lang = $self.lang";

		String cleaned = Parser3TextCleaner.cleanSqlContent(
				sql,
				Parser3TextCleaner.SqlCleanMode.FAST_INDEXING
		);

		assertTrue(cleaned.contains("select item_price_rur, item_price_usd, item_uri"));
		assertTrue(cleaned.contains("AS url"));
		assertTrue(cleaned.contains("from items_text"));
		assertFalse(cleaned.contains("^self.translate"));
		assertFalse(cleaned.contains("$self.work.id"));
		assertFalse(cleaned.contains("$self.lang"));
	}

	public void testParseSqlSelectColumns_customOSqlSnippetFromItems() {
		String sql = "select item_price_rur, item_price_usd, item_uri, " +
				"concat('https://test.com/^self.translate[path;]',item_uri) AS url " +
				"from items_text where item_id = $self.work.id and lang = $self.lang";

		List<String> columns = P3VariableFileIndex.parseSqlSelectColumns(sql);

		assertNotNull(columns);
		assertEquals(List.of("item_price_rur", "item_price_usd", "item_uri", "url"), columns);
	}
}
