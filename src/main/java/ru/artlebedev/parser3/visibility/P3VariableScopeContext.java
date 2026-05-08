package ru.artlebedev.parser3.visibility;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Контекст поиска переменных для конкретной позиции курсора.
 *
 * Здесь собраны данные, которые раньше вычислялись разрозненно в индексе:
 * список файлов поиска, offset подключений через ^use[] и унаследованный
 * @OPTIONS locals для каждого исходного файла.
 */
public final class P3VariableScopeContext {

	private final @NotNull P3ScopeContext scopeContext;
	private final @NotNull List<VirtualFile> searchFiles;

	public P3VariableScopeContext(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull List<VirtualFile> searchFiles,
			@NotNull Map<VirtualFile, Integer> useOffsetMap
	) {
		this.scopeContext = new P3ScopeContext(project, currentFile, cursorOffset);
		this.searchFiles = searchFiles;
	}

	public P3VariableScopeContext(
			@NotNull P3ScopeContext scopeContext
	) {
		this.scopeContext = scopeContext;
		this.searchFiles = scopeContext.getVariableSearchFiles();
	}

	public @NotNull List<VirtualFile> getSearchFiles() {
		return searchFiles;
	}

	public @NotNull VirtualFile getCurrentFile() {
		return scopeContext.getCurrentFile();
	}

	public int getCursorOffset() {
		return scopeContext.getCursorOffset();
	}

	public int getUseOffset(@NotNull VirtualFile sourceFile) {
		return scopeContext.getUseOffset(sourceFile);
	}

	public boolean isSourceVisibleAtCursor(@NotNull VirtualFile sourceFile) {
		return scopeContext.isSourceVisibleAtCursor(sourceFile);
	}

	public boolean hasInheritedMainLocalsForSourceFile(@NotNull VirtualFile sourceFile) {
		return scopeContext.hasInheritedMainLocalsForSourceFile(sourceFile);
	}
}
