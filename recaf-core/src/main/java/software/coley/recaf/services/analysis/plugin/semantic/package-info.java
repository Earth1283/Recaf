/**
 * Recovers the meaning of obfuscated Minecraft plugins from their use of the server API.
 * <p>
 * Obfuscators can rename a plugin's own classes and members, but not the server API it calls, the
 * {@code @EventHandler} annotations the server reads at runtime, or the strings it passes to the API. This package
 * uses those to:
 * <ul>
 *     <li>Index the plugin's structure with {@link software.coley.recaf.services.analysis.plugin.semantic.PluginIndexer}:
 *     entry points, commands and executors, listeners and handled events, scheduled tasks, and the config paths,
 *     permissions, channels and other keys it uses. The resulting
 *     {@link software.coley.recaf.services.analysis.plugin.semantic.PluginIndex} links each to its location in the
 *     code and can be searched.</li>
 *     <li>Suggest names with {@link software.coley.recaf.services.analysis.plugin.semantic.SemanticNameSuggester}. Each
 *     {@link software.coley.recaf.services.analysis.plugin.semantic.NameSuggestion} says what evidence it is based on
 *     and how confident it is.</li>
 * </ul>
 * {@link software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService} is the entry point, and turns
 * chosen suggestions into mappings.
 * <p>
 * See {@code docs/minecraft-plugins.md} for the user-facing description.
 */
package software.coley.recaf.services.analysis.plugin.semantic;
