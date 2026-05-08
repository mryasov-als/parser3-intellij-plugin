package ru.artlebedev.parser3;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;

/**
 * Сложные тесты навигации ^use[] и @USE с CLASS_PATH.
 *
 * Структура:
 * var/www/domains/domain.ru/
 * ├── cgi/
 * │   └── auto.p                    # $CLASS_PATH = /../../../parser_classes//
 * ├── parser_classes/               # Основные классы
 * │   ├── utils.p
 * │   ├── database.p
 * │   ├── common.p
 * │   └── inner_classes/
 * │       ├── helper.p              # Конфликт имён!
 * │       └── validator.p
 * ├── parser_classes_OLD/           # Старые классы
 * │   ├── utils.p                   # Конфликт имён!
 * │   ├── legacy.p
 * │   └── inner_classes/
 * │       ├── helper.p              # Конфликт имён!
 * │       └── old_validator.p
 * └── files/public_html/www/        # document_root
 *     ├── index.p
 *     ├── config.p
 *     ├── inner_classes/            # Локальные (конфликт с parser_classes!)
 *     │   ├── helper.p
 *     │   └── local.p
 *     ├── admin/
 *     │   ├── auto.p                # ^CLASS_PATH.append{parser_classes_OLD}
 *     │   ├── index.p
 *     │   └── users.p
 *     └── api/
 *         ├── handler.p
 *         └── v2/
 *             └── handler.p
 */
public class x2_UseNavigationTestHard extends Parser3TestCase {

	// Базовые пути
	private static final String BASE = "var/www/domains/domain.ru";
	private static final String DOC_ROOT = BASE + "/files/public_html/www";
	private static final String PARSER_CLASSES = BASE + "/parser_classes";
	private static final String PARSER_CLASSES_OLD = BASE + "/parser_classes_OLD";
	private static final String CGI = BASE + "/cgi";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		createTestStructure();
		configureProjectSettings();
	}

	/**
	 * Создаёт структуру тестовых файлов.
	 */
	private void createTestStructure() {
		// === cgi/auto.p - основной auto.p с CLASS_PATH ===
		createParser3FileInDir(CGI + "/auto.p",
				"@CLASS\nauto\n# реальный минимальный кейс main auto с CLASS_PATH\n\n@auto[]\n$CLASS_PATH[^table::create{path\n/../../../parser_classes//\n}]");

		// === parser_classes/ - основные классы ===
		createParser3FileInDir(PARSER_CLASSES + "/utils.p",
				"/" + PARSER_CLASSES + "/utils.p");
		createParser3FileInDir(PARSER_CLASSES + "/database.p",
				"/" + PARSER_CLASSES + "/database.p");
		createParser3FileInDir(PARSER_CLASSES + "/common.p",
				"/" + PARSER_CLASSES + "/common.p");
		createParser3FileInDir(PARSER_CLASSES + "/inner_classes/helper.p",
				"/" + PARSER_CLASSES + "/inner_classes/helper.p");
		createParser3FileInDir(PARSER_CLASSES + "/inner_classes/validator.p",
				"/" + PARSER_CLASSES + "/inner_classes/validator.p");

		// === parser_classes_OLD/ - старые классы ===
		createParser3FileInDir(PARSER_CLASSES_OLD + "/utils.p",
				"/" + PARSER_CLASSES_OLD + "/utils.p");
		createParser3FileInDir(PARSER_CLASSES_OLD + "/legacy.p",
				"/" + PARSER_CLASSES_OLD + "/legacy.p");
		createParser3FileInDir(PARSER_CLASSES_OLD + "/inner_classes/helper.p",
				"/" + PARSER_CLASSES_OLD + "/inner_classes/helper.p");
		createParser3FileInDir(PARSER_CLASSES_OLD + "/inner_classes/old_validator.p",
				"/" + PARSER_CLASSES_OLD + "/inner_classes/old_validator.p");

		// === document_root (files/public_html/www/) ===
		createParser3FileInDir(DOC_ROOT + "/index.p",
				"/" + DOC_ROOT + "/index.p");
		createParser3FileInDir(DOC_ROOT + "/config.p",
				"/" + DOC_ROOT + "/config.p");

		// Локальные inner_classes (конфликт с parser_classes!)
		createParser3FileInDir(DOC_ROOT + "/inner_classes/helper.p",
				"/" + DOC_ROOT + "/inner_classes/helper.p");
		createParser3FileInDir(DOC_ROOT + "/inner_classes/local.p",
				"/" + DOC_ROOT + "/inner_classes/local.p");

		// admin/ - с расширенным CLASS_PATH
		createParser3FileInDir(DOC_ROOT + "/admin/auto.p",
				"@auto[]\n^CLASS_PATH.append{/../../../parser_classes_OLD//}");
		createParser3FileInDir(DOC_ROOT + "/admin/index.p",
				"/" + DOC_ROOT + "/admin/index.p");
		createParser3FileInDir(DOC_ROOT + "/admin/users.p",
				"/" + DOC_ROOT + "/admin/users.p");

		// api/
		createParser3FileInDir(DOC_ROOT + "/api/handler.p",
				"/" + DOC_ROOT + "/api/handler.p");
		createParser3FileInDir(DOC_ROOT + "/api/v2/handler.p",
				"/" + DOC_ROOT + "/api/v2/handler.p");
	}

	/**
	 * Настраивает проект: document_root и main_auto.
	 */
	private void configureProjectSettings() {
		Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(getProject());

		// document_root = files/public_html/www/
		VirtualFile docRoot = myFixture.findFileInTempDir(DOC_ROOT);
		if (docRoot != null) {
			settings.setDocumentRoot(docRoot);
		}

		// main_auto = cgi/auto.p
		VirtualFile mainAuto = myFixture.findFileInTempDir(CGI + "/auto.p");
		if (mainAuto != null) {
			settings.setMainAuto(mainAuto);
		}
	}

	// ==================== FROM ROOT (document_root) ====================

	// --- CLASS_PATH navigation ---

	public void testFromRoot_ClassPath_Utils() {
		assertUseNavigation(DOC_ROOT, "utils.p",
				"/" + PARSER_CLASSES + "/utils.p");
	}

	public void testFromRoot_ClassPath_Database() {
		assertUseNavigation(DOC_ROOT, "database.p",
				"/" + PARSER_CLASSES + "/database.p");
	}

	public void testFromRoot_ClassPath_InnerHelper() {
		// inner_classes/helper.p - сначала ищет ЛОКАЛЬНО, потом в CLASS_PATH
		// Локальный www/inner_classes/helper.p существует, поэтому он и найдётся
		assertUseNavigation(DOC_ROOT, "inner_classes/helper.p",
				"/" + DOC_ROOT + "/inner_classes/helper.p");
	}

	public void testFromRoot_ClassPath_InnerValidator() {
		// inner_classes/validator.p - локально НЕТ, найдёт в CLASS_PATH
		assertUseNavigation(DOC_ROOT, "inner_classes/validator.p",
				"/" + PARSER_CLASSES + "/inner_classes/validator.p");
	}

	public void testFromAdmin_ClassPath_InnerHelper() {
		// Из admin/ нет локального inner_classes/, поэтому найдёт в CLASS_PATH
		assertUseNavigation(DOC_ROOT + "/admin", "inner_classes/helper.p",
				"/" + PARSER_CLASSES + "/inner_classes/helper.p");
	}

	// --- Relative from document_root ---

	public void testFromRoot_Relative_Config() {
		assertUseNavigation(DOC_ROOT, "config.p",
				"/" + DOC_ROOT + "/config.p");
	}

	public void testFromRoot_Relative_AdminIndex() {
		assertUseNavigation(DOC_ROOT, "admin/index.p",
				"/" + DOC_ROOT + "/admin/index.p");
	}

	public void testFromRoot_Relative_ApiHandler() {
		assertUseNavigation(DOC_ROOT, "api/handler.p",
				"/" + DOC_ROOT + "/api/handler.p");
	}

	// --- Absolute paths ---

	public void testFromRoot_Absolute_Index() {
		assertUseNavigation(DOC_ROOT, "/index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	public void testFromRoot_Absolute_AdminUsers() {
		assertUseNavigation(DOC_ROOT, "/admin/users.p",
				"/" + DOC_ROOT + "/admin/users.p");
	}

	public void testFromRoot_Absolute_ApiV2Handler() {
		assertUseNavigation(DOC_ROOT, "/api/v2/handler.p",
				"/" + DOC_ROOT + "/api/v2/handler.p");
	}

	// --- Local inner_classes via ./ (NOT from CLASS_PATH) ---

	public void testFromRoot_LocalInner_DotSlash() {
		assertUseNavigation(DOC_ROOT, "./inner_classes/helper.p",
				"/" + DOC_ROOT + "/inner_classes/helper.p");
	}

	public void testFromRoot_LocalInner_Absolute() {
		assertUseNavigation(DOC_ROOT, "/inner_classes/helper.p",
				"/" + DOC_ROOT + "/inner_classes/helper.p");
	}

	public void testFromRoot_LocalInner_Local() {
		assertUseNavigation(DOC_ROOT, "./inner_classes/local.p",
				"/" + DOC_ROOT + "/inner_classes/local.p");
	}

	// ==================== FROM ADMIN (with extended CLASS_PATH) ====================

	// В admin/ добавлен parser_classes_OLD в CLASS_PATH

	public void testFromAdmin_ClassPath_Utils() {
		// utils.p есть и в parser_classes и в parser_classes_OLD
		// Должен найти из parser_classes (первый в CLASS_PATH)
		assertUseNavigation(DOC_ROOT + "/admin", "utils.p",
				"/" + PARSER_CLASSES + "/utils.p");
	}

	public void testFromAdmin_ClassPath_Legacy() {
		// legacy.p есть только в parser_classes_OLD
		assertUseNavigation(DOC_ROOT + "/admin", "legacy.p",
				"/" + PARSER_CLASSES_OLD + "/legacy.p");
	}

	public void testFromAdmin_ClassPath_OldValidator() {
		// old_validator.p есть только в parser_classes_OLD
		assertUseNavigation(DOC_ROOT + "/admin", "inner_classes/old_validator.p",
				"/" + PARSER_CLASSES_OLD + "/inner_classes/old_validator.p");
	}

	public void testFromAdmin_Relative_Parent() {
		assertUseNavigation(DOC_ROOT + "/admin", "../index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	public void testFromAdmin_Relative_Users() {
		assertUseNavigation(DOC_ROOT + "/admin", "users.p",
				"/" + DOC_ROOT + "/admin/users.p");
	}

	public void testFromAdmin_Absolute_Index() {
		assertUseNavigation(DOC_ROOT + "/admin", "/index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	// ==================== FROM API/V2 (deep nesting) ====================

	public void testFromV2_ClassPath_Utils() {
		assertUseNavigation(DOC_ROOT + "/api/v2", "utils.p",
				"/" + PARSER_CLASSES + "/utils.p");
	}

	public void testFromV2_Relative_ParentHandler() {
		assertUseNavigation(DOC_ROOT + "/api/v2", "../handler.p",
				"/" + DOC_ROOT + "/api/handler.p");
	}

	public void testFromV2_Relative_TwoLevelsUp() {
		assertUseNavigation(DOC_ROOT + "/api/v2", "../../index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	public void testFromV2_Relative_CrossBranch() {
		assertUseNavigation(DOC_ROOT + "/api/v2", "../../admin/users.p",
				"/" + DOC_ROOT + "/admin/users.p");
	}

	public void testFromV2_Absolute_Index() {
		assertUseNavigation(DOC_ROOT + "/api/v2", "/index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	public void testFromV2_Absolute_ApiHandler() {
		assertUseNavigation(DOC_ROOT + "/api/v2", "/api/handler.p",
				"/" + DOC_ROOT + "/api/handler.p");
	}

	// ==================== EDGE CASES ====================

	public void testEdgeCase_DotSlashClassPath() {
		// ./utils.p - должен найтись в CLASS_PATH (как и обычный относительный путь)
		assertUseNavigation(DOC_ROOT, "./utils.p",
				"/" + PARSER_CLASSES + "/utils.p");
	}

	public void testEdgeCase_ParentNotInClassPath() {
		// ../utils.p - выходим из document_root, там нет utils.p
		// Но с fallback находим utils.p в CLASS_PATH!
		assertUseNavigation(DOC_ROOT, "../utils.p",
				"/" + PARSER_CLASSES + "/utils.p");
	}

	public void testEdgeCase_NormalizedPath() {
		assertUseNavigation(DOC_ROOT, "admin/../index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	public void testEdgeCase_ComplexNormalization() {
		assertUseNavigation(DOC_ROOT, "api/v2/../../admin/users.p",
				"/" + DOC_ROOT + "/admin/users.p");
	}

	public void testEdgeCase_ParentFromWww() {
		// ../www/index.p - выходим из www и возвращаемся
		assertUseNavigation(DOC_ROOT, "../www/index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	public void testEdgeCase_AbsoluteParentWww() {
		// /../www/index.p - абсолютный путь с выходом вверх
		assertUseNavigation(DOC_ROOT, "/../www/index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	// ==================== NOT FOUND ====================

	public void testNotFound_Simple() {
		assertUseNavigationNotFound(DOC_ROOT, "not_found.p");
	}

	public void testNotFound_ClassPath() {
		assertUseNavigationNotFound(DOC_ROOT, "nonexistent_class.p");
	}

	public void testNotFound_Absolute() {
		assertUseNavigationNotFound(DOC_ROOT, "/not_found.p");
	}

	public void testNotFound_InnerNotFound() {
		assertUseNavigationNotFound(DOC_ROOT, "inner_classes/not_found.p");
	}

	public void testNotFound_DeepRelative() {
		assertUseNavigationNotFound(DOC_ROOT + "/api/v2", "../../not_found.p");
	}

	// ==================== @USE TESTS ====================

	public void testAtUse_ClassPath_Utils() {
		assertAtUseNavigation(DOC_ROOT, "utils.p",
				"/" + PARSER_CLASSES + "/utils.p");
	}

	public void testAtUse_Absolute_Index() {
		assertAtUseNavigation(DOC_ROOT, "/index.p",
				"/" + DOC_ROOT + "/index.p");
	}

	public void testAtUse_FromAdmin_Legacy() {
		assertAtUseNavigation(DOC_ROOT + "/admin", "legacy.p",
				"/" + PARSER_CLASSES_OLD + "/legacy.p");
	}

	public void testAtUse_NotFound() {
		assertAtUseNavigationNotFound(DOC_ROOT, "not_found.p");
	}

	// ==================== FALLBACK TESTS: ../file.p -> file.p ====================
	// Если ../file.p не найден, но file.p есть в текущей директории — резолвится file.p

	public void testFallback_ParentNotExists_LocalExists() {
		// Создаём файл только в admin/, но НЕ в родительской www/
		createParser3FileInDir(DOC_ROOT + "/admin/fallback_admin.p",
				"/" + DOC_ROOT + "/admin/fallback_admin.p");
		// НЕ создаём DOC_ROOT + "/fallback_admin.p"

		// ^use[../fallback_admin.p] из admin/ -> должен найти admin/fallback_admin.p (fallback)
		assertUseNavigation(DOC_ROOT + "/admin", "../fallback_admin.p",
				"/" + DOC_ROOT + "/admin/fallback_admin.p");
	}

	public void testFallback_ParentExists_TakeParent() {
		// Создаём файл и в родительской и в текущей директории
		createParser3FileInDir(DOC_ROOT + "/parent_priority.p",
				"/" + DOC_ROOT + "/parent_priority.p");
		createParser3FileInDir(DOC_ROOT + "/admin/parent_priority.p",
				"/" + DOC_ROOT + "/admin/parent_priority.p");

		// ^use[../parent_priority.p] из admin/ -> должен найти www/parent_priority.p (приоритет у родителя)
		assertUseNavigation(DOC_ROOT + "/admin", "../parent_priority.p",
				"/" + DOC_ROOT + "/parent_priority.p");
	}

	public void testFallback_DeepNesting() {
		// Создаём файл только в api/v2/, но НЕ в родительских директориях
		createParser3FileInDir(DOC_ROOT + "/api/v2/deep_fallback.p",
				"/" + DOC_ROOT + "/api/v2/deep_fallback.p");

		// ^use[../../deep_fallback.p] из api/v2/ -> должен найти api/v2/deep_fallback.p (fallback)
		assertUseNavigation(DOC_ROOT + "/api/v2", "../../deep_fallback.p",
				"/" + DOC_ROOT + "/api/v2/deep_fallback.p");
	}

	public void testFallback_NotInClassPath() {
		// Проверяем что fallback работает независимо от CLASS_PATH
		// Файл должен быть найден в текущей директории, даже если его нет в CLASS_PATH
		createParser3FileInDir(DOC_ROOT + "/api/local_only.p",
				"/" + DOC_ROOT + "/api/local_only.p");

		assertUseNavigation(DOC_ROOT + "/api", "../local_only.p",
				"/" + DOC_ROOT + "/api/local_only.p");
	}

	// ==================== HELPER METHODS ====================

	/**
	 * Проверяет навигацию через ^use[путь].
	 */
	private void assertUseNavigation(String sourceDir, String usePath, String expectedContent) {
		String sourcePath = sourceDir + "/test_source.p";

		String sourceContent = "@main[]\n# реальный минимальный кейс навигации ^use[] с CLASS_PATH\n^use[" + usePath.substring(0, usePath.length()/2)
				+ "<caret>" + usePath.substring(usePath.length()/2) + "]";

		createParser3FileWithCaret(sourcePath, sourceContent);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				myFixture.getCaretOffset()
		);

		assertNotNull("Targets should not be null for ^use[" + usePath + "] from " + sourceDir, targets);
		assertTrue("Should have target for ^use[" + usePath + "] from " + sourceDir, targets.length > 0);

		PsiFile targetFile = (PsiFile) targets[0];
		assertEquals("Wrong target for ^use[" + usePath + "] from " + sourceDir,
				expectedContent, targetFile.getText().trim());
	}

	/**
	 * Проверяет навигацию через @USE.
	 */
	private void assertAtUseNavigation(String sourceDir, String usePath, String expectedContent) {
		String sourcePath = sourceDir + "/test_source.p";

		String sourceContent = "@USE\n" + usePath.substring(0, usePath.length()/2)
				+ "<caret>" + usePath.substring(usePath.length()/2)
				+ "\n@main[]\n# реальный минимальный кейс навигации @USE с CLASS_PATH";

		createParser3FileWithCaret(sourcePath, sourceContent);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				myFixture.getCaretOffset()
		);

		assertNotNull("Targets should not be null for @USE " + usePath + " from " + sourceDir, targets);
		assertTrue("Should have target for @USE " + usePath + " from " + sourceDir, targets.length > 0);

		PsiFile targetFile = (PsiFile) targets[0];
		assertEquals("Wrong target for @USE " + usePath + " from " + sourceDir,
				expectedContent, targetFile.getText().trim());
	}

	/**
	 * Проверяет что навигация НЕ находит файл.
	 */
	private void assertUseNavigationNotFound(String sourceDir, String usePath) {
		String sourcePath = sourceDir + "/test_source.p";

		String sourceContent = "@main[]\n# реальный минимальный negative-кейс навигации ^use[] с CLASS_PATH\n^use[" + usePath.substring(0, usePath.length()/2)
				+ "<caret>" + usePath.substring(usePath.length()/2) + "]";

		createParser3FileWithCaret(sourcePath, sourceContent);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				myFixture.getCaretOffset()
		);

		assertTrue("Should NOT have target for ^use[" + usePath + "] from " + sourceDir,
				targets == null || targets.length == 0);
	}

	/**
	 * Проверяет что @USE навигация НЕ находит файл.
	 */
	private void assertAtUseNavigationNotFound(String sourceDir, String usePath) {
		String sourcePath = sourceDir + "/test_source.p";

		String sourceContent = "@USE\n" + usePath.substring(0, usePath.length()/2)
				+ "<caret>" + usePath.substring(usePath.length()/2)
				+ "\n@main[]\n# реальный минимальный negative-кейс навигации @USE с CLASS_PATH";

		createParser3FileWithCaret(sourcePath, sourceContent);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				myFixture.getCaretOffset()
		);

		assertTrue("Should NOT have target for @USE " + usePath + " from " + sourceDir,
				targets == null || targets.length == 0);
	}
}
