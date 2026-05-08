package ru.artlebedev.parser3.formatting;

import com.intellij.formatting.Block;
import com.intellij.formatting.ChildAttributes;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Spacing;
import com.intellij.formatting.SpacingBuilder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.tree.IElementType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Parser3Block extends AbstractBlock {

	// Флаг для отладки форматирования
	private static final boolean DEBUG_FORMAT = false;

	private final SpacingBuilder spacingBuilder;
	private final CommonCodeStyleSettings.IndentOptions indentOptions;
	private final boolean rootBlock;
	private final int indentColumns;
	private final TextRange formattingRange;





	static final class LineInfo {
		int lineIndex;
		int level;
		int originalLevel;
		int startOffset;
		int endOffset;
		String text;
		String textWithoutComment;
		List<IElementType> tokens = new ArrayList<>();
		boolean hashComment;
		boolean levelSet = false; // Флаг что level уже установлен (для REM блоков)

		static void setLevels(@NotNull List<LineInfo> lines) {

			List<Integer> stack = new ArrayList<>();
			int indentLevel = 0;
			boolean inTableBlock = false;
			int tableReturnLevel = 0;
			java.util.Deque<Integer> remStack = new java.util.ArrayDeque<>();

			for (LineInfo li : lines) {

				// Строка была hash-комментарием (#) до pre-форматтера: не влияем на стек скобок
				if (li.hashComment) {
					li.level = indentLevel;
					continue;
				}

				// базовый отступ для строки из текущего стека
				if (stack.isEmpty()) {
					indentLevel = 0;
				} else {
					indentLevel = stack.get(stack.size() - 1) + 1;
				}

				int lineLevel = indentLevel;

				String txt = li.textWithoutComment;

				// правило 1: строка метода '@' сбрасывает общий отступ
				if (txt != null && !txt.isEmpty() && txt.charAt(0) == '@') {
					stack.clear();
					indentLevel = 0;
					lineLevel = 0;
				}

				// правило 2: ^table::create{ ... } — строки внутри блока с нулевым отступом
				if (txt != null && !txt.isEmpty()) {
					if (!inTableBlock) {
						boolean hasTable = hasUnescapedTableCreate(txt);
						boolean hasCloseInLine = hasUnescapedRBrace(txt);
						if (hasTable && !hasCloseInLine) {
							inTableBlock = true;
						}
					} else {
						boolean hasCloseInLine = hasUnescapedRBrace(txt);
						lineLevel = 0;
						if (hasCloseInLine) {
							inTableBlock = false;
						}
					}
				}

				// HTML tag-based indent logic
				int firstNonWs = -1;
				for (int ii=0; ii<txt.length(); ii++) {
					char c = txt.charAt(ii);
					if (c!=' ' && c!='\t') { firstNonWs = ii; break; }
				}
				int tagOpen = 0;
				int tagCloseLeading = 0;
				int tagCloseNormal = 0;
				java.util.Set<String> voids = java.util.Set.of("br","img","hr","input","meta","link","area","base","col","embed","param","source","track","wbr");
				for (int i2=0;i2<txt.length();i2++){
					if (txt.charAt(i2)=='<'){
						int j=i2+1; boolean closing=false;
						if (j<txt.length() && txt.charAt(j)=='/'){ closing=true; j++; }
						if (j<txt.length() && Character.isLetter(txt.charAt(j))){
							int k=j+1;
							while(k<txt.length() && Character.isLetterOrDigit(txt.charAt(k))) k++;
							String name=txt.substring(j,k).toLowerCase();

							boolean self=false; int x=k;
							while (x<txt.length() && txt.charAt(x)!='>') x++;
							if (x<txt.length()){
								int y=x-1;
								while (y>k && Character.isWhitespace(txt.charAt(y))) y--;
								if (y>=k && txt.charAt(y)=='/') self=true;
							}

							if (!voids.contains(name) && !self){
								if (closing){
									if (i2==firstNonWs) tagCloseLeading++; else tagCloseNormal++;
								} else tagOpen++;
							}

						}
					}
				}
				for(int n=0;n<tagCloseLeading;n++){
					if (!stack.isEmpty()){
						int open = stack.remove(stack.size()-1);
						if (open < lineLevel) lineLevel = open;
					}
					indentLevel = stack.size();
				}
				for(int n=0;n<tagOpen;n++){
					stack.add(lineLevel);
					indentLevel = stack.size();
				}
				for(int n=0;n<tagCloseNormal;n++){
					if (!stack.isEmpty()) stack.remove(stack.size()-1);
				}


				// шаг 1: ведущие закрывающие скобки (в начале строки)
				// Максимум 1 закрывающая скобка влияет на отступ
				int leadingCloseCount = 0;
				boolean leading = true;
				boolean foundLeadingClose = false;

				// Сначала проверяем токены
				for (IElementType type : li.tokens) {
					if ("WHITE_SPACE".equals(type.toString()) || type == TokenType.WHITE_SPACE) {
						continue;
					}

					if (leading &&
							(type == Parser3TokenTypes.RBRACE
									|| type == Parser3TokenTypes.RBRACKET
									|| type == Parser3TokenTypes.RPAREN)) {

						leadingCloseCount++;

						// Только первая ведущая закрывающая влияет на стек
						if (!foundLeadingClose) {
							foundLeadingClose = true;
							if (!stack.isEmpty()) {
								int openLevel = stack.remove(stack.size() - 1);
								if (openLevel < lineLevel) {
									lineLevel = openLevel;
								}
							} else {
								lineLevel = 0;
							}
							indentLevel = stack.size();
						}
						continue;
					}

					// первый не-закрывающий токен завершает "ведущие" закрытия
					leading = false;
				}

				// Дополнительно: если токены не распознали ведущие скобки, проверяем текст напрямую
				// Это нужно потому что лексер иногда видит ) или } как TEMPLATE_DATA
				if (leadingCloseCount == 0 && txt != null) {
					int textLeadingClose = 0;
					for (int i = 0; i < txt.length(); i++) {
						char c = txt.charAt(i);
						if (c == ' ' || c == '\t') continue;
						if (c == ')' || c == '}' || c == ']') {
							textLeadingClose++;
						} else {
							break;
						}
					}

					// Применяем максимум 1 ведущую закрывающую скобку
					if (textLeadingClose > 0) {
						leadingCloseCount = textLeadingClose;
						if (!stack.isEmpty()) {
							int openLevel = stack.remove(stack.size() - 1);
							if (openLevel < lineLevel) {
								lineLevel = openLevel;
							}
						} else {
							lineLevel = 0;
						}
						indentLevel = stack.size();
					}
				}

				// REM-блок: не трогаем отступы строк внутри ^rem{ ... }
				// Обрабатываем ПОСЛЕ вычисления lineLevel, но ДО присвоения li.level
				if (li.tokens.contains(Parser3TokenTypes.REM_LBRACE)) {
					boolean isNested = !remStack.isEmpty();
					int levelToUse = isNested ? li.originalLevel : lineLevel;
					remStack.push(levelToUse);
					li.level = levelToUse;
					li.levelSet = true;

					if (li.tokens.contains(Parser3TokenTypes.REM_RBRACE)) {
						remStack.pop();
					}
				}

				// Внутри REM блока — сохраняем originalLevel
				if (!remStack.isEmpty() && !li.levelSet) {
					// Проверяем закрывающую скобку СНАЧАЛА
					if (li.tokens.contains(Parser3TokenTypes.REM_RBRACE)) {
						li.level = li.originalLevel;
						li.levelSet = true;
						remStack.pop();
					} else {

						li.level = li.originalLevel;
						li.levelSet = true;
						continue;
					}
				}

				// Устанавливаем li.level если ещё не установлен (для не-REM строк)
				if (!li.levelSet) {
					li.level = lineLevel;
				}


				// шаг 2: обновляем стек для следующих строк
				// Используем анализ текста вместо токенов, потому что лексер иногда
				// видит скобки как TEMPLATE_DATA (например, ) в ^if(...){}
				int skippedClosers = 0;
				// currentLevel отслеживает уровень для следующих скобок на этой же строке
				int currentLevel = lineLevel;

				if (txt != null) {
					if (DEBUG_FORMAT) {
						String escapedTxt = txt.replace("\n", "\\n").replace("\t", "\\t");
						System.out.println("[Format] line='" + escapedTxt + "', leadingCloseCount=" + leadingCloseCount + ", stackBefore=" + new java.util.ArrayList<>(stack));
					}

					int balance = 0;
					int skippedLeading = 0;
					boolean inSingleQuote = false;
					boolean inDoubleQuote = false;
					int depth = 0; // Глубина вложенности скобок — внутри вложенных скобки не считаем

					for (int i = 0; i < txt.length(); i++) {
						char c = txt.charAt(i);

						// Проверяем экранирование
						if (isEscapedByCaret(txt, i)) {
							continue;
						}

						// Отслеживаем строки (только вне вложенных блоков)
						if (c == '\'' && !inDoubleQuote && depth == 0) {
							inSingleQuote = !inSingleQuote;
							continue;
						}
						if (c == '"' && !inSingleQuote && depth == 0) {
							inDoubleQuote = !inDoubleQuote;
							continue;
						}

						// Внутри строк скобки не считаем
						if (inSingleQuote || inDoubleQuote) {
							continue;
						}

						// Все типы скобок обрабатываются одинаково
						if (c == '{' || c == '(' || c == '[') {
							if (depth == 0) {
								balance++;
							}
							depth++;
						} else if (c == '}' || c == ')' || c == ']') {
							depth--;
							if (depth < 0) depth = 0;
							if (depth == 0) {
								if (skippedLeading < leadingCloseCount) {
									skippedLeading++;
								} else {
									balance--;
								}
							}
						}
					}

					// Нормализуем баланс: максимум +1 или -1 за строку
					if (balance > 1) balance = 1;
					if (balance < -1) balance = -1;

					// Применяем к стеку
					if (balance > 0) {
						stack.add(currentLevel);
						currentLevel = currentLevel + 1;
					} else if (balance < 0) {
						if (!stack.isEmpty()) {
							stack.remove(stack.size() - 1);
						}
						currentLevel = stack.isEmpty() ? 0 : stack.get(stack.size() - 1) + 1;
					}

					// indentLevel для следующей строки = размер стека
					indentLevel = stack.size();


					if (DEBUG_FORMAT) {
						System.out.println("[Format] stackAfter=" + stack + ", balance=" + balance + ", indentLevel=" + indentLevel);
					}
				}

			}
		}



		private static boolean hasUnescapedTableCreate(@NotNull String text) {
			String pattern = "^table::create{";
			int idx = text.indexOf(pattern);
			while (idx >= 0) {
				if (isUnescapedAt(text, idx)) {
					return true;
				}
				idx = text.indexOf(pattern, idx + 1);
			}
			return false;
		}

		private static boolean hasUnescapedRBrace(@NotNull String text) {
			for (int i = 0; i < text.length(); i++) {
				if (text.charAt(i) == '}' && isUnescapedAt(text, i)) {
					return true;
				}
			}
			return false;
		}

		private static boolean isUnescapedAt(@NotNull String text, int index) {
			int caretCount = 0;
			for (int i = index - 1; i >= 0; i--) {
				if (text.charAt(i) == '^') {
					caretCount++;
				} else {
					break;
				}
			}
			// чётное количество ^ (0,2,4,...) — символ считается неэкранированным
			return caretCount % 2 == 0;
		}

		// Нечётное количество ^ перед символом = экранирован
		private static boolean isEscapedByCaret(@NotNull String text, int index) {
			return !isUnescapedAt(text, index);
		}

		private static int computeOriginalIndent(@NotNull String text) {
			int level = 0;
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c == '\t' || c == ' ') {
					level++;
				} else {
					break;
				}
			}
			return level;
		}



		static boolean lineIntersectsRange(@NotNull LineInfo li, @NotNull TextRange range) {
			TextRange lineRange = TextRange.create(li.startOffset, li.endOffset);
			return range.intersects(lineRange);
		}

		static boolean lineContainedInRange(@NotNull LineInfo li, @NotNull TextRange range) {

			if (li.startOffset >= range.getStartOffset() && li.endOffset <= range.getEndOffset()) {
				return true;
			}
			return false;
		}

		static boolean isPureClosingLine(@NotNull LineInfo li) {
			boolean hasNonWs = false;
			boolean hasOnlyClosers = false;
			for (IElementType type : li.tokens) {
				if (type == Parser3TokenTypes.WHITE_SPACE) {
					continue;
				}
				hasNonWs = true;
				if (type == Parser3TokenTypes.RBRACE
						|| type == Parser3TokenTypes.RBRACKET
						|| type == Parser3TokenTypes.RPAREN) {
					hasOnlyClosers = true;
				} else {
					hasOnlyClosers = false;
					break;
				}
			}
			return hasNonWs && hasOnlyClosers;
		}

		static int findHeaderLineIndex(@NotNull java.util.List<LineInfo> lines, int lineIndex, int lineLevel) {
			for (int i = lineIndex - 1; i >= 0; i--) {
				LineInfo candidate = lines.get(i);
				if (candidate.level < lineLevel) {
					String txt = candidate.textWithoutComment != null ? candidate.textWithoutComment : candidate.text;
					if (txt != null && !txt.trim().isEmpty()) {
						return i;
					}
				}
			}
			return -1;
		}



		static int findPartialHeaderIndex(@NotNull java.util.List<LineInfo> lines, @NotNull TextRange range) {

			for (int i = 0; i < lines.size(); i++) {
				LineInfo li = lines.get(i);
				boolean inRange = lineIntersectsRange(li, range);
				boolean fullyInRange = inRange && lineContainedInRange(li, range);
				if (inRange && !fullyInRange) {
					if (rangeCoversAllNonWhitespaceOutsideRange(li, range)) {
						continue;
					}

					// Если range начинается внутри строки, но сама строка состоит только из пробельных символов,
					// то игнорируем "partial header" — это типичный кейс paste при сдвинутом caret.
					String txt = li.textWithoutComment != null ? li.textWithoutComment : li.text;
					// Если range начинается внутри ведущих пробелов/табов строки,
					// то игнорируем "partial header": это тоже типичный paste-кейс, когда caret стоит внутри отступа,
					// и базовый отступ нельзя брать из этой строки (иначе он будет считаться от caret-колонки).
					if (rangeStartsInsideLeadingIndent(li, range)) {
						continue;
					}

					if (txt == null || txt.trim().isEmpty()) {
						continue;
					}
					return i;
				}
			}
			return -1;
		}

		static int findNonEmptyLineAbove(@NotNull java.util.List<LineInfo> lines, int fromLineIndex) {
			for (int i = fromLineIndex; i >= 0; i--) {
				LineInfo li = lines.get(i);
				String txt = li.textWithoutComment != null ? li.textWithoutComment : li.text;
				if (txt != null && !txt.trim().isEmpty()) {
					return i;
				}
			}
			return -1;
		}

		static boolean isTableCreateBlockLine(@NotNull java.util.List<LineInfo> lines, int lineIndex) {
			boolean inTable = false;
			for (int i = 0; i <= lineIndex && i < lines.size(); i++) {
				LineInfo li = lines.get(i);
				String txt = li.textWithoutComment != null && !li.textWithoutComment.isEmpty() ? li.textWithoutComment : li.text;
				if (txt == null || txt.isEmpty()) {
					if (inTable && i == lineIndex) {
						return true;
					}
					continue;
				}

				if (!inTable) {
					boolean hasTable = hasUnescapedTableCreate(txt);
					boolean hasCloseHere = hasUnescapedRBrace(txt);
					if (hasTable && !hasCloseHere) {
						// Строка с началом table::create сама остаётся с обычным отступом.
						// Нулевая колонка применяется к последующим строкам тела таблицы.
						inTable = true;
					}
					continue;
				}

				// Внутри table::create блока: текущая строка должна быть от начала строки (включая строку с закрывающей '}').
				if (i == lineIndex) {
					return true;
				}

				if (hasUnescapedRBrace(txt)) {
					inTable = false;
				}
			}
			return false;
		}


		static boolean rangeStartsInsideLeadingIndent(@NotNull LineInfo li, @NotNull TextRange range) {
			int rs = range.getStartOffset();
			if (rs <= li.startOffset || rs >= li.endOffset) {
				return false;
			}
			int rel = rs - li.startOffset;
			if (li.text == null) {
				return false;
			}
			if (rel > li.text.length()) {
				rel = li.text.length();
			}
			for (int i = 0; i < rel; i++) {
				char c = li.text.charAt(i);
				if (c != ' ' && c != '\t') {
					return false;
				}
			}
			return true;
		}

		static boolean rangeCoversAllNonWhitespaceOutsideRange(@NotNull LineInfo li, @NotNull TextRange range) {
			int rs = range.getStartOffset();
			int re = range.getEndOffset();
			if (rs >= li.endOffset || re <= li.startOffset) {
				return false;
			}
			if (li.text == null) {
				return false;
			}
			int textLen = li.text.length();
			int relStart = Math.max(0, Math.min(textLen, rs - li.startOffset));
			int relEnd = Math.max(0, Math.min(textLen, re - li.startOffset));

			// before range
			for (int i = 0; i < relStart; i++) {
				char c = li.text.charAt(i);
				if (c != ' ' && c != '\t') {
					return false;
				}
			}
			// after range
			for (int i = relEnd; i < textLen; i++) {
				char c = li.text.charAt(i);
				if (c != ' ' && c != '\t') {
					return false;
				}
			}
			return true;
		}

		static int findFirstLineInRange(@NotNull java.util.List<LineInfo> lines, @NotNull TextRange range) {
			for (int i = 0; i < lines.size(); i++) {
				LineInfo li = lines.get(i);
				if (lineIntersectsRange(li, range)) {
					return i;
				}
			}
			return -1;
		}


		static int computeIndentColumnsForLine(
				@NotNull java.util.List<LineInfo> lines,
				int lineIndex,
				int indentSize,
				boolean useTabs,
				@Nullable TextRange formattingRange
		) {
			LineInfo li = lines.get(lineIndex);
			int logicalIndent = Math.max(li.level, 0) * indentSize;


			// ^table::create{ ... } — строки тела таблицы и строка с закрывающей '}' должны быть строго с начала строки.
			if (isTableCreateBlockLine(lines, lineIndex)) {
				return 0;
			}

			// Защита от превращения текста в метод:
			// Если строка содержит @ НЕ в начале (после отступа), и после форматирования
			// @ окажется в колонке 0, то добавляем минимум 1 пробел.
			// Это предотвращает ситуацию когда " @not_method" становится "@not_method" (методом).
			int computedIndent = computeIndentColumnsForLineInternal(lines, lineIndex, indentSize, useTabs, formattingRange);
			if (computedIndent == 0 && startsWithAtAfterIndent(li)) {
				return 1; // минимальный отступ чтобы @ не стал методом
			}
			// Защита от превращения текста в hash-комментарий:
			// Если строка содержит # НЕ в начале (после отступа), и после форматирования
			// # окажется в колонке 0, то добавляем минимум 1 пробел.
			if (computedIndent == 0 && startsWithHashAfterIndent(li)) {
				return 1; // минимальный отступ чтобы # не стал комментарием
			}
			return computedIndent;
		}

		/**
		 * Проверяет, начинается ли строка с @ после пробельного отступа (но не с колонки 0).
		 * Если @ стоит в колонке 0 — это уже метод, защита не нужна.
		 */
		private static boolean startsWithAtAfterIndent(@NotNull LineInfo li) {
			String text = li.text;
			if (text == null || text.isEmpty()) {
				return false;
			}
			// Ищем первый непробельный символ
			int firstNonWs = -1;
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c != ' ' && c != '\t') {
					firstNonWs = i;
					break;
				}
			}
			// Если первый непробельный символ — @, и он НЕ в колонке 0, значит строка " @..."
			return firstNonWs > 0 && text.charAt(firstNonWs) == '@';
		}

		/**
		 * Проверяет, начинается ли строка с # после пробельного отступа (но не с колонки 0).
		 * Если # стоит в колонке 0 — это hash-комментарий, защита не нужна.
		 */
		private static boolean startsWithHashAfterIndent(@NotNull LineInfo li) {
			String text = li.text;
			if (text == null || text.isEmpty()) {
				return false;
			}
			// Ищем первый непробельный символ
			int firstNonWs = -1;
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c != ' ' && c != '\t') {
					firstNonWs = i;
					break;
				}
			}
			// Если первый непробельный символ — #, и он НЕ в колонке 0, значит строка " #..."
			return firstNonWs > 0 && text.charAt(firstNonWs) == '#';
		}

		static int computeIndentColumnsForLineInternal(
				@NotNull java.util.List<LineInfo> lines,
				int lineIndex,
				int indentSize,
				boolean useTabs,
				@Nullable TextRange formattingRange
		) {
			LineInfo li = lines.get(lineIndex);
			int logicalIndent = Math.max(li.level, 0) * indentSize;


			// HASH comment rule: # в колонке 0 — это комментарий, всегда оставляем в колонке 0
			if (li.hashComment) {
				return 0;
			}
// Если диапазон — точка (как при автоформате IDE по '}' и т.п.), выходим сразу.
			if (formattingRange != null && formattingRange.getStartOffset() == formattingRange.getEndOffset()) {
				return logicalIndent;
			}

			// Обычное поведение: форматирование всего файла
			if (formattingRange == null) {
				return logicalIndent;
			}

			boolean inRange = lineIntersectsRange(li, formattingRange);
			boolean fullyInRange = inRange && lineContainedInRange(li, formattingRange);

			// Вне диапазона — сохраняем оригинальный отступ строки
			if (!inRange) {
				int originalColumns;
				if (useTabs) {
					originalColumns = Math.max(li.originalLevel, 0) * indentSize;
				} else {
					originalColumns = Math.max(li.originalLevel, 0);
				}
				return originalColumns;
			}

			// Линия частично попала в диапазон (например, заголовок блока).
			// Если range начинается внутри ведущих пробелов/табов строки (типичный paste при сдвинутом caret),
			// то эту строку НУЖНО форматировать как "полностью внутри range", иначе базовый отступ будет считаться от caret-колонки.
			if (inRange && !fullyInRange) {
				if (rangeCoversAllNonWhitespaceOutsideRange(li, formattingRange)) {
					fullyInRange = true;
				}

				if (rangeStartsInsideLeadingIndent(li, formattingRange)) {
					fullyInRange = true;
				} else {
					// Для частично выделенной строки стараемся сохранить фактический отступ.
					// При табах считаем, что каждый символ отступа = один уровень.
					int originalColumns;
					if (useTabs) {
						originalColumns = Math.max(li.originalLevel, 0) * indentSize;
					} else {
						originalColumns = Math.max(li.originalLevel, 0);
					}
					return originalColumns;
				}
			}

			// Здесь: строка полностью внутри выделения.
			// Ищем частично выделенную строку (заголовок блока) и считаем отступ относительно неё.
			int headerIndex = findPartialHeaderIndex(lines, formattingRange);
			if (headerIndex < 0) {
				// Частично выделенного заголовка нет (часто так бывает при paste).
				// Тогда берём "базовую" строку выше диапазона: первую непустую строку над первой строкой, попавшей в range,
				// и считаем отступ относительно неё.
				int firstInRange = findFirstLineInRange(lines, formattingRange);
				int baseHeader = -1;
				if (firstInRange > 0) {
					baseHeader = findNonEmptyLineAbove(lines, firstInRange - 1);
				}
				if (baseHeader >= 0) {
					headerIndex = baseHeader;
				} else {
					// Нет базовой строки выше — ведём себя как при обычном форматировании
					return logicalIndent;
				}
			}

			LineInfo header = lines.get(headerIndex);
			String headerText = header.textWithoutComment != null ? header.textWithoutComment : header.text;
			if (headerText == null || headerText.trim().isEmpty()) {
				int fallbackHeaderIndex = findNonEmptyLineAbove(lines, headerIndex - 1);
				if (fallbackHeaderIndex >= 0) {
					headerIndex = fallbackHeaderIndex;
					header = lines.get(headerIndex);
				}
			}

			int headerUnits;
			if (useTabs) {
				headerUnits = Math.max(header.originalLevel, 0);
			} else {
				headerUnits = Math.max(header.originalLevel, 0) / Math.max(indentSize, 1);
			}
			int headerLevel = Math.max(header.level, 0);
			int childLevel = Math.max(li.level, 0);
			int deltaUnits = headerUnits - headerLevel;

			int childUnits = childLevel + deltaUnits;
			int resultColumns = childUnits * indentSize;
			return Math.max(resultColumns, 0);

		}


		static @NotNull List<LineInfo> buildLines(@NotNull PsiFile file, @NotNull ASTNode rootNode) {
			return buildLines(file, rootNode, null);
		}

		/**
		 * Строит информацию о строках для форматирования.
		 *
		 * ОПТИМИЗАЦИЯ: если задан formattingRange, анализируем только от ближайшего
		 * @method[] выше диапазона (т.к. @method сбрасывает отступы в 0).
		 */
		static @NotNull List<LineInfo> buildLines(@NotNull PsiFile file, @NotNull ASTNode rootNode,
												  @Nullable TextRange formattingRange) {
			List<LineInfo> lines = new ArrayList<>();
			Document doc = file.getViewProvider().getDocument();
			if (doc == null) {
				return lines;
			}
			CharSequence fileText = doc.getCharsSequence();
			int totalLines = doc.getLineCount();

			// Определяем диапазон строк для анализа
			int startLine = 0;
			int endLine = totalLines - 1;

			if (formattingRange != null && formattingRange.getLength() < doc.getTextLength() / 2) {
				// Используем P3MethodBoundaryFinder для поиска границ метода
				int methodStart = ru.artlebedev.parser3.utils.P3MethodBoundaryFinder.findMethodStartAbove(
						fileText, formattingRange.getStartOffset());
				int methodEnd = ru.artlebedev.parser3.utils.P3MethodBoundaryFinder.findMethodStartBelow(
						fileText, formattingRange.getEndOffset());

				startLine = doc.getLineNumber(methodStart);
				endLine = methodEnd >= fileText.length() ? totalLines - 1 : doc.getLineNumber(methodEnd) - 1;
				if (endLine < startLine) {
					endLine = totalLines - 1;
				}
			}

			lines = new ArrayList<>(endLine - startLine + 1);

			// Заполняем строки только в нужном диапазоне
			for (int line = startLine; line <= endLine && line < totalLines; line++) {
				int start = doc.getLineStartOffset(line);
				int end = doc.getLineEndOffset(line);

				LineInfo li = new LineInfo();
				li.lineIndex = line;
				li.level = 0;
				li.startOffset = start;
				li.endOffset = end;
				li.text = fileText.subSequence(start, end).toString();
				li.textWithoutComment = "";
				li.originalLevel = computeOriginalIndent(li.text);

				// Detect hash-comment lines: # СТРОГО в колонке 0 (без отступа)
				// В Parser3 комментарий — это # только если он в начале строки
				//
				// При paste первая строка может получить отступ от IDE, но в буфере
				// она могла начинаться с #. Проверяем: если formattingRange начинается
				// после начала строки, смотрим что стоит на позиции formattingRange.start
				boolean isHashComment = false;

				if (li.originalLevel == 0 && li.text.startsWith("#")) {
					// Простой случай: # в колонке 0
					isHashComment = true;
				} else if (formattingRange != null && li.startOffset < formattingRange.getStartOffset()) {
					// Строка начинается ДО range — возможно это paste и IDE добавила отступ
					int rangeStartInLine = formattingRange.getStartOffset() - li.startOffset;
					if (rangeStartInLine < li.text.length() && li.text.charAt(rangeStartInLine) == '#') {
						// В буфере строка начиналась с # — это комментарий
						isHashComment = true;
					}
				}

				if (isHashComment) {
					li.hashComment = true;
				}

				lines.add(li);
			}

			// Разбрасываем токены по строкам (только для нужного диапазона)
			int startOffset = startLine < totalLines ? doc.getLineStartOffset(startLine) : 0;
			int endOffset = endLine < totalLines ? doc.getLineEndOffset(endLine) : doc.getTextLength();

			ASTNode child = rootNode.getFirstChildNode();
			while (child != null) {
				IElementType type = child.getElementType();
				TextRange r = child.getTextRange();
				int nodeStart = r.getStartOffset();

				// Пропускаем токены вне диапазона
				if (nodeStart < startOffset) {
					child = child.getTreeNext();
					continue;
				}
				if (nodeStart > endOffset) {
					break; // Дальше смотреть нет смысла
				}

				int lineIndex = doc.getLineNumber(nodeStart);
				int listIndex = lineIndex - startLine;
				if (listIndex >= 0 && listIndex < lines.size()) {
					LineInfo li = lines.get(listIndex);
					li.tokens.add(type);
				}

				child = child.getTreeNext();
			}

			// Собираем textWithoutComment: без строк и комментариев + игнорируем hash-комментарии
			Map<Integer, Parser3HashCommentFormatUtil.LineInfo> hashInfo =
					Parser3HashCommentFormatUtil.getOrCreateInfo(doc);

			child = rootNode.getFirstChildNode();
			while (child != null) {
				IElementType type = child.getElementType();

				if (type == Parser3TokenTypes.LINE_COMMENT
						|| type == Parser3TokenTypes.BLOCK_COMMENT
						|| type == Parser3TokenTypes.STRING) {
					child = child.getTreeNext();
					continue;
				}

				TextRange r2 = child.getTextRange();
				int start2 = r2.getStartOffset();

				// Пропускаем токены вне диапазона
				if (start2 < startOffset) {
					child = child.getTreeNext();
					continue;
				}
				if (start2 > endOffset) {
					break;
				}

				int lineIndex2 = doc.getLineNumber(start2);
				int listIndex2 = lineIndex2 - startLine;
				if (listIndex2 >= 0 && listIndex2 < lines.size()) {
					LineInfo li = lines.get(listIndex2);
					// Hash-комментарий только если # строго в колонке 0
					boolean isActualHashComment = li.hashComment ||
							(hashInfo.containsKey(lineIndex2) && li.originalLevel == 0);
					if (isActualHashComment) {
						li.hashComment = true;
					} else {
						li.textWithoutComment += child.getText();
					}
				}

				child = child.getTreeNext();
			}

			setLevels(lines);

			return lines;
		}
	}

	Parser3Block(
			@NotNull ASTNode node,
			@NotNull SpacingBuilder spacingBuilder,
			@NotNull CommonCodeStyleSettings.IndentOptions indentOptions,
			boolean rootBlock,
			int indentColumns,
			boolean continuationIndent,
			boolean templateLike,
			boolean sqlBlock,
			@Nullable TextRange formattingRange
	) {
		super(node, null, null);
		this.spacingBuilder = spacingBuilder;
		this.indentOptions = indentOptions;
		this.rootBlock = rootBlock;
		this.indentColumns = indentColumns;
		this.formattingRange = formattingRange;


	}

	@Override
	protected List<Block> buildChildren() {

		List<Block> result = new ArrayList<>();

		ASTNode rootNode = getNode();
		List<LineInfo> lines = null;
		Document doc = null;
		int linesStartLine = 0; // с какой строки начинается список lines

		// Диапазон для вычисления отступов (от @method до @method)
		int indentRangeStart = 0;
		int indentRangeEnd = Integer.MAX_VALUE;

		if (rootBlock) {
			TextRange fr = this.formattingRange;
			// Оптимизация: не запускаем анализ для точечного диапазона (auto-indent)
			boolean skipAnalysis = (fr != null && fr.getStartOffset() == fr.getEndOffset());

			if (!skipAnalysis) {
				PsiFile file = rootNode.getPsi().getContainingFile();
				doc = file.getViewProvider().getDocument();

				// Определяем диапазон @method..@method для вычисления отступов
				if (fr != null && doc != null) {
					CharSequence fileText = doc.getCharsSequence();
					indentRangeStart = ru.artlebedev.parser3.utils.P3MethodBoundaryFinder.findMethodStartAbove(
							fileText, fr.getStartOffset());
					int methodEnd = ru.artlebedev.parser3.utils.P3MethodBoundaryFinder.findMethodStartBelow(
							fileText, fr.getEndOffset());
					indentRangeEnd = methodEnd >= fileText.length() ? Integer.MAX_VALUE : methodEnd;
				}

				// Передаём formattingRange для оптимизации - анализ только от ближайшего @method
				lines = LineInfo.buildLines(file, rootNode, fr);

				// Определяем с какой строки начинается список
				if (lines != null && !lines.isEmpty()) {
					linesStartLine = lines.get(0).lineIndex;
				}
			}
		}

		// Строим subBlocks для ВСЕХ токенов (IntelliJ требует покрытия всего файла),
		// но отступы вычисляем только для токенов в диапазоне @method..@method
		ASTNode child = rootNode.getFirstChildNode();
		final int finalLinesStartLine = linesStartLine;
		final int finalIndentRangeStart = indentRangeStart;
		final int finalIndentRangeEnd = indentRangeEnd;

		while (child != null) {
			IElementType type = child.getElementType();
			if (type == TokenType.WHITE_SPACE) {
				child = child.getTreeNext();
				continue;
			}

			int childIndentColumns = 0;
			TextRange childRange = child.getTextRange();

			// Вычисляем отступы только для токенов внутри диапазона @method..@method
			boolean inIndentRange = childRange.getStartOffset() >= finalIndentRangeStart &&
					childRange.getStartOffset() < finalIndentRangeEnd;

			if (inIndentRange && lines != null && doc != null) {
				int lineIndex = doc.getLineNumber(childRange.getStartOffset());
				int listIndex = lineIndex - finalLinesStartLine;
				if (listIndex >= 0 && listIndex < lines.size()) {

					TextRange formattingRange = this.formattingRange;
					childIndentColumns = LineInfo.computeIndentColumnsForLine(
							lines,
							listIndex,  // передаём индекс в списке, а не номер строки
							indentOptions.INDENT_SIZE,
							indentOptions.USE_TAB_CHARACTER,
							formattingRange
					);

				}
			}


			Parser3Block block = new Parser3Block(
					child,
					spacingBuilder,
					indentOptions,
					false,
					childIndentColumns,
					false,
					false,
					false,
					formattingRange
			);
			result.add(block);

			child = child.getTreeNext();

		}

		return result;
	}

	@Override
	public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
		Spacing s = spacingBuilder.getSpacing(this, child1, child2);

		if (child1 == null || !(child1 instanceof Parser3Block) || !(child2 instanceof Parser3Block)) {
			return s;
		}

		ASTNode n1 = ((Parser3Block) child1).getNode();
		ASTNode n2 = ((Parser3Block) child2).getNode();
		PsiFile file = n1.getPsi().getContainingFile();
		Document doc = file.getViewProvider().getDocument();
		if (doc == null) {
			return s;
		}

		int end1 = n1.getTextRange().getEndOffset();
		int start2 = n2.getTextRange().getStartOffset();
		int line1 = doc.getLineNumber(end1);
		int line2 = doc.getLineNumber(start2);

		if (line1 == line2) {
			// Не трогаем пробелы/табы внутри строки — оставляем как в документе
			return Spacing.getReadOnlySpacing();
		}

		return s;
	}

	@Override
	public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
		return new ChildAttributes(Indent.getNoneIndent(), null);
	}

	@Override
	public boolean isLeaf() {
		if (rootBlock) {
			return false;
		}
		return getNode().getFirstChildNode() == null;
	}

	@Override
	public @Nullable Indent getIndent() {
		if (rootBlock) {
			return Indent.getNoneIndent();
		}
		if (indentColumns <= 0) {
			return Indent.getNoneIndent();
		}
		return Indent.getSpaceIndent(indentColumns);
	}

	@Override
	public @NotNull TextRange getTextRange() {
		return getNode().getTextRange();
	}

	@Override
	public String toString() {
		return "Parser3Block(" + getNode().getElementType() + ")";
	}
}