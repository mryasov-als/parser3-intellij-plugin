package ru.artlebedev.parser3;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;


/**
 * Набор токенов для лексера Parser3 (IDEA 2025.2.4, SDK 252).
 * Язык для IElementType не указываем (null), чтобы не тащить зависимости здесь.
 */
public final class Parser3TokenTypes {

	private static final Map<String, IElementType> TYPES = new HashMap<>();


	//	private static IElementType t(@NonNls String debugName) { return new IElementType(debugName, (com.intellij.lang.Language) null); }
	private static IElementType t(String name) {
		IElementType type = new IElementType(name, Parser3Language.INSTANCE);
		TYPES.put(name, type);
		return type;
	}

	public static IElementType byName(String name) {
		IElementType type = TYPES.get(name);
		if (type != null) {
			return type;
		}
		// значение по умолчанию
		return TEMPLATE_DATA;
	}

	// структура/внешний текст
	public static final IElementType TEMPLATE_DATA = t("TEMPLATE_DATA");
	public static final IElementType HTML_DATA = t("HTML_DATA");  // HTML контент для инжекции
	public static final IElementType CSS_DATA = t("CSS_DATA");    // CSS контент внутри <style>
	public static final IElementType JS_DATA = t("JS_DATA");      // JS контент внутри <script>
	public static final IElementType WHITE_SPACE = t("WHITE_SPACE");
	public static final IElementType OUTER = t("OUTER");
	public static final IElementType BAD_CHAR = t("BAD_CHAR");
	public static final IElementType SQL_BLOCK = t("SQL_BLOCK");

	// комментарии
	public static final IElementType LINE_COMMENT = t("LINE_COMMENT");
	public static final IElementType BLOCK_COMMENT = t("BLOCK_COMMENT");
	// Квадратные
	public static final IElementType REM_LBRACKET = t("REM_LBRACKET");
	public static final IElementType REM_RBRACKET = t("REM_RBRACKET");

	// Круглые
	public static final IElementType REM_LPAREN   = t("REM_LPAREN");
	public static final IElementType REM_RPAREN   = t("REM_RPAREN");

	// Фигурные
	public static final IElementType REM_LBRACE   = t("REM_LBRACE");
	public static final IElementType REM_RBRACE   = t("REM_RBRACE");



	// литералы и идентификаторы
	public static final IElementType STRING = t("STRING");
	public static final IElementType HEX_ESCAPE = t("HEX_ESCAPE");
	public static final IElementType USE_PATH = t("USE_PATH");  // путь к файлу в @USE / ^use[]
	public static final IElementType NUMBER = t("NUMBER");
	public static final IElementType BOOLEAN = t("BOOLEAN");
	public static final IElementType IDENT = t("IDENT");
	public static final IElementType VARIABLE = t("VARIABLE");
	public static final IElementType DOLLAR_VARIABLE = t("DOLLAR_VARIABLE");
	public static final IElementType IMPORTANT_VARIABLE = t("IMPORTANT_VARIABLE"); // $result, $MAIN:, $self.
	public static final IElementType LOCAL_VARIABLE = t("LOCAL_VARIABLE");
	public static final IElementType KEYWORD = t("KEYWORD");

	// новые типы под отдельную подсветку
	public static final IElementType METHOD = t("METHOD");           // ^print_text, ^data.var.inc
	public static final IElementType DEFINE_METHOD = t("DEFINE_METHOD");           // @print_text
	public static final IElementType CONSTRUCTOR = t("CONSTRUCTOR"); // ^hash::create, ^table:create
	public static final IElementType SPECIAL_METHOD = t("SPECIAL_METHOD"); // @auto[], @USE
	public static final IElementType IMPORTANT_METHOD = t("IMPORTANT_METHOD"); // ^MAIN:, ^BASE: ^self, ^result

	// скобки и знаки
	public static final IElementType LPAREN = t("LPAREN");
	public static final IElementType RPAREN = t("RPAREN");
	public static final IElementType LBRACE = t("LBRACE");
	public static final IElementType RBRACE = t("RBRACE");
	public static final IElementType LBRACKET = t("LBRACKET");
	public static final IElementType RBRACKET = t("RBRACKET");
	public static final IElementType SEMICOLON = t("SEMICOLON");
	public static final IElementType COMMA = t("COMMA");
	public static final IElementType COLON = t("COLON");
	public static final IElementType DOT = t("DOT");
	public static final IElementType OP = t("OP");
	public static final IElementType SINGLE_QUOTE = t("SINGLE_QUOTE");
	public static final IElementType LSINGLE_QUOTE = t("LSINGLE_QUOTE");
	public static final IElementType RSINGLE_QUOTE = t("RSINGLE_QUOTE");
	public static final IElementType DOUBLE_QUOTE = t("DOUBLE_QUOTE");
	public static final IElementType LDOUBLE_QUOTE = t("LDOUBLE_QUOTE");
	public static final IElementType RDOUBLE_QUOTE = t("RDOUBLE_QUOTE");
	public static final IElementType WORD_OPERATOR = t("WORD_OPERATOR");

	public static final IElementType KW_IF       = t("KW_IF");
	public static final IElementType KW_WHILE    = t("KW_WHILE");
	public static final IElementType KW_SWITCH   = t("KW_SWITCH");
	public static final IElementType KW_BREAK    = t("KW_BREAK");
	public static final IElementType KW_CONTINUE = t("KW_CONTINUE");

	public static final IElementType KW_EVAL = t("KW_EVAL");
	public static final IElementType KW_CASE = t("KW_CASE");
	public static final IElementType KW_FOR = t("KW_FOR");
	public static final IElementType KW_CACHE = t("KW_CACHE");
	public static final IElementType KW_CONNECT = t("KW_CONNECT");
	public static final IElementType KW_PROCESS = t("KW_PROCESS");
	public static final IElementType KW_RETURN = t("KW_RETURN");
	public static final IElementType KW_SLEEP = t("KW_SLEEP");
	public static final IElementType KW_USE = t("KW_USE");
	public static final IElementType KW_TRY = t("KW_TRY");
	public static final IElementType KW_THROW = t("KW_THROW");


	private Parser3TokenTypes() {}
}
