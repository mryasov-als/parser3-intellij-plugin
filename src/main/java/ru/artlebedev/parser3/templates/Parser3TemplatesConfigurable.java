package ru.artlebedev.parser3.templates;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Parser3TemplatesConfigurable implements SearchableConfigurable {

	private JPanel mainPanel;
	private DefaultListModel<Parser3UserTemplate> listModel;
	private JBList<Parser3UserTemplate> templatesList;

	private JTextField nameField;
	private JTextField commentField;
	private JTextArea bodyArea;
	private JCheckBox enabledCheckBox;
	private JLabel helpLabel;

	private boolean modified;
	private boolean updatingFromSelection;

	@Override
	public @NotNull String getId() {
		return "ru.artlebedev.parser3.templates";
	}

	@Override
	public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
		return "Parser3 Templates";
	}

	@Override
	public @Nullable JComponent createComponent() {
		if (mainPanel == null) {
			initUi();
		}
		return mainPanel;
	}

	private void initUi() {
		mainPanel = new JPanel(new BorderLayout());

		listModel = new DefaultListModel<>();
		templatesList = new JBList<>(listModel);
		templatesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		templatesList.setCellRenderer(createRenderer());

		ToolbarDecorator decorator = ToolbarDecorator.createDecorator(templatesList)
				.setAddAction(button -> addTemplate())
				.setRemoveAction(button -> removeSelectedTemplate())
				.setMoveUpAction(button -> moveSelectedTemplateUp())
				.setMoveDownAction(button -> moveSelectedTemplateDown());

		JPanel listPanel = decorator.createPanel();

		JPanel editorPanel = createEditorPanel();

		mainPanel.add(listPanel, BorderLayout.WEST);
		mainPanel.add(editorPanel, BorderLayout.CENTER);

		templatesList.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				onSelectionChanged();
			}
		});

		reset();
	}

	private ListCellRenderer<? super Parser3UserTemplate> createRenderer() {
		return new ListCellRenderer<Parser3UserTemplate>() {
			@Override
			public Component getListCellRendererComponent(
					JList<? extends Parser3UserTemplate> list,
					Parser3UserTemplate value,
					int index,
					boolean isSelected,
					boolean cellHasFocus
			) {
				JLabel label = new JLabel();
				String text = (value != null && value.name != null && !value.name.isEmpty())
						? value.name
						: "<unnamed template>";
				label.setText(text);
				if (isSelected) {
					label.setOpaque(true);
					label.setBackground(list.getSelectionBackground());
					label.setForeground(list.getSelectionForeground());
				}
				label.setBorder(new EmptyBorder(2, 4, 2, 4));
				return label;
			}
		};
	}

	private JPanel createEditorPanel() {
		JPanel editorPanel = new JPanel(new BorderLayout());

		JPanel formPanel = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;

		JLabel nameLabel = new JLabel("Name:");
		formPanel.add(nameLabel, c);

		c.gridx = 1;
		c.weightx = 1.0;
		c.fill = GridBagConstraints.HORIZONTAL;
		nameField = new JTextField();
		formPanel.add(nameField, c);

		c.gridx = 0;
		c.gridy++;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		JLabel commentLabel = new JLabel("Comment:");
		formPanel.add(commentLabel, c);

		c.gridx = 1;
		c.weightx = 1.0;
		c.fill = GridBagConstraints.HORIZONTAL;
		commentField = new JTextField();
		formPanel.add(commentField, c);

		c.gridx = 0;
		c.gridy++;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		JLabel enabledLabel = new JLabel("Enabled:");
		formPanel.add(enabledLabel, c);

		c.gridx = 1;
		c.weightx = 1.0;
		c.fill = GridBagConstraints.NONE;
		enabledCheckBox = new JCheckBox();
		formPanel.add(enabledCheckBox, c);

		c.gridx = 0;
		c.gridy++;
		c.weightx = 0;
		c.weighty = 0;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.NORTHWEST;
		JLabel bodyLabel = new JLabel("Body:");
		formPanel.add(bodyLabel, c);

		c.gridx = 1;
		c.weightx = 1.0;
		c.weighty = 1.0;
		c.fill = GridBagConstraints.BOTH;
		bodyArea = new JTextArea(8, 40);
		JBScrollPane scrollPane = new JBScrollPane(bodyArea);
		JPanel bodyPanel = new JPanel(new BorderLayout());
		bodyPanel.add(scrollPane, BorderLayout.CENTER);

		JBTextArea helpArea = new JBTextArea(
				"<CURSOR> — позиция каретки после вставки.\n" +
						"<CURSOR>текст</CURSOR> — выделение текста после вставки.\n" +
						"Тело обычно начинается с '^' или '.'"
		);
		helpArea.setEditable(false);
		helpArea.setOpaque(false);
		helpArea.setBorder(null);
		helpArea.setLineWrap(true);
		helpArea.setWrapStyleWord(true);
		helpArea.setBackground(UIUtil.getPanelBackground());
		helpArea.setFont(JBUI.Fonts.label());
		bodyPanel.add(helpArea, BorderLayout.SOUTH);

		formPanel.add(bodyPanel, c);
		editorPanel.add(formPanel, BorderLayout.CENTER);




		DocumentListener docListener = new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				onEditorChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				onEditorChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				onEditorChanged();
			}
		};

		nameField.getDocument().addDocumentListener(docListener);
		commentField.getDocument().addDocumentListener(docListener);
		bodyArea.getDocument().addDocumentListener(docListener);
		enabledCheckBox.addChangeListener(e -> onEditorChanged());

		return editorPanel;
	}

	private void onSelectionChanged() {
		if (updatingFromSelection) {
			return;
		}
		Parser3UserTemplate selected = templatesList.getSelectedValue();
		updatingFromSelection = true;
		try {
			if (selected == null) {
				nameField.setText("");
				commentField.setText("");
				bodyArea.setText("");
				enabledCheckBox.setSelected(false);
			} else {
				nameField.setText(selected.name != null ? selected.name : "");
				commentField.setText(selected.comment != null ? selected.comment : "");
				bodyArea.setText(selected.body != null ? selected.body : "");
				enabledCheckBox.setSelected(selected.enabled);
			}
		} finally {
			updatingFromSelection = false;
		}
	}

	private void onEditorChanged() {
		if (updatingFromSelection) {
			return;
		}
		Parser3UserTemplate selected = templatesList.getSelectedValue();
		if (selected != null) {
			selected.name = nameField.getText();
			selected.comment = commentField.getText();
			selected.body = bodyArea.getText();
			selected.enabled = enabledCheckBox.isSelected();
		}
		modified = true;
	}

	private void addTemplate() {
		Parser3UserTemplate template = new Parser3UserTemplate();
		template.id = UUID.randomUUID().toString();
		template.name = suggestNewName();
		template.comment = "";
		template.body = "";
		template.enabled = true;
		template.priority = listModel.getSize();
		template.scope = null;
		listModel.addElement(template);
		templatesList.setSelectedValue(template, true);
		modified = true;
	}

	private String suggestNewName() {
		String base = "template";
		int index = 1;
		Set<String> existing = new HashSet<>();
		for (int i = 0; i < listModel.getSize(); i++) {
			Parser3UserTemplate t = listModel.getElementAt(i);
			if (t != null && t.name != null) {
				existing.add(t.name);
			}
		}
		while (true) {
			String candidate = base + index;
			if (!existing.contains(candidate)) {
				return candidate;
			}
			index++;
		}
	}

	private void removeSelectedTemplate() {
		int idx = templatesList.getSelectedIndex();
		if (idx >= 0) {
			listModel.remove(idx);
			modified = true;
			if (!listModel.isEmpty()) {
				int newIndex = Math.min(idx, listModel.getSize() - 1);
				templatesList.setSelectedIndex(newIndex);
			}
		}
	}

	private void moveSelectedTemplateUp() {
		int idx = templatesList.getSelectedIndex();
		if (idx > 0) {
			Parser3UserTemplate t = listModel.getElementAt(idx);
			listModel.remove(idx);
			listModel.add(idx - 1, t);
			templatesList.setSelectedIndex(idx - 1);
			updatePriorities();
			modified = true;
		}
	}

	private void moveSelectedTemplateDown() {
		int idx = templatesList.getSelectedIndex();
		if (idx >= 0 && idx < listModel.getSize() - 1) {
			Parser3UserTemplate t = listModel.getElementAt(idx);
			listModel.remove(idx);
			listModel.add(idx + 1, t);
			templatesList.setSelectedIndex(idx + 1);
			updatePriorities();
			modified = true;
		}
	}

	private void updatePriorities() {
		for (int i = 0; i < listModel.getSize(); i++) {
			Parser3UserTemplate t = listModel.getElementAt(i);
			if (t != null) {
				t.priority = i;
			}
		}
	}

	@Override
	public boolean isModified() {
		return modified;
	}

	@Override
	public void apply() throws ConfigurationException {
		validateTemplates();
		List<Parser3UserTemplate> templates = new ArrayList<>();
		for (int i = 0; i < listModel.getSize(); i++) {
			Parser3UserTemplate t = listModel.getElementAt(i);
			if (t != null) {
				templates.add(copyOf(t));
			}
		}
		Parser3UserTemplatesService.getInstance().setTemplates(templates);
		modified = false;
	}

	private void validateTemplates() throws ConfigurationException {
		Set<String> names = new HashSet<>();
		for (int i = 0; i < listModel.getSize(); i++) {
			Parser3UserTemplate t = listModel.getElementAt(i);
			if (t == null) {
				continue;
			}
			String name = t.name != null ? t.name.trim() : "";
			if (name.isEmpty()) {
				throw new ConfigurationException("Template name must not be empty.");
			}
			if (names.contains(name)) {
				throw new ConfigurationException("Template name must be unique: " + name);
			}
			names.add(name);
			String body = t.body != null ? t.body.trim() : "";
			if (body.isEmpty()) {
				throw new ConfigurationException("Template body must not be empty: " + name);
			}
		}
	}

	@Override
	public void reset() {
		listModel.clear();
		List<Parser3UserTemplate> fromService = Parser3UserTemplatesService.getInstance().getAllTemplates();
		for (Parser3UserTemplate t : fromService) {
			if (t != null) {
				listModel.addElement(copyOf(t));
			}
		}
		if (!listModel.isEmpty()) {
			templatesList.setSelectedIndex(0);
		}
		modified = false;
	}

	private Parser3UserTemplate copyOf(Parser3UserTemplate original) {
		Parser3UserTemplate copy = new Parser3UserTemplate();
		copy.id = original.id;
		copy.name = original.name;
		copy.comment = original.comment;
		copy.body = original.body;
		copy.enabled = original.enabled;
		copy.priority = original.priority;
		copy.scope = original.scope;
		return copy;
	}

	@Override
	public void disposeUIResources() {
		mainPanel = null;
		listModel = null;
		templatesList = null;
		nameField = null;
		commentField = null;
		bodyArea = null;
		enabledCheckBox = null;
		helpLabel = null;
	}
}
