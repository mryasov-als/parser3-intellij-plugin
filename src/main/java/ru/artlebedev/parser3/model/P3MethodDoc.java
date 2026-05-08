package ru.artlebedev.parser3.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Документация метода Parser3, извлечённая из блока комментариев.
 *
 * Формат блока:
 * <pre>
 * ####################
 * # создает пользователя
 * # $email почта
 * # $name(string) имя пользователя
 * # $result(int) ID пользователя
 * ####################
 * @createUser[email;name]
 * </pre>
 */
public final class P3MethodDoc {

	private final @Nullable String title;
	private final @NotNull List<P3MethodParameter> parameters;
	private final @Nullable P3MethodParameter result;
	private final @Nullable String rawText; // Полный текст документации

	private P3MethodDoc(
			@Nullable String title,
			@NotNull List<P3MethodParameter> parameters,
			@Nullable P3MethodParameter result,
			@Nullable String rawText
	) {
		this.title = title;
		this.parameters = Collections.unmodifiableList(parameters);
		this.result = result;
		this.rawText = rawText;
	}

	public @Nullable String getTitle() {
		return title;
	}

	public @NotNull List<P3MethodParameter> getParameters() {
		return parameters;
	}

	public @Nullable P3MethodParameter getResult() {
		return result;
	}

	public @Nullable String getRawText() {
		return rawText;
	}

	/**
	 * Парсит блок документации из текста файла.
	 *
	 * @param text текст файла
	 * @param methodOffset позиция начала метода (@methodName)
	 * @return документация или null если блок не найден
	 */
	public static @Nullable P3MethodDoc parseFromText(@NotNull String text, int methodOffset) {
		// Ищем начало строки с методом
		int lineStart = methodOffset;
		while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
			lineStart--;
		}

		// Ищем конец предыдущей строки (должен быть ###...)
		int prevLineEnd = lineStart - 1;
		if (prevLineEnd < 0) {
			return null; // Метод в начале файла
		}

		// Пропускаем \r\n
		while (prevLineEnd > 0 && (text.charAt(prevLineEnd) == '\r' || text.charAt(prevLineEnd) == '\n')) {
			prevLineEnd--;
		}

		if (prevLineEnd < 0) {
			return null;
		}

		// Ищем начало строки с закрывающим ###...
		int closingLineStart = prevLineEnd;
		while (closingLineStart > 0 && text.charAt(closingLineStart - 1) != '\n') {
			closingLineStart--;
		}

		// Проверяем что это строка из ###... (минимум 20 символов #)
		String closingLine = text.substring(closingLineStart, prevLineEnd + 1).trim();
		if (!isHashLine(closingLine, 20)) {
			return null;
		}

		// Теперь идём вверх и собираем строки комментариев
		List<String> docLines = new ArrayList<>();
		int currentPos = closingLineStart - 1;

		// Пропускаем \r\n
		while (currentPos > 0 && (text.charAt(currentPos) == '\r' || text.charAt(currentPos) == '\n')) {
			currentPos--;
		}

		// Собираем строки комментариев
		while (currentPos >= 0) {
			// Ищем начало текущей строки
			int currentLineStart = currentPos;
			while (currentLineStart > 0 && text.charAt(currentLineStart - 1) != '\n') {
				currentLineStart--;
			}

			String line = text.substring(currentLineStart, currentPos + 1).trim();

			// Проверяем это открывающий ###... ?
			if (isHashLine(line, 20)) {
				// Нашли начало блока
				break;
			}

			// Строка должна начинаться с #
			if (!line.startsWith("#")) {
				return null; // Прервался блок комментариев
			}

			// Убираем # и пробелы в начале
			String content = line.substring(1).trim();
			docLines.add(0, content); // Добавляем в начало (идём снизу вверх)

			// Переходим к предыдущей строке
			currentPos = currentLineStart - 1;
			while (currentPos > 0 && (text.charAt(currentPos) == '\r' || text.charAt(currentPos) == '\n')) {
				currentPos--;
			}

			if (currentPos < 0) {
				return null; // Дошли до начала файла без открывающего ###
			}
		}

		if (docLines.isEmpty()) {
			return null;
		}

		// Парсим содержимое
		return parseDocLines(docLines);
	}

	/**
	 * Проверяет что строка состоит из # и имеет минимальную длину
	 */
	private static boolean isHashLine(@NotNull String line, int minLength) {
		if (line.length() < minLength) {
			return false;
		}
		for (int i = 0; i < line.length(); i++) {
			if (line.charAt(i) != '#') {
				return false;
			}
		}
		return true;
	}

	/**
	 * Парсит строки документации.
	 * Всё содержимое блока сохраняется как есть в description.
	 * Параметры парсятся только если $ стоит в начале строки (без учёта пробелов).
	 */
	private static @NotNull P3MethodDoc parseDocLines(@NotNull List<String> lines) {
		List<P3MethodParameter> parameters = new ArrayList<>();
		P3MethodParameter result = null;
		StringBuilder rawText = new StringBuilder();

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);

			if (!rawText.isEmpty()) {
				rawText.append("\n");
			}
			rawText.append(line);

			// Параметры извлекаем только если $ в начале строки (без учёта пробелов)
			String trimmedLine = line.trim();
			if (trimmedLine.startsWith("$")) {
				P3MethodParameter param = parseParameter(trimmedLine);
				if (param != null) {
					if (param.isResult()) {
						result = param;
					} else {
						parameters.add(param);
					}
				}
			}
		}

		// Всё содержимое — это описание (title)
		String title = rawText.isEmpty() ? null : rawText.toString();
		return new P3MethodDoc(title, parameters, result, rawText.toString());
	}

	/**
	 * Парсит строку параметра: $name(type) описание
	 */
	private static @Nullable P3MethodParameter parseParameter(@NotNull String line) {
		if (!line.startsWith("$")) {
			return null;
		}

		// Убираем $
		String rest = line.substring(1);

		// Ищем имя (до пробела или скобки)
		StringBuilder nameBuilder = new StringBuilder();
		int pos = 0;
		while (pos < rest.length()) {
			char ch = rest.charAt(pos);
			if (Character.isWhitespace(ch) || ch == '(') {
				break;
			}
			nameBuilder.append(ch);
			pos++;
		}

		String name = nameBuilder.toString();
		if (name.isEmpty()) {
			return null;
		}

		// Проверяем тип в скобках
		String type = null;
		if (pos < rest.length() && rest.charAt(pos) == '(') {
			int typeStart = pos + 1;
			int typeEnd = rest.indexOf(')', typeStart);
			if (typeEnd > typeStart) {
				type = rest.substring(typeStart, typeEnd).trim();
				pos = typeEnd + 1;
			}
		}

		// Остальное — описание
		String description = null;
		if (pos < rest.length()) {
			description = rest.substring(pos).trim();
			if (description.isEmpty()) {
				description = null;
			}
		}

		boolean isResult = "result".equalsIgnoreCase(name);

		return new P3MethodParameter(name, type, description, isResult);
	}

	/**
	 * Генерирует HTML для отображения документации
	 */
	public @NotNull String toHtml() {
		StringBuilder sb = new StringBuilder();

		if (title != null && !title.isEmpty()) {
			// Заменяем переносы строк на <br/>
			String htmlTitle = escapeHtml(title).replace("\n", "<br/>");
			sb.append("<p>").append(htmlTitle).append("</p>");
		}

		// Параметры и результат уже включены в title, не дублируем

		return sb.toString();
	}

	private static @NotNull String escapeHtml(@NotNull String text) {
		return text
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

	@Override
	public String toString() {
		return "P3MethodDoc{" +
				"title='" + title + '\'' +
				", parameters=" + parameters.size() +
				", result=" + result +
				'}';
	}
}