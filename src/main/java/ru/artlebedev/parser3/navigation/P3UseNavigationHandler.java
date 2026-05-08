package ru.artlebedev.parser3.navigation;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.classpath.P3ClassPathEvaluator;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.index.P3MethodCallIndex;
import ru.artlebedev.parser3.index.P3MethodIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.use.P3UseResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Навигация по USE-путям, @BASE/@CLASS директивам и CLASS_PATH.
 * Вынесено из P3GotoDeclarationHandler для уменьшения размера файла.
 *
 * Все методы package-private — используются только из P3GotoDeclarationHandler.
 */
final class P3UseNavigationHandler {

	private P3UseNavigationHandler() {}

	// ========== USE-навигация ==========

	/**
	 * Обрабатывает навигацию для ^use[path] — STRING токен.
	 */
	static PsiElement @Nullable [] handleUseNavigation(@NotNull PsiElement sourceElement) {
		String path = sourceElement.getText();

		// Убираем кавычки если есть
		if (path.length() >= 2) {
			char first = path.charAt(0);
			char last = path.charAt(path.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				path = path.substring(1, path.length() - 1);
			}
		}

		// Проверяем что это действительно внутри use директивы
		PsiElement prev = sourceElement.getPrevSibling();
		boolean isInUse = false;

		// Ищем ^use или [ перед строкой
		while (prev != null) {
			IElementType prevType = prev.getNode() != null ? prev.getNode().getElementType() : null;

			if (prevType == Parser3TokenTypes.KW_USE) {
				isInUse = true;
				break;
			}
			if (prevType == Parser3TokenTypes.LBRACKET) {
				PsiElement beforeBracket = prev.getPrevSibling();
				if (beforeBracket != null && beforeBracket.getNode() != null
						&& beforeBracket.getNode().getElementType() == Parser3TokenTypes.KW_USE) {
					isInUse = true;
				}
				break;
			}
			if (prevType == Parser3TokenTypes.WHITE_SPACE) {
				prev = prev.getPrevSibling();
				continue;
			}
			break;
		}

		// Также проверяем @USE (SPECIAL_METHOD)
		if (!isInUse) {
			prev = sourceElement.getPrevSibling();
			while (prev != null) {
				if (prev.getNode() != null && prev.getNode().getElementType() == Parser3TokenTypes.SPECIAL_METHOD) {
					if (prev.getText().startsWith("@USE")) {
						isInUse = true;
						break;
					}
				}
				if (prev.getNode() != null && prev.getNode().getElementType() == Parser3TokenTypes.WHITE_SPACE) {
					prev = prev.getPrevSibling();
					continue;
				}
				break;
			}
		}

		if (!isInUse) {
			return null;
		}

		return resolveUsePath(sourceElement, path);
	}

	/**
	 * Обработка USE_PATH токена — путь уже известен.
	 */
	static PsiElement @Nullable [] handleUsePathNavigation(@NotNull PsiElement sourceElement) {
		String path = sourceElement.getText();
		if (path == null || path.isEmpty()) {
			return null;
		}

		// Сначала проверяем CLASS_PATH контекст — там пути к директориям
		PsiFile containingFile = sourceElement.getContainingFile();
		if (containingFile != null) {
			String fullText = containingFile.getText();
			if (isInClassPathContext(sourceElement, fullText)) {
				return handleClassPathDirectoryNavigation(sourceElement);
			}
		}

		return resolveUsePath(sourceElement, path.trim());
	}

	/**
	 * Обрабатывает навигацию для @USE path (без скобок, путь как TEMPLATE_DATA).
	 */
	static PsiElement @Nullable [] handleAtUseNavigation(@NotNull PsiElement sourceElement) {
		// Собираем полный путь из соседних токенов на текущей строке
		StringBuilder pathBuilder = new StringBuilder();

		PsiElement start = sourceElement;
		PsiElement prev = sourceElement.getPrevSibling();
		while (prev != null) {
			IElementType prevType = prev.getNode() != null ? prev.getNode().getElementType() : null;
			String prevText = prev.getText();

			if (prevText != null && prevText.contains("\n")) {
				break;
			}

			if (prevType == Parser3TokenTypes.TEMPLATE_DATA || prevType == Parser3TokenTypes.DOT) {
				start = prev;
				prev = prev.getPrevSibling();
			} else {
				break;
			}
		}

		// Собираем путь от start вперёд до конца строки
		PsiElement current = start;
		while (current != null) {
			IElementType currType = current.getNode() != null ? current.getNode().getElementType() : null;
			String currText = current.getText();

			if (currText != null && currText.contains("\n")) {
				break;
			}

			if (currType == Parser3TokenTypes.TEMPLATE_DATA || currType == Parser3TokenTypes.DOT) {
				pathBuilder.append(currText);
				current = current.getNextSibling();
			} else {
				break;
			}
		}

		String path = pathBuilder.toString().trim();

		// Проверяем что где-то выше есть @USE
		prev = start.getPrevSibling();
		boolean isAfterAtUse = false;

		while (prev != null) {
			IElementType prevType = prev.getNode() != null ? prev.getNode().getElementType() : null;

			if (prevType == Parser3TokenTypes.SPECIAL_METHOD) {
				String prevText = prev.getText();
				if (prevText != null && prevText.equals("@USE")) {
					isAfterAtUse = true;
				}
				break;
			}

			if (prevType == Parser3TokenTypes.DEFINE_METHOD) {
				break;
			}

			if (prevType == Parser3TokenTypes.WHITE_SPACE ||
					prevType == Parser3TokenTypes.TEMPLATE_DATA ||
					prevType == Parser3TokenTypes.DOT ||
					prevType == Parser3TokenTypes.STRING) {
				prev = prev.getPrevSibling();
				continue;
			}

			break;
		}

		if (!isAfterAtUse) {
			return null;
		}

		// Резолвим путь
		PsiFile currentFile = sourceElement.getContainingFile();
		if (currentFile == null || currentFile.getVirtualFile() == null) {
			return null;
		}

		VirtualFile currentVFile = currentFile.getVirtualFile();
		VirtualFile parentDir = currentVFile.getParent();

		if (parentDir == null) {
			return null;
		}

		VirtualFile targetFile;
		Project project = sourceElement.getProject();

		if (path.startsWith("/")) {
			VirtualFile docRoot = getDocumentRoot(project, parentDir);
			String relativePath = path.substring(1);
			targetFile = docRoot.findFileByRelativePath(relativePath);
		} else {
			targetFile = parentDir.findFileByRelativePath(path);

			if (targetFile == null) {
				P3ClassPathEvaluator evaluator = new P3ClassPathEvaluator(project);
				targetFile = evaluator.resolveInClassPath(path, currentVFile);
			}
		}

		if (targetFile == null) {
			return null;
		}

		PsiFile targetPsiFile = PsiManager.getInstance(project).findFile(targetFile);
		return targetPsiFile != null ? new PsiElement[]{targetPsiFile} : null;
	}

	/**
	 * Обрабатывает навигацию для @USE path где путь - STRING токен.
	 */
	static PsiElement @Nullable [] handleAtUseNavigationForString(@NotNull PsiElement sourceElement) {
		String path = sourceElement.getText();

		// Убираем кавычки если есть
		if (path.length() >= 2) {
			char first = path.charAt(0);
			char last = path.charAt(path.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				path = path.substring(1, path.length() - 1);
			}
		}

		path = path.trim();
		if (path.isEmpty()) {
			return null;
		}

		PsiFile currentFile = sourceElement.getContainingFile();
		if (currentFile == null || currentFile.getVirtualFile() == null) {
			return null;
		}

		VirtualFile currentVFile = currentFile.getVirtualFile();
		VirtualFile parentDir = currentVFile.getParent();
		if (parentDir == null) {
			return null;
		}

		Project project = sourceElement.getProject();
		P3ClassPathEvaluator evaluator = new P3ClassPathEvaluator(project);
		VirtualFile targetFile = null;

		if (path.startsWith("/")) {
			String pathWithoutSlash = path.substring(1);

			for (VirtualFile classPathDir : evaluator.getClassPathDirs(currentVFile)) {
				targetFile = classPathDir.findFileByRelativePath(pathWithoutSlash);
				if (targetFile != null && targetFile.exists()) {
					break;
				}
			}

			if (targetFile == null) {
				VirtualFile documentRoot = getDocumentRoot(project, parentDir);
				targetFile = documentRoot.findFileByRelativePath(pathWithoutSlash);
			}
		} else {
			targetFile = parentDir.findFileByRelativePath(path);

			if (targetFile == null) {
				targetFile = evaluator.resolveInClassPath(path, currentVFile);
			}
		}

		if (targetFile == null || !targetFile.exists()) {
			return null;
		}

		PsiFile targetPsiFile = PsiManager.getInstance(project).findFile(targetFile);
		return targetPsiFile != null ? new PsiElement[]{targetPsiFile} : null;
	}

	/**
	 * Проверяет находится ли элемент в контексте @USE.
	 */
	static boolean isInAtUseContext(@NotNull PsiElement element) {
		PsiElement prev = element.getPrevSibling();

		while (prev != null) {
			IElementType prevType = prev.getNode() != null ? prev.getNode().getElementType() : null;

			if (prevType == Parser3TokenTypes.SPECIAL_METHOD) {
				String prevText = prev.getText();
				return prevText != null && prevText.equals("@USE");
			}

			if (prevType == Parser3TokenTypes.DEFINE_METHOD) {
				return false;
			}

			prev = prev.getPrevSibling();
		}

		return false;
	}

	// ========== @BASE/@CLASS навигация ==========

	/**
	 * Проверяет находится ли VARIABLE после @BASE директивы.
	 */
	static boolean isAfterBaseDirective(@NotNull PsiElement element) {
		PsiElement prev = element.getPrevSibling();

		while (prev != null) {
			IElementType prevType = prev.getNode() != null ? prev.getNode().getElementType() : null;
			String typeName = prevType != null ? prevType.toString() : "null";

			if (prevType == Parser3TokenTypes.SPECIAL_METHOD) {
				String prevText = prev.getText();
				return prevText != null && prevText.equals("@BASE");
			}

			if (typeName.equals("WHITE_SPACE")) {
				prev = prev.getPrevSibling();
				continue;
			}

			return false;
		}

		return false;
	}

	/**
	 * Проверяет находится ли VARIABLE после @CLASS директивы.
	 */
	static boolean isAfterClassDirective(@NotNull PsiElement element) {
		PsiElement prev = element.getPrevSibling();

		while (prev != null) {
			IElementType prevType = prev.getNode() != null ? prev.getNode().getElementType() : null;
			String typeName = prevType != null ? prevType.toString() : "null";

			if (prevType == Parser3TokenTypes.SPECIAL_METHOD) {
				String prevText = prev.getText();
				return prevText != null && prevText.equals("@CLASS");
			}

			if (typeName.equals("WHITE_SPACE")) {
				prev = prev.getPrevSibling();
				continue;
			}

			return false;
		}

		return false;
	}

	/**
	 * Обрабатывает навигацию к определению класса из @BASE директивы.
	 */
	static PsiElement @Nullable [] handleBaseClassNavigation(
			@NotNull PsiElement sourceElement,
			@NotNull List<VirtualFile> visibleFiles
	) {
		String className = sourceElement.getText().trim();
		if (className.isEmpty()) {
			return null;
		}

		Project project = sourceElement.getProject();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		List<P3ClassDeclaration> allClasses = classIndex.findByName(className);

		List<P3ClassDeclaration> visibleClasses = new ArrayList<>();
		for (P3ClassDeclaration classDecl : allClasses) {
			if (visibleFiles.contains(classDecl.getFile())) {
				visibleClasses.add(classDecl);
			}
		}

		if (visibleClasses.isEmpty()) {
			return PsiElement.EMPTY_ARRAY;
		}

		List<PsiElement> targets = new ArrayList<>();
		for (P3ClassDeclaration classDecl : visibleClasses) {
			targets.add(new P3NavigationTargets.ClassTarget(sourceElement, classDecl));
		}

		return targets.isEmpty() ? PsiElement.EMPTY_ARRAY : targets.toArray(new PsiElement[0]);
	}

	/**
	 * Обрабатывает навигацию по клику на имени класса в @CLASS директиве.
	 * Показывает наследников и использования класса.
	 */
	static PsiElement @Nullable [] handleClassDeclarationNavigation(
			@NotNull PsiElement sourceElement,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull P3GotoDeclarationHandler handler
	) {
		String className = sourceElement.getText().trim();
		if (className.isEmpty()) {
			return null;
		}

		Project project = sourceElement.getProject();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		P3MethodCallIndex callIndex = P3MethodCallIndex.getInstance(project);
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		PsiManager psiManager = PsiManager.getInstance(project);

		List<PsiElement> targets = new ArrayList<>();

		// 1. Классы-наследники
		List<P3ClassDeclaration> childClasses = classIndex.findChildClasses(className, visibleFiles);
		for (P3ClassDeclaration childClass : childClasses) {
			targets.add(new P3NavigationTargets.ChildClassTarget(sourceElement, childClass, className));
		}

		// 2. Вызовы класса
		P3ClassDeclaration targetClass = classIndex.findLastInFiles(className, visibleFiles);
		List<P3MethodCallIndex.ClassUsageResult> usages = new ArrayList<>(callIndex.findClassUsages(className, visibleFiles));

		// 3. ^BASE: и ^self. вызовы в наследниках
		for (P3ClassDeclaration childClass : childClasses) {
			usages.addAll(callIndex.findBaseCallsInClass(childClass.getName(), visibleFiles));

			List<P3MethodCallIndex.ClassUsageResult> selfCalls =
					callIndex.findSelfCallsInClass(childClass.getName(), visibleFiles);
			for (P3MethodCallIndex.ClassUsageResult selfCall : selfCalls) {
				if (handler.isMethodDefinedInClass(selfCall.methodName, className, classIndex, methodIndex, visibleFiles) &&
						!handler.isMethodDefinedInClass(selfCall.methodName, childClass.getName(), classIndex, methodIndex, visibleFiles)) {
					usages.add(selfCall);
				}
			}
		}

		List<PsiElement> commentTargets = new ArrayList<>();

		for (P3MethodCallIndex.ClassUsageResult usage : usages) {
			if (targetClass != null) {
				List<P3MethodDeclaration> methods = methodIndex.findInFiles(usage.methodName, visibleFiles);
				boolean methodExists = false;
				for (P3MethodDeclaration method : methods) {
					if (handler.isMethodInClassHierarchy(method, targetClass, classIndex, visibleFiles)) {
						methodExists = true;
						break;
					}
				}
				if (!methodExists) {
					continue;
				}
			}

			PsiFile callFile = psiManager.findFile(usage.file);
			if (callFile != null) {
				PsiElement target = callFile.findElementAt(usage.callInfo.offset);
				if (target != null) {
					P3NavigationTargets.ClassUsageTarget navTarget = new P3NavigationTargets.ClassUsageTarget(target, usage);
					if (usage.callInfo.isInComment) {
						commentTargets.add(navTarget);
					} else {
						targets.add(navTarget);
					}
				}
			}
		}

		targets.addAll(commentTargets);

		return targets.isEmpty() ? PsiElement.EMPTY_ARRAY : targets.toArray(new PsiElement[0]);
	}

	// ========== CLASS_PATH навигация ==========

	/**
	 * Обрабатывает навигацию к директории CLASS_PATH.
	 */
	static PsiElement @Nullable [] handleClassPathDirectoryNavigation(@NotNull PsiElement sourceElement) {
		String text = sourceElement.getText();
		if (text == null || text.isEmpty()) {
			return null;
		}

		String path = text.trim();
		if (path.isEmpty()) {
			return null;
		}

		PsiFile containingFile = sourceElement.getContainingFile();
		if (containingFile == null) {
			return null;
		}

		String fullText = containingFile.getText();
		if (!isInClassPathContext(sourceElement, fullText)) {
			return null;
		}

		Project project = sourceElement.getProject();
		VirtualFile contextFile = containingFile.getVirtualFile();
		if (contextFile == null) {
			return null;
		}

		VirtualFile targetDir = resolveClassPathDirectory(project, contextFile, path);
		if (targetDir == null || !targetDir.isDirectory()) {
			return null;
		}

		PsiManager psiManager = PsiManager.getInstance(project);
		com.intellij.psi.PsiDirectory psiDir = psiManager.findDirectory(targetDir);
		return psiDir != null ? new PsiElement[]{psiDir} : null;
	}

	/**
	 * Проверяет находится ли элемент внутри контекста CLASS_PATH.
	 */
	static boolean isInClassPathContext(@NotNull PsiElement element, @NotNull String fullText) {
		int offset = element.getTextOffset();
		if (offset <= 0 || offset >= fullText.length()) {
			return false;
		}

		int searchStart = Math.max(0, offset - 200);
		String before = fullText.substring(searchStart, offset);

		int assignIdx = Math.max(
				before.lastIndexOf("$CLASS_PATH["),
				before.lastIndexOf("$MAIN:CLASS_PATH[")
		);

		int appendIdx = Math.max(
				before.lastIndexOf("^CLASS_PATH.append{"),
				before.lastIndexOf("^MAIN:CLASS_PATH.append{")
		);

		int contextStart = Math.max(assignIdx, appendIdx);
		if (contextStart == -1) {
			return false;
		}

		String afterContext = before.substring(contextStart);

		if (assignIdx > appendIdx) {
			int bracketCount = 0;
			boolean foundOpen = false;
			for (char c : afterContext.toCharArray()) {
				if (c == '[') { bracketCount++; foundOpen = true; }
				else if (c == ']') { bracketCount--; }
			}
			return foundOpen && bracketCount > 0;
		} else {
			int braceCount = 0;
			boolean foundOpen = false;
			for (char c : afterContext.toCharArray()) {
				if (c == '{') { braceCount++; foundOpen = true; }
				else if (c == '}') { braceCount--; }
			}
			return foundOpen && braceCount > 0;
		}
	}

	// ========== Вспомогательные методы ==========

	/**
	 * Резолвит путь к файлу через P3UseResolver.
	 */
	private static PsiElement @Nullable [] resolveUsePath(@NotNull PsiElement sourceElement, @NotNull String path) {
		PsiFile currentFile = sourceElement.getContainingFile();
		if (currentFile == null || currentFile.getVirtualFile() == null) {
			return null;
		}

		Project project = sourceElement.getProject();
		P3UseResolver useResolver = P3UseResolver.getInstance(project);
		VirtualFile targetFile = useResolver.resolve(path, currentFile.getVirtualFile());

		if (targetFile == null) {
			return null;
		}

		PsiFile targetPsiFile = PsiManager.getInstance(project).findFile(targetFile);
		return targetPsiFile != null ? new PsiElement[]{targetPsiFile} : null;
	}

	/**
	 * Получает document_root из настроек или fallback.
	 */
	private static VirtualFile getDocumentRoot(Project project, VirtualFile fallback) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile docRoot = settings.getDocumentRootWithFallback();

		if (docRoot != null && docRoot.isDirectory()) {
			return docRoot;
		}

		return fallback;
	}

	/**
	 * Резолвит путь CLASS_PATH в директорию.
	 */
	private static @Nullable VirtualFile resolveClassPathDirectory(
			@NotNull Project project,
			@NotNull VirtualFile contextFile,
			@NotNull String path
	) {
		path = path.trim();
		if (path.isEmpty()) {
			return null;
		}

		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);

		if (path.startsWith("/")) {
			VirtualFile documentRoot = settings.getDocumentRootWithFallback();
			if (documentRoot == null || !documentRoot.isValid()) {
				return null;
			}
			return documentRoot.findFileByRelativePath(path.substring(1));
		} else {
			VirtualFile contextDir = contextFile.getParent();
			if (contextDir == null) {
				return null;
			}
			return contextDir.findFileByRelativePath(path);
		}
	}
}
