package ru.artlebedev.parser3.utils;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;

/**
 * Общая проверка: является ли токен началом новой parser3-секции вида @... .
 *
 * Для лексера и инжекторов здесь важно считать границей любой @-метод/директиву,
 * а не только DEFINE_METHOD. Иначе special-методы вроде @auto[] продолжают жить
 * в состоянии предыдущего/следующего метода.
 */
public final class P3MethodDeclarationUtils {

	private P3MethodDeclarationUtils() {
	}

	public static boolean isMethodDeclarationToken(@Nullable IElementType type) {
		return type == Parser3TokenTypes.DEFINE_METHOD || type == Parser3TokenTypes.SPECIAL_METHOD;
	}

	public static boolean isMethodDeclarationToken(@Nullable String type) {
		return "DEFINE_METHOD".equals(type) || "SPECIAL_METHOD".equals(type);
	}
}
