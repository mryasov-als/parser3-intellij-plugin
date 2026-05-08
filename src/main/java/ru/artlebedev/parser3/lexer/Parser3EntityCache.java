package ru.artlebedev.parser3.lexer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Кеш токенов с инкрементальным обновлением на уровне сущностей.
 */
public class Parser3EntityCache {

	@Nullable
	private List<CachedEntity> cachedEntities = null;

	private static class CachedEntity {
		final int contentHash;
		final int length;
		List<Parser3LexerCore.CoreToken> tokens;

		CachedEntity(int contentHash, int length, List<Parser3LexerCore.CoreToken> tokens) {
			this.contentHash = contentHash;
			this.length = length;
			this.tokens = tokens;
		}
	}

	@NotNull
	public synchronized List<Parser3LexerCore.CoreToken> getTokens(
			@NotNull CharSequence buffer,
			int startOffset,
			int endOffset
	) {
		long totalStart = System.nanoTime();

		// 1. Разбиваем на сущности
		long parseStart = System.nanoTime();
		List<Parser3EntityParser.Entity> newEntities = Parser3EntityParser.parseEntities(buffer);
		Parser3LexerLog.logTime("  parseEntities (" + newEntities.size() + " entities)", parseStart);

		// Логируем размеры сущностей для диагностики
		if (Parser3LexerLog.isEnabled() && !newEntities.isEmpty()) {
			int maxSize = 0;
			int maxIdx = 0;
			for (int i = 0; i < newEntities.size(); i++) {
				Parser3EntityParser.Entity e = newEntities.get(i);
				int size = e.end - e.start;
				if (size > maxSize) {
					maxSize = size;
					maxIdx = i;
				}
			}
			Parser3EntityParser.Entity biggest = newEntities.get(maxIdx);
			Parser3LexerLog.log("  biggest entity #%d: [%d-%d] size=%d chars",
					maxIdx, biggest.start, biggest.end, maxSize);
		}

		// Проверяем что сущности непрерывны
		if (!checkEntitiesContinuous(newEntities, buffer.length())) {
			Parser3LexerLog.log("  entities NOT continuous! fullTokenizeDirect");
			List<Parser3LexerCore.CoreToken> result = fullTokenizeDirect(buffer);
			Parser3LexerLog.logTimeAlways("EntityCache.getTokens (direct, " + result.size() + " tokens)", totalStart);
			return result;
		}

		// 2. Если кеша нет — полная токенизация
		if (cachedEntities == null) {
			Parser3LexerLog.log("  no cache, fullTokenize");
			List<Parser3LexerCore.CoreToken> result = fullTokenize(buffer, newEntities);
			Parser3LexerLog.logTimeAlways("EntityCache.getTokens (full, " + result.size() + " tokens)", totalStart);
			return result;
		}

		// 3. Инкрементальное обновление
		Parser3LexerLog.log("  incremental: old=%d entities, new=%d entities", cachedEntities.size(), newEntities.size());

		long indexStart = System.nanoTime();
		java.util.Map<Long, List<Integer>> oldHashIndex = new java.util.HashMap<>();
		for (int i = 0; i < cachedEntities.size(); i++) {
			CachedEntity old = cachedEntities.get(i);
			long key = ((long) old.contentHash << 32) | old.length;
			oldHashIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
		}
		Parser3LexerLog.logTime("  buildHashIndex", indexStart);

		boolean[] usedOld = new boolean[cachedEntities.size()];
		List<CachedEntity> newCachedEntities = new ArrayList<>(newEntities.size());
		List<Parser3LexerCore.CoreToken> allTokens = new ArrayList<>();

		int currentOffset = 0;
		int reusedCount = 0;
		int retokenizedCount = 0;
		long retokenizeTime = 0;

		for (Parser3EntityParser.Entity newEntity : newEntities) {
			int newLength = newEntity.end - newEntity.start;
			int newHash = newEntity.hash;

			long key = ((long) newHash << 32) | newLength;
			List<Integer> candidates = oldHashIndex.get(key);

			CachedEntity matched = null;
			if (candidates != null) {
				for (int oldIdx : candidates) {
					if (!usedOld[oldIdx]) {
						matched = cachedEntities.get(oldIdx);
						usedOld[oldIdx] = true;
						break;
					}
				}
			}

			List<Parser3LexerCore.CoreToken> entityTokens;

			if (matched != null) {
				entityTokens = shiftTokens(matched.tokens, currentOffset);
				newCachedEntities.add(new CachedEntity(newHash, newLength, matched.tokens));
				reusedCount++;
			} else {
				long tokenizeStart = System.nanoTime();
				List<Parser3LexerCore.CoreToken> rawTokens = Parser3LexerCore.tokenize(buffer, newEntity.start, newEntity.end);
				long elapsed = System.nanoTime() - tokenizeStart;
				retokenizeTime += elapsed;

				Parser3LexerLog.log("    retokenize entity [%d-%d] size=%d chars, %d tokens, %.2fms",
						newEntity.start, newEntity.end, newLength, rawTokens.size(), elapsed / 1_000_000.0);

				List<Parser3LexerCore.CoreToken> relativeTokens = shiftTokens(rawTokens, -newEntity.start);
				newCachedEntities.add(new CachedEntity(newHash, newLength, relativeTokens));
				entityTokens = rawTokens;
				retokenizedCount++;
			}

			allTokens.addAll(entityTokens);
			currentOffset += newLength;
		}

		Parser3LexerLog.log("  reused=%d, retokenized=%d, retokenizeTime=%.2fms",
				reusedCount, retokenizedCount, retokenizeTime / 1_000_000.0);

		if (!checkTokensContinuous(allTokens, buffer.length())) {
			Parser3LexerLog.log("  tokens NOT continuous! fullTokenizeDirect");
			return fullTokenizeDirect(buffer);
		}

		cachedEntities = newCachedEntities;
		Parser3LexerLog.logTimeAlways("EntityCache.getTokens (incremental, " + allTokens.size() + " tokens)", totalStart);
		return allTokens;
	}

	private boolean checkEntitiesContinuous(List<Parser3EntityParser.Entity> entities, int bufferLength) {
		if (entities.isEmpty()) {
			return bufferLength == 0;
		}
		if (entities.get(0).start != 0) {
			return false;
		}
		for (int i = 1; i < entities.size(); i++) {
			if (entities.get(i).start != entities.get(i-1).end) {
				return false;
			}
		}
		return entities.get(entities.size()-1).end == bufferLength;
	}

	private boolean checkTokensContinuous(List<Parser3LexerCore.CoreToken> tokens, int bufferLength) {
		if (tokens.isEmpty()) {
			return bufferLength == 0;
		}
		if (tokens.get(0).start != 0) {
			return false;
		}
		for (int i = 1; i < tokens.size(); i++) {
			if (tokens.get(i).start != tokens.get(i-1).end) {
				return false;
			}
		}
		return tokens.get(tokens.size()-1).end == bufferLength;
	}

	@NotNull
	private List<Parser3LexerCore.CoreToken> fullTokenizeDirect(@NotNull CharSequence buffer) {
		cachedEntities = null;
		return Parser3LexerCore.tokenize(buffer, 0, buffer.length());
	}

	@NotNull
	private List<Parser3LexerCore.CoreToken> fullTokenize(
			@NotNull CharSequence buffer,
			@NotNull List<Parser3EntityParser.Entity> entities
	) {
		cachedEntities = new ArrayList<>(entities.size());
		List<Parser3LexerCore.CoreToken> allTokens = new ArrayList<>();

		for (Parser3EntityParser.Entity entity : entities) {
			List<Parser3LexerCore.CoreToken> rawTokens = Parser3LexerCore.tokenize(buffer, entity.start, entity.end);
			List<Parser3LexerCore.CoreToken> relativeTokens = shiftTokens(rawTokens, -entity.start);
			cachedEntities.add(new CachedEntity(entity.hash, entity.end - entity.start, relativeTokens));
			allTokens.addAll(rawTokens);
		}

		return allTokens;
	}

	@NotNull
	private List<Parser3LexerCore.CoreToken> shiftTokens(
			@NotNull List<Parser3LexerCore.CoreToken> tokens,
			int delta
	) {
		if (delta == 0) {
			return new ArrayList<>(tokens);
		}
		List<Parser3LexerCore.CoreToken> shifted = new ArrayList<>(tokens.size());
		for (Parser3LexerCore.CoreToken t : tokens) {
			shifted.add(new Parser3LexerCore.CoreToken(
					t.start + delta,
					t.end + delta,
					t.type,
					t.debugText
			));
		}
		return shifted;
	}

	public synchronized void invalidate() {
		cachedEntities = null;
	}
}