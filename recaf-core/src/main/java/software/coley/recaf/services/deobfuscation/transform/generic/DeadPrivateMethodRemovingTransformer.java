package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationParameter;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.util.analysis.MethodUsageSupport;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes unreferenced methods across a class and workspace:
 * <ul>
 *     <li>Unreferenced {@code private} methods (respecting Java 11+ nestmate calls and declared/lookup reflection)</li>
 *     <li>Non-private methods whose callers were inlined to zero (or optionally any unused non-entry-point helpers)</li>
 * </ul>
 */
@Dependent
public class DeadPrivateMethodRemovingTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "peephole.dead.privatemethods";
	public static final String KEY_IGNORE_SELF_REFLECTION = IDENTIFIER + ".ignore-self-reflection";
	public static final String KEY_REMOVE_INLINED_NON_PRIVATE = IDENTIFIER + ".remove-inlined-non-private";
	public static final String KEY_REMOVE_UNUSED_NON_PRIVATE = IDENTIFIER + ".remove-unused-non-private";
	private static final TransformationParameter<Boolean> IGNORE_SELF_REFLECTION_PARAMETER =
			new TransformationParameter<>(KEY_IGNORE_SELF_REFLECTION, boolean.class, false);
	private static final TransformationParameter<Boolean> REMOVE_INLINED_NON_PRIVATE_PARAMETER =
			new TransformationParameter<>(KEY_REMOVE_INLINED_NON_PRIVATE, boolean.class, true);
	private static final TransformationParameter<Boolean> REMOVE_UNUSED_NON_PRIVATE_PARAMETER =
			new TransformationParameter<>(KEY_REMOVE_UNUSED_NON_PRIVATE, boolean.class, false);

	private final InheritanceGraphService graphService;
	private InheritanceGraph inheritanceGraph;

	@Inject
	public DeadPrivateMethodRemovingTransformer(@Nonnull InheritanceGraphService graphService) {
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
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean ignoreSelfReflection = context.getParameters().getBoolean(KEY_IGNORE_SELF_REFLECTION, false);
		boolean removeInlinedNonPrivate = context.getParameters().getBoolean(KEY_REMOVE_INLINED_NON_PRIVATE, true);
		boolean removeUnusedNonPrivate = context.getParameters().getBoolean(KEY_REMOVE_UNUSED_NON_PRIVATE, false);

		MethodUsageCollectionTransformer usageCollector = context.getOptionalTransformer(MethodUsageCollectionTransformer.class);
		boolean declaredReflection = false;
		boolean publicReflection = false;
		if (!ignoreSelfReflection) {
			if (usageCollector != null) {
				declaredReflection = usageCollector.hasDeclaredMethodReflectionInNest(node);
				publicReflection = usageCollector.hasPublicMethodReflection(node.name);
			} else {
				var localUsage = MethodUsageSupport.scanClassNode(node, inheritanceGraph);
				declaredReflection = localUsage.usesDeclaredMethodReflection();
				publicReflection = localUsage.usesPublicMethodReflection();
			}
		}

		// Iterate to a fixed point so removing a dead helper also exposes helpers it alone called.
		boolean anyRemoved = false;
		boolean removedInPass;
		final boolean finalDeclaredReflection = declaredReflection;
		final boolean finalPublicReflection = publicReflection;
		do {
			Set<String> referencedSameClass = collectSameClassMethodReferences(node);
			removedInPass = node.methods.removeIf(method -> isRemovableMethod(
					context, workspace, node, method, referencedSameClass, usageCollector,
					finalDeclaredReflection, finalPublicReflection, removeInlinedNonPrivate, removeUnusedNonPrivate));
			anyRemoved |= removedInPass;
		} while (removedInPass);

		if (anyRemoved)
			context.setNode(bundle, initialClassState, node);
	}

	private boolean isRemovableMethod(@Nonnull JvmTransformerContext context,
	                                  @Nonnull Workspace workspace,
	                                  @Nonnull ClassNode owner,
	                                  @Nonnull MethodNode method,
	                                  @Nonnull Set<String> referencedSameClass,
	                                  @Nullable MethodUsageCollectionTransformer usageCollector,
	                                  boolean declaredReflection,
	                                  boolean publicReflection,
	                                  boolean removeInlinedNonPrivate,
	                                  boolean removeUnusedNonPrivate) {
		if (MethodUsageSupport.isAlwaysProtectedMethod(owner, method, inheritanceGraph, workspace))
			return false;
		if (referencedSameClass.contains(method.name + method.desc))
			return false;
		if (usageCollector != null && usageCollector.isReferencedOutsideClass(owner.name, method.name, method.desc))
			return false;

		if (AccessFlag.isPrivate(method.access))
			return !declaredReflection;

		if (declaredReflection || (AccessFlag.isPublic(method.access) && publicReflection))
			return false;
		if (removeUnusedNonPrivate)
			return true;
		return removeInlinedNonPrivate && context.wasMethodInlined(owner.name, method.name, method.desc);
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
	public Set<Class<? extends ClassTransformer>> dependencies() {
		return Collections.singleton(MethodUsageCollectionTransformer.class);
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedPredecessors() {
		return Set.of(
				MethodUsageCollectionTransformer.class,
				CallResultInliningTransformer.class,
				OpaqueConstantFoldingTransformer.class,
				DeadCodeRemovingTransformer.class
		);
	}

	@Nonnull
	@Override
	public List<TransformationParameter<?>> getParameterDefinitions() {
		return List.of(IGNORE_SELF_REFLECTION_PARAMETER, REMOVE_INLINED_NON_PRIVATE_PARAMETER, REMOVE_UNUSED_NON_PRIVATE_PARAMETER);
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
				// Unreferenced helper removed
				""";
	}
}
