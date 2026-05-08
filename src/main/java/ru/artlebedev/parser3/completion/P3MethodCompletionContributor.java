package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3FileType;
import ru.artlebedev.parser3.classpath.ClassPathResult;
import ru.artlebedev.parser3.classpath.P3ClassPathEvaluator;
import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.index.P3MethodIndex;
import ru.artlebedev.parser3.index.P3VariableIndex;
import ru.artlebedev.parser3.model.P3MethodCallType;
import ru.artlebedev.parser3.model.P3MethodDeclaration;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;
import ru.artlebedev.parser3.utils.Parser3BuiltinStaticPropertyUsageUtils;
import ru.artlebedev.parser3.utils.Parser3ChainUtils;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;
import ru.artlebedev.parser3.visibility.P3ScopeContext;

import java.util.*;

/**
 * Provides completion for:
 * - User-defined methods (after ^)
 * - File paths (in ^use[...])
 */
public final class P3MethodCompletionContributor extends CompletionContributor {

	private static final boolean DEBUG = false;
	private static final boolean DEBUG_BUILTIN = false;

	private static final class CompletionTextContext {
		private final @NotNull CharSequence text;
		private final int offset;

		private CompletionTextContext(@NotNull CharSequence text, int offset) {
			this.text = text;
			this.offset = offset;
		}
	}

	public P3MethodCompletionContributor() {
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

						PsiFile parser3File = findParser3HostFile(file);
						if (parser3File == null) {
							return;
						}

						// Регистронезависимый автокомплит для Parser3
						result = P3CompletionUtils.makeCaseInsensitive(result);

						if (addHashDeleteKeyCompletions(parameters, result, parser3File)) {
							return;
						}

						String completionContext = detectContext(parameters);
						if (DEBUG_BUILTIN) System.out.println("[P3MethodCompl] detectContext='" + completionContext + "'");

						// File completion теперь обрабатывается в P3UseCompletionContributor
						if (completionContext.equals("method")) {
							addUserDefinedMethods(parameters, result, parser3File);
						} else if (completionContext.equals("directive")) {
							addDirectiveCompletions(parameters, result);
						}
					}
				}
		);
	}

	/**
	 * Определяет контекст completion: "use", "method" или "none"
	 */
	private String detectContext(@NotNull CompletionParameters parameters) {
		CompletionTextContext textContext = getCompletionTextContext(parameters);
		CharSequence text = textContext.text;
		int offset = textContext.offset;
		int textLength = text.length();


		// Защита от выхода за границы
		if (offset <= 0) {
			return "none";
		}

		// Сначала проверяем @ в начале строки — директива
		boolean isDirective = isAtDirectiveStart(text, offset);
		if (isDirective) {
			return "directive";
		}

		// Сначала проверяем @USE (может быть на предыдущих строках)
		if (isAfterAtUseDirective(text, offset)) {
			return "use";
		}

		// Ищем назад на текущей строке
		int searchLimit = Math.max(0, offset - 50);

		// Отслеживаем глубину скобок при обратном проходе:
		// ] / ) / } увеличивает глубину, [ / ( / { уменьшает.
		// Пробел или ^ при глубине 0 означают что мы не внутри вызова.
		int bracketDepth = 0;
		for (int i = offset - 1; i >= searchLimit; i--) {
			if (i >= textLength) continue;

			char ch = text.charAt(i);

			if (ch == '\n' || ch == '\r') {
				break;
			}

			// Обратный проход: ] открывает скобку, [ закрывает
			if (ch == ']' || ch == ')' || ch == '}') {
				bracketDepth++;
				continue;
			}
			if (ch == '[' || ch == '(' || ch == '{') {
				if (bracketDepth > 0) {
					bracketDepth--;
					// Проверяем ^use[ при выходе из скобки
					if (ch == '[' && i >= 4) {
						int start = Math.max(0, i - 4);
						if (i + 1 <= textLength) {
							String before = text.subSequence(start, i + 1).toString();
							if (before.endsWith("^use[")) {
								return "use";
							}
						}
					}
					continue;
				} else {
					// Открывающая скобка при depth=0 — мы внутри чьего-то аргумента
					// Проверяем ^use[
					if (ch == '[' && i >= 4) {
						int start = Math.max(0, i - 4);
						if (i + 1 <= textLength) {
							String before = text.subSequence(start, i + 1).toString();
							if (before.endsWith("^use[")) {
								return "use";
							}
						}
					}
					break;
				}
			}

			if (bracketDepth == 0 && P3CompletionUtils.isMethodPrefixStopChar(ch)) {
				break;
			}

			// Просто ^ при глубине 0 — метод
			if (ch == '^' && bracketDepth == 0) {
				return "method";
			}
		}

		return "none";
	}

	/**
	 * Проверяет что курсор находится после @ в начале строки (колонка 0).
	 * @main[], @auto[], @conf[], @USE, @BASE, @OPTIONS, @CLASS
	 */
	private boolean isAtDirectiveStart(CharSequence text, int offset) {
		// Ищем @ назад на текущей строке
		for (int i = offset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == '\n' || ch == '\r') {
				return false;
			}
			if (ch == '@') {
				return i == 0 || text.charAt(i - 1) == '\n' || text.charAt(i - 1) == '\r';
			}
			if (!Character.isLetter(ch) && ch != '_') {
				return false;
			}
		}
		return false;
	}

	/**
	 * Добавляет автокомплит директив: @main[], @auto[], @conf[], @USE, @BASE, @OPTIONS, @CLASS
	 */
	private void addDirectiveCompletions(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result
	) {
		// Извлекаем prefix: текст после @ до курсора
		CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
		int offset = clampOffset(text, parameters.getOffset());
		int atPos = -1;
		for (int i = offset - 1; i >= 0; i--) {
			if (text.charAt(i) == '@') {
				atPos = i;
				break;
			}
		}
		if (atPos < 0) return;

		String prefix = text.subSequence(atPos + 1, offset).toString();
		CompletionResultSet dirResult = result.withPrefixMatcher(prefix);

		// Методы-точки входа: вставляют "main[]\n" и ставят курсор на новую строку
		String[] methods = {"main[]", "auto[]"};
		for (String m : methods) {
			LookupElementBuilder el = LookupElementBuilder
					.create(m)
					.withIcon(Parser3Icons.FileMethod)
					.withInsertHandler((ctx, item) -> {
						com.intellij.openapi.editor.Editor editor = ctx.getEditor();
						Document doc = ctx.getDocument();
						int tail = ctx.getTailOffset();
						doc.insertString(tail, "\n");
						editor.getCaretModel().moveToOffset(tail + 1);
					});
			dirResult.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(el, 100));
		}

		// @conf[filespec]\n — с параметром filespec
		{
			LookupElementBuilder el = LookupElementBuilder
					.create("conf[filespec]")
					.withIcon(Parser3Icons.FileMethod)
					.withTypeText("Конфигурация (выполняется перед @auto)", true)
					.withInsertHandler((ctx, item) -> {
						com.intellij.openapi.editor.Editor editor = ctx.getEditor();
						Document doc = ctx.getDocument();
						int tail = ctx.getTailOffset();
						doc.insertString(tail, "\n");
						editor.getCaretModel().moveToOffset(tail + 1);
					});
			dirResult.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(el, 100));
		}

		// Директивы: вставляют "USE\n" и ставят курсор на новую строку
		String[][] directives = {
				{"USE", "Подключение файлов"},
				{"BASE", "Базовый класс"},
				{"OPTIONS", "Настройки (locals, partial, ...)"},
				{"CLASS", "Объявление класса"},
		};
		for (String[] d : directives) {
			LookupElementBuilder el = LookupElementBuilder
					.create(d[0])
					.withIcon(Parser3Icons.FileMethod)
					.withTypeText(d[1], true)
					.withInsertHandler((ctx, item) -> {
						com.intellij.openapi.editor.Editor editor = ctx.getEditor();
						Document doc = ctx.getDocument();
						int tail = ctx.getTailOffset();
						doc.insertString(tail, "\n");
						editor.getCaretModel().moveToOffset(tail + 1);
					});
			dirResult.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(el, 95));
		}
	}

	/**
	 * Проверяет находимся ли мы после @USE директивы
	 */
	private boolean isAfterAtUseDirective(CharSequence text, int offset) {
		// Защита от выхода за границы
		int textLength = text.length();
		if (offset <= 0 || offset > textLength) {
			return false;
		}

		// Ищем @USE назад (до 500 символов)
		int searchStart = Math.max(0, offset - 500);
		int searchEnd = Math.min(offset, textLength);

		if (searchStart >= searchEnd) {
			return false;
		}

		String before = text.subSequence(searchStart, searchEnd).toString();

		int usePos = before.lastIndexOf("@USE");
		if (usePos == -1) {
			return false;
		}

		// Проверяем что между @USE и курсором нет других директив
		String between = before.substring(usePos + 4); // 4 = "@USE".length()

		// Если есть @, ^{ или ^[ - мы уже не в @USE контексте
		if (between.contains("@") || between.contains("^{") || between.contains("^[")) {
			return false;
		}

		return true;
	}

	@Override
	public boolean invokeAutoPopup(@NotNull com.intellij.psi.PsiElement position, char typeChar) {

		PsiFile file = position.getContainingFile();
		if (file == null || !Parser3PsiUtils.isParser3File(file) || Parser3PsiUtils.isInjectedFragment(file)) {
			return false;
		}

		CharSequence text = file.getViewProvider().getContents();
		// position - это элемент ПЕРЕД курсором, поэтому берём его конец + 1 (для нового символа)
		int offset = position.getTextRange().getEndOffset() + 1;
		// Но offset может выйти за границы текста при вставке
		if (offset > text.length()) {
			offset = text.length();
		}


		// 1. Show popup after ^ for method completion
		if (typeChar == '^') {
			return true;
		}

		// 1b. Show popup after @ at column 0 for directive completion
		if (typeChar == '@') {
			int insertPos = offset - 1;
			if (insertPos <= 0 || text.charAt(insertPos - 1) == '\n' || text.charAt(insertPos - 1) == '\r') {
				return true;
			}
		}

		// 2. Show popup for letters/digits after ^ (method name typing)
		if (Character.isLetterOrDigit(typeChar) || typeChar == '_') {
			int searchStart = Math.max(0, offset - 50);
			String before = text.subSequence(searchStart, offset).toString();

			// Ищем ^ или @ на текущей строке
			for (int i = before.length() - 1; i >= 0; i--) {
				char ch = before.charAt(i);
				if (ch == '^') {
					// Проверяем что это не внутри ^use[...]
					String afterCaret = before.substring(i);
					if (!afterCaret.contains("use[") || afterCaret.contains("]")) {
						return true;
					}
				}
				if (ch == '@') {
					// @ в начале строки — директива
					int absPos = searchStart + i;
					if (absPos == 0 || text.charAt(absPos - 1) == '\n' || text.charAt(absPos - 1) == '\r') {
						return true;
					}
				}
				if (ch == '[') {
					// @method[][locals] — вторая скобка
					int absPos = searchStart + i;
					if (absPos > 0 && text.charAt(absPos - 1) == ']') {
						return true;
					}
					break;
				}
				if (ch == '\n' || ch == '\r') {
					break;
				}
			}
		}

		// 2b. Открываем popup для ключей хеша внутри ^hash.delete[...]
		if (Character.isLetterOrDigit(typeChar) || typeChar == '_' || typeChar == '-' || typeChar == '[' || typeChar == '(' || typeChar == '{') {
			if (P3CompletionUtils.isHashDeleteKeyContext(text, offset)) {
				return true;
			}
		}

		// 3. Show popup after . in ^self. context
		if (typeChar == '.') {
			return isAfterMethodReceiverDot(text, offset);
		}

		// 4. Для : НЕ создаём новый popup — пусть существующий живёт и обновляется
		// CompletionConfidence теперь считает : частью идентификатора

		// 5. Show popup for any character inside ^use[...]
		if (Character.isLetterOrDigit(typeChar) || typeChar == '/' || typeChar == '.' || typeChar == '_' || typeChar == '-') {
			int searchStart = Math.max(0, offset - 100);
			String before = text.subSequence(searchStart, offset).toString();

			int usePos = before.lastIndexOf("^use[");
			if (usePos != -1) {
				String between = before.substring(usePos);
				if (!between.contains("]")) {
					return true;
				}
			}
		}

		// 6. Show popup for any character inside @USE block
		if (Character.isLetterOrDigit(typeChar) || typeChar == '/' || typeChar == '.' || typeChar == '_' || typeChar == '-') {
			int searchStart = Math.max(0, offset - 500);
			String before = text.subSequence(searchStart, offset).toString();

			int atUsePos = before.lastIndexOf("@USE");
			if (atUsePos != -1) {
				String between = before.substring(atUsePos + 4); // 4 = "@USE".length()
				// Между @USE и курсором не должно быть других директив (@)
				if (!between.contains("@")) {
					return true;
				}
			}
		}

		// 7. Show popup for letters after @BASE (class name completion)
		if (Character.isLetterOrDigit(typeChar) || typeChar == '_') {
			int searchStart = Math.max(0, offset - 100);
			String before = text.subSequence(searchStart, offset).toString();

			int basePos = before.lastIndexOf("@BASE");
			if (basePos != -1) {
				String between = before.substring(basePos + 5); // 5 = "@BASE".length()
				// Между @BASE и курсором не должно быть директив или методов
				if (!between.contains("@") && !between.contains("^")) {
					return true;
				}
			}
		}

		return false;
	}

	private static boolean isAfterMethodReceiverDot(@NotNull CharSequence text, int offset) {
		int safeOffset = Math.min(Math.max(offset, 0), text.length());
		int bracketDepth = 0;
		for (int i = safeOffset - 2; i >= Math.max(0, safeOffset - 50); i--) {
			char ch = text.charAt(i);
			if (ch == ']' || ch == ')' || ch == '}') {
				bracketDepth++;
				continue;
			}
			if (ch == '[' || ch == '(' || ch == '{') {
				bracketDepth--;
				if (bracketDepth < 0) {
					return false;
				}
				continue;
			}
			if (bracketDepth == 0 && ch == '^') {
				return true;
			}
			if (bracketDepth == 0 && P3CompletionUtils.isMethodPrefixStopChar(ch)) {
				return false;
			}
		}
		return false;
	}

	@Override
	public com.intellij.codeInsight.completion.AutoCompletionDecision handleAutoCompletionPossibility(
			@NotNull com.intellij.codeInsight.completion.AutoCompletionContext context
	) {
		// Проверяем что мы в Parser3 файле
		PsiFile file = context.getLookup().getPsiFile();
		if (file == null || !Parser3PsiUtils.isParser3File(file)) {
			return null; // Пусть IntelliJ решает сам
		}

		// Всегда показываем список, никогда не подставляем автоматически
		return com.intellij.codeInsight.completion.AutoCompletionDecision.SHOW_LOOKUP;
	}

	private boolean addHashDeleteKeyCompletions(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file
	) {
		VirtualFile virtualFile = file.getVirtualFile();
		if (virtualFile == null) {
			return false;
		}

		CompletionTextContext textContext = getCompletionTextContext(parameters);
		CharSequence text = textContext.text;
		int cursorOffset = getParser3HostOffset(parameters);
		int textOffset = textContext.offset;
		P3CompletionUtils.HashDeleteKeyContext deleteContext =
				P3CompletionUtils.detectHashDeleteKeyContext(text, textOffset);
		if (deleteContext == null) {
			return false;
		}

		P3VariableIndex variableIndex = P3VariableIndex.getInstance(file.getProject());
		String normalizedReceiver = Parser3ChainUtils.normalizeDynamicSegments(deleteContext.receiverChain);
		P3VariableIndex.ChainResolveInfo chainInfo = variableIndex.resolveEffectiveChain(
				normalizedReceiver,
				new P3ScopeContext(file.getProject(), virtualFile, cursorOffset).getVariableSearchFiles(),
				virtualFile,
				cursorOffset
		);
		java.util.Map<String, ru.artlebedev.parser3.index.HashEntryInfo> hashKeys =
				chainInfo != null ? chainInfo.hashKeys : null;
		if (hashKeys == null || hashKeys.isEmpty()) {
			return true;
		}

		hashKeys = variableIndex.enrichWithAliasKeys(hashKeys, normalizedReceiver, virtualFile, cursorOffset);
		CompletionResultSet keyResult = result.withPrefixMatcher(deleteContext.prefix);
		P3VariableMethodCompletionContributor.addHashDeleteKeys(hashKeys, keyResult);
		return true;
	}

	private void addUserDefinedMethods(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file
	) {
		VirtualFile virtualFile = file.getVirtualFile();
		if (virtualFile == null) {
			return;
		}
		int cursorOffset = getParser3HostOffset(parameters);

		String prefix = extractMethodPrefix(parameters);

		if (DEBUG) System.out.println("[P3MethodCompl] prefix=" + prefix);

		// Определяем контекст вызова
		String contextType = "normal"; // normal, self, MAIN, BASE, ClassName
		String targetClassName = null;
		String cleanPrefix = prefix;

		if (cleanPrefix.startsWith("self.")) {
			contextType = "self";
			cleanPrefix = cleanPrefix.substring(5);
			// ^self.var.method — обрабатываем через единую функцию
			if (cleanPrefix.contains(".")) {
				int dotPos = Parser3ChainUtils.findLastTopLevelDot(cleanPrefix);
				if (dotPos < 0) return;
				String varKey = "self." + cleanPrefix.substring(0, dotPos);
				String methodPrefix = cleanPrefix.substring(dotPos + 1);
				if (DEBUG) System.out.println("[P3MethodCompl] ^self.var. → varKey=" + varKey + " methodPrefix=" + methodPrefix);
				CompletionResultSet dotResult = result.withPrefixMatcher(methodPrefix);
				P3VariableMethodCompletionContributor.completeVariableDot(
						file.getProject(), varKey, virtualFile, cursorOffset, dotResult, true);
				return;
			}
		} else if (cleanPrefix.startsWith("MAIN:")) {
			contextType = "MAIN";
			cleanPrefix = cleanPrefix.substring(5);
			// ^MAIN:var.method — обрабатываем через единую функцию
			if (cleanPrefix.contains(".")) {
				int dotPos = Parser3ChainUtils.findLastTopLevelDot(cleanPrefix);
				if (dotPos < 0) return;
				String varKey = "MAIN:" + cleanPrefix.substring(0, dotPos);
				String methodPrefix = cleanPrefix.substring(dotPos + 1);
				if (DEBUG) System.out.println("[P3MethodCompl] ^MAIN:var. → varKey=" + varKey + " methodPrefix=" + methodPrefix);
				CompletionResultSet dotResult = result.withPrefixMatcher(methodPrefix);
				P3VariableMethodCompletionContributor.completeVariableDot(
						file.getProject(), varKey, virtualFile, cursorOffset, dotResult, true);
				return;
			}
		} else if (cleanPrefix.startsWith("BASE:")) {
			contextType = "BASE";
			cleanPrefix = cleanPrefix.substring(5);
			// ^BASE:var.method — обрабатываем через единую функцию
			if (cleanPrefix.contains(".")) {
				int dotPos = Parser3ChainUtils.findLastTopLevelDot(cleanPrefix);
				if (dotPos < 0) return;
				String varKey = "BASE:" + cleanPrefix.substring(0, dotPos);
				String methodPrefix = cleanPrefix.substring(dotPos + 1);
				if (DEBUG) System.out.println("[P3MethodCompl] ^BASE:var. → varKey=" + varKey + " methodPrefix=" + methodPrefix);
				CompletionResultSet dotResult = result.withPrefixMatcher(methodPrefix);
				P3VariableMethodCompletionContributor.completeVariableDot(
						file.getProject(), varKey, virtualFile, cursorOffset, dotResult, true);
				return;
			}
		} else if (cleanPrefix.contains(":")) {
			// ^ClassName:method или ^ClassName:field.method
			int colonPos = cleanPrefix.indexOf(':');
			String clsName = cleanPrefix.substring(0, colonPos);
			String afterColon = cleanPrefix.substring(colonPos + 1);

			if (afterColon.contains(".")) {
				// ^ClassName:field.method — обрабатываем как обычную переменную ClassName:field.
				int dotPos = Parser3ChainUtils.findLastTopLevelDot(afterColon);
				if (dotPos < 0) return;
				String methodPrefix = afterColon.substring(dotPos + 1);
				String varKey = clsName + ":" + afterColon.substring(0, dotPos);
				if (DEBUG) System.out.println("[P3MethodCompl] ^Class:field. → varKey=" + varKey + " methodPrefix=" + methodPrefix);
				CompletionResultSet dotResult = result.withPrefixMatcher(methodPrefix);
				P3VariableMethodCompletionContributor.completeVariableDot(
						file.getProject(), varKey, virtualFile, cursorOffset, dotResult, true, null, file.getText());
				return;
			}

			targetClassName = clsName;
			contextType = "class";
			cleanPrefix = afterColon.contains(".") ? afterColon : afterColon;
		} else if (cleanPrefix.contains(".")) {
			// ^varName.method — обрабатываем через единую функцию
			int dotPos = Parser3ChainUtils.findLastTopLevelDot(cleanPrefix);
			if (dotPos < 0) return;
			String varKey = cleanPrefix.substring(0, dotPos);
			String methodPrefix = cleanPrefix.substring(dotPos + 1);
			if (DEBUG) System.out.println("[P3MethodCompl] ^var. → varKey=" + varKey + " methodPrefix=" + methodPrefix);
			CompletionResultSet dotResult = result.withPrefixMatcher(methodPrefix);
			P3VariableMethodCompletionContributor.completeVariableDot(
					file.getProject(), varKey, virtualFile, cursorOffset, dotResult, true);
			return;
		}

		CompletionResultSet resultWithCleanPrefix = result.withPrefixMatcher(cleanPrefix);

		P3ScopeContext scopeContext = new P3ScopeContext(file.getProject(), virtualFile, cursorOffset);
		Set<VirtualFile> visibleFilesSet = scopeContext.isAllMethodsMode()
				? null
				: new HashSet<>(scopeContext.getMethodSearchFiles());
		Set<VirtualFile> classVisibleFilesSet = scopeContext.isAllMethodsMode()
				? null
				: new HashSet<>(scopeContext.getClassSearchFiles());

		ru.artlebedev.parser3.index.P3ClassIndex classIndex = ru.artlebedev.parser3.index.P3ClassIndex.getInstance(file.getProject());

		// Переменные с типами (^varName.) — добавляем с полным prefix
		// PrefixMatcher сам отфильтрует — переменные должны быть в результатах
		// чтобы при вводе букв после ^ они сразу появлялись (без Ctrl+Space)
		if (contextType.equals("normal")) {
			addVariablesWithTypes(result.withPrefixMatcher(prefix), file, virtualFile, cursorOffset, visibleFilesSet, "normal");
		}
		// Встроенные классы с свойствами (form:, env:, request:, etc.) — показываем всегда в normal контексте
		if (contextType.equals("normal")) {
			addScopeReceiverCompletions(resultWithCleanPrefix);
			addBuiltinClassesWithProperties(resultWithCleanPrefix);
		}
		// ^self.varName. и ^MAIN:varName. — переменные тоже доступны (в MAIN self=MAIN, $var=$self.var=$MAIN:var)
		if (contextType.equals("self") || contextType.equals("MAIN")) {
			addVariablesWithTypes(resultWithCleanPrefix, file, virtualFile, cursorOffset, visibleFilesSet, contextType);
		}

		// Обработка контекстов — единый код для обоих режимов
		switch (contextType) {
			case "normal":
				// ^method[] — методы текущего класса + MAIN из видимых файлов
				addNormalContextMethods(parameters, resultWithCleanPrefix, file, virtualFile, classIndex, cursorOffset, visibleFilesSet, classVisibleFilesSet);
				break;

			case "MAIN":
				// ^MAIN:method[] — только MAIN методы
				addMainMethodsFromIndex(resultWithCleanPrefix, file, cursorOffset, visibleFilesSet);
				break;

			case "self":
				// ^self.method[] — зависит от контекста
				ru.artlebedev.parser3.model.P3ClassDeclaration currentClass = classIndex.findClassAtOffset(virtualFile, cursorOffset);
				if (currentClass != null) {
					if (currentClass.isMainClass()) {
						// В MAIN классе ^self. эквивалентен ^MAIN:
						addMainMethodsFromIndex(resultWithCleanPrefix, file, cursorOffset, visibleFilesSet);
					} else {
						// В именованном классе — методы этого класса и его @BASE
						addMethodsFromClassHierarchyFast(currentClass, resultWithCleanPrefix, file, classVisibleFilesSet, P3MethodCallType.DYNAMIC);
					}
				}
				break;

			case "BASE":
				// ^BASE:method[] — методы базового класса
				ru.artlebedev.parser3.model.P3ClassDeclaration currentForBase = classIndex.findClassAtOffset(virtualFile, cursorOffset);
				if (currentForBase != null) {
					String baseName = currentForBase.getBaseClassName();
					if (baseName != null && !baseName.isEmpty()) {
						ru.artlebedev.parser3.model.P3ClassDeclaration baseClass = findClassByName(classIndex, baseName, classVisibleFilesSet);
						if (baseClass != null) {
							addMethodsFromClassHierarchyFast(baseClass, resultWithCleanPrefix, file, classVisibleFilesSet, P3MethodCallType.DYNAMIC);
						}
					}
				}
				break;

			case "class":
				// ^ClassName:method[] — методы указанного класса
				if (targetClassName != null) {
					// Сначала проверяем встроенные классы (form, env, request, etc.)
					java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable> builtinProps =
							ru.artlebedev.parser3.lang.Parser3BuiltinMethods.supportsStaticPropertyAccess(targetClassName)
									? ru.artlebedev.parser3.lang.Parser3BuiltinMethods.getStaticPropertiesForClass(targetClassName)
									: java.util.Collections.emptyList();
					if (!builtinProps.isEmpty()) {
						addBuiltinPropertiesAsItems(builtinProps, resultWithCleanPrefix);
						addLocalBuiltinPropertiesAsItems(targetClassName, file.getText(), builtinProps, resultWithCleanPrefix, cursorOffset);
					} else {
						// Пользовательский класс из индекса
						List<VirtualFile> targetClassVisibleFiles = scopeContext.isAllMethodsMode()
								? null
								: scopeContext.getClassSearchFilesForClass(targetClassName);
						Set<VirtualFile> targetClassVisibleFilesSet = targetClassVisibleFiles == null
								? null
								: new HashSet<>(targetClassVisibleFiles);
						ru.artlebedev.parser3.model.P3ClassDeclaration targetClass = findClassByName(classIndex, targetClassName, targetClassVisibleFilesSet);
						if (targetClass != null) {
							addMethodsFromClassHierarchyFast(targetClass, resultWithCleanPrefix, file, targetClassVisibleFiles, P3MethodCallType.STATIC);
						}
					}
				}
				break;
		}
	}

	/**
	 * Находит класс по имени. Если visibleFilesSet != null — фильтрует по видимым файлам.
	 */
	private @org.jetbrains.annotations.Nullable ru.artlebedev.parser3.model.P3ClassDeclaration findClassByName(
			@NotNull ru.artlebedev.parser3.index.P3ClassIndex classIndex,
			@NotNull String className,
			@org.jetbrains.annotations.Nullable Set<VirtualFile> visibleFilesSet
	) {
		List<ru.artlebedev.parser3.model.P3ClassDeclaration> classes = classIndex.findByName(className);
		if (classes.isEmpty()) {
			return null;
		}

		if (visibleFilesSet == null) {
			// Режим ALL_METHODS — берём последний
			return classes.get(classes.size() - 1);
		}

		// Режим USE_ONLY — берём последний из видимых
		ru.artlebedev.parser3.model.P3ClassDeclaration last = null;
		for (ru.artlebedev.parser3.model.P3ClassDeclaration cls : classes) {
			if (visibleFilesSet.contains(cls.getFile())) {
				last = cls;
			}
		}
		return last;
	}

	/**
	 * Добавляет методы для обычного контекста (^method[]).
	 * Показывает методы текущего класса + его @BASE иерархия + MAIN из видимых файлов.
	 */
	private void addNormalContextMethods(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@NotNull VirtualFile virtualFile,
			@NotNull ru.artlebedev.parser3.index.P3ClassIndex classIndex,
			int cursorOffset,
			@org.jetbrains.annotations.Nullable Set<VirtualFile> visibleFilesSet,
			@org.jetbrains.annotations.Nullable Set<VirtualFile> classVisibleFilesSet
	) {
		Set<String> addedNames = new HashSet<>();

		// 1. Методы текущего класса и его @BASE иерархии
		ru.artlebedev.parser3.model.P3ClassDeclaration currentClass = classIndex.findClassAtOffset(virtualFile, cursorOffset);
		if (currentClass != null && !currentClass.isMainClass()) {
			// Обходим иерархию классов
			Set<String> visitedClasses = new HashSet<>();
			ru.artlebedev.parser3.model.P3ClassDeclaration cls = currentClass;

			while (cls != null && !visitedClasses.contains(cls.getName())) {
				visitedClasses.add(cls.getName());

				// Добавляем методы текущего класса в иерархии
				for (P3MethodDeclaration method : cls.getMethods()) {
					if (!addedNames.contains(method.getName())) {
						addedNames.add(method.getName());
						addMethodElementWithDeclaration(result, method, cls.getName());
					}
				}

				// Переходим к базовому классу
				String baseClassName = cls.getBaseClassName();
				if (baseClassName != null && !baseClassName.isEmpty()) {
					cls = findClassByName(classIndex, baseClassName, classVisibleFilesSet);
				} else {
					cls = null;
				}
			}
		}

		// 2. MAIN методы
		addMainMethodsFromIndex(result, file, cursorOffset, visibleFilesSet, addedNames);
	}

	/**
	 * Добавляет системные области вызова, которые сразу продолжаются разделителем.
	 */
	private void addScopeReceiverCompletions(@NotNull CompletionResultSet result) {
		result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(
				P3CompletionUtils.createReceiverCompletion("self.", "self", "scope"), 105));
		result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(
				P3CompletionUtils.createReceiverCompletion("MAIN:", "MAIN", "scope"), 105));
	}

	/**
	 * Добавляет встроенные классы с свойствами в автокомплит после ^.
	 * Например: ^fo → предлагает "form:" и при выборе вставляет "form:" + открывает popup.
	 */
	private void addBuiltinClassesWithProperties(@NotNull CompletionResultSet result) {
		java.util.Map<String, java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable>> allProps =
				ru.artlebedev.parser3.lang.Parser3BuiltinMethods.getAllProperties();

		if (DEBUG_BUILTIN) System.out.println("[P3MethodCompl.BUILTIN] addBuiltinClassesWithProperties: allProps.size=" + allProps.size()
				+ " prefixMatcher='" + result.getPrefixMatcher().getPrefix() + "'");

		for (java.util.Map.Entry<String, java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable>> entry : allProps.entrySet()) {
			String className = entry.getKey();
			java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable> props = entry.getValue();
			if (props.isEmpty()) continue;

			String insertText = className + ":";
			boolean matches = result.getPrefixMatcher().prefixMatches(insertText);
			boolean matchesName = result.getPrefixMatcher().prefixMatches(className);
			if (DEBUG_BUILTIN) System.out.println("[P3MethodCompl.BUILTIN]   class='" + className + "' insertText='" + insertText
					+ "' matchesInsert=" + matches + " matchesName=" + matchesName);

			// Краткое описание — перечень свойств
			StringBuilder tailText = new StringBuilder(" ");
			int count = 0;
			for (ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable p : props) {
				if (count > 0) tailText.append(", ");
				tailText.append(p.name);
				if (++count >= 4) {
					if (props.size() > 4) tailText.append(", …");
					break;
				}
			}

			com.intellij.codeInsight.lookup.LookupElementBuilder element =
					com.intellij.codeInsight.lookup.LookupElementBuilder
							.create(insertText)
							.withLookupString(className)
							.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
							.withTypeText("class", true)
							.withPresentableText(className + ":")
							.withTailText(tailText.toString(), true)
							.withInsertHandler((context, item) -> {
								// Удаляем остаток старого имени (только символы идентификатора)
								com.intellij.openapi.editor.Document doc = context.getDocument();
								int tailOff = context.getTailOffset();
								CharSequence txt = doc.getCharsSequence();
								int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
								if (endOff > tailOff) {
									doc.deleteString(tailOff, endOff);
								}
								// Открываем popup с свойствами
								com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
										.scheduleAutoPopup(context.getEditor());
							});

			result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(element, 70));
		}

		// Перезапуск completion когда prefix содержит ":" — переход от "form:" к свойствам
		result.restartCompletionOnPrefixChange(
				com.intellij.patterns.StandardPatterns.string().contains(":"));
	}

	/**
	 * Добавляет свойства встроенного класса как элементы автокомплита.
	 * Например: ^form: → fields, tables, files, imap
	 */
	private void addBuiltinPropertiesAsItems(
			@NotNull java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable> properties,
			@NotNull CompletionResultSet result
	) {
		for (ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable prop : properties) {
			String typeText = prop.returnType != null ? prop.returnType : "property";
			com.intellij.codeInsight.lookup.LookupElementBuilder element =
					com.intellij.codeInsight.lookup.LookupElementBuilder
							.create(prop.name)
							.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
							.withTypeText(typeText, true)
							.withTailText(prop.description != null ? " " + prop.description : "", true)
							.withInsertHandler((ctx, item) -> {
								// В ^-контексте всегда вставляем точку в конце
								com.intellij.openapi.editor.Document doc = ctx.getDocument();
								int tail = ctx.getTailOffset();
								doc.insertString(tail, ".");
								ctx.getEditor().getCaretModel().moveToOffset(tail + 1);
								// Открываем попап с методами свойства
								com.intellij.codeInsight.AutoPopupController.getInstance(ctx.getProject())
										.scheduleAutoPopup(ctx.getEditor());
							});
			result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(element, 95));
		}
	}

	private void addLocalBuiltinPropertiesAsItems(
			@NotNull String className,
			@NotNull CharSequence fileText,
			@NotNull java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable> builtinProperties,
			@NotNull CompletionResultSet result,
			int cursorOffset
	) {
		java.util.LinkedHashSet<String> builtinNames = new java.util.LinkedHashSet<>();
		for (ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable property : builtinProperties) {
			builtinNames.add(property.name);
		}

		for (String localProperty : Parser3BuiltinStaticPropertyUsageUtils.collectPropertyNames(fileText, className, cursorOffset)) {
			if (builtinNames.contains(localProperty)) {
				continue;
			}

			String localTypeText = Parser3BuiltinStaticPropertyUsageUtils.getLocalPropertyTypeText(className);
			com.intellij.codeInsight.lookup.LookupElementBuilder element =
					com.intellij.codeInsight.lookup.LookupElementBuilder
							.create(localProperty + ".")
							.withLookupString(localProperty)
							.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
							.withTypeText(localTypeText, true)
							.withPresentableText(localProperty)
							.withTailText(" найдено в файле", true)
							.withInsertHandler((ctx, item) -> {
								com.intellij.openapi.editor.Document doc = ctx.getDocument();
								int tail = ctx.getTailOffset();
								CharSequence txt = doc.getCharsSequence();
								if (tail > 0 && tail < txt.length()
										&& txt.charAt(tail - 1) == '.' && txt.charAt(tail) == '.') {
									doc.deleteString(tail, tail + 1);
								}
								com.intellij.codeInsight.AutoPopupController.getInstance(ctx.getProject())
										.scheduleAutoPopup(ctx.getEditor());
							});
			result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(element, 94));
		}
	}

	/**
	 * Добавляет переменные с типами в автокомплит (^varName.)
	 */
	private void addVariablesWithTypes(
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@NotNull VirtualFile virtualFile,
			int cursorOffset,
			@org.jetbrains.annotations.Nullable Set<VirtualFile> visibleFilesSet,
			@NotNull String contextType
	) {
		long startTime = System.currentTimeMillis();
		// Проверяем что мы НЕ уже внутри ^varName. (после точки)
		String prefix = result.getPrefixMatcher().getPrefix();
		if (prefix.contains(".")) {
			// Уже внутри ^varName.method — не показываем переменные
			return;
		}

		ru.artlebedev.parser3.index.P3VariableIndex variableTypeIndex =
				ru.artlebedev.parser3.index.P3VariableIndex.getInstance(file.getProject());
		P3ScopeContext scopeContext = new P3ScopeContext(file.getProject(), virtualFile, cursorOffset);
		List<VirtualFile> visibleFiles = visibleFilesSet != null
				? new ArrayList<>(visibleFilesSet)
				: scopeContext.getVariableSearchFiles();

		// Получаем все видимые переменные
		List<ru.artlebedev.parser3.index.P3VariableIndex.VisibleVariable> allVisible =
				variableTypeIndex.getVisibleVariables(visibleFiles, virtualFile, cursorOffset);

		// Определяем текущий класс для фильтрации
		ru.artlebedev.parser3.index.P3ClassIndex classIdx =
				ru.artlebedev.parser3.index.P3ClassIndex.getInstance(file.getProject());
		ru.artlebedev.parser3.model.P3ClassDeclaration cursorClass = classIdx.findClassAtOffset(virtualFile, cursorOffset);
		String cursorOwnerClass = cursorClass != null ? cursorClass.getName() : null;

		// Фильтрация через ЕДИНЫЙ ИСТОЧНИК (с учётом hasLocals — как в P3VariableCompletionContributor)
		java.util.Set<String> methodParamsForFilter = variableTypeIndex.getMethodParamsAtOffset(virtualFile, cursorOffset);
		boolean hasLocalsForFilter = variableTypeIndex.hasLocalsAtOffset(virtualFile, cursorOffset);
		Map<String, ru.artlebedev.parser3.index.P3VariableIndex.VisibleVariable> filtered =
				ru.artlebedev.parser3.index.P3VariableIndex.filterByContext(allVisible, contextType, cursorOwnerClass, null, hasLocalsForFilter, methodParamsForFilter);

		for (Map.Entry<String, ru.artlebedev.parser3.index.P3VariableIndex.VisibleVariable> entry : filtered.entrySet()) {
			String varName = entry.getKey();
			ru.artlebedev.parser3.index.P3VariableIndex.VisibleVariable v = entry.getValue();

			// Добавляем varName. как элемент автокомплита
			LookupElementBuilder element = LookupElementBuilder
					.create(varName + ".")
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(!v.getDisplayType().isEmpty() ? v.getDisplayType() : null)
					.withInsertHandler((context, item) -> {
						// Удаляем остаток старого имени (только символы идентификатора)
						Document doc = context.getDocument();
						int tailOff = context.getTailOffset();
						CharSequence txt = doc.getCharsSequence();
						int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
						if (endOff > tailOff) {
							doc.deleteString(tailOff, endOff);
						}
						// Удаляем дублирующую точку: lookup string "var." + существующая "."
						int newTail = context.getTailOffset();
						CharSequence newTxt = doc.getCharsSequence();
						if (newTail > 0 && newTail < newTxt.length()
								&& newTxt.charAt(newTail - 1) == '.' && newTxt.charAt(newTail) == '.') {
							doc.deleteString(newTail, newTail + 1);
						}
						// После вставки автоматически открываем popup с методами
						com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
								.scheduleAutoPopup(context.getEditor());
					})
					.withPresentableText(varName + ".");

			result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(element, 100));
		}

		// Параметры текущего метода с типами — добавляем если нет присваивания
		List<ru.artlebedev.parser3.index.P3VariableIndex.MethodParameter> params =
				variableTypeIndex.getMethodParameters(virtualFile, cursorOffset);
		for (ru.artlebedev.parser3.index.P3VariableIndex.MethodParameter param : params) {
			if (filtered.containsKey(param.name)) continue;
			// Показываем только параметры с известным типом — для ^var. нужен тип
			if (param.type == null || param.type.isEmpty()) continue;

			LookupElementBuilder paramElement = LookupElementBuilder
					.create(param.name + ".")
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText(param.type)
					.withInsertHandler((context, item) -> {
						Document doc = context.getDocument();
						int tailOff = context.getTailOffset();
						CharSequence txt = doc.getCharsSequence();
						int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
						if (endOff > tailOff) {
							doc.deleteString(tailOff, endOff);
						}
						int newTail = context.getTailOffset();
						CharSequence newTxt = doc.getCharsSequence();
						if (newTail > 0 && newTail < newTxt.length()
								&& newTxt.charAt(newTail - 1) == '.' && newTxt.charAt(newTail) == '.') {
							doc.deleteString(newTail, newTail + 1);
						}
						com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
								.scheduleAutoPopup(context.getEditor());
					})
					.withPresentableText(param.name + ".");

			result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(paramElement, 100));
		}

		// ^result. — всегда доступна внутри метода, даже если не определена явно
		if ("normal".equals(contextType) && !filtered.containsKey("result")) {
			LookupElementBuilder resultElement = LookupElementBuilder
					.create("result.")
					.withIcon(Parser3Icons.FileVariable)
					.withTypeText("result", true)
					.withInsertHandler((context, item) -> {
						Document doc = context.getDocument();
						int tailOff = context.getTailOffset();
						CharSequence txt = doc.getCharsSequence();
						int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
						if (endOff > tailOff) {
							doc.deleteString(tailOff, endOff);
						}
						int newTail = context.getTailOffset();
						CharSequence newTxt = doc.getCharsSequence();
						if (newTail > 0 && newTail < newTxt.length()
								&& newTxt.charAt(newTail - 1) == '.' && newTxt.charAt(newTail) == '.') {
							doc.deleteString(newTail, newTail + 1);
						}
						com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
								.scheduleAutoPopup(context.getEditor());
					})
					.withPresentableText("result.");

			result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(resultElement, 100));
		}
	}

	/**
	 * Быстрое добавление только MAIN методов из индекса.
	 * Работает напрямую с индексом — не обходит файлы.
	 *
	 * @param visibleFilesSet если не null — фильтрует по видимым файлам (режим USE_ONLY)
	 */
	private void addMainMethodsFromIndex(
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			int cursorOffset,
			@org.jetbrains.annotations.Nullable Set<VirtualFile> visibleFilesSet
	) {
		addMainMethodsFromIndex(result, file, cursorOffset, visibleFilesSet, new HashSet<>());
	}

	/**
	 * Быстрое добавление только MAIN методов из индекса.
	 * Работает напрямую с индексом — не обходит файлы.
	 *
	 * @param visibleFilesSet если не null — фильтрует по видимым файлам (режим USE_ONLY)
	 * @param addedNames уже добавленные имена методов (для исключения дубликатов)
	 */
	private void addMainMethodsFromIndex(
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			int cursorOffset,
			@org.jetbrains.annotations.Nullable Set<VirtualFile> visibleFilesSet,
			@NotNull Set<String> addedNames
	) {
		com.intellij.util.indexing.FileBasedIndex index = com.intellij.util.indexing.FileBasedIndex.getInstance();
		com.intellij.openapi.project.Project project = file.getProject();
		VirtualFile currentFile = file.getVirtualFile();

		// Для приоритизации — сначала файлы из текущего контекста видимости
		Set<VirtualFile> prioritySet = new HashSet<>(
				new P3ScopeContext(project, currentFile, cursorOffset).getMethodSearchFiles());

		// Получаем все имена методов из индекса
		com.intellij.psi.search.GlobalSearchScope scope =
				ru.artlebedev.parser3.index.P3IndexMaintenance.getParser3IndexScope(project);
		Collection<String> allMethodNames = index.getAllKeys(ru.artlebedev.parser3.index.P3MethodFileIndex.NAME, project);

		for (String methodName : allMethodNames) {
			if (addedNames.contains(methodName)) {
				continue;
			}

			// Пропускаем методы, которые не вызываются явно (auto, conf, main, unhandled_exception)
			if (P3CompletionUtils.isUncallableMethod(methodName)) {
				continue;
			}
			Collection<VirtualFile> files = index.getContainingFiles(
					ru.artlebedev.parser3.index.P3MethodFileIndex.NAME, methodName, scope);

			// Ищем MAIN метод (ownerClass == null)
			VirtualFile methodFile = null;
			VirtualFile fallbackFile = null;
			ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo foundInfo = null;
			ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo fallbackInfo = null;

			for (VirtualFile f : files) {
				// В режиме USE_ONLY — пропускаем файлы не из visibleFilesSet
				if (visibleFilesSet != null && !visibleFilesSet.contains(f)) {
					continue;
				}

				java.util.Map<String, java.util.List<ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo>> fileData =
						index.getFileData(ru.artlebedev.parser3.index.P3MethodFileIndex.NAME, f, project);
				java.util.List<ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo> infos = fileData.get(methodName);
				if (infos != null) {
					for (ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo info : infos) {
						// Только MAIN методы (ownerClass == null)
						if (info.ownerClass == null) {
							// Приоритет файлам из USE-цепочки
							if (prioritySet.contains(f)) {
								methodFile = f;
								foundInfo = info;
								break;
							}
							if (fallbackFile == null) {
								fallbackFile = f;
								fallbackInfo = info;
							}
						}
					}
				}
				if (methodFile != null) break;
			}

			// Используем приоритетный файл, или fallback
			if (methodFile == null) {
				methodFile = fallbackFile;
				foundInfo = fallbackInfo;
			}

			if (methodFile != null) {
				addedNames.add(methodName);
				addMethodElementWithFile(result, methodName, null, methodFile, project, foundInfo, currentFile);
			}
		}
	}

	/**
	 * Универсальный метод для создания элемента автодополнения.
	 *
	 * @param result результат автокомплита
	 * @param methodName имя метода
	 * @param typeText текст справа (класс или путь)
	 * @param method декларация метода (может быть null)
	 * @param methodInfo информация из индекса (может быть null)
	 * @param methodFile файл метода (может быть null)
	 * @param project проект (может быть null)
	 */
	private void addMethodLookupElement(
			@NotNull CompletionResultSet result,
			@NotNull String methodName,
			@NotNull String typeText,
			@org.jetbrains.annotations.Nullable P3MethodDeclaration method,
			@org.jetbrains.annotations.Nullable ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo methodInfo,
			@org.jetbrains.annotations.Nullable VirtualFile methodFile,
			@org.jetbrains.annotations.Nullable com.intellij.openapi.project.Project project
	) {
		// Получаем или создаём P3MethodDeclaration
		P3MethodDeclaration decl = method;
		if (decl == null && methodInfo != null && methodFile != null) {
			// Конвертируем MethodInfo в P3MethodDeclaration
			java.util.List<ru.artlebedev.parser3.model.P3MethodParameter> docParams = new java.util.ArrayList<>();
			for (ru.artlebedev.parser3.index.P3MethodFileIndex.DocParam dp : methodInfo.docParams) {
				docParams.add(new ru.artlebedev.parser3.model.P3MethodParameter(
						dp.name, dp.type, dp.description, false));
			}

			ru.artlebedev.parser3.model.P3MethodParameter docResult = null;
			if (methodInfo.docResult != null) {
				docResult = new ru.artlebedev.parser3.model.P3MethodParameter(
						methodInfo.docResult.name,
						methodInfo.docResult.type,
						methodInfo.docResult.description,
						true);
			}

			decl = new P3MethodDeclaration(
					methodName,
					methodFile,
					methodInfo.offset,
					null,
					methodInfo.parameterNames,
					methodInfo.docText,
					docParams,
					docResult,
					methodInfo.inferredResult,
					methodInfo.isGetter,
					methodInfo.callType
			);
		}

		// Формируем tailText с параметрами и описанием
		String tailText = "";
		if (decl != null) {
			if (!decl.getParameterNames().isEmpty()) {
				tailText = "[" + String.join(";", decl.getParameterNames()) + "]";
			}
			if (decl.getDocText() != null) {
				String firstLine = getFirstDocLine(decl.getDocText());
				if (firstLine != null && !firstLine.isEmpty()) {
					tailText += " " + firstLine;
				}
			}
		}

		// Создаём LookupElement
		ru.artlebedev.parser3.lang.P3UserMethodLookupObject lookupObject =
				new ru.artlebedev.parser3.lang.P3UserMethodLookupObject(methodName, decl);

		LookupElementBuilder element = LookupElementBuilder.create(lookupObject, methodName)
				.withIcon(Parser3Icons.FileMethod)
				.withTypeText(typeText, true)
				.withTailText(tailText.isEmpty() ? null : " " + tailText, true)
				.withInsertHandler(P3MethodInsertHandler.INSTANCE);

		result.addElement(element);
	}

	/**
	 * Получает первую строку из текста документации.
	 */
	private static @org.jetbrains.annotations.Nullable String getFirstDocLine(@org.jetbrains.annotations.Nullable String docText) {
		if (docText == null || docText.isEmpty()) {
			return null;
		}
		int newlinePos = docText.indexOf('\n');
		if (newlinePos >= 0) {
			return docText.substring(0, newlinePos).trim();
		}
		return docText.trim();
	}

	// === Удобные обёртки для разных случаев ===

	/**
	 * Добавляет элемент для метода с P3MethodDeclaration.
	 */
	private void addMethodElementWithDeclaration(
			@NotNull CompletionResultSet result,
			@NotNull P3MethodDeclaration method,
			@org.jetbrains.annotations.Nullable String className
	) {
		String typeText = className != null ? className : "MAIN";
		addMethodLookupElement(result, method.getName(), typeText, method, null, null, null);
	}

	/**
	 * Добавляет элемент для метода с MethodInfo и файлом.
	 */
	private void addMethodElementWithFile(
			@NotNull CompletionResultSet result,
			@NotNull String methodName,
			@org.jetbrains.annotations.Nullable String className,
			@NotNull VirtualFile methodFile,
			@NotNull com.intellij.openapi.project.Project project,
			@org.jetbrains.annotations.Nullable ru.artlebedev.parser3.index.P3MethodFileIndex.MethodInfo methodInfo,
			@org.jetbrains.annotations.Nullable VirtualFile currentFile
	) {
		String formattedPath = ru.artlebedev.parser3.utils.Parser3PathFormatter.formatFilePathForUI(
				methodFile,
				project,
				currentFile
		);
		String typeText = (className == null) ? formattedPath : className + " (" + formattedPath + ")";
		addMethodLookupElement(result, methodName, typeText, null, methodInfo, methodFile, project);
	}

	/**
	 * Добавляет методы из класса и его иерархии наследования (через @BASE).
	 * Работает напрямую с индексом — не обходит все файлы.
	 *
	 * @param visibleFilesSet если не null — фильтрует базовые классы по видимым файлам (режим USE_ONLY)
	 */
	private void addMethodsFromClassHierarchyFast(
			@NotNull ru.artlebedev.parser3.model.P3ClassDeclaration startClass,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@org.jetbrains.annotations.Nullable Set<VirtualFile> visibleFilesSet
	) {
		addMethodsFromClassHierarchyFast(startClass, result, file, visibleFilesSet, P3MethodCallType.ANY);
	}

	private void addMethodsFromClassHierarchyFast(
			@NotNull ru.artlebedev.parser3.model.P3ClassDeclaration startClass,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@org.jetbrains.annotations.Nullable Set<VirtualFile> visibleFilesSet,
			@NotNull P3MethodCallType callContext
	) {
		List<VirtualFile> visibleFiles = visibleFilesSet == null ? null : new ArrayList<>(visibleFilesSet);
		addMethodsFromClassHierarchyFast(startClass, result, file, visibleFiles, callContext);
	}

	private void addMethodsFromClassHierarchyFast(
			@NotNull ru.artlebedev.parser3.model.P3ClassDeclaration startClass,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@org.jetbrains.annotations.Nullable List<VirtualFile> visibleFiles
	) {
		addMethodsFromClassHierarchyFast(startClass, result, file, visibleFiles, P3MethodCallType.ANY);
	}

	private void addMethodsFromClassHierarchyFast(
			@NotNull ru.artlebedev.parser3.model.P3ClassDeclaration startClass,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@org.jetbrains.annotations.Nullable List<VirtualFile> visibleFiles,
			@NotNull P3MethodCallType callContext
	) {
		Set<String> visitedClasses = new HashSet<>();
		Set<String> addedNames = new HashSet<>();
		ru.artlebedev.parser3.index.P3ClassIndex classIndex = ru.artlebedev.parser3.index.P3ClassIndex.getInstance(file.getProject());
		ru.artlebedev.parser3.model.P3ClassDeclaration currentClass = startClass;
		Set<VirtualFile> visibleFilesSet = visibleFiles == null ? null : new HashSet<>(visibleFiles);

		while (currentClass != null && !visitedClasses.contains(currentClass.getName())) {
			visitedClasses.add(currentClass.getName());
			addMethodsFromClass(currentClass, result, addedNames, file, visibleFiles, callContext);

			// Переходим к базовому классу
			String baseClassName = currentClass.getBaseClassName();
			if (baseClassName != null && !baseClassName.isEmpty()) {
				currentClass = findClassByName(classIndex, baseClassName, visibleFilesSet);
			} else {
				currentClass = null;
			}
		}
	}

	/**
	 * Добавляет методы из одного класса
	 */
	private void addMethodsFromClass(
			@NotNull ru.artlebedev.parser3.model.P3ClassDeclaration clazz,
			@NotNull CompletionResultSet result,
			@NotNull Set<String> addedNames,
			@NotNull PsiFile file,
			@org.jetbrains.annotations.Nullable List<VirtualFile> visibleFiles
	) {
		addMethodsFromClass(clazz, result, addedNames, file, visibleFiles, P3MethodCallType.ANY);
	}

	private void addMethodsFromClass(
			@NotNull ru.artlebedev.parser3.model.P3ClassDeclaration clazz,
			@NotNull CompletionResultSet result,
			@NotNull Set<String> addedNames,
			@NotNull PsiFile file,
			@org.jetbrains.annotations.Nullable List<VirtualFile> visibleFiles,
			@NotNull P3MethodCallType callContext
	) {
		List<P3MethodDeclaration> methods = new ArrayList<>();
		if (visibleFiles == null) {
			methods.addAll(clazz.getMethods());
		} else {
			ru.artlebedev.parser3.index.P3MethodIndex methodIndex =
					ru.artlebedev.parser3.index.P3MethodIndex.getInstance(file.getProject());
			for (VirtualFile visibleFile : visibleFiles) {
				if (!visibleFile.isValid()) continue;
				for (P3MethodDeclaration method : methodIndex.findInFile(visibleFile)) {
					if (clazz.getName().equals(method.getOwnerClass())) {
						methods.add(method);
					}
				}
			}
		}

		for (P3MethodDeclaration decl : methods) {
			if (!isMethodAllowedForContext(decl, callContext)) {
				continue;
			}
			String methodName = decl.getName();

			if (addedNames.contains(methodName)) {
				continue;
			}
			addedNames.add(methodName);

			String formattedPath = ru.artlebedev.parser3.utils.Parser3PathFormatter.formatFilePathForUI(
					decl.getFile(),
					file.getProject(),
					file.getVirtualFile()
			);

			// Для не-MAIN классов показываем имя класса
			String typeText = clazz.isMainClass() ? formattedPath : clazz.getName() + " (" + formattedPath + ")";

			// Создаём объект с информацией для документации
			ru.artlebedev.parser3.lang.P3UserMethodLookupObject lookupObject =
					new ru.artlebedev.parser3.lang.P3UserMethodLookupObject(methodName, decl);

			// Формируем tailText с параметрами если есть
			String tailText = "";
			if (!decl.getParameterNames().isEmpty()) {
				tailText = "[" + String.join(";", decl.getParameterNames()) + "]";
			}
			if (decl.getDocText() != null) {
				// Берём только первую строку для tailText
				String firstLine = getFirstLine(decl.getDocText());
				if (firstLine != null && !firstLine.isEmpty()) {
					tailText += " " + firstLine;
				}
			}

			LookupElementBuilder element = LookupElementBuilder.create(lookupObject, methodName)
					.withIcon(Parser3Icons.FileMethod)
					.withTypeText(typeText, true)
					.withTailText(tailText.isEmpty() ? null : " " + tailText, true)
					.withInsertHandler(P3MethodInsertHandler.INSTANCE);

			result.addElement(element);
		}
	}

	private static boolean isMethodAllowedForContext(
			@NotNull P3MethodDeclaration method,
			@NotNull P3MethodCallType callContext
	) {
		return switch (callContext) {
			case STATIC -> method.getCallType().allowsStaticCall();
			case DYNAMIC -> method.getCallType().allowsDynamicCall();
			case ANY -> true;
		};
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

	private String extractMethodPrefix(@NotNull CompletionParameters parameters) {
		CompletionTextContext textContext = getCompletionTextContext(parameters);
		CharSequence fileText = textContext.text;
		int offset = textContext.offset;

		int caretPos = -1;
		int bracketDepth = 0; // Глубина [...] при обратном проходе: ] увеличивает, [ уменьшает
		for (int i = offset - 1; i >= Math.max(0, offset - 50); i--) {
			if (i >= fileText.length()) continue;

			char ch = fileText.charAt(i);
			if (ch == '^' && bracketDepth == 0) {
				caretPos = i;
				break;
			}
			// Обратный проход: ] открывает скобку, [ закрывает
			if (ch == ']') {
				bracketDepth++;
			} else if (ch == '[') {
				bracketDepth--;
				if (bracketDepth < 0) break; // Вышли за пределы контекста
			}
			if (bracketDepth == 0 && P3CompletionUtils.isMethodPrefixStopChar(ch)) {
				break;
			}
		}

		if (caretPos == -1) {
			return "";
		}

		String textSegment = fileText.subSequence(caretPos + 1, Math.min(offset, fileText.length())).toString();
		String prefix = textSegment.replaceAll("IntellijIdeaRulezzz.*", "").trim();

		return prefix;
	}

	private static @Nullable PsiFile findParser3HostFile(@Nullable PsiFile file) {
		PsiFile cur = file;
		while (cur != null) {
			if (Parser3PsiUtils.isParser3File(cur) && !Parser3PsiUtils.isInjectedFragment(cur)) {
				return cur;
			}
			if (!Parser3PsiUtils.isInjectedFragment(cur)) {
				return Parser3PsiUtils.isParser3File(cur) ? cur : null;
			}
			if (cur.getContext() == null) {
				return null;
			}
			PsiFile host = cur.getContext().getContainingFile();
			if (host == cur) {
				return null;
			}
			cur = host;
		}
		return null;
	}

	private static int getParser3HostOffset(@NotNull CompletionParameters parameters) {
		CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
		int injectedOffset = clampOffset(text, parameters.getOffset());
		Document document = parameters.getEditor().getDocument();
		if (document instanceof DocumentWindow) {
			DocumentWindow documentWindow = (DocumentWindow) document;
			CharSequence hostText = documentWindow.getDelegate().getCharsSequence();
			int mappedHostOffset = documentWindow.injectedToHost(injectedOffset);
			return chooseInjectedHostOffset(hostText, injectedOffset, mappedHostOffset);
		}
		Integer hostOffset = getInjectedHostOffset(parameters);
		if (hostOffset != null) {
			return hostOffset;
		}
		return injectedOffset;
	}

	private static @NotNull CompletionTextContext getCompletionTextContext(@NotNull CompletionParameters parameters) {
		Document document = parameters.getEditor().getDocument();
		CharSequence text = document.getCharsSequence();
		int offset = clampOffset(text, parameters.getOffset());
		if (document instanceof DocumentWindow) {
			DocumentWindow documentWindow = (DocumentWindow) document;
			Document hostDocument = documentWindow.getDelegate();
			CharSequence hostText = hostDocument.getCharsSequence();
			int mappedHostOffset = documentWindow.injectedToHost(offset);
			return new CompletionTextContext(hostText, chooseInjectedHostOffset(hostText, offset, mappedHostOffset));
		}

		Integer hostOffset = getInjectedHostOffset(parameters);
		if (hostOffset != null) {
			return new CompletionTextContext(text, clampOffset(text, hostOffset));
		}
		return new CompletionTextContext(text, offset);
	}

	private static int chooseInjectedHostOffset(
			@NotNull CharSequence hostText,
			int injectedOffset,
			int mappedHostOffset
	) {
		int mapped = adjustInjectedHostOffset(hostText, mappedHostOffset);
		int raw = clampOffset(hostText, injectedOffset);

		// В HTML-injection после повторного Ctrl+Space IntelliJ иногда отдаёт injected offset,
		// который численно уже является host offset, а injectedToHost уводит его в следующий
		// текстовый фрагмент. Берём raw только когда он указывает на Parser3-вызов в host-документе,
		// а mapped offset уже не находится в таком контексте.
		if (raw != mapped
				&& isLikelyParser3MethodContext(hostText, raw)
				&& !isLikelyParser3MethodContext(hostText, mapped)) {
			return raw;
		}

		return mapped;
	}

	private static @Nullable Integer getInjectedHostOffset(@NotNull CompletionParameters parameters) {
		PsiElement position = parameters.getPosition();
		PsiFile positionFile = position.getContainingFile();
		if (positionFile == null || !Parser3PsiUtils.isInjectedFragment(positionFile)) {
			return null;
		}
		return InjectedLanguageManager.getInstance(position.getProject()).injectedToHost(position, parameters.getOffset());
	}

	private static int adjustInjectedHostOffset(@NotNull CharSequence hostText, int hostOffset) {
		int offset = clampOffset(hostText, hostOffset);
		int methodPrefixEnd = findInjectedMethodPrefixEndOnCurrentLine(hostText, offset);
		if (methodPrefixEnd >= 0) {
			return methodPrefixEnd;
		}
		if (offset <= 0 || !isClosingBracket(hostText.charAt(offset - 1))) {
			return offset;
		}
		if (hasMethodCaretOnCurrentLine(hostText, offset - 1)) {
			return offset - 1;
		}
		return offset;
	}

	private static int findInjectedMethodPrefixEndOnCurrentLine(@NotNull CharSequence text, int offset) {
		int safeOffset = clampOffset(text, offset);
		int lineStart = safeOffset;
		while (lineStart > 0) {
			char ch = text.charAt(lineStart - 1);
			if (ch == '\n' || ch == '\r') {
				break;
			}
			lineStart--;
		}

		int lineEnd = safeOffset;
		while (lineEnd < text.length()) {
			char ch = text.charAt(lineEnd);
			if (ch == '\n' || ch == '\r') {
				break;
			}
			lineEnd++;
		}

		int scanStart = Math.min(Math.max(safeOffset - 1, lineStart), lineEnd - 1);
		for (int i = scanStart; i >= lineStart; i--) {
			if (text.charAt(i) != '^') {
				continue;
			}

			int prefixEnd = i + 1;
			while (prefixEnd < lineEnd && isMethodPrefixChar(text.charAt(prefixEnd))) {
				prefixEnd++;
			}
			if (prefixEnd == i + 1 || prefixEnd >= safeOffset) {
				continue;
			}
			if (prefixEnd < lineEnd && isInjectedBoundaryAfterMethodPrefix(text.charAt(prefixEnd))) {
				return prefixEnd;
			}
		}
		return -1;
	}

	private static boolean isMethodPrefixChar(char ch) {
		return P3CompletionUtils.isVarIdentChar(ch) || ch == '.' || ch == ':';
	}

	private static boolean isInjectedBoundaryAfterMethodPrefix(char ch) {
		return Character.isWhitespace(ch)
				|| P3CompletionUtils.isMethodPrefixStopChar(ch)
				|| isClosingBracket(ch);
	}

	private static boolean hasMethodCaretOnCurrentLine(@NotNull CharSequence text, int offset) {
		for (int i = offset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == '\n' || ch == '\r') {
				return false;
			}
			if (ch == '^') {
				return true;
			}
		}
		return false;
	}

	private static boolean isLikelyParser3MethodContext(@NotNull CharSequence text, int offset) {
		int safeOffset = clampOffset(text, offset);
		for (int i = safeOffset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == '\n' || ch == '\r') {
				return false;
			}
			if (ch == '^') {
				return true;
			}
			if (P3CompletionUtils.isMethodPrefixStopChar(ch) && ch != '.') {
				return false;
			}
		}
		return false;
	}

	private static boolean isClosingBracket(char ch) {
		return ch == ')' || ch == ']' || ch == '}';
	}

	private static int clampOffset(@NotNull CharSequence text, int offset) {
		if (offset < 0) {
			return 0;
		}
		return Math.min(offset, text.length());
	}

	private void addFileCompletions(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file
	) {
		VirtualFile virtualFile = file.getVirtualFile();
		if (virtualFile == null) {
			return;
		}

		String prefix = result.getPrefixMatcher().getPrefix();

		// Убираем use[ из префикса если есть
		if (prefix.startsWith("use[")) {
			prefix = prefix.substring(4); // "use[".length()
		}

		// Убираем завершающий / из prefix для корректной работы matcher
		String matcherPrefix = prefix;
		if (matcherPrefix.endsWith("/") && !matcherPrefix.equals("/")) {
			matcherPrefix = matcherPrefix.substring(0, matcherPrefix.length() - 1);
		}

		// Создаём новый result set с очищенным префиксом
		CompletionResultSet cleanResult = result.withPrefixMatcher(matcherPrefix);

		if (prefix.startsWith("/")) {
			// Проверяем на /../ (абсолютный путь с переходом вверх)
			if (prefix.startsWith("/../")) {
				addFilesFromDocumentRootRelative(cleanResult, file.getProject(), prefix);
			} else {
				addFilesFromDocumentRoot(cleanResult, file.getProject(), prefix);
			}
		} else if (prefix.startsWith("../")) {
			addRelativeFiles(cleanResult, virtualFile, prefix, file.getProject());
		} else {
			// Показываем всегда, даже если prefix неполный
			addFilesFromCurrentDir(cleanResult, virtualFile);
			addFilesFromClassPath(cleanResult, virtualFile, file.getProject());

			// Предлагаем ../
			LookupElementBuilder element = LookupElementBuilder.create("../")
					.withIcon(Parser3Icons.File)
					.withTypeText("parent directory", true);
			cleanResult.addElement(element);
		}
	}

	/**
	 * Handle paths like /../dir/file.p - relative to document root parent
	 */
	private void addFilesFromDocumentRootRelative(
			@NotNull CompletionResultSet result,
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull String prefix
	) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		if (documentRoot == null) {
			return;
		}

		// Count ../ levels and navigate up from document root
		String remaining = prefix.substring(1); // Remove leading /
		VirtualFile currentDir = documentRoot;
		int parentLevels = 0;

		while (remaining.startsWith("../")) {
			remaining = remaining.substring(3);
			parentLevels++;
			currentDir = currentDir.getParent();
			if (currentDir == null) {
				return;
			}
		}

		// Handle remaining path
		String pathPrefix;
		if (!remaining.isEmpty()) {
			// Если путь заканчивается на /, переходим в эту директорию
			if (remaining.endsWith("/")) {
				String dirPath = remaining.substring(0, remaining.length() - 1);
				if (!dirPath.isEmpty()) {
					VirtualFile targetDir = currentDir.findFileByRelativePath(dirPath);
					if (targetDir != null && targetDir.isDirectory()) {
						currentDir = targetDir;
						// pathPrefix БЕЗ завершающего /
						pathPrefix = "/" + "../".repeat(parentLevels) + dirPath;
					} else {
						return;
					}
				} else {
					// Только ../ без дополнительного пути
					pathPrefix = "/" + "../".repeat(parentLevels);
					if (pathPrefix.endsWith("/")) {
						pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
					}
				}
			} else {
				// Иначе берем родительскую директорию
				int lastSlash = remaining.lastIndexOf('/');
				if (lastSlash > 0) {
					String dirPath = remaining.substring(0, lastSlash);
					VirtualFile targetDir = currentDir.findFileByRelativePath(dirPath);
					if (targetDir != null && targetDir.isDirectory()) {
						currentDir = targetDir;
						pathPrefix = "/" + "../".repeat(parentLevels) + dirPath;
					} else {
						return;
					}
				} else {
					// Нет слеша - корневая директория после ../
					pathPrefix = "/" + "../".repeat(parentLevels);
					if (pathPrefix.endsWith("/")) {
						pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
					}
				}
			}
		} else {
			// Только ../ без дополнительного пути
			pathPrefix = "/" + "../".repeat(parentLevels);
			if (pathPrefix.endsWith("/")) {
				pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
			}
		}

		collectFilesRecursive(currentDir, currentDir, result, pathPrefix, new HashSet<>(), 0);
	}

	private void addFilesFromDocumentRoot(
			@NotNull CompletionResultSet result,
			@NotNull com.intellij.openapi.project.Project project,
			@NotNull String prefix
	) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile documentRoot = settings.getDocumentRootWithFallback();

		if (documentRoot == null) {
			return;
		}

		String relativePath = prefix.substring(1); // убираем начальный /
		VirtualFile dir = documentRoot;
		String pathPrefix = "/";

		if (!relativePath.isEmpty()) {
			// Если путь заканчивается на /, переходим в эту директорию
			if (relativePath.endsWith("/")) {
				String dirPath = relativePath.substring(0, relativePath.length() - 1);
				if (!dirPath.isEmpty()) {
					dir = documentRoot.findFileByRelativePath(dirPath);
					pathPrefix = "/" + dirPath + "/";
				}
			} else {
				// Иначе берем родительскую директорию
				int lastSlash = relativePath.lastIndexOf('/');
				if (lastSlash > 0) {
					String dirPath = relativePath.substring(0, lastSlash);
					dir = documentRoot.findFileByRelativePath(dirPath);
					pathPrefix = "/" + dirPath + "/";
				}
			}
		}

		if (dir != null && dir.isDirectory()) {
			collectFilesRecursive(dir, documentRoot, result, pathPrefix, new HashSet<>(), 0);
		}
	}

	private void addRelativeFiles(
			@NotNull CompletionResultSet result,
			@NotNull VirtualFile contextFile,
			@NotNull String prefix,
			@NotNull com.intellij.openapi.project.Project project
	) {

		VirtualFile originalDir = contextFile.getParent();
		if (originalDir == null) {
			return;
		}

		VirtualFile currentDir = originalDir;

		int upLevels = 0;
		String remaining = prefix;
		boolean couldNavigateUp = true;

		while (remaining.startsWith("../")) {
			upLevels++;
			remaining = remaining.substring(3);
			VirtualFile parent = currentDir.getParent();
			if (parent == null) {
				// Не можем подняться выше — fallback на текущую директорию
				couldNavigateUp = false;
				break;
			}
			currentDir = parent;
		}

		// Если не смогли подняться — fallback: показываем файлы из оригинальной директории
		// с именем файла без ../
		if (!couldNavigateUp) {
			// Убираем все ../ из prefix и показываем файлы с этим именем из текущей директории
			String fileNamePart = prefix;
			while (fileNamePart.startsWith("../")) {
				fileNamePart = fileNamePart.substring(3);
			}

			// Показываем файлы из текущей директории (fallback)
			collectFilesRecursive(originalDir, originalDir, result, "", new HashSet<>(), 0);

			// Также показываем файлы из CLASS_PATH
			P3ClassPathEvaluator evaluator = new P3ClassPathEvaluator(project);
			for (VirtualFile classPathDir : evaluator.getClassPathDirs(contextFile)) {
				collectFilesRecursive(classPathDir, classPathDir, result, "", new HashSet<>(), 0);
			}
			return;
		}

		// Handle remaining path
		String pathPrefix;
		if (!remaining.isEmpty()) {
			// Если путь заканчивается на /, переходим в эту директорию
			if (remaining.endsWith("/")) {
				String dirPath = remaining.substring(0, remaining.length() - 1);
				if (!dirPath.isEmpty()) {
					VirtualFile targetDir = currentDir.findFileByRelativePath(dirPath);
					if (targetDir != null && targetDir.isDirectory()) {
						currentDir = targetDir;
						// pathPrefix БЕЗ завершающего /
						pathPrefix = "../".repeat(upLevels) + dirPath;
					} else {
						// Директория не найдена — fallback на текущую директорию
						collectFilesRecursive(originalDir, originalDir, result, "", new HashSet<>(), 0);
						return;
					}
				} else {
					// Только ../ без дополнительного пути
					pathPrefix = "../".repeat(upLevels);
					if (pathPrefix.endsWith("/")) {
						pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
					}
				}
			} else {
				// Иначе берем родительскую директорию
				int lastSlash = remaining.lastIndexOf('/');
				if (lastSlash > 0) {
					String dirPath = remaining.substring(0, lastSlash);
					VirtualFile targetDir = currentDir.findFileByRelativePath(dirPath);
					if (targetDir != null && targetDir.isDirectory()) {
						currentDir = targetDir;
						pathPrefix = "../".repeat(upLevels) + dirPath;
					} else {
						// Директория не найдена — fallback на текущую директорию
						collectFilesRecursive(originalDir, originalDir, result, "", new HashSet<>(), 0);
						return;
					}
				} else {
					// Нет слеша - корневая директория после ../
					pathPrefix = "../".repeat(upLevels);
					if (pathPrefix.endsWith("/")) {
						pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
					}
				}
			}
		} else {
			// Только ../ без дополнительного пути
			pathPrefix = "../".repeat(upLevels);
			if (pathPrefix.endsWith("/")) {
				pathPrefix = pathPrefix.substring(0, pathPrefix.length() - 1);
			}
		}

		collectFilesRecursive(currentDir, currentDir, result, pathPrefix, new HashSet<>(), 0);

		// Также добавляем файлы из текущей директории как fallback вариант
		// (на случай если ../file.p не существует, но file.p рядом есть)
		if (upLevels > 0) {
			collectFilesRecursive(originalDir, originalDir, result, "", new HashSet<>(), 0);
		}
	}

	private void addFilesFromCurrentDir(@NotNull CompletionResultSet result, @NotNull VirtualFile contextFile) {

		VirtualFile currentDir = contextFile.getParent();

		if (currentDir != null && currentDir.isDirectory()) {
			collectFilesRecursive(currentDir, currentDir, result, "", new HashSet<>(), 0);
		}
	}

	private void addFilesFromClassPath(
			@NotNull CompletionResultSet result,
			@NotNull VirtualFile contextFile,
			@NotNull com.intellij.openapi.project.Project project
	) {
		P3ClassPathEvaluator evaluator = new P3ClassPathEvaluator(project);

		for (VirtualFile classPathDir : evaluator.getClassPathDirs(contextFile)) {
			collectFilesRecursive(classPathDir, classPathDir, result, "", new HashSet<>(), 0);
		}
	}

	/**
	 * Проверяет является ли файл Parser3 файлом на основе FileTypeManager
	 */
	private boolean isParser3File(@NotNull VirtualFile file) {
		com.intellij.openapi.fileTypes.FileType fileType = file.getFileType();
		return fileType instanceof Parser3FileType;
	}

	private void collectFilesRecursive(
			@NotNull VirtualFile dir,
			@NotNull VirtualFile baseDir,
			@NotNull CompletionResultSet result,
			@NotNull String pathPrefix,
			@NotNull Set<String> addedPaths,
			int depth
	) {
		if (depth > 10) {
			return;
		}

		VirtualFile[] children = dir.getChildren();

		for (VirtualFile child : children) {
			if (child.isDirectory()) {
				String subPrefix;
				if (pathPrefix.isEmpty()) {
					subPrefix = child.getName();
				} else if (pathPrefix.equals("/")) {
					subPrefix = "/" + child.getName();
				} else {
					subPrefix = pathPrefix + "/" + child.getName();
				}
				collectFilesRecursive(child, baseDir, result, subPrefix, addedPaths, depth + 1);
			} else if (isParser3File(child)) {
				String relativePath;
				if (pathPrefix.isEmpty()) {
					relativePath = child.getName();
				} else if (pathPrefix.equals("/")) {
					relativePath = "/" + child.getName();
				} else {
					relativePath = pathPrefix + "/" + child.getName();
				}


				if (addedPaths.contains(relativePath)) {
					continue;
				}
				addedPaths.add(relativePath);

				String displayPath = child.getPath().substring(baseDir.getPath().length());
				if (displayPath.startsWith("/")) {
					displayPath = displayPath.substring(1);
				}

				LookupElementBuilder element = LookupElementBuilder.create(relativePath)
						.withIcon(Parser3Icons.File)
						.withTypeText(displayPath, true);

				result.addElement(element);
			}
		}
	}
}
