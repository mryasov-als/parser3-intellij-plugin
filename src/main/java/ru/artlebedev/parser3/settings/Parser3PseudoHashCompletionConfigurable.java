package ru.artlebedev.parser3.settings;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.completion.P3PseudoHashCompletionRegistry;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public final class Parser3PseudoHashCompletionConfigurable implements SearchableConfigurable {

	private static final String DOCUMENTATION_URL = "https://github.com/mryasov-als/parser3-intellij-plugin/blob/master/docs/contextual-argument-completion.md";

	private JTextArea textArea;

	@Override
	public @NotNull String getId() {
		return "ru.artlebedev.parser3.pseudoHashCompletion";
	}

	@Override
	public String getDisplayName() {
		return "Contextual argument completion";
	}

	@Override
	public @Nullable JComponent createComponent() {
		JPanel panel = new JPanel(new BorderLayout(0, 8));

		textArea = new JTextArea(18, 60);
		panel.add(new JScrollPane(textArea), BorderLayout.CENTER);

		JLabel hint = new JLabel(
				"<html>Пользовательский JSON для расширения completion аргументов и значений.<br>" +
						"Формат такой же, как у встроенного <code>pseudo-hash-completion.json</code>.<br>" +
						"Конфиг дополняет встроенный, а не заменяет его.<br>" +
						"Корень должен быть JSON-массивом.<br>" +
						"Документация: <a href=\"" + DOCUMENTATION_URL + "\">docs/contextual-argument-completion.md</a></html>"
		);
		hint.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		hint.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				BrowserUtil.browse(DOCUMENTATION_URL);
			}
		});
		panel.add(hint, BorderLayout.SOUTH);

		return panel;
	}

	@Override
	public boolean isModified() {
		Parser3PseudoHashCompletionService service = Parser3PseudoHashCompletionService.getInstance();
		return !Objects.equals(service.getConfigJson(), normalize(textArea.getText()));
	}

	@Override
	public void apply() throws ConfigurationException {
		String json = normalize(textArea.getText());
		String validationError = P3PseudoHashCompletionRegistry.validateUserConfig(json);
		if (validationError != null) {
			throw new ConfigurationException(validationError, "Contextual argument completion");
		}

		Parser3PseudoHashCompletionService service = Parser3PseudoHashCompletionService.getInstance();
		service.setConfigJson(json);
		P3PseudoHashCompletionRegistry.clearCaches();
		for (com.intellij.openapi.project.Project project : ProjectManager.getInstance().getOpenProjects()) {
			DaemonCodeAnalyzer.getInstance(project).restart();
		}
	}

	@Override
	public void reset() {
		Parser3PseudoHashCompletionService service = Parser3PseudoHashCompletionService.getInstance();
		textArea.setText(service.getConfigJson() != null ? service.getConfigJson() : "");
	}

	@Override
	public void disposeUIResources() {
		textArea = null;
	}

	private static @Nullable String normalize(@Nullable String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
