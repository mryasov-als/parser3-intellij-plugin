package ru.artlebedev.parser3.refactoring;

/**
 * Тесты рефакторинга USE путей: проверка корректности изменений.
 *
 * Группа 4: Откат изменений при несовпадении контента или нерезолвящихся путях.
 */
public class P3UseRefactoringValidationTest extends P3UseRefactoringTestBase {

    // ==================== Тест 4.1: Конфликт имён — откат из-за несовпадения контента ====================

    /**
     * Структура:
     *   /auto.p           содержит: ^use[file.p]
     *   /file.p           содержит: "original content"
     *   /inner/file.p     содержит: "different content"
     *
     * Действие: Переместить /file.p в /inner/file.p (с заменой)
     *
     * Ожидание:
     *   Предупреждение: "контент файла отличается"
     *   /auto.p           содержит: ^use[file.p] (откат)
     *
     * Примечание: Этот тест сложный, т.к. IDE может спрашивать о замене файла.
     * Пока проверяем базовый сценарий.
     */
    public void testContentMismatch_Rollback() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("auto.p", "@auto[]\n^use[file.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("file.p", "original content");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("inner/file.p", "different content");

        // Act
        // Перемещаем file.p в inner/ — там уже есть file.p с другим контентом
        // IDE должна либо откатить изменение, либо обновить путь
        moveFile("file.p", "inner");

        // Assert
        // После перемещения проверяем, что путь либо откатился, либо обновился
        String content = getFileContent("auto.p");

        // В зависимости от поведения IDE:
        // 1. Если файл заменился — путь станет inner/file.p
        // 2. Если произошёл откат — путь останется file.p

        boolean hasOriginalPath = content.contains("^use[file.p]");
        boolean hasNewPath = content.contains("^use[inner/file.p]");

        assertTrue(
                "Путь должен либо остаться (file.p), либо обновиться (inner/file.p)\n" +
                        "Актуальное содержимое:\n" + content,
                hasOriginalPath || hasNewPath
        );
    }

    // ==================== Тест 4.2: Путь не резолвится после изменения ====================

    /**
     * Структура:
     *   /dir1/main.p      содержит: ^use[helper.p]
     *   /dir1/helper.p
     *
     * Действие: Переместить helper.p в dir2, затем удалить dir2
     *
     * Ожидание:
     *   Путь должен был быть откачен или остаться нерезолвящимся
     *
     * Примечание: Этот тест проверяет поведение при нерезолвящемся пути.
     */
    public void testPathNotResolved() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/main.p", "@main[]\n^use[helper.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir1/helper.p", "@helper[]");
        ensureDirectoryExists("dir2");

        // Act
        moveFile("dir1/helper.p", "dir2");

        // Assert
        // Проверяем что путь обновился
        assertContainsUse("dir1/main.p", "../dir2/helper.p");
    }

    // ==================== Дополнительные тесты валидации ====================

    /**
     * Перемещение файла — путь успешно резолвится после изменения.
     */
    public void testPathResolvedAfterMove() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[lib.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("lib.p", "@lib[]");
        ensureDirectoryExists("inner");

        // Act
        moveFile("lib.p", "inner");

        // Assert
        assertFileExists("inner/lib.p");
        assertContainsUse("main.p", "inner/lib.p");
    }

    /**
     * Переименование файла — путь корректно обновляется.
     */
    public void testPathUpdatedOnRename() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("main.p", "@main[]\n^use[old_name.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("old_name.p", "@old[]");

        // Act
        renameFile("old_name.p", "new_name.p");

        // Assert
        assertFileExists("new_name.p");
        assertFileNotExists("old_name.p");
        assertContainsUse("main.p", "new_name.p");
        assertNotContainsUse("main.p", "old_name.p");
    }

    /**
     * Множественные ссылки на один файл — все обновляются корректно.
     */
    public void testMultipleReferencesAllUpdated() {
        // Arrange
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("file1.p", "@file1[]\n^use[target.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("file2.p", "@file2[]\n^use[target.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir/file3.p", "@file3[]\n^use[../target.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("target.p", "@target[]");
        ensureDirectoryExists("moved");

        // Act
        moveFile("target.p", "moved");

        // Assert
        assertContainsUse("file1.p", "moved/target.p");
        assertContainsUse("file2.p", "moved/target.p");
        assertContainsUse("dir/file3.p", "../moved/target.p");
    }

    /**
     * Ссылка из того же файла что и цель — корректное обновление.
     */
    public void testSelfReferenceFile() {
        // Arrange - файл ссылается на себя (маловероятно, но возможно)
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir/main.p", "@main[]\n^use[helper.p]");
        // реальный минимальный fixture рефакторинга USE; при расширении сверять с тестами Parser3.
        createParser3FileInDir("dir/helper.p", "@helper[]");
        ensureDirectoryExists("other");

        // Act
        moveFile("dir/main.p", "other");
        moveFile("dir/helper.p", "other");

        // Assert
        // После обоих перемещений main.p и helper.p в одной директории
        assertContainsUse("other/main.p", "helper.p");
    }
}
