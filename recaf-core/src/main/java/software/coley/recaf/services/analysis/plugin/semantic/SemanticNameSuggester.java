package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.Type;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.LocalVariable;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginManifest;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.ClassTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.FieldTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.MethodTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.VariableTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.Accessor;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.AccessorKind;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.Behavior;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.ConstantUse;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.FlowTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts.ValueFlow;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Suggests meaningful names for the classes, fields, methods and variables of a plugin, based on a {@link PluginIndex}.
 * <p>
 * Names are chosen in dependency order: classes first, then fields <i>(which can be named after the classes they hold)</i>,
 * then methods <i>(which can be named after the fields they access, or the events they handle)</i>, then variables.
 * Each suggestion keeps the strongest clue found for its target, and names are deconflicted against each other and
 * against existing names.
 */
public final class SemanticNameSuggester {
	private static final Map<String, String> TYPE_NAMES = new HashMap<>();
	private static final Set<String> COLLECTION_TYPES = Set.of("java/util/List", "java/util/ArrayList",
			"java/util/LinkedList", "java/util/Set", "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet",
			"java/util/Collection", "java/util/Queue", "java/util/Deque", "java/util/ArrayDeque",
			"java/util/concurrent/CopyOnWriteArrayList");
	private static final Set<String> MAP_TYPES = Set.of("java/util/Map", "java/util/HashMap", "java/util/LinkedHashMap",
			"java/util/TreeMap", "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentMap",
			"java/util/EnumMap", "java/util/WeakHashMap");
	private static final List<String> READABLE_TYPE_PACKAGES = List.of("java/", "javax/", "org/bukkit/",
			"net/md_5/bungee/", "io/papermc/", "com/destroystokyo/", "net/kyori/", "com/google/", "me/clip/",
			"net/milkbox/", "com/velocitypowered/");
	private static final Map<String, List<String>> CALLBACK_PARAMETERS = Map.of(
			"onCommand" + PluginIndexer.ON_COMMAND_DESC, List.of("sender", "command", "label", "args"),
			"onTabComplete" + PluginIndexer.ON_TAB_COMPLETE_DESC, List.of("sender", "command", "alias", "args"),
			"execute" + PluginIndexer.BUKKIT_COMMAND_EXECUTE_DESC, List.of("sender", "label", "args"),
			"execute" + PluginIndexer.BUNGEE_COMMAND_EXECUTE_DESC, List.of("sender", "args"));

	static {
		for (String type : List.of("org/bukkit/plugin/java/JavaPlugin", "org/bukkit/plugin/Plugin", PluginIndexer.BUNGEE_PLUGIN))
			TYPE_NAMES.put(type, "plugin");
		for (String type : List.of("org/bukkit/configuration/file/FileConfiguration", "org/bukkit/configuration/file/YamlConfiguration",
				"org/bukkit/configuration/Configuration", "net/md_5/bungee/config/Configuration"))
			TYPE_NAMES.put(type, "config");
		for (String type : List.of("java/util/logging/Logger", "org/slf4j/Logger", "org/apache/logging/log4j/Logger"))
			TYPE_NAMES.put(type, "logger");
		TYPE_NAMES.put("org/bukkit/configuration/ConfigurationSection", "section");
		TYPE_NAMES.put("org/bukkit/Server", "server");
		TYPE_NAMES.put("net/md_5/bungee/api/ProxyServer", "proxy");
		TYPE_NAMES.put("org/bukkit/scheduler/BukkitScheduler", "scheduler");
		TYPE_NAMES.put("org/bukkit/scheduler/BukkitTask", "task");
		TYPE_NAMES.put(PluginIndexer.BUKKIT_RUNNABLE, "task");
		TYPE_NAMES.put("org/bukkit/plugin/PluginManager", "pluginManager");
		TYPE_NAMES.put("net/milkbox/vault/economy/Economy", "economy");
		TYPE_NAMES.put("net/milkbox/vault/permission/Permission", "permissions");
		TYPE_NAMES.put("net/milkbox/vault/chat/Chat", "chat");
		TYPE_NAMES.put("org/bukkit/NamespacedKey", "key");
		TYPE_NAMES.put("org/bukkit/inventory/Inventory", "inventory");
		TYPE_NAMES.put("org/bukkit/inventory/ItemStack", "item");
		TYPE_NAMES.put("org/bukkit/inventory/meta/ItemMeta", "meta");
		TYPE_NAMES.put("org/bukkit/entity/Player", "player");
		TYPE_NAMES.put("net/md_5/bungee/api/connection/ProxiedPlayer", "player");
		TYPE_NAMES.put("org/bukkit/OfflinePlayer", "offlinePlayer");
		TYPE_NAMES.put("org/bukkit/command/CommandSender", "sender");
		TYPE_NAMES.put("net/md_5/bungee/api/CommandSender", "sender");
		TYPE_NAMES.put("org/bukkit/World", "world");
		TYPE_NAMES.put("org/bukkit/Location", "location");
		TYPE_NAMES.put("org/bukkit/block/Block", "block");
		TYPE_NAMES.put("org/bukkit/entity/Entity", "entity");
		TYPE_NAMES.put("java/util/UUID", "uuid");
		TYPE_NAMES.put("java/io/File", "file");
		TYPE_NAMES.put("java/nio/file/Path", "path");
		TYPE_NAMES.put("java/util/Random", "random");
		TYPE_NAMES.put("com/google/gson/Gson", "gson");
		TYPE_NAMES.put("java/sql/Connection", "connection");
		TYPE_NAMES.put("javax/sql/DataSource", "dataSource");
		TYPE_NAMES.put("com/zaxxer/hikari/HikariDataSource", "dataSource");
		TYPE_NAMES.put("org/bukkit/boss/BossBar", "bossBar");
		TYPE_NAMES.put("net/kyori/adventure/bossbar/BossBar", "bossBar");
		TYPE_NAMES.put("org/bukkit/scoreboard/Scoreboard", "scoreboard");
		TYPE_NAMES.put("org/bukkit/scoreboard/Team", "team");
		TYPE_NAMES.put("org/bukkit/scoreboard/Objective", "objective");
		TYPE_NAMES.put("net/kyori/adventure/text/minimessage/MiniMessage", "miniMessage");
		TYPE_NAMES.put("net/kyori/adventure/platform/bukkit/BukkitAudiences", "adventure");
		TYPE_NAMES.put("net/kyori/adventure/text/Component", "component");
		TYPE_NAMES.put("java/text/DecimalFormat", "format");
		TYPE_NAMES.put("java/text/SimpleDateFormat", "dateFormat");
		TYPE_NAMES.put("java/util/regex/Pattern", "pattern");
		TYPE_NAMES.put("java/util/concurrent/ExecutorService", "executor");
		TYPE_NAMES.put("org/bukkit/event/HandlerList", "handlers");
		TYPE_NAMES.put(PluginIndexer.PAPI_EXPANSION, "expansion");
	}

	private final Workspace workspace;
	private final PluginIndex index;
	private final InheritanceGraph graph;
	private final Map<String, JvmClassInfo> classes = new TreeMap<>();
	private final Set<String> mainClasses = new HashSet<>();
	private final Map<String, Proposal> classProposals = new LinkedHashMap<>();
	private final Map<String, String> finalClassNames = new HashMap<>();
	private final Map<NameTarget, Proposal> memberProposals = new LinkedHashMap<>();
	private final Map<CodeLocation, String> finalFieldNames = new HashMap<>();

	private SemanticNameSuggester(@Nonnull Workspace workspace, @Nonnull PluginIndex index,
	                              @Nonnull InheritanceGraph graph, boolean skipShadedLibraries) {
		this.workspace = workspace;
		this.index = index;
		this.graph = graph;
		for (JvmClassInfo cls : workspace.getPrimaryResource().getJvmClassBundle().values())
			if (!skipShadedLibraries || !PluginIndexer.isShadedLibrary(cls.getName()))
				classes.put(cls.getName(), cls);
		for (PluginElement element : index.ofKind(PluginElementKind.MAIN_CLASS))
			mainClasses.add(element.location().className());
	}

	/**
	 * @param workspace
	 * 		Workspace containing the plugin as its primary resource.
	 * @param index
	 * 		Index of the plugin.
	 * @param graph
	 * 		Inheritance graph of the workspace, used to keep names of methods consistent across a hierarchy.
	 * @param skipShadedLibraries
	 * 		{@code true} to make no suggestions for classes of libraries plugins commonly shade.
	 *
	 * @return Suggested names. Targets whose suggested name is the same as their current name are left out.
	 */
	@Nonnull
	public static List<NameSuggestion> suggest(@Nonnull Workspace workspace, @Nonnull PluginIndex index,
	                                           @Nonnull InheritanceGraph graph, boolean skipShadedLibraries) {
		return new SemanticNameSuggester(workspace, index, graph, skipShadedLibraries).run();
	}

	@Nonnull
	private List<NameSuggestion> run() {
		proposeClassNames();
		List<NameSuggestion> suggestions = new ArrayList<>(assignClassNames());
		proposeFieldNames();
		suggestions.addAll(assignFieldNames());
		suggestions.addAll(assignMethodNames());
		suggestions.addAll(assignVariableNames());
		return suggestions;
	}

	// ==================== Classes ==================== //

	private void proposeClassNames() {
		String pluginName = index.pluginName();
		String pluginPascal = pluginName == null ? "" : NameHeuristics.toPascalCase(pluginName);

		// Entry points named in the manifest.
		if (!pluginPascal.isEmpty()) {
			String mainName = pluginPascal.endsWith("Plugin") ? pluginPascal : pluginPascal + "Plugin";
			String reason = "Main class of plugin '" + pluginName + "'";
			for (PluginElement element : index.ofKind(PluginElementKind.MAIN_CLASS))
				proposeClass(element.location().className(), mainName, NamingClue.MANIFEST, reason, 95);
			for (PluginElement element : index.ofKind(PluginElementKind.BOOTSTRAPPER))
				proposeClass(element.location().className(), pluginPascal + "Bootstrap", NamingClue.MANIFEST,
						"Paper bootstrapper of plugin '" + pluginName + "'", 95);
			for (PluginElement element : index.ofKind(PluginElementKind.LOADER))
				proposeClass(element.location().className(), pluginPascal + "Loader", NamingClue.MANIFEST,
						"Paper loader of plugin '" + pluginName + "'", 95);
		}

		// Custom events, before listeners since listeners are named after their events.
		for (PluginElement element : index.ofKind(PluginElementKind.CUSTOM_EVENT)) {
			String parent = element.detail();
			boolean specificParent = parent != null && parent.endsWith("Event") && !parent.equals("Event") &&
					!NameHeuristics.looksObfuscated(parent);
			String name = specificParent ? "Custom" + parent : "CustomEvent";
			String reason = specificParent ? "Event class extending " + parent : "Event class declared by the plugin";
			proposeClass(element.location().className(), name, NamingClue.API_TYPE, reason, specificParent ? 45 : 40);
		}

		proposeCommandClassNames(PluginElementKind.COMMAND_EXECUTOR, "Command", 90);
		proposeCommandClassNames(PluginElementKind.TAB_COMPLETER, "TabCompleter", 85);
		proposeListenerClassNames();

		for (PluginElement element : index.ofKind(PluginElementKind.TASK)) {
			String taskClass = classOfHandler(element.location(), "run");
			if (taskClass == null)
				continue;
			String scheduler = element.key();
			String name = "Task";
			if (scheduler.contains("Timer") || scheduler.contains("Repeating") || scheduler.contains("FixedRate"))
				name = "RepeatingTask";
			else if (scheduler.contains("Later") || scheduler.contains("Delayed"))
				name = "DelayedTask";
			if (scheduler.contains("Async"))
				name = "Async" + name;
			String reason = scheduler.isEmpty() ? "Extends BukkitRunnable" :
					"Scheduled with " + scheduler + (element.related() != null ? " in " + element.related().display() : "");
			proposeClass(taskClass, name, scheduler.isEmpty() ? NamingClue.API_TYPE : NamingClue.BEHAVIOR, reason,
					scheduler.isEmpty() ? 45 : 55);
		}

		for (PluginElement element : index.ofKind(PluginElementKind.SERIALIZABLE))
			if (element.detail() != null)
				proposeClass(element.location().className(), NameHeuristics.toPascalCase(element.key()),
						NamingClue.STRING_CONSTANT, "@SerializableAs(\"" + element.key() + "\")", 85);
		for (PluginElement element : index.ofKind(PluginElementKind.PLACEHOLDER_EXPANSION)) {
			if (element.detail() != null)
				proposeClass(element.location().className(), NameHeuristics.toPascalCase(element.key()) + "Expansion",
						NamingClue.STRING_CONSTANT, "PlaceholderAPI expansion '" + element.key() + "'", 80);
			else if (!pluginPascal.isEmpty())
				proposeClass(element.location().className(), pluginPascal + "Expansion", NamingClue.API_TYPE,
						"PlaceholderAPI expansion of plugin '" + pluginName + "'", 60);
		}
		for (PluginElement element : index.ofKind(PluginElementKind.INVENTORY_HOLDER))
			proposeClass(element.location().className(), "MenuHolder", NamingClue.API_TYPE, "Implements InventoryHolder", 40);
		for (PluginElement element : index.ofKind(PluginElementKind.MESSAGE_LISTENER))
			proposeClass(element.location().className(), "MessageListener", NamingClue.API_TYPE,
					"Implements PluginMessageListener", 45);
	}

	private void proposeCommandClassNames(@Nonnull PluginElementKind kind, @Nonnull String suffix, int confidence) {
		MinecraftPluginManifest manifest = index.manifest();
		Map<String, Set<String>> commandsByClass = new TreeMap<>();
		for (PluginElement element : index.ofKind(kind)) {
			String handlerClass = classOfHandler(element.location(), "onCommand", "onTabComplete", "execute");
			if (handlerClass == null || mainClasses.contains(handlerClass))
				continue;
			Set<String> commands = commandsByClass.computeIfAbsent(handlerClass, k -> new LinkedHashSet<>());
			if (!element.key().isEmpty())
				commands.add(element.key());
		}
		commandsByClass.forEach((handlerClass, commands) -> {
			String what = kind == PluginElementKind.TAB_COMPLETER ? "Tab completer" : "Executor";
			if (commands.isEmpty()) {
				proposeClass(handlerClass, kind == PluginElementKind.TAB_COMPLETER ? "TabCompletionHandler" : "CommandHandler",
						NamingClue.API_TYPE, what + " of a command that could not be determined", 40);
				return;
			}
			String first = commands.iterator().next();
			String name = NameHeuristics.toPascalCase(first) + suffix;
			boolean declared = manifest != null && manifest.commands().stream().anyMatch(first::equalsIgnoreCase);
			NamingClue clue = declared ? NamingClue.MANIFEST : NamingClue.STRING_CONSTANT;
			String list = commands.stream().map(c -> '/' + c).collect(Collectors.joining(", "));
			String reason = what + " of " + list + (declared ? " (declared in manifest)" : "");
			proposeClass(handlerClass, name, clue, reason, commands.size() == 1 ? confidence : confidence - 30);
		});
	}

	private void proposeListenerClassNames() {
		Map<String, List<String>> eventsByListener = new TreeMap<>();
		for (PluginElement element : index.ofKind(PluginElementKind.LISTENER))
			eventsByListener.putIfAbsent(element.location().className(), new ArrayList<>());
		for (PluginElement handler : index.ofKind(PluginElementKind.EVENT_HANDLER))
			if (handler.related() != null)
				eventsByListener.computeIfAbsent(handler.location().className(), k -> new ArrayList<>())
						.add(handler.related().className());
		eventsByListener.forEach((listener, events) -> {
			if (events.isEmpty() || mainClasses.contains(listener))
				return;
			Set<String> eventNames = new LinkedHashSet<>();
			for (String event : events)
				eventNames.add(className(event));
			if (eventNames.size() == 1) {
				String event = eventNames.iterator().next();
				proposeClass(listener, NameHeuristics.stripEventSuffix(event) + "Listener", NamingClue.EVENT,
						"Handles " + event, 80);
				return;
			}

			// Name after the most common kind of event: 'org/bukkit/event/player/...' are player events.
			Map<String, Integer> categories = new HashMap<>();
			for (String event : new LinkedHashSet<>(events)) {
				String category = eventCategory(event);
				if (category != null)
					categories.merge(category, 1, Integer::sum);
			}
			int distinct = new LinkedHashSet<>(events).size();
			var top = categories.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
			if (top != null && top.getValue() * 2 > distinct) {
				String category = NameHeuristics.toPascalCase(top.getKey());
				boolean all = top.getValue() == distinct;
				proposeClass(listener, category + "Listener", NamingClue.EVENT,
						"Handles " + top.getValue() + " of " + distinct + " events in the '" + top.getKey() + "' category",
						all ? 75 : 55);
			} else {
				proposeClass(listener, "EventListener", NamingClue.EVENT, "Handles " + distinct + " unrelated events", 45);
			}
		});
	}

	/**
	 * @return Category of an API event from its package, such as {@code player} for {@code org/bukkit/event/player/PlayerJoinEvent}.
	 */
	@Nullable
	private static String eventCategory(@Nonnull String event) {
		int split = event.indexOf("/event/");
		if (split < 0)
			return null;
		String rest = event.substring(split + "/event/".length());
		int slash = rest.indexOf('/');
		return slash > 0 ? rest.substring(0, slash) : null;
	}

	/**
	 * @return The plugin class handling something, if the location is that class or one of the given methods in it.
	 */
	@Nullable
	private String classOfHandler(@Nonnull CodeLocation location, @Nonnull String... handlerMethods) {
		String owner = location.className();
		if (!classes.containsKey(owner))
			return null;
		if (location.isClass())
			return owner;
		for (String method : handlerMethods)
			if (method.equals(location.memberName()))
				return owner;
		return null; // A lambda, which does not say anything about its class.
	}

	private void proposeClass(@Nonnull String className, @Nonnull String name, @Nonnull NamingClue clue,
	                          @Nonnull String reason, int confidence) {
		if (!classes.containsKey(className) || !NameHeuristics.isValidIdentifier(name))
			return;
		Proposal existing = classProposals.get(className);
		if (existing == null || existing.confidence() < confidence)
			classProposals.put(className, new Proposal(name, clue, reason, confidence));
	}

	@Nonnull
	private List<NameSuggestion> assignClassNames() {
		// Outer classes sort before their inner classes, so the outer's new name is known when the inner is named.
		Set<String> takenNew = new HashSet<>();
		List<NameSuggestion> suggestions = new ArrayList<>();
		for (Map.Entry<String, Proposal> entry : new TreeMap<>(classProposals).entrySet()) {
			String className = entry.getKey();
			Proposal proposal = entry.getValue();
			ClassInfo info = classes.get(className);
			String prefix = classPrefix(info, finalClassNames);
			String simple = proposal.name();
			String candidate = prefix + simple;
			for (int i = 2; isClassNameTaken(candidate, className, takenNew); i++)
				candidate = prefix + simple + i;
			takenNew.add(candidate);
			finalClassNames.put(className, candidate);
			String chosen = candidate.substring(prefix.length());
			String current = NameHeuristics.simpleName(className);
			if (!chosen.equals(current))
				suggestions.add(proposal.toSuggestion(new ClassTarget(className), chosen, current));
		}
		return suggestions;
	}

	private boolean isClassNameTaken(@Nonnull String candidate, @Nonnull String self, @Nonnull Set<String> takenNew) {
		if (candidate.equals(self))
			return false;
		if (takenNew.contains(candidate))
			return true;
		// An existing class with the name is only a conflict if it is not itself being renamed away.
		return workspace.findClass(candidate) != null && !classProposals.containsKey(candidate);
	}

	/**
	 * @return Package and outer class part of the class's name after renaming, with a trailing separator.
	 */
	@Nonnull
	static String classPrefix(@Nonnull ClassInfo info, @Nonnull Map<String, String> renamedClasses) {
		String name = info.getName();
		String outer = info.getOuterClassName();
		if (outer != null && name.startsWith(outer + '$'))
			return renamedClasses.getOrDefault(outer, outer) + '$';
		int slash = name.lastIndexOf('/');
		return slash < 0 ? "" : name.substring(0, slash + 1);
	}

	/**
	 * @return Simple name the class will have after renaming.
	 */
	@Nonnull
	private String className(@Nonnull String internalName) {
		String renamed = finalClassNames.get(internalName);
		return NameHeuristics.simpleName(renamed != null ? renamed : internalName);
	}

	/**
	 * @return {@code true} when the class has, or will have, a name worth naming things after.
	 */
	private boolean hasReadableName(@Nonnull String internalName) {
		return finalClassNames.containsKey(internalName) ||
				!NameHeuristics.looksObfuscated(NameHeuristics.simpleName(internalName));
	}

	// ==================== Fields ==================== //

	private void proposeFieldNames() {
		NamingFacts facts = index.namingFacts();
		for (ValueFlow flow : facts.valueFlows()) {
			if (flow.target() != FlowTarget.FIELD || flow.field() == null)
				continue;
			CodeLocation field = flow.field();
			String base = keyBasedName(flow);
			if (base == null)
				continue;
			proposeField(field, base, NamingClue.STRING_CONSTANT, flowReason(flow), 80);
		}
		for (ConstantUse use : facts.constantUses()) {
			String prefix = switch (use.kind()) {
				case PERMISSION -> "PERMISSION";
				case CONFIG_KEY -> "PATH";
				case CHANNEL -> "CHANNEL";
				case NAMESPACED_KEY -> "KEY";
				case METADATA_KEY -> "METADATA";
				case COMMAND -> "COMMAND";
				case PLUGIN_HOOK -> "PLUGIN";
				default -> "VALUE";
			};
			String value = stripPluginPrefix(use.value());
			proposeField(use.field(), prefix + ' ' + value, NamingClue.STRING_CONSTANT,
					use.kind().displayName() + " constant \"" + use.value() + "\"", 75);
		}
		for (Accessor accessor : facts.accessors())
			if (accessor.kind() == AccessorKind.INSTANCE)
				proposeField(accessor.field(), "instance", NamingClue.ACCESSOR,
						"Singleton returned by " + accessor.method().display(), 85);

		for (JvmClassInfo cls : classes.values()) {
			for (FieldMember field : cls.getFields()) {
				if (field.hasSyntheticModifier() || field.hasEnumModifier())
					continue;
				CodeLocation location = CodeLocation.ofMember(cls.getName(), field.getName(), field.getDescriptor());
				TypeName typeName = typeName(Type.getType(field.getDescriptor()), field.getSignature());
				if (typeName != null)
					proposeField(location, typeName.name(), NamingClue.MEMBER_TYPE, typeName.reason(), typeName.confidence());
				if (field.hasStaticModifier() && field.getDescriptor().equals("L" + cls.getName() + ";"))
					proposeField(location, "instance", NamingClue.MEMBER_TYPE, "Static field holding its own class", 60);
			}
		}
	}

	@Nullable
	private static String keyBasedName(@Nonnull ValueFlow flow) {
		String segment = NameHeuristics.lastSegment(flow.key());
		if (NameHeuristics.toCamelCase(segment).isEmpty())
			return null;
		if (flow.source() == PluginElementKind.NAMESPACED_KEY)
			return segment + " key";
		if (flow.apiMethod().equals("getConfigurationSection") || flow.apiMethod().equals("getSection"))
			return segment + " section";
		return segment;
	}

	@Nonnull
	private static String flowReason(@Nonnull ValueFlow flow) {
		if (flow.source() == PluginElementKind.NAMESPACED_KEY)
			return "Holds namespaced key \"" + flow.key() + "\"";
		return "Value of config path '" + flow.key() + "' (" + flow.apiMethod() + ")";
	}

	@Nonnull
	private String stripPluginPrefix(@Nonnull String value) {
		String pluginName = index.pluginName();
		if (pluginName != null) {
			String prefix = pluginName.toLowerCase(Locale.ROOT) + '.';
			if (value.toLowerCase(Locale.ROOT).startsWith(prefix) && value.length() > prefix.length())
				return value.substring(prefix.length());
		}
		return value;
	}

	/**
	 * @param words
	 * 		Text to build the name from. Converted to camel case, or upper snake case for constants.
	 */
	private void proposeField(@Nonnull CodeLocation field, @Nonnull String words, @Nonnull NamingClue clue,
	                          @Nonnull String reason, int confidence) {
		JvmClassInfo owner = classes.get(field.className());
		if (owner == null || field.memberName() == null || field.memberDescriptor() == null)
			return;
		FieldMember member = owner.getDeclaredField(field.memberName(), field.memberDescriptor());
		if (member == null)
			return;
		boolean constant = member.hasStaticModifier() && member.hasFinalModifier();
		String name = constant ? NameHeuristics.toUpperSnakeCase(words) : NameHeuristics.toCamelCase(words);
		propose(new FieldTarget(field.className(), field.memberName(), field.memberDescriptor()), name, clue, reason, confidence);
	}

	@Nonnull
	private List<NameSuggestion> assignFieldNames() {
		List<NameSuggestion> suggestions = new ArrayList<>();
		Map<String, Set<String>> usedByClass = new HashMap<>();
		List<Map.Entry<NameTarget, Proposal>> fields = memberProposals.entrySet().stream()
				.filter(e -> e.getKey() instanceof FieldTarget)
				.sorted(Comparator.comparingInt((Map.Entry<NameTarget, Proposal> e) -> -e.getValue().confidence()))
				.toList();
		Set<FieldTarget> renamed = new HashSet<>();
		for (var entry : fields)
			renamed.add((FieldTarget) entry.getKey());
		for (var entry : fields) {
			FieldTarget target = (FieldTarget) entry.getKey();
			Set<String> used = usedByClass.computeIfAbsent(target.owner(), owner -> {
				// Names of fields that keep their name are taken.
				Set<String> names = new HashSet<>();
				for (FieldMember field : classes.get(owner).getFields())
					if (!renamed.contains(new FieldTarget(owner, field.getName(), field.getDescriptor())))
						names.add(field.getName());
				return names;
			});
			String name = deconflict(entry.getValue().name(), used, target.name());
			used.add(name);
			finalFieldNames.put(target.location(), name);
			if (!name.equals(target.name()))
				suggestions.add(entry.getValue().toSuggestion(target, name, target.name()));
		}
		return suggestions;
	}

	/**
	 * @return Name the field will have after renaming, or {@code null} if it has no readable name.
	 */
	@Nullable
	private String fieldName(@Nonnull CodeLocation field) {
		String renamed = finalFieldNames.get(field);
		if (renamed != null)
			return renamed;
		String current = field.memberName();
		return current != null && !NameHeuristics.looksObfuscated(current) ? current : null;
	}

	// ==================== Methods ==================== //

	@Nonnull
	private List<NameSuggestion> assignMethodNames() {
		NamingFacts facts = index.namingFacts();
		for (PluginElement handler : index.ofKind(PluginElementKind.EVENT_HANDLER)) {
			if (handler.related() == null)
				continue;
			String event = className(handler.related().className());
			String detail = handler.detail() != null ? " (" + handler.detail() + ")" : "";
			proposeMethod(handler.location(), "on" + NameHeuristics.stripEventSuffix(event), NamingClue.EVENT,
					"Event handler for " + event + detail, 90, true);
		}
		for (Accessor accessor : facts.accessors()) {
			String field = fieldName(accessor.field());
			if (accessor.kind() == AccessorKind.INSTANCE) {
				proposeMethod(accessor.method(), "getInstance", NamingClue.ACCESSOR,
						"Returns the singleton instance", 75, false);
				continue;
			}
			if (field == null)
				continue;
			String property = isUpperSnake(field) ? NameHeuristics.toPascalCase(field) : NameHeuristics.upperFirst(field);
			String name;
			if (accessor.kind() == AccessorKind.SETTER) {
				name = "set" + property;
			} else {
				boolean bool = "Z".equals(accessor.field().memberDescriptor());
				name = bool && field.startsWith("is") && field.length() > 2 && Character.isUpperCase(field.charAt(2)) ? field :
						(bool ? "is" : "get") + property;
			}
			proposeMethod(accessor.method(), name, NamingClue.ACCESSOR,
					(accessor.kind() == AccessorKind.SETTER ? "Sets " : "Returns ") + "field " + field, 70, false);
		}
		for (ValueFlow flow : facts.valueFlows()) {
			if (flow.target() != FlowTarget.RETURN)
				continue;
			String base = keyBasedName(flow);
			if (base == null)
				continue;
			boolean bool = Type.getReturnType(flow.method().memberDescriptor()).getSort() == Type.BOOLEAN;
			proposeMethod(flow.method(), (bool ? "is " : "get ") + base, NamingClue.STRING_CONSTANT,
					"Returns " + flowReason(flow).toLowerCase(Locale.ROOT), 65, false);
		}
		for (PluginElement element : index.elements()) {
			CodeLocation location = element.location();
			if (!location.isMethod() || element.key().isEmpty())
				continue;
			String name = switch (element.kind()) {
				case COMMAND_EXECUTOR -> "handle " + element.key() + " command";
				case TAB_COMPLETER -> "complete " + element.key() + " command";
				default -> null;
			};
			// Only lambdas, other executors are named for the API method they implement.
			if (name != null && classOfHandler(location, "onCommand", "onTabComplete", "execute") == null)
				proposeMethod(location, name, NamingClue.STRING_CONSTANT,
						element.kind().displayName() + " lambda for /" + element.key(), 60, false);
		}
		facts.behaviors().forEach((method, behaviors) -> {
			if (behaviors.size() == 1) {
				Behavior behavior = behaviors.iterator().next();
				proposeMethod(method, behavior.methodName(), NamingClue.BEHAVIOR, describe(behavior), 50, false);
			}
		});

		List<NameSuggestion> suggestions = new ArrayList<>();
		Map<String, Set<String>> usedByClass = new HashMap<>();
		List<Map.Entry<NameTarget, Proposal>> methods = memberProposals.entrySet().stream()
				.filter(e -> e.getKey() instanceof MethodTarget)
				.sorted(Comparator.comparingInt((Map.Entry<NameTarget, Proposal> e) -> -e.getValue().confidence()))
				.toList();
		for (var entry : methods) {
			MethodTarget target = (MethodTarget) entry.getKey();
			Set<String> used = usedByClass.computeIfAbsent(target.owner(), this::methodKeysInHierarchy);
			String desc = target.descriptor();
			String base = entry.getValue().name();
			String name = base;
			for (int i = 2; !name.equals(target.name()) && used.contains(name + desc); i++)
				name = base + i;
			used.add(name + desc);
			if (!name.equals(target.name()))
				suggestions.add(entry.getValue().toSuggestion(target, name, target.name()));
		}
		return suggestions;
	}

	@Nonnull
	private static String describe(@Nonnull Behavior behavior) {
		return switch (behavior) {
			case REGISTERS_LISTENERS -> "Registers event listeners";
			case REGISTERS_COMMANDS -> "Registers commands";
			case SCHEDULES_TASKS -> "Schedules tasks";
			case LOADS_CONFIG -> "Loads or saves the config";
			case HOOKS_ECONOMY -> "Looks up Vault's Economy service";
			case HOOKS_PLUGINS -> "Looks up other plugins or services";
			case SETS_UP_METRICS -> "Starts bStats metrics";
			case CONNECTS_DATABASE -> "Opens a database connection";
			case REGISTERS_CHANNELS -> "Registers plugin messaging channels";
		};
	}

	/**
	 * @param words
	 * 		Text to build the name from. Converted to camel case.
	 * @param allowReadable
	 * 		{@code true} to also suggest a name when the method already has a readable name. Otherwise readable names
	 * 		are kept, since they are likely names the server API calls by name.
	 */
	private void proposeMethod(@Nonnull CodeLocation method, @Nonnull String words, @Nonnull NamingClue clue,
	                           @Nonnull String reason, int confidence, boolean allowReadable) {
		JvmClassInfo owner = classes.get(method.className());
		String name = method.memberName();
		String desc = method.memberDescriptor();
		if (owner == null || name == null || desc == null || name.startsWith("<"))
			return;
		MethodMember member = owner.getDeclaredMethod(name, desc);
		if (member == null || member.hasBridgeModifier())
			return;
		if (!allowReadable && !member.hasSyntheticModifier() && !NameHeuristics.looksObfuscated(name))
			return;
		if (isLibraryMethod(method.className(), name, desc))
			return;
		propose(new MethodTarget(method.className(), name, desc), NameHeuristics.toCamelCase(words), clue, reason, confidence);
	}

	private boolean isLibraryMethod(@Nonnull String owner, @Nonnull String name, @Nonnull String desc) {
		InheritanceVertex vertex = graph.getVertex(owner);
		return vertex != null && vertex.isLibraryMethod(name, desc);
	}

	/**
	 * @return Name and descriptor pairs of methods declared in the class and its parents and children.
	 */
	@Nonnull
	private Set<String> methodKeysInHierarchy(@Nonnull String owner) {
		Set<String> keys = new HashSet<>();
		InheritanceVertex vertex = graph.getVertex(owner);
		List<ClassInfo> infos = new ArrayList<>();
		if (vertex != null) {
			infos.add(vertex.getValue());
			vertex.allParents().forEach(v -> infos.add(v.getValue()));
			vertex.allChildren().forEach(v -> infos.add(v.getValue()));
		} else if (classes.containsKey(owner)) {
			infos.add(classes.get(owner));
		}
		for (ClassInfo info : infos)
			for (MethodMember method : info.getMethods())
				keys.add(method.getName() + method.getDescriptor());
		return keys;
	}

	// ==================== Variables ==================== //

	@Nonnull
	private List<NameSuggestion> assignVariableNames() {
		Map<CodeLocation, Map<Integer, ValueFlow>> localFlows = new HashMap<>();
		for (ValueFlow flow : index.namingFacts().valueFlows())
			if (flow.target() == FlowTarget.LOCAL)
				localFlows.computeIfAbsent(flow.method(), k -> new HashMap<>()).putIfAbsent(flow.localIndex(), flow);
		Set<CodeLocation> handlers = new HashSet<>();
		for (PluginElement handler : index.ofKind(PluginElementKind.EVENT_HANDLER))
			handlers.add(handler.location());

		List<NameSuggestion> suggestions = new ArrayList<>();
		for (JvmClassInfo cls : classes.values()) {
			for (MethodMember method : cls.getMethods()) {
				List<LocalVariable> variables = method.getLocalVariables();
				if (variables.isEmpty())
					continue;
				CodeLocation methodLocation = CodeLocation.ofMember(cls.getName(), method.getName(), method.getDescriptor());
				boolean isStatic = method.hasStaticModifier();
				List<String> callbackNames = CALLBACK_PARAMETERS.get(method.getName() + method.getDescriptor());
				Map<Integer, Integer> parameterSlots = parameterSlots(method.getDescriptor(), isStatic);
				Map<Integer, ValueFlow> flows = localFlows.getOrDefault(methodLocation, Map.of());

				Map<LocalVariable, Proposal> proposals = new LinkedHashMap<>();
				for (LocalVariable variable : variables) {
					if (!isStatic && variable.getIndex() == 0)
						continue; // 'this'
					Integer parameter = parameterSlots.get(variable.getIndex());
					Proposal proposal = null;
					if (parameter != null && callbackNames != null && parameter < callbackNames.size())
						proposal = new Proposal(callbackNames.get(parameter), NamingClue.API_TYPE,
								"Parameter of API method " + method.getName(), 85);
					else if (parameter != null && parameter == 0 && handlers.contains(methodLocation))
						proposal = new Proposal("event", NamingClue.EVENT, "Event handler parameter", 85);
					else if (flows.containsKey(variable.getIndex()) && parameter == null) {
						ValueFlow flow = flows.get(variable.getIndex());
						String base = keyBasedName(flow);
						if (base != null)
							proposal = new Proposal(NameHeuristics.toCamelCase(base), NamingClue.STRING_CONSTANT, flowReason(flow), 70);
					}
					if (proposal == null) {
						TypeName typeName = typeName(Type.getType(variable.getDescriptor()), variable.getSignature());
						if (typeName != null)
							proposal = new Proposal(NameHeuristics.toCamelCase(typeName.name()), NamingClue.MEMBER_TYPE,
									typeName.reason(), typeName.confidence() - 10);
					}
					if (proposal != null && NameHeuristics.isValidIdentifier(proposal.name()))
						proposals.put(variable, proposal);
				}

				Set<String> used = new HashSet<>();
				for (LocalVariable variable : variables)
					if (!proposals.containsKey(variable))
						used.add(variable.getName());
				proposals.forEach((variable, proposal) -> {
					String name = deconflict(proposal.name(), used, variable.getName());
					used.add(name);
					if (!name.equals(variable.getName()))
						suggestions.add(proposal.toSuggestion(new VariableTarget(cls.getName(), method.getName(),
								method.getDescriptor(), variable.getName(), variable.getDescriptor(), variable.getIndex()),
								name, variable.getName()));
				});
			}
		}
		return suggestions;
	}

	/**
	 * @return Map of variable slot to parameter index.
	 */
	@Nonnull
	private static Map<Integer, Integer> parameterSlots(@Nonnull String methodDesc, boolean isStatic) {
		Map<Integer, Integer> slots = new HashMap<>();
		int slot = isStatic ? 0 : 1;
		Type[] args = Type.getArgumentTypes(methodDesc);
		for (int i = 0; i < args.length; i++) {
			slots.put(slot, i);
			slot += args[i].getSize();
		}
		return slots;
	}

	// ==================== Shared ==================== //

	/**
	 * @return Name for a value of the type, or {@code null} if the type says nothing useful.
	 */
	@Nullable
	private TypeName typeName(@Nonnull Type type, @Nullable String signature) {
		boolean array = type.getSort() == Type.ARRAY;
		if (array)
			type = type.getElementType();
		if (type.getSort() != Type.OBJECT)
			return null;
		String internal = type.getInternalName();
		String simple = NameHeuristics.simpleName(internal);
		TypeName name;
		if (mainClasses.contains(internal)) {
			name = new TypeName("plugin", "Holds the main plugin class", 80);
		} else if (TYPE_NAMES.containsKey(internal)) {
			name = new TypeName(TYPE_NAMES.get(internal), "Type " + simple, 70);
		} else if (classes.containsKey(internal) && hasReadableName(internal)) {
			String renamed = className(internal);
			name = new TypeName(NameHeuristics.lowerFirst(renamed), "Type " + renamed, 60);
		} else if (!array && signature != null && (COLLECTION_TYPES.contains(internal) || MAP_TYPES.contains(internal))) {
			name = genericCollectionName(internal, signature);
		} else if (simple.endsWith("Event") && isReadableTypePackage(internal)) {
			name = new TypeName("event", "Type " + simple, 55);
		} else if (isReadableTypePackage(internal) && !NameHeuristics.looksObfuscated(simple) &&
				!internal.startsWith("java/lang/")) {
			name = new TypeName(NameHeuristics.lowerFirst(simple), "Type " + simple, 50);
		} else {
			return null;
		}
		if (name == null)
			return null;
		return array ? new TypeName(NameHeuristics.plural(name.name()), name.reason() + "[]", name.confidence()) : name;
	}

	@Nullable
	private TypeName genericCollectionName(@Nonnull String internal, @Nonnull String signature) {
		// 'Ljava/util/Map<Ljava/util/UUID;Lcom/example/Home;>;' --> [java/util/UUID, com/example/Home]
		int open = signature.indexOf('<');
		int close = signature.lastIndexOf('>');
		if (open < 0 || close < open)
			return null;
		List<String> arguments = new ArrayList<>();
		String inner = signature.substring(open + 1, close);
		int depth = 0;
		int start = -1;
		for (int i = 0; i < inner.length(); i++) {
			char c = inner.charAt(i);
			if (c == '<') depth++;
			else if (c == '>') depth--;
			else if (depth == 0 && c == 'L' && start < 0) start = i + 1;
			else if (depth == 0 && c == ';' && start >= 0) {
				String argument = inner.substring(start, i);
				int generic = argument.indexOf('<');
				arguments.add(generic >= 0 ? argument.substring(0, generic) : argument);
				start = -1;
			}
		}
		if (COLLECTION_TYPES.contains(internal) && arguments.size() == 1) {
			TypeName element = typeName(Type.getObjectType(arguments.getFirst()), null);
			if (element != null)
				return new TypeName(NameHeuristics.plural(element.name()), "Collection of " +
						NameHeuristics.simpleName(arguments.getFirst()), element.confidence() - 15);
		} else if (MAP_TYPES.contains(internal) && arguments.size() == 2) {
			TypeName value = typeName(Type.getObjectType(arguments.get(1)), null);
			TypeName key = typeName(Type.getObjectType(arguments.get(0)), null);
			String reason = "Map of " + NameHeuristics.simpleName(arguments.get(0)) + " to " + NameHeuristics.simpleName(arguments.get(1));
			if (value != null)
				return new TypeName(value.name() + "Map", reason, value.confidence() - 25);
			if (key != null)
				return new TypeName(key.name() + "Map", reason, key.confidence() - 30);
		}
		return null;
	}

	private static boolean isReadableTypePackage(@Nonnull String internal) {
		for (String prefix : READABLE_TYPE_PACKAGES)
			if (internal.startsWith(prefix))
				return true;
		return false;
	}

	private static boolean isUpperSnake(@Nonnull String name) {
		return name.equals(name.toUpperCase(Locale.ROOT)) && name.chars().anyMatch(Character::isLetter);
	}

	private void propose(@Nonnull NameTarget target, @Nonnull String name, @Nonnull NamingClue clue,
	                     @Nonnull String reason, int confidence) {
		if (!NameHeuristics.isValidIdentifier(name))
			return;
		Proposal existing = memberProposals.get(target);
		if (existing == null || existing.confidence() < confidence)
			memberProposals.put(target, new Proposal(name, clue, reason, confidence));
	}

	@Nonnull
	private static String deconflict(@Nonnull String base, @Nonnull Set<String> used, @Nonnull String current) {
		String name = base;
		boolean snake = isUpperSnake(base);
		for (int i = 2; !name.equals(current) && used.contains(name); i++)
			name = snake ? base + '_' + i : base + i;
		return name;
	}

	private record TypeName(@Nonnull String name, @Nonnull String reason, int confidence) {}

	private record Proposal(@Nonnull String name, @Nonnull NamingClue clue, @Nonnull String reason, int confidence) {
		@Nonnull
		NameSuggestion toSuggestion(@Nonnull NameTarget target, @Nonnull String chosenName, @Nonnull String currentName) {
			return new NameSuggestion(target, chosenName, clue, reason, Math.clamp(confidence, 0, 100),
					NameHeuristics.looksObfuscated(currentName));
		}
	}
}
