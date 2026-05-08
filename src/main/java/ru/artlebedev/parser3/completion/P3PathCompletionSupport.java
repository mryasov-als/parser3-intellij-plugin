package ru.artlebedev.parser3.completion;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3FileType;
import ru.artlebedev.parser3.classpath.P3ClassPathEvaluator;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Общая логика определения path-контекста и сбора кандидатов.
 */
public final class P3PathCompletionSupport {

	private static final int USE_SCAN_WINDOW = 200;
	private static final int CLASS_PATH_SCAN_WINDOW = 300;
	private static final int GENERIC_SCAN_WINDOW = 400;

	private P3PathCompletionSupport() {}

	public static @Nullable P3PathCompletionContext detectContext(@NotNull CharSequence text, int offset) {
		offset = clampOffset(text, offset);

		P3PathCompletionContext useContext = detectUseContext(text, offset);
		if (useContext != null) {
			return useContext;
		}

		P3PathCompletionContext classPathContext = detectClassPathContext(text, offset);
		if (classPathContext != null) {
			return classPathContext;
		}

		return detectGenericMethodContext(text, offset);
	}

	public static boolean isInPathContext(@NotNull CharSequence text, int offset) {
		return detectContext(text, offset) != null;
	}

	public static boolean shouldAutoPopupOnTypedChar(@NotNull CharSequence text, int offset, char typedChar) {
		offset = clampOffset(text, offset);
		return switch (typedChar) {
			case '/', '.' -> isInPathContext(text, offset);
			case '[' -> shouldAutoPopupAfterOpenBracket(text, offset);
			case '{' -> shouldAutoPopupAfterOpenBrace(text, offset);
			case ';' -> shouldAutoPopupAfterSemicolon(text, offset);
			default -> false;
		};
	}

	public static @NotNull String normalizePath(@NotNull String path) {
		path = path.trim();

		if (!path.isEmpty()) {
			char first = path.charAt(0);
			if (first == '"' || first == '\'') {
				path = path.substring(1);
			}
		}

		return path.replace('\\', '/');
	}

	public static @NotNull List<CompletionCandidate> getCompletionCandidates(
			@NotNull Project project,
			@NotNull VirtualFile contextFile,
			@NotNull String partialPath,
			@NotNull P3PathCompletionContext.Profile profile
	) {
		PathCollector collector = new PathCollector(project, contextFile, normalizePath(partialPath), profile);
		return collector.collect();
	}

	private static @Nullable P3PathCompletionContext detectUseContext(@NotNull CharSequence text, int offset) {
		int searchStart = Math.max(0, offset - USE_SCAN_WINDOW);
		String before = text.subSequence(searchStart, offset).toString();

		int useIndex = before.lastIndexOf("^use[");
		if (useIndex >= 0) {
			String afterUse = before.substring(useIndex + 5);
			if (!afterUse.contains("]")) {
				String path = stripLeadingQuote(afterUse);
				return new P3PathCompletionContext(
						P3PathCompletionContext.Profile.USE,
						path,
						P3PathCompletionContext.InsertMode.ARGUMENT
				);
			}
		}

		int atUseIndex = before.lastIndexOf("@USE");
		if (atUseIndex >= 0) {
			String afterUse = before.substring(atUseIndex + 4);
			if (!afterUse.contains("@")) {
				int lastNewline = before.lastIndexOf('\n');
				String path;
				if (lastNewline >= 0) {
					path = before.substring(lastNewline + 1).trim();
				} else {
					path = afterUse.replaceFirst("^[\\s]+", "");
				}
				return new P3PathCompletionContext(
						P3PathCompletionContext.Profile.USE,
						path,
						P3PathCompletionContext.InsertMode.LINE
				);
			}
		}

		return null;
	}

	private static @Nullable P3PathCompletionContext detectClassPathContext(@NotNull CharSequence text, int offset) {
		int searchStart = Math.max(0, offset - CLASS_PATH_SCAN_WINDOW);
		String before = text.subSequence(searchStart, offset).toString();

		int assignIdx = Math.max(
				before.lastIndexOf("$CLASS_PATH["),
				before.lastIndexOf("$MAIN:CLASS_PATH[")
		);
		int appendIdx = Math.max(
				before.lastIndexOf("^CLASS_PATH.append{"),
				before.lastIndexOf("^MAIN:CLASS_PATH.append{")
		);
		int tableIdx = Math.max(
				before.lastIndexOf("$CLASS_PATH[^table::create{"),
				before.lastIndexOf("$MAIN:CLASS_PATH[^table::create{")
		);

		if (tableIdx >= 0) {
			String afterTable = before.substring(tableIdx);
			int braceCount = 0;
			int bracketCount = 0;
			for (char ch : afterTable.toCharArray()) {
				if (ch == '{') braceCount++;
				else if (ch == '}') braceCount--;
				else if (ch == '[') bracketCount++;
				else if (ch == ']') bracketCount--;
			}
			if (braceCount > 0 && bracketCount > 0) {
				int lastNewline = before.lastIndexOf('\n');
				String currentLine = lastNewline >= 0 ? before.substring(lastNewline + 1).trim() : before.trim();
				if (currentLine.isEmpty() || currentLine.startsWith("/") || currentLine.startsWith("..")) {
					return new P3PathCompletionContext(
							P3PathCompletionContext.Profile.CLASS_PATH,
							currentLine,
							P3PathCompletionContext.InsertMode.LINE
					);
				}
			}
		}

		if (appendIdx >= 0) {
			String afterAppend = before.substring(appendIdx);
			int braceCount = 0;
			for (char ch : afterAppend.toCharArray()) {
				if (ch == '{') braceCount++;
				else if (ch == '}') braceCount--;
			}
			if (braceCount > 0) {
				int braceIdx = afterAppend.lastIndexOf('{');
				String path = braceIdx >= 0 ? afterAppend.substring(braceIdx + 1).trim() : "";
				return new P3PathCompletionContext(
						P3PathCompletionContext.Profile.CLASS_PATH,
						path,
						P3PathCompletionContext.InsertMode.BRACKET
				);
			}
		}

		if (assignIdx >= 0 && assignIdx != tableIdx) {
			String afterAssign = before.substring(assignIdx);
			if (!afterAssign.contains("^table::create{")) {
				int bracketCount = 0;
				for (char ch : afterAssign.toCharArray()) {
					if (ch == '[') bracketCount++;
					else if (ch == ']') bracketCount--;
				}
				if (bracketCount > 0) {
					int bracketIdx = afterAssign.lastIndexOf('[');
					String path = bracketIdx >= 0 ? afterAssign.substring(bracketIdx + 1).trim() : "";
					return new P3PathCompletionContext(
							P3PathCompletionContext.Profile.CLASS_PATH,
							path,
							P3PathCompletionContext.InsertMode.BRACKET
					);
				}
			}
		}

		return null;
	}

	private static @Nullable P3PathCompletionContext detectGenericMethodContext(@NotNull CharSequence text, int offset) {
		int openIndex = findNearestUnclosedOpen(text, offset);
		if (openIndex < 0) {
			return null;
		}

		char openChar = text.charAt(openIndex);
		if (openChar != '[') {
			return null;
		}

		int scanStart = Math.max(0, openIndex - GENERIC_SCAN_WINDOW);
		String prefix = text.subSequence(scanStart, openIndex + 1).toString();
		String prefixLower = prefix.toLowerCase(Locale.ROOT);

		MethodPattern pattern = findMethodPattern(prefixLower);
		if (pattern == null) {
			return null;
		}

		ArgumentInfo argumentInfo = parseArgumentInfo(text, openIndex + 1, offset);
		if (!pattern.matches(argumentInfo)) {
			return null;
		}

		return new P3PathCompletionContext(
				pattern.profile,
				argumentInfo.currentArgText,
				P3PathCompletionContext.InsertMode.ARGUMENT
		);
	}

	private static @Nullable MethodPattern findMethodPattern(@NotNull String prefixLower) {
		MethodPattern[] patterns = MethodPattern.values();
		for (MethodPattern pattern : patterns) {
			if (prefixLower.endsWith(pattern.trigger)) {
				return pattern;
			}
		}
		return null;
	}

	private static boolean shouldAutoPopupAfterOpenBracket(@NotNull CharSequence text, int offset) {
		int searchStart = Math.max(0, offset - GENERIC_SCAN_WINDOW);
		String prefixLower = text.subSequence(searchStart, offset).toString().toLowerCase(Locale.ROOT);

		return prefixLower.endsWith("^use")
				|| prefixLower.endsWith("$class_path")
				|| prefixLower.endsWith("$main:class_path")
				|| prefixLower.endsWith("^table::load")
				|| prefixLower.endsWith("^xdoc::load")
				|| prefixLower.endsWith("^image::load")
				|| prefixLower.endsWith("^image::measure")
				|| prefixLower.endsWith("^hashfile::open")
				|| prefixLower.endsWith("^file::stat")
				|| prefixLower.endsWith("^file:list");
	}

	private static boolean shouldAutoPopupAfterOpenBrace(@NotNull CharSequence text, int offset) {
		int searchStart = Math.max(0, offset - CLASS_PATH_SCAN_WINDOW);
		String prefixLower = text.subSequence(searchStart, offset).toString().toLowerCase(Locale.ROOT);
		return prefixLower.endsWith("^class_path.append") || prefixLower.endsWith("^main:class_path.append");
	}

	private static boolean shouldAutoPopupAfterSemicolon(@NotNull CharSequence text, int offset) {
		int openIndex = findNearestUnclosedOpen(text, offset);
		if (openIndex < 0 || text.charAt(openIndex) != '[') {
			return false;
		}

		int scanStart = Math.max(0, openIndex - GENERIC_SCAN_WINDOW);
		String prefixLower = text.subSequence(scanStart, openIndex + 1).toString().toLowerCase(Locale.ROOT);
		MethodPattern pattern = findMethodPattern(prefixLower);
		if (pattern == null) {
			return false;
		}

		ArgumentInfo info = parseArgumentInfo(text, openIndex + 1, offset);
		return switch (pattern) {
			case FILE_LOAD, FONT -> info.argIndex == 0;
			case TABLE_LOAD -> info.argIndex == 0 && !info.args.isEmpty() && "nameless".equalsIgnoreCase(info.args.get(0));
			case FILE_EXEC, FILE_CGI -> info.argIndex == 0
					&& !info.args.isEmpty()
					&& ("text".equalsIgnoreCase(info.args.get(0)) || "binary".equalsIgnoreCase(info.args.get(0)));
			default -> false;
		};
	}

	private static @NotNull ArgumentInfo parseArgumentInfo(@NotNull CharSequence text, int start, int end) {
		int squareDepth = 0;
		int braceDepth = 0;
		int parenDepth = 0;
		int argIndex = 0;
		int currentArgStart = start;

		for (int i = start; i < end; i++) {
			char ch = text.charAt(i);
			if (ch == '[') squareDepth++;
			else if (ch == ']') {
				if (squareDepth > 0) squareDepth--;
			} else if (ch == '{') braceDepth++;
			else if (ch == '}') {
				if (braceDepth > 0) braceDepth--;
			} else if (ch == '(') parenDepth++;
			else if (ch == ')') {
				if (parenDepth > 0) parenDepth--;
			} else if (ch == ';' && squareDepth == 0 && braceDepth == 0 && parenDepth == 0) {
				argIndex++;
				currentArgStart = i + 1;
			}
		}

		String currentArg = text.subSequence(currentArgStart, end).toString().trim();
		List<String> args = new ArrayList<>();
		String raw = text.subSequence(start, end).toString();
		splitTopLevelArgs(raw, args);
		return new ArgumentInfo(argIndex, currentArg, args);
	}

	private static void splitTopLevelArgs(@NotNull String raw, @NotNull List<String> target) {
		int squareDepth = 0;
		int braceDepth = 0;
		int parenDepth = 0;
		int argStart = 0;

		for (int i = 0; i < raw.length(); i++) {
			char ch = raw.charAt(i);
			if (ch == '[') squareDepth++;
			else if (ch == ']') {
				if (squareDepth > 0) squareDepth--;
			} else if (ch == '{') braceDepth++;
			else if (ch == '}') {
				if (braceDepth > 0) braceDepth--;
			} else if (ch == '(') parenDepth++;
			else if (ch == ')') {
				if (parenDepth > 0) parenDepth--;
			} else if (ch == ';' && squareDepth == 0 && braceDepth == 0 && parenDepth == 0) {
				target.add(raw.substring(argStart, i).trim());
				argStart = i + 1;
			}
		}

		target.add(raw.substring(argStart).trim());
	}

	private static int findNearestUnclosedOpen(@NotNull CharSequence text, int offset) {
		int squareDepth = 0;
		int braceDepth = 0;
		int parenDepth = 0;

		for (int i = offset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == ']') squareDepth++;
			else if (ch == '}') braceDepth++;
			else if (ch == ')') parenDepth++;
			else if (ch == '[') {
				if (squareDepth > 0) {
					squareDepth--;
				} else {
					return i;
				}
			} else if (ch == '{') {
				if (braceDepth > 0) {
					braceDepth--;
				}
			} else if (ch == '(') {
				if (parenDepth > 0) {
					parenDepth--;
				}
			}
		}

		return -1;
	}

	private static @NotNull String stripLeadingQuote(@NotNull String text) {
		if (!text.isEmpty()) {
			char ch = text.charAt(0);
			if (ch == '"' || ch == '\'') {
				return text.substring(1);
			}
		}
		return text;
	}

	public static int clampOffset(@NotNull CharSequence text, int offset) {
		if (offset < 0) {
			return 0;
		}
		return Math.min(offset, text.length());
	}

	private static final class ArgumentInfo {
		private final int argIndex;
		private final @NotNull String currentArgText;
		private final @NotNull List<String> args;

		private ArgumentInfo(int argIndex, @NotNull String currentArgText, @NotNull List<String> args) {
			this.argIndex = argIndex;
			this.currentArgText = currentArgText;
			this.args = args;
		}
	}

	private enum MethodPattern {
		FILE_LOAD("^file::load[", P3PathCompletionContext.Profile.ANY_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 1;
			}
		},
		FILE_EXEC("^file::exec[", P3PathCompletionContext.Profile.ANY_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 0 || info.argIndex == 1;
			}
		},
		FILE_CGI("^file::cgi[", P3PathCompletionContext.Profile.ANY_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 0 || info.argIndex == 1;
			}
		},
		TABLE_LOAD("^table::load[", P3PathCompletionContext.Profile.ANY_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				if (info.argIndex == 0) {
					return true;
				}
				return info.argIndex == 1
						&& !info.args.isEmpty()
						&& "nameless".equalsIgnoreCase(info.args.get(0));
			}
		},
		XDOC_LOAD("^xdoc::load[", P3PathCompletionContext.Profile.XML_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 0;
			}
		},
		IMAGE_LOAD("^image::load[", P3PathCompletionContext.Profile.GIF_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 0;
			}
		},
		IMAGE_MEASURE("^image::measure[", P3PathCompletionContext.Profile.IMAGE_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 0;
			}
		},
		HASHFILE_OPEN("^hashfile::open[", P3PathCompletionContext.Profile.ANY_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 0;
			}
		},
		FILE_STAT("^file::stat[", P3PathCompletionContext.Profile.ANY_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 0;
			}
		},
		FILE_LIST("^file:list[", P3PathCompletionContext.Profile.DIRECTORY_ONLY) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 0;
			}
		},
		FONT(".font[", P3PathCompletionContext.Profile.GIF_FILE) {
			@Override
			boolean matches(@NotNull ArgumentInfo info) {
				return info.argIndex == 1;
			}
		};

		private final @NotNull String trigger;
		private final @NotNull P3PathCompletionContext.Profile profile;

		MethodPattern(@NotNull String trigger, @NotNull P3PathCompletionContext.Profile profile) {
			this.trigger = trigger;
			this.profile = profile;
		}

		abstract boolean matches(@NotNull ArgumentInfo info);
	}

	public static final class CompletionCandidate {
		private final @NotNull String insertText;
		private final @Nullable VirtualFile file;
		private final boolean directory;

		public CompletionCandidate(@NotNull String insertText, @Nullable VirtualFile file, boolean directory) {
			this.insertText = insertText;
			this.file = file;
			this.directory = directory;
		}

		public @NotNull String getInsertText() {
			return insertText;
		}

		public @Nullable VirtualFile getFile() {
			return file;
		}

		public boolean isDirectory() {
			return directory;
		}
	}

	private static final class PathCollector {
		private final @NotNull Project project;
		private final @NotNull VirtualFile contextFile;
		private final @NotNull String partialPath;
		private final @NotNull P3PathCompletionContext.Profile profile;
		private final @NotNull List<CompletionCandidate> result = new ArrayList<>();
		private final @NotNull Set<String> addedPaths = new HashSet<>();

		private PathCollector(
				@NotNull Project project,
				@NotNull VirtualFile contextFile,
				@NotNull String partialPath,
				@NotNull P3PathCompletionContext.Profile profile
		) {
			this.project = project;
			this.contextFile = contextFile;
			this.partialPath = partialPath;
			this.profile = profile;
		}

		private @NotNull List<CompletionCandidate> collect() {
			if (profile.getResolveMode() == P3PathCompletionContext.ResolveMode.USE) {
				collectUseCandidates();
			} else {
				collectGenericCandidates();
			}
			return result;
		}

		private void collectUseCandidates() {
			VirtualFile contextDir = contextFile.getParent();
			if (contextDir == null) {
				return;
			}

			if (partialPath.startsWith("/")) {
				collectAbsoluteCandidates(partialPath);
			} else {
				collectUseRelativeCandidates(partialPath);
			}
		}

		private void collectGenericCandidates() {
			VirtualFile contextDir = contextFile.getParent();
			if (contextDir == null) {
				return;
			}

			if (partialPath.startsWith("/")) {
				collectAbsoluteCandidates(partialPath);
			} else {
				collectGenericRelativeCandidates(partialPath);
			}
		}

		private void collectAbsoluteCandidates(@NotNull String path) {
			Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
			VirtualFile documentRoot = settings.getDocumentRootWithFallback();
			if (documentRoot == null || !documentRoot.isValid()) {
				return;
			}

			String relativePath = path.length() > 1 ? path.substring(1) : "";
			SearchLocation location = findSearchLocation(documentRoot, relativePath, "/");
			if (location == null) {
				return;
			}

			collectEntriesFromDir(location.dir, location.prefix, 0);
		}

		private void collectUseRelativeCandidates(@NotNull String path) {
			VirtualFile contextDir = contextFile.getParent();
			if (contextDir == null) {
				return;
			}

			VirtualFile searchDir = contextDir;
			String prefix = "";
			int upLevels = 0;
			String remaining = path;

			while (remaining.startsWith("../")) {
				upLevels++;
				remaining = remaining.substring(3);
				VirtualFile parent = searchDir.getParent();
				if (parent == null) {
					searchDir = contextDir;
					prefix = "";
					upLevels = 0;
					remaining = stripDotDotPrefix(path);
					break;
				}
				searchDir = parent;
				prefix = "../".repeat(upLevels);
			}

			SearchLocation location = findSearchLocation(searchDir, remaining, prefix);
			if (location != null) {
				collectEntriesFromDir(location.dir, location.prefix, 0);
			}

			if (upLevels > 0 && searchDir != contextDir) {
				String fallbackPrefix = "../".repeat(upLevels);
				collectEntriesFromDir(contextDir, fallbackPrefix, 0);
			}

			collectUseClassPathCandidates(upLevels);
			addParentShortcutIfNeeded();
		}

		private void collectGenericRelativeCandidates(@NotNull String path) {
			VirtualFile contextDir = contextFile.getParent();
			if (contextDir == null) {
				return;
			}

			VirtualFile searchDir = contextDir;
			String prefix = "";
			String remaining = path;

			while (remaining.startsWith("../")) {
				VirtualFile parent = searchDir.getParent();
				if (parent == null) {
					break;
				}
				searchDir = parent;
				prefix += "../";
				remaining = remaining.substring(3);
			}

			SearchLocation location = findSearchLocation(searchDir, remaining, prefix);
			if (location != null) {
				collectEntriesFromDir(location.dir, location.prefix, 0);
			}

			addParentShortcutIfNeeded();
		}

		private void collectUseClassPathCandidates(int upLevels) {
			P3ClassPathEvaluator evaluator = new P3ClassPathEvaluator(project);
			String prefix = upLevels > 0 ? "../".repeat(upLevels) : "";
			for (VirtualFile classPathDir : evaluator.getClassPathDirs(contextFile)) {
				collectEntriesFromDir(classPathDir, prefix, 0);
			}
		}

		private void addParentShortcutIfNeeded() {
			if (!profile.allowsDirectories()) {
				return;
			}
			if (partialPath.startsWith("/")) {
				return;
			}
			VirtualFile contextDir = contextFile.getParent();
			if (contextDir == null || contextDir.getParent() == null) {
				return;
			}
			if (partialPath.isEmpty() || !partialPath.contains("/")) {
				addCandidate("../", null, true);
			}
		}

		private @Nullable SearchLocation findSearchLocation(
				@NotNull VirtualFile baseDir,
				@NotNull String path,
				@NotNull String prefix
		) {
			VirtualFile searchDir = baseDir;
			String resultPrefix = prefix;

			if (!path.isEmpty()) {
				int lastSlash = path.lastIndexOf('/');
				if (lastSlash >= 0) {
					String dirPath = path.substring(0, lastSlash);
					if (!dirPath.isEmpty()) {
						VirtualFile subDir = searchDir.findFileByRelativePath(dirPath);
						if (subDir == null || !subDir.isDirectory()) {
							return null;
						}
						searchDir = subDir;
						resultPrefix += dirPath + "/";
					}
				}
			}

			return new SearchLocation(searchDir, resultPrefix);
		}

		private void collectEntriesFromDir(@NotNull VirtualFile dir, @NotNull String prefix, int depth) {
			if (!dir.isDirectory() || depth > 10) {
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
					if (profile.allowsDirectories()) {
						addCandidate(prefix + name + "/", child, true);
					}
					collectEntriesFromDir(child, prefix + name + "/", depth + 1);
				} else if (profile.acceptsFileName(name)) {
					addCandidate(prefix + name, child, false);
				}
			}
		}

		private void addCandidate(@NotNull String insertText, @Nullable VirtualFile file, boolean directory) {
			if (addedPaths.add(insertText)) {
				result.add(new CompletionCandidate(insertText, file, directory));
			}
		}

		private @NotNull String stripDotDotPrefix(@NotNull String path) {
			String resultPath = path;
			while (resultPath.startsWith("../")) {
				resultPath = resultPath.substring(3);
			}
			return resultPath;
		}
	}

	private static final class SearchLocation {
		private final @NotNull VirtualFile dir;
		private final @NotNull String prefix;

		private SearchLocation(@NotNull VirtualFile dir, @NotNull String prefix) {
			this.dir = dir;
			this.prefix = prefix;
		}
	}
}
