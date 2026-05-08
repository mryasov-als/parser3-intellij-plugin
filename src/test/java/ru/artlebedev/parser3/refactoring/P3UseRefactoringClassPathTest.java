package ru.artlebedev.parser3.refactoring;

/**
 * Тесты переименования файлов и директорий.
 *
 * Используют myFixture.renameElement() который работает корректно в light-тестах.
 */
public class P3UseRefactoringClassPathTest extends P3UseRefactoringTestBase {

	// ==================== Тесты переименования файлов ====================

	/**
	 * Простое переименование файла в той же директории.
	 */
	public void testRenameFileSimple() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old.p", "@old[]");

		// Act
		renameFile("old.p", "new.p");

		// Assert
		assertFileExists("new.p");
		assertFileNotExists("old.p");
		assertContainsUse("main.p", "new.p");
	}

	/**
	 * Переименование файла в поддиректории.
	 */
	public void testRenameFileInSubdirectory() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[dir/old.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/old.p", "@old[]");

		// Act
		renameFile("dir/old.p", "new.p");

		// Assert
		assertFileExists("dir/new.p");
		assertContainsUse("main.p", "dir/new.p");
	}

	/**
	 * Переименование файла — несколько ссылок на него.
	 */
	public void testRenameFileMultipleReferences() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main1.p", "@main1[]\n^use[target.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main2.p", "@main2[]\n^use[target.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("dir/main3.p", "@main3[]\n^use[../target.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("target.p", "@target[]");

		// Act
		renameFile("target.p", "renamed.p");

		// Assert
		assertFileExists("renamed.p");
		assertContainsUse("main1.p", "renamed.p");
		assertContainsUse("main2.p", "renamed.p");
		assertContainsUse("dir/main3.p", "../renamed.p");
	}

	// ==================== Тесты переименования директорий ====================

	/**
	 * Простое переименование директории.
	 */
	public void testRenameDirectory() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[old_dir/file.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("old_dir/file.p", "@file[]");

		// Act
		renameDirectory("old_dir", "new_dir");

		// Assert
		assertFileExists("new_dir/file.p");
		assertContainsUse("main.p", "new_dir/file.p");
	}

	/**
	 * Переименование вложенной директории.
	 */
	public void testRenameNestedDirectory() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[a/b/old/file.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("a/b/old/file.p", "@file[]");

		// Act
		renameDirectory("a/b/old", "new");

		// Assert
		assertFileExists("a/b/new/file.p");
		assertContainsUse("main.p", "a/b/new/file.p");
	}

	/**
	 * Переименование директории — несколько файлов внутри.
	 */
	public void testRenameDirectoryWithMultipleFiles() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[lib/a.p]\n^use[lib/b.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib/a.p", "@a[]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("lib/b.p", "@b[]");

		// Act
		renameDirectory("lib", "libs");

		// Assert
		assertFileExists("libs/a.p");
		assertFileExists("libs/b.p");
		assertContainsUse("main.p", "libs/a.p");
		assertContainsUse("main.p", "libs/b.p");
	}

	/**
	 * Переименование директории с кириллическим именем.
	 */
	public void testRenameDirectoryCyrillic() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[библиотека/модуль.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("библиотека/модуль.p", "@модуль[]");

		// Act
		renameDirectory("библиотека", "либы");

		// Assert
		assertFileExists("либы/модуль.p");
		assertContainsUse("main.p", "либы/модуль.p");
	}

	/**
	 * Переименование промежуточной директории в пути.
	 */
	public void testRenameIntermediateDirectory() {
		// Arrange
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("main.p", "@main[]\n^use[a/old/c/file.p]");
		// реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
		createParser3FileInDir("a/old/c/file.p", "@file[]");

		// Act
		renameDirectory("a/old", "new");

		// Assert
		assertFileExists("a/new/c/file.p");
		assertContainsUse("main.p", "a/new/c/file.p");
	}
}
