package ru.artlebedev.parser3.injector;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.psi.Parser3CssBlock;
import ru.artlebedev.parser3.psi.Parser3HtmlBlock;
import ru.artlebedev.parser3.psi.Parser3JsBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * Единственный источник истины для сбора частей инжекции из PSI-дерева.
 *
 * Используется во ВСЕХ инжекторах: HTML, CSS, JS, SQL.
 *
 * Логика очистки Parser3 конструкций:
 * - Токены целевого языка сохраняются как есть
 * - WHITE_SPACE сохраняется как есть
 * - Parser3 конструкции заменяются на ОДИН пробел (группа подряд идущих → один пробел)
 *
 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
 * ВАЖНО: Эта функция работает на уровне PSI (после парсинга).
 * Аналогичная логика для ТОКЕНОВ находится в Parser3LexerCore.restoreSqlBlockType().
 * При изменении правил определения Parser3-конструкций нужно менять ОБЕ функции!
 *
 * Parser3-конструкции (вырезаются из целевого языка):
 * - Всё внутри круглых скобок — выражения ^if(...), ^eval(...)
 * - Всё внутри квадратных скобок — параметры ^method[...]
 * - Всё внутри фигурных скобок — тела ^if(...){...}
 * - $переменные, ^методы, @определения
 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
 */
public final class InjectorUtils {

	private InjectorUtils() {
		// Утилитный класс
	}

	/**
	 * Часть инжекции — один хост-элемент с префиксом.
	 */
	public static final class InjectionPart<T extends PsiLanguageInjectionHost> {
		public final T host;
		public final String prefix;
		public final String suffix;

		public InjectionPart(@NotNull T host, @NotNull String prefix, @NotNull String suffix) {
			this.host = host;
			this.prefix = prefix;
			this.suffix = suffix;
		}
	}

	// ==================== ОСНОВНАЯ ФУНКЦИЯ ОЧИСТКИ ====================

	/**
	 * Собирает все части инжекции между двумя элементами-границами.
	 *
	 * Это ЕДИНСТВЕННАЯ функция очистки Parser3 конструкций!
	 * Все инжекторы должны использовать её.
	 *
	 * @param hostClass класс хост-элементов
	 * @param startBoundary начальная граница (не включается)
	 * @param endBoundary конечная граница (не включается), может быть null
	 * @return список частей инжекции
	 */
	@NotNull
	public static <T extends PsiLanguageInjectionHost> List<InjectionPart<T>> collectParts(
			@NotNull Class<T> hostClass,
			@NotNull PsiElement startBoundary,
			@Nullable PsiElement endBoundary
	) {
		return collectPartsInternal(hostClass, startBoundary, endBoundary, -1, -1, false);
	}

	/**
	 * Собирает части инжекции для HTML — с фильтрацией по offset и обработкой style/script.
	 *
	 * @param file файл
	 * @param startOffset начальный offset (включительно)
	 * @param endOffset конечный offset (исключительно)
	 * @return список частей инжекции
	 */
	@NotNull
	public static List<InjectionPart<Parser3HtmlBlock>> collectPartsForHtml(
			@NotNull PsiFile file,
			int startOffset,
			int endOffset
	) {
		return collectPartsInternal(Parser3HtmlBlock.class, file, null, startOffset, endOffset, true);
	}

	/**
	 * Внутренняя функция сбора частей — единая логика для всех инжекторов.
	 *
	 * @param hostClass класс хост-элементов
	 * @param start начальный элемент (для обычного режима — startBoundary, для HTML — file)
	 * @param end конечный элемент-граница (может быть null)
	 * @param startOffset фильтр по начальному offset (-1 = не фильтровать)
	 * @param endOffset фильтр по конечному offset (-1 = не фильтровать)
	 * @param handleStyleScript обрабатывать style/script теги (true для HTML)
	 */
	@NotNull
	private static <T extends PsiLanguageInjectionHost> List<InjectionPart<T>> collectPartsInternal(
			@NotNull Class<T> hostClass,
			@NotNull PsiElement start,
			@Nullable PsiElement end,
			int startOffset,
			int endOffset,
			boolean handleStyleScript
	) {
		List<InjectionPart<T>> result = new ArrayList<>();
		StringBuilder pending = new StringBuilder();
		boolean lastWasParser3 = false;

		// Состояние для style/script (только если handleStyleScript=true)
		boolean insideStyle = false;
		boolean insideScript = false;
		PsiElement styleOpenTag = null;
		PsiElement scriptOpenTag = null;

		// Счётчик вложенности круглых скобок Parser3 конструкций
		// Внутри () — условия/выражения ^if(...), внутри них точно нет HTML/CSS
		int parenDepth = 0;
		// Фигурные скобки только у $var{...} полностью вырезаются из целевого языка.
		int variableBraceDepth = 0;
		// Нужен как контекст: внутри [] legacy ведет себя мягче и не схлопывает вложенные $var{...} так же, как снаружи.
		int squareDepth = 0;
		IElementType lastSignificantType = null;

		// Определяем начало итерации
		PsiElement e;
		if (start instanceof PsiFile) {
			e = ((PsiFile) start).getFirstChild();
		} else {
			e = start.getNextSibling();
		}

		while (e != null && e != end) {
			// Фильтрация по offset (если задан)
			if (startOffset >= 0 && endOffset >= 0) {
				int childStart = e.getTextOffset();
				int childEnd = childStart + e.getTextLength();

				if (childEnd <= startOffset) {
					e = e.getNextSibling();
					continue;
				}
				if (childStart >= endOffset) {
					break;
				}
			}

			IElementType type = e.getNode() != null ? e.getNode().getElementType() : null;

			// Обработка style/script (только для HTML)
			if (handleStyleScript && (insideStyle || insideScript)) {
				if (e instanceof Parser3HtmlBlock) {
					Parser3HtmlBlock htmlBlock = (Parser3HtmlBlock) e;
					String text = htmlBlock.getText();
					String lower = text != null ? text.toLowerCase() : "";

					// Закрывающий </style>
					if (insideStyle && lower.startsWith("</style")) {
						String cssContent = buildVirtualTextBetween(styleOpenTag, htmlBlock, Parser3CssBlock.class);
						// Заменяем на пробелы, сохраняя переводы строк
						String spacedContent = cssContent.replaceAll("[^\n\r]", " ");
						pending.append(spacedContent);

						@SuppressWarnings("unchecked")
						T host = (T) htmlBlock;
						result.add(new InjectionPart<>(host, pending.toString(), ""));
						pending.setLength(0);
						insideStyle = false;
						styleOpenTag = null;
						lastWasParser3 = false;
						e = e.getNextSibling();
						continue;
					}

					// Закрывающий </script>
					if (insideScript && lower.startsWith("</script")) {
						List<InjectionPart<Parser3JsBlock>> jsParts = collectParts(Parser3JsBlock.class, scriptOpenTag, htmlBlock);
						pending.append(buildVirtualDocument(jsParts));

						@SuppressWarnings("unchecked")
						T host = (T) htmlBlock;
						result.add(new InjectionPart<>(host, pending.toString(), ""));
						pending.setLength(0);
						insideScript = false;
						scriptOpenTag = null;
						lastWasParser3 = false;
						e = e.getNextSibling();
						continue;
					}
				}
				// Внутри style/script пропускаем
				e = e.getNextSibling();
				continue;
			}

			// Отслеживаем подавляемые Parser3-области, внутри которых не должно оставаться целевого языка.
			if (type == Parser3TokenTypes.LPAREN) {
				parenDepth++;
			} else if (type == Parser3TokenTypes.RPAREN) {
				parenDepth = Math.max(0, parenDepth - 1);
			} else if (type == Parser3TokenTypes.LBRACKET) {
				if (squareDepth > 0 || lastSignificantType == Parser3TokenTypes.METHOD || lastSignificantType == Parser3TokenTypes.VARIABLE) {
					squareDepth++;
				}
			} else if (type == Parser3TokenTypes.RBRACKET) {
				if (squareDepth > 0) {
					squareDepth--;
				}
			} else if (type == Parser3TokenTypes.LBRACE) {
				if (variableBraceDepth > 0 || (squareDepth == 0 && lastSignificantType == Parser3TokenTypes.VARIABLE)) {
					variableBraceDepth++;
				}
			} else if (type == Parser3TokenTypes.RBRACE) {
				if (variableBraceDepth > 0) {
					variableBraceDepth--;
				}
			}

			// Проверяем, находимся ли мы внутри Parser3-области, полностью вырезаемой из целевого языка.
			boolean insideParser3Brackets = parenDepth > 0;
			if (insideParser3Brackets && hostClass.isInstance(e) && ")".equals(e.getText())) {
				parenDepth = Math.max(0, parenDepth - 1);
				lastWasParser3 = true;
				lastSignificantType = type;
				e = e.getNextSibling();
				continue;
			}

			PsiElement collapsedEnd = tryCollapseSimpleMethodBracketCall(e, hostClass);
			if (!insideParser3Brackets && collapsedEnd != null) {
				if (!lastWasParser3) {
					pending.append(" ");
					lastWasParser3 = true;
				}
				lastSignificantType = type;
				e = collapsedEnd.getNextSibling();
				continue;
			}

			collapsedEnd = tryCollapseSimpleVariableBracketAccess(e, hostClass);
			if (!insideParser3Brackets && collapsedEnd != null) {
				if (!lastWasParser3) {
					pending.append(" ");
					lastWasParser3 = true;
				}
				lastSignificantType = type;
				e = collapsedEnd.getNextSibling();
				continue;
			}

			// Хост-элемент целевого языка — добавляем в результат
			// НО только если мы не внутри Parser3 скобок
			if (hostClass.isInstance(e) && !insideParser3Brackets) {
				@SuppressWarnings("unchecked")
				T host = (T) e;

				// Проверка на открывающие теги style/script (только для HTML)
				if (handleStyleScript && e instanceof Parser3HtmlBlock) {
					String text = host.getText();
					String lower = text != null ? text.toLowerCase() : "";

					if (lower.startsWith("<style")) {
						if (containsClosingTagInSameBlock(lower, "</style")) {
							result.add(new InjectionPart<>(host, pending.toString(), ""));
							pending.setLength(0);
							lastWasParser3 = false;
							lastSignificantType = type;
							e = e.getNextSibling();
							continue;
						}
						result.add(new InjectionPart<>(host, pending.toString(), ""));
						pending.setLength(0);
						insideStyle = true;
						styleOpenTag = e;
						lastWasParser3 = false;
						lastSignificantType = type;
						e = e.getNextSibling();
						continue;
					}

					if (lower.startsWith("<script") && !lower.contains("src=")) {
						if (containsClosingTagInSameBlock(lower, "</script")) {
							result.add(new InjectionPart<>(host, pending.toString(), ""));
							pending.setLength(0);
							lastWasParser3 = false;
							lastSignificantType = type;
							e = e.getNextSibling();
							continue;
						}
						result.add(new InjectionPart<>(host, pending.toString(), ""));
						pending.setLength(0);
						insideScript = true;
						scriptOpenTag = e;
						lastWasParser3 = false;
						lastSignificantType = type;
						e = e.getNextSibling();
						continue;
					}
				}

				result.add(new InjectionPart<>(host, pending.toString(), ""));
				pending.setLength(0);
				lastWasParser3 = false;
				lastSignificantType = type;
				e = e.getNextSibling();
				continue;
			}

			String t = e.getText();
			if (t == null || t.isEmpty()) {
				e = e.getNextSibling();
				continue;
			}

			// WHITE_SPACE — сохраняем как есть, НО только если не внутри Parser3 скобок
			if (isWhiteSpace(e, type) && !insideParser3Brackets) {
				if (lastWasParser3 && (squareDepth > 0 || variableBraceDepth > 0) && !containsLineBreak(t)) {
					e = e.getNextSibling();
					continue;
				}
				pending.append(t);
				lastWasParser3 = false;
				e = e.getNextSibling();
				continue;
			}

			if (handleStyleScript && !insideParser3Brackets && type == Parser3TokenTypes.COLON) {
				pending.append(t);
				lastWasParser3 = false;
				lastSignificantType = type;
				e = e.getNextSibling();
				continue;
			}

			// Parser3 конструкции (или WHITE_SPACE внутри скобок) — заменяем группу на ОДИН пробел
			if (!lastWasParser3) {
				pending.append(" ");
				lastWasParser3 = true;
			}

			if (!isWhiteSpace(e, type)) {
				lastSignificantType = type;
			}

			e = e.getNextSibling();
		}

		return result;
	}

	// ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

	/**
	 * Собирает все части инжекции для CSS/JS — от открывающего до закрывающего HTML тега.
	 */
	@NotNull
	public static <T extends PsiLanguageInjectionHost> List<InjectionPart<T>> collectPartsForHtmlTag(
			@NotNull Class<T> hostClass,
			@NotNull T current,
			@NotNull String openTagPrefix,
			@NotNull String closeTagPrefix
	) {
		PsiElement openTag = findHtmlTag(current, openTagPrefix, true);
		if (openTag == null) {
			return new ArrayList<>();
		}

		PsiElement closeTag = findHtmlTag(openTag, closeTagPrefix, false);
		return collectParts(hostClass, openTag, closeTag);
	}

	/**
	 * Ищет HTML тег (HTML_DATA элемент с указанным префиксом).
	 */
	@Nullable
	private static PsiElement findHtmlTag(
			@NotNull PsiElement start,
			@NotNull String tagPrefix,
			boolean searchBackward
	) {
		PsiElement e = start;
		while (e != null) {
			IElementType type = e.getNode() != null ? e.getNode().getElementType() : null;
			if (type == Parser3TokenTypes.HTML_DATA) {
				String text = e.getText();
				if (text != null && text.toLowerCase().startsWith(tagPrefix.toLowerCase())) {
					return e;
				}
			}
			e = searchBackward ? e.getPrevSibling() : e.getNextSibling();
		}
		return null;
	}

	/**
	 * Собирает полный виртуальный документ из частей.
	 */
	@NotNull
	public static <T extends PsiLanguageInjectionHost> String buildVirtualDocument(
			@NotNull List<InjectionPart<T>> parts
	) {
		StringBuilder sb = new StringBuilder();
		for (InjectionPart<T> part : parts) {
			sb.append(part.prefix);
			sb.append(part.host.getText());
			sb.append(part.suffix);
		}
		return sb.toString();
	}

	/**
	 * Собирает виртуальный текст между двумя элементами-границами.
	 */
	@NotNull
	public static <T extends PsiLanguageInjectionHost> String buildVirtualTextBetween(
			@NotNull PsiElement startBoundary,
			@Nullable PsiElement endBoundary,
			@NotNull Class<T> hostClass
	) {
		List<InjectionPart<T>> parts = collectParts(hostClass, startBoundary, endBoundary);
		return buildVirtualDocument(parts);
	}

	/**
	 * Проверяет, является ли элемент пробельным.
	 */
	private static boolean isWhiteSpace(@NotNull PsiElement e, @Nullable IElementType type) {
		return type == Parser3TokenTypes.WHITE_SPACE || e instanceof com.intellij.psi.PsiWhiteSpace;
	}

	private static boolean containsLineBreak(@NotNull String text) {
		return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
	}

	private static boolean containsClosingTagInSameBlock(@NotNull String lowerText, @NotNull String closingTag) {
		int openEnd = lowerText.indexOf('>');
		return openEnd >= 0 && lowerText.indexOf(closingTag, openEnd + 1) >= 0;
	}

	@Nullable
	private static <T extends PsiLanguageInjectionHost> PsiElement tryCollapseSimpleMethodBracketCall(
			@NotNull PsiElement start,
			@NotNull Class<T> hostClass
	) {
		IElementType startType = start.getNode() != null ? start.getNode().getElementType() : null;
		if (startType != Parser3TokenTypes.METHOD) {
			return null;
		}

		PsiElement open = start.getNextSibling();
		if (open == null || open.getNode() == null || open.getNode().getElementType() != Parser3TokenTypes.LBRACKET) {
			return null;
		}

		PsiElement e = open.getNextSibling();
		while (e != null) {
			IElementType type = e.getNode() != null ? e.getNode().getElementType() : null;
			if (type == Parser3TokenTypes.RBRACKET) {
				return e;
			}
			if (hostClass.isInstance(e)) {
				return null;
			}
			if (!isWhiteSpace(e, type) && type != Parser3TokenTypes.STRING) {
				return null;
			}
			e = e.getNextSibling();
		}

		return null;
	}

	@Nullable
	private static <T extends PsiLanguageInjectionHost> PsiElement tryCollapseSimpleVariableBracketAccess(
			@NotNull PsiElement start,
			@NotNull Class<T> hostClass
	) {
		IElementType startType = start.getNode() != null ? start.getNode().getElementType() : null;
		if (startType != Parser3TokenTypes.DOLLAR_VARIABLE) {
			return null;
		}

		PsiElement variable = start.getNextSibling();
		if (variable == null || variable.getNode() == null || variable.getNode().getElementType() != Parser3TokenTypes.VARIABLE) {
			return null;
		}

		PsiElement open = variable.getNextSibling();
		if (open == null || open.getNode() == null || open.getNode().getElementType() != Parser3TokenTypes.LBRACKET) {
			return null;
		}

		PsiElement e = open.getNextSibling();
		while (e != null) {
			IElementType type = e.getNode() != null ? e.getNode().getElementType() : null;
			if (type == Parser3TokenTypes.RBRACKET) {
				return e;
			}
			if (hostClass.isInstance(e)) {
				return null;
			}
			if (!isWhiteSpace(e, type) && type != Parser3TokenTypes.STRING) {
				return null;
			}
			e = e.getNextSibling();
		}

		return null;
	}

}
