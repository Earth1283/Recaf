package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationReport.*;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.util.ClassMethodPair;
import software.coley.recaf.util.EscapeUtil;
import software.coley.recaf.util.analysis.eval.*;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.util.analysis.value.StringValue;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.objectweb.asm.Opcodes.*;

/**
 * Resource-scoped bytecode inventory, dynamic-call inspection, and bounded constant-input string evaluation.
 * Inspects class files directly and interprets selected helpers without loading input classes into the JVM.
 */
@ApplicationScoped
public class BytecodeInvestigationService implements Service {
	public static final String SERVICE_ID = "bytecode-investigation";
	private final BytecodeInvestigationConfig config;

	@Inject
	public BytecodeInvestigationService(@Nonnull BytecodeInvestigationConfig config) {
		this.config = config;
	}

	/** Analyze the selected resource and embedded descendants, retaining their exact paths. */
	@Nonnull
	public BytecodeInvestigationReport analyze(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource,
	                                          @Nonnull BooleanSupplier cancelled) {
		List<MethodProfile> methods = new ArrayList<>();
		List<CallSite> sites = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		List<ResourceEntry> resources = resources(resource);
		int classes = 0;
		for (ResourceEntry entry : resources) {
			for (JvmClassBundle bundle : entry.resource().jvmAllClassBundleStream().toList()) {
				for (JvmClassInfo info : bundle.values().stream().sorted(Comparator.comparing(JvmClassInfo::getName)).toList()) {
					checkCancelled(cancelled);
					classes++;
					try {
						ClassNode node = read(info);
						for (MethodNode method : node.methods) {
							checkCancelled(cancelled);
							var member = info.getDeclaredMethod(method.name, method.desc);
							if (member == null) continue;
							ClassMemberPathNode path = PathNodes.memberPath(workspace, entry.resource(), bundle, info, member);
							inspect(path, entry.name() + (bundle instanceof VersionedJvmClassBundle versioned ? " (Java " + versioned.version() + ")" : ""), method, methods, sites);
						}
					} catch (CancellationException ex) {
						throw ex;
					} catch (Exception ex) {
						failures.add(entry.name() + ": " + info.getName() + ": " + ex.getMessage());
					}
				}
			}
		}
		methods.sort(Comparator.comparingInt(MethodProfile::score).reversed().thenComparing(MethodProfile::methodName).thenComparing(MethodProfile::resource));
		Map<String, List<CallSite>> groups = new TreeMap<>();
		for (CallSite site : sites) {
			if (site.dynamic())
				groups.computeIfAbsent(site.method().resource() + '\0' + site.targetName(), _ -> new ArrayList<>()).add(site);
		}
		List<BootstrapGroup> bootstraps = new ArrayList<>();
		for (List<CallSite> group : groups.values()) {
			CallSite first = group.getFirst();
			bootstraps.add(new BootstrapGroup(first.target(), first.method().resource(), List.copyOf(group), resolve(workspace, first.method().path(), first.target())));
		}
		bootstraps.sort(Comparator.comparingInt((BootstrapGroup group) -> group.sites().size()).reversed()
				.thenComparing(group -> group.target().owner()).thenComparing(BootstrapGroup::resource));
		return new BytecodeInvestigationReport(classes, List.copyOf(methods), List.copyOf(sites), List.copyOf(bootstraps), List.copyOf(failures));
	}

	private static void inspect(ClassMemberPathNode path, String resource, MethodNode method,
	                            List<MethodProfile> methods, List<CallSite> sites) {
		int instructions = 0, branches = 0, bits = 0, dynamic = 0, encoded = 0, crypto = 0, reflection = 0, loading = 0;
		for (AbstractInsnNode insn : method.instructions) {
			int op = insn.getOpcode();
			if (op < 0) continue;
			instructions++;
			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) branches++;
			if ((op >= ISHL && op <= LXOR) || isBitHelper(insn)) bits++;
			if (insn instanceof InvokeDynamicInsnNode) dynamic++;
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text && looksEncoded(text)) encoded++;
			if (insn instanceof MethodInsnNode call) {
				if (call.owner.startsWith("javax/crypto/")) crypto++;
				if (call.owner.startsWith("java/lang/reflect/") || call.owner.startsWith("java/lang/invoke/")
						|| (call.owner.equals("java/lang/Class") && (call.name.startsWith("getDeclared") || call.name.equals("forName")))) reflection++;
				if (call.name.equals("defineClass") || call.name.equals("loadClass") || call.owner.endsWith("ClassLoader")) loading++;
			}
		}
		boolean helper = (method.access & ACC_STATIC) != 0 && Type.getReturnType(method.desc).equals(Type.getType(String.class))
				&& (crypto > 0 || bits > 0 || (method.access & ACC_PRIVATE) != 0);
		MethodProfile profile = new MethodProfile(path, resource, instructions, branches, method.tryCatchBlocks.size(), bits,
				dynamic, encoded, crypto, reflection, loading, helper);
		methods.add(profile);
		int ordinal = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() < 0) continue;
			if (insn instanceof InvokeDynamicInsnNode indy) {
				var handle = indy.bsm;
				boolean standard = handle.getOwner().equals("java/lang/invoke/LambdaMetafactory") || handle.getOwner().equals("java/lang/invoke/StringConcatFactory");
				sites.add(new CallSite(profile, ordinal, indy.name, indy.desc, new MethodRef(handle.getOwner(), handle.getName(), handle.getDesc()),
						Arrays.stream(indy.bsmArgs).map(BytecodeInvestigationService::literal).toList(), true, standard));
			} else if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && isStringCall(call)) {
				sites.add(new CallSite(profile, ordinal, call.name, call.desc, new MethodRef(call.owner, call.name, call.desc), List.of(), false, false));
			}
			ordinal++;
		}
	}

	/** Inspect surrounding opcodes on demand instead of retaining copies for every call site. */
	@Nonnull
	public String describe(@Nonnull CallSite site) {
		StringBuilder text = new StringBuilder(site.dynamic() ? "INVOKEDYNAMIC" : "STATIC STRING CALL");
		text.append("\nResource: ").append(site.method().resource()).append("\nCaller: ").append(site.method().methodName())
				.append("\nInstruction: ").append(site.instruction()).append("\nCall: ").append(site.name()).append(site.descriptor())
				.append(site.dynamic() ? "\nBootstrap: " : "\nTarget: ").append(site.targetName());
		for (int i = 0; i < site.bootstrapArguments().size(); i++)
			text.append("\nBootstrap argument ").append(i).append(": ").append(site.bootstrapArguments().get(i));
		if (site.dynamic()) text.append("\nKind: ").append(site.standardBootstrap() ? "JDK lambda/string concatenation" : "Custom bootstrap");
		ClassNode node = read(site.method().path().getParent().getValue().asJvmClass());
		MethodNode method = method(node, site.method().path());
		text.append("\n\nBytecode context:\n");
		int ordinal = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() < 0) continue;
			if (ordinal >= site.instruction() - 8 && ordinal <= site.instruction() + 5)
				text.append(ordinal == site.instruction() ? "> " : "  ").append(ordinal).append(": ").append(instruction(insn)).append('\n');
			ordinal++;
		}
		return text.toString();
	}

	/** Recover arguments at a selected static call and interpret the target with a fresh, bounded state. */
	@Nonnull
	public Evaluation evaluate(@Nonnull Workspace workspace, @Nonnull CallSite site, int maxSteps, boolean initializeClasses) {
		if (maxSteps < 100 || maxSteps > 1_000_000)
			throw new IllegalArgumentException("Instruction budget must be between 100 and 1000000");
		if (site.dynamic())
			return result(Evaluation.Status.UNSUPPORTED, List.of(), "Custom bootstrap execution is not inferred; inspect its target and string helpers instead.", 0);
		ClassMemberPathNode callerPath = site.method().path();
		JvmClassInfo original = callerPath.getParent().getValue().asJvmClass();
		JvmClassBundle callerBundle = callerPath.getValueOfType(JvmClassBundle.class);
		if (callerBundle == null || callerBundle.get(original.getName()) != original)
			return result(Evaluation.Status.STALE, List.of(), "The caller changed after scanning. Rescan before evaluating.", 0);
		List<String> inputs = new ArrayList<>();
		AtomicInteger executed = new AtomicInteger();
		try {
			WorkspaceResource scope = Objects.requireNonNull(callerPath.getValueOfType(WorkspaceResource.class));
			List<WorkspaceResource> support = new ArrayList<>();
			if (scope != workspace.getPrimaryResource()) support.add(workspace.getPrimaryResource());
			support.addAll(workspace.getSupportingResources());
			Workspace view = new BasicWorkspace(scope, support);
			InheritanceGraph graph = new InheritanceGraph(view, false);
			JvmTransformerContext context = new JvmTransformerContext(view, scope, List.of());
			ClassNode node = read(original);
			MethodNode caller = method(node, callerPath);
			if (caller.instructions.size() > 100_000)
				return result(Evaluation.Status.UNSUPPORTED, inputs, "Caller exceeds the 100000-instruction analysis limit.", 0);
			int index = index(caller, site.instruction());
			if (index < 0 || !(caller.instructions.get(index) instanceof MethodInsnNode call)
					|| !site.target().equals(new MethodRef(call.owner, call.name, call.desc)))
				return result(Evaluation.Status.STALE, inputs, "The selected call no longer matches the scan.", 0);
			var frames = context.analyze(graph, node, caller);
			var frame = frames[index];
			Type[] types = Type.getArgumentTypes(call.desc);
			if (frame == null || frame.getStackSize() < types.length)
				return result(Evaluation.Status.UNKNOWN_INPUTS, inputs, "The call is unreachable or has no recoverable stack state.", 0);
			List<ReValue> arguments = new ArrayList<>();
			boolean known = true;
			for (int i = 0; i < types.length; i++) {
				ReValue value = frame.getStack(frame.getStackSize() - types.length + i);
				arguments.add(value);
				inputs.add(value instanceof StringValue string && string.getText().isPresent() ? literal(string.getText().get()) : value.toString());
				known &= value.hasKnownValue();
			}
			if (!known)
				return result(Evaluation.Status.UNKNOWN_INPUTS, inputs, "One or more arguments depend on unknown runtime values. Try constant/static-value folding first.", 0);
			ClassMemberPathNode targetPath = resolve(workspace, callerPath, site.target());
			if (targetPath == null)
				return result(Evaluation.Status.UNSUPPORTED, inputs, "The target is not a workspace method.", 0);
			ClassNode target = read(targetPath.getParent().getValue().asJvmClass());
			MethodNode targetMethod = method(target, targetPath);
			Evaluator evaluator = new Evaluator(view, context.newInterpreter(graph), new FieldCacheManager(), maxSteps, false, initializeClasses);
			evaluator.setAllowUnsupportedUnreachableInstructions(true);
			evaluator.registerClassNodeOverride(node);
			evaluator.registerClassNodeOverride(target);
			evaluator.setCallStackSeed(List.of(new ClassMethodPair(node, caller)));
			evaluator.addListener((owner, evaluatedMethod, instruction, evaluatedFrame) -> {
				if (Thread.currentThread().isInterrupted()) throw new CancellationException();
				executed.incrementAndGet();
			});
			EvaluationResult evaluation = evaluator.evaluate(target, targetMethod, null, arguments);
			if (evaluation instanceof EvaluationYieldResult(StringValue value) && value.getText().isPresent())
				return new Evaluation(Evaluation.Status.VALUE, List.copyOf(inputs), value.getText().get(), "Interpreted without modifying the workspace.", executed.get());
			if (evaluation instanceof EvaluationFailureResult failure)
				return result(Evaluation.Status.FAILED, inputs, failure.reason(), executed.get());
			return result(Evaluation.Status.FAILED, inputs, evaluation.toString(), executed.get());
		} catch (Exception ex) {
			return result(Evaluation.Status.FAILED, inputs, ex.getClass().getSimpleName() + ": " + ex.getMessage(), executed.get());
		}
	}

	/** Resolve within the caller's exact resource before consulting the rest of the workspace. */
	@Nullable
	public static ClassMemberPathNode resolve(@Nonnull Workspace workspace, @Nonnull ClassMemberPathNode caller, @Nonnull MethodRef target) {
		WorkspaceResource resource = caller.getValueOfType(WorkspaceResource.class);
		if (resource != null) {
			List<JvmClassBundle> bundles = new ArrayList<>(resource.jvmAllClassBundleStream().toList());
			JvmClassBundle callerBundle = caller.getValueOfType(JvmClassBundle.class);
			if (callerBundle != null) { bundles.remove(callerBundle); bundles.addFirst(callerBundle); }
			for (JvmClassBundle bundle : bundles) {
				JvmClassInfo info = bundle.get(target.owner());
				if (info != null && info.getDeclaredMethod(target.name(), target.desc()) != null)
					return PathNodes.memberPath(workspace, resource, bundle, info, info.getDeclaredMethod(target.name(), target.desc()));
			}
		}
		var path = workspace.findJvmClass(target.owner());
		return path == null ? null : path.child(target.name(), target.desc());
	}

	private static Evaluation result(Evaluation.Status status, List<String> inputs, String reason, int instructions) {
		return new Evaluation(status, List.copyOf(inputs), null, reason, instructions);
	}

	private static int index(MethodNode method, int ordinal) {
		int count = 0;
		for (int i = 0; i < method.instructions.size(); i++)
			if (method.instructions.get(i).getOpcode() >= 0 && count++ == ordinal) return i;
		return -1;
	}

	private static MethodNode method(ClassNode node, ClassMemberPathNode path) {
		return node.methods.stream().filter(m -> m.name.equals(path.getValue().getName()) && m.desc.equals(path.getValue().getDescriptor())).findFirst().orElseThrow();
	}

	private static ClassNode read(JvmClassInfo info) {
		ClassNode node = new ClassNode();
		info.getClassReader().accept(node, ClassReader.SKIP_DEBUG);
		return node;
	}

	private static boolean isStringCall(MethodInsnNode call) {
		return !call.owner.startsWith("java/") && !call.owner.startsWith("javax/")
				&& Type.getReturnType(call.desc).equals(Type.getType(String.class)) && Type.getArgumentCount(call.desc) > 0;
	}

	private static boolean isBitHelper(AbstractInsnNode instruction) {
		return instruction instanceof MethodInsnNode call && (call.owner.equals("java/lang/Integer") || call.owner.equals("java/lang/Long"))
				&& Set.of("reverse", "reverseBytes", "rotateLeft", "rotateRight", "bitCount", "numberOfLeadingZeros", "numberOfTrailingZeros", "highestOneBit", "lowestOneBit").contains(call.name);
	}

	private static boolean looksEncoded(String text) {
		return text.length() >= 8 && text.chars().filter(c -> Character.isISOControl(c) || (c >= 0x80 && !Character.isWhitespace(c))).count() * 2 > text.length();
	}

	@Nonnull
	public static String literal(@Nullable Object value) {
		if (value instanceof String text) return '"' + EscapeUtil.escapeStandard(text) + '"';
		return String.valueOf(value);
	}

	private static String instruction(AbstractInsnNode insn) {
		String opcode = Printer.OPCODES[insn.getOpcode()].toLowerCase(Locale.ROOT);
		return opcode + switch (insn) {
			case MethodInsnNode call -> " " + call.owner + "." + call.name + call.desc;
			case InvokeDynamicInsnNode call -> " " + call.name + call.desc;
			case FieldInsnNode field -> " " + field.owner + "." + field.name + " " + field.desc;
			case LdcInsnNode ldc -> " " + literal(ldc.cst);
			case VarInsnNode var -> " " + var.var;
			case IntInsnNode integer -> " " + integer.operand;
			case TypeInsnNode type -> " " + type.desc;
			default -> "";
		};
	}

	private static List<ResourceEntry> resources(WorkspaceResource root) {
		List<ResourceEntry> result = new ArrayList<>();
		Set<WorkspaceResource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<ResourceEntry> pending = new ArrayDeque<>();
		pending.add(new ResourceEntry(root, root instanceof WorkspaceFileResource file ? file.getFileInfo().getName() : "Selected resource"));
		while (!pending.isEmpty()) {
			ResourceEntry entry = pending.removeFirst();
			if (!seen.add(entry.resource())) continue;
			result.add(entry);
			entry.resource().getEmbeddedResources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(child ->
					pending.addLast(new ResourceEntry(child.getValue(), entry.name() + " > " + child.getKey())));
		}
		return result;
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
	}

	private record ResourceEntry(WorkspaceResource resource, String name) {}

	@Nonnull
	@Override
	public String getServiceId() { return SERVICE_ID; }

	@Nonnull
	@Override
	public BytecodeInvestigationConfig getServiceConfig() { return config; }
}
