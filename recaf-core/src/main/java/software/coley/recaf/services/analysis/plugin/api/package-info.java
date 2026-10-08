/**
 * Attaching the server API a Minecraft plugin was written against to a workspace.
 * <p>
 * A plugin jar does not contain its server API. Attaching it as a supporting resource lets the decompiler resolve
 * those types, and lets automatic renaming recognize methods the server calls by name <i>(like {@code onEnable})</i>
 * so it does not rename them.
 * <p>
 * {@link software.coley.recaf.services.analysis.plugin.api.MinecraftPluginApiService} is the entry point. It
 * decides what to get, downloads it, and adds it to the workspace. It only does so when asked.
 * <ul>
 *     <li>{@link software.coley.recaf.services.analysis.plugin.api.PaperApiVersionSelector} chooses the version from
 *     the plugin's {@code api-version}.</li>
 *     <li>{@link software.coley.recaf.services.analysis.plugin.api.MavenRepositoryClient} reads from Maven
 *     repositories, verifies checksums, and caches files.</li>
 *     <li>{@link software.coley.recaf.services.analysis.plugin.api.MavenDependencyResolver} finds the libraries the
 *     API depends on. It is a small best-effort resolver, not a replacement for Maven.</li>
 *     <li>{@link software.coley.recaf.services.analysis.plugin.api.PluginApiFetcher} is the only code that touches
 *     the network, so that it can be replaced in tests.</li>
 * </ul>
 * Everything here that is derived from downloaded files is untrusted. Maven coordinates are validated before they are
 * used to build URLs or file paths, and XML is parsed with DTDs and entities disabled.
 * <p>
 * See {@code docs/minecraft-plugins.md} for the user-facing description.
 */
package software.coley.recaf.services.analysis.plugin.api;
