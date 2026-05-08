package ru.artlebedev.parser3.completion;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * Контекст автокомплита путей в Parser3.
 */
public final class P3PathCompletionContext {

	public enum ResolveMode {
		USE,
		GENERIC
	}

	public enum InsertMode {
		LINE,
		BRACKET,
		ARGUMENT
	}

	public enum Profile {
		USE(ResolveMode.USE, false, true, "parser3 file", Set.of("p", "p3"), false),
		CLASS_PATH(ResolveMode.GENERIC, true, false, "directory", Set.of(), true),
		DIRECTORY_ONLY(ResolveMode.GENERIC, true, false, "directory", Set.of(), true),
		ANY_FILE(ResolveMode.GENERIC, false, true, "file", Set.of(), false),
		XML_FILE(ResolveMode.GENERIC, false, true, "xml file", Set.of("xml", "xsl", "xsd"), false),
		GIF_FILE(ResolveMode.GENERIC, false, true, "gif file", Set.of("gif"), false),
		IMAGE_FILE(ResolveMode.GENERIC, false, true, "image file", Set.of("gif", "jpg", "jpeg", "png", "tif", "tiff", "bmp", "webp", "mp4", "mov"), false);

		private final @NotNull ResolveMode resolveMode;
		private final boolean allowDirectories;
		private final boolean allowFiles;
		private final @NotNull String fileTypeText;
		private final @NotNull Set<String> extensions;
		private final boolean directoriesOnly;

		Profile(
				@NotNull ResolveMode resolveMode,
				boolean allowDirectories,
				boolean allowFiles,
				@NotNull String fileTypeText,
				@NotNull Set<String> extensions,
				boolean directoriesOnly
		) {
			this.resolveMode = resolveMode;
			this.allowDirectories = allowDirectories;
			this.allowFiles = allowFiles;
			this.fileTypeText = fileTypeText;
			this.extensions = extensions;
			this.directoriesOnly = directoriesOnly;
		}

		public @NotNull ResolveMode getResolveMode() {
			return resolveMode;
		}

		public boolean allowsDirectories() {
			return allowDirectories;
		}

		public boolean allowsFiles() {
			return allowFiles;
		}

		public boolean isDirectoriesOnly() {
			return directoriesOnly;
		}

		public @NotNull String getFileTypeText() {
			return fileTypeText;
		}

		public boolean acceptsFileName(@NotNull String name) {
			if (!allowFiles || directoriesOnly) {
				return false;
			}
			if (extensions.isEmpty()) {
				return true;
			}
			int dot = name.lastIndexOf('.');
			if (dot < 0 || dot == name.length() - 1) {
				return false;
			}
			String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
			return extensions.contains(ext);
		}
	}

	private final @NotNull Profile profile;
	private final @NotNull String originalPath;
	private final @NotNull InsertMode insertMode;

	public P3PathCompletionContext(
			@NotNull Profile profile,
			@NotNull String originalPath,
			@NotNull InsertMode insertMode
	) {
		this.profile = profile;
		this.originalPath = originalPath;
		this.insertMode = insertMode;
	}

	public @NotNull Profile getProfile() {
		return profile;
	}

	public @NotNull String getOriginalPath() {
		return originalPath;
	}

	public @NotNull InsertMode getInsertMode() {
		return insertMode;
	}
}
