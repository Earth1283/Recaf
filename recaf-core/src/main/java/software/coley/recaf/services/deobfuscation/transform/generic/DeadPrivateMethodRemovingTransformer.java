package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationParameter;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes unreferenced {@code private static} methods (such as decoy license traps or already-inlined
 * string/xor helpers) from a class.
 */
@Dependent
public class DeadPrivateMethodRemovingTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "peephole.dead.privatemethods";
	public static final String KEY_IGNORE_SELF_REFLECTION = IDENTIFIER + ".ignore-self-reflection";
	private static final TransformationParameter<Boolean> IGNORE_SELF_REFLECTION_PARAMETER =
			new TransformationParameter<>(KEY_IGNORE_SELF_REFLECTION, boolean.class, false);
	private static final Set<String> SERIALIZATION_HOOKS = Set.of(
			"readObject", "writeObject", "readObjectNoData", "readResolve", "writeReplace"
	);

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean ignoreSelfReflection = context.getParameters().getBoolean(KEY_IGNORE_SELF_REFLECTION, false);
		if (!ignoreSelfReflection && usesMethodReflection(node))
			return;

		// Iterate to a fixed point so removing a dead helper also exposes helpers it alone called.
		boolean anyRemoved = false;
		boolean removedInPass;
		do {
			Set<String> referenced = collectSameClassMethodReferences(node);
			removedInPass = node.methods.removeIf(method -> isRemovableDeadPrivateStatic(method, referenced));
			anyRemoved |= removedInPass;
		} while (removedInPass);

		if (anyRemoved)
			context.setNode(bundle, initialClassState, node);
	}

	private static boolean isRemovableDeadPrivateStatic(@Nonnull MethodNode method, @Nonnull Set<String> referenced) {
		int required = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
		if ((method.access & required) != required)
			return false;
		if (method.name.startsWith("<") || SERIALIZATION_HOOKS.contains(method.name))
			return false;
		if (method.visibleAnnotations != null && !method.visibleAnnotations.isEmpty())
			return false;
		if (method.invisibleAnnotations != null && !method.invisibleAnnotations.isEmpty())
			return false;
		return !referenced.contains(method.name + method.desc);
	}

	private static boolean usesMethodReflection(@Nonnull ClassNode node) {
		for (MethodNode method : node.methods) {
			if (method.instructions == null)
				continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if ("java/lang/Class".equals(call.owner)
							&& ("getDeclaredMethod".equals(call.name) || "getDeclaredMethods".equals(call.name)
							|| "getMethod".equals(call.name) || "getMethods".equals(call.name))) {
						return true;
					}
					if ("java/lang/invoke/MethodHandles$Lookup".equals(call.owner)
							&& ("findStatic".equals(call.name) || "findSpecial".equals(call.name) || "findVirtual".equals(call.name))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	@Nonnull
	private static Set<String> collectSameClassMethodReferences(@Nonnull ClassNode node) {
		Set<String> refs = new HashSet<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null)
				continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if (node.name.equals(call.owner))
						refs.add(call.name + call.desc);
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					recordHandle(node.name, indy.bsm, refs);
					if (indy.bsmArgs != null) {
						for (Object arg : indy.bsmArgs)
							recordConstant(node.name, arg, refs);
					}
				} else if (insn instanceof LdcInsnNode ldc) {
					recordConstant(node.name, ldc.cst, refs);
				}
			}
		}
		return refs;
	}

	private static void recordConstant(@Nonnull String owner, @Nullable Object cst, @Nonnull Set<String> refs) {
		if (cst instanceof Handle handle) {
			recordHandle(owner, handle, refs);
		} else if (cst instanceof ConstantDynamic cd) {
			recordHandle(owner, cd.getBootstrapMethod(), refs);
			for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++)
				recordConstant(owner, cd.getBootstrapMethodArgument(i), refs);
		}
	}

	private static void recordHandle(@Nonnull String owner, @Nullable Handle handle, @Nonnull Set<String> refs) {
		if (handle != null && owner.equals(handle.getOwner()))
			refs.add(handle.getName() + handle.getDesc());
	}

	@Nonnull
	@Override
	public List<TransformationParameter<?>> getParameterDefinitions() {
		return List.of(IGNORE_SELF_REFLECTION_PARAMETER);
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
				private static String unusedDecoy() {
				    return "decoy";
				}
				""";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return """
				// Unreferenced private static helper removed
				""";
	}
}
