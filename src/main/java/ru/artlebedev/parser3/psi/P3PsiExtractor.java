package ru.artlebedev.parser3.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.model.P3MethodCallType;
import ru.artlebedev.parser3.model.P3MethodDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for extracting Parser3 constructs from PSI.
 */
public final class P3PsiExtractor {

	private static final Pattern USE_DIRECTIVE_PATTERN = Pattern.compile(
			"\\^use\\s*\\[\\s*([^\\]]+)\\s*\\]"
	);

	private static final Pattern AT_USE_PATTERN = Pattern.compile(
			"@USE\\s+([^\\s\\r\\n]+)"
	);

	private P3PsiExtractor() {
	}

	// ========== РЕЗУЛЬТАТ ПАРСИНГА ВЫЗОВА МЕТОДА ==========

	/**
	 * Результат парсинга вызова метода.
	 * Содержит всю информацию о вызове: имя класса, имя метода, валидность.
	 */
	public static class MethodCallInfo {
		private final String fullCallName;      // Полное имя: "User::create" или "method" или "self.method"
		private final String className;         // Имя класса или null для простых вызовов
		private final String methodName;        // Имя метода
		private final boolean isValid;          // Валидный ли вызов для резолвинга
		private final boolean isClassMethod;    // Это вызов метода класса (User::create)
		private final boolean isSelfCall;       // Это ^self.method[]
		private final boolean isMainCall;       // Это ^MAIN:method[]
		private final boolean isBaseCall;       // Это ^BASE:method[]
		private final boolean isObjectMethodCall; // Это ^var.method[] (вызов метода объекта)
		private final String variableName;      // Имя переменной для ^var.method[]

		private MethodCallInfo(String fullCallName, String className, String methodName,
							   boolean isValid, boolean isClassMethod, boolean isSelfCall,
							   boolean isMainCall, boolean isBaseCall,
							   boolean isObjectMethodCall, String variableName) {
			this.fullCallName = fullCallName;
			this.className = className;
			this.methodName = methodName;
			this.isValid = isValid;
			this.isClassMethod = isClassMethod;
			this.isSelfCall = isSelfCall;
			this.isMainCall = isMainCall;
			this.isBaseCall = isBaseCall;
			this.isObjectMethodCall = isObjectMethodCall;
			this.variableName = variableName;
		}

		public String getFullCallName() { return fullCallName; }
		public String getClassName() { return className; }
		public String getMethodName() { return methodName; }
		public boolean isValid() { return isValid; }
		public boolean isClassMethod() { return isClassMethod; }
		public boolean isSelfCall() { return isSelfCall; }
		public boolean isMainCall() { return isMainCall; }
		public boolean isBaseCall() { return isBaseCall; }
		public boolean isObjectMethodCall() { return isObjectMethodCall; }
		public String getVariableName() { return variableName; }

		// Создаёт невалидный результат
		public static MethodCallInfo invalid() {
			return new MethodCallInfo(null, null, null, false, false, false, false, false, false, null);
		}
	}

	// ========== ГЛАВНАЯ ФУНКЦИЯ: ПАРСИНГ ВЫЗОВА МЕТОДА ИЗ PSI ==========

	/**
	 * Парсит вызов метода из PSI элемента.
	 * Это ЕДИНСТВЕННАЯ функция для определения валидности и извлечения информации о вызове.
	 *
	 * Валидные форматы:
	 * - ^method[] → method
	 * - ^self.method[] → method (self. — единственный допустимый префикс с точкой)
	 * - ^MAIN:method[] → method
	 * - ^BASE:method[] или ^BASE::method[] → method
	 * - ^ClassName:method[] или ^ClassName::method[] → ClassName + method
	 *
	 * НЕвалидные форматы (не резолвим):
	 * - ^method.something[] — вызов метода объекта из переменной
	 * - ^ClassName::method.something[] — точка после имени метода
	 * - Клик на элемент после точки (например на "something" в ^method.something[])
	 *
	 * @param element PSI элемент (METHOD, CONSTRUCTOR, или IMPORTANT_METHOD токен)
	 * @return MethodCallInfo с информацией о вызове
	 */
	public static @NotNull MethodCallInfo parseMethodCall(@NotNull PsiElement element) {
		// Проверяем тип токена
		if (element.getNode() == null) {
			return MethodCallInfo.invalid();
		}

		IElementType elementType = element.getNode().getElementType();
		boolean isNavigableToken = elementType == Parser3TokenTypes.CONSTRUCTOR
				|| elementType == Parser3TokenTypes.METHOD
				|| elementType == Parser3TokenTypes.IMPORTANT_METHOD;

		if (!isNavigableToken) {
			return MethodCallInfo.invalid();
		}

		// Определяем откуда искать siblings
		// Если у элемента нет siblings — ищем через родителя
		PsiElement searchFrom = element;
		PsiElement parent = element.getParent();
		if (parent != null && element.getNextSibling() == null && element.getPrevSibling() == null) {
			searchFrom = parent;
		}

		// Проверяем что ПЕРЕД элементом стоит DOT — это может быть вызов метода объекта
		PsiElement prev = searchFrom.getPrevSibling();
		if (prev != null && prev.getNode() != null) {
			IElementType prevType = prev.getNode().getElementType();
			if (prevType == Parser3TokenTypes.DOT) {
				PsiElement beforeDot = prev.getPrevSibling();
				if (beforeDot != null) {
					String beforeDotText = beforeDot.getText();

					// ^self.method[] — валидный вызов (НЕ ^self.var.method — проверяем что после нет ещё DOT)
					if (beforeDotText.equals("^self")) {
						String methodName = element.getText();
						// Проверяем: после element есть DOT? Тогда это ^self.user.method — клик на "user"
						PsiElement afterElement = searchFrom.getNextSibling();
						if (afterElement != null && afterElement.getNode() != null
								&& afterElement.getNode().getElementType() == Parser3TokenTypes.DOT) {
							// ^self.user.method[] — клик на "user" → invalid, handleVariableNavigation перехватит
							return MethodCallInfo.invalid();
						}
						// ^self.method[] — обычный self вызов
						return new MethodCallInfo("^self." + methodName, null, methodName, true, false, true, false, false, false, null);
					}

					// ^var.method[] — вызов метода объекта (beforeDot = ^varName)
					if (beforeDotText.startsWith("^")) {
						String varName = beforeDotText.substring(1); // убираем ^
						String methodName = element.getText();
						return new MethodCallInfo("^" + varName + "." + methodName, null, methodName, true, false, false, false, false, true, varName);
					}

					// METHOD.method — промежуточный элемент (user в ^MAIN:user.method[] или ^self.user.method[])
					// Идём дальше назад чтобы найти полный контекст
					IElementType beforeDotType = beforeDot.getNode() != null ? beforeDot.getNode().getElementType() : null;
					if (beforeDotType == Parser3TokenTypes.METHOD || beforeDotType == Parser3TokenTypes.CONSTRUCTOR) {
						// user.method — ищем что перед user
						PsiElement prevPrev = beforeDot.getPrevSibling();
						if (prevPrev != null && prevPrev.getNode() != null) {
							IElementType ppType = prevPrev.getNode().getElementType();
							if (ppType == Parser3TokenTypes.COLON) {
								// X:user.method — ищем X
								PsiElement beforeColon = prevPrev.getPrevSibling();
								if (beforeColon != null) {
									String bcText = beforeColon.getText();
									if (bcText != null && bcText.equals("^MAIN")) {
										// ^MAIN:user.method[] — клик на method
										String varName = beforeDot.getText();
										String methodName = element.getText();
										return new MethodCallInfo("^MAIN:" + varName + "." + methodName, null, methodName, true, false, false, false, false, true, "MAIN:" + varName);
									}
								}
							}
							if (ppType == Parser3TokenTypes.DOT) {
								// X.user.method — ищем X
								PsiElement beforeDot2 = prevPrev.getPrevSibling();
								if (beforeDot2 != null) {
									String bd2Text = beforeDot2.getText();
									if (bd2Text != null && bd2Text.equals("^self")) {
										// ^self.user.method[] — клик на method
										String varName = beforeDot.getText();
										String methodName = element.getText();
										return new MethodCallInfo("^self." + varName + "." + methodName, null, methodName, true, false, false, false, false, true, "self." + varName);
									}
								}
							}
						}
					}
				}
				return MethodCallInfo.invalid();
			}
		}

		// Собираем полное имя вызова
		String fullCallName = extractFullCallNameFromPsi(element, searchFrom);
		if (fullCallName == null || fullCallName.isEmpty()) {
			return MethodCallInfo.invalid();
		}

		// Парсим полное имя и проверяем валидность
		return parseFullCallName(fullCallName);
	}

	/**
	 * Собирает полное имя вызова из PSI структуры.
	 * Идёт назад до ^ и вперёд до конца вызова.
	 */
	private static @Nullable String extractFullCallNameFromPsi(@NotNull PsiElement element, @NotNull PsiElement searchFrom) {
		String text = element.getText();

		// Если токен начинается с ^ — идём только вперёд
		if (text.startsWith("^")) {
			StringBuilder fullName = new StringBuilder(text);
			PsiElement next = searchFrom.getNextSibling();

			while (next != null) {
				IElementType nextType = next.getNode() != null ? next.getNode().getElementType() : null;
				String nextText = next.getText();

				if (nextType == Parser3TokenTypes.COLON) {
					fullName.append(":");
					next = next.getNextSibling();
					continue;
				}

				if (nextType == Parser3TokenTypes.DOT) {
					fullName.append(".");
					next = next.getNextSibling();
					continue;
				}

				if (nextType == Parser3TokenTypes.CONSTRUCTOR || nextType == Parser3TokenTypes.METHOD) {
					fullName.append(nextText);
					next = next.getNextSibling();
					continue;
				}

				// Любой другой токен — конец
				break;
			}

			return fullName.toString();
		}

		// Токен без ^ — идём в обе стороны
		StringBuilder fullName = new StringBuilder(text);

		// Сначала идём ВПЕРЁД — собираем .asdf если есть
		PsiElement next = searchFrom.getNextSibling();
		while (next != null) {
			IElementType nextType = next.getNode() != null ? next.getNode().getElementType() : null;
			String nextText = next.getText();

			if (nextType == Parser3TokenTypes.DOT) {
				fullName.append(".");
				next = next.getNextSibling();
				continue;
			}

			if (nextType == Parser3TokenTypes.CONSTRUCTOR || nextType == Parser3TokenTypes.METHOD) {
				fullName.append(nextText);
				next = next.getNextSibling();
				continue;
			}

			break;
		}

		// Теперь идём НАЗАД — собираем ^User::
		PsiElement prev = searchFrom.getPrevSibling();
		while (prev != null) {
			IElementType prevType = prev.getNode() != null ? prev.getNode().getElementType() : null;
			String prevText = prev.getText();

			if (prevType == Parser3TokenTypes.COLON) {
				fullName.insert(0, ":");
				prev = prev.getPrevSibling();
				continue;
			}

			if (prevType == Parser3TokenTypes.DOT) {
				fullName.insert(0, ".");
				prev = prev.getPrevSibling();
				continue;
			}

			if (prevType == Parser3TokenTypes.CONSTRUCTOR
					|| prevType == Parser3TokenTypes.METHOD
					|| prevType == Parser3TokenTypes.IMPORTANT_METHOD) {
				fullName.insert(0, prevText);

				if (prevText.startsWith("^")) {
					// Нашли начало
					return fullName.toString();
				}

				prev = prev.getPrevSibling();
				continue;
			}

			break;
		}

		// Не нашли ^ — возможно это начальный токен
		if (fullName.toString().startsWith("^")) {
			return fullName.toString();
		}

		return null;
	}

	/**
	 * Парсит полное имя вызова и возвращает MethodCallInfo.
	 * Проверяет валидность синтаксиса.
	 */
	private static @NotNull MethodCallInfo parseFullCallName(@NotNull String fullCallName) {
		// Убираем ^ в начале
		String name = fullCallName.startsWith("^") ? fullCallName.substring(1) : fullCallName;

		// ^self.method[]
		if (name.startsWith("self.")) {
			String afterSelf = name.substring(5);
			// После self. не должно быть точек или двоеточий
			if (afterSelf.contains(".") || afterSelf.contains(":")) {
				return MethodCallInfo.invalid();
			}
			return new MethodCallInfo(fullCallName, null, afterSelf, true, false, true, false, false, false, null);
		}

		// ^MAIN:method[]
		if (name.startsWith("MAIN:")) {
			String afterMain = name.substring(5);
			// После MAIN: не должно быть точек или ещё двоеточий
			if (afterMain.contains(".") || afterMain.contains(":")) {
				return MethodCallInfo.invalid();
			}
			return new MethodCallInfo(fullCallName, null, afterMain, true, false, false, true, false, false, null);
		}

		// ^BASE:method[] или ^BASE::method[]
		if (name.startsWith("BASE:")) {
			String afterBase = name.substring(5);
			if (afterBase.startsWith(":")) {
				afterBase = afterBase.substring(1);
			}
			// После BASE: не должно быть точек или ещё двоеточий
			if (afterBase.contains(".") || afterBase.contains(":")) {
				return MethodCallInfo.invalid();
			}
			return new MethodCallInfo(fullCallName, null, afterBase, true, false, false, false, true, false, null);
		}

		// ^ClassName:method[] или ^ClassName::method[]
		if (name.contains(":")) {
			int colonPos = name.indexOf(':');
			String className = name.substring(0, colonPos);
			String afterColon = name.substring(colonPos + 1);
			// Убираем второе двоеточие если есть
			if (afterColon.startsWith(":")) {
				afterColon = afterColon.substring(1);
			}
			// После имени метода не должно быть точек или ещё двоеточий
			if (afterColon.contains(".") || afterColon.contains(":")) {
				return MethodCallInfo.invalid();
			}
			return new MethodCallInfo(fullCallName, className, afterColon, true, true, false, false, false, false, null);
		}

		// Простой вызов ^method[]
		// Не должно быть точек — это был бы вызов метода объекта
		if (name.contains(".")) {
			return MethodCallInfo.invalid();
		}

		return new MethodCallInfo(fullCallName, null, name, true, false, false, false, false, false, null);
	}

	/**
	 * Extracts all method declarations from a file.
	 * Uses DEFINE_METHOD tokens from lexer.
	 */
	public static @NotNull List<P3MethodDeclaration> extractMethodDeclarations(@NotNull PsiFile file) {
		List<P3MethodDeclaration> declarations = new ArrayList<>();

		// Находим все элементы с типом DEFINE_METHOD
		PsiElement[] elements = PsiTreeUtil.collectElements(file, e -> {
			if (e.getNode() == null) return false;
			return e.getNode().getElementType() == Parser3TokenTypes.DEFINE_METHOD;
		});

		for (PsiElement element : elements) {
			String text = element.getText();
			String name = extractMethodName(text);
			P3MethodCallType callType = extractMethodCallType(text);

			if (name != null) {
				declarations.add(new P3MethodDeclaration(
						name,
						file.getVirtualFile(),
						element.getTextOffset(),
						element,
						List.of(),
						null,
						List.of(),
						null,
						null,
						false,
						callType
				));
			}
		}

		return declarations;
	}

	/**
	 * Извлекает имя метода из токена DEFINE_METHOD.
	 * @test_method[] -> test_method
	 */
	private static @Nullable String extractMethodName(@NotNull String text) {
		if (!text.startsWith("@")) {
			return null;
		}

		text = text.substring(1); // убрать @
		int bracketPos = text.indexOf('[');
		if (bracketPos > 0) {
			text = text.substring(0, bracketPos);
		}

		if (text.startsWith("static:")) {
			text = text.substring("static:".length());
		} else if (text.startsWith("dynamic:")) {
			text = text.substring("dynamic:".length());
		}

		return text.trim();
	}

	private static @NotNull P3MethodCallType extractMethodCallType(@NotNull String text) {
		text = text.trim();
		if (text.startsWith("^")) {
			text = text.substring(1);
		}
		if (text.startsWith("@")) {
			text = text.substring(1);
		}

		if (text.startsWith("static:")) {
			return P3MethodCallType.STATIC;
		}
		if (text.startsWith("dynamic:")) {
			return P3MethodCallType.DYNAMIC;
		}
		return P3MethodCallType.ANY;
	}

	/**
	 * Extracts all ^use[...] directives from a file.
	 */
	public static @NotNull List<String> extractUseDirectives(@NotNull PsiFile file) {
		List<String> uses = new ArrayList<>();
		String text = file.getText();

		Matcher useMatcher = USE_DIRECTIVE_PATTERN.matcher(text);
		while (useMatcher.find()) {
			String path = useMatcher.group(1).trim();
			path = removeQuotes(path);
			uses.add(path);
		}

		Matcher atUseMatcher = AT_USE_PATTERN.matcher(text);
		while (atUseMatcher.find()) {
			String path = atUseMatcher.group(1).trim();
			uses.add(path);
		}

		return uses;
	}

	private static String removeQuotes(String str) {
		if (str.length() >= 2) {
			char first = str.charAt(0);
			char last = str.charAt(str.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return str.substring(1, str.length() - 1);
			}
		}
		return str;
	}
	/**
	 * Extracts all class declarations from a file.
	 * If file has no @CLASS - creates implicit MAIN class.
	 * Returns classes with their methods and offset ranges.
	 */
	public static @NotNull List<ru.artlebedev.parser3.model.P3ClassDeclaration> extractClassDeclarations(@NotNull PsiFile file) {
		List<ru.artlebedev.parser3.model.P3ClassDeclaration> classes = new ArrayList<>();

		// Collect all SPECIAL_METHOD tokens (@CLASS, @BASE, @OPTIONS)
		PsiElement[] specialElements = PsiTreeUtil.collectElements(file, e -> {
			if (e.getNode() == null) return false;
			IElementType type = e.getNode().getElementType();
			return type == Parser3TokenTypes.SPECIAL_METHOD;
		});

		// Find all @CLASS positions
		List<ClassInfo> classInfos = new ArrayList<>();
		for (PsiElement element : specialElements) {
			String text = element.getText();
			if (text.startsWith("@CLASS")) {
				// Extract class name from next line
				String className = extractNextLineContent(file.getText(), element.getTextOffset() + text.length());
				if (className != null && !className.isEmpty()) {
					ClassInfo info = new ClassInfo();
					info.name = className;
					info.startOffset = element.getTextOffset();
					info.element = element;
					classInfos.add(info);
				}
			}
		}

		// If no @CLASS found - create implicit MAIN class
		if (classInfos.isEmpty()) {
			List<P3MethodDeclaration> allMethods = extractMethodDeclarations(file);
			ru.artlebedev.parser3.model.P3ClassDeclaration mainClass = new ru.artlebedev.parser3.model.P3ClassDeclaration(
					"MAIN",
					file.getVirtualFile(),
					0,
					file.getTextLength(),
					null, // no base class
					new ArrayList<>(), // no options
					allMethods,
					true, // isMainClass
					null
			);
			classes.add(mainClass);
			return classes;
		}

		// Process each class
		for (int i = 0; i < classInfos.size(); i++) {
			ClassInfo info = classInfos.get(i);

			// Determine end offset (next @CLASS or EOF)
			int endOffset = (i + 1 < classInfos.size())
					? classInfos.get(i + 1).startOffset
					: file.getTextLength();

			info.endOffset = endOffset;

			// Extract @BASE and @OPTIONS for this class
			extractClassDirectives(file, specialElements, info);

			// Extract methods for this class
			info.methods = extractMethodsInRange(file, info.startOffset, info.endOffset);

			// Create class declaration
			ru.artlebedev.parser3.model.P3ClassDeclaration classDecl = new ru.artlebedev.parser3.model.P3ClassDeclaration(
					info.name,
					file.getVirtualFile(),
					info.startOffset,
					info.endOffset,
					info.baseClassName,
					info.options,
					info.methods,
					false, // not MAIN
					info.element
			);

			classes.add(classDecl);
		}

		return classes;
	}

	/**
	 * Helper class to collect class information during parsing
	 */
	private static class ClassInfo {
		String name;
		int startOffset;
		int endOffset;
		String baseClassName;
		List<String> options = new ArrayList<>();
		List<P3MethodDeclaration> methods = new ArrayList<>();
		PsiElement element;
	}

	/**
	 * Extract content from next non-empty line after given offset
	 */
	private static @Nullable String extractNextLineContent(String text, int startOffset) {
		int length = text.length();
		int i = startOffset;

		// Skip current line
		while (i < length && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
			i++;
		}

		// Skip line breaks
		while (i < length && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) {
			i++;
		}

		// Read next line
		int lineStart = i;
		while (i < length && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
			i++;
		}

		if (i > lineStart) {
			return text.substring(lineStart, i).trim();
		}

		return null;
	}

	/**
	 * Extract @BASE and @OPTIONS directives for a class
	 */
	private static void extractClassDirectives(PsiFile file, PsiElement[] specialElements, ClassInfo info) {
		String text = file.getText();

		for (PsiElement element : specialElements) {
			int offset = element.getTextOffset();

			// Only process directives within this class range
			if (offset < info.startOffset || offset >= info.endOffset) {
				continue;
			}

			String elementText = element.getText();

			if (elementText.startsWith("@BASE")) {
				String baseName = extractNextLineContent(text, offset + elementText.length());
				if (baseName != null && !baseName.isEmpty()) {
					info.baseClassName = baseName;
				}
			} else if (elementText.startsWith("@OPTIONS")) {
				// Extract options from following lines until next directive
				List<String> opts = extractOptionsContent(text, offset + elementText.length(), info.endOffset);
				info.options.addAll(opts);
			}
		}
	}

	/**
	 * Extract @OPTIONS content (can be multiple lines)
	 */
	private static List<String> extractOptionsContent(String text, int startOffset, int endOffset) {
		List<String> options = new ArrayList<>();
		int length = Math.min(text.length(), endOffset);
		int i = startOffset;

		// Skip to next line
		while (i < length && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
			i++;
		}
		while (i < length && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) {
			i++;
		}

		// Read lines until we hit @ or end
		while (i < length) {
			int lineStart = i;

			// Read line
			while (i < length && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
				i++;
			}

			String line = text.substring(lineStart, i).trim();

			// Stop if we hit a directive
			if (line.startsWith("@")) {
				break;
			}

			// Add non-empty options
			if (!line.isEmpty() && !line.startsWith("#")) {
				// Split by whitespace and add each option
				String[] parts = line.split("\\s+");
				for (String part : parts) {
					if (!part.isEmpty()) {
						options.add(part);
					}
				}
			}

			// Skip line breaks
			while (i < length && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) {
				i++;
			}
		}

		return options;
	}

	/**
	 * Extract methods in given offset range
	 */
	private static List<P3MethodDeclaration> extractMethodsInRange(PsiFile file, int startOffset, int endOffset) {
		List<P3MethodDeclaration> result = new ArrayList<>();

		PsiElement[] elements = PsiTreeUtil.collectElements(file, e -> {
			if (e.getNode() == null) return false;
			if (e.getNode().getElementType() != Parser3TokenTypes.DEFINE_METHOD) return false;

			int offset = e.getTextOffset();
			return offset >= startOffset && offset < endOffset;
		});

		for (PsiElement element : elements) {
			String text = element.getText();
			String name = extractMethodName(text);
			P3MethodCallType callType = extractMethodCallType(text);

			if (name != null) {
				result.add(new P3MethodDeclaration(
						name,
						file.getVirtualFile(),
						element.getTextOffset(),
						element,
						List.of(),
						null,
						List.of(),
						null,
						null,
						false,
						callType
				));
			}
		}

		return result;
	}
}
