package ru.artlebedev.parser3.references;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.utils.Parser3ClassUtils;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.List;

/**
 * Reference from Parser3 class/method call to class/method declaration.
 * Provides Ctrl+Click navigation and proper underlining.
 */
public class P3ClassReference extends PsiReferenceBase<PsiElement> {
	private final String className;
	private final String methodName; // null if reference is to class only

	public P3ClassReference(@NotNull PsiElement element, @NotNull TextRange rangeInElement, @NotNull String className, @Nullable String methodName) {
		super(element, rangeInElement);
		this.className = className;
		this.methodName = methodName;
	}

	@Override
	public @Nullable PsiElement resolve() {

		PsiFile file = getElement().getContainingFile();
		if (file == null) {
			return null;
		}

		VirtualFile virtualFile = file.getVirtualFile();
		if (virtualFile == null) {
			return null;
		}

		Project project = getElement().getProject();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		P3ScopeContext scopeContext = new P3ScopeContext(project, virtualFile, getElement().getTextOffset());
		List<VirtualFile> visibleFiles = scopeContext.getClassSearchFiles();

		// Find class
		P3ClassDeclaration classDecl = classIndex.findLastInFiles(className, visibleFiles);
		if (classDecl == null) {
			return null;
		}


		// If method name specified, find method
		if (methodName != null) {
			List<P3MethodDeclaration> methods = Parser3ClassUtils.getAllMethods(classDecl, visibleFiles, project);
			for (P3MethodDeclaration method : methods) {
				String cleanName = Parser3ClassUtils.getCleanMethodName(method);
				if (cleanName.equals(methodName)) {
					return method.getElement();
				}
			}
			// Method not found, return class
			return classDecl.getElement();
		}

		// Return class
		return classDecl.getElement();
	}

	@Override
	public Object @NotNull [] getVariants() {
		// For completion - not needed here as we have separate completion contributor
		return new Object[0];
	}
}
