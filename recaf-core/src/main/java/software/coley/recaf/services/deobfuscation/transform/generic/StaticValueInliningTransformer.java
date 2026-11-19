package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationParameter;
import software.coley.recaf.util.analysis.ReAnalyzer;
import software.coley.recaf.util.analysis.ReInterpreter;
import software.coley.recaf.util.analysis.value.ArrayValue;
import software.coley.recaf.util.analysis.value.IntValue;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer.toInsn;
import static software.coley.recaf.util.AsmInsnUtil.getControlFlowBoundaries;
import static software.coley.recaf.util.AsmInsnUtil.getDefaultValue;
import static software.coley.recaf.util.AsmInsnUtil.getNumericLiteral;
import static software.coley.recaf.util.AsmInsnUtil.intToInsn;
import static software.coley.recaf.util.AsmInsnUtil.isArrayLoad;
import static software.coley.recaf.util.AsmInsnUtil.isFlowControl;
import static software.coley.recaf.util.AsmInsnUtil.isMetaData;
import static software.coley.recaf.util.AsmInsnUtil.isReturn;

/**
 * A transformer that inlines values from {@link StaticValueCollectionTransformer}.
 *
 * @author Matt Coley
 */
@Dependent
public class StaticValueInliningTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "peephole.data.staticinline";
	public static final String KEY_AUTO_RESOLVE_VARIABLES = IDENTIFIER + ".auto-resolve-variables";
	private static final TransformationParameter<Boolean> AUTO_RESOLVE_VARIABLES =
			new TransformationParameter<>(KEY_AUTO_RESOLVE_VARIABLES, boolean.class, true);

	private final InheritanceGraphService graphService;
	private InheritanceGraph inheritanceGraph;

	@Inject
	public StaticValueInliningTransformer(@Nonnull InheritanceGraphService graphService) {
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
		var staticValueCollector = context.getTransformer(StaticValueCollectionTransformer.class);
		boolean autoResolve = context.getParameters().getBoolean(KEY_AUTO_RESOLVE_VARIABLES, AUTO_RESOLVE_VARIABLES.defaultValue());

		boolean dirty = false;
		ClassNode node = context.getNode(bundle, initialClassState);
		Set<String> initializationMethods = collectInitializationMethods(node);
		for (MethodNode method : node.methods) {
			// Resolve reads in the initializer using values at the point of each read.
			if (autoResolve && method.name.equals("<clinit>") && method.desc.equals("()V")
					&& (method.access & Opcodes.ACC_STATIC) != 0) {
				dirty |= resolveInitializerVariables(node, method);
				continue;
			}
			// Methods reachable during initialization may observe earlier field values.
			if (method.instructions == null || initializationMethods.contains(method.name + method.desc))
				continue;

			// Replace static field reads with their known values.
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction.getOpcode() != Opcodes.GETSTATIC || !(instruction instanceof FieldInsnNode fieldInsn))
					continue;

				// Reading a field of another class initializes it, which we cannot drop if it has other effects.
				if (!fieldInsn.owner.equals(node.name) && staticValueCollector.isInitializationObservable(fieldInsn.owner))
					continue;

				// Get replacement for the field value, if its representable as an inlinable constant.
				// Keep array reads intact until we know the read value of the array at some index is also inlinable.
				ReValue value = staticValueCollector.getStaticValue(fieldInsn.owner, fieldInsn.name, fieldInsn.desc);
				AbstractInsnNode replacement = fieldInsn.desc.startsWith("[") ? null : toInsn(value);
				if (replacement != null) {
					method.instructions.set(instruction, replacement);
					dirty = true;
				}
			}

			// Analyze after simple value replacement so array producers can use collected field values.
			Frame<ReValue>[] frames;
			try {
				ReAnalyzer analyzer = context.newAnalyzer(inheritanceGraph, node, method);
				ReInterpreter interpreter = analyzer.getInterpreter();
				interpreter.setGetStaticLookup(staticValueCollector);
				frames = analyzer.analyze(node.name, method);
			} catch (Throwable ignored) {
				// Analyzer failure, abort array read replacement for this method.
				// We still want to keep any static field reads that were replaced.
				continue;
			}

			// Second pass to replace array reads with their known values.
			AbstractInsnNode[] instructions = method.instructions.toArray();
			for (int i = 0; i < instructions.length; i++) {
				AbstractInsnNode instruction = instructions[i];
				if (!isArrayLoad(instruction.getOpcode()))
					continue;

				Frame<ReValue> frame = frames[i];
				if (frame == null || frame.getStackSize() < 2)
					continue;

				ReValue indexValue = frame.getStack(frame.getStackSize() - 1);
				if (!(indexValue instanceof IntValue intIndex) || intIndex.value().isEmpty())
					continue;

				ReValue arrayValue = frame.getStack(frame.getStackSize() - 2);
				if (!(arrayValue instanceof ArrayValue array)
						|| !array.isNotNull()
						|| array.dimensions() != 1
						|| array.getFirstDimensionLength().isEmpty())
					continue;

				int index = intIndex.value().getAsInt();
				if (index < 0 || index >= array.getFirstDimensionLength().getAsInt())
					continue;

				ReValue elementValue = array.getValue(index);
				AbstractInsnNode replacement = toInsn(elementValue);
				if (replacement == null)
					continue;

				// Consume the array and index while preserving producer side effects and stack shape.
				method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP2));
				method.instructions.set(instruction, replacement);
				dirty = true;
			}
		}

		// Record transformed class if we made any changes.
		if (dirty)
			context.setNode(bundle, initialClassState, node);
	}

	/** Tracks primitive fields in straight-line initializer code using literal stores and JVM initial values. */
	private static boolean resolveInitializerVariables(@Nonnull ClassNode node, @Nonnull MethodNode method) {
		if (method.instructions == null || method.instructions.size() == 0)
			return false;
		// Parent or default-method interface initializers may call back into this class before <clinit> starts.
		boolean canUseInitialValues = "java/lang/Object".equals(node.superName) && node.interfaces.isEmpty();
		Map<FieldKey, FieldNode> declaredFields = new HashMap<>();
		Map<FieldNode, AbstractInsnNode> knownValues = new HashMap<>();
		for (FieldNode field : node.fields) {
			if ((field.access & Opcodes.ACC_STATIC) == 0)
				continue;
			declaredFields.put(new FieldKey(field.name, field.desc), field);
			if (!canUseInitialValues || !isTrackable(field))
				continue;
			AbstractInsnNode initialValue = field.value == null ? getDefaultValue(Type.getType(field.desc))
					: primitiveFieldLiteral(field.desc, new LdcInsnNode(field.value));
			if (initialValue != null)
				knownValues.put(field, initialValue);
		}

		Set<LabelNode> boundaries = getControlFlowBoundaries(method);
		AbstractInsnNode previous = null;
		boolean dirty = false;
		for (AbstractInsnNode instruction : method.instructions) {
			int opcode = instruction.getOpcode();
			boolean flowBoundary = instruction instanceof FrameNode || (instruction instanceof LabelNode label && boundaries.contains(label))
					|| isFlowControl(instruction) || isReturn(opcode);
			boolean canRunOtherCode = (opcode >= Opcodes.INVOKEVIRTUAL && opcode <= Opcodes.INVOKEDYNAMIC)
					|| opcode == Opcodes.NEW || (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic);
			if (flowBoundary || canRunOtherCode) {
				knownValues.clear();
				previous = null;
				continue;
			}
			if (isMetaData(instruction) || opcode == Opcodes.NOP)
				continue;
			if (instruction instanceof FieldInsnNode access && (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC)) {
				FieldNode field = access.owner.equals(node.name) ? declaredFields.get(new FieldKey(access.name, access.desc)) : null;
				if (field == null) {
					// Resolving a foreign or inherited static field can run another initializer.
					knownValues.clear();
				} else if (opcode == Opcodes.PUTSTATIC && isTrackable(field)) {
					AbstractInsnNode literal = primitiveFieldLiteral(field.desc, previous);
					if (literal == null)
						knownValues.remove(field);
					else
						knownValues.put(field, literal);
				} else if (opcode == Opcodes.GETSTATIC) {
					AbstractInsnNode literal = knownValues.get(field);
					if (literal != null) {
						previous = literal.clone(Map.of());
						method.instructions.set(instruction, previous);
						dirty = true;
						continue;
					}
				}
			}
			previous = instruction;
		}
		return dirty;
	}

	private static boolean isTrackable(@Nonnull FieldNode field) {
		return (field.access & Opcodes.ACC_VOLATILE) == 0 && field.desc.length() == 1
				&& "ZBCSIJFD".indexOf(field.desc.charAt(0)) >= 0;
	}

	@Nullable
	private static AbstractInsnNode primitiveFieldLiteral(@Nonnull String descriptor, @Nullable AbstractInsnNode literal) {
		Number value = getNumericLiteral(literal);
		// The JVM narrows int stack values when storing into boolean, byte, char, or short fields.
		return switch (descriptor) {
			case "Z" -> value instanceof Integer integer ? intToInsn(integer & 1) : null;
			case "B" -> value instanceof Integer integer ? intToInsn(integer.byteValue()) : null;
			case "C" -> value instanceof Integer integer ? intToInsn((char) integer.intValue()) : null;
			case "S" -> value instanceof Integer integer ? intToInsn(integer.shortValue()) : null;
			case "I" -> value instanceof Integer ? literal : null;
			case "J" -> value instanceof Long ? literal : null;
			case "F" -> value instanceof Float ? literal : null;
			case "D" -> value instanceof Double ? literal : null;
			default -> null;
		};
	}

	private record FieldKey(String name, String descriptor) {}

	/**
	 * @param node
	 * 		Class to check.
	 *
	 * @return Keys <i>(name and descriptor)</i> of the static initializer and every method of the class
	 * that it can reach by calling other methods of the same class, such as helpers and constructors.
	 */
	@Nonnull
	private static Set<String> collectInitializationMethods(@Nonnull ClassNode node) {
		Map<String, MethodNode> methodsByKey = new HashMap<>();
		for (MethodNode method : node.methods)
			methodsByKey.put(method.name + method.desc, method);

		Set<String> reachable = new HashSet<>();
		Deque<MethodNode> queue = new ArrayDeque<>();
		MethodNode clinit = methodsByKey.get("<clinit>()V");
		if (clinit != null) {
			reachable.add("<clinit>()V");
			queue.add(clinit);
		}

		// Walk calls to other methods of the same class, and add them to the reachable set.
		while (!queue.isEmpty()) {
			MethodNode method = queue.poll();
			if (method.instructions == null)
				continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode methodInsn) || !methodInsn.owner.equals(node.name))
					continue;
				String key = methodInsn.name + methodInsn.desc;
				MethodNode target = methodsByKey.get(key);
				if (target != null && reachable.add(key))
					queue.add(target);
			}
		}
		return reachable;
	}

	@Nonnull
	@Override
	public String identifier() {
		return IDENTIFIER;
	}

	@Nonnull
	@Override
	public List<TransformationParameter<?>> getParameterDefinitions() {
		return List.of(AUTO_RESOLVE_VARIABLES);
	}

	@Nonnull
	@Override
	public String beforeExample() {
		return """
				static final int foo = "abc".length();
				
				void foo() {
				  if (foo > 0) {
				    System.out.println("Hello world");
				  }
				}
				""";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return """
				static final int foo = "abc".length();
				
				void foo() {
				  // The static field read is inlined, which you could further clean up with other transformers.
				  if (3 > 0) {
				    System.out.println("Hello world");
				  }
				}
				""";
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> dependencies() {
		return Collections.singleton(StaticValueCollectionTransformer.class);
	}
}
