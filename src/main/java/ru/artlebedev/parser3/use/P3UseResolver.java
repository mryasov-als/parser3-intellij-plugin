package ru.artlebedev.parser3.use;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.completion.P3PathCompletionSupport;
import ru.artlebedev.parser3.classpath.P3ClassPathEvaluator;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;

/**
 * Resolves use paths to VirtualFile.
 *
 * Resolution rules:
 * 1. Absolute path (starts with /) → from document_root
 * 2. Relative path → from current file directory
 * 3. If path contains ../ and not found → try without ../ prefix in current directory
 * 4. Still not found → search in CLASS_PATH directories
 *
 * Example for ^use[../file.p] from www/inner/auto.p:
 * 1. First: www/inner/../file.p = www/file.p — if exists, use it
 * 2. If not found: www/inner/file.p (same dir, without ../)
 * 3. If still not found: search file.p in CLASS_PATH
 */
@Service(Service.Level.PROJECT)
public final class P3UseResolver {

	private final Project project;

	public P3UseResolver(@NotNull Project project) {
		this.project = project;
	}

	public static P3UseResolver getInstance(@NotNull Project project) {
		return project.getService(P3UseResolver.class);
	}

	public @Nullable VirtualFile resolve(@NotNull String usePath, @NotNull VirtualFile contextFile) {
		// Normalize path
		usePath = normalizePath(usePath);

		if (usePath.isEmpty()) {
			return null;
		}


		// 1. Absolute path
		if (usePath.startsWith("/")) {
			VirtualFile result = resolveAbsolute(usePath);
			if (result != null) {
				return result;
			}
			// Absolute paths are not searched in CLASS_PATH
			return null;
		}

		// 2. Relative path - first try from current file directory
		VirtualFile result = resolveRelative(usePath, contextFile);
		if (result != null) {
			return result;
		}

		// 3. If path contains ../ and not found - try without ../ prefix in current directory
		if (usePath.contains("../")) {
			String pathWithoutDotDot = stripDotDotPrefix(usePath);
			if (!pathWithoutDotDot.equals(usePath)) {
				result = resolveRelative(pathWithoutDotDot, contextFile);
				if (result != null) {
					return result;
				}
			}
		}

		// 4. CLASS_PATH - search the base filename (without ../ prefix)
		String pathForClassPath = stripDotDotPrefix(usePath);
		result = resolveInClassPath(pathForClassPath, contextFile);
		return result;
	}

	/**
	 * Strips all leading ../ from the path.
	 * "../file.p" -> "file.p"
	 * "../../dir/file.p" -> "dir/file.p"
	 * "file.p" -> "file.p"
	 */
	private @NotNull String stripDotDotPrefix(@NotNull String path) {
		while (path.startsWith("../")) {
			path = path.substring(3);
		}
		return path;
	}

	private @NotNull String normalizePath(@NotNull String path) {
		String normalized = P3PathCompletionSupport.normalizePath(path);
		if (normalized.length() >= 1) {
			char last = normalized.charAt(normalized.length() - 1);
			if (last == '"' || last == '\'') {
				normalized = normalized.substring(0, normalized.length() - 1);
			}
		}
		return normalized;
	}

	private @Nullable VirtualFile resolveAbsolute(@NotNull String path) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		if (documentRoot == null || !documentRoot.isValid()) {
			return null;
		}

		String relativePath = path.substring(1);
		return documentRoot.findFileByRelativePath(relativePath);
	}

	private @Nullable VirtualFile resolveRelative(@NotNull String path, @NotNull VirtualFile contextFile) {
		VirtualFile contextDir = contextFile.getParent();
		if (contextDir == null) {
			return null;
		}

		return contextDir.findFileByRelativePath(path);
	}

	private @Nullable VirtualFile resolveInClassPath(@NotNull String path, @NotNull VirtualFile contextFile) {
		P3ClassPathEvaluator evaluator = new P3ClassPathEvaluator(project);
		return evaluator.resolveInClassPath(path, contextFile);
	}

	// ==================== Методы для автокомплита ====================

	/**
	 * Возвращает список файлов для автокомплита по частичному пути.
	 * Использует ту же логику что и resolve():
	 * 1. Абсолютный путь → от document_root
	 * 2. Относительный путь → от текущей директории
	 * 3. Если путь с ../ и директория не найдена → файлы из текущей директории
	 * 4. CLASS_PATH
	 *
	 * @param partialPath частичный путь (например "../" или "../fi")
	 * @param contextFile файл из которого вызывается
	 * @return список пар (insertText, VirtualFile)
	 */
	public @NotNull java.util.List<CompletionCandidate> getCompletionCandidates(
			@NotNull String partialPath,
			@NotNull VirtualFile contextFile
	) {
		java.util.List<CompletionCandidate> result = new java.util.ArrayList<>();
		java.util.Set<String> addedPaths = new java.util.HashSet<>();

		partialPath = normalizePath(partialPath);
		VirtualFile contextDir = contextFile.getParent();
		if (contextDir == null) {
			return result;
		}

		if (partialPath.startsWith("/")) {
			// Абсолютный путь — от document_root
			collectAbsolutePathCandidates(partialPath, result, addedPaths);
		} else {
			// Относительный путь
			collectRelativePathCandidates(partialPath, contextFile, result, addedPaths);
		}

		return result;
	}

	private void collectAbsolutePathCandidates(
			@NotNull String partialPath,
			@NotNull java.util.List<CompletionCandidate> result,
			@NotNull java.util.Set<String> addedPaths
	) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();
		if (documentRoot == null) {
			return;
		}

		// Убираем начальный /
		String relativePath = partialPath.substring(1);

		// Находим директорию для поиска
		VirtualFile searchDir = documentRoot;
		String prefix = "/";

		int lastSlash = relativePath.lastIndexOf('/');
		if (lastSlash >= 0) {
			String dirPath = relativePath.substring(0, lastSlash);
			if (!dirPath.isEmpty()) {
				searchDir = documentRoot.findFileByRelativePath(dirPath);
				if (searchDir == null || !searchDir.isDirectory()) {
					return;
				}
				prefix = "/" + dirPath + "/";
			}
		}

		collectFilesFromDir(searchDir, prefix, result, addedPaths, 0);
	}

	private void collectRelativePathCandidates(
			@NotNull String partialPath,
			@NotNull VirtualFile contextFile,
			@NotNull java.util.List<CompletionCandidate> result,
			@NotNull java.util.Set<String> addedPaths
	) {
		VirtualFile contextDir = contextFile.getParent();
		if (contextDir == null) {
			return;
		}

		// Разбираем путь с ../
		VirtualFile searchDir = contextDir;
		String prefix = "";
		int upLevels = 0;
		String remaining = partialPath;

		while (remaining.startsWith("../")) {
			upLevels++;
			remaining = remaining.substring(3);
			VirtualFile parent = searchDir.getParent();
			if (parent == null) {
				// Не можем подняться выше — fallback на текущую директорию
				searchDir = contextDir;
				prefix = "";
				upLevels = 0;
				remaining = stripDotDotPrefix(partialPath);
				break;
			}
			searchDir = parent;
			prefix = "../".repeat(upLevels);
		}

		// Если есть оставшийся путь после ../
		if (!remaining.isEmpty()) {
			int lastSlash = remaining.lastIndexOf('/');
			if (lastSlash >= 0) {
				String dirPath = remaining.substring(0, lastSlash);
				if (!dirPath.isEmpty()) {
					VirtualFile subDir = searchDir.findFileByRelativePath(dirPath);
					if (subDir != null && subDir.isDirectory()) {
						searchDir = subDir;
						prefix = prefix + dirPath + "/";
					} else {
						// Директория не найдена — fallback на текущую директорию
						searchDir = contextDir;
						prefix = "";
					}
				}
			}
		}

		// Собираем файлы из найденной директории
		collectFilesFromDir(searchDir, prefix, result, addedPaths, 0);

		// Если был путь с ../ — также добавляем файлы из текущей директории (fallback)
		// Важно: добавляем с тем же ../ префиксом чтобы они матчились с введённым путём
		if (upLevels > 0 && searchDir != contextDir) {
			String fallbackPrefix = "../".repeat(upLevels);
			collectFilesFromDir(contextDir, fallbackPrefix, result, addedPaths, 0);
		}

		// CLASS_PATH — тоже с ../ префиксом если был введён
		String classPathPrefix = upLevels > 0 ? "../".repeat(upLevels) : "";
		collectFromClassPath(partialPath, contextFile, classPathPrefix, result, addedPaths);
	}

	private void collectFromClassPath(
			@NotNull String partialPath,
			@NotNull VirtualFile contextFile,
			@NotNull String prefix,
			@NotNull java.util.List<CompletionCandidate> result,
			@NotNull java.util.Set<String> addedPaths
	) {
		P3ClassPathEvaluator evaluator = new P3ClassPathEvaluator(project);

		for (VirtualFile classPathDir : evaluator.getClassPathDirs(contextFile)) {
			collectFilesFromDir(classPathDir, prefix, result, addedPaths, 0);
		}
	}

	private void collectFilesFromDir(
			@NotNull VirtualFile dir,
			@NotNull String prefix,
			@NotNull java.util.List<CompletionCandidate> result,
			@NotNull java.util.Set<String> addedPaths,
			int depth
	) {
		if (depth > 10 || !dir.isDirectory()) {
			return;
		}

		VirtualFile[] children = dir.getChildren();
		if (children == null) {
			return;
		}

		for (VirtualFile child : children) {
			String name = child.getName();
			if (name.startsWith(".")) {
				continue;
			}

			if (child.isDirectory()) {
				collectFilesFromDir(child, prefix + name + "/", result, addedPaths, depth + 1);
			} else if (isParser3File(child)) {
				String insertText = prefix + name;
				if (!addedPaths.contains(insertText)) {
					addedPaths.add(insertText);
					result.add(new CompletionCandidate(insertText, child));
				}
			}
		}
	}

	private boolean isParser3File(@NotNull VirtualFile file) {
		com.intellij.openapi.fileTypes.FileType fileType = file.getFileType();
		return fileType instanceof ru.artlebedev.parser3.Parser3FileType;
	}

	/**
	 * Кандидат для автокомплита
	 */
	public static class CompletionCandidate {
		public final String insertText;
		public final VirtualFile file;

		public CompletionCandidate(@NotNull String insertText, @NotNull VirtualFile file) {
			this.insertText = insertText;
			this.file = file;
		}
	}
}
