package ru.artlebedev.parser3.utils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;

/**
 * Утилиты для форматирования путей относительно document_root.
 *
 * NOTE: Пока не используется из-за ограничений IntelliJ Platform.
 * IntelliJ показывает путь от корня проекта в popup и не использует
 * кастомные VirtualFile wrapper. Оставлено для будущего использования.
 */
public final class Parser3PathFormatter {

	private Parser3PathFormatter() {
	}

	/**
	 * Форматирует путь к файлу для показа в UI (popup, completion, etc).
	 *
	 * Правила:
	 * - Если document_root указан - показываем путь относительно него
	 * - Если файл выше document_root - показываем с /../
	 * - Если document_root не указан - показываем от корня проекта
	 *
	 * Примеры:
	 * - inner-project/www/test1.p → test1.p (если document_root = inner-project/www)
	 * - inner-project/www/dir1/auto.p → dir1/auto.p
	 * - inner-project/data/classes/use_out.p → /../data/classes/use_out.p
	 * - inner-project/www/test1.p → inner-project/www/test1.p (если document_root не указан)
	 */
	public static @NotNull String formatFilePathForUI(
			@NotNull VirtualFile file,
			@NotNull Project project
	) {
		VirtualFile projectRoot = project.getBaseDir();
		if (projectRoot == null) {
			return file.getName();
		}

		// Получаем document_root из настроек (с fallback на корень проекта)
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		// Если document_root не определён - показываем от корня проекта
		if (documentRoot == null) {
			String projectPath = projectRoot.getPath();
			String filePath = file.getPath();
			if (filePath.startsWith(projectPath + "/")) {
				return filePath.substring(projectPath.length() + 1);
			}
			return file.getName();
		}

		String documentRootFullPath = documentRoot.getPath();
		String fileFullPath = file.getPath();

		// Случай 1: Файл внутри document_root
		if (fileFullPath.startsWith(documentRootFullPath + "/")) {
			return fileFullPath.substring(documentRootFullPath.length() + 1);
		}

		// Случай 2: Файл выше document_root - считаем сколько уровней вверх
		if (fileFullPath.startsWith(projectRoot.getPath() + "/")) {
			// Оба пути от корня проекта, можем вычислить относительный путь
			String relativeFromProject = fileFullPath.substring(projectRoot.getPath().length() + 1);
			String documentRootFromProject = documentRootFullPath.substring(projectRoot.getPath().length() + 1);

			// Формируем путь с ../
			String[] fileParts = relativeFromProject.split("/");
			String[] docRootParts = documentRootFromProject.split("/");

			int commonPrefix = 0;
			while (commonPrefix < Math.min(fileParts.length, docRootParts.length) &&
					fileParts[commonPrefix].equals(docRootParts[commonPrefix])) {
				commonPrefix++;
			}

			// Убираем лишние ../ за общую часть
			int levelsUp = docRootParts.length - commonPrefix;
			StringBuilder result = new StringBuilder();
			for (int i = 0; i < levelsUp; i++) {
				result.append("../");
			}

			// Добавляем остаток пути к файлу
			for (int i = commonPrefix; i < fileParts.length; i++) {
				if (i > commonPrefix) {
					result.append("/");
				}
				result.append(fileParts[i]);
			}

			// Добавляем начальный / только если путь не пустой
			String finalPath = result.toString();
			if (!finalPath.isEmpty() && !finalPath.startsWith("/")) {
				return "/" + finalPath;
			}
			return finalPath;
		}

		// Fallback - просто имя файла
		return file.getName();
	}

	/**
	 * Форматирует путь к файлу для показа в UI.
	 * Если файл совпадает с текущим — возвращает "current file".
	 */
	public static @NotNull String formatFilePathForUI(
			@NotNull VirtualFile file,
			@NotNull Project project,
			@org.jetbrains.annotations.Nullable VirtualFile currentFile
	) {
		if (currentFile != null && file.equals(currentFile)) {
			return "current file";
		}
		return formatFilePathForUI(file, project);
	}

	/**
	 * Форматирует путь к файлу с номером строки для показа в UI.
	 * Если файл совпадает с текущим — показывает "current file:42".
	 */
	public static @NotNull String formatFilePathWithLineForUI(
			@NotNull VirtualFile file,
			@NotNull Project project,
			int offset,
			@org.jetbrains.annotations.Nullable VirtualFile currentFile
	) {
		String path = formatFilePathForUI(file, project, currentFile);
		int lineNumber = offsetToLine(file, offset);
		return path + ":" + lineNumber;
	}

	/**
	 * Упрощённый вариант - только имя файла для случаев где не нужен полный путь
	 */
	public static @NotNull String formatFileNameForUI(@NotNull VirtualFile file) {
		return file.getName();
	}

	/**
	 * Форматирует путь к файлу с номером строки для показа в UI.
	 * Формат: path/to/file.p:42
	 *
	 * @param file файл
	 * @param project проект
	 * @param offset смещение в файле (будет преобразовано в номер строки)
	 * @return путь с номером строки
	 */
	public static @NotNull String formatFilePathWithLineForUI(
			@NotNull VirtualFile file,
			@NotNull Project project,
			int offset
	) {
		String path = formatFilePathForUI(file, project);
		int lineNumber = offsetToLine(file, offset);
		return path + ":" + lineNumber;
	}

	/**
	 * Преобразует offset в номер строки (1-based).
	 */
	private static int offsetToLine(@NotNull VirtualFile file, int offset) {
		try {
			byte[] content = file.contentsToByteArray();
			String text = new String(content, file.getCharset());
			int line = 1;
			for (int i = 0; i < Math.min(offset, text.length()); i++) {
				if (text.charAt(i) == '\n') {
					line++;
				}
			}
			return line;
		} catch (Exception e) {
			return 1;
		}
	}
}