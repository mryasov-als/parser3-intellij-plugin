package ru.artlebedev.parser3;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;

import java.io.IOException;

/**
 * Базовый класс для тестов Parser3 плагина.
 * Предоставляет утилиты для создания файлов и настройки окружения.
 */
public abstract class Parser3TestCase extends BasePlatformTestCase {

	@Override
	protected String getTestDataPath() {
		return "src/test/resources/testData";
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();

		// Инициализируем язык Parser3 до использования
		// Это нужно чтобы FileType корректно загрузился
		Parser3Language.INSTANCE.getID();

		// Сбрасываем настройки перед каждым тестом
		resetSettings();
	}

	/**
	 * Сбрасывает настройки проекта в значения по умолчанию.
	 */
	protected void resetSettings() {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(getProject());
		settings.setDocumentRootPath(null);
		settings.setMainAutoPath(null);
		settings.setMethodCompletionMode(Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS);
	}

	/**
	 * Создаёт Parser3 файл в тестовом проекте и делает его активным.
	 * Работает только для файлов в корне проекта.
	 */
	protected PsiFile createParser3File(@NotNull String fileName, @NotNull String content) {
		VirtualFile existing = myFixture.getTempDirFixture().getFile(fileName);
		if (existing != null) {
			ApplicationManager.getApplication().runWriteAction(() -> {
				try {
					VfsUtil.saveText(existing, content);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			return myFixture.configureByFile(fileName);
		}
		myFixture.addFileToProject(fileName, content);
		return myFixture.configureByFile(fileName);
	}

	/**
	 * Создаёт Parser3 файл в указанной директории и делает его активным (с кареткой).
	 * Поддерживает пути вида "dir1/sub1/file.p".
	 */
	protected PsiFile createParser3FileWithCaret(@NotNull String path, @NotNull String content) {
		// Создаём файл через addFileToProject
		myFixture.addFileToProject(path, content);
		// Открываем его и настраиваем каретку
		return myFixture.configureByFile(path);
	}

	/**
	 * Создаёт Parser3 файл в указанной директории.
	 */
	protected VirtualFile createParser3FileInDir(@NotNull String path, @NotNull String content) {
		return myFixture.addFileToProject(path, content).getVirtualFile();
	}

	/**
	 * Заменяет Parser3 файл в тестовом проекте или создаёт его, если файла ещё нет.
	 */
	protected VirtualFile replaceParser3FileInDir(@NotNull String path, @NotNull String content) {
		VirtualFile existing = myFixture.getTempDirFixture().getFile(path);
		if (existing == null) {
			return createParser3FileInDir(path, content);
		}
		ApplicationManager.getApplication().runWriteAction(() -> {
			try {
				VfsUtil.saveText(existing, content);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		return existing;
	}

	/**
	 * Создаёт директорию в тестовом проекте.
	 */
	protected VirtualFile createDirectory(@NotNull String path) throws IOException {
		return myFixture.getTempDirFixture().findOrCreateDir(path);
	}

	/**
	 * Устанавливает document_root для тестов.
	 * @param relativePath путь относительно корня тестового проекта, или null для сброса
	 */
	protected void setDocumentRoot(@Nullable String relativePath) throws IOException {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(getProject());
		if (relativePath == null) {
			settings.setDocumentRootPath(null);
		} else {
			VirtualFile dir = myFixture.getTempDirFixture().findOrCreateDir(relativePath);
			settings.setDocumentRoot(dir);
		}
	}

	/**
	 * Устанавливает document_root на корень тестового проекта.
	 */
	protected void setDocumentRootToProjectBase() {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(getProject());
		try {
			VirtualFile baseDir = myFixture.getTempDirFixture().findOrCreateDir(".");
			if (baseDir != null) {
				settings.setDocumentRoot(baseDir);
			}
		} catch (IOException e) {
			// fallback - используем project base dir
			VirtualFile projectBase = getProject().getBaseDir();
			if (projectBase != null) {
				settings.setDocumentRoot(projectBase);
			}
		}
	}

	/**
	 * Устанавливает main auto.p файл.
	 */
	protected void setMainAuto(@NotNull String path) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(getProject());
		VirtualFile file = myFixture.getTempDirFixture().getFile(path);
		if (file != null) {
			settings.setMainAuto(file);
		}
	}

	/**
	 * Устанавливает режим автокомплита методов.
	 */
	protected void setMethodCompletionMode(@NotNull Parser3ProjectSettings.MethodCompletionMode mode) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(getProject());
		settings.setMethodCompletionMode(mode);
	}

	/**
	 * Получает текущие настройки проекта.
	 */
	protected Parser3ProjectSettings getSettings() {
		return Parser3ProjectSettings.getInstance(getProject());
	}

	/**
	 * Создаёт структуру директорий с файлами.
	 * Формат: "path/to/file.p" -> "content"
	 */
	protected void createFileStructure(@NotNull String... pathsAndContents) {
		if (pathsAndContents.length % 2 != 0) {
			throw new IllegalArgumentException("Должно быть чётное количество аргументов (путь, содержимое)");
		}
		for (int i = 0; i < pathsAndContents.length; i += 2) {
			String path = pathsAndContents[i];
			String content = pathsAndContents[i + 1];
			createParser3FileInDir(path, content);
		}
	}

	/**
	 * Создаёт auto.p файл в указанной директории.
	 */
	protected VirtualFile createAutoFile(@NotNull String dir, @NotNull String content) throws IOException {
		String path = dir.isEmpty() ? "auto.p" : dir + "/auto.p";
		return createParser3FileInDir(path, content);
	}
}
