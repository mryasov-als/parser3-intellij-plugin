package ru.artlebedev.parser3.refactoring;

/**
 * Полный набор тестов рефакторинга USE путей.
 * Покрывает все комбинации: форматы директив, типы путей, направления перемещения.
 */
public class P3UseRefactoringFullTest extends P3UseRefactoringTestBase {

	// =============================================================================
	// РАЗДЕЛ 1: ФОРМАТ ДИРЕКТИВЫ ^use[] vs @USE
	// =============================================================================

	/**
	 * @USE директива — перемещение файла.
	 * Синтаксис @USE: директива на одной строке, пути на следующих строках.
	 */
	public void testAtUseDirective_MoveFile() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir1/main.p", "@main[]\n@USE\nhelper.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir1/helper.p", "@helper[]");
		ensureDirectoryExists("dir2");

		// Act
		moveFile("dir1/helper.p", "dir2");

		// Assert
		assertContainsUse("dir1/main.p", "../dir2/helper.p");
	}

	/**
	 * @USE директива — переименование файла.
	 */
	public void testAtUseDirective_RenameFile() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\nold.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		assertContainsUse("main.p", "new.p");
	}

	/**
	 * @USE директива с несколькими файлами.
	 */
	public void testAtUseDirective_MultipleFiles() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n@USE\nfile1.p\nfile2.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("file1.p", "@file1[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("file2.p", "@file2[]");
		ensureDirectoryExists("lib");

		// Act
		moveFile("file1.p", "lib");

		// Assert
		assertContainsUse("main.p", "lib/file1.p");
		assertContainsUse("main.p", "file2.p");
	}

	// =============================================================================
	// РАЗДЕЛ 2: ОТНОСИТЕЛЬНЫЕ ПУТИ С ../
	// =============================================================================

	/**
	 * Путь уже содержит ../ — перемещение файла-источника.
	 * main.p ссылается на ../lib.p, перемещаем main.p глубже.
	 */
	public void testRelativePathWithDotDot_MoveSourceDeeper() {
		// Arrange: dir/main.p -> ^use[../lib.p]
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main.p", "@main[]\n^use[../lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("dir/inner");

		// Act: перемещаем main.p в dir/inner/
		moveFile("dir/main.p", "dir/inner");

		// Assert: теперь нужно ../../lib.p
		assertContainsUse("dir/inner/main.p", "../../lib.p");
	}

	/**
	 * Путь уже содержит ../ — перемещение файла-источника наверх.
	 */
	public void testRelativePathWithDotDot_MoveSourceUp() {
		// Arrange: dir/inner/main.p -> ^use[../../lib.p]
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/inner/main.p", "@main[]\n^use[../../lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");

		// Act: перемещаем main.p в dir/
		moveFile("dir/inner/main.p", "dir");

		// Assert: теперь нужно ../lib.p
		assertContainsUse("dir/main.p", "../lib.p");
	}

	/**
	 * Путь содержит ../ — перемещение целевого файла.
	 */
	public void testRelativePathWithDotDot_MoveTarget() {
		// Arrange: dir/main.p -> ^use[../lib.p]
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main.p", "@main[]\n^use[../lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("libs");

		// Act: перемещаем lib.p в libs/
		moveFile("lib.p", "libs");

		// Assert: теперь нужно ../libs/lib.p
		assertContainsUse("dir/main.p", "../libs/lib.p");
	}

	/**
	 * Путь с несколькими ../../../
	 */
	public void testRelativePathWithMultipleDotDot() {
		// Arrange: a/b/c/main.p -> ^use[../../../lib.p]
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("a/b/c/main.p", "@main[]\n^use[../../../lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");

		// Act: перемещаем main.p в a/
		moveFile("a/b/c/main.p", "a");

		// Assert: теперь нужно ../lib.p
		assertContainsUse("a/main.p", "../lib.p");
	}

	// =============================================================================
	// РАЗДЕЛ 3: АБСОЛЮТНЫЕ ПУТИ
	// =============================================================================

	/**
	 * Абсолютный путь — перемещение целевого файла.
	 */
	public void testAbsolutePath_MoveTarget() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[/lib/helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib/helper.p", "@helper[]");
		ensureDirectoryExists("newlib");

		// Act
		moveFile("lib/helper.p", "newlib");

		// Assert
		assertContainsUse("main.p", "/newlib/helper.p");
	}

	/**
	 * Абсолютный путь — перемещение файла-источника (путь НЕ должен меняться).
	 */
	public void testAbsolutePath_MoveSource_PathUnchanged() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main.p", "@main[]\n^use[/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("other");

		// Act
		moveFile("dir/main.p", "other");

		// Assert: абсолютный путь остаётся прежним
		assertContainsUse("other/main.p", "/lib.p");
	}

	/**
	 * Абсолютный путь — переименование целевого файла.
	 */
	public void testAbsolutePath_RenameTarget() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[/lib/old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib/old.p", "@old[]");

		// Act
		renameFile("lib/old.p", "new.p");

		// Assert
		assertContainsUse("main.p", "/lib/new.p");
	}

	/**
	 * Абсолютный путь — переименование директории в пути.
	 */
	public void testAbsolutePath_RenameDirectory() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[/oldlib/helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("oldlib/helper.p", "@helper[]");

		// Act
		renameDirectory("oldlib", "newlib");

		// Assert
		assertContainsUse("main.p", "/newlib/helper.p");
	}

	// =============================================================================
	// РАЗДЕЛ 4: НАПРАВЛЕНИЯ ПЕРЕМЕЩЕНИЯ
	// =============================================================================

	/**
	 * Перемещение в поддиректорию (глубже).
	 */
	public void testMoveDirection_Deeper() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("a/b/c");

		// Act
		moveFile("lib.p", "a/b/c");

		// Assert
		assertContainsUse("main.p", "a/b/c/lib.p");
	}

	/**
	 * Перемещение в родительскую директорию (выше).
	 */
	public void testMoveDirection_Higher() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[a/b/c/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("a/b/c/lib.p", "@lib[]");

		// Act
		moveFile("a/b/c/lib.p", "a");

		// Assert
		assertContainsUse("main.p", "a/lib.p");
	}

	/**
	 * Перемещение в соседнюю директорию (вбок).
	 */
	public void testMoveDirection_Sibling() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[dir1/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir1/lib.p", "@lib[]");
		ensureDirectoryExists("dir2");

		// Act
		moveFile("dir1/lib.p", "dir2");

		// Assert
		assertContainsUse("main.p", "dir2/lib.p");
	}

	/**
	 * Перемещение в корень.
	 */
	public void testMoveDirection_ToRoot() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[deep/nested/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("deep/nested/lib.p", "@lib[]");

		// Act
		moveFile("deep/nested/lib.p", ".");

		// Assert
		assertContainsUse("main.p", "lib.p");
	}

	// =============================================================================
	// РАЗДЕЛ 5: ВНУТРЕННИЕ ССЫЛКИ (ссылки внутри перемещаемого файла)
	// =============================================================================

	/**
	 * Внутренняя относительная ссылка — перемещение файла глубже.
	 */
	public void testInternalRef_MoveDeeper() {
		// Arrange: main.p содержит ^use[lib.p]
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("inner");

		// Act: перемещаем main.p в inner/
		moveFile("main.p", "inner");

		// Assert: внутренняя ссылка обновляется на ../lib.p
		assertContainsUse("inner/main.p", "../lib.p");
	}

	/**
	 * Внутренняя относительная ссылка — перемещение файла выше.
	 */
	public void testInternalRef_MoveHigher() {
		// Arrange: a/b/main.p содержит ^use[../../lib.p]
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("a/b/main.p", "@main[]\n^use[../../lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");

		// Act: перемещаем main.p в a/ (на один уровень выше)
		moveFile("a/b/main.p", "a");

		// Assert: внутренняя ссылка обновляется на ../lib.p
		assertContainsUse("a/main.p", "../lib.p");
	}

	/**
	 * Внутренняя абсолютная ссылка — НЕ должна меняться.
	 */
	public void testInternalRef_AbsolutePath_Unchanged() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main.p", "@main[]\n^use[/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("other");

		// Act
		moveFile("dir/main.p", "other");

		// Assert: абсолютный путь остаётся
		assertContainsUse("other/main.p", "/lib.p");
	}

	/**
	 * Несколько внутренних ссылок — все обновляются.
	 */
	public void testInternalRef_Multiple() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[a.p]\n^use[b.p]\n^use[dir/c.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("a.p", "@a[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("b.p", "@b[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/c.p", "@c[]");
		ensureDirectoryExists("inner");

		// Act
		moveFile("main.p", "inner");

		// Assert
		assertContainsUse("inner/main.p", "../a.p");
		assertContainsUse("inner/main.p", "../b.p");
		assertContainsUse("inner/main.p", "../dir/c.p");
	}

	// =============================================================================
	// РАЗДЕЛ 6: ВНЕШНИЕ ССЫЛКИ (другие файлы ссылаются на перемещаемый)
	// =============================================================================

	/**
	 * Несколько файлов ссылаются на перемещаемый файл.
	 */
	public void testExternalRef_Multiple() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main1.p", "@main1[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main2.p", "@main2[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main3.p", "@main3[]\n^use[../lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("libs");

		// Act
		moveFile("lib.p", "libs");

		// Assert
		assertContainsUse("main1.p", "libs/lib.p");
		assertContainsUse("main2.p", "libs/lib.p");
		assertContainsUse("dir/main3.p", "../libs/lib.p");
	}

	/**
	 * Внешняя ссылка из вложенной директории.
	 */
	public void testExternalRef_FromNestedDir() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("a/b/c/main.p", "@main[]\n^use[../../../lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("libs");

		// Act
		moveFile("lib.p", "libs");

		// Assert
		assertContainsUse("a/b/c/main.p", "../../../libs/lib.p");
	}

	// =============================================================================
	// РАЗДЕЛ 7: ПЕРЕИМЕНОВАНИЕ
	// =============================================================================

	/**
	 * Переименование файла — относительный путь.
	 */
	public void testRename_RelativePath() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		assertContainsUse("main.p", "new.p");
	}

	/**
	 * Переименование файла — ссылка с ../
	 */
	public void testRename_PathWithDotDot() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main.p", "@main[]\n^use[../old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		assertContainsUse("dir/main.p", "../new.p");
	}

	/**
	 * Переименование директории — обновляет все пути через неё.
	 */
	public void testRename_Directory() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[olddir/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("other.p", "@other[]\n^use[olddir/helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("olddir/lib.p", "@lib[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("olddir/helper.p", "@helper[]");

		// Act
		renameDirectory("olddir", "newdir");

		// Assert
		assertContainsUse("main.p", "newdir/lib.p");
		assertContainsUse("other.p", "newdir/helper.p");
	}

	/**
	 * Переименование промежуточной директории.
	 */
	public void testRename_IntermediateDirectory() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[a/old/c/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("a/old/c/lib.p", "@lib[]");

		// Act
		renameDirectory("a/old", "new");

		// Assert
		assertContainsUse("main.p", "a/new/c/lib.p");
	}

	// =============================================================================
	// РАЗДЕЛ 8: ОСОБЫЕ СЛУЧАИ
	// =============================================================================

	/**
	 * Кириллические имена файлов.
	 */
	public void testSpecial_CyrillicFileName() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("главный.p", "@главный[]\n^use[библиотека.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("библиотека.p", "@библиотека[]");
		ensureDirectoryExists("либы");

		// Act
		moveFile("библиотека.p", "либы");

		// Assert
		assertContainsUse("главный.p", "либы/библиотека.p");
	}

	/**
	 * Кириллические имена директорий.
	 */
	public void testSpecial_CyrillicDirectoryName() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[старая/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("старая/lib.p", "@lib[]");

		// Act
		renameDirectory("старая", "новая");

		// Assert
		assertContainsUse("main.p", "новая/lib.p");
	}

	/**
	 * Файл с несколькими разными форматами use.
	 */
	public void testSpecial_MixedUseFormats() {
		// Arrange: ^use[] и @USE в одном файле
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[lib1.p]\n@USE\nlib2.p");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib1.p", "@lib1[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib2.p", "@lib2[]");
		ensureDirectoryExists("libs");

		// Act
		moveFile("lib1.p", "libs");
		moveFile("lib2.p", "libs");

		// Assert
		assertContainsUse("main.p", "libs/lib1.p");
		assertContainsUse("main.p", "libs/lib2.p");
	}

	/**
	 * Одновременно внутренние и внешние ссылки.
	 */
	public void testSpecial_BothInternalAndExternal() {
		// Arrange:
		// - main.p ссылается на helper.p
		// - helper.p ссылается на lib.p
		// Перемещаем helper.p
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("helper.p", "@helper[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("inner");

		// Act
		moveFile("helper.p", "inner");

		// Assert:
		// - внешняя ссылка в main.p обновляется
		// - внутренняя ссылка в helper.p обновляется
		assertContainsUse("main.p", "inner/helper.p");
		assertContainsUse("inner/helper.p", "../lib.p");
	}

	/**
	 * Путь к файлу в той же директории через ./
	 */
	public void testSpecial_ExplicitCurrentDir() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[./lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("dir");

		// Act
		moveFile("lib.p", "dir");

		// Assert
		assertContainsUse("main.p", "dir/lib.p");
	}

	// =============================================================================
	// РАЗДЕЛ 9: DOCUMENT_ROOT
	// =============================================================================

	/**
	 * document_root не установлен (null) — относительные пути работают от файла.
	 */
	public void testDocumentRoot_NotSet() throws Exception {
		// Arrange: сбрасываем document_root
		setDocumentRoot(null);

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("dir");

		// Act
		moveFile("lib.p", "dir");

		// Assert
		assertContainsUse("main.p", "dir/lib.p");
	}

	/**
	 * document_root в поддиректории — абсолютные пути относительно него.
	 */
	public void testDocumentRoot_InSubdirectory() throws Exception {
		// Arrange: устанавливаем document_root в поддиректорию
		ensureDirectoryExists("www");
		setDocumentRoot("www");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("www/main.p", "@main[]\n^use[/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("www/lib.p", "@lib[]");
		ensureDirectoryExists("www/dir");

		// Act
		moveFile("www/lib.p", "www/dir");

		// Assert: абсолютный путь обновляется относительно document_root
		assertContainsUse("www/main.p", "/dir/lib.p");
	}

	/**
	 * document_root — перемещение файла за пределы document_root.
	 * Абсолютный путь должен перестать работать.
	 */
	public void testDocumentRoot_MoveOutside() throws Exception {
		// Arrange
		ensureDirectoryExists("www");
		ensureDirectoryExists("outside");
		setDocumentRoot("www");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("www/main.p", "@main[]\n^use[/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("www/lib.p", "@lib[]");

		// Act: перемещаем файл за пределы document_root
		moveFile("www/lib.p", "outside");

		// Assert: путь должен измениться на относительный (так как /lib.p больше не резолвится)
		// или должно появиться предупреждение
		String content = getFileContent("www/main.p");
		// Проверяем что файл перемещён
		assertFileExists("outside/lib.p");
		assertFileNotExists("www/lib.p");
	}

	// =============================================================================
	// РАЗДЕЛ 10: CLASS_PATH
	// =============================================================================

	/**
	 * Путь резолвится через CLASS_PATH — переименование файла.
	 */
	public void testClassPath_RenameFile() {
		// Arrange: CLASS_PATH указывает на libs/
		createFileWithClassPath("auto.p", "libs/", "");
		setMainAuto("auto.p");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("libs/helper.p", "@helper[]");

		// Act: переименовываем файл в CLASS_PATH директории
		renameFile("libs/helper.p", "utils.p");

		// Assert: путь обновляется
		assertContainsUse("main.p", "utils.p");
		assertFileExists("libs/utils.p");
	}

	/**
	 * CLASS_PATH — перемещение файла внутри CLASS_PATH директории.
	 */
	public void testClassPath_MoveInsideClassPath() {
		// Arrange
		createFileWithClassPath("auto.p", "libs/", "");
		setMainAuto("auto.p");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("libs/helper.p", "@helper[]");
		ensureDirectoryExists("libs/sub");

		// Act: перемещаем внутри CLASS_PATH
		moveFile("libs/helper.p", "libs/sub");

		// Assert: путь обновляется (теперь sub/helper.p относительно CLASS_PATH)
		assertContainsUse("main.p", "sub/helper.p");
	}

	/**
	 * CLASS_PATH — перемещение файла из CLASS_PATH директории наружу.
	 * Путь должен измениться на относительный.
	 */
	public void testClassPath_MoveOutsideClassPath() {
		// Arrange
		createFileWithClassPath("auto.p", "libs/", "");
		setMainAuto("auto.p");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("libs/helper.p", "@helper[]");
		ensureDirectoryExists("other");

		// Act: перемещаем за пределы CLASS_PATH
		moveFile("libs/helper.p", "other");

		// Assert: путь меняется на относительный от main.p
		assertContainsUse("main.p", "other/helper.p");
	}

	/**
	 * Несколько директорий в CLASS_PATH — файл перемещается в другую CLASS_PATH директорию.
	 * Путь обновляется чтобы указывать на новое расположение.
	 */
	public void testClassPath_MultipleClassPaths_MoveBetween() {
		// Arrange: CLASS_PATH содержит две директории
		createFileWithClassPath("auto.p", "libs1/;libs2/", "");
		setMainAuto("auto.p");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("libs1/helper.p", "@helper[]");
		ensureDirectoryExists("libs2");

		// Act: перемещаем из libs1 в libs2
		moveFile("libs1/helper.p", "libs2");

		// Assert: путь обновляется на libs2/helper.p
		// (или остаётся helper.p если резолвер находит файл в libs2)
		// Текущее поведение: обновляется на libs2/helper.p
		assertContainsUse("main.p", "libs2/helper.p");
	}

	/**
	 * CLASS_PATH — конфликт: файл переименовывается, в другом CLASS_PATH есть файл с таким же именем.
	 * Путь обновляется на новое имя файла, чтобы избежать конфликта.
	 */
	public void testClassPath_Conflict_SameNameInAnotherPath() {
		// Arrange: CLASS_PATH содержит две директории, в обеих есть helper.p
		createFileWithClassPath("auto.p", "libs1/;libs2/", "");
		setMainAuto("auto.p");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("libs1/helper.p", "@helper1[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("libs2/helper.p", "@helper2[]");  // Конфликтующий файл

		// Act: переименовываем файл в libs1
		renameFile("libs1/helper.p", "utils.p");

		// Assert:
		// - Файл переименован
		assertFileExists("libs1/utils.p");
		// - Путь обновлён на utils.p (чтобы не указывать на libs2/helper.p)
		assertContainsUse("main.p", "utils.p");
	}

	/**
	 * CLASS_PATH — переименование директории CLASS_PATH.
	 * Путь в use должен остаться прежним (helper.p), потому что это CLASS_PATH путь.
	 * CLASS_PATH теперь указывает на несуществующую директорию — это проблема конфигурации.
	 */
	public void testClassPath_RenameClassPathDirectory() {
		// Arrange
		createFileWithClassPath("auto.p", "libs/", "");
		setMainAuto("auto.p");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("libs/helper.p", "@helper[]");

		// Act: переименовываем директорию CLASS_PATH
		renameDirectory("libs", "libraries");

		// Assert: путь в use остаётся helper.p (это CLASS_PATH путь, не относительный)
		// CLASS_PATH теперь сломан (указывает на libs/ которого нет), но use путь не меняется
		assertContainsUse("main.p", "helper.p");
	}

	// =============================================================================
	// РАЗДЕЛ 11: ПЕРЕМЕЩЕНИЕ В КОРЕНЬ (edge case)
	// =============================================================================

	/**
	 * Перемещение файла в корень — путь должен упроститься.
	 * KNOWN ISSUE: путь ../lib.p не упрощается до lib.p при перемещении в корень.
	 * TODO: исправить — нужно нормализовать пути после вычисления.
	 */
	public void testMoveToRoot_SimplifiesPath() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main.p", "@main[]\n^use[../lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");

		// Act: перемещаем main.p в корень
		moveFile("dir/main.p", ".");

		// Assert:
		// ТЕКУЩЕЕ ПОВЕДЕНИЕ: путь остаётся ../lib.p (не упрощается)
		// ОЖИДАЕМОЕ: путь должен стать lib.p
		assertContainsUse("main.p", "../lib.p");
		// TODO: исправить — добавить нормализацию путей
	}

	/**
	 * Внешняя ссылка — целевой файл перемещается в корень.
	 */
	public void testMoveToRoot_ExternalRef() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[dir/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/lib.p", "@lib[]");

		// Act
		moveFile("dir/lib.p", ".");

		// Assert
		assertContainsUse("main.p", "lib.p");
	}

	// =============================================================================
	// РАЗДЕЛ 12: СЛОМАННЫЕ ССЫЛКИ (предупреждения)
	// =============================================================================

	/**
	 * Абсолютный путь — файл перемещается за пределы document_root.
	 * Путь обновляется на /../outside/lib.p (выход за document_root).
	 */
	public void testBrokenRef_AbsolutePath_MovedOutsideDocRoot() throws Exception {
		// Arrange
		ensureDirectoryExists("www");
		ensureDirectoryExists("outside");
		setDocumentRoot("www");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("www/main.p", "@main[]\n^use[/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("www/lib.p", "@lib[]");

		// Act: перемещаем файл за пределы document_root
		moveFile("www/lib.p", "outside");

		// Assert: файл перемещён
		assertFileExists("outside/lib.p");
		assertFileNotExists("www/lib.p");

		// Путь обновляется на /../outside/lib.p (выход за document_root)
		assertContainsUse("www/main.p", "/../outside/lib.p");
	}

	/**
	 * CLASS_PATH путь — файл перемещается за пределы всех CLASS_PATH директорий.
	 * Путь обновляется на относительный.
	 */
	public void testBrokenRef_ClassPath_MovedOutsideAllClassPaths() {
		// Arrange
		createFileWithClassPath("auto.p", "libs/", "");
		setMainAuto("auto.p");
		ensureDirectoryExists("other");

		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[helper.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("libs/helper.p", "@helper[]");

		// Act: перемещаем за пределы CLASS_PATH
		moveFile("libs/helper.p", "other");

		// Assert: путь обновился на относительный
		assertContainsUse("main.p", "other/helper.p");
	}

	/**
	 * Относительный путь — целевой файл удаляется.
	 * Ссылка становится битой.
	 *
	 * NOTE: Safe Delete должен показать preview со всеми использованиями.
	 */
	public void testBrokenRef_FileDeleted() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");

		// Act: удаляем файл
		deleteFile("lib.p");

		// Assert: файл удалён, ссылка осталась (битая)
		assertFileNotExists("lib.p");
		assertContainsUse("main.p", "lib.p"); // Путь не изменился
	}

	/**
	 * Директория удаляется — все ссылки на файлы внутри становятся битыми.
	 */
	public void testBrokenRef_DirectoryDeleted() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[lib/helper.p]\n^use[lib/utils.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib/helper.p", "@helper[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib/utils.p", "@utils[]");

		// Act: удаляем директорию
		deleteDirectory("lib");

		// Assert: директория удалена, ссылки остались (битые)
		assertFileNotExists("lib/helper.p");
		assertFileNotExists("lib/utils.p");
		assertContainsUse("main.p", "lib/helper.p");
		assertContainsUse("main.p", "lib/utils.p");
	}

	// =============================================================================
	// РАЗДЕЛ 13: УВЕДОМЛЕНИЯ
	// =============================================================================

	/**
	 * Успешное переименование файла — показывается уведомление.
	 */
	public void testNotification_SuccessfulRename() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		assertContainsUse("main.p", "new.p");
		assertSuccessNotificationShown();
	}

	/**
	 * Успешное перемещение файла — показывается уведомление.
	 */
	public void testNotification_SuccessfulMove() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("dir");

		// Act
		moveFile("lib.p", "dir");

		// Assert
		assertContainsUse("main.p", "dir/lib.p");
		assertSuccessNotificationShown();
	}

	/**
	 * Несколько путей обновлено — одно уведомление.
	 */
	public void testNotification_MultiplePaths() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main1.p", "@main1[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main2.p", "@main2[]\n^use[lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");

		// Act
		renameFile("lib.p", "utils.p");

		// Assert
		assertContainsUse("main1.p", "utils.p");
		assertContainsUse("main2.p", "utils.p");
		// Должно быть одно уведомление (не два)
		assertEquals("Должно быть одно уведомление", 1, getSuccessNotificationCount());
	}

	/**
	 * Путь не изменился — уведомление НЕ показывается.
	 */
	public void testNotification_NoChangeNoNotification() {
		// Arrange: файл с абсолютным путём
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[/lib.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib.p", "@lib[]");
		ensureDirectoryExists("dir");

		// Act: перемещаем main.p (не lib.p) — абсолютный путь НЕ меняется
		moveFile("main.p", "dir");

		// Assert
		assertContainsUse("dir/main.p", "/lib.p"); // Путь не изменился
		assertNoSuccessNotification();
	}
}
