package ru.artlebedev.parser3.refactoring;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3TestCase;
import ru.artlebedev.parser3.utils.P3UsePathUtils;

import java.io.IOException;

import ru.artlebedev.parser3.references.P3RefactoringListenerProvider;

/**
 * Базовый класс для тестов рефакторинга USE путей.
 * Предоставляет утилиты для перемещения/переименования файлов и проверки результатов.
 */
public abstract class P3UseRefactoringTestBase extends Parser3TestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Устанавливаем document_root на корень тестового проекта
        setDocumentRootToProjectBase();
        // Сбрасываем состояние для тестов
        P3RefactoringListenerProvider.resetTestState();
    }

    // ==================== ФАЙЛОВЫЕ ОПЕРАЦИИ ====================

    /**
     * Перемещает файл в указанную директорию с обновлением ссылок.
     *
     * @param filePath      путь к файлу (например "dir1/file.p")
     * @param targetDirPath путь к целевой директории (например "dir2")
     */
    protected void moveFile(@NotNull String filePath, @NotNull String targetDirPath) {
        VirtualFile file = myFixture.findFileInTempDir(filePath);
        assertNotNull("Файл не найден: " + filePath, file);

        VirtualFile targetDir = myFixture.findFileInTempDir(targetDirPath);
        if (targetDir == null) {
            try {
                targetDir = myFixture.getTempDirFixture().findOrCreateDir(targetDirPath);
            } catch (IOException e) {
                fail("Не удалось создать директорию: " + targetDirPath);
            }
        }
        assertNotNull("Целевая директория не найдена: " + targetDirPath, targetDir);

        PsiFile psiFile = PsiManager.getInstance(getProject()).findFile(file);
        assertNotNull("PsiFile не найден для: " + filePath, psiFile);

        PsiDirectory psiTargetDir = PsiManager.getInstance(getProject()).findDirectory(targetDir);
        assertNotNull("PsiDirectory не найдена для: " + targetDirPath, psiTargetDir);

        // Создаём процессор с searchForReferences=true для обновления ссылок
        MoveFilesOrDirectoriesProcessor processor = new MoveFilesOrDirectoriesProcessor(
                getProject(),
                new PsiElement[]{psiFile},
                psiTargetDir,
                true,  // searchForReferences
                false, // searchInComments
                false, // searchInNonJavaFiles
                null,
                null
        );

        processor.setPreviewUsages(false);

        // Запускаем с ProgressIndicator чтобы избежать NPE
        ProgressManager.getInstance().runProcess(() -> processor.run(), new EmptyProgressIndicator());
    }

    /**
     * Переименовывает файл с обновлением ссылок.
     *
     * @param filePath путь к файлу (например "dir1/old.p")
     * @param newName  новое имя файла (например "new.p")
     */
    protected void renameFile(@NotNull String filePath, @NotNull String newName) {
        VirtualFile file = myFixture.findFileInTempDir(filePath);
        assertNotNull("Файл не найден: " + filePath, file);

        PsiFile psiFile = PsiManager.getInstance(getProject()).findFile(file);
        assertNotNull("PsiFile не найден для: " + filePath, psiFile);

        // myFixture.renameElement() работает корректно в тестах
        myFixture.renameElement(psiFile, newName);
    }

    /**
     * Перемещает директорию с обновлением ссылок.
     */
    protected void moveDirectory(@NotNull String dirPath, @NotNull String targetDirPath) {
        VirtualFile dir = myFixture.findFileInTempDir(dirPath);
        assertNotNull("Директория не найдена: " + dirPath, dir);

        VirtualFile targetDir = myFixture.findFileInTempDir(targetDirPath);
        if (targetDir == null) {
            try {
                targetDir = myFixture.getTempDirFixture().findOrCreateDir(targetDirPath);
            } catch (IOException e) {
                fail("Не удалось создать директорию: " + targetDirPath);
            }
        }
        assertNotNull("Целевая директория не найдена: " + targetDirPath, targetDir);

        PsiDirectory psiDir = PsiManager.getInstance(getProject()).findDirectory(dir);
        assertNotNull("PsiDirectory не найдена для: " + dirPath, psiDir);

        PsiDirectory psiTargetDir = PsiManager.getInstance(getProject()).findDirectory(targetDir);
        assertNotNull("PsiDirectory не найдена для: " + targetDirPath, psiTargetDir);

        MoveFilesOrDirectoriesProcessor processor = new MoveFilesOrDirectoriesProcessor(
                getProject(),
                new PsiElement[]{psiDir},
                psiTargetDir,
                true,
                false,
                false,
                null,
                null
        );

        processor.setPreviewUsages(false);
        ProgressManager.getInstance().runProcess(() -> processor.run(), new EmptyProgressIndicator());
    }

    /**
     * Переименовывает директорию с обновлением ссылок.
     *
     * @param dirPath путь к директории (например "old_dir")
     * @param newName новое имя директории (например "new_dir")
     */
    protected void renameDirectory(@NotNull String dirPath, @NotNull String newName) {
        VirtualFile dir = myFixture.findFileInTempDir(dirPath);
        assertNotNull("Директория не найдена: " + dirPath, dir);

        PsiDirectory psiDir = PsiManager.getInstance(getProject()).findDirectory(dir);
        assertNotNull("PsiDirectory не найдена для: " + dirPath, psiDir);

        // myFixture.renameElement() работает корректно в тестах
        myFixture.renameElement(psiDir, newName);
    }

    /**
     * Удаляет директорию.
     *
     * @param dirPath путь к директории
     */
    protected void deleteDirectory(@NotNull String dirPath) {
        VirtualFile dir = myFixture.findFileInTempDir(dirPath);
        if (dir != null) {
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    dir.delete(this);
                } catch (IOException e) {
                    fail("Не удалось удалить директорию: " + dirPath);
                }
            });
        }
    }

    // ==================== ПРОВЕРКИ ====================

    /**
     * Проверяет что файл содержит указанный use-путь.
     *
     * @param filePath    путь к файлу
     * @param expectedUse ожидаемый путь в ^use[] или @USE
     */
    protected void assertContainsUse(@NotNull String filePath, @NotNull String expectedUse) {
        VirtualFile file = myFixture.findFileInTempDir(filePath);
        assertNotNull("Файл не найден: " + filePath, file);

        String content = getFileContent(file);

        // Проверяем формат ^use[path]
        boolean containsUseMethod = content.contains("^use[" + expectedUse + "]");

        // Проверяем формат @USE — путь может быть на любой строке после @USE
        // Ищем путь как отдельную строку (начинается с начала строки или после \n)
        boolean containsUseDirective = false;
        if (content.contains("@USE")) {
            // Путь должен быть на отдельной строке
            containsUseDirective = content.contains("\n" + expectedUse + "\n") ||
                    content.contains("\n" + expectedUse + "\r") ||
                    content.endsWith("\n" + expectedUse) ||
                    // Или сразу после @USE на той же строке (старый формат, на всякий случай)
                    content.contains("@USE " + expectedUse);
        }

        assertTrue(
                "Файл " + filePath + " должен содержать use-путь: " + expectedUse +
                        "\nАктуальное содержимое:\n" + content,
                containsUseMethod || containsUseDirective
        );
    }

    /**
     * Проверяет что файл НЕ содержит указанный use-путь.
     *
     * @param filePath      путь к файлу
     * @param unexpectedUse путь который НЕ должен присутствовать
     */
    protected void assertNotContainsUse(@NotNull String filePath, @NotNull String unexpectedUse) {
        VirtualFile file = myFixture.findFileInTempDir(filePath);
        assertNotNull("Файл не найден: " + filePath, file);

        String content = getFileContent(file);

        // Проверяем формат ^use[path]
        boolean containsUseMethod = content.contains("^use[" + unexpectedUse + "]");

        // Проверяем формат @USE — путь может быть на любой строке после @USE
        boolean containsUseDirective = false;
        if (content.contains("@USE")) {
            containsUseDirective = content.contains("\n" + unexpectedUse + "\n") ||
                    content.contains("\n" + unexpectedUse + "\r") ||
                    content.endsWith("\n" + unexpectedUse) ||
                    content.contains("@USE " + unexpectedUse);
        }

        assertFalse(
                "Файл " + filePath + " НЕ должен содержать use-путь: " + unexpectedUse +
                        "\nАктуальное содержимое:\n" + content,
                containsUseMethod || containsUseDirective
        );
    }

    /**
     * Проверяет что файл существует по указанному пути.
     *
     * @param filePath путь к файлу
     */
    protected void assertFileExists(@NotNull String filePath) {
        VirtualFile file = myFixture.findFileInTempDir(filePath);
        assertNotNull("Файл должен существовать: " + filePath, file);
    }

    /**
     * Проверяет что файл НЕ существует по указанному пути.
     *
     * @param filePath путь к файлу
     */
    protected void assertFileNotExists(@NotNull String filePath) {
        VirtualFile file = myFixture.findFileInTempDir(filePath);
        assertNull("Файл НЕ должен существовать: " + filePath, file);
    }

    /**
     * Получает содержимое файла.
     * Читает из Document если он есть (содержит uncommitted изменения).
     */
    protected String getFileContent(@NotNull VirtualFile file) {
        // Сначала пробуем получить содержимое из Document (там актуальные изменения)
        com.intellij.openapi.fileEditor.FileDocumentManager docManager =
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        com.intellij.openapi.editor.Document document = docManager.getDocument(file);
        if (document != null) {
            return document.getText();
        }

        // Fallback: читаем из файла
        try {
            return new String(file.contentsToByteArray(), file.getCharset());
        } catch (IOException e) {
            fail("Не удалось прочитать файл: " + file.getPath());
            return "";
        }
    }

    /**
     * Получает содержимое файла по пути.
     */
    protected String getFileContent(@NotNull String filePath) {
        VirtualFile file = myFixture.findFileInTempDir(filePath);
        assertNotNull("Файл не найден: " + filePath, file);
        return getFileContent(file);
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    /**
     * Создаёт пустой файл-маркер для создания директории.
     * IntelliJ не создаёт пустые директории, поэтому нужен файл внутри.
     *
     * @param dirPath путь к директории
     */
    protected void ensureDirectoryExists(@NotNull String dirPath) {
        try {
            myFixture.getTempDirFixture().findOrCreateDir(dirPath);
        } catch (IOException e) {
            fail("Не удалось создать директорию: " + dirPath);
        }
    }

    /**
     * Устанавливает CLASS_PATH через создание файла с переменной.
     * Использует синтаксис ^table::create{} для нескольких путей.
     *
     * @param autoFilePath путь к auto.p файлу
     * @param classPath    значение CLASS_PATH (пути через ";")
     * @param content      дополнительное содержимое после CLASS_PATH
     */
    protected void createFileWithClassPath(@NotNull String autoFilePath,
                                           @NotNull String classPath,
                                           @NotNull String content) {
        // Разбиваем пути по ";" и формируем table::create
        String[] paths = classPath.split(";");
        StringBuilder tableContent = new StringBuilder();
        for (String path : paths) {
            path = path.trim();
            if (!path.isEmpty()) {
                tableContent.append(path).append("\n");
            }
        }

        String fullContent = "@auto[]\n$CLASS_PATH[^table::create{path\n" + tableContent + "}]\n" + content;
        createParser3FileInDir(autoFilePath, fullContent);
    }

    /**
     * Удаляет файл.
     *
     * @param filePath путь к файлу
     */
    protected void deleteFile(@NotNull String filePath) {
        VirtualFile file = myFixture.findFileInTempDir(filePath);
        assertNotNull("Файл не найден для удаления: " + filePath, file);

        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                file.delete(this);
            } catch (IOException e) {
                fail("Не удалось удалить файл: " + filePath + ", ошибка: " + e.getMessage());
            }
        });
    }

    // ==================== ПРОВЕРКИ УВЕДОМЛЕНИЙ ====================

    /**
     * Ждёт выполнения всех задач в EDT (Event Dispatch Thread).
     * Нужно для корректной проверки уведомлений которые показываются через invokeLater.
     */
    protected void waitForNotifications() {
        // Даём время на invokeLater
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
        // Дополнительная задержка для асинхронных операций
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // ignore
        }
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
    }

    /**
     * Проверяет что было показано уведомление об успешном обновлении путей.
     */
    protected void assertSuccessNotificationShown() {
        waitForNotifications();
        assertTrue("Должно было появиться уведомление об успешном обновлении путей",
                P3RefactoringListenerProvider.getSuccessNotificationCountForTest() > 0);
    }

    /**
     * Проверяет что уведомление об успешном обновлении НЕ было показано.
     */
    protected void assertNoSuccessNotification() {
        waitForNotifications();
        assertEquals("Не должно быть уведомления об успешном обновлении",
                0, P3RefactoringListenerProvider.getSuccessNotificationCountForTest());
    }

    /**
     * Проверяет что было показано предупреждение о сломанных ссылках.
     */
    protected void assertWarningDialogShown() {
        waitForNotifications();
        assertTrue("Должен был появиться диалог с предупреждениями",
                P3RefactoringListenerProvider.getWarningDialogCountForTest() > 0);
    }

    /**
     * Проверяет что предупреждение о сломанных ссылках НЕ было показано.
     */
    protected void assertNoWarningDialog() {
        waitForNotifications();
        assertEquals("Не должно быть диалога с предупреждениями",
                0, P3RefactoringListenerProvider.getWarningDialogCountForTest());
    }

    /**
     * Возвращает количество показанных уведомлений об успехе.
     */
    protected int getSuccessNotificationCount() {
        waitForNotifications();
        return P3RefactoringListenerProvider.getSuccessNotificationCountForTest();
    }

    /**
     * Возвращает количество показанных диалогов с предупреждениями.
     */
    protected int getWarningDialogCount() {
        waitForNotifications();
        return P3RefactoringListenerProvider.getWarningDialogCountForTest();
    }
}