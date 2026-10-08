package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates the class names in a plugin manifest after classes have been renamed.
 * <p>
 * The text is edited in place rather than being parsed and written back out. A manifest is hand-written, and rewriting
 * it from a data model would discard its comments, ordering, and quoting. Only the value of the top-level
 * {@code main}, {@code bootstrapper}, and {@code loader} keys is touched.
 * <p>
 * The edit is line based, so unusual layouts <i>(flow-style maps, multi-line scalars)</i> are not recognized. To avoid
 * silently leaving a stale name behind, the result is checked against the manifest parser and anything that should have
 * been updated, but was not, is reported in {@link Result#unresolved()}.
 */
public final class MinecraftPluginManifestRewriter {
	private static final Pattern CLASS_KEY_LINE = Pattern.compile(
			"^(?<key>main|bootstrapper|loader)(?<sep>[ \\t]*:[ \\t]*)(?<q>[\"']?)(?<val>[^\"'\\s#]+)\\k<q>(?<tail>[ \\t]*(?:#[^\\r\\n]*)?)$",
			Pattern.MULTILINE);

	private MinecraftPluginManifestRewriter() {}

	/**
	 * @param fileName
	 * 		Name of the manifest file.
	 * @param text
	 * 		Text of the manifest.
	 * @param classRenames
	 * 		Map of old internal class names to their new internal class names.
	 *
	 * @return Result containing the updated text, which is the same as the input when nothing needed to change.
	 */
	@Nonnull
	public static Result rewrite(@Nonnull String fileName, @Nonnull String text, @Nonnull Map<String, String> classRenames) {
		List<Change> changes = new ArrayList<>();
		Matcher matcher = CLASS_KEY_LINE.matcher(text);
		StringBuilder sb = new StringBuilder(text.length() + 16);
		while (matcher.find()) {
			String oldName = matcher.group("val").replace('.', '/');
			String newName = classRenames.get(oldName);
			if (newName == null || newName.equals(oldName)) {
				matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
				continue;
			}
			String quote = matcher.group("q");
			String replacement = matcher.group("key") + matcher.group("sep") + quote +
					newName.replace('/', '.') + quote + matcher.group("tail");
			matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
			changes.add(new Change(matcher.group("key"), oldName, newName));
		}
		matcher.appendTail(sb);
		String rewritten = changes.isEmpty() ? text : sb.toString();

		// Anything the original referenced that was renamed, but is not what we expect in the result, was missed.
		List<String> unresolved = new ArrayList<>();
		Optional<MinecraftPluginManifest> before = MinecraftPluginManifestParser.parse(fileName, text);
		Optional<MinecraftPluginManifest> after = MinecraftPluginManifestParser.parse(fileName, rewritten);
		if (before.isPresent()) {
			MinecraftPluginManifest original = before.get();
			MinecraftPluginManifest updated = after.orElse(null);
			checkResolved("main", original.mainClass(), updated == null ? null : updated.mainClass(), classRenames, unresolved);
			checkResolved("bootstrapper", original.bootstrapperClass(), updated == null ? null : updated.bootstrapperClass(), classRenames, unresolved);
			checkResolved("loader", original.loaderClass(), updated == null ? null : updated.loaderClass(), classRenames, unresolved);
		}
		return new Result(rewritten, List.copyOf(changes), List.copyOf(unresolved));
	}

	private static void checkResolved(@Nonnull String key, String originalValue, String updatedValue,
	                                  @Nonnull Map<String, String> classRenames, @Nonnull List<String> unresolved) {
		if (originalValue == null)
			return;
		String expected = classRenames.getOrDefault(originalValue, originalValue);
		if (!expected.equals(updatedValue))
			unresolved.add(key);
	}

	/**
	 * @param key
	 * 		Manifest key that was updated.
	 * @param oldName
	 * 		Internal name of the class before.
	 * @param newName
	 * 		Internal name of the class after.
	 */
	public record Change(@Nonnull String key, @Nonnull String oldName, @Nonnull String newName) {}

	/**
	 * @param text
	 * 		Updated manifest text.
	 * @param changes
	 * 		Edits that were made.
	 * @param unresolved
	 * 		Keys that reference a renamed class, but could not be updated.
	 */
	public record Result(@Nonnull String text, @Nonnull List<Change> changes, @Nonnull List<String> unresolved) {
		/**
		 * @return {@code true} when the text differs from the input.
		 */
		public boolean isModified() {
			return !changes.isEmpty();
		}
	}
}
