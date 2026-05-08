package ru.artlebedev.parser3.refactoring;

/**
 * Тесты рефакторинга USE путей: перемещение и переименование директорий.
 *
 * Группа 5: Операции с директориями.
 */
public class P3UseRefactoringDirectoryTest extends P3UseRefactoringTestBase {

    // ==================== Тест 5.1: Перемещение директории ====================

    /**
     * Структура:
     *   /main.p           содержит: ^use[lib/a.p]
     *   /lib/a.p
     *   /lib/b.p
     *
     * Действие: Переместить директорию lib в libs/lib
     *
     * Ожидание:
     *   /main.p           содержит: ^use[libs/lib/a.p]
     */
    public void testMoveDirectory() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[lib/a.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib/a.p", "@a[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib/b.p", "@b[]");
        ensureDirectoryExists("libs");

        // Act
        moveDirectory("lib", "libs");

        // Assert
        assertFileExists("libs/lib/a.p");
        assertContainsUse("main.p", "libs/lib/a.p");
    }

    // ==================== Тест 5.2: Переименование директории ====================

    /**
     * Структура:
     *   /main.p           содержит: ^use[old_dir/file.p]
     *   /old_dir/file.p
     *
     * Действие: Переименовать old_dir в new_dir
     *
     * Ожидание:
     *   /main.p           содержит: ^use[new_dir/file.p]
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
        assertFileNotExists("old_dir/file.p");
        assertContainsUse("main.p", "new_dir/file.p");
    }

    // ==================== Дополнительные тесты ====================

    /**
     * Переименование вложенной директории.
     */
    public void testRenameNestedDirectory() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[a/b/c/file.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("a/b/c/file.p", "@file[]");

        // Act
        renameDirectory("a/b/c", "renamed");

        // Assert
        assertFileExists("a/b/renamed/file.p");
        assertContainsUse("main.p", "a/b/renamed/file.p");
    }

    /**
     * Переименование директории — несколько ссылок на разные файлы внутри.
     */
    public void testRenameDirectoryMultipleFiles() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[lib/a.p]\n^use[lib/b.p]\n^use[lib/sub/c.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib/a.p", "@a[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib/b.p", "@b[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib/sub/c.p", "@c[]");

        // Act
        renameDirectory("lib", "libs");

        // Assert
        assertContainsUse("main.p", "libs/a.p");
        assertContainsUse("main.p", "libs/b.p");
        assertContainsUse("main.p", "libs/sub/c.p");
    }

    /**
     * Перемещение директории с вложенными поддиректориями.
     */
    public void testMoveDirectoryWithSubdirs() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[old/sub1/a.p]\n^use[old/sub2/b.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("old/sub1/a.p", "@a[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("old/sub2/b.p", "@b[]");
        ensureDirectoryExists("new_location");

        // Act
        moveDirectory("old", "new_location");

        // Assert
        assertFileExists("new_location/old/sub1/a.p");
        assertFileExists("new_location/old/sub2/b.p");
        assertContainsUse("main.p", "new_location/old/sub1/a.p");
        assertContainsUse("main.p", "new_location/old/sub2/b.p");
    }

    /**
     * Переименование директории с абсолютными путями.
     */
    public void testRenameDirectoryAbsolutePaths() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[/old/file.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("old/file.p", "@file[]");

        // Act
        renameDirectory("old", "new");

        // Assert
        assertContainsUse("main.p", "/new/file.p");
    }

    /**
     * Переименование директории — ссылка из файла внутри директории на внешний файл.
     */
    public void testRenameDirectoryInternalRefsToExternal() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("external.p", "@external[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir/main.p", "@main[]\n^use[../external.p]");

        // Act
        renameDirectory("dir", "renamed");

        // Assert
        // Путь ../external.p должен остаться — он всё ещё работает
        assertContainsUse("renamed/main.p", "../external.p");
    }

    /**
     * Переименование директории — ссылка из внешнего файла на файл в директории.
     */
    public void testRenameDirectoryExternalRefsToInternal() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[dir/inner.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir/inner.p", "@inner[]");

        // Act
        renameDirectory("dir", "folder");

        // Assert
        assertContainsUse("main.p", "folder/inner.p");
    }

    /**
     * Перемещение директории в глубокую вложенность.
     */
    public void testMoveDirectoryToDeepNesting() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[lib/file.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib/file.p", "@file[]");
        ensureDirectoryExists("a/b/c");

        // Act
        moveDirectory("lib", "a/b/c");

        // Assert
        assertFileExists("a/b/c/lib/file.p");
        assertContainsUse("main.p", "a/b/c/lib/file.p");
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
        assertContainsUse("main.p", "либы/модуль.p");
    }

    /**
     * Переименование промежуточной директории в пути.
     */
    public void testRenameIntermediateDirectory() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[a/b/c/file.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("a/b/c/file.p", "@file[]");

        // Act
        renameDirectory("a/b", "renamed_b");

        // Assert
        assertContainsUse("main.p", "a/renamed_b/c/file.p");
    }
}
