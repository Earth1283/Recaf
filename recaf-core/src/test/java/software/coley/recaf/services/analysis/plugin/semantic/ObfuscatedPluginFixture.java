package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.compile.CompilerResult;
import software.coley.recaf.services.compile.JavacArgumentsBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicFileBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A small plugin written the way an obfuscator leaves it: every class, field, method and variable of the plugin has a
 * meaningless name, while everything belonging to the server API keeps its name. A minimal stand-in for the Bukkit API
 * is compiled alongside it.
 */
final class ObfuscatedPluginFixture {
	static final String PLUGIN_YML = """
			name: Homes
			version: 1.0
			main: a.a
			api-version: '1.21'
			commands:
			  home: {}
			  sethome: {}
			permissions:
			  homes.admin: {}
			""";
	private static final Map<String, String> API = new LinkedHashMap<>();
	private static final Map<String, String> PLUGIN = new LinkedHashMap<>();

	static {
		api("org/bukkit/permissions/Permissible", "public interface Permissible { boolean hasPermission(String name); }");
		api("org/bukkit/command/CommandSender", "public interface CommandSender extends org.bukkit.permissions.Permissible { void sendMessage(String message); }");
		api("org/bukkit/command/CommandExecutor", "public interface CommandExecutor { boolean onCommand(CommandSender sender, Command command, String label, String[] args); }");
		api("org/bukkit/command/TabCompleter", "public interface TabCompleter { java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args); }");
		api("org/bukkit/command/TabExecutor", "public interface TabExecutor extends CommandExecutor, TabCompleter {}");
		api("org/bukkit/command/Command", "public abstract class Command { protected Command(String name) {} public abstract boolean execute(CommandSender sender, String label, String[] args); }");
		api("org/bukkit/command/PluginCommand", """
				public final class PluginCommand extends Command {
					PluginCommand() { super("x"); }
					public boolean execute(CommandSender sender, String label, String[] args) { return false; }
					public void setExecutor(CommandExecutor executor) {}
					public void setTabCompleter(TabCompleter completer) {}
				}""");
		api("org/bukkit/plugin/Plugin", "public interface Plugin extends org.bukkit.command.TabExecutor {}");
		api("org/bukkit/configuration/ConfigurationSection", """
				public interface ConfigurationSection {
					String getString(String path);
					int getInt(String path);
					ConfigurationSection getConfigurationSection(String path);
				}""");
		api("org/bukkit/configuration/MemorySection", """
				public class MemorySection implements ConfigurationSection {
					public String getString(String path) { return null; }
					public int getInt(String path) { return 0; }
					public ConfigurationSection getConfigurationSection(String path) { return null; }
				}""");
		api("org/bukkit/configuration/file/FileConfiguration", "public abstract class FileConfiguration extends org.bukkit.configuration.MemorySection {}");
		api("org/bukkit/event/Listener", "public interface Listener {}");
		api("org/bukkit/event/EventPriority", "public enum EventPriority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }");
		api("org/bukkit/event/EventHandler", """
				@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
				public @interface EventHandler {
					EventPriority priority() default EventPriority.NORMAL;
					boolean ignoreCancelled() default false;
				}""");
		api("org/bukkit/event/HandlerList", "public class HandlerList {}");
		api("org/bukkit/event/Event", "public abstract class Event { public abstract HandlerList getHandlers(); }");
		api("org/bukkit/entity/Player", "public interface Player extends org.bukkit.command.CommandSender {}");
		api("org/bukkit/event/player/PlayerEvent", "public abstract class PlayerEvent extends org.bukkit.event.Event { public org.bukkit.entity.Player getPlayer() { return null; } }");
		api("org/bukkit/event/player/PlayerJoinEvent", "public class PlayerJoinEvent extends PlayerEvent { public org.bukkit.event.HandlerList getHandlers() { return null; } }");
		api("org/bukkit/event/player/PlayerQuitEvent", "public class PlayerQuitEvent extends PlayerEvent { public org.bukkit.event.HandlerList getHandlers() { return null; } }");
		api("org/bukkit/plugin/PluginManager", """
				public interface PluginManager {
					void registerEvents(org.bukkit.event.Listener listener, Plugin plugin);
					void callEvent(org.bukkit.event.Event event);
				}""");
		api("org/bukkit/scheduler/BukkitTask", "public interface BukkitTask {}");
		api("org/bukkit/scheduler/BukkitScheduler", "public interface BukkitScheduler { BukkitTask runTaskTimer(org.bukkit.plugin.Plugin plugin, Runnable task, long delay, long period); }");
		api("org/bukkit/Server", "public interface Server { org.bukkit.plugin.PluginManager getPluginManager(); org.bukkit.scheduler.BukkitScheduler getScheduler(); }");
		api("org/bukkit/NamespacedKey", "public final class NamespacedKey { public NamespacedKey(org.bukkit.plugin.Plugin plugin, String key) {} }");
		api("org/bukkit/plugin/java/JavaPlugin", """
				import org.bukkit.command.*;
				public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
					public void onEnable() {}
					public void onDisable() {}
					public PluginCommand getCommand(String name) { return null; }
					public org.bukkit.configuration.file.FileConfiguration getConfig() { return null; }
					public void saveDefaultConfig() {}
					public org.bukkit.Server getServer() { return null; }
					public boolean onCommand(CommandSender sender, Command command, String label, String[] args) { return false; }
					public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return null; }
				}""");

		// The main class. Field 'e' is a permission constant, 'h' is a singleton getter, 'i' a getter, 'j' reads config.
		plugin("a/a", """
				public class a extends org.bukkit.plugin.java.JavaPlugin {
					private static a a;
					private b b;
					private int c;
					private org.bukkit.NamespacedKey d;
					public static String e = "homes.admin";
					public void onEnable() {
						a = this;
						saveDefaultConfig();
						this.c = getConfig().getInt("settings.max-homes");
						this.d = new org.bukkit.NamespacedKey(this, "home_count");
						f();
						g();
						getServer().getScheduler().runTaskTimer(this, new e(this), 20L, 20L);
					}
					private void f() {
						this.b = new b(this);
						getServer().getPluginManager().registerEvents(this.b, this);
					}
					private void g() {
						getCommand("home").setExecutor(new c(this));
						org.bukkit.command.PluginCommand m = getCommand("sethome");
						d n = new d();
						m.setExecutor(n);
						m.setTabCompleter(n);
					}
					public static a h() { return a; }
					public int i() { return this.c; }
					public String j() { return getConfig().getString("messages.prefix"); }
				}""");
		plugin("a/b", """
				import org.bukkit.event.*;
				import org.bukkit.event.player.*;
				public class b implements Listener {
					private final a a;
					public b(a a) { this.a = a; }
					@EventHandler(priority = EventPriority.HIGH)
					public void a(PlayerJoinEvent m) {
						m.getPlayer().sendMessage("Welcome");
						this.a.getServer().getPluginManager().callEvent(new f(m.getPlayer()));
					}
					@EventHandler
					public void b(PlayerQuitEvent m) {}
				}""");
		plugin("a/c", """
				import org.bukkit.command.*;
				public class c implements CommandExecutor {
					private final a a;
					c(a a) { this.a = a; }
					public boolean onCommand(CommandSender m, Command n, String o, String[] p) {
						if (!m.hasPermission(a.e))
							return false;
						m.sendMessage("Teleporting");
						return true;
					}
				}""");
		plugin("a/d", """
				import org.bukkit.command.*;
				public class d implements TabExecutor {
					public boolean onCommand(CommandSender m, Command n, String o, String[] p) {
						return m.hasPermission("homes.sethome");
					}
					public java.util.List<String> onTabComplete(CommandSender m, Command n, String o, String[] p) { return null; }
				}""");
		plugin("a/e", """
				public class e implements Runnable {
					private final a a;
					e(a a) { this.a = a; }
					public void run() {}
				}""");
		plugin("a/f", """
				public class f extends org.bukkit.event.player.PlayerEvent {
					private static final org.bukkit.event.HandlerList a = new org.bukkit.event.HandlerList();
					private final org.bukkit.entity.Player b;
					f(org.bukkit.entity.Player b) { this.b = b; }
					public org.bukkit.event.HandlerList getHandlers() { return a; }
					public static org.bukkit.event.HandlerList getHandlerList() { return a; }
				}""");
	}

	private ObfuscatedPluginFixture() {}

	/**
	 * @param javac
	 * 		Compiler to use.
	 * @param withApi
	 * 		{@code true} to add the API as a library, as if it had been attached.
	 *
	 * @return Workspace with the plugin as the primary resource.
	 */
	@Nonnull
	static Workspace create(@Nonnull JavacCompiler javac, boolean withApi) {
		Map<String, String> sources = new LinkedHashMap<>(API);
		sources.putAll(PLUGIN);
		CompilerResult result = javac.compile(new JavacArgumentsBuilder()
				.withClassSources(sources)
				.withVersionTarget(17)
				.build(), null, null);
		if (!result.wasSuccess())
			fail("Fixture did not compile:\n" + result.getDiagnostics().stream()
					.map(CompilerDiagnostic::toString)
					.collect(Collectors.joining("\n")));

		BasicJvmClassBundle pluginClasses = new BasicJvmClassBundle();
		BasicJvmClassBundle apiClasses = new BasicJvmClassBundle();
		result.getCompilations().forEach((name, bytecode) -> {
			var info = new JvmClassInfoBuilder(bytecode).build();
			(name.startsWith("org/") ? apiClasses : pluginClasses).initialPut(info);
		});
		FileInfo yml = TestClassUtils.createFile("plugin.yml", PLUGIN_YML.getBytes(StandardCharsets.UTF_8));
		BasicFileBundle files = new BasicFileBundle();
		files.initialPut(yml);

		WorkspaceResource primary = new WorkspaceResourceBuilder()
				.withJvmClassBundle(pluginClasses)
				.withFileBundle(files)
				.build();
		if (!withApi)
			return new BasicWorkspace(primary);
		WorkspaceResource api = new WorkspaceResourceBuilder().withJvmClassBundle(apiClasses).build();
		return new BasicWorkspace(primary, List.of(api));
	}

	private static void api(@Nonnull String name, @Nonnull String body) {
		String pkg = name.substring(0, name.lastIndexOf('/')).replace('/', '.');
		API.put(name, "package " + pkg + ";\n" + body);
	}

	private static void plugin(@Nonnull String name, @Nonnull String body) {
		String pkg = name.substring(0, name.lastIndexOf('/')).replace('/', '.');
		PLUGIN.put(name, "package " + pkg + ";\n" + body);
	}
}
