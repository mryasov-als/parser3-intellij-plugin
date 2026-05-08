package ru.artlebedev.parser3.index;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.indexing.IndexableSetContributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.utils.Parser3FileUtils;

import java.util.Collections;
import java.util.Set;

/**
 * Добавляет открытую папку проекта в обычную индексацию IntelliJ для Parser3-файлов.
 */
public final class P3IndexableSetContributor extends IndexableSetContributor {
	private static final boolean DEBUG = false;

	@Override
	public @NotNull Set<VirtualFile> getAdditionalProjectRootsToIndex(@NotNull Project project) {
		VirtualFile baseDir = project.getBaseDir();
		if (baseDir == null || !baseDir.isValid()) {
			return Collections.emptySet();
		}

		if (DEBUG) {
			System.out.println("[P3IndexableSetContributor] addProjectRoot project=" + project.getName()
					+ " root=" + baseDir.getPath());
		}
		return Set.of(baseDir);
	}

	@Override
	public boolean acceptFile(@NotNull VirtualFile file, @NotNull VirtualFile root, @Nullable Project project) {
		boolean accepted = project != null
				? Parser3FileUtils.isProjectIndexableParser3File(project, file)
				: Parser3FileUtils.isParser3File(file);
		if (DEBUG && accepted && project != null) {
			System.out.println("[P3IndexableSetContributor] accept project=" + project.getName()
					+ " file=" + file.getPath());
		}
		return accepted;
	}

	@Override
	public @NotNull Set<VirtualFile> getAdditionalRootsToIndex() {
		return Collections.emptySet();
	}

	@Override
	public @NotNull String getDebugName() {
		return "Parser3 directory-based project root";
	}
}
