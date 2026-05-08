package ru.artlebedev.parser3.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.index.P3IndexMaintenance;
import ru.artlebedev.parser3.index.P3ClassFileIndex;
import ru.artlebedev.parser3.index.P3MethodFileIndex;
import ru.artlebedev.parser3.utils.Parser3FileUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class Parser3SettingsConfigurable implements SearchableConfigurable {
	private static final boolean DEBUG = false;

	private final Project project;
	private TextFieldWithBrowseButton documentRootField;
	private TextFieldWithBrowseButton mainAutoField;
	private JRadioButton allMethodsRadio;
	private JRadioButton useOnlyRadio;
	private JBLabel methodsStatsLabel;
	private JBLabel classesStatsLabel;
	private JButton rebuildButton;

	public Parser3SettingsConfigurable(@NotNull Project project) {
		this.project = project;
	}

	@Override
	public @NotNull String getId() {
		return "parser3.settings";
	}

	@Override
	public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
		return "Parser 3";
	}

	@Override
	public @Nullable JComponent createComponent() {
		// Document Root - темный IntelliJ диалог с деревом проекта
		documentRootField = new TextFieldWithBrowseButton();
		documentRootField.addActionListener((ActionEvent e) -> {
			FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
			descriptor.setTitle("Выберите Document Root");
			descriptor.setDescription("Web-root директория проекта");
			descriptor.setRoots(project.getBaseDir());
			descriptor.withTreeRootVisible(true);

			VirtualFile currentFile = null;
			String currentPath = documentRootField.getText();
			if (!currentPath.isEmpty()) {
				currentFile = project.getBaseDir().findFileByRelativePath(currentPath);
			}

			// Использовать темный IntelliJ диалог вместо нативного Windows
			FileChooserDialogImpl dialog = new FileChooserDialogImpl(descriptor, project);
			VirtualFile[] chosen = dialog.choose(
					project,
					currentFile != null ? currentFile : project.getBaseDir()
			);

			if (chosen.length > 0) {
				String relativePath = getRelativePath(project.getBaseDir(), chosen[0]);
				documentRootField.setText(relativePath);
			}
		});

		// Main Auto - темный IntelliJ диалог с деревом файлов проекта
		mainAutoField = new TextFieldWithBrowseButton();
		mainAutoField.addActionListener((ActionEvent e) -> {
			FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor();
			descriptor.setTitle("Выберите главный auto.p");
			descriptor.setDescription("Файл auto.p рядом с parser3.cgi");
			descriptor.setRoots(project.getBaseDir());
			descriptor.withTreeRootVisible(true);

			VirtualFile currentFile = null;
			String currentPath = mainAutoField.getText();
			if (!currentPath.isEmpty()) {
				currentFile = project.getBaseDir().findFileByRelativePath(currentPath);
			}

			// Использовать темный IntelliJ диалог вместо нативного Windows
			FileChooserDialogImpl dialog = new FileChooserDialogImpl(descriptor, project);
			VirtualFile[] chosen = dialog.choose(
					project,
					currentFile != null ? currentFile : project.getBaseDir()
			);

			if (chosen.length > 0) {
				String relativePath = getRelativePath(project.getBaseDir(), chosen[0]);
				mainAutoField.setText(relativePath);
			}
		});

		// Radio buttons for method completion mode
		allMethodsRadio = new JRadioButton("Все методы");
		useOnlyRadio = new JRadioButton("Только через use");
		ButtonGroup completionModeGroup = new ButtonGroup();
		completionModeGroup.add(allMethodsRadio);
		completionModeGroup.add(useOnlyRadio);

		JPanel completionModePanel = new JPanel();
		completionModePanel.setLayout(new BoxLayout(completionModePanel, BoxLayout.Y_AXIS));
		completionModePanel.add(allMethodsRadio);
		completionModePanel.add(useOnlyRadio);

		// Описание режимов автокомплита
		JBLabel descriptionLabel = new JBLabel(
				"<html><div style='margin-top: 8px; color: #808080; width: 450px;'>" +
						"<b>Все методы</b> — показывает методы из всех файлов Parser3 в проекте " +
						"(включая файлы с пользовательскими расширениями из Editor → File Types), " +
						"независимо от того, подключены они через use или нет.<br><br>" +
						"<b>Только через use</b> — показывает только методы из подключённых файлов. " +
						"Плагин анализирует <code>^use[...]</code> и <code>@USE</code>, учитывая <code>$MAIN:CLASS_PATH</code>. " +
						"Если пути задаются переменными или используется собственная система подключения файлов, " +
						"рекомендуется режим \"Все методы\"." +
						"</div></html>"
		);

		JPanel mainPanel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.NORTHWEST;
		gbc.insets = new Insets(0, 0, 4, 0);

		// Document root
		JBLabel docRootLabel = new JBLabel("Document root:");
		mainPanel.add(docRootLabel, gbc);

		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 12, 0);
		mainPanel.add(documentRootField, gbc);

		// Main auto.p
		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 4, 0);
		JBLabel mainAutoLabel = new JBLabel("Основной auto.p (рядом с parser3.cgi):");
		mainPanel.add(mainAutoLabel, gbc);

		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 12, 0);
		mainPanel.add(mainAutoField, gbc);

		// Автокомплит методов
		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 4, 0);
		JBLabel completionLabel = new JBLabel("Автокомплит методов:");
		mainPanel.add(completionLabel, gbc);

		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 0, 0);
		gbc.fill = GridBagConstraints.NONE;
		mainPanel.add(completionModePanel, gbc);

		gbc.gridy++;
		mainPanel.add(descriptionLabel, gbc);

		// Разделитель
		gbc.gridy++;
		gbc.insets = new Insets(16, 0, 8, 0);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		mainPanel.add(new JSeparator(), gbc);

		// Статистика индексов
		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 8, 0);
		JBLabel indexLabel = new JBLabel("Индексы:");
		indexLabel.setFont(indexLabel.getFont().deriveFont(Font.BOLD));
		mainPanel.add(indexLabel, gbc);

		// Статистика методов (ссылка + текст)
		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 4, 0);
		JPanel methodsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		methodsPanel.setOpaque(false);

		JBLabel methodsLink = new JBLabel("<html><u>Методы</u></html>");
		methodsLink.setForeground(com.intellij.ui.JBColor.BLUE);
		methodsLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		methodsLink.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				showMethodsList();
			}
		});
		methodsPanel.add(methodsLink);

		methodsStatsLabel = new JBLabel(": загрузка...");
		methodsPanel.add(methodsStatsLabel);
		mainPanel.add(methodsPanel, gbc);

		// Статистика классов (ссылка + текст)
		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 8, 0);
		JPanel classesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		classesPanel.setOpaque(false);

		JBLabel classesLink = new JBLabel("<html><u>Классы</u></html>");
		classesLink.setForeground(com.intellij.ui.JBColor.BLUE);
		classesLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		classesLink.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				showClassesList();
			}
		});
		classesPanel.add(classesLink);

		classesStatsLabel = new JBLabel(": загрузка...");
		classesPanel.add(classesStatsLabel);
		mainPanel.add(classesPanel, gbc);

		// Кнопка обновления индексов
		gbc.gridy++;
		gbc.insets = new Insets(0, 0, 0, 0);
		gbc.fill = GridBagConstraints.NONE;
		gbc.anchor = GridBagConstraints.WEST;
		rebuildButton = new JButton("Обновить индексы");
		rebuildButton.addActionListener(e -> rebuildIndexes());
		mainPanel.add(rebuildButton, gbc);

		// Загружаем статистику
		updateIndexStats();

		// Пустое пространство внизу
		gbc.gridy++;
		gbc.weighty = 1.0;
		gbc.fill = GridBagConstraints.BOTH;
		mainPanel.add(new JPanel(), gbc);

		return mainPanel;
	}

	/**
	 * Обновляет статистику индексов в UI
	 */
	private void updateIndexStats() {
		// Проверяем что UI компоненты ещё существуют
		if (methodsStatsLabel == null || classesStatsLabel == null) {
			return;
		}

		// Если в dumb mode — ждём окончания индексации
		if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb()) {
			com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart(this::updateIndexStats);
			return;
		}

		com.intellij.openapi.application.ReadAction.nonBlocking(() -> {
					P3IndexMaintenance.ensureParser3IndexesUpToDate(project);
					FileBasedIndex index = FileBasedIndex.getInstance();
					Collection<VirtualFile> parser3Files = P3IndexMaintenance.getIndexedParser3Files(project);
					if (DEBUG) {
						System.out.println("[P3Settings] updateIndexStats files=" + parser3Files.size()
								+ " project=" + project.getName());
					}

					// Методы: количество файлов и методов (только MAIN, не в классах)
					Set<VirtualFile> methodFiles = new HashSet<>();
					int methodCount = 0;

					for (VirtualFile file : parser3Files) {
						Map<String, List<P3MethodFileIndex.MethodInfo>> fileData =
								index.getFileData(P3MethodFileIndex.NAME, file, project);
						for (List<P3MethodFileIndex.MethodInfo> infos : fileData.values()) {
							for (P3MethodFileIndex.MethodInfo info : infos) {
								// Только методы из MAIN (не в классах)
								if (info.ownerClass == null) {
									methodFiles.add(file);
									methodCount++;
								}
							}
						}
					}

					// Классы: количество явных классов (не MAIN), файлов с классами и методов в классах
					Set<VirtualFile> classFiles = new HashSet<>();
					int classCount = 0;
					int classMethodCount = 0;

					for (VirtualFile file : parser3Files) {
						Map<String, List<P3ClassFileIndex.ClassInfo>> fileData =
								index.getFileData(P3ClassFileIndex.NAME, file, project);
						for (Map.Entry<String, List<P3ClassFileIndex.ClassInfo>> entry : fileData.entrySet()) {
							// Пропускаем неявный MAIN класс
							if ("MAIN".equals(entry.getKey())) {
								continue;
							}

							List<P3ClassFileIndex.ClassInfo> infos = entry.getValue();
							if (infos != null) {
								classFiles.add(file);
								classCount += infos.size();
								// Считаем методы внутри классов
								for (P3ClassFileIndex.ClassInfo info : infos) {
									classMethodCount += countMethodsInRange(file, info.startOffset, info.endOffset);
								}
							}
						}
					}

					if (DEBUG) {
						System.out.println("[P3Settings] updateIndexStats result"
								+ " methodFiles=" + methodFiles.size()
								+ " methods=" + methodCount
								+ " classes=" + classCount
								+ " classFiles=" + classFiles.size()
								+ " classMethods=" + classMethodCount);
					}

					return new int[]{methodFiles.size(), methodCount, classCount, classFiles.size(), classMethodCount};
				})
				.finishOnUiThread(com.intellij.openapi.application.ModalityState.any(), stats -> {
					if (methodsStatsLabel != null) {
						methodsStatsLabel.setText(String.format(": %d %s, %d %s",
								stats[0], pluralFiles(stats[0]),
								stats[1], pluralMethods(stats[1])));
					}
					if (classesStatsLabel != null) {
						classesStatsLabel.setText(String.format(": %d %s, %d %s, %d %s",
								stats[2], pluralClasses(stats[2]),
								stats[3], pluralFiles(stats[3]),
								stats[4], pluralMethods(stats[4])));
					}
					if (rebuildButton != null) {
						rebuildButton.setEnabled(true);
					}
				})
				.submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
	}

	/**
	 * Считает количество методов в диапазоне offset
	 */
	private int countMethodsInRange(VirtualFile file, int startOffset, int endOffset) {
		FileBasedIndex index = FileBasedIndex.getInstance();
		int count = 0;

		Map<String, List<P3MethodFileIndex.MethodInfo>> fileData = index.getFileData(P3MethodFileIndex.NAME, file, project);
		for (List<P3MethodFileIndex.MethodInfo> infos : fileData.values()) {
			for (P3MethodFileIndex.MethodInfo info : infos) {
				if (info.offset >= startOffset && info.offset < endOffset) {
					count++;
				}
			}
		}

		return count;
	}

	/**
	 * Обновляет индексы
	 */
	private void rebuildIndexes() {
		// Проверяем что UI компоненты ещё существуют
		if (methodsStatsLabel == null || classesStatsLabel == null) {
			return;
		}

		// Проверяем что не в dumb mode
		if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb()) {
			return;
		}

		// Блокируем кнопку и показываем индикатор
		if (rebuildButton != null) {
			rebuildButton.setEnabled(false);
		}
		methodsStatsLabel.setText(": обновление...");
		classesStatsLabel.setText(": обновление...");

		// Принудительно перестраиваем наши индексы и переиндексируем найденные Parser3 файлы
		P3IndexMaintenance.requestParser3IndexRebuild(project, "settings button");
		com.intellij.openapi.application.ReadAction.nonBlocking(() -> Parser3FileUtils.getProjectParser3Files(project))
				.finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState(), filesToReindex -> {
					if (DEBUG) {
						System.out.println("[P3Settings] rebuildIndexes filesToReindex=" + filesToReindex.size()
								+ " project=" + project.getName());
					}
					P3IndexMaintenance.requestParser3FileReindex(project, filesToReindex, "settings button");

					com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart(this::updateIndexStats);

					// Обновляем статистику через небольшую задержку, если индексация завершилась быстро
					new javax.swing.Timer(500, e -> {
						((javax.swing.Timer)e.getSource()).stop();
						updateIndexStats();
					}).start();
				})
				.submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
	}

	/**
	 * Показывает список всех методов MAIN
	 */
	private void showMethodsList() {
		// Проверяем dumb mode
		if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb()) {
			return;
		}

		java.util.List<MethodListItem> methods = com.intellij.openapi.application.ApplicationManager.getApplication()
				.runReadAction((com.intellij.openapi.util.Computable<java.util.List<MethodListItem>>) () -> {
					java.util.List<MethodListItem> result = new java.util.ArrayList<>();
					FileBasedIndex index = FileBasedIndex.getInstance();

					for (VirtualFile file : P3IndexMaintenance.getIndexedParser3Files(project)) {
						Map<String, List<P3MethodFileIndex.MethodInfo>> fileData =
								index.getFileData(P3MethodFileIndex.NAME, file, project);
						for (Map.Entry<String, List<P3MethodFileIndex.MethodInfo>> entry : fileData.entrySet()) {
							for (P3MethodFileIndex.MethodInfo info : entry.getValue()) {
								// Только методы из MAIN
								if (info.ownerClass == null) {
									String relativePath = getRelativePathFromDocRoot(file);
									result.add(new MethodListItem(entry.getKey(), relativePath, file, info.offset));
								}
							}
						}
					}

					// Сортируем по имени метода
					result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
					return result;
				});

		showMethodsPopup(methods);
	}

	/**
	 * Показывает список всех классов
	 */
	private void showClassesList() {
		// Проверяем dumb mode
		if (com.intellij.openapi.project.DumbService.getInstance(project).isDumb()) {
			return;
		}

		java.util.List<ClassListItem> classes = com.intellij.openapi.application.ApplicationManager.getApplication()
				.runReadAction((com.intellij.openapi.util.Computable<java.util.List<ClassListItem>>) () -> {
					java.util.List<ClassListItem> result = new java.util.ArrayList<>();
					FileBasedIndex index = FileBasedIndex.getInstance();

					for (VirtualFile file : P3IndexMaintenance.getIndexedParser3Files(project)) {
						Map<String, List<P3ClassFileIndex.ClassInfo>> fileData =
								index.getFileData(P3ClassFileIndex.NAME, file, project);
						for (Map.Entry<String, List<P3ClassFileIndex.ClassInfo>> entry : fileData.entrySet()) {
							String className = entry.getKey();
							// Пропускаем MAIN
							if ("MAIN".equals(className)) continue;

							List<P3ClassFileIndex.ClassInfo> infos = entry.getValue();
							if (infos != null && !infos.isEmpty()) {
								String relativePath = getRelativePathFromDocRoot(file);
								result.add(new ClassListItem(className, relativePath, file, infos.get(0).startOffset));
							}
						}
					}

					// Сортируем по имени класса
					result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
					return result;
				});

		showClassesPopup(classes);
	}

	/**
	 * Возвращает путь относительно document_root
	 */
	private String getRelativePathFromDocRoot(VirtualFile file) {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);
		VirtualFile docRoot = settings.getDocumentRoot();

		if (docRoot != null) {
			String docRootPath = docRoot.getPath();
			String filePath = file.getPath();

			if (filePath.startsWith(docRootPath)) {
				return filePath.substring(docRootPath.length());
			} else {
				// Файл выше document_root - вычисляем относительный путь с /../
				String[] docParts = docRootPath.split("/");
				String[] fileParts = filePath.split("/");

				// Находим общий префикс
				int common = 0;
				while (common < docParts.length && common < fileParts.length
						&& docParts[common].equals(fileParts[common])) {
					common++;
				}

				// Строим путь
				StringBuilder sb = new StringBuilder();
				for (int i = common; i < docParts.length; i++) {
					sb.append("/..");
				}
				for (int i = common; i < fileParts.length; i++) {
					sb.append("/").append(fileParts[i]);
				}
				return sb.toString();
			}
		}

		// Fallback - от корня проекта
		String basePath = project.getBasePath();
		if (basePath != null && file.getPath().startsWith(basePath)) {
			return file.getPath().substring(basePath.length());
		}
		return file.getName();
	}

	/**
	 * Показывает popup со списком методов
	 */
	private void showMethodsPopup(java.util.List<MethodListItem> methods) {
		MethodsDialog dialog = new MethodsDialog(project, methods);
		dialog.show();
	}

	/**
	 * Показывает popup со списком классов
	 */
	private void showClassesPopup(java.util.List<ClassListItem> classes) {
		ClassesDialog dialog = new ClassesDialog(project, classes);
		dialog.show();
	}

	/**
	 * Диалог списка методов
	 */
	private static class MethodsDialog extends com.intellij.openapi.ui.DialogWrapper {
		private final Project project;
		private final java.util.List<MethodListItem> methods;
		private com.intellij.ui.table.JBTable table;
		private javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel> sorter;

		MethodsDialog(Project project, java.util.List<MethodListItem> methods) {
			super(project, true);
			this.project = project;
			this.methods = methods;
			setTitle("Методы MAIN (" + methods.size() + ")");
			init();
		}

		@Override
		protected JComponent createCenterPanel() {
			JPanel panel = new JPanel(new BorderLayout(0, 8));

			// Панель поиска с двумя полями и чекбоксами под ними
			JPanel searchPanel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.fill = GridBagConstraints.HORIZONTAL;
			gbc.insets = new Insets(0, 0, 0, 16);

			// Первая строка: лейблы и поля ввода
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.weightx = 0;
			searchPanel.add(new JBLabel("Метод:"), gbc);

			gbc.gridx = 1;
			gbc.weightx = 1;
			com.intellij.ui.SearchTextField nameSearchField = new com.intellij.ui.SearchTextField();
			searchPanel.add(nameSearchField, gbc);

			gbc.gridx = 2;
			gbc.weightx = 0;
			searchPanel.add(new JBLabel("Файл:"), gbc);

			gbc.gridx = 3;
			gbc.weightx = 1;
			gbc.insets = new Insets(0, 0, 0, 0);
			com.intellij.ui.SearchTextField fileSearchField = new com.intellij.ui.SearchTextField();
			searchPanel.add(fileSearchField, gbc);

			// Вторая строка: чекбоксы под полями
			gbc.gridy = 1;
			gbc.gridx = 1;
			gbc.weightx = 0;
			gbc.insets = new Insets(2, 0, 0, 16);
			gbc.anchor = GridBagConstraints.WEST;
			JCheckBox nameExactCheckbox = new JCheckBox("Точное совпадение");
			nameExactCheckbox.setToolTipText("Точное совпадение (с учётом регистра)");
			searchPanel.add(nameExactCheckbox, gbc);

			gbc.gridx = 3;
			gbc.insets = new Insets(2, 0, 0, 0);
			JCheckBox fileExactCheckbox = new JCheckBox("С учётом регистра");
			fileExactCheckbox.setToolTipText("Поиск с учётом регистра");
			searchPanel.add(fileExactCheckbox, gbc);

			panel.add(searchPanel, BorderLayout.NORTH);

			// Таблица
			String[] columns = {"Метод", "Файл"};
			Object[][] data = new Object[methods.size()][2];
			for (int i = 0; i < methods.size(); i++) {
				data[i][0] = methods.get(i).name;
				data[i][1] = methods.get(i).path;
			}

			javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(data, columns) {
				@Override
				public boolean isCellEditable(int row, int column) {
					return false;
				}
			};

			table = new com.intellij.ui.table.JBTable(model);
			sorter = new javax.swing.table.TableRowSorter<>(model);
			table.setRowSorter(sorter);
			table.getColumnModel().getColumn(0).setPreferredWidth(200);
			table.getColumnModel().getColumn(1).setPreferredWidth(450);
			table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

			// Фильтрация по двум полям
			Runnable updateFilter = () -> {
				String nameText = nameSearchField.getText().trim();
				String fileText = fileSearchField.getText().trim();
				boolean nameExact = nameExactCheckbox.isSelected();
				boolean fileExact = fileExactCheckbox.isSelected();

				java.util.List<javax.swing.RowFilter<Object, Object>> filters = new java.util.ArrayList<>();
				if (!nameText.isEmpty()) {
					String pattern = java.util.regex.Pattern.quote(nameText);
					if (nameExact) {
						// Точное совпадение — ищем полное совпадение с учётом регистра
						filters.add(javax.swing.RowFilter.regexFilter("^" + pattern + "$", 0));
					} else {
						// Частичное совпадение без учёта регистра
						filters.add(javax.swing.RowFilter.regexFilter("(?i)" + pattern, 0));
					}
				}
				if (!fileText.isEmpty()) {
					String pattern = java.util.regex.Pattern.quote(fileText);
					if (fileExact) {
						filters.add(javax.swing.RowFilter.regexFilter(pattern, 1));
					} else {
						filters.add(javax.swing.RowFilter.regexFilter("(?i)" + pattern, 1));
					}
				}

				if (filters.isEmpty()) {
					sorter.setRowFilter(null);
				} else {
					sorter.setRowFilter(javax.swing.RowFilter.andFilter(filters));
				}
			};

			nameSearchField.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
				@Override
				protected void textChanged(@org.jetbrains.annotations.NotNull javax.swing.event.DocumentEvent e) {
					updateFilter.run();
				}
			});
			fileSearchField.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
				@Override
				protected void textChanged(@org.jetbrains.annotations.NotNull javax.swing.event.DocumentEvent e) {
					updateFilter.run();
				}
			});
			nameExactCheckbox.addActionListener(e -> updateFilter.run());
			fileExactCheckbox.addActionListener(e -> updateFilter.run());

			// Двойной клик — открыть файл
			table.addMouseListener(new java.awt.event.MouseAdapter() {
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e) {
					if (e.getClickCount() == 2) {
						int row = table.getSelectedRow();
						if (row >= 0) {
							int modelRow = table.convertRowIndexToModel(row);
							MethodListItem item = methods.get(modelRow);
							com.intellij.openapi.fileEditor.OpenFileDescriptor descriptor =
									new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, item.file, item.offset);
							descriptor.navigate(true);
							close(OK_EXIT_CODE);
						}
					}
				}
			});

			JScrollPane scrollPane = new JScrollPane(table);
			scrollPane.setPreferredSize(new Dimension(800, 400));
			panel.add(scrollPane, BorderLayout.CENTER);

			return panel;
		}

		@Override
		protected Action @org.jetbrains.annotations.NotNull [] createActions() {
			return new Action[]{getOKAction()};
		}
	}

	/**
	 * Диалог списка классов
	 */
	private static class ClassesDialog extends com.intellij.openapi.ui.DialogWrapper {
		private final Project project;
		private final java.util.List<ClassListItem> classes;
		private com.intellij.ui.table.JBTable table;
		private javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel> sorter;

		ClassesDialog(Project project, java.util.List<ClassListItem> classes) {
			super(project, true);
			this.project = project;
			this.classes = classes;
			setTitle("Классы (" + classes.size() + ")");
			init();
		}

		@Override
		protected JComponent createCenterPanel() {
			JPanel panel = new JPanel(new BorderLayout(0, 8));

			// Панель поиска с двумя полями и чекбоксами под ними
			JPanel searchPanel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.fill = GridBagConstraints.HORIZONTAL;
			gbc.insets = new Insets(0, 0, 0, 16);

			// Первая строка: лейблы и поля ввода
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.weightx = 0;
			searchPanel.add(new JBLabel("Класс:"), gbc);

			gbc.gridx = 1;
			gbc.weightx = 1;
			com.intellij.ui.SearchTextField nameSearchField = new com.intellij.ui.SearchTextField();
			searchPanel.add(nameSearchField, gbc);

			gbc.gridx = 2;
			gbc.weightx = 0;
			searchPanel.add(new JBLabel("Файл:"), gbc);

			gbc.gridx = 3;
			gbc.weightx = 1;
			gbc.insets = new Insets(0, 0, 0, 0);
			com.intellij.ui.SearchTextField fileSearchField = new com.intellij.ui.SearchTextField();
			searchPanel.add(fileSearchField, gbc);

			// Вторая строка: чекбоксы под полями
			gbc.gridy = 1;
			gbc.gridx = 1;
			gbc.weightx = 0;
			gbc.insets = new Insets(2, 0, 0, 16);
			gbc.anchor = GridBagConstraints.WEST;
			JCheckBox nameExactCheckbox = new JCheckBox("Точное совпадение");
			nameExactCheckbox.setToolTipText("Точное совпадение (с учётом регистра)");
			searchPanel.add(nameExactCheckbox, gbc);

			gbc.gridx = 3;
			gbc.insets = new Insets(2, 0, 0, 0);
			JCheckBox fileExactCheckbox = new JCheckBox("С учётом регистра");
			fileExactCheckbox.setToolTipText("Поиск с учётом регистра");
			searchPanel.add(fileExactCheckbox, gbc);

			panel.add(searchPanel, BorderLayout.NORTH);

			// Таблица
			String[] columns = {"Класс", "Файл"};
			Object[][] data = new Object[classes.size()][2];
			for (int i = 0; i < classes.size(); i++) {
				data[i][0] = classes.get(i).name;
				data[i][1] = classes.get(i).path;
			}

			javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(data, columns) {
				@Override
				public boolean isCellEditable(int row, int column) {
					return false;
				}
			};

			table = new com.intellij.ui.table.JBTable(model);
			sorter = new javax.swing.table.TableRowSorter<>(model);
			table.setRowSorter(sorter);
			table.getColumnModel().getColumn(0).setPreferredWidth(200);
			table.getColumnModel().getColumn(1).setPreferredWidth(450);
			table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

			// Фильтрация по двум полям
			Runnable updateFilter = () -> {
				String nameText = nameSearchField.getText().trim();
				String fileText = fileSearchField.getText().trim();
				boolean nameExact = nameExactCheckbox.isSelected();
				boolean fileExact = fileExactCheckbox.isSelected();

				java.util.List<javax.swing.RowFilter<Object, Object>> filters = new java.util.ArrayList<>();
				if (!nameText.isEmpty()) {
					String pattern = java.util.regex.Pattern.quote(nameText);
					if (nameExact) {
						// Точное совпадение — ищем полное совпадение с учётом регистра
						filters.add(javax.swing.RowFilter.regexFilter("^" + pattern + "$", 0));
					} else {
						// Частичное совпадение без учёта регистра
						filters.add(javax.swing.RowFilter.regexFilter("(?i)" + pattern, 0));
					}
				}
				if (!fileText.isEmpty()) {
					String pattern = java.util.regex.Pattern.quote(fileText);
					if (fileExact) {
						filters.add(javax.swing.RowFilter.regexFilter(pattern, 1));
					} else {
						filters.add(javax.swing.RowFilter.regexFilter("(?i)" + pattern, 1));
					}
				}

				if (filters.isEmpty()) {
					sorter.setRowFilter(null);
				} else {
					sorter.setRowFilter(javax.swing.RowFilter.andFilter(filters));
				}
			};

			nameSearchField.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
				@Override
				protected void textChanged(@org.jetbrains.annotations.NotNull javax.swing.event.DocumentEvent e) {
					updateFilter.run();
				}
			});
			fileSearchField.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
				@Override
				protected void textChanged(@org.jetbrains.annotations.NotNull javax.swing.event.DocumentEvent e) {
					updateFilter.run();
				}
			});
			nameExactCheckbox.addActionListener(e -> updateFilter.run());
			fileExactCheckbox.addActionListener(e -> updateFilter.run());

			// Двойной клик — открыть файл
			table.addMouseListener(new java.awt.event.MouseAdapter() {
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e) {
					if (e.getClickCount() == 2) {
						int row = table.getSelectedRow();
						if (row >= 0) {
							int modelRow = table.convertRowIndexToModel(row);
							ClassListItem item = classes.get(modelRow);
							com.intellij.openapi.fileEditor.OpenFileDescriptor descriptor =
									new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, item.file, item.offset);
							descriptor.navigate(true);
							close(OK_EXIT_CODE);
						}
					}
				}
			});

			JScrollPane scrollPane = new JScrollPane(table);
			scrollPane.setPreferredSize(new Dimension(800, 400));
			panel.add(scrollPane, BorderLayout.CENTER);

			return panel;
		}

		@Override
		protected Action @org.jetbrains.annotations.NotNull [] createActions() {
			return new Action[]{getOKAction()};
		}
	}

	/**
	 * Элемент списка методов
	 */
	private static class MethodListItem {
		final String name;
		final String path;
		final VirtualFile file;
		final int offset;

		MethodListItem(String name, String path, VirtualFile file, int offset) {
			this.name = name;
			this.path = path;
			this.file = file;
			this.offset = offset;
		}

		@Override
		public String toString() {
			return "@" + name + "[] " + path;
		}
	}

	/**
	 * Элемент списка классов
	 */
	private static class ClassListItem {
		final String name;
		final String path;
		final VirtualFile file;
		final int offset;

		ClassListItem(String name, String path, VirtualFile file, int offset) {
			this.name = name;
			this.path = path;
			this.file = file;
			this.offset = offset;
		}

		@Override
		public String toString() {
			return name + " " + path;
		}
	}

	/**
	 * Склонение слова "файл"
	 */
	private String pluralFiles(int count) {
		int mod10 = count % 10;
		int mod100 = count % 100;
		if (mod10 == 1 && mod100 != 11) return "файл";
		if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "файла";
		return "файлов";
	}

	/**
	 * Склонение слова "метод"
	 */
	private String pluralMethods(int count) {
		int mod10 = count % 10;
		int mod100 = count % 100;
		if (mod10 == 1 && mod100 != 11) return "метод";
		if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "метода";
		return "методов";
	}

	/**
	 * Склонение слова "класс"
	 */
	private String pluralClasses(int count) {
		int mod10 = count % 10;
		int mod100 = count % 100;
		if (mod10 == 1 && mod100 != 11) return "класс";
		if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "класса";
		return "классов";
	}

	@Override
	public boolean isModified() {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);

		String currentDocRoot = pathFromUrl(settings.getDocumentRootPath());
		String uiDocRoot = documentRootField.getText().trim();
		if (uiDocRoot.isEmpty()) uiDocRoot = null;

		String currentMainAuto = pathFromUrl(settings.getMainAutoPath());
		String uiMainAuto = mainAutoField.getText().trim();
		if (uiMainAuto.isEmpty()) uiMainAuto = null;

		Parser3ProjectSettings.MethodCompletionMode currentMode = settings.getMethodCompletionMode();
		Parser3ProjectSettings.MethodCompletionMode uiMode = allMethodsRadio.isSelected()
				? Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS
				: Parser3ProjectSettings.MethodCompletionMode.USE_ONLY;

		return !Objects.equals(currentDocRoot, uiDocRoot) ||
				!Objects.equals(currentMainAuto, uiMainAuto) ||
				currentMode != uiMode;
	}

	@Override
	public void apply() {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);

		String docRoot = documentRootField.getText().trim();
		if (!docRoot.isEmpty()) {
			VirtualFile file = project.getBaseDir().findFileByRelativePath(docRoot);
			settings.setDocumentRoot(file);
		} else {
			settings.setDocumentRoot(null);
		}

		String mainAuto = mainAutoField.getText().trim();
		if (!mainAuto.isEmpty()) {
			VirtualFile file = project.getBaseDir().findFileByRelativePath(mainAuto);
			settings.setMainAuto(file);
		} else {
			settings.setMainAuto(null);
		}

		Parser3ProjectSettings.MethodCompletionMode mode = allMethodsRadio.isSelected()
				? Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS
				: Parser3ProjectSettings.MethodCompletionMode.USE_ONLY;
		settings.setMethodCompletionMode(mode);
	}

	@Override
	public void reset() {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(project);

		String docRoot = pathFromUrl(settings.getDocumentRootPath());
		documentRootField.setText(docRoot != null ? docRoot : "");

		String mainAuto = pathFromUrl(settings.getMainAutoPath());
		mainAutoField.setText(mainAuto != null ? mainAuto : "");

		Parser3ProjectSettings.MethodCompletionMode mode = settings.getMethodCompletionMode();
		if (mode == Parser3ProjectSettings.MethodCompletionMode.ALL_METHODS) {
			allMethodsRadio.setSelected(true);
		} else {
			useOnlyRadio.setSelected(true);
		}
	}

	@Override
	public void disposeUIResources() {
		documentRootField = null;
		mainAutoField = null;
		allMethodsRadio = null;
		useOnlyRadio = null;
		methodsStatsLabel = null;
		classesStatsLabel = null;
		rebuildButton = null;
	}

	// Вычисляет относительный путь от base до target
	private String getRelativePath(VirtualFile base, VirtualFile target) {
		String basePath = base.getPath();
		String targetPath = target.getPath();

		if (targetPath.startsWith(basePath)) {
			String relative = targetPath.substring(basePath.length());
			if (relative.startsWith("/")) {
				relative = relative.substring(1);
			}
			return relative;
		}

		return targetPath;
	}

	// Преобразует URL (file://...) в относительный путь
	private String pathFromUrl(String url) {
		if (url == null) return null;

		if (url.startsWith("file://")) {
			url = url.substring(7);
		}

		// Преобразуем абсолютный путь в относительный от проекта
		VirtualFile baseDir = project.getBaseDir();
		if (baseDir != null) {
			String basePath = baseDir.getPath();
			if (url.startsWith(basePath)) {
				String relative = url.substring(basePath.length());
				if (relative.startsWith("/")) {
					relative = relative.substring(1);
				}
				return relative;
			}
		}

		return url;
	}
}
