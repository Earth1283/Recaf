package software.coley.recaf.ui.pane;

import jakarta.annotation.Nonnull;
import software.coley.recaf.config.ConfigContainer;
import software.coley.recaf.config.ConfigValue;
import software.coley.recaf.util.Lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static software.coley.recaf.config.ConfigGroups.PACKAGE_SPLIT;
import static software.coley.recaf.config.ConfigGroups.getGroupPackages;

/**
 * Search helpers for {@link ConfigPane}.
 * <p>
 * Searching operates on two levels:
 * <ul>
 *     <li><b>Header text:</b> The group and name of a container. When this alone satisfies the query,
 *     the whole container is a match and all of its values are shown.</li>
 *     <li><b>Value text:</b> The label and raw ID of a single value. A value matches when each query token
 *     appears in either its own text <i>or</i> the header text of its container.
 *     So {@code decompiler indent} finds an {@code indent} value in a {@code decompiler} container.</li>
 * </ul>
 * A container is included in the results when any of its visible values match.
 *
 * @author Matt Coley
 */
public class ConfigPaneSearch {
	private ConfigPaneSearch() {}

	/**
	 * @param container
	 * 		Config container to check.
	 * @param query
	 * 		Query text.
	 *
	 * @return {@code true} when any visible value in the container matches the query.
	 */
	protected static boolean matches(@Nonnull ConfigContainer container, @Nonnull String query) {
		return matches(container, tokenizeQuery(query));
	}

	/**
	 * @param text
	 * 		Query text.
	 *
	 * @return Lower-cased, whitespace-split search tokens.
	 */
	@Nonnull
	protected static List<String> tokenizeQuery(@Nonnull String text) {
		return Arrays.stream(text.toLowerCase().trim().split("\\s+"))
				.filter(token -> !token.isBlank())
				.toList();
	}

	/**
	 * @param container
	 * 		Config container to check.
	 * @param tokens
	 * 		Query tokens.
	 *
	 * @return {@code true} when any visible value in the container matches the tokens.
	 */
	protected static boolean matches(@Nonnull ConfigContainer container, @Nonnull List<String> tokens) {
		if (tokens.isEmpty())
			return true;
		return !matchingValueIds(container, tokens).isEmpty();
	}

	/**
	 * @param container
	 * 		Config container to check.
	 * @param tokens
	 * 		Query tokens.
	 *
	 * @return IDs of the visible values in the container that match the tokens.
	 * With no tokens, all visible values are matched.
	 */
	@Nonnull
	protected static Set<String> matchingValueIds(@Nonnull ConfigContainer container, @Nonnull List<String> tokens) {
		String headerText = buildHeaderText(container);
		Set<String> ids = new LinkedHashSet<>();
		for (ConfigValue<?> value : container.getValues().values()) {
			if (value.isHidden())
				continue;

			if (matchesAll(headerText + "\n" + buildValueText(container, value), tokens))
				ids.add(value.getId());
		}
		return ids;
	}

	/**
	 * @param container
	 * 		Config container to build searchable text for.
	 *
	 * @return Lower-cased search corpus for the group and name of the container.
	 */
	@Nonnull
	protected static String buildHeaderText(@Nonnull ConfigContainer container) {
		List<String> parts = new ArrayList<>();

		String currentPackage = null;
		for (String packageName : getGroupPackages(container)) {
			currentPackage = currentPackage == null ? packageName : currentPackage + PACKAGE_SPLIT + packageName;
			addTranslatedAndLiteral(parts, currentPackage, packageName);
		}

		addTranslatedAndLiteral(parts, container.getGroupAndId(), container.getId());

		return String.join("\n", parts).toLowerCase();
	}

	/**
	 * @param container
	 * 		Container the value belongs to.
	 * @param value
	 * 		Config value to build searchable text for.
	 *
	 * @return Lower-cased search corpus for the label and raw ID of the value.
	 */
	@Nonnull
	protected static String buildValueText(@Nonnull ConfigContainer container, @Nonnull ConfigValue<?> value) {
		List<String> parts = new ArrayList<>();
		addTranslatedAndLiteral(parts, container.getScopedId(value), value.getId());
		return String.join("\n", parts).toLowerCase();
	}

	private static boolean matchesAll(@Nonnull String text, @Nonnull List<String> tokens) {
		for (String token : tokens)
			if (!text.contains(token))
				return false;
		return true;
	}

	/**
	 * Adds both the translated text (when available) and the raw ID, so that users can search using either
	 * what they see in the UI or the key-style names found in config files.
	 */
	private static void addTranslatedAndLiteral(@Nonnull List<String> parts, @Nonnull String translationKey, @Nonnull String literal) {
		if (Lang.has(translationKey))
			parts.add(Lang.get(translationKey));
		parts.add(literal);
	}
}
