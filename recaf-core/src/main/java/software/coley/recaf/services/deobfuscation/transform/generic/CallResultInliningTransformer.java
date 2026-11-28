package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationParameter;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.util.ClassMethodPair;
import software.coley.recaf.util.analysis.Nullness;
import software.coley.recaf.util.analysis.ReAnalyzer;
import software.coley.recaf.util.analysis.ReFrame;
import software.coley.recaf.util.analysis.ReInterpreter;
import software.coley.recaf.util.analysis.eval.EvaluationListener;
import software.coley.recaf.util.analysis.eval.EvaluationResult;
import software.coley.recaf.util.analysis.eval.EvaluationYieldResult;
import software.coley.recaf.util.analysis.eval.Evaluator;
import software.coley.recaf.util.analysis.eval.FieldCache;
import software.coley.recaf.util.analysis.eval.FieldCacheManager;
import software.coley.recaf.util.analysis.lookup.GetStaticLookup;
import software.coley.recaf.util.analysis.value.DoubleValue;
import software.coley.recaf.util.analysis.value.LongValue;
import software.coley.recaf.util.analysis.value.ObjectValue;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * A transformer that inlines method calls that can be fully evaluated.
 *
 * @author Matt Coley
 */
@Dependent
public class CallResultInliningTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "peephole.data.callinline";
	public static final String KEY_MAX_STEPS = IDENTIFIER + ".max-steps";
	public static final String KEY_EVALUATE_CLASS_INITIALIZERS = IDENTIFIER + ".evaluate-class-initializers";
	public static final String KEY_ALLOW_UNREACHABLE_UNSUPPORTED = IDENTIFIER + ".allow-unreachable-unsupported";

	private static final int DEFAULT_MAX_STEPS = 20_000;
	private static final boolean DEFAULT_EVALUATE_CLASS_INITIALIZERS = false;
	private static final TransformationParameter<Integer> MAX_STEPS_PARAMETER =
			new TransformationParameter<>(KEY_MAX_STEPS, int.class, DEFAULT_MAX_STEPS);
	private static final TransformationParameter<Boolean> EVALUATE_CLASS_INITIALIZERS_PARAMETER =
			new TransformationParameter<>(KEY_EVALUATE_CLASS_INITIALIZERS, boolean.class, DEFAULT_EVALUATE_CLASS_INITIALIZERS);
	private static final TransformationParameter<Boolean> ALLOW_UNREACHABLE_UNSUPPORTED_PARAMETER =
			new TransformationParameter<>(KEY_ALLOW_UNREACHABLE_UNSUPPORTED, boolean.class, false);

	private final InheritanceGraphService graphService;

	private InheritanceGraph inheritanceGraph;

	@Inject
	public CallResultInliningTransformer(@Nonnull InheritanceGraphService graphService) {
		this.graphService = graphService;
	}

	@Override
	public void setup(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace) {
		inheritanceGraph = graphService.getOrCreateInheritanceGraph(workspace);
	}

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		boolean dirty = false;
		String className = initialClassState.getName();
		ClassNode node = context.getNode(bundle, initialClassState);
		StaticValueCollectionTransformer staticValueCollector = context.getOptionalTransformer(StaticValueCollectionTransformer.class);

		// The transformer instance is shared across classes transformed in parallel, so the evaluator
		// and its field cache must be scoped to this invocation rather than stored as instance state.
		// We used to have a shared evaluator + cache, but that caused issues with the parallel evaluation
		// of multiple classes, where the field cache would be polluted by other threads.
		int maxSteps = context.getParameters().getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS);
		boolean evaluateClassInitializers = context.getParameters().getBoolean(KEY_EVALUATE_CLASS_INITIALIZERS, DEFAULT_EVALUATE_CLASS_INITIALIZERS);
		Set<ReValue> localAllocations = Collections.newSetFromMap(new IdentityHashMap<>());
		ReValue[] lastWrittenInstance = new ReValue[1];
		boolean[] hasSideEffect = new boolean[1];
		FieldCacheManager fieldCacheManager = new FieldCacheManager() {
			@Nonnull
			@Override
			public FieldCache getInstanceFieldCache(@Nonnull ReValue instance) {
				return instanceFields.computeIfAbsent(instance, ignored -> new FieldCache() {
					@Override
					public void setField(@Nonnull String owner, @Nonnull String name, @Nonnull String desc, @Nonnull ReValue value) {
						lastWrittenInstance[0] = instance;
						super.setField(owner, name, desc, value);
					}
				});
			}
		};

		// If we have the static value collection transformer active we want to pull values from it when evaluating static fields.
		// So we'll create a lookup wrapper that bundles both the static value collection and the default lookup together.
		ReInterpreter evaluatorInterpreter = context.newInterpreter(inheritanceGraph);
		installStaticValueCollector(evaluatorInterpreter, staticValueCollector);

		Evaluator evaluator = new Evaluator(workspace, evaluatorInterpreter, fieldCacheManager, maxSteps, false, evaluateClassInitializers);
		evaluator.setAllowUnsupportedUnreachableInstructions(context.getParameters().getBoolean(KEY_ALLOW_UNREACHABLE_UNSUPPORTED, false));
		evaluator.setClassNodeProvider(context::resolveClassNode);
		evaluator.registerClassNodeOverride(node);
		evaluator.addListener(new EvaluationListener() {
			@Override
			public void onInstruction(@Nullable ClassNode classNode, @Nullable MethodNode methodNode,
			                          @Nonnull AbstractInsnNode instruction, @Nonnull ReFrame frame) {
				int op = instruction.getOpcode();
				if (op == Opcodes.NEW && frame.getStackSize() > 0) {
					localAllocations.add(frame.getStack(frame.getStackSize() - 1));
				} else if (op == Opcodes.PUTSTATIC && (methodNode == null || !"<clinit>".equals(methodNode.name))) {
					hasSideEffect[0] = true;
				} else if (op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT) {
					hasSideEffect[0] = true;
				} else if (op == Opcodes.PUTFIELD) {
					ReValue written = lastWrittenInstance[0];
					if (written == null || !localAllocations.contains(written))
						hasSideEffect[0] = true;
				}
			}
		});
		for (MethodNode method : node.methods) {
			// Skip if abstract.
			InsnList instructions = method.instructions;
			if (instructions == null)
				continue;

			Frame<ReValue>[] frames;
			try {
				ReAnalyzer analyzer = context.newAnalyzer(inheritanceGraph, node, method);
				installStaticValueCollector(analyzer.getInterpreter(), staticValueCollector);
				frames = analyzer.analyze(node.name, method);
			} catch (Throwable ex) {
				// Analysis failed, skip this method.
				continue;
			}
			for (int i = instructions.size() - 1; i >= 0; i--) {
				AbstractInsnNode insn = instructions.get(i);
				int opcode = insn.getOpcode();
				if ((opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL || opcode == Opcodes.INVOKEVIRTUAL)
						&& insn instanceof MethodInsnNode min) {
					if (opcode != Opcodes.INVOKESTATIC && "<init>".equals(min.name))
						continue;
					Frame<ReValue> frame = frames[i];
					if (frame == null)
						continue;

					// Collect arguments and receiver (for instance calls).
					Type methodType = Type.getMethodType(min.desc);
					int argCount = methodType.getArgumentCount();
					int requiredStack = argCount + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
					if (frame.getStackSize() < requiredStack)
						continue;

					List<ReValue> arguments = new ArrayList<>(argCount);
					for (int j = 0; j < argCount; j++)
						arguments.addFirst(frame.getStack(frame.getStackSize() - 1 - j));

					ObjectValue receiver = null;
					if (opcode != Opcodes.INVOKESTATIC) {
						ReValue rawReceiver = frame.getStack(frame.getStackSize() - 1 - argCount);
						receiver = resolveKnownNonNullReceiver(method, insn, rawReceiver);
						if (receiver == null)
							continue;
					}

					// Target method must be resolvable and evaluable.
					ClassMethodPair target = context.resolveMethod(min);
					if (target == null)
						continue;
					MethodNode targetMethod = target.methodNode();
					if (opcode == Opcodes.INVOKESTATIC) {
						if (!AccessFlag.isStatic(targetMethod.access))
							continue;
					} else {
						if (AccessFlag.isStatic(targetMethod.access))
							continue;
						if (opcode == Opcodes.INVOKESPECIAL && !target.classNode().name.equals(min.owner))
							continue;
						if (opcode == Opcodes.INVOKEVIRTUAL
								&& !AccessFlag.isPrivate(targetMethod.access)
								&& !AccessFlag.isFinal(targetMethod.access)
								&& !AccessFlag.isFinal(target.classNode().access))
							continue;
					}

					boolean allArgsKnown = arguments.isEmpty() || arguments.stream().allMatch(ReValue::hasKnownValue);
					if (!allArgsKnown) {
						boolean isStaticCall = opcode == Opcodes.INVOKESTATIC;
						if (methodReadsParameters(targetMethod, isStaticCall)
								&& (targetMethod.instructions == null || targetMethod.instructions.size() > 32 || hasBackwardsJump(targetMethod)))
							continue;
					}

					if (!evaluator.canEvaluate(targetMethod))
						continue;

					// Reset per-evaluation state to prevent state pollution.
					fieldCacheManager.reset();
					localAllocations.clear();
					lastWrittenInstance[0] = null;
					hasSideEffect[0] = false;

					// Seed the call stack so trace-dependent operations can be evaluated at depth [1].
					evaluator.setCallStackSeed(List.of(new ClassMethodPair(node, method)));

					// Attempt evaluation. If it yields a side-effect-free constant, replace the call with the result.
					EvaluationResult result = evaluator.evaluate(target.classNode(), targetMethod, receiver, arguments);
					if (!hasSideEffect[0] && result instanceof EvaluationYieldResult(ReValue retVal)) {
						AbstractInsnNode replacement = OpaqueConstantFoldingTransformer.toInsn(retVal);
						if (replacement != null) {
							for (int arg = arguments.size() - 1; arg >= 0; arg--) {
								ReValue argValue = arguments.get(arg);
								if (argValue instanceof LongValue || argValue instanceof DoubleValue)
									instructions.insertBefore(min, new InsnNode(Opcodes.POP2));
								else
									instructions.insertBefore(min, new InsnNode(Opcodes.POP));
							}
							if (opcode != Opcodes.INVOKESTATIC)
								instructions.insertBefore(min, new InsnNode(Opcodes.POP));
							instructions.set(min, replacement);
							context.recordInlinedMethod(target.classNode().name, targetMethod.name, targetMethod.desc);
							dirty = true;
						}
					}
				}
			}
		}
		if (dirty)
			context.setNode(bundle, initialClassState, node);
	}

	private static void installStaticValueCollector(@Nonnull ReInterpreter interpreter,
	                                                @Nullable StaticValueCollectionTransformer collector) {
		// Static value collector transformer isn't active.
		if (collector == null)
			return;

		// If the interpreter has no existing lookup, we can just set the collector as the lookup.
		GetStaticLookup fallback = interpreter.getGetStaticLookup();
		if (fallback == null) {
			interpreter.setGetStaticLookup(collector);
			return;
		}

		// Combine both the collector and the existing lookup so the evaluator can benefit from both.
		interpreter.setGetStaticLookup(new GetStaticLookup() {
			@Nonnull
			@Override
			public ReValue get(@Nonnull FieldInsnNode field) {
				return collector.hasLookup(field) ? collector.get(field) : fallback.get(field);
			}

			@Override
			public boolean hasLookup(@Nonnull FieldInsnNode field) {
				return collector.hasLookup(field) || fallback.hasLookup(field);
			}
		});
	}

	@Nullable
	private static ObjectValue resolveKnownNonNullReceiver(@Nonnull MethodNode caller,
	                                                       @Nonnull AbstractInsnNode callInsn,
	                                                       @Nonnull ReValue rawReceiver) {
		if (!(rawReceiver instanceof ObjectValue objectValue))
			return null;
		if (objectValue.isNotNull())
			return objectValue;
		// 'this' (local slot 0 in an instance method) is guaranteed non-null when loaded right before a zero-arg call.
		if (!AccessFlag.isStatic(caller.access)) {
			AbstractInsnNode prev = callInsn.getPrevious();
			while (prev != null && (prev.getOpcode() < 0 || prev.getOpcode() == Opcodes.NOP))
				prev = prev.getPrevious();
			if (prev instanceof VarInsnNode varInsn && varInsn.getOpcode() == Opcodes.ALOAD && varInsn.var == 0)
				return ObjectValue.object(objectValue.type(), Nullness.NOT_NULL);
		}
		return null;
	}

	private static boolean methodReadsParameters(@Nonnull MethodNode method, boolean isStatic) {
		if (method.instructions == null)
			return true;
		Type methodType = Type.getMethodType(method.desc);
		int slotLimit = isStatic ? 0 : 1;
		for (Type arg : methodType.getArgumentTypes())
			slotLimit += arg.getSize();
		if (slotLimit == 0)
			return false;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof VarInsnNode varInsn && varInsn.var < slotLimit)
				return true;
			if (insn instanceof IincInsnNode iinc && iinc.var < slotLimit)
				return true;
		}
		return false;
	}

	private static boolean hasBackwardsJump(@Nonnull MethodNode method) {
		if (method.instructions == null)
			return false;
		Set<AbstractInsnNode> seenLabels = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getType() == AbstractInsnNode.LABEL) {
				seenLabels.add(insn);
			} else if (insn instanceof JumpInsnNode jump && seenLabels.contains(jump.label)) {
				return true;
			}
		}
		return false;
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedPredecessors() {
		return Set.of(AlgebraicOpaquePredicateTransformer.class, StaticValueInliningTransformer.class);
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedSuccessors() {
		// This transformer results in the creation of a lot of POP/POP2 instructions.
		// The stack-operation folding transformer can clean up afterward.
		return Collections.singleton(OpaqueConstantFoldingTransformer.class);
	}

	@Nonnull
	@Override
	public String identifier() {
		return IDENTIFIER;
	}

	@Nonnull
	@Override
	public String beforeExample() {
		return """
				// Assuming 'xor(String, int)' is a static method, and the inputs are known
				// the result is also effectively known, so the call can be replaced with the result.
				return xor("Idmmn!vnsme", 0x010101);
				""";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return """
				return "Hello world";
				""";
	}

	@Nonnull
	@Override
	public List<TransformationParameter<?>> getParameterDefinitions() {
		return List.of(MAX_STEPS_PARAMETER, EVALUATE_CLASS_INITIALIZERS_PARAMETER, ALLOW_UNREACHABLE_UNSUPPORTED_PARAMETER);
	}
}
