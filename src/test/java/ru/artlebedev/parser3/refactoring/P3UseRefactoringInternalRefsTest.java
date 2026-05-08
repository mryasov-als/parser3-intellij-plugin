package ru.artlebedev.parser3.refactoring;

/**
 * Тесты рефакторинга USE путей: ссылки внутри перемещаемого файла.
 *
 * Группа 2: Перемещение файла и обновление ссылок ВНУТРИ него.
 */
public class P3UseRefactoringInternalRefsTest extends P3UseRefactoringTestBase {

    // ==================== Тест 2.1: Перемещение файла с внутренними ссылками ====================

    /**
     * Структура:
     *   /auto.p           содержит: ^use[helper.p]
     *   /helper.p         содержит: ^use[lib.p]
     *   /lib.p
     *
     * Действие: Переместить helper.p в /inner/helper.p
     *
     * Ожидание:
     *   /auto.p           содержит: ^use[inner/helper.p]
     *   /inner/helper.p   содержит: ^use[../lib.p]
     */
    public void testMoveFileUpdatesInternalReferences() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("auto.p", "@auto[]\n^use[helper.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("helper.p", "@helper[]\n^use[lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib.p", "@lib[]");
        ensureDirectoryExists("inner");

        // Act
        moveFile("helper.p", "inner");

        // Assert
        assertContainsUse("auto.p", "inner/helper.p");
        assertContainsUse("inner/helper.p", "../lib.p");
    }

    // ==================== Тест 2.2: Несколько внутренних ссылок ====================

    /**
     * Структура:
     *   /helper.p         содержит: ^use[a.p] и ^use[b.p]
     *   /a.p
     *   /b.p
     *
     * Действие: Переместить helper.p в /inner/helper.p
     *
     * Ожидание:
     *   /inner/helper.p   содержит: ^use[../a.p] и ^use[../b.p]
     */
    public void testMoveFileMultipleInternalRefs() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("helper.p", "@helper[]\n^use[a.p]\n^use[b.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("a.p", "@a[]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("b.p", "@b[]");
        ensureDirectoryExists("inner");

        // Act
        moveFile("helper.p", "inner");

        // Assert
        assertContainsUse("inner/helper.p", "../a.p");
        assertContainsUse("inner/helper.p", "../b.p");
    }

    // ==================== Тест 2.3: Абсолютная внутренняя ссылка не меняется ====================

    /**
     * Структура:
     *   /dir1/main.p      содержит: ^use[/lib.p]
     *   /lib.p
     *
     * Действие: Переместить main.p в /dir2/main.p
     *
     * Ожидание:
     *   /dir2/main.p      содержит: ^use[/lib.p] (абсолютный путь не меняется)
     */
    public void testMoveFileWithAbsoluteInternalRef() {
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
}
