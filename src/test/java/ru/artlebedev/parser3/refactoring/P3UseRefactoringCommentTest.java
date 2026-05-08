package ru.artlebedev.parser3.refactoring;

/**
 * Тесты рефакторинга USE путей: обновление путей в комментариях.
 *
 * Проверяет что переименование/перемещение файла обновляет пути
 * и в закомментированных директивах @USE и ^use[].
 */
public class P3UseRefactoringCommentTest extends P3UseRefactoringTestBase {

	// =============================================================================
	// РАЗДЕЛ 1: ^use[] в комментариях
	// =============================================================================

	/**
	 * ^use[] внутри # комментария — переименование файла.
	 * Путь в закомментированном ^use[] должен обновиться.
	 */
	public void testHashCommentedUse_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n#^use[old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Путь в # комментарии должен обновиться: #^use[new.p]",
				content.contains("#^use[new.p]"));
		assertFalse("Старый путь не должен остаться",
				content.contains("old.p"));
	}

	/**
	 * ^use[] внутри ^rem{} — переименование файла.
	 * Путь в ^rem{^use[]} должен обновиться.
	 */
	public void testRemCommentedUse_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^rem{^use[old.p]}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Путь в ^rem{} должен обновиться: ^rem{^use[new.p]}",
				content.contains("^rem{^use[new.p]}"));
		assertFalse("Старый путь не должен остаться",
				content.contains("old.p"));
	}

	/**
	 * ^use[] внутри ^rem{} — перемещение файла.
	 */
	public void testRemCommentedUse_Move() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^rem{^use[helper.p]}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("helper.p", "@helper[]");
		ensureDirectoryExists("lib");

		// Act
		moveFile("helper.p", "lib");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Путь в ^rem{} должен обновиться: ^rem{^use[lib/helper.p]}",
				content.contains("^rem{^use[lib/helper.p]}"));
	}

	// =============================================================================
	// РАЗДЕЛ 2: @USE блок с # комментариями
	// =============================================================================

	/**
	 * @USE блок — путь закомментирован #.
	 * Закомментированный путь должен обновиться при переименовании.
	 */
	public void testAtUseBlockHashComment_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\n#old.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Закомментированный путь в @USE должен обновиться: #new.p",
				content.contains("#new.p"));
		assertFalse("Старый путь не должен остаться",
				content.contains("old.p"));
	}

	/**
	 * @USE блок — путь закомментирован #, перемещение файла.
	 */
	public void testAtUseBlockHashComment_Move() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\n#helper.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("helper.p", "@helper[]");
		ensureDirectoryExists("lib");

		// Act
		moveFile("helper.p", "lib");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Закомментированный путь в @USE должен обновиться: #lib/helper.p",
				content.contains("#lib/helper.p"));
	}

	/**
	 * @USE блок — смесь активных и закомментированных путей.
	 * Оба должны обновиться при переименовании.
	 */
	public void testAtUseBlockMixed_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\nactive.p\n#old.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("active.p", "@active[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Закомментированный путь должен обновиться",
				content.contains("#new.p"));
		assertTrue("Активный путь не должен измениться",
				content.contains("active.p"));
	}

	// =============================================================================
	// РАЗДЕЛ 3: @USE блок с ^rem{} комментариями
	// =============================================================================

	/**
	 * @USE блок — путь внутри ^rem{}.
	 * Закомментированный ^rem{путь} должен обновиться при переименовании.
	 */
	public void testAtUseBlockRemComment_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\n^rem{old.p}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Путь внутри ^rem{} в @USE должен обновиться",
				content.contains("^rem{new.p}"));
		assertFalse("Старый путь не должен остаться",
				content.contains("old.p"));
	}

	/**
	 * @USE блок — путь внутри ^rem{} с полным путём, перемещение файла.
	 */
	public void testAtUseBlockRemComment_Move() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\n^rem{helper.p}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("helper.p", "@helper[]");
		ensureDirectoryExists("lib");

		// Act
		moveFile("helper.p", "lib");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Путь внутри ^rem{} в @USE должен обновиться",
				content.contains("^rem{lib/helper.p}"));
	}

	// =============================================================================
	// РАЗДЕЛ 4: Смешанные случаи
	// =============================================================================

	/**
	 * Файл имеет и активный ^use[], и закомментированный #^use[].
	 * Оба пути должны обновиться.
	 */
	public void testActiveAndHashCommentedUse_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[old.p]\n#^use[old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Активный ^use должен обновиться",
				content.contains("^use[new.p]"));
		assertTrue("Закомментированный ^use должен обновиться",
				content.contains("#^use[new.p]"));
	}

	/**
	 * Файл имеет и активный ^use[], и ^rem{^use[]}.
	 * Оба пути должны обновиться.
	 */
	public void testActiveAndRemCommentedUse_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[old.p]\n^rem{^use[old.p]}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Активный ^use должен обновиться",
				content.contains("^use[new.p]"));
		assertTrue("^rem{^use[]} должен обновиться",
				content.contains("^rem{^use[new.p]}"));
	}

	/**
	 * @USE блок — активный и закомментированный #, переименование.
	 */
	public void testAtUseBlockActiveAndComment_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\nold.p\n#old.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Активный путь в @USE должен обновиться",
				content.contains("\nnew.p"));
		assertTrue("Закомментированный путь в @USE должен обновиться",
				content.contains("#new.p"));
	}

	// =============================================================================
	// РАЗДЕЛ 5: Многострочные ^rem{} комментарии
	// =============================================================================

	/**
	 * @USE блок — ^rem{} на нескольких строках с отступами.
	 *
	 * До:
	 *   @USE
	 *   ^rem{
	 *   	old.p
	 *   }
	 *
	 * После переименования old.p → new.p:
	 *   @USE
	 *   ^rem{
	 *   	new.p
	 *   }
	 */
	public void testAtUseBlockRemMultiline_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\n^rem{\n\told.p\n}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Путь внутри многострочного ^rem{} должен обновиться",
				content.contains("new.p"));
		assertFalse("Старый путь не должен остаться",
				content.contains("old.p"));
	}

	/**
	 * Вложенный ^rem{} с # комментарием внутри.
	 *
	 * До:
	 *   @USE
	 *   ^rem{
	 *   #	^rem{./old.p}
	 *   }
	 *
	 * После переименования old.p → new.p:
	 *   @USE
	 *   ^rem{
	 *   #	^rem{./new.p}
	 *   }
	 */
	public void testAtUseBlockNestedRemWithHash_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\n^rem{\n#\t^rem{./old.p}\n}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("Путь внутри вложенного ^rem{} должен обновиться",
				content.contains("new.p"));
		assertFalse("Старый путь не должен остаться",
				content.contains("old.p"));
	}

	// =============================================================================
	// РАЗДЕЛ 6: ^use[] в строках и переменных — НЕ должен обновляться
	// =============================================================================

	/**
	 * ^use[] внутри строки переменной — НЕ реальный use, а просто текст.
	 *
	 * До:
	 *   # $var[asf ^use[old.p]]
	 *
	 * После переименования old.p → new.p:
	 *   # $var[asf ^use[new.p]]
	 *
	 * ^use[] в комментарии — текст обновляется потому что ^use[] матчится
	 * регулярным выражением вне зависимости от контекста переменной.
	 */
	public void testHashCommentedUseInsideVariable_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n# $var[asf ^use[old.p]]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertTrue("^use[] в # комментарии обновляется даже внутри $var",
				content.contains("^use[new.p]"));
	}

	// =============================================================================
	// РАЗДЕЛ 7: Относительные пути в комментариях
	// =============================================================================

	/**
	 * Относительный путь с ../ в закомментированном ^use[].
	 *
	 * До (dir/main.p):
	 *   #^use[../old.p]
	 *
	 * После переименования old.p → new.p:
	 *   #^use[../new.p]
	 */
	public void testHashCommentedUseRelativePath_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main.p", "@main[]\n#^use[../old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("dir/main.p");
		assertTrue("Относительный путь в # комментарии должен обновиться",
				content.contains("#^use[../new.p]"));
	}

	/**
	 * Относительный путь в закомментированном @USE блоке.
	 *
	 * До (dir/main.p):
	 *   @USE
	 *   #../old.p
	 *
	 * После переименования old.p → new.p:
	 *   @USE
	 *   #../new.p
	 */
	public void testAtUseBlockHashCommentRelativePath_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main.p", "@main[]\n@USE\n#../old.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("dir/main.p");
		assertTrue("Относительный путь в @USE # комментарии должен обновиться",
				content.contains("#../new.p"));
	}

	// =============================================================================
	// РАЗДЕЛ 8: Множественные закомментированные ^use[] в одном файле
	// =============================================================================

	/**
	 * Несколько закомментированных ^use[] на один и тот же файл.
	 *
	 * До:
	 *   @main[]
	 *   ^use[old.p]
	 *   #^use[old.p]
	 *   ^rem{^use[old.p]}
	 *
	 * После переименования old.p → new.p:
	 *   @main[]
	 *   ^use[new.p]
	 *   #^use[new.p]
	 *   ^rem{^use[new.p]}
	 */
	public void testMultipleCommentedUses_Rename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p",
				"@main[]\n^use[old.p]\n#^use[old.p]\n^rem{^use[old.p]}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		String content = getFileContent("main.p");
		assertFalse("Старый путь не должен остаться нигде",
				content.contains("old.p"));
		// Должно быть 3 вхождения new.p
		int count = countOccurrences(content, "new.p");
		assertEquals("Все 3 вхождения должны обновиться", 3, count);
	}

	// =============================================================================
	// РАЗДЕЛ 9: Видимость — закомментированные не влияют
	// =============================================================================

	/**
	 * Закомментированный ^use[] НЕ должен влиять на видимость.
	 */
	public void testHashCommentedUse_DoesNotAffectVisibility() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n#^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@helper[]\n$result[ok]");

		// Act & Assert
		com.intellij.openapi.vfs.VirtualFile mainFile = myFixture.findFileInTempDir("main.p");
		assertNotNull(mainFile);

		ru.artlebedev.parser3.visibility.P3VisibilityService visService =
				ru.artlebedev.parser3.visibility.P3VisibilityService.getInstance(getProject());
		java.util.List<com.intellij.openapi.vfs.VirtualFile> visible = visService.getVisibleFiles(mainFile);

		com.intellij.openapi.vfs.VirtualFile libFile = myFixture.findFileInTempDir("lib.p");
		assertNotNull(libFile);

		assertFalse("Файл из закомментированного ^use[] НЕ должен быть видимым",
				visible.contains(libFile));
	}

	/**
	 * Закомментированный путь в @USE НЕ должен влиять на видимость.
	 */
	public void testAtUseBlockHashComment_DoesNotAffectVisibility() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\n#lib.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@helper[]\n$result[ok]");

		// Act & Assert
		com.intellij.openapi.vfs.VirtualFile mainFile = myFixture.findFileInTempDir("main.p");
		assertNotNull(mainFile);

		ru.artlebedev.parser3.visibility.P3VisibilityService visService =
				ru.artlebedev.parser3.visibility.P3VisibilityService.getInstance(getProject());
		java.util.List<com.intellij.openapi.vfs.VirtualFile> visible = visService.getVisibleFiles(mainFile);

		com.intellij.openapi.vfs.VirtualFile libFile = myFixture.findFileInTempDir("lib.p");
		assertNotNull(libFile);

		assertFalse("Файл из закомментированного @USE НЕ должен быть видимым",
				visible.contains(libFile));
	}

	/**
	 * ^rem{^use[]} НЕ должен влиять на видимость.
	 */
	public void testRemCommentedUse_DoesNotAffectVisibility() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^rem{^use[lib.p]}");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@helper[]\n$result[ok]");

		// Act & Assert
		com.intellij.openapi.vfs.VirtualFile mainFile = myFixture.findFileInTempDir("main.p");
		assertNotNull(mainFile);

		ru.artlebedev.parser3.visibility.P3VisibilityService visService =
				ru.artlebedev.parser3.visibility.P3VisibilityService.getInstance(getProject());
		java.util.List<com.intellij.openapi.vfs.VirtualFile> visible = visService.getVisibleFiles(mainFile);

		com.intellij.openapi.vfs.VirtualFile libFile = myFixture.findFileInTempDir("lib.p");
		assertNotNull(libFile);

		assertFalse("Файл из ^rem{^use[]} НЕ должен быть видимым",
				visible.contains(libFile));
	}

	// ==================== Утилиты ====================

	/**
	 * Считает количество вхождений подстроки в строке.
	 */
	private int countOccurrences(String text, String substring) {
		int count = 0;
		int idx = 0;
		while ((idx = text.indexOf(substring, idx)) != -1) {
			count++;
			idx += substring.length();
		}
		return count;
	}
}
