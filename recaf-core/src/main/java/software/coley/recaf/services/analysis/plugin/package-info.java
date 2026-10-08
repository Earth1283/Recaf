/**
 * Support for Minecraft server plugins <i>(Bukkit / Spigot / Paper, and BungeeCord)</i>.
 * <p>
 * A plugin jar describes itself with a YAML manifest at its root. This package reads those manifests and uses them:
 * <ul>
 *     <li>{@link software.coley.recaf.services.analysis.plugin.MinecraftPluginManifestParser} reads a manifest into a
 *     {@link software.coley.recaf.services.analysis.plugin.MinecraftPluginManifest}. Manifests are untrusted input, so
 *     parsing never constructs objects and is bounded in size and depth.</li>
 *     <li>{@link software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService} finds manifests in
 *     workspace resources. Entry point discovery uses it as an extra signal for what the main class is.</li>
 *     <li>{@link software.coley.recaf.services.analysis.plugin.MinecraftPluginMappingListener} keeps the manifest
 *     pointing at the right classes when classes are renamed, using
 *     {@link software.coley.recaf.services.analysis.plugin.MinecraftPluginManifestRewriter} to edit the text in
 *     place.</li>
 * </ul>
 * The {@code api} subpackage downloads the server API a plugin was written against.
 * <p>
 * See {@code docs/minecraft-plugins.md} for the user-facing description.
 */
package software.coley.recaf.services.analysis.plugin;
