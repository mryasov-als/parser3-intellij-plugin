package ru.artlebedev.parser3.lang;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.openapi.editor.Document;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.Nullable;


import ru.artlebedev.parser3.templates.Parser3TemplateDescriptor;
import ru.artlebedev.parser3.templates.Parser3UserTemplatesService;
import org.jetbrains.annotations.NotNull;

import ru.artlebedev.parser3.icons.Parser3Icons;
import ru.artlebedev.parser3.psi.Parser3SqlBlock;
import ru.artlebedev.parser3.utils.Parser3PsiUtils;
import ru.artlebedev.parser3.utils.Parser3TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * CompletionContributor для языка Parser3.
 *
 * Разделение:
 *  - после '^xxx' подсказываем только конструкторы/статические методы: ^hash::create[], ^hash::sql{}, ^file:list[];
 *  - после точки ( ^hash. ) подсказываем только объектные методы: add[], contains[], select() и т.п.;
 *  - $var. игнорируем — для переменных автокомплит не нужен.
 */
public class Parser3CompletionContributor extends CompletionContributor {

	private static final double USER_TEMPLATE_PRIORITY = -1000.0;
	private static final String[] SQL_KEYWORDS = {
			"SELECT", "FROM", "WHERE", "AND", "OR", "ORDER", "GROUP", "BY", "HAVING", "LIMIT",
			"JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "IN", "IS", "NOT", "NULL",
			"AS", "INSERT", "UPDATE", "DELETE", "VALUES", "SET"
	};

	public Parser3CompletionContributor() {

		extend(
				CompletionType.BASIC,
				PlatformPatterns.psiElement(),
				new CompletionProvider<CompletionParameters>() {
					@Override
					protected void addCompletions(@NotNull CompletionParameters parameters,
												  @NotNull ProcessingContext context,
												  @NotNull CompletionResultSet result) {

						PsiElement position = parameters.getPosition();
						if (position == null) {
							return;
						}


						PsiFile originalFile = parameters.getOriginalFile();
						if (!Parser3PsiUtils.isParser3File(originalFile)) {
							return;
						}

						// Регистронезависимый автокомплит для Parser3
						result = ru.artlebedev.parser3.completion.P3CompletionUtils.makeCaseInsensitive(result);


						CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
						int offset = parameters.getOffset();

						// Проверка границ для injected документов
						if (offset > text.length()) {
							return;
						}

						if (addPlainTextSqlKeywordFallback(parameters, result)) {
							return;
						}

						if (parameters.isAutoPopup() && isInTableColumnArgumentContext(originalFile, text, offset)) {
							return;
						}

						boolean dollarVariableDotContext =
								ru.artlebedev.parser3.completion.P3CompletionUtils
										.isDollarVariableDotCompletionContext(text, offset);
						boolean explicitCompletion = !parameters.isAutoPopup();
						boolean suppressExplicitUserTemplates =
								ru.artlebedev.parser3.completion.P3VariableInsertHandler
										.consumeVariableDotAutoPopupUserTemplateSuppression(parameters.getEditor());
						boolean caretMethodDotContext = isCaretMethodDotContext(text, offset);
						boolean explicitUserTemplatesAdded = false;
						if (explicitCompletion && !suppressExplicitUserTemplates && !dollarVariableDotContext && !caretMethodDotContext) {
							fillUserTemplates(result.withPrefixMatcher(""), null);
							explicitUserTemplatesAdded = true;
						}

						ru.artlebedev.parser3.completion.P3CompletionUtils.addBooleanLiteralCompletionsIfNeeded(text, offset, result);

						if (dollarVariableDotContext) {
							if (explicitCompletion && !suppressExplicitUserTemplates) {
								fillUserTemplates(result.withPrefixMatcher(""), null);
							}
							return;
						}

						// Гарантированный bracket-context для $var[<CURSOR>]
						int off = offset;
						if (off > 0 && off <= text.length() && text.charAt(off - 1) == '[') {
							if (!explicitUserTemplatesAdded) {
								result = result.withPrefixMatcher("");
								fillUserTemplates(result,null);
							}
							return;
						}
						String txt = Parser3TextUtils.extractLeftSegment(text, offset);

						// Если прямо перед кареткой мы стоим после открывающей скобки
						// и при этом в этой же строке нет ни '^', ни '.', считаем это "нейтральной" зоной
						// и показываем пользовательские шаблоны (например: $var[<CURSOR>]).
						int scan = offset - 1;
						boolean neutralBracketContext = false;
						while (scan >= 0 && scan < text.length()) {
							char ch = text.charAt(scan);
							if (ch == '\n' || ch == '\r') {
								break;
							}
							if (ch == '^' || ch == '.') {
								// есть ^ или . слева в той же строке — пусть работает обычная логика
								break;
							}
							if (!Character.isWhitespace(ch)) {
								if (ch == '[' || ch == '(' || ch == '{') {
									neutralBracketContext = true;
								}
								break;
							}
							scan--;
						}
						if (neutralBracketContext) {
							if (!explicitUserTemplatesAdded) {
								result = result.withPrefixMatcher("");
								fillUserTemplates(result, null);
							}
							return;
						}


						// Если в текущей строке нет ни '^', ни '.', показываем только пользовательские шаблоны
						int lineStart = offset - 1;
						while (lineStart >= 0 && lineStart < text.length()) {
							char ch = text.charAt(lineStart);
							if (ch == '\n' || ch == '\r') {
								lineStart++;
								break;
							}
							lineStart--;
						}
						if (lineStart < 0) {
							lineStart = 0;
						}
						CharSequence linePrefixSeq = text.subSequence(lineStart, offset);
						String linePrefix = linePrefixSeq.toString();

						boolean hasCaret = linePrefix.indexOf('^') != -1;
						boolean hasDot = linePrefix.indexOf('.') != -1;

						// Если есть точка но нет ^ — не показываем ничего
						// (точка сама по себе не является контекстом для автокомплита)
						if (hasDot && !hasCaret) {
							if (suppressExplicitUserTemplates && !dollarVariableDotContext) {
								fillUserTemplates(result.withPrefixMatcher(""), ".");
							}
							return;
						}

						// Если нет ни ^, ни . — показываем пользовательские шаблоны
						// Но не в контексте $builtinClass: (form:, env:, request:, etc.)
						if (!hasCaret && !hasDot) {
							if (!isInBuiltinClassPropContext(linePrefix)) {
								// Если в строке есть $ — показываем только шаблоны с prefix "$"
								if (!explicitUserTemplatesAdded) {
									boolean hasDollar = linePrefix.indexOf('$') != -1;
									fillUserTemplates(result, hasDollar ? "$" : null);
								}
							}
							return;
						}


						if (txt.indexOf('^') == -1) {
							// Нет ^ в сегменте — не показываем ничего
							return;
						}
						String escapedText = Parser3TextUtils.escapedString(txt);
						if (escapedText.indexOf('^') == -1) {
							// ^ заэкранирован — не показываем ничего
							return;
						}




						int lastDot = txt.lastIndexOf('.');
						int lastCaret = txt.lastIndexOf('^');
						boolean hasDotOutsideMask = escapedText.indexOf('.') > -1;
						if (hasDotOutsideMask && lastDot > -1 && lastDot > lastCaret) {
							// ^var.method — обрабатывается P3MethodCompletionContributor.completeVariableDot
							// Не дублируем здесь, чтобы не было двойных результатов
							if (!explicitUserTemplatesAdded && explicitCompletion && ru.artlebedev.parser3.completion.P3CompletionUtils.hasMethodPrefixStopChar(txt, lastCaret + 1, lastDot)) {
								fillUserTemplates(result.withPrefixMatcher(""), null);
							}
							return;
						} else {
							// конструкторы и статистические методы
							int caretPos = txt.lastIndexOf('^');
							if (caretPos == -1) return;
							String prefix = txt.substring(caretPos + 1);
							CompletionResultSet caretResult = result.withPrefixMatcher(prefix);
							fillConstructorsAndStaticMethods(caretResult, !explicitUserTemplatesAdded);
						}
					}
				}
		);
	}

	@Override
	public boolean invokeAutoPopup(@NotNull PsiElement position, char typeChar) {
		return Character.isLetter(typeChar) && isPlainTextSqlInjection(position);
	}

	private static boolean isPlainTextSqlInjection(@NotNull PsiElement position) {
		PsiFile injectedFile = position.getContainingFile();
		return injectedFile != null
				&& "TEXT".equals(injectedFile.getLanguage().getID())
				&& injectedFile.getContext() instanceof Parser3SqlBlock;
	}

	private static boolean addPlainTextSqlKeywordFallback(
			@NotNull CompletionParameters parameters,
			@NotNull CompletionResultSet result
	) {
		Document document = parameters.getEditor().getDocument();
		if (!(document instanceof DocumentWindow)) {
			return false;
		}

		if (!isPlainTextSqlInjection(parameters.getPosition())) {
			return false;
		}

		DocumentWindow documentWindow = (DocumentWindow) document;
		CharSequence injectedText = document.getCharsSequence();
		int injectedOffset = clampOffset(injectedText, parameters.getOffset());
		int hostOffset = documentWindow.injectedToHost(injectedOffset);
		CharSequence hostText = documentWindow.getDelegate().getCharsSequence();
		if (hostOffset < 0 || hostOffset > hostText.length()) {
			return false;
		}
		if (ru.artlebedev.parser3.completion.P3CompletionUtils.isParser3AutoPopupPrefixContext(hostText, hostOffset)
				|| ru.artlebedev.parser3.completion.P3CompletionUtils.isEmbeddedParser3CallInsideSql(hostText, hostOffset)
				|| ru.artlebedev.parser3.completion.P3CompletionUtils.isInsideParser3PrefixInSql(hostText, hostOffset)) {
			return false;
		}

		CompletionResultSet sqlResult = ru.artlebedev.parser3.completion.P3CompletionUtils.makeCaseInsensitive(result);
		for (String keyword : SQL_KEYWORDS) {
			sqlResult.addElement(PrioritizedLookupElement.withPriority(
					LookupElementBuilder.create(keyword).withBoldness(true),
					50.0
			));
		}
		return true;
	}

	private static int clampOffset(@NotNull CharSequence text, int offset) {
		if (offset < 0) {
			return 0;
		}
		return Math.min(offset, text.length());
	}

	private static boolean isCaretMethodDotContext(
			@NotNull CharSequence text,
			int offset
	) {
		int safeOffset = Math.max(0, Math.min(offset, text.length()));
		int lineStart = safeOffset;
		while (lineStart > 0) {
			char ch = text.charAt(lineStart - 1);
			if (ch == '\n' || ch == '\r') {
				break;
			}
			lineStart--;
		}

		int prefixStart = safeOffset;
		while (prefixStart > lineStart && ru.artlebedev.parser3.completion.P3CompletionUtils.isVarIdentChar(text.charAt(prefixStart - 1))) {
			prefixStart--;
		}
		if (prefixStart <= lineStart || text.charAt(prefixStart - 1) != '.') {
			return false;
		}

		for (int i = prefixStart - 2; i >= lineStart; i--) {
			char ch = text.charAt(i);
			if (ch == '^') {
				return true;
			}
			if (ru.artlebedev.parser3.completion.P3CompletionUtils.isMethodPrefixStopChar(ch)) {
				return false;
			}
		}
		return false;
	}

	private static boolean isInTableColumnArgumentContext(
			@NotNull PsiFile originalFile,
			@NotNull CharSequence text,
			int offset
	) {
		VirtualFile virtualFile = originalFile.getVirtualFile();
		if (virtualFile == null) {
			return false;
		}
		return ru.artlebedev.parser3.completion.P3TableColumnArgumentCompletionSupport.isColumnArgumentContext(
				originalFile.getProject(),
				virtualFile,
				text.toString(),
				offset
		);
	}

	private static void fillConstructorsAndStaticMethodsHelper(CompletionResultSet result, String separator, List<Parser3BuiltinMethods.CaretConstructor> list) {
		for (Parser3BuiltinMethods.CaretConstructor item : list) {
			String className   = item.className;
			String name        = item.callable.name;
			String description = item.callable.description;
			String suffix      = item.callable.suffix;	// "[]", "{}", "()"
			String template    = item.callable.template;
			String url         = item.callable.url;

			String comment;
			if (description == null || description.isEmpty()) {
				comment = description; // или ""
			} else {
				comment = description.substring(0, 1).toLowerCase() + description.substring(1);
			}

			// Lookup string БЕЗ скобок - они добавятся в InsertHandler
			String lookupText = className + separator + name;
			String presentableText = "^" + lookupText + suffix;

			// Создаём объект с информацией о документации
			Parser3DocLookupObject docObject = new Parser3DocLookupObject(lookupText, url, description);

			LookupElementBuilder builder;

			// Если есть template с маркерами — используем MoveCaretInsideBracesHandler
			if (template != null && !template.isEmpty()) {
				builder = LookupElementBuilder.create(docObject, lookupText + suffix)
						.withIcon(Parser3Icons.File)
						.withPresentableText(presentableText)
						.withTailText(" " + comment, true)
						.withLookupString(className)
						.withLookupString(name)
						.withInsertHandler(new MoveCaretInsideBracesHandler(template, suffix));
			} else {
				// Обычный метод - используем P3MethodInsertHandler
				builder = LookupElementBuilder.create(docObject, lookupText)
						.withIcon(Parser3Icons.File)
						.withPresentableText(presentableText)
						.withTailText(" " + comment, true)
						.withLookupString(className)
						.withLookupString(name)
						.withInsertHandler(ru.artlebedev.parser3.completion.P3MethodInsertHandler.withSuffix(suffix));
			}

			// Для :: добавляем также className: для матчинга date:
			if ("::".equals(separator)) {
				builder = builder.withLookupString(className + ":");
			}

			result.addElement(builder);
		}
	}

	private static void fillConstructorsAndStaticMethods (@NotNull CompletionResultSet result) {
		fillConstructorsAndStaticMethods(result, true);
	}

	private static void fillConstructorsAndStaticMethods (@NotNull CompletionResultSet result, boolean includeUserTemplates) {
		if (includeUserTemplates) {
			fillUserTemplates(result, "^");
		}
		fillConstructorsAndStaticMethodsHelper(result, "", Parser3BuiltinMethods.getAllSystemMethodsMeta());
		fillConstructorsAndStaticMethodsHelper(result, ":", Parser3BuiltinMethods.getAllStaticMethodsMeta());
		fillConstructorsAndStaticMethodsHelper(result, "::", Parser3BuiltinMethods.getAllConstructorsMeta());
	}


	private static void fillMethods (@NotNull CompletionResultSet result) {
		fillUserTemplates(result, ".");
		for (Parser3BuiltinMethods.CaretConstructor item : Parser3BuiltinMethods.getAllMethodsMeta()) {
			String name        = item.callable.name;
			String description = item.callable.description;
			String suffix      = item.callable.suffix;	// "[]", "{}", "()"
			String url         = item.callable.url;

			// Создаём объект с информацией о документации
			Parser3DocLookupObject docObject = new Parser3DocLookupObject("." + name, url, description);

			// Lookup string БЕЗ скобок - они добавятся в InsertHandler
			result.addElement(
					LookupElementBuilder.create(docObject, name)
							.withIcon(Parser3Icons.File)
							.withPresentableText("." + name + suffix)
							.withTailText(" " + description, true)
							.withInsertHandler(ru.artlebedev.parser3.completion.P3MethodInsertHandler.withSuffix(suffix))
			);
		}
	}


	/**
	 * Проверяет находимся ли мы в контексте $builtinClass: (например $form:, $env:, $request:).
	 * В этом контексте пользовательские шаблоны не нужны.
	 */
	private static boolean isInBuiltinClassPropContext(@NotNull String linePrefix) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$([a-z][a-z0-9_]*):\\s*$")
				.matcher(linePrefix);
		if (!m.find()) {
			// Также проверяем $class:partialField (без пробела в конце)
			m = java.util.regex.Pattern.compile("\\$([a-z][a-z0-9_]*):[\\p{L}0-9_]*$")
					.matcher(linePrefix);
			if (!m.find()) return false;
		}
		String className = m.group(1);
		return ru.artlebedev.parser3.lang.Parser3BuiltinMethods.isBuiltinClass(className)
				&& ru.artlebedev.parser3.lang.Parser3BuiltinMethods.supportsStaticPropertyAccess(className);
	}

	public static void fillUserTemplates(@NotNull CompletionResultSet result, @Nullable String selectorPrefix) {
		Parser3UserTemplatesService service = Parser3UserTemplatesService.getInstance();
		if (service == null) {
			return;
		}

		List<String> used = new ArrayList<>();
		for (Parser3TemplateDescriptor descriptor : service.getEnabledTemplateDescriptors()) {
			if (descriptor == null) {
				continue;
			}
			String prefix = descriptor.prefix != null ? descriptor.prefix : "";
			if (selectorPrefix != null && !selectorPrefix.isEmpty() && !selectorPrefix.equals(prefix)) {
				continue;
			}
			String name = descriptor.name != null ? descriptor.name : "";
			String suffix = descriptor.suffix != null ? descriptor.suffix : "";
			String comment = descriptor.comment != null ? descriptor.comment : "";
			String lookupText = name + suffix;
			String body = descriptor.template != null ? descriptor.template.body : null;
			LookupElementBuilder builder;
			if (used.contains(lookupText)) {
				builder = LookupElementBuilder.create(descriptor, lookupText);
			} else {
				builder = LookupElementBuilder.create(lookupText);
			}
			String presentableText = prefix + lookupText;
			String rawName = descriptor.template != null && descriptor.template.name != null ? descriptor.template.name.trim() : "";
			if (!rawName.isEmpty() && rawName.startsWith("^")) {
				presentableText = rawName;
			}
			builder = builder.withIcon(Parser3Icons.FileTemplate)
					.withPresentableText(presentableText)
					.withInsertHandler(new MoveCaretInsideBracesHandler(body, suffix));
			if (!comment.isEmpty()) {
				builder = builder.withTailText(" " + comment, true);
			}
			if (selectorPrefix != null && !selectorPrefix.isEmpty()) {
				builder = builder.bold();
			}
			used.add(lookupText);
			result.addElement(PrioritizedLookupElement.withPriority(builder, USER_TEMPLATE_PRIORITY));
		}
	}

	private static class MoveCaretInsideBracesHandler implements com.intellij.codeInsight.completion.InsertHandler<LookupElement> {
		private static final String CURSOR_MARKER = "<CURSOR>";
		private static final String CURSOR_CLOSE_MARKER = "</CURSOR>";
		private static final String LEGACY_CURSOR_MARKER = "$CURSOR$";

		private final String template;
		private final String suffix;

		public MoveCaretInsideBracesHandler(String template, String suffix) {
			this.template = template;
			this.suffix = suffix != null ? suffix : "";
		}

		@Override
		public void handleInsert(@NotNull com.intellij.codeInsight.completion.InsertionContext context,
								 @NotNull LookupElement item) {
			com.intellij.openapi.editor.Editor editor = context.getEditor();
			com.intellij.openapi.editor.Document doc = editor.getDocument();
			int tail = context.getTailOffset();
			if (tail == 0) {
				return;
			}
			if (doc instanceof DocumentWindow) {
				char head = template != null && !template.isEmpty() ? template.charAt(0) : 0;
				if (head == '.' || head == '^' || head == '$') {
					handleInjectedInsert(context, item, (DocumentWindow) doc);
					return;
				}
			}

			// Сначала удаляем остаток старого имени метода (до скобки, пробела, ^, $ и т.д.)
			CharSequence text = doc.getCharsSequence();
			int textLength = text.length();
			int endOffset = tail;
			while (endOffset < textLength) {
				char c = text.charAt(endOffset);
				if (c == '[' || c == '(' || c == '{' ||
						c == ']' || c == ')' || c == '}' ||
						c == ' ' || c == '\t' ||
						c == '^' || c == '$' ||
						c == '\n' || c == '\r') {
					break;
				}
				endOffset++;
			}
			if (endOffset > tail) {
				doc.deleteString(tail, endOffset);
			}

			// Обновляем tail после удаления
			tail = context.getTailOffset();

			// Нет шаблона — старая логика: просто двигаем каретку внутрь скобок
			if (template == null || template.isEmpty()) {
				editor.getCaretModel().moveToOffset(tail - 1);
				return;
			}

			int baseStart = context.getStartOffset();
			String rawTemplate = template;
			String suffix = this.suffix != null ? this.suffix : "";
			String lookupString = item.getLookupString();

			// Определяем, откуда заменять: если template начинается с '.' или '^',
			// пробуем найти этот символ слева от каретки в пределах текущей строки.
			// Если не нашли — вырезаем всю только что вставленную lookup-строку,
			// а в крайнем случае — только суффикс ([], {}, …).
			int start = tail;
			char head = rawTemplate.charAt(0);
			if (head == '.' || head == '^' || head == '$') {
				int line = doc.getLineNumber(tail);
				int lineStart = doc.getLineStartOffset(line);
				CharSequence chars = doc.getCharsSequence();
				int i = tail - 1;
				while (i >= lineStart) {
					char c = chars.charAt(i);
					if (c == head) {
						start = i;
						break;
					}
					i--;
				}
			}
			// Никогда не заходим левее исходного startOffset, чтобы не сносить ^var и прочее,
			// когда completion вызван на "пустом" месте (Ctrl+Space без префикса).
			// Но если непосредственно перед startOffset стоит '^' или '.', значит префикс уже набран,
			// и нужно уметь расшириться до этого символа (например: $var[^cu$] -> ^curl:load[...]).
			if (start < baseStart) {
				CharSequence chars2 = doc.getCharsSequence();
				if (baseStart > 0) {
					char prev = chars2.charAt(baseStart - 1);
					if (prev != '^' && prev != '.' && prev != '$') {
						start = baseStart;
					}
				} else {
					start = baseStart;
				}
			} {
				CharSequence chars2 = doc.getCharsSequence();
				if (baseStart <= 0) {
					start = baseStart;
				} else {
					char prev = chars2.charAt(baseStart - 1);
					if (prev != '^' && prev != '.' && prev != '$') {
						start = baseStart;
					}
				}
			}
			if (head == '.' || head == '^' || head == '$') {
				int boundedBaseStart = Math.max(0, Math.min(baseStart, doc.getTextLength()));
				int baseLine = doc.getLineNumber(boundedBaseStart);
				int baseLineStart = doc.getLineStartOffset(baseLine);
				CharSequence chars3 = doc.getCharsSequence();
				int selectorStart = -1;
				for (int i = Math.min(boundedBaseStart - 1, chars3.length() - 1); i >= baseLineStart; i--) {
					char c = chars3.charAt(i);
					if (c == head) {
						selectorStart = i;
						break;
					}
					if (c == '[' || c == '(' || c == '{' ||
							c == ']' || c == ')' || c == '}' ||
							c == ' ' || c == '\t' ||
							c == '\n' || c == '\r') {
						break;
					}
				}
				if (selectorStart >= 0) {
					start = selectorStart;
				}
			}
			if (start == tail) {
				if (lookupString != null && !lookupString.isEmpty()) {
					start = Math.max(0, tail - lookupString.length());
				} else {
					start = Math.max(0, tail - suffix.length());
				}
			}

			// Вычисляем базовый отступ строки, где начинается конструкция
			int line = doc.getLineNumber(start);
			int lineStart = doc.getLineStartOffset(line);
			CharSequence chars = doc.getCharsSequence();
			int p = lineStart;
			while (p < start) {
				char ch = chars.charAt(p);
				if (ch != ' ' && ch != '\t') {
					break;
				}
				p++;
			}
			String baseIndent = chars.subSequence(lineStart, p).toString();

			// Берём настройки отступов из CodeStyle
			com.intellij.psi.PsiFile file = context.getFile();

			com.intellij.psi.codeStyle.CodeStyleSettings settings =
					com.intellij.application.options.CodeStyle.getSettings(file);

			com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions opts =
					com.intellij.application.options.CodeStyle.getIndentOptions(file);




			String unitIndent;
			if (opts.USE_TAB_CHARACTER) {
				unitIndent = "\t";
			} else {
				int size = Math.max(1, opts.INDENT_SIZE);
				StringBuilder sb = new StringBuilder(size);
				for (int i = 0; i < size; i++) {
					sb.append(' ');
				}
				unitIndent = sb.toString();
			}

			// Применяем отступы:
			// \n -> \n + baseIndent
			// \t -> unitIndent
			StringBuilder templated = new StringBuilder(rawTemplate.length() + 32);
			for (int i = 0; i < rawTemplate.length(); i++) {
				char ch = rawTemplate.charAt(i);
				if (ch == '\n') {
					templated.append('\n').append(baseIndent);
				} else if (ch == '\t') {
					templated.append(unitIndent);
				} else {
					templated.append(ch);
				}
			}
			String tpl = templated.toString();

			TemplateMarkers markers = extractMarkers(tpl);

			// Заменяем текст в документе
			doc.deleteString(start, tail);
			doc.insertString(start, markers.text);

			// Если есть оба маркера выделения, выделение важнее одиночной позиции каретки.
			if (markers.selectionStart >= 0 && markers.selectionEnd >= 0) {
				int selectionStart = start + Math.min(markers.selectionStart, markers.selectionEnd);
				int selectionEnd = start + Math.max(markers.selectionStart, markers.selectionEnd);
				editor.getCaretModel().moveToOffset(selectionEnd);
				editor.getSelectionModel().setSelection(selectionStart, selectionEnd);
			} else if (markers.cursor >= 0) {
				editor.getCaretModel().moveToOffset(start + markers.cursor);
			}
		}

		private void handleInjectedInsert(
				@NotNull com.intellij.codeInsight.completion.InsertionContext context,
				@NotNull LookupElement item,
				@NotNull DocumentWindow injectedDocument
		) {
			com.intellij.openapi.editor.Editor editor = context.getEditor();
			com.intellij.openapi.editor.Document hostDocument = injectedDocument.getDelegate();
			int injectedTail = context.getTailOffset();
			int tail = injectedDocument.injectedToHost(injectedTail);
			if (tail <= 0 || tail > hostDocument.getTextLength()) {
				return;
			}

			String rawTemplate = template;
			String suffix = this.suffix != null ? this.suffix : "";
			String lookupString = item.getLookupString();

			int baseStart = injectedDocument.injectedToHost(Math.max(0, context.getStartOffset()));
			int start = findTemplateReplacementStart(hostDocument, tail, baseStart, rawTemplate.charAt(0), lookupString, suffix);
			// В HTML-injection диапазон замены не должен уходить за границу Parser3-вызова и съедать тег.
			tail = findTemplateReplacementTail(hostDocument, start, tail);

			String baseIndent = getBaseIndent(hostDocument, start);
			String unitIndent = getUnitIndent(context.getFile());
			TemplateMarkers markers = extractMarkers(applyTemplateIndent(rawTemplate, baseIndent, unitIndent));

			hostDocument.deleteString(start, tail);
			hostDocument.insertString(start, markers.text);

			if (markers.selectionStart >= 0 && markers.selectionEnd >= 0) {
				int selectionStart = start + Math.min(markers.selectionStart, markers.selectionEnd);
				int selectionEnd = start + Math.max(markers.selectionStart, markers.selectionEnd);
				moveInjectedCaret(editor, injectedDocument, selectionEnd);
				int injectedSelectionStart = injectedDocument.hostToInjected(selectionStart);
				int injectedSelectionEnd = injectedDocument.hostToInjected(selectionEnd);
				if (injectedSelectionStart >= 0 && injectedSelectionEnd >= 0) {
					editor.getSelectionModel().setSelection(injectedSelectionStart, injectedSelectionEnd);
				}
			} else if (markers.cursor >= 0) {
				moveInjectedCaret(editor, injectedDocument, start + markers.cursor);
			}
		}

		private static int findTemplateReplacementStart(
				@NotNull com.intellij.openapi.editor.Document doc,
				int tail,
				int baseStart,
				char head,
				@Nullable String lookupString,
				@NotNull String suffix
		) {
			CharSequence chars = doc.getCharsSequence();
			int safeTail = Math.max(0, Math.min(tail, doc.getTextLength()));
			int line = doc.getLineNumber(safeTail);
			int lineStart = doc.getLineStartOffset(line);
			int start = safeTail;
			int scanTail = safeTail;
			if (!suffix.isEmpty() && scanTail >= suffix.length()
					&& suffix.contentEquals(chars.subSequence(scanTail - suffix.length(), scanTail))) {
				scanTail -= suffix.length();
			}

			for (int i = scanTail - 1; i >= lineStart; i--) {
				char c = chars.charAt(i);
				if (c == head) {
					start = i;
					break;
				}
				if (c == '[' || c == '(' || c == '{' ||
						c == ']' || c == ')' || c == '}' ||
						c == ' ' || c == '\t' ||
						c == '\n' || c == '\r') {
					break;
				}
			}

			if (start == safeTail) {
				if (lookupString != null && !lookupString.isEmpty()) {
					start = Math.max(0, safeTail - lookupString.length());
				} else {
					start = Math.max(0, safeTail - suffix.length());
				}
			}

			if (start < baseStart) {
				if (baseStart <= 0) {
					start = baseStart;
				} else {
					char prev = chars.charAt(baseStart - 1);
					if (prev != '^' && prev != '.' && prev != '$') {
						start = baseStart;
					}
				}
			}
			return Math.max(0, Math.min(start, safeTail));
		}

		private static int findTemplateReplacementTail(
				@NotNull com.intellij.openapi.editor.Document doc,
				int start,
				int tail
		) {
			CharSequence chars = doc.getCharsSequence();
			int safeStart = Math.max(0, Math.min(start, doc.getTextLength()));
			int safeTail = Math.max(safeStart, Math.min(tail, doc.getTextLength()));
			int i = safeStart;
			while (i < safeTail) {
				char c = chars.charAt(i);
				if (c == '<' || c == '>' || c == '\n' || c == '\r') {
					break;
				}
				i++;
			}
			return i;
		}

		private static @NotNull String getBaseIndent(@NotNull com.intellij.openapi.editor.Document doc, int offset) {
			int line = doc.getLineNumber(Math.max(0, Math.min(offset, doc.getTextLength())));
			int lineStart = doc.getLineStartOffset(line);
			CharSequence chars = doc.getCharsSequence();
			int p = lineStart;
			while (p < offset && p < chars.length()) {
				char ch = chars.charAt(p);
				if (ch != ' ' && ch != '\t') {
					break;
				}
				p++;
			}
			return chars.subSequence(lineStart, p).toString();
		}

		private static @NotNull String getUnitIndent(@NotNull com.intellij.psi.PsiFile file) {
			com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions opts =
					com.intellij.application.options.CodeStyle.getIndentOptions(file);
			if (opts.USE_TAB_CHARACTER) {
				return "\t";
			}
			int size = Math.max(1, opts.INDENT_SIZE);
			StringBuilder sb = new StringBuilder(size);
			for (int i = 0; i < size; i++) {
				sb.append(' ');
			}
			return sb.toString();
		}

		private static @NotNull String applyTemplateIndent(
				@NotNull String rawTemplate,
				@NotNull String baseIndent,
				@NotNull String unitIndent
		) {
			StringBuilder templated = new StringBuilder(rawTemplate.length() + 32);
			for (int i = 0; i < rawTemplate.length(); i++) {
				char ch = rawTemplate.charAt(i);
				if (ch == '\n') {
					templated.append('\n').append(baseIndent);
				} else if (ch == '\t') {
					templated.append(unitIndent);
				} else {
					templated.append(ch);
				}
			}
			return templated.toString();
		}

		private static void moveInjectedCaret(
				@NotNull com.intellij.openapi.editor.Editor editor,
				@NotNull DocumentWindow injectedDocument,
				int hostOffset
		) {
			int injectedOffset = injectedDocument.hostToInjected(hostOffset);
			if (injectedOffset >= 0) {
				editor.getCaretModel().moveToOffset(injectedOffset);
			}
		}

		private static TemplateMarkers extractMarkers(@NotNull String text) {
			StringBuilder result = new StringBuilder(text.length());
			int cursor = -1;
			int selectionStart = -1;
			int selectionEnd = -1;
			int i = 0;
			while (i < text.length()) {
				if (text.startsWith(CURSOR_MARKER, i)) {
					if (selectionStart < 0 && text.indexOf(CURSOR_CLOSE_MARKER, i + CURSOR_MARKER.length()) >= 0) {
						selectionStart = result.length();
					} else if (cursor < 0) {
						cursor = result.length();
					}
					i += CURSOR_MARKER.length();
				} else if (text.startsWith(CURSOR_CLOSE_MARKER, i)) {
					if (selectionStart >= 0 && selectionEnd < 0) {
						selectionEnd = result.length();
					}
					i += CURSOR_CLOSE_MARKER.length();
				} else if (text.startsWith(LEGACY_CURSOR_MARKER, i)) {
					if (cursor < 0) {
						cursor = result.length();
					}
					i += LEGACY_CURSOR_MARKER.length();
				} else {
					result.append(text.charAt(i));
					i++;
				}
			}
			return new TemplateMarkers(result.toString(), cursor, selectionStart, selectionEnd);
		}

		private static class TemplateMarkers {
			private final String text;
			private final int cursor;
			private final int selectionStart;
			private final int selectionEnd;

			private TemplateMarkers(String text, int cursor, int selectionStart, int selectionEnd) {
				this.text = text;
				this.cursor = cursor;
				this.selectionStart = selectionStart;
				this.selectionEnd = selectionEnd;
			}
		}
	}



}
