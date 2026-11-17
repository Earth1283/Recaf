package software.coley.recaf.services.analysis.flow;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/** Explicit library boundaries. Unknown calls are reported as gaps rather than treated as sanitizers. */
final class FlowModels {
	private FlowModels() {}

	static boolean messageAnnotation(MethodNode method) {
		return java.util.stream.Stream.of(method.visibleAnnotations, method.invisibleAnnotations)
				.filter(java.util.Objects::nonNull).flatMap(java.util.Collection::stream)
				.anyMatch(a -> Set.of("Ljavax/websocket/OnMessage;", "Ljakarta/websocket/OnMessage;").contains(a.desc));
	}

	static boolean eventAnnotation(MethodNode method) {
		return java.util.stream.Stream.of(method.visibleAnnotations, method.invisibleAnnotations)
				.filter(java.util.Objects::nonNull).flatMap(java.util.Collection::stream)
				.anyMatch(a -> Set.of("Lorg/bukkit/event/EventHandler;", "Lnet/md_5/bungee/event/EventHandler;",
						"Lcom/velocitypowered/api/event/Subscribe;").contains(a.desc));
	}

	static String source(MethodInsnNode call) {
		if ((call.owner.equals("javax/servlet/ServletRequest") || call.owner.equals("jakarta/servlet/ServletRequest") ||
				call.owner.endsWith("/HttpServletRequest")) && Set.of("getParameter", "getReader", "getInputStream", "getHeader").contains(call.name))
			return "HTTP request input";
		if (call.owner.equals("java/net/http/HttpResponse") && call.name.equals("body")) return "HTTP response body";
		if ((call.owner.startsWith("org/bukkit/event/player/") || call.owner.startsWith("io/papermc/paper/event/player/")) &&
				Set.of("getMessage", "message", "getSignedMessage").contains(call.name)) return "Player chat input";
		return null;
	}

	static String propagation(MethodInsnNode call) {
		String owner = call.owner, name = call.name;
		if ((owner.endsWith("/gson/Gson") && name.equals("fromJson")) ||
				(owner.endsWith("/jackson/databind/ObjectMapper") && Set.of("readValue", "readTree", "convertValue").contains(name)) ||
				(owner.endsWith("/gson/JsonParser") && Set.of("parse", "parseString", "parseReader").contains(name)))
			return "JSON deserialization (object-level model)";
		if ((owner.contains("/gson/Json") || owner.contains("/jackson/databind/JsonNode") || owner.startsWith("org/json/")) &&
				(name.startsWith("get") || name.startsWith("as") || name.equals("path"))) return "JSON member/accessor model";
		if (owner.equals("java/lang/String") || owner.equals("java/lang/StringBuilder") || owner.equals("java/lang/StringBuffer"))
			return "String operation model";
		if (Set.of("java/lang/Boolean", "java/lang/Integer", "java/lang/Long", "java/lang/Double", "java/lang/Float",
				"java/util/Objects", "java/util/UUID").contains(owner)) return "JDK value conversion model";
		if ((owner.equals("org/bukkit/Bukkit") || owner.equals("org/bukkit/Server")) &&
				Set.of("getPlayer", "getPlayerExact", "getOfflinePlayer").contains(name)) return "Player lookup from supplied identity";
		if (Set.of("java/util/Map", "java/util/List", "java/util/Optional").contains(owner) &&
				Set.of("get", "getOrDefault", "orElse", "of", "ofNullable").contains(name)) return "Container access (object-level model)";
		return null;
	}

	static boolean scheduler(MethodInsnNode call) {
		return (call.owner.startsWith("org/bukkit/scheduler/") && (call.name.startsWith("runTask") || call.name.startsWith("schedule"))) ||
				(Set.of("java/util/concurrent/Executor", "java/util/concurrent/ExecutorService", "java/util/concurrent/ScheduledExecutorService").contains(call.owner) &&
						Set.of("execute", "submit", "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay").contains(call.name));
	}

	static boolean lambdaInvocation(MethodInsnNode call) {
		return (call.owner.equals("java/lang/Runnable") && call.name.equals("run") && call.desc.equals("()V")) ||
				(call.owner.equals("java/util/concurrent/Callable") && call.name.equals("call") && call.desc.equals("()Ljava/lang/Object;"));
	}

	static boolean runnableArgument(String descriptor, int index) {
		Type type = Type.getArgumentTypes(descriptor)[index];
		return type.getDescriptor().equals("Ljava/lang/Runnable;") || type.getDescriptor().equals("Ljava/util/concurrent/Callable;");
	}
}
