package ru.artlebedev.parser3.refactoring;

/**
 * Тесты рефакторинга USE путей: базовое перемещение файла.
 *
 * Группа 1: Перемещение/переименование файла и обновление ссылок НА него.
 */
public class P3UseRefactoringMoveTest extends P3UseRefactoringTestBase {

    // ==================== Тест 1.1: Перемещение файла с относительной ссылкой ====================

    /**
     * Структура:
     *   /dir1/main.p      содержит: ^use[helper.p]
     *   /dir1/helper.p
     *
     * Действие: Переместить helper.p в /dir2/helper.p
     *
     * Ожидание:
     *   /dir1/main.p      содержит: ^use[../dir2/helper.p]
     */
    public void testMoveFileWithRelativeReference() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/main.p", "@main[]\n^use[helper.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/helper.p", "@helper[]");
        ensureDirectoryExists("dir2");

        // Act
        moveFile("dir1/helper.p", "dir2");

        // Assert
        assertFileExists("dir2/helper.p");
        assertFileNotExists("dir1/helper.p");
        assertContainsUse("dir1/main.p", "../dir2/helper.p");
    }

    // ==================== Тест 1.2: Перемещение файла с абсолютной ссылкой ====================

    /**
     * Структура:
     *   /dir1/main.p      содержит: ^use[/dir1/helper.p]
     *   /dir1/helper.p
     *
     * Действие: Переместить helper.p в /dir2/helper.p
     *
     * Ожидание:
     *   /dir1/main.p      содержит: ^use[/dir2/helper.p]
     */
    public void testMoveFileWithAbsoluteReference() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/main.p", "@main[]\n^use[/dir1/helper.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/helper.p", "@helper[]");
        ensureDirectoryExists("dir2");

        // Act
        moveFile("dir1/helper.p", "dir2");

        // Assert
        assertFileExists("dir2/helper.p");
        assertContainsUse("dir1/main.p", "/dir2/helper.p");
    }

    // ==================== Тест 1.3: Переименование файла ====================

    /**
     * Структура:
     *   /dir1/main.p      содержит: ^use[old.p]
     *   /dir1/old.p
     *
     * Действие: Переименовать old.p в new.p
     *
     * Ожидание:
     *   /dir1/main.p      содержит: ^use[new.p]
     */
    public void testRenameFile() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/main.p", "@main[]\n^use[old.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/old.p", "@old[]");

        // Act
        renameFile("dir1/old.p", "new.p");

        // Assert
        assertFileExists("dir1/new.p");
        assertFileNotExists("dir1/old.p");
        assertContainsUse("dir1/main.p", "new.p");
    }

    // ==================== Тест 1.4: Несколько ссылок на один файл ====================

    /**
     * Структура:
     *   /main.p           содержит: ^use[lib.p]
     *   /dir/other.p      содержит: ^use[../lib.p]
     *   /lib.p
     *
     * Действие: Переместить lib.p в /libs/lib.p
     *
     * Ожидание:
     *   /main.p           содержит: ^use[libs/lib.p]
     *   /dir/other.p      содержит: ^use[../libs/lib.p]
     */
    public void testMoveFileWithMultipleReferences() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir/other.p", "@other[]\n^use[../lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib.p", "@lib[]");
        ensureDirectoryExists("libs");

        // Act
        moveFile("lib.p", "libs");

        // Assert
        assertFileExists("libs/lib.p");
        assertFileNotExists("lib.p");
        assertContainsUse("main.p", "libs/lib.p");
        assertContainsUse("dir/other.p", "../libs/lib.p");
    }

    // ==================== Дополнительные тесты ====================

    /**
     * Перемещение файла в глубокую вложенность.
     */
    public void testMoveFileToDeepNesting() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib.p", "@lib[]");
        ensureDirectoryExists("a/b/c");

        // Act
        moveFile("lib.p", "a/b/c");

        // Assert
        assertFileExists("a/b/c/lib.p");
        assertContainsUse("main.p", "a/b/c/lib.p");
    }

    /**
     * Перемещение файла из глубокой вложенности наверх.
     */
    public void testMoveFileFromDeepNesting() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[a/b/c/lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("a/b/c/lib.p", "@lib[]");

        // Act
        moveFile("a/b/c/lib.p", ".");

        // Assert
        assertFileExists("lib.p");
        assertContainsUse("main.p", "lib.p");
    }
}
