package ru.artlebedev.parser3.visibility;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;

import java.nio.charset.StandardCharsets;
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

	private final @Nullable P3ScopeContext scopeContext;
	private final @NotNull Project project;
	private final @NotNull VirtualFile currentFile;
	private final int cursorOffset;
	private final boolean allMethodsMode;
	private final @NotNull List<VirtualFile> searchFiles;
	private final @NotNull Map<VirtualFile, Integer> useOffsetMap;
	private final @NotNull Map<VirtualFile, Boolean> inheritedMainLocalsMap;

	public P3VariableScopeContext(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset,
			@NotNull List<VirtualFile> searchFiles,
			@NotNull Map<VirtualFile, Integer> useOffsetMap
	) {
		this.scopeContext = null;
		this.project = project;
		this.currentFile = currentFile;
		this.cursorOffset = cursorOffset;
		this.allMethodsMode = Parser3ProjectSettings.getInstance(project).getMethodCompletionMode()
				== Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS;
		this.searchFiles = searchFiles;
		this.useOffsetMap = useOffsetMap;
		this.inheritedMainLocalsMap = P3EffectiveScopeService.getInstance(project)
				.buildInheritedMainLocalsMap(searchFiles);
	}

	public P3VariableScopeContext(
			@NotNull P3ScopeContext scopeContext
	) {
		this.scopeContext = scopeContext;
		this.project = scopeContext.getProject();
		this.currentFile = scopeContext.getCurrentFile();
		this.cursorOffset = scopeContext.getCursorOffset();
		this.allMethodsMode = scopeContext.isAllMethodsMode();
		this.searchFiles = scopeContext.getVariableSearchFiles();
		this.useOffsetMap = java.util.Collections.emptyMap();
		this.inheritedMainLocalsMap = java.util.Collections.emptyMap();
	}

	public @NotNull List<VirtualFile> getSearchFiles() {
		return searchFiles;
	}

	public @NotNull VirtualFile getCurrentFile() {
		return currentFile;
	}

	public int getCursorOffset() {
		return cursorOffset;
	}

	public int getUseOffset(@NotNull VirtualFile sourceFile) {
		if (scopeContext != null) {
			return scopeContext.getUseOffset(sourceFile);
		}
		return useOffsetMap.getOrDefault(sourceFile, -1);
	}

	public boolean isSourceVisibleAtCursor(@NotNull VirtualFile sourceFile) {
		if (scopeContext != null) {
			return scopeContext.isSourceVisibleAtCursor(sourceFile);
		}
		return allMethodsMode || P3ScopeContext.isSourcePositionallyVisibleAtCursor(
				sourceFile, currentFile, useOffsetMap, cursorOffset);
	}

	public boolean hasInheritedMainLocalsForSourceFile(@NotNull VirtualFile sourceFile) {
		if (scopeContext != null) {
			return scopeContext.hasInheritedMainLocalsForSourceFile(sourceFile);
		}
		Boolean inheritedFromCurrentVisibility = inheritedMainLocalsMap.get(sourceFile);
		if (inheritedFromCurrentVisibility != null) {
			return inheritedFromCurrentVisibility;
		}
		return P3EffectiveScopeService.getInstance(project)
				.hasInheritedMainLocals(sourceFile, getFileEndOffset(sourceFile));
	}

	private static int getFileEndOffset(@NotNull VirtualFile file) {
		com.intellij.openapi.editor.Document document =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
		if (document != null) {
			return document.getTextLength();
		}
		try {
			return new String(file.contentsToByteArray(), StandardCharsets.UTF_8).length();
		} catch (Exception ignored) {
			return 0;
		}
	}
}
