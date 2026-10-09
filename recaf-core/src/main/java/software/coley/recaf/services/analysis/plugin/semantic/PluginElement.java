package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Locale;

/**
 * A single piece of plugin structure, such as an event handler or the use of a config path.
 *
 * @param kind
 * 		What the element is.
 * @param key
 * 		What the element is about, such as the event name or the config path. See {@link PluginElementKind} for what
 * 		each kind uses. May be empty when it could not be determined.
 * @param location
 * 		Where in the code the element is.
 * @param related
 * 		The other end of the element, when there is one. For example the class of the event an event handler
 * 		receives, or where a command executor is registered.
 * @param detail
 * 		Extra information for display, such as an event handler's priority, or the API method a config path is
 * 		passed to.
 */
public record PluginElement(@Nonnull PluginElementKind kind,
                            @Nonnull String key,
                            @Nonnull CodeLocation location,
                            @Nullable CodeLocation related,
                            @Nullable String detail) {
	/**
	 * @param query
	 * 		Lowercase search text.
	 *
	 * @return {@code true} when any part of the element contains the query.
	 */
	public boolean matches(@Nonnull String query) {
		if (query.isEmpty())
			return true;
		return searchText().contains(query);
	}

	/**
	 * @return Lowercase text that searches are matched against.
	 */
	@Nonnull
	public String searchText() {
		StringBuilder sb = new StringBuilder();
		sb.append(kind.displayName()).append(' ').append(kind.searchTerms()).append(' ')
				.append(key).append(' ')
				.append(location).append(' ').append(location.display());
		if (related != null)
			sb.append(' ').append(related).append(' ').append(related.display());
		if (detail != null)
			sb.append(' ').append(detail);
		return sb.toString().toLowerCase(Locale.ROOT);
	}
}
