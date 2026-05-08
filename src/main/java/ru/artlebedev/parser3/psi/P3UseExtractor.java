package ru.artlebedev.parser3.psi;

import com.intellij.lang.Language;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.templateLanguages.TemplateLanguageFileViewProvider;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.Parser3TokenTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts ^use[...] and @USE directives from Parser3 files.
 * Uses lexer tokens to ignore comments.
 */
public final class P3UseExtractor {

	private static final Pattern USE_METHOD = Pattern.compile(
			"\\^use\\s*\\[\\s*([^\\]]+)\\s*\\]"
	);

	private static final Pattern USE_DECLARATION = Pattern.compile(
			"@USE\\s+([^\\s\\r\\n]+)"
	);

	private P3UseExtractor() {
	}

	/**
	 * Информация о use-директиве.
	 */
	public static final class UseInfo {
		private final String path;
		private final PsiElement element;
		private final int offset;
		private final int lineNumber;
		private final ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType;

		public UseInfo(@NotNull String path, @Nullable PsiElement element, int offset, int lineNumber,
					   @NotNull ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType) {
			this.path = path;
			this.element = element;
			this.offset = offset;
			this.lineNumber = lineNumber;
			this.directiveType = directiveType;
		}

		/**
		 * Конструктор для обратной совместимости — определяет тип автоматически.
		 */
		public UseInfo(@NotNull String path, @Nullable PsiElement element, int offset, int lineNumber) {
			this(path, element, offset, lineNumber, ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType.USE_METHOD);
		}

		public @NotNull String getPath() {
			return path;
		}

		public @Nullable PsiElement getElement() {
			return element;
		}

		public int getOffset() {
			return offset;
		}

		public int getLineNumber() {
			return lineNumber;
		}

		public @NotNull ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType getDirectiveType() {
			return directiveType;
		}
	}

	/**
	 * Extracts use directives with their PSI elements.
	 */
	public static @NotNull List<UseInfo> extractUses(@NotNull PsiFile file) {
		List<UseInfo> uses = new ArrayList<>();

		// Get Parser3 PSI tree (not HTML!)
		PsiFile parser3File = getParser3Psi(file);
		if (parser3File == null) {
			return uses;
		}

		String fullText = parser3File.getText();

		// Collect STRING tokens that are in use context
		collectUseElements(parser3File, uses, fullText);

		return uses;
	}

	/**
	 * Собирает STRING и USE_PATH элементы, представляющие use-пути.
	 */
	private static void collectUseElements(@NotNull PsiElement root, @NotNull List<UseInfo> uses, @NotNull String fullText) {
		for (PsiElement child : root.getChildren()) {
			if (child.getNode() != null) {
				IElementType type = child.getNode().getElementType();

				// Пропускаем комментарии
				if (type == Parser3TokenTypes.LINE_COMMENT || type == Parser3TokenTypes.BLOCK_COMMENT) {
					continue;
				}

				// Проверяем STRING и USE_PATH токены
				if (type == Parser3TokenTypes.STRING || type == Parser3TokenTypes.USE_PATH) {
					String path = extractPathFromString(child);
					if (path != null) {
						// Определяем тип use-контекста
						ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType directiveType =
								getUseContextType(child, fullText);

						if (directiveType != null) {
							// Получаем offset из TextRange в оригинальном файле
							int offset = child.getTextRange().getStartOffset();
							// Вычисляем номер строки (1-based)
							int lineNumber = computeLineNumber(fullText, offset);
							uses.add(new UseInfo(path, child, offset, lineNumber, directiveType));
						}
					}
				}
			}

			// Рекурсия
			collectUseElements(child, uses, fullText);
		}
	}

	/**
	 * Вычисляет номер строки (1-based) по offset.
	 */
	private static int computeLineNumber(@NotNull String text, int offset) {
		int lineNumber = 1;
		for (int i = 0; i < offset && i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				lineNumber++;
			}
		}
		return lineNumber;
	}

	/**
	 * Extracts path from STRING element.
	 */
	private static @Nullable String extractPathFromString(@NotNull PsiElement element) {
		String text = element.getText();
		if (text == null || text.isEmpty()) {
			return null;
		}

		String path = removeQuotes(text).trim();

		// Must look like a file path
		if (path.isEmpty() || (!path.contains(".") && !path.contains("/"))) {
			return null;
		}

		return normalizePath(path);
	}

	/**
	 * Определяет тип use-контекста для STRING элемента.
	 * Возвращает USE_METHOD для ^use[], USE_DIRECTIVE для @USE, или null если не в контексте.
	 */
	private static @Nullable ru.artlebedev.parser3.utils.P3UsePathUtils.UseDirectiveType getUseContextType(
			@NotNull PsiElement element, @NotNull String fullText) {
		// Используем общую утилиту для единообразного поведения
		return ru.artlebedev.parser3.utils.P3UseContextUtils.getUseContextType(element, fullText);
	}

	/**
	 * Checks if STRING element is in ^use[] or @USE context.
	 * Оставлен для обратной совместимости.
	 */
	private static boolean isInUseContext(@NotNull PsiElement element, @NotNull String fullText) {
		return getUseContextType(element, fullText) != null;
	}

	public static @NotNull List<String> extractUsePaths(@NotNull PsiFile file) {
		List<String> paths = new ArrayList<>();

		// Get Parser3 PSI tree (not HTML!)
		PsiFile parser3File = getParser3Psi(file);
		if (parser3File == null) {
			return paths;
		}

		// Collect text only from non-comments
		StringBuilder cleanText = new StringBuilder();
		collectNonCommentText(parser3File, cleanText);

		String text = cleanText.toString();

		// 1. ^use[path]
		Matcher useMethodMatcher = USE_METHOD.matcher(text);
		while (useMethodMatcher.find()) {
			String path = useMethodMatcher.group(1).trim();
			path = removeQuotes(path);
			path = normalizePath(path);

			if (!path.isEmpty()) {
				paths.add(path);
			}
		}

		// 2. @USE path
		Matcher useDeclarationMatcher = USE_DECLARATION.matcher(text);
		while (useDeclarationMatcher.find()) {
			String path = useDeclarationMatcher.group(1).trim();
			path = removeQuotes(path);
			path = normalizePath(path);

			if (!path.isEmpty()) {
				paths.add(path);
			}
		}

		return paths;
	}

	private static PsiFile getParser3Psi(@NotNull PsiFile file) {
		FileViewProvider viewProvider = file.getViewProvider();

		if (viewProvider instanceof TemplateLanguageFileViewProvider) {
			TemplateLanguageFileViewProvider templateProvider = (TemplateLanguageFileViewProvider) viewProvider;

			for (Language language : templateProvider.getLanguages()) {
				if (language instanceof Parser3Language) {
					PsiFile parser3File = templateProvider.getPsi(language);
					if (parser3File != null) {
						return parser3File;
					}
				}
			}
		}

		return file;
	}

	private static @NotNull String normalizePath(@NotNull String path) {
		return path.replaceAll("/+", "/");
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