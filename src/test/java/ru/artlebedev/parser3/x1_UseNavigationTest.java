package ru.artlebedev.parser3;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import ru.artlebedev.parser3.settings.Parser3ProjectSettings;

/**
 * Комплексный тест навигации по ^use[] и @USE директивам.
 *
 * Структура тестового проекта:
 * /
 * ├── root_target.p         -> содержит "/root_target.p"
 * ├── файл.p                -> содержит "/файл.p"
 * ├── dir1/
 * │   ├── target.p          -> содержит "/dir1/target.p"
 * │   └── sub1/
 * │       ├── target.p      -> содержит "/dir1/sub1/target.p"
 * │       └── deep1/
 * │           └── target.p  -> содержит "/dir1/sub1/deep1/target.p"
 * ├── dir2/
 * │   ├── target.p          -> содержит "/dir2/target.p"
 * │   └── sub2/
 * │       └── target.p      -> содержит "/dir2/sub2/target.p"
 * ├── папка/
 * │   ├── файл.p            -> содержит "/папка/файл.p"
 * │   └── вложенная/
 * │       └── глубокий.p    -> содержит "/папка/вложенная/глубокий.p"
 * └── каталог/
 *     └── модуль.p          -> содержит "/каталог/модуль.p"
 */
public class x1_UseNavigationTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		createTestStructure();

		// Устанавливаем document_root на директорию где создаются тестовые файлы
		com.intellij.openapi.vfs.VirtualFile rootTarget = myFixture.findFileInTempDir("root_target.p");
		if (rootTarget != null && rootTarget.getParent() != null) {
			Parser3ProjectSettings settings = Parser3ProjectSettings.getInstance(getProject());
			settings.setDocumentRoot(rootTarget.getParent());
		}
	}

	/**
	 * Создаёт структуру тестовых файлов.
	 * Каждый целевой файл содержит свой абсолютный путь.
	 */
	private void createTestStructure() {
		// Корневые файлы
		createParser3FileInDir("root_target.p", "/root_target.p");
		createParser3FileInDir("файл.p", "/файл.p");

		// dir1/
		createParser3FileInDir("dir1/target.p", "/dir1/target.p");
		createParser3FileInDir("dir1/sub1/target.p", "/dir1/sub1/target.p");
		createParser3FileInDir("dir1/sub1/deep1/target.p", "/dir1/sub1/deep1/target.p");

		// dir2/
		createParser3FileInDir("dir2/target.p", "/dir2/target.p");
		createParser3FileInDir("dir2/sub2/target.p", "/dir2/sub2/target.p");

		// Кириллические директории
		createParser3FileInDir("папка/файл.p", "/папка/файл.p");
		createParser3FileInDir("папка/вложенная/глубокий.p", "/папка/вложенная/глубокий.p");
		createParser3FileInDir("каталог/модуль.p", "/каталог/модуль.p");
	}

	// ==================== HELPER METHODS ====================

	/**
	 * Проверяет навигацию через ^use[путь].
	 * @param sourceDir директория исходного файла (например "dir1" или "dir1/sub1")
	 * @param usePath путь в ^use[...] (например "../target.p")
	 * @param expectedContent ожидаемое содержимое целевого файла (например "/dir1/target.p")
	 */
	private void assertUseNavigation(String sourceDir, String usePath, String expectedContent) {
		String sourcePath = sourceDir.isEmpty()
				? "test_source.p"
				: sourceDir + "/test_source.p";

		String sourceContent = "@main[]\n# реальный минимальный кейс навигации ^use[]\n^use[" + usePath.substring(0, usePath.length()/2)
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
	 * Проверяет навигацию через @USE путь.
	 */
	private void assertAtUseNavigation(String sourceDir, String usePath, String expectedContent) {
		String sourcePath = sourceDir.isEmpty()
				? "test_source.p"
				: sourceDir + "/test_source.p";

		// Ставим каретку примерно в середину пути
		String sourceContent = "@USE\n" + usePath.substring(0, usePath.length()/2)
				+ "<caret>" + usePath.substring(usePath.length()/2)
				+ "\n@main[]\n# реальный минимальный кейс навигации @USE";

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

	// ==================== TESTS FROM ROOT ====================

	public void testFromRoot_RelativeSimple() {
		assertUseNavigation("", "root_target.p", "/root_target.p");
	}

	public void testFromRoot_RelativeSubdir() {
		assertUseNavigation("", "dir1/target.p", "/dir1/target.p");
	}

	public void testFromRoot_RelativeDeep() {
		assertUseNavigation("", "dir1/sub1/target.p", "/dir1/sub1/target.p");
	}

	public void testFromRoot_RelativeVeryDeep() {
		assertUseNavigation("", "dir1/sub1/deep1/target.p", "/dir1/sub1/deep1/target.p");
	}

	// TODO: fix document_root in tests
	public void testFromRoot_AbsoluteSimple() {
		assertUseNavigation("", "/root_target.p", "/root_target.p");
	}

	public void testFromRoot_AbsoluteSubdir() {
		assertUseNavigation("", "/dir1/target.p", "/dir1/target.p");
	}

	public void testFromRoot_AbsoluteDeep() {
		assertUseNavigation("", "/dir1/sub1/deep1/target.p", "/dir1/sub1/deep1/target.p");
	}

	public void testFromRoot_CyrillicSimple() {
		assertUseNavigation("", "файл.p", "/файл.p");
	}

	public void testFromRoot_CyrillicSubdir() {
		assertUseNavigation("", "папка/файл.p", "/папка/файл.p");
	}

	public void testFromRoot_CyrillicDeep() {
		assertUseNavigation("", "папка/вложенная/глубокий.p", "/папка/вложенная/глубокий.p");
	}

	public void testFromRoot_CyrillicAbsolute() {
		assertUseNavigation("", "/каталог/модуль.p", "/каталог/модуль.p");
	}

	// ==================== TESTS FROM dir1 ====================

	public void testFromDir1_RelativeSimple() {
		assertUseNavigation("dir1", "target.p", "/dir1/target.p");
	}

	public void testFromDir1_RelativeSubdir() {
		assertUseNavigation("dir1", "sub1/target.p", "/dir1/sub1/target.p");
	}

	public void testFromDir1_RelativeDeep() {
		assertUseNavigation("dir1", "sub1/deep1/target.p", "/dir1/sub1/deep1/target.p");
	}

	public void testFromDir1_ParentDir() {
		assertUseNavigation("dir1", "../root_target.p", "/root_target.p");
	}

	public void testFromDir1_ParentDirOtherBranch() {
		assertUseNavigation("dir1", "../dir2/target.p", "/dir2/target.p");
	}

	public void testFromDir1_ParentDirDeep() {
		assertUseNavigation("dir1", "../dir2/sub2/target.p", "/dir2/sub2/target.p");
	}

	public void testFromDir1_ParentCyrillic() {
		assertUseNavigation("dir1", "../файл.p", "/файл.p");
	}

	public void testFromDir1_ParentCyrillicSubdir() {
		assertUseNavigation("dir1", "../папка/файл.p", "/папка/файл.p");
	}

	public void testFromDir1_Absolute() {
		assertUseNavigation("dir1", "/root_target.p", "/root_target.p");
	}

	public void testFromDir1_AbsoluteOther() {
		assertUseNavigation("dir1", "/dir2/target.p", "/dir2/target.p");
	}

	// ==================== TESTS FROM dir1/sub1 ====================

	public void testFromSub1_RelativeSimple() {
		assertUseNavigation("dir1/sub1", "target.p", "/dir1/sub1/target.p");
	}

	public void testFromSub1_RelativeDeep() {
		assertUseNavigation("dir1/sub1", "deep1/target.p", "/dir1/sub1/deep1/target.p");
	}

	public void testFromSub1_ParentOne() {
		assertUseNavigation("dir1/sub1", "../target.p", "/dir1/target.p");
	}

	public void testFromSub1_ParentTwo() {
		assertUseNavigation("dir1/sub1", "../../root_target.p", "/root_target.p");
	}

	public void testFromSub1_ParentTwoOtherBranch() {
		assertUseNavigation("dir1/sub1", "../../dir2/target.p", "/dir2/target.p");
	}

	public void testFromSub1_ParentTwoCyrillic() {
		assertUseNavigation("dir1/sub1", "../../файл.p", "/файл.p");
	}

	// ==================== TESTS FROM dir1/sub1/deep1 (deepest) ====================

	public void testFromDeep1_RelativeSimple() {
		assertUseNavigation("dir1/sub1/deep1", "target.p", "/dir1/sub1/deep1/target.p");
	}

	public void testFromDeep1_ParentOne() {
		assertUseNavigation("dir1/sub1/deep1", "../target.p", "/dir1/sub1/target.p");
	}

	public void testFromDeep1_ParentTwo() {
		assertUseNavigation("dir1/sub1/deep1", "../../target.p", "/dir1/target.p");
	}

	public void testFromDeep1_ParentThree() {
		assertUseNavigation("dir1/sub1/deep1", "../../../root_target.p", "/root_target.p");
	}

	public void testFromDeep1_ParentThreeOtherBranch() {
		assertUseNavigation("dir1/sub1/deep1", "../../../dir2/target.p", "/dir2/target.p");
	}

	public void testFromDeep1_ParentThreeDeep() {
		assertUseNavigation("dir1/sub1/deep1", "../../../dir2/sub2/target.p", "/dir2/sub2/target.p");
	}

	public void testFromDeep1_ParentThreeCyrillic() {
		assertUseNavigation("dir1/sub1/deep1", "../../../файл.p", "/файл.p");
	}

	public void testFromDeep1_ParentThreeCyrillicDeep() {
		assertUseNavigation("dir1/sub1/deep1", "../../../папка/вложенная/глубокий.p", "/папка/вложенная/глубокий.p");
	}

	public void testFromDeep1_Absolute() {
		assertUseNavigation("dir1/sub1/deep1", "/root_target.p", "/root_target.p");
	}

	public void testFromDeep1_AbsoluteCyrillic() {
		assertUseNavigation("dir1/sub1/deep1", "/каталог/модуль.p", "/каталог/модуль.p");
	}

	// ==================== TESTS FROM dir2 (cross-branch) ====================

	public void testFromDir2_RelativeSimple() {
		assertUseNavigation("dir2", "target.p", "/dir2/target.p");
	}

	public void testFromDir2_ParentToOtherBranch() {
		assertUseNavigation("dir2", "../dir1/target.p", "/dir1/target.p");
	}

	public void testFromDir2_ParentToOtherBranchDeep() {
		assertUseNavigation("dir2", "../dir1/sub1/deep1/target.p", "/dir1/sub1/deep1/target.p");
	}

	// ==================== TESTS FROM CYRILLIC DIRECTORIES ====================

	public void testFromПапка_RelativeSimple() {
		assertUseNavigation("папка", "файл.p", "/папка/файл.p");
	}

	public void testFromПапка_RelativeDeep() {
		assertUseNavigation("папка", "вложенная/глубокий.p", "/папка/вложенная/глубокий.p");
	}

	public void testFromПапка_ParentToRoot() {
		assertUseNavigation("папка", "../root_target.p", "/root_target.p");
	}

	public void testFromПапка_ParentToLatin() {
		assertUseNavigation("папка", "../dir1/target.p", "/dir1/target.p");
	}

	public void testFromПапка_ParentToCyrillic() {
		assertUseNavigation("папка", "../каталог/модуль.p", "/каталог/модуль.p");
	}

	public void testFromВложенная_ParentOne() {
		assertUseNavigation("папка/вложенная", "../файл.p", "/папка/файл.p");
	}

	public void testFromВложенная_ParentTwo() {
		assertUseNavigation("папка/вложенная", "../../root_target.p", "/root_target.p");
	}

	public void testFromВложенная_ParentTwoToLatin() {
		assertUseNavigation("папка/вложенная", "../../dir1/target.p", "/dir1/target.p");
	}

	// ==================== @USE TESTS ====================

	public void testAtUse_FromRoot_Simple() {
		assertAtUseNavigation("", "root_target.p", "/root_target.p");
	}

	public void testAtUse_FromRoot_Subdir() {
		assertAtUseNavigation("", "dir1/target.p", "/dir1/target.p");
	}

	public void testAtUse_FromRoot_Absolute() {
		assertAtUseNavigation("", "/dir1/sub1/target.p", "/dir1/sub1/target.p");
	}

	public void testAtUse_FromRoot_Cyrillic() {
		assertAtUseNavigation("", "папка/файл.p", "/папка/файл.p");
	}

	public void testAtUse_FromDir1_Parent() {
		assertAtUseNavigation("dir1", "../root_target.p", "/root_target.p");
	}

	public void testAtUse_FromDeep1_ParentThree() {
		assertAtUseNavigation("dir1/sub1/deep1", "../../../root_target.p", "/root_target.p");
	}

	public void testAtUse_FromПапка_Parent() {
		assertAtUseNavigation("папка", "../файл.p", "/файл.p");
	}

	// ==================== EDGE CASES ====================

	public void testEdgeCase_DotSlash() {
		assertUseNavigation("", "./root_target.p", "/root_target.p");
	}

	public void testEdgeCase_DotSlashSubdir() {
		assertUseNavigation("", "./dir1/target.p", "/dir1/target.p");
	}

	public void testEdgeCase_NormalizedPath() {
		assertUseNavigation("", "dir1/../root_target.p", "/root_target.p");
	}

	public void testEdgeCase_NormalizedPathDeep() {
		assertUseNavigation("", "dir1/sub1/../../root_target.p", "/root_target.p");
	}

	public void testEdgeCase_DotInMiddle() {
		assertUseNavigation("", "dir1/./target.p", "/dir1/target.p");
	}

	public void testEdgeCase_ComplexNormalization() {
		assertUseNavigation("", "dir1/sub1/deep1/../../../dir2/sub2/target.p", "/dir2/sub2/target.p");
	}

	public void testEdgeCase_CyrillicNormalization() {
		assertUseNavigation("", "папка/вложенная/../../каталог/модуль.p", "/каталог/модуль.p");
	}

	// ==================== NOT FOUND TESTS ====================

	/**
	 * Проверяет что навигация НЕ находит несуществующий файл.
	 */
	private void assertUseNavigationNotFound(String sourceDir, String usePath) {
		String sourcePath = sourceDir.isEmpty()
				? "test_source.p"
				: sourceDir + "/test_source.p";

		String sourceContent = "@main[]\n# реальный минимальный negative-кейс навигации ^use[]\n^use[" + usePath.substring(0, usePath.length()/2)
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
	 * Проверяет что @USE навигация НЕ находит несуществующий файл.
	 */
	private void assertAtUseNavigationNotFound(String sourceDir, String usePath) {
		String sourcePath = sourceDir.isEmpty()
				? "test_source.p"
				: sourceDir + "/test_source.p";

		String sourceContent = "@USE\n" + usePath.substring(0, usePath.length()/2)
				+ "<caret>" + usePath.substring(usePath.length()/2)
				+ "\n@main[]\n# реальный минимальный negative-кейс навигации @USE";

		createParser3FileWithCaret(sourcePath, sourceContent);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				myFixture.getCaretOffset()
		);

		assertTrue("Should NOT have target for @USE " + usePath + " from " + sourceDir,
				targets == null || targets.length == 0);
	}

	public void testNotFound_Simple() {
		assertUseNavigationNotFound("", "not_found.p");
	}

	public void testNotFound_DotSlash() {
		assertUseNavigationNotFound("", "./not_found.p");
	}

	public void testNotFound_Parent() {
		assertUseNavigationNotFound("dir1", "../not_found.p");
	}

	public void testNotFound_Absolute() {
		assertUseNavigationNotFound("", "/not_found.p");
	}

	public void testNotFound_AbsoluteParent() {
		assertUseNavigationNotFound("", "/../not_found.p");
	}

	public void testNotFound_RelativeSubdir() {
		assertUseNavigationNotFound("", "../test/not_found.p");
	}

	public void testNotFound_DeepRelative() {
		assertUseNavigationNotFound("dir1/sub1", "../../not_found.p");
	}

	public void testNotFound_AbsoluteDeep() {
		assertUseNavigationNotFound("", "/dir1/sub1/not_found.p");
	}

	public void testNotFound_Cyrillic() {
		assertUseNavigationNotFound("", "несуществующий.p");
	}

	public void testNotFound_CyrillicPath() {
		assertUseNavigationNotFound("", "папка/несуществующий.p");
	}

	public void testNotFound_FromDeep() {
		assertUseNavigationNotFound("dir1/sub1/deep1", "../../../not_found.p");
	}

	public void testAtUse_NotFound_Simple() {
		assertAtUseNavigationNotFound("", "not_found.p");
	}

	public void testAtUse_NotFound_Absolute() {
		assertAtUseNavigationNotFound("", "/not_found.p");
	}

	public void testAtUse_NotFound_Parent() {
		assertAtUseNavigationNotFound("dir1", "../not_found.p");
	}

	// ==================== FALLBACK TESTS: ../file.p -> file.p ====================
	// Если ../file.p не найден, но file.p есть в текущей директории — резолвится file.p

	/**
	 * Структура для тестов fallback:
	 * www/
	 * ├── file.p           <- есть в родительской директории
	 * └── inner/
	 *     ├── auto.p       <- ^use[../file.p] -> www/file.p (обычный случай)
	 *     └── file.p       <- есть в текущей директории
	 *
	 * Если убрать www/file.p:
	 * www/
	 * └── inner/
	 *     ├── auto.p       <- ^use[../file.p] -> www/inner/file.p (fallback!)
	 *     └── file.p
	 */

	public void testFallback_ParentExists() {
		// Структура: www/file.p существует, www/inner/file.p тоже
		// ^use[../file.p] из www/inner/ должен найти www/file.p (приоритет у родителя)
		createParser3FileInDir("www/file.p", "/www/file.p");
		createParser3FileInDir("www/inner/file.p", "/www/inner/file.p");

		assertUseNavigation("www/inner", "../file.p", "/www/file.p");
	}

	public void testFallback_ParentNotExists_LocalExists() {
		// Структура: www/fallback_local.p НЕ существует, www/inner/fallback_local.p существует
		// ^use[../fallback_local.p] из www/inner/ должен найти www/inner/fallback_local.p (fallback)
		createParser3FileInDir("www/inner/fallback_local.p", "/www/inner/fallback_local.p");
		// НЕ создаём www/fallback_local.p !

		assertUseNavigation("www/inner", "../fallback_local.p", "/www/inner/fallback_local.p");
	}

	public void testFallback_ParentNotExists_LocalNotExists() {
		// Структура: ни www/missing.p ни www/inner/missing.p НЕ существуют
		// ^use[../missing.p] из www/inner/ НЕ должен резолвиться
		// (www/inner/ уже есть из предыдущих тестов)

		assertUseNavigationNotFound("www/inner", "../missing.p");
	}

	public void testFallback_DeepNesting_ParentExists() {
		// www/deep/nested/file.p, www/deep/file.p, www/file.p
		// ^use[../../file.p] из www/deep/nested/ -> www/file.p
		createParser3FileInDir("www/deep/nested/local.p", "/www/deep/nested/local.p");
		createParser3FileInDir("www/deep/file.p", "/www/deep/file.p");
		createParser3FileInDir("www/file.p", "/www/file.p");

		assertUseNavigation("www/deep/nested", "../../file.p", "/www/file.p");
	}

	public void testFallback_DeepNesting_FallbackToLocal() {
		// www/deep/nested/fallback_deep.p существует
		// www/fallback_deep.p НЕ существует
		// ^use[../../fallback_deep.p] из www/deep/nested/ -> www/deep/nested/fallback_deep.p
		createParser3FileInDir("www/deep/nested/fallback_deep.p", "/www/deep/nested/fallback_deep.p");

		assertUseNavigation("www/deep/nested", "../../fallback_deep.p", "/www/deep/nested/fallback_deep.p");
	}

	public void testFallback_AtUse_ParentNotExists() {
		// То же самое через @USE
		createParser3FileInDir("www/inner/at_use_fallback.p", "/www/inner/at_use_fallback.p");

		assertAtUseNavigation("www/inner", "../at_use_fallback.p", "/www/inner/at_use_fallback.p");
	}
}
