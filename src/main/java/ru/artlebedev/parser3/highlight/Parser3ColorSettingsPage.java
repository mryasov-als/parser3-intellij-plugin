package ru.artlebedev.parser3.highlight;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Map;

public final class Parser3ColorSettingsPage implements ColorSettingsPage {
	private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[] {
			new AttributesDescriptor("Method (@name...)", Parser3SyntaxHighlighter.DEFINE_METHOD),
			new AttributesDescriptor("Method (^name...)", Parser3SyntaxHighlighter.METHOD),
			new AttributesDescriptor("Variable ($name)", Parser3SyntaxHighlighter.VARIABLE),
			new AttributesDescriptor("Local variable (@method[][local])", Parser3SyntaxHighlighter.LOCAL_VARIABLE),
			new AttributesDescriptor("Line comment", Parser3SyntaxHighlighter.LINE_COMMENT),
			new AttributesDescriptor("Block comment", Parser3SyntaxHighlighter.BLOCK_COMMENT),
			new AttributesDescriptor("Keyword", Parser3SyntaxHighlighter.KEYWORD),
			new AttributesDescriptor("Parentheses", Parser3SyntaxHighlighter.PAREN),
			new AttributesDescriptor("Braces", Parser3SyntaxHighlighter.BRACE),
			new AttributesDescriptor("Brackets", Parser3SyntaxHighlighter.BRACKET),
//			new AttributesDescriptor("Comma", Parser3SyntaxHighlighter.COMMA),
			new AttributesDescriptor("Colon", Parser3SyntaxHighlighter.COLON),
			new AttributesDescriptor("Dot", Parser3SyntaxHighlighter.DOT),
			new AttributesDescriptor("Operator", Parser3SyntaxHighlighter.OP),
			new AttributesDescriptor("Quotes", Parser3SyntaxHighlighter.QUOTE),
			new AttributesDescriptor("Word operator", Parser3SyntaxHighlighter.WORD_OPERATOR),
//			new AttributesDescriptor("Identifier", Parser3SyntaxHighlighter.IDENT),
			new AttributesDescriptor("Constructor (^name::name...)", Parser3SyntaxHighlighter.CONSTRUCTOR),
			new AttributesDescriptor("String literal / $[...]", Parser3SyntaxHighlighter.STRING),
			new AttributesDescriptor("Hex escape (^#HH)", Parser3SyntaxHighlighter.HEX_ESCAPE),
			new AttributesDescriptor("Number literal / $(...)", Parser3SyntaxHighlighter.NUMBER),
			new AttributesDescriptor("Boolean literal", Parser3SyntaxHighlighter.BOOLEAN),
//			new AttributesDescriptor("Template / HTML / text", Parser3SyntaxHighlighter.TEMPLATE_DATA),
//			new AttributesDescriptor("Bad character", Parser3SyntaxHighlighter.BAD_CHAR),
			new AttributesDescriptor("SQL", Parser3SyntaxHighlighter.SQL_BLOCK),
	};

	@Override
	public Icon getIcon() { return null; }

	@Override
	public @NotNull SyntaxHighlighter getHighlighter() {
		return new Parser3SyntaxHighlighter();
	}

	@Override
	public @NotNull String getDemoText() {
		return ""
				+ "# обычный комментарий\n"
				+ "\n"
				+ "^rem{\n"
				+ "\tблочный комментарий\n"
				+ "\tс вложенным кодом\n"
				+ "\t^if($var){\n"
				+ "\t\t$result[^method[]]\n"
				+ "\t}\n"
				+ "}\n"
				+ "\n"
				+ "# глобальные переменные\n"
				+ "$MAIN:context\n"
				+ "$MAIN:title\n"
				+ "\n"
				+ "# обычные переменные\n"
				+ "$var(true)\n"
				+ "$var.x.y\n"
				+ "\n"
				+ "# метод\n"
				+ "@mehod[var1;var2][local_var]\n"
				+ "\n"
				+ "# «важный» метод\n"
				+ "@auto[]\n"
				+ "\n"
				+ "# конструкторы и статические методы\n"
				+ "$hash[^hash::create[\n"
				+ "\t$.a[1]\n"
				+ "\t$.b[2]\n"
				+ "]]\n"
				+ "\n"
				+ "$list[^list::create[]]\n"
				+ "^list.foreach[key;value]{\n"
				+ "\t$result[$key]\n"
				+ "\t^break[]\n"
				+ "}\n"
				+ "\n"
				+ "$file[^curl:load[\n"
				+ "\t$.url[https://test.com]\n"
				+ "\t$.timeout(10)\n"
				+ "]]\n"
				+ "^return[$file]\n"
				+ "\n"
				+ "# выражения + уровни вложенности\n"
				+ "$cond($a eq 'b' && $b ne \"c\" && $c == 15)\n"
				+ "$cond2($a eq 'b' && ^method{$a eq 'x'})\n"
				+ "\n"
				+ "# hex escape\n"
				+ "$tab[^#09]\n"
				+ "$newline[^#0A]\n"
				+ "\n"
				+ "# sql\n"
				+ "$id(^int:sql{select id from users where email = '$email'}[$.default(0)])\n"
				+ "\n"
				+ "# системные операторы\n"
				+ "^if(!$id){\n"
				+ "\t^switch[$current_type]{\n"
				+ "\t\t^case[main]{^return[]}\n"
				+ "\t\t^case[DEFAULT]{^code[]}\n"
				+ "\t}\n"
				+ "}\n";
	}

	@Override
	public AttributesDescriptor @NotNull [] getAttributeDescriptors() { return DESCRIPTORS; }

	@Override
	public ColorDescriptor @NotNull [] getColorDescriptors() { return ColorDescriptor.EMPTY_ARRAY; }

	@Override
	public @NotNull String getDisplayName() { return "Parser 3"; }

	@Override
	public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() { return Map.of(); }
}
