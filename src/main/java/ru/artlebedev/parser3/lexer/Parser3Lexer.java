package ru.artlebedev.parser3.lexer;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.artlebedev.parser3.Parser3TokenTypes;
import ru.artlebedev.parser3.settings.Parser3SqlInjectionSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * Основной лексер для Parser3, завязанный на IntelliJ.
 *
 * Архитектура:
 * - Parser3LexerCore.tokenize(text) → List<CoreToken>
 *   (TEMPLATE_DATA / LINE_COMMENT / BLOCK_COMMENT /
 *    DEFINE_METHOD / SPECIAL_METHOD / VARIABLE / LOCAL_VARIABLE)
 * - здесь токены маппятся на Parser3TokenTypes и режутся под диапазон [startOffset, endOffset)
 * - advance() возвращает по одному токену за вызов
 * - getState() пока всегда 0 (состояний нет)
 *
 * Важно:
 * - Core-уровень ничего не знает про IntelliJ.
 * - Подсветка корректно работает даже если start() вызывается с середины файла —
 *   мы просто отбрасываем токены, выходящие за пределы диапазона.
 *
 * ОПТИМИЗАЦИЯ:
 * - Используется Parser3EntityCache для инкрементального обновления токенов
 * - При редактировании перетокенизируются только затронутые "сущности"
 */
public final class Parser3Lexer extends LexerBase {

	private static final class TokenInfo {
		private final int start;
		private final int end;
		private final @NotNull IElementType type;
		@SuppressWarnings("unused")
		private final @Nullable String debugText;

		private TokenInfo(int start, int end, @NotNull IElementType type, @Nullable String debugText) {
			this.start = start;
			this.end = end;
			this.type = type;
			this.debugText = debugText;
		}
	}

	@Nullable
	private CharSequence buffer;
	private int bufferStart;
	private int bufferEnd;

	@Nullable
	private TokenInfo[] tokens = null;
	private int tokenIndex = 0;

	private int tokenStart = 0;
	private int tokenEnd = 0;
	@Nullable
	private IElementType tokenType = null;

	// Инкрементальный кеш на уровне сущностей
	private final Parser3EntityCache entityCache = new Parser3EntityCache();
	private @Nullable Boolean cachedLexerModeSinglePass = null;
	private @Nullable Integer cachedSqlInjectionPrefixesHash = null;

	private String escapeS (String text) {
		text = text.replace("\t", "\\t");
		text = text.replace("\n", "\\n");
		text = text.replace("\r", "\\r");
		return text;
	}

	@Override
	public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
		long totalStart = System.nanoTime();
		boolean useSinglePass = !Boolean.getBoolean("parser3.lexer.legacy");

		this.buffer = buffer;
		this.bufferStart = startOffset;
		this.bufferEnd = endOffset;

		Parser3LexerLog.log("======= Lexer.start() bufLen=%d, range=[%d,%d] =======",
				buffer.length(), startOffset, endOffset);

		// Быстрый путь: пустой диапазон
		if (startOffset == 0 && endOffset == 0) {
			this.tokens = new TokenInfo[0];
			this.tokenIndex = 0;
			setAtEnd(startOffset);
			Parser3LexerLog.log("  empty range, returning");
			return;
		}

		// Проверка на бинарный контент — если файл содержит нулевые байты,
		// это не текстовый файл. Возвращаем один токен HTML_DATA на весь диапазон.
		if (containsBinaryContent(buffer, startOffset, endOffset)) {
			Parser3LexerLog.log("  binary content detected, returning single HTML_DATA token");
			this.tokens = new TokenInfo[] {
					new TokenInfo(startOffset, endOffset, Parser3TokenTypes.HTML_DATA, null)
			};
			this.tokenIndex = 0;
			applyCurrentToken();
			return;
		}

		// Настраиваем SQL-инъекции
		Parser3LexerCore.setUserSqlInjectionPrefixesSupplier(Parser3SqlInjectionSupport::getConfiguredPrefixes);
		int sqlInjectionPrefixesHash = Parser3SqlInjectionSupport.getConfiguredPrefixes().hashCode();

		if (cachedLexerModeSinglePass == null || cachedLexerModeSinglePass != useSinglePass) {
			entityCache.invalidate();
			cachedLexerModeSinglePass = useSinglePass;
			Parser3LexerLog.log("  lexer mode changed, entity cache invalidated");
		}
		if (cachedSqlInjectionPrefixesHash == null || cachedSqlInjectionPrefixesHash != sqlInjectionPrefixesHash) {
			entityCache.invalidate();
			cachedSqlInjectionPrefixesHash = sqlInjectionPrefixesHash;
			Parser3LexerLog.log("  SQL injection prefixes changed, entity cache invalidated");
		}

		long cacheStart = System.nanoTime();
		List<Parser3LexerCore.CoreToken> coreTokens = entityCache.getTokens(buffer, startOffset, endOffset);
		Parser3LexerLog.logTime("  entityCache.getTokens total", cacheStart);

		// Маппим CoreToken → TokenInfo (IntelliJ-типы)
		long mapStart = System.nanoTime();
		TokenInfo[] allTokens = mapCoreTokensToIdeTokens(coreTokens, buffer);
		Parser3LexerLog.logTime("  mapCoreTokensToIdeTokens (" + allTokens.length + " tokens)", mapStart);

		// Возвращаем только токены в запрошенном диапазоне
		long sliceStart = System.nanoTime();
		this.tokens = sliceTokensForRange(allTokens, startOffset, endOffset);
		Parser3LexerLog.logTime("  sliceTokensForRange (" + this.tokens.length + " tokens)", sliceStart);

		this.tokenIndex = 0;

		if (this.tokens.length == 0) {
			setAtEnd(startOffset);
			return;
		}

		applyCurrentToken();
		Parser3LexerLog.logTimeAlways("Lexer.start() TOTAL", totalStart);
	}

	@Override
	public int getState() {
		// Пока никаких состояний нет
		return 0;
	}

	@Override
	public @Nullable IElementType getTokenType() {
		return tokenType;
	}

	@Override
	public int getTokenStart() {
		return tokenStart;
	}

	@Override
	public int getTokenEnd() {
		return tokenEnd;
	}

	@Override
	public void advance() {
		if (tokens == null) {
			setAtEnd(bufferEnd);
			return;
		}

		if (tokenIndex >= tokens.length) {
			setAtEnd(bufferEnd);
			return;
		}

		tokenIndex++;
		if (tokenIndex >= tokens.length) {
			setAtEnd(bufferEnd);
			return;
		}

		applyCurrentToken();
	}

	private void setAtEnd(int offset) {
		this.tokenType = null;
		this.tokenStart = offset;
		this.tokenEnd = offset;
	}

	private void applyCurrentToken() {
		TokenInfo info = tokens[tokenIndex];
		this.tokenStart = info.start;
		this.tokenEnd = info.end;
		this.tokenType = info.type;
	}

	// ------------------------------------------------------------
	// Маппинг CoreToken → TokenInfo с IntelliJ-типами
	// ------------------------------------------------------------

	@NotNull
	private TokenInfo[] mapCoreTokensToIdeTokens(@NotNull List<Parser3LexerCore.CoreToken> coreTokens,
												 @NotNull CharSequence text) {

		List<TokenInfo> result = new ArrayList<>(coreTokens.size());

		for (Parser3LexerCore.CoreToken core : coreTokens) {
			IElementType ideType;
			if ("WHITE_SPACE".equals(core.type)) {
				ideType = TokenType.WHITE_SPACE;
			} else {
				ideType = mapCoreTypeToIdeType(core.type);
			}
			String debug = Parser3LexerCore.substringSafely(text, core.start, core.end);
			result.add(new TokenInfo(core.start, core.end, ideType, debug));
		}

		return result.toArray(new TokenInfo[0]);
	}

	@NotNull
	private IElementType mapCoreTypeToIdeType(@NotNull String coreType) {
		return Parser3TokenTypes.byName(coreType);
	}

	// ------------------------------------------------------------
	// Обрезаем токены под запрошенный диапазон
	// ------------------------------------------------------------

	@NotNull
	private TokenInfo[] sliceTokensForRange(@NotNull TokenInfo[] source, int rangeStart, int rangeEnd) {
		if (rangeStart >= rangeEnd) {
			return new TokenInfo[0];
		}
		if (source.length == 0) {
			return createFallbackRangeToken(rangeStart, rangeEnd);
		}

		List<TokenInfo> sliced = new ArrayList<>();

		for (TokenInfo token : source) {
			if (token.end <= rangeStart) {
				continue;
			}
			if (token.start >= rangeEnd) {
				break;
			}

			int clippedStart = Math.max(token.start, rangeStart);
			int clippedEnd = Math.min(token.end, rangeEnd);
			if (clippedStart >= clippedEnd) {
				continue;
			}

			String debug = null;
			if (buffer != null) {
				debug = Parser3LexerCore.substringSafely(buffer, clippedStart, clippedEnd);
			}

			sliced.add(new TokenInfo(clippedStart, clippedEnd, token.type, debug));
		}

		TokenInfo[] result = sliced.toArray(new TokenInfo[0]);
		if (!isContinuousInRange(result, rangeStart, rangeEnd)) {
			Parser3LexerLog.log("  sliced tokens are discontinuous for range [%d,%d], recovering by entities", rangeStart, rangeEnd);
			return recoverDiscontinuousRangeByEntities(source, rangeStart, rangeEnd);
		}

		return result;
	}

	@NotNull
	private TokenInfo[] recoverDiscontinuousRangeByEntities(@NotNull TokenInfo[] source, int rangeStart, int rangeEnd) {
		if (buffer == null) {
			return createTemplateFallbackRangeToken(rangeStart, rangeEnd);
		}

		List<Parser3EntityParser.Entity> entities = Parser3EntityParser.parseEntities(buffer);
		if (entities.isEmpty()) {
			return createTemplateFallbackRangeToken(rangeStart, rangeEnd);
		}

		List<TokenInfo> recovered = new ArrayList<>();
		int cursor = rangeStart;

		for (Parser3EntityParser.Entity entity : entities) {
			if (entity.end <= rangeStart) {
				continue;
			}
			if (entity.start >= rangeEnd) {
				break;
			}

			int entityRangeStart = Math.max(rangeStart, entity.start);
			int entityRangeEnd = Math.min(rangeEnd, entity.end);
			if (entityRangeStart >= entityRangeEnd) {
				continue;
			}

			TokenInfo[] entitySlice = sliceTokensRaw(source, entityRangeStart, entityRangeEnd);
			if (isContinuousInRange(entitySlice, entityRangeStart, entityRangeEnd)) {
				for (TokenInfo token : entitySlice) {
					recovered.add(token);
				}
			} else {
				TokenInfo[] fallback = createTemplateFallbackRangeToken(entityRangeStart, entityRangeEnd);
				for (TokenInfo token : fallback) {
					recovered.add(token);
				}
			}
			cursor = entityRangeEnd;
		}

		if (cursor < rangeEnd) {
			TokenInfo[] tailFallback = createTemplateFallbackRangeToken(cursor, rangeEnd);
			for (TokenInfo token : tailFallback) {
				recovered.add(token);
			}
		}

		TokenInfo[] result = recovered.toArray(new TokenInfo[0]);
		if (!isContinuousInRange(result, rangeStart, rangeEnd)) {
			Parser3LexerLog.log("  entity recovery failed for range [%d,%d], using TEMPLATE_DATA fallback", rangeStart, rangeEnd);
			return createTemplateFallbackRangeToken(rangeStart, rangeEnd);
		}
		return result;
	}

	@NotNull
	private TokenInfo[] sliceTokensRaw(@NotNull TokenInfo[] source, int rangeStart, int rangeEnd) {
		List<TokenInfo> sliced = new ArrayList<>();

		for (TokenInfo token : source) {
			if (token.end <= rangeStart) {
				continue;
			}
			if (token.start >= rangeEnd) {
				break;
			}

			int clippedStart = Math.max(token.start, rangeStart);
			int clippedEnd = Math.min(token.end, rangeEnd);
			if (clippedStart >= clippedEnd) {
				continue;
			}

			String debug = null;
			if (buffer != null) {
				debug = Parser3LexerCore.substringSafely(buffer, clippedStart, clippedEnd);
			}

			sliced.add(new TokenInfo(clippedStart, clippedEnd, token.type, debug));
		}

		return sliced.toArray(new TokenInfo[0]);
	}

	private boolean isContinuousInRange(@NotNull TokenInfo[] source, int rangeStart, int rangeEnd) {
		if (source.length == 0) {
			return false;
		}
		if (source[0].start != rangeStart) {
			return false;
		}
		for (int i = 1; i < source.length; i++) {
			if (source[i].start != source[i - 1].end) {
				return false;
			}
		}
		return source[source.length - 1].end == rangeEnd;
	}

	@NotNull
	private TokenInfo[] createFallbackRangeToken(int rangeStart, int rangeEnd) {
		String debug = null;
		if (buffer != null) {
			debug = Parser3LexerCore.substringSafely(buffer, rangeStart, rangeEnd);
		}
		return new TokenInfo[] {
				new TokenInfo(rangeStart, rangeEnd, Parser3TokenTypes.HTML_DATA, debug)
		};
	}

	@NotNull
	private TokenInfo[] createTemplateFallbackRangeToken(int rangeStart, int rangeEnd) {
		String debug = null;
		if (buffer != null) {
			debug = Parser3LexerCore.substringSafely(buffer, rangeStart, rangeEnd);
		}
		return new TokenInfo[] {
				new TokenInfo(rangeStart, rangeEnd, Parser3TokenTypes.TEMPLATE_DATA, debug)
		};
	}

	// ------------------------------------------------------------
	// Обязательные методы LexerBase для доступа к buffer
	// ------------------------------------------------------------

	@Override
	public @NotNull CharSequence getBufferSequence() {
		return buffer != null ? buffer : "";
	}

	@Override
	public int getBufferEnd() {
		return bufferEnd;
	}

	/**
	 * Проверяет содержит ли буфер бинарные данные.
	 * Сканирует первые 8К символов на наличие нулевых байтов (\0).
	 * Нулевой байт практически никогда не встречается в текстовых файлах Parser3.
	 */
	private static boolean containsBinaryContent(@NotNull CharSequence buffer, int start, int end) {
		int checkEnd = Math.min(end, start + 8192);
		for (int i = start; i < checkEnd; i++) {
			if (buffer.charAt(i) == '\0') {
				return true;
			}
		}
		return false;
	}
}
