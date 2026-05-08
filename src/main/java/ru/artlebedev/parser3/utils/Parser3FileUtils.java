package ru.artlebedev.parser3.utils;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3FileType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Общие проверки и сбор файлов Parser3.
 */
public final class Parser3FileUtils {
	private static final Set<String> PARSER3_EXTENSIONS = Set.of("p", "p3");
	private static final boolean DEBUG = false;

	private Parser3FileUtils() {
	}

	public static boolean isParser3File(@Nullable VirtualFile file) {
		if (file == null || file.isDirectory()) {
			return false;
		}
		if (file.getFileType() instanceof Parser3FileType) {
			return true;
		}

		String extension = file.getExtension();
		return extension != null && PARSER3_EXTENSIONS.contains(extension.toLowerCase(java.util.Locale.ROOT));
	}

	public static boolean isProjectIndexableParser3File(@NotNull Project project, @Nullable VirtualFile file) {
		return isParser3File(file)
				&& isUnderProjectBase(project, file)
				&& !isExcludedFromProject(project, file);
	}

	public static @NotNull Collection<VirtualFile> getProjectParser3Files(@NotNull Project project) {
		LinkedHashSet<VirtualFile> result = new LinkedHashSet<>();
		Collection<VirtualFile> excludedRoots = getProjectExcludedRoots(project);
		GlobalSearchScope scope = GlobalSearchScope.allScope(project);

		Collection<VirtualFile> fileTypeFiles = FileTypeIndex.getFiles(Parser3FileType.INSTANCE, scope);
		Collection<VirtualFile> pFiles = FilenameIndex.getAllFilesByExt(project, "p", scope);
		Collection<VirtualFile> p3Files = FilenameIndex.getAllFilesByExt(project, "p3", scope);

		addProjectBaseFiles(project, result, fileTypeFiles, excludedRoots);
		addProjectBaseFiles(project, result, pFiles, excludedRoots);
		addProjectBaseFiles(project, result, p3Files, excludedRoots);

		if (DEBUG) {
			System.out.println("[P3FileUtils] indexedSources project=" + project.getName()
					+ " base=" + project.getBasePath()
					+ " fileType=" + fileTypeFiles.size()
					+ " extP=" + pFiles.size()
					+ " extP3=" + p3Files.size()
					+ " total=" + result.size());
		}

		int beforeBaseScan = result.size();
		collectParser3FilesFromProjectBase(project, result);
		if (DEBUG) {
			System.out.println("[P3FileUtils] baseScan project=" + project.getName()
					+ " before=" + beforeBaseScan
					+ " after=" + result.size()
					+ " sample=" + samplePaths(result));
		}

		return result;
	}

	private static void addProjectBaseFiles(
			@NotNull Project project,
			@NotNull LinkedHashSet<VirtualFile> result,
			@NotNull Collection<VirtualFile> files,
			@NotNull Collection<VirtualFile> excludedRoots
	) {
		for (VirtualFile file : files) {
			if (isParser3File(file) && isUnderProjectBase(project, file) && !isUnderExcludedRoot(file, excludedRoots)) {
				result.add(file);
			}
		}
	}

	private static boolean isUnderProjectBase(@NotNull Project project, @Nullable VirtualFile file) {
		VirtualFile baseDir = project.getBaseDir();
		return baseDir != null && file != null && file.isValid()
				&& (file.equals(baseDir) || VfsUtilCore.isAncestor(baseDir, file, false));
	}

	private static boolean isExcludedFromProject(@NotNull Project project, @Nullable VirtualFile file) {
		return file != null && isUnderExcludedRoot(file, getProjectExcludedRoots(project));
	}

	private static @NotNull Collection<VirtualFile> getProjectExcludedRoots(@NotNull Project project) {
		LinkedHashSet<VirtualFile> excludedRoots = new LinkedHashSet<>();
		for (Module module : ModuleManager.getInstance(project).getModules()) {
			for (VirtualFile root : ModuleRootManager.getInstance(module).getExcludeRoots()) {
				if (root != null && root.isValid() && isUnderProjectBase(project, root)) {
					excludedRoots.add(root);
				}
			}
		}
		return excludedRoots;
	}

	private static boolean isUnderExcludedRoot(
			@Nullable VirtualFile file,
			@NotNull Collection<VirtualFile> excludedRoots
	) {
		if (file == null || !file.isValid()) {
			return false;
		}
		for (VirtualFile root : excludedRoots) {
			if (file.equals(root) || VfsUtilCore.isAncestor(root, file, false)) {
				return true;
			}
		}
		return false;
	}

	private static void collectParser3FilesFromProjectBase(
			@NotNull Project project,
			@NotNull LinkedHashSet<VirtualFile> result
	) {
		VirtualFile baseDir = project.getBaseDir();
		if (baseDir == null || !baseDir.isValid()) {
			return;
		}

		int[] visited = {0};
		Collection<VirtualFile> excludedRoots = getProjectExcludedRoots(project);
		collectParser3FilesRecursive(project, excludedRoots, baseDir, result, visited);
		if (DEBUG) {
			System.out.println("[P3FileUtils] fallbackVisited project=" + project.getName()
					+ " visited=" + visited[0]);
		}
	}

	private static void collectParser3FilesRecursive(
			@NotNull Project project,
			@NotNull Collection<VirtualFile> excludedRoots,
			@NotNull VirtualFile file,
			@NotNull LinkedHashSet<VirtualFile> result,
			int @NotNull [] visited
	) {
		if (!file.isValid()) {
			return;
		}
		visited[0]++;

		if (file.isDirectory()) {
			VirtualFile baseDir = project.getBaseDir();
			if (!file.equals(baseDir) && isUnderExcludedRoot(file, excludedRoots)) {
				return;
			}
			for (VirtualFile child : file.getChildren()) {
				collectParser3FilesRecursive(project, excludedRoots, child, result, visited);
			}
			return;
		}

		if (isParser3File(file) && !isUnderExcludedRoot(file, excludedRoots)) {
			result.add(file);
		}
	}

	private static @NotNull String samplePaths(@NotNull Collection<VirtualFile> files) {
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (VirtualFile file : files) {
			if (count > 0) {
				sb.append(", ");
			}
			sb.append(file.getPath());
			count++;
			if (count >= 5) {
				break;
			}
		}
		if (files.size() > count) {
			sb.append(", ...");
		}
		return sb.toString();
	}
}
