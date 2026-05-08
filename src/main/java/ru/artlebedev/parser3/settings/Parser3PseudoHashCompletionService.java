package ru.artlebedev.parser3.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.APP)
@State(
		name = "Parser3PseudoHashCompletion",
		storages = @Storage("parser3.xml")
)
public final class Parser3PseudoHashCompletionService implements PersistentStateComponent<Parser3PseudoHashCompletionService.State> {

	public static final class State {
		public @Nullable String configJson;
	}

	private State state = new State();

	public static Parser3PseudoHashCompletionService getInstance() {
		return ApplicationManager.getApplication().getService(Parser3PseudoHashCompletionService.class);
	}

	@Override
	public @NotNull State getState() {
		return state;
	}

	@Override
	public void loadState(@NotNull State state) {
		this.state = state;
	}

	public @Nullable String getConfigJson() {
		return normalize(state.configJson);
	}

	public void setConfigJson(@Nullable String json) {
		state.configJson = normalize(json);
	}

	private static @Nullable String normalize(@Nullable String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
