package ru.artlebedev.parser3.index;

import com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.annotations.NotNull;

/**
 * Общая обработка исключений индексаторов Parser3.
 */
public final class P3IndexExceptionUtil {

	private P3IndexExceptionUtil() {
	}

	public static void rethrowIfControlFlow(@NotNull Throwable e) {
		if (e instanceof ProcessCanceledException) {
			throw (ProcessCanceledException) e;
		}
	}
}
