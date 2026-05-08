package ru.artlebedev.parser3;

/**
 * Тесты для EnterHandler — обработка комментариев (#).
 *
 * В Parser3 комментарий — это # СТРОГО в колонке 0.
 * Если перед # есть пробел/таб — это НЕ комментарий.
 */
public class x7_EnterHandlerCommentTest extends Parser3TestCase {

	// =====================================================
	// CASE COMMENT 1: Блок документации перед @method
	// =====================================================

	/**
	 * ###<enter> перед @method → создаёт блок документации с 40 #
	 */
	public void testDocBlockCreation() {
		createParser3File("test.p", "###<caret>\n@method[]");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Должно быть:
		// ########################################
		// # <caret>
		// ########################################
		// @method[]
		assertTrue("Должен содержать 40 #", result.contains("########################################"));
		assertTrue("Должен содержать '# ' для комментария", result.contains("\n# \n"));
	}

	/**
	 * Полный блок ### уже есть → добивает до 40 #
	 */
	public void testDocBlockExtension() {
		createParser3File("test.p", "####################<caret>\n@method[]");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		assertTrue("Должен содержать 40 #", result.contains("########################################"));
	}

	// =====================================================
	// CASE COMMENT 2: Продолжение блока комментариев
	// =====================================================

	/**
	 * Предыдущая строка — комментарий → продолжаем с тем же префиксом
	 */
	public void testContinueCommentFromPrevLine() {
		createParser3File("test.p", "# comment1\n# comment2<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Должно быть:
		// # comment1
		// # comment2
		// # <caret>
		assertEquals("# comment1\n# comment2\n# ", result);
	}

	/**
	 * Следующая строка — комментарий → продолжаем с тем же префиксом
	 */
	public void testContinueCommentFromNextLine() {
		createParser3File("test.p", "# comment1<caret>\n# comment2\n# comment3");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Должно быть:
		// # comment1
		// #
		// # comment2
		// # comment3
		assertEquals("# comment1\n# \n# comment2\n# comment3", result);
	}

	/**
	 * Сохраняет количество # (###)
	 */
	public void testPreserveHashCount() {
		createParser3File("test.p", "### comment1\n### comment2<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		assertEquals("### comment1\n### comment2\n### ", result);
	}

	/**
	 * Сохраняет сложный префикс (### # #\t\t)
	 */
	public void testPreserveComplexPrefix() {
		createParser3File("test.p", "### # #\t\ttext1\n### # #\t\ttext2<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		assertEquals("### # #\t\ttext1\n### # #\t\ttext2\n### # #\t\t", result);
	}

	/**
	 * Курсор в начале строки → стандартное поведение (без вставки префикса)
	 */
	public void testCursorAtLineStartNoPrefix() {
		createParser3File("test.p", "#### text1\n<caret>#### text2\n#### text3");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Должно быть просто перенос строки без добавления ####
		assertEquals("#### text1\n\n#### text2\n#### text3", result);
	}

	// =====================================================
	// CASE COMMENT 3: Одиночный комментарий
	// =====================================================

	/**
	 * Одиночный комментарий с табом → добавляет отступ
	 */
	public void testSingleCommentWithTab() {
		createParser3File("test.p", "#\tcomment<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Должно добавить таб (т.к. один таб после #)
		assertEquals("#\tcomment\n\t", result);
	}

	/**
	 * Одиночный комментарий с несколькими пробелами → добавляет отступ
	 */
	public void testSingleCommentWithMultipleSpaces() {
		createParser3File("test.p", "#  comment<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Должно добавить два пробела (т.к. больше одного пробельного символа)
		assertEquals("#  comment\n  ", result);
	}

	/**
	 * Одиночный комментарий с одним пробелом → НЕ добавляет отступ
	 */
	public void testSingleCommentWithOneSpace() {
		createParser3File("test.p", "# comment<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Один пробел — не добавляем отступ, стандартное поведение
		assertEquals("# comment\n", result);
	}

	/**
	 * Комментарий без пробела → НЕ добавляет отступ
	 */
	public void testCommentWithoutSpace() {
		createParser3File("test.p", "#comment<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Нет пробела после # — стандартное поведение
		assertEquals("#comment\n", result);
	}

	/**
	 * Курсор в начале одиночного комментария → стандартное поведение
	 */
	public void testCursorAtStartOfSingleComment() {
		createParser3File("test.p", "<caret># comment");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Просто перенос строки
		assertEquals("\n# comment", result);
	}

	// =====================================================
	// НЕ комментарии (# не в колонке 0)
	// =====================================================

	/**
	 * # с отступом — это НЕ комментарий
	 */
	public void testHashWithIndentIsNotComment() {
		createParser3File("test.p", "\t# not a comment<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		// Это не комментарий, стандартное форматирование
		// Не должно добавлять # в следующую строку
		assertFalse("Не должен добавлять # (это не комментарий)", result.endsWith("# "));
	}

	/**
	 * # с пробелом перед ним — это НЕ комментарий
	 */
	public void testHashWithSpaceBeforeIsNotComment() {
		createParser3File("test.p", " # not a comment<caret>");
		myFixture.type("\n");

		String result = myFixture.getEditor().getDocument().getText();
		assertFalse("Не должен добавлять # (это не комментарий)", result.endsWith("# "));
	}
}