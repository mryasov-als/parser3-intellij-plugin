package ru.artlebedev.parser3.visibility;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.utils.Parser3ClassUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Считает эффективные опции Parser3 с учётом порядка подключения файлов.
 *
 * Индекс переменных хранит локальность, вычисленную только по тексту файла.
 * Для Parser3 этого недостаточно: @OPTIONS locals из уже подключённого auto.p
 * или use-файла действует на MAIN-методы последующих файлов.
 */
@Service(Service.Level.PROJECT)
public final class P3EffectiveScopeService {

	private final Project project;

	public P3EffectiveScopeService(@NotNull Project project) {
		this.project = project;
	}

	public static @NotNull P3EffectiveScopeService getInstance(@NotNull Project project) {
		return project.getService(P3EffectiveScopeService.class);
	}

	public boolean hasInheritedMainLocals(@NotNull VirtualFile file, int cursorOffset) {
		return buildInheritedMainLocalsMapForCurrentFile(file, cursorOffset).getOrDefault(file, false);
	}

	public boolean hasInheritedMainLocals(
			@NotNull VirtualFile file,
			@NotNull List<VirtualFile> orderedVisibleFiles
	) {
		return buildInheritedMainLocalsMap(orderedVisibleFiles).getOrDefault(file, false);
	}

	public @NotNull Map<VirtualFile, Boolean> buildInheritedMainLocalsMap(
			@NotNull List<VirtualFile> orderedVisibleFiles
	) {
		Map<VirtualFile, Boolean> result = new LinkedHashMap<>();
		boolean mainLocals = false;
		for (VirtualFile visibleFile : orderedVisibleFiles) {
			result.putIfAbsent(visibleFile, mainLocals);
			if (hasOwnMainLocals(visibleFile)) {
				mainLocals = true;
			}
		}
		return result;
	}

	public @NotNull Map<VirtualFile, Boolean> buildInheritedMainLocalsMapForCurrentFile(
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		List<VirtualFile> visibleFiles = P3VisibilityService.getInstance(project).getVisibleFiles(currentFile);
		Map<VirtualFile, Integer> useOffsetMap = P3ScopeContext.buildUseOffsetMap(project, currentFile);
		return buildInheritedMainLocalsMap(P3ScopeContext.filterPositionallyVisibleFiles(
				visibleFiles, currentFile, useOffsetMap, cursorOffset));
	}

	public boolean hasOwnMainLocals(@NotNull VirtualFile file) {
		String text = readUtf8(file);
		if (text == null) return false;
		return Parser3ClassUtils.hasMainLocals(text, Parser3ClassUtils.findClassBoundaries(text));
	}

	private static String readUtf8(@NotNull VirtualFile file) {
		if (!file.isValid() || file.isDirectory()) return null;
		com.intellij.openapi.editor.Document document = FileDocumentManager.getInstance().getDocument(file);
		if (document != null) {
			return document.getText();
		}
		try {
			return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			return null;
		}
	}
}
