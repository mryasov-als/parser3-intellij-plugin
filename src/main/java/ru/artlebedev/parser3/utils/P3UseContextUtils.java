package ru.artlebedev.parser3.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;

/**
 * Единая утилита для определения контекста ^use[] и @USE.
 *
 * ВАЖНО: Это ЕДИНСТВЕННЫЙ источник истины для определения use-контекста.
 * Все остальные классы должны использовать эти методы вместо дублирования логики.
 */
public final class P3UseContextUtils {

	private static final Logger LOG = Logger.getInstance(P3UseContextUtils.class);

	private P3UseContextUtils() {
	}

	/**
	 * Проверяет находится ли PSI элемент внутри ^use[] или @USE контекста.
	 * Работает для STRING токенов, представляющих пути к файлам.
	 *
	 * @param element PSI элемент для проверки (обычно STRING токен)
	 * @return true если элемент в use-контексте
	 */
	public static boolean isInUseContext(@NotNull PsiElement element) {
		return getUseContextType(element) != null;
	}

	/**
	 * Проверяет находится ли PSI элемент внутри ^use[] или @USE контекста.
	 * Версия с fullText для более надёжной проверки @USE.
	 *
	 * @param element PSI элемент для проверки (обычно STRING токен)
	 * @param fullText полный текст файла для text-based проверки @USE
	 * @return true если элемент в use-контексте
	 */
	public static boolean isInUseContext(@NotNull PsiElement element, @NotNull String fullText) {
		return getUseContextType(element, fullText) != null;
	}

	/**
	 * Определяет тип use-контекста для PSI элемента.
	 *
	 * @param element PSI элемент для проверки (обычно STRING токен)
	 * @return тип директивы (USE_METHOD для ^use[], USE_DIRECTIVE для @USE), или null если не в контексте
	 */
	public static @Nullable P3UsePathUtils.UseDirectiveType getUseContextType(@NotNull PsiElement element) {
		// Сначала проверяем ^use[] — ищем KW_USE токен слева
		if (isAfterKwUse(element)) {
			return P3UsePathUtils.UseDirectiveType.USE_METHOD;
		}

		// Потом проверяем @USE директиву
		if (isAfterAtUseDirective(element)) {
			return P3UsePathUtils.UseDirectiveType.USE_DIRECTIVE;
		}

		return null;
	}

	/**
	 * Определяет тип use-контекста для PSI элемента.
	 * Версия с fullText для более надёжной проверки @USE через текст.
	 *
	 * @param element PSI элемент для проверки (обычно STRING токен)
	 * @param fullText полный текст файла для text-based проверки @USE
	 * @return тип директивы (USE_METHOD для ^use[], USE_DIRECTIVE для @USE), или null если не в контексте
	 */
	public static @Nullable P3UsePathUtils.UseDirectiveType getUseContextType(@NotNull PsiElement element, @NotNull String fullText) {
		LOG.info("[P3Context] getUseContextType: element='" + element.getText() + "', type=" +
				(element.getNode() != null ? element.getNode().getElementType() : "null"));

		// Сначала проверяем ^use[] — ищем KW_USE токен слева (PSI-based)
		boolean afterKwUse = isAfterKwUse(element);
		LOG.info("[P3Context] isAfterKwUse = " + afterKwUse);

		if (afterKwUse) {
			LOG.info("[P3Context] Returning USE_METHOD");
			return P3UsePathUtils.UseDirectiveType.USE_METHOD;
		}

		// Потом проверяем @USE директиву через текст (более надёжно)
		int offset = element.getTextOffset();
		boolean afterAtUse = isAfterAtUseDirectiveText(fullText, offset);
		LOG.info("[P3Context] isAfterAtUseDirectiveText (offset=" + offset + ") = " + afterAtUse);

		if (afterAtUse) {
			LOG.info("[P3Context] Returning USE_DIRECTIVE");
			return P3UsePathUtils.UseDirectiveType.USE_DIRECTIVE;
		}

		LOG.info("[P3Context] Returning null (not in use context)");
		return null;
	}

	/**
	 * Проверяет находится ли элемент внутри ^use[...] по наличию KW_USE токена.
	 * Обходит siblings влево пока не найдёт KW_USE или не достигнет границы.
	 */
	private static boolean isAfterKwUse(@NotNull PsiElement element) {
		LOG.info("[P3Context] isAfterKwUse: checking element='" + element.getText() + "'");
		PsiElement prev = element.getPrevSibling();

		int siblingCount = 0;
		while (prev != null) {
			siblingCount++;
			if (prev.getNode() != null) {
				IElementType type = prev.getNode().getElementType();
				LOG.info("[P3Context]   sibling #" + siblingCount + ": type=" + type + ", text='" + prev.getText() + "'");

				// Нашли ^use
				if (type == Parser3TokenTypes.KW_USE) {
					LOG.info("[P3Context]   Found KW_USE!");
					return true;
				}

				// Границы — прекращаем поиск
				if (type == Parser3TokenTypes.RBRACKET ||
						type == Parser3TokenTypes.METHOD ||
						type == Parser3TokenTypes.DEFINE_METHOD ||
						type == Parser3TokenTypes.SPECIAL_METHOD) {
					LOG.info("[P3Context]   Hit boundary: " + type);
					break;
				}
			}
			prev = prev.getPrevSibling();
		}

		// Если исчерпали siblings, пробуем siblings родителя
		PsiElement parent = element.getParent();
		if (parent != null && !(parent instanceof PsiFile)) {
			LOG.info("[P3Context]   No KW_USE in siblings, checking parent: " + parent.getClass().getSimpleName());
			return isAfterKwUse(parent);
		}

		LOG.info("[P3Context]   No KW_USE found, returning false");
		return false;
	}

	/**
	 * Проверяет находится ли элемент после директивы @USE.
	 * Использует токен-based подход — ищет SPECIAL_METHOD с текстом "@USE".
	 */
	private static boolean isAfterAtUseDirective(@NotNull PsiElement element) {
		// Обходим siblings назад и siblings родителя
		PsiElement current = element.getPrevSibling();

		while (current != null) {
			if (current.getNode() != null) {
				IElementType type = current.getNode().getElementType();

				// Нашли директиву @USE
				if (type == Parser3TokenTypes.SPECIAL_METHOD) {
					String text = current.getText();
					if ("@USE".equals(text)) {
						return true;
					}
					// Другая директива (@CLASS, @BASE и т.д.) — прекращаем
					return false;
				}

				// Нашли определение метода (@methodName) — прекращаем
				if (type == Parser3TokenTypes.DEFINE_METHOD) {
					return false;
				}

				// Нашли вызов метода (^something) — прекращаем
				if (type == Parser3TokenTypes.METHOD ||
						type == Parser3TokenTypes.KW_USE ||
						type == Parser3TokenTypes.CONSTRUCTOR ||
						type == Parser3TokenTypes.IMPORTANT_METHOD) {
					return false;
				}
			}
			current = current.getPrevSibling();
		}

		// Если исчерпали siblings, пробуем siblings родителя
		PsiElement parent = element.getParent();
		if (parent != null && !(parent instanceof PsiFile)) {
			return isAfterAtUseDirective(parent);
		}

		return false;
	}

	/**
	 * Проверяет находится ли текстовая позиция внутри @USE контекста.
	 * Текстовая версия для использования в редакторах где PSI может быть недоступен.
	 *
	 * @param text текст документа
	 * @param offset позиция для проверки
	 * @return true если позиция после @USE и до следующей директивы/метода
	 */
	public static boolean isInUseContext(@NotNull CharSequence text, int offset) {
		// Проверяем контекст ^use[
		if (isInsideUseCall(text, offset)) {
			return true;
		}

		// Проверяем контекст @USE
		return isAfterAtUseDirectiveText(text, offset);
	}

	/**
	 * Проверяет находится ли offset внутри вызова ^use[...].
	 */
	private static boolean isInsideUseCall(@NotNull CharSequence text, int offset) {
		// Ищем назад ^use[ без закрывающей ]
		int searchStart = Math.max(0, offset - 200);

		for (int i = offset - 1; i >= searchStart; i--) {
			char c = text.charAt(i);

			// Нашли закрывающую скобку — не внутри use[]
			if (c == ']') {
				return false;
			}

			// Проверяем ^use[
			if (c == '[' && i >= 4) {
				String before = text.subSequence(Math.max(0, i - 10), i).toString();
				if (before.endsWith("^use") || before.matches(".*\\^use\\s*$")) {
					return true;
				}
			}

			// Нашли другой вызов метода — прекращаем
			if (c == '^' && i + 1 < offset) {
				char next = text.charAt(i + 1);
				if (Character.isLetter(next) || next == '_') {
					return false;
				}
			}
		}

		return false;
	}

	/**
	 * Проверяет находится ли offset после директивы @USE (текстовый метод).
	 */
	private static boolean isAfterAtUseDirectiveText(@NotNull CharSequence text, int offset) {
		int searchStart = Math.max(0, offset - 500);
		String before = text.subSequence(searchStart, offset).toString();

		int usePos = before.lastIndexOf("@USE");
		if (usePos == -1) {
			return false;
		}

		// Текст между @USE и текущей позицией
		String between = before.substring(usePos + 4);

		// Не должно содержать другие директивы или вызовы методов
		// Но нужно быть осторожным: ^use[ НЕ должен ломать контекст @USE
		// потому что мы проверяем @USE, а не ^use[]

		// Проверяем другие @ директивы
		if (between.contains("@")) {
			return false;
		}

		// Проверяем определения методов или вызовы (но не сам ^use в середине)
		// Нужно проверить есть ли паттерн ^something[ который НЕ является ^use[
		int caretPos = between.indexOf('^');
		while (caretPos >= 0) {
			// Проверяем что идёт после ^
			int nameStart = caretPos + 1;
			int nameEnd = nameStart;
			while (nameEnd < between.length()) {
				char ch = between.charAt(nameEnd);
				if (!Character.isLetterOrDigit(ch) && ch != '_') {
					break;
				}
				nameEnd++;
			}

			if (nameEnd > nameStart) {
				String methodName = between.substring(nameStart, nameEnd);
				// Если это не "use", то это другой метод — мы не в контексте @USE
				if (!"use".equals(methodName)) {
					return false;
				}
			}

			caretPos = between.indexOf('^', caretPos + 1);
		}

		return true;
	}
}