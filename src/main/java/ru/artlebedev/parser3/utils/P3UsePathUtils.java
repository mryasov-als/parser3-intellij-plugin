package ru.artlebedev.parser3.utils;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Утилита для поиска и замены путей к файлам в директивах ^use[] и @USE.
 *
 * Это ЕДИНСТВЕННЫЙ источник истины для манипуляций с путями в use-директивах.
 * Поддерживаются оба формата: ^use[path] и @USE path.
 */
public final class P3UsePathUtils {

	private P3UsePathUtils() {
	}

	/**
	 * Результат поиска пути в строке текста.
	 */
	public static final class PathMatch {
		/** Начальный offset пути внутри строки */
		public final int pathStart;
		/** Конечный offset пути внутри строки */
		public final int pathEnd;
		/** Тип найденной директивы */
		public final UseDirectiveType directiveType;

		public PathMatch(int pathStart, int pathEnd, UseDirectiveType directiveType) {
			this.pathStart = pathStart;
			this.pathEnd = pathEnd;
			this.directiveType = directiveType;
		}
	}

	/**
	 * Тип use-директивы.
	 */
	public enum UseDirectiveType {
		/** Формат ^use[path] */
		USE_METHOD,
		/** Формат @USE с путём */
		USE_DIRECTIVE
	}

	/**
	 * Ищет конкретный путь в строке текста.
	 * Проверяет оба формата: ^use[path] и путь после @USE.
	 *
	 * @param lineText строка для поиска
	 * @param path искомый путь
	 * @return PathMatch с offset'ами, или null если не найдено
	 */
	public static @Nullable PathMatch findPathInLine(@NotNull String lineText, @NotNull String path) {
		// Сначала пробуем формат ^use[path]
		PathMatch useMethod = findUseMethodPath(lineText, path);
		if (useMethod != null) {
			return useMethod;
		}

		// Потом формат @USE path
		return findUseDirectivePath(lineText, path);
	}

	/**
	 * Ищет путь в формате ^use[path].
	 */
	private static @Nullable PathMatch findUseMethodPath(@NotNull String lineText, @NotNull String path) {
		// Ищем ^use[path]
		String pattern = "^use[" + path + "]";
		int idx = lineText.indexOf(pattern);
		if (idx >= 0) {
			int pathStart = idx + 5; // после "^use["
			int pathEnd = pathStart + path.length();
			return new PathMatch(pathStart, pathEnd, UseDirectiveType.USE_METHOD);
		}

		// Также пробуем с кавычками: ^use["path"] или ^use['path']
		String patternDoubleQuote = "^use[\"" + path + "\"]";
		idx = lineText.indexOf(patternDoubleQuote);
		if (idx >= 0) {
			int pathStart = idx + 6; // после "^use[\""
			int pathEnd = pathStart + path.length();
			return new PathMatch(pathStart, pathEnd, UseDirectiveType.USE_METHOD);
		}

		String patternSingleQuote = "^use['" + path + "']";
		idx = lineText.indexOf(patternSingleQuote);
		if (idx >= 0) {
			int pathStart = idx + 6; // после "^use['"
			int pathEnd = pathStart + path.length();
			return new PathMatch(pathStart, pathEnd, UseDirectiveType.USE_METHOD);
		}

		return null;
	}

	/**
	 * Ищет путь в формате директивы @USE.
	 * Путь может быть на той же строке что и @USE или отдельно (после @USE на предыдущей строке).
	 */
	private static @Nullable PathMatch findUseDirectivePath(@NotNull String lineText, @NotNull String path) {
		String trimmed = lineText.trim();

		// Случай 1: @USE path (на одной строке)
		if (trimmed.startsWith("@USE")) {
			String afterUse = trimmed.substring(4).trim();
			if (afterUse.equals(path)) {
				int pathStart = lineText.indexOf(path);
				if (pathStart >= 0) {
					return new PathMatch(pathStart, pathStart + path.length(), UseDirectiveType.USE_DIRECTIVE);
				}
			}
		}

		// Случай 2: путь отдельно (строка содержит только путь, возможно с пробелами)
		// Это для случая когда @USE на предыдущей строке
		if (trimmed.equals(path)) {
			int pathStart = lineText.indexOf(path);
			if (pathStart >= 0) {
				return new PathMatch(pathStart, pathStart + path.length(), UseDirectiveType.USE_DIRECTIVE);
			}
		}

		return null;
	}

	/**
	 * Вычисляет относительный путь от директории к целевому файлу.
	 * Это ЕДИНСТВЕННАЯ функция для вычисления относительных путей — используется
	 * и в P3UseFileReference, и в P3RefactoringListenerProvider.
	 *
	 * @param fromDir директория от которой вычисляем путь
	 * @param toFile целевой файл
	 * @return строка относительного пути, или null если не удалось вычислить
	 */
	public static @Nullable String computeRelativePath(@NotNull VirtualFile fromDir, @NotNull VirtualFile toFile) {
		String fromPath = fromDir.getPath();
		String toPath = toFile.getPath();


		// Разбиваем на компоненты
		String[] fromParts = fromPath.split("/");
		String[] toParts = toPath.split("/");


		// Находим общий префикс
		int commonLength = 0;
		int minLength = Math.min(fromParts.length, toParts.length);
		for (int i = 0; i < minLength; i++) {
			if (fromParts[i].equals(toParts[i])) {
				commonLength = i + 1;
			} else {
				break;
			}
		}


		// Строим относительный путь
		StringBuilder result = new StringBuilder();

		// Добавляем "../" для каждого уровня выше общего предка
		for (int i = commonLength; i < fromParts.length; i++) {
			if (result.length() > 0) {
				result.append("/");
			}
			result.append("..");
		}

		// Добавляем путь к целевому файлу
		for (int i = commonLength; i < toParts.length; i++) {
			if (result.length() > 0) {
				result.append("/");
			}
			result.append(toParts[i]);
		}

		return result.toString();
	}

	/**
	 * Вычисляет относительный путь от директории файла к целевому файлу.
	 * Удобный метод, извлекающий родительскую директорию из исходного файла.
	 *
	 * @param fromFile файл, от директории которого вычисляем путь
	 * @param toFile целевой файл
	 * @return строка относительного пути, или просто имя файла если в одной директории
	 */
	public static @Nullable String computeRelativePathFromFile(@NotNull VirtualFile fromFile, @NotNull VirtualFile toFile) {
		VirtualFile fromDir = fromFile.getParent();
		if (fromDir == null) {
			return toFile.getName();
		}

		// Простой случай — файлы в одной директории
		VirtualFile toDir = toFile.getParent();
		if (toDir != null && toDir.equals(fromDir)) {
			return toFile.getName();
		}

		return computeRelativePath(fromDir, toFile);
	}

	/**
	 * Формирует строку отображения для use-директивы (для UI/логов).
	 *
	 * @param path путь к файлу
	 * @param type тип директивы
	 * @return форматированная строка вида "^use[path]" или "@USE path"
	 */
	public static @NotNull String formatUsePath(@NotNull String path, @NotNull UseDirectiveType type) {
		switch (type) {
			case USE_METHOD:
				return "^use[" + path + "]";
			case USE_DIRECTIVE:
				return "@USE " + path;
			default:
				return path;
		}
	}

	/**
	 * Формирует строку отображения для use-директивы.
	 * Использует формат ^use[] по умолчанию, так как он более распространён.
	 *
	 * @param path путь к файлу
	 * @return форматированная строка вида "^use[path]"
	 */
	public static @NotNull String formatUsePath(@NotNull String path) {
		return formatUsePath(path, UseDirectiveType.USE_METHOD);
	}

	/**
	 * Вычисляет новый путь для use-директивы после перемещения файла.
	 *
	 * ЕДИНАЯ ФУНКЦИЯ для вычисления нового пути — используется во всех местах:
	 * - P3UseFileReference.bindToElement()
	 * - P3RefactoringListenerProvider
	 * - P3UsageService
	 *
	 * @param usePath текущий путь в use-директиве
	 * @param oldFilePath старый абсолютный путь целевого файла
	 * @param newFilePath новый абсолютный путь целевого файла
	 * @return новый путь для use-директивы, или null если не удалось вычислить
	 */
	public static @Nullable String computeNewUsePath(@NotNull String usePath,
													 @NotNull String oldFilePath,
													 @NotNull String newFilePath) {
		// Нормализуем пути
		String normalizedUsePath = usePath.replace('\\', '/');
		String normalizedOldPath = oldFilePath.replace('\\', '/');
		String normalizedNewPath = newFilePath.replace('\\', '/');

		// Убираем ведущий / из usePath для сравнения
		String usePathWithoutLeadingSlash = normalizedUsePath.startsWith("/")
				? normalizedUsePath.substring(1)
				: normalizedUsePath;

		// Проверяем что старый путь файла заканчивается на usePath
		if (normalizedOldPath.endsWith("/" + usePathWithoutLeadingSlash)) {
			// Вычисляем базовый путь (часть пути до usePath)
			String basePath = normalizedOldPath.substring(0,
					normalizedOldPath.length() - usePathWithoutLeadingSlash.length() - 1);

			// Проверяем что новый путь начинается с того же базового пути
			if (normalizedNewPath.startsWith(basePath + "/")) {
				// Вычисляем новый usePath
				String newUsePath = normalizedNewPath.substring(basePath.length() + 1);

				// Сохраняем стиль пути (с ведущим / или без)
				if (normalizedUsePath.startsWith("/")) {
					return "/" + newUsePath;
				}
				return newUsePath;
			}
		}

		// Не удалось вычислить автоматически
		return null;
	}
}