package ru.artlebedev.parser3.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.references.P3MethodReference;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.List;

/**
 * PSI элемент для вызова метода Parser3 (^methodName[]).
 */
public class P3MethodCallElement extends ASTWrapperPsiElement {

	public P3MethodCallElement(@NotNull ASTNode node) {
		super(node);
	}

	@Override
	public PsiReference @NotNull [] getReferences() {
		// Используем единую функцию парсинга из P3PsiExtractor
		P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(this);

		if (!callInfo.isValid()) {
			return PsiReference.EMPTY_ARRAY;
		}

		// Вызов метода объекта (^var.method[]) — навигация через GotoDeclarationHandler
		// который учитывает тип переменной. Reference не нужен.
		if (callInfo.isObjectMethodCall()) {
			return PsiReference.EMPTY_ARRAY;
		}

		String methodName = callInfo.getMethodName();
		if (methodName == null || methodName.isEmpty()) {
			return PsiReference.EMPTY_ARRAY;
		}

		// Если это вызов метода класса - проверяем существует ли класс
		if (callInfo.isClassMethod() && callInfo.getClassName() != null) {
			if (getContainingFile() == null || getContainingFile().getVirtualFile() == null) {
				return PsiReference.EMPTY_ARRAY;
			}
			P3ClassIndex classIndex = P3ClassIndex.getInstance(getProject());
			List<VirtualFile> classFiles = new P3ScopeContext(
					getProject(),
					getContainingFile().getVirtualFile(),
					getTextOffset()).getClassSearchFiles();

			P3ClassDeclaration classDecl = classIndex.findLastInFiles(callInfo.getClassName(), classFiles);

			if (classDecl == null) {
				// Класс не существует - не создаём reference
				return PsiReference.EMPTY_ARRAY;
			}
		}

		TextRange range = TextRange.from(0, getTextLength());
		P3MethodReference reference = new P3MethodReference(this, range, methodName);

		return new PsiReference[] { reference };
	}

	@Override
	public PsiReference getReference() {
		PsiReference[] refs = getReferences();
		return refs.length > 0 ? refs[0] : null;
	}
}
