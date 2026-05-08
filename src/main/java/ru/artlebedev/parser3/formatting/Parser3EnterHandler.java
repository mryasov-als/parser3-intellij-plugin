package ru.artlebedev.parser3.formatting;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3Language;

public class Parser3EnterHandler extends EnterHandlerDelegateAdapter {

	public static final boolean DEBUG = false;

	public void formatFile (
			@NotNull PsiFile file,
			int startOffset,
			int endOffset
	) {
		PsiFile baseFile = file.getViewProvider().getPsi(Parser3Language.INSTANCE);
		if (baseFile != null) {
			file = baseFile;
		}
		Project project = file.getProject();
		CodeStyleManager style = CodeStyleManager.getInstance(project);
		com.intellij.openapi.util.TextRange range = new com.intellij.openapi.util.TextRange(startOffset, endOffset);
		style.reformatText(file, java.util.Collections.singletonList(range));
	}

	@Override
	public @NotNull EnterHandlerDelegate.Result preprocessEnter(
			@NotNull PsiFile file,
			@NotNull Editor editor,
			@NotNull Ref<Integer> caretOffsetRef,
			@NotNull Ref<Integer> caretAdvance,
			@NotNull DataContext dataContext,
			@NotNull EditorActionHandler originalHandler
	) {
		// Работаем только в Parser3 файлах (включая injected HTML/CSS/JS внутри .p)
		if (!ru.artlebedev.parser3.utils.Parser3PsiUtils.isParser3File(file)) {
			return Result.Continue;
		}

		// Safety fallback: если lookup открыт и почему-то без выделения,
		// подтверждаем первый пункт одним Enter вместо переноса строки.
		Lookup activeLookup = LookupManager.getActiveLookup(editor);
		if (activeLookup instanceof LookupImpl) {
			LookupImpl lookupImpl = (LookupImpl) activeLookup;
			if (!lookupImpl.isLookupDisposed() && lookupImpl.getCurrentItem() == null) {
				java.util.List<LookupElement> items = lookupImpl.getItems();
				if (!items.isEmpty()) {
					Project lookupProject = file.getProject();
					ApplicationManager.getApplication().invokeLater(() -> {
						if (lookupProject.isDisposed()) {
							return;
						}
						if (LookupManager.getActiveLookup(editor) != lookupImpl || lookupImpl.isLookupDisposed()) {
							return;
						}
						if (lookupImpl.getCurrentItem() == null) {
							java.util.List<LookupElement> delayedItems = lookupImpl.getItems();
							if (delayedItems.isEmpty()) {
								return;
							}
							lookupImpl.setCurrentItem(delayedItems.get(0));
						}
						if (DEBUG) System.out.println("[Parser3EnterHandler] Отложенно завершаем lookup без write action");
						lookupImpl.finishLookup(Lookup.NORMAL_SELECT_CHAR);
					});
					return Result.Stop;
				}
			}
		}

		Document document = editor.getDocument();
		PsiFile currentFile = file;
		boolean isInjected = false;
		Editor hostEditor = editor;
		int initialHostOffset = -1;

		// Для инжектированных документов (HTML, CSS, JS) — работаем через host документ
		if (document instanceof com.intellij.psi.impl.source.tree.injected.DocumentWindowImpl) {
			isInjected = true;
			com.intellij.psi.impl.source.tree.injected.DocumentWindowImpl injectedDoc =
					(com.intellij.psi.impl.source.tree.injected.DocumentWindowImpl) document;

			// Находим host файл
			com.intellij.psi.PsiElement context = file.getContext();
			if (context == null) {
				return Result.Continue;
			}

			PsiFile hostFile = context.getContainingFile();
			if (hostFile == null || hostFile.getFileType() != ru.artlebedev.parser3.Parser3FileType.INSTANCE) {
				return Result.Continue;
			}

			Document hostDocument = PsiDocumentManager.getInstance(file.getProject()).getDocument(hostFile);
			if (hostDocument == null) {
				return Result.Continue;
			}

			// Находим host editor
			com.intellij.openapi.fileEditor.FileEditorManager fem =
					com.intellij.openapi.fileEditor.FileEditorManager.getInstance(file.getProject());
			for (com.intellij.openapi.fileEditor.FileEditor fe : fem.getAllEditors()) {
				if (fe instanceof com.intellij.openapi.fileEditor.TextEditor) {
					Editor e = ((com.intellij.openapi.fileEditor.TextEditor) fe).getEditor();
					if (e.getDocument() == hostDocument) {
						hostEditor = e;
						break;
					}
				}
			}

			// Конвертируем offset в host координаты
			int injectedOffset = caretOffsetRef.get();
			int hostOffset = injectedDoc.injectedToHost(injectedOffset);

			document = hostDocument;
			currentFile = hostFile;
			// НЕ меняем caretOffsetRef — IDE проверяет его в пределах injected документа
			initialHostOffset = hostOffset;
		}

		// Final переменные для лямбд
		final Document finalDoc = document;
		final PsiFile finalFile = currentFile;
		final boolean finalIsInjected = isInjected;
		final Editor finalHostEditor = hostEditor;

		CharSequence text = finalDoc.getCharsSequence();
		int offset = finalIsInjected ? initialHostOffset : caretOffsetRef.get();

		if (offset <= 0 || offset > text.length()) {
			return Result.Continue;
		}

		Project project = finalFile.getProject();
		int line = finalDoc.getLineNumber(offset);
		int lineStart = finalDoc.getLineStartOffset(line);
		int lineEnd = finalDoc.getLineEndOffset(line);

		// CASE 1: cursor strictly between matching brackets or between '>' and '<'
		if (offset < text.length()) {
			char before = text.charAt(offset - 1);
			char after = text.charAt(offset);
			boolean betweenTagPair = (before == '>' && after == '<' && offset + 1 < text.length() && text.charAt(offset + 1) == '/');
			if (isBracketPair(before, after) || betweenTagPair) {
				String baseIndent = getBaseIndent(text, lineStart);
				String oneIndent = getOneIndent(finalFile);
				String innerIndent = baseIndent + oneIndent;
				String lineSeparator = "\n";
				int insertOffset = offset;
				int lineIndex = line;

				WriteCommandAction.runWriteCommandAction(project, () -> {
					String insert = lineSeparator + innerIndent + lineSeparator + baseIndent;
					finalDoc.insertString(insertOffset, insert);

					int newCaretOffset = insertOffset + lineSeparator.length() + innerIndent.length();
					finalHostEditor.getCaretModel().moveToOffset(newCaretOffset);
					if (!finalIsInjected) { caretOffsetRef.set(newCaretOffset); }
					caretAdvance.set(0);

				});
				return Result.Stop;
			}
		}

		// CASE 2: caret is inside a bracket or tag pair on the same line
		int[] tagPair = findEnclosingTagPair(text, lineStart, lineEnd, offset);
		int[] bracketPair = findEnclosingBracketPair(text, lineStart, lineEnd, offset);
		boolean useTag = false;
		boolean useBracket = false;
		if (tagPair != null) {
			int ltOpen = tagPair[0];
			int gtOpen = tagPair[1];
			int ltClose = tagPair[2];
			int insideStart = gtOpen + 1;
			int insideEnd = ltClose;
			if (offset == insideStart || offset == insideEnd) {
				useTag = true;
			}
		}
		if (!useTag && bracketPair != null && text.charAt(bracketPair[0]) != '{') {
			int openB = bracketPair[0];
			int closeB = bracketPair[1];
			int insideStart = openB + 1;
			int insideEnd = closeB;
			if (offset == insideStart || offset == insideEnd) {
				useBracket = true;
			}
		}
		if (useTag || useBracket) {
			int openPos;
			int insideStart;
			int insideEnd;
			int closingStart;

			if (useTag) {
				int ltOpen = tagPair[0];
				int gtOpen = tagPair[1];
				int ltClose = tagPair[2];
				openPos = ltOpen;
				insideStart = gtOpen + 1;
				insideEnd = ltClose;
				closingStart = ltClose;
			} else {
				int openB = bracketPair[0];
				int closeB = bracketPair[1];
				openPos = openB;
				insideStart = openB + 1;
				insideEnd = closeB;
				closingStart = closeB;
			}

			if (insideStart > insideEnd) {
				// nothing inside, fall back
			} else {
				String baseIndent = getBaseIndent(text, lineStart);
				String oneIndent = getOneIndent(finalFile);
				String innerIndent = baseIndent + oneIndent;
				String lineSeparator = "\n";

				String insideText = text.subSequence(insideStart, insideEnd).toString();
				String closingTail = text.subSequence(closingStart, lineEnd).toString();

				int offsetInside = Math.max(0, Math.min(offset - insideStart, insideText.length()));

				// Проверяем: если курсор стоит перед закрывающей скобкой (insideEnd)
				// и последний непробельный символ перед курсором — ";"
				// то не разлепляем левую часть, только правую
				boolean semicolonBeforeCaret = false;
				if (offset == insideEnd && insideText.length() > 0) {
					// Ищем последний непробельный символ в insideText
					int lastNonWs = insideText.length() - 1;
					while (lastNonWs >= 0) {
						char ch = insideText.charAt(lastNonWs);
						if (ch != ' ' && ch != '\t') break;
						lastNonWs--;
					}
					if (lastNonWs >= 0 && insideText.charAt(lastNonWs) == ';') {
						semicolonBeforeCaret = true;
					}
				}

				int lineIndex = line;
				int lineStartFinal = lineStart;
				int lineEndFinal = lineEnd;
				int insideStartFinal = insideStart;
				int offsetInsideFinal = offsetInside;
				String baseIndentFinal = baseIndent;
				String innerIndentFinal = innerIndent;
				String lineSeparatorFinal = lineSeparator;
				String insideTextFinal = insideText;
				String closingTailFinal = closingTail;
				boolean semicolonFinal = semicolonBeforeCaret;

				WriteCommandAction.runWriteCommandAction(project, () -> {
					CharSequence currentText = finalDoc.getCharsSequence();
					String lineText = currentText.subSequence(lineStartFinal, lineEndFinal).toString();

					int relInsideStart = insideStartFinal - lineStartFinal;
					String prefix = lineText.substring(0, relInsideStart);

					StringBuilder sb = new StringBuilder();
					if (semicolonFinal) {
						// ; перед курсором — не разлепляем левую часть
						// prefix + insideText + \n + innerIndent + \n + baseIndent + closingTail
						sb.append(prefix);
						sb.append(insideTextFinal);
						sb.append(lineSeparatorFinal);
						sb.append(innerIndentFinal);
						sb.append(lineSeparatorFinal);
						sb.append(baseIndentFinal);
						sb.append(closingTailFinal);
					} else {
						// Обычный случай — разлепляем обе стороны
						sb.append(prefix);
						sb.append(lineSeparatorFinal);
						sb.append(innerIndentFinal);
						sb.append(insideTextFinal);
						sb.append(lineSeparatorFinal);
						sb.append(baseIndentFinal);
						sb.append(closingTailFinal);
					}

					String newLineText = sb.toString();

					finalDoc.replaceString(lineStartFinal, lineEndFinal, newLineText);

					int newCaretOffset;
					if (semicolonFinal) {
						// Каретка на пустой строке с innerIndent (между insideText и closingTail)
						newCaretOffset = lineStartFinal
								+ prefix.length()
								+ insideTextFinal.length()
								+ lineSeparatorFinal.length()
								+ innerIndentFinal.length();
					} else {
						newCaretOffset = lineStartFinal
								+ prefix.length()
								+ lineSeparatorFinal.length()
								+ innerIndentFinal.length()
								+ offsetInsideFinal;
					}

					finalHostEditor.getCaretModel().moveToOffset(newCaretOffset);
					if (!finalIsInjected) { caretOffsetRef.set(newCaretOffset); }
					caretAdvance.set(0);

				});
				return Result.Stop;
			}
		}

		// CASE 3: split block and reformat when caret is right after '{' and there is content on the same line
		if (offset > lineStart) {
			int pos = offset - 1;
			CharSequence txt = text;
			while (pos >= lineStart) {
				char ch = txt.charAt(pos);
				if (ch == ' ' || ch == '\t') {
					pos--;
					continue;
				}
				break;
			}
			if (pos >= lineStart) {
				char prev = txt.charAt(pos);
				char next = (pos + 1 < txt.length()) ? txt.charAt(pos + 1) : '\0';
				// BRACE CASE
				if (prev == '{' || prev == '[' || prev == '(') {
					boolean hasContentAfter = false;
					for (int i = offset; i < lineEnd; i++) {
						char ch = txt.charAt(i);
						if (ch != ' ' && ch != '\t') {
							hasContentAfter = true;
							break;
						}
					}
					if (hasContentAfter) {
						int matchClose = findMatchingBrace(text, pos);
						if (matchClose > pos) {
							int openPosFinal = pos;
							int caretOffsetFinal = offset;
							int matchCloseFinal = matchClose;
							WriteCommandAction.runWriteCommandAction(project, () -> {
								Document doc = finalDoc;
								CharSequence cur = doc.getCharsSequence();
								int caretOff = Math.max(0, Math.min(caretOffsetFinal, doc.getTextLength()));
								doc.insertString(caretOff, "\n");
								CharSequence cur2 = doc.getCharsSequence();
								int closePos = matchCloseFinal;
								if (closePos >= caretOff) closePos++;
								if (closePos >= 0 && closePos < doc.getTextLength()) {
									int closeLine = doc.getLineNumber(closePos);
									int closeLineStart = doc.getLineStartOffset(closeLine);
									int closeLineEnd = doc.getLineEndOffset(closeLine);
									if (closePos > closeLineStart) {
										String closeLineText = cur2.subSequence(closeLineStart, closeLineEnd).toString();
										int rel = closePos - closeLineStart;
										String before = closeLineText.substring(0, rel);
										String tail = closeLineText.substring(rel);
										String baseIndentClose = "";
										String replacement = before;
										if (tail.length() > 0) {
											StringBuilder sb = new StringBuilder();
											sb.append(before);
											sb.append("\n");
											sb.append(baseIndentClose);
											sb.append(tail);
											replacement = sb.toString();
										}
										doc.replaceString(closeLineStart, closeLineEnd, replacement);
									}
								}
								PsiDocumentManager.getInstance(project).commitDocument(doc);
								CodeStyleManager style = CodeStyleManager.getInstance(project);
								int firstLine = doc.getLineNumber(caretOff + 1);
								int lastLine = doc.getLineNumber(closePos);
								int totalLines = doc.getLineCount();
								int extendedFirstLine = firstLine;
								for (int ln = firstLine - 1; ln >= 0; ln--) {
									int ls2 = doc.getLineStartOffset(ln);
									int le2 = doc.getLineEndOffset(ln);
									String lineText = doc.getCharsSequence().subSequence(ls2, le2).toString();
									String trimmed = lineText.trim();
									if (trimmed.isEmpty()) {
										extendedFirstLine = ln;
										continue;
									}
									boolean onlyClosers = true;
									for (int i = 0; i < trimmed.length(); i++) {
										char ch2 = trimmed.charAt(i);
										if (ch2 != '}' && ch2 != ']' && ch2 != ')') {
											onlyClosers = false;
											break;
										}
									}
									if (onlyClosers) {
										extendedFirstLine = ln;
										continue;
									}
									break;
								}
								int extendedLastLine = lastLine;
								for (int ln = lastLine + 1; ln < totalLines; ln++) {
									int ls2 = doc.getLineStartOffset(ln);
									int le2 = doc.getLineEndOffset(ln);
									String lineText = doc.getCharsSequence().subSequence(ls2, le2).toString();
									String trimmed = lineText.trim();
									if (trimmed.isEmpty()) {
										extendedLastLine = ln;
										continue;
									}
									boolean onlyClosers = true;
									for (int i = 0; i < trimmed.length(); i++) {
										char ch2 = trimmed.charAt(i);
										if (ch2 != '}' && ch2 != ']' && ch2 != ')') {
											onlyClosers = false;
											break;
										}
									}
									if (onlyClosers) {
										extendedLastLine = ln;
										continue;
									}
									break;
								}
								int rangeStart = doc.getLineStartOffset(extendedFirstLine);
								int rangeEnd = doc.getLineEndOffset(extendedLastLine);
								formatFile(finalFile, rangeStart, rangeEnd);

								int newLine = doc.getLineNumber(caretOff + 1);
								int newLineStart = doc.getLineStartOffset(newLine);
								CharSequence cur3 = doc.getCharsSequence();
								String indent = getBaseIndent(cur3, newLineStart);
								int newCaret = newLineStart + indent.length();
								finalHostEditor.getCaretModel().moveToOffset(newCaret);
								if (!finalIsInjected) { caretOffsetRef.set(newCaret); }
								caretAdvance.set(0);
							});
							return Result.Stop;
						}
					}
				}
				// BRACE CASE when caret is near closing '}' of the same block
				else if (
						(prev == '}' || prev == ']' || prev == ')')
								&& (next == '}' || next == ']' || next == ')')
				) {
					int closePos = -1;
					char closeChar = prev;
					int scan = offset;
					while (scan < text.length()) {
						char chScan = txt.charAt(scan);
						if (chScan == ' ' || chScan == '	') {
							scan++;
							continue;
						}
						if (chScan == closeChar) {
							closePos = scan;
						}
						break;
					}
					if (closePos == -1) return Result.Continue;
					int openPos = -1;
					for (int i = closePos; i >= 0; i--) {
						char ch2 = text.charAt(i);
						if (ch2 == '{' || ch2 == '[' || ch2 == '(') {
							int mc = findMatchingBrace(text, i);
							if (mc == closePos) {
								openPos = i;
								break;
							}
						}
					}
					if (openPos != -1) {
						int openLineStart = openPos;
						while (openLineStart > 0 && text.charAt(openLineStart - 1) != '\n') openLineStart--;
						int openLineEnd = openPos;
						int textLen = text.length();
						while (openLineEnd < textLen && text.charAt(openLineEnd) != '\n') openLineEnd++;
						boolean hasContentAfter = false;
						int fakeOffset = openPos + 1;
						for (int i = fakeOffset; i < openLineEnd; i++) {
							char ch = text.charAt(i);
							if (ch != ' ' && ch != '	') {
								hasContentAfter = true;
								break;
							}
						}
						if (hasContentAfter) {
							int openPosFinal = openPos;
							int caretOffsetFinal = fakeOffset;
							int matchCloseFinal = closePos;
							WriteCommandAction.runWriteCommandAction(project, () -> {
								Document doc = finalDoc;
								CharSequence cur = doc.getCharsSequence();
								int caretOff = Math.max(0, Math.min(caretOffsetFinal, doc.getTextLength()));
								doc.insertString(caretOff, "\n");
								CharSequence cur2 = doc.getCharsSequence();
								int closePos2 = matchCloseFinal;
								if (closePos2 >= caretOff) closePos2++;
								if (closePos2 >= 0 && closePos2 < doc.getTextLength()) {
									int closeLine = doc.getLineNumber(closePos2);
									int closeLineStart = doc.getLineStartOffset(closeLine);
									int closeLineEnd = doc.getLineEndOffset(closeLine);
									if (closePos2 > closeLineStart) {
										String closeLineText = cur2.subSequence(closeLineStart, closeLineEnd).toString();
										int rel = closePos2 - closeLineStart;
										if (rel > 0 && rel < closeLineText.length()) {
											String before = closeLineText.substring(0, rel);
											String tail = closeLineText.substring(rel);
											String baseIndentClose = "";
											String replacement = before;
											if (tail.length() > 0) {
												StringBuilder sb = new StringBuilder();
												sb.append(before);
												sb.append("\n");
												sb.append(baseIndentClose);
												sb.append(tail);
												replacement = sb.toString();
											}
											doc.replaceString(closeLineStart, closeLineEnd, replacement);
										}
									}
								}
								PsiDocumentManager.getInstance(project).commitDocument(doc);
								int firstLine = doc.getLineNumber(caretOff + 1);
								int lastLine = doc.getLineNumber(closePos2);
								int totalLines = doc.getLineCount();
								int extendedFirstLine = firstLine;
								for (int ln = firstLine - 1; ln >= 0; ln--) {
									int ls2 = doc.getLineStartOffset(ln);
									int le2 = doc.getLineEndOffset(ln);
									String lineText = doc.getCharsSequence().subSequence(ls2, le2).toString();
									String trimmed = lineText.trim();
									if (trimmed.isEmpty()) {
										extendedFirstLine = ln;
										continue;
									}
									boolean onlyClosers = true;
									for (int i = 0; i < trimmed.length(); i++) {
										char ch2 = trimmed.charAt(i);
										if (ch2 != '}' && ch2 != ']' && ch2 != ')') {
											onlyClosers = false;
											break;
										}
									}
									if (onlyClosers) {
										extendedFirstLine = ln;
										continue;
									}
									break;
								}
								int extendedLastLine = lastLine;
								for (int ln = lastLine + 1; ln < totalLines; ln++) {
									int ls2 = doc.getLineStartOffset(ln);
									int le2 = doc.getLineEndOffset(ln);
									String lineText = doc.getCharsSequence().subSequence(ls2, le2).toString();
									String trimmed = lineText.trim();
									if (trimmed.isEmpty()) {
										extendedLastLine = ln;
										continue;
									}
									boolean onlyClosers = true;
									for (int i = 0; i < trimmed.length(); i++) {
										char ch2 = trimmed.charAt(i);
										if (ch2 != '}' && ch2 != ']' && ch2 != ')') {
											onlyClosers = false;
											break;
										}
									}
									if (onlyClosers) {
										extendedLastLine = ln;
										continue;
									}
									break;
								}
								int startOffset = doc.getLineStartOffset(extendedFirstLine);
								int endOffset = doc.getLineEndOffset(extendedLastLine);
								formatFile(finalFile, startOffset, endOffset);
								int newLine = doc.getLineNumber(caretOff + 1);
								int newLineStart = doc.getLineStartOffset(newLine);
								CharSequence cur3 = doc.getCharsSequence();
								String indent = getBaseIndent(cur3, newLineStart);
								int newCaret = newLineStart + indent.length();
								finalHostEditor.getCaretModel().moveToOffset(newCaret);
								if (!finalIsInjected) { caretOffsetRef.set(newCaret); }
								caretAdvance.set(0);
							});
							return Result.Stop;
						}
					}
				}
				// TAG CASE: caret right after an opening tag before inner content
				else if (prev == '>') {
					int lt = pos - 1;
					while (lt >= lineStart && txt.charAt(lt) != '<' && txt.charAt(lt) != '\n') lt--;
					if (lt >= lineStart && txt.charAt(lt) == '<') {
						if (offset < text.length() - 1 && text.charAt(offset) == '<' && text.charAt(offset + 1) == '/') {
							// caret before a closing tag like </div>, skip TAG CASE
						} else if (lt >= lineStart && txt.charAt(lt) == '<') {
							boolean hasContentAfter = false;
							for (int i = offset; i < lineEnd; i++) {
								char ch2 = txt.charAt(i);
								if (ch2 != ' ' && ch2 != '\t') {
									hasContentAfter = true;
									break;
								}
							}
							if (hasContentAfter) {
								String baseIndentTag = getBaseIndent(text, lineStart);
								String oneIndentTag = getOneIndent(finalFile);
								String innerIndentTag = baseIndentTag + oneIndentTag;
								String tagName = "";
								int nameStart = lt + 1;
								int nameEnd = nameStart;
								while (nameEnd < txt.length()) {
									char c = txt.charAt(nameEnd);
									if (c == ' ' || c == '\t' || c == '>' || c == '/' || c == '\n') break;
									nameEnd++;
								}
								if (nameEnd > nameStart) {
									tagName = text.subSequence(nameStart, nameEnd).toString();
								}
								final int ls = lineStart;
								final int le = lineEnd;
								final int openEnd = offset;
								final String baseIndentTagFinal = baseIndentTag;
								final String innerIndentTagFinal = innerIndentTag;
								final String tagNameFinal = tagName;
								WriteCommandAction.runWriteCommandAction(project, () -> {
									Document doc = finalDoc;
									CharSequence cur = doc.getCharsSequence();
									String lineText = cur.subSequence(ls, le).toString();
									int openEndRel = openEnd - ls;
									if (openEndRel < 0) openEndRel = 0;
									if (openEndRel > lineText.length()) openEndRel = lineText.length();
									String beforeOpen = lineText.substring(0, openEndRel);
									String afterOpen = lineText.substring(openEndRel);
									int closeIdxRel = -1;
									if (!tagNameFinal.isEmpty()) {
										closeIdxRel = afterOpen.indexOf("</" + tagNameFinal);
									}
									if (closeIdxRel < 0) {
										closeIdxRel = afterOpen.indexOf("</");
									}
									String firstPart;
									String tail;
									if (closeIdxRel >= 0) {
										firstPart = afterOpen.substring(0, closeIdxRel);
										tail = afterOpen.substring(closeIdxRel);
									} else {
										firstPart = afterOpen;
										tail = "";
									}
									StringBuilder sb = new StringBuilder();
									sb.append(beforeOpen);
									sb.append("\n");
									sb.append(innerIndentTagFinal);
									sb.append(firstPart);
									if (!tail.isEmpty()) {
										sb.append("\n");
										sb.append(baseIndentTagFinal);
										sb.append(tail);
									}
									String replacement = sb.toString();
									doc.replaceString(ls, le, replacement);
									int newCaret = ls + beforeOpen.length() + 1 + innerIndentTagFinal.length();
									if (newCaret > doc.getTextLength()) newCaret = doc.getTextLength();
									finalHostEditor.getCaretModel().moveToOffset(newCaret);
									if (!finalIsInjected) { caretOffsetRef.set(newCaret); }
									caretAdvance.set(0);
								});
								return Result.Stop;
							}
						}
					}
				}
			}
		}
// CASE: Handle hash comments (#)
		// В Parser3 комментарий — это # СТРОГО в начале строки (без пробелов перед ним)
		String currentLineText = text.subSequence(lineStart, lineEnd).toString();
		String trimmedLine = currentLineText.trim();

		// Комментарий только если строка начинается с #
		boolean isHashComment = currentLineText.startsWith("#");


		if (isHashComment) {

			// CASE COMMENT 1: Строка содержит только # (начало блока документации)
			// и НЕПОСРЕДСТВЕННО следующая строка начинается с @ (определение метода)
			if (isOnlyHashes(trimmedLine)) {
				// Проверяем есть ли следующая строка
				int totalLines = finalDoc.getLineCount();
				if (line + 1 < totalLines) {
					int nextLineStart = finalDoc.getLineStartOffset(line + 1);
					int nextLineEnd = finalDoc.getLineEndOffset(line + 1);
					String nextLineText = text.subSequence(nextLineStart, nextLineEnd).toString();
					String nextTrimmed = nextLineText.trim();


					if (nextTrimmed.startsWith("@")) {
						// Следующая строка — определение метода!
						// Создаём блок документации
						// Если пользователь ввёл меньше 80 # — добиваем до 80
						int hashCount = Math.max(80, trimmedLine.length());
						String hashes = repeat('#', hashCount);

						// Если первая строка короче 80, нужно её тоже дополнить
						boolean needExtendFirstLine = trimmedLine.length() < 80;

						int insertOffsetFinal = offset;
						String hashesFinal = hashes;
						int lineStartFinal = lineStart;
						int lineEndFinal = lineEnd;


						WriteCommandAction.runWriteCommandAction(project, () -> {
							// Если нужно дополнить первую строку
							if (needExtendFirstLine) {
								// Заменяем первую строку на полную версию с 80 # (в колонке 0)
								String newFirstLine = hashesFinal;
								finalDoc.replaceString(lineStartFinal, lineEndFinal, newFirstLine);

								// Пересчитываем позицию для вставки
								int newLineEnd = lineStartFinal + newFirstLine.length();

								StringBuilder sb = new StringBuilder();
								sb.append("\n");
								sb.append("# ");  // Комментарий в колонке 0
								int cursorPos = newLineEnd + sb.length();
								sb.append("\n");
								sb.append(hashesFinal);  // Закрывающие # в колонке 0

								finalDoc.insertString(newLineEnd, sb.toString());
								finalHostEditor.getCaretModel().moveToOffset(cursorPos);
								if (!finalIsInjected) { caretOffsetRef.set(cursorPos); }
								caretAdvance.set(0);
							} else {
								StringBuilder sb = new StringBuilder();
								sb.append("\n");
								sb.append("# ");  // Комментарий в колонке 0
								int cursorPos = insertOffsetFinal + sb.length();
								sb.append("\n");
								sb.append(hashesFinal);  // Закрывающие # в колонке 0

								finalDoc.insertString(insertOffsetFinal, sb.toString());
								finalHostEditor.getCaretModel().moveToOffset(cursorPos);
								if (!finalIsInjected) { caretOffsetRef.set(cursorPos); }
								caretAdvance.set(0);
							}
						});
						return Result.Stop;
					}
				}
			}

			// CASE COMMENT 2: Текущая строка начинается с # и предыдущая ИЛИ следующая тоже начинается с #
			// Продолжаем комментарий с тем же префиксом (все # и пробелы до первого текста)
			// Комментарии всегда в колонке 0
			// Если курсор в начале строки — стандартное поведение
			if (offset != lineStart) {
				boolean prevIsComment = false;
				boolean nextIsComment = false;

				// Проверяем предыдущую строку
				if (line > 0) {
					int prevLineStart = finalDoc.getLineStartOffset(line - 1);
					int prevLineEnd = finalDoc.getLineEndOffset(line - 1);
					String prevLineText = text.subSequence(prevLineStart, prevLineEnd).toString();
					prevIsComment = prevLineText.startsWith("#");
				}

				// Проверяем следующую строку
				int totalLines = finalDoc.getLineCount();
				if (line + 1 < totalLines) {
					int nextLineStart = finalDoc.getLineStartOffset(line + 1);
					int nextLineEnd = finalDoc.getLineEndOffset(line + 1);
					String nextLineText = text.subSequence(nextLineStart, nextLineEnd).toString();
					nextIsComment = nextLineText.startsWith("#");
				}

				// Предыдущая ИЛИ следующая строка тоже начинается с # (СТРОГО первый символ)
				if (prevIsComment || nextIsComment) {
					// Извлекаем весь префикс: все # и пробелы до первого непробельного не-# символа
					String commentPrefix = getCommentPrefix(currentLineText);

					int insertOffsetFinal = offset;
					String commentPrefixFinal = commentPrefix;


					WriteCommandAction.runWriteCommandAction(project, () -> {
						// Комментарии всегда в колонке 0
						String insert = "\n" + commentPrefixFinal;
						finalDoc.insertString(insertOffsetFinal, insert);

						int newCaretOffset = insertOffsetFinal + insert.length();
						finalHostEditor.getCaretModel().moveToOffset(newCaretOffset);
						if (!finalIsInjected) { caretOffsetRef.set(newCaretOffset); }
						caretAdvance.set(0);
					});
					return Result.Stop;
				}
			}

			// CASE COMMENT 3: Строка начинается с # но не выполняются условия выше
			// (первая строка комментария или после не-комментария)

			// Если курсор в начале строки — ничего не делаем, стандартное поведение
			if (offset == lineStart) {
				// Пропускаем, пусть IDE обработает стандартно
			} else {
				// Курсор не в начале строки
				// Добавляем отступ только если после # более одного пробельного символа
				// ИЛИ один пробельный символ но это таб
				String afterHash = getIndentAfterHash(currentLineText);

				// Проверяем нужно ли добавлять отступ
				boolean shouldAddIndent = false;
				if (afterHash.length() > 1) {
					// Более одного пробельного символа
					shouldAddIndent = true;
				} else if (afterHash.length() == 1 && afterHash.charAt(0) == '\t') {
					// Один символ и это таб
					shouldAddIndent = true;
				}
				// Если один пробел или пусто — не добавляем отступ

				if (shouldAddIndent) {
					int insertOffsetFinal = offset;
					String afterHashFinal = afterHash;

					WriteCommandAction.runWriteCommandAction(project, () -> {
						// Новая строка с отступом как после #
						String insert = "\n" + afterHashFinal;
						finalDoc.insertString(insertOffsetFinal, insert);

						int newCaretOffset = insertOffsetFinal + insert.length();
						finalHostEditor.getCaretModel().moveToOffset(newCaretOffset);
						if (!finalIsInjected) { caretOffsetRef.set(newCaretOffset); }
						caretAdvance.set(0);
					});
					return Result.Stop;
				}
				// Иначе — стандартное поведение (не добавляем отступ)
			}
		}

		// CASE 3: inherit current line's actual indent (for non-comment lines)
		String baseIndent = getBaseIndent(text, lineStart);
		String extraIndent = "";

		if (offset > lineStart) {
			int pos2 = offset - 1;
			while (pos2 >= lineStart) {
				char ch2 = text.charAt(pos2);
				if (ch2 == ' ' || ch2 == '\t') {
					pos2--;
					continue;
				}
				break;
			}
			if (pos2 >= lineStart) {
				char prev2 = text.charAt(pos2);
				if (prev2 == '{' || prev2 == '(' || prev2 == '[' || prev2 == '<') {
					extraIndent = getOneIndent(finalFile);
				}
			}
		}
		String finalIndent = baseIndent + extraIndent;
		String lineSeparator = "\n";
		int insertOffset = offset;

		WriteCommandAction.runWriteCommandAction(project, () -> {
			String insert = lineSeparator + finalIndent;
			finalDoc.insertString(insertOffset, insert);

			int newCaretOffset = insertOffset + insert.length();
			finalHostEditor.getCaretModel().moveToOffset(newCaretOffset);
			if (!finalIsInjected) { caretOffsetRef.set(newCaretOffset); }
			caretAdvance.set(0);

		});
		return Result.Stop;
	}

	/**
	 * Проверяет что строка состоит только из символов #
	 */
	private static boolean isOnlyHashes(String s) {
		if (s.isEmpty()) return false;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) != '#') return false;
		}
		return true;
	}

	/**
	 * Извлекает префикс из символов # в начале строки (после пробелов).
	 * Например: "  ### comment" -> "###"
	 *           "#### comment" -> "####"
	 *           "# comment" -> "#"
	 */
	private static String getHashPrefix(String line) {
		String trimmed = line.trim();
		if (!trimmed.startsWith("#")) {
			return "#";
		}

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < trimmed.length(); i++) {
			char ch = trimmed.charAt(i);
			if (ch == '#') {
				sb.append(ch);
			} else {
				break;
			}
		}

		return sb.length() > 0 ? sb.toString() : "#";
	}

	/**
	 * Извлекает отступ (пробелы/табы) после ВСЕХ # в строке.
	 * Например: "### \tcomment" -> " \t" (пробел + таб)
	 *           "####comment" -> " " (default пробел)
	 */
	private static String getIndentAfterHashes(String line) {
		String trimmed = line.trim();
		if (!trimmed.startsWith("#")) {
			return " ";
		}

		// Пропускаем все #
		int i = 0;
		while (i < trimmed.length() && trimmed.charAt(i) == '#') {
			i++;
		}

		// Собираем пробельные символы после #
		StringBuilder sb = new StringBuilder();
		while (i < trimmed.length()) {
			char ch = trimmed.charAt(i);
			if (ch == ' ' || ch == '\t') {
				sb.append(ch);
				i++;
			} else {
				break;
			}
		}

		// Если нет отступа после #, возвращаем один пробел
		return sb.length() > 0 ? sb.toString() : " ";
	}

	/**
	 * Извлекает весь префикс комментария: все # и пробелы до первого непробельного не-# символа.
	 * Например: "### # #		text" -> "### # #		"
	 *           "##  comment" -> "##  "
	 *           "#text" -> "# " (добавляем пробел если его нет)
	 */
	private static String getCommentPrefix(String line) {
		if (!line.startsWith("#")) {
			return "# ";
		}

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == '#' || ch == ' ' || ch == '\t') {
				sb.append(ch);
			} else {
				break;
			}
		}

		// Если префикс не заканчивается пробелом/табом, добавляем пробел
		if (sb.length() > 0) {
			char lastChar = sb.charAt(sb.length() - 1);
			if (lastChar != ' ' && lastChar != '\t') {
				sb.append(' ');
			}
		}

		return sb.length() > 0 ? sb.toString() : "# ";
	}

	/**
	 * Извлекает отступ (пробелы/табы) после первого # в строке
	 */
	private static String getIndentAfterHash(String line) {
		int hashPos = line.indexOf('#');
		if (hashPos < 0 || hashPos + 1 >= line.length()) {
			return " "; // default: один пробел
		}

		StringBuilder sb = new StringBuilder();
		for (int i = hashPos + 1; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == ' ' || ch == '\t') {
				sb.append(ch);
			} else {
				break;
			}
		}

		// Если нет отступа после #, возвращаем один пробел
		if (sb.length() == 0) {
			return " ";
		}
		return sb.toString();
	}

	/**
	 * Повторяет символ n раз
	 */
	private static String repeat(char ch, int count) {
		StringBuilder sb = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			sb.append(ch);
		}
		return sb.toString();
	}


	private static int findMatchingBrace(CharSequence text, int openPos) {
		if (openPos < 0 || openPos >= text.length()) return -1;
		char open = text.charAt(openPos);
		char close;
		if (open == '{') close = '}';
		else if (open == '(') close = ')';
		else if (open == '[') close = ']';
		else return -1;
		int depth = 0;
		for (int i = openPos; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == open) depth++;
			else if (ch == close) {
				depth--;
				if (depth == 0) return i;
			}
		}
		return -1;
	}

	private static boolean isBracketPair(char open, char close) {
		return (open == '{' && close == '}')
				|| (open == '[' && close == ']')
				|| (open == '(' && close == ')');
	}

	private static boolean isOpeningBracket(char ch) {
		return ch == '{' || ch == '[' || ch == '(';
	}

	private static boolean isClosingBracket(char ch) {
		return ch == '}' || ch == ']' || ch == ')';
	}

	private static int[] findEnclosingBracketPair(CharSequence text, int lineStart, int lineEnd, int caretOffset) {
		int bestOpen = -1;
		int bestClose = -1;

		char[] openStack = new char[64];
		int[] posStack = new int[64];
		int depth = 0;

		for (int i = lineStart; i < lineEnd; i++) {
			char ch = text.charAt(i);
			if (isOpeningBracket(ch)) {
				if (depth == openStack.length) {
					char[] newOpen = new char[openStack.length * 2];
					int[] newPos = new int[posStack.length * 2];
					System.arraycopy(openStack, 0, newOpen, 0, openStack.length);
					System.arraycopy(posStack, 0, newPos, 0, posStack.length);
					openStack = newOpen;
					posStack = newPos;
				}
				openStack[depth] = ch;
				posStack[depth] = i;
				depth++;
			} else if (isClosingBracket(ch) && depth > 0) {
				char openCh = openStack[depth - 1];
				int openPos = posStack[depth - 1];
				if (isBracketPair(openCh, ch)) {
					depth--;
					if (caretOffset > openPos && caretOffset <= i) {
						if (openPos > bestOpen) {
							bestOpen = openPos;
							bestClose = i;
						}
					}
				} else {
					depth--;
				}
			}
		}

		if (bestOpen >= 0 && bestClose >= 0) {
			return new int[]{bestOpen, bestClose};
		}
		return null;
	}

	private static int[] findEnclosingTagPair(CharSequence text, int lineStart, int lineEnd, int caretOffset) {
		// find nearest '>' before caret
		int gtOpen = -1;
		for (int i = caretOffset - 1; i >= lineStart; i--) {
			char ch = text.charAt(i);
			if (ch == '>') {
				gtOpen = i;
				break;
			}
			if (ch == '\n' || ch == '\r') {
				break;
			}
		}
		if (gtOpen == -1) {
			return null;
		}

		// find '<' before that
		int ltOpen = -1;
		for (int i = gtOpen - 1; i >= lineStart; i--) {
			char ch = text.charAt(i);
			if (ch == '<') {
				ltOpen = i;
				break;
			}
			if (ch == '\n' || ch == '\r') {
				break;
			}
		}
		if (ltOpen == -1) {
			return null;
		}

		// caret must be after '>'
		if (caretOffset <= gtOpen) {
			return null;
		}

		// parse tag name
		int nameStart = ltOpen + 1;
		while (nameStart < gtOpen && Character.isWhitespace(text.charAt(nameStart))) {
			nameStart++;
		}
		if (nameStart >= gtOpen) {
			return null;
		}
		if (text.charAt(nameStart) == '/') {
			return null;
		}

		int nameEnd = nameStart;
		while (nameEnd < gtOpen) {
			char ch = text.charAt(nameEnd);
			if (Character.isWhitespace(ch) || ch == '>' || ch == '/') {
				break;
			}
			nameEnd++;
		}
		if (nameEnd <= nameStart) {
			return null;
		}
		String tagName = text.subSequence(nameStart, nameEnd).toString();

		String closePattern = "</" + tagName;
		int ltClose = -1;

		for (int i = caretOffset; i < lineEnd; i++) {
			if (text.charAt(i) == '<') {
				int j = i;
				int k = 0;
				while (j < lineEnd && k < closePattern.length()) {
					if (text.charAt(j) != closePattern.charAt(k)) {
						break;
					}
					j++;
					k++;
				}
				if (k == closePattern.length()) {
					ltClose = i;
					break;
				}
			}
		}
		if (ltClose == -1) {
			return null;
		}

		int gtClose = -1;
		for (int i = ltClose; i < lineEnd; i++) {
			if (text.charAt(i) == '>') {
				gtClose = i;
				break;
			}
		}
		if (gtClose == -1) {
			return null;
		}

		// caret must be before closing tag
		if (caretOffset > ltClose) {
			return null;
		}

		return new int[]{ltOpen, gtOpen, ltClose, gtClose};
	}

	private static String getBaseIndent(CharSequence text, int lineStart) {
		int indentEnd = lineStart;
		int textLength = text.length();
		while (indentEnd < textLength) {
			char ch = text.charAt(indentEnd);
			if (ch == ' ' || ch == '\t') {
				indentEnd++;
			} else {
				break;
			}
		}
		return text.subSequence(lineStart, indentEnd).toString();
	}

	private static String getOneIndent(@NotNull PsiFile file) {
		CommonCodeStyleSettings.IndentOptions indentOptions = CodeStyle.getIndentOptions(file);
		if (indentOptions.USE_TAB_CHARACTER) {
			return "\t";
		}
		int size = Math.max(1, indentOptions.INDENT_SIZE);
		StringBuilder sb = new StringBuilder(size);
		for (int i = 0; i < size; i++) {
			sb.append(' ');
		}
		return sb.toString();
	}
}
