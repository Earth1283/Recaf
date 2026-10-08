package software.coley.recaf.services.analysis.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import software.coley.recaf.analytics.logging.Logging;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parser for {@code plugin.yml}, {@code paper-plugin.yml}, and {@code bungee.yml} manifests.
 * <p>
 * Manifests come from arbitrary jars, so they are untrusted input. The YAML is only ever read as a node tree which
 * never constructs objects from tags, and parsing is bounded in size, nesting depth, and alias expansion.
 * Scalars are read as the raw text written in the file. This is intentional: an unquoted {@code api-version: 1.20}
 * would otherwise be read as the float {@code 1.2}.
 */
public final class MinecraftPluginManifestParser {
	private static final Logger logger = Logging.get(MinecraftPluginManifestParser.class);
	private static final int MAX_MANIFEST_CHARS = 1 << 20;
	private static final int MAX_NESTING_DEPTH = 32;
	private static final int MAX_ALIASES = 16;

	private MinecraftPluginManifestParser() {}

	/**
	 * @param fileName
	 * 		Name of the manifest file. Used to determine the platform.
	 * @param content
	 * 		Raw content of the manifest.
	 *
	 * @return Parsed manifest, or empty if the file name is not a known manifest name or the content is not valid.
	 */
	@Nonnull
	public static Optional<MinecraftPluginManifest> parse(@Nonnull String fileName, @Nonnull byte[] content) {
		return parse(fileName, new String(content, StandardCharsets.UTF_8));
	}

	/**
	 * @param fileName
	 * 		Name of the manifest file. Used to determine the platform.
	 * @param text
	 * 		Text of the manifest.
	 *
	 * @return Parsed manifest, or empty if the file name is not a known manifest name or the content is not valid.
	 */
	@Nonnull
	public static Optional<MinecraftPluginManifest> parse(@Nonnull String fileName, @Nonnull String text) {
		MinecraftPluginPlatform platform = MinecraftPluginPlatform.fromManifestFileName(fileName);
		if (platform == null)
			return Optional.empty();

		// Strip the BOM if present, the platforms themselves read the file as UTF-8 and tolerate it.
		if (!text.isEmpty() && text.charAt(0) == '﻿')
			text = text.substring(1);

		Node root;
		try {
			LoaderOptions options = new LoaderOptions();
			options.setCodePointLimit(MAX_MANIFEST_CHARS);
			options.setNestingDepthLimit(MAX_NESTING_DEPTH);
			options.setMaxAliasesForCollections(MAX_ALIASES);
			options.setAllowRecursiveKeys(false);
			root = new Yaml(options).compose(new StringReader(text));
		} catch (Throwable t) {
			// Malformed YAML, or a limit was exceeded. Either way there is nothing for us to read.
			logger.debug("Could not parse '{}' as a plugin manifest: {}", fileName, t.getMessage());
			return Optional.empty();
		}
		if (!(root instanceof MappingNode map))
			return Optional.empty();

		List<String> dependencies = new ArrayList<>();
		List<String> softDependencies = new ArrayList<>();
		List<String> loadBefore = new ArrayList<>();
		switch (platform) {
			case BUKKIT -> {
				dependencies.addAll(stringList(get(map, "depend")));
				softDependencies.addAll(stringList(get(map, "softdepend")));
				loadBefore.addAll(stringList(get(map, "loadbefore")));
			}
			case BUNGEE -> {
				dependencies.addAll(stringList(get(map, "depends")));
				softDependencies.addAll(stringList(get(map, "softDepends")));
			}
			case PAPER -> {
				// 'dependencies' is a map of section (bootstrap/server) --> plugin name --> options
				if (get(map, "dependencies") instanceof MappingNode sections) {
					for (NodeTuple section : sections.getValue()) {
						if (!(section.getValueNode() instanceof MappingNode plugins))
							continue;
						for (NodeTuple plugin : plugins.getValue()) {
							String pluginName = scalar(plugin.getKeyNode());
							if (pluginName == null)
								continue;
							boolean required = true;
							if (plugin.getValueNode() instanceof MappingNode options)
								required = !"false".equalsIgnoreCase(scalar(get(options, "required")));
							(required ? dependencies : softDependencies).add(pluginName);
						}
					}
				}
			}
		}

		return Optional.of(new MinecraftPluginManifest(platform,
				scalar(get(map, "name")),
				scalar(get(map, "version")),
				className(get(map, "main")),
				scalar(get(map, "api-version")),
				className(get(map, "bootstrapper")),
				className(get(map, "loader")),
				List.copyOf(dependencies),
				List.copyOf(softDependencies),
				List.copyOf(loadBefore),
				keys(get(map, "commands")),
				keys(get(map, "permissions"))));
	}

	@Nullable
	private static Node get(@Nonnull MappingNode map, @Nonnull String key) {
		// Last one wins, same as the platforms' own YAML handling of duplicate keys.
		Node found = null;
		for (NodeTuple tuple : map.getValue())
			if (key.equals(scalar(tuple.getKeyNode())))
				found = tuple.getValueNode();
		return found;
	}

	@Nullable
	private static String scalar(@Nullable Node node) {
		if (node instanceof ScalarNode scalar) {
			String value = scalar.getValue().trim();
			return value.isEmpty() ? null : value;
		}
		return null;
	}

	@Nullable
	private static String className(@Nullable Node node) {
		String value = scalar(node);
		return value == null ? null : value.replace('.', '/');
	}

	@Nonnull
	private static List<String> stringList(@Nullable Node node) {
		List<String> list = new ArrayList<>();
		if (node instanceof SequenceNode sequence) {
			for (Node item : sequence.getValue()) {
				String value = scalar(item);
				if (value != null)
					list.add(value);
			}
		} else {
			// Platforms accept a lone string where a list is expected.
			String value = scalar(node);
			if (value != null)
				list.add(value);
		}
		return list;
	}

	@Nonnull
	private static List<String> keys(@Nullable Node node) {
		List<String> list = new ArrayList<>();
		if (node instanceof MappingNode map)
			for (NodeTuple tuple : map.getValue()) {
				String key = scalar(tuple.getKeyNode());
				if (key != null)
					list.add(key);
			}
		return List.copyOf(list);
	}
}
