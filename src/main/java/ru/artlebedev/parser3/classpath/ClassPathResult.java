package ru.artlebedev.parser3.classpath;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of CLASS_PATH evaluation with confidence level.
 */
public final class ClassPathResult {
    
    public enum Confidence {
        /** All paths are known with certainty */
        FULL,
        /** Some paths are known, but there may be more */
        PARTIAL,
        /** Cannot determine CLASS_PATH at all */
        UNKNOWN
    }
    
    private final @NotNull List<String> paths;
    private final @NotNull Confidence confidence;
    private final @NotNull String reason;
    
    private ClassPathResult(
        @NotNull List<String> paths, 
        @NotNull Confidence confidence,
        @NotNull String reason
    ) {
        this.paths = Collections.unmodifiableList(new ArrayList<>(paths));
        this.confidence = confidence;
        this.reason = reason;
    }
    
    public static ClassPathResult full(@NotNull List<String> paths) {
        return new ClassPathResult(paths, Confidence.FULL, "Fully evaluated");
    }
    
    public static ClassPathResult partial(@NotNull List<String> paths, @NotNull String reason) {
        return new ClassPathResult(paths, Confidence.PARTIAL, reason);
    }
    
    public static ClassPathResult unknown(@NotNull String reason) {
        return new ClassPathResult(Collections.emptyList(), Confidence.UNKNOWN, reason);
    }
    
    public @NotNull List<String> getPaths() {
        return paths;
    }
    
    public @NotNull Confidence getConfidence() {
        return confidence;
    }
    
    public @NotNull String getReason() {
        return reason;
    }
    
    public boolean isEmpty() {
        return paths.isEmpty();
    }
    
    @Override
    public String toString() {
        return "ClassPathResult{" +
               "confidence=" + confidence +
               ", paths=" + paths +
               ", reason='" + reason + '\'' +
               '}';
    }
}
