package ru.artlebedev.parser3.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service(Service.Level.APP)
@State(
		name = "Parser3SqlInjections",
		storages = @Storage("parser3.xml")
)
public final class Parser3SqlInjectionsService implements PersistentStateComponent<Parser3SqlInjectionsService.State> {

	public static final class State {
		public List<String> prefixes = new ArrayList<>();
	}

	private State state = new State();

	@Override
	public @Nullable State getState() {
		return state;
	}

	@Override
	public void loadState(@NotNull State state) {
		this.state = state;
	}

	public @NotNull List<String> getPrefixes() {
		return state.prefixes;
	}

	public void setPrefixes(@NotNull List<String> prefixes) {
		state.prefixes = new ArrayList<>(prefixes);
	}

	public @NotNull List<String> getNormalizedPrefixesCopy() {
		if (state.prefixes == null || state.prefixes.isEmpty()) {
			return Collections.emptyList();
		}

		List<String> result = new ArrayList<>();
		for (String v : state.prefixes) {
			if (v == null) {
				continue;
			}
			String s = v.trim();
			if (!s.isEmpty()) {
				result.add(s);
			}
		}
		return result;
	}
}
