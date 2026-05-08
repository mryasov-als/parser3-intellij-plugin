package ru.artlebedev.parser3.lexer;

import junit.framework.TestCase;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3TokenTypes;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Parser3LexerSafetyTest extends TestCase {

	public void testDiscontinuousSliceFallsBackToSingleToken() throws Exception {
		Parser3Lexer lexer = new Parser3Lexer();
		setField(lexer, "buffer", "abcdef");

		Class<?> tokenInfoClass = Class.forName("ru.artlebedev.parser3.lexer.Parser3Lexer$TokenInfo");
		Object source = Array.newInstance(tokenInfoClass, 2);
		Array.set(source, 0, newToken(tokenInfoClass, 0, 2));
		Array.set(source, 1, newToken(tokenInfoClass, 4, 6));

		Method sliceMethod = Parser3Lexer.class.getDeclaredMethod("sliceTokensForRange", source.getClass(), int.class, int.class);
		sliceMethod.setAccessible(true);

		Object result = sliceMethod.invoke(lexer, source, 0, 6);
		assertEquals("Discontinuous range should collapse to one fallback token", 1, Array.getLength(result));

		Object token = Array.get(result, 0);
		assertEquals(0, readInt(token, "start"));
		assertEquals(6, readInt(token, "end"));
		assertSame(Parser3TokenTypes.TEMPLATE_DATA, readType(token, "type"));
	}

	@NotNull
	private static Object newToken(Class<?> tokenInfoClass, int start, int end) throws Exception {
		Constructor<?> constructor = tokenInfoClass.getDeclaredConstructor(int.class, int.class, com.intellij.psi.tree.IElementType.class, String.class);
		constructor.setAccessible(true);
		return constructor.newInstance(start, end, Parser3TokenTypes.HTML_DATA, null);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static int readInt(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(target);
	}

	private static Object readType(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}
