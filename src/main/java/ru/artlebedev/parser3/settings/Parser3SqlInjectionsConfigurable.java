package ru.artlebedev.parser3.settings;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3IndexMaintenance;
import ru.artlebedev.parser3.lexer.Parser3LexerCore;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class Parser3SqlInjectionsConfigurable implements SearchableConfigurable {

	private JTextArea textArea;

	@Override
	public @NotNull String getId() {
		return "ru.artlebedev.parser3.sqlInjections";
	}

	@Override
	public String getDisplayName() {
		return "SQL injections";
	}

	@Override
	public @Nullable JComponent createComponent() {
		JPanel panel = new JPanel(new BorderLayout(0, 8));

		textArea = new JTextArea(10, 50);
		panel.add(new JScrollPane(textArea), BorderLayout.CENTER);

		JLabel hint = new JLabel(
				"<html>Методы, внутри которых работает SQL-подсветка.<br>" +
						"Указывайте только начало (например <code>^oSql.hash</code>).<br>" +
						"Скобки <code>{}</code> добавлять не нужно — они считаются автоматически.<br>По одному на строку.</html>"
		);
		panel.add(hint, BorderLayout.SOUTH);

		return panel;
	}

	@Override
	public boolean isModified() {
		Parser3SqlInjectionsService service = ApplicationManager.getApplication().getService(Parser3SqlInjectionsService.class);
		List<String> stored = service.getNormalizedPrefixesCopy();
		String current = String.join("\n", stored);
		return !current.equals(textArea.getText().trim());
	}

	@Override
	public void apply() {
		Parser3SqlInjectionsService service = ApplicationManager.getApplication().getService(Parser3SqlInjectionsService.class);
		List<String> prefixes = new ArrayList<>();

		for (String line : textArea.getText().split("\n")) {
			String value = line.trim();
			if (!value.isEmpty()) {
				prefixes.add(value);
			}
		}

		service.setPrefixes(prefixes);
		Parser3LexerCore.setUserSqlInjectionPrefixesSupplier(Parser3SqlInjectionSupport::getConfiguredPrefixes);

		for (Project project : ProjectManager.getInstance().getOpenProjects()) {
			DaemonCodeAnalyzer.getInstance(project).restart();
			P3IndexMaintenance.requestParser3FileReindexAsync(project, "sql injections settings", () ->
					DaemonCodeAnalyzer.getInstance(project).restart());
		}
	}

	@Override
	public void reset() {
		Parser3SqlInjectionsService service = ApplicationManager.getApplication().getService(Parser3SqlInjectionsService.class);
		textArea.setText(String.join("\n", service.getNormalizedPrefixesCopy()));
	}
}
