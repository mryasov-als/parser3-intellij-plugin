package ru.artlebedev.parser3.settings;

import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Единое место для чтения и сопоставления пользовательских SQL injection prefixes.
 */
public final class Parser3SqlInjectionSupport {

	private Parser3SqlInjectionSupport() {
	}

	@NotNull
	public static List<String> getConfiguredPrefixes() {
		if (ApplicationManager.getApplication() == null) {
			return List.of();
		}

		Parser3SqlInjectionsService service = ApplicationManager.getApplication().getService(Parser3SqlInjectionsService.class);
		return service != null ? service.getNormalizedPrefixesCopy() : List.of();
	}

	public static boolean matchesConfiguredPrefix(@NotNull String callPrefix) {
		String normalizedCallPrefix = callPrefix.trim();
		if (normalizedCallPrefix.isEmpty()) {
			return false;
		}

		for (String prefix : getConfiguredPrefixes()) {
			if (normalizedCallPrefix.equals(prefix)) {
				return true;
			}
		}

		return false;
	}
}
