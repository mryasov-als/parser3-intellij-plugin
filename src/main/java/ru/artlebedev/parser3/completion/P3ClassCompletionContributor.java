package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.AutoCompletionPolicy;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.patterns.StandardPatterns;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.utils.Parser3BuiltinStaticPropertyUsageUtils;
import ru.artlebedev.parser3.utils.Parser3ClassUtils;
import ru.artlebedev.parser3.utils.Parser3IdentifierUtils;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.*;

/**
 * Автокомплит для классов Parser3.
 *
 * Двухэтапный подход (как у builtin классов):
 * 1. При вводе "^Us" — показываем только "User" с insertHandler → вставляет "User:" + popup
 * 2. При вводе "^User:" или "^User::" — показываем только методы "create", "method"
 *
 * restartCompletionOnPrefixChange(contains(":")) обеспечивает переход между этапами.
 */
public final class P3ClassCompletionContributor extends CompletionContributor {

	public P3ClassCompletionContributor() {
		extend(
				CompletionType.BASIC,
				PlatformPatterns.psiElement(),
				new CompletionProvider<CompletionParameters>() {
					@Override
					protected void addCompletions(
							@NotNull CompletionParameters parameters,
							@NotNull ProcessingContext context,
							@NotNull CompletionResultSet result
					) {
						PsiFile file = parameters.getOriginalFile();

						if (!Parser3PsiUtils.isParser3File(file)) {
							return;
						}

						// Регистронезависимый автокомплит
						result = P3CompletionUtils.makeCaseInsensitive(result);

						String completionContext = detectContext(parameters);

						if (completionContext.startsWith("class_method:")) {
							// ^User: или ^User:: — показываем только методы
							// Формат: "class_method:ClassName:single" или "class_method:ClassName:double"
							String[] parts = completionContext.split(":", -1);
							// parts[0]="class_method", parts[1]=имя класса, parts[2]="single"/"double"
							if (parts.length >= 3) {
								String className = parts[1];
								boolean isSingleColon = "single".equals(parts[2]);
								addClassMethods(parameters, result, file, className, isSingleColon);
							}
						} else if (completionContext.equals("class_name")) {
							// ^Us — показываем только имена классов
							addClassNames(parameters, result, file);
						}
					}
				}
		);
	}

	/**
	 * Определяем контекст автокомплита по тексту до курсора.
	 *
	 * Возможные результаты:
	 * - "class_method:ClassName:single" — курсор после "^ClassName:" (одно двоеточие)
	 * - "class_method:ClassName:double" — курсор после "^ClassName::" (два двоеточия)
	 * - "class_name"                    — курсор после "^" (только идентификатор, без двоеточий)
	 * - "none"                          — другой контекст
	 */
	private String detectContext(@NotNull CompletionParameters parameters) {
		CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
		int offset = clampOffset(text, parameters.getOffset());
		if (offset <= 0) {
			return "none";
		}

		// Собираем текст текущей строки до курсора
		StringBuilder contextBuilder = new StringBuilder();
		for (int i = offset - 1; i >= Math.max(0, offset - 200); i--) {
			if (i >= text.length()) continue;
			char ch = text.charAt(i);
			if (ch == '\n' || ch == '\r') {
				break;
			}
			contextBuilder.insert(0, ch);
		}
		String context = contextBuilder.toString();

		// Ищем последний ^ на строке
		int lastCaret = context.lastIndexOf('^');
		if (lastCaret < 0) {
			return "none";
		}

		// Проверяем что ^ не экранирован (например ^^ — экранирование)
		int caretCount = 0;
		for (int i = lastCaret - 1; i >= 0 && context.charAt(i) == '^'; i--) {
			caretCount++;
		}
		if (caretCount % 2 != 0) {
			// Нечётное число ^ перед нашим ^ — наш ^ экранирован
			return "none";
		}

		String afterCaret = context.substring(lastCaret + 1);

		// Сначала проверяем :: (двойное двоеточие) — важен порядок!
		if (afterCaret.contains("::")) {
			int dcPos = afterCaret.indexOf("::");
			String className = afterCaret.substring(0, dcPos);
			String afterDoubleColon = afterCaret.substring(dcPos + 2);
			// После :: до курсора должен быть только идентификатор (или пусто)
			if (isValidClassName(className) && isCleanMethodPrefix(afterDoubleColon)) {
				return "class_method:" + className + ":double";
			}
		}

		// Потом проверяем одиночное двоеточие
		if (afterCaret.contains(":")) {
			int cPos = afterCaret.indexOf(':');
			String className = afterCaret.substring(0, cPos);
			String afterColon = afterCaret.substring(cPos + 1);
			// После : до курсора должен быть только идентификатор (или пусто)
			// Это защита от случая "^User: тут текст ::create" — после : есть пробел
			if (isValidClassName(className) && isCleanMethodPrefix(afterColon)) {
				return "class_method:" + className + ":single";
			}
		}

		// Нет двоеточий — контекст имени класса
		// (пустой afterCaret тоже сюда попадает)
		if (!afterCaret.contains("[") && !afterCaret.contains("(") && !afterCaret.contains("{")) {
			return "class_name";
		}

		return "none";
	}

	/**
	 * Проверяет что текст после двоеточия является чистым префиксом метода:
	 * только идентификатор (или пусто). Никаких пробелов, скобок, переносов.
	 * Защита от ложного срабатывания в случае: "^User: тут текст ::create"
	 */
	private boolean isCleanMethodPrefix(@NotNull String afterColon) {
		for (int i = 0; i < afterColon.length(); i++) {
			char ch = afterColon.charAt(i);
			if (!Character.isLetterOrDigit(ch) && ch != '_') {
				return false;
			}
		}
		return true;
	}

	/**
	 * Проверяет что строка является допустимым именем пользовательского класса в Parser3.
	 * MAIN, BASE, self — обрабатываются отдельно в P3MethodCompletionContributor.
	 */
	private boolean isValidClassName(@NotNull String name) {
		if (name.isEmpty()) return false;
		if ("MAIN".equals(name)) return false;
		if ("BASE".equals(name)) return false;
		if ("self".equals(name)) return false;
		return name.matches(Parser3IdentifierUtils.NAME_REGEX);
	}

	/**
	 * Добавляет только имена классов (этап 1).
	 * InsertHandler вставляет "ClassName:" и открывает popup с методами.
	 * restartCompletionOnPrefixChange обеспечивает переход к показу методов.
	 */
	private void addClassNames(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file
	) {
		VirtualFile virtualFile = file.getVirtualFile();
		if (virtualFile == null) {
			return;
		}

		Project project = file.getProject();
		// Извлекаем что пользователь ввёл после ^
		CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
		int offset = clampOffset(text, parameters.getOffset());
		P3ScopeContext scopeContext = new P3ScopeContext(project, virtualFile, offset);
		StringBuilder typed = new StringBuilder();
		for (int i = offset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == '^' || ch == '\n' || ch == '\r') {
				break;
			}
			typed.insert(0, ch);
		}
		String typedText = typed.toString();

		CompletionResultSet resultWithPrefix = result.withPrefixMatcher(typedText);

		// Свойства встроенных классов (form:fields, env:PARSER_VERSION, etc.)
		// Показываем когда введено ClassName: и ClassName — встроенный класс
		if (typedText.contains(":") && !typedText.contains("::")) {
			int cp = typedText.indexOf(':');
			String clsName = typedText.substring(0, cp);
			String fldPrefix = typedText.substring(cp + 1);
			if (!fldPrefix.contains(".")
					&& ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(clsName)
					&& ru.artlebedev.parser3.lang.Parser3BuiltinMethods.supportsStaticPropertyAccess(clsName)) {
				CompletionResultSet propResult = result.withPrefixMatcher(fldPrefix);
				java.util.LinkedHashSet<String> addedNames = new java.util.LinkedHashSet<>();
				java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable> properties =
						ru.artlebedev.parser3.lang.Parser3BuiltinMethods.getStaticPropertiesForClass(clsName);
				for (ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable prop : properties) {
					ru.artlebedev.parser3.lang.Parser3DocLookupObject docObject =
							new ru.artlebedev.parser3.lang.Parser3DocLookupObject(
									clsName + ":" + prop.name, prop.url, prop.description);
					String typeText = prop.returnType != null ? prop.returnType : clsName;
					LookupElementBuilder propElement = LookupElementBuilder
							.create(docObject, prop.name + ".")
							.withLookupString(prop.name)
							.withIcon(Parser3Icons.FileVariable)
							.withTypeText(typeText, true)
							.withPresentableText(prop.name)
							.withTailText(prop.description != null ? " " + prop.description : null, true)
							.withInsertHandler((ctx, item) -> {
								// Удаляем дублирующую точку если есть
								com.intellij.openapi.editor.Document doc = ctx.getDocument();
								int tailOff = ctx.getTailOffset();
								CharSequence txt = doc.getCharsSequence();
								if (tailOff > 0 && tailOff < txt.length()
										&& txt.charAt(tailOff - 1) == '.' && txt.charAt(tailOff) == '.') {
									doc.deleteString(tailOff, tailOff + 1);
								}
								AutoPopupController.getInstance(ctx.getProject())
										.scheduleAutoPopup(ctx.getEditor());
							});
					propResult.addElement(
							PrioritizedLookupElement.withPriority(propElement, 95));
					addedNames.add(prop.name);
				}

				for (String localProperty : Parser3BuiltinStaticPropertyUsageUtils.collectPropertyNames(text, clsName, offset)) {
					if (!addedNames.add(localProperty)) {
						continue;
					}

					String localTypeText = Parser3BuiltinStaticPropertyUsageUtils.getLocalPropertyTypeText(clsName);
					LookupElementBuilder propElement = LookupElementBuilder
							.create(localProperty + ".")
							.withLookupString(localProperty)
							.withIcon(Parser3Icons.FileVariable)
							.withTypeText(localTypeText, true)
							.withPresentableText(localProperty)
							.withTailText(" найдено в файле", true)
							.withInsertHandler((ctx, item) -> {
								com.intellij.openapi.editor.Document doc = ctx.getDocument();
								int tailOff = ctx.getTailOffset();
								CharSequence txt = doc.getCharsSequence();
								if (tailOff > 0 && tailOff < txt.length()
										&& txt.charAt(tailOff - 1) == '.' && txt.charAt(tailOff) == '.') {
									doc.deleteString(tailOff, tailOff + 1);
								}
								AutoPopupController.getInstance(ctx.getProject())
										.scheduleAutoPopup(ctx.getEditor());
							});
					propResult.addElement(
							PrioritizedLookupElement.withPriority(propElement, 94));
				}
			}
		}

		// Добавляем только имена классов (без методов)
		if (scopeContext.isAllMethodsMode() || scopeContext.hasAutouse()) {
			addAllClassNamesOnly(resultWithPrefix, project, typedText, virtualFile);
		} else {
			addClassNamesFromFilesOnly(resultWithPrefix, project, scopeContext.getClassSearchFiles(), virtualFile);
		}

		// Перезапуск completion при вводе ":" — переход к показу методов
		result.restartCompletionOnPrefixChange(StandardPatterns.string().contains(":"));
	}

	/**
	 * Добавляет только имена классов из индекса (режим ALL_METHODS / @autouse).
	 */
	private void addAllClassNamesOnly(
			@NotNull CompletionResultSet result,
			@NotNull Project project,
			@NotNull String typedText,
			@NotNull VirtualFile currentFile
	) {
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		String filterPrefix = typedText.toLowerCase();
		Set<String> addedClasses = new HashSet<>();

		List<P3ClassDeclaration> allClasses = classIndex.getAllClasses();
		for (P3ClassDeclaration cls : allClasses) {
			if (result.isStopped()) return;

			String className = cls.getName();
			if ("MAIN".equals(className)) continue;
			if (addedClasses.contains(className)) continue;

			// Ранняя фильтрация по prefix
			if (!filterPrefix.isEmpty() && !className.toLowerCase().startsWith(filterPrefix)) {
				continue;
			}

			addedClasses.add(className);
			addClassNameElement(result, className, cls, project, currentFile);
		}
	}

	/**
	 * Добавляет только имена классов из видимых файлов (режим USE_ONLY).
	 */
	private void addClassNamesFromFilesOnly(
			@NotNull CompletionResultSet result,
			@NotNull Project project,
			@NotNull List<VirtualFile> visibleFiles,
			@org.jetbrains.annotations.Nullable VirtualFile currentFile
	) {
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		Set<String> addedClasses = new HashSet<>();

		for (VirtualFile visibleFile : visibleFiles) {
			List<P3ClassDeclaration> classes = classIndex.findInFile(visibleFile);

			for (P3ClassDeclaration classDecl : classes) {
				if (classDecl.isMainClass()) continue;

				String className = classDecl.getName();
				if (addedClasses.contains(className)) continue;
				addedClasses.add(className);

				addClassNameElement(result, className, classDecl, project, currentFile);
			}
		}
	}

	/**
	 * Добавляет один элемент с именем класса.
	 * InsertHandler: вставляет "ClassName:" и открывает popup с методами.
	 */
	private void addClassNameElement(
			@NotNull CompletionResultSet result,
			@NotNull String className,
			@NotNull P3ClassDeclaration classDecl,
			@NotNull Project project,
			@org.jetbrains.annotations.Nullable VirtualFile currentFile
	) {
		String formattedPath = ru.artlebedev.parser3.utils.Parser3PathFormatter.formatFilePathForUI(
				classDecl.getFile(), project, currentFile);

		// Краткий tailText — перечисление методов класса
		List<P3MethodDeclaration> methods = classDecl.getMethods();
		StringBuilder tailText = new StringBuilder();
		int shown = 0;
		for (P3MethodDeclaration m : methods) {
			String mn = Parser3ClassUtils.getCleanMethodName(m);
			if (P3CompletionUtils.isUncallableMethod(mn)) continue;
			if (shown > 0) tailText.append(", ");
			tailText.append(mn);
			shown++;
			if (shown >= 5) {
				tailText.append(", ...");
				break;
			}
		}

		LookupElementBuilder element = LookupElementBuilder.create(className)
				.withLookupString(className)
				.withIcon(Parser3Icons.FileMethod)
				.withTypeText(formattedPath, true)
				.withPresentableText(className)
				.withTailText(shown > 0 ? " " + tailText : null, true)
				.withInsertHandler((ctx, item) -> {
					com.intellij.openapi.editor.Document doc = ctx.getDocument();
					int tailOff = ctx.getTailOffset();
					CharSequence txt = doc.getCharsSequence();
					// Удаляем остаток идентификатора за курсором если есть
					int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
					if (endOff > tailOff) {
						doc.deleteString(tailOff, endOff);
					}
					// Вставляем ":"
					doc.insertString(tailOff, ":");
					ctx.getEditor().getCaretModel().moveToOffset(tailOff + 1);
					// Открываем popup с методами
					AutoPopupController.getInstance(ctx.getProject())
							.scheduleAutoPopup(ctx.getEditor());
				});

		result.addElement(element.withAutoCompletionPolicy(AutoCompletionPolicy.NEVER_AUTOCOMPLETE));
	}

	/**
	 * Добавляет методы класса (этап 2 — после ввода двоеточия).
	 * Показывает только имена методов, без "ClassName:" префикса.
	 *
	 * @param isSingleColon true = одно двоеточие (:), false = двойное (::)
	 */
	private void addClassMethods(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@NotNull String className,
			boolean isSingleColon
	) {
		VirtualFile virtualFile = file.getVirtualFile();
		if (virtualFile == null) {
			return;
		}

		Project project = file.getProject();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		// Извлекаем что пользователь ввёл после двоеточия (или двух двоеточий)
		CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
		int offset = clampOffset(text, parameters.getOffset());
		P3ScopeContext scopeContext = new P3ScopeContext(project, virtualFile, offset);
		List<VirtualFile> visibleFiles = scopeContext.getClassSearchFilesForClass(className);
		StringBuilder typed = new StringBuilder();
		for (int i = offset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == ':' || ch == '\n' || ch == '\r' || ch == '^') {
				break;
			}
			typed.insert(0, ch);
		}
		String methodPrefix = typed.toString();

		CompletionResultSet resultWithPrefix = result.withPrefixMatcher(methodPrefix);

		// Находим класс
		P3ClassDeclaration classDecl = classIndex.findLastInFiles(className, visibleFiles);
		if (classDecl == null) {
			return;
		}

		// Получаем все методы (включая наследованные)
		List<P3MethodDeclaration> methods = Parser3ClassUtils.getAllMethods(classDecl, visibleFiles, project);

		Set<String> addedNames = new HashSet<>();

		for (P3MethodDeclaration method : methods) {
			String methodName = Parser3ClassUtils.getCleanMethodName(method);

			// Пропускаем методы, не вызываемые явно
			if (P3CompletionUtils.isUncallableMethod(methodName)) {
				continue;
			}

			// Проверяем что метод подходит для данного типа вызова (: или ::)
			if (!Parser3ClassUtils.isMethodValidForCallType(method, classDecl, isSingleColon)) {
				continue;
			}

			if (addedNames.contains(methodName)) {
				continue;
			}
			addedNames.add(methodName);

			// Форматируем путь файла
			String formattedPath = ru.artlebedev.parser3.utils.Parser3PathFormatter.formatFilePathForUI(
					method.getFile(), project, virtualFile);

			// Объект с информацией для документации
			ru.artlebedev.parser3.lang.P3UserMethodLookupObject lookupObject =
					new ru.artlebedev.parser3.lang.P3UserMethodLookupObject(methodName, method);

			// tailText с параметрами и первой строкой doc
			String tailText = buildMethodTailText(method);

			// Вставляем только имя метода — класс и двоеточие уже введены
			LookupElementBuilder element = LookupElementBuilder.create(lookupObject, methodName)
					.withLookupString(methodName)
					.withPresentableText(methodName)
					.withIcon(Parser3Icons.FileMethod)
					.withTypeText(formattedPath, true)
					.withTailText(tailText.isEmpty() ? null : " " + tailText, true)
					.withInsertHandler(P3MethodInsertHandler.INSTANCE);

			resultWithPrefix.addElement(element);
		}

		// Перезапуск completion при вводе ":" — переход между : и ::
		// (Parser3CharFilter добавляет ':' к prefix, поэтому prefix меняется с "" на ":")
		result.restartCompletionOnPrefixChange(StandardPatterns.string().contains(":"));
	}

	@Override
	public AutoCompletionDecision handleAutoCompletionPossibility(
			@NotNull AutoCompletionContext context
	) {
		// Всегда показываем список, никогда не подставляем автоматически
		PsiFile file = context.getLookup().getPsiFile();
		if (file != null && Parser3PsiUtils.isParser3File(file)) {
			return AutoCompletionDecision.SHOW_LOOKUP;
		}
		return null;
	}

	@Override
	public boolean invokeAutoPopup(@NotNull com.intellij.psi.PsiElement position, char typeChar) {
		PsiFile file = position.getContainingFile();
		if (file == null || !Parser3PsiUtils.isParser3File(file) || Parser3PsiUtils.isInjectedFragment(file)) {
			return false;
		}

		CharSequence text = file.getViewProvider().getContents();
		int offset = position.getTextRange().getEndOffset() + 1;
		return shouldAutoPopupAfterClassMethodSeparator(text, offset, typeChar);
	}

	public static boolean shouldAutoPopupAfterClassMethodSeparator(
			@NotNull CharSequence text,
			int offset,
			char typeChar
	) {
		if (typeChar != ':') {
			return false;
		}

		int safeOffset = clampOffset(text, offset);
		if (safeOffset <= 0 || text.charAt(safeOffset - 1) != ':') {
			return false;
		}

		int lineStart = safeOffset;
		while (lineStart > 0) {
			char ch = text.charAt(lineStart - 1);
			if (ch == '\n' || ch == '\r') {
				break;
			}
			lineStart--;
		}

		int caretPos = -1;
		for (int i = safeOffset - 1; i >= lineStart; i--) {
			if (text.charAt(i) == '^') {
				caretPos = i;
				break;
			}
		}
		if (caretPos < 0) {
			return false;
		}

		String afterCaret = text.subSequence(caretPos + 1, safeOffset).toString();
		int separatorPos = afterCaret.indexOf(':');
		if (separatorPos < 0) {
			return false;
		}

		String className = afterCaret.substring(0, separatorPos);
		String afterSeparator = afterCaret.substring(separatorPos);
		if (!isValidUserClassNameForAutoPopup(className)) {
			return false;
		}
		return ":".equals(afterSeparator) || "::".equals(afterSeparator);
	}

	public static boolean shouldAutoPopupBeforeClassMethodSeparator(
			@NotNull CharSequence text,
			int offset,
			char typeChar
	) {
		if (typeChar != ':') {
			return false;
		}

		int safeOffset = clampOffset(text, offset);
		int lineStart = safeOffset;
		while (lineStart > 0) {
			char ch = text.charAt(lineStart - 1);
			if (ch == '\n' || ch == '\r') {
				break;
			}
			lineStart--;
		}

		int caretPos = -1;
		for (int i = safeOffset - 1; i >= lineStart; i--) {
			if (text.charAt(i) == '^') {
				caretPos = i;
				break;
			}
		}
		if (caretPos < 0) {
			return false;
		}

		String className = text.subSequence(caretPos + 1, safeOffset).toString();
		return isValidUserClassNameForAutoPopup(className);
	}

	private static boolean isValidUserClassNameForAutoPopup(@NotNull String name) {
		if (name.isEmpty()) return false;
		if ("MAIN".equals(name)) return false;
		if ("BASE".equals(name)) return false;
		if ("self".equals(name)) return false;
		return name.matches(Parser3IdentifierUtils.NAME_REGEX);
	}

	/**
	 * Формирует tailText для метода: "[param1;param2] первая строка doc"
	 */
	private static String buildMethodTailText(@NotNull P3MethodDeclaration method) {
		StringBuilder tailText = new StringBuilder();
		if (!method.getParameterNames().isEmpty()) {
			tailText.append("[").append(String.join(";", method.getParameterNames())).append("]");
		}
		if (method.getDocText() != null) {
			String firstLine = getFirstLine(method.getDocText());
			if (firstLine != null && !firstLine.isEmpty()) {
				if (tailText.length() > 0) tailText.append(" ");
				tailText.append(firstLine);
			}
		}
		return tailText.toString();
	}

	/**
	 * Возвращает первую непустую строку из текста
	 */
	private static String getFirstLine(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		int newlinePos = text.indexOf('\n');
		if (newlinePos >= 0) {
			return text.substring(0, newlinePos).trim();
		}
		return text.trim();
	}

	private static int clampOffset(@NotNull CharSequence text, int offset) {
		if (offset < 0) {
			return 0;
		}
		return Math.min(offset, text.length());
	}
}
