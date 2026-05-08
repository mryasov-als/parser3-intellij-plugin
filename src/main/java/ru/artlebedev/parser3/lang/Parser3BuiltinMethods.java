package ru.artlebedev.parser3.lang;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Справочник встроенных классов/методов Parser3 для code completion.
 * <p>
 * Новая версия с поддержкой описаний и суффиксов ([], {}, и т.п.).
 * <p>
 * ВНИМАНИЕ: файл сгенерирован как черновой вариант и содержит
 * только регистрацию конструкторов для класса array.
 * Остальные классы/методы нужно будет перенести сюда по аналогии.
 */
public final class Parser3BuiltinMethods {
	/**
	 * Встроенный элемент (конструктор / метод), который может использоваться
	 * как через ^class::method, так и через точку (obj.method).
	 */
	public static final class BuiltinCallable {
		public final @NotNull String name;
		public final @Nullable String description;
		public final @NotNull String suffix;
		public final @Nullable String url;
		public final @Nullable String template;
		/** Тип возвращаемого значения (для свойств). null если не определён. */
		public final @Nullable String returnType;
		/** Суффикс для присваивания (например "[]" или "{}"). null — свойство только для чтения. */
		public final @Nullable String assignSuffix;

		private BuiltinCallable(@NotNull String name, @Nullable String description, @Nullable String url, @NotNull String suffix, @Nullable String template) {
			this(name, description, url, suffix, template, null, null);
		}

		private BuiltinCallable(@NotNull String name, @Nullable String description, @Nullable String url, @NotNull String suffix, @Nullable String template, @Nullable String returnType) {
			this(name, description, url, suffix, template, returnType, null);
		}

		private BuiltinCallable(@NotNull String name, @Nullable String description, @Nullable String url, @NotNull String suffix, @Nullable String template, @Nullable String returnType, @Nullable String assignSuffix) {
			this.name = name;
			this.description = description;
			this.suffix = suffix;
			this.url = url;
			this.template = template;
			this.returnType = returnType;
			this.assignSuffix = assignSuffix;
		}
	}

	private static final Map<String, List<BuiltinCallable>> CONSTRUCTORS = new LinkedHashMap<>();
	private static final Map<String, List<BuiltinCallable>> STATIC_METHODS = new LinkedHashMap<>();
	private static final Map<String, List<BuiltinCallable>> METHODS = new LinkedHashMap<>();
	private static final Map<String, List<BuiltinCallable>> SYSTEM_METHODS = new LinkedHashMap<>();
	private static final Map<String, String> BUILTIN_BASE_CLASSES = new LinkedHashMap<>();

	/**
	 * Справочник свойств (полей) встроенных классов.
	 * Свойства доступны через $class:field (статический доступ) или $obj.field (через объект).
	 * Например: $form:fields, $request:uri, $env:PARSER_VERSION
	 */
	private static final Map<String, List<BuiltinCallable>> PROPERTIES = new LinkedHashMap<>();
	private static final Map<String, Set<String>> INSTANCE_ONLY_PROPERTIES = new LinkedHashMap<>();

	public static final class CaretConstructor {
		public final @NotNull String className;
		public final @NotNull BuiltinCallable callable;

		private CaretConstructor(@NotNull String className, @NotNull BuiltinCallable callable) {
			this.className = className;
			this.callable = callable;
		}
	}


	static {
		registerSystemMethods(
				ctor("eval", "Вычисление математических выражений", "https://www.parser.ru/docs/lang/opeval.htm", "()", ""),
				ctor("if", "Выбор одного варианта из двух", "https://www.parser.ru/docs/lang/opif.htm", "()", ""),
				ctor("switch", "Выбор одного варианта из нескольких", "https://www.parser.ru/docs/lang/opswitch.htm", "[]", ""),
				ctor("case", "Выбор одного варианта из нескольких", "https://www.parser.ru/docs/lang/opswitch.htm", "[]", ""),
				ctor("break", "Выход из цикла", "https://www.parser.ru/docs/lang/opbreak.htm", "[]", ""),
				ctor("continue", "Переход к следующей итерации цикла", "https://www.parser.ru/docs/lang/opcontinue.htm", "[]", ""),
				ctor("for", "Цикл с заданным числом повторов", "https://www.parser.ru/docs/lang/opfor.htm", "[]", ""),
				ctor("while", "Цикл с условием", "https://www.parser.ru/docs/lang/opwhile.htm", "()", ""),
				ctor("cache", "Сохранение результатов работы кода", "https://www.parser.ru/docs/lang/opcache.htm", "[]", ""),
				ctor("connect", "Подключение к базе данных", "https://www.parser.ru/docs/lang/opconnect.htm", "[]", ""),
				ctor("process", "Компиляция и исполнение строки", "https://www.parser.ru/docs/lang/opprocess.htm", "[]", ""),
				ctor("rem", "Вставка комментария", "https://www.parser.ru/docs/lang/oprem.htm", "{}", ""),
				ctor("return", "Возврат из метода", "https://www.parser.ru/docs/lang/opreturn.htm", "[]", ""),
				ctor("sleep", "Задержка выполнения программы", "https://www.parser.ru/docs/lang/opsleep.htm", "()", ""),
				ctor("use", "Подключение модулей", "https://www.parser.ru/docs/lang/opuse.htm", "[]", ""),
				ctor("taint", "Задание преобразований данных", "https://www.parser.ru/docs/lang/optaint.htm", "[]", ""),
				ctor("untaint", "Задание преобразований данных", "https://www.parser.ru/docs/lang/opuntaint.htm", "[]", ""),
				ctor("apply-taint", "Применение преобразований данных", "https://www.parser.ru/docs/lang/opapplytaint.htm", "[]", ""),
				ctor("try", "Перехват и обработка ошибок", "https://www.parser.ru/docs/lang/optry.htm", "{}", ""),
				ctor("throw", "Сообщение об ошибке", "https://www.parser.ru/docs/lang/opthrow.htm", "[]", "")
		);
		registerConstructors("array",
				ctor("create", "Создание массива с заданными значениями или пустого массива", "https://www.parser.ru/docs/lang/arraycreate.htm", "[]", ""),
				ctor("copy", "Копирование массива или хеша", "https://www.parser.ru/docs/lang/array_copy.htm", "[]", ""),
				ctor("sql", "Создание массива на основе выборки из базы данных", "https://www.parser.ru/docs/lang/arraysql.htm", "{}", "")
		);

		registerMethods("array",
				ctor("add", "Добавление элементов из другого массива или хеша с перезаписью", "https://www.parser.ru/docs/lang/arrayadd.htm", "[]", ""),
				ctor("append", "Добавление элементов в конец массива", "https://www.parser.ru/docs/lang/arrayaappend.htm", "[]", ""),
				ctor("at", "Доступ к элементу массива по порядковому номеру", "https://www.parser.ru/docs/lang/arrayat.htm", "()", ""),
				ctor("compact", "Удаление неинициализированных элементов", "https://www.parser.ru/docs/lang/arraycompact.htm", "[]", ""),
				ctor("contains", "Проверка существования элемента по индексу", "https://www.parser.ru/docs/lang/arraycontains.htm", "()", ""),
				ctor("count", "Количество элементов массива", "https://www.parser.ru/docs/lang/arraycount.htm", "[]", ""),
				ctor("delete", "Удаление элемента массива", "https://www.parser.ru/docs/lang/arraydelete.htm", "()", ""),
				ctor("for", "Перебор всех элементов массива", "https://www.parser.ru/docs/lang/array_for.htm", "[]", ""),
				ctor("foreach", "Перебор элементов массива", "https://www.parser.ru/docs/lang/arrayforeach.htm", "[]", ""),
				ctor("insert", "Вставка элементов в указанную позицию массива", "https://www.parser.ru/docs/lang/arrayinsert.htm", "()", ""),
				ctor("join", "Добавление элементов другого массива или хеша", "https://www.parser.ru/docs/lang/arrayjoin.htm", "[]", ""),
				ctor("keys", "Список индексов массива", "https://www.parser.ru/docs/lang/arraykeys.htm", "[]", ""),
				ctor("left", "Получение первых n элементов массива", "https://www.parser.ru/docs/lang/arrayleft.htm", "()", ""),
				ctor("mid", "Получение диапазона элементов массива", "https://www.parser.ru/docs/lang/arraymid.htm", "()", ""),
				ctor("pop", "Удаление и возврат последнего элемента массива", "https://www.parser.ru/docs/lang/arrayapop.htm", "[]", ""),
				ctor("push", "Добавление элемента в конец массива", "https://www.parser.ru/docs/lang/arrayapush.htm", "[]", ""),
				ctor("remove", "Удаление элемента со сдвигом", "https://www.parser.ru/docs/lang/arrayremove.htm", "()", ""),
				ctor("reverse", "Обратный порядок элементов", "https://www.parser.ru/docs/lang/arrayreverse.htm", "[]", ""),
				ctor("right", "Получение последних n элементов массива", "https://www.parser.ru/docs/lang/arrayright.htm", "()", ""),
				ctor("select", "Отбор элементов", "https://www.parser.ru/docs/lang/arrayselect.htm", "[]", ""),
				ctor("set", "Установка значения элемента массива", "https://www.parser.ru/docs/lang/arrayset.htm", "()", ""),
				ctor("sort", "Сортировка массива", "https://www.parser.ru/docs/lang/arraysort.htm", "[]", "")
		);


		registerStaticMethods("curl",
				ctor("info", "Информация о последнем запросе", "https://www.parser.ru/docs/lang/curlinfo.htm", "[]", ""),
				ctor("load", "Загрузка файла с удаленного сервера", "https://www.parser.ru/docs/lang/curlload.htm", "[]", ""),
				ctor("options", "Задание опций для сессии", "https://www.parser.ru/docs/lang/curloptions.htm", "[]", ""),
				ctor("session", "Создание сессии", "https://www.parser.ru/docs/lang/curlsession.htm", "{}", ""),
				ctor("version", "Возврат текущей версии cURL", "https://www.parser.ru/docs/lang/curlversion.htm", "[]", "")
		);


		registerConstructors("date",
				ctor("create", "Дата или время в стандартном формате для СУБД", "https://www.parser.ru/docs/lang/datecreatestring.htm", "[]", ""),
				ctor("create", "Дата в формате ISO 8601", "https://www.parser.ru/docs/lang/datecreateiso8601.htm", "[]", ""),
				ctor("create", "Копирование даты", "https://www.parser.ru/docs/lang/datecreatecopy.htm", "[]", ""),
				ctor("create", "Относительная дата", "https://www.parser.ru/docs/lang/datecreaterel.htm", "()", ""),
				ctor("create", "Произвольная дата", "https://www.parser.ru/docs/lang/datecreateabs.htm", "()", ""),
				ctor("now", "Текущая дата", "https://www.parser.ru/docs/lang/datenow.htm", "[]", ""),
				ctor("today", "Дата на начало текущего дня", "https://www.parser.ru/docs/lang/datetoday.htm", "[]", ""),
				ctor("unix-timestamp", "Дата и время в Unix-формате", "https://www.parser.ru/docs/lang/dateunixtscreate.htm", "()", "")
		);

		registerMethods("date",
				ctor("int", "Преобразование даты в число", "https://www.parser.ru/docs/lang/dateintdouble.htm", "[]", ""),
				ctor("double", "Преобразование даты в число", "https://www.parser.ru/docs/lang/dateintdouble.htm", "[]", ""),
				ctor("gmt-string", "Вывод даты в виде строки в формате RFC 822", "https://www.parser.ru/docs/lang/dategmtstring.htm", "[]", ""),
				ctor("iso-string", "Вывод даты в виде строки в формате ISO 8601", "https://www.parser.ru/docs/lang/dateisostring.htm", "[]", ""),
				ctor("last-day", "Получение последнего дня месяца", "https://www.parser.ru/docs/lang/datelastdaym.htm", "[]", ""),
				ctor("roll", "Сдвиг даты", "https://www.parser.ru/docs/lang/dateroll.htm", "[]", ""),
				ctor("sql-string", "Преобразование даты в вид, стандартный для СУБД", "https://www.parser.ru/docs/lang/datesqlstring.htm", "[]", ""),
				ctor("unix-timestamp", "Преобразование даты и времени в Unix-формат", "https://www.parser.ru/docs/lang/dateunixts.htm", "[]", "")
		);

		registerStaticMethods("date",
				ctor("calendar", "Создание календаря на заданную неделю месяца", "https://www.parser.ru/docs/lang/datecalendarweek.htm", "[]", ""),
				ctor("calendar", "Создание календаря на заданный месяц", "https://www.parser.ru/docs/lang/datecalendarmonth.htm", "[]", ""),
				ctor("last-day", "Получение последнего дня месяца", "https://www.parser.ru/docs/lang/datelastday.htm", "()", ""),
				ctor("roll", "Установка временной зоны по умолчанию", "https://www.parser.ru/docs/lang/daterolldefault.htm", "[]", "")
		);


		registerConstructors("file",
				ctor("base64", "Декодирование из Base64", "https://www.parser.ru/docs/lang/filebase64c.htm", "[]", ""),
				ctor("cgi", "Исполнение программы", "https://www.parser.ru/docs/lang/filecgiexec.htm", "[]", ""),
				ctor("exec", "Исполнение программы", "https://www.parser.ru/docs/lang/filecgiexec.htm", "[]", ""),
				ctor("create", "Создание файла", "https://www.parser.ru/docs/lang/filecreate.htm", "[]", ""),
				ctor("load", "Загрузка файла с диска или HTTP-сервера", "https://www.parser.ru/docs/lang/fileload.htm", "[]", ""),
				ctor("sql", "Загрузка файла с SQL-сервера", "https://www.parser.ru/docs/lang/filesql.htm", "{}", ""),
				ctor("stat", "Получение информации о файле", "https://www.parser.ru/docs/lang/filestat.htm", "[]", "")
		);

		registerMethods("file",
				ctor("base64", "Кодирование в Base64", "https://www.parser.ru/docs/lang/filebase64m.htm", "[]", ""),
				ctor("crc32", "Подсчет контрольной суммы файла", "https://www.parser.ru/docs/lang/filecrc32m.htm", "[]", ""),
				ctor("md5", "MD5-отпечаток файла", "https://www.parser.ru/docs/lang/filemd5m.htm", "[]", ""),
				ctor("save", "Сохранение файла на диске", "https://www.parser.ru/docs/lang/filesave.htm", "[]", ""),
				ctor("sql-string", "Сохранение файла на SQL-сервере", "https://www.parser.ru/docs/lang/filesqlstring.htm", "[]", "")
		);

		registerStaticMethods("file",
				ctor("base64", "Кодирование в Base64", "https://www.parser.ru/docs/lang/filebase64.htm", "[]", ""),
				ctor("basename", "Имя файла без пути", "https://www.parser.ru/docs/lang/filebasename.htm", "[]", ""),
				ctor("copy", "Копирование файла", "https://www.parser.ru/docs/lang/filecopy.htm", "[]", ""),
				ctor("crc32", "Подсчет контрольной суммы файла", "https://www.parser.ru/docs/lang/filecrc32.htm", "[]", ""),
				ctor("delete", "Удаление файла с диска", "https://www.parser.ru/docs/lang/filedelete.htm", "[]", ""),
				ctor("dirname", "Путь к файлу", "https://www.parser.ru/docs/lang/filedirname.htm", "[]", ""),
				ctor("find", "Поиск файла на диске", "https://www.parser.ru/docs/lang/filefind.htm", "[]", ""),
				ctor("fullpath", "Полное имя файла от корня веб-пространства", "https://www.parser.ru/docs/lang/filefullpath.htm", "[]", ""),
				ctor("justext", "Расширение имени файла", "https://www.parser.ru/docs/lang/filejustext.htm", "[]", ""),
				ctor("justname", "Имя файла без расширения", "https://www.parser.ru/docs/lang/filejustname.htm", "[]", ""),
				ctor("list", "Получение оглавления каталога", "https://www.parser.ru/docs/lang/filelist.htm", "[]", ""),
				ctor("lock", "Эксклюзивное выполнение кода", "https://www.parser.ru/docs/lang/filelock.htm", "[]", ""),
				ctor("md5", "MD5-отпечаток файла", "https://www.parser.ru/docs/lang/filemd5.htm", "[]", ""),
				ctor("move", "Перемещение или переименование файла", "https://www.parser.ru/docs/lang/filemove.htm", "[]", "")
		);


		registerConstructors("hash",
				ctor("create", "Создание пустого хеша и копирование хеша", "https://www.parser.ru/docs/lang/hashcreate.htm", "[]", ""),
				ctor("sql", "Создание хеша на основе выборки из базы данных", "https://www.parser.ru/docs/lang/hashsql.htm", "{}", "")
		);

		registerMethods("hash",
				ctor("at", "Доступ к элементу хеша по индексу", "https://www.parser.ru/docs/lang/hashat.htm", "()", ""),
				ctor("contains", "Проверка существования ключа", "https://www.parser.ru/docs/lang/hashcontains.htm", "[]", ""),
				ctor("count", "Количество ключей хеша", "https://www.parser.ru/docs/lang/hash_count.htm", "[]", ""),
				ctor("_count", "Количество ключей хеша", "https://www.parser.ru/docs/lang/hash_count.htm", "[]", ""),
				ctor("delete", "Удаление пары «ключ / значение»", "https://www.parser.ru/docs/lang/hashdelete.htm", "[]", ""),
				ctor("foreach", "Перебор элементов хеша", "https://www.parser.ru/docs/lang/hashforeach.htm", "[]", ""),
				ctor("keys", "Список ключей хеша", "https://www.parser.ru/docs/lang/hash_keys.htm", "[]", ""),
				ctor("rename", "Переименовывание ключей хеша", "https://www.parser.ru/docs/lang/hashrename.htm", "[]", ""),
				ctor("reverse", "Обратный порядок элементов", "https://www.parser.ru/docs/lang/hashreverse.htm", "[]", ""),
				ctor("select", "Отбор элементов", "https://www.parser.ru/docs/lang/hashselect.htm", "[]", ""),
				ctor("set", "Установка значения по индексу", "https://www.parser.ru/docs/lang/hashset.htm", "()", ""),
				ctor("sort", "Сортировка хеша", "https://www.parser.ru/docs/lang/hashsort.htm", "[]", ""),
				ctor("add", "Сложение хешей", "https://www.parser.ru/docs/lang/hashadd.htm", "[]", ""),
				ctor("intersection", "Пересечение хешей", "https://www.parser.ru/docs/lang/hashintersection.htm", "[]", ""),
				ctor("intersects", "Определение наличия пересечения хешей", "https://www.parser.ru/docs/lang/hashintersects.htm", "[]", ""),
				ctor("sub", "Вычитание хешей", "https://www.parser.ru/docs/lang/hashsub.htm", "[]", ""),
				ctor("union", "Объединение хешей", "https://www.parser.ru/docs/lang/hashunion.htm", "[]", "")
		);


		registerMethods("hashfile",
				ctor("cleanup", "Удаление устаревших записей", "https://www.parser.ru/docs/lang/hashfilecleanup.htm", "[]", ""),
				ctor("clear", "Удаление всего содержимого", "https://www.parser.ru/docs/lang/hashfileclear.htm", "[]", ""),
				ctor("delete", "Удаление пары «ключ / значение»", "https://www.parser.ru/docs/lang/hashfiledelete.htm", "[]", ""),
				ctor("delete", "Удаление файлов данных с диска", "https://www.parser.ru/docs/lang/hashfiledeletefiles.htm", "[]", ""),
				ctor("foreach", "Перебор ключей хеша", "https://www.parser.ru/docs/lang/hashfileforeach.htm", "[]", ""),
				ctor("hash", "Получение обычного хеша", "https://www.parser.ru/docs/lang/hashfilehash.htm", "[]", ""),
				ctor("release", "Сохранение изменений и снятие блокировок", "https://www.parser.ru/docs/lang/hashfilerelease.htm", "[]", "")
		);


		registerConstructors("image",
				ctor("create", "Создание объекта с заданными размерами", "https://www.parser.ru/docs/lang/imagecreate.htm", "()", ""),
				ctor("load", "Cоздание объекта на основе графического файла в формате GIF", "https://www.parser.ru/docs/lang/imageload.htm", "[]", ""),
				ctor("measure", "Создание объекта на основе существующего графического файла", "https://www.parser.ru/docs/lang/imagemeasure.htm", "[]", "")
		);

		registerStaticMethods("inet",
				ctor("aton", "Преобразование строки с IP-адресом в число", "https://www.parser.ru/docs/lang/inetaton.htm", "[]", ""),
				ctor("hostname", "Имя хоста", "https://www.parser.ru/docs/lang/inethostname.htm", "[]", ""),
				ctor("ip2name", "Определение домена по IP-адресу", "https://www.parser.ru/docs/lang/inetip2name.htm", "[]", ""),
				ctor("name2ip", "Определение IP-адреса домена", "https://www.parser.ru/docs/lang/inetname2ip.htm", "[]", ""),
				ctor("ntoa", "Преобразование числа в строку с IP-адресом", "https://www.parser.ru/docs/lang/inetntoa.htm", "()", "")
		);


		registerStaticMethods("json",
				ctor("parse", "Преобразование JSON-строки в хеш", "https://www.parser.ru/docs/lang/jsonparse.htm", "[]", ""),
				ctor("string", "Преобразование объекта Parser в JSON-строку", "https://www.parser.ru/docs/lang/jsonstring.htm", "[]", "")
		);


		registerStaticMethods("mail",
				ctor("send", "Отправка сообщения по электронной почте", "https://www.parser.ru/docs/lang/mailsend.htm", "[]", ""),
				ctor("received", "Прием сообщения по электронной почте", "https://www.parser.ru/docs/lang/mailreceived.htm", "[]", "")
		);


		registerStaticMethods("math",
				ctor("abs", "Операции со знаком", "https://www.parser.ru/docs/lang/mathabssign.htm", "()", ""),
				ctor("sign", "Операции со знаком", "https://www.parser.ru/docs/lang/mathabssign.htm", "()", ""),
				ctor("convert", "Конвертирование из одной системы счисления в другую", "https://www.parser.ru/docs/lang/mathconvert.htm", "[]", ""),
				ctor("crc32", "Подсчет контрольной суммы строки", "https://www.parser.ru/docs/lang/mathcrc32.htm", "[]", ""),
				ctor("crypt", "Хеширование паролей", "https://www.parser.ru/docs/lang/mathcrypt.htm", "[]", ""),
				ctor("degrees", "Преобразования градусы — радианы", "https://www.parser.ru/docs/lang/matdegreesetc.htm", "()", ""),
				ctor("radians", "Преобразования градусы — радианы", "https://www.parser.ru/docs/lang/matdegreesetc.htm", "()", ""),
				ctor("digest", "Криптографическое хеширование", "https://www.parser.ru/docs/lang/mathdigest.htm", "[]", ""),
				ctor("exp", "Логарифмические функции", "https://www.parser.ru/docs/lang/mathexplog.htm", "()", ""),
				ctor("log", "Логарифмические функции", "https://www.parser.ru/docs/lang/mathexplog.htm", "()", ""),
				ctor("log10", "Логарифмические функции", "https://www.parser.ru/docs/lang/mathexplog.htm", "()", ""),
				ctor("md5", "MD5-отпечаток строки", "https://www.parser.ru/docs/lang/mathmd5.htm", "[]", ""),
				ctor("pow", "Возведение числа в степень", "https://www.parser.ru/docs/lang/mathpow.htm", "()", ""),
				ctor("random", "Случайное число", "https://www.parser.ru/docs/lang/mathrandom.htm", "()", ""),
				ctor("round", "Округление до ближайшего целого", "https://www.parser.ru/docs/lang/mathroundetc.htm", "()", ""),
				ctor("floor", "Округление до целого в меньшую сторону", "https://www.parser.ru/docs/lang/mathroundetc.htm", "()", ""),
				ctor("ceiling", "Округление до целого в большую сторону", "https://www.parser.ru/docs/lang/mathroundetc.htm", "()", ""),
				ctor("sha1", "Хеш строки по алгоритму SHA1", "https://www.parser.ru/docs/lang/mathsha1.htm", "[]", ""),
				ctor("sin", "Тригонометрические функции", "https://www.parser.ru/docs/lang/mathsinetc.htm", "()", ""),
				ctor("asin", "Тригонометрические функции", "https://www.parser.ru/docs/lang/mathsinetc.htm", "()", ""),
				ctor("cos", "Тригонометрические функции", "https://www.parser.ru/docs/lang/mathsinetc.htm", "()", ""),
				ctor("acos", "Тригонометрические функции", "https://www.parser.ru/docs/lang/mathsinetc.htm", "()", ""),
				ctor("tan", "Тригонометрические функции", "https://www.parser.ru/docs/lang/mathsinetc.htm", "()", ""),
				ctor("atan", "Тригонометрические функции", "https://www.parser.ru/docs/lang/mathsinetc.htm", "()", ""),
				ctor("atan2", "Тригонометрические функции", "https://www.parser.ru/docs/lang/mathsinetc.htm", "()", ""),
				ctor("sqrt", "Квадратный корень числа", "https://www.parser.ru/docs/lang/matsqrt.htm", "()", ""),
				ctor("trunc", "Операции с целой/дробной частью числа", "https://www.parser.ru/docs/lang/mathtruncfrac.htm", "()", ""),
				ctor("frac", "Операции с целой/дробной частью числа", "https://www.parser.ru/docs/lang/mathtruncfrac.htm", "()", ""),
				ctor("uid64", "64-битный уникальный идентификатор", "https://www.parser.ru/docs/lang/mathuid64.htm", "[]", ""),
				ctor("uuid", "Универсальный уникальный идентификатор версии 4", "https://www.parser.ru/docs/lang/mathuuid.htm", "[]", ""),
				ctor("uuid7", "Универсальный уникальный идентификатор версии 7", "https://www.parser.ru/docs/lang/mathuuid7.htm", "[]", "")
		);


		registerMethods("memcached",
				ctor("add", "Добавление записи", "https://www.parser.ru/docs/lang/memcachedadd.htm", "[]", ""),
				ctor("clear", "Удаление всех данных с сервера", "https://www.parser.ru/docs/lang/memcachedclear.htm", "()", ""),
				ctor("delete", "Удаление записи", "https://www.parser.ru/docs/lang/memcacheddelete.htm", "[]", ""),
				ctor("mget", "Получение множества значений", "https://www.parser.ru/docs/lang/memcachedmget.htm", "[]", ""),
				ctor("release", "Закрытие соединения с сервером", "https://www.parser.ru/docs/lang/memcachedrelease.htm", "[]", "")
		);


		registerStaticMethods("memory",
				ctor("auto-compact", "Автоматическая сборка мусора", "https://www.parser.ru/docs/lang/memoryautocompact.htm", "()", ""),
				ctor("compact", "Сборка мусора", "https://www.parser.ru/docs/lang/memorycompact.htm", "[]", "")
		);


		registerStaticMethods("reflection",
				ctor("base", "Родительский класс объекта", "https://www.parser.ru/docs/lang/reflectionobjectbaseclass.htm", "[]", ""),
				ctor("base_name", "Имя родительского класса объекта", "https://www.parser.ru/docs/lang/reflectionobjectbaseclassname.htm", "[]", ""),
				ctor("class", "Класс объекта", "https://www.parser.ru/docs/lang/reflectionobjectclass.htm", "[]", ""),
				ctor("class_alias", "Создание псевдонима класса", "https://www.parser.ru/docs/lang/reflectionclassalias.htm", "[]", ""),
				ctor("class_by_name", "Получение класса по имени", "https://www.parser.ru/docs/lang/reflectionclassbyname.htm", "[]", ""),
				ctor("class_name", "Имя класса объекта", "https://www.parser.ru/docs/lang/reflectionobjectclassname.htm", "[]", ""),
				ctor("classes", "Список классов", "https://www.parser.ru/docs/lang/reflectionclasses.htm", "[]", ""),
				ctor("copy", "Копирование объекта", "https://www.parser.ru/docs/lang/reflectioncopy.htm", "[]", ""),
				ctor("create", "Создание объекта", "https://www.parser.ru/docs/lang/reflectioncreate.htm", "[]", ""),
				ctor("def", "Проверка существования класса", "https://www.parser.ru/docs/lang/reflectiondef.htm", "[]", ""),
				ctor("delete", "Удаление поля объекта", "https://www.parser.ru/docs/lang/reflectiondelete.htm", "[]", ""),
				ctor("dynamical", "Тип вызова метода", "https://www.parser.ru/docs/lang/reflectiondynamical.htm", "[]", ""),
				ctor("field", "Получение значения поля объекта", "https://www.parser.ru/docs/lang/reflectionfield.htm", "[]", ""),
				ctor("fields", "Список полей объекта", "https://www.parser.ru/docs/lang/reflectionfields.htm", "[]", ""),
				ctor("fields_reference", "Ссылка на поля объекта", "https://www.parser.ru/docs/lang/reflectionfieldsreference.htm", "[]", ""),
				ctor("filename", "Получение имени файла", "https://www.parser.ru/docs/lang/reflectionfilename.htm", "[]", ""),
				ctor("is", "Проверка типа", "https://www.parser.ru/docs/lang/reflectionis.htm", "[]", ""),
				ctor("method", "Получение метода объекта", "https://www.parser.ru/docs/lang/reflectionmethod.htm", "[]", ""),
				ctor("method_info", "Информация о методе", "https://www.parser.ru/docs/lang/reflectionmethodinfo.htm", "[]", ""),
				ctor("methods", "Список методов класса", "https://www.parser.ru/docs/lang/reflectionmethods.htm", "[]", ""),
				ctor("mixin", "Дополнение типа", "https://www.parser.ru/docs/lang/reflectionmixin.htm", "[]", ""),
				ctor("stack", "Стек вызовов методов", "https://www.parser.ru/docs/lang/reflectionstack.htm", "[]", ""),
				ctor("tainting", "Преобразования строки", "https://www.parser.ru/docs/lang/reflectiontainting.htm", "[]", ""),
				ctor("uid", "Уникальный идентификатор объекта", "https://www.parser.ru/docs/lang/reflectionuid.htm", "[]", "")
		);


		registerStaticMethods("string",
				ctor("base64", "Декодирование из Base64", "https://www.parser.ru/docs/lang/stringbase64c.htm", "[]", ""),
				ctor("idna", "Декодирование из IDNA", "https://www.parser.ru/docs/lang/stringidna.htm", "[]", ""),
				ctor("js-unescape", "Декодирование, аналогичное функции unescape в JavaScript", "https://www.parser.ru/docs/lang/stringjsunescape.htm", "[]", ""),
				ctor("sql", "Получение строки из базы данных", "https://www.parser.ru/docs/lang/stringsql.htm", "{}", ""),
				ctor("unescape", "Декодирование JavaScript- или URI-кодирования", "https://www.parser.ru/docs/lang/stringunescape.htm", "[]", "")
		);

		registerMethods("string",
				ctor("base64", "Кодирование в Base64", "https://www.parser.ru/docs/lang/stringbase64m.htm", "[]", ""),
//				ctor("format", "Вывод числа в заданном формате", "https://www.parser.ru/docs/lang/stringformat.htm", "[]", ""),
				ctor("int", "Преобразование строки в число или bool", "https://www.parser.ru/docs/lang/stringintdouble.htm", "()", ""),
				ctor("double", "Преобразование строки в число или bool", "https://www.parser.ru/docs/lang/stringintdouble.htm", "()", ""),
				ctor("bool", "Преобразование строки в число или bool", "https://www.parser.ru/docs/lang/stringintdouble.htm", "()", ""),
				ctor("idna", "Кодирование в IDNA", "https://www.parser.ru/docs/lang/stringidnam.htm", "[]", ""),
				ctor("js-escape", "Кодирование, аналогичное функции escape в JavaScript", "https://www.parser.ru/docs/lang/stringjsescape.htm", "[]", ""),
				ctor("left", "Подстрока слева и справа", "https://www.parser.ru/docs/lang/stringltrt.htm", "()", ""),
				ctor("right", "Подстрока слева и справа", "https://www.parser.ru/docs/lang/stringltrt.htm", "()", ""),
				ctor("length", "Длина строки", "https://www.parser.ru/docs/lang/stringlength.htm", "[]", ""),
				ctor("match", "Поиск подстроки по шаблону", "https://www.parser.ru/docs/lang/stringmatch.htm", "[]", ""),
				ctor("match", "Замена подстроки, соответствующей шаблону", "https://www.parser.ru/docs/lang/stringmatchreplace.htm", "[]", ""),
				ctor("mid", "Подстрока с заданной позиции", "https://www.parser.ru/docs/lang/stringmid.htm", "()", ""),
				ctor("pos", "Получение позиции подстроки", "https://www.parser.ru/docs/lang/stringpos.htm", "[]", ""),
				ctor("replace", "Замена подстрок в строке", "https://www.parser.ru/docs/lang/stringreplace.htm", "[]", ""),
				ctor("save", "Сохранение строки в файл", "https://www.parser.ru/docs/lang/stringsave.htm", "[]", ""),
				ctor("split", "Разбиение строки", "https://www.parser.ru/docs/lang/stringsplit.htm", "[]", "", "table"),
				ctor("trim", "Отсечение букв с концов строки", "https://www.parser.ru/docs/lang/stringtrim.htm", "[]", ""),
				ctor("upper", "Преобразование регистра строки", "https://www.parser.ru/docs/lang/stringuplow.htm", "[]", ""),
				ctor("lower", "Преобразование регистра строки", "https://www.parser.ru/docs/lang/stringuplow.htm", "[]", "")
		);

		registerMethods("image",
				ctor("gif", "Кодирование объектов класса image в формат GIF", "https://www.parser.ru/docs/lang/imagegif.htm", "[]", ""),
				ctor("html", "Вывод изображения", "https://www.parser.ru/docs/lang/imagehtml.htm", "[]", ""),
				ctor("arc", "Рисование дуги", "https://www.parser.ru/docs/lang/imagearc.htm", "()", ""),
				ctor("bar", "Рисование закрашенных прямоугольников", "https://www.parser.ru/docs/lang/imagebar.htm", "()", ""),
				ctor("circle", "Рисование неокрашенной окружности", "https://www.parser.ru/docs/lang/imagecircle.htm", "()", ""),
				ctor("copy", "Копирование фрагментов изображений", "https://www.parser.ru/docs/lang/imagecopy.htm", "[]", ""),
				ctor("fill", "Закрашивание одноцветной области изображения", "https://www.parser.ru/docs/lang/imagefill.htm", "()", ""),
				ctor("font", "Загрузка файла шрифта для нанесения надписей на изображение", "https://www.parser.ru/docs/lang/imagefont.htm", "[]", ""),
				ctor("length", "Получение длины надписи в пикселях", "https://www.parser.ru/docs/lang/imagelength.htm", "[]", ""),
				ctor("line", "Рисование линии на изображении", "https://www.parser.ru/docs/lang/imageline.htm", "()", ""),
				ctor("pixel", "Работа с точками изображения", "https://www.parser.ru/docs/lang/imagepixel.htm", "()", ""),
				ctor("polybar", "Рисование окрашенных многоугольников по координатам узлов", "https://www.parser.ru/docs/lang/imagepolybar.htm", "()", ""),
				ctor("polygon", "Рисование неокрашенных многоугольников по координатам узлов", "https://www.parser.ru/docs/lang/imagepolygon.htm", "()", ""),
				ctor("polyline", "Рисование ломаных линий по координатам узлов", "https://www.parser.ru/docs/lang/imagepolyline.htm", "()", ""),
				ctor("rectangle", "Рисование незакрашенных прямоугольников", "https://www.parser.ru/docs/lang/imagerectangle.htm", "()", ""),
				ctor("replace", "Замена цвета в области, заданной таблицей координат", "https://www.parser.ru/docs/lang/imagereplace.htm", "()", ""),
				ctor("sector", "Рисование сектора", "https://www.parser.ru/docs/lang/imagesector.htm", "()", ""),
				ctor("text", "Нанесение надписей на изображение", "https://www.parser.ru/docs/lang/imagetext.htm", "()", "")
		);
		registerConstructors("table",
				ctor("create", "Создание объекта на основе заданной таблицы", "https://www.parser.ru/docs/lang/tablecreate.htm", "{}", ""),
				ctor("create", "Копирование существующей таблицы", "https://www.parser.ru/docs/lang/tablecreateclone.htm", "[]", ""),
				ctor("load", "Загрузка таблицы с диска или HTTP-сервера", "https://www.parser.ru/docs/lang/tableload.htm", "[]", ""),
				ctor("sql", "Выборка таблицы из базы данных", "https://www.parser.ru/docs/lang/tablesql.htm", "{}", "")
		);

		registerMethods("table",
				ctor("append", "Добавление строки в таблицу", "https://www.parser.ru/docs/lang/tableappend.htm", "{}", ""),
				ctor("array", "Преобразование таблицы в массив", "https://www.parser.ru/docs/lang/tablearray.htm", "{}", ""),
				ctor("cells", "Получение значений столбцов текущей строки таблицы", "https://www.parser.ru/docs/lang/tablecells.htm", "()", ""),
				ctor("columns", "Получение структуры таблицы", "https://www.parser.ru/docs/lang/tablecolumns.htm", "[]", ""),
				ctor("count", "Количество строк в таблице", "https://www.parser.ru/docs/lang/tablecount.htm", "[]", ""),
				ctor("csv-string", "Преобразование в строку в формате CSV", "https://www.parser.ru/docs/lang/tablecsvstring.htm", "[]", ""),
				ctor("delete", "Удаление текущей строки", "https://www.parser.ru/docs/lang/tabledelete.htm", "[]", ""),
				ctor("flip", "Транспонирование таблицы", "https://www.parser.ru/docs/lang/tableflip.htm", "[]", ""),
				ctor("foreach", "Последовательный перебор всех строк таблицы", "https://www.parser.ru/docs/lang/tableforeach.htm", "[]", ""),
				ctor("hash", "Преобразование таблицы в хеш с заданными ключами", "https://www.parser.ru/docs/lang/table2hash.htm", "[]", ""),
				ctor("insert", "Вставка строки в таблицу", "https://www.parser.ru/docs/lang/tableinsert.htm", "{}", ""),
				ctor("join", "Объединение двух таблиц", "https://www.parser.ru/docs/lang/tablejoin.htm", "[]", ""),
				ctor("locate", "Поиск в таблице", "https://www.parser.ru/docs/lang/tablelocate.htm", "[]", ""),
				ctor("menu", "Последовательный перебор всех строк таблицы", "https://www.parser.ru/docs/lang/tablemenu.htm", "{}", ""),
				ctor("offset", "Получение смещения указателя текущей строки", "https://www.parser.ru/docs/lang/tableline.htm", "[]", ""),
				ctor("offset", "Смещение указателя текущей строки", "https://www.parser.ru/docs/lang/tableoffset.htm", "()", ""),
				ctor("line", "Получение смещения указателя текущей строки", "https://www.parser.ru/docs/lang/tableline.htm", "[]", ""),
				ctor("rename", "Изменение названия столбца", "https://www.parser.ru/docs/lang/tablerename.htm", "[]", ""),
				ctor("save", "Сохранение таблицы в файл", "https://www.parser.ru/docs/lang/tablesave.htm", "[]", ""),
				ctor("select", "Отбор записей", "https://www.parser.ru/docs/lang/tableselect.htm", "()", ""),
				ctor("sort", "Сортировка данных таблицы", "https://www.parser.ru/docs/lang/tablesort.htm", "{}", "")
		);


		registerConstructors("xdoc",
				ctor("create", "Создание  документа на основе заданного XML", "https://www.parser.ru/docs/lang/xdoccreatexml.htm", "{}", ""),
				ctor("create", "Создание нового пустого документа", "https://www.parser.ru/docs/lang/xdoccreateempty.htm", "[]", ""),
				ctor("create", "Создание  документа на основе файла", "https://www.parser.ru/docs/lang/xdoccreatefile.htm", "[]", "")
		);

		registerMethods("xdoc",
				ctor("load", "Загрузка XML с диска, HTTP-сервера или иного источника", "https://www.parser.ru/docs/lang/xdocload.htm", "[]", ""),
				ctor("file", "Преобразование документа в объект класса file", "https://www.parser.ru/docs/lang/xdocfile.htm", "[]", ""),
				ctor("save", "Сохранение документа в файл", "https://www.parser.ru/docs/lang/xdocsave.htm", "[]", ""),
				ctor("string", "Преобразование документа в строку", "https://www.parser.ru/docs/lang/xdocstring.htm", "[]", ""),
				ctor("transform", "XSL-преобразование", "https://www.parser.ru/docs/lang/xdoctransform.htm", "[]", ""),
				ctor("createElement", "DOM Document: создание элемента", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createDocumentFragment", "DOM Document: создание фрагмента документа", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createTextNode", "DOM Document: создание текстового узла", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createComment", "DOM Document: создание комментария", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createCDATASection", "DOM Document: создание CDATA-секции", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createProcessingInstruction", "DOM Document: создание processing-instruction", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createAttribute", "DOM Document: создание атрибута", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createEntityReference", "DOM Document: создание ссылки на entity", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("getElementsByTagName", "DOM Document: поиск элементов по имени тега", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("importNode", "DOM Document: импорт узла из другого документа", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createElementNS", "DOM Document: создание элемента с пространством имен", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("createAttributeNS", "DOM Document: создание атрибута с пространством имен", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("getElementsByTagNameNS", "DOM Document: поиск элементов по пространству имен", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", ""),
				ctor("getElementById", "DOM Document: поиск элемента по идентификатору", "https://www.parser.ru/docs/lang/xdocdommethods.htm", "[]", "")
		);


		registerMethods("xnode",
				ctor("select", "XPath-поиск узлов", "https://www.parser.ru/docs/lang/xnodeselect.htm", "[]", ""),
				ctor("selectSingle", "XPath-поиск одного узла", "https://www.parser.ru/docs/lang/xnodeselectsingle.htm", "[]", ""),
				ctor("selectString", "Вычисление строчного XPath-запроса", "https://www.parser.ru/docs/lang/xnodeselectstring.htm", "[]", ""),
				ctor("selectNumber", "Вычисление числового XPath-запроса", "https://www.parser.ru/docs/lang/xnodeselectnumber.htm", "[]", ""),
				ctor("selectBool", "Вычисление логического XPath-запроса", "https://www.parser.ru/docs/lang/xnodeselectbool.htm", "[]", ""),
				ctor("insertBefore", "DOM Node: вставка дочернего узла перед указанным", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("replaceChild", "DOM Node: замена дочернего узла", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("removeChild", "DOM Node: удаление дочернего узла", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("appendChild", "DOM Node: добавление дочернего узла", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("hasChildNodes", "DOM Node: проверка наличия дочерних узлов", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("cloneNode", "DOM Node: клонирование узла", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "()", ""),
				ctor("getAttribute", "DOM Element: чтение атрибута", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("setAttribute", "DOM Element: запись атрибута", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("removeAttribute", "DOM Element: удаление атрибута", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("getAttributeNode", "DOM Element: получение узла атрибута", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("setAttributeNode", "DOM Element: установка узла атрибута", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("removeAttributeNode", "DOM Element: удаление узла атрибута", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("getElementsByTagName", "DOM Element: поиск потомков по имени тега", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("normalize", "DOM Element: нормализация текстовых узлов", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("getAttributeNS", "DOM Element: чтение атрибута с пространством имен", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("setAttributeNS", "DOM Element: запись атрибута с пространством имен", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("removeAttributeNS", "DOM Element: удаление атрибута с пространством имен", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("getAttributeNodeNS", "DOM Element: получение узла атрибута с пространством имен", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("setAttributeNodeNS", "DOM Element: установка узла атрибута с пространством имен", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("getElementsByTagNameNS", "DOM Element: поиск потомков по пространству имен", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("hasAttribute", "DOM Element: проверка наличия атрибута", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("hasAttributeNS", "DOM Element: проверка наличия атрибута с пространством имен", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", ""),
				ctor("hasAttributes", "DOM Element: проверка наличия любых атрибутов", "https://www.parser.ru/docs/lang/xnodedommethods.htm", "[]", "")
		);

		registerInstanceOnlyProperties("xdoc",
				prop("search-namespaces", "Хеш пространств имен для поиска", "https://www.parser.ru/docs/lang/xdocsearchns.htm", "hash")
		);

		registerInstanceOnlyProperties("xnode",
				prop("nodeName", "DOM Node: имя узла", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("nodeValue", "DOM Node: значение узла", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("nodeType", "DOM Node: тип узла", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "int"),
				prop("parentNode", "DOM Node: родительский узел", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "xnode"),
				prop("childNodes", "DOM NodeList: дочерние узлы", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "hash"),
				prop("firstChild", "DOM Node: первый дочерний узел", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "xnode"),
				prop("lastChild", "DOM Node: последний дочерний узел", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "xnode"),
				prop("previousSibling", "DOM Node: предыдущий соседний узел", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "xnode"),
				prop("nextSibling", "DOM Node: следующий соседний узел", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "xnode"),
				prop("attributes", "DOM NamedNodeMap: атрибуты узла", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "hash"),
				prop("ownerDocument", "DOM Document: документ-владелец", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "xdoc"),
				prop("prefix", "DOM Node: префикс пространства имен", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("namespaceURI", "DOM Node: URI пространства имен", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("tagName", "DOM Element: имя тега", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("name", "DOM Attr/DocumentType: имя узла", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("specified", "DOM Attr: признак явной установки", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "int"),
				prop("value", "DOM Attr: значение атрибута", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("target", "DOM ProcessingInstruction: цель инструкции", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("data", "DOM ProcessingInstruction: данные инструкции", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("publicId", "DOM Notation: publicId", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string"),
				prop("systemId", "DOM Notation: systemId", "https://www.parser.ru/docs/lang/xnodedomfields.htm", "string")
		);

		registerProperties("xnode",
				prop("ELEMENT_NODE", "DOM-константа типа элемента", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("ATTRIBUTE_NODE", "DOM-константа типа атрибута", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("TEXT_NODE", "DOM-константа типа текста", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("CDATA_SECTION_NODE", "DOM-константа типа CDATA", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("ENTITY_REFERENCE_NODE", "DOM-константа типа entity reference", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("ENTITY_NODE", "DOM-константа типа entity", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("PROCESSING_INSTRUCTION_NODE", "DOM-константа processing instruction", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("COMMENT_NODE", "DOM-константа комментария", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("DOCUMENT_NODE", "DOM-константа документа", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("DOCUMENT_TYPE_NODE", "DOM-константа document type", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("DOCUMENT_FRAGMENT_NODE", "DOM-константа document fragment", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("NOTATION_NODE", "DOM-константа notation", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int")
		);

		registerProperties("xdoc",
				prop("ELEMENT_NODE", "DOM-константа типа элемента", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("ATTRIBUTE_NODE", "DOM-константа типа атрибута", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("TEXT_NODE", "DOM-константа типа текста", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("CDATA_SECTION_NODE", "DOM-константа типа CDATA", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("ENTITY_REFERENCE_NODE", "DOM-константа типа entity reference", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("ENTITY_NODE", "DOM-константа типа entity", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("PROCESSING_INSTRUCTION_NODE", "DOM-константа processing instruction", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("COMMENT_NODE", "DOM-константа комментария", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("DOCUMENT_NODE", "DOM-константа документа", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("DOCUMENT_TYPE_NODE", "DOM-константа document type", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("DOCUMENT_FRAGMENT_NODE", "DOM-константа document fragment", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int"),
				prop("NOTATION_NODE", "DOM-константа notation", "https://www.parser.ru/docs/lang/dom_nodetype.htm", "int")
		);

		registerBuiltinInheritance("file-local", "file");
		registerBuiltinInheritance("file-http", "file");
		registerBuiltinInheritance("file-exec", "file");
		registerBuiltinInheritance("file-sql", "file");
		registerBuiltinInheritance("xdoc", "xnode");

		registerMethods("int",
				ctor("format", "Вывод числа в заданном формате", "https://www.parser.ru/docs/lang/intdoubleformat.htm", "[]", ""),
				ctor("inc", "Метод увеличивает значение переменной на 1 или число", "https://www.parser.ru/docs/lang/intdoubleincetc.htm", "()", ""),
				ctor("dec", "Метод уменьшает значение переменной на 1 или число", "https://www.parser.ru/docs/lang/intdoubleincetc.htm", "()", ""),
				ctor("mul", "Метод умножает значение переменной на число", "https://www.parser.ru/docs/lang/intdoubleincetc.htm", "()", ""),
				ctor("div", "Метод делит значение переменной на число", "https://www.parser.ru/docs/lang/intdoubleincetc.htm", "()", ""),
				ctor("mod", "Метод помещает в переменную остаток от деления ее значения на число", "https://www.parser.ru/docs/lang/intdoubleincetc.htm", "()", "")
		);

		// ─── Свойства (поля) встроенных классов ───────────────────────────────
		// Доступны через $class:field или $obj.field

		registerProperties("cookie",
				prop("fields", "Все cookie", "https://www.parser.ru/docs/lang/cookiefields.htm", "hash")
		);


		registerInstanceOnlyProperties("date",
				prop("month", "месяц", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("year", "год", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("day", "день", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("hour", "часы", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("minute", "минуты", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("second", "секунды", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("weekday", "день недели (0 - воскресенье, 1 - понедельник, …)", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("week", "номер недели в году (согласно стандарту ISO 8601)", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("weekyear", "год, к которому принадлежит неделя (согласно стандарту ISO 8601)", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("yearday", "день года (0 - 1 января, 1 - 2 января, …)", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("daylightsaving", "1 - летнее время, 0 - стандартное время", "https://www.parser.ru/docs/lang/datefields.htm", "int"),
				prop("TZ", "часовой пояс; содержит значение, оно было задано этой дате", "https://www.parser.ru/docs/lang/datefields.htm", "string")
		);

		registerProperties("env",
				prop("fields", "Все переменные окружения", "https://www.parser.ru/docs/lang/envfields.htm", "hash"),
				prop("PARSER_VERSION", "Версия Parser3", "https://www.parser.ru/docs/lang/envfields.htm", "string"),
				prop("SERVER_SOFTWARE", "Название и версия HTTP-сервера", "", "string"),
				prop("SERVER_NAME", "Имя сервера (домен)", "", "string"),
				prop("SERVER_ADDR", "IP-адрес сервера", "", "string"),
				prop("SERVER_PORT", "Порт сервера (80, 443 и т.д.)", "", "string"),
				prop("SERVER_PROTOCOL", "Версия протокола HTTP", "", "string"),
				prop("DOCUMENT_ROOT", "Корень веб-пространства на сервере", "", "string"),
				prop("GATEWAY_INTERFACE", "Версия CGI-интерфейса", "", "string"),
				prop("REQUEST_METHOD", "Метод HTTP-запроса (GET, POST и т.д.)", "", "string"),
				prop("REQUEST_URI", "Полный URI запроса", "", "string"),
				prop("QUERY_STRING", "Строка параметров после ?", "", "string"),
				prop("SCRIPT_NAME", "Путь к текущему скрипту", "", "string"),
				prop("SCRIPT_FILENAME", "Полный путь к файлу скрипта", "", "string"),
				prop("PATH_INFO", "Дополнительный путь после имени скрипта", "", "string"),
				prop("PATH_TRANSLATED", "Физический путь к PATH_INFO", "", "string"),
				prop("REMOTE_ADDR", "IP-адрес клиента", "", "string"),
				prop("REMOTE_PORT", "Порт клиента", "", "string"),
				prop("HTTPS", "Используется ли HTTPS (on/off)", "", "string"),
				prop("HTTP_HOST", "Значение заголовка Host", "", "string"),
				prop("HTTP_USER_AGENT", "Строка User-Agent клиента", "", "string"),
				prop("HTTP_ACCEPT", "Поддерживаемые клиентом типы содержимого", "", "string"),
				prop("HTTP_ACCEPT_LANGUAGE", "Предпочтительные языки клиента", "", "string"),
				prop("HTTP_ACCEPT_ENCODING", "Поддерживаемые методы сжатия", "", "string"),
				prop("HTTP_COOKIE", "Все cookie, переданные клиентом", "", "string"),
				prop("HTTP_REFERER", "Источник перехода", "", "string"),
				prop("HTTP_ORIGIN", "Origin запроса", "", "string"),
				prop("HTTP_CONNECTION", "Тип соединения (keep-alive и т.д.)", "", "string"),
				prop("HTTP_CACHE_CONTROL", "Инструкции кэширования запроса", "", "string"),
				prop("HTTP_X_FORWARDED_FOR", "Оригинальный IP клиента при прокси", "", "string"),
				prop("HTTP_X_FORWARDED_PROTO", "Оригинальный протокол при прокси", "", "string"),
				prop("HTTP_X_REAL_IP", "Реальный IP клиента, переданный прокси", "", "string"),
				prop("CONTENT_TYPE", "MIME-тип тела запроса", "", "string"),
				prop("CONTENT_LENGTH", "Длина тела запроса в байтах", "", "string")

		);
		registerProperties("form",
				prop("elements", "Массивы всех полей формы", "https://www.parser.ru/docs/lang/formelements.htm", "hash"),
				prop("fields", "Все поля формы", "https://www.parser.ru/docs/lang/formfields.htm", "hash"),
				prop("files", "Получение множества файлов", "https://www.parser.ru/docs/lang/formfiles.htm", "hash"),
				prop("imap", "Получение координат нажатия в ISMAP", "https://www.parser.ru/docs/lang/formimap.htm", "hash"),
				prop("qtail", "Получение остатка строки запроса", "https://www.parser.ru/docs/lang/formqtail.htm", "string"),
				prop("tables", "Получение множества значений поля", "https://www.parser.ru/docs/lang/formtables.htm", "table")
		);


		registerInstanceOnlyProperties("file",
				prop("name", "Имя файла", "https://www.parser.ru/docs/lang/filename.htm", "string"),
				prop("size", "Размер файла", "https://www.parser.ru/docs/lang/filesize.htm", "int"),
				prop("text", "Текст файла", "https://www.parser.ru/docs/lang/filetext.htm", "string"),
				prop("mode", "Формат файла", "https://www.parser.ru/docs/lang/filemode.htm", "string"),
				prop("content-type", "MIME-тип файла", "https://www.parser.ru/docs/lang/filecontenttype.htm", "string")
		);

		registerInstanceOnlyProperties("file-local",
				prop("cdate", "Дата создания", "https://www.parser.ru/docs/lang/filecdate.htm", "date"),
				prop("mdate", "Дата изменения", "https://www.parser.ru/docs/lang/filemdate.htm", "date"),
				prop("adate", "Дата последнего обращения", "https://www.parser.ru/docs/lang/fileadate.htm", "date")
		);

		registerInstanceOnlyProperties("file-exec",
				prop("stderr", "Текст ошибки выполнения программы", "https://www.parser.ru/docs/lang/filestderr.htm", "string"),
				prop("status", "Статус получения файла", "https://www.parser.ru/docs/lang/filestatus.htm", "int")
		);

		registerInstanceOnlyProperties("file-http",
				prop("status", "Статус получения файла", "https://www.parser.ru/docs/lang/filestatus.htm", "int"),
				prop("tables", "Повторяющиеся поля HTTP-ответа", "https://www.parser.ru/docs/lang/filehttpfields.htm", "hash"),
				prop("cookies", "Cookies из HTTP-ответа", "https://www.parser.ru/docs/lang/filecookies.htm", "table")
		);

		registerProperties("request",
				prop("argv", "Аргументы командной строки", "https://www.parser.ru/docs/lang/requestargv.htm", "hash"),
				prop("body", "Получение текста запроса", "https://www.parser.ru/docs/lang/requestbody.htm", "string"),
				prop("body-charset", "Получение кодировки пришедшего POST-запроса", "https://www.parser.ru/docs/lang/requestpostcharset.htm", "string"),
				prop("post-charset", "Получение кодировки пришедшего POST-запроса", "https://www.parser.ru/docs/lang/requestpostcharset.htm", "string"),
				prop("body-file", "Тело содержимого запроса", "https://www.parser.ru/docs/lang/requestpostbody.htm", "file"),
				prop("post-body", "Тело содержимого запроса", "https://www.parser.ru/docs/lang/requestpostbody.htm", "file"),
				prop("charset", "Задание кодировки документов на сервере", "https://www.parser.ru/docs/lang/requestcharset.htm", "string"),
				prop("headers", "Получение заголовков HTTP-запроса", "https://www.parser.ru/docs/lang/requestheaders.htm", "hash"),
				prop("method", "Получение метода HTTP-запроса", "https://www.parser.ru/docs/lang/requestmethod.htm", "string"),
				prop("path", "Получение пути запроса", "https://www.parser.ru/docs/lang/requestpath.htm", "string"),
				prop("query", "Получение параметров строки запроса", "https://www.parser.ru/docs/lang/requestquery.htm", "string"),
				prop("uri", "Получение URI запроса", "https://www.parser.ru/docs/lang/requesturi.htm", "string")
		);

		registerProperties("console",
				prop("line", "Чтение строки", "https://www.parser.ru/docs/lang/consoleread.htm", "string", ""),
				prop("line", "Запись строки", "https://www.parser.ru/docs/lang/consolewrite.htm", "string", "[]")
		);

		registerProperties("response",
				prop("body", "Задание нового тела ответа", "https://www.parser.ru/docs/lang/responsebody.htm", "string", "[]"),
				prop("download", "Задание нового тела ответа", "https://www.parser.ru/docs/lang/responsedownload.htm", "", "[]"),
				prop("charset", "Задание кодировки ответа", "https://www.parser.ru/docs/lang/responsecharset.htm", "string", "[]"),
				prop("content-type", "Задание Content-Type ответа", "https://www.parser.ru/docs/lang/responsefields.htm", "string", "[]"),
				prop("status", "Задание HTTP-статуса ответа", "https://www.parser.ru/docs/lang/responsefields.htm", "int", "[]"),
				prop("headers", "Заданные заголовки HTTP-ответа", "https://www.parser.ru/docs/lang/responseheaders.htm", "hash"),
				prop("location", "Редирект на указанный URL (HTTP Location)", "", "string", "[]"),
				prop("refresh", "Автоматическое перенаправление через заданное время", "", "string", "[]"),
				prop("content-disposition", "Задание режима отображения/скачивания файла", "", "string", "[]"),
				prop("cache-control", "Управление кэшированием ответа", "", "string", "[]"),
				prop("expires", "Дата истечения кэша ответа", "", "string", "[]"),
				prop("pragma", "Дополнительные директивы кэширования", "", "string", "[]"),
				prop("set-cookie", "Установка HTTP-cookie в ответе", "", "string", "[]"),
				prop("last-modified", "Дата последнего изменения ресурса", "", "string", "[]")

		);

		registerProperties("status",
				prop("memory", "Информация о памяти под контролем сборщика мусора", "https://www.parser.ru/docs/lang/statusmemory.htm", "hash"),
				prop("log-filename", "Путь к журналу ошибок", "https://www.parser.ru/docs/lang/statuslogfilename.htm", "string"),
				prop("mode", "Режим работы", "https://www.parser.ru/docs/lang/statusmode.htm", "string"),
				prop("pid", "Идентификатор процесса", "https://www.parser.ru/docs/lang/statuspid.htm", "int"),
				prop("rusage", "Информация о затраченных ресурсах", "https://www.parser.ru/docs/lang/statusrusage.htm", "hash"),
				prop("tid", "Идентификатор потока", "https://www.parser.ru/docs/lang/statustid.htm", "int")
		);

	}

	private Parser3BuiltinMethods() {
	}

	// ─── Информация о типах ────────────────────────────────────────────────

	/**
	 * Список всех встроенных классов Parser3.
	 * Используется для определения типа переменной при присваивании $var[^ClassName::...]
	 */
	private static final java.util.Set<String> BUILTIN_CLASS_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
			"array", "bool", "console", "cookie", "curl", "date", "double", "env",
			"file", "file-local", "file-http", "file-exec", "file-sql", "form", "hash", "hashfile", "image", "inet", "int", "json",
			"junction", "mail", "math", "memcached", "memory", "reflection", "regex",
			"request", "response", "sqlite", "status", "string", "table", "void",
			"xdoc", "xnode"
	));

	/**
	 * Проверяет, является ли имя встроенным классом Parser3.
	 */
	public static boolean isBuiltinClass(@NotNull String className) {
		return BUILTIN_CLASS_NAMES.contains(className.toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * Возвращает множество всех встроенных классов (в нижнем регистре).
	 */
	public static @NotNull java.util.Set<String> getBuiltinClassNames() {
		return Collections.unmodifiableSet(BUILTIN_CLASS_NAMES);
	}

	/**
	 * Проверяет, есть ли у класса конструкторы (можно создать объект через ^Class::method[]).
	 */
	public static boolean hasConstructors(@NotNull String className) {
		String lower = className.toLowerCase(java.util.Locale.ROOT);
		return CONSTRUCTORS.containsKey(lower);
	}

	/**
	 * Возвращает свойства (поля) для указанного встроенного класса.
	 */
	public static @NotNull List<BuiltinCallable> getPropertiesForClass(@NotNull String className) {
		return Collections.unmodifiableList(collectBuiltinCallables(PROPERTIES, className));
	}

	public static @NotNull List<BuiltinCallable> getStaticPropertiesForClass(@NotNull String className) {
		return Collections.unmodifiableList(collectBuiltinProperties(className, false));
	}

	public static boolean supportsStaticPropertyAccess(@NotNull String className) {
		String current = className.toLowerCase(java.util.Locale.ROOT);
		while (current != null && !current.isEmpty()) {
			List<BuiltinCallable> props = PROPERTIES.get(current);
			if (props != null && !props.isEmpty()) {
				for (BuiltinCallable prop : props) {
					if (!isInstanceOnlyProperty(current, prop.name)) {
						return true;
					}
				}
			}
			current = BUILTIN_BASE_CLASSES.get(current);
		}
		return false;
	}

	/**
	 * Возвращает все свойства всех встроенных классов.
	 */
	public static @NotNull List<CaretConstructor> getAllPropertiesMeta() {
		return collect(PROPERTIES);
	}

	/**
	 * Возвращает карту всех встроенных классов с их свойствами.
	 * Ключ — имя класса, значение — список свойств.
	 */
	public static @NotNull Map<String, List<BuiltinCallable>> getAllProperties() {
		return Collections.unmodifiableMap(PROPERTIES);
	}

	/**
	 * Возвращает методы объекта для указанного встроенного класса.
	 */
	public static @NotNull List<BuiltinCallable> getMethodsForClass(@NotNull String className) {
		return Collections.unmodifiableList(collectBuiltinCallables(METHODS, className));
	}

	/**
	 * Возвращает конструкторы для указанного встроенного класса.
	 */
	public static @NotNull List<BuiltinCallable> getConstructorsForClass(@NotNull String className) {
		String lower = className.toLowerCase(java.util.Locale.ROOT);
		List<BuiltinCallable> ctors = CONSTRUCTORS.get(lower);
		return ctors != null ? Collections.unmodifiableList(ctors) : Collections.emptyList();
	}

	private static @NotNull List<CaretConstructor> collect(@NotNull Map<String, List<BuiltinCallable>> map) {
		List<CaretConstructor> result = new ArrayList<>();
		for (Map.Entry<String, List<BuiltinCallable>> entry : map.entrySet()) {
			String className = entry.getKey();
			for (BuiltinCallable callable : entry.getValue()) {
				result.add(new CaretConstructor(className, callable));
			}
		}
		return Collections.unmodifiableList(result);
	}


	public static @NotNull List<CaretConstructor> getAllConstructorsMeta() {
		return collect(CONSTRUCTORS);
	}

	public static @NotNull List<CaretConstructor> getAllSystemMethodsMeta() {
		return collect(SYSTEM_METHODS);
	}

	public static @NotNull List<CaretConstructor> getAllStaticMethodsMeta() {
		return collect(STATIC_METHODS);
	}

	public static @NotNull List<CaretConstructor> getAllMethodsMeta() {
		return collect(METHODS);
	}


	private static void collectNameUrlPairs(@NotNull Map<String, List<BuiltinCallable>> map, @NotNull List<String[]> target, @NotNull String separator) {
		for (Map.Entry<String, List<BuiltinCallable>> entry : map.entrySet()) {
			String className = entry.getKey();
			for (BuiltinCallable callable : entry.getValue()) {
				if (callable.url == null || callable.url.isEmpty()) {
					continue;
				}

				String key = (className == null || className.isEmpty() || separator.equals("."))
						? separator + callable.name
						: className + separator + callable.name;
				target.add(new String[] { key, callable.url, callable.description });
			}
		}
	}

	public static @NotNull List<String[]> getAllNameUrlPairs() {
		List<String[]> result = new ArrayList<>();
		collectNameUrlPairs(CONSTRUCTORS, result, "::");
		collectNameUrlPairs(STATIC_METHODS, result, ":");
		collectNameUrlPairs(METHODS, result, ".");
		collectNameUrlPairs(SYSTEM_METHODS, result, "");
		collectNameUrlPairs(PROPERTIES, result, ":");
		return result;
	}

	public static @NotNull List<String[]> findNameUrlConflicts(@NotNull String url) {
		List<String[]> all = getAllNameUrlPairs();
		String targetKey = null;
		for (String[] pair : all) {
			if (url.equals(pair[1])) {
				targetKey = pair[0];
				break;
			}
		}
		if (targetKey == null) {
			return Collections.emptyList();
		}
		List<String[]> result = new ArrayList<>();
		java.util.Set<String> seenUrls = new java.util.LinkedHashSet<>();
		for (String[] pair : all) {
			if (targetKey.equals(pair[0]) && seenUrls.add(pair[1])) {
				result.add(pair);
			}
		}
		return result;
	}


	// ---------- Регистрация ----------

	private static void registerConstructors(@NotNull String className, @NotNull BuiltinCallable... callables) {
		List<BuiltinCallable> list = CONSTRUCTORS.computeIfAbsent(className, k -> new ArrayList<>());
		Collections.addAll(list, callables);
	}
	private static void registerSystemMethods(@NotNull BuiltinCallable... callables) {
		List<BuiltinCallable> list = SYSTEM_METHODS.computeIfAbsent("", k -> new ArrayList<>());
		Collections.addAll(list, callables);
	}

	private static void registerStaticMethods(@NotNull String className, @NotNull BuiltinCallable... callables) {
		List<BuiltinCallable> list = STATIC_METHODS.computeIfAbsent(className, k -> new ArrayList<>());
		Collections.addAll(list, callables);
	}

	private static void registerMethods(@NotNull String className, @NotNull BuiltinCallable... callables) {
		List<BuiltinCallable> list = METHODS.computeIfAbsent(className, k -> new ArrayList<>());
		Collections.addAll(list, callables);
	}

	private static void registerProperties(@NotNull String className, @NotNull BuiltinCallable... callables) {
		List<BuiltinCallable> list = PROPERTIES.computeIfAbsent(className, k -> new ArrayList<>());
		Collections.addAll(list, callables);
	}

	private static void registerInstanceOnlyProperties(@NotNull String className, @NotNull BuiltinCallable... callables) {
		registerProperties(className, callables);
		String lower = className.toLowerCase(java.util.Locale.ROOT);
		Set<String> names = INSTANCE_ONLY_PROPERTIES.computeIfAbsent(lower, k -> new HashSet<>());
		for (BuiltinCallable callable : callables) {
			names.add(callable.name);
		}
	}

	private static void registerBuiltinInheritance(@NotNull String className, @NotNull String baseClassName) {
		BUILTIN_BASE_CLASSES.put(className.toLowerCase(java.util.Locale.ROOT), baseClassName.toLowerCase(java.util.Locale.ROOT));
	}

	private static @NotNull List<BuiltinCallable> collectBuiltinCallables(
			@NotNull Map<String, List<BuiltinCallable>> source,
			@NotNull String className
	) {
		List<BuiltinCallable> result = new ArrayList<>();
		Set<String> seenNames = new HashSet<>();
		String current = className.toLowerCase(java.util.Locale.ROOT);
		while (current != null && !current.isEmpty()) {
			List<BuiltinCallable> callables = source.get(current);
			if (callables != null) {
				for (BuiltinCallable callable : callables) {
					if (seenNames.add(callable.name)) {
						result.add(callable);
					}
				}
			}
			current = BUILTIN_BASE_CLASSES.get(current);
		}
		return result;
	}

	private static @NotNull List<BuiltinCallable> collectBuiltinProperties(
			@NotNull String className,
			boolean includeInstanceOnly
	) {
		List<BuiltinCallable> result = new ArrayList<>();
		Set<String> seenNames = new HashSet<>();
		String current = className.toLowerCase(java.util.Locale.ROOT);
		while (current != null && !current.isEmpty()) {
			List<BuiltinCallable> callables = PROPERTIES.get(current);
			if (callables != null) {
				for (BuiltinCallable callable : callables) {
					if (!includeInstanceOnly && isInstanceOnlyProperty(current, callable.name)) {
						continue;
					}
					if (seenNames.add(callable.name)) {
						result.add(callable);
					}
				}
			}
			current = BUILTIN_BASE_CLASSES.get(current);
		}
		return result;
	}

	private static boolean isInstanceOnlyProperty(@NotNull String className, @NotNull String propertyName) {
		Set<String> names = INSTANCE_ONLY_PROPERTIES.get(className.toLowerCase(java.util.Locale.ROOT));
		return names != null && names.contains(propertyName);
	}

	// Создание записи свойства (поля) — без suffix и template
	private static @NotNull BuiltinCallable prop(
			@NotNull String name,
			@NotNull String description,
			@Nullable String url
	) {
		return new BuiltinCallable(name, description, url, "", null, null);
	}

	// Создание записи свойства с указанием типа возвращаемого значения
	private static @NotNull BuiltinCallable prop(
			@NotNull String name,
			@NotNull String description,
			@Nullable String url,
			@NotNull String returnType
	) {
		return new BuiltinCallable(name, description, url, "", null, returnType, null);
	}

	// Создание записи свойства с типом и суффиксом для присваивания (например "[]")
	private static @NotNull BuiltinCallable prop(
			@NotNull String name,
			@NotNull String description,
			@Nullable String url,
			@NotNull String returnType,
			@NotNull String assignSuffix
	) {
		return new BuiltinCallable(name, description, url, "", null, returnType, assignSuffix);
	}

	// добавление одного
	private static @NotNull BuiltinCallable ctor(
			@NotNull String name,
			@Nullable String description,
			@Nullable String url,
			@NotNull String suffix,
			@Nullable String template
	) {
		return new BuiltinCallable(name, description, url, suffix, template);
	}

	private static @NotNull BuiltinCallable ctor(
			@NotNull String name,
			@Nullable String description,
			@Nullable String url,
			@NotNull String suffix,
			@Nullable String template,
			@Nullable String returnType
	) {
		return new BuiltinCallable(name, description, url, suffix, template, returnType);
	}
}
