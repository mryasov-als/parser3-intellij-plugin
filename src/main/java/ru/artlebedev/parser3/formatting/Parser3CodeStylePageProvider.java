package ru.artlebedev.parser3.formatting;

import com.intellij.lang.Language;
import com.intellij.openapi.options.Configurable;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3Language;

public final class Parser3CodeStylePageProvider extends CodeStyleSettingsProvider {
	@Override
	public String getConfigurableDisplayName() {
		return "Parser 3";
	}

	@Override
	public @Nullable Language getLanguage() {
		return Parser3Language.INSTANCE;
	}

	@Override
	public @NotNull String getConfigurableId() {
		return "language.Parser3";
	}



	@Override
	public @NotNull Configurable createSettingsPage(@NotNull CodeStyleSettings settings,
													@NotNull CodeStyleSettings originalSettings) {
		return new com.intellij.application.options.CodeStyleAbstractConfigurable(settings, originalSettings, "Parser 3") {
			@Override
			protected com.intellij.application.options.CodeStyleAbstractPanel createPanel(CodeStyleSettings settings) {
				return new com.intellij.application.options.TabbedLanguageCodeStylePanel(
						Parser3Language.INSTANCE,
						getCurrentSettings(),
						settings
				) {
					@Override
					protected void initTabs(CodeStyleSettings settings) {
						// Минимальный обязательный таб, чтобы язык появился в списке
						addIndentOptionsTab(settings);
					}
				};
			}
		};
	}
}