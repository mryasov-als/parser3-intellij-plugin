package ru.artlebedev.parser3.index;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.FileContentUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.ID;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.utils.Parser3FileUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Обслуживание Parser3-индексов после установки и обновления плагина.
 */
public final class P3IndexMaintenance {
	public static final String BOOTSTRAP_VERSION = "parser3-index-bootstrap-2026-05-08-5";
	private static final boolean DEBUG = false;

	private static final List<ID<?, ?>> PARSER3_INDEXES = List.of(
			P3MethodFileIndex.NAME,
			P3ClassFileIndex.NAME,
			P3UseFileIndex.NAME,
			P3MethodCallFileIndex.NAME,
			P3VariableFileIndex.NAME,
			P3CssClassFileIndex.NAME
	);

	private P3IndexMaintenance() {
	}

	public static void bootstrapIndexesIfNeeded(@NotNull Project project) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		if (BOOTSTRAP_VERSION.equals(settings.getIndexBootstrapVersion())) {
			if (DEBUG) {
				System.out.println("[P3IndexMaintenance] bootstrap skip project=" + project.getName()
						+ " version=" + BOOTSTRAP_VERSION);
			}
			return;
		}

		if (DEBUG) {
			System.out.println("[P3IndexMaintenance] bootstrap run project=" + project.getName()
					+ " oldVersion=" + settings.getIndexBootstrapVersion()
					+ " newVersion=" + BOOTSTRAP_VERSION);
		}
		requestParser3IndexRebuild(project, "startup bootstrap");
		requestParser3FileReindexAsync(project, "startup bootstrap", () ->
				settings.setIndexBootstrapVersion(BOOTSTRAP_VERSION));
	}

	public static void requestParser3IndexRebuild(@NotNull Project project, @NotNull String reason) {
		FileBasedIndex index = FileBasedIndex.getInstance();
		for (ID<?, ?> id : PARSER3_INDEXES) {
			if (DEBUG) {
				System.out.println("[P3IndexMaintenance] requestRebuild reason=" + reason + " index=" + id.getName());
			}
			index.requestRebuild(id);
		}
	}

	public static @NotNull Collection<VirtualFile> requestParser3FileReindex(
			@NotNull Project project,
			@NotNull String reason
	) {
		Collection<VirtualFile> files = ReadAction.compute(() -> Parser3FileUtils.getProjectParser3Files(project));
		requestParser3FileReindex(project, files, reason);
		return files;
	}

	public static void requestParser3FileReindexAsync(
			@NotNull Project project,
			@NotNull String reason,
			@NotNull Runnable afterRequest
	) {
		ReadAction.nonBlocking(() -> Parser3FileUtils.getProjectParser3Files(project))
				.finishOnUiThread(ModalityState.defaultModalityState(), files -> {
					requestParser3FileReindex(project, files, reason);
					afterRequest.run();
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	public static void requestParser3FileReindex(
			@NotNull Project project,
			@NotNull Collection<VirtualFile> files,
			@NotNull String reason
	) {
		if (DEBUG) {
			System.out.println("[P3IndexMaintenance] requestParser3FileReindex reason=" + reason
					+ " project=" + project.getName()
					+ " files=" + files.size()
					+ " sample=" + samplePaths(files));
		}
		FileBasedIndex index = FileBasedIndex.getInstance();
		for (VirtualFile file : files) {
			if (file.isValid()) {
				index.requestReindex(file);
			}
		}
		if (!files.isEmpty()) {
			FileContentUtil.reparseFiles(project, files, true);
		}
	}

	public static void ensureParser3IndexesUpToDate(@NotNull Project project) {
		if (DumbService.getInstance(project).isDumb()) {
			return;
		}
		if (DEBUG) {
			System.out.println("[P3IndexMaintenance] indexes are smart project=" + project.getName());
		}
	}

	public static @NotNull GlobalSearchScope getParser3IndexScope(@NotNull Project project) {
		return GlobalSearchScope.allScope(project);
	}

	public static @NotNull List<VirtualFile> getIndexedParser3Files(@NotNull Project project) {
		LinkedHashSet<VirtualFile> result = new LinkedHashSet<>(Parser3FileUtils.getProjectParser3Files(project));
		if (DumbService.getInstance(project).isDumb()) {
			if (DEBUG) {
				System.out.println("[P3IndexMaintenance] getIndexedParser3Files dumb project=" + project.getName()
						+ " files=" + result.size());
			}
			return new ArrayList<>(result);
		}

		FileBasedIndex index = FileBasedIndex.getInstance();
		GlobalSearchScope scope = getParser3IndexScope(project);

		int beforeIndexKeys = result.size();
		collectFilesFromMethodIndex(index, project, scope, result);
		collectFilesFromClassIndex(index, project, scope, result);

		if (DEBUG) {
			System.out.println("[P3IndexMaintenance] getIndexedParser3Files project=" + project.getName()
					+ " beforeIndexKeys=" + beforeIndexKeys
					+ " total=" + result.size()
					+ " sample=" + samplePaths(result));
		}

		return new ArrayList<>(result);
	}

	private static void collectFilesFromMethodIndex(
			@NotNull FileBasedIndex index,
			@NotNull Project project,
			@NotNull GlobalSearchScope scope,
			@NotNull Set<VirtualFile> result
	) {
		for (String methodName : index.getAllKeys(P3MethodFileIndex.NAME, project)) {
			for (VirtualFile file : index.getContainingFiles(P3MethodFileIndex.NAME, methodName, scope)) {
				if (file.isValid()) {
					result.add(file);
				}
			}
		}
	}

	private static void collectFilesFromClassIndex(
			@NotNull FileBasedIndex index,
			@NotNull Project project,
			@NotNull GlobalSearchScope scope,
			@NotNull Set<VirtualFile> result
	) {
		for (String className : index.getAllKeys(P3ClassFileIndex.NAME, project)) {
			for (VirtualFile file : index.getContainingFiles(P3ClassFileIndex.NAME, className, scope)) {
				if (file.isValid()) {
					result.add(file);
				}
			}
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
