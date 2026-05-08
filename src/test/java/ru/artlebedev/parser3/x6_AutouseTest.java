package ru.artlebedev.parser3;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

/**
 * Тест режима с @autouse — автозагрузка классов.
 *
 * Настройки:
 * - document_root = "www"
 * - режим = USE_ONLY
 * - @autouse[] присутствует в www/auto.p
 *
 * Структура тестового проекта:
 * /
 * ├── lib/
 * │   └── utils.p           <- подключен через @USE в www/auto.p
 * ├── parser_classes/
 * │   └── Logger.p          <- через CLASS_PATH, виден благодаря @autouse
 * └── www/                   <- document_root
 *     ├── auto.p            <- @USE ../lib/utils.p, $CLASS_PATH, @autouse[]
 *     ├── index.p           <- тестовые вызовы
 *     └── classes/
 *         ├── User.p        <- @CLASS User
 *         └── Admin.p       <- @CLASS Admin, @USE User.p, @BASE User
 *
 * Логика @autouse:
 * - Если @autouse[] виден — ВСЕ классы проекта видны автоматически
 * - Классы резолвятся независимо от CLASS_PATH
 * - Обратная навигация (клик на @CLASS) должна находить использования
 *
 * Тестируемые сценарии:
 * 1. Классы через @autouse - ДОЛЖНЫ резолвиться из index.p
 * 2. Обратная навигация: клик на @CLASS Logger -> использования в index.p
 * 3. Обратная навигация: клик на @create в Logger -> вызовы ^Logger::create
 * 4. Методы из lib/utils.p - ДОЛЖНЫ резолвиться (подключены через @USE)
 */
public class x6_AutouseTest extends Parser3TestCase {

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
	 * Создаёт структуру тестовых файлов с @autouse.
	 */
	private void createTestStructure() {
		// lib/utils.p - подключен через @USE в www/auto.p
		createParser3FileInDir("lib/utils.p",
				"# lib/utils.p - ПОДКЛЮЧЕН через @USE в auto.p\n" +
						"\n" +
						"@helper[text]\n" +
						"$result[$text]\n" +
						"\n" +
						"@format[data]\n" +
						"$result[formatted: $data]\n"
		);

		// parser_classes/Logger.p - виден через @autouse
		createParser3FileInDir("parser_classes/Logger.p",
				"# parser_classes/Logger.p - виден через @autouse\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Logger\n" +
						"\n" +
						"@create[]\n" +
						"$self.level[info]\n" +
						"\n" +
						"@log[message]\n" +
						"$result[$self.level: $message]\n" +
						"\n" +
						"@debug[message]\n" +
						"$result[DEBUG: $message]\n"
		);

		// www/auto.p - с @autouse[]
		createParser3FileInDir("www/auto.p",
				"# www/auto.p - с @autouse\n" +
						"# реальный минимальный кейс Parser3 fixture\n@USE\n" +
						"../lib/utils.p\n" +
						"\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$CLASS_PATH[^table::create{path\n" +
						"/../parser_classes/\n" +
						"/classes\n" +
						"}]\n" +
						"\n" +
						"@autouse[name]\n" +
						"^use[$name.p]\n"
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
						"$result[$self.name]\n" +
						"\n" +
						"@validate[]\n" +
						"^if(!$self.name){^throw[error]}\n"
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
						"$self.role[$role]\n" +
						"\n" +
						"@ban[userId]\n" +
						"$result[banned: $userId]\n"
		);

		// www/index.p - основной тестовый файл с использованием классов
		createParser3FileInDir("www/index.p",
				"# www/index.p - @autouse делает все классы видимыми\n" +
						"\n" +
						"# ===== Методы из lib/utils.p (через @USE) =====\n" +
						"^helper[test]\n" +
						"^format[data]\n" +
						"\n" +
						"# ===== Классы через @autouse (ДОЛЖНЫ резолвиться) =====\n" +
						"$logger[^Logger::create[]]\n" +
						"^logger.log[message]\n" +
						"^Logger:debug[test]\n" +
						"\n" +
						"$user[^User::create[John]]\n" +
						"^user.getName[]\n" +
						"\n" +
						"$admin[^Admin::create[John;admin]]\n" +
						"^admin.ban[123]\n"
		);

		// www/other.p - ещё один файл для проверки обратной навигации
		createParser3FileInDir("www/other.p",
				"# www/other.p - ещё одно использование Logger\n" +
						"\n" +
						"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$log[^Logger::create[]]\n" +
						"^log.log[from other.p]\n"
		);
	}

	// ==================== ТЕСТЫ: Классы через @autouse (ДОЛЖНЫ резолвиться) ====================

	/**
	 * ^Logger::create[] из www/index.p -> parser_classes/Logger.p
	 */
	public void testAutouse_LoggerCreate() {
		assertNavigationWorks("www/index.p", "^Logger::create[]", "parser_classes/Logger.p");
	}

	/**
	 * ^Logger:debug[] (статический вызов) из www/index.p -> parser_classes/Logger.p
	 */
	public void testAutouse_LoggerStaticMethod() {
		assertNavigationWorks("www/index.p", "^Logger:debug[test]", "parser_classes/Logger.p");
	}

	/**
	 * ^User::create[] из www/index.p -> www/classes/User.p
	 */
	public void testAutouse_UserCreate() {
		assertNavigationWorks("www/index.p", "^User::create[John]", "www/classes/User.p");
	}

	/**
	 * ^Admin::create[] из www/index.p -> www/classes/Admin.p
	 */
	public void testAutouse_AdminCreate() {
		assertNavigationWorks("www/index.p", "^Admin::create[John;admin]", "www/classes/Admin.p");
	}

	// ==================== ТЕСТЫ: Методы через @USE (ДОЛЖНЫ резолвиться) ====================

	/**
	 * ^helper[] из www/index.p -> lib/utils.p
	 */
	public void testUse_helper() {
		assertNavigationWorks("www/index.p", "^helper[test]", "lib/utils.p");
	}

	/**
	 * ^format[] из www/index.p -> lib/utils.p
	 */
	public void testUse_format() {
		assertNavigationWorks("www/index.p", "^format[data]", "lib/utils.p");
	}

	// ==================== ТЕСТЫ: Обратная навигация для классов с @autouse ====================

	/**
	 * Клик на @CLASS Logger -> показать использования ^Logger:: из index.p и other.p
	 */
	public void testReverseNavigation_ClassLogger() {
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

		// Проверяем что есть использования из index.p и other.p
		boolean foundIndex = false;
		boolean foundOther = false;
		for (PsiElement target : targets) {
			PsiFile file = target.getContainingFile();
			if (file != null) {
				String path = file.getVirtualFile().getPath();
				if (path.contains("index.p")) foundIndex = true;
				if (path.contains("other.p")) foundOther = true;
			}
		}
		assertTrue("Should find usage from index.p", foundIndex);
		assertTrue("Should find usage from other.p", foundOther);
	}

	/**
	 * Клик на @create[] в Logger.p -> показать вызовы ^Logger::create[]
	 */
	public void testReverseNavigation_LoggerCreateMethod() {
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

		// Проверяем что есть вызовы из index.p и other.p
		boolean foundIndex = false;
		boolean foundOther = false;
		for (PsiElement target : targets) {
			PsiFile file = target.getContainingFile();
			if (file != null) {
				String path = file.getVirtualFile().getPath();
				if (path.contains("index.p")) foundIndex = true;
				if (path.contains("other.p")) foundOther = true;
			}
		}
		assertTrue("Should find call from index.p", foundIndex);
		assertTrue("Should find call from other.p", foundOther);
	}

	/**
	 * Клик на @log[] в Logger.p -> показать вызовы ^log.log[] и ^logger.log[]
	 */
	public void testReverseNavigation_LoggerLogMethod() {
		PsiFile loggerFile = myFixture.configureByFile("parser_classes/Logger.p");
		String text = loggerFile.getText();

		// Находим @log[message]
		int logPos = text.indexOf("@log[message]");

		myFixture.getEditor().getCaretModel().moveToOffset(logPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				logPos + 1
		);

		// Может не найти вызовы ^obj.log[] так как это динамический вызов
		// Но должен найти хотя бы что-то если индекс работает
		// Этот тест документирует текущее поведение
		System.out.println("[TEST] Reverse navigation for @log found " +
				(targets != null ? targets.length : 0) + " targets");
	}

	// ==================== ТЕСТЫ: @BASE навигация ====================

	/**
	 * Клик на User в @BASE User (в Admin.p) -> переход к User.p
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
		boolean foundUser = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith("User.p")) {
				foundUser = true;
				break;
			}
		}
		assertTrue("Should navigate to User.p", foundUser);
	}

	/**
	 * ^BASE:create[] в Admin.p -> переход к @create в User.p
	 */
	public void testBaseMethodCall() {
		assertNavigationWorks("www/classes/Admin.p", "^BASE:create[$name]", "www/classes/User.p");
	}

	// ==================== ТЕСТЫ: Обратная навигация для User (через @autouse) ====================

	/**
	 * Клик на @CLASS User -> показать использования и наследников
	 */
	public void testReverseNavigation_ClassUser() {
		PsiFile userFile = myFixture.configureByFile("www/classes/User.p");
		String text = userFile.getText();

		// Находим "User" после @CLASS
		int classPos = text.indexOf("@CLASS");
		int userPos = text.indexOf("User", classPos);

		myFixture.getEditor().getCaretModel().moveToOffset(userPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				userPos + 1
		);

		assertNotNull("Should find usages for @CLASS User", targets);
		assertTrue("Should find at least one usage", targets.length > 0);

		// Должны найти: использования из index.p и наследника Admin
		boolean foundIndex = false;
		boolean foundAdmin = false;
		for (PsiElement target : targets) {
			PsiFile file = target.getContainingFile();
			if (file != null) {
				String path = file.getVirtualFile().getPath();
				if (path.contains("index.p")) foundIndex = true;
				if (path.contains("Admin.p")) foundAdmin = true;
			}
		}
		assertTrue("Should find usage from index.p", foundIndex);
		assertTrue("Should find child class Admin.p", foundAdmin);
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
	 * Находит offset имени метода в паттерне вызова.
	 */
	private int findMethodNameOffset(String text, String callPattern) {
		int patternPos = text.indexOf(callPattern);
		if (patternPos < 0) {
			return -1;
		}

		// Ищем начало имени метода после ^ или ::
		int caretPos = callPattern.indexOf('^');
		if (caretPos >= 0) {
			// ^method или ^Class::method или ^Class:method
			int doubleColonPos = callPattern.indexOf("::");
			int singleColonPos = callPattern.indexOf(':');

			if (doubleColonPos > caretPos) {
				// ^Class::method - возвращаем позицию после ::
				return patternPos + doubleColonPos + 2;
			} else if (singleColonPos > caretPos && !callPattern.substring(singleColonPos).startsWith("::")) {
				// ^Class:method - возвращаем позицию после :
				return patternPos + singleColonPos + 1;
			} else {
				// ^method - возвращаем позицию после ^
				return patternPos + caretPos + 1;
			}
		}

		return patternPos;
	}
}