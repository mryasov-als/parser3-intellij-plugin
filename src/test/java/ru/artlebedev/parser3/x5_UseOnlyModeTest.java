package ru.artlebedev.parser3;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

/**
 * Тест режима "Только через USE" с document_root.
 *
 * Настройки:
 * - document_root = "www"
 * - режим = USE_ONLY (только через @USE)
 * - БЕЗ @autouse
 *
 * Структура тестового проекта (на основе 4-goto):
 * /
 * ├── external/
 * │   └── secret.p          <- вне document_root, НЕ подключен
 * ├── lib/
 * │   ├── utils.p           <- подключен через @USE в www/auto.p
 * │   └── database.p        <- НЕ подключен
 * ├── parser_classes/
 * │   └── Logger.p          <- через CLASS_PATH, подключен через ^use[Logger.p]
 * ├── other_dir/
 * │   └── Isolated.p        <- подключен через абсолютный путь, НЕ в CLASS_PATH
 * └── www/                   <- document_root
 *     ├── auto.p            <- @USE ../lib/utils.p, $CLASS_PATH[...]
 *     ├── index.p           <- тестовые вызовы
 *     ├── inner/
 *     │   └── file.p        <- ^use[Admin.p], тесты разных скобок
 *     ├── withClassPath/
 *     │   └── page.p        <- ^use[Logger.p] (короткий путь + CLASS_PATH)
 *     ├── withAbsolutePath/
 *     │   └── page.p        <- ^use[/../other_dir/Isolated.p] (абсолютный путь)
 *     └── classes/
 *         ├── User.p        <- @CLASS User
 *         └── Admin.p       <- @CLASS Admin, @USE User.p, @BASE User
 *
 * Тестируемые сценарии:
 * 1. Методы из подключенного файла (lib/utils.p) - ДОЛЖНЫ резолвиться
 * 2. Методы из НЕ подключенного файла (lib/database.p) - НЕ должны резолвиться
 * 3. Методы вне document_root (external/secret.p) - НЕ должны резолвиться
 * 4. Классы через ^use[] в inner/file.p - ДОЛЖНЫ резолвиться
 * 5. Навигация @BASE User -> User.p - ДОЛЖНА работать
 * 6. Разные типы скобок: [], (), {} - все ДОЛЖНЫ работать
 * 7. CLASS_PATH: ^use[Logger.p] + путь в CLASS_PATH - ДОЛЖЕН резолвиться
 * 8. Абсолютный путь: ^use[/../other_dir/Isolated.p] - ДОЛЖЕН резолвиться
 * 9. Класс НЕ подключенный (Isolated2) - НЕ должен резолвиться
 */
public class x5_UseOnlyModeTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();

		// Устанавливаем режим USE_ONLY
		ru.artlebedev.parser3.settings.Parser3ProjectSettings settings =
				ru.artlebedev.parser3.settings.Parser3ProjectSettings.getInstance(getProject());
		settings.setMethodCompletionMode(
				ru.artlebedev.parser3.settings.Parser3ProjectSettings.MethodCompletionMode.USE_ONLY);

		createTestStructure();

		// Устанавливаем document_root на www
		com.intellij.openapi.vfs.VirtualFile wwwDir =
				myFixture.findFileInTempDir("www");
		if (wwwDir != null) {
			settings.setDocumentRoot(wwwDir);
		}

		// Коммитим все документы
		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

		// Ждём пока индексация завершится
		com.intellij.openapi.project.DumbService dumbService =
				com.intellij.openapi.project.DumbService.getInstance(getProject());
		dumbService.waitForSmartMode();
	}

	/**
	 * Создаёт структуру тестовых файлов (копия 4-goto).
	 */
	private void createTestStructure() {
		// external/secret.p - вне document_root, не подключен
		createParser3FileInDir("external/secret.p",
				"# external/secret.p - НЕ подключен\n" +
						"\n" +
						"@secretMethod[]\n" +
						"$result[secret]\n"
		);

		// lib/utils.p - подключен через @USE в www/auto.p
		createParser3FileInDir("lib/utils.p",
				"# lib/utils.p - ПОДКЛЮЧЕН через @USE в auto.p\n" +
						"\n" +
						"@helper[text]\n" +
						"$result[$text]\n" +
						"\n" +
						"#####################################\n" +
						"# asdf\n" +
						"# $data(hash) информация о юзере\n" +
						"# $result(string) инфо\n" +
						"#####################################\n" +
						"@format[data]\n" +
						"$result[formatted: $data]\n"
		);

		// lib/database.p - НЕ подключен
		createParser3FileInDir("lib/database.p",
				"# lib/database.p - НЕ подключен через @USE\n" +
						"\n" +
						"@connect[host]\n" +
						"$result[connected to $host]\n" +
						"\n" +
						"@query[sql]\n" +
						"$result[query: $sql]\n"
		);

		// parser_classes/Logger.p - через CLASS_PATH (но без @autouse)
		createParser3FileInDir("parser_classes/Logger.p",
				"# parser_classes/Logger.p - через CLASS_PATH\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Logger\n" +
						"\n" +
						"@create[]\n" +
						"$self.level[info]\n" +
						"\n" +
						"@log[message]\n" +
						"$result[$self.level: $message]\n"
		);

		// other_dir/Isolated.p - подключается через абсолютный путь, НЕ в CLASS_PATH
		createParser3FileInDir("other_dir/Isolated.p",
				"# other_dir/Isolated.p - подключен через абсолютный путь\n" +
						"# Директория other_dir НЕ в CLASS_PATH\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Isolated\n" +
						"\n" +
						"@create[]\n" +
						"$self.isolated(true)\n" +
						"\n" +
						"@process[]\n" +
						"$result[isolated processing]\n"
		);

		// other_dir/Isolated2.p - НЕ подключен никак
		createParser3FileInDir("other_dir/Isolated2.p",
				"# other_dir/Isolated2.p - НЕ подключен\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Isolated2\n" +
						"\n" +
						"@create[]\n" +
						"$self.data[test]\n" +
						"\n" +
						"@doSomething[]\n" +
						"$result[something]\n"
		);

		// www/auto.p - главный auto файл
		createParser3FileInDir("www/auto.p",
				"# www/auto.p\n" +
						"# Подключаем ТОЛЬКО lib/utils.p\n" +
						"# НЕТ @autouse - значит CLASS_PATH и классы внутри www НЕ видны автоматически\n" +
						"# реальный минимальный кейс Parser3 fixture\n@USE\n" +
						"../lib/utils.p\n" +
						"\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$CLASS_PATH[^table::create{path\n" +
						"/../parser_classes/\n" +
						"/classes\n" +
						"}]\n"
		);

		// www/classes/User.p
		createParser3FileInDir("www/classes/User.p",
				"# www/classes/User.p\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"User\n" +
						"\n" +
						"@create[name]\n" +
						"$self.name[$name]\n" +
						"\n" +
						"@getName[]\n" +
						"$result[$self.name]\n"
		);

		// www/classes/Admin.p - с @USE User.p и @BASE User
		createParser3FileInDir("www/classes/Admin.p",
				"# www/classes/Admin.p\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Admin\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@USE\n" +
						"User.p\n" +
						"\n" +
						"@BASE\n" +
						"User\n" +
						"\n" +
						"@create[name;role]\n" +
						"^BASE:create[$name]\n" +
						"$self.role[$role]\n"
		);

		// www/index.p - основной тестовый файл
		createParser3FileInDir("www/index.p",
				"# www/index.p\n" +
						"# Настройки: document_root=www, режим=\"только через USE\", БЕЗ @autouse\n" +
						"\n" +
						"# ===== ДОЛЖНЫ резолвиться (lib/utils.p подключен через @USE) =====\n" +
						"^helper[test]\n" +
						"^format[data]\n" +
						"\n" +
						"# ===== НЕ должны резолвиться (lib/database.p НЕ подключен) =====\n" +
						"^connect[localhost]\n" +
						"^query[SELECT * FROM users]\n" +
						"\n" +
						"# ===== НЕ должны резолвиться (external вне document_root, не подключен) =====\n" +
						"^secretMethod[]\n" +
						"\n" +
						"# ===== НЕ должны резолвиться (классы внутри www, но НЕ подключены через @USE) =====\n" +
						"$user[^User::create[name]]\n" +
						"$admin[^Admin::create[name;role]]\n" +
						"\n" +
						"# ===== НЕ должны резолвиться (CLASS_PATH без @autouse не работает) =====\n" +
						"$logger[^Logger::create[]]\n"
		);

		// www/inner/file.p - тесты через ^use[] и разные скобки
		createParser3FileInDir("www/inner/file.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^use[../classes/Admin.p]\n" +
						"# должны резолвиться\n" +
						"^User::create[]\n" +
						"$Admin[^Admin::create[]]\n" +
						"$Admin(^Admin::create[])\n" +
						"$Admin{^Admin::create[]}\n"
		);

		// www/withClassPath/page.p - подключает Logger.p через короткое имя + CLASS_PATH
		createParser3FileInDir("www/withClassPath/page.p",
				"# www/withClassPath/page.p\n" +
						"# Logger.p подключен через короткое имя, резолвится через CLASS_PATH\n" +
						"\n" +
						"^use[Logger.p]\n" +
						"\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"# ДОЛЖНЫ резолвиться (Logger.p подключен через ^use + CLASS_PATH)\n" +
						"$logger[^Logger::create[]]\n" +
						"^logger.log[test message]\n" +
						"^Logger:log[static call]\n"
		);

		// www/withAbsolutePath/page.p - подключает Isolated.p через абсолютный путь
		createParser3FileInDir("www/withAbsolutePath/page.p",
				"# www/withAbsolutePath/page.p\n" +
						"# Isolated.p подключен через абсолютный путь (other_dir НЕ в CLASS_PATH)\n" +
						"\n" +
						"^use[/../other_dir/Isolated.p]\n" +
						"\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"# ДОЛЖНЫ резолвиться (Isolated.p подключен через абсолютный путь)\n" +
						"$obj[^Isolated::create[]]\n" +
						"^obj.process[]\n" +
						"\n" +
						"# НЕ ДОЛЖНЫ резолвиться (Isolated2 НЕ подключен)\n" +
						"$obj2[^Isolated2::create[]]\n" +
						"^obj2.doSomething[]\n"
		);
	}

	// ==================== ТЕСТЫ: Подключенные методы (ДОЛЖНЫ резолвиться) ====================

	/**
	 * ^helper[] из www/index.p -> lib/utils.p (подключен через @USE в auto.p)
	 */
	public void testConnectedMethod_helper() {
		assertNavigationWorks("www/index.p", "^helper[test]", "lib/utils.p");
	}

	/**
	 * ^format[] из www/index.p -> lib/utils.p (подключен через @USE в auto.p)
	 */
	public void testConnectedMethod_format() {
		assertNavigationWorks("www/index.p", "^format[data]", "lib/utils.p");
	}

	// ==================== ТЕСТЫ: НЕ подключенные методы (НЕ должны резолвиться) ====================

	/**
	 * ^connect[] из www/index.p - lib/database.p НЕ подключен
	 */
	public void testNotConnectedMethod_connect() {
		assertNavigationFails("www/index.p", "^connect[localhost]");
	}

	/**
	 * ^query[] из www/index.p - lib/database.p НЕ подключен
	 */
	public void testNotConnectedMethod_query() {
		assertNavigationFails("www/index.p", "^query[SELECT");
	}

	/**
	 * ^secretMethod[] из www/index.p - external/secret.p вне document_root и не подключен
	 */
	public void testNotConnectedMethod_secret() {
		assertNavigationFails("www/index.p", "^secretMethod[]");
	}

	// ==================== ТЕСТЫ: Классы без @autouse (НЕ должны резолвиться из index.p) ====================

	/**
	 * ^User::create[] из www/index.p - класс НЕ подключен через @USE
	 */
	public void testClassNotConnected_User() {
		assertNavigationFails("www/index.p", "^User::create[name]");
	}

	/**
	 * ^Admin::create[] из www/index.p - класс НЕ подключен через @USE
	 */
	public void testClassNotConnected_Admin() {
		assertNavigationFails("www/index.p", "^Admin::create[name;role]");
	}

	/**
	 * ^Logger::create[] из www/index.p - CLASS_PATH без @autouse не работает
	 */
	public void testClassPath_withoutAutouse() {
		assertNavigationFails("www/index.p", "^Logger::create[]");
	}

	// ==================== ТЕСТЫ: inner/file.p с ^use[] (ДОЛЖНЫ резолвиться) ====================

	/**
	 * ^User::create[] из www/inner/file.p -> www/classes/User.p
	 * (Admin.p подключен через ^use[], Admin имеет @USE User.p)
	 */
	public void testUseChain_User() {
		assertNavigationWorks("www/inner/file.p", "^User::create[]", "www/classes/User.p");
	}

	/**
	 * ^Admin::create[] из www/inner/file.p -> www/classes/Admin.p
	 * (Admin.p подключен через ^use[])
	 */
	public void testUseChain_Admin_SquareBrackets() {
		assertNavigationWorks("www/inner/file.p", "$Admin[^Admin::create[]]", "www/classes/Admin.p");
	}

	/**
	 * ^Admin::create[] с круглыми скобками - ДОЛЖЕН работать
	 */
	public void testUseChain_Admin_RoundBrackets() {
		assertNavigationWorks("www/inner/file.p", "$Admin(^Admin::create[])", "www/classes/Admin.p");
	}

	/**
	 * ^Admin::create[] с фигурными скобками - ДОЛЖЕН работать
	 */
	public void testUseChain_Admin_CurlyBrackets() {
		assertNavigationWorks("www/inner/file.p", "$Admin{^Admin::create[]}", "www/classes/Admin.p");
	}

	// ==================== ТЕСТЫ: @BASE навигация ====================

	/**
	 * Клик на User в @BASE User -> переход к User.p
	 */
	public void testBaseNavigation_UserInAdmin() {
		PsiFile adminFile = myFixture.configureByFile("www/classes/Admin.p");
		String text = adminFile.getText();

		// Находим User после @BASE
		int basePos = text.indexOf("@BASE");
		int userPos = text.indexOf("User", basePos);

		myFixture.getEditor().getCaretModel().moveToOffset(userPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				userPos + 1
		);

		assertNotNull("Should find navigation target for @BASE User", targets);
		assertTrue("Should find at least one target", targets.length > 0);

		// Проверяем что цель в User.p
		PsiFile targetFile = targets[0].getContainingFile();
		assertNotNull("Target should have containing file", targetFile);
		assertTrue("Should navigate to User.p",
				targetFile.getVirtualFile().getPath().endsWith("User.p"));
	}

	// ==================== ТЕСТЫ: Обратная навигация (клик на определении) ====================

	/**
	 * Клик на @helper[] в lib/utils.p -> показать вызовы из www/index.p
	 */
	public void testReverseNavigation_helper() {
		PsiFile utilsFile = myFixture.configureByFile("lib/utils.p");
		String text = utilsFile.getText();

		// Находим @helper
		int helperPos = text.indexOf("@helper[text]");

		myFixture.getEditor().getCaretModel().moveToOffset(helperPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				helperPos + 1
		);

		// Должны найти вызовы
		assertNotNull("Should find call sites for @helper", targets);
		assertTrue("Should find at least one call site", targets.length > 0);

		// Проверяем что есть вызов из index.p
		boolean foundIndexCall = false;
		for (PsiElement target : targets) {
			PsiFile file = target.getContainingFile();
			if (file != null && file.getVirtualFile().getPath().contains("index.p")) {
				foundIndexCall = true;
				break;
			}
		}
		assertTrue("Should find call from index.p", foundIndexCall);
	}

	// ==================== ТЕСТЫ: CLASS_PATH с коротким именем (^use[Logger.p]) ====================

	/**
	 * ^Logger::create[] из www/withClassPath/page.p -> parser_classes/Logger.p
	 * Logger.p подключен через ^use[Logger.p], резолвится через CLASS_PATH
	 */
	public void testClassPath_LoggerCreate() {
		assertNavigationWorks("www/withClassPath/page.p", "^Logger::create[]", "parser_classes/Logger.p");
	}

	/**
	 * ^Logger:log[] (статический вызов) из www/withClassPath/page.p -> parser_classes/Logger.p
	 */
	public void testClassPath_LoggerStaticMethod() {
		assertNavigationWorks("www/withClassPath/page.p", "^Logger:log[static call]", "parser_classes/Logger.p");
	}

	/**
	 * Обратная навигация: клик на @create[] в Logger.p -> вызовы из withClassPath/page.p
	 */
	public void testClassPath_ReverseNavigation_LoggerCreate() {
		PsiFile loggerFile = myFixture.configureByFile("parser_classes/Logger.p");
		String text = loggerFile.getText();

		// Находим @create[]
		int createPos = text.indexOf("@create[]");

		myFixture.getEditor().getCaretModel().moveToOffset(createPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				createPos + 1
		);

		assertNotNull("Should find call sites for @create in Logger", targets);
		assertTrue("Should find at least one call site", targets.length > 0);

		// Проверяем что есть вызов из withClassPath/page.p
		boolean foundPage = false;
		for (PsiElement target : targets) {
			PsiFile file = target.getContainingFile();
			if (file != null && file.getVirtualFile().getPath().contains("withClassPath/page.p")) {
				foundPage = true;
				break;
			}
		}
		assertTrue("Should find call from withClassPath/page.p", foundPage);
	}

	/**
	 * Обратная навигация: клик на @CLASS Logger -> использования из withClassPath/page.p
	 */
	public void testClassPath_ReverseNavigation_ClassLogger() {
		PsiFile loggerFile = myFixture.configureByFile("parser_classes/Logger.p");
		String text = loggerFile.getText();

		// Находим "Logger" после @CLASS
		int classPos = text.indexOf("@CLASS");
		int loggerPos = text.indexOf("Logger", classPos);

		myFixture.getEditor().getCaretModel().moveToOffset(loggerPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				loggerPos + 1
		);

		assertNotNull("Should find usages for @CLASS Logger", targets);
		assertTrue("Should find at least one usage", targets.length > 0);

		// Проверяем что есть использование из withClassPath/page.p
		boolean foundPage = false;
		for (PsiElement target : targets) {
			PsiFile file = target.getContainingFile();
			if (file != null && file.getVirtualFile().getPath().contains("withClassPath/page.p")) {
				foundPage = true;
				break;
			}
		}
		assertTrue("Should find usage from withClassPath/page.p", foundPage);
	}

	// ==================== ТЕСТЫ: Абсолютный путь (^use[/../other_dir/Isolated.p]) ====================

	/**
	 * ^Isolated::create[] из www/withAbsolutePath/page.p -> other_dir/Isolated.p
	 * Isolated.p подключен через абсолютный путь, other_dir НЕ в CLASS_PATH
	 */
	public void testAbsolutePath_IsolatedCreate() {
		assertNavigationWorks("www/withAbsolutePath/page.p", "^Isolated::create[]", "other_dir/Isolated.p");
	}

	/**
	 * Обратная навигация: клик на @create[] в Isolated.p -> вызовы из withAbsolutePath/page.p
	 */
	public void testAbsolutePath_ReverseNavigation_IsolatedCreate() {
		PsiFile isolatedFile = myFixture.configureByFile("other_dir/Isolated.p");
		String text = isolatedFile.getText();

		// Находим @create[]
		int createPos = text.indexOf("@create[]");

		myFixture.getEditor().getCaretModel().moveToOffset(createPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				createPos + 1
		);

		assertNotNull("Should find call sites for @create in Isolated", targets);
		assertTrue("Should find at least one call site", targets.length > 0);

		// Проверяем что есть вызов из withAbsolutePath/page.p
		boolean foundPage = false;
		for (PsiElement target : targets) {
			PsiFile file = target.getContainingFile();
			if (file != null && file.getVirtualFile().getPath().contains("withAbsolutePath/page.p")) {
				foundPage = true;
				break;
			}
		}
		assertTrue("Should find call from withAbsolutePath/page.p", foundPage);
	}

	/**
	 * Обратная навигация: клик на @CLASS Isolated -> использования из withAbsolutePath/page.p
	 */
	public void testAbsolutePath_ReverseNavigation_ClassIsolated() {
		PsiFile isolatedFile = myFixture.configureByFile("other_dir/Isolated.p");
		String text = isolatedFile.getText();

		// Находим "Isolated" после @CLASS
		int classPos = text.indexOf("@CLASS");
		int isolatedPos = text.indexOf("Isolated", classPos);

		myFixture.getEditor().getCaretModel().moveToOffset(isolatedPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				isolatedPos + 1
		);

		assertNotNull("Should find usages for @CLASS Isolated", targets);
		assertTrue("Should find at least one usage", targets.length > 0);

		// Проверяем что есть использование из withAbsolutePath/page.p
		boolean foundPage = false;
		for (PsiElement target : targets) {
			PsiFile file = target.getContainingFile();
			if (file != null && file.getVirtualFile().getPath().contains("withAbsolutePath/page.p")) {
				foundPage = true;
				break;
			}
		}
		assertTrue("Should find usage from withAbsolutePath/page.p", foundPage);
	}

	// ==================== ТЕСТЫ: НЕ подключенный класс Isolated2 ====================

	/**
	 * ^Isolated2::create[] из www/withAbsolutePath/page.p - НЕ должен резолвиться
	 * Isolated2.p существует, но НЕ подключен через @USE
	 */
	public void testNotConnected_Isolated2Create() {
		assertNavigationFails("www/withAbsolutePath/page.p", "^Isolated2::create[]");
	}

	/**
	 * Обратная навигация: клик на @CLASS Isolated2 -> НЕ должен найти использования
	 * (withAbsolutePath/page.p НЕ подключал Isolated2.p)
	 */
	public void testNotConnected_ReverseNavigation_ClassIsolated2() {
		PsiFile isolated2File = myFixture.configureByFile("other_dir/Isolated2.p");
		String text = isolated2File.getText();

		// Находим "Isolated2" после @CLASS
		int classPos = text.indexOf("@CLASS");
		int isolated2Pos = text.indexOf("Isolated2", classPos);

		myFixture.getEditor().getCaretModel().moveToOffset(isolated2Pos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				isolated2Pos + 1
		);

		// НЕ должен найти использования из withAbsolutePath/page.p
		// (там есть вызов ^Isolated2::create[], но Isolated2 НЕ подключен)
		boolean foundPage = false;
		if (targets != null) {
			for (PsiElement target : targets) {
				PsiFile file = target.getContainingFile();
				if (file != null && file.getVirtualFile().getPath().contains("withAbsolutePath/page.p")) {
					foundPage = true;
					break;
				}
			}
		}
		assertFalse("Should NOT find usage from withAbsolutePath/page.p (Isolated2 not connected)", foundPage);
	}

	// ==================== ТЕСТЫ: позиционный ^use[] для navigation ====================

	/**
	 * Ctrl+Click не должен видеть MAIN-метод из ^use[] ниже курсора.
	 */
	public void testNavigation_useBelowCursorDoesNotExposeMainMethod() {
		createParser3FileInDir("www/future_method.p",
				"@futureNav[]\n" +
						"$result[ok]\n"
		);
		createParser3FileInDir("www/future_method_page.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^futureNav[]\n" +
						"^use[future_method.p]\n"
		);

		assertNavigationFails("www/future_method_page.p", "^futureNav[]");
	}

	/**
	 * Ctrl+Click должен видеть MAIN-метод из ^use[] выше курсора.
	 */
	public void testNavigation_useAboveCursorExposesMainMethod() {
		createParser3FileInDir("www/past_method.p",
				"@pastNav[]\n" +
						"$result[ok]\n"
		);
		createParser3FileInDir("www/past_method_page.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^use[past_method.p]\n" +
						"^pastNav[]\n"
		);

		assertNavigationWorks("www/past_method_page.p", "^pastNav[]", "www/past_method.p");
	}

	/**
	 * Ctrl+Click не должен видеть класс из ^use[] ниже курсора.
	 */
	public void testNavigation_useBelowCursorDoesNotExposeClassMethod() {
		createParser3FileInDir("www/FutureUser.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"FutureUser\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("www/future_class_page.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$user[^FutureUser::create[]]\n" +
						"^use[FutureUser.p]\n"
		);

		assertNavigationFails("www/future_class_page.p", "^FutureUser::create[]");
	}

	/**
	 * Ctrl+Click должен видеть класс из ^use[] выше курсора.
	 */
	public void testNavigation_useAboveCursorExposesClassMethod() {
		createParser3FileInDir("www/PastUser.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"PastUser\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("www/past_class_page.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^use[PastUser.p]\n" +
						"$user[^PastUser::create[]]\n"
		);

		assertNavigationWorks("www/past_class_page.p", "^PastUser::create[]", "www/PastUser.p");
	}

	/**
	 * @BASE не должен резолвиться в класс из ^use[] ниже строки @BASE.
	 */
	public void testBaseNavigation_useBelowCursorDoesNotExposeBaseClass() {
		createParser3FileInDir("www/FutureBase.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"FutureBase\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("www/FutureChild.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"FutureChild\n" +
						"\n" +
						"@BASE\n" +
						"FutureBase\n" +
						"\n" +
						"^use[FutureBase.p]\n"
		);

		assertBaseNavigationFails("www/FutureChild.p", "FutureBase");
	}

	/**
	 * @BASE должен резолвиться в класс из ^use[] выше строки @BASE.
	 */
	public void testBaseNavigation_useAboveCursorExposesBaseClass() {
		createParser3FileInDir("www/PastBase.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"PastBase\n" +
						"\n" +
						"@create[]\n"
		);
		createParser3FileInDir("www/PastChild.p",
				"^use[PastBase.p]\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"PastChild\n" +
						"\n" +
						"@BASE\n" +
						"PastBase\n"
		);

		assertBaseNavigationWorks("www/PastChild.p", "PastBase", "www/PastBase.p");
	}

	/**
	 * Навигация по переменной не должна видеть присваивание из ^use[] ниже курсора.
	 */
	public void testVariableNavigation_useBelowCursorDoesNotExposeVariable() {
		createParser3FileInDir("www/future_vars.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$sharedValue[ok]\n"
		);
		createParser3FileInDir("www/future_vars_page.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$copy[$sharedValue]\n" +
						"^use[future_vars.p]\n"
		);

		assertVariableNavigationFails("www/future_vars_page.p", "$sharedValue");
	}

	/**
	 * Навигация по переменной должна видеть присваивание из ^use[] выше курсора.
	 */
	public void testVariableNavigation_useAboveCursorExposesVariable() {
		createParser3FileInDir("www/past_vars.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$sharedPast[ok]\n"
		);
		createParser3FileInDir("www/past_vars_page.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^use[past_vars.p]\n" +
						"$copy[$sharedPast]\n"
		);

		assertVariableNavigationWorks("www/past_vars_page.p", "$sharedPast", "www/past_vars.p");
	}

	// ==================== Helper методы ====================

	/**
	 * Проверяет что навигация работает: клик на вызове ведёт к ожидаемому файлу.
	 */
	private void assertNavigationWorks(String sourceFile, String callPattern, String expectedFile) {
		PsiFile file = myFixture.configureByFile(sourceFile);
		String text = file.getText();

		int offset = findMethodNameOffset(text, callPattern);
		assertTrue("Should find pattern '" + callPattern + "' in " + sourceFile, offset >= 0);

		myFixture.getEditor().getCaretModel().moveToOffset(offset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				offset
		);

		assertNotNull("Should find navigation target for '" + callPattern + "'", targets);
		assertTrue("Should find at least one target for '" + callPattern + "'", targets.length > 0);

		// Проверяем что хотя бы один target в ожидаемом файле
		boolean found = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith(expectedFile)) {
				found = true;
				break;
			}
		}
		assertTrue("Navigation should lead to " + expectedFile + " for '" + callPattern + "'", found);
	}

	/**
	 * Проверяет что навигация НЕ работает (метод не резолвится).
	 */
	private void assertNavigationFails(String sourceFile, String callPattern) {
		PsiFile file = myFixture.configureByFile(sourceFile);
		String text = file.getText();

		int offset = findMethodNameOffset(text, callPattern);
		assertTrue("Should find pattern '" + callPattern + "' in " + sourceFile, offset >= 0);

		myFixture.getEditor().getCaretModel().moveToOffset(offset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				offset
		);

		// Должно быть null или пустой массив
		boolean noTargets = (targets == null || targets.length == 0);
		assertTrue("Should NOT find navigation target for '" + callPattern + "' (method not connected)", noTargets);
	}

	private void assertBaseNavigationWorks(String sourceFile, String baseClassName, String expectedFile) {
		PsiElement[] targets = findTargetsOnText(sourceFile, baseClassName);

		assertNotNull("Should find navigation target for @BASE " + baseClassName, targets);
		assertTrue("Should find at least one target for @BASE " + baseClassName, targets.length > 0);

		boolean found = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith(expectedFile)) {
				found = true;
				break;
			}
		}
		assertTrue("Navigation should lead to " + expectedFile + " for @BASE " + baseClassName, found);
	}

	private void assertBaseNavigationFails(String sourceFile, String baseClassName) {
		PsiElement[] targets = findTargetsOnText(sourceFile, baseClassName);
		boolean noTargets = targets == null || targets.length == 0;
		assertTrue("@BASE " + baseClassName + " should not navigate through ^use[] below cursor", noTargets);
	}

	private void assertVariableNavigationWorks(String sourceFile, String variableText, String expectedFile) {
		PsiElement[] targets = findTargetsOnText(sourceFile, variableText);

		assertNotNull("Should find navigation target for variable " + variableText, targets);
		assertTrue("Should find at least one target for variable " + variableText, targets.length > 0);

		boolean found = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith(expectedFile)) {
				found = true;
				break;
			}
		}
		assertTrue("Variable navigation should lead to " + expectedFile + " for " + variableText, found);
	}

	private void assertVariableNavigationFails(String sourceFile, String variableText) {
		PsiElement[] targets = findTargetsOnText(sourceFile, variableText);
		boolean noTargets = targets == null || targets.length == 0;
		assertTrue("Variable " + variableText + " should not navigate through ^use[] below cursor", noTargets);
	}

	private PsiElement[] findTargetsOnText(String sourceFile, String textPattern) {
		PsiFile file = myFixture.configureByFile(sourceFile);
		String text = file.getText();

		int offset = text.indexOf(textPattern);
		assertTrue("Should find pattern '" + textPattern + "' in " + sourceFile, offset >= 0);
		if (textPattern.startsWith("$")) {
			offset++;
		}

		myFixture.getEditor().getCaretModel().moveToOffset(offset);
		return GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				offset
		);
	}

	/**
	 * Находит offset имени метода в паттерне вызова.
	 * Например для "^helper[test]" вернёт позицию 'h' в helper.
	 */
	private int findMethodNameOffset(String text, String callPattern) {
		int patternPos = text.indexOf(callPattern);
		if (patternPos < 0) {
			return -1;
		}

		// Ищем начало имени метода после ^ или :: или :
		int caretPos = callPattern.indexOf('^');
		if (caretPos >= 0) {
			// ^method или ^Class::method или ^Class:method
			int doubleColonPos = callPattern.indexOf("::");
			int singleColonPos = callPattern.indexOf(':');

			if (doubleColonPos > caretPos) {
				// ^Class::method - возвращаем позицию после ::
				return patternPos + doubleColonPos + 2;
			} else if (singleColonPos > caretPos && (doubleColonPos < 0 || singleColonPos < doubleColonPos)) {
				// ^Class:method (статический вызов) - возвращаем позицию после :
				return patternPos + singleColonPos + 1;
			} else {
				// ^method - возвращаем позицию после ^
				return patternPos + caretPos + 1;
			}
		}

		return patternPos;
	}
}
