package ru.artlebedev.parser3.visibility;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.index.P3MethodIndex;
import ru.artlebedev.parser3.index.P3UseFileIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.use.P3UseResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Общий контекст области видимости для конкретной позиции курсора.
 *
 * Этот класс собирает в одном месте правила выбора файлов для completion,
 * navigation, documentation и references. Низкоуровневый граф подключений
 * остаётся в P3VisibilityService, а позиционные правила Parser3 применяются тут.
 */
public final class P3ScopeContext {

	private static final int MAX_USE_OFFSET_DEPTH = 10;
	private static final boolean DEBUG_PERF = false;
	private static final Pattern REPLACE_USE_PATTERN = Pattern.compile(
			"\\^use\\s*\\[\\s*([^\\];]+)\\s*;[^\\]]*\\$\\.replace\\s*\\(\\s*true\\s*\\)[^\\]]*\\]",
			Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
	);

	private final @NotNull Project project;
	private final @NotNull VirtualFile currentFile;
	private final int cursorOffset;
	private final boolean allMethodsMode;
	private final @NotNull List<VirtualFile> visibleFiles;
	private final @NotNull List<VirtualFile> positionallyVisibleFiles;
	private final @NotNull List<VirtualFile> allProjectFiles;
	private final @NotNull List<VirtualFile> methodSearchFiles;
	private final @NotNull List<VirtualFile> classSearchFiles;
	private final @NotNull List<VirtualFile> variableSearchFiles;
	private final @NotNull Map<VirtualFile, Integer> useOffsetMap;
	private final @NotNull Map<VirtualFile, Boolean> inheritedMainLocalsMap;
	private final @Nullable P3ClassDeclaration currentClass;
	private final boolean hasAutouse;

	public P3ScopeContext(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		this.project = project;
		this.currentFile = currentFile;
		this.cursorOffset = cursorOffset;
		this.allMethodsMode = Parser3ProjectSettings.getInstance(project).getMethodCompletionMode()
				== Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS;

		long totalStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		P3VisibilityService visibilityService = P3VisibilityService.getInstance(project);
		long visibleStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.visibleFiles = visibilityService.getVisibleFiles(currentFile);
		logPerf("getVisibleFiles", visibleStart, currentFile, cursorOffset,
				"visibleFiles=" + visibleFiles.size());
		long allProjectStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.allProjectFiles = allMethodsMode
				? visibilityService.getAllProjectFiles()
				: new ArrayList<>();
		logPerf("getAllProjectFiles", allProjectStart, currentFile, cursorOffset,
				"allMethodsMode=" + allMethodsMode + " allProjectFiles=" + allProjectFiles.size());
		long useOffsetStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.useOffsetMap = buildUseOffsetMap(project, currentFile);
		logPerf("buildUseOffsetMap", useOffsetStart, currentFile, cursorOffset,
				"useOffsets=" + useOffsetMap.size());
		long positionalStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.positionallyVisibleFiles = filterPositionallyVisibleFiles(
				visibleFiles, currentFile, useOffsetMap, cursorOffset);
		logPerf("filterPositionallyVisibleFiles", positionalStart, currentFile, cursorOffset,
				"positionallyVisibleFiles=" + positionallyVisibleFiles.size());
		long modeStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.methodSearchFiles = buildModeSearchFiles();
		this.variableSearchFiles = methodSearchFiles;
		logPerf("buildModeSearchFiles", modeStart, currentFile, cursorOffset,
				"methodSearchFiles=" + methodSearchFiles.size());
		long inheritedStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.inheritedMainLocalsMap = P3EffectiveScopeService.getInstance(project)
				.buildInheritedMainLocalsMap(positionallyVisibleFiles);
		logPerf("buildInheritedMainLocalsMap", inheritedStart, currentFile, cursorOffset,
				"inheritedMainLocals=" + inheritedMainLocalsMap.size());
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		long classStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.currentClass = classIndex.findClassAtOffset(currentFile, cursorOffset);
		logPerf("findClassAtOffset", classStart, currentFile, cursorOffset,
				"currentClass=" + (currentClass != null ? currentClass.getName() : "null"));
		long autouseStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.hasAutouse = hasAutouseInFiles(project, methodSearchFiles);
		logPerf("hasAutouseInFiles", autouseStart, currentFile, cursorOffset,
				"hasAutouse=" + hasAutouse);
		long classSearchStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		this.classSearchFiles = buildClassSearchFiles(visibilityService);
		logPerf("buildClassSearchFiles", classSearchStart, currentFile, cursorOffset,
				"classSearchFiles=" + classSearchFiles.size());
		if (DEBUG_PERF) {
			System.out.println("[P3ScopeContext.PERF] TOTAL: "
					+ (System.currentTimeMillis() - totalStart) + "ms"
					+ " file=" + currentFile.getName()
					+ " offset=" + cursorOffset
					+ " allMethodsMode=" + allMethodsMode
					+ " visibleFiles=" + visibleFiles.size()
					+ " positionallyVisibleFiles=" + positionallyVisibleFiles.size()
					+ " methodSearchFiles=" + methodSearchFiles.size()
					+ " classSearchFiles=" + classSearchFiles.size()
					+ " variableSearchFiles=" + variableSearchFiles.size()
					+ " hasAutouse=" + hasAutouse);
		}
	}

	private static void logPerf(
			@NotNull String step,
			long startTime,
			@NotNull VirtualFile file,
			int cursorOffset,
			@NotNull String details
	) {
		if (!DEBUG_PERF) return;
		System.out.println("[P3ScopeContext.PERF] " + step + ": "
				+ (System.currentTimeMillis() - startTime) + "ms"
				+ " file=" + file.getName()
				+ " offset=" + cursorOffset
				+ " " + details);
	}

	private @NotNull List<VirtualFile> buildModeSearchFiles() {
		if (allMethodsMode) {
			return allProjectFiles;
		}
		return positionallyVisibleFiles;
	}

	private @NotNull List<VirtualFile> buildClassSearchFiles(@NotNull P3VisibilityService visibilityService) {
		if (allMethodsMode || hasAutouse) {
			return allMethodsMode ? allProjectFiles : visibilityService.getAllProjectFiles();
		}
		return methodSearchFiles;
	}

	public @NotNull Project getProject() {
		return project;
	}

	public @NotNull VirtualFile getCurrentFile() {
		return currentFile;
	}

	public int getCursorOffset() {
		return cursorOffset;
	}

	public boolean isAllMethodsMode() {
		return allMethodsMode;
	}

	public @NotNull List<VirtualFile> getVisibleFiles() {
		return visibleFiles;
	}

	public @NotNull List<VirtualFile> getPositionallyVisibleFiles() {
		return positionallyVisibleFiles;
	}

	public @NotNull List<VirtualFile> getMethodSearchFiles() {
		return methodSearchFiles;
	}

	public @NotNull List<VirtualFile> getClassSearchFiles() {
		return classSearchFiles;
	}

	public @NotNull List<VirtualFile> getClassSearchFilesForClass(@NotNull String className) {
		if (allMethodsMode || hasAutouse) {
			return classSearchFiles;
		}

		int replaceOffset = findLastReplaceUseOffsetForClass(className);
		if (replaceOffset < 0) {
			return classSearchFiles;
		}

		List<VirtualFile> result = new ArrayList<>();
		for (VirtualFile file : classSearchFiles) {
			if (file.equals(currentFile)) {
				result.add(file);
				continue;
			}

			Integer useOffset = useOffsetMap.get(file);
			if (useOffset != null && useOffset >= replaceOffset) {
				result.add(file);
			}
		}
		return result;
	}

	public @NotNull List<VirtualFile> getVariableSearchFiles() {
		return variableSearchFiles;
	}

	public @Nullable P3ClassDeclaration getCurrentClass() {
		return currentClass;
	}

	public boolean hasAutouse() {
		return hasAutouse;
	}

	public boolean canSeeMethodSourceFile(@NotNull VirtualFile sourceFile, @Nullable String ownerClassName) {
		if (methodSearchFiles.contains(sourceFile)) {
			return true;
		}
		return ownerClassName != null && !"MAIN".equals(ownerClassName) && hasAutouse;
	}

	public boolean canSeeClassSourceFile(@NotNull VirtualFile sourceFile) {
		return classSearchFiles.contains(sourceFile);
	}

	public int getUseOffset(@NotNull VirtualFile sourceFile) {
		return useOffsetMap.getOrDefault(sourceFile, -1);
	}

	public boolean isSourceVisibleAtCursor(@NotNull VirtualFile sourceFile) {
		return allMethodsMode || isSourcePositionallyVisibleAtCursor(sourceFile);
	}

	public boolean isSourcePositionallyVisibleAtCursor(@NotNull VirtualFile sourceFile) {
		return isSourcePositionallyVisibleAtCursor(sourceFile, currentFile, useOffsetMap, cursorOffset);
	}

	public boolean hasInheritedMainLocalsForSourceFile(@NotNull VirtualFile sourceFile) {
		Boolean inheritedFromCurrentVisibility = inheritedMainLocalsMap.get(sourceFile);
		if (inheritedFromCurrentVisibility != null) {
			return inheritedFromCurrentVisibility;
		}
		return P3EffectiveScopeService.getInstance(project)
				.hasInheritedMainLocals(sourceFile, getFileEndOffset(sourceFile));
	}

	public static @NotNull List<VirtualFile> getAllProjectFiles(@NotNull Project project) {
		return P3VisibilityService.getInstance(project).getAllProjectFiles();
	}

	public static @NotNull List<VirtualFile> getReverseClassSearchFiles(
			@NotNull Project project,
			@NotNull VirtualFile sourceFile
	) {
		if (isAllMethodsMode(project)) {
			return getAllProjectFiles(project);
		}

		List<VirtualFile> result = new ArrayList<>();
		for (VirtualFile candidateFile : getAllProjectFiles(project)) {
			P3ScopeContext candidateContext = new P3ScopeContext(project, candidateFile, getFileEndOffset(candidateFile));
			if (candidateContext.canSeeClassSourceFile(sourceFile)) {
				result.add(candidateFile);
			}
		}
		return result;
	}

	public static @NotNull List<VirtualFile> filterFilesThatCanSeeMethod(
			@NotNull Project project,
			@NotNull VirtualFile sourceFile,
			@Nullable String ownerClassName,
			@NotNull List<VirtualFile> candidateFiles
	) {
		if (isAllMethodsMode(project)) {
			return candidateFiles;
		}

		List<VirtualFile> result = new ArrayList<>();
		for (VirtualFile candidateFile : candidateFiles) {
			P3ScopeContext candidateContext = new P3ScopeContext(project, candidateFile, getFileEndOffset(candidateFile));
			if (candidateContext.canSeeMethodSourceFile(sourceFile, ownerClassName)) {
				result.add(candidateFile);
			}
		}
		return result;
	}

	private static boolean isAllMethodsMode(@NotNull Project project) {
		return Parser3ProjectSettings.getInstance(project).getMethodCompletionMode()
				== Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS;
	}

	private int findLastReplaceUseOffsetForClass(@NotNull String className) {
		P3UseResolver useResolver = P3UseResolver.getInstance(project);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		int result = -1;
		String text = readFileTextSmart(currentFile);

		Matcher matcher = REPLACE_USE_PATTERN.matcher(text);
		while (matcher.find()) {
			if (matcher.start() >= cursorOffset) continue;

			String usePath = matcher.group(1).trim();
			VirtualFile resolved = useResolver.resolve(usePath, currentFile);
			if (resolved == null || !resolved.isValid()) continue;

			for (P3ClassDeclaration cls : classIndex.findInFile(resolved)) {
				if (className.equals(cls.getName())) {
					result = Math.max(result, matcher.start());
					break;
				}
			}
		}

		return result;
	}

	private static @NotNull String readFileTextSmart(@NotNull VirtualFile file) {
		com.intellij.openapi.editor.Document document =
				com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
		if (document != null) {
			return document.getText();
		}
		try {
			return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			return "";
		}
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

	public static @NotNull List<VirtualFile> filterPositionallyVisibleFiles(
			@NotNull List<VirtualFile> files,
			@NotNull VirtualFile currentFile,
			@NotNull Map<VirtualFile, Integer> useOffsetMap,
			int cursorOffset
	) {
		List<VirtualFile> result = new ArrayList<>();
		for (VirtualFile file : files) {
			if (isSourcePositionallyVisibleAtCursor(file, currentFile, useOffsetMap, cursorOffset)) {
				result.add(file);
			}
		}
		return result;
	}

	public static boolean isSourcePositionallyVisibleAtCursor(
			@NotNull VirtualFile sourceFile,
			@NotNull VirtualFile currentFile,
			@NotNull Map<VirtualFile, Integer> useOffsetMap,
			int cursorOffset
	) {
		if (sourceFile.equals(currentFile)) return true;
		Integer useOffset = useOffsetMap.get(sourceFile);
		return useOffset == null || useOffset < cursorOffset;
	}

	public static @NotNull Map<VirtualFile, Integer> buildUseOffsetMap(
			@NotNull Project project,
			@NotNull VirtualFile currentFile
	) {
		Map<VirtualFile, Integer> map = new HashMap<>();
		Set<VirtualFile> visited = new HashSet<>();
		visited.add(currentFile);

		P3UseResolver useResolver = P3UseResolver.getInstance(project);
		FileBasedIndex index = FileBasedIndex.getInstance();
		Map<String, List<P3UseFileIndex.UseInfo>> useData = index.getFileData(P3UseFileIndex.NAME, currentFile, project);

		for (List<P3UseFileIndex.UseInfo> infos : useData.values()) {
			for (P3UseFileIndex.UseInfo info : infos) {
				if (info.inComment) continue;
				VirtualFile resolved = useResolver.resolve(info.fullPath, currentFile);
				if (resolved != null && !visited.contains(resolved)) {
					addTransitiveUseOffsets(project, resolved, info.offset, map, visited, useResolver, 0);
				}
			}
		}

		return map;
	}

	private static void addTransitiveUseOffsets(
			@NotNull Project project,
			@NotNull VirtualFile file,
			int rootUseOffset,
			@NotNull Map<VirtualFile, Integer> map,
			@NotNull Set<VirtualFile> visited,
			@NotNull P3UseResolver useResolver,
			int depth
	) {
		if (depth >= MAX_USE_OFFSET_DEPTH || !visited.add(file)) return;

		map.put(file, rootUseOffset);

		FileBasedIndex index = FileBasedIndex.getInstance();
		Map<String, List<P3UseFileIndex.UseInfo>> useData = index.getFileData(P3UseFileIndex.NAME, file, project);

		for (List<P3UseFileIndex.UseInfo> infos : useData.values()) {
			for (P3UseFileIndex.UseInfo info : infos) {
				if (info.inComment) continue;
				VirtualFile resolved = useResolver.resolve(info.fullPath, file);
				if (resolved != null && !visited.contains(resolved)) {
					addTransitiveUseOffsets(project, resolved, rootUseOffset, map, visited, useResolver, depth + 1);
				}
			}
		}
	}

	private static boolean hasAutouseInFiles(
			@NotNull Project project,
			@NotNull List<VirtualFile> files
	) {
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		for (VirtualFile file : files) {
			List<P3MethodDeclaration> methods = methodIndex.findInFiles("autouse", java.util.Collections.singletonList(file));
			for (P3MethodDeclaration method : methods) {
				P3ClassDeclaration clazz = classIndex.findClassAtOffset(method.getFile(), method.getOffset());
				if (clazz == null || clazz.isMainClass() || "MAIN".equals(clazz.getName())) {
					return true;
				}
			}
		}
		return false;
	}
}
