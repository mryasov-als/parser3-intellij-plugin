package ru.artlebedev.parser3.settings;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Автоматическое определение настроек проекта Parser3:
 * - Document root (public_html, www)
 * - Основной auto.p (в cgi-bin, cgi)
 */
@Service(Service.Level.PROJECT)
public final class P3ProjectSettingsDetector {

	private final Project project;

	// Приоритеты для поиска
	private static final List<String> CGI_FOLDER_NAMES = Arrays.asList(
			"cgi-bin", "cgi", "scripts", "cgi_scripts"
	);
	private static final List<String> WWW_FOLDER_NAMES = Arrays.asList(
			"public_html", "www", "httpdocs", "htdocs", "html", "site", "public"
	);
	private static final int MAX_DEPTH = 10;

	public P3ProjectSettingsDetector(@NotNull Project project) {
		this.project = project;
	}

	public static P3ProjectSettingsDetector getInstance(@NotNull Project project) {
		return project.getService(P3ProjectSettingsDetector.class);
	}

	/**
	 * Результат автоопределения настроек
	 */
	public static class DetectionResult {
		public final String documentRoot; // относительный путь или null
		public final String mainAutoP;    // относительный путь или null

		public DetectionResult(String documentRoot, String mainAutoP) {
			this.documentRoot = documentRoot;
			this.mainAutoP = mainAutoP;
		}
	}

	/**
	 * Автоматически определяет настройки проекта
	 */
	public @NotNull DetectionResult detectSettings() {
		VirtualFile baseDir = project.getBaseDir();
		if (baseDir == null) {
			return new DetectionResult(null, null);
		}

		String mainAutoP = findMainAutoP(baseDir);
		String documentRoot = findDocumentRoot(baseDir);

		return new DetectionResult(documentRoot, mainAutoP);
	}

	/**
	 * Проверяет есть ли в проекте хоть один auto.p файл (до глубины MAX_DEPTH)
	 */
	public boolean hasAutoPFiles() {
		VirtualFile baseDir = project.getBaseDir();
		if (baseDir == null) {
			return false;
		}

		return findAutoPRecursive(baseDir, 0);
	}

	/**
	 * Рекурсивно ищет auto.p файлы
	 */
	private boolean findAutoPRecursive(@NotNull VirtualFile dir, int depth) {
		if (depth > MAX_DEPTH) {
			return false;
		}

		VirtualFile[] children = dir.getChildren();
		for (VirtualFile child : children) {
			if (child.getName().startsWith(".") || child.getName().equals("node_modules")) {
				continue;
			}

			if (!child.isDirectory() && child.getName().equals("auto.p")) {
				return true;
			}

			if (child.isDirectory()) {
				if (findAutoPRecursive(child, depth + 1)) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Ищет основной auto.p в cgi-bin, cgi папках
	 */
	private @Nullable String findMainAutoP(@NotNull VirtualFile baseDir) {
		// Этап 1: Ищем папку с parser3 файлом
		for (String folderName : CGI_FOLDER_NAMES) {
			VirtualFile cgiFolder = findFolderRecursive(baseDir, folderName, 0);
			if (cgiFolder != null) {
				// Проверяем наличие файла с "parser3" в названии
				boolean hasParser3File = Arrays.stream(cgiFolder.getChildren())
						.anyMatch(f -> !f.isDirectory() && f.getName().toLowerCase().contains("parser3"));

				if (hasParser3File) {
					// Проверяем наличие auto.p
					VirtualFile autoP = cgiFolder.findChild("auto.p");
					if (autoP != null && !autoP.isDirectory()) {
						return getRelativePath(baseDir, autoP);
					}
				}
			}
		}

		// Этап 2: Ищем папку без требования parser3 файла
		for (String folderName : CGI_FOLDER_NAMES) {
			VirtualFile cgiFolder = findFolderRecursive(baseDir, folderName, 0);
			if (cgiFolder != null) {
				VirtualFile autoP = cgiFolder.findChild("auto.p");
				if (autoP != null && !autoP.isDirectory()) {
					return getRelativePath(baseDir, autoP);
				}
			}
		}

		return null;
	}

	/**
	 * Ищет document root в public_html, www папках
	 */
	private @Nullable String findDocumentRoot(@NotNull VirtualFile baseDir) {
		// Этап 1: Ищем папку с auto.p
		for (String folderName : WWW_FOLDER_NAMES) {
			VirtualFile wwwFolder = findFolderRecursive(baseDir, folderName, 0);
			if (wwwFolder != null) {
				VirtualFile autoP = wwwFolder.findChild("auto.p");
				if (autoP != null && !autoP.isDirectory()) {
					return getRelativePath(baseDir, wwwFolder);
				}
			}
		}

		// Этап 2: Ищем папку без требования auto.p
		for (String folderName : WWW_FOLDER_NAMES) {
			VirtualFile wwwFolder = findFolderRecursive(baseDir, folderName, 0);
			if (wwwFolder != null) {
				return getRelativePath(baseDir, wwwFolder);
			}
		}

		return null;
	}

	/**
	 * Рекурсивно ищет папку по имени
	 */
	private @Nullable VirtualFile findFolderRecursive(
			@NotNull VirtualFile dir,
			@NotNull String folderName,
			int depth
	) {
		if (depth > MAX_DEPTH) {
			return null;
		}

		// Проверяем текущую директорию
		VirtualFile found = dir.findChild(folderName);
		if (found != null && found.isDirectory()) {
			return found;
		}

		// Рекурсивно ищем в поддиректориях
		VirtualFile[] children = dir.getChildren();
		for (VirtualFile child : children) {
			if (child.isDirectory() && !child.getName().startsWith(".") && !child.getName().equals("node_modules")) {
				VirtualFile result = findFolderRecursive(child, folderName, depth + 1);
				if (result != null) {
					return result;
				}
			}
		}

		return null;
	}

	/**
	 * Получает относительный путь от baseDir до file
	 */
	private @NotNull String getRelativePath(@NotNull VirtualFile baseDir, @NotNull VirtualFile file) {
		String basePath = baseDir.getPath();
		String filePath = file.getPath();

		if (filePath.startsWith(basePath)) {
			String relative = filePath.substring(basePath.length());
			if (relative.startsWith("/")) {
				relative = relative.substring(1);
			}
			return relative;
		}

		return filePath;
	}
}