package ru.artlebedev.parser3.lang;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.index.P3MethodIndex;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.psi.P3PsiExtractor;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Parser3DocumentationProvider extends AbstractDocumentationProvider {




	private static final boolean DEBUG = false;
	private static final Key<Integer> P3DOC_HOVER_LOCAL_OFFSET_KEY = Key.create("P3DOC_HOVER_LOCAL_OFFSET");
	private static final ThreadLocal<Integer> P3DOC_HOVER_LOCAL_OFFSET = new ThreadLocal<>();
	private static final Pattern SYSTEM_PATTERN = Pattern.compile("\\^(?<name>[A-Za-z_][A-Za-z0-9_-]*)(?=\\(|\\[|\\{)");
	private static final Pattern CTOR_PATTERN = Pattern.compile("\\^([A-Za-z_][A-Za-z0-9_-]*)\\s*(::|:)\\s*(?<name>[A-Za-z_][A-Za-z0-9_-]*)");
	// Матчим каждое .name в цепочке отдельно (например ^data.menu.foreach — найдёт .menu и .foreach)
	// Lookbehind гарантирует что перед точкой есть идентификатор
	// n — имя метода после точки, var — NOT captured (для цепочек не нужен из этого паттерна)
	private static final Pattern DOT_CALL_PATTERN = Pattern.compile("(?<=[A-Za-z0-9_-])\\.([A-Za-z_][A-Za-z0-9_-]*)");
	// Паттерн для $class:field — доступ к свойствам встроенных классов
	private static final Pattern DOLLAR_PROP_PATTERN = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_-]*)");
	// Паттерн для $obj.field — доступ к свойствам через объект
	private static final Pattern DOLLAR_DOT_PATTERN = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_-]*)");

	@Override
	public @Nullable PsiElement getCustomDocumentationElement(
			@NotNull Editor editor,
			@NotNull PsiFile file,
			@Nullable PsiElement contextElement,
			int targetOffset
	) {
		if (contextElement == null) {
			return null;
		}
		try {
			CharSequence text = editor.getDocument().getCharsSequence();
			if (targetOffset < 0 || targetOffset > text.length()) {
				return contextElement;
			}
			int line = editor.getDocument().getLineNumber(targetOffset);
			int lineStart = editor.getDocument().getLineStartOffset(line);
			int lineEnd = editor.getDocument().getLineEndOffset(line);
			int localOffset = targetOffset - lineStart;
			String lineText = text.subSequence(lineStart, lineEnd).toString();
			boolean hotspot = isInDocHotspot(lineText, localOffset);
			if (hotspot) {
				contextElement.putUserData(P3DOC_HOVER_LOCAL_OFFSET_KEY, Integer.valueOf(localOffset));
			}
			return hotspot ? contextElement : null;
		} catch (Throwable t) {
			t.printStackTrace();
			return contextElement;
		}
	}

	private static boolean isInDocHotspot(@NotNull String lineText, int localOffset) {
		try {
			Matcher m = SYSTEM_PATTERN.matcher(lineText);
			while (m.find()) {
				int nameStart = m.start(1);
				int nameEnd = m.end(1);
				boolean afterBracket = hasBracketAfter(lineText, nameEnd);
				if (afterBracket && localOffset >= nameStart && localOffset <= nameEnd) {
					return true;
				}
			}

			Matcher ctorMatcher = CTOR_PATTERN.matcher(lineText);
			while (ctorMatcher.find()) {
				int classStart = ctorMatcher.start(1);
				int nameEnd = ctorMatcher.end(3);
				boolean afterBracket = hasBracketAfter(lineText, nameEnd);
				if (afterBracket && localOffset >= classStart && localOffset <= nameEnd) {
					return true;
				}
			}

			Matcher dotMatcher = DOT_CALL_PATTERN.matcher(lineText);
			while (dotMatcher.find()) {
				int nameStart = dotMatcher.start(1);
				int nameEnd = dotMatcher.end(1);
				boolean afterBracket = hasBracketAfter(lineText, nameEnd);
				if (afterBracket && localOffset >= nameStart && localOffset <= nameEnd) {
					return true;
				}
			}

			// $class:field — свойства встроенных классов (не требуют скобок)
			Matcher dollarPropMatcher = DOLLAR_PROP_PATTERN.matcher(lineText);
			while (dollarPropMatcher.find()) {
				String className = dollarPropMatcher.group(1);
				String fieldName = dollarPropMatcher.group(2);
				// Проверяем что это встроенный класс с таким свойством
				if (findBuiltinProperty(className, fieldName) != null) {
					int start = dollarPropMatcher.start(1);
					int end = dollarPropMatcher.end(2);
					if (localOffset >= start && localOffset <= end) {
						return true;
					}
				}
			}

			// $obj.field — свойства через объект (не требуют скобок)
			Matcher dollarDotMatcher = DOLLAR_DOT_PATTERN.matcher(lineText);
			while (dollarDotMatcher.find()) {
				int start = dollarDotMatcher.start(1);
				int end = dollarDotMatcher.end(2);
				if (localOffset >= start && localOffset <= end) {
					return true;
				}
			}

			return false;
		} catch (Throwable t) {
			t.printStackTrace();
			return true;
		}
	}

	/**
	 * Извлекает имя переменной непосредственно перед точкой.
	 * Для "^data.menu.foreach" и dotStart указывающим на точку перед foreach — вернёт "menu".
	 */
	private static @Nullable String extractVarBeforeDot(@NotNull String text, int dotPos) {
		// dotPos указывает на точку (lookbehind уже проверил что перед ней идентификатор)
		if (dotPos <= 0) return null;
		int end = dotPos; // точка
		int i = end - 1;
		while (i >= 0 && isIdentChar(text.charAt(i))) {
			i--;
		}
		if (i + 1 >= end) return null;
		return text.substring(i + 1, end);
	}

	private static boolean isIdentChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '-';
	}

	private static boolean hasBracketAfter(@NotNull String lineText, int index) {
		int i = index;
		while (i < lineText.length()) {
			char ch = lineText.charAt(i);
			if (!Character.isWhitespace(ch)) {
				boolean result = ch == '(' || ch == '[' || ch == '{';
				return result;
			}
			i++;
		}
		return false;
	}

	@Override
	public @Nullable String generateDoc(@NotNull PsiElement element, @Nullable PsiElement originalElement) {

		PsiElement target = originalElement != null ? originalElement : element;
		Integer __p3docLocalOffset = null;
		if (target != null) {
			__p3docLocalOffset = target.getUserData(P3DOC_HOVER_LOCAL_OFFSET_KEY);
		}
		if (__p3docLocalOffset == null) {
			__p3docLocalOffset = P3DOC_HOVER_LOCAL_OFFSET.get();
			P3DOC_HOVER_LOCAL_OFFSET.remove();
		}
		CallSignature signature = (__p3docLocalOffset != null ? findCallSignature(target, __p3docLocalOffset.intValue()) : findCallSignature(target));

		// Документация для встроенных методов из локальных HTML файлов
		if (element instanceof Parser3DocPsiElement) {
			String file = ((Parser3DocPsiElement) element).getFileName();
			String html = Parser3DocHtmlLoader.loadLocalHtmlByFileName(file);
			return html;
		}

		// Документация для пользовательских методов
		if (element instanceof Parser3UserMethodDocPsiElement) {
			ru.artlebedev.parser3.model.P3MethodDeclaration decl =
					((Parser3UserMethodDocPsiElement) element).getDeclaration();
			String html = decl.generateDocHtml();
			return html;
		}

		// Документация для свойств встроенных классов ($class:field, $obj.field)
		if (signature != null && "property".equals(signature.type)) {
			Parser3BuiltinMethods.BuiltinCallable prop = findBuiltinProperty(signature.className, signature.name);
			if (prop != null) {
				String localHtml = Parser3DocHtmlLoader.loadLocalHtml(prop.url);
				if (localHtml != null) {
					return localHtml;
				}
				// Fallback: генерируем простую документацию
				StringBuilder sb = new StringBuilder();
				sb.append("<b>$").append(StringUtil.escapeXmlEntities(signature.className))
						.append(":").append(StringUtil.escapeXmlEntities(prop.name)).append("</b>");
				if (prop.description != null && !prop.description.isEmpty()) {
					sb.append("<br/>").append(StringUtil.escapeXmlEntities(prop.description));
				}
				if (prop.url != null && !prop.url.isEmpty()) {
					sb.append("<br/><br/><a href=\"")
							.append(StringUtil.escapeXmlEntities(prop.url))
							.append("\">Открыть документацию на parser.ru</a>");
				}
				return sb.toString();
			}
		}

		if (signature == null) {
			return null;
		}

		P3MethodDeclaration userMethod = findUserMethodByNavigationLogic(element);
		if (userMethod != null) {
			return userMethod.generateDocHtml();
		}
		CallSignature builtinSignature = enrichBuiltinObjectSignature(target, signature);
		Parser3BuiltinMethods.BuiltinCallable callable = findBuiltinCallable(builtinSignature);
		if (callable == null) {
			// Не встроенный метод — пробуем найти пользовательский
			return null;
		}

		StringBuilder sb = new StringBuilder();


		String localHtml = Parser3DocHtmlLoader.loadLocalHtml(callable.url);
		if (localHtml != null) {
			return localHtml;
		}

		// доки не нашлись
		String suffix = callable.suffix != null ? callable.suffix : "";
		String title;
		if ("::".equals(builtinSignature.separator) || ":".equals(builtinSignature.separator)) {
			title = "^" + builtinSignature.className + builtinSignature.separator + callable.name + suffix;
		} else if (".".equals(builtinSignature.separator)) {
			title = "." + callable.name + suffix;
		} else {
			title = callable.name + suffix;
		}

		sb.append("<b>").append(StringUtil.escapeXmlEntities(title)).append("</b>");

		if (callable.description != null && !callable.description.isEmpty()) {
			sb.append("<br/>").append(StringUtil.escapeXmlEntities(callable.description));
		}

		if (callable.url != null && !callable.url.isEmpty()) {
			sb.append("<br/><br/><a href=\"")
					.append(StringUtil.escapeXmlEntities(callable.url))
					.append("\">")
					.append("Открыть документацию на parser.ru")
					.append("</a>");
		}

		return sb.toString();
	}

	public PsiElement getDocumentationElementForLink(
			@org.jetbrains.annotations.NotNull com.intellij.psi.PsiManager psiManager,
			@org.jetbrains.annotations.NotNull String link,
			com.intellij.psi.PsiElement context
	) {
		if (!link.startsWith("p3doc/")) return null;
		String file = link.substring("p3doc/".length());
		return new Parser3DocPsiElement(psiManager, file);
	}

	/**
	 * Возвращает элемент для показа документации в списке автокомплита (Ctrl+Q).
	 *
	 * Работает для:
	 * - Встроенных методов Parser3 (^if, ^for, ^hash::create и т.п.)
	 * - Пользовательских методов (P3UserMethodLookupObject)
	 * - Если LookupElement содержит Parser3DocLookupObject
	 */
	@Override
	public @Nullable PsiElement getDocumentationElementForLookupItem(
			@NotNull PsiManager psiManager,
			@NotNull Object object,
			@Nullable PsiElement element
	) {
		// Если это пользовательский метод — всегда показываем документацию
		// (хотя бы имя метода и параметры)
		if (object instanceof P3UserMethodLookupObject) {
			P3UserMethodLookupObject userMethod = (P3UserMethodLookupObject) object;
			if (userMethod.declaration != null) {
				return new Parser3UserMethodDocPsiElement(psiManager, userMethod.declaration);
			}
			return null;
		}

		// Если это наш специальный объект с документацией для встроенных методов/свойств
		if (object instanceof Parser3DocLookupObject) {
			Parser3DocLookupObject docObject = (Parser3DocLookupObject) object;
			if (docObject.url != null) {
				// Извлекаем имя файла из URL
				int slash = docObject.url.lastIndexOf('/');
				if (slash >= 0 && slash < docObject.url.length() - 1) {
					String fileName = docObject.url.substring(slash + 1);
					return new Parser3DocPsiElement(psiManager, fileName);
				}
			}
		}

		// Попробуем извлечь информацию из строки (для обычных LookupElement)
		if (object instanceof String) {
			String lookupString = (String) object;

			// Сначала проверяем встроенные методы
			Parser3BuiltinMethods.BuiltinCallable callable = findCallableByLookupString(lookupString);
			if (callable != null && callable.url != null) {
				int slash = callable.url.lastIndexOf('/');
				if (slash >= 0 && slash < callable.url.length() - 1) {
					String fileName = callable.url.substring(slash + 1);
					return new Parser3DocPsiElement(psiManager, fileName);
				}
			}

			// Пробуем найти пользовательский метод по строке формата ClassName:methodName или ClassName::methodName
			P3MethodDeclaration userDecl = findUserMethodByLookupString(psiManager.getProject(), lookupString);
			if (userDecl != null) {
				return new Parser3UserMethodDocPsiElement(psiManager, userDecl);
			}
		}

		return null;
	}

	/**
	 * Ищет пользовательский метод по строке формата ClassName:methodName или ClassName::methodName.
	 */
	private static @Nullable P3MethodDeclaration findUserMethodByLookupString(
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull String lookupString
	) {
		// Убираем возможный ^ в начале
		if (lookupString.startsWith("^")) {
			lookupString = lookupString.substring(1);
		}

		String className;
		String methodName;

		// Формат: ClassName::methodName или ClassName:methodName
		if (lookupString.contains("::")) {
			String[] parts = lookupString.split("::", 2);
			if (parts.length != 2) return null;
			className = parts[0];
			methodName = parts[1];
		} else if (lookupString.contains(":")) {
			String[] parts = lookupString.split(":", 2);
			if (parts.length != 2) return null;
			className = parts[0];
			methodName = parts[1];
		} else {
			return null; // Не формат класс:метод
		}

		if (className.isEmpty() || methodName.isEmpty()) {
			return null;
		}

		// Ищем класс и метод через индекс
		ru.artlebedev.parser3.index.P3ClassIndex classIndex =
				ru.artlebedev.parser3.index.P3ClassIndex.getInstance(project);

		// Получаем все определения класса
		java.util.List<ru.artlebedev.parser3.model.P3ClassDeclaration> classDecls = classIndex.findByName(className);
		if (classDecls.isEmpty()) {
			return null;
		}

		// Ищем метод в определениях класса
		for (ru.artlebedev.parser3.model.P3ClassDeclaration classDecl : classDecls) {
			for (P3MethodDeclaration method : classDecl.getMethods()) {
				String cleanName = ru.artlebedev.parser3.utils.Parser3ClassUtils.getCleanMethodName(method);
				if (cleanName.equals(methodName)) {
					return method;
				}
			}
		}

		return null;
	}

	/**
	 * Ищет пользовательский метод по сигнатуре вызова.
	 * Для hover документации.
	 */
	private static @Nullable P3MethodDeclaration findUserMethodByNavigationLogic(@NotNull PsiElement element) {
		PsiFile file = element.getContainingFile();
		if (file == null) {
			return null;
		}

		com.intellij.openapi.vfs.VirtualFile currentFile = file.getVirtualFile();
		if (currentFile == null) {
			return null;
		}

		com.intellij.openapi.project.Project project = element.getProject();
		P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(element);
		if (!callInfo.isValid()) {
			return null;
		}

		String methodName = callInfo.getMethodName();
		int offset = element.getTextOffset();
		List<com.intellij.openapi.vfs.VirtualFile> visibleFiles = getVisibleFilesForMethods(project, currentFile, offset);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		if (callInfo.isObjectMethodCall()) {
			String variableName = callInfo.getVariableName();
			if (variableName == null || variableName.isEmpty()) {
				return null;
			}
			ru.artlebedev.parser3.index.P3VariableIndex variableIndex =
					ru.artlebedev.parser3.index.P3VariableIndex.getInstance(project);
			ru.artlebedev.parser3.index.P3VariableIndex.ChainResolveInfo chainInfo =
					variableIndex.resolveEffectiveChain(variableName, visibleFiles, currentFile, offset);
			String varClassName = chainInfo != null ? chainInfo.className : null;
			if (varClassName == null || varClassName.isEmpty()) {
				return null;
			}
			return findClassMethodInVisibleFiles(project, varClassName, methodName, getVisibleFilesForClasses(project, currentFile, offset));
		}

		if (callInfo.isClassMethod()) {
			String className = callInfo.getClassName();
			if (className == null || className.isEmpty()) {
				return null;
			}
			return findClassMethodInVisibleFiles(project, className, methodName, getVisibleFilesForClasses(project, currentFile, offset));
		}

		if (callInfo.isBaseCall()) {
			ru.artlebedev.parser3.model.P3ClassDeclaration currentClass = classIndex.findClassAtOffset(currentFile, offset);
			if (currentClass == null || currentClass.getBaseClassName() == null || currentClass.getBaseClassName().isEmpty()) {
				return null;
			}
			return findClassMethodInVisibleFiles(project, currentClass.getBaseClassName(), methodName, visibleFiles);
		}

		if (callInfo.isSelfCall()) {
			ru.artlebedev.parser3.model.P3ClassDeclaration currentClass = classIndex.findClassAtOffset(currentFile, offset);
			if (currentClass != null && !"MAIN".equals(currentClass.getName())) {
				return findFirstMethodInHierarchy(project, currentClass, methodName, visibleFiles);
			}
			return findMainMethodInVisibleFiles(project, methodName, visibleFiles, currentFile);
		}

		if (callInfo.isMainCall()) {
			return findMainMethodInVisibleFiles(project, methodName, visibleFiles, currentFile);
		}

		ru.artlebedev.parser3.model.P3ClassDeclaration currentClass = classIndex.findClassAtOffset(currentFile, offset);
		if (currentClass != null && !"MAIN".equals(currentClass.getName())) {
			P3MethodDeclaration method = findFirstMethodInHierarchy(project, currentClass, methodName, visibleFiles);
			if (method != null) {
				return method;
			}
		}

		return findMainMethodInVisibleFiles(project, methodName, visibleFiles, currentFile);
	}

	private static @NotNull List<com.intellij.openapi.vfs.VirtualFile> getVisibleFilesForMethods(
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull com.intellij.openapi.vfs.VirtualFile currentFile,
			int cursorOffset
	) {
		return new P3ScopeContext(project, currentFile, cursorOffset).getMethodSearchFiles();
	}

	private static @NotNull List<com.intellij.openapi.vfs.VirtualFile> getVisibleFilesForClasses(
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull com.intellij.openapi.vfs.VirtualFile currentFile,
			int cursorOffset
	) {
		return new P3ScopeContext(project, currentFile, cursorOffset).getClassSearchFiles();
	}

	private static @Nullable P3MethodDeclaration findClassMethodInVisibleFiles(
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull String className,
			@NotNull String methodName,
			@NotNull List<com.intellij.openapi.vfs.VirtualFile> visibleFiles
	) {
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		List<ru.artlebedev.parser3.model.P3ClassDeclaration> visibleClassDecls = new java.util.ArrayList<>();
		for (ru.artlebedev.parser3.model.P3ClassDeclaration decl : classIndex.findByName(className)) {
			if (visibleFiles.contains(decl.getFile())) {
				visibleClassDecls.add(decl);
			}
		}
		if (visibleClassDecls.isEmpty()) {
			return null;
		}

		List<com.intellij.openapi.vfs.VirtualFile> classFiles = new java.util.ArrayList<>();
		for (ru.artlebedev.parser3.model.P3ClassDeclaration decl : visibleClassDecls) {
			if (!classFiles.contains(decl.getFile())) {
				classFiles.add(decl.getFile());
			}
		}

		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		for (P3MethodDeclaration method : methodIndex.findInFiles(methodName, classFiles)) {
			if (className.equals(findClassNameForMethod(method, project))) {
				return method;
			}
		}

		ru.artlebedev.parser3.model.P3ClassDeclaration lastDecl = visibleClassDecls.get(visibleClassDecls.size() - 1);
		String baseClassName = lastDecl.getBaseClassName();
		if (baseClassName == null || baseClassName.isEmpty()) {
			return null;
		}
		return findClassMethodInVisibleFiles(project, baseClassName, methodName, visibleFiles);
	}

	private static @Nullable P3MethodDeclaration findFirstMethodInHierarchy(
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull ru.artlebedev.parser3.model.P3ClassDeclaration currentClass,
			@NotNull String methodName,
			@NotNull List<com.intellij.openapi.vfs.VirtualFile> visibleFiles
	) {
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> allMethods = methodIndex.findInFiles(methodName, visibleFiles);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		ru.artlebedev.parser3.model.P3ClassDeclaration current = currentClass;
		java.util.Set<String> visited = new java.util.HashSet<>();

		while (current != null && visited.add(current.getName())) {
			for (P3MethodDeclaration method : allMethods) {
				if (isMethodDirectlyInClass(method, current)) {
					return method;
				}
			}
			String baseName = current.getBaseClassName();
			if (baseName == null || baseName.isEmpty()) {
				break;
			}
			current = classIndex.findLastInFiles(baseName, visibleFiles);
		}

		return null;
	}

	private static @Nullable P3MethodDeclaration findMainMethodInVisibleFiles(
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull String methodName,
			@NotNull List<com.intellij.openapi.vfs.VirtualFile> visibleFiles,
			@NotNull com.intellij.openapi.vfs.VirtualFile currentFile
	) {
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> mainMethods = new java.util.ArrayList<>();
		for (P3MethodDeclaration method : methodIndex.findInFiles(methodName, visibleFiles)) {
			if ("MAIN".equals(findClassNameForMethod(method, project))) {
				mainMethods.add(method);
			}
		}
		if (mainMethods.isEmpty()) {
			return null;
		}

		mainMethods.sort((a, b) -> {
			boolean aIsCurrent = a.getFile().equals(currentFile);
			boolean bIsCurrent = b.getFile().equals(currentFile);
			if (aIsCurrent && !bIsCurrent) return -1;
			if (!aIsCurrent && bIsCurrent) return 1;

			int aIndex = visibleFiles.indexOf(a.getFile());
			int bIndex = visibleFiles.indexOf(b.getFile());
			if (aIndex == -1) aIndex = Integer.MAX_VALUE;
			if (bIndex == -1) bIndex = Integer.MAX_VALUE;
			return Integer.compare(aIndex, bIndex);
		});
		return mainMethods.get(0);
	}

	private static boolean isMethodDirectlyInClass(
			@NotNull P3MethodDeclaration method,
			@NotNull ru.artlebedev.parser3.model.P3ClassDeclaration classDecl
	) {
		if (!method.getFile().equals(classDecl.getFile())) {
			return false;
		}
		int methodOffset = method.getOffset();
		return methodOffset >= classDecl.getStartOffset() && methodOffset < classDecl.getEndOffset();
	}

	private static @NotNull String findClassNameForMethod(
			@NotNull P3MethodDeclaration method,
			@NotNull com.intellij.openapi.project.Project project
	) {
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		ru.artlebedev.parser3.model.P3ClassDeclaration classDecl = classIndex.findClassAtOffset(method.getFile(), method.getOffset());
		return classDecl != null ? classDecl.getName() : "MAIN";
	}

	private static @Nullable P3MethodDeclaration findUserMethodBySignature(
			@NotNull PsiElement element,
			@NotNull CallSignature signature
	) {
		com.intellij.openapi.project.Project project = element.getProject();
		com.intellij.openapi.vfs.VirtualFile currentFile = element.getContainingFile().getVirtualFile();
		if (currentFile == null) {
			return null;
		}

		String methodName = signature.name;
		String className = signature.className;
		String separator = signature.separator;
		String variableName = signature.variableName;

		ru.artlebedev.parser3.index.P3ClassIndex classIndex =
				ru.artlebedev.parser3.index.P3ClassIndex.getInstance(project);

		// Определяем текущий класс (где находится курсор)
		ru.artlebedev.parser3.model.P3ClassDeclaration currentClass =
				classIndex.findClassAtOffset(currentFile, element.getTextOffset());
		String currentClassName = currentClass != null ? currentClass.getName() : null;

		// Обработка self.method
		if (".".equals(separator) && "self".equalsIgnoreCase(className)) {
			if (currentClassName != null && !"MAIN".equals(currentClassName)) {
				return findMethodInClassHierarchy(project, currentClassName, methodName, classIndex);
			}
			return findUserMethodInMainOrVisibleFiles(element, methodName);
		}

		// Обработка ^var.method[] — определяем тип переменной
		if (".".equals(separator) && "self".equalsIgnoreCase(variableName)) {
			if (currentClassName != null && !"MAIN".equals(currentClassName)) {
				return findMethodInClassHierarchy(project, currentClassName, methodName, classIndex);
			}
			return findUserMethodInMainOrVisibleFiles(element, methodName);
		}

		if (".".equals(separator) && variableName != null && !variableName.isEmpty()) {
			ru.artlebedev.parser3.index.P3VariableIndex varTypeIndex =
					ru.artlebedev.parser3.index.P3VariableIndex.getInstance(project);

			java.util.List<com.intellij.openapi.vfs.VirtualFile> visibleFiles =
					getVisibleFilesForMethods(project, currentFile, element.getTextOffset());

			// Определяем тип переменной (поддерживает цепочки var.prop)
			ru.artlebedev.parser3.index.P3VariableIndex.ChainResolveInfo chainInfo =
					varTypeIndex.resolveEffectiveChain(
							variableName,
							visibleFiles,
							currentFile,
							element.getTextOffset()
					);
			String varClassName = chainInfo != null ? chainInfo.className : null;

			if (varClassName != null) {
				// Ищем метод в классе переменной
				return findMethodInClassHierarchy(project, varClassName, methodName, classIndex);
			}

			// Для вызова вида ^var.method[] не повторяем fallback navigation в MAIN/current class:
			// если тип переменной не резолвится, пользовательский метод здесь не найден.
			return null;
		}

		// Обработка BASE:method
		if ("BASE".equals(className)) {
			if (currentClass != null && currentClass.getBaseClassName() != null) {
				return findMethodInClassHierarchy(project, currentClass.getBaseClassName(), methodName, classIndex);
			}
			return null;
		}

		// Если есть конкретное имя класса (User:method, User::method)
		if (className != null && !className.isEmpty()) {
			return findMethodInClassHierarchy(project, className, methodName, classIndex);
		}

		// Если нет класса (^methodName) — ищем в текущем классе, потом в MAIN
		// Сначала в текущем классе
		if (currentClassName != null && !"MAIN".equals(currentClassName)) {
			P3MethodDeclaration method = findMethodInClassHierarchy(project, currentClassName, methodName, classIndex);
			if (method != null) {
				return method;
			}
		}

		// Потом в MAIN и видимых файлах
		return findUserMethodInMainOrVisibleFiles(element, methodName);
	}

	/**
	 * Ищет метод в классе и его иерархии наследования (через @BASE).
	 */
	private static @Nullable P3MethodDeclaration findMethodInClassHierarchy(
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull String className,
			@NotNull String methodName,
			@NotNull ru.artlebedev.parser3.index.P3ClassIndex classIndex
	) {
		java.util.Set<String> visited = new java.util.HashSet<>();
		String currentClassName = className;

		while (currentClassName != null && !visited.contains(currentClassName)) {
			visited.add(currentClassName);

			// Получаем все определения класса
			java.util.List<ru.artlebedev.parser3.model.P3ClassDeclaration> classDecls =
					classIndex.findByName(currentClassName);

			// Ищем метод в определениях класса
			for (ru.artlebedev.parser3.model.P3ClassDeclaration classDecl : classDecls) {
				for (P3MethodDeclaration method : classDecl.getMethods()) {
					String cleanName = ru.artlebedev.parser3.utils.Parser3ClassUtils.getCleanMethodName(method);
					if (cleanName.equals(methodName)) {
						return method;
					}
				}
			}

			// Переходим к базовому классу
			if (!classDecls.isEmpty()) {
				currentClassName = classDecls.get(0).getBaseClassName();
			} else {
				break;
			}
		}

		return null;
	}

	/**
	 * Ищет пользовательский метод в MAIN классе или видимых файлах.
	 */
	private static @Nullable P3MethodDeclaration findUserMethodInMainOrVisibleFiles(
			@NotNull PsiElement element,
			@NotNull String methodName
	) {
		com.intellij.openapi.project.Project project = element.getProject();
		com.intellij.openapi.vfs.VirtualFile currentFile = element.getContainingFile().getVirtualFile();

		if (currentFile == null) {
			return null;
		}

		com.intellij.util.indexing.FileBasedIndex index = com.intellij.util.indexing.FileBasedIndex.getInstance();

		// Сначала ищем в текущем файле (MAIN методы)
		java.util.Map<String, java.util.List<ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo>> fileData =
				index.getFileData(ru.artlebedev.parser3.index.P3MethodFileIndex.NAME, currentFile, project);

		java.util.List<ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo> infos = fileData.get(methodName);
		if (infos != null && !infos.isEmpty()) {
			// Предпочитаем методы из MAIN (ownerClass == null)
			for (ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo info : infos) {
				if (info.ownerClass == null) {
					return createMethodDeclaration(methodName, currentFile, info);
				}
			}
		}

		// Если не нашли в текущем файле, ищем в видимых файлах
		java.util.List<com.intellij.openapi.vfs.VirtualFile> visibleFiles =
				getVisibleFilesForMethods(project, currentFile, element.getTextOffset());

		for (com.intellij.openapi.vfs.VirtualFile file : visibleFiles) {
			if (file.equals(currentFile)) continue;

			java.util.Map<String, java.util.List<ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo>> data =
					index.getFileData(ru.artlebedev.parser3.index.P3MethodFileIndex.NAME, file, project);

			java.util.List<ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo> methodInfos = data.get(methodName);
			if (methodInfos != null) {
				for (ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo info : methodInfos) {
					if (info.ownerClass == null) {
						return createMethodDeclaration(methodName, file, info);
					}
				}
			}
		}

		return null;
	}

	/**
	 * Создаёт P3MethodDeclaration из MethodInfo.
	 */
	private static @NotNull P3MethodDeclaration createMethodDeclaration(
			@NotNull String methodName,
			@NotNull com.intellij.openapi.vfs.VirtualFile file,
			@NotNull ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo info
	) {
		// Конвертируем DocParam в P3MethodParameter
		java.util.List<ru.artlebedev.parser3.model.P3MethodParameter> docParams = new java.util.ArrayList<>();
		for (ru.artlebedev.parser3.index.P3MethodFileIndex.DocParam dp : info.docParams) {
			docParams.add(new ru.artlebedev.parser3.model.P3MethodParameter(
					dp.name, dp.type, dp.description, false));
		}

		ru.artlebedev.parser3.model.P3MethodParameter docResult = null;
		if (info.docResult != null) {
			docResult = new ru.artlebedev.parser3.model.P3MethodParameter(
					info.docResult.name,
					info.docResult.type,
					info.docResult.description,
					true);
		}

		return new P3MethodDeclaration(
				methodName,
				file,
				info.offset,
				null,
				info.parameterNames,
				info.docText,
				docParams,
				docResult,
				info.inferredResult,
				info.isGetter,
				info.callType
		);
	}

	/**
	 * Ищет свойство встроенного класса по имени класса и имени свойства.
	 */
	private static @Nullable Parser3BuiltinMethods.BuiltinCallable findBuiltinProperty(
			@NotNull String className, @NotNull String fieldName) {
		List<Parser3BuiltinMethods.BuiltinCallable> props =
				Parser3BuiltinMethods.getPropertiesForClass(className);
		for (Parser3BuiltinMethods.BuiltinCallable prop : props) {
			if (fieldName.equals(prop.name)) {
				return prop;
			}
		}
		return null;
	}

	/**
	 * Ищет BuiltinCallable по строке из LookupElement.
	 * Поддерживает форматы: "hash::create", "file:list", "if", ".count" и т.п.
	 */
	private static @Nullable Parser3BuiltinMethods.BuiltinCallable findCallableByLookupString(@NotNull String lookupString) {
		// Убираем возможный ^ в начале
		if (lookupString.startsWith("^")) {
			lookupString = lookupString.substring(1);
		}

		// Формат: ClassName::methodName (конструктор)
		if (lookupString.contains("::")) {
			String[] parts = lookupString.split("::", 2);
			if (parts.length == 2) {
				String className = parts[0];
				String methodName = parts[1];
				for (Parser3BuiltinMethods.CaretConstructor item : Parser3BuiltinMethods.getAllConstructorsMeta()) {
					if (className.equals(item.className) && methodName.equals(item.callable.name)) {
						return item.callable;
					}
				}
			}
		}
		// Формат: ClassName:methodName (статический метод)
		else if (lookupString.contains(":")) {
			String[] parts = lookupString.split(":", 2);
			if (parts.length == 2) {
				String className = parts[0];
				String methodName = parts[1];
				for (Parser3BuiltinMethods.CaretConstructor item : Parser3BuiltinMethods.getAllStaticMethodsMeta()) {
					if (className.equals(item.className) && methodName.equals(item.callable.name)) {
						return item.callable;
					}
				}
			}
		}
		// Формат: .methodName (метод объекта)
		else if (lookupString.startsWith(".")) {
			String methodName = lookupString.substring(1);
			for (Parser3BuiltinMethods.CaretConstructor item : Parser3BuiltinMethods.getAllMethodsMeta()) {
				if (methodName.equals(item.callable.name)) {
					return item.callable;
				}
			}
		}
		// Формат: methodName (системный метод)
		else {
			for (Parser3BuiltinMethods.CaretConstructor item : Parser3BuiltinMethods.getAllSystemMethodsMeta()) {
				if (lookupString.equals(item.callable.name)) {
					return item.callable;
				}
			}
		}

		return null;
	}


	@Override
	public @Nullable String generateHoverDoc(@NotNull PsiElement element, @Nullable PsiElement originalElement) {
		return generateDoc(element, originalElement);
	}


	private static @Nullable CallSignature findCallSignature(@NotNull PsiElement element, int localOffset) {
		// Получаем текст всей строки из файла, а не из элемента
		PsiFile psiFile = element.getContainingFile();
		if (psiFile == null) {
			return findCallSignature(element);
		}
		String fileText = psiFile.getText();
		if (fileText == null || fileText.isEmpty()) {
			return findCallSignature(element);
		}

		int elementOffset = element.getTextOffset();
		// Находим начало и конец строки
		int lineStart = elementOffset;
		while (lineStart > 0 && fileText.charAt(lineStart - 1) != '\n' && fileText.charAt(lineStart - 1) != '\r') {
			lineStart--;
		}
		int lineEnd = elementOffset;
		while (lineEnd < fileText.length() && fileText.charAt(lineEnd) != '\n' && fileText.charAt(lineEnd) != '\r') {
			lineEnd++;
		}

		String line = fileText.substring(lineStart, lineEnd);

		Matcher systemCtorMatcher = SYSTEM_PATTERN.matcher(line);
		while (systemCtorMatcher.find()) {
			String name = systemCtorMatcher.group(1);
			if (name == null) {
				continue;
			}
			int nameStart = systemCtorMatcher.start(1);
			int nameEnd = systemCtorMatcher.end(1);
			boolean afterBracket = hasBracketAfter(line, nameEnd);
			if (!afterBracket) {
				continue;
			}
			if (localOffset >= nameStart && localOffset < nameEnd) {
				return new CallSignature("", "", name, "system_method");
			}
		}

		// Добавляем обработку конструкторов и статических методов
		Matcher ctorMatcher = CTOR_PATTERN.matcher(line);
		while (ctorMatcher.find()) {
			String className = ctorMatcher.group(1);
			String separator = ctorMatcher.group(2);
			String name = ctorMatcher.group(3);
			if (className == null || name == null || separator == null) {
				continue;
			}
			int classStart = ctorMatcher.start(1);
			int nameEnd = ctorMatcher.end(3);
			boolean afterBracket = hasBracketAfter(line, nameEnd);
			if (!afterBracket) {
				continue;
			}
			if (localOffset >= classStart && localOffset <= nameEnd) {
				return new CallSignature(className, separator, name, "constructor_or_static");
			}
		}

		Matcher dotMatcher = DOT_CALL_PATTERN.matcher(line);
		while (dotMatcher.find()) {
			String name = dotMatcher.group(1);
			// Извлекаем имя переменной перед точкой (идентификатор непосредственно до точки)
			String varName = extractVarBeforeDot(line, dotMatcher.start());
			if (name == null) {
				continue;
			}
			int nameStart = dotMatcher.start(1);
			int nameEnd = dotMatcher.end(1);
			boolean afterBracket = hasBracketAfter(line, nameEnd);
			if (!afterBracket) {
				continue;
			}
			if (localOffset >= nameStart && localOffset < nameEnd) {
				return new CallSignature("", ".", name, "method", varName);
			}
		}

		// $class:field — свойства встроенных классов
		Matcher dollarPropMatcher = DOLLAR_PROP_PATTERN.matcher(line);
		while (dollarPropMatcher.find()) {
			String className = dollarPropMatcher.group(1);
			String fieldName = dollarPropMatcher.group(2);
			if (findBuiltinProperty(className, fieldName) != null) {
				int start = dollarPropMatcher.start(1);
				int end = dollarPropMatcher.end(2);
				if (localOffset >= start && localOffset <= end) {
					return new CallSignature(className, ":", fieldName, "property");
				}
			}
		}

		return null;
	}

	private static @Nullable CallSignature findCallSignature(@NotNull PsiElement element) {
		PsiFile file = element.getContainingFile();
		if (file == null) {
			return null;
		}

		String text = file.getText();
		if (text == null || text.isEmpty()) {
			return null;
		}

		int offset = element.getTextOffset();
		if (offset < 0 || offset >= text.length()) {
			return null;
		}

		int lineStart = offset;
		while (lineStart > 0) {
			char c = text.charAt(lineStart - 1);
			if (c == '\n' || c == '\r') {
				break;
			}
			lineStart--;
		}

		int lineEnd = offset;
		int length = text.length();
		while (lineEnd < length) {
			char c = text.charAt(lineEnd);
			if (c == '\n' || c == '\r') {
				break;
			}
			lineEnd++;
		}

		if (lineStart >= lineEnd) {
			return null;
		}

		String line = text.substring(lineStart, lineEnd);
		int localOffset = offset - lineStart;


		// ^if, ^eval, ^return
		Matcher systemCtorMatcher = SYSTEM_PATTERN.matcher(line);
		while (systemCtorMatcher.find()) {
			int start = systemCtorMatcher.start();
			int end = systemCtorMatcher.end();
			if (false && (localOffset < start || localOffset > end)) {
				continue;
			}
			String name = systemCtorMatcher.group(1);
			if (name == null) {
				continue;
			}
			return new CallSignature("", "", name, "system_method");
		}
		// 1. Конструкторы и статические методы: ^hash::sql{}, ^file:list[] и т.п.
		Matcher ctorMatcher = CTOR_PATTERN.matcher(line);
		while (ctorMatcher.find()) {
			int start = ctorMatcher.start();
			int end = ctorMatcher.end();
			if (false && (localOffset < start || localOffset > end)) {
				continue;
			}
			String className = ctorMatcher.group(1);
			String separator = ctorMatcher.group(2);
			String name = ctorMatcher.group(3);
			if (className == null || name == null || separator == null) {
				continue;
			}
			return new CallSignature(className, separator, name, "constructor_or_static");
		}

		// 2. Методы с точкой: .count[], .foreach[], в том числе в цепочках ^data.xxx.count[]
		Matcher dotMatcher = DOT_CALL_PATTERN.matcher(line);
		String lastName = null;
		String lastVarName = null;
		while (dotMatcher.find()) {
			String name = dotMatcher.group(1);
			if (name == null) continue;
			int nameEnd = dotMatcher.end(1);
			// Без скобок после имени — это не вызов метода (например file.p)
			if (!hasBracketAfter(line, nameEnd)) continue;
			lastName = name;
			lastVarName = extractVarBeforeDot(line, dotMatcher.start());
		}
		if (lastName != null) {
			return new CallSignature("", ".", lastName, "method", lastVarName);
		}

		// $class:field — свойства встроенных классов
		Matcher dollarPropMatcher = DOLLAR_PROP_PATTERN.matcher(line);
		while (dollarPropMatcher.find()) {
			String className = dollarPropMatcher.group(1);
			String fieldName = dollarPropMatcher.group(2);
			if (findBuiltinProperty(className, fieldName) != null) {
				return new CallSignature(className, ":", fieldName, "property");
			}
		}

		return null;
	}

	/**
	 * Для ^var.method[] уточняет класс встроенного объекта по типу переменной.
	 */
	private static @NotNull CallSignature enrichBuiltinObjectSignature(
			@NotNull PsiElement element,
			@NotNull CallSignature signature
	) {
		if (!".".equals(signature.separator)
				|| signature.className != null && !signature.className.isEmpty()
				|| signature.variableName == null
				|| signature.variableName.isEmpty()) {
			return signature;
		}

		PsiFile file = element.getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return signature;
		}

		com.intellij.openapi.vfs.VirtualFile currentFile = file.getVirtualFile();
		com.intellij.openapi.project.Project project = element.getProject();
		java.util.List<com.intellij.openapi.vfs.VirtualFile> visibleFiles =
				getVisibleFilesForMethods(project, currentFile, element.getTextOffset());
		ru.artlebedev.parser3.index.P3VariableIndex variableIndex =
				ru.artlebedev.parser3.index.P3VariableIndex.getInstance(project);
		ru.artlebedev.parser3.index.P3VariableIndex.ChainResolveInfo chainInfo =
				variableIndex.resolveEffectiveChain(signature.variableName, visibleFiles, currentFile, element.getTextOffset());
		String className = chainInfo != null ? chainInfo.className : null;
		if (className == null || className.isEmpty() || !Parser3BuiltinMethods.isBuiltinClass(className)) {
			if (DEBUG) System.out.println("[Parser3DocumentationProvider] Не удалось уточнить тип для ^"
					+ signature.variableName + "." + signature.name + " → " + className);
			return signature;
		}

		if (DEBUG) System.out.println("[Parser3DocumentationProvider] Уточнён тип для ^"
				+ signature.variableName + "." + signature.name + " → " + className);
		return new CallSignature(className, signature.separator, signature.name, signature.type, signature.variableName);
	}

	private static @Nullable Parser3BuiltinMethods.BuiltinCallable findBuiltinCallable(@NotNull CallSignature signature) {
		List<Parser3BuiltinMethods.CaretConstructor> meta;

		if ("::".equals(signature.separator)) {
			meta = Parser3BuiltinMethods.getAllConstructorsMeta();
		} else if (":".equals(signature.separator)) {
			meta = Parser3BuiltinMethods.getAllStaticMethodsMeta();
		} else if (".".equals(signature.separator)) {
			meta = Parser3BuiltinMethods.getAllMethodsMeta();
		} else if ("system_method".equals(signature.type)) {
			meta = Parser3BuiltinMethods.getAllSystemMethodsMeta();
		} else {
			return null;
		}

		// Для методов объектов (.method) используем fallback, т.к. тип объекта неизвестен
		// Для конструкторов (::) и статических методов (:) — только точное совпадение класса
		boolean useFallback = ".".equals(signature.separator);
		Parser3BuiltinMethods.BuiltinCallable fallback = null;

		for (Parser3BuiltinMethods.CaretConstructor item : meta) {
			Parser3BuiltinMethods.BuiltinCallable callable = item.callable;
			if (!signature.name.equals(callable.name)) {
				continue;
			}
			// Для методов объектов без класса — возвращаем первый найденный
			if (".".equals(signature.separator) && (signature.className == null || signature.className.isEmpty())) {
				return callable;
			}
			// Точное совпадение класса
			if (signature.className.equals(item.className)) {
				return callable;
			}
			// Запоминаем fallback только для методов объектов
			if (useFallback && fallback == null) {
				fallback = callable;
			}
		}

		return fallback;
	}

	private static final class CallSignature {

		private final @NotNull String className;
		private final @NotNull String separator;
		private final @NotNull String name;
		private final @NotNull String type;
		private final @Nullable String variableName;  // Имя переменной для ^var.method[]

		private CallSignature(@NotNull String className, @NotNull String separator, @NotNull String name, @NotNull String type) {
			this(className, separator, name, type, null);
		}

		private CallSignature(@NotNull String className, @NotNull String separator, @NotNull String name, @NotNull String type, @Nullable String variableName) {
			this.className = className;
			this.separator = separator;
			this.name = name;
			this.type = type;
			this.variableName = variableName;
		}
	}

	// === Методы для тестирования ===

	/**
	 * Результат парсинга сигнатуры вызова для тестов.
	 */
	public static final class SignatureInfo {
		public final String className;
		public final String separator;
		public final String methodName;
		public final String type;

		SignatureInfo(String className, String separator, String methodName, String type) {
			this.className = className;
			this.separator = separator;
			this.methodName = methodName;
			this.type = type;
		}
	}

	/**
	 * Парсит сигнатуру вызова из строки по указанному offset.
	 * Публичный метод для тестирования.
	 *
	 * @param lineText текст строки
	 * @param localOffset позиция курсора в строке
	 * @return информация о сигнатуре или null
	 */
	public static SignatureInfo parseSignatureFromLine(String lineText, int localOffset) {
		// Проверяем системные методы: ^if, ^eval, ^return
		Matcher systemMatcher = SYSTEM_PATTERN.matcher(lineText);
		while (systemMatcher.find()) {
			String name = systemMatcher.group(1);
			if (name == null) continue;
			int nameStart = systemMatcher.start(1);
			int nameEnd = systemMatcher.end(1);
			if (!hasBracketAfter(lineText, nameEnd)) continue;
			if (localOffset >= nameStart && localOffset <= nameEnd) {
				return new SignatureInfo("", "", name, "system_method");
			}
		}

		// Проверяем конструкторы и статические методы: ^hash::create, ^file:list
		Matcher ctorMatcher = CTOR_PATTERN.matcher(lineText);
		while (ctorMatcher.find()) {
			String className = ctorMatcher.group(1);
			String separator = ctorMatcher.group(2);
			String name = ctorMatcher.group(3);
			if (className == null || name == null || separator == null) continue;
			int classStart = ctorMatcher.start(1);
			int nameEnd = ctorMatcher.end(3);
			if (!hasBracketAfter(lineText, nameEnd)) continue;
			if (localOffset >= classStart && localOffset <= nameEnd) {
				return new SignatureInfo(className, separator, name, "constructor_or_static");
			}
		}

		// Проверяем методы объектов: .count[], .foreach[]
		Matcher dotMatcher = DOT_CALL_PATTERN.matcher(lineText);
		while (dotMatcher.find()) {
			String name = dotMatcher.group(1);
			if (name == null) continue;
			int nameStart = dotMatcher.start(1);
			int nameEnd = dotMatcher.end(1);
			if (!hasBracketAfter(lineText, nameEnd)) continue;
			if (localOffset >= nameStart && localOffset <= nameEnd) {
				return new SignatureInfo("", ".", name, "method");
			}
		}

		return null;
	}
}
