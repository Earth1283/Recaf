package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginManifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Searchable index of the structure of a plugin: its entry points, listeners, commands, tasks, and the keys
 * <i>(config paths, permissions, ...)</i> it uses, all linked to where they are in the code.
 * <p>
 * Instances are immutable snapshots. Locations use the class and member names at the time of indexing.
 *
 * @see PluginIndexer Creates indexes.
 */
public final class PluginIndex {
	/** Index with nothing in it. */
	public static final PluginIndex EMPTY = new PluginIndex(List.of(), List.of(), NamingFacts.EMPTY);
	private static final Comparator<PluginElement> ORDER = Comparator.comparing(PluginElement::kind)
			.thenComparing(PluginElement::key, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(PluginElement::location);
	private final List<MinecraftPluginManifest> manifests;
	private final List<PluginElement> elements;
	private final NamingFacts namingFacts;
	private final Map<PluginElementKind, List<PluginElement>> byKind = new EnumMap<>(PluginElementKind.class);
	private final Map<String, List<PluginElement>> byClass = new HashMap<>();

	/**
	 * @param manifests
	 * 		Manifests of the plugin.
	 * @param elements
	 * 		Elements found in the plugin.
	 * @param namingFacts
	 * 		Facts used for naming.
	 */
	public PluginIndex(@Nonnull List<MinecraftPluginManifest> manifests,
	                   @Nonnull List<PluginElement> elements,
	                   @Nonnull NamingFacts namingFacts) {
		List<PluginElement> sorted = new ArrayList<>(elements);
		sorted.sort(ORDER);
		this.manifests = List.copyOf(manifests);
		this.elements = Collections.unmodifiableList(sorted);
		this.namingFacts = namingFacts;
		for (PluginElement element : this.elements) {
			byKind.computeIfAbsent(element.kind(), k -> new ArrayList<>()).add(element);
			byClass.computeIfAbsent(element.location().className(), k -> new ArrayList<>()).add(element);
			CodeLocation related = element.related();
			if (related != null && !related.className().equals(element.location().className()))
				byClass.computeIfAbsent(related.className(), k -> new ArrayList<>()).add(element);
		}
	}

	/**
	 * @return {@code true} when nothing was found.
	 */
	public boolean isEmpty() {
		return elements.isEmpty() && manifests.isEmpty();
	}

	/**
	 * @return Manifests of the plugin. Usually zero or one.
	 */
	@Nonnull
	public List<MinecraftPluginManifest> manifests() {
		return manifests;
	}

	/**
	 * @return The first manifest, or {@code null} when the plugin has none.
	 */
	@Nullable
	public MinecraftPluginManifest manifest() {
		return manifests.isEmpty() ? null : manifests.getFirst();
	}

	/**
	 * @return Plugin name from the manifest, or {@code null} if not known.
	 */
	@Nullable
	public String pluginName() {
		for (MinecraftPluginManifest manifest : manifests)
			if (manifest.name() != null)
				return manifest.name();
		return null;
	}

	/**
	 * @return All elements, sorted by kind then key.
	 */
	@Nonnull
	public List<PluginElement> elements() {
		return elements;
	}

	/**
	 * @return Facts used for naming.
	 */
	@Nonnull
	public NamingFacts namingFacts() {
		return namingFacts;
	}

	/**
	 * @param kind
	 * 		Kind of element.
	 *
	 * @return Elements of the kind, sorted by key.
	 */
	@Nonnull
	public List<PluginElement> ofKind(@Nonnull PluginElementKind kind) {
		return byKind.getOrDefault(kind, List.of());
	}

	/**
	 * @param kind
	 * 		Kind of element.
	 *
	 * @return Distinct keys of elements of the kind, sorted.
	 */
	@Nonnull
	public SortedSet<String> keysOf(@Nonnull PluginElementKind kind) {
		SortedSet<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (PluginElement element : ofKind(kind))
			keys.add(element.key());
		return keys;
	}

	/**
	 * @param kind
	 * 		Kind of element.
	 * @param key
	 * 		Element key.
	 *
	 * @return Elements of the kind with the given key.
	 */
	@Nonnull
	public List<PluginElement> withKey(@Nonnull PluginElementKind kind, @Nonnull String key) {
		return ofKind(kind).stream().filter(e -> e.key().equals(key)).toList();
	}

	/**
	 * @param className
	 * 		Internal class name.
	 *
	 * @return Elements located in, or related to, the class or its members.
	 */
	@Nonnull
	public List<PluginElement> inClass(@Nonnull String className) {
		return byClass.getOrDefault(className, List.of());
	}

	/**
	 * @param location
	 * 		Some location.
	 *
	 * @return Elements located exactly at the location.
	 */
	@Nonnull
	public List<PluginElement> at(@Nonnull CodeLocation location) {
		return inClass(location.className()).stream()
				.filter(e -> e.location().equals(location))
				.toList();
	}

	/**
	 * @param location
	 * 		Some location.
	 *
	 * @return Elements whose related location is exactly the location.
	 */
	@Nonnull
	public List<PluginElement> relatedTo(@Nonnull CodeLocation location) {
		return inClass(location.className()).stream()
				.filter(e -> location.equals(e.related()))
				.toList();
	}

	/**
	 * @param eventClassName
	 * 		Internal name of an event class.
	 *
	 * @return Event handlers that receive the event.
	 */
	@Nonnull
	public List<PluginElement> handlersOf(@Nonnull String eventClassName) {
		return ofKind(PluginElementKind.EVENT_HANDLER).stream()
				.filter(e -> e.related() != null && e.related().className().equals(eventClassName))
				.toList();
	}

	/**
	 * @param eventClassName
	 * 		Internal name of an event class.
	 *
	 * @return Places the event is fired from.
	 */
	@Nonnull
	public List<PluginElement> callsOf(@Nonnull String eventClassName) {
		return ofKind(PluginElementKind.EVENT_CALL).stream()
				.filter(e -> e.related() != null && e.related().className().equals(eventClassName))
				.toList();
	}

	/**
	 * @param query
	 * 		Search text. Case is ignored. Multiple words must all match.
	 *
	 * @return Elements matching the query.
	 */
	@Nonnull
	public List<PluginElement> search(@Nonnull String query) {
		String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		return elements.stream().filter(e -> {
			String text = e.searchText();
			for (String word : words)
				if (!text.contains(word))
					return false;
			return true;
		}).toList();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof PluginIndex other)) return false;
		return manifests.equals(other.manifests) && elements.equals(other.elements) &&
				namingFacts.equals(other.namingFacts);
	}

	@Override
	public int hashCode() {
		return Objects.hash(manifests, elements, namingFacts);
	}

	@Override
	public String toString() {
		return "PluginIndex[" + elements.size() + " elements]";
	}
}
