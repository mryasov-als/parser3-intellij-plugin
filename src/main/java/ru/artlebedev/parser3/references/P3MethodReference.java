package ru.artlebedev.parser3.references;

import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.index.P3MethodIndex;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PSI reference для вызовов методов Parser3.
 * Поддерживает navigation с popup при множественных объявлениях.
 */
public final class P3MethodReference extends PsiReferenceBase<PsiElement> implements PsiPolyVariantReference {

	private final String methodName;

	public P3MethodReference(@NotNull PsiElement element, @NotNull TextRange range, @NotNull String methodName) {
		super(element, range);
		this.methodName = methodName;
	}

	@Override
	public @NotNull ResolveResult[] multiResolve(boolean incompleteCode) {
		PsiElement element = getElement();
		PsiFile file = element.getContainingFile();

		if (file == null || file.getVirtualFile() == null) {
			return ResolveResult.EMPTY_ARRAY;
		}

		P3ScopeContext scopeContext = new P3ScopeContext(element.getProject(), file.getVirtualFile(), element.getTextOffset());
		List<VirtualFile> visibleFiles = scopeContext.getMethodSearchFiles();

		// Находим объявления в видимых файлах
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(element.getProject());
		List<P3MethodDeclaration> allDeclarations = methodIndex.findInFiles(methodName, visibleFiles);

		if (allDeclarations.isEmpty()) {
			return ResolveResult.EMPTY_ARRAY;
		}

		// Если только одно объявление
		if (allDeclarations.size() == 1) {
			P3MethodDeclaration decl = allDeclarations.get(0);
			PsiElement navTarget = createNavigatableTarget(decl, element.getProject());
			if (navTarget != null) {
				return new ResolveResult[] { new PsiElementResolveResult(navTarget, true) };
			}
			return ResolveResult.EMPTY_ARRAY;
		}

		// Несколько объявлений - создаём popup
		Collections.reverse(allDeclarations);

		List<ResolveResult> results = new ArrayList<>();

		for (P3MethodDeclaration decl : allDeclarations) {
			PsiElement navTarget = createNavigatableTarget(decl, element.getProject());
			if (navTarget != null) {
				results.add(new PsiElementResolveResult(navTarget, true));
			}
		}

		return results.toArray(ResolveResult.EMPTY_ARRAY);
	}

	/**
	 * Создаёт navigatable обёртку для объявления.
	 */
	private @Nullable PsiElement createNavigatableTarget(
			@NotNull P3MethodDeclaration decl,
			@NotNull com.intellij.openapi.project.Project project
	) {
		PsiManager psiManager = PsiManager.getInstance(project);
		PsiFile declFile = psiManager.findFile(decl.getFile());

		if (declFile == null) {
			return null;
		}

		PsiElement target = declFile.findElementAt(decl.getOffset());
		if (target == null) {
			return null;
		}

		// Простая версия - без форматирования путей
		return new FakePsiElement() {
			@Override
			public void navigate(boolean requestFocus) {
				com.intellij.openapi.fileEditor.OpenFileDescriptor descriptor =
						new com.intellij.openapi.fileEditor.OpenFileDescriptor(
								project,
								decl.getFile(),
								decl.getOffset()
						);
				descriptor.navigate(requestFocus);
			}

			@Override
			public boolean canNavigate() {
				return true;
			}

			@Override
			public boolean canNavigateToSource() {
				return true;
			}

			@Override
			public PsiElement getParent() {
				return declFile;
			}

			@Override
			public PsiFile getContainingFile() {
				return declFile;
			}

			@Override
			public String getText() {
				return target.getText();
			}

			@Override
			public int getTextOffset() {
				return decl.getOffset();
			}

			@Override
			public TextRange getTextRange() {
				return new TextRange(decl.getOffset(), decl.getOffset() + target.getTextLength());
			}

			@Override
			public String toString() {
				return "P3MethodTarget(" + methodName + " @ " + decl.getFile().getName() + ")";
			}

			@Override
			public String getName() {
				return methodName + " (" + decl.getFile().getName() + ")";
			}

			@Override
			public ItemPresentation getPresentation() {
				return new ItemPresentation() {
					@Override
					public String getPresentableText() {
						return methodName;
					}

					@Override
					public String getLocationString() {
						return decl.getFile().getName();
					}

					@Override
					public Icon getIcon(boolean unused) {
						return Parser3Icons.File;
					}
				};
			}
		};
	}

	@Override
	public @Nullable PsiElement resolve() {
		ResolveResult[] results = multiResolve(false);
		return results.length > 0 ? results[0].getElement() : null;
	}

	@Override
	public @NotNull Object[] getVariants() {
		return new Object[0];
	}
}
