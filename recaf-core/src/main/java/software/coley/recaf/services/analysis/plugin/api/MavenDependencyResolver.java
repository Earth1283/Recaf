package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.w3c.dom.Element;
import software.coley.recaf.analytics.logging.Logging;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately small, best-effort Maven dependency resolver.
 * <p>
 * It exists to find the libraries an API jar's types refer to <i>(for example the Adventure and Guava types in Paper's
 * API)</i> so the decompiler can resolve them. It is not a replacement for Maven or Gradle, and handles only what
 * that purpose needs:
 * <ul>
 *     <li>Parent POMs, property interpolation, {@code dependencyManagement}, and BOM imports</li>
 *     <li>{@code compile} scope, non-optional, plain {@code jar} dependencies. Test, provided, and runtime
 *     dependencies are not needed to understand an API's types</li>
 *     <li>Exclusions, and the nearest declaration of an artifact winning</li>
 * </ul>
 * Anything it does not understand <i>(version ranges, unresolved properties, missing POMs)</i> is skipped and logged
 * rather than failing the whole operation.
 */
public class MavenDependencyResolver {
	private static final Logger logger = Logging.get(MavenDependencyResolver.class);
	private static final long MAX_POM_BYTES = 4L * 1024 * 1024;
	private static final int MAX_POM_DEPTH = 12;
	private static final int MAX_INTERPOLATION_PASSES = 10;
	private final Map<MavenCoordinate, RawPom> rawPoms = new HashMap<>();
	private final Map<MavenCoordinate, EffectivePom> effectivePoms = new HashMap<>();
	private final MavenRepositoryClient client;
	private final int maxArtifacts;

	/**
	 * @param client
	 * 		Client to read POMs with.
	 * @param maxArtifacts
	 * 		Maximum number of dependencies to resolve.
	 */
	public MavenDependencyResolver(@Nonnull MavenRepositoryClient client, int maxArtifacts) {
		this.client = client;
		this.maxArtifacts = maxArtifacts;
	}

	/**
	 * @param root
	 * 		Artifact to resolve the dependencies of.
	 *
	 * @return The dependencies of the artifact, direct ones first <i>(The root itself is not included)</i>.
	 *
	 * @throws IOException
	 * 		When the POM of the root artifact could not be read.
	 */
	@Nonnull
	public List<MavenCoordinate> resolve(@Nonnull MavenCoordinate root) throws IOException {
		EffectivePom rootPom = effective(root, 0);
		List<MavenCoordinate> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		seen.add(root.versionlessKey());

		// The exclusions held are those inherited from ancestors. A dependency's own exclusions only apply
		// to the things it brings in, never to itself.
		record Pending(Dependency dependency, Set<String> exclusions) {}
		Deque<Pending> queue = new ArrayDeque<>();
		for (Dependency dependency : rootPom.dependencies)
			queue.add(new Pending(dependency, Set.of()));

		// Breadth first, so the declaration nearest the root wins when an artifact is reached more than one way.
		while (!queue.isEmpty() && result.size() < maxArtifacts) {
			Pending pending = queue.poll();
			Dependency dependency = pending.dependency;
			if (!isWanted(dependency) || isExcluded(pending.exclusions, dependency))
				continue;

			String key = dependency.groupId + ':' + dependency.artifactId;
			if (!seen.add(key))
				continue;

			// The root's own management decides versions for everything, as in Maven.
			String version = rootPom.managedVersion(key);
			if (version == null)
				version = dependency.version;
			version = cleanVersion(version);
			if (version == null) {
				logger.debug("Skipping {} since it has no usable version", key);
				continue;
			}

			MavenCoordinate coordinate;
			try {
				coordinate = new MavenCoordinate(dependency.groupId, dependency.artifactId, version);
			} catch (IllegalArgumentException ex) {
				logger.warn("Skipping dependency with an invalid coordinate: {}", ex.getMessage());
				continue;
			}
			result.add(coordinate);

			// Walk into the dependency's own dependencies.
			try {
				Set<String> childExclusions = new HashSet<>(pending.exclusions);
				childExclusions.addAll(dependency.exclusions);
				for (Dependency child : effective(coordinate, 0).dependencies)
					queue.add(new Pending(child, childExclusions));
			} catch (IOException ex) {
				logger.warn("Could not read dependencies of {}: {}", coordinate, ex.getMessage());
			}
		}
		if (!queue.isEmpty() && result.size() >= maxArtifacts)
			logger.warn("Stopped resolving dependencies of {} at the limit of {} artifacts", root, maxArtifacts);
		return result;
	}

	private static boolean isWanted(@Nonnull Dependency dependency) {
		if (dependency.optional)
			return false;
		if (dependency.scope != null && !dependency.scope.equals("compile"))
			return false;
		if (dependency.type != null && !dependency.type.equals("jar"))
			return false;
		return dependency.classifier == null;
	}

	private static boolean isExcluded(@Nonnull Set<String> exclusions, @Nonnull Dependency dependency) {
		return exclusions.contains(dependency.groupId + ':' + dependency.artifactId)
				|| exclusions.contains(dependency.groupId + ":*")
				|| exclusions.contains("*:" + dependency.artifactId)
				|| exclusions.contains("*:*");
	}

	/**
	 * @return Version that can be downloaded, or {@code null} if it is unresolved or a range we cannot pick from.
	 */
	@Nullable
	private static String cleanVersion(@Nullable String version) {
		if (version == null || version.isBlank() || version.contains("${"))
			return null;
		version = version.trim();
		// "[1.2.3]" pins one version. Real ranges would need the metadata, which we do not support.
		if (version.startsWith("[") && version.endsWith("]") && !version.contains(","))
			return version.substring(1, version.length() - 1).trim();
		if (version.startsWith("[") || version.startsWith("(") || version.contains(","))
			return null;
		return version;
	}

	@Nonnull
	private EffectivePom effective(@Nonnull MavenCoordinate coordinate, int depth) throws IOException {
		EffectivePom cached = effectivePoms.get(coordinate);
		if (cached != null)
			return cached;
		if (depth > MAX_POM_DEPTH)
			throw new IOException("POM parent/import chain is too deep at " + coordinate);

		RawPom raw = rawPom(coordinate);
		EffectivePom parent = EffectivePom.EMPTY;
		if (raw.parent != null)
			parent = effective(raw.parent, depth + 1);

		// Properties: inherited, then built-ins for this project, then the ones it declares.
		Map<String, String> properties = new HashMap<>(parent.properties);
		String groupId = raw.groupId != null ? raw.groupId : coordinate.groupId();
		String version = raw.version != null ? raw.version : coordinate.version();
		for (String prefix : List.of("project.", "pom.")) {
			properties.put(prefix + "groupId", groupId);
			properties.put(prefix + "artifactId", raw.artifactId);
			properties.put(prefix + "version", version);
			if (raw.parent != null)
				properties.put(prefix + "parent.version", raw.parent.version());
		}
		properties.putAll(raw.properties);

		// Management: inherited, overridden by our own, with imported BOMs filling in anything still undecided.
		Map<String, Dependency> managed = new LinkedHashMap<>(parent.managed);
		List<Dependency> imports = new ArrayList<>();
		for (RawDependency managedDependency : raw.managed) {
			Dependency dependency = interpolate(managedDependency, properties);
			if ("import".equals(dependency.scope) && "pom".equals(dependency.type)) {
				imports.add(dependency);
				continue;
			}
			managed.put(dependency.groupId + ':' + dependency.artifactId, dependency);
		}
		for (Dependency bom : imports) {
			String bomVersion = cleanVersion(bom.version);
			if (bomVersion == null)
				continue;
			try {
				EffectivePom imported = effective(new MavenCoordinate(bom.groupId, bom.artifactId, bomVersion), depth + 1);
				imported.managed.forEach(managed::putIfAbsent);
			} catch (IOException | IllegalArgumentException ex) {
				logger.warn("Could not read imported BOM {}:{}:{}: {}", bom.groupId, bom.artifactId, bomVersion, ex.getMessage());
			}
		}

		// Dependencies: inherited, then our own, with management filling in whatever they leave out.
		List<Dependency> dependencies = new ArrayList<>(parent.dependencies);
		for (RawDependency rawDependency : raw.dependencies)
			dependencies.add(interpolate(rawDependency, properties));
		List<Dependency> resolved = new ArrayList<>(dependencies.size());
		for (Dependency dependency : dependencies) {
			Dependency manage = managed.get(dependency.groupId + ':' + dependency.artifactId);
			resolved.add(manage == null ? dependency : dependency.withManagement(manage));
		}

		EffectivePom effective = new EffectivePom(properties, managed, resolved);
		effectivePoms.put(coordinate, effective);
		return effective;
	}

	@Nonnull
	private RawPom rawPom(@Nonnull MavenCoordinate coordinate) throws IOException {
		RawPom cached = rawPoms.get(coordinate);
		if (cached != null)
			return cached;
		byte[] content = client.fetchFile(coordinate, "pom", MAX_POM_BYTES, false);
		if (content == null)
			throw new IOException("POM not found for " + coordinate);
		RawPom raw = RawPom.parse(content);
		rawPoms.put(coordinate, raw);
		return raw;
	}

	@Nonnull
	private static Dependency interpolate(@Nonnull RawDependency raw, @Nonnull Map<String, String> properties) {
		return new Dependency(
				interpolate(raw.groupId, properties),
				interpolate(raw.artifactId, properties),
				interpolate(raw.version, properties),
				interpolate(raw.scope, properties),
				interpolate(raw.type, properties),
				interpolate(raw.classifier, properties),
				raw.optional,
				raw.exclusions);
	}

	@Nullable
	private static String interpolate(@Nullable String value, @Nonnull Map<String, String> properties) {
		if (value == null)
			return null;
		for (int pass = 0; pass < MAX_INTERPOLATION_PASSES && value.contains("${"); pass++) {
			StringBuilder sb = new StringBuilder();
			int i = 0;
			boolean changed = false;
			while (i < value.length()) {
				int start = value.indexOf("${", i);
				int end = start < 0 ? -1 : value.indexOf('}', start);
				if (start < 0 || end < 0) {
					sb.append(value, i, value.length());
					break;
				}
				sb.append(value, i, start);
				String replacement = properties.get(value.substring(start + 2, end));
				if (replacement != null) {
					sb.append(replacement);
					changed = true;
				} else {
					sb.append(value, start, end + 1);
				}
				i = end + 1;
			}
			value = sb.toString();
			if (!changed)
				break;
		}
		return value;
	}

	/** A dependency as written in a POM, before properties are applied. */
	private record RawDependency(String groupId, String artifactId, String version, String scope, String type,
	                             String classifier, boolean optional, List<String> exclusions) {
		@Nullable
		static RawDependency parse(@Nonnull Element element) {
			String groupId = MavenXml.text(element, "groupId");
			String artifactId = MavenXml.text(element, "artifactId");
			if (groupId == null || artifactId == null)
				return null;
			List<String> exclusions = new ArrayList<>();
			Element exclusionsElement = MavenXml.child(element, "exclusions");
			if (exclusionsElement != null)
				for (Element exclusion : MavenXml.children(exclusionsElement, "exclusion")) {
					String exclusionGroup = MavenXml.text(exclusion, "groupId");
					String exclusionArtifact = MavenXml.text(exclusion, "artifactId");
					exclusions.add((exclusionGroup == null ? "*" : exclusionGroup) + ':' +
							(exclusionArtifact == null ? "*" : exclusionArtifact));
				}
			return new RawDependency(groupId, artifactId,
					MavenXml.text(element, "version"),
					MavenXml.text(element, "scope"),
					MavenXml.text(element, "type"),
					MavenXml.text(element, "classifier"),
					"true".equalsIgnoreCase(MavenXml.text(element, "optional")),
					List.copyOf(exclusions));
		}
	}

	/** A dependency with its properties applied. */
	private record Dependency(String groupId, String artifactId, String version, String scope, String type,
	                          String classifier, boolean optional, List<String> exclusions) {
		/** Fills in whatever this dependency leaves out from its managed counterpart. */
		@Nonnull
		Dependency withManagement(@Nonnull Dependency managed) {
			List<String> mergedExclusions = new ArrayList<>(exclusions);
			managed.exclusions.stream().filter(e -> !mergedExclusions.contains(e)).forEach(mergedExclusions::add);
			return new Dependency(groupId, artifactId,
					version != null ? version : managed.version,
					scope != null ? scope : managed.scope,
					type, classifier, optional, mergedExclusions);
		}
	}

	/** A POM as written, with nothing inherited. */
	private record RawPom(MavenCoordinate parent, String groupId, String artifactId, String version,
	                      Map<String, String> properties, List<RawDependency> managed, List<RawDependency> dependencies) {
		@Nonnull
		static RawPom parse(@Nonnull byte[] content) throws IOException {
			Element project = MavenXml.parse(content);
			MavenCoordinate parent = null;
			Element parentElement = MavenXml.child(project, "parent");
			if (parentElement != null) {
				String parentGroup = MavenXml.text(parentElement, "groupId");
				String parentArtifact = MavenXml.text(parentElement, "artifactId");
				String parentVersion = MavenXml.text(parentElement, "version");
				if (parentGroup != null && parentArtifact != null && parentVersion != null) {
					try {
						parent = new MavenCoordinate(parentGroup, parentArtifact, parentVersion);
					} catch (IllegalArgumentException ex) {
						throw new IOException("POM has an invalid parent: " + ex.getMessage(), ex);
					}
				}
			}

			Map<String, String> properties = new HashMap<>();
			Element propertiesElement = MavenXml.child(project, "properties");
			if (propertiesElement != null)
				for (org.w3c.dom.Node node = propertiesElement.getFirstChild(); node != null; node = node.getNextSibling())
					if (node instanceof Element property)
						properties.put(property.getTagName(), property.getTextContent().trim());

			List<RawDependency> managed = new ArrayList<>();
			Element management = MavenXml.child(project, "dependencyManagement");
			Element managedList = management == null ? null : MavenXml.child(management, "dependencies");
			if (managedList != null)
				for (Element element : MavenXml.children(managedList, "dependency")) {
					RawDependency dependency = RawDependency.parse(element);
					if (dependency != null)
						managed.add(dependency);
				}

			List<RawDependency> dependencies = new ArrayList<>();
			Element dependenciesList = MavenXml.child(project, "dependencies");
			if (dependenciesList != null)
				for (Element element : MavenXml.children(dependenciesList, "dependency")) {
					RawDependency dependency = RawDependency.parse(element);
					if (dependency != null)
						dependencies.add(dependency);
				}

			String artifactId = MavenXml.text(project, "artifactId");
			return new RawPom(parent, MavenXml.text(project, "groupId"), artifactId == null ? "" : artifactId,
					MavenXml.text(project, "version"), properties, managed, dependencies);
		}
	}

	/** A POM with its parents applied. */
	private record EffectivePom(Map<String, String> properties, Map<String, Dependency> managed,
	                            List<Dependency> dependencies) {
		static final EffectivePom EMPTY = new EffectivePom(Map.of(), Map.of(), List.of());

		@Nullable
		String managedVersion(@Nonnull String key) {
			Dependency dependency = managed.get(key);
			return dependency == null ? null : dependency.version;
		}
	}
}
