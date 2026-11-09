/**
 * Security triage of Minecraft server plugins: a static read of a plugin's bytecode for behavior worth checking before
 * running it, such as running programs, loading code from outside the jar, or granting operator from chat.
 * <p>
 * {@link software.coley.recaf.services.analysis.plugin.security.PluginSecurityScanner} does the reading, and
 * {@link software.coley.recaf.services.analysis.plugin.security.PluginSecurityService} caches the result for the
 * current workspace.
 */
package software.coley.recaf.services.analysis.plugin.security;
