// asdfhjlk
package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3ClassIndex;
import ru.artlebedev.parser3.index.P3VariableIndex;
import ru.artlebedev.parser3.utils.Parser3BuiltinStaticPropertyUsageUtils;
import ru.artlebedev.parser3.utils.Parser3ChainUtils;
import ru.artlebedev.parser3.utils.Parser3IdentifierUtils;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;
import ru.artlebedev.parser3.visibility.P3ScopeContext;
import ru.artlebedev.parser3.visibility.P3VariableScopeContext;

import java.util.*;

/**
 * Автокомплит переменных после $.
 *
 * Поддерживает:
 * - $v → $var, $var2, ...
 * - $self.v → $self.var, ...
 * - $MAIN:v → $MAIN:var, ...
 *
 * Использует тот же P3VariableIndex что и ^var. в P3MethodCompletionContributor.
 * Один и тот же индекс, одни и те же переменные, одна и та же видимость.
 */
public class P3VariableCompletionContributor extends CompletionContributor {

	private static final boolean DEBUG = false;
	private static final boolean DEBUG_PERF = false;

	private static final class CompletionTextContext {
		private final @NotNull CharSequence text;
		private final int offset;

		private CompletionTextContext(@NotNull CharSequence text, int offset) {
			this.text = text;
			this.offset = offset;
		}
	}

	public P3VariableCompletionContributor() {
		extend(CompletionType.BASIC,
				com.intellij.patterns.PlatformPatterns.psiElement(),
				new CompletionProvider<>() {
					@Override
					protected void addCompletions(
							@NotNull CompletionParameters parameters,
							@NotNull com.intellij.util.ProcessingContext context,
							@NotNull CompletionResultSet result
					) {
						PsiFile file = parameters.getOriginalFile();
						if (!Parser3PsiUtils.isParser3File(file)) return;

						// Регистронезависимый автокомплит для Parser3
						result = P3CompletionUtils.makeCaseInsensitive(result);

						VirtualFile virtualFile = file.getVirtualFile();
						if (virtualFile == null) return;

						CompletionTextContext textContext = getCompletionTextContext(parameters);
						int cursorOffset = textContext.offset;
						String originalEditorText = textContext.text.toString();
						long requestId = DEBUG_PERF ? System.nanoTime() : 0;
						long requestStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
						if (DEBUG_PERF) {
							System.out.println("[P3VarCompletion.PERF] START id=" + requestId
									+ " file=" + virtualFile.getName()
									+ " offset=" + cursorOffset);
						}

						long tableStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
						boolean tableMatched = P3TableColumnArgumentCompletionSupport.addCompletions(
								file.getProject(),
								virtualFile,
								originalEditorText,
								cursorOffset,
								result
						);
						if (DEBUG_PERF) {
							System.out.println("[P3VarCompletion.PERF] id=" + requestId
									+ " tableColumnCompletion=" + (System.currentTimeMillis() - tableStart) + "ms"
									+ " matched=" + tableMatched);
						}
						if (tableMatched) {
							if (DEBUG) {
								System.out.println("[P3VarCompletion] table column argument completion matched at offset=" + cursorOffset);
							}
							if (DEBUG_PERF) {
								System.out.println("[P3VarCompletion.PERF] TOTAL id=" + requestId
										+ " route=tableColumn elapsed=" + (System.currentTimeMillis() - requestStart) + "ms");
							}
							result.stopHere();
							return;
						}

						long pseudoTextStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
						boolean pseudoTextMatched = P3PseudoHashCompletionRegistry.addParamTextCompletions(
								file.getProject(),
								virtualFile,
								originalEditorText,
								cursorOffset,
								result
						);
						if (DEBUG_PERF) {
							System.out.println("[P3VarCompletion.PERF] id=" + requestId
									+ " pseudoHashParamText=" + (System.currentTimeMillis() - pseudoTextStart) + "ms"
									+ " matched=" + pseudoTextMatched);
						}
						if (pseudoTextMatched) {
							if (DEBUG) {
								System.out.println("[P3VarCompletion] addParamTextCompletions matched at offset=" + cursorOffset);
							}
							if (DEBUG_PERF) {
								System.out.println("[P3VarCompletion.PERF] TOTAL id=" + requestId
										+ " route=pseudoHashParamText elapsed=" + (System.currentTimeMillis() - requestStart) + "ms");
							}
							result.stopHere();
							return;
						}

						long extractStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
						VarPrefix varCtx = extractVarPrefix(parameters);
						if (DEBUG_PERF) {
							System.out.println("[P3VarCompletion.PERF] id=" + requestId
									+ " extractVarPrefix=" + (System.currentTimeMillis() - extractStart) + "ms"
									+ " ctx=" + (varCtx != null
									? varCtx.contextType + " prefix='" + varCtx.typedPrefix + "' varKey=" + varCtx.varKey
									: "null"));
						}
						if (DEBUG) System.out.println("[P3VarCompletion] varCtx=" + (varCtx != null
								? "type=" + varCtx.contextType + " prefix='" + varCtx.typedPrefix + "' varKey=" + varCtx.varKey
								: "null"));
						if (varCtx == null) {
							if (DEBUG_PERF) {
								System.out.println("[P3VarCompletion.PERF] TOTAL id=" + requestId
										+ " route=noVarContext elapsed=" + (System.currentTimeMillis() - requestStart) + "ms");
							}
							return;
						}
						result.stopHere();

						// В комментарии-документации (#) перед @method — показываем только параметры метода
						if ("normal".equals(varCtx.contextType)) {
							long docStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
							java.util.List<String> docParams = findDocCommentMethodParams(parameters);
							if (DEBUG_PERF) {
								System.out.println("[P3VarCompletion.PERF] id=" + requestId
										+ " findDocCommentMethodParams=" + (System.currentTimeMillis() - docStart) + "ms"
										+ " params=" + (docParams != null ? docParams.size() : 0));
							}
							if (docParams != null) {
								CompletionResultSet varResult = result.withPrefixMatcher(varCtx.typedPrefix);
								for (String param : docParams) {
									com.intellij.codeInsight.lookup.LookupElementBuilder el =
											com.intellij.codeInsight.lookup.LookupElementBuilder
													.create(param)
													.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
													.withTypeText("параметр", true)
													.withInsertHandler(P3VariableInsertHandler.INSTANCE);
									varResult.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(el, 90));
								}
							}
						}

						long addVarsStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
						addVariableCompletions(parameters, result, file, virtualFile, varCtx);
						if (DEBUG_PERF) {
							System.out.println("[P3VarCompletion.PERF] id=" + requestId
									+ " addVariableCompletionsCall=" + (System.currentTimeMillis() - addVarsStart) + "ms");
							System.out.println("[P3VarCompletion.PERF] TOTAL id=" + requestId
									+ " route=" + varCtx.contextType
									+ " elapsed=" + (System.currentTimeMillis() - requestStart) + "ms");
						}
					}
				}
		);
	}

	private static final boolean DEBUG_POPUP = false;

	@Override
	public boolean invokeAutoPopup(@NotNull com.intellij.psi.PsiElement position, char typeChar) {
		if (!Parser3PsiUtils.isParser3File(position.getContainingFile())) {
			return false;
		}

		if (DEBUG_POPUP) {
			String text = position.getContainingFile().getText();
			int offset = position.getTextRange().getEndOffset() + 1;
			if (offset > text.length()) offset = text.length();
			int from = Math.max(0, offset - 20);
			System.out.println("[invokeAutoPopup] typeChar='" + typeChar + "' offset=" + offset
					+ " before='" + text.substring(from, offset).replace("\n", "↵") + "'");
		}

		// Показываем popup после $
		if (typeChar == '$') {
			return true;
		}

		// Показываем popup при вводе { после $ — начало ${...}
		if (typeChar == '{') {
			PsiFile file = position.getContainingFile();
			if (file == null) return false;
			String text = file.getText();
			int offset = position.getTextRange().getEndOffset() + 1;
			if (offset > text.length()) offset = text.length();
			// { ещё не вставлен в text — смотрим последний символ (должен быть $)
			if (offset >= 1 && text.charAt(offset - 1) == '$') {
				if (DEBUG_POPUP) System.out.println("[invokeAutoPopup] ${ detected → true");
				return true;
			}
		}

		if (Character.isLetterOrDigit(typeChar)
				|| typeChar == '_'
				|| typeChar == '['
				|| typeChar == '('
				|| typeChar == '$'
				|| typeChar == '.'
				|| typeChar == ';') {
			PsiFile file = position.getContainingFile();
			if (file == null) return false;
			VirtualFile virtualFile = file.getVirtualFile();
			if (virtualFile != null) {
				String text = file.getText();
				int offset = position.getTextRange().getEndOffset() + 1;
				if (offset > text.length()) offset = text.length();
				if (P3TableColumnArgumentCompletionSupport.shouldAutoPopupOnTypedChar(
						file.getProject(),
						virtualFile,
						text,
						offset,
						typeChar
				)) {
					return true;
				}
			}
		}

		// Показываем popup для букв/цифр после $ или ${ на текущей строке
		if (Character.isLetterOrDigit(typeChar) || typeChar == '_' || typeChar == '-') {
			PsiFile file = position.getContainingFile();
			if (file == null) return false;
			String text = file.getText();
			int offset = position.getTextRange().getEndOffset() + 1;
			if (offset > text.length()) offset = text.length();

			if (P3CompletionUtils.shouldAutoPopupBooleanLiteral(text, offset, typeChar)) {
				return true;
			}

			VirtualFile virtualFile = file.getVirtualFile();
			if (virtualFile != null && P3PseudoHashCompletionRegistry.shouldAutoPopupParamText(
					file.getProject(),
					virtualFile,
					text,
					offset,
					typeChar
			)) {
				return true;
			}

			int searchStart = Math.max(0, offset - 50);
			String before = text.substring(searchStart, offset);

			for (int i = before.length() - 1; i >= 0; i--) {
				char ch = before.charAt(i);
				if (ch == '$') {
					// ^$ — экранирование, не переменная
					if (i > 0 && before.charAt(i - 1) == '^') return false;
					return true;
				}
				// ${d — курсор после { прямо после $
				if (ch == '{' && i > 0 && before.charAt(i - 1) == '$') return true;
				if (ch == '\n' || ch == '\r') break;
			}
		}

		// Показываем popup после . в $self. и ${var. контексте
		if (typeChar == '.') {
			PsiFile file = position.getContainingFile();
			if (file == null) return false;
			String text = file.getText();
			int offset = position.getTextRange().getEndOffset() + 1;
			if (offset > text.length()) offset = text.length();

			for (int i = offset - 1; i >= Math.max(0, offset - 30); i--) {
				char ch = text.charAt(i);
				if (ch == '$') return true;
				// Внутри ${...}: сканируем через { назад до $
				if (ch == '{' && i > 0 && text.charAt(i - 1) == '$') return true;
				if (ch == '\n' || ch == '\r' || ch == ' ') break;
			}
		}

		// Показываем popup после : в $MAIN:, $BASE: и $builtinClass: контексте
		if (typeChar == ':') {
			PsiFile file = position.getContainingFile();
			if (file == null) return false;
			String text = file.getText();
			int offset = position.getTextRange().getEndOffset() + 1;
			if (offset > text.length()) offset = text.length();

			int searchStart = Math.max(0, offset - 30);
			String before = text.substring(searchStart, offset);
			// Проверяем что перед : стоит $MAIN или $BASE
			if (before.contains("$MAIN") || before.contains("$BASE")) {
				return true;
			}
			// Проверяем $builtinClass: (form, env, request, etc.)
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$([a-z][a-z0-9_]*)$")
					.matcher(before);
			if (m.find()) {
				String className = m.group(1);
				if (ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(className)
						&& ru.artlebedev.parser3.lang.Parser3BuiltinMethods.supportsStaticPropertyAccess(className)) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Контекст ввода после $
	 */
	static class VarPrefix {
		final String contextType; // "normal", "self", "MAIN", "objectMethod"
		final String typedPrefix; // что набрано после $, $self., $MAIN:, или после $var.
		final @Nullable String varKey; // для objectMethod — ключ переменной (var, self.var, MAIN:var)
		final boolean needsClosingBrace; // true если курсор внутри незакрытой ${...
		final int receiverResolveOffset; // offset начала текущего $-выражения для резолва receiver-а

		VarPrefix(String contextType, String typedPrefix) {
			this(contextType, typedPrefix, null, false);
		}

		VarPrefix(String contextType, String typedPrefix, @Nullable String varKey) {
			this(contextType, typedPrefix, varKey, false);
		}

		VarPrefix(String contextType, String typedPrefix, @Nullable String varKey, boolean needsClosingBrace) {
			this(contextType, typedPrefix, varKey, needsClosingBrace, -1);
		}

		VarPrefix(String contextType, String typedPrefix, @Nullable String varKey, boolean needsClosingBrace, int receiverResolveOffset) {
			this.contextType = contextType;
			this.typedPrefix = typedPrefix;
			this.varKey = varKey;
			this.needsClosingBrace = needsClosingBrace;
			this.receiverResolveOffset = receiverResolveOffset;
		}
	}

	/**
	 * Извлекает контекст переменной из позиции курсора.
	 * $v → ("normal", "v")
	 * $self.v → ("self", "v")
	 * $MAIN:v → ("MAIN", "v")
	 * $user.m → ("objectMethod", "m", varKey="user")
	 * $self.user.m → ("objectMethod", "m", varKey="self.user")
	 * $MAIN:user.m → ("objectMethod", "m", varKey="MAIN:user")
	 */
	/**
	 * Проверяет что курсор в # комментарии перед @method, и возвращает параметры метода.
	 * Комментарий-документация — это блок # строк, за которым сразу идёт @methodName[params].
	 * Возвращает null если курсор не в документации.
	 */
	private static @org.jetbrains.annotations.Nullable java.util.List<String> findDocCommentMethodParams(
			@NotNull CompletionParameters parameters
	) {
		PsiFile completionFile = parameters.getPosition().getContainingFile();
		String text = completionFile != null ? completionFile.getText() : parameters.getOriginalFile().getText();
		int offset = clampOffset(text, parameters.getOffset());

		// 1. Проверяем что курсор на строке, начинающейся с #
		int lineStart = offset;
		while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
			lineStart--;
		}
		if (lineStart >= text.length() || text.charAt(lineStart) != '#') {
			return null;
		}

		// 2. Ищем конец блока комментариев — первую строку без # после текущей
		int pos = offset;
		while (pos < text.length()) {
			// Переходим к началу следующей строки
			while (pos < text.length() && text.charAt(pos) != '\n') pos++;
			if (pos < text.length()) pos++; // пропускаем \n

			if (pos >= text.length()) return null;

			// Пропускаем пустые строки
			if (text.charAt(pos) == '\n' || text.charAt(pos) == '\r') continue;

			if (text.charAt(pos) == '#') {
				// Ещё комментарий — продолжаем
				continue;
			}

			// Первая не-комментарийная строка — ожидаем @methodName[params]
			if (text.charAt(pos) != '@') return null;

			// Парсим @methodName[params]
			int nameStart = pos + 1;
			int nameEnd = nameStart;
			while (nameEnd < text.length() && (Character.isLetterOrDigit(text.charAt(nameEnd)) || text.charAt(nameEnd) == '_')) {
				nameEnd++;
			}
			if (nameEnd >= text.length() || text.charAt(nameEnd) != '[') return null;

			int bracketClose = text.indexOf(']', nameEnd + 1);
			if (bracketClose < 0) return null;

			String paramsStr = text.substring(nameEnd + 1, bracketClose).trim();
			// Убираем IntellijIdeaRulezzz если попал
			paramsStr = paramsStr.replaceAll("IntellijIdeaRulezzz\\w*", "").trim();
			if (paramsStr.isEmpty()) return null;

			java.util.List<String> params = new java.util.ArrayList<>();
			for (String p : paramsStr.split(";")) {
				String param = p.trim();
				if (!param.isEmpty()) {
					params.add(param);
				}
			}
			return params.isEmpty() ? null : params;
		}

		return null;
	}

	private static @org.jetbrains.annotations.Nullable VarPrefix extractVarPrefix(@NotNull CompletionParameters parameters) {
		// Используем completion file — содержит набранный текст + IntellijIdeaRulezzz
		PsiFile completionFile = parameters.getPosition().getContainingFile();
		String text = completionFile != null ? completionFile.getText() : parameters.getOriginalFile().getText();
		int offset = clampOffset(text, parameters.getOffset());

		// Ищем $ назад
		int dollarPos = -1;
		int squareDepth = 0;
		int roundDepth = 0;
		for (int i = offset - 1; i >= Math.max(0, offset - 100); i--) {
			if (i >= text.length()) continue;
			char ch = text.charAt(i);
			if (ch == ']') {
				squareDepth++;
				continue;
			}
			if (ch == ')') {
				roundDepth++;
				continue;
			}
			if (ch == '[' && squareDepth > 0) {
				squareDepth--;
				continue;
			}
			if (ch == '(' && roundDepth > 0) {
				roundDepth--;
				continue;
			}
			if (squareDepth > 0 || roundDepth > 0) {
				continue;
			}
			if (ch == '$') {
				if (i > 0 && text.charAt(i - 1) == '.') {
					continue;
				}
				// ^$ — экранирование
				if (i > 0 && text.charAt(i - 1) == '^') return null;
				dollarPos = i;
				break;
			}
			if (ch == '\n' || ch == '\r' || ch == '^') break;
		}

		if (dollarPos == -1) return null;

		// Случай ${...} — бракетная форма: ${varName} или ${data.key}
		if (dollarPos + 1 < text.length() && text.charAt(dollarPos + 1) == '{') {
			int braceContentStart = dollarPos + 2;
			if (DEBUG) System.out.println("[extractVarPrefix] ${} mode: dollarPos=" + dollarPos + " braceContentStart=" + braceContentStart + " offset=" + offset);
			if (braceContentStart > offset) {
				if (DEBUG) System.out.println("[extractVarPrefix] ${} cursor before content → null");
				return null; // курсор до содержимого
			}
			// Содержимое от { до курсора
			String innerText = text.substring(braceContentStart, Math.min(offset, text.length()));
			innerText = innerText.replaceAll("IntellijIdeaRulezzz.*", "").trim();
			// Проверяем: есть ли } после курсора на той же строке
			boolean hasClosingBrace = false;
			for (int i = offset; i < Math.min(text.length(), offset + 200); i++) {
				char c = text.charAt(i);
				if (c == '}') { hasClosingBrace = true; break; }
				if (c == '\n' || c == '\r') break;
			}
			if (DEBUG) System.out.println("[extractVarPrefix] ${} innerText='" + innerText + "' hasClosingBrace=" + hasClosingBrace);
			VarPrefix r = parseAfterDollarText(innerText, !hasClosingBrace, dollarPos);
			if (DEBUG) System.out.println("[extractVarPrefix] ${} → " + (r != null ? "type=" + r.contextType + " prefix='" + r.typedPrefix + "' varKey=" + r.varKey + " needsBrace=" + r.needsClosingBrace : "null"));
			return r;
		}

		// Сканируем от $ вперёд, поддерживая и обычные сегменты через точку,
		// и динамические ключи вида .[expr] / .(expr).
		// Останавливаемся на первом стоп-символе вне bracket-expression.
		int varEnd = dollarPos + 1;
		while (varEnd < offset && varEnd < text.length()) {
			char ch = text.charAt(varEnd);
			if (P3CompletionUtils.isVarIdentChar(ch) || ch == '.' || ch == ':') {
				varEnd++;
			} else if (ch == '$' && varEnd > dollarPos + 1 && text.charAt(varEnd - 1) == '.') {
				int dynamicEnd = scanDynamicDollarSegment(text, varEnd, offset);
				if (dynamicEnd <= varEnd) {
					break;
				}
				varEnd = dynamicEnd;
			} else if (ch == '[' || ch == '(') {
				int closePos = findMatchingBracket(text, varEnd, offset, ch);
				if (closePos < 0) {
					break;
				}
				varEnd = closePos + 1;
			} else {
				break;
			}
		}
		// Если курсор стоит за пределами валидной части переменной — не показываем автокомплит
		if (varEnd < offset) return null;

		String afterDollar = text.substring(dollarPos + 1, Math.min(varEnd, text.length()));
		afterDollar = afterDollar.replaceAll("IntellijIdeaRulezzz.*", "").trim();

		return parseAfterDollarText(afterDollar, false, dollarPos);
	}

	private static int findMatchingBracket(@NotNull String text, int openPos, int limit, char openCh) {
		return Parser3ChainUtils.findMatchingBracket(text, openPos, limit, openCh);
	}

	private static int scanDynamicDollarSegment(@NotNull String text, int start, int limit) {
		int pos = start;
		if (pos >= limit || pos >= text.length() || text.charAt(pos) != '$') {
			return start;
		}

		pos++;
		if (pos + 5 <= limit && pos + 5 <= text.length()) {
			String prefix = text.substring(pos, pos + 5);
			if ("self.".equals(prefix) || "MAIN:".equals(prefix) || "BASE:".equals(prefix)) {
				pos += 5;
			}
		}

		if (pos >= limit || pos >= text.length()) {
			return start;
		}
		char first = text.charAt(pos);
		if (!Parser3IdentifierUtils.isIdentifierStart(first)) {
			return start;
		}

		pos++;
		while (pos < limit && pos < text.length() && P3CompletionUtils.isVarIdentChar(text.charAt(pos))) {
			pos++;
		}
		return pos;
	}

	private static int findLastTopLevelDot(@NotNull String text) {
		return Parser3ChainUtils.findLastTopLevelDot(text);
	}

	private static @NotNull String normalizeDynamicSegments(@NotNull String text) {
		return Parser3ChainUtils.normalizeDynamicSegments(text);
	}

	/**
	 * Парсит строку после $ (или внутри ${...}) в VarPrefix.
	 * Общая логика для обычного $var и ${var} синтаксиса.
	 *
	 * @param afterDollar текст после $ (уже без IntellijIdeaRulezzz)
	 * @param needsClosingBrace true если скобка в ${...} не закрыта — InsertHandler добавит }
	 */
	private static @Nullable VarPrefix parseAfterDollarText(@NotNull String afterDollar, boolean needsClosingBrace) {
		return parseAfterDollarText(afterDollar, needsClosingBrace, -1);
	}

	private static @Nullable VarPrefix parseAfterDollarText(
			@NotNull String afterDollar,
			boolean needsClosingBrace,
			int receiverResolveOffset
	) {
		if (afterDollar.startsWith("self.")) {
			String afterSelf = afterDollar.substring(5);
			// $self.var.method → objectMethod с varKey="self.var"
			int dotPos = findLastTopLevelDot(afterSelf);
			if (dotPos >= 0) {
				String varPart = "self." + normalizeDynamicSegments(afterSelf.substring(0, dotPos));
				String methodPrefix = afterSelf.substring(dotPos + 1);
				return new VarPrefix("objectMethod", methodPrefix, varPart, needsClosingBrace, receiverResolveOffset);
			}
			return new VarPrefix("self", afterSelf, null, needsClosingBrace, receiverResolveOffset);
		} else if (afterDollar.startsWith("MAIN:")) {
			String afterMain = afterDollar.substring(5);
			// $MAIN:var.method → objectMethod с varKey="MAIN:var"
			int dotPos = findLastTopLevelDot(afterMain);
			if (dotPos >= 0) {
				String varPart = "MAIN:" + normalizeDynamicSegments(afterMain.substring(0, dotPos));
				String methodPrefix = afterMain.substring(dotPos + 1);
				return new VarPrefix("objectMethod", methodPrefix, varPart, needsClosingBrace, receiverResolveOffset);
			}
			return new VarPrefix("MAIN", afterMain, null, needsClosingBrace, receiverResolveOffset);
		} else if (afterDollar.startsWith("BASE:")) {
			// $BASE:var — переменные базового класса
			String afterBase = afterDollar.substring(5);
			return new VarPrefix("BASE", afterBase, null, needsClosingBrace, receiverResolveOffset);
		} else if (afterDollar.startsWith(".")) {
			// $.key и $.parent.child — конфигурируемые псевдо-хеши внутри специальных вызовов
			String afterDot = afterDollar.substring(1);
			int dotPos = findLastTopLevelDot(afterDot);
			if (dotPos >= 0) {
				String pathPart = normalizeDynamicSegments(afterDot.substring(0, dotPos));
				String keyPrefix = afterDot.substring(dotPos + 1);
				return new VarPrefix("pseudoHash", keyPrefix, pathPart, needsClosingBrace, receiverResolveOffset);
			}
			return new VarPrefix("pseudoHash", afterDot, null, needsClosingBrace, receiverResolveOffset);
		} else {
			// $obj.field → objectMethod с varKey="obj"
			int dotPos = findLastTopLevelDot(afterDollar);
			if (dotPos >= 0) {
				String varPart = normalizeDynamicSegments(afterDollar.substring(0, dotPos));
				String methodPrefix = afterDollar.substring(dotPos + 1);
				// varPart не должен содержать точку — иначе это цепочка
				return new VarPrefix("objectMethod", methodPrefix, varPart, needsClosingBrace, receiverResolveOffset);
			}
			// $builtinClass:field → свойства встроенного класса
			int colonPos = afterDollar.indexOf(':');
			if (colonPos >= 0) {
				String className = afterDollar.substring(0, colonPos);
				String fieldPrefix = afterDollar.substring(colonPos + 1);
				// Проверяем что это встроенный класс с зарегистрированными свойствами
				if (ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(className)
						&& ru.artlebedev.parser3.lang.Parser3BuiltinMethods.supportsStaticPropertyAccess(className)) {
					return new VarPrefix("builtinClassProp", fieldPrefix, className, needsClosingBrace, receiverResolveOffset);
				}
				return null;
			}
			return new VarPrefix("normal", afterDollar, null, needsClosingBrace, receiverResolveOffset);
		}
	}

	/**
	 * Добавляет переменные в автокомплит.
	 * Переиспользует P3VariableIndex.getVisibleVariables —
	 * тот же источник данных что и ^var. в P3MethodCompletionContributor.
	 */
	private void addVariableCompletions(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@NotNull VirtualFile virtualFile,
			@NotNull VarPrefix varCtx
	) {
		long startTime = DEBUG_PERF ? System.currentTimeMillis() : 0;
		Project project = file.getProject();
		CompletionTextContext textContext = getCompletionTextContext(parameters);
		int cursorOffset = textContext.offset;
		String originalEditorText = textContext.text.toString();

		// $var.method — показываем методы/переменные класса (как ^var.)
		if ("objectMethod".equals(varCtx.contextType) && varCtx.varKey != null) {
			long branchStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			addObjectMethodCompletions(parameters, result, file, virtualFile, varCtx);
			if (DEBUG_PERF) {
				System.out.println("[P3VarCompletion.PERF] addVariableCompletions route=objectMethod elapsed="
						+ (System.currentTimeMillis() - branchStart) + "ms");
			}
			return;
		}

		// $BASE:var — переменные базового класса
		if ("BASE".equals(varCtx.contextType)) {
			long branchStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			addBaseClassVariableCompletions(parameters, result, file, virtualFile, varCtx);
			if (DEBUG_PERF) {
				System.out.println("[P3VarCompletion.PERF] addVariableCompletions route=BASE elapsed="
						+ (System.currentTimeMillis() - branchStart) + "ms");
			}
			return;
		}

		// $builtinClass:field — свойства встроенного класса (form:fields, env:PARSER_VERSION, etc.)
		if ("builtinClassProp".equals(varCtx.contextType) && varCtx.varKey != null) {
			long branchStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			addBuiltinClassPropCompletions(result, varCtx, originalEditorText, cursorOffset);
			// Подавляем Live Templates и другие контрибьюторы — в $form: они не нужны
			result.stopHere();
			if (DEBUG_PERF) {
				System.out.println("[P3VarCompletion.PERF] addVariableCompletions route=builtinClassProp elapsed="
						+ (System.currentTimeMillis() - branchStart) + "ms");
			}
			return;
		}

		// $.key[] внутри специальных вызовов (например ^curl:load[...]) — данные приходят из конфига.
		if ("pseudoHash".equals(varCtx.contextType)) {
			long branchStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
			P3PseudoHashCompletionRegistry.addCompletions(
					file.getProject(), virtualFile, originalEditorText, cursorOffset, varCtx, result);
			result.stopHere();
			if (DEBUG_PERF) {
				System.out.println("[P3VarCompletion.PERF] addVariableCompletions route=pseudoHash elapsed="
						+ (System.currentTimeMillis() - branchStart) + "ms");
			}
			return;
		}

		CompletionResultSet varResult = result.withPrefixMatcher(varCtx.typedPrefix);

		long tvf = DEBUG_PERF ? System.currentTimeMillis() : 0;
		P3ScopeContext scopeContext = new P3ScopeContext(project, virtualFile, cursorOffset);
		List<VirtualFile> visibleFiles = scopeContext.getVariableSearchFiles();
		if (DEBUG_PERF) System.out.println("[P3VarCompletion.PERF] visibleFiles=" + visibleFiles.size()
				+ " allMethods=" + scopeContext.isAllMethodsMode()
				+ " calcTime=" + (System.currentTimeMillis() - tvf) + "ms");

		long t1 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		// Тот же источник данных что и для ^var.
		P3VariableIndex varTypeIndex = P3VariableIndex.getInstance(project);
		List<P3VariableIndex.VisibleVariable> allVisible =
				varTypeIndex.getVisibleVariables(new P3VariableScopeContext(scopeContext));
		if (DEBUG_PERF) System.out.println("[P3VarCompletion.PERF] getVisibleVariables: " + (System.currentTimeMillis() - t1) + "ms, allVisible=" + allVisible.size());

		// Определяем текущий класс для фильтрации
		long t2a = DEBUG_PERF ? System.currentTimeMillis() : 0;
		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		ru.artlebedev.parser3.model.P3ClassDeclaration cursorClass = classIndex.findClassAtOffset(virtualFile, cursorOffset);
		String cursorOwnerClass = cursorClass != null ? cursorClass.getName() : null;
		if (DEBUG_PERF) System.out.println("[P3VarCompletion.PERF] findClassAtOffset: " + (System.currentTimeMillis() - t2a) + "ms, class=" + cursorOwnerClass);

		// Собираем иерархию @BASE для $self.var — переменные базовых классов тоже видны
		long t2b = DEBUG_PERF ? System.currentTimeMillis() : 0;
		java.util.Set<String> classHierarchy = buildClassHierarchy(classIndex, cursorOwnerClass);
		if (DEBUG_PERF) System.out.println("[P3VarCompletion.PERF] buildClassHierarchy: " + (System.currentTimeMillis() - t2b) + "ms, hierarchy=" + classHierarchy);

		// Фильтрация через ЕДИНЫЙ ИСТОЧНИК
		long t2c = DEBUG_PERF ? System.currentTimeMillis() : 0;
		boolean hasLocalsForFilter = varTypeIndex.hasLocalsAtOffset(virtualFile, cursorOffset);
		if (DEBUG) System.out.println("[P3VarCompletion.addVars] hasLocalsForFilter=" + hasLocalsForFilter + " contextType=" + varCtx.contextType);
		Map<String, P3VariableIndex.VisibleVariable> filtered = P3VariableIndex.filterByContext(
				allVisible, varCtx.contextType, cursorOwnerClass, classHierarchy,
				hasLocalsForFilter, null);
		if (DEBUG_PERF) System.out.println("[P3VarCompletion.PERF] filterByContext: " + (System.currentTimeMillis() - t2c) + "ms, filtered=" + filtered.size());

		java.util.Set<String> getterPropertyNames = java.util.Collections.emptySet();
		long getterNamesStart = DEBUG_PERF ? System.currentTimeMillis() : 0;
		if (cursorOwnerClass != null && ("self".equals(varCtx.contextType) || "normal".equals(varCtx.contextType))) {
			getterPropertyNames = P3VariableMethodCompletionContributor.collectGetterPropertyNames(
					project, cursorOwnerClass, scopeContext.getClassSearchFiles());
		}
		if (DEBUG_PERF) {
			System.out.println("[P3VarCompletion.PERF] collectGetterPropertyNames: "
					+ (System.currentTimeMillis() - getterNamesStart) + "ms"
					+ " count=" + getterPropertyNames.size());
		}

		if (DEBUG) {
			System.out.println("[P3VarCompletion.addVars] contextType=" + varCtx.contextType
					+ " typedPrefix='" + varCtx.typedPrefix + "'"
					+ " allVisible=" + allVisible.size()
					+ " filtered=" + filtered.size()
					+ " cursorOwnerClass=" + cursorOwnerClass
					+ " classHierarchy=" + classHierarchy);
			for (P3VariableIndex.VisibleVariable v : allVisible) {
				System.out.println("[P3VarCompletion.addVars]   allVisible: cleanName=" + v.cleanName
						+ " owner=" + v.ownerClass + " class=" + v.className
						+ " varKey=" + v.varKey + " file=" + v.file.getName()
						+ " columns=" + v.columns + " sourceVarKey=" + v.sourceVarKey
						+ " methodName=" + v.methodName + " receiverVarKey=" + v.receiverVarKey
						+ " targetClassName=" + v.targetClassName);
			}
			for (Map.Entry<String, P3VariableIndex.VisibleVariable> fe : filtered.entrySet()) {
				System.out.println("[P3VarCompletion.addVars]   filtered: " + fe.getKey()
						+ " → class=" + fe.getValue().className + " owner=" + fe.getValue().ownerClass
						+ " columns=" + fe.getValue().columns + " sourceVarKey=" + fe.getValue().sourceVarKey
						+ " methodName=" + fe.getValue().methodName + " receiverVarKey=" + fe.getValue().receiverVarKey
						+ " targetClassName=" + fe.getValue().targetClassName);
			}
		}

		long t3 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		final int[] findColumnsCallCount = {0};
		final long[] findColumnsTime = {0};
		final int[] skippedByPrefixCount = {0};
		final java.util.Set<String> getterPropertyNamesForLoop = getterPropertyNames;
		varTypeIndex.withSharedResolveCache(() -> {
		for (Map.Entry<String, P3VariableIndex.VisibleVariable> entry : filtered.entrySet()) {
			String varName = entry.getKey();
			P3VariableIndex.VisibleVariable v = entry.getValue();
			if (shouldHideClassPropertyBehindGetter(v, varName, getterPropertyNamesForLoop)) {
				if (DEBUG) System.out.println("[P3VarCompletion.addVars] skip class-property duplicate for getter: " + varName);
				continue;
			}
			if (!varCtx.typedPrefix.isEmpty() && !varResult.getPrefixMatcher().prefixMatches(varName)) {
				skippedByPrefixCount[0]++;
				continue;
			}

			// Приоритет: параметр метода выше обычных переменных
			int priority = v.isMethodParam ? 85 : 80;

			// Для параметра метода: typeText = тип из документации или "param"
			// Это единственное место где параметры добавляются в автокомплит.

			// Ленивый резолв: $copy[$original] → тип original (глобально для всех типов)
			long tc0 = DEBUG_PERF ? System.currentTimeMillis() : 0;
			P3VariableIndex.VariableCompletionInfo completionInfo =
					varTypeIndex.analyzeVariableCompletion(v, visibleFiles, virtualFile, cursorOffset);
			if (DEBUG_PERF) {
				findColumnsCallCount[0]++;
				findColumnsTime[0] += System.currentTimeMillis() - tc0;
			}
			v = completionInfo.variable;

			boolean appendDot = completionInfo.appendDot;
			String insertText = appendDot ? varName + "." : varName;
			LookupElementBuilder element = LookupElementBuilder
					.create(insertText)
					.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
					.withTypeText(v.isMethodParam
							? (!v.getDisplayType().isEmpty() ? v.getDisplayType() : "param")
							: (!v.getDisplayType().isEmpty() ? v.getDisplayType() : null), true)
					.withPresentableText(insertText);

			if (appendDot) {
				// При ${da → вставляем "data." + popup; } добавится при выборе финального ключа
				element = element.withInsertHandler("normal".equals(varCtx.contextType)
						? P3VariableInsertHandler.createNormalContextHandler(originalEditorText, cursorOffset, null, true)
						: (context, item) -> {
					// Удаляем остаток старого имени (только символы идентификатора)
					com.intellij.openapi.editor.Document doc = P3VariableInsertHandler.resolveHostDocument(context);
					com.intellij.openapi.editor.Editor editor = P3VariableInsertHandler.resolveCompletionEditor(context, doc);
					int tailOff = P3VariableInsertHandler.findCaretAfterInsertedVariable(
							doc,
							Math.max(cursorOffset, context.getTailOffset()),
							item.getLookupString());
					CharSequence txt = doc.getCharsSequence();
					int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
					if (endOff > tailOff) {
						doc.deleteString(tailOff, endOff);
					}
					// Удаляем дублирующую точку
					int newTail = Math.min(tailOff, doc.getTextLength());
					CharSequence newTxt = doc.getCharsSequence();
					if (newTail > 0 && newTail < newTxt.length()
							&& newTxt.charAt(newTail - 1) == '.' && newTxt.charAt(newTail) == '.') {
						doc.deleteString(newTail, newTail + 1);
					}
					// Открываем popup с колонками/ключами хеша
					int popupOffset = Math.min(newTail, doc.getTextLength());
					P3VariableInsertHandler.moveCaretToHostOffset(editor, doc, popupOffset);
					Runnable previous = context.getLaterRunnable();
					context.setLaterRunnable(() -> {
						if (previous != null) {
							previous.run();
						}
						if (context.getProject().isDisposed()) {
							return;
						}
						P3VariableInsertHandler.moveCaretToHostOffset(editor, doc, Math.min(popupOffset, doc.getTextLength()));
						com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
							if (context.getProject().isDisposed()) {
								return;
							}
							P3VariableInsertHandler.moveCaretToHostOffset(editor, doc, Math.min(popupOffset, doc.getTextLength()));
							P3VariableInsertHandler.showBasicCompletion(context.getProject(), editor);
						});
					});
						});
			} else if (varCtx.needsClosingBrace) {
				// ${varName (без подключей) — вставляем имя и закрываем }
				element = element.withInsertHandler("normal".equals(varCtx.contextType)
						? P3VariableInsertHandler.createNormalContextHandler(originalEditorText, cursorOffset, "}", false)
						: P3VariableMethodCompletionContributor.wrapInsertHandlerWithSuffix(
								P3VariableInsertHandler.INSTANCE, "}"));
			} else {
				element = element.withInsertHandler("normal".equals(varCtx.contextType)
						? P3VariableInsertHandler.createNormalContextHandler(originalEditorText, cursorOffset, null, false)
						: P3VariableInsertHandler.INSTANCE);
			}

			varResult.addElement(PrioritizedLookupElement.withPriority(element, priority));
		}
		return null;
		});

		// Параметры метода теперь приходят через filtered (isMethodParam=true в VisibleVariable),
		// добавленные в getVisibleVariables как единственный источник истины.
		// Отдельного добавления здесь не нужно.

		// $result — всегда доступна внутри метода, даже если не определена явно
		if ("normal".equals(varCtx.contextType) && !filtered.containsKey("result")) {
			InsertHandler<com.intellij.codeInsight.lookup.LookupElement> resultHandler = varCtx.needsClosingBrace
					? P3VariableInsertHandler.createNormalContextHandler(originalEditorText, cursorOffset, "}", false)
					: P3VariableInsertHandler.createNormalContextHandler(originalEditorText, cursorOffset, null, false);

			LookupElementBuilder resultElement = LookupElementBuilder
					.create("result")
					.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
					.withTypeText("result", true)
					.withInsertHandler(resultHandler);

			varResult.addElement(PrioritizedLookupElement.withPriority(resultElement, 75));
		}

		// Для $self. и $var в классе — добавляем @GET_ геттеры текущего класса и @BASE иерархии
		if (DEBUG_PERF) System.out.println("[P3VarCompletion.PERF] loop addElement: " + (System.currentTimeMillis() - t3) + "ms");
		long t4 = DEBUG_PERF ? System.currentTimeMillis() : 0;
		if (cursorOwnerClass != null && ("self".equals(varCtx.contextType) || "normal".equals(varCtx.contextType))) {
			P3VariableMethodCompletionContributor.addGetterProperties(
					project, cursorOwnerClass, scopeContext.getClassSearchFiles(), varResult);
		}
		if (DEBUG_PERF) System.out.println("[P3VarCompletion.PERF] addGetterProperties: " + (System.currentTimeMillis() - t4) + "ms");

		// Встроенные классы с свойствами (form, env, request, etc.) — вставляют "form:" + popup
		if ("normal".equals(varCtx.contextType)) {
			addScopeReceiverCompletions(varResult);
			addBuiltinClassNameCompletions(varResult);
		}

		if (DEBUG_PERF) {
			long totalTime = System.currentTimeMillis() - startTime;
			System.out.println("[P3VarCompletion.PERF] addVariableCompletions TOTAL: " + totalTime + "ms"
					+ " filtered=" + filtered.size()
					+ " skippedByPrefix=" + skippedByPrefixCount[0]
					+ " findColumnsCallCount=" + findColumnsCallCount[0]
					+ " findColumnsTime=" + findColumnsTime[0] + "ms");
		}
	}

	private boolean shouldHideClassPropertyBehindGetter(
			@NotNull P3VariableIndex.VisibleVariable variable,
			@NotNull String varName,
			@NotNull java.util.Set<String> getterPropertyNames
	) {
		if (!getterPropertyNames.contains(varName)) {
			return false;
		}
		// Getter в Parser3 является свойством класса, но не должен прятать локальные переменные
		// и параметры метода с тем же именем в normal-контексте.
		return !variable.isMethodParam && !variable.isLocal;
	}

	/**
	 * Добавляет системные области переменных, которые сразу продолжаются разделителем.
	 */
	private void addScopeReceiverCompletions(@NotNull CompletionResultSet result) {
		result.addElement(PrioritizedLookupElement.withPriority(
				P3CompletionUtils.createReceiverCompletion("self.", "self", "scope"), 105));
		result.addElement(PrioritizedLookupElement.withPriority(
				P3CompletionUtils.createReceiverCompletion("MAIN:", "MAIN", "scope"), 105));
	}

	/**
	 * Автокомплит свойств базового класса после $BASE:.
	 * Переиспользует P3VariableMethodCompletionContributor.addClassProperties —
	 * те же свойства что и для $obj. (переменные + @GET_ геттеры).
	 */
	private void addBaseClassVariableCompletions(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@NotNull VirtualFile virtualFile,
			@NotNull VarPrefix varCtx
	) {
		Project project = file.getProject();
		int cursorOffset = getEditorCaretOffset(parameters);

		P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
		ru.artlebedev.parser3.model.P3ClassDeclaration cursorClass = classIndex.findClassAtOffset(virtualFile, cursorOffset);
		if (cursorClass == null) return;

		String baseName = cursorClass.getBaseClassName();
		if (baseName == null || baseName.isEmpty()) return;

		CompletionResultSet varResult = result.withPrefixMatcher(varCtx.typedPrefix);

		// Переиспользуем addClassProperties — те же свойства (переменные + @GET_ геттеры)
		P3VariableMethodCompletionContributor.addClassProperties(
				project, baseName, virtualFile, cursorOffset, varResult, java.util.Collections.emptySet());
	}

	/**
	 * Автокомплит свойств встроенного класса после $builtinClass:.
	 * Например: $form:fields, $env:PARSER_VERSION, $request:uri
	 * varCtx.varKey = имя класса (form, env, request, ...)
	 * varCtx.typedPrefix = что набрано после :
	 */
	private void addBuiltinClassPropCompletions(
			@NotNull CompletionResultSet result,
			@NotNull VarPrefix varCtx,
			@NotNull CharSequence fileText,
			int cursorOffset
	) {
		String className = varCtx.varKey;
		if (!ru.artlebedev.parser3.lang.Parser3BuiltinMethods.supportsStaticPropertyAccess(className)) {
			return;
		}
		CompletionResultSet propResult = result.withPrefixMatcher(varCtx.typedPrefix);
		java.util.LinkedHashSet<String> addedNames = new java.util.LinkedHashSet<>();

		java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable> properties =
				ru.artlebedev.parser3.lang.Parser3BuiltinMethods.getStaticPropertiesForClass(className);

		for (ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable prop : properties) {
			ru.artlebedev.parser3.lang.Parser3DocLookupObject docObject =
					new ru.artlebedev.parser3.lang.Parser3DocLookupObject(
							className + ":" + prop.name, prop.url, prop.description);

			String typeText = prop.returnType != null ? prop.returnType : className;
			String assignSuffix = prop.assignSuffix;

			com.intellij.codeInsight.lookup.LookupElementBuilder element =
					com.intellij.codeInsight.lookup.LookupElementBuilder
							.create(docObject, prop.name)
							.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
							.withTypeText(typeText, true)
							.withTailText((assignSuffix != null ? assignSuffix : "") +
									(prop.description != null ? " " + prop.description : ""), true);

			if (assignSuffix != null && !assignSuffix.isEmpty()) {
				// Вставляем свойство со скобками и курсором внутри: body[] → body[<cursor>]
				element = element.withInsertHandler((ctx, item) -> {
					com.intellij.openapi.editor.Document doc = ctx.getDocument();
					int tail = ctx.getTailOffset();
					doc.insertString(tail, assignSuffix);
					// Ставим курсор внутрь скобок (перед закрывающей)
					ctx.getEditor().getCaretModel().moveToOffset(tail + 1);
				});
			} else {
				element = element.withInsertHandler(P3VariableInsertHandler.INSTANCE);
			}

			propResult.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(element, 95));
			addedNames.add(prop.name);
		}

		for (String localProperty : Parser3BuiltinStaticPropertyUsageUtils.collectPropertyNames(fileText, className, cursorOffset)) {
			if (!addedNames.add(localProperty)) {
				continue;
			}

			String localTypeText = Parser3BuiltinStaticPropertyUsageUtils.getLocalPropertyTypeText(className);
			com.intellij.codeInsight.lookup.LookupElementBuilder element =
					com.intellij.codeInsight.lookup.LookupElementBuilder
							.create(localProperty)
							.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
							.withTypeText(localTypeText, true)
							.withTailText(" найдено в файле", true)
							.withInsertHandler(P3VariableInsertHandler.INSTANCE);

			propResult.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(element, 94));
		}
	}

	/**
	 * Добавляет имена встроенных классов с свойствами в автокомплит после $.
	 * Например: $fo → предлагает "form:" и при выборе вставляет "form:" + открывает popup.
	 * Только классы у которых есть зарегистрированные свойства (form, env, request, etc.)
	 */
	private void addBuiltinClassNameCompletions(@NotNull CompletionResultSet result) {
		java.util.Map<String, java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable>> allProps =
				ru.artlebedev.parser3.lang.Parser3BuiltinMethods.getAllProperties();

		for (java.util.Map.Entry<String, java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable>> entry : allProps.entrySet()) {
			String className = entry.getKey();
			java.util.List<ru.artlebedev.parser3.lang.Parser3BuiltinMethods.BuiltinCallable> props = entry.getValue();
			if (props.isEmpty()) continue;
			if (!ru.artlebedev.parser3.lang.Parser3BuiltinMethods.supportsStaticPropertyAccess(className)) continue;

			// Краткое описание свойств
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

			String insertText = className + ":";

			com.intellij.codeInsight.lookup.LookupElementBuilder element =
					com.intellij.codeInsight.lookup.LookupElementBuilder
							.create(insertText)
							.withLookupString(className)
							.withIcon(ru.artlebedev.parser3.icons.Parser3Icons.FileVariable)
							.withTypeText("class", true)
							.withPresentableText(className + ":")
							.withTailText(tailText.toString(), true)
							.withInsertHandler((context, item) -> {
								com.intellij.openapi.editor.Document doc = context.getDocument();
								int tailOff = context.getTailOffset();
								CharSequence txt = doc.getCharsSequence();
								int endOff = P3VariableInsertHandler.skipIdentifierChars(txt, tailOff, txt.length());
								if (endOff > tailOff) {
									doc.deleteString(tailOff, endOff);
								}
								com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject())
										.scheduleAutoPopup(context.getEditor());
							});

			result.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(element, 70));
		}

		// Перезапуск completion когда prefix изменяется (пользователь ввёл ":")
		// Это позволяет перейти от "form:" к свойствам "fields", "tables" etc.
		result.restartCompletionOnPrefixChange(
				com.intellij.patterns.StandardPatterns.string().contains(":"));
	}

	/**
	 * Собирает цепочку наследования: текущий класс + все @BASE предки.
	 * Для MAIN (ownerClass=null) возвращает null.
	 */
	private static @Nullable java.util.Set<String> buildClassHierarchy(
			@NotNull P3ClassIndex classIndex,
			@Nullable String ownerClass
	) {
		if (ownerClass == null) return null;

		java.util.Set<String> hierarchy = new java.util.LinkedHashSet<>();
		hierarchy.add(ownerClass);

		// MAIN не может иметь @BASE — нет смысла искать
		if ("MAIN".equals(ownerClass)) return hierarchy;

		String current = ownerClass;
		while (current != null && hierarchy.size() < 50) { // защита от циклов
			List<ru.artlebedev.parser3.model.P3ClassDeclaration> classes = classIndex.findByName(current);
			if (classes.isEmpty()) break;
			String base = classes.get(classes.size() - 1).getBaseClassName();
			if (base == null || base.isEmpty() || hierarchy.contains(base)) break;
			hierarchy.add(base);
			current = base;
		}
		return hierarchy;
	}

	/**
	 * Автокомплит методов/переменных класса после $var.
	 * Переиспользует P3VariableMethodCompletionContributor.addClassMethods —
	 * та же логика что и для ^var. в Parser3CompletionContributor.
	 */
	private void addObjectMethodCompletions(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result,
			@NotNull PsiFile file,
			@NotNull VirtualFile virtualFile,
			@NotNull VarPrefix varCtx
	) {
		int cursorOffset = getEditorCaretOffset(parameters);
		CompletionResultSet dotResult = result.withPrefixMatcher(varCtx.typedPrefix);

		// Подавляем стандартный word completion IntelliJ — мы сами управляем списком
		result.stopHere();

		// ЕДИНАЯ ТОЧКА: $var. — свойства класса + колонки (caretDot=false)
		P3VariableMethodCompletionContributor.completeVariableDot(
				file.getProject(), varCtx.varKey, virtualFile, cursorOffset, dotResult, false,
				varCtx.needsClosingBrace ? "}" : null, parameters.getOriginalFile().getText(),
				varCtx.receiverResolveOffset >= 0 ? varCtx.receiverResolveOffset : cursorOffset);
	}

	private static int clampOffset(@NotNull CharSequence text, int offset) {
		if (offset < 0) {
			return 0;
		}
		return Math.min(offset, text.length());
	}

	private static int getEditorCaretOffset(@NotNull CompletionParameters parameters) {
		return getCompletionTextContext(parameters).offset;
	}

	private static @NotNull CompletionTextContext getCompletionTextContext(@NotNull CompletionParameters parameters) {
		Document document = parameters.getEditor().getDocument();
		CharSequence text = document.getCharsSequence();
		int rawParameterOffset = parameters.getOffset();
		int offset = clampOffset(text, rawParameterOffset);
		if (document instanceof DocumentWindow) {
			DocumentWindow documentWindow = (DocumentWindow) document;
			Document hostDocument = documentWindow.getDelegate();
			CharSequence hostText = hostDocument.getCharsSequence();
			int mappedHostOffset = documentWindow.injectedToHost(offset);
			return new CompletionTextContext(hostText, chooseInjectedHostOffset(hostText, rawParameterOffset, mappedHostOffset));
		}

		Integer hostOffset = getInjectedHostOffset(parameters);
		if (hostOffset != null) {
			PsiFile hostFile = findParser3HostFile(parameters.getPosition().getContainingFile());
			if (hostFile != null) {
				Document hostDocument = PsiDocumentManager.getInstance(parameters.getPosition().getProject()).getDocument(hostFile);
				if (hostDocument != null) {
					CharSequence hostText = hostDocument.getCharsSequence();
					return new CompletionTextContext(hostText, chooseInjectedHostOffset(hostText, offset, hostOffset));
				}
			}
			return new CompletionTextContext(parameters.getOriginalFile().getText(), hostOffset);
		}

		int bestOffset = parameters.getOffset();
		bestOffset = Math.max(bestOffset, parameters.getEditor().getCaretModel().getOffset());
		if (parameters.getOriginalPosition() != null) {
			bestOffset = Math.max(bestOffset, parameters.getOriginalPosition().getTextOffset());
		}
		return new CompletionTextContext(text, clampOffset(text, bestOffset));
	}

	private static int chooseInjectedHostOffset(
			@NotNull CharSequence hostText,
			int rawOffset,
			int mappedHostOffset
	) {
		int mapped = clampOffset(hostText, mappedHostOffset);
		int raw = clampOffset(hostText, rawOffset);
		if (raw != mapped
				&& isLikelyParser3VariableContext(hostText, raw)
				&& !isLikelyParser3VariableContext(hostText, mapped)) {
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

	private static boolean isLikelyParser3VariableContext(@NotNull CharSequence text, int offset) {
		int safeOffset = clampOffset(text, offset);
		for (int i = safeOffset - 1; i >= 0; i--) {
			char ch = text.charAt(i);
			if (ch == '\n' || ch == '\r') {
				return false;
			}
			if (ch == '$') {
				return true;
			}
			if (ch == '<' || ch == '>' || Character.isWhitespace(ch)) {
				return false;
			}
			if (!P3CompletionUtils.isVarIdentChar(ch) && ch != '.' && ch != ':' && ch != '[' && ch != ']') {
				return false;
			}
		}
		return false;
	}
}
