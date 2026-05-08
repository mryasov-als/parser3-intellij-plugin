package ru.artlebedev.parser3.classpath;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3TokenTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts CLASS_PATH operations from Parser3 files.
 * Supports: $CLASS_PATH[...], ^CLASS_PATH.append{...}, ^table::create{...}
 *
 * Distinguishes between:
 * - ASSIGN: $CLASS_PATH[...] — replaces entire CLASS_PATH
 * - APPEND: ^CLASS_PATH.append{...} — adds to existing CLASS_PATH
 */
public final class P3ClassPathExtractor {

	// Присваивание простой строки: $CLASS_PATH[/path] или $MAIN:CLASS_PATH[/path]
	private static final Pattern STRING_ASSIGNMENT = Pattern.compile(
			"\\$(?:MAIN:)?CLASS_PATH\\s*\\[\\s*([^\\[\\]^]+?)\\s*\\]"
	);

	// Присваивание через table::create: $CLASS_PATH[^table::create{...}]
	private static final Pattern TABLE_CREATE = Pattern.compile(
			"\\$(?:MAIN:)?CLASS_PATH\\s*\\[\\s*\\^table::create\\s*\\{([^}]+)\\}\\s*\\]",
			Pattern.DOTALL
	);

	// Добавление: ^CLASS_PATH.append{/path}
	private static final Pattern METHOD_APPEND = Pattern.compile(
			"\\^(?:MAIN:)?CLASS_PATH\\.append\\s*\\{([^}]+)\\}",
			Pattern.DOTALL
	);

	private P3ClassPathExtractor() {
	}

	/**
	 * Извлекает все операции над CLASS_PATH из файла, отсортированные по позиции.
	 */
	public static @NotNull List<ClassPathOperation> extractOperations(@NotNull PsiFile file) {
		List<ClassPathOperation> operations = new ArrayList<>();

		// Collect text only from non-comments
		StringBuilder cleanText = new StringBuilder();
		collectNonCommentText(file, cleanText);

		String text = cleanText.toString();

		// 1. Simple string assignment: $CLASS_PATH[/path]
		Matcher stringMatcher = STRING_ASSIGNMENT.matcher(text);
		while (stringMatcher.find()) {
			String value = stringMatcher.group(1).trim();
			value = removeQuotes(value);

			if (!value.isEmpty() && !value.startsWith("^")) {
				operations.add(new ClassPathOperation(
						ClassPathOperation.Type.ASSIGN,
						Collections.singletonList(value),
						stringMatcher.start()
				));
			}
		}

		// 2. Table creation: $CLASS_PATH[^table::create{...}]
		Matcher tableMatcher = TABLE_CREATE.matcher(text);
		while (tableMatcher.find()) {
			String tableContent = tableMatcher.group(1);
			List<String> tablePaths = parseTableContent(tableContent);
			if (!tablePaths.isEmpty()) {
				operations.add(new ClassPathOperation(
						ClassPathOperation.Type.ASSIGN,
						tablePaths,
						tableMatcher.start()
				));
			}
		}

		// 3. Append method: ^CLASS_PATH.append{/path}
		Matcher appendMatcher = METHOD_APPEND.matcher(text);
		while (appendMatcher.find()) {
			String value = appendMatcher.group(1).trim();
			value = removeQuotes(value);

			if (!value.isEmpty()) {
				operations.add(new ClassPathOperation(
						ClassPathOperation.Type.APPEND,
						Collections.singletonList(value),
						appendMatcher.start()
				));
			}
		}

		// Сортируем по позиции в файле
		operations.sort((a, b) -> Integer.compare(a.getOffset(), b.getOffset()));

		return operations;
	}

	/**
	 * Извлекает все пути из файла (старый API для совместимости).
	 * Просто возвращает все пути без учёта ASSIGN/APPEND семантики.
	 */
	public static @NotNull List<String> extractPaths(@NotNull PsiFile file) {
		List<String> paths = new ArrayList<>();
		for (ClassPathOperation op : extractOperations(file)) {
			paths.addAll(op.getPaths());
		}
		return paths;
	}

	private static void collectNonCommentText(@NotNull PsiElement element, @NotNull StringBuilder builder) {
		if (element.getNode() != null) {
			IElementType type = element.getNode().getElementType();

			if (type == Parser3TokenTypes.LINE_COMMENT ||
					type == Parser3TokenTypes.BLOCK_COMMENT) {
				return;
			}
		}

		if (element.getChildren().length == 0) {
			builder.append(element.getText());
		} else {
			for (PsiElement child : element.getChildren()) {
				collectNonCommentText(child, builder);
			}
		}
	}

	private static @NotNull List<String> parseTableContent(@NotNull String content) {
		List<String> values = new ArrayList<>();
		String[] lines = content.split("\\r?\\n");

		for (int i = 1; i < lines.length; i++) {
			String line = lines[i].trim();
			line = removeQuotes(line);

			if (!line.isEmpty()) {
				values.add(line);
			}
		}

		return values;
	}

	private static @NotNull String removeQuotes(@NotNull String value) {
		value = value.trim();

		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				value = value.substring(1, value.length() - 1);
			}
		}

		return value;
	}
}