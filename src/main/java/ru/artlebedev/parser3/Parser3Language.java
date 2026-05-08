package ru.artlebedev.parser3;

import com.intellij.lang.Language;
import com.intellij.psi.templateLanguages.TemplateLanguage;

public final class Parser3Language extends Language implements TemplateLanguage {
    public static final Parser3Language INSTANCE = new Parser3Language();

    private Parser3Language() {
        super("Parser3");
    }
}