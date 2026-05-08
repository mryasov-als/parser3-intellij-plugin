package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Парсер "сущностей" (entities) для инкрементального кеширования токенов.
 *
 * Сущность — крупный безопасный фрагмент для инкрементального кеша.
 *
 * Для single-pass лексера безопасная граница — это новый @method / @directive
 * в начале строки. Внутри одного метода лексер держит состояние
 * (HTML/SQL/директивы), поэтому резать его на отдельные $/^-строки нельзя:
 * такая нарезка меняет результат токенизации по сравнению с полным проходом файла.
 *
 * Учитывает:
 * - Строчные комментарии #...
 * - Блочные комментарии ^rem{...}
 * - Экранирование ^[ ^] ^{ ^} ^( ^)
 */
public class Parser3EntityParser {

	/**
	 * Границы одной сущности.
	 */
	public static class Entity {
		public final int start;
		public final int end;
		public final int hash; // Хеш содержимого для проверки изменений

		public Entity(int start, int end, int hash) {
			this.start = start;
			this.end = end;
			this.hash = hash;
		}
	}

	/**
	 * Разбивает текст на сущности.
	 * Быстрая операция O(n) — просто проход по символам.
	 *
	 * ВАЖНО: сущности должны быть НЕПРЕРЫВНЫМИ (покрывать весь файл без дырок),
	 * иначе IntelliJ выдаст "Discontinuous sequence of tokens".
	 */
	@NotNull
	public static List<Entity> parseEntities(@NotNull CharSequence text) {
		List<Entity> entities = new ArrayList<>();
		int length = text.length();

		if (length == 0) {
			return entities;
		}

		int entityStart = 0;
		int bracketDepth = 0;  // Суммарная глубина []{}()
		int remDepth = 0;      // Глубина ^rem{} (для вложенных rem)
		boolean inLineComment = false;
		boolean inRemBlock = false;
		int i = 0;
		while (i < length) {
			char c = text.charAt(i);

			// === Обработка переводов строки ===
			if (c == '\n') {
				inLineComment = false;

				// Для single-pass режем только по началу следующего метода/директивы.
				// Внутренние строки метода должны оставаться в одной сущности,
				// иначе теряется межстрочное состояние лексера.
				if (bracketDepth == 0 && !inRemBlock) {
					int nextNonEmpty = findNextNonWhitespace(text, i + 1);

					if (nextNonEmpty == -1) {
						i++;
						continue;
					}

					char nextChar = text.charAt(nextNonEmpty);
					boolean isNewEntity = nextChar == '@' && isAtLineStart(text, nextNonEmpty);

					if (isNewEntity && i >= entityStart) {
						int hash = computeHash(text, entityStart, i + 1);
						entities.add(new Entity(entityStart, i + 1, hash));
						entityStart = i + 1;
					}
				}
				i++;
				continue;
			}

			// === Строчный комментарий # ===
			// В Parser3 комментарий # работает только в начале строки (после пробелов/табов)
			if (c == '#' && !inLineComment && !inRemBlock) {
				if (isAtLineStart(text, i)) {
					inLineComment = true;
				}
				i++;
				continue;
			}

			// Внутри строчного комментария — пропускаем
			if (inLineComment) {
				i++;
				continue;
			}

			// === Проверка на @method (принудительный конец сущности) ===
			// @method в начале строки ВСЕГДА начинает новую сущность,
			// независимо от баланса скобок (несбалансированные скобки — ошибка в предыдущем коде)
			if (c == '@' && !inRemBlock) {
				// Проверяем что это начало строки (или после пробелов)
				if (isAtLineStart(text, i)) {
					// Заканчиваем текущую сущность перед @method
					if (i > entityStart && hasNonWhitespace(text, entityStart, i)) {
						int hash = computeHash(text, entityStart, i);
						entities.add(new Entity(entityStart, i, hash));
					}
					entityStart = i;
					// Сбрасываем состояние для новой сущности
					bracketDepth = 0;
					inLineComment = false;
				}
				i++;
				continue;
			}

			// === Проверка на ^rem{ ===
			if (c == '^' && !inRemBlock && i + 4 < length) {
				if (matchesAt(text, i, "^rem{")) {
					inRemBlock = true;
					remDepth = 1;
					i += 5;
					continue;
				}
			}

			// === Внутри ^rem{} — считаем только {} для определения конца ===
			if (inRemBlock) {
				if (c == '{' && !isEscaped(text, i)) {
					remDepth++;
				} else if (c == '}' && !isEscaped(text, i)) {
					remDepth--;
					if (remDepth == 0) {
						inRemBlock = false;
					}
				}
				i++;
				continue;
			}

			// === Экранированные скобки ^[ ^] ^{ ^} ^( ^) — пропускаем ===
			if (c == '^' && i + 1 < length) {
				char next = text.charAt(i + 1);
				if (next == '[' || next == ']' || next == '{' || next == '}' || next == '(' || next == ')') {
					i += 2; // Пропускаем и ^, и скобку
					continue;
				}
			}

			// === Скобки — меняем баланс ===
			if (c == '[' || c == '{' || c == '(') {
				bracketDepth++;
			} else if (c == ']' || c == '}' || c == ')') {
				bracketDepth--;
				if (bracketDepth < 0) {
					bracketDepth = 0; // Защита от несбалансированных скобок
				}
			}

			i++;
		}

		// Последняя сущность (до конца файла)
		if (entityStart < length) {
			int hash = computeHash(text, entityStart, length);
			entities.add(new Entity(entityStart, length, hash));
		}

		return entities;
	}

	/**
	 * Находит индекс сущности, содержащей позицию.
	 */
	public static int findEntityAt(@NotNull List<Entity> entities, int pos) {
		// Бинарный поиск
		int lo = 0;
		int hi = entities.size() - 1;

		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			Entity e = entities.get(mid);

			if (pos < e.start) {
				hi = mid - 1;
			} else if (pos >= e.end) {
				lo = mid + 1;
			} else {
				return mid;
			}
		}

		return -1;
	}

	/**
	 * Находит диапазон сущностей, затронутых изменением.
	 * Возвращает [firstIdx, lastIdx] включительно.
	 */
	@Nullable
	public static int[] findAffectedRange(@NotNull List<Entity> entities, int changeStart, int changeEnd) {
		if (entities.isEmpty()) {
			return null;
		}

		int first = findEntityAt(entities, changeStart);
		int last = findEntityAt(entities, changeEnd);

		// Если позиция между сущностями
		if (first < 0) {
			first = 0;
			for (int i = 0; i < entities.size(); i++) {
				if (entities.get(i).start > changeStart) {
					first = Math.max(0, i - 1);
					break;
				}
			}
		}

		if (last < 0) {
			last = entities.size() - 1;
		}

		return new int[]{first, last};
	}

	// === Вспомогательные методы ===

	/**
	 * Находит позицию следующего непробельного символа.
	 * Возвращает -1 если до конца файла только пробелы.
	 */
	private static int findNextNonWhitespace(@NotNull CharSequence text, int start) {
		int length = text.length();
		for (int i = start; i < length; i++) {
			char c = text.charAt(i);
			if (!Character.isWhitespace(c)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean hasNonWhitespace(@NotNull CharSequence text, int start, int end) {
		for (int i = start; i < end; i++) {
			char c = text.charAt(i);
			if (!Character.isWhitespace(c)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isAtLineStart(@NotNull CharSequence text, int pos) {
		if (pos == 0) {
			return true;
		}
		// Ищем назад — должны быть только пробелы/табы до начала строки или начала файла
		for (int i = pos - 1; i >= 0; i--) {
			char c = text.charAt(i);
			if (c == '\n') {
				return true;
			}
			if (c != ' ' && c != '\t') {
				return false;
			}
		}
		return true;
	}

	private static boolean isEscaped(@NotNull CharSequence text, int pos) {
		int count = 0;
		int i = pos - 1;
		while (i >= 0 && text.charAt(i) == '^') {
			count++;
			i--;
		}
		return (count % 2) != 0;
	}

	private static boolean matchesAt(@NotNull CharSequence text, int pos, @NotNull String pattern) {
		if (pos + pattern.length() > text.length()) {
			return false;
		}
		for (int i = 0; i < pattern.length(); i++) {
			if (text.charAt(pos + i) != pattern.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static int computeHash(@NotNull CharSequence text, int start, int end) {
		// Простой и быстрый хеш
		int hash = 0;
		for (int i = start; i < end; i++) {
			hash = 31 * hash + text.charAt(i);
		}
		return hash;
	}
}
