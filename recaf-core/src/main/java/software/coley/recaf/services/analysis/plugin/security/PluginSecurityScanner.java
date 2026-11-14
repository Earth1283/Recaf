package software.coley.recaf.services.analysis.plugin.security;

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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.analysis.plugin.security.ResourcePayloadScanner.Match;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.analysis.plugin.semantic.MethodFlow;
import software.coley.recaf.services.analysis.plugin.semantic.PluginIndexer;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static software.coley.recaf.services.analysis.plugin.security.SecurityRule.*;

/**
 * Reads the bytecode of a plugin for behavior worth a closer look before running it on a server: running programs,
 * loading code that is not in the jar, contacting unusual hosts, granting operator, touching other plugins' jars,
 * and so on.
 * <p>
 * Many of these have legitimate uses, so single uses are reported at the severity of the API alone. What raises a
 * finding to {@link Severity#CRITICAL} is a combination that is rarely legitimate, such as defining classes from
 * decoded bytes, or a chat handler that can grant operator. Combinations are looked for within one class
 * <i>(including its nested classes)</i>, and for chat handlers, in what they call.
 * <p>
 * This is static triage. Behavior that is hidden behind reflection with encrypted names, or that is downloaded
 * later, is not visible here, so a report with no findings is not proof that a plugin is safe.
 */
public final class PluginSecurityScanner implements Opcodes {
	private static final Logger logger = Logging.get(PluginSecurityScanner.class);
	private static final int MAX_REACH_DEPTH = 3;
	private static final int MAX_EVIDENCE = 25;
	private static final int STRING_ENCRYPTION_CLASSES = 20;
	private static final int MAX_DETAIL_LENGTH = 160;
	private static final String GET_PARENT_OF_DATA = "getDataFolder().getParentFile()";
	private static final Set<String> HANDLER_ANNOTATIONS = Set.of(
			"Lorg/bukkit/event/EventHandler;",
			"Lnet/md_5/bungee/event/EventHandler;",
			"Lcom/velocitypowered/api/event/Subscribe;");
	private static final Set<String> CHAT_EVENTS = Set.of(
			"org/bukkit/event/player/AsyncPlayerChatEvent",
			"org/bukkit/event/player/PlayerChatEvent",
			"org/bukkit/event/player/PlayerCommandPreprocessEvent",
			"io/papermc/paper/event/player/AsyncChatEvent",
			"io/papermc/paper/event/player/ChatEvent",
			"net/md_5/bungee/api/event/ChatEvent",
			"com/velocitypowered/api/event/player/PlayerChatEvent",
			"com/velocitypowered/api/event/command/CommandExecuteEvent");
	/** What a backdoor does once triggered. */
	private static final Set<SecurityRule> BACKDOOR_ACTIONS = EnumSet.of(SET_OP, PROCESS_EXEC, PRIVILEGE_COMMAND,
			WILDCARD_PERMISSION, DEFINE_CLASS, SCRIPT_ENGINE, NATIVE_LIBRARY);
	private static final Set<String> CLASS_LOADER_TYPES = Set.of(
			"java/lang/ClassLoader", "java/security/SecureClassLoader", "java/net/URLClassLoader");
	private static final Set<String> DEFINE_CLASS_NAMES = Set.of("defineClass", "defineClass0", "defineClass1",
			"defineClass2", "defineHiddenClass", "defineHiddenClassWithClassData", "defineAnonymousClass");
	private static final Set<String> PLAYER_IDENTITY_CALLS = Set.of("getName", "getUniqueId", "getDisplayName",
			"getPlayerListName", "getUsername");
	private static final Set<String> PLAYER_TYPES = Set.of("org/bukkit/entity/Player", "org/bukkit/OfflinePlayer",
			"org/bukkit/entity/HumanEntity", "org/bukkit/command/CommandSender", "org/bukkit/profile/PlayerProfile",
			"com/destroystokyo/paper/profile/PlayerProfile", "net/md_5/bungee/api/connection/ProxiedPlayer",
			"net/md_5/bungee/api/CommandSender", "net/md_5/bungee/api/connection/PendingConnection",
			"com/velocitypowered/api/proxy/Player", "com/velocitypowered/api/util/GameProfile");
	private static final Set<String> SERVER_TYPES = Set.of("org/bukkit/Bukkit", "org/bukkit/Server",
			"net/md_5/bungee/api/ProxyServer", "com/velocitypowered/api/proxy/ProxyServer");
	private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?|wss?|ftp)://([^/\\s\"'?#:@]+)(?::\\d+)?[^\\s\"'<>]*");
	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
	private static final Pattern PRIVILEGE_COMMAND_PATTERN = Pattern.compile(
			"^/?(?:minecraft:)?(?:op|lp|luckperms|pex|permissionsex|manuadd|manuaddp|mangaddp|perm|perms)(?:\\s|$)",
			Pattern.CASE_INSENSITIVE);
	private static final List<String> SENSITIVE_STRINGS = List.of("server.properties", "ops.json", "whitelist.json",
			"banned-players.json", "banned-ips.json", "usercache.json", "/etc/passwd", "/etc/shadow", ".ssh/",
			"id_rsa", "Local Storage/leveldb", "Login Data", "launcher_accounts.json", "launcher_profiles.json",
			"wallet.dat");
	/** Package parts that plugins commonly relocate bundled libraries under, in addition to those the indexer knows. */
	private static final List<String> LIBRARY_MARKERS = List.of("/depend/", "/dependencies/", "/relocated/",
			"/mongodb/", "/sqlite/", "/mysql/", "/mariadb/", "/postgresql/", "com/sun/jna/", "META-INF/native/", "org/jline/",
			"org/fusesource/", "net/jpountz/", "io/netty/");
	/** What bytes become when loaded, by the kind of file they were found in. */
	private static final Set<SecurityRule> CODE_LOADING = EnumSet.of(DEFINE_CLASS, CLASS_LOADER, INSTRUMENTATION);
	private static final Set<SecurityRule> NATIVE_LOADING = EnumSet.of(NATIVE_LIBRARY, INSTRUMENTATION);
	private static final List<Pattern> SUSPICIOUS_URLS = List.of(
			Pattern.compile("discord(?:app)?\\.com/api/webhooks/\\d{5,}/[\\w-]{10,}"),
			Pattern.compile("api\\.telegram\\.org/bot\\d+:[\\w-]+"),
			Pattern.compile("(?:pastebin\\.com|hastebin\\.com)/raw/\\w+"),
			Pattern.compile("paste\\.ee/r/\\w+"),
			Pattern.compile("transfer\\.sh/\\w+"),
			Pattern.compile("//[^/]*(?:ngrok\\.io|ngrok-free\\.app|trycloudflare\\.com|workers\\.dev)\\b"));
	private static final Comparator<SecurityFinding> ORDER = Comparator
			.comparing(SecurityFinding::severity)
			.thenComparing(SecurityFinding::library)
			.thenComparing(SecurityFinding::rule)
			.thenComparing(SecurityFinding::location, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(f -> Objects.requireNonNullElse(f.file(), ""))
			.thenComparing(f -> Objects.requireNonNullElse(f.detail(), ""));

	private final Map<String, ClassNode> classes = new TreeMap<>();
	private final Map<String, String> constantStrings = new HashMap<>();
	private final Map<String, String> embeddedIn = new HashMap<>();
	private final Set<CodeLocation> bStatsMethods = new HashSet<>();
	private final Set<String> generatorClasses = new HashSet<>();
	private final Map<CodeLocation, Set<SecurityFinding>> direct = new LinkedHashMap<>();
	private final Map<CodeLocation, Set<CodeLocation>> calls = new HashMap<>();
	private final List<CodeLocation> chatHandlers = new ArrayList<>();
	private final List<FlaggedFile> flaggedFiles = new ArrayList<>();
	private final Map<String, List<FlaggedFile>> flaggedFilesByName = new HashMap<>();
	private final Map<FlaggedFile, Set<CodeLocation>> fileReferences = new HashMap<>();
	private int filesScanned;

	private PluginSecurityScanner() {}

	/**
	 * @param workspace
	 * 		Workspace containing the plugin as its primary resource.
	 * @param skipLibraries
	 *        {@code true} to not read classes in packages of libraries that plugins commonly bundle.
	 *
	 * @return Findings in the primary resource.
	 */
	@Nonnull
	public static PluginSecurityReport scan(@Nonnull Workspace workspace, boolean skipLibraries) {
		return new PluginSecurityScanner().run(workspace.getPrimaryResource(), skipLibraries);
	}

	@Nonnull
	private PluginSecurityReport run(@Nonnull WorkspaceResource primary, boolean skipLibraries) {
		// Plugins can keep their real code in a jar inside the jar and load it at runtime, so those are read too.
		List<String> embeddedPaths = new ArrayList<>();
		Deque<Map.Entry<String, WorkspaceResource>> resources = new ArrayDeque<>();
		resources.add(Map.entry("", primary));
		while (!resources.isEmpty()) {
			Map.Entry<String, WorkspaceResource> entry = resources.poll();
			String path = entry.getKey();
			WorkspaceResource resource = entry.getValue();
			if (!path.isEmpty())
				embeddedPaths.add(path);
			readClasses(resource.getJvmClassBundle().values(), path.isEmpty() ? null : path, skipLibraries);
			readFiles(resource, path.isEmpty() ? null : path, skipLibraries);
			resource.getEmbeddedResources().forEach((name, embedded) ->
					resources.add(Map.entry(path.isEmpty() ? name : path + " > " + name, embedded)));
		}
		for (FlaggedFile file : flaggedFiles)
			for (String name : file.referenceNames())
				flaggedFilesByName.computeIfAbsent(name, k -> new ArrayList<>()).add(file);
		for (ClassNode node : classes.values())
			collectConstants(node);
		for (ClassNode node : classes.values()) {
			try {
				scanClass(node);
			} catch (Throwable t) {
				logger.debug("Failed to scan class '{}' for security triage", node.name, t);
			}
		}

		// Classes defined from bytecode the plugin generates itself are not hidden code.
		direct.forEach((method, findings) -> {
			if (!generatorClasses.contains(outerClass(method.className())))
				return;
			List<SecurityFinding> defines = findings.stream().filter(f -> f.rule() == DEFINE_CLASS).toList();
			defines.forEach(findings::remove);
			defines.forEach(f -> findings.add(finding(GENERATED_CLASS, f.location(), f.detail(), List.of())));
		});

		// bStats keeps its own config in 'plugins/bStats', which it finds from the plugin's data folder.
		for (CodeLocation method : bStatsMethods)
			direct.getOrDefault(method, new HashSet<>()).removeIf(f -> f.rule() == PLUGINS_FOLDER && GET_PARENT_OF_DATA.equals(f.detail()));
		Set<SecurityFinding> all = new LinkedHashSet<>();
		direct.values().forEach(all::addAll);
		List<SecurityFinding> classCombinations = combineWithinClasses();
		all.addAll(classCombinations);
		all.addAll(combineAcrossClasses(classCombinations));
		all.addAll(findBackdoors());
		all.addAll(fileFindings());
		collapseStringEncryption(all);
		List<SecurityFinding> sorted = new ArrayList<>(all);
		sorted.sort(ORDER);
		return new PluginSecurityReport(List.copyOf(sorted), classes.size(), filesScanned, List.copyOf(embeddedPaths));
	}

	private void readClasses(@Nonnull Collection<JvmClassInfo> infos, @Nullable String embeddedPath, boolean skipLibraries) {
		for (JvmClassInfo info : infos) {
			if ((skipLibraries && isLibrary(info.getName())) || classes.containsKey(info.getName()))
				continue;
			try {
				ClassNode node = new ClassNode();
				info.getClassReader().accept(node, ClassReader.SKIP_FRAMES);
				classes.put(node.name, node);
				if (embeddedPath != null)
					embeddedIn.put(node.name, embeddedPath);
			} catch (Throwable t) {
				logger.debug("Skipping unreadable class '{}' in security scan", info.getName(), t);
			}
		}
	}

	/**
	 * Checks the content of the files in a resource that are not classes, and the classes and archives the importer
	 * found under names that do not say what they are.
	 */
	private void readFiles(@Nonnull WorkspaceResource resource, @Nullable String embeddedPath, boolean skipLibraries) {
		for (JvmClassInfo info : resource.getJvmClassBundle().values()) {
			String path = ResourcePayloadScanner.originalPath(info);
			if (!path.endsWith(".class"))
				flag(path, embeddedPath, skipLibraries, ResourcePayloadScanner.inspect(path, info.getBytecode()));
		}
		for (FileInfo file : resource.getFileBundle().values()) {
			String path = file.getName();
			if (skipLibraries && isLibrary(path))
				continue;
			try {
				byte[] content = file.getRawContent();
				filesScanned++;
				flag(path, embeddedPath, skipLibraries, ResourcePayloadScanner.inspect(path, content));
			} catch (Throwable t) {
				logger.debug("Skipping unreadable file '{}' in security scan", path, t);
			}
		}
		for (String path : resource.getEmbeddedResources().keySet()) {
			if (skipLibraries && isLibrary(path))
				continue;
			filesScanned++;
			flag(path, embeddedPath, skipLibraries, ResourcePayloadScanner.inspectArchive(path));
		}
	}

	private void flag(@Nonnull String path, @Nullable String embeddedPath, boolean skipLibraries, @Nullable Match match) {
		if (match == null)
			return;
		boolean library = isLibrary(path);
		if (!(skipLibraries && library))
			flaggedFiles.add(new FlaggedFile(path, embeddedPath, match, library));
	}

	private void collectConstants(@Nonnull ClassNode node) {
		for (FieldNode field : node.fields)
			if ((field.access & ACC_STATIC) != 0 && field.value instanceof String value)
				constantStrings.put(node.name + '.' + field.name, value);
		for (MethodNode method : node.methods) {
			if (!"<clinit>".equals(method.name))
				continue;
			for (AbstractInsnNode insn : method.instructions)
				if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && put.owner.equals(node.name) &&
						insn.getPrevious() instanceof LdcInsnNode ldc && ldc.cst instanceof String value)
					constantStrings.putIfAbsent(node.name + '.' + put.name, value);
		}
	}

	private void scanClass(@Nonnull ClassNode node) {
		CodeLocation classLocation = CodeLocation.ofClass(node.name);
		if (node.superName != null && CLASS_LOADER_TYPES.contains(node.superName))
			add(CLASS_LOADER, classLocation, "extends " + simpleName(node.superName));
		for (FieldNode field : node.fields)
			if (field.value instanceof String value)
				scanString(value, CodeLocation.ofMember(node.name, field.name, field.desc));

		for (MethodNode method : node.methods) {
			CodeLocation here = CodeLocation.ofMember(node.name, method.name, method.desc);
			if (isChatHandler(method))
				chatHandlers.add(here);
			Lazy flow = new Lazy(() -> MethodFlow.analyze(node.name, method));
			boolean comparesPlayers = callsPlayerIdentity(method);
			for (AbstractInsnNode insn : method.instructions) {
				switch (insn) {
					case MethodInsnNode call -> {
						if (isBytecodeGenerator(call.owner))
							generatorClasses.add(outerClass(node.name));
						handleCall(here, call, flow, comparesPlayers);
						if (classes.containsKey(call.owner) && declares(call.owner, call.name, call.desc))
							link(here, CodeLocation.ofMember(call.owner, call.name, call.desc));
					}
					case TypeInsnNode type when type.getOpcode() == NEW -> {
						handleNew(here, type.desc);
						if (classes.containsKey(type.desc) && declares(type.desc, "run", "()V"))
							link(here, CodeLocation.ofMember(type.desc, "run", "()V"));
					}
					case LdcInsnNode ldc when ldc.cst instanceof String text -> scanString(text, here);
					case InvokeDynamicInsnNode indy -> {
						CodeLocation lambda = MethodFlow.lambdaImplementation(indy);
						if (lambda != null && classes.containsKey(lambda.className()))
							link(here, lambda);
						String recipe = concatRecipe(indy);
						if (recipe != null)
							scanString(recipe, here);
					}
					default -> {}
				}
			}
		}
	}

	private void handleCall(@Nonnull CodeLocation here, @Nonnull MethodInsnNode call, @Nonnull Lazy flow,
	                        boolean comparesPlayers) {
		String owner = call.owner;
		String name = call.name;
		String desc = call.desc;
		String api = simpleName(owner) + '.' + name;
		switch (owner) {
			case "java/lang/Runtime" -> {
				switch (name) {
					case "exec" -> add(PROCESS_EXEC, here, api);
					case "load", "loadLibrary" -> add(NATIVE_LIBRARY, here, api);
					case "exit", "halt" -> add(SHUTDOWN, here, api);
					default -> {}
				}
			}
			case "java/lang/ProcessBuilder" -> {
				if (name.equals("start") || name.equals("startPipeline"))
					add(PROCESS_EXEC, here, api);
			}
			case "java/lang/System" -> {
				switch (name) {
					case "load", "loadLibrary" -> add(NATIVE_LIBRARY, here, api);
					case "exit" -> add(SHUTDOWN, here, api);
					case "getenv" -> add(ENVIRONMENT, here, api);
					default -> {}
				}
			}
			case "java/net/URLClassLoader" -> {
				if (name.equals("newInstance"))
					add(CLASS_LOADER, here, api);
			}
			case "java/net/URL" -> {
				if (name.equals("openConnection") || name.equals("openStream") || name.equals("getContent"))
					add(SecurityRule.NETWORK, here, api);
			}
			case "java/net/http/HttpClient" -> {
				if (name.equals("send") || name.equals("sendAsync"))
					add(SecurityRule.NETWORK, here, api);
			}
			case "java/net/Socket", "java/nio/channels/SocketChannel", "java/nio/channels/AsynchronousSocketChannel" -> {
				if (name.equals("connect") || (name.equals("open") && Type.getArgumentCount(desc) > 0))
					add(SecurityRule.NETWORK, here, api);
			}
			case "java/net/DatagramSocket", "java/nio/channels/DatagramChannel" -> {
				if (name.equals("send"))
					add(SecurityRule.NETWORK, here, api);
			}
			case "java/nio/channels/ServerSocketChannel", "java/nio/channels/AsynchronousServerSocketChannel" -> {
				if (name.equals("bind"))
					add(LISTEN_SOCKET, here, api);
			}
			case "com/sun/net/httpserver/HttpServer" -> {
				if (name.equals("create"))
					add(LISTEN_SOCKET, here, api);
			}
			case "java/nio/file/Files" -> {
				switch (name) {
					case "write", "writeString", "newOutputStream", "newBufferedWriter", "copy", "move", "createFile" ->
							add(FILE_WRITE, here, api);
					case "delete", "deleteIfExists" -> add(FILE_DELETE, here, api);
					default -> {}
				}
			}
			case "java/io/File" -> {
				if (name.equals("delete") || name.equals("deleteOnExit"))
					add(FILE_DELETE, here, api);
				else if (name.equals("getParentFile") && flow.get() != null &&
						flow.get().receiver(call) instanceof MethodInsnNode source && source.name.equals("getDataFolder"))
					add(PLUGINS_FOLDER, here, GET_PARENT_OF_DATA);
			}
			case "java/nio/file/FileSystems" -> {
				if (name.equals("newFileSystem"))
					add(ARCHIVE_WRITE, here, api);
			}
			case "java/util/Base64$Decoder" -> {
				if (name.equals("decode"))
					add(DECODE, here, "Base64.Decoder.decode");
			}
			case "javax/xml/bind/DatatypeConverter" -> {
				if (name.equals("parseBase64Binary"))
					add(DECODE, here, api);
			}
			case "javax/crypto/Cipher" -> {
				if (name.equals("init") && flow.get() != null) {
					Integer mode = intConstant(flow.get().argument(call, 0));
					if (mode == null || mode == 2)
						add(DECRYPT, here, mode == null ? "Cipher.init" : "Cipher.init(DECRYPT_MODE)");
				}
			}
			case "java/lang/Class" -> {
				if (name.equals("forName") && flow.get() != null && constantText(flow.get().argument(call, 0)) == null)
					add(COMPUTED_CLASS_NAME, here, api);
			}
			case "java/lang/ClassLoader" -> {
				if (name.equals("loadClass") && flow.get() != null && constantText(flow.get().argument(call, 0)) == null)
					add(COMPUTED_CLASS_NAME, here, api);
			}
			case "java/util/UUID", "java/lang/String", "java/lang/Object" -> {
				if (comparesPlayers && (name.equals("equals") || name.equals("equalsIgnoreCase") || name.equals("contentEquals")))
					checkIdentityComparison(here, call, flow);
			}
			default -> {}
		}

		if (owner.equals("sun/misc/Unsafe") || owner.equals("jdk/internal/misc/Unsafe"))
			add(UNSAFE, here, "Unsafe");
		if (owner.startsWith("java/lang/reflect/") && (name.equals("setAccessible") || name.equals("trySetAccessible"))) {
			Type[] args = Type.getArgumentTypes(desc);
			int flag = args.length - 1;
			if (flag < 0 || flow.get() == null || !isFalse(flow.get().argument(call, flag)))
				add(REFLECTION, here, api);
		}
		if (DEFINE_CLASS_NAMES.contains(name) && !DEFINE_CLASS_NAMES.contains(here.memberName())) {
			Type ret = Type.getReturnType(desc);
			if (ret.getSort() == Type.OBJECT &&
					(ret.getInternalName().equals("java/lang/Class") || ret.getInternalName().equals("java/lang/invoke/MethodHandles$Lookup")))
				add(DEFINE_CLASS, here, api);
		}
		if (name.equals("addURL") && desc.equals("(Ljava/net/URL;)V"))
			add(CLASS_LOADER, here, api);
		if ((owner.startsWith("javax/script/") && (name.equals("eval") || name.equals("compile"))) ||
				(owner.equals("groovy/lang/GroovyShell") && (name.equals("evaluate") || name.equals("parse"))) ||
				(owner.equals("org/mozilla/javascript/Context") && name.equals("evaluateString")))
			add(SCRIPT_ENGINE, here, api);
		if ((owner.equals("java/lang/instrument/Instrumentation") && Set.of("redefineClasses", "retransformClasses",
				"addTransformer", "appendToSystemClassLoaderSearch", "appendToBootstrapClassLoaderSearch").contains(name)) ||
				(owner.equals("com/sun/tools/attach/VirtualMachine") && Set.of("attach", "loadAgent", "loadAgentPath",
						"loadAgentLibrary").contains(name)) ||
				(owner.endsWith("/ByteBuddyAgent") && name.equals("install")))
			add(INSTRUMENTATION, here, api);
		if (name.equals("getPluginsFolder") || name.equals("getUpdateFolderFile"))
			add(PLUGINS_FOLDER, here, api);
		if (SERVER_TYPES.contains(owner) && (name.equals("shutdown") || name.equals("stop")))
			add(SHUTDOWN, here, api);

		// Bukkit and BungeeCord API calls. Matched by name and descriptor, since the owner is often a subtype.
		if (name.equals("setOp") && desc.equals("(Z)V")) {
			// A sender wrapper's own setOp forwarding the call is not a use of it.
			if (!name.equals(here.memberName()) && (flow.get() == null || !isFalse(flow.get().argument(call, 0))))
				add(SET_OP, here, api);
		} else if ((name.equals("dispatchCommand") && Type.getArgumentCount(desc) == 2 &&
				Type.getReturnType(desc).getSort() == Type.BOOLEAN) ||
				(owner.equals("com/velocitypowered/api/command/CommandManager") &&
						(name.equals("executeAsync") || name.equals("executeImmediatelyAsync")))) {
			String command = flow.get() == null ? null : constantText(flow.get().argument(call, 1));
			if (command != null && PRIVILEGE_COMMAND_PATTERN.matcher(command.trim()).find())
				add(PRIVILEGE_COMMAND, here, command);
			else
				add(CONSOLE_COMMAND, here, command);
		} else if (name.equals("addAttachment") || (name.equals("setPermission") && owner.startsWith("org/bukkit/"))) {
			Type[] args = Type.getArgumentTypes(desc);
			for (int i = 0; i < args.length && flow.get() != null; i++)
				if (args[i].getDescriptor().equals("Ljava/lang/String;") && "*".equals(constantText(flow.get().argument(call, i))))
					add(WILDCARD_PERMISSION, here, api + "(\"*\")");
		} else if (name.equals("disablePlugin") && Type.getArgumentCount(desc) >= 1 && flow.get() != null) {
			AbstractInsnNode target = flow.get().argument(call, 0);
			String type = flow.get().typeOf(target);
			if (!flow.get().isThis(target) && (type == null || !classes.containsKey(type)))
				add(DISABLE_PLUGIN, here, api);
		}
	}

	private void handleNew(@Nonnull CodeLocation here, @Nonnull String type) {
		switch (type) {
			case "java/net/URLClassLoader" -> add(CLASS_LOADER, here, "new URLClassLoader");
			case "java/net/Socket", "javax/net/ssl/SSLSocket" -> add(SecurityRule.NETWORK, here, "new " + simpleName(type));
			case "java/net/ServerSocket" -> add(LISTEN_SOCKET, here, "new ServerSocket");
			case "java/io/FileOutputStream", "java/io/FileWriter", "java/io/RandomAccessFile" ->
					add(FILE_WRITE, here, "new " + simpleName(type));
			case "java/util/jar/JarOutputStream", "java/util/zip/ZipOutputStream" ->
					add(ARCHIVE_WRITE, here, "new " + simpleName(type));
			default -> {}
		}
	}

	/**
	 * Comparisons of a player's name or UUID against a constant are how backdoors recognize their author.
	 */
	private void checkIdentityComparison(@Nonnull CodeLocation here, @Nonnull MethodInsnNode call, @Nonnull Lazy flow) {
		MethodFlow f = flow.get();
		if (f == null || Type.getArgumentCount(call.desc) != 1)
			return;
		AbstractInsnNode left = f.receiver(call);
		AbstractInsnNode right = f.argument(call, 0);
		String constant = identityConstant(f, right);
		if (constant != null && isPlayerIdentity(f, left)) {
			add(HARDCODED_PLAYER, here, constant);
			return;
		}
		constant = identityConstant(f, left);
		if (constant != null && isPlayerIdentity(f, right))
			add(HARDCODED_PLAYER, here, constant);
	}

	@Nullable
	private String identityConstant(@Nonnull MethodFlow flow, @Nullable AbstractInsnNode origin) {
		if (origin instanceof MethodInsnNode call && call.owner.equals("java/util/UUID") && call.name.equals("fromString"))
			return constantText(flow.argument(call, 0));
		return constantText(origin);
	}

	private static boolean isPlayerIdentity(@Nonnull MethodFlow flow, @Nullable AbstractInsnNode origin) {
		if (!(origin instanceof MethodInsnNode call))
			return false;
		if (call.name.equals("toString") && call.owner.equals("java/util/UUID"))
			return isPlayerIdentity(flow, flow.receiver(call));
		return PLAYER_IDENTITY_CALLS.contains(call.name) && PLAYER_TYPES.contains(call.owner);
	}

	private void scanString(@Nonnull String text, @Nonnull CodeLocation here) {
		if (text.isEmpty())
			return;
		List<FlaggedFile> named = flaggedFilesByName.get(text);
		if (named != null)
			for (FlaggedFile file : named)
				fileReferences.computeIfAbsent(file, k -> new TreeSet<>()).add(here);
		Matcher matcher = URL_PATTERN.matcher(text);
		while (matcher.find()) {
			String url = matcher.group();
			String host = matcher.group(1).toLowerCase(Locale.ROOT);
			String lower = url.toLowerCase(Locale.ROOT);
			if (isSuspiciousUrl(host, lower))
				add(SUSPICIOUS_URL, here, url);
			else if (!host.equals("localhost"))
				add(URL, here, url);
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (String sensitive : SENSITIVE_STRINGS)
			if (lower.contains(sensitive.toLowerCase(Locale.ROOT)))
				add(SENSITIVE_FILE, here, sensitive);
		// The plugin's own data folder is under 'plugins' too, so only the folder itself and jars in it count.
		String path = lower.replace('\\', '/');
		if (path.startsWith("./"))
			path = path.substring(2);
		if (path.equals("plugins") || path.equals("plugins/") || (path.startsWith("plugins/") && path.endsWith(".jar")))
			add(PLUGINS_FOLDER, here, text);
		if (text.equals("bStats"))
			bStatsMethods.add(here);
		if (text.equals("addURL"))
			add(CLASS_LOADER, here, "addURL (reflection)");
	}

	/**
	 * Webhooks, paste sites, tunnels and public IP addresses. Service URLs without an ID, such as a prefix used to
	 * validate a webhook from the config, or a placeholder in a default config, do not count.
	 */
	private static boolean isSuspiciousUrl(@Nonnull String host, @Nonnull String lowerUrl) {
		if (IPV4.matcher(host).matches())
			return !host.startsWith("127.") && !host.equals("0.0.0.0") && !host.startsWith("10.") &&
					!host.startsWith("192.168.") && !host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
		for (Pattern pattern : SUSPICIOUS_URLS)
			if (pattern.matcher(lowerUrl).find())
				return true;
		return false;
	}

	@Nonnull
	private List<SecurityFinding> combineWithinClasses() {
		Map<String, Map<SecurityRule, List<SecurityFinding>>> byClass = new TreeMap<>();
		for (Set<SecurityFinding> findings : direct.values())
			for (SecurityFinding finding : findings)
				byClass.computeIfAbsent(outerClass(finding.location().className()), k -> new EnumMap<>(SecurityRule.class))
						.computeIfAbsent(finding.rule(), k -> new ArrayList<>()).add(finding);

		List<SecurityFinding> combined = new ArrayList<>();
		byClass.forEach((className, rules) -> {
			CodeLocation location = CodeLocation.ofClass(className);
			boolean defines = rules.containsKey(DEFINE_CLASS) || rules.containsKey(SCRIPT_ENGINE);
			boolean network = rules.containsKey(SecurityRule.NETWORK);
			if (rules.containsKey(DEFINE_CLASS) && (rules.containsKey(DECODE) || rules.containsKey(DECRYPT)))
				combined.add(combine(HIDDEN_CODE, location, rules, DEFINE_CLASS, DECODE, DECRYPT));
			if (defines && network)
				combined.add(combine(REMOTE_CODE, location, rules, DEFINE_CLASS, SCRIPT_ENGINE, SecurityRule.NETWORK, URL, SUSPICIOUS_URL));
			if (rules.containsKey(PROCESS_EXEC) && network)
				combined.add(combine(REMOTE_EXEC, location, rules, PROCESS_EXEC, SecurityRule.NETWORK, URL, SUSPICIOUS_URL));
			if (rules.containsKey(CLASS_LOADER) && network && !defines)
				combined.add(combine(RUNTIME_LIBRARIES, location, rules, CLASS_LOADER, SecurityRule.NETWORK, URL, SUSPICIOUS_URL));
			if (rules.containsKey(PLUGINS_FOLDER) && rules.containsKey(ARCHIVE_WRITE))
				combined.add(combine(JAR_INFECTION, location, rules, PLUGINS_FOLDER, ARCHIVE_WRITE, FILE_WRITE));
			if (rules.containsKey(PLUGINS_FOLDER) && network && rules.containsKey(FILE_WRITE))
				combined.add(combine(DOWNLOADS_TO_PLUGINS, location, rules, PLUGINS_FOLDER, SecurityRule.NETWORK, FILE_WRITE, URL, SUSPICIOUS_URL));
		});
		return combined;
	}

	/**
	 * Obfuscators that encrypt strings put a decryption routine in every class. Listing each one hides everything else,
	 * so past a threshold they are replaced by a single finding per jar. It matters mostly as a warning: URLs, commands
	 * and paths in those classes are not visible to this scan.
	 */
	private static void collapseStringEncryption(@Nonnull Set<SecurityFinding> all) {
		Map<String, List<SecurityFinding>> bySource = new TreeMap<>();
		for (SecurityFinding finding : all)
			if (finding.evidence().isEmpty() && (finding.rule() == DECODE || finding.rule() == DECRYPT))
				bySource.computeIfAbsent(Objects.requireNonNullElse(finding.embeddedIn(), ""), k -> new ArrayList<>()).add(finding);
		bySource.forEach((source, findings) -> {
			long classCount = findings.stream().map(f -> outerClass(f.location().className())).distinct().count();
			if (classCount < STRING_ENCRYPTION_CLASSES)
				return;
			findings.sort(ORDER);
			findings.forEach(all::remove);
			SecurityFinding first = findings.getFirst();
			all.add(new SecurityFinding(STRING_ENCRYPTION, first.location().classLocation(), classCount + " classes",
					cap(findings), false, source.isEmpty() ? null : source, null));
		});
	}

	/**
	 * Loaders often download in one class and define the classes in another, which {@link #combineWithinClasses()}
	 * cannot see. Each jar is looked at as a whole for that, leaving out bundled libraries.
	 */
	@Nonnull
	private List<SecurityFinding> combineAcrossClasses(@Nonnull List<SecurityFinding> classCombinations) {
		Map<String, Map<SecurityRule, List<SecurityFinding>>> bySource = new TreeMap<>();
		for (Set<SecurityFinding> findings : direct.values())
			for (SecurityFinding finding : findings)
				if (!finding.library())
					bySource.computeIfAbsent(Objects.requireNonNullElse(finding.embeddedIn(), ""), k -> new EnumMap<>(SecurityRule.class))
							.computeIfAbsent(finding.rule(), k -> new ArrayList<>()).add(finding);
		Set<String> alreadyRemote = new HashSet<>();
		for (SecurityFinding combination : classCombinations)
			if (combination.rule() == REMOTE_CODE)
				alreadyRemote.add(Objects.requireNonNullElse(combination.embeddedIn(), ""));

		List<SecurityFinding> combined = new ArrayList<>();
		bySource.forEach((source, rules) -> {
			List<SecurityFinding> defines = rules.getOrDefault(DEFINE_CLASS, List.of());
			if (defines.isEmpty() || alreadyRemote.contains(source))
				return;
			CodeLocation location = defines.stream().map(SecurityFinding::location).sorted().findFirst().orElseThrow().classLocation();
			if (rules.containsKey(SUSPICIOUS_URL)) {
				SecurityFinding remote = combine(REMOTE_CODE, location, rules, DEFINE_CLASS, SUSPICIOUS_URL, SecurityRule.NETWORK);
				String hosts = rules.get(SUSPICIOUS_URL).stream().map(SecurityFinding::detail).filter(Objects::nonNull)
						.map(PluginSecurityScanner::host).distinct().limit(5).reduce((a, b) -> a + ", " + b).orElse(null);
				combined.add(new SecurityFinding(REMOTE_CODE, location, hosts, remote.evidence(), false, remote.embeddedIn(), null));
			} else if (rules.containsKey(SecurityRule.NETWORK)) {
				combined.add(combine(DOWNLOADS_AND_DEFINES, location, rules, DEFINE_CLASS, SecurityRule.NETWORK, URL));
			}
		});
		return combined;
	}

	@Nonnull
	private SecurityFinding combine(@Nonnull SecurityRule rule, @Nonnull CodeLocation location,
	                                @Nonnull Map<SecurityRule, List<SecurityFinding>> rules, @Nonnull SecurityRule... parts) {
		List<SecurityFinding> evidence = new ArrayList<>();
		for (SecurityRule part : parts)
			evidence.addAll(rules.getOrDefault(part, List.of()));
		evidence.sort(ORDER);
		String detail = rules.getOrDefault(URL, List.of()).stream()
				.map(SecurityFinding::detail).filter(Objects::nonNull).map(PluginSecurityScanner::host)
				.distinct().limit(5).reduce((a, b) -> a + ", " + b).orElse(null);
		return finding(rule, location, detail, cap(evidence));
	}

	/**
	 * @return Findings for the flagged files, with the code that names each file as their location. Where that code
	 * also turns bytes into code, a {@link SecurityRule#PAYLOAD_LOADER} for the pair.
	 */
	@Nonnull
	private List<SecurityFinding> fileFindings() {
		Map<String, List<SecurityFinding>> codeByClass = new HashMap<>();
		if (!fileReferences.isEmpty())
			for (Set<SecurityFinding> findings : direct.values())
				for (SecurityFinding finding : findings)
					if (CODE_LOADING.contains(finding.rule()) || NATIVE_LOADING.contains(finding.rule()))
						codeByClass.computeIfAbsent(outerClass(finding.location().className()), k -> new ArrayList<>()).add(finding);

		List<SecurityFinding> result = new ArrayList<>();
		for (FlaggedFile file : flaggedFiles) {
			Set<CodeLocation> references = fileReferences.getOrDefault(file, Set.of());
			SecurityFinding found = new SecurityFinding(file.match().rule(), references.stream().findFirst().orElse(null),
					file.match().detail(), List.of(), file.library(), file.embeddedPath(), file.path());
			result.add(found);
			Set<SecurityRule> loading = file.isNative() ? NATIVE_LOADING : CODE_LOADING;
			Set<String> seen = new HashSet<>();
			for (CodeLocation reference : references) {
				String outer = outerClass(reference.className());
				if (!seen.add(outer))
					continue;
				List<SecurityFinding> evidence = new ArrayList<>(List.of(found));
				codeByClass.getOrDefault(outer, List.of()).stream().filter(f -> loading.contains(f.rule())).sorted(ORDER).forEach(evidence::add);
				if (evidence.size() > 1)
					result.add(new SecurityFinding(PAYLOAD_LOADER, reference, file.path(), cap(evidence),
							isLibrary(outer) && file.library(), embeddedIn.get(reference.className()), file.path()));
			}
		}
		return result;
	}

	@Nonnull
	private List<SecurityFinding> findBackdoors() {
		List<SecurityFinding> backdoors = new ArrayList<>();
		for (CodeLocation handler : chatHandlers) {
			List<SecurityFinding> actions = reachable(handler, BACKDOOR_ACTIONS);
			if (!actions.isEmpty())
				backdoors.add(finding(CHAT_BACKDOOR, handler, eventName(handler), cap(actions)));
		}
		Set<SecurityRule> playerActions = EnumSet.copyOf(BACKDOOR_ACTIONS);
		playerActions.add(CONSOLE_COMMAND);
		direct.forEach((method, findings) -> {
			List<SecurityFinding> identities = findings.stream().filter(f -> f.rule() == HARDCODED_PLAYER).toList();
			if (identities.isEmpty())
				return;
			List<SecurityFinding> actions = reachable(method, playerActions);
			if (actions.isEmpty())
				return;
			List<SecurityFinding> evidence = new ArrayList<>(identities);
			evidence.addAll(actions);
			backdoors.add(finding(PLAYER_BACKDOOR, method, identities.getFirst().detail(), cap(evidence)));
		});
		return backdoors;
	}

	/**
	 * @return Findings of the given rules in the method, and in the plugin's methods it calls, schedules, or passes
	 * as lambdas, a few calls deep.
	 */
	@Nonnull
	private List<SecurityFinding> reachable(@Nonnull CodeLocation start, @Nonnull Set<SecurityRule> rules) {
		Set<CodeLocation> visited = new HashSet<>();
		Deque<CodeLocation> queue = new ArrayDeque<>();
		Map<CodeLocation, Integer> depth = new HashMap<>();
		queue.add(start);
		depth.put(start, 0);
		List<SecurityFinding> found = new ArrayList<>();
		while (!queue.isEmpty()) {
			CodeLocation method = queue.poll();
			if (!visited.add(method))
				continue;
			for (SecurityFinding finding : direct.getOrDefault(method, Set.of()))
				if (rules.contains(finding.rule()))
					found.add(finding);
			int next = depth.get(method) + 1;
			if (next > MAX_REACH_DEPTH)
				continue;
			for (CodeLocation callee : calls.getOrDefault(method, Set.of())) {
				if (!visited.contains(callee)) {
					depth.putIfAbsent(callee, next);
					queue.add(callee);
				}
			}
		}
		found.sort(ORDER);
		return found;
	}

	private void add(@Nonnull SecurityRule rule, @Nonnull CodeLocation location, @Nullable String detail) {
		if (detail != null && detail.length() > MAX_DETAIL_LENGTH)
			detail = detail.substring(0, MAX_DETAIL_LENGTH) + '…';
		direct.computeIfAbsent(location, k -> new LinkedHashSet<>()).add(finding(rule, location, detail, List.of()));
	}

	@Nonnull
	private SecurityFinding finding(@Nonnull SecurityRule rule, @Nonnull CodeLocation location, @Nullable String detail,
	                                @Nonnull List<SecurityFinding> evidence) {
		String className = location.className();
		return new SecurityFinding(rule, location, detail, evidence, isLibrary(className), embeddedIn.get(className), null);
	}

	private void link(@Nonnull CodeLocation caller, @Nonnull CodeLocation callee) {
		if (!caller.equals(callee))
			calls.computeIfAbsent(caller, k -> new LinkedHashSet<>()).add(callee);
	}

	private boolean declares(@Nonnull String className, @Nonnull String name, @Nonnull String desc) {
		ClassNode node = classes.get(className);
		if (node == null)
			return false;
		for (MethodNode method : node.methods)
			if (method.name.equals(name) && method.desc.equals(desc))
				return true;
		return false;
	}

	/**
	 * @return Text of a value when it is a constant: a literal, a constant field, a concatenation with constant parts
	 * (with {@code …} for the parts that are not), or {@code String.format} of a constant pattern.
	 */
	@Nullable
	private String constantText(@Nullable AbstractInsnNode origin) {
		return switch (origin) {
			case LdcInsnNode ldc when ldc.cst instanceof String text -> text;
			case FieldInsnNode field when field.getOpcode() == GETSTATIC -> constantStrings.get(field.owner + '.' + field.name);
			case InvokeDynamicInsnNode indy -> concatRecipe(indy);
			case null, default -> null;
		};
	}

	@Nullable
	private static String concatRecipe(@Nonnull InvokeDynamicInsnNode indy) {
		if ("makeConcatWithConstants".equals(indy.name) && indy.bsmArgs.length > 0 && indy.bsmArgs[0] instanceof String recipe)
			return recipe.replace('\u0001', '…').replace('\u0002', '…');
		return null;
	}

	private static boolean isFalse(@Nullable AbstractInsnNode origin) {
		return origin instanceof InsnNode insn && insn.getOpcode() == ICONST_0;
	}

	@Nullable
	private static Integer intConstant(@Nullable AbstractInsnNode origin) {
		if (origin instanceof InsnNode insn && insn.getOpcode() >= ICONST_M1 && insn.getOpcode() <= ICONST_5)
			return insn.getOpcode() - ICONST_0;
		if (origin instanceof IntInsnNode push && (push.getOpcode() == BIPUSH || push.getOpcode() == SIPUSH))
			return push.operand;
		if (origin instanceof FieldInsnNode field && field.owner.equals("javax/crypto/Cipher"))
			return switch (field.name) {
				case "ENCRYPT_MODE" -> 1;
				case "DECRYPT_MODE" -> 2;
				case "WRAP_MODE" -> 3;
				case "UNWRAP_MODE" -> 4;
				default -> null;
			};
		return null;
	}

	/**
	 * @param className
	 * 		Internal class name.
	 *
	 * @return {@code true} when the class is in the package of a library that plugins commonly bundle.
	 */
	public static boolean isLibrary(@Nonnull String className) {
		if (PluginIndexer.isShadedLibrary(className))
			return true;
		for (String marker : LIBRARY_MARKERS)
			if (className.contains(marker))
				return true;
		return false;
	}

	private static boolean isBytecodeGenerator(@Nonnull String owner) {
		return owner.endsWith("/ClassWriter") || owner.startsWith("javassist/") || owner.contains("/javassist/") ||
				owner.contains("bytebuddy/");
	}

	private static boolean callsPlayerIdentity(@Nonnull MethodNode method) {
		for (AbstractInsnNode insn : method.instructions)
			if (insn instanceof MethodInsnNode call && PLAYER_IDENTITY_CALLS.contains(call.name) && PLAYER_TYPES.contains(call.owner))
				return true;
		return false;
	}

	private static boolean isChatHandler(@Nonnull MethodNode method) {
		Type[] args = Type.getArgumentTypes(method.desc);
		if (args.length != 1 || args[0].getSort() != Type.OBJECT || !CHAT_EVENTS.contains(args[0].getInternalName()))
			return false;
		for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
			if (annotations != null)
				for (AnnotationNode annotation : annotations)
					if (HANDLER_ANNOTATIONS.contains(annotation.desc))
						return true;
		return false;
	}

	@Nonnull
	private static String eventName(@Nonnull CodeLocation handler) {
		Type[] args = Type.getArgumentTypes(Objects.requireNonNull(handler.memberDescriptor()));
		return simpleName(args[0].getInternalName());
	}

	@Nonnull
	private static String outerClass(@Nonnull String className) {
		int slash = className.lastIndexOf('/');
		int dollar = className.indexOf('$', slash + 1);
		return dollar > 0 ? className.substring(0, dollar) : className;
	}

	@Nonnull
	private static String simpleName(@Nonnull String internalName) {
		return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
	}

	@Nonnull
	private static String host(@Nonnull String url) {
		Matcher matcher = URL_PATTERN.matcher(url);
		return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : url;
	}

	@Nonnull
	private static List<SecurityFinding> cap(@Nonnull List<SecurityFinding> evidence) {
		List<SecurityFinding> distinct = new ArrayList<>(new LinkedHashSet<>(evidence));
		return List.copyOf(distinct.size() > MAX_EVIDENCE ? distinct.subList(0, MAX_EVIDENCE) : distinct);
	}

	/**
	 * A file that is not a class and was recognized as something to report.
	 *
	 * @param path
	 * 		Path of the file in its jar.
	 * @param embeddedPath
	 * 		Path of the jar inside the plugin, or {@code null} for the plugin jar itself.
	 * @param match
	 * 		What the file was recognized as.
	 * @param library
	 *        {@code true} when the path is in a location of bundled libraries.
	 */
	private record FlaggedFile(@Nonnull String path, @Nullable String embeddedPath, @Nonnull Match match, boolean library) {
		boolean isNative() {
			return match.rule() == NATIVE_BINARY || match.rule() == DISGUISED_BINARY;
		}

		/**
		 * @return Strings that name the file when found in code: the full path, with a leading slash, and the bare name.
		 */
		@Nonnull
		Set<String> referenceNames() {
			return new LinkedHashSet<>(List.of(path, '/' + path, path.substring(path.lastIndexOf('/') + 1)));
		}
	}

	/**
	 * Analyzes a method only if a check needs to know where a value comes from, since most methods do not.
	 */
	private static final class Lazy {
		private final Supplier<MethodFlow> supplier;
		private boolean computed;
		private MethodFlow flow;

		private Lazy(@Nonnull Supplier<MethodFlow> supplier) {
			this.supplier = supplier;
		}

		@Nullable
		MethodFlow get() {
			if (!computed) {
				computed = true;
				flow = supplier.get();
			}
			return flow;
		}
	}
}
