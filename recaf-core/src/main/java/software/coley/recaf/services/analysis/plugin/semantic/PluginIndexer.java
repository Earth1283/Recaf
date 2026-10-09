package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginManifest;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.Accessor;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.AccessorKind;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.Behavior;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.ConstantUse;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.FlowTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.ValueFlow;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a {@link PluginIndex} by reading the bytecode of a plugin.
 * <p>
 * Obfuscators rename the plugin's own classes and members, but cannot rename anything that belongs to the server
 * API: the server calls {@code onEnable} by name, reads {@code @EventHandler} annotations at runtime, and the API's
 * own classes and methods are not in the plugin jar. Those API references are what this indexer anchors on. String
 * arguments to API methods <i>(command names, config paths, permission nodes, ...)</i> are recovered by following
 * where each argument value comes from, so they are found even when they are stored in a local variable or a
 * constant field first.
 * <p>
 * The server API does not need to be in the workspace, but when it is, class hierarchies resolve further.
 */
public final class PluginIndexer implements Opcodes {
	private static final Logger logger = Logging.get(PluginIndexer.class);
	static final String JAVA_PLUGIN = "org/bukkit/plugin/java/JavaPlugin";
	static final String BUNGEE_PLUGIN = "net/md_5/bungee/api/plugin/Plugin";
	static final String BUKKIT_LISTENER = "org/bukkit/event/Listener";
	static final String BUNGEE_LISTENER = "net/md_5/bungee/api/plugin/Listener";
	static final String BUKKIT_EVENT = "org/bukkit/event/Event";
	static final String BUNGEE_EVENT = "net/md_5/bungee/api/plugin/Event";
	static final String BUKKIT_COMMAND = "org/bukkit/command/Command";
	static final String BUNGEE_COMMAND = "net/md_5/bungee/api/plugin/Command";
	static final String BUKKIT_RUNNABLE = "org/bukkit/scheduler/BukkitRunnable";
	static final String SERIALIZABLE = "org/bukkit/configuration/serialization/ConfigurationSerializable";
	static final String SERIALIZABLE_AS = "Lorg/bukkit/configuration/serialization/SerializableAs;";
	static final String INVENTORY_HOLDER = "org/bukkit/inventory/InventoryHolder";
	static final String PAPI_EXPANSION = "me/clip/placeholderapi/expansion/PlaceholderExpansion";
	static final String MESSAGE_LISTENER = "org/bukkit/plugin/messaging/PluginMessageListener";
	static final String HANDLER_LIST_DESC = "()Lorg/bukkit/event/HandlerList;";
	static final String ON_COMMAND_DESC = "(Lorg/bukkit/command/CommandSender;Lorg/bukkit/command/Command;Ljava/lang/String;[Ljava/lang/String;)Z";
	static final String ON_TAB_COMPLETE_DESC = "(Lorg/bukkit/command/CommandSender;Lorg/bukkit/command/Command;Ljava/lang/String;[Ljava/lang/String;)Ljava/util/List;";
	static final String BUKKIT_COMMAND_EXECUTE_DESC = "(Lorg/bukkit/command/CommandSender;Ljava/lang/String;[Ljava/lang/String;)Z";
	static final String BUNGEE_COMMAND_EXECUTE_DESC = "(Lnet/md_5/bungee/api/CommandSender;[Ljava/lang/String;)V";
	private static final Set<String> HANDLER_ANNOTATIONS = Set.of(
			"Lorg/bukkit/event/EventHandler;",
			"Lnet/md_5/bungee/event/EventHandler;",
			"Lcom/velocitypowered/api/event/Subscribe;");
	private static final Set<String> SCHEDULER_OWNERS = Set.of(
			"org/bukkit/scheduler/BukkitScheduler",
			"net/md_5/bungee/api/scheduler/TaskScheduler");
	private static final String FOLIA_SCHEDULER_PACKAGE = "io/papermc/paper/threadedregions/scheduler/";
	private static final Set<String> SCHEDULER_NAMES = Set.of(
			"runTask", "runTaskLater", "runTaskTimer", "runTaskAsynchronously", "runTaskLaterAsynchronously",
			"runTaskTimerAsynchronously", "scheduleSyncDelayedTask", "scheduleSyncRepeatingTask",
			"scheduleAsyncDelayedTask", "scheduleAsyncRepeatingTask", "callSyncMethod",
			"run", "runDelayed", "runAtFixedRate", "runNow", "execute", "runAsync", "schedule");
	private static final Set<String> TASK_TYPES = Set.of(
			"java/lang/Runnable", "java/util/function/Consumer", "java/util/concurrent/Callable", BUKKIT_RUNNABLE);
	private static final Set<String> CONFIG_METHODS = Set.of(
			"get", "getString", "getInt", "getBoolean", "getDouble", "getLong", "getFloat", "getShort", "getByte",
			"getChar", "getList", "getStringList", "getIntegerList", "getIntList", "getDoubleList", "getFloatList",
			"getLongList", "getByteList", "getCharacterList", "getCharList", "getShortList", "getBooleanList",
			"getMapList", "getConfigurationSection", "getSection", "contains", "isSet", "set", "isString", "isInt",
			"isBoolean", "isDouble", "isLong", "isList", "isConfigurationSection", "getItemStack", "getLocation",
			"getColor", "getVector", "getOfflinePlayer", "getObject", "getSerializable", "createSection", "addDefault",
			"getComponent", "getRichMessage");
	private static final Set<String> CONFIG_SECTION_METHODS = Set.of("getConfigurationSection", "getSection", "createSection");
	private static final Set<String> CHANNEL_METHODS = Set.of(
			"registerOutgoingPluginChannel", "registerIncomingPluginChannel", "unregisterOutgoingPluginChannel",
			"unregisterIncomingPluginChannel", "sendPluginMessage", "registerChannel", "unregisterChannel", "sendData");
	private static final Set<String> METADATA_METHODS = Set.of("setMetadata", "getMetadata", "hasMetadata", "removeMetadata");
	private static final Set<String> CONFIG_IO_METHODS = Set.of("saveDefaultConfig", "reloadConfig", "saveConfig",
			"saveResource", "loadConfiguration");
	private static final Set<String> INTERESTING_CALLS;
	/** Package prefixes, or package parts when starting with '/', of libraries that plugins commonly shade. */
	private static final List<String> SHADED_LIBRARIES = List.of(
			"org/bstats/", "/bstats/", "com/zaxxer/hikari/", "/hikari/", "kotlin/", "kotlinx/", "org/jetbrains/",
			"org/intellij/", "com/google/", "net/kyori/", "de/tr7zw/", "/nbtapi/", "org/apache/", "com/fasterxml/",
			"org/slf4j/", "co/aikar/", "org/yaml/", "com/cryptomorin/xseries/", "/xseries/", "dev/triumphteam/",
			"io/papermc/lib/", "/paperlib/", "org/mariadb/", "com/mysql/", "org/sqlite/", "org/h2/", "redis/clients/",
			"io/lettuce/", "com/github/benmanes/", "org/checkerframework/", "javax/annotation/", "org/json/",
			"fr/mrmicky/fastboard/", "/fastboard/", "me/lucko/", "dev/dejvokep/", "/boostedyaml/",
			"org/spongepowered/configurate/", "/libs/", "/shaded/");

	static {
		Set<String> calls = new HashSet<>(CONFIG_METHODS);
		calls.addAll(SCHEDULER_NAMES);
		calls.addAll(CHANNEL_METHODS);
		calls.addAll(METADATA_METHODS);
		calls.addAll(CONFIG_IO_METHODS);
		calls.addAll(Set.of("getCommand", "setExecutor", "setTabCompleter", "registerEvents", "registerListener",
				"registerCommand", "register", "callEvent", "hasPermission", "isPermissionSet", "setPermission",
				"<init>", "fromString", "minecraft", "getPlugin", "isPluginEnabled", "getRegistration", "load",
				"getConnection"));
		INTERESTING_CALLS = Collections.unmodifiableSet(calls);
	}

	private final Workspace workspace;
	private final List<MinecraftPluginManifest> manifests;
	private final boolean skipShadedLibraries;
	private final Map<String, ClassNode> classes = new TreeMap<>();
	private final Map<String, Set<String>> supertypeCache = new HashMap<>();
	private final Map<String, String> constantStrings = new HashMap<>();
	private final Map<String, String> commandClassNames = new HashMap<>();
	private final Set<PluginElement> elements = new LinkedHashSet<>();
	private final List<ValueFlow> valueFlows = new ArrayList<>();
	private final Set<ConstantUse> constantUses = new LinkedHashSet<>();
	private final List<Accessor> accessors = new ArrayList<>();
	private final Map<CodeLocation, Set<Behavior>> behaviors = new LinkedHashMap<>();

	private PluginIndexer(@Nonnull Workspace workspace, @Nonnull List<MinecraftPluginManifest> manifests,
	                      boolean skipShadedLibraries) {
		this.workspace = workspace;
		this.manifests = manifests;
		this.skipShadedLibraries = skipShadedLibraries;
	}

	/**
	 * @param workspace
	 * 		Workspace containing the plugin as its primary resource.
	 * @param manifests
	 * 		Manifests found in the primary resource.
	 * @param skipShadedLibraries
	 * 		{@code true} to ignore classes in packages of libraries plugins commonly shade, such as bStats.
	 *
	 * @return Index of the plugin.
	 */
	@Nonnull
	public static PluginIndex index(@Nonnull Workspace workspace,
	                                @Nonnull List<MinecraftPluginManifest> manifests,
	                                boolean skipShadedLibraries) {
		return new PluginIndexer(workspace, manifests, skipShadedLibraries).run();
	}

	/**
	 * @param className
	 * 		Internal class name.
	 *
	 * @return {@code true} when the class is in a package of a library that plugins commonly shade.
	 */
	public static boolean isShadedLibrary(@Nonnull String className) {
		for (String library : SHADED_LIBRARIES) {
			if (library.charAt(0) == '/' ? className.contains(library) : className.startsWith(library))
				return true;
		}
		return false;
	}

	@Nonnull
	private PluginIndex run() {
		for (JvmClassInfo cls : workspace.getPrimaryResource().getJvmClassBundle().values()) {
			if (skipShadedLibraries && isShadedLibrary(cls.getName()))
				continue;
			try {
				ClassNode node = new ClassNode();
				cls.getClassReader().accept(node, ClassReader.SKIP_FRAMES);
				classes.put(node.name, node);
			} catch (Throwable t) {
				logger.debug("Skipping unreadable class '{}' in plugin indexing", cls.getName(), t);
			}
		}

		// Values that other classes refer to have to be known before looking at uses.
		for (ClassNode node : classes.values()) {
			collectConstants(node);
			collectCommandClassName(node);
		}
		indexManifests();
		for (ClassNode node : classes.values()) {
			try {
				indexClass(node);
			} catch (Throwable t) {
				logger.debug("Failed to index class '{}' for plugin structure", node.name, t);
			}
		}
		assignDefaultExecutors();

		NamingFacts facts = new NamingFacts(List.copyOf(valueFlows), List.copyOf(constantUses),
				List.copyOf(accessors), Collections.unmodifiableMap(behaviors));
		return new PluginIndex(manifests, removeRedundant(elements), facts);
	}

	private void collectConstants(@Nonnull ClassNode node) {
		for (FieldNode field : node.fields)
			if ((field.access & ACC_STATIC) != 0 && field.value instanceof String value)
				constantStrings.put(node.name + '.' + field.name, value);

		// Constants that are not compile-time constants are assigned in the static initializer.
		for (MethodNode method : node.methods) {
			if (!"<clinit>".equals(method.name))
				continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && put.owner.equals(node.name) &&
						"Ljava/lang/String;".equals(put.desc) && previousReal(insn) instanceof LdcInsnNode ldc &&
						ldc.cst instanceof String value)
					constantStrings.putIfAbsent(node.name + '.' + put.name, value);
			}
		}
	}

	private void collectCommandClassName(@Nonnull ClassNode node) {
		Set<String> supers = supertypes(node.name);
		if (!supers.contains(BUKKIT_COMMAND) && !supers.contains(BUNGEE_COMMAND))
			return;

		// Command classes pass their name to the super constructor: 'super("heal", ...)'
		for (MethodNode method : node.methods) {
			if (!"<init>".equals(method.name))
				continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL &&
						"<init>".equals(call.name) && call.owner.equals(node.superName) &&
						call.desc.startsWith("(Ljava/lang/String;")) {
					MethodFlow flow = MethodFlow.analyze(node.name, method);
					String name = flow == null ? null : key(flow, call, 0, PluginElementKind.COMMAND);
					if (name != null) {
						commandClassNames.put(node.name, name);
						return;
					}
				}
			}
		}
	}

	private void indexManifests() {
		for (MinecraftPluginManifest manifest : manifests) {
			String pluginName = manifest.name();
			addEntryClass(PluginElementKind.MAIN_CLASS, pluginName, manifest.mainClass(),
					Set.of("onEnable", "onDisable", "onLoad"));
			addEntryClass(PluginElementKind.BOOTSTRAPPER, pluginName, manifest.bootstrapperClass(),
					Set.of("bootstrap", "createPlugin"));
			addEntryClass(PluginElementKind.LOADER, pluginName, manifest.loaderClass(), Set.of("classloader"));
		}
	}

	private void addEntryClass(@Nonnull PluginElementKind kind, @Nullable String pluginName,
	                           @Nullable String className, @Nonnull Set<String> lifecycleMethods) {
		if (className == null)
			return;
		ClassNode node = classes.get(className);
		if (node == null)
			return;
		String key = pluginName != null ? pluginName : NameHeuristics.simpleName(className);
		elements.add(new PluginElement(kind, key, CodeLocation.ofClass(className), null, null));
		for (MethodNode method : node.methods)
			if (lifecycleMethods.contains(method.name))
				elements.add(new PluginElement(PluginElementKind.LIFECYCLE, method.name,
						CodeLocation.ofMember(className, method.name, method.desc), null, null));
	}

	private boolean isManifestMain(@Nonnull String className) {
		for (MinecraftPluginManifest manifest : manifests)
			if (manifest.isMainClass(className))
				return true;
		return false;
	}

	private void indexClass(@Nonnull ClassNode node) {
		String name = node.name;
		String simple = NameHeuristics.simpleName(name);
		CodeLocation classLocation = CodeLocation.ofClass(name);
		Set<String> supers = supertypes(name);
		boolean isInterface = (node.access & ACC_INTERFACE) != 0;

		// Main classes not named by a manifest, such as when the manifest is missing.
		if (!isInterface && !isManifestMain(name) && (supers.contains(JAVA_PLUGIN) || supers.contains(BUNGEE_PLUGIN)))
			addEntryClass(PluginElementKind.MAIN_CLASS, null, name, Set.of("onEnable", "onDisable", "onLoad"));

		// Roles from the API types a class extends or implements.
		boolean hasHandlers = false;
		for (MethodNode method : node.methods)
			if (eventHandlerAnnotation(method) != null)
				hasHandlers = true;
		if (!isInterface) {
			if (hasHandlers || supers.contains(BUKKIT_LISTENER) || supers.contains(BUNGEE_LISTENER))
				elements.add(new PluginElement(PluginElementKind.LISTENER, simple, classLocation, null, null));
			MethodNode onCommand = declared(node, "onCommand", ON_COMMAND_DESC);
			if (onCommand != null)
				elements.add(new PluginElement(PluginElementKind.COMMAND_EXECUTOR, "",
						CodeLocation.ofMember(name, onCommand.name, onCommand.desc), null, null));
			MethodNode onTabComplete = declared(node, "onTabComplete", ON_TAB_COMPLETE_DESC);
			if (onTabComplete != null)
				elements.add(new PluginElement(PluginElementKind.TAB_COMPLETER, "",
						CodeLocation.ofMember(name, onTabComplete.name, onTabComplete.desc), null, null));
			if (supers.contains(BUKKIT_COMMAND) || supers.contains(BUNGEE_COMMAND))
				elements.add(new PluginElement(PluginElementKind.COMMAND_EXECUTOR, commandClassNames.getOrDefault(name, ""),
						commandClassTarget(name), null, "command class"));
			if (supers.contains(BUKKIT_RUNNABLE)) {
				MethodNode run = declared(node, "run", "()V");
				elements.add(new PluginElement(PluginElementKind.TASK, "",
						run != null ? CodeLocation.ofMember(name, "run", "()V") : classLocation, null, null));
			}
			if (supers.contains(BUKKIT_EVENT) || supers.contains(BUNGEE_EVENT) || declaresHandlerList(node))
				elements.add(new PluginElement(PluginElementKind.CUSTOM_EVENT, simple, classLocation, null,
						node.superName == null ? null : NameHeuristics.simpleName(node.superName)));
			if (supers.contains(SERIALIZABLE)) {
				String alias = serializableAlias(node);
				elements.add(new PluginElement(PluginElementKind.SERIALIZABLE, alias != null ? alias : simple,
						classLocation, null, alias != null ? "@SerializableAs" : null));
			}
			if (supers.contains(INVENTORY_HOLDER))
				elements.add(new PluginElement(PluginElementKind.INVENTORY_HOLDER, simple, classLocation, null, null));
			if (supers.contains(PAPI_EXPANSION)) {
				String identifier = constantReturn(node, "getIdentifier", "()Ljava/lang/String;");
				elements.add(new PluginElement(PluginElementKind.PLACEHOLDER_EXPANSION,
						identifier != null ? identifier : simple, classLocation, null, identifier != null ? "identifier" : null));
			}
			if (supers.contains(MESSAGE_LISTENER))
				elements.add(new PluginElement(PluginElementKind.MESSAGE_LISTENER, simple, classLocation, null, null));
		}

		for (MethodNode method : node.methods) {
			CodeLocation methodLocation = CodeLocation.ofMember(name, method.name, method.desc);
			AnnotationNode handler = eventHandlerAnnotation(method);
			if (handler != null) {
				Type[] args = Type.getArgumentTypes(method.desc);
				if (args.length == 1 && args[0].getSort() == Type.OBJECT) {
					String event = args[0].getInternalName();
					elements.add(new PluginElement(PluginElementKind.EVENT_HANDLER, NameHeuristics.simpleName(event),
							methodLocation, CodeLocation.ofClass(event), handlerDetail(handler)));
				}
			}
			detectAccessor(node, method);
			try {
				scanMethod(node, method, methodLocation);
			} catch (Throwable t) {
				logger.debug("Failed to scan method '{}' for plugin structure", methodLocation, t);
			}
		}
	}

	private void scanMethod(@Nonnull ClassNode node, @Nonnull MethodNode method, @Nonnull CodeLocation here) {
		// Most methods do not touch the plugin API at all, so avoid the cost of analysis for those.
		boolean interesting = false;
		for (AbstractInsnNode insn : method.instructions) {
			if ((insn instanceof MethodInsnNode call && INTERESTING_CALLS.contains(call.name)) ||
					(insn instanceof TypeInsnNode type && type.getOpcode() == NEW && newBehavior(type.desc) != null)) {
				interesting = true;
				break;
			}
		}
		if (!interesting)
			return;
		MethodFlow flow = MethodFlow.analyze(node.name, method);
		if (flow == null)
			return;

		boolean hasVariables = method.localVariables != null && !method.localVariables.isEmpty();
		Map<AbstractInsnNode, String> namespacedKeys = new HashMap<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) {
				handleCall(flow, here, call, namespacedKeys);
			} else if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) {
				Behavior behavior = newBehavior(type.desc);
				if (behavior != null)
					addBehavior(here, behavior);
			} else {
				handleValueFlow(flow, here, insn, namespacedKeys, hasVariables);
			}
		}
	}

	private void handleCall(@Nonnull MethodFlow flow, @Nonnull CodeLocation here, @Nonnull MethodInsnNode call,
	                        @Nonnull Map<AbstractInsnNode, String> namespacedKeys) {
		String owner = call.owner;
		String name = call.name;
		String desc = call.desc;
		switch (name) {
			case "getCommand" -> {
				if (desc.equals("(Ljava/lang/String;)Lorg/bukkit/command/PluginCommand;")) {
					String command = key(flow, call, 0, PluginElementKind.COMMAND);
					if (command != null)
						elements.add(new PluginElement(PluginElementKind.COMMAND, command, here, null, null));
					addBehavior(here, Behavior.REGISTERS_COMMANDS);
				}
			}
			case "setExecutor", "setTabCompleter" -> {
				boolean executor = name.equals("setExecutor");
				if (!desc.equals(executor ? "(Lorg/bukkit/command/CommandExecutor;)V" : "(Lorg/bukkit/command/TabCompleter;)V"))
					return;
				String command = "";
				if (flow.receiver(call) instanceof MethodInsnNode getCommand && "getCommand".equals(getCommand.name)) {
					String found = key(flow, getCommand, 0, PluginElementKind.COMMAND);
					if (found != null)
						command = found;
				}
				CodeLocation target = executor ?
						handlerTarget(flow, flow.argument(call, 0), "onCommand", ON_COMMAND_DESC) :
						handlerTarget(flow, flow.argument(call, 0), "onTabComplete", ON_TAB_COMPLETE_DESC);
				if (target != null)
					elements.add(new PluginElement(executor ? PluginElementKind.COMMAND_EXECUTOR : PluginElementKind.TAB_COMPLETER,
							command, target, here, null));
				addBehavior(here, Behavior.REGISTERS_COMMANDS);
			}
			case "registerEvents", "registerListener" -> {
				int listenerArg;
				if (desc.equals("(Lorg/bukkit/event/Listener;Lorg/bukkit/plugin/Plugin;)V"))
					listenerArg = 0;
				else if (desc.equals("(Lnet/md_5/bungee/api/plugin/Plugin;Lnet/md_5/bungee/api/plugin/Listener;)V"))
					listenerArg = 1;
				else
					return;
				String listener = workspaceType(flow, flow.argument(call, listenerArg));
				if (listener != null)
					elements.add(new PluginElement(PluginElementKind.LISTENER, NameHeuristics.simpleName(listener),
							CodeLocation.ofClass(listener), here, null));
				addBehavior(here, Behavior.REGISTERS_LISTENERS);
			}
			case "registerCommand", "register" -> {
				int commandArg;
				if (desc.equals("(Lnet/md_5/bungee/api/plugin/Plugin;Lnet/md_5/bungee/api/plugin/Command;)V") ||
						desc.equals("(Ljava/lang/String;Lorg/bukkit/command/Command;)Z"))
					commandArg = 1;
				else if (desc.equals("(Ljava/lang/String;Ljava/lang/String;Lorg/bukkit/command/Command;)Z"))
					commandArg = 2;
				else
					return;
				String commandClass = workspaceType(flow, flow.argument(call, commandArg));
				if (commandClass != null) {
					String command = commandClassNames.getOrDefault(commandClass, "");
					if (!command.isEmpty())
						elements.add(new PluginElement(PluginElementKind.COMMAND, command, here, null, null));
					elements.add(new PluginElement(PluginElementKind.COMMAND_EXECUTOR, command,
							commandClassTarget(commandClass), here, "command class"));
				}
				addBehavior(here, Behavior.REGISTERS_COMMANDS);
			}
			case "callEvent" -> {
				if (desc.equals("(Lorg/bukkit/event/Event;)V") ||
						desc.equals("(Lnet/md_5/bungee/api/plugin/Event;)Lnet/md_5/bungee/api/plugin/Event;")) {
					String event = flow.typeOf(flow.argument(call, 0));
					if (event != null && !event.equals(BUKKIT_EVENT) && !event.equals(BUNGEE_EVENT))
						elements.add(new PluginElement(PluginElementKind.EVENT_CALL, NameHeuristics.simpleName(event),
								here, CodeLocation.ofClass(event), null));
				}
			}
			case "hasPermission", "isPermissionSet" -> {
				if (desc.equals("(Ljava/lang/String;)Z"))
					addKeyElement(flow, here, call, 0, PluginElementKind.PERMISSION);
			}
			case "setPermission" -> {
				if (desc.equals("(Ljava/lang/String;)V") && isApiOwner(owner))
					addKeyElement(flow, here, call, 0, PluginElementKind.PERMISSION);
			}
			case "<init>" -> {
				if (owner.equals("org/bukkit/permissions/Permission") && desc.startsWith("(Ljava/lang/String;")) {
					addKeyElement(flow, here, call, 0, PluginElementKind.PERMISSION);
				} else if (owner.equals("org/bukkit/NamespacedKey") &&
						(desc.equals("(Lorg/bukkit/plugin/Plugin;Ljava/lang/String;)V") ||
								desc.equals("(Ljava/lang/String;Ljava/lang/String;)V"))) {
					String key = addKeyElement(flow, here, call, 1, PluginElementKind.NAMESPACED_KEY);
					AbstractInsnNode instance = flow.receiver(call);
					if (key != null && instance != null)
						namespacedKeys.put(instance, key);
				}
			}
			case "fromString", "minecraft" -> {
				if (owner.equals("org/bukkit/NamespacedKey") && desc.startsWith("(Ljava/lang/String;"))
					addKeyElement(flow, here, call, 0, PluginElementKind.NAMESPACED_KEY);
			}
			case "getPlugin", "isPluginEnabled" -> {
				if (desc.equals("(Ljava/lang/String;)Lorg/bukkit/plugin/Plugin;") ||
						desc.equals("(Ljava/lang/String;)Lnet/md_5/bungee/api/plugin/Plugin;") ||
						(name.equals("isPluginEnabled") && desc.equals("(Ljava/lang/String;)Z"))) {
					addKeyElement(flow, here, call, 0, PluginElementKind.PLUGIN_HOOK);
					addBehavior(here, Behavior.HOOKS_PLUGINS);
				}
			}
			case "getRegistration", "load" -> {
				if (owner.equals("org/bukkit/plugin/ServicesManager") && desc.startsWith("(Ljava/lang/Class;)") &&
						flow.argument(call, 0) instanceof LdcInsnNode ldc && ldc.cst instanceof Type service) {
					String serviceName = NameHeuristics.simpleName(service.getInternalName());
					elements.add(new PluginElement(PluginElementKind.PLUGIN_HOOK, serviceName, here, null, "service"));
					addBehavior(here, serviceName.equals("Economy") ? Behavior.HOOKS_ECONOMY : Behavior.HOOKS_PLUGINS);
				}
			}
			case "getConnection" -> {
				if (owner.equals("java/sql/DriverManager"))
					addBehavior(here, Behavior.CONNECTS_DATABASE);
			}
			default -> {
				// Handled below
			}
		}

		if (SCHEDULER_NAMES.contains(name))
			handleSchedule(flow, here, call);
		if (CHANNEL_METHODS.contains(name) && isApiOwner(owner)) {
			Type[] args = Type.getArgumentTypes(desc);
			for (int i = 0; i < args.length; i++) {
				if (args[i].getDescriptor().equals("Ljava/lang/String;")) {
					addKeyElement(flow, here, call, i, PluginElementKind.CHANNEL);
					break;
				}
			}
			if (name.startsWith("register"))
				addBehavior(here, Behavior.REGISTERS_CHANNELS);
		}
		if (METADATA_METHODS.contains(name) && desc.startsWith("(Ljava/lang/String;"))
			addKeyElement(flow, here, call, 0, PluginElementKind.METADATA_KEY);
		if (CONFIG_IO_METHODS.contains(name) && (isApiOwner(owner) || classes.containsKey(owner)))
			addBehavior(here, Behavior.LOADS_CONFIG);
		if (isConfigCall(call)) {
			String path = configPath(flow, call, 0);
			if (path != null)
				elements.add(new PluginElement(PluginElementKind.CONFIG_KEY, path, here, null, name));
		}
	}

	private void handleSchedule(@Nonnull MethodFlow flow, @Nonnull CodeLocation here, @Nonnull MethodInsnNode call) {
		CodeLocation target = null;
		if (SCHEDULER_OWNERS.contains(call.owner) || call.owner.startsWith(FOLIA_SCHEDULER_PACKAGE)) {
			Type[] args = Type.getArgumentTypes(call.desc);
			for (int i = 0; i < args.length && target == null; i++)
				if (args[i].getSort() == Type.OBJECT && TASK_TYPES.contains(args[i].getInternalName()))
					target = handlerTarget(flow, flow.argument(call, i), "run", "()V");
		} else if (call.name.startsWith("run") && call.desc.startsWith("(Lorg/bukkit/plugin/Plugin;") &&
				call.desc.endsWith(")Lorg/bukkit/scheduler/BukkitTask;")) {
			// BukkitRunnable scheduling itself: 'new Task().runTaskTimer(plugin, 0, 20)'
			target = handlerTarget(flow, flow.receiver(call), "run", "()V");
		} else {
			return;
		}
		if (target != null)
			elements.add(new PluginElement(PluginElementKind.TASK, call.name, target, here, null));
		addBehavior(here, Behavior.SCHEDULES_TASKS);
	}

	private void handleValueFlow(@Nonnull MethodFlow flow, @Nonnull CodeLocation here, @Nonnull AbstractInsnNode insn,
	                             @Nonnull Map<AbstractInsnNode, String> namespacedKeys, boolean hasVariables) {
		FlowTarget target;
		CodeLocation field = null;
		int local = -1;
		int op = insn.getOpcode();
		if (insn instanceof FieldInsnNode put && (op == PUTFIELD || op == PUTSTATIC)) {
			if (!classes.containsKey(put.owner))
				return;
			target = FlowTarget.FIELD;
			field = CodeLocation.ofMember(put.owner, put.name, put.desc);
		} else if (insn instanceof VarInsnNode store && op >= ISTORE && op <= ASTORE) {
			if (!hasVariables)
				return;
			target = FlowTarget.LOCAL;
			local = store.var;
		} else if (op >= IRETURN && op <= ARETURN) {
			target = FlowTarget.RETURN;
		} else {
			return;
		}

		AbstractInsnNode origin = flow.stackOrigin(insn, 0);
		if (origin instanceof MethodInsnNode call && isConfigCall(call)) {
			String path = configPath(flow, call, 0);
			if (path != null)
				valueFlows.add(new ValueFlow(target, here, field, local, PluginElementKind.CONFIG_KEY, path, call.name));
		} else if (origin != null && namespacedKeys.containsKey(origin)) {
			valueFlows.add(new ValueFlow(target, here, field, local, PluginElementKind.NAMESPACED_KEY,
					namespacedKeys.get(origin), "NamespacedKey"));
		}
	}

	private void detectAccessor(@Nonnull ClassNode node, @Nonnull MethodNode method) {
		if (method.name.startsWith("<") || (method.access & ACC_ABSTRACT) != 0)
			return;
		List<AbstractInsnNode> code = new ArrayList<>(6);
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() < 0)
				continue; // Labels, line numbers, frames
			code.add(insn);
			if (code.size() > 4)
				return;
		}
		boolean isStatic = (method.access & ACC_STATIC) != 0;
		Type[] args = Type.getArgumentTypes(method.desc);
		Type ret = Type.getReturnType(method.desc);
		CodeLocation methodLocation = CodeLocation.ofMember(node.name, method.name, method.desc);
		FieldInsnNode field = null;
		AccessorKind kind = null;
		if (args.length == 0 && ret.getSort() != Type.VOID) {
			if (!isStatic && code.size() == 3 && isLoad(code.get(0), ALOAD, 0) &&
					code.get(1).getOpcode() == GETFIELD && isReturn(code.get(2))) {
				field = (FieldInsnNode) code.get(1);
				kind = AccessorKind.GETTER;
			} else if (isStatic && code.size() == 2 && code.get(0).getOpcode() == GETSTATIC && isReturn(code.get(1))) {
				field = (FieldInsnNode) code.get(0);
				kind = field.desc.equals("L" + node.name + ";") ? AccessorKind.INSTANCE : AccessorKind.GETTER;
			}
		} else if (args.length == 1 && ret.getSort() == Type.VOID) {
			int load = args[0].getOpcode(ILOAD);
			if (!isStatic && code.size() == 4 && isLoad(code.get(0), ALOAD, 0) && isLoad(code.get(1), load, 1) &&
					code.get(2).getOpcode() == PUTFIELD && code.get(3).getOpcode() == RETURN) {
				field = (FieldInsnNode) code.get(2);
				kind = AccessorKind.SETTER;
			} else if (isStatic && code.size() == 3 && isLoad(code.get(0), load, 0) &&
					code.get(1).getOpcode() == PUTSTATIC && code.get(2).getOpcode() == RETURN) {
				field = (FieldInsnNode) code.get(1);
				kind = AccessorKind.SETTER;
			}
		}
		if (field != null && field.owner.equals(node.name) && declaresField(node, field.name, field.desc))
			accessors.add(new Accessor(methodLocation, CodeLocation.ofMember(field.owner, field.name, field.desc), kind));
	}

	/**
	 * Commands declared in the manifest but never given an executor are handled by the main class.
	 */
	private void assignDefaultExecutors() {
		for (MinecraftPluginManifest manifest : manifests) {
			String main = manifest.mainClass();
			if (main == null || !classes.containsKey(main) || declared(classes.get(main), "onCommand", ON_COMMAND_DESC) == null)
				continue;
			CodeLocation onCommand = CodeLocation.ofMember(main, "onCommand", ON_COMMAND_DESC);
			for (String command : manifest.commands()) {
				boolean hasExecutor = elements.stream().anyMatch(e ->
						e.kind() == PluginElementKind.COMMAND_EXECUTOR && e.key().equalsIgnoreCase(command));
				if (!hasExecutor)
					elements.add(new PluginElement(PluginElementKind.COMMAND_EXECUTOR, command, onCommand, null,
							"main class is the default executor"));
			}
		}
	}

	/**
	 * Role-based elements are found without a key or registration site. When the same thing was also found with
	 * more detail, only the detailed element is kept.
	 */
	@Nonnull
	private static List<PluginElement> removeRedundant(@Nonnull Set<PluginElement> elements) {
		Map<String, List<PluginElement>> byKindAndLocation = new HashMap<>();
		for (PluginElement element : elements)
			byKindAndLocation.computeIfAbsent(element.kind() + " " + element.location(), k -> new ArrayList<>()).add(element);
		List<PluginElement> kept = new ArrayList<>(elements.size());
		for (PluginElement element : elements) {
			List<PluginElement> same = byKindAndLocation.get(element.kind() + " " + element.location());
			boolean redundant = same.stream().anyMatch(other -> other != element &&
					((element.key().isEmpty() && !other.key().isEmpty()) ||
							(element.related() == null && other.related() != null && other.key().equals(element.key()))));
			if (!redundant)
				kept.add(element);
		}
		return kept;
	}

	@Nullable
	private String addKeyElement(@Nonnull MethodFlow flow, @Nonnull CodeLocation here, @Nonnull MethodInsnNode call,
	                             int argument, @Nonnull PluginElementKind kind) {
		String key = key(flow, call, argument, kind);
		if (key != null && !key.isBlank())
			elements.add(new PluginElement(kind, key, here, null, call.name.equals("<init>") ?
					"new " + NameHeuristics.simpleName(call.owner) : call.name));
		return key;
	}

	/**
	 * @return The constant string passed as the argument, or {@code null} if not a constant. Constant fields are
	 * recorded as being used for the kind of key.
	 */
	@Nullable
	private String key(@Nonnull MethodFlow flow, @Nonnull MethodInsnNode call, int argument, @Nonnull PluginElementKind kind) {
		AbstractInsnNode origin = flow.argument(call, argument);
		if (origin instanceof LdcInsnNode ldc && ldc.cst instanceof String value)
			return value;
		if (origin instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC) {
			String value = constantStrings.get(field.owner + '.' + field.name);
			if (value != null && classes.containsKey(field.owner))
				constantUses.add(new ConstantUse(CodeLocation.ofMember(field.owner, field.name, field.desc), kind, value));
			return value;
		}
		return null;
	}

	@Nullable
	private String configPath(@Nonnull MethodFlow flow, @Nonnull MethodInsnNode call, int depth) {
		String key = key(flow, call, 0, PluginElementKind.CONFIG_KEY);
		if (key == null)
			return null;

		// 'config.getConfigurationSection("a").getString("b")' reads 'a.b'
		if (depth < 4 && flow.receiver(call) instanceof MethodInsnNode section &&
				CONFIG_SECTION_METHODS.contains(section.name) && isConfigCall(section)) {
			String prefix = configPath(flow, section, depth + 1);
			if (prefix != null)
				return prefix + '.' + key;
		}
		return key;
	}

	private static boolean isConfigCall(@Nonnull MethodInsnNode call) {
		return (call.owner.startsWith("org/bukkit/configuration/") || call.owner.equals("net/md_5/bungee/config/Configuration")) &&
				CONFIG_METHODS.contains(call.name) && call.desc.startsWith("(Ljava/lang/String;");
	}

	private static boolean isApiOwner(@Nonnull String owner) {
		return owner.startsWith("org/bukkit/") || owner.startsWith("net/md_5/bungee/") ||
				owner.startsWith("io/papermc/") || owner.startsWith("com/destroystokyo/paper/") ||
				owner.startsWith("com/velocitypowered/");
	}

	/**
	 * @return Where the code that handles a value is: the implementation method of a lambda, the given method in a
	 * plugin class if it declares it, or otherwise the plugin class.
	 */
	@Nullable
	private CodeLocation handlerTarget(@Nonnull MethodFlow flow, @Nullable AbstractInsnNode origin,
	                                   @Nonnull String methodName, @Nonnull String methodDesc) {
		CodeLocation lambda = MethodFlow.lambdaImplementation(origin);
		if (lambda != null)
			return lambda;
		String type = workspaceType(flow, origin);
		if (type == null)
			return null;
		if (declared(classes.get(type), methodName, methodDesc) != null)
			return CodeLocation.ofMember(type, methodName, methodDesc);
		return CodeLocation.ofClass(type);
	}

	@Nullable
	private String workspaceType(@Nonnull MethodFlow flow, @Nullable AbstractInsnNode origin) {
		String type = flow.typeOf(origin);
		return type != null && classes.containsKey(type) ? type : null;
	}

	@Nonnull
	private CodeLocation commandClassTarget(@Nonnull String className) {
		ClassNode node = classes.get(className);
		if (declared(node, "execute", BUKKIT_COMMAND_EXECUTE_DESC) != null)
			return CodeLocation.ofMember(className, "execute", BUKKIT_COMMAND_EXECUTE_DESC);
		if (declared(node, "execute", BUNGEE_COMMAND_EXECUTE_DESC) != null)
			return CodeLocation.ofMember(className, "execute", BUNGEE_COMMAND_EXECUTE_DESC);
		return CodeLocation.ofClass(className);
	}

	private void addBehavior(@Nonnull CodeLocation method, @Nonnull Behavior behavior) {
		behaviors.computeIfAbsent(method, k -> EnumSet.noneOf(Behavior.class)).add(behavior);
	}

	@Nullable
	private static Behavior newBehavior(@Nonnull String type) {
		if (type.endsWith("/Metrics") && type.contains("bstats"))
			return Behavior.SETS_UP_METRICS;
		if (type.endsWith("/HikariDataSource") || type.endsWith("/HikariConfig"))
			return Behavior.CONNECTS_DATABASE;
		return null;
	}

	/**
	 * @return All supertypes of the class, including ones that are referenced but not in the workspace.
	 */
	@Nonnull
	private Set<String> supertypes(@Nonnull String className) {
		Set<String> cached = supertypeCache.get(className);
		if (cached != null)
			return cached;
		Set<String> supers = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		queue.add(className);
		while (!queue.isEmpty()) {
			String name = queue.poll();
			List<String> parents = directParents(name);
			for (String parent : parents)
				if (supers.add(parent))
					queue.add(parent);
		}
		supertypeCache.put(className, supers);
		return supers;
	}

	@Nonnull
	private List<String> directParents(@Nonnull String className) {
		List<String> parents = new ArrayList<>(3);
		ClassNode node = classes.get(className);
		if (node != null) {
			if (node.superName != null)
				parents.add(node.superName);
			parents.addAll(node.interfaces);
			return parents;
		}
		ClassPathNode path = workspace.findClass(className);
		if (path != null) {
			ClassInfo info = path.getValue();
			if (info.getSuperName() != null)
				parents.add(info.getSuperName());
			parents.addAll(info.getInterfaces());
		}
		return parents;
	}

	@Nullable
	private static AnnotationNode eventHandlerAnnotation(@Nonnull MethodNode method) {
		for (List<AnnotationNode> annotations : List.of(
				method.visibleAnnotations == null ? List.<AnnotationNode>of() : method.visibleAnnotations,
				method.invisibleAnnotations == null ? List.<AnnotationNode>of() : method.invisibleAnnotations))
			for (AnnotationNode annotation : annotations)
				if (HANDLER_ANNOTATIONS.contains(annotation.desc))
					return annotation;
		return null;
	}

	@Nullable
	private static String handlerDetail(@Nonnull AnnotationNode annotation) {
		if (annotation.values == null)
			return null;
		List<String> parts = new ArrayList<>(2);
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			Object name = annotation.values.get(i);
			Object value = annotation.values.get(i + 1);
			if ("priority".equals(name) && value instanceof String[] enumValue && enumValue.length == 2)
				parts.add(enumValue[1]);
			else if ("ignoreCancelled".equals(name) && Boolean.TRUE.equals(value))
				parts.add("ignoreCancelled");
			else if ("order".equals(name) && value != null)
				parts.add("order " + value);
		}
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	@Nullable
	private static String serializableAlias(@Nonnull ClassNode node) {
		for (List<AnnotationNode> annotations : List.of(
				node.visibleAnnotations == null ? List.<AnnotationNode>of() : node.visibleAnnotations,
				node.invisibleAnnotations == null ? List.<AnnotationNode>of() : node.invisibleAnnotations))
			for (AnnotationNode annotation : annotations)
				if (SERIALIZABLE_AS.equals(annotation.desc) && annotation.values != null)
					for (int i = 0; i + 1 < annotation.values.size(); i += 2)
						if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String alias)
							return alias;
		return null;
	}

	/**
	 * @return The string the method returns, if it only returns a constant.
	 */
	@Nullable
	private static String constantReturn(@Nonnull ClassNode node, @Nonnull String name, @Nonnull String desc) {
		MethodNode method = declared(node, name, desc);
		if (method == null)
			return null;
		for (AbstractInsnNode insn : method.instructions)
			if (insn.getOpcode() == ARETURN && previousReal(insn) instanceof LdcInsnNode ldc && ldc.cst instanceof String value)
				return value;
		return null;
	}

	private static boolean declaresHandlerList(@Nonnull ClassNode node) {
		MethodNode method = declared(node, "getHandlerList", HANDLER_LIST_DESC);
		return method != null && (method.access & ACC_STATIC) != 0;
	}

	@Nullable
	private static MethodNode declared(@Nullable ClassNode node, @Nonnull String name, @Nonnull String desc) {
		if (node == null)
			return null;
		for (MethodNode method : node.methods)
			if (method.name.equals(name) && method.desc.equals(desc))
				return method;
		return null;
	}

	private static boolean declaresField(@Nonnull ClassNode node, @Nonnull String name, @Nonnull String desc) {
		for (FieldNode field : node.fields)
			if (field.name.equals(name) && field.desc.equals(desc))
				return true;
		return false;
	}

	@Nullable
	private static AbstractInsnNode previousReal(@Nonnull AbstractInsnNode insn) {
		AbstractInsnNode prev = insn.getPrevious();
		while (prev != null && prev.getOpcode() < 0)
			prev = prev.getPrevious();
		return prev;
	}

	private static boolean isLoad(@Nonnull AbstractInsnNode insn, int opcode, int var) {
		return insn instanceof VarInsnNode load && load.getOpcode() == opcode && load.var == var;
	}

	private static boolean isReturn(@Nonnull AbstractInsnNode insn) {
		int op = insn.getOpcode();
		return op >= IRETURN && op <= ARETURN;
	}
}
