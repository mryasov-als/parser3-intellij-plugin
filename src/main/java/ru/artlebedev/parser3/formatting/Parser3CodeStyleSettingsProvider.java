package ru.artlebedev.parser3.formatting;

import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.application.options.SmartIndentOptionsEditor;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class Parser3CodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

	static {
		Language lang = Language.findLanguageByID("Parser3");
	}

	@Override
	public @NotNull Language getLanguage() {
//		return Objects.requireNonNull(Language.findLanguageByID("Parser3"));
		return ru.artlebedev.parser3.Parser3Language.INSTANCE;
	}

	@Override
	public String getConfigurableDisplayName() {
		return "Parser 3";
	}

	@Override
	public IndentOptionsEditor getIndentOptionsEditor() {
		return new SmartIndentOptionsEditor();
	}

	@Override
	public void customizeDefaults(@NotNull CommonCodeStyleSettings common,
								  @NotNull CommonCodeStyleSettings.IndentOptions indent) {
		indent.USE_TAB_CHARACTER = true;
		indent.TAB_SIZE = 4;
		indent.INDENT_SIZE = 4;
		indent.CONTINUATION_INDENT_SIZE = 4;
	}

	@Override
	public @NotNull CommonCodeStyleSettings getDefaultCommonSettings() {
		CommonCodeStyleSettings s = new CommonCodeStyleSettings(getLanguage());
		// Line comments must always start at column 0 (like in Java)
		s.LINE_COMMENT_AT_FIRST_COLUMN = true;
		CommonCodeStyleSettings.IndentOptions indent = s.initIndentOptions();
		indent.USE_TAB_CHARACTER = true;
		indent.TAB_SIZE = 4;
		indent.INDENT_SIZE = 4;
		indent.CONTINUATION_INDENT_SIZE = 4;
		return s;
	}

	@Override
	public String getCodeSample(@NotNull SettingsType type) {
		return "# sample Parser3\n^data.foreach[key;userData]{\n\t^if(!$userData.sended){\n\t\t^mail:send[\n\t\t\t$.from[]\n\t\t\t$.to[]\n\t\t\t$.subject[]\n\t\t\t$.html[\n\t\t\t\t$.value{^html.base64[]}\n\t\t\t\t$.content-transfer-encoding[base64]\n\t\t\t]\n\t\t]\n\t}\n}";
	}
}