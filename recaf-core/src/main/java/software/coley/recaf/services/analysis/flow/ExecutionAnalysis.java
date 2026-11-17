package software.coley.recaf.services.analysis.flow;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.Printer;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static software.coley.recaf.services.analysis.flow.FlowGraph.*;

/**
 * Resource-aware static call inventory and demand-driven, call-site-sensitive backward slicing.
 * Never loads or executes workspace classes. Heap edges are deliberately conservative and labeled.
 */
public final class ExecutionAnalysis implements Opcodes {
	private final Workspace workspace;
	private final Map<JvmClassBundle, Map<String, JvmClassInfo>> originals = new IdentityHashMap<>();
	private final Map<MethodId, MethodData> methods = new TreeMap<>();
	private final Map<String, List<ClassData>> classes = new TreeMap<>();
	private final Map<MethodId, List<Binding>> incoming = new HashMap<>();
	private final Map<MethodId, List<Call>> outgoing = new HashMap<>();
	private final Map<String, List<Ref>> writes = new HashMap<>();
	private final Map<String, List<MethodId>> resolutionCache = new HashMap<>();
	private final Map<String, Set<String>> ancestorCache = new HashMap<>();
	private final List<Site> sinks = new ArrayList<>();
	private final List<String> problems = new ArrayList<>();

	private ExecutionAnalysis(Workspace workspace) { this.workspace = workspace; }

	/** Build a snapshot of the primary resource and embedded resources. Supporting libraries are model boundaries. */
	public static ExecutionAnalysis scan(Workspace workspace, BooleanSupplier cancelled) {
		ExecutionAnalysis result = new ExecutionAnalysis(workspace);
		result.read(workspace.getPrimaryResource(), "primary", cancelled);
		result.classes.forEach((name, definitions) -> {
			if (definitions.size() > 1) result.problems.add("Multiple definitions of " + name + "; resolution requires a same-resource or unique definition");
		});
		for (MethodData method : result.methods.values()) {
			check(cancelled);
			result.index(method, cancelled);
		}
		return result;
	}

	public List<Method> methods() { return methods.values().stream().map(m -> m.view).toList(); }
	public List<Method> entries() { return methods().stream().filter(m -> m.entryReason() != null).toList(); }
	public List<Site> sinks() { return List.copyOf(sinks); }
	public List<String> problems() { return List.copyOf(problems); }
	public Method method(MethodId id) { MethodData data = methods.get(id); return data == null ? null : data.view; }
	public List<Call> calls(MethodId id) { return outgoing.getOrDefault(id, List.of()); }
	public List<Call> callers(MethodId id) {
		return incoming.getOrDefault(id, List.of()).stream().map(b -> b.call).toList();
	}

	/** Identity checks also cover newly added classes, embedded resources, and multi-release bundles. */
	public boolean isCurrent() {
		Map<JvmClassBundle, Map<String, JvmClassInfo>> now = new IdentityHashMap<>();
		Deque<WorkspaceResource> resources = new ArrayDeque<>();
		resources.add(workspace.getPrimaryResource());
		while (!resources.isEmpty()) {
			WorkspaceResource resource = resources.removeFirst();
			resource.jvmAllClassBundleStream().forEach(bundle -> now.put(bundle, new HashMap<>(bundle)));
			resources.addAll(resource.getEmbeddedResources().values());
		}
		if (now.size() != originals.size()) return false;
		for (var entry : originals.entrySet()) {
			Map<String, JvmClassInfo> current = now.get(entry.getKey());
			if (current == null || current.size() != entry.getValue().size()) return false;
			for (var cls : entry.getValue().entrySet()) if (current.get(cls.getKey()) != cls.getValue()) return false;
		}
		return true;
	}

	private void read(WorkspaceResource resource, String scope, BooleanSupplier cancelled) {
		for (JvmClassBundle bundle : resource.jvmAllClassBundleStream().toList()) {
			originals.put(bundle, new HashMap<>(bundle));
			String origin = scope + (bundle instanceof VersionedJvmClassBundle v ? " (Java " + v.version() + ")" : "");
			for (JvmClassInfo info : bundle.values().stream().sorted(Comparator.comparing(JvmClassInfo::getName)).toList()) {
				check(cancelled);
				if (methods.size() >= 100_000) { problems.add("Method inventory limit reached at " + origin + "/" + info.getName()); break; }
				try {
					ClassNode node = new ClassNode();
					info.getClassReader().accept(node, ClassReader.SKIP_FRAMES);
					ClassData cls = new ClassData(origin, node);
					classes.computeIfAbsent(node.name, _ -> new ArrayList<>()).add(cls);
					for (MethodNode method : node.methods) {
						if (methods.size() >= 100_000) { problems.add("Method inventory limit reached within " + origin + "/" + info.getName()); break; }
						var member = info.getDeclaredMethod(method.name, method.desc);
						if (member == null) continue;
						MethodId id = new MethodId(origin, CodeLocation.ofMember(node.name, method.name, method.desc));
						methods.put(id, new MethodData(new Method(id, PathNodes.memberPath(workspace, resource, bundle, info, member), null, false), method));
					}
				} catch (RuntimeException ex) { problems.add("Unreadable class " + origin + "/" + info.getName() + ": " + ex.getMessage()); }
			}
		}
		for (var embedded : new TreeMap<>(resource.getEmbeddedResources()).entrySet())
			read(embedded.getValue(), scope + " > " + embedded.getKey(), cancelled);
	}

	private void index(MethodData method, BooleanSupplier cancelled) {
		MethodId id = method.view.id();
		String owner = id.location().className(), name = method.node.name;
		boolean network = FlowModels.messageAnnotation(method.node) ||
				(name.equals("onMessage") && (method.node.desc.equals("(Ljava/lang/String;)V") || method.node.desc.equals("(Ljava/nio/ByteBuffer;)V") ||
						method.node.desc.equals("(Lorg/java_websocket/WebSocket;Ljava/lang/String;)V") || method.node.desc.equals("(Lorg/java_websocket/WebSocket;Ljava/nio/ByteBuffer;)V")) &&
						(subtype(id.resource(), owner, "org/java_websocket/client/WebSocketClient") || subtype(id.resource(), owner, "org/java_websocket/server/WebSocketServer"))) ||
				(Set.of("onText", "onBinary").contains(name) && subtype(id.resource(), owner, "java/net/http/WebSocket$Listener")) ||
				(Set.of("channelRead", "channelRead0").contains(name) &&
						Set.of("io/netty/channel/ChannelInboundHandler", "io/netty/channel/ChannelInboundHandlerAdapter", "io/netty/channel/SimpleChannelInboundHandler")
								.stream().anyMatch(type -> subtype(id.resource(), owner, type)));
		String entry = network ? "Recognized network message callback" : FlowModels.eventAnnotation(method.node) ? "Annotated event handler" :
				name.equals("main") && method.node.desc.equals("([Ljava/lang/String;)V") && (method.node.access & ACC_STATIC) != 0 ? "JVM main" :
				(Set.of("onLoad", "onEnable", "onDisable").contains(name) && method.node.desc.equals("()V") &&
						(subtype(id.resource(), owner, "org/bukkit/plugin/java/JavaPlugin") || subtype(id.resource(), owner, "net/md_5/bungee/api/plugin/Plugin"))) ? "Plugin lifecycle callback" :
				name.equals("onCommand") && subtype(id.resource(), owner, "org/bukkit/command/CommandExecutor") ? "Command callback" : null;
		method.view = new Method(id, method.view.path(), entry, network);
		for (int i = 0; i < method.instructions.length; i++) {
			check(cancelled);
			AbstractInsnNode instruction = method.instructions[i];
			if (instruction instanceof FieldInsnNode field && (field.getOpcode() == PUTFIELD || field.getOpcode() == PUTSTATIC))
				writes.computeIfAbsent(fieldKey(field), _ -> new ArrayList<>()).add(new Ref(id, i, Mode.VALUE));
			if (!(instruction instanceof MethodInsnNode call)) continue;
			Site site = new Site(id, method.ordinal[i], call.owner + '.' + call.name + call.desc, sink(id.resource(), call));
			if (site.sink() != null) sinks.add(site);
			List<MethodId> targets = resolve(id.resource(), call);
			if (targets.isEmpty()) addCall(new Call(site, null, FlowModels.propagation(call) != null || site.sink() != null ? CallKind.EXTERNAL : CallKind.UNRESOLVED,
					FlowModels.propagation(call) != null ? FlowModels.propagation(call) : site.sink() != null ? "Known privileged API boundary; body not inspected" : "External or unresolved call; no body inspected"));
			for (MethodId target : targets) {
				boolean virtual = call.getOpcode() == INVOKEVIRTUAL || call.getOpcode() == INVOKEINTERFACE;
				boolean cross = !target.resource().equals(id.resource());
				Call edge = new Call(site, target, virtual ? CallKind.VIRTUAL : cross ? CallKind.CROSS_RESOURCE : CallKind.DIRECT,
						virtual ? "Possible virtual dispatch (class hierarchy approximation)" : cross ? "Possible cross-resource call; runtime classloader not proven" : "Resolved bytecode call");
				addBinding(new Binding(edge, i, i, false));
			}
			if (FlowModels.scheduler(call) || FlowModels.lambdaInvocation(call)) schedule(method, i, site, cancelled);
		}
	}

	private void addCall(Call edge) { outgoing.computeIfAbsent(edge.site().method(), _ -> new ArrayList<>()).add(edge); }
	private void addBinding(Binding binding) {
		addCall(binding.call);
		incoming.computeIfAbsent(binding.call.target(), _ -> new ArrayList<>()).add(binding);
	}

	private void schedule(MethodData method, int raw, Site site, BooleanSupplier cancelled) {
		if (!method.prepare(cancelled)) return;
		MethodInsnNode call = (MethodInsnNode) method.instructions[raw];
		List<Set<AbstractInsnNode>> inputs = method.inputs.getOrDefault(call, List.of());
		int receiver = call.getOpcode() == INVOKESTATIC ? 0 : 1;
		boolean invocation = FlowModels.lambdaInvocation(call);
		for (int arg = invocation ? -1 : 0; arg < Type.getArgumentCount(call.desc); arg++) {
			if ((!invocation && !FlowModels.runnableArgument(call.desc, arg)) || arg + receiver >= inputs.size()) continue;
			Set<AbstractInsnNode> origins = new LinkedHashSet<>();
			collectOrigins(method, inputs.get(arg + receiver), origins, new HashSet<>(), 0);
			for (AbstractInsnNode origin : origins) {
				if (origin instanceof InvokeDynamicInsnNode indy && indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") &&
						indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle impl) {
					for (MethodId target : exact(method.view.id().resource(), impl.getOwner(), impl.getName(), impl.getDesc()))
						addBinding(new Binding(new Call(site, target, invocation ? CallKind.LAMBDA : CallKind.SCHEDULED,
								invocation ? "Modeled lambda invocation; captured values" : "Modeled scheduler handoff; captured lambda values"), raw, method.index.get(indy), true));
				} else if (origin instanceof TypeInsnNode type && type.getOpcode() == NEW) {
					for (MethodId target : exact(method.view.id().resource(), type.desc, "run", "()V"))
						addBinding(new Binding(new Call(site, target, invocation ? CallKind.LAMBDA : CallKind.SCHEDULED,
								"Modeled Runnable handoff; receiver fields are conservative"), raw, method.index.get(origin), true));
				}
			}
		}
	}

	private static void collectOrigins(MethodData method, Set<AbstractInsnNode> sources, Set<AbstractInsnNode> result, Set<AbstractInsnNode> seen, int depth) {
		if (depth > 32) return;
		for (AbstractInsnNode source : sources) {
			if (!seen.add(source)) continue;
			if (source instanceof InvokeDynamicInsnNode || source instanceof TypeInsnNode) result.add(source);
			else for (Set<AbstractInsnNode> input : method.inputs.getOrDefault(source, List.of())) collectOrigins(method, input, result, seen, depth + 1);
		}
	}

	private String sink(String scope, MethodInsnNode call) {
		if (call.name.equals("setOp") && call.desc.equals("(Z)V") &&
				(Set.of("org/bukkit/entity/Player", "org/bukkit/OfflinePlayer", "org/bukkit/permissions/ServerOperator").contains(call.owner) ||
						subtype(scope, call.owner, "org/bukkit/permissions/ServerOperator") || subtype(scope, call.owner, "org/bukkit/entity/Player"))) return "Operator state change";
		if ((call.owner.equals("java/lang/Runtime") && call.name.equals("exec")) ||
				(call.owner.equals("java/lang/ProcessBuilder") && call.name.equals("start"))) return "Process execution";
		if (Set.of("org/bukkit/Bukkit", "org/bukkit/Server").contains(call.owner) && call.name.equals("dispatchCommand")) return "Server command dispatch";
		if (call.name.equals("defineClass") && subtype(scope, call.owner, "java/lang/ClassLoader")) return "Dynamic class definition";
		return null;
	}

	private List<MethodId> resolve(String scope, MethodInsnNode call) {
		String key = scope + '\0' + call.getOpcode() + '\0' + call.owner + '.' + call.name + call.desc;
		return resolutionCache.computeIfAbsent(key, _ -> {
			LinkedHashSet<MethodId> found = new LinkedHashSet<>(exact(scope, call.owner, call.name, call.desc));
			if (call.getOpcode() == INVOKEVIRTUAL || call.getOpcode() == INVOKEINTERFACE) {
				for (var entry : classes.entrySet()) {
					if (entry.getKey().equals(call.owner) || !subtype(scope, entry.getKey(), call.owner)) continue;
					found.addAll(exact(scope, entry.getKey(), call.name, call.desc));
				}
			}
			return List.copyOf(found);
		});
	}

	private List<MethodId> exact(String scope, String owner, String name, String descriptor) {
		return exact(scope, owner, name, descriptor, new HashSet<>());
	}
	private List<MethodId> exact(String scope, String owner, String name, String descriptor, Set<String> seen) {
		if (!seen.add(owner)) return List.of();
		List<ClassData> candidates = definitions(scope, owner);
		if (candidates.size() != 1) return List.of();
		ClassData cls = candidates.getFirst();
		MethodId id = new MethodId(cls.scope, CodeLocation.ofMember(owner, name, descriptor));
		if (methods.containsKey(id) && (methods.get(id).node.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0) return List.of(id);
		if (name.equals("<init>")) return List.of();
		List<MethodId> result = new ArrayList<>();
		if (cls.node.superName != null) result.addAll(exact(scope, cls.node.superName, name, descriptor, seen));
		for (String itf : cls.node.interfaces) result.addAll(exact(scope, itf, name, descriptor, seen));
		return result;
	}
	private List<ClassData> definitions(String scope, String owner) {
		List<ClassData> all = classes.getOrDefault(owner, List.of());
		List<ClassData> local = all.stream().filter(c -> c.scope.equals(scope)).toList();
		return local.isEmpty() ? all : local;
	}
	private boolean subtype(String scope, String child, String parent) {
		return ancestorCache.computeIfAbsent(scope + '\0' + child, _ -> ancestors(scope, child)).contains(parent);
	}
	private Set<String> ancestors(String scope, String child) {
		Deque<String> queue = new ArrayDeque<>(); queue.add(child);
		Set<String> seen = new HashSet<>();
		while (!queue.isEmpty() && seen.size() < 512) {
			String next = queue.removeFirst();
			if (!seen.add(next)) continue;
			List<ClassData> definitions = definitions(scope, next);
			if (definitions.size() == 1) {
				ClassNode cls = definitions.getFirst().node;
				if (cls.superName != null) queue.add(cls.superName);
				queue.addAll(cls.interfaces);
			}
		}
		return Set.copyOf(seen);
	}

	/** Trace one exact call site. Synchronized because per-method analyses are populated lazily. */
	public synchronized Trace trace(Site sink, Limits limits, BooleanSupplier cancelled) {
		if (!isCurrent()) throw new IllegalStateException("Workspace changed; rescan before tracing");
		MethodData method = methods.get(sink.method());
		if (method == null) throw new IllegalArgumentException("Unknown method");
		int raw = method.raw(sink.instruction());
		if (raw < 0 || !(method.instructions[raw] instanceof MethodInsnNode call) ||
				!sink.target().equals(call.owner + '.' + call.name + call.desc)) throw new IllegalArgumentException("Unknown call site");
		return new Slicer(limits, cancelled).run(sink, new Ref(sink.method(), raw, Mode.SINK));
	}

	private enum Mode { VALUE, PARAMETER, ENTRY, EXECUTION, SINK }
	private record Ref(MethodId method, int index, Mode mode) {}
	private record ClassData(String scope, ClassNode node) {}
	private record Binding(Call call, int rawCall, int rawArguments, boolean captured) {}
	private record Edge(Ref target, EdgeKind kind, String detail, boolean assumed, Binding context, boolean enter) {}
	private record State(Ref ref, List<Binding> context, Influence influence, boolean assumed) {}
	private record Work(State state, List<Step> path, int depth) {}
	private static String fieldKey(FieldInsnNode field) { return field.owner + '.' + field.name + ':' + field.desc; }
	private static void check(BooleanSupplier cancelled) {
		if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
	}

	private final class Slicer {
		final Limits limits;
		final BooleanSupplier cancelled;
		final Set<String> gaps = new LinkedHashSet<>(problems);
		final List<Witness> witnesses = new ArrayList<>();
		final Set<State> visited = new HashSet<>();
		final Map<Ref, Step> conditions = new LinkedHashMap<>();
		boolean truncated;
		Slicer(Limits limits, BooleanSupplier cancelled) { this.limits = limits; this.cancelled = cancelled; }

		Trace run(Site sink, Ref root) {
			Deque<Work> queue = new ArrayDeque<>();
			queue.add(new Work(new State(root, List.of(), Influence.DATA, false), List.of(step(root, EdgeKind.VALUE, false, sink.sink())), 0));
			while (!queue.isEmpty()) {
				check(cancelled);
				if (visited.size() >= limits.maxStates() || witnesses.size() >= limits.maxPaths()) { truncated = true; break; }
				Work work = queue.removeFirst();
				if (!visited.add(work.state)) continue;
				Ref ref = work.state.ref;
				String source = source(ref);
				if (source != null) {
					List<Step> path = new ArrayList<>(work.path); Collections.reverse(path);
					Influence influence = ref.mode == Mode.ENTRY ? Influence.REACHABILITY : work.state.influence;
					witnesses.add(new Witness(influence, source, List.copyOf(path)));
					continue;
				}
				if (work.depth >= limits.maxDepth()) { truncated = true; gaps.add("Trace depth limit reached"); continue; }
				for (Edge edge : edges(ref, work.state.context)) {
					if (queue.size() >= limits.maxStates()) { truncated = true; gaps.add("Pending-state budget reached"); break; }
					List<Binding> context = work.state.context;
					if (edge.kind == EdgeKind.FIELD && !edge.target.method.equals(ref.method)) context = List.of();
					if (edge.context != null) {
						context = new ArrayList<>(context);
						if (edge.enter) {
							if (context.size() >= limits.maxContext()) { truncated = true; gaps.add("Call context limit reached (possibly recursive)"); continue; }
							context.add(edge.context);
						} else if (!context.isEmpty()) {
							if (!context.getLast().equals(edge.context)) continue;
							context.removeLast();
						}
						context = List.copyOf(context);
					}
					Influence influence = edge.kind == EdgeKind.CONTROL ? Influence.CONTROL : work.state.influence;
					List<Step> path = new ArrayList<>(work.path);
					path.add(step(edge.target, edge.kind, edge.assumed, edge.detail));
					queue.addLast(new Work(new State(edge.target, context, influence, work.state.assumed || edge.assumed), List.copyOf(path), work.depth + 1));
				}
			}
			if (truncated) gaps.add("Analysis budget reached; paths may be missing");
			return new Trace(sink, List.copyOf(witnesses), List.copyOf(conditions.values()), List.copyOf(gaps), visited.size(), truncated);
		}

		String source(Ref ref) {
			MethodData data = methods.get(ref.method);
			if (ref.mode == Mode.ENTRY && data.view.networkEntry()) return "Network callback invocation (execution reachability only)";
			if (ref.mode == Mode.PARAMETER && data.view.networkEntry() && data.payloadSlot(ref.index)) return "Network message parameter, local slot " + ref.index;
			if (ref.mode == Mode.VALUE && data.instructions[ref.index] instanceof MethodInsnNode call) return FlowModels.source(call);
			return null;
		}

		List<Edge> edges(Ref ref, List<Binding> context) {
			MethodData data = methods.get(ref.method);
			List<Edge> result = new ArrayList<>();
			if (ref.mode == Mode.ENTRY || ref.mode == Mode.PARAMETER) {
				List<Binding> callers = incoming.getOrDefault(ref.method, List.of());
				if (callers.isEmpty() && ref.mode == Mode.PARAMETER && !data.view.networkEntry()) gaps.add("Unbound parameter in " + ref.method);
				if (callers.isEmpty() && ref.mode == Mode.ENTRY && !data.view.networkEntry()) gaps.add("No modeled callers for " + ref.method + "; external callbacks may be missing");
				if (callers.isEmpty() && ref.mode == Mode.PARAMETER && data.view.networkEntry() && ref.index != 0 && !data.payloadSlot(ref.index))
					gaps.add("Unmodeled network callback parameter/local slot " + ref.index + " in " + ref.method);
				for (Binding binding : callers) {
					if (!context.isEmpty() && !context.getLast().equals(binding)) continue;
					MethodData caller = methods.get(binding.call.site().method());
					if (!prepare(caller)) continue;
					if (!caller.reachable(binding.rawCall)) continue;
					if (ref.mode == Mode.ENTRY) result.add(new Edge(new Ref(caller.view.id(), binding.rawCall, Mode.EXECUTION),
							binding.captured ? EdgeKind.CALLBACK : EdgeKind.EXECUTION, binding.call.explanation(), binding.call.kind() != CallKind.DIRECT, binding, false));
					else {
						if (binding.captured && caller.instructions[binding.rawArguments] instanceof TypeInsnNode && ref.index == 0) {
							result.add(new Edge(new Ref(caller.view.id(), binding.rawArguments, Mode.VALUE), EdgeKind.CALLBACK,
									"Constructed Runnable receiver (object-level model)", true, binding, false)); continue;
						}
						int input = parameterInput(data, binding, ref.index);
						List<Set<AbstractInsnNode>> inputs = caller.inputs.getOrDefault(caller.instructions[binding.rawArguments], List.of());
						if (input < 0 || input >= inputs.size()) { gaps.add("Unmodeled callback parameter in " + ref.method); continue; }
						for (Ref actual : refs(caller, inputs.get(input))) result.add(new Edge(actual, EdgeKind.ARGUMENT,
								binding.captured ? "Captured lambda value" : "Argument/receiver at calling instruction " + binding.call.site().instruction(),
								binding.call.kind() != CallKind.DIRECT, binding, false));
					}
				}
				return result;
			}
			if (!prepare(data) || !data.reachable(ref.index)) return result;
			AbstractInsnNode instruction = data.instructions[ref.index];
			if (ref.mode == Mode.SINK || ref.mode == Mode.EXECUTION) {
				result.add(new Edge(new Ref(ref.method, -1, Mode.ENTRY), EdgeKind.EXECUTION, "Enclosing method invocation", false, null, false));
				controls(data, ref.index, result);
				if (ref.mode == Mode.SINK) operands(data, instruction, result, EdgeKind.VALUE, "Sink argument/receiver", false);
				return result;
			}
			controls(data, ref.index, result);
			if (instruction instanceof MethodInsnNode call) {
				String knownModel = FlowModels.propagation(call);
				if (knownModel != null) {
					if (knownModel.startsWith("Container")) gaps.add("Container mutation/key-specific contents are not tracked at " + ref.method);
					operands(data, instruction, result, EdgeKind.MODEL, knownModel, true);
					return result;
				}
				List<Call> targets = calls(ref.method).stream().filter(c -> c.site().instruction() == data.ordinal[ref.index] && c.kind() != CallKind.SCHEDULED && c.target() != null).toList();
				if (!targets.isEmpty()) {
					for (Call target : targets) {
						Binding binding = incoming.get(target.target()).stream().filter(b -> b.call.equals(target)).findFirst().orElseThrow();
						MethodData callee = methods.get(target.target());
						if (!prepare(callee)) continue;
						for (int i = 0; i < callee.instructions.length; i++) {
							int op = callee.instructions[i].getOpcode();
							if (op >= IRETURN && op <= ARETURN && callee.reachable(i)) result.add(new Edge(new Ref(target.target(), i, Mode.VALUE), EdgeKind.RETURN,
									"Return to call instruction " + data.ordinal[ref.index], target.kind() != CallKind.DIRECT, binding, true));
						}
					}
				} else {
					String model = FlowModels.propagation(call);
					if (model == null) gaps.add("Unknown return/side effects: " + call.owner + '.' + call.name + call.desc + " at " + ref.method);
					else operands(data, instruction, result, EdgeKind.MODEL, model, true);
				}
			} else if (instruction instanceof FieldInsnNode field && (field.getOpcode() == GETFIELD || field.getOpcode() == GETSTATIC)) {
				List<ClassData> definitions = definitions(ref.method.resource(), field.owner);
				for (Ref write : writes.getOrDefault(fieldKey(field), List.of())) {
					if (definitions.size() != 1 || !write.method.resource().equals(definitions.getFirst().scope)) continue;
					result.add(new Edge(write, EdgeKind.FIELD, "Possible write to " + fieldKey(field) + "; receiver alias/order not proven", true, null, false));
				}
				if (field.getOpcode() == GETFIELD) operands(data, instruction, result, EdgeKind.FIELD,
						"Field " + field.name + " from receiver (object-level heap approximation)", true);
				gaps.add("Heap approximation for " + fieldKey(field) + ": field writes are not ordered or receiver-sensitive");
			} else if (instruction instanceof InvokeDynamicInsnNode indy) {
				if (indy.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) operands(data, instruction, result, EdgeKind.MODEL, "JDK string concatenation", false);
				else gaps.add("Unresolved dynamic value: " + indy.bsm.getOwner() + '.' + indy.bsm.getName());
			} else {
				int opcode = instruction.getOpcode();
				boolean permutation = (opcode >= DUP_X1 && opcode <= DUP2_X2) || opcode == SWAP;
				boolean array = opcode >= IALOAD && opcode <= SALOAD;
				if (array) gaps.add("Array element writes/aliases are not tracked at " + ref.method);
				if (opcode == NEW || opcode == NEWARRAY || opcode == ANEWARRAY || opcode == MULTIANEWARRAY)
					gaps.add("Constructor/mutation contents of allocated objects are not tracked at " + ref.method);
				operands(data, instruction, result, permutation || array ? EdgeKind.ASSUMED : EdgeKind.VALUE,
						permutation ? "Stack permutation may merge operand origins" : array ? "Array reference/index approximation" : null, permutation || array);
			}
			return result;
		}

		boolean prepare(MethodData data) {
			boolean ready = data.prepare(cancelled);
			if (data.failure != null) gaps.add(data.view.id() + ": " + data.failure);
			return ready;
		}

		void controls(MethodData data, int raw, List<Edge> edges) {
			data.controls(cancelled);
			if (data.controlGap != null) gaps.add(data.view.id() + ": " + data.controlGap);
			for (int branch : data.control.getOrDefault(raw, Set.of())) {
				Ref ref = new Ref(data.view.id(), branch, Mode.VALUE);
				String description = "Controlling branch: " + data.describeGuard(branch);
				conditions.putIfAbsent(ref, step(ref, EdgeKind.CONTROL, false, description));
				edges.add(new Edge(ref, EdgeKind.CONTROL, description, false, null, false));
			}
		}

		void operands(MethodData data, AbstractInsnNode instruction, List<Edge> edges, EdgeKind kind, String detail, boolean assumed) {
			List<Set<AbstractInsnNode>> inputs = data.inputs.getOrDefault(instruction, List.of());
			// A field store's receiver does not determine the value written into the field.
			if (instruction.getOpcode() == PUTFIELD && inputs.size() == 2) inputs = inputs.subList(1, 2);
			for (Set<AbstractInsnNode> input : inputs) for (Ref source : refs(data, input)) edges.add(new Edge(source, kind, detail, assumed, null, false));
		}
		List<Ref> refs(MethodData data, Set<AbstractInsnNode> sources) {
			List<Ref> result = new ArrayList<>();
			for (AbstractInsnNode source : sources) {
				Integer parameter = data.parameters.get(source), index = data.index.get(source);
				if (parameter != null) result.add(new Ref(data.view.id(), parameter, Mode.PARAMETER));
				else if (index != null) result.add(new Ref(data.view.id(), index, Mode.VALUE));
			}
			return result;
		}
		int parameterInput(MethodData target, Binding binding, int slot) {
			int current = (target.node.access & ACC_STATIC) == 0 ? 1 : 0;
			int input = (target.node.access & ACC_STATIC) == 0 ? 1 : 0;
			if (slot == 0 && current == 1) return 0;
			for (Type arg : Type.getArgumentTypes(target.node.desc)) {
				if (slot == current) return input;
				current += arg.getSize(); input++;
			}
			return -1;
		}
		Step step(Ref ref, EdgeKind edge, boolean assumed, String detail) {
			MethodData data = methods.get(ref.method);
			String description = switch (ref.mode) {
				case ENTRY -> data.view.entryReason() == null ? "Method entry" : data.view.entryReason();
				case PARAMETER -> "Parameter/local slot " + ref.index;
				default -> data.describe(ref.index);
			};
			return new Step(ref.method, ref.mode == Mode.ENTRY || ref.mode == Mode.PARAMETER ? -1 : data.ordinal[ref.index],
					detail == null ? description : detail + " · " + description, edge, assumed);
		}
	}

	/** SourceInterpreter records the full def-use relation, including merged origins, without requiring dependencies. */
	private static final class MethodData {
		Method view;
		final MethodNode node;
		final AbstractInsnNode[] instructions;
		final Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
		final Map<AbstractInsnNode, Integer> parameters = new IdentityHashMap<>();
		final Map<AbstractInsnNode, List<Set<AbstractInsnNode>>> inputs = new IdentityHashMap<>();
		final Map<Integer, Set<Integer>> successors = new HashMap<>(), control = new HashMap<>();
		final int[] ordinal;
		Frame<SourceValue>[] frames;
		String failure, controlGap;
		boolean prepared, controlsPrepared;
		MethodData(Method view, MethodNode node) {
			this.view = view; this.node = node; instructions = node.instructions.toArray(); ordinal = new int[instructions.length];
			int position = 0;
			for (int i = 0; i < instructions.length; i++) { index.put(instructions[i], i); ordinal[i] = instructions[i].getOpcode() < 0 ? -1 : position++; }
		}
		int raw(int opOrdinal) { for (int i = 0; i < ordinal.length; i++) if (ordinal[i] == opOrdinal) return i; return -1; }
		boolean reachable(int raw) { return frames != null && raw >= 0 && raw < frames.length && frames[raw] != null; }
		boolean payloadSlot(int slot) {
			int current = (node.access & ACC_STATIC) == 0 ? 1 : 0;
			Type[] arguments = Type.getArgumentTypes(node.desc);
			for (int arg = 0; arg < arguments.length; arg++) {
				Type type = arguments[arg];
				if (current == slot) return Set.of("Ljava/lang/String;", "Ljava/lang/CharSequence;", "[B", "Ljava/nio/ByteBuffer;").contains(type.getDescriptor()) ||
						(node.name.startsWith("channelRead") && arg == arguments.length - 1);
				current += type.getSize();
			}
			return false;
		}
		boolean prepare(BooleanSupplier cancelled) {
			if (prepared) return failure == null;
			check(cancelled);
			if (instructions.length == 0 || instructions.length > 20_000) { prepared = true; failure = "No bytecode or method exceeds 20,000-instruction analysis limit"; return false; }
			if ((long) instructions.length * (node.maxLocals + node.maxStack) > 2_000_000) {
				prepared = true; failure = "Method exceeds 2,000,000 estimated frame slots"; return false;
			}
			try {
				SourceInterpreter interpreter = new SourceInterpreter(ASM9) {
					@Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
						VarInsnNode parameter = new VarInsnNode(type.getOpcode(ILOAD), local); parameters.put(parameter, local);
						return new SourceValue(type.getSize(), parameter);
					}
					void record(AbstractInsnNode insn, List<? extends SourceValue> values) {
						check(cancelled);
						List<Set<AbstractInsnNode>> previous = inputs.computeIfAbsent(insn, _ -> new ArrayList<>());
						while (previous.size() < values.size()) previous.add(new LinkedHashSet<>());
						for (int i = 0; i < values.size(); i++) previous.get(i).addAll(values.get(i).insns);
					}
					@Override public SourceValue copyOperation(AbstractInsnNode i, SourceValue v) { record(i, List.of(v)); return super.copyOperation(i, v); }
					@Override public SourceValue unaryOperation(AbstractInsnNode i, SourceValue v) { record(i, List.of(v)); return super.unaryOperation(i, v); }
					@Override public SourceValue binaryOperation(AbstractInsnNode i, SourceValue a, SourceValue b) { record(i, List.of(a, b)); return super.binaryOperation(i, a, b); }
					@Override public SourceValue ternaryOperation(AbstractInsnNode i, SourceValue a, SourceValue b, SourceValue c) { record(i, List.of(a, b, c)); return super.ternaryOperation(i, a, b, c); }
					@Override public SourceValue naryOperation(AbstractInsnNode i, List<? extends SourceValue> v) { record(i, v); return super.naryOperation(i, v); }
					@Override public void returnOperation(AbstractInsnNode i, SourceValue value, SourceValue expected) { record(i, List.of(value)); }
				};
				Analyzer<SourceValue> analyzer = new Analyzer<>(interpreter) {
					@Override protected void newControlFlowEdge(int source, int target) { successors.computeIfAbsent(source, _ -> new LinkedHashSet<>()).add(target); }
				};
				frames = analyzer.analyze(view.id().location().className(), node);
				prepared = true;
				return true;
			} catch (CancellationException ex) { inputs.clear(); parameters.clear(); successors.clear(); throw ex; }
			catch (AnalyzerException | RuntimeException ex) { prepared = true; failure = "Bytecode analysis failed: " + ex.getMessage(); return false; }
		}

		void controls(BooleanSupplier cancelled) {
			if (controlsPrepared) return;
			if (instructions.length > 1800) { controlGap = "Control dependencies omitted above 1,800 instructions"; controlsPrepared = true; return; }
			if (!node.tryCatchBlocks.isEmpty()) controlGap = "Exception-dependent control flow is not modeled";
			int n = instructions.length;
			List<Set<Integer>> next = new ArrayList<>();
			List<Set<Integer>> reverse = new ArrayList<>();
			for (int i = 0; i <= n; i++) reverse.add(new HashSet<>());
			for (int i = 0; i < n; i++) {
				Set<Integer> targets = new HashSet<>(successors.getOrDefault(i, Set.of()));
				if (targets.isEmpty()) targets.add(n);
				next.add(targets); for (int target : targets) reverse.get(target).add(i);
			}
			BitSet exits = new BitSet(n + 1); Deque<Integer> queue = new ArrayDeque<>(); queue.add(n);
			while (!queue.isEmpty()) { int i = queue.removeFirst(); if (exits.get(i)) continue; exits.set(i); queue.addAll(reverse.get(i)); }
			BitSet[] post = new BitSet[n + 1];
			for (int i = 0; i <= n; i++) { post[i] = new BitSet(n + 1); if (i == n) post[i].set(n); else post[i].set(0, n + 1); }
			for (int i = 0; i < n; i++) if (!exits.get(i)) { next.get(i).add(n); controlGap = "Non-terminating regions use conservative control dependencies"; }
			boolean changed;
			do {
				check(cancelled); changed = false;
				for (int i = n - 1; i >= 0; i--) {
					BitSet updated = new BitSet(n + 1); updated.set(0, n + 1);
					for (int target : next.get(i)) updated.and(post[target]);
					updated.set(i); if (!updated.equals(post[i])) { post[i] = updated; changed = true; }
				}
			} while (changed);
			int[] immediate = new int[n + 1]; Arrays.fill(immediate, n);
			for (int i = 0; i < n; i++) {
				int largest = -1;
				for (int candidate = post[i].nextSetBit(0); candidate >= 0; candidate = post[i].nextSetBit(candidate + 1))
					if (candidate != i && post[candidate].cardinality() > largest) { immediate[i] = candidate; largest = post[candidate].cardinality(); }
			}
			for (int branch = 0; branch < n; branch++) {
				if (!(instructions[branch] instanceof JumpInsnNode jump && jump.getOpcode() != GOTO) &&
						!(instructions[branch] instanceof TableSwitchInsnNode) && !(instructions[branch] instanceof LookupSwitchInsnNode)) continue;
				for (int successor : next.get(branch)) {
					int runner = successor; Set<Integer> seen = new HashSet<>();
					while (runner != immediate[branch] && runner < n && seen.add(runner)) {
						control.computeIfAbsent(runner, _ -> new LinkedHashSet<>()).add(branch); runner = immediate[runner];
					}
				}
			}
			controlsPrepared = true;
		}
		String describeGuard(int raw) {
			Set<String> calls = new LinkedHashSet<>();
			Deque<AbstractInsnNode> queue = new ArrayDeque<>(); queue.add(instructions[raw]);
			Set<AbstractInsnNode> seen = new HashSet<>();
			while (!queue.isEmpty() && seen.size() < 32) {
				AbstractInsnNode insn = queue.removeFirst(); if (!seen.add(insn)) continue;
				if (insn instanceof MethodInsnNode call) calls.add(call.owner + '.' + call.name);
				else for (var sources : inputs.getOrDefault(insn, List.of())) queue.addAll(sources);
			}
			return describe(raw) + (calls.isEmpty() ? "" : " using " + String.join(", ", calls));
		}
		String describe(int raw) {
			AbstractInsnNode insn = instructions[raw];
			String op = insn.getOpcode() < 0 ? "label" : Printer.OPCODES[insn.getOpcode()];
			return op + switch (insn) {
				case MethodInsnNode call -> " " + call.owner + '.' + call.name + call.desc;
				case FieldInsnNode field -> " " + fieldKey(field);
				case VarInsnNode var -> " local " + var.var;
				case LdcInsnNode ldc -> " " + String.valueOf(ldc.cst).replace('\n', ' ').substring(0, Math.min(100, String.valueOf(ldc.cst).length()));
				case IntInsnNode value -> " " + value.operand;
				default -> "";
			};
		}
	}
}
