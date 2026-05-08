package ru.artlebedev.parser3.index;

import ru.artlebedev.parser3.utils.Parser3FileUtils;

import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FileBasedIndex для USE директив в Parser3.
 *
 * Индексирует: имя файла (без пути) -> список UseInfo (полный путь, offset)
 *
 * Это позволяет быстро находить файлы которые используют определённый файл:
 * - По имени файла получаем список потенциальных использований
 * - Резолвим только их (а не все файлы проекта)
 *
 * Работает на уровне текста файла (без PSI), поэтому очень быстрый.
 * Обновляется автоматически при изменении файлов.
 */
public final class P3UseFileIndex extends FileBasedIndexExtension<String, List<P3UseFileIndex.UseInfo>> {

	public static final ID<String, List<UseInfo>> NAME = ID.create("parser3.uses");

	/**
	 * Информация о USE директиве
	 */
	public static final class UseInfo {
		public final String fullPath;  // полный путь как написан в коде
		public final int offset;       // offset в файле
		public final boolean inComment; // true если use находится в комментарии
		public final boolean replace;  // true для ^use[path; $.replace(true)]

		public UseInfo(String fullPath, int offset) {
			this(fullPath, offset, false, false);
		}

		public UseInfo(String fullPath, int offset, boolean inComment) {
			this(fullPath, offset, inComment, false);
		}

		public UseInfo(String fullPath, int offset, boolean inComment, boolean replace) {
			this.fullPath = fullPath;
			this.offset = offset;
			this.inComment = inComment;
			this.replace = replace;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			UseInfo useInfo = (UseInfo) o;
			return offset == useInfo.offset &&
					inComment == useInfo.inComment &&
					replace == useInfo.replace &&
					java.util.Objects.equals(fullPath, useInfo.fullPath);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(fullPath, offset, inComment, replace);
		}
	}

	// ^use[path] или ^use[path;options]
	private static final Pattern USE_METHOD_PATTERN = Pattern.compile(
			"\\^use\\s*\\[\\s*([^\\];]+)(?:;([^\\]]*))?\\]",
			Pattern.MULTILINE
	);

	private static final Pattern USE_REPLACE_OPTION_PATTERN = Pattern.compile(
			"\\$\\.replace\\s*\\(\\s*true\\s*\\)",
			Pattern.CASE_INSENSITIVE
	);

	// @USE path — может быть на той же строке или на следующих строках
	// Формат 1: @USE path (на одной строке)
	// Формат 2: @USE\npath1\npath2\n... (на разных строках)
	// Захватываем каждый путь отдельно через отдельный паттерн
	private static final Pattern USE_DIRECTIVE_SINGLE_LINE = Pattern.compile(
			"@USE[ \\t]+([^\\s\\r\\n@]+)",
			Pattern.MULTILINE
	);

	// Паттерн для поиска блоков @USE с путями на следующих строках
	// Захватываем и закомментированные строки (#path, ^rem{path}) — для рефакторинга
	private static final Pattern USE_DIRECTIVE_BLOCK = Pattern.compile(
			"@USE\\s*[\\r\\n]+((?:[^@\\r\\n][^\\r\\n]*(?:[\\r\\n]+|$))*)",
			Pattern.MULTILINE
	);

	// Паттерн для извлечения отдельных путей из блока (включая закомментированные #path)
	private static final Pattern PATH_LINE_PATTERN = Pattern.compile(
			"^\\s*(#?)\\s*([^\\s\\r\\n][^\\r\\n]*)\\s*$",
			Pattern.MULTILINE
	);

	// Паттерн для извлечения пути из ^rem{path} (однострочный или многострочный)
	private static final Pattern REM_PATH_PATTERN = Pattern.compile(
			"\\^rem\\s*\\{\\s*([^}]+?)\\s*\\}",
			Pattern.DOTALL
	);

	private static final com.intellij.openapi.diagnostic.Logger LOG =
			com.intellij.openapi.diagnostic.Logger.getInstance(P3UseFileIndex.class);

	@Override
	public @NotNull ID<String, List<UseInfo>> getName() {
		return NAME;
	}

	@Override
	public @NotNull DataIndexer<String, List<UseInfo>, FileContent> getIndexer() {
		return inputData -> {
			Map<String, List<UseInfo>> result = new HashMap<>();

			try {
				String text = inputData.getContentAsText().toString();

				// Бинарный файл — не индексируем
				if (P3VariableFileIndex.isBinaryContent(text)) return result;

				// Ищем ^use[path] — в том числе в комментариях (для переименования файлов)
				Matcher useMethodMatcher = USE_METHOD_PATTERN.matcher(text);
				while (useMethodMatcher.find()) {
					String path = useMethodMatcher.group(1).trim();
					path = normalizePath(path);
					if (!path.isEmpty()) {
						String fileName = extractFileName(path);
						int offset = useMethodMatcher.start(1);
						boolean inComment = ru.artlebedev.parser3.utils.Parser3ClassUtils
								.isOffsetInComment(text, useMethodMatcher.start());
						boolean replace = useMethodMatcher.group(2) != null
								&& USE_REPLACE_OPTION_PATTERN.matcher(useMethodMatcher.group(2)).find();

						result.computeIfAbsent(fileName, k -> new ArrayList<>())
								.add(new UseInfo(path, offset, inComment, replace));
					}
				}

				// Ищем @USE path (однострочный формат: @USE path)
				Matcher singleLineMatcher = USE_DIRECTIVE_SINGLE_LINE.matcher(text);
				while (singleLineMatcher.find()) {
					String path = singleLineMatcher.group(1).trim();
					path = normalizePath(path);
					if (!path.isEmpty() && looksLikeFilePath(path)) {
						String fileName = extractFileName(path);
						int offset = singleLineMatcher.start(1);

						result.computeIfAbsent(fileName, k -> new ArrayList<>())
								.add(new UseInfo(path, offset));
					}
				}

				// Ищем @USE с путями на следующих строках (многострочный формат)
				Matcher blockMatcher = USE_DIRECTIVE_BLOCK.matcher(text);
				while (blockMatcher.find()) {
					String pathsBlock = blockMatcher.group(1);
					int blockStart = blockMatcher.start(1);

					// Извлекаем каждый путь из блока
					Matcher pathMatcher = PATH_LINE_PATTERN.matcher(pathsBlock);
					while (pathMatcher.find()) {
						boolean isCommented = "#".equals(pathMatcher.group(1));
						String rawLine = pathMatcher.group(2).trim();

						// Проверяем ^rem{path} — извлекаем путь изнутри
						Matcher remMatcher = REM_PATH_PATTERN.matcher(rawLine);
						if (remMatcher.find()) {
							// Это ^rem{path} — содержимое может быть путём или другим комментарием
							String remContent = remMatcher.group(1).trim();
							// Внутри ^rem может быть ещё ^rem, #, ^use[] — разбираем рекурсивно
							indexRemContent(remContent, blockStart + pathMatcher.start(2) + remMatcher.start(1), result);
							continue;
						}

						String path = normalizePath(rawLine);
						if (!path.isEmpty() && looksLikeFilePath(path)) {
							String fileName = extractFileName(path);
							int offset = blockStart + pathMatcher.start(2);

							result.computeIfAbsent(fileName, k -> new ArrayList<>())
									.add(new UseInfo(path, offset, isCommented));
						}
					}
				}


			} catch (Exception e) {
				P3IndexExceptionUtil.rethrowIfControlFlow(e);
				LOG.error("[Parser3Index] Error indexing uses in file: " + inputData.getFileName(), e);
			}

			return result;
		};
	}

	/**
	 * Индексирует содержимое ^rem{} в блоке @USE.
	 * Содержимое может быть: путь к файлу, #комментарий с путём, вложенный ^rem{}.
	 */
	private static void indexRemContent(String content, int contentOffset,
										Map<String, List<UseInfo>> result) {
		// Разбиваем по строкам
		String[] lines = content.split("[\\r\\n]+");
		int currentOffset = contentOffset;

		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				currentOffset += line.length() + 1; // +1 для \n
				continue;
			}

			// Вложенный ^rem{}
			Matcher remMatcher = REM_PATH_PATTERN.matcher(trimmed);
			if (remMatcher.find()) {
				String innerContent = remMatcher.group(1).trim();
				int innerOffset = currentOffset + line.indexOf(trimmed) + remMatcher.start(1);
				indexRemContent(innerContent, innerOffset, result);
				currentOffset += line.length() + 1;
				continue;
			}

			// Убираем # в начале
			String pathStr = trimmed;
			if (pathStr.startsWith("#")) {
				pathStr = pathStr.substring(1).trim();
			}

			// ^use[path] внутри rem — уже обрабатывается USE_METHOD_PATTERN, пропускаем
			if (pathStr.startsWith("^use")) {
				currentOffset += line.length() + 1;
				continue;
			}

			String path = normalizePath(pathStr);
			if (!path.isEmpty() && looksLikeFilePath(path)) {
				String fileName = extractFileName(path);
				int pathOffset = currentOffset + line.indexOf(pathStr);

				result.computeIfAbsent(fileName, k -> new ArrayList<>())
						.add(new UseInfo(path, pathOffset, true)); // всегда inComment — внутри ^rem
			}

			currentOffset += line.length() + 1;
		}
	}

	/**
	 * Проверяет похоже ли это на путь к файлу.
	 */
	private static boolean looksLikeFilePath(String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}
		// Путь должен содержать точку (расширение) или слеш (директория)
		// или начинаться с ./ или ../
		return path.contains(".") || path.contains("/") || path.startsWith(".");
	}

	/**
	 * Извлекает имя файла из пути (последний компонент)
	 */
	private static String extractFileName(String path) {
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash < path.length() - 1) {
			return path.substring(lastSlash + 1);
		}
		return path;
	}

	/**
	 * Нормализует путь: убирает кавычки, лишние слеши
	 */
	private static String normalizePath(String path) {
		// Убираем кавычки
		if (path.length() >= 2) {
			char first = path.charAt(0);
			char last = path.charAt(path.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				path = path.substring(1, path.length() - 1);
			}
		}

		// Убираем лишние слеши
		path = path.replaceAll("/+", "/");

		return path.trim();
	}

	@Override
	public @NotNull KeyDescriptor<String> getKeyDescriptor() {
		return EnumeratorStringDescriptor.INSTANCE;
	}

	@Override
	public @NotNull DataExternalizer<List<UseInfo>> getValueExternalizer() {
		return new DataExternalizer<>() {
			@Override
			public void save(@NotNull DataOutput out, List<UseInfo> value) throws IOException {
				out.writeInt(value.size());
				for (UseInfo info : value) {
					out.writeUTF(info.fullPath);
					out.writeInt(info.offset);
					out.writeBoolean(info.inComment);
					out.writeBoolean(info.replace);
				}
			}

			@Override
			public List<UseInfo> read(@NotNull DataInput in) throws IOException {
				int size = in.readInt();
				List<UseInfo> result = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					String path = in.readUTF();
					int offset = in.readInt();
					boolean inComment = in.readBoolean();
					boolean replace = in.readBoolean();
					result.add(new UseInfo(path, offset, inComment, replace));
				}
				return result;
			}
		};
	}

	@Override
	public int getVersion() {
		return 12; // v12: принудительный bootstrap перестройки после установки плагина
	}

	@Override
	public FileBasedIndex.@NotNull InputFilter getInputFilter() {
		return Parser3FileUtils::isParser3File;
	}

	@Override
	public boolean dependsOnFileContent() {
		return true;
	}

	/**
	 * Получает список USE путей для файла из индекса (все, включая комментарии).
	 * Используется для рефакторинга (переименование файлов).
	 */
	public static @NotNull List<String> getUsePaths(
			@NotNull com.intellij.openapi.vfs.VirtualFile file,
			@NotNull com.intellij.openapi.project.Project project
	) {
		FileBasedIndex index = FileBasedIndex.getInstance();
		List<String> paths = new ArrayList<>();

		Map<String, List<UseInfo>> fileData = index.getFileData(NAME, file, project);
		for (List<UseInfo> infos : fileData.values()) {
			for (UseInfo info : infos) {
				paths.add(info.fullPath);
			}
		}

		return paths;
	}

	/**
	 * Получает список USE путей для файла, ИСКЛЮЧАЯ комментарии.
	 * Используется для построения графа видимости (автокомплит, навигация).
	 */
	public static @NotNull List<String> getUsePathsExcludingComments(
			@NotNull com.intellij.openapi.vfs.VirtualFile file,
			@NotNull com.intellij.openapi.project.Project project
	) {
		List<String> paths = new ArrayList<>();
		for (UseInfo info : getUseInfosExcludingComments(file, project)) {
			paths.add(info.fullPath);
		}

		return paths;
	}

	public static @NotNull List<UseInfo> getUseInfosExcludingComments(
			@NotNull com.intellij.openapi.vfs.VirtualFile file,
			@NotNull com.intellij.openapi.project.Project project
	) {
		FileBasedIndex index = FileBasedIndex.getInstance();
		List<UseInfo> result = new ArrayList<>();
		Map<String, List<UseInfo>> fileData = index.getFileData(NAME, file, project);
		for (List<UseInfo> infos : fileData.values()) {
			for (UseInfo info : infos) {
				if (!info.inComment) {
					result.add(info);
				}
			}
		}
		result.sort(java.util.Comparator.comparingInt(info -> info.offset));

		return result;
	}
}
