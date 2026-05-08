package ru.artlebedev.parser3;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.vfs.VirtualFile;
import ru.artlebedev.parser3.completion.P3PathCompletionSupport;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Тесты автокомплита путей в файловых контекстах Parser3.
 */
public class x13_PathCompletionTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		createCommonFiles();
		setDocumentRoot("www");

		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
		com.intellij.openapi.project.DumbService.getInstance(getProject()).waitForSmartMode();
	}

	private void createCommonFiles() {
		createFileStructure(
				"www/page.p", "@main[]\n# реальный минимальный файл для path completion\n",
				"www/tests/051.html", "@USE\n/tests/051t.p\n\n@main[]\n# на основе parser3/tests/051.html\n$t[^t::create[]]\n^t.method[]\n",
				"www/tests/051t.p", "@USE\n/tests/051b.p\n\n@CLASS\nt\n\n@BASE\nb\n",
				"www/tests/051b.p", "@CLASS\nb\n\n@base_method[]\nbase\n",
				"www/tests/019paf2001.gif", "gif",
				"www/tests/022_dir/a.html", "<html/>",
				"www/tests/022_dir/b.txt", "b",
				"www/tests/022_dir/b[b].txt", "bb",
				"www/tests/022_dir/c.htm", "<html/>",
				"www/tests/058_paf2000.png", "png",
				"www/tests/096_dir/163.jpg", "jpg",
				"www/tests/096_dir/188.jpg", "jpg",
				"www/tests/098font.gif", "gif",
				"www/tests/103mark.gif", "gif",
				"www/tests/108.xsl", "<xsl:stylesheet version=\"1.0\"/>",
				"www/tests/152.html", "@main[]\n# на основе parser3/tests/152.html\n$fFile[^file::load[text;152.html]]\n$fStat[^file::stat[152.html]]\n$fImage[^image::measure[103paf2001.gif]]\n",
				"www/tests/161_utf8.txt", "utf8",
				"www/lib/utils.p", "@helper[]\n$result[ok]\n",
				"www/lib/extra/inner.p", "@helper2[]\n$result[ok]\n",
				"www/lib/data.xml", "<root/>",
				"www/lib/template.xsl", "<xsl:stylesheet version=\"1.0\"/>",
				"www/lib/schema.xsd", "<schema/>",
				"www/lib/readme.txt", "hello",
				"www/lib/script.pl", "print 1;",
				"www/lib/.hidden.txt", "hidden",
				"www/lib/img/photo.png", "png",
				"www/lib/img/photo.gif", "gif",
				"www/lib/img/banner.jpg", "jpg",
				"www/lib/img/movie.mov", "mov",
				"www/lib/img/font.gif", "gif",
				"www/lib/img/.secret.gif", "gif",
				"www/classes/User.p", "@CLASS\nUser\n",
				"www/sub/page.p", "@main[]\n# реальный минимальный файл для path completion из подпапки\n"
		);
	}

	private List<String> getCompletions(String path, String content) {
		createParser3FileInDir(path, content);
		VirtualFile vf = myFixture.findFileInTempDir(path);
		assertNotNull("Тестовый файл должен существовать: " + path, vf);
		myFixture.configureFromExistingVirtualFile(vf);
		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		if (elements == null) {
			return List.of();
		}
		return Arrays.stream(elements)
				.map(LookupElement::getLookupString)
				.collect(Collectors.toList());
	}

	private void assertCompletionContains(String path, String content, String... expected) {
		List<String> completions = getCompletions(path, content);
		for (String item : expected) {
			assertTrue("Автокомплит должен содержать '" + item + "', но содержит: " + completions, completions.contains(item));
		}
	}

	private void assertCompletionNotContains(String path, String content, String... unexpected) {
		List<String> completions = getCompletions(path, content);
		for (String item : unexpected) {
			assertFalse("Автокомплит не должен содержать '" + item + "', но содержит: " + completions, completions.contains(item));
		}
	}

	public void testUseCompletionShowsParser3FilesAndDirectoriesOnly() {
		assertCompletionContains(
				"www/test_use_completion.p",
				"@main[]\n# на основе parser3/tests/051.html и 051t.p\n^use[tests/<caret>]",
				"tests/051t.p",
				"tests/051b.p"
		);

		assertCompletionNotContains(
				"www/test_use_completion_negative.p",
				"@main[]\n# на основе parser3/tests/051.html и 051t.p\n^use[tests/<caret>]",
				"tests/051.html",
				"tests/",
				"lib/data.xml",
				"lib/readme.txt",
				"lib/img/photo.png"
		);
	}

	public void testUseCompletionDoesNotOfferParentDirectory() {
		assertCompletionNotContains(
				"www/sub/test_use_parent_completion.p",
				"@main[]\n^use[<caret>]",
				"../"
		);
	}

	public void testFileLoadCompletionShowsAnyFilesAndDirectories() {
		assertCompletionContains(
				"www/test_file_load_completion.p",
				"@main[]\n# на основе parser3/tests/152.html\n$f[^file::load[text;tests/<caret>]]",
				"tests/152.html",
				"tests/161_utf8.txt",
				"tests/022_dir/a.html"
		);

		assertCompletionNotContains(
				"www/test_file_load_completion_negative.p",
				"@main[]\n# на основе parser3/tests/152.html\n$f[^file::load[text;tests/<caret>]]",
				"lib/.hidden.txt",
				"lib/img/.secret.gif"
		);
	}

	public void testImageMeasureCompletionShowsOnlySupportedMedia() {
		assertCompletionContains(
				"www/test_image_measure_completion.p",
				"@main[]\n# на основе parser3/tests/058.html и 152.html\n$img[^image::measure[tests/<caret>]]",
				"tests/019paf2001.gif",
				"tests/058_paf2000.png",
				"tests/098font.gif"
		);

		assertCompletionNotContains(
				"www/test_image_measure_completion_negative.p",
				"@main[]\n# на основе parser3/tests/058.html и 152.html\n$img[^image::measure[tests/<caret>]]",
				"tests/152.html",
				"tests/161_utf8.txt",
				"tests/108.xsl"
		);
	}

	public void testImageLoadCompletionShowsOnlyGif() {
		assertCompletionContains(
				"www/test_image_load_completion.p",
				"@main[]\n# на основе parser3/tests/019.html и 103.html\n$img[^image::load[tests/<caret>]]",
				"tests/019paf2001.gif",
				"tests/098font.gif",
				"tests/103mark.gif"
		);

		assertCompletionNotContains(
				"www/test_image_load_completion_negative.p",
				"@main[]\n# на основе parser3/tests/019.html и 103.html\n$img[^image::load[tests/<caret>]]",
				"tests/058_paf2000.png",
				"tests/152.html",
				"tests/161_utf8.txt"
		);
	}

	public void testImageFontCompletionShowsOnlyGifInSecondArgument() {
		assertCompletionContains(
				"www/test_image_font_completion.p",
				"@main[]\n# на основе parser3/tests/098.html\n^picture.font[012;tests/<caret>]",
				"tests/019paf2001.gif",
				"tests/098font.gif",
				"tests/103mark.gif"
		);

		assertCompletionNotContains(
				"www/test_image_font_completion_negative.p",
				"@main[]\n# на основе parser3/tests/098.html\n^picture.font[012;tests/<caret>]",
				"tests/058_paf2000.png",
				"tests/152.html"
		);
	}

	public void testXdocLoadCompletionShowsOnlyXmlFamily() {
		assertCompletionContains(
				"www/test_xdoc_completion.p",
				"@main[]\n# на основе parser3/tests/108.html\n$doc[^xdoc::load[tests/<caret>]]",
				"tests/108.xsl"
		);

		assertCompletionNotContains(
				"www/test_xdoc_completion_negative.p",
				"@main[]\n# на основе parser3/tests/108.html\n$doc[^xdoc::load[tests/<caret>]]",
				"tests/152.html",
				"tests/058_paf2000.png",
				"tests/161_utf8.txt"
		);
	}

	public void testClassPathCompletionShowsOnlyDirectories() {
		assertCompletionContains(
				"www/test_classpath_completion.p",
				"@main[]\n# реальный минимальный кейс CLASS_PATH path completion\n$CLASS_PATH[/lib/<caret>]",
				"/lib/extra/",
				"/lib/img/"
		);

		assertCompletionNotContains(
				"www/test_classpath_completion_negative.p",
				"@main[]\n# реальный минимальный кейс CLASS_PATH path completion\n$CLASS_PATH[/lib/<caret>]",
				"/lib/utils.p",
				"/lib/data.xml",
				"/lib/readme.txt"
		);
	}

	public void testDirectoryInsertKeepsTrailingSlash() {
		createParser3FileInDir("www/test_insert_target.p",
				"@main[]\n# на основе parser3/tests/152.html: минимальный кейс вставки пути\n$f[^file::load[text;lib/<caret>]]");
		VirtualFile vf = myFixture.findFileInTempDir("www/test_insert_target.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты path completion", elements);

		LookupElement file = Arrays.stream(elements)
				.filter(e -> "lib/readme.txt".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element 'lib/readme.txt'", file);

		myFixture.getLookup().setCurrentItem(file);
		myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("После вставки файла путь должен подставиться целиком: " + text, text.contains("^file::load[text;lib/readme.txt]"));
	}

	public void testFileInsertReplacesTailInsideArgument() {
		createParser3FileInDir("www/test_insert_tail_target.p",
				"@main[]\n# на основе parser3/tests/152.html: минимальный кейс замены хвоста пути\n$f[^file::load[text;lib/re<caret>mainder]]");
		VirtualFile vf = myFixture.findFileInTempDir("www/test_insert_tail_target.p");
		myFixture.configureFromExistingVirtualFile(vf);

		myFixture.complete(CompletionType.BASIC);
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull("Должны быть варианты path completion", elements);

		LookupElement file = Arrays.stream(elements)
				.filter(e -> "lib/readme.txt".equals(e.getLookupString()))
				.findFirst()
				.orElse(null);
		assertNotNull("Должен быть lookup element 'lib/readme.txt'", file);

		myFixture.getLookup().setCurrentItem(file);
		myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);

		String text = myFixture.getEditor().getDocument().getText();
		assertTrue("Хвост аргумента должен замениться путём файла: " + text, text.contains("^file::load[text;lib/readme.txt]"));
		assertFalse("Старый хвост не должен остаться после вставки: " + text, text.contains("remainder"));
	}

	public void testFileListCompletionShowsOnlyDirectories() {
		assertCompletionContains(
				"www/test_file_list_completion.p",
					"@main[]\n# на основе parser3/tests/022.html\n$list[^file:list[/tests/<caret>]]",
				"/tests/022_dir/",
				"/tests/096_dir/"
		);

		assertCompletionNotContains(
				"www/test_file_list_completion_negative.p",
					"@main[]\n# на основе parser3/tests/022.html\n$list[^file:list[/tests/<caret>]]",
				"/tests/152.html",
				"/tests/161_utf8.txt",
				"/tests/108.xsl"
		);
	}

	public void testFileListAutoPopupStartsImmediatelyAfterBracket() {
		String source = "@main[]\n# на основе parser3/tests/022.html: минимальный кейс auto-popup file:list\n$list[^file:list<caret>]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		assertTrue(
				"После открытия аргумента у file::list popup должен стартовать сразу",
				P3PathCompletionSupport.shouldAutoPopupOnTypedChar(text, offset, '[')
		);
	}

	public void testFileLoadAutoPopupStartsImmediatelyAfterSemicolon() {
		String source = "@main[]\n# на основе parser3/tests/152.html: минимальный кейс auto-popup file::load\n$f[^file::load[text<caret>]]";
		int offset = source.indexOf("<caret>");
		String text = source.replace("<caret>", "");

		assertTrue(
				"После перехода к path-аргументу file::load popup должен открыться сразу",
				P3PathCompletionSupport.shouldAutoPopupOnTypedChar(text, offset, ';')
		);
	}
}
