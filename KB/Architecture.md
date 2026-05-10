# Parser3 IntelliJ Plugin — Architecture

## Индексы (FileBasedIndex)

### P3MethodFileIndex
- **Путь**: `ru.artlebedev.parser3.index.P3MethodFileIndex`
- **ID**: `parser3.methods`
- **Назначение**: Индексирует объявления методов (`@method[]`)
- **Ключ**: имя метода
- **Значение**: `List<MethodInfo>` (offset, ownerClass, параметры, документация)

**ВАЖНО**: `MethodInfo.equals()` должен сравнивать ВСЕ поля (offset, ownerClass, parameterNames, docText, isGetter), иначе индекс не будет обновляться при изменении параметров метода.

#### Поле isGetter

`MethodInfo.isGetter` — `true` если метод объявлен как `@GET_xxx`. При индексации `@GET_xxx` сохраняется под ключом `xxx` с `isGetter=true`.

Используется в `P3VariableMethodCompletionContributor.addClassProperties()` для показа свойств через `$obj.xxx`.

### P3ClassFileIndex
- **Путь**: `ru.artlebedev.parser3.index.P3ClassFileIndex`
- **ID**: `parser3.classes`
- **Назначение**: Индексирует объявления классов (`@CLASS`)
- **Ключ**: имя класса
- **Значение**: `List<ClassInfo>` (offset, baseClassName, options)

### P3UseFileIndex
- **Путь**: `ru.artlebedev.parser3.index.P3UseFileIndex`
- **ID**: `parser3.uses`
- **Назначение**: Индексирует директивы `@USE` и `^use[]`
- **Ключ**: путь файла
- **Значение**: список USE-путей

### P3MethodCallFileIndex
- **Путь**: `ru.artlebedev.parser3.index.P3MethodCallFileIndex`
- **ID**: `parser3.methodCalls`
- **Назначение**: Индексирует вызовы методов (`^method[]`, `^Class:method[]`, etc.)
- **Ключ**: имя метода
- **Значение**: `List<MethodCallInfo>` (offset, callType, targetClassName, callerClassName)
- **Типы вызовов** (`CallType`):
    - `SIMPLE` — `^method[]`
    - `SELF` — `^self.method[]`
    - `MAIN` — `^MAIN:method[]`
    - `BASE` — `^BASE:method[]`
    - `CLASS_STATIC` — `^ClassName:method[]`
    - `CLASS_CONSTRUCTOR` — `^ClassName::method[]`

### P3VariableTypeFileIndex
- **Путь**: `ru.artlebedev.parser3.index.P3VariableTypeFileIndex`
- **ID**: `parser3.variableTypes`
- **Назначение**: Индексирует присваивания переменных с определением типа
- **Ключ**: полное имя переменной с префиксом (`var`, `self.var`, `MAIN:var`)
- **Значение**: `List<VariableTypeInfo>` (offset, className, ownerClass, ownerMethod, methodName, targetClassName, varPrefix, isLocal, columns, sourceVarKey)

#### P3VariableParser — единственный источник парсинга переменных

**Путь**: `ru.artlebedev.parser3.index.P3VariableParser`

**ВАЖНО**: Вся логика парсинга переменных из текста ОБЯЗАТЕЛЬНО должна быть в одном месте — в `P3VariableParser.parse()`. Это посимвольный парсер, заменивший 6 отдельных regex-паттернов. Дублирование логики парсинга в других местах ЗАПРЕЩЕНО.

**Почему посимвольный парсер, а не regex:**
- Regex не может корректно обрабатывать вложенные скобки (`$var[^Class::create[$inner]]`)
- Regex не может отслеживать контекст (мы внутри `$data[` или снаружи)
- Баг с `$MAIN:var` — прямое следствие разрыва между regex-матчингом и посимвольным поиском `::` в `extractColumnsResult`
- Один проход вместо 6 отдельных regex — проще поддерживать и отлаживать

**Логика парсера (один проход по тексту):**
1. Пропускает комментарии (`#` в колонке 0, `^rem{...}`)
2. Находит `$` (не экранированный `^$`)
3. Парсит префикс: `self.` или `MAIN:` или null
4. Парсит varName (идентификатор)
5. Ожидает скобку: `[` `(` `{`
6. Смотрит внутри на `^className::` или `^className:` или `^method[`
7. Для table типов: вызывает `extractTableColumnsResult()` для парсинга колонок
8. Создаёт `VariableTypeInfo` со всеми метаданными

**Вызывается из:**
- `P3VariableFileIndex.parseVariablesFromText()` → `P3VariableParser.parse()`
- `P3VariableFileIndex.getIndexer()` вызывает `parseVariablesFromText()` при индексации
- `P3VariableIndex.getVisibleVariables()` вызывает `parseVariablesFromText()` для текущего файла
- НЕ дублировать логику парсинга!

#### Поле isLocal

`VariableTypeInfo.isLocal` — `true` если переменная локальна в своём методе. Вычисляется при индексации через `computeIsLocal()`:
- `$self.var` и `$MAIN:var` → всегда `false` (глобальные)
- `$var` вне метода → `false`
- `$var` в методе с `@OPTIONS locals` → `true`
- `$var` в методе с `[locals]` → `true`
- `$var` в методе с `[varName]` и имя совпадает → `true`

**Важно**: для видимости из других файлов проверяется `info.isLocal` из индекса — парсинг текста НЕ требуется.

#### Поле columns

`VariableTypeInfo.columns` — список колонок таблицы (для `className="table"`). Парсится при индексации:
- `$list[^table::create{name\turi}]` → columns = `["name", "uri"]` (из первой строки TSV)
- `$list[^table::sql{SELECT name, uri FROM menu}]` → columns = `["name", "uri"]` (из SQL SELECT)
- `$list[^table:sql{SELECT id FROM t}]` → columns = `["id"]` (одно двоеточие тоже работает)
- `$list[^table::create[nameless]{data}]` → columns = null (nameless таблица)
- `$list[^table::load[file.cfg]]` → columns = null (нет `{}`)

**Парсинг SQL колонок** (`P3VariableFileIndex.parseSqlSelectColumns()`):
- Простые колонки: `SELECT name, uri FROM t` → `["name", "uri"]`
- С alias: `SELECT t.name AS menu_name` → `["menu_name"]`
- Через точку: `SELECT t.name` → `["name"]`
- Выражения без AS: `SELECT lower(x)` → `["lower(x)"]`
- Подзапросы: `SELECT (select count(*) from t2)` → `["(select count(*) from t2)"]`
- Кавычки в alias: `SELECT x AS 'col name'` → `["col name"]`
- `SELECT *` → null (колонки неизвестны)

#### Поле sourceVarKey

`VariableTypeInfo.sourceVarKey` — ключ переменной-источника. Используется в двух разных смыслах:

**1. Копирование таблицы:**
- `$copy[^table::create[$list]]` → sourceVarKey = `"list"`, колонки наследуются от `$list`
- Резолв рекурсивный через `P3VariableIndex.findVariableColumnsInternal()` (глубина до 5)

**2. Dotpath-ссылка (псевдоним хеша/массива):**
- `$var[$data.key1]` → sourceVarKey = `"data.key1"` (ссылка, НЕ копия)
- `$var[$arr.0]` → sourceVarKey = `"arr.0"` (числовой индекс массива)
- Отличие от копии: `$var[^hash::create[$data.key1]]` — это копия (sourceVarKey не содержит `.`)

**Семантика ссылки vs копии:**
- `$var[$data.key1]` — `$var` и `$data.key1` указывают на ОДИН объект (как ссылка в JS)
- `$var.key2[...]` — добавляет ключ к объекту, виден и через `$var.key2` и через `$data.key1.key2`
- `$var[^hash::create[$data.key1]]` — `$var` это новый объект (копия), ключи НЕ разделяются

**Резолв псевдонимов:**
- `$data.key1.` → `mergeAliasHashKeys("data.key1")` находит все `$var` у которых `sourceVarKey="data.key1"`, собирает их hashKeys
- `$data.` → `enrichWithAliasKeys(hashKeys, "data")` — для каждого ключа проверяет алиасы через `mergeAliasHashKeys("data.keyN")`
- Поиск алиасов: текущий файл (с проверкой offset) + видимые файлы через use (без ограничения offset)
- Ключи псевдонима видны только если добавлены ПОСЛЕ объявления псевдонима (offset псевдонима < offset ключа)
- Dotpath `sourceVarKey` у конкретного присваивания всегда резолвится на позиции перед этим присваиванием (`offset - 1`). Это важно для самоприсваиваний вида `$data[^data.at[first]]`: ссылка `data.*` должна смотреть на предыдущее состояние `$data`, а не снова находить текущее присваивание и зацикливать completion.
- Сегменты dot-chain используют правила имени переменной, включая дефис: `$SQL.connect-string[...]` должен регистрировать additive-ключ `connect-string`, а не обрываться на `connect`.
- Для переменных из видимых внешних файлов (`main_auto`, auto.p chain, use-файлы) применяется та же `P3VariableEffectiveShape`, что и для текущего файла: полное присваивание задаёт базовую структуру, все последующие additive-записи мержатся. Нельзя брать только последнее присваивание из auto.p, иначе реальный `$SQL` сводится к последнему `$SQL.connect-string-clickhouse[...]`.

#### Параметры итераторных методов

`foreach`, `for`, `select` и `sort` объявляют параметры из первой скобки как локально видимые переменные:

```parser3
^data.select[key;value]($value.name eq 'x')
^data.sort[key;value]($value.name)
```

Правило реализации:
- общий helper в `P3VariableParser` регистрирует `key` как `UNKNOWN_TYPE`, а `value` как переменную с `sourceVarKey="foreach:<source>"`;
- та же логика применяется к цепочным вызовам `^data.items.select[key;value](...)` через `sourceVarKey="foreach_field:<root>:<fieldPath>"`;
- в single-pass лексере первая скобка этих методов должна разбираться как список `VARIABLE`-токенов, а не как обычная строка;
- нельзя добавлять отдельные regex или отдельные ветки для `select`/`sort`: они должны идти через общий механизм итераторных параметров, как `foreach`/`for`.

#### Паттерны определения типа

**1. Конструкторы пользовательских классов** (имя с большой буквы):
```regex
\$(self\.|MAIN:)?([\p{L}_][\p{L}0-9_]*)\s*\[\s*\^\s*([A-Z][\p{L}0-9_]*)\s*::
```
| Пример | Ключ индекса | Тип |
|--------|--------------|-----|
| `$user[^User::create[]]` | `user` | `User` |
| `$self.db[^Database::connect[]]` | `self.db` | `Database` |
| `$MAIN:config[^Config::load[]]` | `MAIN:config` | `Config` |

**2. Конструкторы встроенных классов** (имя с маленькой буквы):
```regex
\$(self\.|MAIN:)?([\p{L}_][\p{L}0-9_]*)\s*\[\s*\^\s*([a-z][a-z0-9_]*)\s*::
```
| Пример | Ключ индекса | Тип |
|--------|--------------|-----|
| `$data[^table::load[file.cfg]]` | `data` | `table` |
| `$self.items[^hash::create[]]` | `self.items` | `hash` |
| `$MAIN:now[^date::now[]]` | `MAIN:now` | `date` |

**3. Статические методы встроенных классов с известным типом возврата**:
```regex
\$(self\.|MAIN:)?([\p{L}_][\p{L}0-9_]*)\s*\[\s*\^\s*([a-z][a-z0-9_]*)\s*:\s*([a-z][a-z0-9_-]*)\s*[\[({]
```
| Пример | Ключ индекса | Тип | Почему |
|--------|--------------|-----|--------|
| `$files[^file:list[/path]]` | `files` | `table` | `file:list` возвращает table |
| `$info[^file:stat[file.txt]]` | `info` | `hash` | `file:stat` возвращает hash |

**4. Вызовы пользовательских методов** (тип из документации):
```regex
\$(self\.|MAIN:)?([\p{L}_][\p{L}0-9_]*)\s*\[\s*\^\s*([a-z_][\p{L}0-9_]*)(?!\s*:)\s*[\[({]
```
Индексируется с маркером `METHOD_CALL_MARKER`, тип определяется в runtime из `# $result(type)`:

| Пример | Ключ индекса | methodName | Тип (runtime) |
|--------|--------------|------------|---------------|
| `$data[^res_hash[]]` | `data` | `res_hash` | из `# $result(hash)` |

**5. Статические вызовы пользовательских классов** (тип из документации):
```regex
\$(self\.|MAIN:)?([\p{L}_][\p{L}0-9_]*)\s*\[\s*\^\s*([A-Z][\p{L}0-9_]*)\s*:\s*([\p{L}_][\p{L}0-9_]*)\s*[\[({]
```
| Пример | Ключ индекса | targetClassName | methodName | Тип (runtime) |
|--------|--------------|-----------------|------------|---------------|
| `$var[^User:method[]]` | `var` | `User` | `method` | из `# $result(hash)` в `@method` класса `User` |

**6. Типы параметров методов** (runtime, не индекс):

Внутри метода параметры имеют тип из документации `# $param(type)`:
```parser3
########################################
# $info(hash) входящие данные
########################################
@process[info]
^info.   # автокомплит показывает методы hash
```

#### Встроенные классы Parser3

Полный список (32 класса):
```
array, bool, console, cookie, curl, date, double, env,
file, form, hash, hashfile, image, inet, int, json,
junction, mail, math, memcached, memory, reflection, regex,
request, response, sqlite, status, string, table, void,
xdoc, xnode
```

Проверка: `Parser3BuiltinMethods.isBuiltinClass(name)`

#### Эквивалентность форм переменных

**ВАЖНО**: Ключ индекса теперь включает префикс! Три формы индексируются РАЗДЕЛЬНО:
- `$var[...]` → ключ `var`
- `$self.var[...]` → ключ `self.var`
- `$MAIN:var[...]` → ключ `MAIN:var`

Логика эквивалентности реализована в `P3VariableIndex.filterByContext()` (ранее `P3VariableTypeIndex.getEquivalentKeys()`).

#### Единая функция парсинга

`P3VariableFileIndex.parseVariablesFromText(text)` → делегирует в `P3VariableParser.parse(text)`:
- `P3VariableParser` — **единственный источник** парсинга переменных (посимвольный парсер)
- `getIndexer()` вызывает `parseVariablesFromText()` при индексации файлов
- `P3VariableIndex.getVisibleVariables()` вызывает `parseVariablesFromText()` для текущего файла (из Document, с несохранёнными изменениями)
- НЕ дублировать логику парсинга!

**ВАЖНО**: Ранее использовались 6 отдельных regex-паттернов. Это приводило к багам (например, `$MAIN:var` — regex находил `:` от `MAIN:` вместо `::` от конструктора). Теперь один посимвольный парсер `P3VariableParser` заменяет все regex.

### Пропуск комментариев

Все индексы пропускают содержимое комментариев. Общий метод:
- `Parser3ClassUtils.isOffsetInComment(text, offset)` — единый источник истины
- Проверяет `#` в колонке 0 (строковый комментарий до конца строки)
- Проверяет `^rem{...}` / `^rem[...]` / `^rem(...)`
- Вызывается в каждом индексе после `matcher.find()`, перед добавлением в результат

## Сервисы индексов

### P3MethodIndex
- Обёртка над `P3MethodFileIndex`
- Методы: `findByName()`, `findInFiles()`, `findInFile()`

### P3ClassIndex
- Обёртка над `P3ClassFileIndex`
- Методы: `findByName()`, `findClassAtOffset()`, `hasAutouseVisibleFrom()`

### P3MethodCallIndex
- Обёртка над `P3MethodCallFileIndex`
- **Ключевой метод**: `findCallsForMethod(methodName, ownerClassName, visibleFiles)`
    - Учитывает наследование — ищет вызовы из класса и всех его наследников
    - Для метода в `User` находит вызовы из `User`, `UserEx` (extends User), etc.

### P3VariableIndex (ранее P3VariableTypeIndex)
- Обёртка над `P3VariableFileIndex`
- **Единственный источник видимых переменных**: `getVisibleVariables(visibleFiles, currentFile, cursorOffset)`
    - Возвращает `List<VisibleVariable>` — все переменные видимые из позиции курсора
    - Для текущего файла: парсит из Document через `P3VariableFileIndex.parseVariablesFromText()`
    - Для других файлов: данные из индекса, фильтрация по `info.isLocal` (без парсинга текста)
    - Дедупликация по `cleanName + ownerClass` с учётом позиции `^use[]` (effective offset)
    - **ЛЕНИВЫЙ РЕЗОЛВ**: НЕ вызывает `resolveClassName()` — сохраняет METHOD_CALL_MARKER как есть с `methodName`/`targetClassName` для резолва по запросу
    - `ProgressManager.checkCanceled()` в цикле — позволяет IntelliJ отменить при вводе символа
- **Публичные обёртки** (все делегируют в `getVisibleVariables` + `filterByContext`):
    - `findVariable(varKey, files, currentFile, offset)` — поиск конкретной переменной
    - `findVariableDefinitionLocation(varKey, files, currentFile, offset)` — навигация к определению
    - `findVariableClassInFiles(varKey, files, currentFile, offset)` — определение типа (3-уровневый поиск)
    - `resolveChainedType(varKey, files, currentFile, offset)` — резолв цепочек `user.u_var2`
- **3-уровневый поиск типа** (`findVariableClassInFiles`):
    1. **Fast path** (~0ms): `findVariableInCurrentFileOnly()` — парсит только текущий Document
    2. **Index query** (~1ms): `findVariableByIndexQuery()` — `processValues()` по ключу в индексе
    3. **Full scan** (~100ms+): `findVariable()` + `resolveVariableType()` — для METHOD_CALL_MARKER и параметров
- **Ленивый резолв типа**: `resolveVariableType(v, visibleFiles)`
    - Если `v.needsTypeResolve()` → вызывает `P3MethodDocTypeResolver.getResultTypeByIndexQuery()` — прямой запрос в индекс методов по ключу
    - Иначе → возвращает `v.className` как есть

#### VisibleVariable — результат поиска переменной

| Поле | Тип | Описание |
|------|-----|----------|
| `varKey` | String | Ключ индекса: `"var"`, `"self.var"`, `"MAIN:var"` |
| `cleanName` | String | Чистое имя без префикса: `"var"` |
| `className` | String | Тип (имя класса) или `METHOD_CALL_MARKER` (не резолвлено) |
| `ownerClass` | String? | Класс-владелец: null = MAIN |
| `file` | VirtualFile | Файл определения |
| `offset` | int | Offset определения |
| `columns` | List<String>? | Колонки таблицы |
| `sourceVarKey` | String? | Ключ переменной-источника |
| `methodName` | String? | Имя метода (для ленивого резолва METHOD_CALL_MARKER) |
| `targetClassName` | String? | Класс метода (для ленивого резолва METHOD_CALL_MARKER) |
| `isMethodParam` | boolean | true если это параметр метода (затеняет внешние переменные) |

**Методы:**
- `needsTypeResolve()` — true если `className == METHOD_CALL_MARKER`
- `getDisplayType()` — тип для UI (скрывает METHOD_CALL_MARKER и UNKNOWN_TYPE, возвращает `""`)
- `ofMethodParam(name, type, file, offset)` — static factory для создания параметра метода

#### findVariableByIndexQuery — прямой поиск по ключу в индексе

`P3VariableIndex.findVariableByIndexQuery(varKey, visibleFiles, currentFile, offset)`:
- Запрашивает 3 эквивалентных ключа: `"var"`, `"self.var"`, `"MAIN:var"` через `processValues()`
- `visibleSet` (HashSet) для O(1) проверки видимости файла
- Та же логика фильтрации что в `collectVisibleFromOtherFile`: isLocal, ownerClass, classHierarchy
- Возвращает `null` для METHOD_CALL_MARKER и UNKNOWN_TYPE (нужен полный поиск)
- **Когда используется**: `findVariableClassInFiles()`, `findVariableColumnsInternal()` — tier 2

#### findVariableInCurrentFileOnly — обёртка над findVariable

`P3VariableIndex.findVariableInCurrentFileOnly(varKey, currentFile, offset)`:
- Тонкая обёртка: вызывает `findVariable(varKey, [currentFile], currentFile, offset)`
- Не сканирует другие файлы (visibleFiles = только currentFile)
- Возвращает `null` для METHOD_CALL_MARKER и UNKNOWN_TYPE без sourceVarKey (нужен полный поиск)
- **Когда используется**: `findVariableClassInFiles()`, `findVariableColumnsInternal()`, резолв hash-цепочек — tier 1

- **Фильтрация по контексту**: `filterByContext(allVisible, contextType, ownerClass, classHierarchy)`
    - `contextType="normal"` → переменные текущего класса + @BASE иерархия + MAIN
    - `contextType="self"` → текущий класс + @BASE иерархия (в классе), или все MAIN (в MAIN)
    - `contextType="MAIN"` → только $MAIN:var (ownerClass=null)
    - `contextType="BASE"` → только переменные из @BASE иерархии (без текущего класса)
    - `classHierarchy` — набор имён классов (текущий + @BASE цепочка), строится через `buildClassHierarchy()`

**ВАЖНО**: `ownerClass=null` в индексе означает MAIN класс. В `filterByContext` для `contextType="self"` в MAIN контексте (`cursorOwnerClass="MAIN"`) `ownerClass=null` приводится к `"MAIN"` перед сравнением с `classHierarchy`.

#### buildClassHierarchy

`P3VariableIndex.buildClassHierarchy(ownerClass)` — строит цепочку наследования:
- Для `"MAIN"` → `Set["MAIN"]`
- Для `"Admin"` с `@BASE User` → `Set["Admin", "User"]`
- Используется в `getVisibleVariables()` и передаётся в `filterByContext()`
- Также используется в `P3VariableCompletionContributor.addVariableCompletions()` для фильтрации

#### collectVisibleFromOtherFile

`collectVisibleFromOtherFile(file, classHierarchy, dedup, dedupEffectiveOffset, useOffset)` — собирает переменные из другого файла:
- MAIN переменные (`ownerClass=null`) — всегда видны
- Переменные из @BASE классов (`classHierarchy.contains(ownerClass)`) — видны через наследование
- Остальные классовые переменные — не видны
- Локальные переменные (`isLocal=true`) — не видны

#### Дедупликация с учётом позиции ^use[]

Переменная из другого файла перезаписывает существующую, если `^use[]` через который она подключена расположен **позже** в текущем файле. Это моделирует рантайм-порядок Parser3.

**Effective offset** — "виртуальный offset" переменной в контексте текущего файла:
- Переменная из текущего файла → effective offset = реальный offset
- Переменная из другого файла → effective offset = offset `^use[]` в текущем файле
- Файлы из auto.p chain / main_auto → effective offset = -1 (до любого кода)

Примеры:
```
$xxx[string]      ← effective offset = 12
^use[f1.p]        ← offset 26. f1.p: $xxx[^hash::create[]]
                    → effective offset = 26. hash перезаписывает string (26 > 12) ✓
$xxx              ← тип = hash
```
```
^use[f1.p]        ← offset 10. f1.p: $xxx[^hash::create[]]
$xxx[string]      ← effective offset = 26. string НЕ перезаписывается (10 < 26) ✓
$xxx              ← тип = string
```

#### buildUseOffsetMap

`P3VariableIndex.buildUseOffsetMap(currentFile)` — строит карту файл → offset `^use[]`:
- Получает `UseInfo` (с offset) из `P3UseFileIndex` для текущего файла
- Резолвит пути через `P3UseResolver`
- Транзитивные файлы (use из подключённого файла) наследуют offset корневого `^use[]`
- Файлы из auto.p chain не попадают в карту → `getOrDefault(file, -1)`

#### Проверка видимости: isVariableVisible()

Единая функция для текущего файла. Для других файлов используется `info.isLocal` из индекса напрямую.

| Проверка | Условие | Результат |
|----------|---------|-----------|
| Разные классы | `info.ownerClass != ctx.ownerClass` | Не видна (кроме MAIN→CLASS) |
| Локальная | `info.isLocal == true` | Видна только в том же методе |
| Иначе | — | Видна |

#### Эквивалентность переменных (filterByContext)

| Контекст | `^var.` ищет | `^self.var.` ищет | `^MAIN:var.` ищет |
|----------|--------------|-------------------|-------------------|
| MAIN без locals | `var`, `self.var`, `MAIN:var` | `var`, `self.var`, `MAIN:var` | `var`, `self.var`, `MAIN:var` |
| MAIN с locals | только `var` | `self.var`, `MAIN:var` | `self.var`, `MAIN:var` |
| @CLASS без locals | `var`, `self.var` | `var`, `self.var` | только `MAIN:var` |
| @CLASS с locals | только `var` | только `self.var` | только `MAIN:var` |

**Пример:**
```parser3
@auto[]
$MAIN:var[^table::create{name}]

@main[]
$var[^hash::create[]]
^MAIN:var.   # → hash (все три формы эквивалентны, берём последнее)

@main[][var]
$var[^hash::create[]]
^MAIN:var.   # → table ($var локальная, $MAIN:var глобальная)
^var.        # → hash (локальная)
```

### P3MethodDocTypeResolver
- **Путь**: `ru.artlebedev.parser3.index.P3MethodDocTypeResolver`
- **Назначение**: Резолв результата метода и типов параметров
- **ВАЖНО**: несмотря на историческое имя, это уже не doc-only resolver
- **Приоритет источников результата метода**:
    1. Вывод по телу метода через `$result[...]` и `^return[...]`
    2. Fallback на документацию `# $result(type)`, только если в теле нет непустого присваивания результата
- **Семантика результата метода**:
    - `^return[...]` для типизации эквивалентен `$result[...]`
    - Пустые формы `$result[]` и `^return[]` не затирают уже найденный тип
    - Итог метода определяется по последней эффективной форме `$result`/`^return`, а не только по тексту последнего `$result[...]`
    - Additive-записи `$result.key[...]`, `$result.key.sub[...]`, `$result.key.rename[...]`, `^result.add[...]` должны попадать в ту же эффективную hash-форму
    - `$result[$localHash]` и `^result.add[$extraHash]` внутри метода должны раскрывать локальные source-переменные на позиции фактической mutation, иначе теряются ключи между присваиваниями
    - Если позднее полное `$result[...]` идёт после additive-записей, оно сбрасывает более ранние additive-ключи по обычным правилам `P3VariableEffectiveShape`
    - Основание из Parser3: write-context для hash автосоздаёт `VHash`, поэтому запись вида `$result.db_name.y[x]` считается созданием вложенной hash-ветки результата
- **Быстрый метод**: `getResultTypeByIndexQuery(methodName, ownerClass, visibleSet)`
    - Прямой запрос в индекс `P3MethodFileIndex` через `processValues()` по ключу `methodName`
    - Фильтрация по `visibleSet` (HashSet для O(1) lookup)
    - Возвращает результат из `MethodInfo` — сначала inferred, затем doc fallback
- **Устаревший метод**: `getMethodResultType(methodName, ownerClass, visibleFiles)`
    - Итерирует `visibleFiles` через `findInFiles()` — O(N)
    - Использовать `getResultTypeByIndexQuery()` где возможно

### Единый резолв значения: P3ResolvedValue + P3VariableIndex
- **Главный инвариант**: completion, chained type resolve, documentation и navigation не должны самостоятельно угадывать тип выражения
- **Единый источник истины**: `P3VariableIndex` + модель `P3ResolvedValue`
- `P3ResolvedValue` хранит полное описание значения:
    - `className`
    - `columns`
    - `sourceVarKey`
    - `hashKeys`
    - `hashSourceVars`
    - `methodName`
    - `targetClassName`
    - `receiverVarKey`
- Через эту модель представляются:
    - обычные переменные
    - alias через `sourceVarKey`
    - hash/table literal
    - результат `^method[]`
    - результат `^Class:method[]`
    - результат `^var.method[]`
    - вложенные цепочки вида `^a.b.method[]`
- **Типы method call origin**:
    - `receiverVarKey != null` → object method
    - `targetClassName != null` → static/class method
    - иначе `methodName` → global method
- `P3VariableIndex` обязан быть тонкой общей точкой входа для:
    - `resolveChainedType()`
    - поиска hash keys
    - поиска table columns
    - `analyzeVariableCompletion()`
    - любых дальнейших capability-checks для variable completion
- `HashEntryInfo.offset` всегда должен трактоваться только вместе с `HashEntryInfo.file`.
  При чтении текущего файла и индексированных видимых файлов `P3VariableIndex` привязывает все `hashKeys` к их `VirtualFile`;
  navigation не должна угадывать файл ключа по текущему файлу или root-переменной.
- Ключи, выведенные только из read-chain (`$data.key` без присваивания), являются синтетическими:
  они могут участвовать в completion/типизации, но не должны иметь location для goto declaration и не должны перетирать location настоящего объявления.
- **Архитектурное правило**: если в UI-точке (`P3VariableCompletionContributor`, `P3VariableMethodCompletionContributor`, documentation, goto declaration) появляется собственная логика вида “а вдруг это hash/table/result метода”, это почти наверняка ошибка архитектуры
- Результат должен всегда доезжать одинаково во всех сценариях:
    - `$res`
    - `$res.`
    - `$res.x.`
    - `^res.`
    - hover / docs
    - goto declaration

### Parser3IdentifierUtils
- **Путь**: `ru.artlebedev.parser3.utils.Parser3IdentifierUtils`
- Единое правило идентификатора для lexer/index/completion/reference-слоя
- **Важно**: идентификатор может начинаться с буквы, цифры или `_`
- Это правило должно одинаково применяться к:
    - именам классов (`@CLASS 222`)
    - именам переменных (`$2var`)
    - всем местам, где раньше были локальные эвристики “первая буква должна быть letter/_”

## Резолв путей

### P3UseResolver
- **Путь**: `ru.artlebedev.parser3.use.P3UseResolver`
- **Единый источник истины** для резолва путей в `@USE` и `^use[]`
- Используется в навигации (`P3GotoDeclarationHandler`) и автокомплите (`P3UseCompletionContributor`)

#### Порядок резолва (приоритет):

1. **Абсолютный путь** (`/path/file.p`) → от `document_root`
2. **Относительный путь** → от директории текущего файла
3. **Fallback для `../`**: если путь содержит `../` и файл не найден → ищем без `../` в текущей директории
4. **CLASS_PATH**: если всё ещё не найден → ищем в директориях из `$CLASS_PATH`

#### Пример fallback для `../`:

```
Структура:
www/
├── file.p           <- есть
└── inner/
    ├── auto.p       <- ^use[../file.p]
    └── file.p       <- есть

^use[../file.p] из www/inner/auto.p:
1. Ищем www/inner/../file.p = www/file.p → НАЙДЕН

Если www/file.p НЕ существует:
1. Ищем www/inner/../file.p = www/file.p → не найден
2. Fallback: ищем file.p в www/inner/ → www/inner/file.p → НАЙДЕН
```

#### Методы:
- `resolve(path, contextFile)` — резолв пути в VirtualFile (для навигации)
- `getCompletionCandidates(partialPath, contextFile)` — список кандидатов (для автокомплита)

### Связанные сервисы

| Сервис | Назначение |
|--------|------------|
| `P3ClassPathEvaluator` | Получение списка CLASS_PATH директорий |
| `P3ClassPathExtractor` | Парсинг $CLASS_PATH из кода |
| `Parser3ProjectSettings` | Получение document_root из настроек |

## Навигация

### P3GotoDeclarationHandler
- **Путь**: `ru.artlebedev.parser3.navigation.P3GotoDeclarationHandler`
- Обрабатывает Ctrl+Click

#### Поддерживаемые сценарии:
1. **Вызов метода** → переход к объявлению
    - `^method[]` → `@method[]`
    - `^Class:method[]` → `@method[]` в классе
    - `^self.method[]` → `@method[]` в текущем классе
    - `^MAIN:method[]` → `@method[]` в MAIN
    - `^BASE:method[]` → `@method[]` в базовом классе

2. **Объявление метода** → переход к вызовам (обратная навигация)
    - `@method[]` → список всех `^method[]` вызовов
    - Учитывает наследование (показывает вызовы из наследников)
    - При одном результате — прямой переход
    - При нескольких — popup со списком

3. **Объявление класса** → переход к использованиям (обратная навигация)
    - `@CLASS ClassName` → список всех `^ClassName::` и `^ClassName:` вызовов
    - Показывает классы-наследники
    - Показывает вызовы из файлов с @autouse

4. **USE-путь** → переход к файлу
    - `@USE path` → файл (через `P3UseResolver`)
    - `^use[path]` → файл (через `P3UseResolver`)

5. **Имя класса в @BASE** → переход к классу

6. **Переменная $var** → переход к определению (первому присваиванию)
    - `$var` → `$var[^ClassName::...]`
    - `$self.var` → `$self.var[^ClassName::...]`
    - `$MAIN:var` → `$MAIN:var[^ClassName::...]`
    - `$BASE:var` → переменная из @BASE класса (без текущего)
    - `$result` → `$result[...]` (IMPORTANT_VARIABLE, **всегда локальна в пределах текущего метода** — `$result` в `@main[]` и `$result` в `@method[]` это разные переменные, навигация и резолв ищут определение только внутри текущего метода)
    - Поиск через `P3VariableIndex.findVariableDefinitionLocation()`
    - Те же правила видимости что и для `^var.method[]`
    - **Fallback на @GET_**: если переменная не найдена, ищется `@GET_varName` в классе (для `$self.prop`, `$BASE:prop`)
    - **Навигация на себя**: если `location.offset == dollarOffset` — клик на определении, fallback на `navigateToMethodParameter()`
    - **ВАЖНО**: Для IMPORTANT_VARIABLE (`$result`) `dollarOffset` берётся от предшествующего DOLLAR_VARIABLE токена

#### Затенение параметрами метода

Параметр метода затеняет одноимённую внешнюю переменную. Вся логика — **единственное место** — шаг 3 в `getVisibleVariables()`:

1. После сбора переменных из текущего и других файлов — добавляются параметры текущего метода с `isMethodParam=true`
2. Если в `dedup` уже есть локальное присваивание **внутри текущего метода** (offset в `[method.start, method.end)`) — параметр не перезаписывает его
3. `filterByContext()`: `isMethodParam=true` переменная проходит только в `contextType="normal"` (не в self/MAIN/BASE)
4. `filterByContext()`: существующая `isMethodParam=true` запись не перезаписывается другой переменной с тем же именем

`findParameterType(varName, file, offset)` — определяет тип параметра из документации `# $param(type)`. Вызывается из шага 3.
`navigateToMethodParameter()` — ищет offset параметра в `@method[param]` из текста PsiFile (для навигации Ctrl+Click).

7. **Вызов метода объекта** `^var.method[]` → переход к `@method[]` в классе переменной
    - Тип переменной определяется через `P3VariableIndex.resolveChainedType()`
    - Поиск метода через `handleClassMethodCall()` с рекурсией по `@BASE`
    - **ВАЖНО**: `P3MethodCallElement.getReferences()` возвращает `EMPTY_ARRAY` для `objectMethodCall`,
      чтобы `P3MethodReference` не перехватывал навигацию (он не учитывает тип переменной)

#### Чтение текста файлов: readFileTextSmart

`P3VariableIndex.readFileTextSmart(file)` — чтение текста с приоритетом Document:
1. Проверяет `FileDocumentManager.getCachedDocument(file)` → берёт из Document (несохранённые изменения)
2. Если Document нет → читает с диска через `readFileText(file)`

**ВАЖНО**: Все методы, которые парсят текст **текущего файла** в P3VariableIndex, должны использовать `readFileTextSmart()`:
- `getVisibleVariables()` — парсинг переменных и параметров метода (шаг 3)
- `findParameterType()` — тип параметра из документации (вызывается из шага 3)
- `findParamOffsetInMethod()` — offset параметра в сигнатуре метода (вызывается из шага 3)

`readFileText(file)` (чтение с диска) используется только для **других** файлов, когда Document не закеширован.

## Видимость файлов

### P3VisibilityService
- **Путь**: `ru.artlebedev.parser3.visibility.P3VisibilityService`
- Низкоуровневый граф подключённых файлов
- Порядок: main_auto → auto.p chain → USE файлы → текущий файл
- Не знает про позицию курсора, режим автокомплита и `@OPTIONS locals`

### P3ScopeContext
- **Путь**: `ru.artlebedev.parser3.visibility.P3ScopeContext`
- Единственный production API для позиционной видимости IDE-поверхностей
- Строится как `new P3ScopeContext(project, currentFile, cursorOffset)`
- Даёт отдельные списки:
    - `getMethodSearchFiles()`
    - `getClassSearchFiles()`
    - `getVariableSearchFiles()`
- Учитывает режим "Все методы" / "Только через use", `^use[]` ниже курсора, `@autouse[]`, текущий класс и унаследованный `@OPTIONS locals`

### Обратная видимость
- Для классов: `P3ScopeContext.getReverseClassSearchFiles(project, sourceFile)`
- Для методов: `P3ScopeContext.filterFilesThatCanSeeMethod(project, sourceFile, ownerClassName, candidateFiles)`
- Используется для поиска вызовов метода/класса при клике на определении
- **ВАЖНО**: Это обратная операция — не "что видно из F", а "откуда виден F"

### Режимы (Parser3ProjectSettings.MethodCompletionMode)
- `USE_ONLY` — только через @USE и auto.p
- `ALL_METHODS` — все файлы проекта

### @autouse
- Если `@autouse[]` виден из файла — все классы проекта доступны
- Проверка: `new P3ScopeContext(project, file, cursorOffset).hasAutouse()`
- **Важно**: @autouse делает ВСЕ классы видимыми, независимо от CLASS_PATH
- `@autouse[]` влияет только на классы, не на обычные методы

### CLASS_PATH и видимость классов
- Без @autouse класс виден если:
    1. Подключен напрямую через `@USE /full/path/Class.p`
    2. ИЛИ подключен через `^use[Class.p]` + директория класса есть в CLASS_PATH
- CLASS_PATH позволяет резолвить короткие имена, но НЕ подключает файлы автоматически

## Автодетекция настроек

### P3SettingsAutoDetectionActivity
- **Путь**: `ru.artlebedev.parser3.settings.P3SettingsAutoDetectionActivity`
- Реализует `StartupActivity.DumbAware`
- При открытии проекта автоматически определяет:
    - `document_root` — ищет директорию с auto.p
    - `main_auto` — ищет auto.p в document_root
- Флаг выполнения хранится в `Parser3ProjectSettings.settingsAutoDetected`

## Лексер

### Экранирование символом ^

В Parser3 символ `^` используется для экранирования специальных символов. Экранированный символ теряет своё специальное значение.

### Кавычки не образуют строковый режим

Для Parser3 нельзя переносить модель из JavaScript/Java/PHP, где `'...'` и `"..."` включают особый строковый режим парсера.
В контексте плагина это важное правило:

- одинарные и двойные кавычки сами по себе не отключают разбор Parser3-конструкций;
- вызовы `^method[]`, переменные `$var`, pseudo-hash `$.key[]` и другие конструкции могут находиться внутри текста с кавычками и всё равно должны распознаваться;
- блокировать разбор должен только реальный механизм экранирования через `^`.

Примеры:

```parser3
$var["^taint[as-is]"]     # ^taint[] должен распознаваться
$sql['$form:name']        # $form:name должен распознаваться
^^taint[as-is]            # НЕ вызов метода, а экранированный текст
```

Практическое следствие для кода плагина:

- нельзя строить эвристику вида `inSingleQuote/inDoubleQuote` и на её основе отключать разбор контекста;
- если нужно понять, является ли символ служебным, надо проверять только экранирование через `Parser3LexerUtils.isEscapedByCaret(...)` или эквивалентную общую логику;
- любые баги вида "внизу файла completion пропал из-за кавычек выше" означают неверную модель синтаксиса.

#### Экранируемые символы:
- `^'` — экранированная одинарная кавычка (не начинает строку)
- `^"` — экранированная двойная кавычка (не начинает строку)
- `^(` `^)` — экранированные круглые скобки
- `^[` `^]` — экранированные квадратные скобки
- `^{` `^}` — экранированные фигурные скобки
- `^;` — экранированная точка с запятой
- `^$` — экранированный знак доллара (не начинает переменную)
- `^^` — экранированный карет (выводит один `^`)
- `^#HH` — символ по hex-коду, где `H` это `0-9`, `A-F` или `a-f`
- `^#` без двух hex-цифр — экранированная решётка, а не битый hex-код

В оригинальном Parser3 `^#00` запрещён как `BAD_HEX_LITERAL`.
Плагин на уровне лексера сейчас не вводит диагностику этой ошибки: все `^#HH`, включая `^#00`, выделяются одним токеном `HEX_ESCAPE`.

#### Правило чётности:
**Нечётное** количество `^` перед символом = символ экранирован.
**Чётное** количество `^` = символ НЕ экранирован (каретки экранируют друг друга).

```parser3
^'text'     # ^' — экранирована, это НЕ строка, выводит 'text'
^^'text'    # ^^ = один ^, затем ' начинает строку
^^^'text'   # ^^^ = один ^ + экранированная ', выводит ^'text'
```

#### Реализация:

**Общий класс**: `Parser3LexerUtils`

```java
// Проверка экранирования — нечётное количество ^ перед позицией
public static boolean isEscapedByCaret(CharSequence text, int pos) {
	if (pos <= 0) return false;
	int count = 0;
	int i = pos - 1;
	while (i >= 0 && text.charAt(i) == '^') {
		count++;
		i--;
	}
	return (count % 2) != 0;
}

// Поиск парной скобки с учётом строк и экранирования
public static int findMatchingBracket(CharSequence text, int openPos, int limit,
									  char openCh, char closeCh) {
	// Учитывает:
	// 1. Экранирование ^( ^) ^' ^" и т.д.
	// 2. Строковые литералы '...' и "..."
	// 3. Вложенность скобок
}
```

**Использование**: Все лексеры используют общие методы из `Parser3LexerUtils`:
- `Parser3LexerCore` — общая логика
- `Parser3MethodCallAndVariblesLexerCore` — обработка `^method()` и `$var()`
- `Parser3SqlBlockLexerCore` — обработка SQL-блоков
- `Parser3BracketLexerCore` — выделение скобок

**ВАЖНО**: Логика экранирования должна быть ОДИНАКОВОЙ везде. Все методы поиска парных скобок и проверки кавычек должны использовать `Parser3LexerUtils.isEscapedByCaret()`.

### Токены (Parser3TokenTypes)
- `DEFINE_METHOD` — `@methodName` (объявление метода)
- `METHOD` — `^methodName` (вызов метода)
- `CONSTRUCTOR` — `^ClassName::method`
- `SPECIAL_METHOD` — `@CLASS`, `@BASE`, `@USE`, etc.
- `IMPORTANT_METHOD` — `^MAIN:`, `^BASE:`, `^self.`

## Инжекции языков (SQL, CSS, JS)

### Общие принципы

Плагин поддерживает инжекцию SQL, CSS и JavaScript внутри Parser3 кода. Инжекторы создают "виртуальные документы" для целевого языка, заменяя Parser3 конструкции пробелами.

### Файлы:
- `InjectorUtils` — общая утилита для сбора частей инжекции
- `Parser3SqlInjector` — инжекция SQL в `^table::sql{...}`
- `Parser3CssInjector` — инжекция CSS в `<style>...</style>`
- `Parser3JsInjector` — инжекция JS в `<script>...</script>`
- `Parser3PostProcessorHtml` — разметка CSS_DATA и JS_DATA токенов в лексере

### Правила фильтрации Parser3 конструкций

#### Что оставляем в инжекции (заменяя Parser3 на пробелы):

1. **Фигурные скобки после `]` или `)` — это вывод:**
   ```parser3
   ^data.foreach[k;v]{
       CSS/JS код здесь  ← ОСТАВЛЯЕМ
   }
   ```

2. **Фигурные скобки после METHOD (без `[]`/`()` перед ними) — это вывод:**
   ```parser3
   ^list.menu{
       CSS/JS код здесь  ← ОСТАВЛЯЕМ
   }
   ```

#### Что вырезаем полностью:

1. **Содержимое квадратных скобок `[...]` — это параметры:**
   ```parser3
   ^method[параметры]     ← вырезаем всё включая ^method
   ^data.foreach[k;v]{    ← [k;v] вырезаем, {} оставляем
   ```

2. **Содержимое круглых скобок `(...)` — это параметры:**
   ```parser3
   ^method(параметры)     ← вырезаем всё
   ^if($condition){       ← ($condition) вырезаем, {} оставляем
   ```

3. **Переменные полностью — это присваивание или обращение:**
   ```parser3
   $var{...}              ← вырезаем всё (присваивание)
   $var[...]              ← вырезаем всё (обращение к хешу)
   $var(...)              ← вырезаем всё
   $var.field             ← вырезаем всё
   $color                 ← вырезаем
   ```

4. **Вызовы методов с точкой:**
   ```parser3
   ^obj.method[]          ← вырезаем всё
   ^data.foreach[]{...}   ← ^data.foreach[] вырезаем, {} оставляем
   ```

### Реализация в лексере (Parser3PostProcessorHtml)

Метод `markStyleScriptContent()` использует **стек типов скобок** для отслеживания контекста:

**Типы в стеке:**
- `PARAM` — `[...]` или `(...)` — параметры, всё внутри вырезаем
- `VAR_BRACE` — `$var{...}` — присваивание, всё внутри вырезаем
- `OUTPUT_BRACE` — `^method[]{}` — блок вывода Parser3, содержимое CSS/JS
- `CSS_BRACE` — `.item {}` — CSS/JS фигурная скобка

**ВАЖНО: Правило пробелов при присваивании:**
```parser3
$var{value}    # Присваивание — НЕТ пробела между $var и {
$var {value}   # НЕ присваивание — вывод $var + CSS скобка
```

Метод `isPrecededByVariable()` проверяет что **непосредственно** перед `{` (без пробелов!) стоит цепочка `$var` или `$var.field.x`.

**Правила конвертации токенов в CSS_DATA/JS_DATA:**

| Ситуация | Конвертировать? |
|----------|-----------------|
| `{` после `]` или `)` | НЕТ — OUTPUT_BRACE (Parser3 блок вывода) |
| `{` после `$var` (без пробела) | НЕТ — VAR_BRACE (присваивание) |
| `{` внутри PARAM или VAR_BRACE | НЕТ — вложенная Parser3 скобка |
| `{` иначе | ДА — CSS_BRACE |
| `}` закрывает CSS_BRACE | ДА — CSS/JS скобка |
| `}` закрывает другое | НЕТ — Parser3 скобка |
| `;` внутри PARAM | НЕТ — разделитель параметров |
| `;` иначе | ДА — CSS/JS |
| HTML_DATA | ДА — всегда конвертируем |

### Реализация в инжекторах (InjectorUtils)

Метод `collectParts()` собирает хост-элементы (CSS_DATA, JS_DATA, SQL_BLOCK) и формирует префиксы:

```java
// Хост-элемент — добавляем в результат
if (hostClass.isInstance(e)) {
		result.add(new InjectionPart(host, pending.toString(), ""));
		pending.setLength(0);
lastWasParser3 = false;
		continue;
		}

// WHITE_SPACE — сохраняем как есть
		if (type == WHITE_SPACE) {
		pending.append(t);
lastWasParser3 = false;
		continue;
		}

// Parser3 конструкции — заменяем группу на один пробел
		if (!lastWasParser3) {
		pending.append(" ");
lastWasParser3 = true;
		}
```

### Особый случай: SQL и переменные

В SQL инжекции переменные `$var` работают особым образом — внутри `$var{...}` может быть вложенный SQL:

```parser3
^void:sql{
    SELECT * FROM $table WHERE id IN (
        $subquery{SELECT id FROM other}
    )
}
```

SQL инжектор обрабатывает это через `Parser3SqlBlock` PSI элементы, которые уже корректно разделены лексером.

### HTML инжектор и теги style/script

`Parser3HtmlInjector` собирает HTML_DATA токены в виртуальный HTML документ, но **игнорирует контент внутри `<style>` и `<script>`**:

- Теги `<style>` и `</style>` — попадают в HTML
- Контент между ними — заменяется пробелами (обрабатывается CSS инжектором)
- Аналогично для `<script>`

Это предотвращает попадание CSS/JS кода (и Parser3 конструкций внутри них) в HTML виртуальный документ.

### SQL: Fallback на PlainText

Если SQL плагин недоступен (Community Edition), SQL инжектор использует `Language.findLanguageByID("TEXT")` как fallback. Это даёт подсветку фона для injected fragment, хотя без синтаксиса SQL.

### Пример виртуального документа

Исходный код:
```parser3
<style>
    .item {
        color: $color;
        font-weight: bold;
        ^styleMethod[]
    }
    .list {
        ^data.foreach[k;v]{
            .list-item-$k {
                color: $v.color;
            }
        }
    }
</style>
```

Виртуальный CSS документ:
```css
    .item {
	color:  ;
	font-weight: bold;

}
.list {

	.list-item-  {
		color:   ;
	}

}
```

## Форматирование

### Hash-комментарии (#)

В Parser3 есть два типа комментариев:

#### 1. Строковые комментарии (# в колонке 0)
**ВАЖНО**: Строковый комментарий — это `#` СТРОГО в колонке 0 (первый символ строки). Если перед `#` есть пробел или таб — это НЕ комментарий, а обычный текст.

```parser3
# это комментарий
	# это НЕ комментарий (есть таб перед #)
```

#### 2. Комментарии в выражениях
Внутри круглых скобок (в выражениях) можно использовать `#` для комментариев:
```parser3
^if(
    $condition    # проверка условия
    && $another   # ещё проверка
){...}
```
Эти комментарии НЕ обрабатываются EnterHandler — только строковые комментарии в колонке 0.

### Форматирование строковых комментариев

#### Файлы:
- `Parser3Block` — основной форматтер, определяет отступы
- `Parser3EnterHandler` — обработка Enter в комментариях
- `Parser3HashCommentPreFormatProcessor` — сохранение отступов перед форматированием
- `Parser3HashCommentPostFormatProcessor` — восстановление отступов после форматирования

#### Parser3Block — определение комментария:
```java
// Комментарий только если # в колонке 0
boolean isHashComment = li.originalLevel == 0 && li.text.startsWith("#");

// При paste первая строка может получить отступ от IDE
// Проверяем символ на позиции range.start
if (formattingRange != null && li.startOffset < formattingRange.getStartOffset()) {
int rangeStartInLine = formattingRange.getStartOffset() - li.startOffset;
    if (li.text.charAt(rangeStartInLine) == '#') {
isHashComment = true;  // В буфере строка начиналась с #
		}
		}
```

#### Parser3Block — отступы для комментариев:
- Hash-комментарии ВСЕГДА остаются в колонке 0
- `computeIndentColumnsForLine()` возвращает 0 для `hashComment = true`

#### Parser3EnterHandler — поведение Enter:

| Ситуация | Условие | Результат |
|----------|---------|-----------|
| CASE COMMENT 1 | `###` + следующая строка `@method` | Создаёт блок документации |
| CASE COMMENT 2 | Текущая и предыдущая строки — комментарии | Продолжает с тем же префиксом |
| CASE COMMENT 3 | Только текущая строка — комментарий | Выход из комментария |

#### getCommentPrefix() — извлечение префикса:
Извлекает все `#` и пробелы до первого непробельного не-`#` символа:
- `"### # #\t\ttext"` → `"### # #\t\t"`
- `"##  comment"` → `"##  "`
- `"#text"` → `"# "` (добавляет пробел)

#### Paste и комментарии:
При paste IDE добавляет отступ к первой строке. `Parser3HashCommentPreFormatProcessor` вычисляет реальный отступ из буфера:
```java
int addedByIDE = startOffset - firstLineStart;
int realIndentLen = indentLen - addedByIDE;  // Для первой строки
```

## Регекспы для индексации

### Объявления методов (P3MethodFileIndex)
```regex
^@([\p{L}_][\p{L}0-9_]*)(?:\s*\[([^\]]*?)\]|\s*$|\s+)
```

### Вызовы методов (P3MethodCallFileIndex)
```regex
\^(?:(MAIN:|BASE::|BASE:|([A-Z][\p{L}0-9_]*)::|([A-Z][\p{L}0-9_]*):)|(self\.))?
([\p{L}_][\p{L}0-9_]*)\s*\[
```

### Классы (P3ClassFileIndex)
```regex
@CLASS\s*[\r\n]+\s*([\p{L}_][\p{L}0-9_]*)
```

### Типы переменных (P3VariableTypeFileIndex)

**ВАЖНО**: Ранее использовались regex-паттерны. Теперь заменены на посимвольный парсер `P3VariableParser.parse()` — см. секцию P3VariableTypeFileIndex выше.

## Автокомплит

### P3MethodCompletionContributor
- Автокомплит методов после `^`
- `invokeAutoPopup()` — определяет когда показывать popup
- **ВАЖНО**: Для работы внутри `$var[^method]` нужно проверять `^` после последней `[`, а не во всём тексте
- Добавляет переменные с типами (`^varName.`) как кандидаты автокомплита через `addVariablesWithTypes()`
- Переменные добавляются для контекстов `normal`, `self` и `MAIN` — во всех трёх случаях список переменных одинаков (в MAIN `$var` = `$self.var` = `$MAIN:var`)
- **ВАЖНО**: `addVariablesWithTypes` не вызывается при пустом prefix (нет `^` в контексте), иначе показываются лишние переменные

#### extractMethodPrefix — извлечение ^-цепочки

Ищет `^` назад от курсора. Стоп-условия (только вне `[...]`):
- Пробел, таб, перенос строки — прерывают цепочку
- Скобки `[` `]` — НЕ прерывают (часть обращения `^data.[field name].field.[$var].`)

**ВАЖНО**: При обратном проходе `]` увеличивает глубину скобок, `[` уменьшает. Пробел внутри `[...]` не прерывает цепочку.

#### Парсинг ^var.prop.subprop — lastIndexOf

Для цепочек используется `lastIndexOf('.')`, а не `indexOf('.')`:
- `^list.name.` → `varKey="list.name"`, `methodPrefix=""` → резолв: list=table, name=string → методы string
- `^user.address.city.` → `varKey="user.address.city"`, `methodPrefix=""` → цепочка резолвится через `resolveChainedType`

**ВАЖНО**: `indexOf('.')` давал `varKey="list"`, `methodPrefix="name."` → бесконечный цикл (показывал колонки table вместо методов string).

### P3ClassCompletionContributor
- Автокомплит классов после `^ClassName:`
- **ВАЖНО**: Проверка `[` должна быть только после последней `^`, иначе `$var[^Class` не работает

### P3UseCompletionContributor
- Автокомплит путей в `^use[...]` и `@USE`
- Использует `P3UseResolver.getCompletionCandidates()` для единой логики резолва

### P3VariableCompletionContributor
- Автокомплит после `$` — предлагает имена переменных
- Автокомплит после `$var.` — предлагает свойства объекта (переменные класса + @GET_ геттеры)
- **Определение контекста** через `VarPrefix`:
    - `$u` → contextType="normal", prefix="u"
    - `$self.u` → contextType="self", prefix="u"
    - `$MAIN:u` → contextType="MAIN", prefix="u"
    - `$BASE:u` → contextType="BASE", prefix="u"
    - `$user.m` → contextType="objectMethod", prefix="m", varKey="user"
    - `$self.user.m` → contextType="objectMethod", prefix="m", varKey="self.user"
- Для `objectMethod` вызывает `P3VariableMethodCompletionContributor.addClassProperties()`
- Для `BASE` вызывает `addBaseClassVariableCompletions()` — свойства @BASE класса (переменные + @GET_)
- Использует `buildClassHierarchy()` для передачи в `filterByContext()` — обеспечивает видимость переменных @BASE классов через `$self.`

### P3VariableMethodCompletionContributor
- Утилитный класс для добавления методов/свойств класса в автокомплит
- **Единая точка входа**: `completeVariableDot(project, varKey, currentFile, offset, result, caretDot)`
- **Ленивая загрузка visibleFiles**: visibleFiles НЕ вычисляются до тех пор, пока тип не найден fast path:
    1. `findVariableInCurrentFileOnly(varKey)` — если тип найден в текущем файле → visibleFiles не нужны
    2. Только если fast path вернул null → вычисляются visibleFiles и делается полный поиск
    - **Результат**: `$list.` для `$list[^table::create{...}]` в том же файле = 0 файлов сканировано
- **Два режима**:
    - `addClassMethods()` — методы + переменные с точкой (для `^var.`)
    - `addClassProperties()` — только свойства: переменные без точки + @GET_ геттеры (для `$var.`)

#### Различие `^var.` vs `$var.`

| Контекст | Что показывается | Точка | Метод |
|----------|-----------------|-------|-------|
| `^user.` | Методы + переменные + колонки (с точкой) + пользовательские шаблоны | `varName.` | `addClassMethods()` |
| `$user.` | Только свойства (@GET_ + $self.var) + колонки (без точки) | `varName` | `addClassProperties()` |

#### Колонки таблиц в автокомплите

Если тип переменной = `table` и колонки проиндексированы:
- `^list.` → колонки с точкой: `name.`, `uri.` (для продолжения цепочки)
- `$list.` → колонки без точки: `name`, `uri`
- Колонки со спецсимволами: `[some column name].` / `[some column name]`
- Тип колонки: `string` (typeText в автокомплите)

#### Резолв цепочек типов (resolveChainedType)

`P3VariableIndex.resolveChainedType()` резолвит цепочки через точку:
- `list` → `table` (простая переменная)
- `list.name` → `string` (колонка таблицы = string)
- `user.address` → тип `$self.address` в классе User

**ВАЖНО**: Для `table` + колонка → тип = `string`. После `^list.name.` предлагаются методы `string`, а не снова колонки table.

#### Пользовательские шаблоны

`completeVariableDot()` для `caretDot=true` вызывает `Parser3CompletionContributor.fillUserTemplates(result, ".")` — добавляет пользовательские шаблоны с `prefix="."` (например `.foreach[key;value]{...}`).

#### Фильтрация переменных класса (`addClassVariables`)

Переменные класса — это `$self.var[^Type::]`. Простые `$var` глобальны только если:
1. Нет `@OPTIONS locals` у класса
2. Нет `[locals]` у метода где переменная объявлена
3. Переменная не в списке `[var1;var2]` у метода

Проверка через `MethodBoundary.isVariableLocal()` и `Parser3ClassUtils.hasClassLocals()`.

### P3VariableInsertHandler

InsertHandler для переменных — удаляет остаток старого имени после курсора при вставке из автокомплита.

**Стоп-символы**: `[ ( { ] ) } пробел таб ^ $ \n \r ; .`

**Используется в**:
- `P3VariableCompletionContributor` — для `$var`, `$self.var`, `$MAIN:var`, `$BASE:var`
- `P3VariableMethodCompletionContributor.addClassProperties()` — для свойств `$var.prop`
- `P3VariableMethodCompletionContributor.addGetterProperties()` — для @GET_ свойств

**Дедупликация точки**: Inline InsertHandler в `addVariablesWithTypes()` и `addClassVariables()` (для `^var.` с точкой) дополнительно проверяет и удаляет дублирующую точку: если lookup string = `"var."` и после вставки следующий символ тоже `.` — удаляет дубль.

**Сравнение с P3MethodInsertHandler**:

| Handler | Для чего | Удаляет остаток | Добавляет скобки |
|---------|----------|-----------------|------------------|
| `P3MethodInsertHandler` | Методы (`^method`) | Да | Да (`[]`, `()`, `{}`) |
| `P3VariableInsertHandler` | Переменные (`$var`) | Да | Нет |


## Injected Languages (HTML, CSS, JS)

### Обзор

Parser3 файлы содержат встроенные языки:
- **HTML** — весь текст вне Parser3 конструкций
- **CSS** — внутри `<style>...</style>`
- **JS** — внутри `<script>...</script>`
- **SQL** — внутри `^void:sql{...}` и других SQL-методов

IDE "инжектирует" эти языки в Parser3 файл, создавая **виртуальные документы** для подсветки, автокомплита и навигации.

### Архитектура инжекции

```
┌─────────────────────────────────────────────────────────────┐
│  Parser3 файл (host document)                               │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ <style>                                                │ │
│  │   .item { color: $color; }  ← CSS injection            │ │
│  │ </style>                                               │ │
│  │ <div class="item">^getText[]</div>  ← HTML injection   │ │
│  └────────────────────────────────────────────────────────┘ │
│                            ↓                                │
│  ┌─────────────────┐  ┌─────────────────┐                   │
│  │ Virtual CSS doc │  │ Virtual HTML doc│                   │
│  │ .item { color:  │  │ <style>...</style>                 │
│  │   ; }           │  │ <div class="item"> </div>          │
│  └─────────────────┘  └─────────────────┘                   │
└─────────────────────────────────────────────────────────────┘
```

### Инжекторы

| Инжектор | Язык | Хост-класс | Триггер |
|----------|------|------------|---------|
| `Parser3HtmlInjector` | HTML | `Parser3HtmlBlock` | HTML_DATA токены |
| `Parser3CssInjector` | CSS | `Parser3CssBlock` | CSS_DATA внутри `<style>` |
| `Parser3JsInjector` | JavaScript | `Parser3JsBlock` | JS_DATA внутри `<script>` |
| `Parser3SqlInjector` | SQL | `Parser3SqlBlock` | SQL_BLOCK внутри sql-методов |

### InjectorUtils — единый источник истины

**Путь**: `ru.artlebedev.parser3.injector.InjectorUtils`

Все инжекторы используют единую функцию очистки Parser3 конструкций:

```java
// Для CSS, JS, SQL — между границами
List<InjectionPart<T>> parts = InjectorUtils.collectParts(
				hostClass, startBoundary, endBoundary);

// Для HTML — по offset с обработкой style/script
List<InjectionPart<Parser3HtmlBlock>> parts = InjectorUtils.collectPartsForHtml(
		file, startOffset, endOffset);
```

**Логика очистки:**
- Токены целевого языка — сохраняются как есть
- WHITE_SPACE — сохраняется как есть
- Parser3 конструкции — заменяются на ОДИН пробел (группа подряд → один пробел)

### Проблема координат в Injected документах

**ВАЖНО**: Injected документ имеет ДРУГИЕ координаты чем host документ!

```
Host document (Parser3):     position 150
                                 ↓
"<style>.item { color: $color; }</style><div>..."
                                 ↓
Injected CSS document:       position 50
".item { color:  ; }"
```

При работе с injected документами:
- `editor.getDocument()` — возвращает `DocumentWindow` (injected)
- `parameters.getOffset()` — может быть в координатах HOST документа
- Это вызывает `StringIndexOutOfBoundsException`!

### Решение: проверка границ

В CompletionContributor'ах и других местах:

```java
CharSequence text = parameters.getEditor().getDocument().getCharsSequence();
int offset = parameters.getOffset();

// ОБЯЗАТЕЛЬНО: проверка границ для injected документов
if (offset > text.length()) {
		return; // или offset = text.length();
		}
```

### EnterHandler и Injected документы

`Parser3EnterHandler` должен работать с host документом при нажатии Enter внутри `<style>` или `<script>`:

```java
if (document instanceof DocumentWindow) {
DocumentWindow injectedDoc = (DocumentWindow) document;

// Находим host файл
PsiElement context = file.getContext();
PsiFile hostFile = context.getContainingFile();
Document hostDocument = PsiDocumentManager.getInstance(project)
		.getDocument(hostFile);

// Конвертируем координаты
int hostOffset = injectedDoc.injectedToHost(injectedOffset);

// Находим host editor для позиционирования каретки
FileEditorManager fem = FileEditorManager.getInstance(project);
    for (FileEditor fe : fem.getAllEditors()) {
		if (fe instanceof TextEditor) {
Editor e = ((TextEditor) fe).getEditor();
            if (e.getDocument() == hostDocument) {
hostEditor = e;
                break;
						}
						}
						}

// Работаем с host документом и host editor
document = hostDocument;
file = hostFile;
}
```

### CSS Class Reference и Автокомплит

`Parser3CssClassReferenceContributor` обеспечивает:

1. **Навигацию** — клик на `class="item"` → переход к `.item` в `<style>`
2. **Автокомплит** — предложение классов при вводе в `class="..."`

**Текущая модель:** самописный CSS-resolve в плагине работает только для классов из `<style>` внутри Parser3-файлов через `P3CssClassFileIndex` и `P3CssClassIndex`. Внешние `css/scss/less` файлы специально не сканируются кодом плагина: их обрабатывает встроенная CSS-поддержка IDE. Это ограничение введено специально, чтобы не читать весь CSS-проект из daemon inspections и не ловить фризы на больших проектах.

**Важно для автокомплита нескольких классов:**

```html
<div class="class1 class2 class3">
```

Каждый класс должен иметь свой `TextRange`:

```java
// НЕПРАВИЛЬНО: весь атрибут как один reference
refs.add(new CssClassReference(attrValue, "class1 class2"));

// ПРАВИЛЬНО: отдельный reference с TextRange для каждого класса
TextRange range1 = new TextRange(1, 7);  // "class1"
TextRange range2 = new TextRange(8, 14); // "class2"
refs.add(new CssClassReference(attrValue, "class1", range1));
		refs.add(new CssClassReference(attrValue, "class2", range2));
```

### Отладка инжекций

Включение логирования в `InjectorDebug`:

```java
public class InjectorDebug {
	public static final boolean HTML_INJECTION_LOG = true;
	public static final boolean CSS_INJECTION_LOG = true;
	public static final boolean JS_INJECTION_LOG = true;
	public static final boolean SQL_INJECTION_LOG = true;
}
```

Виртуальный документ логируется при первом хосте:

```java
if (InjectorDebug.CSS_INJECTION_LOG && parts.get(0).host == host) {
String virtualDoc = InjectorUtils.buildVirtualDocument(parts);
    InjectorDebug.logVirtualDocument("CSS", virtualDoc);
}
```

## Свойства встроенных классов ($class:field)

### Справочник свойств — Parser3BuiltinMethods.PROPERTIES

**Путь**: `ru.artlebedev.parser3.lang.Parser3BuiltinMethods`

Справочник `PROPERTIES` хранит свойства встроенных классов: `$form:fields`, `$env:REMOTE_ADDR`, `$response:body` и т.д.

**Структура BuiltinCallable для свойств:**
- `name` — имя свойства (`fields`, `REMOTE_ADDR`)
- `description` — описание
- `url` — ссылка на документацию parser.ru
- `returnType` — тип значения (`hash`, `string`, `int`, `table`)
- `assignSuffix` — суффикс для присваивания (`"[]"`, `"{}"`, `"()"`, `null` = только чтение)

**Зарегистрированные классы с свойствами:**
cookie, env, form, request, console, response, status

**Регистрация:**
```java
registerProperties("response",
    prop("body", "Задание нового тела ответа", "url", "string", "[]"),  // с присваиванием
    prop("headers", "Заголовки HTTP-ответа", "url", "hash")             // только чтение
);
```

**Перегрузки prop():**
- `prop(name, description, url)` — без типа
- `prop(name, description, url, returnType)` — с типом, только чтение
- `prop(name, description, url, returnType, assignSuffix)` — с типом и присваиванием

### Автокомплит свойств встроенных классов

#### Контекст $form: (переменные)

**Файл:** `P3VariableCompletionContributor`

1. `extractVarPrefix` определяет контекст `builtinClassProp` при `$form:f`
2. `addBuiltinClassPropCompletions` показывает свойства с учётом `assignSuffix`:
    - Если `assignSuffix != null` — InsertHandler вставляет скобки с курсором внутри: `body[<cursor>]`
    - Если `assignSuffix == null` — стандартный `P3VariableInsertHandler`

**Плавная фильтрация $form:f:**
- `addBuiltinClassNameCompletions` добавляет `form:` в normal контексте
- `result.restartCompletionOnPrefixChange(string().contains(":"))` перезапускает completion при вводе `:`
- При рестарте `extractVarPrefix` возвращает `builtinClassProp` → показываются `fields`, `files` и т.д.

#### Контекст ^form: (методы)

**Файл:** `P3MethodCompletionContributor`

- `addBuiltinClassesWithProperties` добавляет `form:` в автокомплит после `^`
- `restartCompletionOnPrefixChange(string().contains(":"))` перезапускает при вводе `:`
- При рестарте свойства показываются из `P3ClassCompletionContributor` (блок `typedText.contains(":")`)

**Файл:** `P3ClassCompletionContributor`

- В `addClassNames`: блок `typedText.contains(":") && !fldPrefix.contains(".")`
- Свойства всегда вставляются с точкой: `fields.` + `scheduleAutoPopup`
- Если `fldPrefix` содержит `.` — свойства НЕ показываются (другой контекст)

#### Контекст ^form:fields. (методы по типу свойства)

**Файл:** `P3MethodCompletionContributor`

- В `addUserDefinedMethods`: паттерн `ClassName:field.method`
- Резолвит тип `field` через `Parser3BuiltinMethods.getPropertiesForClass()`
- Вызывает `P3VariableMethodCompletionContributor.completeForKnownType(resolvedType)`

**Файл:** `P3VariableMethodCompletionContributor`

- `completeForKnownType()` — показывает методы/свойства для известного типа без резолва переменной
- `completeVariableDot()` — fallback для `varKey="form:fields"` через `getPropertiesForClass()`

### Автооткрытие completion

Для Parser3 нельзя рассчитывать только на один механизм автооткрытия popup. Надёжная схема состоит из нескольких уровней, и использовать их нужно вместе по смыслу:

1. `CompletionConfidence.shouldSkipAutopopup(...) -> ThreeState.NO`
   - это механизм разрешения: IDE должна **не подавлять** стандартный autopopup в распознанном контексте;
   - именно так работают стабильные текстовые контексты вроде `@OPTIONS`, а теперь и `paramText` внутри `^taint[...]`, `^file::load[text;...]` и аналогичных мест;
   - важно: сам по себе `CompletionConfidence` не гарантирует запуск popup для любой буквы. Он разрешает запуск, но первичный триггер может идти через `CompletionContributor.invokeAutoPopup(...)` или `TypedHandlerDelegate`.

2. `CompletionContributor.invokeAutoPopup(...)`
   - это явный триггер для символов, на которых IDE должна начать completion-задачу;
   - нужен, когда popup должен открываться при наборе обычной буквы/символа в уже существующем контексте;
   - пример: `true/false` в boolean-контексте. Общая проверка лежит в `P3CompletionUtils.shouldAutoPopupBooleanLiteral(...)`, а вызов подключён в `P3VariableCompletionContributor.invokeAutoPopup(...)`.

3. `TypedHandlerDelegate`
   - нужен как ранний триггер там, где контекст проверяется **до вставки символа** или появляется прямо в момент ввода;
   - типичные случаи: ввод `[` / `;` / `(` / `{`, после которых нужно сразу поднять popup;
   - если popup планируется из `beforeCharTyped(...)`, вызывать `AutoPopupController.scheduleAutoPopup(...)` нужно отложенно через `ApplicationManager.getApplication().invokeLater(...)`, чтобы документ уже содержал набранный символ;
   - в проекте для этого используется `P3UseTypedHandler`, но он не должен быть единственным механизмом auto-popup.

4. `AutoPopupController.scheduleAutoPopup(...)` из `InsertHandler`
   - используется после выбора элемента completion, когда вставка должна сразу открыть следующий уровень подсказок;
   - пример: вставили свойство с вложенными параметрами и сразу открыли completion внутри него.

Практическое правило:
- если popup должен открываться при обычном наборе текста внутри уже существующего контекста — сначала добавить распознавание контекста в общий helper, затем подключить `Parser3CompletionConfidence` и, если IDE не стартует completion-задачу на нужный символ, добавить `invokeAutoPopup(...)`;
- если popup должен открыться сразу после специального разделителя или контекст надо проверять до вставки символа — дополнять это `TypedHandler`;
- если popup нужен сразу после вставки lookup-элемента — вызывать `scheduleAutoPopup(...)` в `InsertHandler`.
- контекстные проверки нельзя дублировать по классам: сначала вынести предикат в общий helper (`P3CompletionUtils`, `P3PathCompletionSupport`, `P3PseudoHashCompletionRegistry` и т.п.), потом использовать его из `CompletionConfidence`, `invokeAutoPopup` и `TypedHandler`.

### Явный Ctrl+Space

Явный `Ctrl+Space` должен показывать пользовательские шаблоны/методы **в любом месте Parser3-файла**, даже если текущий текст не является валидным Parser3-контекстом или текущий префикс выглядит как мусор.

Правило реализации:
- в `Parser3CompletionContributor` для `!parameters.isAutoPopup()` пользовательские шаблоны добавляются сразу через `result.withPrefixMatcher("")`;
- остальные контекстные completion-ветки могут только дополнять результат, но не должны убирать этот общий explicit-слой;
- ограничения вроде `$builtinClass:prefix`, `^if (` с пробелом, битых `^...` или `.` без receiver применяются к автопопапу и контекстным подсказкам, но не должны запрещать пользовательские шаблоны при ручном `Ctrl+Space`.

### Конфигурируемый contextual argument completion

В проекте есть отдельный слой completion для контекстов, где внутри вызова метода или оператора нужны:

- pseudo-hash параметры вида `$.key[]`, `$.key()`, `$.nested[...]`;
- перечислимые текстовые значения без `$.`, например `text`, `binary`, `as-is`, `optimized-html`.

Это не "частный хак под curl", а общая схема.

#### Где лежит конфиг

- встроенный конфиг: `src/main/resources/ru/artlebedev/parser3/completion/pseudo-hash-completion.json`
- пользовательское расширение: `Settings | Editor | Parser 3 | Contextual argument completion`

Пользовательский JSON имеет тот же формат, что и встроенный, и дополняет его.

#### Что описывает одна запись

Одна запись в JSON может содержать:

- `targets` — список мест, где должен работать completion;
- `params` — pseudo-hash варианты вида `$.name[]`;
- `paramText` — plain-text варианты без `$.`.

Поддерживаемые target-цели:

- `staticMethod`
- `constructor`
- `dynamicMethod`
- `operator`
- `builtinProperty`

Поддерживаемые типы позиции:

- `params` — первый аргумент вызова;
- `colon` — второй аргумент;
- `colon2` — третий аргумент;
- `colon3` — четвёртый аргумент.

#### Как считается позиция аргумента

Для completion все группы аргументов считаются как единая последовательность параметров вызова.
То есть для целей completion трактуются эквивалентно:

```parser3
^json:parse[data;$.depth(1)]
^json:parse[data][$.depth(1)]

^call[A;B;C]
^call[A](B)[C]
```

Это упрощение сделано намеренно: completion не обязан строго валидировать синтаксис Parser3, он должен стабильно находить нужный аргумент.

#### Как устроен runtime

Основной код находится в `P3PseudoHashCompletionRegistry`.
Он отвечает за:

- разбор встроенного и пользовательского JSON;
- merge пользовательских записей с built-in конфигом;
- определение текущего контекста вызова;
- выдачу `params` и `paramText`;
- insert-логику для `$.key[]` и plain-text вариантов.

#### Важные правила реализации

- completion-контекст нельзя отключать из-за кавычек `'` и `"` — Parser3 не использует их как отдельный строковый режим;
- единственный общий механизм, который реально выключает специальный синтаксис, — экранирование через `^`;
- если completion должен открываться автоматически при обычном наборе текста, сначала настраивается `Parser3CompletionConfidence`, а не только `TypedHandler`;
- `TypedHandler` нужен только как дополнительный триггер на символах, которые создают контекст прямо в момент ввода;
- после выбора lookup-элемента следующий popup открывается из `InsertHandler`.

### Подавление пользовательских шаблонов

**Файл:** `Parser3CompletionContributor`

- `isInBuiltinClassPropContext()` проверяет паттерн `$builtinClass:prefix`
- Если true — пользовательские шаблоны (`.foreach[]`, `^curl:load[]`) не показываются в обычной контекстной ветке
- При явном `Ctrl+Space` пользовательские шаблоны всё равно должны быть видны

### Case-insensitive автокомплит

**Файл:** `P3CompletionUtils`

```java
public static CompletionResultSet makeCaseInsensitive(CompletionResultSet result)
```

- Создаёт кастомный `PrefixMatcher` с `toLowerCase` сравнением
- Вызывается в начале `addCompletions` во ВСЕХ contributors:
    - `P3VariableCompletionContributor`
    - `P3MethodCompletionContributor`
    - `P3ClassCompletionContributor`
    - `P3BaseCompletionContributor`
    - `Parser3CompletionContributor`

**Пример:** `$env:remote` → показывает `REMOTE_ADDR`, `REMOTE_PORT`

### Навигация в документации (findNameUrlConflicts)

`collectNameUrlPairs(PROPERTIES, result, ":")` — свойства используют separator `:` (не `.`).
Это предотвращает ложные конфликты типа `console:line` ↔ `image.line`.
## Документация Parser3

- `parser3_5_0.ru.pdf` — исходная полная документация по языку.
- `parser3_5_0.ru.pdf.txt` — поисковая текстовая версия этого PDF для быстрого поиска по репозиторию.
- При расхождении источником истины считается `parser3_5_0.ru.pdf`.
