# Принципы разработки Parser3 IntelliJ Plugin

## Главный принцип: ЕДИНСТВЕННЫЙ ИСТОЧНИК ИСТИНЫ

Каждая операция должна иметь **одну** функцию, которая её выполняет. Все остальные места должны вызывать эту функцию.

---

## 1. Одно действие = Одна функция

### ❌ Плохо: дублирование логики
```java
// В GotoDeclarationHandler
private PsiElement[] handleUseNavigation() { /* резолв пути */ }
private PsiElement[] handleAtUseNavigation() { /* тот же резолв пути */ }
private PsiElement[] handleAtUseNavigationForString() { /* снова тот же резолв */ }

// В ReferenceContributor  
private void resolveFile() { /* опять резолв пути */ }
```

### ✅ Хорошо: единая функция
```java
// Одна функция резолва
private PsiElement[] resolveUsePath(PsiElement element, String path) {
	// Вся логика резолва здесь
}

// Все остальные методы вызывают её
private PsiElement[] handleUseNavigation() {
	return resolveUsePath(element, extractPath(element));
}
private PsiElement[] handleAtUseNavigation() {
	return resolveUsePath(element, extractPath(element));
}
```

---

## 2. Сервисы как единственные источники

| Операция | Единственный источник |
|----------|----------------------|
| Поиск использований файла | `P3UsageService.findUsages()` |
| Вычисление нового пути | `P3UsePathUtils.computeNewUsePath()` |
| Проверка use-контекста | `P3UseContextUtils.isInUseContext()` |
| Резолв пути к файлу | `P3UseResolver.resolve()` |
| Парсинг вызова метода из PSI | `P3PsiExtractor.parseMethodCall()` |
| Определение класса по offset | `P3ClassIndex.findClassAtOffset()` |
| Позиционная видимость методов/классов/переменных | `P3ScopeContext(project, currentFile, cursorOffset)` |
| Низкоуровневый граф подключённых файлов | `P3VisibilityService.getVisibleFiles()` только внутри visibility-слоя |
| Обратная видимость классов/методов | `P3ScopeContext.getReverseClassSearchFiles()` / `P3ScopeContext.filterFilesThatCanSeeMethod()` |
| Проверка комментария по offset | `Parser3ClassUtils.isOffsetInComment()` |
| Видимые переменные из позиции курсора | `P3VariableIndex.getVisibleVariables()` |
| Поиск типа переменной | `P3VariableIndex.findVariableClassInFiles()` |
| Навигация к определению переменной | `P3VariableIndex.findVariableDefinitionLocation()` |
| Парсинг переменных из текста | `P3VariableParser.parse()` (через `P3VariableFileIndex.parseVariablesFromText()`) |
| Иерархия классов для переменных | `P3VariableIndex.buildClassHierarchy()` |
| Фильтрация переменных по контексту | `P3VariableIndex.filterByContext()` |
| Проверка параметра метода | `P3VariableIndex.getVisibleVariables()` шаг 3 (встроено) |
| Навигация к параметру метода | `P3GotoDeclarationHandler.navigateToMethodParameter()` |
| Чтение текста текущего файла | `P3VariableIndex.readFileTextSmart()` |
| Поиск по ключу в индексе (не итерация файлов) | `FileBasedIndex.processValues()` |
| Ленивый резолв типа переменной | `P3VariableIndex.resolveVariableType()` |
| Быстрый поиск типа результата метода | `P3MethodDocTypeResolver.getResultTypeByIndexQuery()` |
| Поиск ключей хеша по dotpath-цепочке | `P3VariableIndex.findHashKeysForChain()` |
| Поиск entry хеша по dotpath-цепочке | `P3VariableIndex.findHashEntryForChain()` |
| Поиск ключей псевдонимов объекта | `P3VariableIndex.mergeAliasHashKeys()` (private) |
| Обогащение ключей хеша алиасными данными | `P3VariableIndex.enrichWithAliasKeys()` |

### Правило: Если нужна операция — сначала проверь, есть ли сервис. Если нет — создай.

### КРИТИЧНОЕ правило: Парсинг переменных — ТОЛЬКО через P3VariableParser

Вся логика парсинга переменных (`$var[^Class::]`, `$self.var[^table::create{...}]`, etc.) ОБЯЗАТЕЛЬНО должна быть в одном месте — в `P3VariableParser.parse()`.

**История**: Изначально использовались 6 отдельных regex-паттернов (`USER_CLASS_CONSTRUCTOR_PATTERN`, `BUILTIN_CLASS_CONSTRUCTOR_PATTERN`, `BUILTIN_STATIC_METHOD_PATTERN`, `USER_METHOD_CALL_PATTERN`, `USER_CLASS_STATIC_METHOD_PATTERN`, `ANY_VAR_ASSIGNMENT_PATTERN`). Это привело к багам:
- `$MAIN:var[^table::create{...}]` — regex находил `:` от `MAIN:` вместо `::` от конструктора
- Невозможность корректно обрабатывать вложенные скобки
- Дублирование логики в 6 методах индексации

**Решение**: Один посимвольный парсер `P3VariableParser` — единый проход по тексту, один источник истины.

### ❌ Плохо: добавлять новый regex для нового паттерна переменной
```java
private static final Pattern NEW_VAR_PATTERN = Pattern.compile("...");
private static void indexNewPattern(text, result) { /* ещё один regex */ }
```

### ✅ Хорошо: расширять P3VariableParser.parse()
```java
// Один парсер, один проход — добавляем новый case в существующую логику
// P3VariableParser.parse() → находит $, парсит prefix, varName, скобку, содержимое
```

---

## 3. Прямая и обратная видимость

### Прямая видимость: "Что видно ИЗ файла"
```java
// Для автодополнения и навигации к определению
P3ScopeContext scopeContext = new P3ScopeContext(project, currentFile, cursorOffset);
List<VirtualFile> visibleMethods = scopeContext.getMethodSearchFiles();
List<VirtualFile> visibleClasses = scopeContext.getClassSearchFiles();
```

### Обратная видимость: "Кто ВИДИТ файл"
```java
// Для поиска вызовов метода/класса (клик на определении)
List<VirtualFile> classObservers = P3ScopeContext.getReverseClassSearchFiles(project, currentFile);
List<VirtualFile> methodObservers = P3ScopeContext.filterFilesThatCanSeeMethod(
		project, currentFile, ownerClassName, candidateFiles);
```

### ❌ Плохо: использовать прямую видимость для поиска вызовов
```java
// При клике на @helper[] в utils.p — это НЕПРАВИЛЬНО!
P3ScopeContext scopeContext = new P3ScopeContext(project, utilsFile, cursorOffset);
List<VirtualFile> visible = scopeContext.getMethodSearchFiles();
// visible содержит файлы видимые ИЗ utils.p, а не файлы ИСПОЛЬЗУЮЩИЕ utils.p
```

### ✅ Хорошо: использовать обратную видимость
```java
// При клике на @helper[] в utils.p
List<VirtualFile> callers = P3ScopeContext.filterFilesThatCanSeeMethod(
		project, utilsFile, ownerClassName, candidateFiles);
// callers содержит index.p и другие файлы, которые подключили utils.p
```

---

## 4. Резолв путей в Parser3

### Типы путей

| Тип | Пример | Откуда резолвится |
|-----|--------|-------------------|
| Относительный | `lib.p`, `utils/helper.p` | От директории текущего файла |
| С `../` | `../common/lib.p` | От директории текущего файла |
| Абсолютный | `/includes/lib.p` | От `document_root` |

### Порядок поиска (приоритет)

1. **Абсолютный путь** (`/path/file.p`) → от `document_root`
2. **Относительный путь** → от директории текущего файла
3. **Fallback для `../`**: если путь содержит `../` и файл не найден → ищем без `../` в текущей директории
4. **CLASS_PATH**: если всё ещё не найден → ищем в директориях из `$CLASS_PATH`

Если файл найден на любом шаге — следующие шаги не выполняются.

#### Fallback для путей с `../`

Если `^use[../file.p]` не находит файл в родительской директории, Parser3 ищет `file.p` в текущей директории:

```
Структура:
www/
├── file.p           <- может быть, может не быть
└── inner/
    ├── auto.p       <- ^use[../file.p]
    └── file.p       <- fallback

Из www/inner/auto.p вызов ^use[../file.p]:
1. Ищем www/file.p → если найден, используем его
2. Если не найден → ищем www/inner/file.p (fallback)
3. Если и там нет → ищем в CLASS_PATH
```

### $CLASS_PATH

- Обычная переменная Parser3 (строка или таблица)
- Парсинг уже реализован в плагине: `P3ClassPathExtractor`, `P3ClassPathEvaluator`
- Содержит список директорий для поиска файлов
- **ВАЖНО**: CLASS_PATH позволяет резолвить короткие имена (`^use[Logger.p]`), но НЕ подключает файлы автоматически

### document_root

- Корень web-пространства (как `DOCUMENT_ROOT` в Apache/nginx)
- Абсолютные пути (`/path/to/file.p`) резолвятся от него
- Задаётся в настройках плагина
- Если не задан — используется корень проекта как fallback

### Примеры резолва

```
Текущий файл: /var/www/site/pages/index.p
document_root: /var/www/site
$CLASS_PATH[^table::create{path
/var/www/site/lib
/var/www/site/vendor
}]

^use[utils.p]           → /var/www/site/pages/utils.p (относительно текущего)
                        → если нет, то /var/www/site/lib/utils.p (1-й CLASS_PATH)
                        → если нет, то /var/www/site/vendor/utils.p (2-й CLASS_PATH)

^use[../common/lib.p]   → /var/www/site/common/lib.p (../ от pages/)
                        → если нет, то /var/www/site/pages/common/lib.p (fallback)
                        → если нет, то /var/www/site/lib/common/lib.p (CLASS_PATH)

^use[/includes/db.p]    → /var/www/site/includes/db.p (от document_root)
```

### Сервисы для работы с путями

| Сервис | Назначение |
|--------|------------|
| `P3UseResolver` | **Единый резолвер путей** — навигация и автокомплит |
| `P3ClassPathEvaluator` | Получение списка CLASS_PATH директорий |
| `P3ClassPathExtractor` | Парсинг $CLASS_PATH из кода |
| `P3UsePathUtils` | Вычисление новых путей при рефакторинге |
| `Parser3ProjectSettings` | Получение document_root из настроек |

---

## 5. @autouse и видимость классов

### Логика видимости классов

```
┌─────────────────────────────────────────────────────────────┐
│  Есть @autouse в видимых файлах?                            │
│  ├── ДА → ВСЕ классы проекта видны                         │
│  └── НЕТ → класс виден только если:                        │
│           ├── Подключен через @USE /full/path/Class.p      │
│           └── ИЛИ подключен через ^use[Class.p]            │
│               + директория класса есть в CLASS_PATH         │
└─────────────────────────────────────────────────────────────┘
```

### ❌ Неправильное понимание
```
@autouse + CLASS_PATH = классы из CLASS_PATH видны
```

### ✅ Правильное понимание
```
@autouse = ВСЕ классы видны (независимо от CLASS_PATH)
Без @autouse = класс должен быть явно подключен через @USE или ^use[]
```

---

## 6. Парсинг вызовов методов

### Единственный источник: `P3PsiExtractor.parseMethodCall()`

Все компоненты плагина, которым нужно определить информацию о вызове метода, должны использовать эту функцию:

```java
// В P3GotoDeclarationHandler
P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(element);
if (!callInfo.isValid()) {
		return PsiElement.EMPTY_ARRAY;
}

// В P3ReferenceContributor
P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(element);
if (!callInfo.isValid()) {
		return PsiReference.EMPTY_ARRAY;
}

// В P3MethodCallElement
P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(this);
```

### Что возвращает MethodCallInfo

| Поле | Описание |
|------|----------|
| `isValid()` | Валиден ли вызов для резолвинга |
| `getClassName()` | Имя класса (для `^User::method[]`) или null |
| `getMethodName()` | Имя метода |
| `isClassMethod()` | Это `^ClassName::method[]` |
| `isSelfCall()` | Это `^self.method[]` |
| `isMainCall()` | Это `^MAIN:method[]` |
| `isBaseCall()` | Это `^BASE:method[]` |

### Невалидные форматы (parseMethodCall возвращает invalid)

- `^method.something[]` — вызов метода объекта из переменной
- `^ClassName::method.something[]` — точка после имени метода
- Клик на элемент после точки (например на "something")

---

## 7. Определение контекста класса

### Единственный источник: `P3ClassIndex.findClassAtOffset()`

```java
// Определить в каком классе находится элемент
P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
P3ClassDeclaration classDecl = classIndex.findClassAtOffset(file, offset);
String className = classDecl != null ? classDecl.getName() : "MAIN";
```

### Правило: НЕ дублировать логику поиска класса

❌ Плохо:
```java
// В одном месте
List<P3ClassDeclaration> classes = classIndex.findInFile(file);
for (P3ClassDeclaration c : classes) {
		if (offset >= c.getStartOffset() && offset < c.getEndOffset()) {
		return c.getName();
    }
			}

// В другом месте — тот же код
```

✅ Хорошо:
```java
P3ClassDeclaration classDecl = classIndex.findClassAtOffset(file, offset);
```

---

## 8. Получение видимых файлов

### Единственный источник: `P3ScopeContext`

Для production-кода completion/navigation/documentation/references нельзя вручную выбирать между `P3VisibilityService.getVisibleFiles()` и `getAllProjectFiles()`. Такой выбор уже собран в позиционном контексте.

```java
P3ScopeContext scopeContext = new P3ScopeContext(project, currentFile, cursorOffset);

List<VirtualFile> methodFiles = scopeContext.getMethodSearchFiles();
List<VirtualFile> classFiles = scopeContext.getClassSearchFiles();
List<VirtualFile> variableFiles = scopeContext.getVariableSearchFiles();
```

Правила:

- всегда передавать реальный `cursorOffset`;
- не использовать `Integer.MAX_VALUE` как замену позиции курсора;
- `P3VisibilityService` использовать только как низкоуровневый граф подключённых файлов внутри visibility-слоя;
- `@OPTIONS locals` и `[locals]` считать семантикой языка, а не настройкой автокомплита;
- режим "Все методы" / "Только через use" влияет на набор файлов-кандидатов, но не отключает локальность переменных.

Для обратной видимости:

```java
List<VirtualFile> classCallers = P3ScopeContext.getReverseClassSearchFiles(project, classFile);
List<VirtualFile> methodCallers = P3ScopeContext.filterFilesThatCanSeeMethod(
		project, methodFile, ownerClassName, candidateFiles);
```

---

## 9. Рефакторинг: сбор данных отдельно от действий

### ❌ Плохо: действие внутри проверки
```java
void checkAndWarn() {
    // проверка
    if (broken) {
        showDialog(); // действие прямо здесь!
    }
}

void verify() {
    // другая проверка
    if (broken) {
        showDialog(); // ещё один диалог!
    }
}
```

### ✅ Хорошо: сбор отдельно, действие в конце
```java
List<Problem> allProblems = new ArrayList<>();

void collectProblems1() {
    // только добавляем в список
    allProblems.add(problem);
}

void collectProblems2() {
    // только добавляем в список  
    allProblems.add(problem);
}

// В конце — ОДНО действие
if (!allProblems.isEmpty()) {
    showDialog(allProblems);
}
```

---

## 10. Различия в контексте — это параметры, не функции

### ❌ Плохо: отдельная функция для каждого случая
```java
void handleFileMoved() { /* логика */ }
void handleFileRenamed() { /* та же логика */ }
void handleDirectoryMoved() { /* та же логика */ }
void handleDirectoryRenamed() { /* та же логика */ }
```

### ✅ Хорошо: одна функция с параметрами
```java
void handleRefactoring(PsiElement element, RefactorType type) {
    // Одна логика для всех случаев
}
```

---

## Правило тестов от реального кейса

Любой bugfix обязан начинаться с regression-теста на исправляемое поведение. Нельзя сначала править production-код, а потом подгонять тест под уже сделанный фикс.

1. Взять реальный кейс: пользовательский файл, реальный Parser3-тест или другой фактический сценарий, где баг наблюдается.
2. Воспроизвести этот кейс 1:1 как fixture или inline-текст, убрав только внешние зависимости, не влияющие на баг.
3. Сузить код до минимально воспроизводимого состояния, проверяя что ошибка всё ещё проявляется.
4. Сначала добавить минимальный кейс в тесты и убедиться, что он падает без production-фикса.
5. Только после падающего теста править production-код.
6. Фикс считается закрытым только после того, как regression-тест и минимально нужный набор соседних тестов зелёные.
7. Если пользователь явно попросил временно не трогать тесты, фикс можно сделать без теста только как незавершённую правку: в ответе обязательно указать, какой regression-тест нужно добавить следующим шагом.

Если баг найден в реальном файле пользователя, тест должен начинаться с этого файла, а не с придуманного минимального примера:

1. Сначала воспроизвести файл 1:1 как test fixture или inline-текст, убрав только внешние зависимости, если они не нужны для воспроизведения.
2. Проверить, что тест на 1:1 кейсе реально падает до исправления.
3. Только после этого сужать файл до минимального кода, где ошибка всё ещё воспроизводится.
4. Исправлять production-код по минимальному воспроизведению, но оставлять регрессионный тест на реальный 1:1 кейс или максимально близкий к нему вариант.
5. При добавлении тестов по Parser3 всегда смотреть `D:\_Server\domains\test\parser3\tests`, нет ли там похожих семантических кейсов, и переносить релевантные идеи в тесты плагина.

---

## 11. Постобработка лучше дублирования

### Пример: лексер

### ❌ Плохо: менять логику в 5 разных местах лексера
```java
// В Parser3MethodCallLexer
if (isUse) createUSE_PATH();

// В Parser3DirectiveLexer  
if (afterAtUSE) createUSE_PATH();

// В Parser3BracketLexer
if (inUseBracket) createUSE_PATH();
```

### ✅ Хорошо: постобработка в одном месте
```java
// Parser3DirectiveLexerCore.postProcess()
// Проходим по готовым токенам и меняем STRING → USE_PATH где нужно
for (token : tokens) {
    if (isInUseContext(token)) {
        token.type = "USE_PATH";
    }
}
```


---

## 12. Чтение текста файлов: Document vs Disk

### Правило: текущий файл → Document, остальные → индекс или диск

При работе с текстом **текущего файла** (в котором находится курсор) нужно использовать `readFileTextSmart()`, который берёт текст из Document (несохранённые изменения). Без этого функции не видят изменения до сохранения файла.

### ❌ Плохо: всегда читать с диска
```java
String text = readFileText(file); // Не увидит несохранённые изменения!
```

### ✅ Хорошо: приоритет Document
```java
String text = readFileTextSmart(file); // Document → disk fallback
```

### Где это критично:
- `getVisibleVariables()` — парсинг переменных и параметров метода (шаг 3)
- `findParameterType()` — тип параметра из документации
- Любой парсинг текста текущего файла вне индексов

---

## 13. Правило grep перед написанием кода

Перед тем как писать новый код, **всегда** ищи существующий:

```bash
# Ищем похожую функциональность
grep -rn "resolve.*[Pp]ath\|find.*[Ff]ile" src/

# Ищем использование типа/класса
grep -rn "P3UseResolver\|STRING" src/

# Ищем все места где нужно будет добавить новый код
grep -rn "elementMoved\|elementRenamed" src/

# Ищем существующие методы в индексах
grep -rn "findClassAtOffset\|findInFile" src/
```

---

## 14. Версионирование индексов

### Правило: при изменении логики индексации — инкрементируй версию

```java
// В P3ClassFileIndex, P3MethodFileIndex, P3UseFileIndex
public int getVersion() {
    return 6; // Увеличить при изменении!
}
```

**Когда инкрементировать:**
- Изменилась структура данных в индексе
- Изменился алгоритм парсинга
- Добавлены новые поля
- Исправлен баг в индексации
- **Изменился equals() у данных индекса**

**Почему важно:**
- IDE кеширует индексы между запусками
- Без изменения версии старый кеш будет использоваться
- Пользователю придётся делать "Invalidate Caches"

### ВАЖНО: equals() в данных индекса

```java
// ❌ Плохо: equals сравнивает только часть полей
public boolean equals(Object o) {
    return offset == that.offset && 
           Objects.equals(ownerClass, that.ownerClass);
    // parameterNames и docText игнорируются!
}

// ✅ Хорошо: equals сравнивает ВСЕ поля
public boolean equals(Object o) {
    return offset == that.offset && 
           Objects.equals(ownerClass, that.ownerClass) &&
           Objects.equals(parameterNames, that.parameterNames) &&
           Objects.equals(docText, that.docText);
}
```

Если `equals()` не сравнивает все поля — IntelliJ не увидит изменений и не обновит индекс!

---

## 15. Признаки того, что нужен рефакторинг

1. **Копипаст** — если копируешь код из одного места в другое
2. **"Похожие" функции** — `handleX()`, `handleY()` с почти одинаковым телом
3. **Множественные диалоги/уведомления** — показываются из разных мест
4. **Несколько мест изменения** — при баге нужно править в 3+ местах
5. **Длинный чеклист** — "не забудь обновить там, там и там"
6. **Одинаковые циклы** — один и тот же паттерн итерации в нескольких местах

---

## 16. Данные из индекса — через индекс, не через файлы

### Правило: если данные уже проиндексированы — берём из индекса через `processValues()`, а не итерируем файлы

FileBasedIndex хранит данные в BTree — поиск по ключу O(log N). Итерация всех файлов — O(N).

### ❌ Плохо: итерация файлов для поиска данных, которые уже в индексе
```java
// Ищем тип результата метода — итерируем 500 файлов
for (VirtualFile file : visibleFiles) {
List<MethodInfo> infos = index.getFileData(P3MethodFileIndex.NAME, file, project);
    for (MethodInfo info : infos) {
		if (methodName.equals(info.name)) return info.docResult;
    }
			}
```

### ✅ Хорошо: прямой запрос по ключу
```java
// Один запрос в BTree по ключу methodName — O(1)
index.processValues(P3MethodFileIndex.NAME, methodName, null,
		(file, infos) -> {
		if (!visibleSet.contains(file)) return true;
		// infos — только для нужного ключа
		...
		}, scope);
```

### Когда применять:

| Ситуация | Метод |
|----------|-------|
| Поиск конкретной переменной по имени | `processValues(P3VariableFileIndex.NAME, varKey, ...)` |
| Поиск метода по имени | `processValues(P3MethodFileIndex.NAME, methodName, ...)` |
| Поиск класса по имени | `processValues(P3ClassFileIndex.NAME, className, ...)` |
| Получение ВСЕХ переменных для автокомплита | `getFileData()` в цикле (нужны все ключи) |

### Когда `getFileData()` допустим:
- Нужны **все** данные файла (например все переменные для `$` автокомплита)
- Нужна итерация по всем ключам файла
- Файл один (текущий)

### Ленивый резолв типов

Если данные требуют кросс-индексного резолва (например METHOD_CALL_MARKER → `# $result(type)` из индекса методов), резолв должен быть **ленивым**:

```java
// ❌ Плохо: жадный резолв при сборе ВСЕХ переменных
for (VirtualFile file : visibleFiles) {
var data = index.getFileData(varIndex, file);
    for (var info : data) {
String type = resolveMethodResultType(info); // Кросс-индексный запрос для КАЖДОЙ переменной!
        dedup.put(key, new VisibleVariable(type));
		}
		}

// ✅ Хорошо: сохраняем маркер, резолвим только когда нужен тип
		dedup.put(key, new VisibleVariable(METHOD_CALL_MARKER, methodName, targetClass));
// ... позже, когда реально нужен тип:
String type = resolveVariableType(v, visibleFiles); // Один processValues по ключу
```

---

## Резюме

```
┌─────────────────────────────────────────────────────────────┐
│  ЕДИНСТВЕННЫЙ ИСТОЧНИК ИСТИНЫ                               │
│                                                             │
│  • Одна функция для одного действия                        │
│  • Сервисы для переиспользуемой логики                     │
│  • grep перед написанием нового кода                       │
│  • Сбор данных отдельно от действий                        │
│  • Постобработка вместо дублирования в лексере             │
│  • readFileTextSmart для текущего файла (Document)          │
│  • Различия = параметры, не отдельные функции              │
│  • Версионирование индексов при изменениях                 │
│  • P3PsiExtractor.parseMethodCall() — единый парсер        │
│  • P3VariableParser.parse() — единый парсер переменных     │
│  • P3ClassIndex.findClassAtOffset() — контекст             │
│  • P3ScopeContext — позиционная видимость                  │
│  • reverse-методы P3ScopeContext — обратная видимость      │
│  • equals() в индексах должен сравнивать ВСЕ поля          │
│  • processValues() вместо итерации файлов для поиска по ключу │
│  • Ленивый резолв кросс-индексных типов                    │
└─────────────────────────────────────────────────────────────┘
```

### ❌ Плохо: дублирование логики
```java
// В GotoDeclarationHandler
private PsiElement[] handleUseNavigation() { /* резолв пути */ }
private PsiElement[] handleAtUseNavigation() { /* тот же резолв пути */ }
private PsiElement[] handleAtUseNavigationForString() { /* снова тот же резолв */ }

// В ReferenceContributor  
private void resolveFile() { /* опять резолв пути */ }
```

### ✅ Хорошо: единая функция
```java
// Одна функция резолва
private PsiElement[] resolveUsePath(PsiElement element, String path) {
	// Вся логика резолва здесь
}

// Все остальные методы вызывают её
private PsiElement[] handleUseNavigation() {
	return resolveUsePath(element, extractPath(element));
}
private PsiElement[] handleAtUseNavigation() {
	return resolveUsePath(element, extractPath(element));
}
```

---

## 2. Сервисы как единственные источники

| Операция | Единственный источник |
|----------|----------------------|
| Поиск использований файла | `P3UsageService.findUsages()` |
| Вычисление нового пути | `P3UsePathUtils.computeNewUsePath()` |
| Проверка use-контекста | `P3UseContextUtils.isInUseContext()` |
| Резолв пути к файлу | `P3UseResolver.resolve()` |
| Парсинг вызова метода из PSI | `P3PsiExtractor.parseMethodCall()` |
| Определение класса по offset | `P3ClassIndex.findClassAtOffset()` |
| Позиционная видимость методов/классов/переменных | `P3ScopeContext(project, currentFile, cursorOffset)` |
| Низкоуровневый граф подключённых файлов | `P3VisibilityService.getVisibleFiles()` только внутри visibility-слоя |
| Обратная видимость классов/методов | `P3ScopeContext.getReverseClassSearchFiles()` / `P3ScopeContext.filterFilesThatCanSeeMethod()` |
| Проверка комментария по offset | `Parser3ClassUtils.isOffsetInComment()` |
| Видимые переменные из позиции курсора | `P3VariableIndex.getVisibleVariables()` |
| Поиск типа переменной | `P3VariableIndex.findVariableClassInFiles()` |
| Навигация к определению переменной | `P3VariableIndex.findVariableDefinitionLocation()` |
| Парсинг переменных из текста | `P3VariableParser.parse()` (через `P3VariableFileIndex.parseVariablesFromText()`) |
| Иерархия классов для переменных | `P3VariableIndex.buildClassHierarchy()` |
| Фильтрация переменных по контексту | `P3VariableIndex.filterByContext()` |
| Проверка параметра метода | `P3VariableIndex.getVisibleVariables()` шаг 3 (встроено) |
| Навигация к параметру метода | `P3GotoDeclarationHandler.navigateToMethodParameter()` |
| Чтение текста текущего файла | `P3VariableIndex.readFileTextSmart()` |
| Поиск по ключу в индексе (не итерация файлов) | `FileBasedIndex.processValues()` |
| Ленивый резолв типа переменной | `P3VariableIndex.resolveVariableType()` |
| Быстрый поиск типа результата метода | `P3MethodDocTypeResolver.getResultTypeByIndexQuery()` |

### Правило: Если нужна операция — сначала проверь, есть ли сервис. Если нет — создай.

---

## 3. Резолв путей в Parser3

### Типы путей

| Тип | Пример | Откуда резолвится |
|-----|--------|-------------------|
| Относительный | `lib.p`, `utils/helper.p` | От директории текущего файла |
| С `../` | `../common/lib.p` | От директории текущего файла |
| Абсолютный | `/includes/lib.p` | От `document_root` |

### Порядок поиска (приоритет)

1. **Абсолютный путь** (`/path/file.p`) → от `document_root`
2. **Относительный путь** → от директории текущего файла
3. **Fallback для `../`**: если путь содержит `../` и файл не найден → ищем без `../` в текущей директории
4. **CLASS_PATH**: если всё ещё не найден → ищем в директориях из `$CLASS_PATH`

Если файл найден на любом шаге — следующие шаги не выполняются.

### $CLASS_PATH

- Обычная переменная Parser3 (строка или таблица)
- Парсинг уже реализован в плагине: `P3ClassPathExtractor`, `P3ClassPathEvaluator`
- Содержит список директорий для поиска файлов

### document_root

- Корень web-пространства (как `DOCUMENT_ROOT` в Apache/nginx)
- Абсолютные пути (`/path/to/file.p`) резолвятся от него
- Задаётся в настройках плагина
- Если не задан — используется корень проекта как fallback

### Примеры резолва

```
Текущий файл: /var/www/site/pages/index.p
document_root: /var/www/site
$CLASS_PATH[^table::create{path
/var/www/site/lib
/var/www/site/vendor
}]

^use[utils.p]           → /var/www/site/pages/utils.p (относительно текущего)
                        → если нет, то /var/www/site/lib/utils.p (1-й CLASS_PATH)
                        → если нет, то /var/www/site/vendor/utils.p (2-й CLASS_PATH)

^use[../common/lib.p]   → /var/www/site/common/lib.p (../ от pages/)
                        → если нет, то /var/www/site/pages/common/lib.p (fallback)
                        → если нет, то /var/www/site/lib/common/lib.p (CLASS_PATH)

^use[/includes/db.p]    → /var/www/site/includes/db.p (от document_root)
```

### Сервисы для работы с путями

| Сервис | Назначение |
|--------|------------|
| `P3UseResolver` | **Единый резолвер путей** — навигация и автокомплит |
| `P3ClassPathEvaluator` | Получение списка CLASS_PATH директорий |
| `P3ClassPathExtractor` | Парсинг $CLASS_PATH из кода |
| `P3UsePathUtils` | Вычисление новых путей при рефакторинге |
| `Parser3ProjectSettings` | Получение document_root из настроек |

---

## 4. Парсинг вызовов методов

### Единственный источник: `P3PsiExtractor.parseMethodCall()`

Все компоненты плагина, которым нужно определить информацию о вызове метода, должны использовать эту функцию:

```java
// В P3GotoDeclarationHandler
P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(element);
if (!callInfo.isValid()) {
		return PsiElement.EMPTY_ARRAY;
}

// В P3ReferenceContributor
P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(element);
if (!callInfo.isValid()) {
		return PsiReference.EMPTY_ARRAY;
}

// В P3MethodCallElement
P3PsiExtractor.MethodCallInfo callInfo = P3PsiExtractor.parseMethodCall(this);
```

### Что возвращает MethodCallInfo

| Поле | Описание |
|------|----------|
| `isValid()` | Валиден ли вызов для резолвинга |
| `getClassName()` | Имя класса (для `^User::method[]`) или null |
| `getMethodName()` | Имя метода |
| `isClassMethod()` | Это `^ClassName::method[]` |
| `isSelfCall()` | Это `^self.method[]` |
| `isMainCall()` | Это `^MAIN:method[]` |
| `isBaseCall()` | Это `^BASE:method[]` |

### Невалидные форматы (parseMethodCall возвращает invalid)

- `^method.something[]` — вызов метода объекта из переменной
- `^ClassName::method.something[]` — точка после имени метода
- Клик на элемент после точки (например на "something")

---

## 5. Определение контекста класса

### Единственный источник: `P3ClassIndex.findClassAtOffset()`

```java
// Определить в каком классе находится элемент
P3ClassIndex classIndex = P3ClassIndex.getInstance(project);
P3ClassDeclaration classDecl = classIndex.findClassAtOffset(file, offset);
String className = classDecl != null ? classDecl.getName() : "MAIN";
```

### Правило: НЕ дублировать логику поиска класса

❌ Плохо:
```java
// В одном месте
List<P3ClassDeclaration> classes = classIndex.findInFile(file);
for (P3ClassDeclaration c : classes) {
		if (offset >= c.getStartOffset() && offset < c.getEndOffset()) {
		return c.getName();
    }
			}

// В другом месте — тот же код
```

✅ Хорошо:
```java
P3ClassDeclaration classDecl = classIndex.findClassAtOffset(file, offset);
```

---

## 6. Получение видимых файлов

### Единственный источник: `P3ScopeContext`

Для IDE-поверхностей область видимости всегда строится для конкретной позиции:

```java
P3ScopeContext scopeContext = new P3ScopeContext(project, currentFile, cursorOffset);
List<VirtualFile> methodFiles = scopeContext.getMethodSearchFiles();
List<VirtualFile> classFiles = scopeContext.getClassSearchFiles();
List<VirtualFile> variableFiles = scopeContext.getVariableSearchFiles();
```

Прямой `P3VisibilityService.getVisibleFiles()` допустим только внутри visibility-слоя, где строится низкоуровневый граф подключений.

---

## 7. Рефакторинг: сбор данных отдельно от действий

### ❌ Плохо: действие внутри проверки
```java
void checkAndWarn() {
	// проверка
	if (broken) {
		showDialog(); // действие прямо здесь!
	}
}

void verify() {
	// другая проверка
	if (broken) {
		showDialog(); // ещё один диалог!
	}
}
```

### ✅ Хорошо: сбор отдельно, действие в конце
```java
List<Problem> allProblems = new ArrayList<>();

void collectProblems1() {
	// только добавляем в список
	allProblems.add(problem);
}

void collectProblems2() {
	// только добавляем в список  
	allProblems.add(problem);
}

// В конце — ОДНО действие
if (!allProblems.isEmpty()) {
showDialog(allProblems);
}
```

---

## 8. Различия в контексте — это параметры, не функции

### ❌ Плохо: отдельная функция для каждого случая
```java
void handleFileMoved() { /* логика */ }
void handleFileRenamed() { /* та же логика */ }
void handleDirectoryMoved() { /* та же логика */ }
void handleDirectoryRenamed() { /* та же логика */ }
```

### ✅ Хорошо: одна функция с параметрами
```java
void handleRefactoring(PsiElement element, RefactorType type) {
	// Одна логика для всех случаев
}
```

---

## 9. Постобработка лучше дублирования

### Пример: лексер

### ❌ Плохо: менять логику в 5 разных местах лексера
```java
// В Parser3MethodCallLexer
if (isUse) createUSE_PATH();

// В Parser3DirectiveLexer  
if (afterAtUSE) createUSE_PATH();

// В Parser3BracketLexer
if (inUseBracket) createUSE_PATH();
```

### ✅ Хорошо: постобработка в одном месте
```java
// Parser3DirectiveLexerCore.postProcess()
// Проходим по готовым токенам и меняем STRING → USE_PATH где нужно
for (token : tokens) {
		if (isInUseContext(token)) {
token.type = "USE_PATH";
		}
		}
```

---

## 10. Правило grep перед написанием кода

Перед тем как писать новый код, **всегда** ищи существующий:

```bash
# Ищем похожую функциональность
grep -rn "resolve.*[Pp]ath\|find.*[Ff]ile" src/

# Ищем использование типа/класса
grep -rn "P3UseResolver\|STRING" src/

# Ищем все места где нужно будет добавить новый код
grep -rn "elementMoved\|elementRenamed" src/

# Ищем существующие методы в индексах
grep -rn "findClassAtOffset\|findInFile" src/
```

---

## 11. Версионирование индексов

### Правило: при изменении логики индексации — инкрементируй версию

```java
// В P3ClassFileIndex, P3MethodFileIndex, P3UseFileIndex
public int getVersion() {
	return 6; // Увеличить при изменении логики!
}
```

**Когда инкрементировать:**
- Изменилась структура данных в индексе
- Изменился алгоритм парсинга
- Добавлены новые поля
- Исправлен баг в индексации

**Почему важно:**
- IDE кеширует индексы между запусками
- Без изменения версии старый кеш будет использоваться
- Пользователю придётся делать "Invalidate Caches"

---

## 12. Признаки того, что нужен рефакторинг

1. **Копипаст** — если копируешь код из одного места в другое
2. **"Похожие" функции** — `handleX()`, `handleY()` с почти одинаковым телом
3. **Множественные диалоги/уведомления** — показываются из разных мест
4. **Несколько мест изменения** — при баге нужно править в 3+ местах
5. **Длинный чеклист** — "не забудь обновить там, там и там"
6. **Одинаковые циклы** — один и тот же паттерн итерации в нескольких местах

---

## 13. DEBUG-флаги и логирование

### Правило: всё логирование только через `System.out.println`, всегда под флагом

```java
// В каждом классе с логированием
private static final boolean DEBUG = false;

// Использование
if (DEBUG) System.out.println("[MyClass] someValue=" + value);
```

### Флаги по умолчанию

| Флаг | Значение по умолчанию | Назначение |
|------|-----------------------|------------|
| `DEBUG` | `false` | Основной отладочный вывод |
| `DEBUG_PERF` | `false` | Замер производительности |
| `DEBUG_POPUP` | `false` | Отладка авто-попапа |

### ❌ Плохо: голые println
```java
System.out.println("[MyClass] value=" + value); // всегда печатает!
if (prev == null) { System.out.println("null!"); return null; }
```

### ✅ Хорошо: все под флагом
```java
if (DEBUG) System.out.println("[MyClass] value=" + value);
if (prev == null) { if (DEBUG) System.out.println("null!"); return null; }
```

### Где хранить флаг

Флаг `DEBUG` — приватное статическое поле класса. Переключать только его — не нужно трогать сам код логов.

---

## Стиль ответов

1. **Работать молча** — не описывать каждый шаг, не объяснять ход мыслей
2. **Результат** — только финальные файлы + 2-3 предложения что сделано
3. **Вопросы** — задавать только если действительно нужно уточнение
4. **Без повторов** — не пересказывать что уже известно из контекста
5. **grep/view** — делать молча, без комментариев

## Резюме

```
┌─────────────────────────────────────────────────────┐
│  ЕДИНСТВЕННЫЙ ИСТОЧНИК ИСТИНЫ                       │
│                                                     │
│  • Одна функция для одного действия                │
│  • Сервисы для переиспользуемой логики             │
│  • grep перед написанием нового кода               │
│  • Сбор данных отдельно от действий                │
│  • Постобработка вместо дублирования в лексере     │
│  • Различия = параметры, не отдельные функции      │
│  • readFileTextSmart для текущего файла (Document) │
│  • Версионирование индексов при изменениях         │
│  • P3PsiExtractor.parseMethodCall() — единый парсер│
│  • P3VariableParser.parse() — парсер переменных   │
│  • P3ClassIndex.findClassAtOffset() — контекст     │
│  • processValues() для поиска по ключу в индексе   │
│  • Ленивый резолв кросс-индексных типов            │
└─────────────────────────────────────────────────────┘
```
