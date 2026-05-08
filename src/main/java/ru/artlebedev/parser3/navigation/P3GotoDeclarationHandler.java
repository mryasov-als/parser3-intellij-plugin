package ru.artlebedev.parser3.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.index.P3MethodIndex;
import ru.artlebedev.parser3.index.P3MethodCallIndex;
import ru.artlebedev.parser3.model.P3ClassDeclaration;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.psi.P3PsiExtractor;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.artlebedev.parser3.Parser3TokenTypes;

/**
 * Handles Ctrl+Click navigation for Parser3 methods and classes.
 *
 * Scenarios:
 * - ^User::create[] - click on User or create → navigate to method create in User class
 * - ^User:method[] - same with single colon
 * - ^methodName[] - navigate to method declaration
 * - ^ClassName[] - navigate to class (if no method with this name found, treat as class)
 */
public final class P3GotoDeclarationHandler implements GotoDeclarationHandler {

	private static boolean DEBUG = false;

	@Override
	public PsiElement @Nullable [] getGotoDeclarationTargets(
			@Nullable PsiElement sourceElement,
			int offset,
			Editor editor
	) {

		if (sourceElement == null) {
			return null;
		}

		IElementType elementType = sourceElement.getNode() != null
				? sourceElement.getNode().getElementType()
				: null;


		// Проверяем что это релевантный токен
		if (elementType == null) {
			return null;
		}

		if (DEBUG) System.out.println("[GotoDecl] elementType=" + elementType + " text='" + sourceElement.getText() + "' offset=" + offset);

		// Обработка USE_PATH токенов (пути в @USE и ^use[])
		if (elementType == Parser3TokenTypes.USE_PATH) {
			return P3UseNavigationHandler.handleUsePathNavigation(sourceElement);
		}

		// Обработка STRING токенов внутри ^use[...], @USE или $CLASS_PATH[...]
		if (elementType == Parser3TokenTypes.STRING) {
			// Сначала проверяем CLASS_PATH контекст — там пути к директориям
			PsiFile containingFile = sourceElement.getContainingFile();
			if (containingFile != null) {
				String fullText = containingFile.getText();
				if (P3UseNavigationHandler.isInClassPathContext(sourceElement, fullText)) {
					return P3UseNavigationHandler.handleClassPathDirectoryNavigation(sourceElement);
				}
			}
			// Потом пробуем как ^use[path]
			PsiElement[] useResult = P3UseNavigationHandler.handleUseNavigation(sourceElement);
			if (useResult != null) {
				return useResult;
			}
			// Если не ^use[...], проверяем @USE контекст
			if (P3UseNavigationHandler.isInAtUseContext(sourceElement)) {
				return P3UseNavigationHandler.handleAtUseNavigationForString(sourceElement);
			}
			return null;
		}

		// Обработка TEMPLATE_DATA - может быть путь в @USE или директория в CLASS_PATH
		if (elementType == Parser3TokenTypes.TEMPLATE_DATA) {
			// Сначала проверяем CLASS_PATH контекст
			PsiElement[] classPathResult = P3UseNavigationHandler.handleClassPathDirectoryNavigation(sourceElement);
			if (classPathResult != null) {
				return classPathResult;
			}
			// Иначе пробуем как @USE путь
			return P3UseNavigationHandler.handleAtUseNavigation(sourceElement);
		}

		// Обработка VARIABLE после @BASE или @CLASS — навигация к классу
		if (elementType == Parser3TokenTypes.VARIABLE) {
			if (P3UseNavigationHandler.isAfterBaseDirective(sourceElement)) {
				PsiFile file2 = sourceElement.getContainingFile();
				if (file2 != null && file2.getVirtualFile() != null) {
					List<VirtualFile> classFiles = getVisibleFilesForClasses(
							sourceElement.getProject(), file2.getVirtualFile(), sourceElement.getTextOffset());
					return P3UseNavigationHandler.handleBaseClassNavigation(sourceElement, classFiles);
				}
				return null;
			}
			if (P3UseNavigationHandler.isAfterClassDirective(sourceElement)) {
				PsiFile file2 = sourceElement.getContainingFile();
				if (file2 != null && file2.getVirtualFile() != null) {
					List<VirtualFile> declFiles = getVisibleFilesForClassDeclaration(sourceElement.getProject(), file2.getVirtualFile());
					return P3UseNavigationHandler.handleClassDeclarationNavigation(sourceElement, declFiles, this);
				}
				return null;
			}
			// Клик на var после $ — навигация к определению переменной
			PsiElement[] dollarVarResult = handleDollarVariableNavigation(sourceElement);
			if (dollarVarResult != null) {
				return dollarVarResult;
			}
			PsiElement[] hashKeyResult = handleHashKeyNavigation(sourceElement);
			if (hashKeyResult != null) {
				return hashKeyResult;
			}
			return null;
		}

		// Обработка DOLLAR_VARIABLE ($) — навигация к определению переменной
		if (elementType == Parser3TokenTypes.DOLLAR_VARIABLE) {
			PsiElement[] dollarVarResult = handleDollarVariableNavigation(sourceElement);
			if (dollarVarResult != null) {
				return dollarVarResult;
			}
			return PsiElement.EMPTY_ARRAY;
		}

		// Обработка IMPORTANT_VARIABLE ($self., $MAIN:, $result) — навигация к определению переменной
		if (elementType == Parser3TokenTypes.IMPORTANT_VARIABLE) {
			PsiElement[] dollarVarResult = handleDollarVariableNavigation(sourceElement);
			if (dollarVarResult != null) {
				return dollarVarResult;
			}
			return PsiElement.EMPTY_ARRAY;
		}

		// Обработка DEFINE_METHOD (@method[]) — навигация к вызовам этого метода
		if (elementType == Parser3TokenTypes.DEFINE_METHOD) {
			return handleMethodDefinitionNavigation(sourceElement);
		}

		// Обработка SPECIAL_METHOD (@GET_xxx) — навигация к вызовам ^xxx[]
		if (elementType == Parser3TokenTypes.SPECIAL_METHOD) {
			String text = sourceElement.getText();
			if (text != null && text.startsWith("@GET_") && !text.startsWith("@GET_DEFAULT")) {
				return handleMethodDefinitionNavigation(sourceElement);
			}
		}

		// Клик на ^self, ^MAIN, ^BASE — не навигируем (навигация только по имени метода)
		if (elementType == Parser3TokenTypes.IMPORTANT_METHOD) {
			String text = sourceElement.getText();
			if (text != null && (text.equals("^self") || text.equals("^MAIN") || text.equals("^BASE"))) {
				return PsiElement.EMPTY_ARRAY;
			}
		}

		// Используем единую функцию парсинга вызова метода из P3PsiExtractor
		P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(sourceElement);


		if (!callInfo.isValid()) {
			// Проверяем: может это клик на ^var в ^var.method[] — навигация к определению переменной
			if (elementType == Parser3TokenTypes.METHOD) {
				PsiElement[] varResult = handleVariableNavigation(sourceElement);
				if (varResult != null) {
					return varResult;
				}
			}
			return PsiElement.EMPTY_ARRAY;
		}

		PsiFile file = sourceElement.getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return null;
		}

		Project project = sourceElement.getProject();

		// Получаем видимые файлы
		List<VirtualFile> visibleFiles = getVisibleFiles(project, file.getVirtualFile(), sourceElement.getTextOffset());

		// Вызов метода объекта (^var.method[]) — определяем тип переменной
		if (callInfo.isObjectMethodCall()) {
			// Сначала проверяем: может это hash key (^var.key.), а не метод класса
			// ^config.name. — name может быть ключом хеша, а не методом
			PsiElement[] hashKeyResult = handleHashKeyNavigation(sourceElement);
			if (hashKeyResult != null) return hashKeyResult;
			return handleObjectMethodCall(sourceElement, callInfo.getVariableName(),
					callInfo.getMethodName(), project, visibleFiles);
		}

		// Вызов метода класса (^ClassName::method[])
		if (callInfo.isClassMethod()) {
			// Для классов используем расширенный список (учитывает @autouse)
			List<VirtualFile> classVisibleFiles = getVisibleFilesForClasses(project, file.getVirtualFile(), sourceElement.getTextOffset());
			return handleClassMethodCall(sourceElement, callInfo.getClassName(),
					callInfo.getMethodName(), project, classVisibleFiles);
		}

		// Вызов через BASE: — ищем в базовом классе
		if (callInfo.isBaseCall()) {
			return handleBaseMethodCall(sourceElement, callInfo.getMethodName(), project, visibleFiles);
		}

		// Вызов через self: — ищем только в текущем классе и его @BASE цепочке
		if (callInfo.isSelfCall()) {
			return handleSelfMethodCall(sourceElement, callInfo.getMethodName(), project, visibleFiles);
		}

		// Вызов через MAIN: — ищем только в MAIN классах
		if (callInfo.isMainCall()) {
			return handleMainMethodCall(sourceElement, callInfo.getMethodName(), project, visibleFiles);
		}

		// Обычный метод (^method[])
		return handleSimpleCall(sourceElement, callInfo.getMethodName(), project, visibleFiles);
	}

	/**
	 * Обрабатывает вызов метода объекта: ^var.method[]
	 * Определяет тип переменной через P3VariableIndex и ищет метод в этом классе.
	 */
	private PsiElement @Nullable [] handleObjectMethodCall(
			@NotNull PsiElement sourceElement,
			@NotNull String variableName,
			@NotNull String methodName,
			@NotNull Project project,
			@NotNull List<VirtualFile> visibleFiles
	) {
		PsiFile file = sourceElement.getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return PsiElement.EMPTY_ARRAY;
		}

		// Определяем тип переменной — resolveChainedType поддерживает и простые имена, и цепочки
		// ($var = $self.var = $MAIN:var в MAIN без locals)
		ru.artlebedev.parser3.index.P3VariableIndex varTypeIndex =
				ru.artlebedev.parser3.index.P3VariableIndex.getInstance(project);
		ru.artlebedev.parser3.index.P3VariableIndex.ChainResolveInfo chainInfo =
				varTypeIndex.resolveEffectiveChain(
						variableName, visibleFiles, file.getVirtualFile(), sourceElement.getTextOffset()
				);
		String varClassName = chainInfo != null ? chainInfo.className : null;

		if (DEBUG) System.out.println("[handleObjectMethodCall] variableName=" + variableName
				+ " methodName=" + methodName + " varClassName=" + varClassName
				+ " offset=" + sourceElement.getTextOffset());

		if (varClassName == null || varClassName.isEmpty()) {
			return PsiElement.EMPTY_ARRAY;
		}

		// Ищем метод в классе переменной (и его @BASE цепочке)
		List<VirtualFile> classVisibleFiles = getVisibleFilesForClasses(project, file.getVirtualFile(), sourceElement.getTextOffset());
		return handleClassMethodCall(sourceElement, varClassName, methodName, project, classVisibleFiles);
	}

	/**
	 * Обрабатывает клик на переменную для навигации к определению $var[...].
	 *
	 * Поддерживаемые случаи:
	 * - ^var.method[] — клик на ^var → переход к $var[...]
	 * - ^MAIN:user.method[] — клик на user → переход к $user[...]
	 * - ^self.user.method[] — клик на user → переход к $user[...]
	 *
	 * Ищет определение через P3VariableIndex во всех видимых файлах.
	 */
	private PsiElement @Nullable [] handleVariableNavigation(@NotNull PsiElement sourceElement) {
		String text = sourceElement.getText();
		if (text == null) {
			return null;
		}

		PsiElement searchFrom = sourceElement;
		PsiElement parent = sourceElement.getParent();
		if (parent != null && sourceElement.getNextSibling() == null && sourceElement.getPrevSibling() == null) {
			searchFrom = parent;
		}

		// Проверяем что после токена стоит DOT (значит это var.xxx)
		PsiElement next = searchFrom.getNextSibling();
		if (next == null || next.getNode() == null
				|| next.getNode().getElementType() != Parser3TokenTypes.DOT) {
			return null;
		}

		String varName;

		if (text.startsWith("^")) {
			// ^var.method[] — клик на ^var
			varName = text.substring(1);
		} else {
			// user.method — проверяем что перед элементом стоит DOT + ^self или COLON + ^MAIN
			PsiElement prev = searchFrom.getPrevSibling();
			if (prev == null || prev.getNode() == null) {
				return null;
			}
			IElementType prevType = prev.getNode().getElementType();

			if (prevType == Parser3TokenTypes.DOT) {
				// .user.method — перед DOT должен быть ^self
				PsiElement beforeDot = prev.getPrevSibling();
				if (beforeDot == null) {
					return null;
				}
				String bdText = beforeDot.getText();
				if (!"^self".equals(bdText) && !"self".equals(bdText)) {
					return null;
				}
				varName = "self." + text;
			} else if (prevType == Parser3TokenTypes.COLON) {
				// :user.method — перед COLON должен быть ^MAIN
				PsiElement beforeColon = prev.getPrevSibling();
				if (beforeColon == null) {
					return null;
				}
				String bcText = beforeColon.getText();
				if (!"^MAIN".equals(bcText) && !"MAIN".equals(bcText)) {
					return null;
				}
				varName = "MAIN:" + text;
			} else {
				return null;
			}
		}

		PsiFile file = sourceElement.getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return null;
		}

		Project project = sourceElement.getProject();
		VirtualFile currentVFile = file.getVirtualFile();
		List<VirtualFile> visibleFiles = getVisibleFiles(project, currentVFile, sourceElement.getTextOffset());

		// Ищем определение переменной через индекс (во всех видимых файлах)
		ru.artlebedev.parser3.index.P3VariableIndex varTypeIndex =
				ru.artlebedev.parser3.index.P3VariableIndex.getInstance(project);

		ru.artlebedev.parser3.index.P3VariableIndex.VariableDefinitionLocation location =
				varTypeIndex.findVariableDefinitionLocation(varName, visibleFiles, currentVFile, sourceElement.getTextOffset());

		if (location == null) {
			// Нет присваивания — пробуем навигировать к параметру метода
			String pureVarName = ru.artlebedev.parser3.index.P3VariableIndex.extractPureVarName(varName);
			PsiElement[] paramResult = navigateToMethodParameter(sourceElement, pureVarName, currentVFile);
			if (paramResult != null) return paramResult;
			return null;
		}

		// Навигация на себя: location.offset совпадает с позицией клика — fallback на параметр
		if (location.file.equals(currentVFile) && location.offset == sourceElement.getTextOffset()) {
			String pureVarName = ru.artlebedev.parser3.index.P3VariableIndex.extractPureVarName(varName);
			PsiElement[] paramResult = navigateToMethodParameter(sourceElement, pureVarName, currentVFile);
			if (paramResult != null) return paramResult;
			return null;
		}

		PsiManager psiManager = PsiManager.getInstance(project);
		PsiFile targetFile = psiManager.findFile(location.file);
		if (targetFile == null) {
			return null;
		}

		PsiElement targetElement = targetFile.findElementAt(location.offset);
		if (targetElement != null) {
			return new PsiElement[]{P3NavigationTargets.createVariableTarget(targetElement, location.file, location.offset)};
		}
		return null;
	}

	/**
	 * Обрабатывает навигацию по $var, $self.var, $MAIN:var — переход к определению переменной.
	 * Переиспользует findVariableDefinitionLocation из P3VariableIndex.
	 *
	 * Поддерживает:
	 * - Клик на VARIABLE после DOLLAR_VARIABLE: $|var| → ищем определение var
	 * - Клик на DOLLAR_VARIABLE: |$|var → ищем определение var (следующий токен)
	 * - Клик на IMPORTANT_VARIABLE: |$self|.var → ищем определение self.var
	 * - Клик на VARIABLE после DOT после IMPORTANT_VARIABLE: $self.|var| → self.var
	 */
	private PsiElement @Nullable [] handleDollarVariableNavigation(@NotNull PsiElement sourceElement) {
		IElementType elementType = sourceElement.getNode().getElementType();
		String varKey = null;

		PsiElement searchFrom = sourceElement;
		PsiElement parent = sourceElement.getParent();

		if (DEBUG) System.out.println("[handleDollarVar] ENTER: elementType=" + elementType
				+ " text='" + sourceElement.getText() + "'"
				+ " parentType=" + (parent != null && parent.getNode() != null ? parent.getNode().getElementType() : "null")
				+ " hasPrev=" + (sourceElement.getPrevSibling() != null)
				+ " hasNext=" + (sourceElement.getNextSibling() != null));

		if (parent != null && sourceElement.getNextSibling() == null && sourceElement.getPrevSibling() == null) {
			searchFrom = parent;
			if (DEBUG) System.out.println("[handleDollarVar] searchFrom switched to parent: " + parent.getNode().getElementType()
					+ " text='" + parent.getText() + "'");
		}

		if (elementType == Parser3TokenTypes.VARIABLE) {
			PsiElement prev = searchFrom.getPrevSibling();
			if (DEBUG) System.out.println("[handleDollarVar] VARIABLE branch: prev="
					+ (prev != null && prev.getNode() != null ? prev.getNode().getElementType() + " '" + prev.getText() + "'" : "null"));
			if (prev != null && prev.getNode() != null) {
				IElementType prevType = prev.getNode().getElementType();
				if (prevType == Parser3TokenTypes.DOT) {
					PsiElement beforeDot = prev.getPrevSibling();
					if (DEBUG) System.out.println("[handleDollarVar] DOT found, beforeDot="
							+ (beforeDot != null && beforeDot.getNode() != null ? beforeDot.getNode().getElementType() + " '" + beforeDot.getText() + "'" : "null"));
				}
			}
		}
		if (elementType == Parser3TokenTypes.IMPORTANT_VARIABLE) {
			PsiElement next = searchFrom.getNextSibling();
			if (DEBUG) System.out.println("[handleDollarVar] IMPORTANT_VARIABLE branch: next="
					+ (next != null && next.getNode() != null ? next.getNode().getElementType() + " '" + next.getText() + "'" : "null"));
		}

		if (elementType == Parser3TokenTypes.DOLLAR_VARIABLE) {
			// Клик на $ — берём имя из следующего VARIABLE токена
			PsiElement next = searchFrom.getNextSibling();
			if (next != null && next.getNode() != null
					&& next.getNode().getElementType() == Parser3TokenTypes.VARIABLE) {
				varKey = next.getText();
			}
		} else if (elementType == Parser3TokenTypes.NUMBER) {
			// Клик на числовой индекс: $arr.0 → навигация по hash key chain
			PsiElement prev = searchFrom.getPrevSibling();
			if (DEBUG) System.out.println("[GotoDecl] NUMBER branch: text='" + sourceElement.getText() + "' prev=" + (prev != null && prev.getNode() != null ? prev.getNode().getElementType() : "null"));
			if (prev != null && prev.getNode() != null
					&& prev.getNode().getElementType() == Parser3TokenTypes.DOT) {
				PsiElement[] hashKeyResult = handleHashKeyNavigation(sourceElement);
				if (DEBUG) System.out.println("[GotoDecl] NUMBER hashKeyResult=" + (hashKeyResult != null ? hashKeyResult.length : "null"));
				if (hashKeyResult != null) return hashKeyResult;
			}
		} else if (elementType == Parser3TokenTypes.VARIABLE) {
			// Клик на var — проверяем что перед ним $ или $self. или $MAIN:
			PsiElement prev = searchFrom.getPrevSibling();
			if (prev == null || prev.getNode() == null) return null;
			IElementType prevType = prev.getNode().getElementType();

			if (prevType == Parser3TokenTypes.DOLLAR_VARIABLE) {
				// $var
				varKey = sourceElement.getText();
			} else if (prevType == Parser3TokenTypes.LBRACE) {
				// ${var — перед { должен стоять $
				PsiElement beforeLbrace = prev.getPrevSibling();
				if (beforeLbrace != null && beforeLbrace.getNode() != null
						&& beforeLbrace.getNode().getElementType() == Parser3TokenTypes.DOLLAR_VARIABLE) {
					varKey = sourceElement.getText();
				}
			} else if (prevType == Parser3TokenTypes.DOT) {
				// .var — проверяем что перед DOT стоит IMPORTANT_VARIABLE (self)
				PsiElement beforeDot = prev.getPrevSibling();
				if (beforeDot != null && beforeDot.getNode() != null
						&& beforeDot.getNode().getElementType() == Parser3TokenTypes.IMPORTANT_VARIABLE) {
					String impText = beforeDot.getText();
					if ("self".equals(impText) || "$self".equals(impText)) {
						varKey = "self." + sourceElement.getText();
					}
				}
				// Навигация по hash key chain: $value.key4.list → клик на key4/list
				if (varKey == null) {
					PsiElement[] hashKeyResult = handleHashKeyNavigation(sourceElement);
					if (hashKeyResult != null) return hashKeyResult;
				}
			} else if (prevType == Parser3TokenTypes.COLON) {
				// :var — проверяем что перед COLON стоит IMPORTANT_VARIABLE (MAIN или BASE)
				PsiElement beforeColon = prev.getPrevSibling();
				if (DEBUG) System.out.println("[handleDollarVar] COLON found, beforeColon="
						+ (beforeColon != null && beforeColon.getNode() != null
						? beforeColon.getNode().getElementType() + " '" + beforeColon.getText() + "'" : "null"));
				if (beforeColon != null && beforeColon.getNode() != null
						&& beforeColon.getNode().getElementType() == Parser3TokenTypes.IMPORTANT_VARIABLE) {
					String impText = beforeColon.getText();
					if (DEBUG) System.out.println("[handleDollarVar] IMPORTANT_VARIABLE before COLON: '" + impText + "'");
					if ("MAIN".equals(impText) || "$MAIN".equals(impText)) {
						varKey = "MAIN:" + sourceElement.getText();
					} else if ("BASE".equals(impText) || "$BASE".equals(impText)) {
						varKey = "BASE:" + sourceElement.getText();
					}
				}
			} else {
				return null;
			}
		} else if (elementType == Parser3TokenTypes.IMPORTANT_VARIABLE) {
			String text = sourceElement.getText();
			if (text == null) return null;

			PsiElement next = searchFrom.getNextSibling();

			if (next != null && next.getNode() != null) {
				// $MAIN:var — берём следующий COLON + VARIABLE
				if (("MAIN".equals(text) || "$MAIN".equals(text)) && next.getNode().getElementType() == Parser3TokenTypes.COLON) {
					PsiElement varEl = next.getNextSibling();
					if (varEl != null && varEl.getNode() != null
							&& varEl.getNode().getElementType() == Parser3TokenTypes.VARIABLE) {
						varKey = "MAIN:" + varEl.getText();
					}
				}
				// $self.var — не обрабатываем тут (обрабатывается в VARIABLE + DOT)
				// $BASE:var — не обрабатываем тут (обрабатывается в VARIABLE + COLON)
			}

			// $result — обычная переменная (IMPORTANT_VARIABLE без следующего : или .)
			if (varKey == null) {
				String cleanName = text.startsWith("$") ? text.substring(1) : text;
				if ("result".equals(cleanName)) {
					varKey = cleanName;
				}
			}
		}

		if (varKey == null) return null;

		if (DEBUG) System.out.println("[GotoDecl.handleDollarVar] varKey=" + varKey
				+ " elementType=" + elementType + " text=" + sourceElement.getText()
				+ " offset=" + sourceElement.getTextOffset());

		PsiFile file = sourceElement.getContainingFile();
		if (file == null || file.getVirtualFile() == null) return null;

		Project project = sourceElement.getProject();
		VirtualFile currentVFile = file.getVirtualFile();
		List<VirtualFile> visibleFiles = getVisibleFiles(project, currentVFile, sourceElement.getTextOffset());

		// Вычисляем offset начала $ — индекс хранит offset $, а sourceElement может быть на имени.
		// Передаём dollarOffset в findVariable, чтобы текущее присваивание не попало в результат
		// (фильтр info.offset < maxOffset отсечёт его).
		int dollarOffset = computeDollarOffset(sourceElement, searchFrom, elementType);

		if (DEBUG) System.out.println("[GotoDecl.handleDollarVar] dollarOffset=" + dollarOffset);

		ru.artlebedev.parser3.index.P3VariableIndex varIndex =
				ru.artlebedev.parser3.index.P3VariableIndex.getInstance(project);

		ru.artlebedev.parser3.index.P3VariableIndex.VisibleVariable visibleVariable =
				varIndex.findVariable(varKey, visibleFiles, currentVFile, dollarOffset);
		ru.artlebedev.parser3.index.P3VariableIndex.VariableDefinitionLocation location =
				visibleVariable != null
						? new ru.artlebedev.parser3.index.P3VariableIndex.VariableDefinitionLocation(
								visibleVariable.file, visibleVariable.offset)
						: null;

		if (visibleVariable != null && visibleVariable.isAdditive) {
			PsiElement[] getterResult = navigateToGetterProperty(sourceElement, varKey, project, currentVFile, visibleFiles);
			if (getterResult != null && getterResult.length > 0) {
				if (DEBUG) System.out.println("[handleDollarVar] additive variable resolved to @GET_: " + varKey);
				return getterResult;
			}
		}

		// Навигация на самого себя — location.offset совпадает с dollarOffset (клик на единственном определении)
		if (location != null && location.file.equals(currentVFile) && location.offset == dollarOffset) {
			if (DEBUG) System.out.println("[handleDollarVar] navigation to self — trying parameter fallback");
			// Это определение переменной — fallback на параметр метода
			String pureVarName = ru.artlebedev.parser3.index.P3VariableIndex.extractPureVarName(varKey);
			PsiElement[] paramResult = navigateToMethodParameter(sourceElement, pureVarName, currentVFile);
			if (paramResult != null) return paramResult;
			return PsiElement.EMPTY_ARRAY;
		}

		if (location == null) {
			// Fallback 1: параметр текущего метода
			String pureVarName = ru.artlebedev.parser3.index.P3VariableIndex.extractPureVarName(varKey);
			PsiElement[] paramResult = navigateToMethodParameter(sourceElement, pureVarName, currentVFile);
			if (paramResult != null) return paramResult;

			// Fallback 2: ищем @GET_prop метод в текущем классе и @BASE иерархии
			String prefix = ru.artlebedev.parser3.index.P3VariableIndex.extractVarPrefix(varKey);
			String propName = pureVarName;
			if (DEBUG) System.out.println("[handleDollarVar] location=null, trying @GET_ fallback: prefix=" + prefix + " propName=" + propName);

			PsiElement[] getterResult = navigateToGetterProperty(sourceElement, varKey, project, currentVFile, visibleFiles);
			if (DEBUG) System.out.println("[handleDollarVar] getterResult=" + (getterResult != null ? getterResult.length : "null"));
			if (getterResult != null && getterResult.length > 0) return getterResult;
			return null;
		}

		PsiManager psiManager = PsiManager.getInstance(project);
		PsiFile targetFile = psiManager.findFile(location.file);
		if (targetFile == null) return null;

		PsiElement targetElement = targetFile.findElementAt(location.offset);
		if (targetElement != null) {
			return new PsiElement[]{P3NavigationTargets.createVariableTarget(targetElement, location.file, location.offset)};
		}
		return null;
	}

	/**
	 * Навигация к @GET_ свойству. Parser3 отдаёт getter раньше обычного значения свойства,
	 * поэтому синтетическая read-chain не должна вести на место чтения.
	 */
	private PsiElement @Nullable [] navigateToGetterProperty(
			@NotNull PsiElement sourceElement,
			@NotNull String varKey,
			@NotNull Project project,
			@NotNull VirtualFile currentVFile,
			@NotNull List<VirtualFile> visibleFiles
	) {
		String pureVarName = ru.artlebedev.parser3.index.P3VariableIndex.extractPureVarName(varKey);
		if (pureVarName.contains(".")) return null;

		String prefix = ru.artlebedev.parser3.index.P3VariableIndex.extractVarPrefix(varKey);
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		P3ClassDeclaration cursorClass = classIndex.findClassAtOffset(currentVFile, sourceElement.getTextOffset());
		if (cursorClass == null) return null;

		String targetClassName;
		if ("BASE:".equals(prefix)) {
			targetClassName = cursorClass.getBaseClassName();
		} else {
			targetClassName = cursorClass.getName();
		}
		if (targetClassName == null || targetClassName.isEmpty()) return null;

		return handleGetterClassPropertyCall(project, targetClassName, pureVarName, visibleFiles);
	}

	/**
	 * Навигация к параметру метода: $data → @method[data].
	 * Ищет текущий метод по offset, проверяет параметры, находит offset параметра в тексте.
	 */
	private PsiElement @Nullable [] navigateToMethodParameter(
			@NotNull PsiElement sourceElement,
			@NotNull String paramName,
			@NotNull VirtualFile currentVFile
	) {
		PsiFile file = sourceElement.getContainingFile();
		if (file == null) return null;

		String text = file.getText();
		if (text == null) return null;

		int offset = sourceElement.getTextOffset();

		// Находим текущий метод
		List<ru.artlebedev.parser3.utils.Parser3ClassUtils.ClassBoundary> cb =
				ru.artlebedev.parser3.utils.Parser3ClassUtils.findClassBoundaries(text);
		List<ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary> mb =
				ru.artlebedev.parser3.utils.Parser3ClassUtils.findMethodBoundaries(text, cb);
		ru.artlebedev.parser3.utils.Parser3ClassUtils.MethodBoundary currentMethod =
				ru.artlebedev.parser3.utils.Parser3ClassUtils.findOwnerMethod(offset, mb);
		if (currentMethod == null) return null;

		// Парсим параметры прямо из текста: @methodName[param1;param2;param3]
		int methodOffset = currentMethod.start;
		int bracketPos = text.indexOf('[', methodOffset);
		if (bracketPos == -1) return null;
		int bracketEnd = text.indexOf(']', bracketPos);
		if (bracketEnd == -1) return null;

		String paramsStr = text.substring(bracketPos + 1, bracketEnd);
		// Ищем конкретный параметр
		int searchPos = bracketPos + 1;
		for (String p : paramsStr.split(";")) {
			String trimmed = p.trim();
			// Пропускаем пробелы в text
			while (searchPos < text.length() && Character.isWhitespace(text.charAt(searchPos))) searchPos++;

			if (trimmed.equals(paramName)) {
				// Нашли — навигируем сюда
				PsiElement target = file.findElementAt(searchPos);
				if (target != null) {
					return new PsiElement[]{target};
				}
				return null;
			}

			// Пропускаем имя параметра + разделитель
			searchPos += trimmed.length();
			// Пропускаем пробелы после имени
			while (searchPos < text.length() && Character.isWhitespace(text.charAt(searchPos))) searchPos++;
			// Пропускаем ;
			if (searchPos < text.length() && text.charAt(searchPos) == ';') searchPos++;
		}
		return null;
	}

	/**
	 * Вычисляет offset символа $ для переменной из PSI элемента.
	 * Индекс хранит offset именно $, а sourceElement может быть на любом токене
	 * ($, self, MAIN, var). Нужно пройти назад по цепочке токенов до $.
	 *
	 * Примеры: $var → offset($), $self.var → offset($), $MAIN:var → offset($)
	 */
	private static int computeDollarOffset(
			@NotNull PsiElement sourceElement,
			@NotNull PsiElement searchFrom,
			@NotNull IElementType elementType
	) {
		// Если сам элемент — $ (DOLLAR_VARIABLE), возвращаем его offset
		if (elementType == Parser3TokenTypes.DOLLAR_VARIABLE) {
			return sourceElement.getTextOffset();
		}

		// Если IMPORTANT_VARIABLE ($result, $self, $MAIN) — ищём $ перед ним
		if (elementType == Parser3TokenTypes.IMPORTANT_VARIABLE) {
			PsiElement prevDollar = searchFrom.getPrevSibling();
			if (prevDollar != null && prevDollar.getNode() != null
					&& prevDollar.getNode().getElementType() == Parser3TokenTypes.DOLLAR_VARIABLE) {
				return prevDollar.getTextOffset();
			}
			return sourceElement.getTextOffset();
		}

		// VARIABLE — идём назад по цепочке
		int result = sourceElement.getTextOffset();
		PsiElement prev = sourceElement.getPrevSibling();
		if (prev == null && sourceElement.getParent() != null) {
			prev = sourceElement.getParent().getPrevSibling();
		}
		if (prev == null || prev.getNode() == null) return result;

		IElementType pt = prev.getNode().getElementType();

		if (pt == Parser3TokenTypes.DOLLAR_VARIABLE) {
			// $var — prev = $
			return prev.getTextOffset();
		}

		if (pt == Parser3TokenTypes.IMPORTANT_VARIABLE) {
			// $self (prev = "self") или $MAIN (prev = "MAIN") — ищём $ перед ним
			return findDollarBefore(prev);
		}

		if (pt == Parser3TokenTypes.DOT || pt == Parser3TokenTypes.COLON) {
			// .var или :var — ищём ещё назад
			PsiElement pp = prev.getPrevSibling();
			if (pp != null && pp.getNode() != null) {
				IElementType ppt = pp.getNode().getElementType();
				if (ppt == Parser3TokenTypes.IMPORTANT_VARIABLE) {
					// $self.var или $MAIN:var — pp = "self"/"MAIN", ищём $ перед ним
					return findDollarBefore(pp);
				}
				if (ppt == Parser3TokenTypes.DOLLAR_VARIABLE) {
					return pp.getTextOffset();
				}
			}
		}

		return result;
	}

	/**
	 * Ищет DOLLAR_VARIABLE ($) перед заданным элементом (обычно IMPORTANT_VARIABLE).
	 */
	private static int findDollarBefore(@NotNull PsiElement element) {
		PsiElement before = element.getPrevSibling();
		if (before != null && before.getNode() != null
				&& before.getNode().getElementType() == Parser3TokenTypes.DOLLAR_VARIABLE) {
			return before.getTextOffset();
		}
		return element.getTextOffset();
	}

	/**
	 * Обрабатывает вызов метода через BASE: — ищет метод в базовом классе текущего класса.
	 */
	private PsiElement @Nullable [] handleBaseMethodCall(
			@NotNull PsiElement sourceElement,
			@NotNull String methodName,
			@NotNull Project project,
			@NotNull List<VirtualFile> visibleFiles
	) {
		PsiFile file = sourceElement.getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return null;
		}

		// Находим текущий класс
		int offset = sourceElement.getTextOffset();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		P3ClassDeclaration currentClass = classIndex.findClassAtOffset(file.getVirtualFile(), offset);

		if (currentClass == null) {
			return null;
		}

		// Получаем имя базового класса
		String baseClassName = currentClass.getBaseClassName();
		if (baseClassName == null || baseClassName.isEmpty()) {
			return null;
		}

		// Находим базовый класс
		P3ClassDeclaration baseClass = classIndex.findLastInFiles(baseClassName, visibleFiles);
		if (baseClass == null) {
			return null;
		}

		// Ищем метод в базовом классе
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> allMethods = methodIndex.findInFiles(methodName, visibleFiles);

		// Фильтруем — оставляем только методы из базового класса и его иерархии
		List<P3MethodDeclaration> baseMethods = new ArrayList<>();
		for (P3MethodDeclaration method : allMethods) {
			if (isMethodInClassHierarchy(method, baseClass, classIndex, visibleFiles)) {
				baseMethods.add(method);
			}
		}

		if (baseMethods.isEmpty()) {
			return null;
		}

		Collections.reverse(baseMethods);
		return createMethodTargets(project, baseMethods, methodName);
	}

	/**
	 * Обрабатывает вызов метода через self: — ищет метод только в текущем классе и его @BASE цепочке.
	 * ^self.method[] — вызов метода текущего объекта.
	 */
	private PsiElement @Nullable [] handleSelfMethodCall(
			@NotNull PsiElement sourceElement,
			@NotNull String methodName,
			@NotNull Project project,
			@NotNull List<VirtualFile> visibleFiles
	) {
		PsiFile file = sourceElement.getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return null;
		}

		// Находим текущий класс
		int offset = sourceElement.getTextOffset();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		P3ClassDeclaration currentClass = classIndex.findClassAtOffset(file.getVirtualFile(), offset);

		// Ищем все методы с этим именем
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> allMethods = methodIndex.findInFiles(methodName, visibleFiles);

		if (currentClass != null && !"MAIN".equals(currentClass.getName())) {
			// Есть именованный класс (не MAIN) — ищем по иерархии с приоритетом текущему классу
			// Идём по иерархии: текущий класс -> @BASE -> @BASE @BASE -> ...
			// Возвращаем метод из ПЕРВОГО класса в иерархии где он найден

			P3ClassDeclaration current = currentClass;
			Set<String> visited = new HashSet<>();

			while (current != null && visited.add(current.getName())) {
				// Ищем метод в текущем классе иерархии
				for (P3MethodDeclaration method : allMethods) {
					if (isMethodDirectlyInClass(method, current)) {
						// Нашли метод в этом классе — возвращаем только его
						return createMethodTargets(project, Collections.singletonList(method), methodName);
					}
				}

				// Идём к базовому классу
				String baseName = current.getBaseClassName();
				if (baseName == null || baseName.isEmpty()) {
					break;
				}
				current = classIndex.findLastInFiles(baseName, visibleFiles);
			}

			return null;
		} else {
			// Мы в MAIN классе — ^self.method[] работает как ^MAIN:method[]
			// Ищем методы MAIN во всех видимых файлах
			List<P3MethodDeclaration> mainMethods = new ArrayList<>();
			for (P3MethodDeclaration method : allMethods) {
				String methodClassName = findClassNameForMethod(method, project);
				if ("MAIN".equals(methodClassName)) {
					mainMethods.add(method);
				}
			}

			if (mainMethods.isEmpty()) {
				return null;
			}

			Collections.reverse(mainMethods);
			return createMethodTargets(project, mainMethods, methodName);
		}
	}

	/**
	 * Проверяет, определён ли метод непосредственно в указанном классе (по offset).
	 */
	private boolean isMethodDirectlyInClass(
			@NotNull P3MethodDeclaration method,
			@NotNull P3ClassDeclaration classDecl
	) {
		if (!method.getFile().equals(classDecl.getFile())) {
			return false;
		}
		int methodOffset = method.getOffset();
		int classStart = classDecl.getStartOffset();
		int classEnd = classDecl.getEndOffset();
		return methodOffset >= classStart && methodOffset < classEnd;
	}

	/**
	 * Обрабатывает вызов метода через MAIN: — ищет метод только в MAIN классах.
	 * ^MAIN:method[] — вызов глобального метода.
	 */
	private PsiElement @Nullable [] handleMainMethodCall(
			@NotNull PsiElement sourceElement,
			@NotNull String methodName,
			@NotNull Project project,
			@NotNull List<VirtualFile> visibleFiles
	) {
		// Ищем все методы с этим именем
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> allMethods = methodIndex.findInFiles(methodName, visibleFiles);

		// Фильтруем — оставляем только методы из MAIN классов
		List<P3MethodDeclaration> mainMethods = new ArrayList<>();
		for (P3MethodDeclaration method : allMethods) {
			String methodClassName = findClassNameForMethod(method, project);
			if ("MAIN".equals(methodClassName)) {
				mainMethods.add(method);
			}
		}

		if (mainMethods.isEmpty()) {
			return null;
		}

		Collections.reverse(mainMethods);
		return createMethodTargets(project, mainMethods, methodName);
	}

	/**
	 * Обрабатывает клик на объявлении метода (@method[]) — навигация к вызовам этого метода.
	 * Ищет все вызовы в видимых файлах с учётом класса-владельца.
	 *
	 * ОПТИМИЗАЦИЯ: Сначала получаем ВСЕ вызовы метода из индекса (быстро),
	 * потом фильтруем по видимости только файлы с вызовами (а не все файлы проекта).
	 */
	private PsiElement @Nullable [] handleMethodDefinitionNavigation(@NotNull PsiElement sourceElement) {
		String text = sourceElement.getText();
		if (text == null || !text.startsWith("@")) {
			return null;
		}

		// Извлекаем имя метода из @methodName или @methodName[params]
		String methodName = extractMethodNameFromDefinition(text);
		if (methodName == null || methodName.isEmpty()) {
			return null;
		}

		PsiFile file = sourceElement.getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return null;
		}

		Project project = sourceElement.getProject();
		VirtualFile currentFile = file.getVirtualFile();

		// Определяем класс-владелец метода
		int offset = sourceElement.getTextOffset();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		P3ClassDeclaration ownerClass = classIndex.findClassAtOffset(currentFile, offset);
		String ownerClassName = ownerClass != null ? ownerClass.getName() : null;

		// Ищем вызовы метода с оптимизацией
		List<P3MethodCallIndex.MethodCallResult> calls = findMethodCallsOptimized(
				project, currentFile, methodName, ownerClassName
		);


		if (calls.isEmpty()) {
			return PsiElement.EMPTY_ARRAY;
		}

		// Создаём navigation targets
		return createMethodCallTargets(project, calls, methodName);
	}

	/**
	 * Оптимизированный поиск вызовов метода.
	 *
	 * Вместо того чтобы сначала вычислять обратную видимость для ВСЕХ файлов проекта
	 * (что очень медленно), мы:
	 * 1. Получаем из индекса ВСЕ вызовы метода (быстро, это просто чтение индекса)
	 * 2. Фильтруем по видимости только файлы с вызовами
	 *
	 * Это намного быстрее, потому что вызовов конкретного метода обычно мало.
	 */
	private @NotNull List<P3MethodCallIndex.MethodCallResult> findMethodCallsOptimized(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull String methodName,
			@Nullable String ownerClassName
	) {
		P3MethodCallIndex callIndex = P3MethodCallIndex.getInstance(project);

		// Получаем ВСЕ вызовы метода из индекса (быстро)
		List<P3MethodCallIndex.MethodCallResult> allCalls = callIndex.findByName(methodName);

		if (allCalls.isEmpty()) {
			return Collections.emptyList();
		}

		// 2. Собираем уникальные файлы с вызовами
		Set<VirtualFile> filesWithCalls = new HashSet<>();
		for (P3MethodCallIndex.MethodCallResult call : allCalls) {
			filesWithCalls.add(call.file);
		}

		// Фильтруем файлы через общий контекст обратной видимости.
		List<VirtualFile> visibleFilesWithCalls = P3ScopeContext.filterFilesThatCanSeeMethod(
				project,
				currentFile,
				ownerClassName,
				new ArrayList<>(filesWithCalls));

		if (visibleFilesWithCalls.isEmpty()) {
			return Collections.emptyList();
		}

		// 4. Теперь ищем вызовы только в видимых файлах с учётом класса-владельца
		return callIndex.findCallsForMethod(methodName, ownerClassName, visibleFilesWithCalls);
	}

	/**
	 * Извлекает имя метода из строки определения (@methodName или @methodName[params]).
	 */
	private @Nullable String extractMethodNameFromDefinition(@NotNull String text) {
		if (!text.startsWith("@")) {
			return null;
		}

		// Убираем @
		String withoutAt = text.substring(1);

		// Ищем [ или конец строки
		int bracketPos = withoutAt.indexOf('[');
		String methodName;
		if (bracketPos > 0) {
			methodName = withoutAt.substring(0, bracketPos).trim();
		} else {
			methodName = withoutAt.trim();
		}

		// @GET_xxx → xxx (для навигации к вызовам ^xxx[])
		if (methodName.startsWith("GET_") && !methodName.equals("GET_DEFAULT")) {
			methodName = methodName.substring(4);
		}

		return methodName;
	}

	/**
	 * Создаёт navigation targets для вызовов методов.
	 */
	private PsiElement @Nullable [] createMethodCallTargets(
			@NotNull Project project,
			@NotNull List<P3MethodCallIndex.MethodCallResult> calls,
			@NotNull String methodName
	) {
		List<PsiElement> targets = new ArrayList<>();
		List<PsiElement> commentTargets = new ArrayList<>(); // Вызовы в комментариях - отдельно
		PsiManager psiManager = PsiManager.getInstance(project);

		for (P3MethodCallIndex.MethodCallResult call : calls) {
			PsiFile callFile = psiManager.findFile(call.file);
			if (callFile != null) {
				PsiElement target = callFile.findElementAt(call.callInfo.offset);
				if (target != null) {
					P3NavigationTargets.MethodCallTarget navTarget = new P3NavigationTargets.MethodCallTarget(target, call, methodName);
					// Комментарии в отдельный список
					if (call.callInfo.isInComment) {
						commentTargets.add(navTarget);
					} else {
						targets.add(navTarget);
					}
				}
			}
		}

		// Добавляем комментарии в конец
		targets.addAll(commentTargets);

		return targets.isEmpty() ? PsiElement.EMPTY_ARRAY : targets.toArray(new PsiElement[0]);
	}

	/**
	 * Проверяет принадлежит ли метод классу или его иерархии наследования.
	 */
	boolean isMethodInClassHierarchy(
			@NotNull P3MethodDeclaration method,
			@NotNull P3ClassDeclaration targetClass,
			@NotNull P3ClassIndex classIndex,
			@NotNull List<VirtualFile> visibleFiles
	) {
		// Проверяем по файлу и offset — метод должен быть внутри класса
		VirtualFile methodFile = method.getFile();
		int methodOffset = method.getOffset();

		// Проходим по иерархии классов
		P3ClassDeclaration current = targetClass;
		Set<String> visited = new HashSet<>();

		while (current != null && visited.add(current.getName())) {
			// Проверяем что метод внутри этого класса
			if (methodFile.equals(current.getFile())) {
				int classStart = current.getStartOffset();
				int classEnd = current.getEndOffset();
				if (methodOffset >= classStart && methodOffset < classEnd) {
					return true;
				}
			}

			// Идём к базовому классу
			String baseName = current.getBaseClassName();
			if (baseName == null || baseName.isEmpty()) {
				break;
			}
			current = classIndex.findLastInFiles(baseName, visibleFiles);
		}

		return false;
	}

	/**
	 * Проверяет, определён ли метод непосредственно в указанном классе (не в иерархии).
	 */
	boolean isMethodDefinedInClass(
			@NotNull String methodName,
			@NotNull String className,
			@NotNull P3ClassIndex classIndex,
			@NotNull P3MethodIndex methodIndex,
			@NotNull List<VirtualFile> visibleFiles
	) {
		P3ClassDeclaration classDecl = classIndex.findLastInFiles(className, visibleFiles);
		if (classDecl == null) {
			return false;
		}

		List<P3MethodDeclaration> methods = methodIndex.findInFiles(methodName, visibleFiles);
		for (P3MethodDeclaration method : methods) {
			// Проверяем что метод определён именно в этом классе
			if (method.getFile().equals(classDecl.getFile())) {
				int methodOffset = method.getOffset();
				int classStart = classDecl.getStartOffset();
				int classEnd = classDecl.getEndOffset();
				if (methodOffset >= classStart && methodOffset < classEnd) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Ищет только @GET_ свойство в указанном классе и его @BASE.
	 * Обычный метод с таким же именем не считается свойством.
	 */
	private PsiElement @Nullable [] handleGetterClassPropertyCall(
			@NotNull Project project,
			@NotNull String className,
			@NotNull String propertyName,
			@NotNull List<VirtualFile> visibleFiles
	) {
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		List<P3ClassDeclaration> allClassDecls = classIndex.findByName(className);

		List<P3ClassDeclaration> visibleClassDecls = new ArrayList<>();
		for (P3ClassDeclaration decl : allClassDecls) {
			if (visibleFiles.contains(decl.getFile())) {
				visibleClassDecls.add(decl);
			}
		}
		if (visibleClassDecls.isEmpty()) {
			return PsiElement.EMPTY_ARRAY;
		}

		List<VirtualFile> classFiles = new ArrayList<>();
		for (P3ClassDeclaration decl : visibleClassDecls) {
			if (!classFiles.contains(decl.getFile())) {
				classFiles.add(decl.getFile());
			}
		}

		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> methodDeclarations = methodIndex.findInFiles(propertyName, classFiles);
		List<P3MethodDeclaration> filteredMethods = new ArrayList<>();
		for (P3MethodDeclaration method : methodDeclarations) {
			if (!method.isGetter()) continue;

			String methodClassName = findClassNameForMethod(method, project);
			if (methodClassName.equals(className)) {
				filteredMethods.add(method);
			}
		}

		if (!filteredMethods.isEmpty()) {
			return createMethodTargets(project, filteredMethods, propertyName);
		}

		P3ClassDeclaration lastDecl = visibleClassDecls.get(visibleClassDecls.size() - 1);
		String baseClassName = lastDecl.getBaseClassName();
		if (baseClassName != null && !baseClassName.isEmpty()) {
			return handleGetterClassPropertyCall(project, baseClassName, propertyName, visibleFiles);
		}
		return PsiElement.EMPTY_ARRAY;
	}

	/**
	 * Обрабатывает вызов метода класса: ^User::create[] или ^User:method[]
	 * Валидность уже проверена в P3PsiExtractor.parseMethodCall()
	 */
	private PsiElement @Nullable [] handleClassMethodCall(
			@NotNull PsiElement sourceElement,
			@NotNull String className,
			@NotNull String methodName,
			@NotNull Project project,
			@NotNull List<VirtualFile> visibleFiles
	) {

		// Ищем класс
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);

		// Для partial-классов ищем все объявления класса
		List<P3ClassDeclaration> allClassDecls = classIndex.findByName(className);

		// Фильтруем по видимым файлам
		List<P3ClassDeclaration> visibleClassDecls = new ArrayList<>();
		for (P3ClassDeclaration decl : allClassDecls) {
			if (visibleFiles.contains(decl.getFile())) {
				visibleClassDecls.add(decl);
			}
		}


		if (visibleClassDecls.isEmpty()) {
			return PsiElement.EMPTY_ARRAY;
		}

		// Если нет имени метода - переходим к последнему классу
		if (methodName.isEmpty()) {
			return navigateToClass(project, visibleClassDecls.get(visibleClassDecls.size() - 1));
		}

		// Собираем все файлы, в которых определён класс (для partial)
		List<VirtualFile> classFiles = new ArrayList<>();
		for (P3ClassDeclaration decl : visibleClassDecls) {
			if (!classFiles.contains(decl.getFile())) {
				classFiles.add(decl.getFile());
			}
		}

		// Ищем метод во всех файлах класса
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> methodDeclarations = methodIndex.findInFiles(methodName, classFiles);


		if (methodDeclarations.isEmpty()) {
			// Метод не найден в самом классе — ищем в @BASE
			String baseClassName = null;
			P3ClassDeclaration lastDecl = visibleClassDecls.get(visibleClassDecls.size() - 1);
			baseClassName = lastDecl.getBaseClassName();

			if (baseClassName != null && !baseClassName.isEmpty()) {
				return handleClassMethodCall(sourceElement, baseClassName, methodName, project, visibleFiles);
			}
			return PsiElement.EMPTY_ARRAY;
		}

		// Фильтруем методы — оставляем только те, что принадлежат указанному классу
		List<P3MethodDeclaration> filteredMethods = new ArrayList<>();
		for (P3MethodDeclaration method : methodDeclarations) {
			String methodClassName = findClassNameForMethod(method, project);
			if (methodClassName.equals(className)) {
				filteredMethods.add(method);
			}
		}

		if (filteredMethods.isEmpty()) {
			// Не найден в указанном классе — ищем в @BASE
			String baseClassName = null;
			P3ClassDeclaration lastDecl = visibleClassDecls.get(visibleClassDecls.size() - 1);
			baseClassName = lastDecl.getBaseClassName();

			if (baseClassName != null && !baseClassName.isEmpty()) {
				return handleClassMethodCall(sourceElement, baseClassName, methodName, project, visibleFiles);
			}
			return PsiElement.EMPTY_ARRAY;
		}

		// Создаём targets для найденных методов
		return createMethodTargets(project, filteredMethods, methodName);
	}

	/**
	 * Обрабатывает простой вызов: ^methodName[] или ^ClassName[]
	 */
	private PsiElement @Nullable [] handleSimpleCall(
			@NotNull PsiElement sourceElement,
			@NotNull String name,
			@NotNull Project project,
			@NotNull List<VirtualFile> visibleFiles
	) {
		PsiFile currentFile = sourceElement.getContainingFile();
		VirtualFile currentVirtualFile = currentFile != null ? currentFile.getVirtualFile() : null;

		// Определяем в каком классе находится вызов
		int offset = sourceElement.getTextOffset();
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		P3ClassDeclaration currentClass = currentVirtualFile != null
				? classIndex.findClassAtOffset(currentVirtualFile, offset)
				: null;
		String currentClassName = currentClass != null ? currentClass.getName() : "MAIN";

		// Сначала ищем метод
		P3MethodIndex methodIndex = P3MethodIndex.getInstance(project);
		List<P3MethodDeclaration> allMethodDeclarations = methodIndex.findInFiles(name, visibleFiles);

		// Если мы внутри именованного класса (не MAIN) — ищем метод по иерархии как ^self.method[]
		if (currentClass != null && !"MAIN".equals(currentClassName)) {
			// Идём по иерархии: текущий класс -> @BASE -> ...
			P3ClassDeclaration current = currentClass;
			Set<String> visited = new HashSet<>();

			while (current != null && visited.add(current.getName())) {
				// Ищем метод в текущем классе иерархии
				for (P3MethodDeclaration method : allMethodDeclarations) {
					if (isMethodDirectlyInClass(method, current)) {
						// Нашли метод в этом классе — возвращаем только его
						return createMethodTargets(project, Collections.singletonList(method), name);
					}
				}

				// Идём к базовому классу
				String baseName = current.getBaseClassName();
				if (baseName == null || baseName.isEmpty()) {
					break;
				}
				current = classIndex.findLastInFiles(baseName, visibleFiles);
			}

			// Не нашли в иерархии — пробуем MAIN методы
		}

		// Фильтруем методы — только MAIN
		List<P3MethodDeclaration> filteredMethods = new ArrayList<>();

		for (P3MethodDeclaration decl : allMethodDeclarations) {
			String declClassName = findClassNameForMethod(decl, project);
			if ("MAIN".equals(declClassName)) {
				filteredMethods.add(decl);
			}
		}

		if (!filteredMethods.isEmpty()) {
			// Сортируем по приоритету видимости: текущий файл первым, затем по порядку в visibleFiles
			VirtualFile finalCurrentVirtualFile = currentVirtualFile;
			filteredMethods.sort((a, b) -> {
				// Текущий файл всегда первый
				boolean aIsCurrent = a.getFile().equals(finalCurrentVirtualFile);
				boolean bIsCurrent = b.getFile().equals(finalCurrentVirtualFile);
				if (aIsCurrent && !bIsCurrent) return -1;
				if (!aIsCurrent && bIsCurrent) return 1;

				// Затем по порядку в visibleFiles (чем раньше — тем ближе по видимости)
				int aIndex = visibleFiles.indexOf(a.getFile());
				int bIndex = visibleFiles.indexOf(b.getFile());

				// Если файл не найден в списке — он в конце
				if (aIndex == -1) aIndex = Integer.MAX_VALUE;
				if (bIndex == -1) bIndex = Integer.MAX_VALUE;

				return Integer.compare(aIndex, bIndex);
			});

			return createMethodTargets(project, filteredMethods, name);
		}

		// Методы не найдены - пробуем как класс
		P3ClassDeclaration classDecl = classIndex.findLastInFiles(name, visibleFiles);

		if (classDecl != null) {
			return navigateToClass(project, classDecl);
		}

		return null;
	}

	/**
	 * Определяет имя класса, в котором находится элемент.
	 * Возвращает "MAIN" если элемент не внутри именованного класса.
	 */
	private @NotNull String findContainingClassName(@NotNull PsiElement element) {
		PsiFile file = element.getContainingFile();
		if (file == null || file.getVirtualFile() == null) {
			return "MAIN";
		}

		P3ClassIndex classIndex = P3ClassIndex.getInstance(element.getProject());
		P3ClassDeclaration classDecl = classIndex.findClassAtOffset(file.getVirtualFile(), element.getTextOffset());

		return classDecl != null ? classDecl.getName() : "MAIN";
	}

	/**
	 * Определяет имя класса, которому принадлежит метод.
	 */
	private @NotNull String findClassNameForMethod(@NotNull P3MethodDeclaration method, @NotNull Project project) {
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		P3ClassDeclaration classDecl = classIndex.findClassAtOffset(method.getFile(), method.getOffset());

		return classDecl != null ? classDecl.getName() : "MAIN";
	}

	/**
	 * Навигация по hash key chain: $value.key4.list → клик на "key4" или "list".
	 * Ходит назад по DOT/VARIABLE до $root, строит цепочку, ищет HashEntryInfo с offset.
	 */
	private PsiElement @Nullable [] handleHashKeyNavigation(@NotNull PsiElement clickedElement) {
		String clickedName = clickedElement.getText();
		if (clickedName == null || clickedName.isEmpty()) return null;
		if (DEBUG) System.out.println("[handleHashKeyNav] clickedName=" + clickedName + " type=" + clickedElement.getNode().getElementType());

		PsiElement searchFrom = clickedElement;
		PsiElement parent = clickedElement.getParent();
		if (parent != null && clickedElement.getNextSibling() == null && clickedElement.getPrevSibling() == null) {
			searchFrom = parent;
		}
		if (DEBUG) System.out.println("[handleHashKeyNav] searchFrom=" + searchFrom.getText() + " type=" + searchFrom.getNode().getElementType());

		// Собираем цепочку сегментов назад: list → key4 → value → $
		java.util.List<String> segments = new java.util.ArrayList<>();
		segments.add(clickedName);

		String prefix = null; // self. или MAIN:
		PsiElement cur = searchFrom;
		int chainLookupOffset = -1;

		while (true) {
			PsiElement prev = cur.getPrevSibling();
			if (prev == null || prev.getNode() == null) { if (DEBUG) System.out.println("[handleHashKeyNav] prev=null, return null"); return null; }

			IElementType prevType = prev.getNode().getElementType();
			if (DEBUG) System.out.println("[handleHashKeyNav] prevType=" + prevType + " prev='" + prev.getText() + "'");
			if (prevType != Parser3TokenTypes.DOT) { if (DEBUG) System.out.println("[handleHashKeyNav] not DOT, return null"); return null; }

			PsiElement beforeDot = prev.getPrevSibling();
			if (beforeDot == null || beforeDot.getNode() == null) { if (DEBUG) System.out.println("[handleHashKeyNav] beforeDot=null, return null"); return null; }

			// Разворачиваем wrapper (VARIABLE внутри COMPOSITE)
			PsiElement unwrapped = beforeDot;
			if (unwrapped.getFirstChild() != null && unwrapped.getFirstChild() == unwrapped.getLastChild()) {
				unwrapped = unwrapped.getFirstChild();
			}

			IElementType bdType = unwrapped.getNode().getElementType();
			if (DEBUG) System.out.println("[handleHashKeyNav] bdType=" + bdType + " unwrapped='" + unwrapped.getText() + "'");

			if (bdType == Parser3TokenTypes.VARIABLE || bdType == Parser3TokenTypes.NUMBER) {
				segments.add(unwrapped.getText());
				cur = beforeDot;
				// Проверяем: что стоит перед этим VARIABLE/NUMBER?
				PsiElement beforeVar = beforeDot.getPrevSibling();
				if (beforeVar != null && beforeVar.getNode() != null) {
					IElementType beforeVarType = beforeVar.getNode().getElementType();
					if (beforeVarType == Parser3TokenTypes.DOLLAR_VARIABLE) {
						// Нашли корень: $root
						chainLookupOffset = beforeVar.getTextOffset();
						break;
					}
					// ${root.key} — перед root стоит LBRACE, перед LBRACE стоит $
					if (beforeVarType == Parser3TokenTypes.LBRACE) {
						PsiElement beforeLbrace = beforeVar.getPrevSibling();
						if (beforeLbrace != null && beforeLbrace.getNode() != null
								&& beforeLbrace.getNode().getElementType() == Parser3TokenTypes.DOLLAR_VARIABLE) {
							chainLookupOffset = beforeLbrace.getTextOffset();
							break;
						}
					}
					// ^MAIN:root.key или $MAIN:root.key — перед root стоит COLON
					if (beforeVarType == Parser3TokenTypes.COLON) {
						PsiElement beforeColon = beforeVar.getPrevSibling();
						if (beforeColon != null && beforeColon.getNode() != null) {
							IElementType bct = beforeColon.getNode().getElementType();
							if (bct == Parser3TokenTypes.IMPORTANT_METHOD || bct == Parser3TokenTypes.METHOD
									|| bct == Parser3TokenTypes.IMPORTANT_VARIABLE) {
								String bcText = beforeColon.getText();
								if (bcText.contains("MAIN")) prefix = "MAIN:";
								else if (bcText.contains("BASE")) prefix = "BASE:";
								break;
							}
						}
					}
					// ^root.key — перед root стоит METHOD токен (^root)
					if (beforeVarType == Parser3TokenTypes.METHOD || beforeVarType == Parser3TokenTypes.IMPORTANT_METHOD) {
						// Это цепочка вида ^root.key — root уже в segments, останавливаемся
						break;
					}
				}
				// Может быть ещё DOT → продолжаем
				continue;
			} else if (bdType == Parser3TokenTypes.IMPORTANT_VARIABLE) {
				String impText = unwrapped.getText();
				if ("self".equals(impText) || "$self".equals(impText)) {
					prefix = "self.";
					// Проверяем $ перед self
					PsiElement beforeSelf = beforeDot.getPrevSibling();
					if (beforeSelf != null && beforeSelf.getNode() != null
							&& beforeSelf.getNode().getElementType() == Parser3TokenTypes.DOLLAR_VARIABLE) {
						break;
					}
					break;
				} else if ("MAIN".equals(impText) || "$MAIN".equals(impText)) {
					prefix = "MAIN:";
					break;
				}
				return null;
			} else if (bdType == Parser3TokenTypes.METHOD) {
				// ^root.key — ^root это METHOD токен, текст начинается с ^
				String methodText = unwrapped.getText();
				if (methodText != null && methodText.startsWith("^")) {
					segments.add(methodText.substring(1)); // без ^
				} else {
					segments.add(methodText != null ? methodText : "");
				}
				break; // METHOD — это всегда корень
			} else {
				return null;
			}
		}

		// Переворачиваем: [list, key4, value] → [value, key4, list]
		java.util.Collections.reverse(segments);
		if (DEBUG) System.out.println("[handleHashKeyNav] segments=" + segments);
		if (segments.size() < 2) { if (DEBUG) System.out.println("[handleHashKeyNav] segments<2, return null"); return null; }

		// Строим varKey: "value.key4.list" (цепочка до кликнутого сегмента включительно)
		String rootVarName = (prefix != null ? prefix : "") + segments.get(0);
		StringBuilder chainBuilder = new StringBuilder(rootVarName);
		for (int i = 1; i < segments.size(); i++) {
			chainBuilder.append('.').append(segments.get(i));
		}
		String fullChain = chainBuilder.toString();

		if (DEBUG) System.out.println("[handleHashKeyNav] chain=" + fullChain + " clicked=" + clickedName);

		PsiFile file = clickedElement.getContainingFile();
		if (file == null || file.getVirtualFile() == null) return null;

		Project project = clickedElement.getProject();
		VirtualFile currentVFile = file.getVirtualFile();

		ru.artlebedev.parser3.index.P3VariableIndex varIndex =
				ru.artlebedev.parser3.index.P3VariableIndex.getInstance(project);

		// Ищем HashEntryInfo для кликнутого сегмента через единый chain-resolve
		int lookupOffset = isHashKeyAssignmentDefinition(clickedElement) && chainLookupOffset >= 0
				? chainLookupOffset
				: clickedElement.getTextOffset();
		java.util.List<VirtualFile> visibleFiles = preferFile(
				getVisibleFiles(project, currentVFile, lookupOffset),
				currentVFile
		);
		ru.artlebedev.parser3.index.P3VariableIndex.ChainResolveInfo chainInfo =
				varIndex.resolveEffectiveChain(fullChain, visibleFiles, currentVFile, lookupOffset);
		ru.artlebedev.parser3.index.HashEntryInfo entry = chainInfo != null ? chainInfo.hashEntry : null;

		if (DEBUG) System.out.println("[handleHashKeyNav] entry=" + entry + (entry != null ? " offset=" + entry.offset : ""));
		if (entry != null && entry.offset >= 0) {
			VirtualFile targetVFile = entry.file != null
					? entry.file
					: chainInfo != null && chainInfo.rootVariable != null
							? chainInfo.rootVariable.file
							: currentVFile;
			PsiElement targetElement = findHashEntryTargetElement(
					project,
					targetVFile,
					currentVFile,
					visibleFiles,
					entry.offset,
					clickedName
			);
			if (DEBUG) System.out.println("[handleHashKeyNav] targetElement=" + targetElement);
			if (targetElement != null) {
				PsiFile targetPsiFile = targetElement.getContainingFile();
				VirtualFile actualTargetFile = targetPsiFile != null && targetPsiFile.getVirtualFile() != null
						? targetPsiFile.getVirtualFile()
						: targetVFile;
				return new PsiElement[]{P3NavigationTargets.createVariableTarget(targetElement, actualTargetFile, entry.offset)};
			}
		}

		PsiElement explicitChainTarget = findExplicitHashChainTargetElement(
				project,
				currentVFile,
				visibleFiles,
				fullChain,
				clickedName,
				clickedElement.getTextOffset()
		);
		if (DEBUG) System.out.println("[handleHashKeyNav] explicit chain target=" + explicitChainTarget);
		if (explicitChainTarget != null) {
			PsiFile targetPsiFile = explicitChainTarget.getContainingFile();
			VirtualFile targetVFile = targetPsiFile != null ? targetPsiFile.getVirtualFile() : currentVFile;
			return new PsiElement[]{P3NavigationTargets.createVariableTarget(
					explicitChainTarget,
					targetVFile != null ? targetVFile : currentVFile,
					explicitChainTarget.getTextOffset()
			)};
		}

		if (entry != null) {
			PsiElement readChainTarget = findSyntheticReadChainTargetElement(
					project,
					currentVFile,
					visibleFiles,
					fullChain,
					clickedName,
					clickedElement.getTextOffset()
			);
			if (DEBUG) System.out.println("[handleHashKeyNav] synthetic read-chain target=" + readChainTarget);
			if (readChainTarget != null) {
				PsiFile targetPsiFile = readChainTarget.getContainingFile();
				VirtualFile targetVFile = targetPsiFile != null ? targetPsiFile.getVirtualFile() : currentVFile;
				return new PsiElement[]{P3NavigationTargets.createVariableTarget(
						readChainTarget,
						targetVFile != null ? targetVFile : currentVFile,
						readChainTarget.getTextOffset()
				)};
			}
		}

		if (DEBUG) System.out.println("[handleHashKeyNav] not found, return null");
		return null;
	}

	private boolean isHashKeyAssignmentDefinition(@NotNull PsiElement clickedElement) {
		PsiElement next = clickedElement.getNextSibling();
		while (next != null && next.getText() != null && next.getText().trim().isEmpty()) {
			next = next.getNextSibling();
		}
		return next != null
				&& next.getNode() != null
				&& next.getNode().getElementType() == Parser3TokenTypes.LBRACKET;
	}

	private static @NotNull List<VirtualFile> preferFile(
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull VirtualFile preferredFile
	) {
		if (visibleFiles.isEmpty() || preferredFile.equals(visibleFiles.get(0)) || !visibleFiles.contains(preferredFile)) {
			return visibleFiles;
		}
		ArrayList<VirtualFile> result = new ArrayList<>(visibleFiles.size());
		result.add(preferredFile);
		for (VirtualFile file : visibleFiles) {
			if (!file.equals(preferredFile)) result.add(file);
		}
		return result;
	}

	private @Nullable PsiElement findExplicitHashChainTargetElement(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull String fullChain,
			@NotNull String clickedName,
			int clickedOffset
	) {
		String textPattern = "$" + fullChain;
		PsiManager psiManager = PsiManager.getInstance(project);

		PsiElement currentFileTarget = findExplicitHashChainTargetInFile(
				psiManager,
				currentFile,
				textPattern,
				clickedName,
				clickedOffset
		);
		if (currentFileTarget != null) return currentFileTarget;

		for (VirtualFile file : visibleFiles) {
			if (file.equals(currentFile) || !file.isValid()) continue;
			PsiElement target = findExplicitHashChainTargetInFile(
					psiManager,
					file,
					textPattern,
					clickedName,
					Integer.MAX_VALUE
			);
			if (target != null) return target;
		}

		return null;
	}

	private @Nullable PsiElement findExplicitHashChainTargetInFile(
			@NotNull PsiManager psiManager,
			@NotNull VirtualFile file,
			@NotNull String textPattern,
			@NotNull String clickedName,
			int maxTargetOffset
	) {
		PsiFile psiFile = psiManager.findFile(file);
		if (psiFile == null) return null;
		String text = psiFile.getText();
		int from = 0;
		int bestTargetOffset = -1;
		while (from < text.length()) {
			int occurrence = text.indexOf(textPattern, from);
			if (occurrence < 0) break;
			int occurrenceEnd = occurrence + textPattern.length();
			int targetOffset = occurrenceEnd - clickedName.length();
			if (targetOffset >= occurrence
					&& targetOffset < maxTargetOffset
					&& isExplicitHashChainAssignment(text, occurrence, occurrenceEnd)) {
				bestTargetOffset = targetOffset;
			}
			from = occurrence + 1;
		}
		if (bestTargetOffset < 0) return null;
		return psiFile.findElementAt(bestTargetOffset);
	}

	private boolean isExplicitHashChainAssignment(
			@NotNull String text,
			int occurrence,
			int occurrenceEnd
	) {
		if (occurrence < 0 || occurrenceEnd > text.length()) return false;
		if (ru.artlebedev.parser3.lexer.Parser3LexerUtils.isEscapedByCaret(text, occurrence)) return false;
		if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, occurrence)) return false;
		int pos = occurrenceEnd;
		while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
		if (pos >= text.length()) return false;
		char nextChar = text.charAt(pos);
		return nextChar == '[' || nextChar == '(' || nextChar == '{';
	}

	private @Nullable PsiElement findSyntheticReadChainTargetElement(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			@NotNull List<VirtualFile> visibleFiles,
			@NotNull String fullChain,
			@NotNull String clickedName,
			int clickedOffset
	) {
		String textPattern = "$" + fullChain;
		PsiManager psiManager = PsiManager.getInstance(project);

		PsiElement currentFileTarget = findSyntheticReadChainTargetInFile(
				psiManager,
				currentFile,
				textPattern,
				clickedName,
				clickedOffset
		);
		if (currentFileTarget != null) return currentFileTarget;

		for (VirtualFile file : visibleFiles) {
			if (file.equals(currentFile) || !file.isValid()) continue;
			PsiElement target = findSyntheticReadChainTargetInFile(
					psiManager,
					file,
					textPattern,
					clickedName,
					Integer.MAX_VALUE
			);
			if (target != null) return target;
		}

		return findSyntheticReadChainTargetInFile(
				psiManager,
				currentFile,
				textPattern,
				clickedName,
				Integer.MAX_VALUE
		);
	}

	private @Nullable PsiElement findSyntheticReadChainTargetInFile(
			@NotNull PsiManager psiManager,
			@NotNull VirtualFile file,
			@NotNull String textPattern,
			@NotNull String clickedName,
			int maxTargetOffset
	) {
		PsiFile psiFile = psiManager.findFile(file);
		if (psiFile == null) return null;
		String text = psiFile.getText();
		int from = 0;
		int bestTargetOffset = -1;
		while (from < text.length()) {
			int occurrence = text.indexOf(textPattern, from);
			if (occurrence < 0) break;
			int targetOffset = occurrence + textPattern.length() - clickedName.length();
			int occurrenceEnd = occurrence + textPattern.length();
			if (targetOffset >= occurrence
					&& targetOffset < text.length()
					&& targetOffset < maxTargetOffset
					&& isReadChainOccurrence(text, occurrence, occurrenceEnd)) {
				bestTargetOffset = targetOffset;
			}
			from = occurrence + 1;
		}
		if (bestTargetOffset < 0) return null;
		return psiFile.findElementAt(bestTargetOffset);
	}

	private boolean isReadChainOccurrence(
			@NotNull String text,
			int occurrence,
			int occurrenceEnd
	) {
		if (occurrence < 0 || occurrenceEnd > text.length()) return false;
		if (ru.artlebedev.parser3.lexer.Parser3LexerUtils.isEscapedByCaret(text, occurrence)) return false;
		if (ru.artlebedev.parser3.utils.Parser3ClassUtils.isOffsetInComment(text, occurrence)) return false;
		if (occurrenceEnd >= text.length()) return false;
		char nextChar = text.charAt(occurrenceEnd);
		return ru.artlebedev.parser3.utils.Parser3VariableTailUtils.isTailStopChar(nextChar);
	}

	private @Nullable PsiElement findHashEntryTargetElement(
			@NotNull Project project,
			@Nullable VirtualFile preferredFile,
			@NotNull VirtualFile currentFile,
			@NotNull List<VirtualFile> visibleFiles,
			int offset,
			@NotNull String clickedName
	) {
		PsiManager psiManager = PsiManager.getInstance(project);
		java.util.LinkedHashSet<VirtualFile> candidateFiles = new java.util.LinkedHashSet<>();
		if (preferredFile != null) candidateFiles.add(preferredFile);
		candidateFiles.add(currentFile);
		candidateFiles.addAll(visibleFiles);

		PsiElement fallback = null;
		for (VirtualFile candidateFile : candidateFiles) {
			PsiFile candidatePsiFile = psiManager.findFile(candidateFile);
			if (candidatePsiFile == null) continue;
			if (offset < 0 || offset >= candidatePsiFile.getTextLength()) continue;

			PsiElement element = candidatePsiFile.findElementAt(offset);
			if (element == null) continue;
			if (hashEntryTargetMatches(element, clickedName)) {
				return element;
			}
			if (fallback == null && candidateFile.equals(preferredFile)) {
				fallback = element;
			}
		}
		return fallback;
	}

	private boolean hashEntryTargetMatches(@NotNull PsiElement element, @NotNull String clickedName) {
		String text = element.getText();
		if (clickedName.equals(text)) return true;

		PsiElement parent = element.getParent();
		return parent != null && clickedName.equals(parent.getText());
	}

	/**
	 * Получает список видимых файлов в зависимости от настройки проекта.
	 */
	private @NotNull List<VirtualFile> getVisibleFiles(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		return new P3ScopeContext(project, currentFile, cursorOffset).getMethodSearchFiles();
	}

	/**
	 * Получает список видимых файлов для поиска классов.
	 * Если @autouse виден из текущего файла — все классы видны.
	 */
	private @NotNull List<VirtualFile> getVisibleFilesForClasses(
			@NotNull Project project,
			@NotNull VirtualFile currentFile,
			int cursorOffset
	) {
		return new P3ScopeContext(project, currentFile, cursorOffset).getClassSearchFiles();
	}

	/**
	 * Получает список файлов для поиска ИСПОЛЬЗОВАНИЙ класса (обратная видимость).
	 *
	 * При клике на @CLASS Logger нужно найти все файлы, которые ВИДЯТ Logger.p:
	 * 1. Файлы с @autouse — видят ВСЕ классы проекта
	 * 2. Файлы без @autouse — видят классы только через @USE или CLASS_PATH
	 *
	 * @param project проект
	 * @param currentFile файл с объявлением класса
	 * @return список файлов, из которых виден класс
	 */
	private @NotNull List<VirtualFile> getVisibleFilesForClassDeclaration(
			@NotNull Project project,
			@NotNull VirtualFile currentFile
	) {
		return P3ScopeContext.getReverseClassSearchFiles(project, currentFile);
	}

	/**
	 * Создаёт navigation targets для методов
	 */
	private PsiElement @Nullable [] createMethodTargets(
			@NotNull Project project,
			@NotNull List<P3MethodDeclaration> declarations,
			@NotNull String methodName
	) {
		List<PsiElement> targets = new ArrayList<>();
		PsiManager psiManager = PsiManager.getInstance(project);

		for (P3MethodDeclaration decl : declarations) {
			PsiFile declFile = psiManager.findFile(decl.getFile());

			if (declFile != null) {
				PsiElement target = declFile.findElementAt(decl.getOffset());

				if (target != null) {
					targets.add(new P3NavigationTargets.MethodTarget(target, decl, methodName));
				}
			}
		}

		if (targets.isEmpty()) {
			return null;
		}

		return targets.toArray(new PsiElement[0]);
	}

	/**
	 * Создаёт navigation target для класса
	 */
	private PsiElement @Nullable [] navigateToClass(
			@NotNull Project project,
			@NotNull P3ClassDeclaration classDecl
	) {
		PsiManager psiManager = PsiManager.getInstance(project);
		PsiFile classFile = psiManager.findFile(classDecl.getFile());

		if (classFile == null) {
			return null;
		}

		// Если есть PSI element для @CLASS - используем его offset
		// Иначе используем startOffset класса
		int targetOffset = classDecl.getStartOffset();
		PsiElement target = classFile.findElementAt(targetOffset);

		if (target != null) {
			return new PsiElement[]{new P3NavigationTargets.ClassTarget(target, classDecl)};
		}

		// Fallback - возвращаем весь файл
		return new PsiElement[]{classFile};
	}
}
