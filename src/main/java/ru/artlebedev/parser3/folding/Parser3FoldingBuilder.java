package ru.artlebedev.parser3.folding;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Обеспечивает сворачивание (folding) для Parser3:
 * - Методы с параметрами [] (оставляет видимой первую строку с @method[])
 * - Блочные комментарии ^rem{...}
 * - Конструкции ^if, ^while, ^for (оставляет видимой первую строку)
 * - Многострочные линейные комментарии (последовательности # ...)
 *
 * Примеры сворачивания:
 * @create[name]{...}           - первая строка видна
 * ^if($condition){...}         - первая строка видна
 * ^for[x](1;100){...}          - первая строка видна
 */
public class Parser3FoldingBuilder extends FoldingBuilderEx {

	@NotNull
	@Override
	public FoldingDescriptor[] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick) {
		List<FoldingDescriptor> descriptors = new ArrayList<>();

		// Обходим все элементы дерева PSI
		collectFoldingRegions(root, descriptors, document);

		return descriptors.toArray(new FoldingDescriptor[0]);
	}

	private void collectFoldingRegions(PsiElement root, List<FoldingDescriptor> descriptors, Document document) {
		// Итерируем по AST-дереву вместо рекурсии по PSI — это быстрее
		ASTNode node = root.getNode();
		if (node == null) return;

		// Используем стек для итеративного обхода
		java.util.ArrayDeque<ASTNode> stack = new java.util.ArrayDeque<>();
		stack.push(node);

		while (!stack.isEmpty()) {
			ASTNode current = stack.pop();
			IElementType type = current.getElementType();

			// Добавляем детей в стек (в обратном порядке для правильного порядка обхода)
			ASTNode child = current.getLastChildNode();
			while (child != null) {
				stack.push(child);
				child = child.getTreePrev();
			}

			// Обрабатываем текущий элемент
			processFoldingNode(current, type, descriptors, document);
		}
	}

	private void processFoldingNode(ASTNode node, IElementType type, List<FoldingDescriptor> descriptors, Document document) {
		PsiElement element = node.getPsi();
		if (element == null) return;

		// Сворачивание методов (только с квадратными скобками [])
		if (type == Parser3TokenTypes.DEFINE_METHOD ||
				type == Parser3TokenTypes.SPECIAL_METHOD) {
			// Проверяем, есть ли следующий элемент с квадратными скобками
			ASTNode next = node.getTreeNext();

			// Пропускаем пробелы
			while (next != null && next.getElementType() == Parser3TokenTypes.WHITE_SPACE) {
				next = next.getTreeNext();
			}

			// Проверяем, что следующий элемент - это LBRACKET
			if (next != null && next.getElementType() == Parser3TokenTypes.LBRACKET) {
				TextRange methodRange = findMethodRange(element, document);
				if (methodRange != null) {
					int startLine = document.getLineNumber(methodRange.getStartOffset());
					int endLine = document.getLineNumber(methodRange.getEndOffset());
					if (endLine > startLine) {
						String placeholder = generateMethodPlaceholder(element);
						descriptors.add(new FoldingDescriptor(node, methodRange, null, placeholder));
					}
				}
			}
		}

		// Сворачивание ^rem{...} и вложенных скобок внутри rem (все типы: {}, [], ())
		if (type == Parser3TokenTypes.REM_LBRACE ||
				type == Parser3TokenTypes.REM_LBRACKET ||
				type == Parser3TokenTypes.REM_LPAREN) {

			// Определяем парные типы скобок
			IElementType closeType;
			String openStr, closeStr;
			if (type == Parser3TokenTypes.REM_LBRACE) {
				closeType = Parser3TokenTypes.REM_RBRACE;
				openStr = "{";
				closeStr = "}";
			} else if (type == Parser3TokenTypes.REM_LBRACKET) {
				closeType = Parser3TokenTypes.REM_RBRACKET;
				openStr = "[";
				closeStr = "]";
			} else {
				closeType = Parser3TokenTypes.REM_RPAREN;
				openStr = "(";
				closeStr = ")";
			}

			TextRange remRange = findMatchingRemBracketGeneric(element, type, closeType);
			if (remRange != null && isMultiLine(remRange, document)) {
				// Проверяем, есть ли ^rem перед скобкой (только для {)
				PsiElement remKeyword = (type == Parser3TokenTypes.REM_LBRACE)
						? findRemKeywordStrict(element)
						: null;

				if (remKeyword != null) {
					// Это главная скобка ^rem{...}
					int startOffset = remKeyword.getTextRange().getStartOffset();
					int endOffset = remRange.getEndOffset();
					TextRange fullRange = new TextRange(startOffset, endOffset);
					descriptors.add(new FoldingDescriptor(remKeyword.getNode(), fullRange, null, "^rem{...}"));
				} else {
					// Это вложенная скобка внутри rem
					// Расширяем диапазон до первого непробельного символа строки
					int bracketOffset = element.getTextRange().getStartOffset();
					int startLine = document.getLineNumber(bracketOffset);
					int lineStartOffset = document.getLineStartOffset(startLine);
					String lineText = document.getText(new TextRange(lineStartOffset, bracketOffset));

					// Ищем первый непробельный символ
					int firstNonWhitespace = 0;
					for (int i = 0; i < lineText.length(); i++) {
						if (!Character.isWhitespace(lineText.charAt(i))) {
							firstNonWhitespace = i;
							break;
						}
					}

					int expandedStartOffset = lineStartOffset + firstNonWhitespace;
					TextRange expandedRange = new TextRange(expandedStartOffset, remRange.getEndOffset());

					// Генерируем placeholder: текст перед скобкой + скобки
					String beforeBracket = document.getText(new TextRange(expandedStartOffset, bracketOffset));
					String placeholder = beforeBracket + openStr + "..." + closeStr;

					descriptors.add(new FoldingDescriptor(node, expandedRange, null, placeholder));
				}
			}
		}

		// Сворачивание парных скобок (){}[]
		if (type == Parser3TokenTypes.LPAREN ||
				type == Parser3TokenTypes.LBRACE ||
				type == Parser3TokenTypes.LBRACKET) {

			boolean skipThisBracket = false;

			if (type == Parser3TokenTypes.LPAREN) {
				ASTNode prev = node.getTreePrev();
				while (prev != null && prev.getElementType() == Parser3TokenTypes.WHITE_SPACE) {
					prev = prev.getTreePrev();
				}

				if (prev != null) {
					IElementType prevType = prev.getElementType();
					if (prevType == Parser3TokenTypes.KW_IF ||
							prevType == Parser3TokenTypes.KW_WHILE ||
							prevType == Parser3TokenTypes.KW_FOR ||
							prevType == Parser3TokenTypes.RBRACKET) {
						skipThisBracket = true;
					}
				}
			}

			if (type == Parser3TokenTypes.LBRACKET) {
				ASTNode prev = node.getTreePrev();
				while (prev != null && prev.getElementType() == Parser3TokenTypes.WHITE_SPACE) {
					prev = prev.getTreePrev();
				}

				if (prev != null) {
					IElementType prevType = prev.getElementType();
					if (prevType == Parser3TokenTypes.KW_FOR ||
							prevType == Parser3TokenTypes.KW_SWITCH ||
							prevType == Parser3TokenTypes.KW_CASE ||
							prevType == Parser3TokenTypes.DEFINE_METHOD ||
							prevType == Parser3TokenTypes.SPECIAL_METHOD) {
						skipThisBracket = true;
					}
				}
			}

			if (type == Parser3TokenTypes.LBRACE) {
				ASTNode prev = node.getTreePrev();
				while (prev != null && prev.getElementType() == Parser3TokenTypes.WHITE_SPACE) {
					prev = prev.getTreePrev();
				}

				if (prev != null) {
					IElementType prevType = prev.getElementType();
					// Пропускаем { после ) — это часть ^if(){}
					// Пропускаем { после ключевых слов — они обрабатываются отдельно
					// { после ] — это ^method[]{}, нужно сворачивать
					if (prevType == Parser3TokenTypes.RPAREN ||
							prevType == Parser3TokenTypes.KW_TRY ||
							prevType == Parser3TokenTypes.RBRACE) {
						// RBRACE - это вторая ветка: ^try{...}{...} или ^if(){...}{...}
						skipThisBracket = true;
					}
				}
			}

			if (!skipThisBracket) {
				TextRange bracketRange = findMatchingBracket(element, document);
				if (bracketRange != null && isMultiLine(bracketRange, document)) {
					String placeholder = generateBracketPlaceholder(element, document, type);
					descriptors.add(new FoldingDescriptor(node, bracketRange, null, placeholder));
				}
			}
		}

		// Сворачивание управляющих конструкций ^if, ^while, ^for, ^try
		if (type == Parser3TokenTypes.KW_IF ||
				type == Parser3TokenTypes.KW_WHILE ||
				type == Parser3TokenTypes.KW_FOR ||
				type == Parser3TokenTypes.KW_TRY) {
			TextRange controlRange = findFullControlStructureRange(element, document);
			if (controlRange != null && isMultiLine(controlRange, document)) {
				String placeholder = generateControlStructurePlaceholder(element, document);
				descriptors.add(new FoldingDescriptor(node, controlRange, null, placeholder));
			}
		}

		// Сворачивание последовательных линейных комментариев
		if (type == Parser3TokenTypes.LINE_COMMENT) {
			TextRange commentBlockRange = findConsecutiveLineComments(element, document);
			if (commentBlockRange != null && isMultiLine(commentBlockRange, document)) {
				descriptors.add(new FoldingDescriptor(node, commentBlockRange, null, "#..."));
			}
		}
	}

	/**
	 * Возвращает текст ключевого слова для отображения
	 */
	private String getKeywordText(IElementType type) {
		if (type == Parser3TokenTypes.KW_IF) return "^if";
		if (type == Parser3TokenTypes.KW_WHILE) return "^while";
		if (type == Parser3TokenTypes.KW_FOR) return "^for";
		if (type == Parser3TokenTypes.KW_TRY) return "^try";
		return "";
	}

	/**
	 * Генерирует placeholder для метода: собирает всю первую строку + {... }
	 */
	private String generateMethodPlaceholder(PsiElement methodElement) {
		Document document = PsiDocumentManager.getInstance(methodElement.getProject()).getDocument(methodElement.getContainingFile());
		if (document == null) {
			return "{...}";
		}

		int methodStartOffset = methodElement.getTextRange().getStartOffset();
		int methodStartLine = document.getLineNumber(methodStartOffset);
		int lineEndOffset = document.getLineEndOffset(methodStartLine);

		// Получаем весь текст первой строки
		String firstLine = document.getText(new TextRange(methodStartOffset, lineEndOffset));

		return firstLine + "{...}";
	}

	/**
	 * Генерирует placeholder для if/while/for: собирает текст от начала до открывающей { + {... }
	 */
	private String generateControlStructurePlaceholder(PsiElement keywordElement, Document document) {
		int startOffset = keywordElement.getTextRange().getStartOffset();

		// Ищем первую открывающую скобку {
		PsiElement current = keywordElement.getNextSibling();
		int openBraceOffset = -1;

		while (current != null) {
			if (current.getNode() != null) {
				IElementType type = current.getNode().getElementType();
				if (type == Parser3TokenTypes.LBRACE) {
					openBraceOffset = current.getTextRange().getEndOffset();
					break;
				}
			}
			current = current.getNextSibling();
		}

		if (openBraceOffset == -1) {
			// Не нашли открывающую скобку, возвращаем просто ключевое слово
			return keywordElement.getText() + "...";
		}

		// Берем текст от начала ключевого слова до конца открывающей скобки {
		String beforeBrace = document.getText(new TextRange(startOffset, openBraceOffset));

		return beforeBrace + "...}";
	}

	/**
	 * Генерирует placeholder для скобок: текст от первого непробельного символа до открывающей скобки + скобка + ...закрывающая
	 * Для фигурных скобок { после квадратных [ включает содержимое квадратных скобок
	 */
	private String generateBracketPlaceholder(PsiElement openBracket, Document document, IElementType openType) {
		int startOffset = openBracket.getTextRange().getStartOffset();
		int startLine = document.getLineNumber(startOffset);
		int lineStartOffset = document.getLineStartOffset(startLine);
		int lineEndOffset = document.getLineEndOffset(startLine);

		// Ищем первый непробельный символ на строке
		String lineText = document.getText(new TextRange(lineStartOffset, lineEndOffset));
		int firstNonWhitespace = 0;
		for (int i = 0; i < lineText.length(); i++) {
			if (!Character.isWhitespace(lineText.charAt(i))) {
				firstNonWhitespace = i;
				break;
			}
		}

		int firstNonWhitespaceOffset = lineStartOffset + firstNonWhitespace;

		// Для фигурных скобок проверяем, нет ли перед ними [...]
		if (openType == Parser3TokenTypes.LBRACE) {
			// Ищем квадратные скобки перед {
			PsiElement prev = openBracket.getPrevSibling();
			PsiElement closingBracket = null;

			// Пропускаем whitespace
			while (prev != null && prev.getNode() != null &&
					prev.getNode().getElementType() == Parser3TokenTypes.WHITE_SPACE) {
				prev = prev.getPrevSibling();
			}

			// Проверяем, что это ]
			if (prev != null && prev.getNode() != null &&
					prev.getNode().getElementType() == Parser3TokenTypes.RBRACKET) {
				closingBracket = prev;

				// Ищем соответствующую [
				int nesting = 1;
				PsiElement current = prev.getPrevSibling();
				while (current != null && nesting > 0) {
					if (current.getNode() != null) {
						IElementType type = current.getNode().getElementType();
						if (type == Parser3TokenTypes.RBRACKET) {
							nesting++;
						} else if (type == Parser3TokenTypes.LBRACKET) {
							nesting--;
							if (nesting == 0) {
								// Нашли парную [, включаем весь текст от начала строки до }
								String beforeBrace = document.getText(new TextRange(firstNonWhitespaceOffset, startOffset));
								return beforeBrace + "{...}";
							}
						}
					}
					current = current.getPrevSibling();
				}
			}
		}

		// Получаем текст от первого непробельного символа до открывающей скобки (НЕ включая скобку)
		String beforeBracket = document.getText(new TextRange(firstNonWhitespaceOffset, startOffset));

		// Определяем скобки
		String openBracketStr = "(";
		String closeBracket = ")";
		if (openType == Parser3TokenTypes.LBRACE) {
			openBracketStr = "{";
			closeBracket = "}";
		} else if (openType == Parser3TokenTypes.LBRACKET) {
			openBracketStr = "[";
			closeBracket = "]";
		}

		return beforeBracket + openBracketStr + "..." + closeBracket;
	}

	/**
	 * Находит парную закрывающую скобку для (){}[]
	 * Возвращает диапазон от начала строки (где открывающая скобка) до закрывающей скобки
	 */
	@Nullable
	private TextRange findMatchingBracket(PsiElement openBracket, Document document) {
		ASTNode openNode = openBracket.getNode();
		if (openNode == null) return null;

		IElementType openType = openNode.getElementType();
		IElementType closeType = null;

		if (openType == Parser3TokenTypes.LPAREN) closeType = Parser3TokenTypes.RPAREN;
		else if (openType == Parser3TokenTypes.LBRACE) closeType = Parser3TokenTypes.RBRACE;
		else if (openType == Parser3TokenTypes.LBRACKET) closeType = Parser3TokenTypes.RBRACKET;

		if (closeType == null) return null;

		PsiElement current = openBracket.getNextSibling();
		int nestingLevel = 1;
		PsiElement closeBracket = null;

		while (current != null && nestingLevel > 0) {
			ASTNode node = current.getNode();
			if (node != null) {
				IElementType currentType = node.getElementType();

				if (currentType == openType) {
					nestingLevel++;
				} else if (currentType == closeType) {
					nestingLevel--;
					if (nestingLevel == 0) {
						closeBracket = current;
						break;
					}
				}
			}
			current = current.getNextSibling();
		}

		if (closeBracket != null) {
			// Начинаем диапазон с первого непробельного символа на строке
			int openBracketLine = document.getLineNumber(openBracket.getTextRange().getStartOffset());
			int lineStartOffset = document.getLineStartOffset(openBracketLine);
			int lineEndOffset = document.getLineEndOffset(openBracketLine);

			// Ищем первый непробельный символ на строке
			String lineText = document.getText(new TextRange(lineStartOffset, lineEndOffset));
			int firstNonWhitespace = 0;
			for (int i = 0; i < lineText.length(); i++) {
				if (!Character.isWhitespace(lineText.charAt(i))) {
					firstNonWhitespace = i;
					break;
				}
			}

			int startOffset = lineStartOffset + firstNonWhitespace;
			int endOffset = closeBracket.getTextRange().getEndOffset();
			return new TextRange(startOffset, endOffset);
		}

		return null;
	}

	/**
	 * Находит полный диапазон для скобок с учетом соприкасающихся конструкций.
	 * Примеры:
	 * ^if($a){b}          -> от начала строки до }
	 * ^if($a){b}{c}       -> от начала строки до последней }
	 * ^int:sql{...}[...]  -> от начала строки до последней ]
	 */
	@Nullable
	private TextRange findBracketRangeWithChaining(PsiElement firstOpenBracket, Document document) {
		int firstBraceLine = document.getLineNumber(firstOpenBracket.getTextRange().getStartOffset());
		int lineStartOffset = document.getLineStartOffset(firstBraceLine);
		int lineEndOffset = document.getLineEndOffset(firstBraceLine);

		// Ищем первый непробельный символ на строке
		String lineText = document.getText(new TextRange(lineStartOffset, lineEndOffset));
		int firstNonWhitespace = 0;
		for (int i = 0; i < lineText.length(); i++) {
			if (!Character.isWhitespace(lineText.charAt(i))) {
				firstNonWhitespace = i;
				break;
			}
		}

		int startOffset = lineStartOffset + firstNonWhitespace;

		PsiElement current = firstOpenBracket;
		PsiElement lastClosingBrace = null;

		// Проходим через все блоки конструкции
		while (current != null) {
			if (current.getNode() != null) {
				IElementType currentType = current.getNode().getElementType();

				// Обрабатываем любую открывающую скобку
				if (currentType == Parser3TokenTypes.LBRACE) {
					PsiElement closeBrace = findMatchingCloseBrace(current);
					if (closeBrace != null) {
						lastClosingBrace = closeBrace;
						current = closeBrace;
					} else {
						break;
					}
				} else if (currentType == Parser3TokenTypes.LBRACKET) {
					PsiElement closeBracket = findMatchingCloseBracket(current);
					if (closeBracket != null) {
						lastClosingBrace = closeBracket;
						current = closeBracket;
					} else {
						break;
					}
				} else if (currentType == Parser3TokenTypes.LPAREN) {
					PsiElement closeParen = findMatchingCloseParen(current);
					if (closeParen != null) {
						lastClosingBrace = closeParen;
						current = closeParen;
					} else {
						break;
					}
				}

				// Проверяем, есть ли следующая скобка сразу после текущей
				PsiElement next = current.getNextSibling();
				boolean hasMoreBrackets = false;

				// Пропускаем whitespace
				while (next != null && next.getNode() != null &&
						next.getNode().getElementType() == Parser3TokenTypes.WHITE_SPACE) {
					next = next.getNextSibling();
				}

				// Проверяем следующий элемент
				if (next != null && next.getNode() != null) {
					IElementType nextType = next.getNode().getElementType();
					// Если следующее - любая открывающая скобка, продолжаем
					if (nextType == Parser3TokenTypes.LPAREN ||
							nextType == Parser3TokenTypes.LBRACE ||
							nextType == Parser3TokenTypes.LBRACKET) {
						hasMoreBrackets = true;
						current = next;
					}
				}

				if (!hasMoreBrackets) {
					break;
				}
			} else {
				current = current.getNextSibling();
			}
		}

		if (lastClosingBrace != null) {
			int endOffset = lastClosingBrace.getTextRange().getEndOffset();
			return new TextRange(startOffset, endOffset);
		}

		return null;
	}

	/**
	 * Находит парную закрывающую фигурную скобку для данной открывающей
	 */
	@Nullable
	private PsiElement findMatchingCloseBrace(PsiElement openBrace) {
		PsiElement current = openBrace.getNextSibling();
		int nestingLevel = 1;

		while (current != null && nestingLevel > 0) {
			if (current.getNode() != null) {
				IElementType type = current.getNode().getElementType();

				if (type == Parser3TokenTypes.LBRACE) {
					nestingLevel++;
				} else if (type == Parser3TokenTypes.RBRACE) {
					nestingLevel--;
					if (nestingLevel == 0) {
						return current;
					}
				}
			}
			current = current.getNextSibling();
		}

		return null;
	}

	/**
	 * Находит парную закрывающую квадратную скобку
	 */
	@Nullable
	private PsiElement findMatchingCloseBracket(PsiElement openBracket) {
		PsiElement current = openBracket.getNextSibling();
		int nestingLevel = 1;

		while (current != null && nestingLevel > 0) {
			if (current.getNode() != null) {
				IElementType type = current.getNode().getElementType();

				if (type == Parser3TokenTypes.LBRACKET) {
					nestingLevel++;
				} else if (type == Parser3TokenTypes.RBRACKET) {
					nestingLevel--;
					if (nestingLevel == 0) {
						return current;
					}
				}
			}
			current = current.getNextSibling();
		}

		return null;
	}

	/**
	 * Находит парную закрывающую круглую скобку
	 */
	@Nullable
	private PsiElement findMatchingCloseParen(PsiElement openParen) {
		PsiElement current = openParen.getNextSibling();
		int nestingLevel = 1;

		while (current != null && nestingLevel > 0) {
			if (current.getNode() != null) {
				IElementType type = current.getNode().getElementType();

				if (type == Parser3TokenTypes.LPAREN) {
					nestingLevel++;
				} else if (type == Parser3TokenTypes.RPAREN) {
					nestingLevel--;
					if (nestingLevel == 0) {
						return current;
					}
				}
			}
			current = current.getNextSibling();
		}

		return null;
	}

	/**
	 * Находит диапазон метода от @method[params] до конца строки перед следующим методом.
	 * НЕ включает следующий метод и комментарии непосредственно перед ним (без пустых строк).
	 */
	@Nullable
	private TextRange findMethodRange(PsiElement methodStart, Document document) {
		int methodStartLine = document.getLineNumber(methodStart.getTextRange().getStartOffset());
		int startOffset = methodStart.getTextRange().getStartOffset();


		// Ищем конец метода (перед следующим методом на новой строке или конец файла)
		PsiElement current = methodStart.getNextSibling();
		PsiElement lastNonWhitespace = methodStart;

		// Список комментариев перед следующим методом
		List<PsiElement> commentsBeforeNextMethod = new ArrayList<>();
		boolean foundNextMethod = false;

		while (current != null) {
			ASTNode node = current.getNode();
			if (node != null) {
				IElementType type = node.getElementType();

				// Если встретили следующий метод на новой строке
				if (type == Parser3TokenTypes.DEFINE_METHOD ||
						type == Parser3TokenTypes.SPECIAL_METHOD) {
					int nextMethodLine = document.getLineNumber(current.getTextRange().getStartOffset());
					if (nextMethodLine > methodStartLine) {
						foundNextMethod = true;
						break;
					}
				}

				// Собираем комментарии
				if (type == Parser3TokenTypes.LINE_COMMENT) {
					commentsBeforeNextMethod.add(current);
				} else if (type == Parser3TokenTypes.REM_LBRACE) {
					// Для ^rem нужно найти элемент ^rem перед скобкой и добавить его
					PsiElement remKeyword = findRemKeyword(current);
					if (remKeyword != null) {
						commentsBeforeNextMethod.add(remKeyword);
					}
					// Пропускаем все элементы внутри ^rem до закрывающей скобки
					PsiElement remScan = current.getNextSibling();
					int remNesting = 1;
					PsiElement remCloseBrace = null;
					while (remScan != null && remNesting > 0) {
						if (remScan.getNode() != null) {
							IElementType remType = remScan.getNode().getElementType();
							if (remType == Parser3TokenTypes.REM_LBRACE) {
								remNesting++;
							} else if (remType == Parser3TokenTypes.REM_RBRACE) {
								remNesting--;
								if (remNesting == 0) {
									remCloseBrace = remScan;
								}
							}
						}
						remScan = remScan.getNextSibling();
					}
					// Перемещаем current на закрывающую скобку, чтобы следующая итерация началась после неё
					if (remCloseBrace != null) {
						current = remCloseBrace;
						// Обновляем type и node для корректной дальнейшей обработки
						node = current.getNode();
						if (node != null) {
							type = node.getElementType();
						}
					}
				}

				// Запоминаем последний не-whitespace элемент
				if (type != Parser3TokenTypes.WHITE_SPACE) {
					String elementText = current.getText();
					boolean isOnlyWhitespace = elementText.trim().isEmpty();

					if (!isOnlyWhitespace) {
						int elementLine = document.getLineNumber(current.getTextRange().getStartOffset());

						// Проверяем следующий элемент - не метод ли это
						PsiElement next = current.getNextSibling();
						boolean nextIsMethod = false;
						int nextMethodLine = -1;

						while (next != null && next.getNode() != null) {
							IElementType nextType = next.getNode().getElementType();
							if (nextType == Parser3TokenTypes.WHITE_SPACE) {
								next = next.getNextSibling();
								continue;
							}
							if (nextType == Parser3TokenTypes.DEFINE_METHOD || nextType == Parser3TokenTypes.SPECIAL_METHOD) {
								nextIsMethod = true;
								nextMethodLine = document.getLineNumber(next.getTextRange().getStartOffset());
							}
							break;
						}

						// Если следующий элемент - метод на другой строке, не обновляем lastNonWhitespace
						if (!nextIsMethod || elementLine < nextMethodLine) {
							// Если это не комментарий, сбрасываем список комментариев
							if (type != Parser3TokenTypes.LINE_COMMENT &&
									type != Parser3TokenTypes.REM_LBRACE &&
									type != Parser3TokenTypes.REM_RBRACE &&
									!isRemKeyword(current)) {
								commentsBeforeNextMethod.clear();
								lastNonWhitespace = current;
							}
						}
					}
				}
			}

			current = current.getNextSibling();
		}


		// Если нашли следующий метод и перед ним есть комментарии
		if (foundNextMethod && !commentsBeforeNextMethod.isEmpty()) {

			// Проверяем, есть ли пустая строка между кодом и комментариями
			PsiElement firstComment = commentsBeforeNextMethod.get(0);
			int firstCommentLine = document.getLineNumber(firstComment.getTextRange().getStartOffset());

			// Ищем последний реальный код перед комментариями
			// Идем по всем элементам от начала метода до первого комментария
			PsiElement scan = methodStart;
			PsiElement lastRealCode = methodStart;

			while (scan != null && scan != firstComment) {
				if (scan.getNode() != null) {
					IElementType scanType = scan.getNode().getElementType();

					// Пропускаем весь блок ^rem
					if (scanType == Parser3TokenTypes.REM_LBRACE) {
						// Пропускаем до закрывающей скобки
						int remNesting = 1;
						scan = scan.getNextSibling();
						while (scan != null && remNesting > 0 && scan != firstComment) {
							if (scan.getNode() != null) {
								IElementType remType = scan.getNode().getElementType();
								if (remType == Parser3TokenTypes.REM_LBRACE) {
									remNesting++;
								} else if (remType == Parser3TokenTypes.REM_RBRACE) {
									remNesting--;
								}
							}
							if (remNesting > 0) {
								scan = scan.getNextSibling();
							}
						}
						continue;
					}

					// Если это не whitespace и не комментарий - это код
					if (scanType != Parser3TokenTypes.WHITE_SPACE &&
							scanType != Parser3TokenTypes.LINE_COMMENT &&
							scanType != Parser3TokenTypes.REM_LBRACE &&
							scanType != Parser3TokenTypes.REM_RBRACE &&
							!isRemKeyword(scan)) {
						String scanText = scan.getText();
						if (!scanText.trim().isEmpty()) {
							lastRealCode = scan;
						}
					}
				}
				scan = scan.getNextSibling();
			}

			if (lastRealCode != null && lastRealCode != methodStart) {
				int lastCodeLine = document.getLineNumber(lastRealCode.getTextRange().getEndOffset());

				// Если между кодом и первым комментарием есть пустая строка (разница > 1)
				// значит комментарии относятся к следующему методу
				if (firstCommentLine - lastCodeLine > 1) {
					// Исключаем комментарии из текущего метода
					// Устанавливаем конец на последний реальный код
					lastNonWhitespace = lastRealCode;
				}
			}
		}

		// Конец диапазона - конец последнего не-whitespace элемента
		int endOffset = lastNonWhitespace.getTextRange().getEndOffset();

		if (endOffset > startOffset) {
			return new TextRange(startOffset, endOffset);
		}

		return null;
	}

	/**
	 * Находит парную закрывающую скобку для ^rem{...}
	 */
	@Nullable
	private TextRange findMatchingRemBracket(PsiElement openBracket, Document document) {
		return findMatchingRemBracketGeneric(openBracket, Parser3TokenTypes.REM_LBRACE, Parser3TokenTypes.REM_RBRACE);
	}

	/**
	 * Находит парную закрывающую REM-скобку любого типа
	 */
	@Nullable
	private TextRange findMatchingRemBracketGeneric(PsiElement openBracket, IElementType openType, IElementType closeType) {
		ASTNode openNode = openBracket.getNode();
		if (openNode == null) return null;

		PsiElement current = openBracket.getNextSibling();
		int nestingLevel = 1;
		PsiElement closeBracket = null;

		// Ищем парную закрывающую REM скобку
		while (current != null && nestingLevel > 0) {
			ASTNode node = current.getNode();
			if (node != null) {
				IElementType currentType = node.getElementType();

				// Увеличиваем уровень при встрече открывающей REM скобки того же типа
				if (currentType == openType) {
					nestingLevel++;
				}
				// Уменьшаем уровень при встрече закрывающей REM скобки того же типа
				else if (currentType == closeType) {
					nestingLevel--;
					if (nestingLevel == 0) {
						closeBracket = current;
						break;
					}
				}
			}
			current = current.getNextSibling();
		}

		if (closeBracket != null) {
			int startOffset = openBracket.getTextRange().getStartOffset();
			int endOffset = closeBracket.getTextRange().getEndOffset();
			return new TextRange(startOffset, endOffset);
		}

		return null;
	}

	/**
	 * Ищет элемент ^rem перед открывающей скобкой REM_LBRACE
	 */
	@Nullable
	private PsiElement findRemKeyword(PsiElement remBrace) {
		// Идем назад от скобки, ищем элемент с текстом "^rem"
		PsiElement current = remBrace.getPrevSibling();
		while (current != null) {
			// Пропускаем пробелы
			if (current.getNode() != null &&
					current.getNode().getElementType() == Parser3TokenTypes.WHITE_SPACE) {
				current = current.getPrevSibling();
				continue;
			}

			// Проверяем, что это ^rem
			String text = current.getText();
			if (text != null && text.equals("^rem")) {
				return current;
			}

			// Если встретили что-то другое, значит ^rem не нашли
			break;
		}

		// Если не нашли ^rem перед скобкой, возвращаем саму скобку
		return remBrace;
	}

	/**
	 * Ищет элемент ^rem перед открывающей скобкой REM_LBRACE.
	 * Возвращает null если ^rem не найден (вложенная скобка внутри rem).
	 */
	@Nullable
	private PsiElement findRemKeywordStrict(PsiElement remBrace) {
		// Идем назад от скобки, ищем элемент с текстом "^rem"
		PsiElement current = remBrace.getPrevSibling();
		while (current != null) {
			// Пропускаем пробелы
			if (current.getNode() != null &&
					current.getNode().getElementType() == Parser3TokenTypes.WHITE_SPACE) {
				current = current.getPrevSibling();
				continue;
			}

			// Проверяем, что это ^rem
			String text = current.getText();
			if (text != null && text.equals("^rem")) {
				return current;
			}

			// Если встретили что-то другое, значит ^rem не нашли
			break;
		}

		// Не нашли ^rem — это вложенная скобка
		return null;
	}

	/**
	 * Проверяет, является ли элемент ключевым словом ^rem
	 */
	private boolean isRemKeyword(PsiElement element) {
		if (element == null) return false;
		String text = element.getText();
		return text != null && text.equals("^rem");
	}

	/**
	 * Находит полный диапазон if/while/for конструкции.
	 * Оставляет первую строку видимой, сворачивает тело.
	 * Примеры:
	 * ^if($a){ -> остается видимой
	 *   b      -> сворачивается
	 * }        -> сворачивается
	 *
	 * ^for[x](1;100){ -> остается видимой
	 *   код           -> сворачивается
	 * }               -> сворачивается
	 */
	@Nullable
	private TextRange findFullControlStructureRange(PsiElement keywordElement, Document document) {
		int keywordLine = document.getLineNumber(keywordElement.getTextRange().getStartOffset());

		PsiElement current = keywordElement.getNextSibling();
		PsiElement lastClosingBrace = null;
		PsiElement firstOpenBrace = null;
		int openBraceCount = 0;

		// Ищем первую открывающую скобку и последнюю закрывающую
		while (current != null) {
			ASTNode node = current.getNode();
			if (node == null) {
				current = current.getNextSibling();
				continue;
			}

			IElementType type = node.getElementType();

			if (type == Parser3TokenTypes.LBRACE) {
				if (firstOpenBrace == null) {
					firstOpenBrace = current;
				}
				openBraceCount++;
			} else if (type == Parser3TokenTypes.RBRACE) {
				lastClosingBrace = current;
				openBraceCount--;

				if (openBraceCount == 0) {
					// Проверяем, есть ли еще ветки (для if)
					PsiElement next = current.getNextSibling();
					boolean hasMoreBranches = false;

					while (next != null && next.getNode() != null &&
							next.getNode().getElementType() == Parser3TokenTypes.WHITE_SPACE) {
						next = next.getNextSibling();
					}

					if (next != null && next.getNode() != null) {
						IElementType nextType = next.getNode().getElementType();
						if (nextType == Parser3TokenTypes.LPAREN ||
								nextType == Parser3TokenTypes.LBRACE) {
							hasMoreBranches = true;
						}
					}

					if (!hasMoreBranches) {
						break;
					}
				}
			}

			current = current.getNextSibling();
		}

		if (firstOpenBrace != null && lastClosingBrace != null) {
			// Проверяем, что открывающая скобка на той же строке или на следующей
			int openBraceLine = document.getLineNumber(firstOpenBrace.getTextRange().getStartOffset());

			// Начинаем сворачивание с ключевого слова (^if, ^while, ^for)
			int startOffset = keywordElement.getTextRange().getStartOffset();
			int endOffset = lastClosingBrace.getTextRange().getEndOffset();

			if (endOffset > startOffset) {
				return new TextRange(startOffset, endOffset);
			}
		}

		return null;
	}

	/**
	 * Находит группу последовательных линейных комментариев
	 */
	@Nullable
	private TextRange findConsecutiveLineComments(PsiElement firstComment, Document document) {
		PsiElement current = firstComment;
		PsiElement lastComment = firstComment;
		int commentCount = 0;

		// Идем вперед, собирая последовательные комментарии
		while (current != null) {
			if (current.getNode() != null &&
					current.getNode().getElementType() == Parser3TokenTypes.LINE_COMMENT) {
				lastComment = current;
				commentCount++;
				current = current.getNextSibling();

				// Пропускаем whitespace между комментариями
				while (current != null &&
						current.getNode() != null &&
						current.getNode().getElementType() == Parser3TokenTypes.WHITE_SPACE) {
					String wsText = current.getText();
					// Если больше одной пустой строки - прерываем группу
					if (wsText.chars().filter(ch -> ch == '\n').count() > 1) {
						break;
					}
					current = current.getNextSibling();
				}
			} else {
				break;
			}
		}

		// Создаем диапазон только если есть несколько комментариев
		if (commentCount >= 2) {
			int startOffset = firstComment.getTextRange().getStartOffset();
			int endOffset = lastComment.getTextRange().getEndOffset();
			return new TextRange(startOffset, endOffset);
		}

		return null;
	}

	/**
	 * Проверяет, занимает ли диапазон больше одной строки
	 */
	private boolean isMultiLine(TextRange range, Document document) {
		int startLine = document.getLineNumber(range.getStartOffset());
		int endLine = document.getLineNumber(range.getEndOffset());
		return endLine > startLine;
	}

	@Nullable
	@Override
	public String getPlaceholderText(@NotNull ASTNode node) {
		IElementType type = node.getElementType();

		if (type == Parser3TokenTypes.LINE_COMMENT) {
			return "#...";
		}
		if (type == Parser3TokenTypes.DEFINE_METHOD ||
				type == Parser3TokenTypes.SPECIAL_METHOD) {
			// Этот метод не должен вызываться для методов,
			// так как placeholder передается в конструктор FoldingDescriptor
			return "{...}";
		}
		if (type == Parser3TokenTypes.REM_LBRACE) {
			return "^rem{...}";
		}
		if (type == Parser3TokenTypes.KW_IF ||
				type == Parser3TokenTypes.KW_WHILE ||
				type == Parser3TokenTypes.KW_FOR ||
				type == Parser3TokenTypes.KW_TRY) {
			return "{...}";
		}
		return "...";
	}

	@Override
	public boolean isCollapsedByDefault(@NotNull ASTNode node) {
		// По умолчанию ничего не сворачиваем
		return false;
	}
}