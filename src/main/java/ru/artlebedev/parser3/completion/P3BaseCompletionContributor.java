package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Автокомплит имён классов после директивы @BASE.
 *
 * @BASE
 * <курсор> — показываем список классов (все или только подключённые через use)
 */
public class P3BaseCompletionContributor extends CompletionContributor {

	public P3BaseCompletionContributor() {
		// Для VARIABLE токенов (имя класса после @BASE)
		extend(
				CompletionType.BASIC,
				PlatformPatterns.psiElement(Parser3TokenTypes.VARIABLE)
						.withLanguage(Parser3Language.INSTANCE),
				new BaseClassCompletionProvider()
		);

		// Для WHITE_SPACE после @BASE (когда ещё ничего не набрано)
		extend(
				CompletionType.BASIC,
				PlatformPatterns.psiElement()
						.withLanguage(Parser3Language.INSTANCE),
				new BaseClassCompletionProvider()
		);
	}

	private static class BaseClassCompletionProvider extends CompletionProvider<CompletionParameters> {
		@Override
		protected void addCompletions(
				@NotNull CompletionParameters parameters,
				@NotNull ProcessingContext context,
				@NotNull CompletionResultSet result) {

			PsiElement position = parameters.getPosition();

			// Проверяем что мы после @BASE
			if (!isAfterBaseDirective(position)) {
				return;
			}

			// Регистронезависимый автокомплит для Parser3
			result = P3CompletionUtils.makeCaseInsensitive(result);

			Project project = position.getProject();
			PsiFile currentFile = parameters.getOriginalFile();
			VirtualFile currentVFile = currentFile.getVirtualFile();

			if (currentVFile == null) {
				return;
			}

			Set<String> addedNames = new HashSet<>();
			P3ScopeContext scopeContext = new P3ScopeContext(project, currentVFile, parameters.getOffset());
			if (!scopeContext.isAllMethodsMode() && !scopeContext.hasAutouse()) {
				// Только классы из видимых файлов (подключённых через use)
				List<VirtualFile> visibleFiles = scopeContext.getClassSearchFiles();
				P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

				for (VirtualFile file : visibleFiles) {
					List<P3ClassDeclaration> fileClasses = classIndex.findInFile(file);
					for (P3ClassDeclaration classDecl : fileClasses) {
						String className = classDecl.getName();

						// Пропускаем MAIN класс
						if ("MAIN".equals(className)) {
							continue;
						}

						if (addedNames.add(className)) {
							result.addElement(
									LookupElementBuilder.create(className)
											.withIcon(Parser3Icons.FileClass)
											.withTypeText(getFileNameWithoutExtension(classDecl.getFile()), true)
							);
						}
					}
				}
			} else {
				// Все классы из проекта
				P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
				List<P3ClassDeclaration> allClasses = classIndex.getAllClasses();

				for (P3ClassDeclaration classDecl : allClasses) {
					String className = classDecl.getName();

					// Пропускаем MAIN класс
					if ("MAIN".equals(className)) {
						continue;
					}

					if (addedNames.add(className)) {
						result.addElement(
								LookupElementBuilder.create(className)
										.withIcon(Parser3Icons.FileClass)
										.withTypeText(getFileNameWithoutExtension(classDecl.getFile()), true)
						);
					}
				}
			}
		}

		/**
		 * Проверяет находится ли позиция после @BASE директивы.
		 */
		private boolean isAfterBaseDirective(@NotNull PsiElement element) {
			// Проверяем сам элемент — если это SPECIAL_METHOD (начинается с @),
			// то это уже другая директива, не контекст @BASE
			IElementType elementType = element.getNode() != null ? element.getNode().getElementType() : null;
			if (elementType == Parser3TokenTypes.SPECIAL_METHOD) {
				return false;
			}

			// Также проверяем текст — если начинается с @, это новая директива
			String elementText = element.getText();
			if (elementText != null && elementText.startsWith("@")) {
				return false;
			}

			PsiElement prev = element.getPrevSibling();

			// Если element это IntellijIdeaRulezzz (dummy identifier),
			// нужно смотреть на parent и его соседей
			if (prev == null) {
				PsiElement parent = element.getParent();
				if (parent != null) {
					// Проверяем parent тоже
					IElementType parentType = parent.getNode() != null ? parent.getNode().getElementType() : null;
					if (parentType == Parser3TokenTypes.SPECIAL_METHOD) {
						return false;
					}
					String parentText = parent.getText();
					if (parentText != null && parentText.startsWith("@")) {
						return false;
					}
					prev = parent.getPrevSibling();
				}
			}

			while (prev != null) {
				IElementType prevType = prev.getNode() != null ? prev.getNode().getElementType() : null;
				String typeName = prevType != null ? prevType.toString() : "null";

				if (prevType == Parser3TokenTypes.SPECIAL_METHOD) {
					String prevText = prev.getText();
					return prevText != null && prevText.equals("@BASE");
				}

				// Пропускаем WHITE_SPACE и VARIABLE (частично введённое имя)
				if (typeName.equals("WHITE_SPACE") || prevType == Parser3TokenTypes.VARIABLE) {
					prev = prev.getPrevSibling();
					continue;
				}

				// Любой другой токен - не @BASE контекст
				return false;
			}

			return false;
		}

		private String getFileNameWithoutExtension(VirtualFile file) {
			if (file == null) {
				return "";
			}
			String name = file.getName();
			int dotIndex = name.lastIndexOf('.');
			return dotIndex > 0 ? name.substring(0, dotIndex) : name;
		}
	}
}
