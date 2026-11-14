package software.coley.recaf.ui.window;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import jakarta.annotation.Nonnull;

import java.util.Arrays;
import java.util.List;

/**
 * Line-based preview diff with three lines of context around each change.
 * Computed off the JavaFX thread; the same model supports source and bytecode previews.
 */
record DeobfuscationDiff(@Nonnull List<String> lines, int added, int removed) {
	@Nonnull
	static DeobfuscationDiff between(@Nonnull String before, @Nonnull String after) {
		if (before.equals(after))
			return new DeobfuscationDiff(List.of(), 0, 0);
		List<String> original = lines(before);
		List<String> modified = lines(after);
		var patch = DiffUtils.diff(original, modified);
		int added = 0;
		int removed = 0;
		for (var delta : patch.getDeltas()) {
			added += delta.getTarget().size();
			removed += delta.getSource().size();
		}
		return new DeobfuscationDiff(List.copyOf(UnifiedDiffUtils.generateUnifiedDiff(
				"Before", "After", original, patch, 3)), added, removed);
	}

	private static List<String> lines(String text) {
		return text.isEmpty() ? List.of() : Arrays.asList(text.split("\\R", -1));
	}
}
