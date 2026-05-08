package ru.artlebedev.parser3.highlight;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import java.awt.Color;
import java.awt.Font;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.lexer.Parser3Lexer;
import java.awt.Font;
import com.intellij.openapi.editor.markup.TextAttributes;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

public final class Parser3SyntaxHighlighter extends SyntaxHighlighterBase {
	// Базовые ключи
	public static final TextAttributesKey LINE_COMMENT = createTextAttributesKey("P3_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
	public static final TextAttributesKey BLOCK_COMMENT = createTextAttributesKey("P3_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
	public static final TextAttributesKey REM_BRACKET = BLOCK_COMMENT;
	public static final TextAttributesKey REM_PAREN   = BLOCK_COMMENT;
	public static final TextAttributesKey REM_BRACE   = BLOCK_COMMENT;

	public static final TextAttributesKey KEYWORD = createTextAttributesKey("P3_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
	public static final TextAttributesKey IMPORTANT_KEYWORD =
			createTextAttributesKey(
					"P3_IMPORTANT_KEYWORD",
					new TextAttributes(
							null,
							null,
							null,
							null,
							Font.BOLD
					)
			);

	public static final TextAttributesKey IDENT = createTextAttributesKey("P3_IDENT", DefaultLanguageHighlighterColors.IDENTIFIER);

	public static final TextAttributesKey VARIABLE =
			createTextAttributesKey(
					"P3_VARIABLE",
					new TextAttributes(
							new Color(0x7F97E1),
							null,
							null,
							null,
							Font.PLAIN
					)
			);

	public static final TextAttributesKey IMPORTANT_VARIABLE =
			createTextAttributesKey(
					"P3_IMPORTANT_VARIABLE",
					new TextAttributes(
							null,
							null,
							null,
							null,
							Font.BOLD
					)
			);

	public static final TextAttributesKey LOCAL_VARIABLE = createTextAttributesKey("P3_LOCAL_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);

	public static final TextAttributesKey METHOD =
			createTextAttributesKey(
					"P3_METHOD",
					new TextAttributes(
							new Color(0xFAFDB7), // можно переопределить в схеме
							null,
							null,
							null,
							Font.PLAIN
					)
			);

	public static final TextAttributesKey IMPORTANT_METHOD =
			createTextAttributesKey(
					"P3_IMPORTANT_METHOD",
					new TextAttributes(
							null,
							null,
							null,
							null,
							Font.BOLD
					)
			);

	public static final TextAttributesKey DEFINE_METHOD = createTextAttributesKey("P3_DEFINE_METHOD", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
	public static TextAttributesKey SPECIAL_METHOD = createTextAttributesKey("P3_SPECIAL_METHOD", DEFINE_METHOD);
	public static final TextAttributesKey SPECIAL_METHOD_BOLD =
			createTextAttributesKey(
					"P3_SPECIAL_METHOD_BOLD",
					new TextAttributes(
							null,
							null,
							null,
							null,
							Font.BOLD
					)
			);




	// CONSTRUCTOR — фиолетовый
	public static final TextAttributesKey CONSTRUCTOR =
			createTextAttributesKey(
					"P3_CONSTRUCTOR",
					new TextAttributes(
							new Color(0x8E79D6), // можно переопределить в схеме
							null,
							null,
							null,
							Font.BOLD
					)
			);

	public static final TextAttributesKey STRING = createTextAttributesKey("P3_STRING", DefaultLanguageHighlighterColors.STRING);
	public static final TextAttributesKey HEX_ESCAPE = createTextAttributesKey("P3_HEX_ESCAPE", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE);

	// Путь к файлу в @USE / ^use[] — наследуем от STRING, но это отдельный тип для reference search
	public static final TextAttributesKey USE_PATH = createTextAttributesKey("P3_USE_PATH", DefaultLanguageHighlighterColors.STRING);

	public static final TextAttributesKey NUMBER = createTextAttributesKey("P3_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
	public static final TextAttributesKey BOOLEAN = createTextAttributesKey("P3_BOOLEAN", DefaultLanguageHighlighterColors.KEYWORD);
	public static final TextAttributesKey BAD_CHAR = createTextAttributesKey("P3_BAD_CHAR", HighlighterColors.BAD_CHARACTER);
	public static final TextAttributesKey OP = createTextAttributesKey("P3_OP", DefaultLanguageHighlighterColors.OPERATION_SIGN);
	public static final TextAttributesKey WORD_OPERATOR = createTextAttributesKey("P3_WORD_OPERATOR", DefaultLanguageHighlighterColors.METADATA);
	public static final TextAttributesKey PAREN = createTextAttributesKey("P3_PAREN", DefaultLanguageHighlighterColors.BRACKETS);
	public static final TextAttributesKey BRACE = createTextAttributesKey("P3_BRACE", DefaultLanguageHighlighterColors.BRACES);
	public static final TextAttributesKey BRACKET = createTextAttributesKey("P3_BRACKET", DefaultLanguageHighlighterColors.BRACKETS);
	public static final TextAttributesKey COMMA = createTextAttributesKey("P3_COMMA", DefaultLanguageHighlighterColors.COMMA);
	public static final TextAttributesKey COLON = createTextAttributesKey("P3_COLON", DefaultLanguageHighlighterColors.OPERATION_SIGN);
	public static final TextAttributesKey DOT = createTextAttributesKey("P3_DOT", DefaultLanguageHighlighterColors.DOT);
	public static final TextAttributesKey TEMPLATE_DATA = createTextAttributesKey("P3_TEMPLATE_DATA", DefaultLanguageHighlighterColors.IDENTIFIER);
	public static final TextAttributesKey SQL_BLOCK = createTextAttributesKey("P3_SQL_BLOCK", DefaultLanguageHighlighterColors.IDENTIFIER);

	public static final TextAttributesKey QUOTE = createTextAttributesKey("P3_QUOTE", DefaultLanguageHighlighterColors.BRACES);

	@Override
	public @NotNull Lexer getHighlightingLexer() {
		return new Parser3Lexer();
	}

	@Override
	public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {

		if (tokenType == Parser3TokenTypes.LINE_COMMENT) return pack(LINE_COMMENT);
		if (tokenType == Parser3TokenTypes.BLOCK_COMMENT) return pack(BLOCK_COMMENT);

		if (tokenType == Parser3TokenTypes.REM_LBRACKET || tokenType == Parser3TokenTypes.REM_RBRACKET) return pack(REM_BRACKET);
		if (tokenType == Parser3TokenTypes.REM_LPAREN || tokenType == Parser3TokenTypes.REM_RPAREN) return pack(REM_PAREN);
		if (tokenType == Parser3TokenTypes.REM_LBRACE || tokenType == Parser3TokenTypes.REM_RBRACE) return pack(REM_BRACE);

		if (tokenType == Parser3TokenTypes.STRING) return pack(STRING);
		if (tokenType == Parser3TokenTypes.HEX_ESCAPE) return pack(HEX_ESCAPE);
		if (tokenType == Parser3TokenTypes.USE_PATH) return pack(USE_PATH);
		if (tokenType == Parser3TokenTypes.NUMBER) return pack(NUMBER);
		if (tokenType == Parser3TokenTypes.BOOLEAN) return pack(BOOLEAN);
		if (tokenType == Parser3TokenTypes.VARIABLE || tokenType == Parser3TokenTypes.DOLLAR_VARIABLE) return pack(VARIABLE);
		if (tokenType == Parser3TokenTypes.IMPORTANT_VARIABLE) return pack(VARIABLE, IMPORTANT_VARIABLE);
		if (tokenType == Parser3TokenTypes.LOCAL_VARIABLE) return pack(LOCAL_VARIABLE);
		if (tokenType == Parser3TokenTypes.KW_RETURN) return pack(KEYWORD, IMPORTANT_KEYWORD);
		if (tokenType == Parser3TokenTypes.KEYWORD
				|| tokenType == Parser3TokenTypes.KW_IF
				|| tokenType == Parser3TokenTypes.KW_WHILE
				|| tokenType == Parser3TokenTypes.KW_SWITCH
				|| tokenType == Parser3TokenTypes.KW_BREAK
				|| tokenType == Parser3TokenTypes.KW_CONTINUE
				|| tokenType == Parser3TokenTypes.KW_EVAL
				|| tokenType == Parser3TokenTypes.KW_CASE
				|| tokenType == Parser3TokenTypes.KW_FOR
				|| tokenType == Parser3TokenTypes.KW_CACHE
				|| tokenType == Parser3TokenTypes.KW_CONNECT
				|| tokenType == Parser3TokenTypes.KW_PROCESS
				|| tokenType == Parser3TokenTypes.KW_SLEEP
				|| tokenType == Parser3TokenTypes.KW_USE
				|| tokenType == Parser3TokenTypes.KW_TRY
				|| tokenType == Parser3TokenTypes.KW_THROW
		) return pack(KEYWORD);
		if (
				tokenType == Parser3TokenTypes.DOUBLE_QUOTE
						|| tokenType == Parser3TokenTypes.RDOUBLE_QUOTE
						|| tokenType == Parser3TokenTypes.LDOUBLE_QUOTE
						|| tokenType == Parser3TokenTypes.SINGLE_QUOTE
						|| tokenType == Parser3TokenTypes.LSINGLE_QUOTE
						|| tokenType == Parser3TokenTypes.RSINGLE_QUOTE
		) return pack(QUOTE);
		if (tokenType == Parser3TokenTypes.METHOD) return pack(METHOD);
		if (tokenType == Parser3TokenTypes.IMPORTANT_METHOD) return pack(METHOD, IMPORTANT_METHOD);
		if (tokenType == Parser3TokenTypes.SPECIAL_METHOD) return pack(SPECIAL_METHOD, SPECIAL_METHOD_BOLD);
		if (tokenType == Parser3TokenTypes.DEFINE_METHOD) return pack(DEFINE_METHOD);
		if (tokenType == Parser3TokenTypes.CONSTRUCTOR) return pack(CONSTRUCTOR);
		if (tokenType == Parser3TokenTypes.LPAREN || tokenType == Parser3TokenTypes.RPAREN) return pack(PAREN);
		if (tokenType == Parser3TokenTypes.LBRACE || tokenType == Parser3TokenTypes.RBRACE) return pack(BRACE);
		if (tokenType == Parser3TokenTypes.LBRACKET || tokenType == Parser3TokenTypes.RBRACKET) return pack(BRACKET);
		if (tokenType == Parser3TokenTypes.COMMA) return pack(COMMA);
		if (tokenType == Parser3TokenTypes.COLON) return pack(COLON);
		if (tokenType == Parser3TokenTypes.DOT) return pack(DOT);
		if (tokenType == Parser3TokenTypes.OP) return pack(OP);
		if (tokenType == Parser3TokenTypes.WORD_OPERATOR) return pack(WORD_OPERATOR);
		if (tokenType == Parser3TokenTypes.BAD_CHAR) return pack(BAD_CHAR);
		if (tokenType == Parser3TokenTypes.TEMPLATE_DATA || tokenType == Parser3TokenTypes.OUTER || tokenType == Parser3TokenTypes.HTML_DATA || tokenType == Parser3TokenTypes.CSS_DATA || tokenType == Parser3TokenTypes.JS_DATA) return pack(TEMPLATE_DATA);
		if (tokenType == Parser3TokenTypes.SQL_BLOCK) return pack(SQL_BLOCK);
		if (tokenType == Parser3TokenTypes.IDENT) return pack(IDENT);
		return TextAttributesKey.EMPTY_ARRAY;
	}
}
