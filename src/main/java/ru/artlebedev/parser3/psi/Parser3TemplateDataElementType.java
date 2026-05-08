package ru.artlebedev.parser3.psi;

import com.intellij.lang.html.HTMLLanguage;
import com.intellij.psi.templateLanguages.TemplateDataElementType;
import ru.artlebedev.parser3.Parser3TokenTypes;

public final class Parser3TemplateDataElementType {
	public static final TemplateDataElementType INSTANCE = new TemplateDataElementType(
			"PARSER3_TEMPLATE_DATA",
			HTMLLanguage.INSTANCE,
			Parser3TokenTypes.TEMPLATE_DATA,	// что считать «внутренним» HTML
			Parser3TokenTypes.OUTER				// «обёртка» Parser3 (директивы и т.п.)
	);

	private Parser3TemplateDataElementType() {}
}