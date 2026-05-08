package ru.artlebedev.parser3.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.icons.Parser3Icons;

/**
 * Автокомплит опций после директивы @OPTIONS.
 *
 * @OPTIONS
 * <курсор> — показываем: locals, partial, dynamic, static
 */
public class P3OptionsCompletionContributor extends CompletionContributor {

	public P3OptionsCompletionContributor() {
		// Для VARIABLE токенов (набираемое слово после @OPTIONS)
		extend(
				CompletionType.BASIC,
				PlatformPatterns.psiElement(Parser3TokenTypes.VARIABLE)
						.withLanguage(Parser3Language.INSTANCE),
				new OptionsCompletionProvider()
		);

		// Для WHITE_SPACE после @OPTIONS (когда ещё ничего не набрано)
		extend(
				CompletionType.BASIC,
				PlatformPatterns.psiElement()
						.withLanguage(Parser3Language.INSTANCE),
				new OptionsCompletionProvider()
		);
	}

	private static class OptionsCompletionProvider extends CompletionProvider<CompletionParameters> {

		private static final String[] OPTIONS = {"locals", "partial", "dynamic", "static"};

		@Override
		protected void addCompletions(
				@NotNull CompletionParameters parameters,
				@NotNull ProcessingContext context,
				@NotNull CompletionResultSet result) {

			PsiElement position = parameters.getPosition();
			boolean inOptions = isAfterOptionsDirective(position);
			boolean inMethodLocals = isInMethodLocalsBracket(parameters);

			if (!inOptions && !inMethodLocals) {
				return;
			}

			// Регистронезависимый автокомплит
			result = P3CompletionUtils.makeCaseInsensitive(result);

			if (inOptions) {
				// После @OPTIONS — все 4 опции, вставка с переводом строки
				for (String option : OPTIONS) {
					LookupElementBuilder el = LookupElementBuilder
							.create(option)
							.withIcon(Parser3Icons.FileMethod)
							.withInsertHandler((ctx, item) -> {
								Document doc = ctx.getDocument();
								int tail = ctx.getTailOffset();
								doc.insertString(tail, "\n");
								ctx.getEditor().getCaretModel().moveToOffset(tail + 1);
							});
					result.addElement(PrioritizedLookupElement.withPriority(el, 100));
				}
			} else {
				// Внутри @method[][...] — только "locals", курсор после ]\n
				LookupElementBuilder el = LookupElementBuilder
						.create("locals")
						.withIcon(Parser3Icons.FileMethod)
						.withInsertHandler((ctx, item) -> {
							com.intellij.openapi.editor.Editor editor = ctx.getEditor();
							Document doc = ctx.getDocument();
							int tail = ctx.getTailOffset();
							CharSequence txt = doc.getCharsSequence();
							// Пропускаем закрывающую ] если она есть
							int afterBracket = tail;
							if (afterBracket < txt.length() && txt.charAt(afterBracket) == ']') {
								afterBracket++;
							}
							// Вставляем перевод строки после ]
							doc.insertString(afterBracket, "\n");
							editor.getCaretModel().moveToOffset(afterBracket + 1);
						});
				result.addElement(PrioritizedLookupElement.withPriority(el, 100));
			}
		}

		/**
		 * Проверяет находится ли позиция после @OPTIONS директивы и до следующего @.
		 * Использует текстовый поиск — надёжнее чем PSI-обход через несколько строк.
		 */
		private boolean isAfterOptionsDirective(@NotNull PsiElement element) {
			PsiFile file = element.getContainingFile();
			if (file == null) return false;

			String text = file.getText();
			int offset = element.getTextRange().getStartOffset();

			// Ищем @OPTIONS назад от курсора
			String before = text.substring(0, Math.min(offset, text.length()));
			int optionsIdx = before.lastIndexOf("@OPTIONS");
			if (optionsIdx < 0) return false;

			// Между @OPTIONS и курсором не должно быть другого @
			String between = before.substring(optionsIdx + 8); // 8 = "@OPTIONS".length()
			return !between.contains("@");
		}

		/**
		 * Проверяет находится ли курсор внутри вторых квадратных скобок @method[][...].
		 * Паттерн: @methodName[params][<cursor>
		 */
		private boolean isInMethodLocalsBracket(@NotNull CompletionParameters parameters) {
			CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
			int offset = clampOffset(text, parameters.getOffset());

			// Ищем назад от курсора открывающую [ на той же строке
			int openBracket = -1;
			for (int i = offset - 1; i >= 0; i--) {
				char ch = text.charAt(i);
				if (ch == '\n' || ch == '\r') break;
				if (ch == '[') { openBracket = i; break; }
				// Внутри скобки могут быть буквы, ;, пробелы (имена переменных)
				if (ch == ']') break; // закрытая скобка — мы не внутри
			}
			if (openBracket < 0) return false;

			// Перед [ должно быть ][ — т.е. сразу ] (конец первых скобок)
			if (openBracket == 0 || text.charAt(openBracket - 1) != ']') return false;
			int firstClose = openBracket - 1;

			// Ищем открывающую [ первых скобок
			int depth = 1;
			int firstOpen = -1;
			for (int i = firstClose - 1; i >= 0; i--) {
				char ch = text.charAt(i);
				if (ch == ']') depth++;
				else if (ch == '[') {
					depth--;
					if (depth == 0) { firstOpen = i; break; }
				}
			}
			if (firstOpen < 0) return false;

			// Перед первой [ должно быть @имяМетода
			int nameEnd = firstOpen;
			int nameStart = nameEnd;
			while (nameStart > 0 && (Character.isLetterOrDigit(text.charAt(nameStart - 1)) || text.charAt(nameStart - 1) == '_')) {
				nameStart--;
			}
			if (nameStart <= 0 || nameStart == nameEnd) return false;

			// Перед именем должен быть @
			return text.charAt(nameStart - 1) == '@';
		}

		private static int clampOffset(@NotNull CharSequence text, int offset) {
			if (offset < 0) {
				return 0;
			}
			return Math.min(offset, text.length());
		}
	}
}
