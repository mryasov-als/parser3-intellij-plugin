package ru.artlebedev.parser3.refactoring;

/**
 * Тесты рефакторинга USE путей: граничные случаи.
 *
 * Группа 6: Особые случаи, fallback-логика для ../, циклические ссылки и т.д.
 */
public class P3UseRefactoringEdgeCasesTest extends P3UseRefactoringTestBase {

    // ==================== Тест 6.1: Файл без ссылок ====================

    /**
     * Структура:
     *   /file.p
     *
     * Действие: Переместить file.p в inner/
     *
     * Ожидание: Ошибок нет, файл перемещён
     */
    public void testFileWithNoReferences() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("file.p", "@file[]");
        ensureDirectoryExists("inner");

        // Act
        moveFile("file.p", "inner");

        // Assert
        assertFileExists("inner/file.p");
        assertFileNotExists("file.p");
    }

    // ==================== Тест 6.2: Циклические ссылки ====================

    /**
     * Структура:
     *   /a.p              содержит: ^use[b.p]
     *   /b.p              содержит: ^use[a.p]
     *
     * Действие: Переместить a.p в inner/
     *
     * Ожидание:
     *   /inner/a.p        содержит: ^use[../b.p]
     *   /b.p              содержит: ^use[inner/a.p]
     */
    public void testCyclicReferences() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("a.p", "@a[]\n^use[b.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("b.p", "@b[]\n^use[a.p]");
        ensureDirectoryExists("inner");

        // Act
        moveFile("a.p", "inner");

        // Assert
        assertContainsUse("inner/a.p", "../b.p");
        assertContainsUse("b.p", "inner/a.p");
    }

    // ==================== Тест 6.3: Глубокая вложенность — выход наверх ====================

    /**
     * Структура:
     *   /a/b/c/d/main.p   содержит: ^use[../../../../lib.p]
     *   /lib.p
     *
     * Действие: Переместить main.p в /x/
     *
     * Ожидание:
     *   /x/main.p         содержит: ^use[../lib.p]
     */
    public void testDeepNesting() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("a/b/c/d/main.p", "@main[]\n^use[../../../../lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib.p", "@lib[]");
        ensureDirectoryExists("x");

        // Act
        moveFile("a/b/c/d/main.p", "x");

        // Assert
        assertContainsUse("x/main.p", "../lib.p");
    }

    // ==================== Тест 6.4: Абсолютный путь не меняется при перемещении источника ====================

    /**
     * Структура:
     *   /dir1/main.p      содержит: ^use[/lib.p]
     *   /lib.p
     *
     * Действие: Переместить main.p в /dir2/
     *
     * Ожидание:
     *   /dir2/main.p      содержит: ^use[/lib.p] (без изменений)
     */
    public void testAbsolutePathNotChangedOnSourceMove() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/main.p", "@main[]\n^use[/lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib.p", "@lib[]");
        ensureDirectoryExists("dir2");

        // Act
        moveFile("dir1/main.p", "dir2");

        // Assert
        assertContainsUse("dir2/main.p", "/lib.p");
    }

    // ==================== Тест 6.5: @USE директива ====================

    /**
     * Структура:
     *   /main.p           содержит: @USE helper.p
     *   /helper.p
     *
     * Действие: Переместить helper.p в inner/
     *
     * Ожидание:
     *   /main.p           содержит: @USE inner/helper.p
     */
    public void testAtUseDirective() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@USE\nhelper.p\n@main[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("helper.p", "@helper[]");
        ensureDirectoryExists("inner");

        // Act
        moveFile("helper.p", "inner");

        // Assert
        String content = getFileContent("main.p");
        assertTrue("Должен содержать inner/helper.p",
                content.contains("inner/helper.p"));
    }

    // ==================== Fallback тесты для ../ ====================

    /**
     * Fallback: файл в родительской директории существует.
     *
     * Структура:
     *   /www/file.p           существует
     *   /www/inner/file.p     существует
     *   /www/inner/auto.p     содержит: ^use[../file.p]
     *
     * Ссылка резолвится на /www/file.p (родительский).
     *
     * Действие: Переименовать /www/inner/file.p → other.p
     *
     * Ожидание: ^use[../file.p] НЕ меняется (указывает на www/file.p)
     */
    public void testFallback_ParentExists_RenameLocalFile() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/file.p", "@parent_file[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/file.p", "@local_file[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/auto.p", "@auto[]\n^use[../file.p]");

        // Act
        renameFile("www/inner/file.p", "other.p");

        // Assert
        // Путь ../file.p НЕ должен измениться — он указывает на www/file.p
        assertContainsUse("www/inner/auto.p", "../file.p");
        assertFileExists("www/inner/other.p");
    }

    /**
     * Fallback: файл в родительской директории НЕ существует — резолв через fallback.
     *
     * Структура:
     *   /www/inner/file.p     существует
     *   /www/inner/auto.p     содержит: ^use[../file.p]
     *   (www/file.p НЕ существует)
     *
     * Ссылка резолвится на /www/inner/file.p через fallback.
     *
     * Действие: Переименовать /www/inner/file.p → other.p
     *
     * Ожидание: ^use[../file.p] → ^use[../other.p] (сохраняем формат с ../)
     */
    public void testFallback_ParentNotExists_RenameLocalFile() {
        // Arrange
        // НЕ создаём www/file.p — только локальный файл
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/file.p", "@local_file[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/auto.p", "@auto[]\n^use[../file.p]");

        // Act
        renameFile("www/inner/file.p", "other.p");

        // Assert
        // Путь должен обновиться с сохранением ../
        assertContainsUse("www/inner/auto.p", "../other.p");
        assertFileExists("www/inner/other.p");
    }

    /**
     * Fallback: оба файла существуют — переименование родительского.
     *
     * Структура:
     *   /www/file.p           существует
     *   /www/inner/file.p     существует
     *   /www/inner/auto.p     содержит: ^use[../file.p]
     *
     * Ссылка резолвится на /www/file.p.
     *
     * Действие: Переименовать /www/file.p → renamed.p
     *
     * Ожидание: ^use[../file.p] → ^use[../renamed.p]
     */
    public void testFallback_ParentExists_RenameParentFile() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/file.p", "@parent_file[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/file.p", "@local_file[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/auto.p", "@auto[]\n^use[../file.p]");

        // Act
        renameFile("www/file.p", "renamed.p");

        // Assert
        assertContainsUse("www/inner/auto.p", "../renamed.p");
        assertFileExists("www/renamed.p");
        // Локальный файл не тронут
        assertFileExists("www/inner/file.p");
    }

    /**
     * Fallback: перемещение локального файла при наличии родительского.
     *
     * Структура:
     *   /www/file.p           существует
     *   /www/inner/file.p     существует
     *   /www/inner/auto.p     содержит: ^use[../file.p]
     *
     * Действие: Переместить /www/inner/file.p в /www/other/
     *
     * Ожидание: ^use[../file.p] НЕ меняется (указывает на www/file.p)
     */
    public void testFallback_ParentExists_MoveLocalFile() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/file.p", "@parent_file[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/file.p", "@local_file[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/auto.p", "@auto[]\n^use[../file.p]");
        ensureDirectoryExists("www/other");

        // Act
        moveFile("www/inner/file.p", "www/other");

        // Assert
        // Путь НЕ должен измениться
        assertContainsUse("www/inner/auto.p", "../file.p");
        assertFileExists("www/other/file.p");
    }

    /**
     * Fallback: перемещение файла при fallback-резолве.
     *
     * Структура:
     *   /www/inner/file.p     существует
     *   /www/inner/auto.p     содержит: ^use[../file.p]
     *   (www/file.p НЕ существует)
     *
     * Действие: Переместить /www/inner/file.p в /www/other/
     *
     * Ожидание: ^use[../file.p] должен обновиться (файл больше не в inner/)
     */
    public void testFallback_ParentNotExists_MoveLocalFile() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/file.p", "@local_file[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("www/inner/auto.p", "@auto[]\n^use[../file.p]");
        ensureDirectoryExists("www/other");

        // Act
        moveFile("www/inner/file.p", "www/other");

        // Assert
        // Путь должен обновиться — файл переместился
        assertContainsUse("www/inner/auto.p", "../other/file.p");
    }

    // ==================== Другие граничные случаи ====================

    /**
     * Файл с множественными use на одной строке (маловероятно, но возможно).
     */
    public void testMultipleUsesOnSameLine() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n$x[^use[a.p]]$y[^use[b.p]]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("a.p", "@a[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("b.p", "@b[]");
        ensureDirectoryExists("inner");

        // Act
        moveFile("a.p", "inner");

        // Assert
        assertContainsUse("main.p", "inner/a.p");
        assertContainsUse("main.p", "b.p");
    }

    /**
     * Пустой путь в use (граничный случай).
     */
    public void testEmptyPath() {
        // Arrange — файл с "нормальной" ссылкой
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib.p", "@lib[]");

        // Act
        renameFile("lib.p", "library.p");

        // Assert
        assertContainsUse("main.p", "library.p");
    }

    /**
     * Путь с точками в имени файла.
     */
    public void testDotsInFileName() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[my.module.v2.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("my.module.v2.p", "@module[]");
        ensureDirectoryExists("lib");

        // Act
        moveFile("my.module.v2.p", "lib");

        // Assert
        assertContainsUse("main.p", "lib/my.module.v2.p");
    }

    /**
     * Перемещение в ту же директорию (no-op).
     */
    public void testMoveToSameDirectory() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir/main.p", "@main[]\n^use[lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir/lib.p", "@lib[]");

        // Act
        moveFile("dir/lib.p", "dir");

        // Assert
        // Файл остался на месте, путь не изменился
        assertFileExists("dir/lib.p");
        assertContainsUse("dir/main.p", "lib.p");
    }

    /**
     * Несколько файлов с одинаковым именем в разных директориях.
     */
    public void testSameNameDifferentDirectories() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[dir1/lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("other.p", "@other[]\n^use[dir2/lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/lib.p", "@lib1[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir2/lib.p", "@lib2[]");

        // Act
        renameFile("dir1/lib.p", "renamed.p");

        // Assert
        assertContainsUse("main.p", "dir1/renamed.p");
        assertContainsUse("other.p", "dir2/lib.p");  // Не изменился
    }
}
