package ru.artlebedev.parser3;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

/**
 * Тест навигации по методам: вызовы и объявления.
 *
 * Тестирует:
 * 1. Клик на вызове метода -> переход к объявлению @method[]
 * 2. Клик на объявлении @method[] -> показ всех вызовов
 * 3. Клик на имени класса в @CLASS -> показ наследников и использований
 * 4. Клик на имени класса в @BASE -> переход к родительскому классу
 *
 * Структура тестового проекта:
 * /
 * ├── auto.p              -> ^use[file.p]
 * ├── file.p              -> @method1[], @method2[], @helper[]
 * └── www/
 *     ├── test.p          -> вызовы методов, классы TestClass, NoBaseClass
 *     └── classes/
 *         ├── User.p      -> @CLASS User, @create[], @init[], @validate[], @save[], @getName[], @helper[]
 *         └── Admin.p     -> @CLASS Admin, @BASE User, @create[], @ban[], @checkPermission[], @helper[]
 */
public class x4_MethodNavigationTest extends Parser3TestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		createTestStructure();

		// Коммитим все документы
		com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

		// Ждём пока индексация завершится
		com.intellij.openapi.project.DumbService dumbService =
				com.intellij.openapi.project.DumbService.getInstance(getProject());
		dumbService.waitForSmartMode();
	}

	/**
	 * Создаёт структуру тестовых файлов.
	 */
	private void createTestStructure() {
		// auto.p в корне
		createParser3FileInDir("auto.p",
				"# auto.p\n" +
						"^use[file.p]\n"
		);

		// www/auto.p - подключает классы
		createParser3FileInDir("www/auto.p",
				"# www/auto.p\n" +
						"# реальный минимальный кейс Parser3 fixture\n@USE\n" +
						"classes/User.p\n" +
						"classes/Admin.p\n"
		);

		// file.p - методы MAIN (включая @GET_*)
		createParser3FileInDir("file.p",
				"# file.p\n" +
						"\n" +
						"@method1[]\n" +
						"$result[method1]\n" +
						"\n" +
						"@method2[]\n" +
						"$result[method2]\n" +
						"\n" +
						"@helper[]\n" +
						"$result[helper]\n" +
						"\n" +
						"####################\n" +
						"# getter\n" +
						"####################\n" +
						"@GET_main_xxx[]\n" +
						"$result[value]\n"
		);

		// www/classes/User.p (включая @GET_name)
		createParser3FileInDir("www/classes/User.p",
				"# www/classes/User.p\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"User\n" +
						"\n" +
						"@create[name;email]\n" +
						"$self.name[$name]\n" +
						"$self.email[$email]\n" +
						"^self.init[]\n" +
						"\n" +
						"@init[]\n" +
						"$self.created[^date::now[]]\n" +
						"\n" +
						"@validate[data]\n" +
						"^if(!$data){\n" +
						"    ^throw[error;Data required]\n" +
						"}\n" +
						"\n" +
						"@save[]\n" +
						"^self.validate[$self.email]\n" +
						"^MAIN:method1[]\n" +
						"\n" +
						"####################\n" +
						"# возвращает имя пользователя\n" +
						"####################\n" +
						"@GET_name[]\n" +
						"$result[$self._name]\n" +
						"\n" +
						"@helper[]\n" +
						"$result[helper from User]\n"
		);

		// www/classes/Admin.p (с вызовами через @BASE и своим @GET_name)
		createParser3FileInDir("www/classes/Admin.p",
				"# www/classes/Admin.p\n" +
						"\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Admin\n" +
						"\n" +
						"@BASE\n" +
						"User\n" +
						"\n" +
						"@create[name;email;role]\n" +
						"^BASE:create[$name;$email]\n" +
						"$self.role[$role]\n" +
						"^self.init[]\n" +
						"\n" +
						"@ban[user_id]\n" +
						"^self.checkPermission[ban]\n" +
						"^MAIN:method2[]\n" +
						"\n" +
						"@checkPermission[action]\n" +
						"^throw[access;No permission]\n" +
						"\n" +
						"####################\n" +
						"# возвращает имя админа\n" +
						"####################\n" +
						"@GET_name[]\n" +
						"$result[Admin: $self._name]\n" +
						"\n" +
						"@testAdminSelf[]\n" +
						"# self.name в Admin должен вести к Admin.@GET_name\n" +
						"^self.name[]\n" +
						"# BASE:name в Admin должен вести к User.@GET_name\n" +
						"^BASE:name[]\n" +
						"\n" +
						"@helper[]\n" +
						"^BASE:helper[]\n" +
						"$result[helper from Admin]\n"
		);

		// www/test.p - основной тестовый файл (с вызовами @GET_* методов)
		createParser3FileInDir("www/test.p",
				"# www/test.p\n" +
						"\n" +
						"# Простые вызовы MAIN\n" +
						"^method1[]\n" +
						"^method1()\n" +
						"^method1{}\n" +
						"\n" +
						"# Вызовы через MAIN:\n" +
						"^MAIN:method1[]\n" +
						"^MAIN:method2[]\n" +
						"^MAIN:helper[]\n" +
						"\n" +
						"# Вызовы GET_main_xxx\n" +
						"^main_xxx[]\n" +
						"^self.main_xxx[]\n" +
						"^MAIN:main_xxx[]\n" +
						"\n" +
						"# Конструкторы User\n" +
						"$u1[^User::create[Name;email]]\n" +
						"$u2[^User::create(Name;email)]\n" +
						"$u3[^User::create{Name;email}]\n" +
						"\n" +
						"# Вызовы GET_name как User:name\n" +
						"^User:name[]\n" +
						"\n" +
						"# Статические методы User\n" +
						"^User:validate[data]\n" +
						"^User:validate(data)\n" +
						"^User:validate{data}\n" +
						"\n" +
						"# Конструкторы Admin\n" +
						"$a1[^Admin::create[Name;email;role]]\n" +
						"$a2[^Admin::create(Name;email;role)]\n" +
						"$a3[^Admin::create{Name;email;role}]\n" +
						"\n" +
						"# Статические методы Admin\n" +
						"^Admin:ban[1]\n" +
						"^Admin:ban(2)\n" +
						"^Admin:ban{3}\n" +
						"\n" +
						"# self в MAIN\n" +
						"^self.method1[]\n" +
						"\n" +
						"# Класс TestClass\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"TestClass\n" +
						"\n" +
						"@BASE\n" +
						"User\n" +
						"\n" +
						"@create[name;email]\n" +
						"^BASE:create[$name;$email]\n" +
						"^self.init[]\n" +
						"\n" +
						"@localMethod[]\n" +
						"^self.validate[data]\n" +
						"^self.save[]\n" +
						"^self.localHelper[]\n" +
						"# Вызов @GET_name через self\n" +
						"^self.name[]\n" +
						"# Вызов @GET_name через BASE\n" +
						"^BASE:name[]\n" +
						"# Вызов @GET_name без префикса\n" +
						"^name[]\n" +
						"\n" +
						"@testSelf[]\n" +
						"^self.getName[]\n" +
						"\n" +
						"@localHelper[]\n" +
						"$result[local helper]\n" +
						"\n" +
						"# Класс NoBaseClass - без @BASE\n" +
						"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"NoBaseClass\n" +
						"\n" +
						"@process[]\n" +
						"^self.validate[]\n" +
						"\n" +
						"@validate[]\n" +
						"$valid(true)\n"
		);

		// www/vartest.p — тесты навигации по переменным с типами
		createParser3FileInDir("www/vartest.p",
				"# www/vartest.p\n" +
						"\n" +
						"# === Присваивание $var ===\n" +
						"$v1[^User::create[Name;email]]\n" +
						"^v1.validate[]\n" +
						"^self.v1.validate[]\n" +
						"^MAIN:v1.validate[]\n" +
						"\n" +
						"# === Присваивание $self.var ===\n" +
						"$self.v2[^User::create[Name;email]]\n" +
						"^v2.validate[]\n" +
						"^self.v2.validate[]\n" +
						"^MAIN:v2.validate[]\n" +
						"\n" +
						"# === Присваивание $MAIN:var ===\n" +
						"$MAIN:v3[^User::create[Name;email]]\n" +
						"^v3.validate[]\n" +
						"^self.v3.validate[]\n" +
						"^MAIN:v3.validate[]\n" +
						"\n" +
						"# === Навигация к переменным ===\n" +
						"$vn[^User::create[Name;email]]\n" +
						"^vn.validate[]\n" +
						"$self.vsn[^User::create[Name;email]]\n" +
						"^self.vsn.validate[]\n" +
						"$MAIN:vmn[^User::create[Name;email]]\n" +
						"^MAIN:vmn.validate[]\n" +
						"\n" +
						"# === Нет навигации по ^self/^MAIN/^BASE ===\n" +
						"^self.method1[]\n" +
						"^MAIN:method1[]\n"
		);

		createParser3FileInDir("reverse-var/auto.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^use[Stat.p]\n" +
						"$varStat[^Stat::create[]]\n" +
						"$info[^varStat.get_info[]]\n"
		);

		createParser3FileInDir("reverse-var/Stat.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"Stat\n" +
						"\n" +
						"@create[]\n" +
						"$result[^Stat::new[]]\n" +
						"\n" +
						"@get_info[]\n" +
						"$result[ok]\n"
		);

		createParser3FileInDir("parser182-array-nav/page.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"^use[ArrayUser.p]\n" +
						"^array:is-user[]\n"
		);

		createParser3FileInDir("parser182-array-nav/ArrayUser.p",
				"# реальный минимальный кейс Parser3 fixture\n@CLASS\n" +
						"array\n" +
						"\n" +
						"@is-user[]\n" +
						"$result[yes]\n"
		);
	}

	// ==================== HELPER METHODS ====================

	/**
	 * Проверяет навигацию от вызова к объявлению метода.
	 * @param sourceFile файл с вызовом
	 * @param callPattern паттерн вызова (каретка ставится на имя метода)
	 * @param expectedFile ожидаемый файл с объявлением
	 * @param expectedMethodName ожидаемое имя метода в объявлении
	 */
	private void assertCallToDeclaration(String sourceFile, String callPattern,
										 String expectedFile, String expectedMethodName) {
		// Получаем содержимое файла
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("File not found: " + sourceFile, vFile);

		String content;
		try {
			content = new String(vFile.contentsToByteArray(), vFile.getCharset());
		} catch (Exception e) {
			fail("Cannot read file: " + sourceFile);
			return;
		}

		int caretPos = content.indexOf(callPattern);
		assertTrue("Pattern '" + callPattern + "' not found in " + sourceFile, caretPos >= 0);

		// Находим позицию имени метода (после ^ и возможных префиксов)
		// Паттерны: ^method[], ^MAIN:method[], ^Class::method[], ^Class:method[], ^self.method[], ^BASE:method[]
		int methodNameStart = caretPos + 1; // после ^
		String patternContent = callPattern.substring(1); // без ^

		if (patternContent.startsWith("MAIN:")) {
			methodNameStart += 5;
		} else if (patternContent.startsWith("BASE:")) {
			methodNameStart += 5;
		} else if (patternContent.startsWith("self.")) {
			methodNameStart += 5;
		} else if (patternContent.contains("::")) {
			methodNameStart += patternContent.indexOf("::") + 2;
		} else if (patternContent.contains(":")) {
			methodNameStart += patternContent.indexOf(":") + 1;
		}

		// Ставим каретку на начало имени метода + 2 символа (внутри имени)
		int offset = methodNameStart + 2;

		// Открываем файл и ставим каретку
		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(offset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				offset
		);

		assertNotNull("Targets should not be null for " + callPattern, targets);
		assertTrue("Should have target for " + callPattern, targets.length > 0);

		// Проверяем что попали в нужный файл
		PsiFile targetFile = targets[0].getContainingFile();
		assertNotNull("Target file should not be null", targetFile);
		assertTrue("Should navigate to " + expectedFile + ", got " + targetFile.getVirtualFile().getPath(),
				targetFile.getVirtualFile().getPath().endsWith(expectedFile));
	}

	/**
	 * Проверяет количество вызовов метода при клике на объявлении.
	 * @param sourceFile файл с объявлением
	 * @param methodDecl объявление метода (@methodName)
	 * @param expectedCallCount ожидаемое количество вызовов
	 */
	private void assertDeclarationCallCount(String sourceFile, String methodDecl, int expectedCallCount) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("File not found: " + sourceFile, vFile);

		String content;
		try {
			content = new String(vFile.contentsToByteArray(), vFile.getCharset());
		} catch (Exception e) {
			fail("Cannot read file: " + sourceFile);
			return;
		}

		int caretPos = content.indexOf(methodDecl);
		assertTrue("Declaration '" + methodDecl + "' not found in " + sourceFile, caretPos >= 0);

		// Открываем файл и ставим каретку на имя метода (после @)
		myFixture.configureFromExistingVirtualFile(vFile);
		int offset = caretPos + 1; // после @
		myFixture.getEditor().getCaretModel().moveToOffset(offset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				offset
		);

		int actualCount = targets == null ? 0 : targets.length;
		assertEquals("Call count for " + methodDecl + " in " + sourceFile,
				expectedCallCount, actualCount);
	}

	/**
	 * Получает содержимое файла.
	 */
	private String getFileContent(String path) {
		com.intellij.openapi.vfs.VirtualFile file = myFixture.findFileInTempDir(path);
		assertNotNull("File not found: " + path, file);
		try {
			return new String(file.contentsToByteArray(), file.getCharset());
		} catch (Exception e) {
			fail("Cannot read file: " + path);
			return "";
		}
	}

	// ==================== TESTS: Call to Declaration ====================

	public void testSimpleCall_method1() {
		assertCallToDeclaration("www/test.p", "^method1[]", "file.p", "method1");
	}

	public void testSimpleCall_method1_round() {
		assertCallToDeclaration("www/test.p", "^method1()", "file.p", "method1");
	}

	public void testSimpleCall_method1_curly() {
		assertCallToDeclaration("www/test.p", "^method1{}", "file.p", "method1");
	}

	public void testMainCall_method1() {
		assertCallToDeclaration("www/test.p", "^MAIN:method1[]", "file.p", "method1");
	}

	public void testMainCall_method2() {
		assertCallToDeclaration("www/test.p", "^MAIN:method2[]", "file.p", "method2");
	}

	public void testMainCall_helper() {
		assertCallToDeclaration("www/test.p", "^MAIN:helper[]", "file.p", "helper");
	}

	public void testUserConstructor() {
		assertCallToDeclaration("www/test.p", "^User::create[Name;email]",
				"www/classes/User.p", "create");
	}

	public void testUserStaticMethod() {
		assertCallToDeclaration("www/test.p", "^User:validate[data]",
				"www/classes/User.p", "validate");
	}

	public void testParser182LowercaseClassHyphenMethod() {
		assertCallToDeclaration("parser182-array-nav/page.p", "^array:is-user[]",
				"parser182-array-nav/ArrayUser.p", "is-user");
	}

	public void testAdminConstructor() {
		assertCallToDeclaration("www/test.p", "^Admin::create[Name;email;role]",
				"www/classes/Admin.p", "create");
	}

	public void testAdminStaticMethod() {
		assertCallToDeclaration("www/test.p", "^Admin:ban[1]",
				"www/classes/Admin.p", "ban");
	}

	public void testReverseNavigation_ObjectMethodCall() {
		PsiFile statFile = myFixture.configureByFile("reverse-var/Stat.p");
		String text = statFile.getText();

		int methodPos = text.indexOf("@get_info[]");
		assertTrue("Должен найти @get_info[]", methodPos >= 0);

		myFixture.getEditor().getCaretModel().moveToOffset(methodPos + 1);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				methodPos + 1
		);

		assertNotNull("Должен найти обратную навигацию для @get_info[]", targets);
		assertTrue("Должен найти хотя бы один вызов @get_info[]", targets.length > 0);

		boolean foundAuto = false;
		for (PsiElement target : targets) {
			PsiFile targetFile = target.getContainingFile();
			if (targetFile != null && targetFile.getVirtualFile().getPath().endsWith("reverse-var/auto.p")) {
				foundAuto = true;
				break;
			}
		}
		assertTrue("Обратная навигация должна вести в reverse-var/auto.p", foundAuto);
	}

	public void testSelfInMain() {
		assertCallToDeclaration("www/test.p", "^self.method1[]", "file.p", "method1");
	}

	public void testBaseCall_inAdmin() {
		assertCallToDeclaration("www/classes/Admin.p", "^BASE:create[$name;$email]",
				"www/classes/User.p", "create");
	}

	public void testSelfCall_inheritedMethod() {
		// ^self.init[] в Admin должен вести к User.init[]
		assertCallToDeclaration("www/classes/Admin.p", "^self.init[]",
				"www/classes/User.p", "init");
	}

	public void testSelfCall_ownMethod() {
		// ^self.checkPermission[] в Admin должен вести к Admin.checkPermission[]
		assertCallToDeclaration("www/classes/Admin.p", "^self.checkPermission[ban]",
				"www/classes/Admin.p", "checkPermission");
	}

	// ==================== TESTS: @GET_* Methods ====================

	// --- MAIN @GET_main_xxx ---

	public void testGetMethod_main_simpleCall() {
		// ^main_xxx[] должен вести к @GET_main_xxx[] в file.p
		assertCallToDeclaration("www/test.p", "^main_xxx[]", "file.p", "GET_main_xxx");
	}

	public void testGetMethod_main_selfCall() {
		// ^self.main_xxx[] должен вести к @GET_main_xxx[] в file.p
		assertCallToDeclaration("www/test.p", "^self.main_xxx[]", "file.p", "GET_main_xxx");
	}

	public void testGetMethod_main_mainCall() {
		// ^MAIN:main_xxx[] должен вести к @GET_main_xxx[] в file.p
		assertCallToDeclaration("www/test.p", "^MAIN:main_xxx[]", "file.p", "GET_main_xxx");
	}

	// --- Class @GET_name: User ---

	public void testGetMethod_class_staticCall() {
		// ^User:name[] должен вести к @GET_name[] в User.p
		assertCallToDeclaration("www/test.p", "^User:name[]", "www/classes/User.p", "GET_name");
	}

	// --- Class @GET_name: TestClass (наследует User) ---

	public void testGetMethod_class_selfCall_inherited() {
		// ^self.name[] в TestClass должен вести к @GET_name[] в User.p (через @BASE)
		// TestClass -> User, у TestClass нет своего @GET_name
		assertCallToDeclaration("www/test.p", "^self.name[]", "www/classes/User.p", "GET_name");
	}

	public void testGetMethod_class_baseCall() {
		// ^BASE:name[] в TestClass должен вести к @GET_name[] в User.p
		assertCallToDeclaration("www/test.p", "^BASE:name[]", "www/classes/User.p", "GET_name");
	}

	public void testGetMethod_class_simpleCall() {
		// ^name[] в TestClass должен вести к @GET_name[] в User.p (унаследован)
		assertCallToDeclaration("www/test.p", "^name[]", "www/classes/User.p", "GET_name");
	}

	// --- Class @GET_name: Admin (наследует User, но переопределяет @GET_name) ---

	public void testGetMethod_class_selfCall_overridden() {
		// ^self.name[] в Admin должен вести к @GET_name[] в Admin.p (свой метод, не родительский)
		assertCallToDeclaration("www/classes/Admin.p", "^self.name[]", "www/classes/Admin.p", "GET_name");
	}

	public void testGetMethod_class_baseCall_fromAdmin() {
		// ^BASE:name[] в Admin должен вести к @GET_name[] в User.p (родительский)
		assertCallToDeclaration("www/classes/Admin.p", "^BASE:name[]", "www/classes/User.p", "GET_name");
	}

	// --- Navigation from @GET_* declaration to calls ---
	// Примечание: тесты на количество вызовов сложны из-за логики наследования
	// ^self.name[] в наследнике может считаться вызовом как своего метода, так и родительского

	public void testGetMethod_declarationToCallsMain() {
		// Клик на @GET_main_xxx должен показать вызовы ^main_xxx[]
		// В www/test.p есть 3 вызова: ^main_xxx[], ^self.main_xxx[], ^MAIN:main_xxx[]
		assertDeclarationCallCount("file.p", "@GET_main_xxx", 3);
	}

	// ==================== TESTS: @BASE Navigation ====================

	public void testBaseNavigation_Admin() {
		// Клик на User в @BASE User должен вести к @CLASS User
		// Используем файл Admin.p созданный в createTestStructure()

		// Читаем содержимое Admin.p
		com.intellij.openapi.vfs.VirtualFile adminFile = myFixture.findFileInTempDir("www/classes/Admin.p");
		assertNotNull("Admin.p not found", adminFile);

		String content;
		try {
			content = new String(adminFile.contentsToByteArray(), adminFile.getCharset());
		} catch (Exception e) {
			fail("Cannot read Admin.p");
			return;
		}

		// Находим позицию User после @BASE
		int basePos = content.indexOf("@BASE");
		assertTrue("@BASE not found in Admin.p", basePos >= 0);
		int userPos = content.indexOf("User", basePos);
		assertTrue("User not found after @BASE", userPos >= 0);

		// Ставим каретку внутри "User" (на 2-й символ)
		int offset = userPos + 1;

		// Открываем файл и ставим каретку
		myFixture.configureFromExistingVirtualFile(adminFile);
		myFixture.getEditor().getCaretModel().moveToOffset(offset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(),
				myFixture.getEditor(),
				offset
		);

		assertNotNull("Should navigate from @BASE User", targets);
		assertTrue("Should have target for @BASE User", targets.length > 0);

		PsiFile targetFile = targets[0].getContainingFile();
		assertTrue("Should navigate to User.p, got " + targetFile.getVirtualFile().getPath(),
				targetFile.getVirtualFile().getPath().endsWith("User.p"));
	}

	// ==================== TESTS: ^var.method[] Navigation ====================

	// --- Клик на method в ^var.method[] → @method в классе переменной ---

	// $var + ^var.method
	public void testVarMethod_var_var() {
		assertVarMethodNavigation("www/vartest.p", "^v1.validate[]", "validate", "www/classes/User.p");
	}

	// $var + ^self.var.method
	public void testVarMethod_var_selfVar() {
		assertVarMethodNavigation("www/vartest.p", "^self.v1.validate[]", "validate", "www/classes/User.p");
	}

	// $var + ^MAIN:var.method
	public void testVarMethod_var_mainVar() {
		assertVarMethodNavigation("www/vartest.p", "^MAIN:v1.validate[]", "validate", "www/classes/User.p");
	}

	// $self.var + ^var.method
	public void testVarMethod_selfVar_var() {
		assertVarMethodNavigation("www/vartest.p", "^v2.validate[]", "validate", "www/classes/User.p");
	}

	// $self.var + ^self.var.method
	public void testVarMethod_selfVar_selfVar() {
		assertVarMethodNavigation("www/vartest.p", "^self.v2.validate[]", "validate", "www/classes/User.p");
	}

	// $self.var + ^MAIN:var.method
	public void testVarMethod_selfVar_mainVar() {
		assertVarMethodNavigation("www/vartest.p", "^MAIN:v2.validate[]", "validate", "www/classes/User.p");
	}

	// $MAIN:var + ^var.method
	public void testVarMethod_mainVar_var() {
		assertVarMethodNavigation("www/vartest.p", "^v3.validate[]", "validate", "www/classes/User.p");
	}

	// $MAIN:var + ^self.var.method
	public void testVarMethod_mainVar_selfVar() {
		assertVarMethodNavigation("www/vartest.p", "^self.v3.validate[]", "validate", "www/classes/User.p");
	}

	// $MAIN:var + ^MAIN:var.method
	public void testVarMethod_mainVar_mainVar() {
		assertVarMethodNavigation("www/vartest.p", "^MAIN:v3.validate[]", "validate", "www/classes/User.p");
	}

	// --- Клик на var в ^var.method[] → $var[...] ---

	public void testVarNavigation_var() {
		assertVarDefinitionNavigation("www/vartest.p", "^vn.validate[]", "vn", "$vn[");
	}

	public void testVarNavigation_selfVar() {
		assertVarDefinitionNavigation("www/vartest.p", "^self.vsn.validate[]", "vsn", "$self.vsn[");
	}

	public void testVarNavigation_mainVar() {
		assertVarDefinitionNavigation("www/vartest.p", "^MAIN:vmn.validate[]", "vmn", "$MAIN:vmn[");
	}

	public void testHashKeyNavigation_assignmentDoesNotResolveToItself() {
		createParser3FileInDir("www/errors_3_real_hash_key_self_assignment_navigation.p",
				"@main[]\n# реальный минимальный кейс Parser3 fixture\n" +
						"$data[\n" +
						"\t$.x[\n" +
						"\t\t$.y[1]\n" +
						"\t]\n" +
						"]\n" +
						"\n" +
						"$data.x.y[12]\n" +
						"$data.x.y\n"
		);
		com.intellij.openapi.vfs.VirtualFile vFile =
				myFixture.findFileInTempDir("www/errors_3_real_hash_key_self_assignment_navigation.p");
		assertNotNull("Файл реального кейса должен быть создан", vFile);

		String content;
		try {
			content = new String(vFile.contentsToByteArray(), vFile.getCharset());
		} catch (Exception e) {
			fail("Не удалось прочитать файл реального кейса");
			return;
		}

		int assignmentPos = content.indexOf("$data.x.y[12]");
		assertTrue("Присваивание из реального кейса должно существовать", assignmentPos >= 0);
		int clickOffset = assignmentPos + "$data.x.".length();
		int expectedOffset = content.indexOf("$.y[1]") + "$.".length();
		assertTrue("Исходный ключ $.y[1] должен существовать", expectedOffset >= "$.".length());

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(clickOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), clickOffset);

		assertNotNull("Клик по y в $data.x.y[12] должен иметь цель навигации", targets);
		assertTrue("Клик по y в $data.x.y[12] должен иметь непустую цель навигации", targets.length > 0);
		assertEquals("Клик по y в присваивании не должен вести на самого себя",
				expectedOffset, targets[0].getTextOffset());
	}

	// --- Нет навигации по ^self/^MAIN ---

	public void testNoNavigation_self() {
		assertNoNavigationOnPrefix("www/vartest.p", "^self.method1[]", "^self");
	}

	public void testNoNavigation_main() {
		assertNoNavigationOnPrefix("www/vartest.p", "^MAIN:method1[]", "^MAIN");
	}

	// ==================== HELPERS: ^var.method[] ====================

	/**
	 * Проверяет навигацию от ^var.method[] к @method в классе переменной.
	 * Клик ставится на имя метода (последний элемент после последней точки).
	 */
	private void assertVarMethodNavigation(String sourceFile, String callPattern,
										   String expectedMethodName, String expectedFile) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("File not found: " + sourceFile, vFile);

		String content;
		try {
			content = new String(vFile.contentsToByteArray(), vFile.getCharset());
		} catch (Exception e) {
			fail("Cannot read file: " + sourceFile);
			return;
		}

		int patternPos = content.indexOf(callPattern);
		assertTrue("Pattern '" + callPattern + "' not found in " + sourceFile, patternPos >= 0);

		// Находим позицию имени метода — после последней точки в паттерне
		int lastDot = callPattern.lastIndexOf('.');
		assertTrue("No dot in pattern: " + callPattern, lastDot >= 0);
		int methodOffset = patternPos + lastDot + 1 + 2; // +2 символа внутрь имени метода

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(methodOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), methodOffset);

		assertNotNull("Targets should not be null for " + callPattern, targets);
		assertTrue("Should have target for " + callPattern + " (method: " + expectedMethodName + ")",
				targets.length > 0);

		PsiFile targetFile = targets[0].getContainingFile();
		assertNotNull("Target file should not be null", targetFile);
		assertTrue("Should navigate to " + expectedFile + ", got " + targetFile.getVirtualFile().getPath(),
				targetFile.getVirtualFile().getPath().endsWith(expectedFile));
	}

	/**
	 * Проверяет навигацию от клика на переменную к её определению $var[...].
	 * Клик ставится на имя переменной (между ^ и .).
	 */
	private void assertVarDefinitionNavigation(String sourceFile, String callPattern,
											   String varName, String expectedDefinition) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("File not found: " + sourceFile, vFile);

		String content;
		try {
			content = new String(vFile.contentsToByteArray(), vFile.getCharset());
		} catch (Exception e) {
			fail("Cannot read file: " + sourceFile);
			return;
		}

		int patternPos = content.indexOf(callPattern);
		assertTrue("Pattern '" + callPattern + "' not found in " + sourceFile, patternPos >= 0);

		// Находим позицию имени переменной в паттерне
		int varPos = callPattern.indexOf(varName);
		assertTrue("Var '" + varName + "' not found in pattern: " + callPattern, varPos >= 0);
		int varOffset = patternPos + varPos + 1; // +1 внутрь имени

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(varOffset);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), varOffset);

		assertNotNull("Targets should not be null for var click in " + callPattern, targets);
		assertTrue("Should have target for var '" + varName + "' in " + callPattern,
				targets.length > 0);

		// Проверяем что навигация ведёт к определению переменной
		PsiElement target = targets[0];
		int targetOffset = target.getTextOffset();
		String defContext = content.substring(targetOffset, Math.min(targetOffset + expectedDefinition.length() + 5, content.length()));
		assertTrue("Should navigate to '" + expectedDefinition + "', got '" + defContext + "'",
				defContext.startsWith(expectedDefinition));
	}

	/**
	 * Проверяет что клик на ^self/^MAIN/^BASE НЕ выполняет навигацию.
	 */
	private void assertNoNavigationOnPrefix(String sourceFile, String callPattern, String prefix) {
		com.intellij.openapi.vfs.VirtualFile vFile = myFixture.findFileInTempDir(sourceFile);
		assertNotNull("File not found: " + sourceFile, vFile);

		String content;
		try {
			content = new String(vFile.contentsToByteArray(), vFile.getCharset());
		} catch (Exception e) {
			fail("Cannot read file: " + sourceFile);
			return;
		}

		int patternPos = content.indexOf(callPattern);
		assertTrue("Pattern '" + callPattern + "' not found in " + sourceFile, patternPos >= 0);

		// Ставим каретку на ^self/^MAIN/^BASE (на 2-й символ после ^)
		int prefixPos = patternPos + 2; // внутрь ^self/^MAIN

		myFixture.configureFromExistingVirtualFile(vFile);
		myFixture.getEditor().getCaretModel().moveToOffset(prefixPos);

		PsiElement[] targets = GotoDeclarationAction.findAllTargetElements(
				getProject(), myFixture.getEditor(), prefixPos);

		// Ожидаем: null или пустой массив
		assertTrue("Should NOT navigate on '" + prefix + "' in " + callPattern,
				targets == null || targets.length == 0);
	}
}
