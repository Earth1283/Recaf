package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.util.bytecode.TryCatchRepairUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Set;

/**
 * Removes no-op identity-rethrow and degenerate try-catch blocks, prunes orphaned unreachable
 * handler code, and repairs malformed local-variable debug ranges.
 */
@Dependent
public class TryCatchRepairTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "peephole.flow.trycatchrepair";

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean dirty = TryCatchRepairUtil.repairTryCatches(node);
		dirty |= TryCatchRepairUtil.repairLocalVariables(node);
		if (dirty) {
			if (!((node.version & 0xFFFF) <= Opcodes.V1_6 && hasSubroutine(node)))
				context.setRecomputeFrames(initialClassState.getName());
			context.setNode(bundle, initialClassState, node);
		}
	}

	private static boolean hasSubroutine(@Nonnull ClassNode node) {
		for (MethodNode method : node.methods) {
			if (method.instructions == null)
				continue;
			for (AbstractInsnNode insn : method.instructions) {
				int op = insn.getOpcode();
				if (op == Opcodes.JSR || op == Opcodes.RET)
					return true;
			}
		}
		return false;
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedSuccessors() {
		return Set.of(DeadCodeRemovingTransformer.class, DispatcherInliningTransformer.class, OpaquePredicateFoldingTransformer.class);
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
				try {
				    return compute();
				} catch (RuntimeException ex) {
				    throw rethrow(ex); // rethrow(e) simply returns e
				}
				""";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return """
				return compute();
				""";
	}
}
