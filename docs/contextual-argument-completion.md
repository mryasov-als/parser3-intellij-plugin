# Contextual argument completion

Эта настройка расширяет автодополнение аргументов Parser3 без изменения кода плагина:

`Settings | Editor | Parser 3 | Contextual argument completion`

В поле нужно вставлять JSON. Корень JSON всегда массив. Пользовательский конфиг дополняет встроенный `src/main/resources/ru/artlebedev/parser3/completion/pseudo-hash-completion.json`, а не заменяет его.

## Быстрый пример

```json
[
	{
		"targets": [
			{
				"class": "file",
				"constructor": "load",
				"type": "colon2"
			}
		],
		"params": [
			{
				"name": "cache",
				"brackets": "()",
				"comment": "Включить кеширование результата."
			},
			{
				"name": "format",
				"brackets": "[]",
				"comment": "Формат ответа.",
				"paramText": [
					{
						"text": "json",
						"comment": "JSON-данные."
					},
					{
						"text": "text",
						"comment": "Текстовые данные."
					}
				]
			}
		]
	}
]
```

После этого в контексте второго дополнительного аргумента `^file::load[...]` плагин будет предлагать `$.cache()` и `$.format[]`, а внутри `$.format[]` - значения `json` и `text`.

## Общая структура

```json
[
	{
		"targets": [
			{
				"class": "className",
				"staticMethod": "methodName",
				"type": "params"
			}
		],
		"params": [],
		"paramText": []
	}
]
```

Один элемент массива описывает набор подсказок для одного или нескольких одинаковых по смыслу мест. В пользовательском конфиге у каждого элемента обязательно должны быть:

- `targets` - непустой массив контекстов, где работают подсказки.
- `params` или `paramText` - хотя бы один список подсказок.

## targets

`targets` - массив объектов. Каждый объект выбирает Parser3-вызов и позицию аргумента.

В target нужно указать ровно одно из полей вызова:

- `staticMethod` - статический вызов класса: `^class:method[...]`.
- `constructor` - конструктор класса: `^class::constructor[...]`.
- `dynamicMethod` - метод объекта: `^object.method[...]`, когда тип объекта известен.
- `operator` - оператор Parser3: `^operator[...]`. Для operator поле `class` не указывается.
- `builtinProperty` - свойство встроенного класса: `$class:property[...]`.

Для всех target, кроме `operator`, обязательно поле:

- `class` - имя встроенного или пользовательского класса.

Позиция аргумента задается полем `type`:

- `params` - первый аргумент вызова. Это значение используется по умолчанию, если `type` не указан.
- `colon` - второй аргумент.
- `colon2` - третий аргумент.
- `colon3` - четвертый аргумент.

Позиции считаются по смысловой последовательности аргументов, а не строго по виду скобок. Поэтому для completion эти формы считаются эквивалентными:

```parser3
^json:parse[data;$.depth(1)]
^json:parse[data][$.depth(1)]
^json:parse[data]{$.depth(1)}
```

Во всех трех случаях `$.depth()` находится в позиции `colon`.

## params

`params` описывает pseudo-hash параметры вида `$.name[]`, `$.name()`, `$.name{}`.

```json
{
	"name": "timeout",
	"brackets": "()",
	"comment": "Таймаут в секундах."
}
```

Поля параметра:

- `name` - имя после `$.`, обязательное и непустое.
- `brackets` - пара скобок для вставки. Допустимые практические значения: `[]`, `()`, `{}`. Если поле не указано или некорректно, при разборе используется `[]`.
- `comment` - текст справа в completion, необязательное поле.
- `params` - вложенные pseudo-hash параметры, необязательное поле.
- `paramText` - текстовые значения внутри этого параметра, необязательное поле.

Вложенные `params` позволяют описывать цепочки:

```json
[
	{
		"targets": [
			{
				"class": "mail",
				"staticMethod": "send",
				"type": "params"
			}
		],
		"params": [
			{
				"name": "file",
				"brackets": "[]",
				"comment": "Вложение файла.",
				"params": [
					{
						"name": "name",
						"brackets": "[]",
						"comment": "Имя вложения."
					},
					{
						"name": "format",
						"brackets": "[]",
						"comment": "Формат кодирования.",
						"paramText": [
							"base64",
							"uue"
						]
					}
				]
			}
		]
	}
]
```

Это дает подсказки для:

```parser3
^mail:send[$.file[$.name[] $.format[]]]
```

## paramText

`paramText` описывает plain-text значения без `$.`.

Элементом массива может быть строка:

```json
"json"
```

Или объект с комментарием:

```json
{
	"text": "json",
	"comment": "JSON-данные."
}
```

`paramText` можно задавать на уровне всего target:

```json
[
	{
		"targets": [
			{
				"class": "date",
				"staticMethod": "calendar",
				"type": "params"
			}
		],
		"paramText": [
			{
				"text": "rus",
				"comment": "Неделя начинается с понедельника."
			},
			{
				"text": "eng",
				"comment": "Неделя начинается с воскресенья."
			}
		]
	}
]
```

Или внутри конкретного pseudo-hash параметра:

```json
[
	{
		"targets": [
			{
				"class": "json",
				"staticMethod": "parse",
				"type": "colon"
			}
		],
		"params": [
			{
				"name": "array",
				"brackets": "[]",
				"comment": "Тип контейнера для JSON-массивов.",
				"paramText": [
					{
						"text": "array",
						"comment": "JSON-массив как array."
					},
					{
						"text": "hash",
						"comment": "JSON-массив как hash с числовыми ключами."
					}
				]
			}
		]
	}
]
```

## Примеры target

### Статический метод

Для `^json:parse[data;...]`:

```json
[
	{
		"targets": [
			{
				"class": "json",
				"staticMethod": "parse",
				"type": "colon"
			}
		],
		"params": [
			{
				"name": "depth",
				"brackets": "()",
				"comment": "Максимальная глубина разбора."
			}
		]
	}
]
```

### Конструктор

Для `^file::create[data;...]`:

```json
[
	{
		"targets": [
			{
				"class": "file",
				"constructor": "create",
				"type": "colon"
			}
		],
		"params": [
			{
				"name": "name",
				"brackets": "[]",
				"comment": "Имя создаваемого файла."
			}
		]
	}
]
```

### Метод объекта

Для `^items.sort[...]`, когда `$items` известен как `table`, `array` или `hash`:

```json
[
	{
		"targets": [
			{
				"class": "table",
				"dynamicMethod": "sort",
				"type": "colon"
			},
			{
				"class": "array",
				"dynamicMethod": "sort",
				"type": "colon"
			},
			{
				"class": "hash",
				"dynamicMethod": "sort",
				"type": "colon"
			}
		],
		"paramText": [
			{
				"text": "asc",
				"comment": "Сортировка по возрастанию."
			},
			{
				"text": "desc",
				"comment": "Сортировка по убыванию."
			}
		]
	}
]
```

### Оператор

Для `^taint[...]`, `^untaint[...]` и `^apply-taint[...]`:

```json
[
	{
		"targets": [
			{
				"operator": "taint",
				"type": "params"
			},
			{
				"operator": "untaint",
				"type": "params"
			},
			{
				"operator": "apply-taint",
				"type": "params"
			}
		],
		"paramText": [
			"as-is",
			"html",
			"json",
			"sql",
			"uri"
		]
	}
]
```

### Свойство встроенного класса

Для `$response:content-type[...]`:

```json
[
	{
		"targets": [
			{
				"class": "response",
				"builtinProperty": "content-type",
				"type": "params"
			}
		],
		"paramText": [
			"application/json",
			"text/html",
			"text/plain"
		]
	}
]
```

## Как пользовательский конфиг объединяется со встроенным

Объединение идет по ключу target: `class`, тип вызова, имя вызова и `type`.

Если пользовательский JSON добавляет новый target, он просто появляется рядом со встроенными подсказками.

Если пользовательский JSON описывает уже существующий target:

- `params` объединяются по `name` + `brackets`, без учета регистра `name`.
- При совпадении параметра пользовательский `comment` заменяет встроенный, если он непустой.
- Вложенные `params` объединяются по тем же правилам.
- `paramText` объединяется по `text`, без учета регистра.
- При совпадении `paramText` пользовательский `comment` заменяет встроенный, если он непустой.

Конфиг не удаляет встроенные подсказки. Если нужно скрыть встроенный вариант, текущий формат этого не поддерживает.

## Проверка и частые ошибки

При нажатии `Apply` или `OK` настройка валидируется. Если JSON некорректен, IDE покажет ошибку.

Частые ошибки:

- Корень не массив: нужно `[...]`, а не `{...}`.
- В элементе нет `targets`.
- `targets` пустой.
- В target указано несколько типов вызова одновременно, например `staticMethod` и `constructor`.
- Для `staticMethod`, `constructor`, `dynamicMethod` или `builtinProperty` не указан `class`.
- Указан неизвестный `type`.
- В пользовательском элементе нет ни `params`, ни `paramText`.
- `params` или `paramText` заданы объектом вместо массива.
- У `param` пустой `name`.
- У `paramText` пустой `text`.

Минимальный валидный конфиг:

```json
[
	{
		"targets": [
			{
				"operator": "process",
				"type": "params"
			}
		],
		"paramText": [
			"memory",
			"time"
		]
	}
]
```

Пустое поле настройки тоже валидно: в этом случае работает только встроенный конфиг.
