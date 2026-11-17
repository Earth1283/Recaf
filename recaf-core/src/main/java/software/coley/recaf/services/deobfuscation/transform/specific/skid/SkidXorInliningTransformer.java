package software.coley.recaf.services.deobfuscation.transform.specific.skid;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** Inlines same-class private XOR helpers without requiring known operands or evaluating input code. */
@Dependent
public class SkidXorInliningTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "specific.skid.xorinline";

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) {
		ClassNode node = context.getNode(bundle, initialClassState);
		Map<String, Integer> helpers = new HashMap<>();
		for (MethodNode method : node.methods) {
			if ((method.access & (ACC_PRIVATE | ACC_STATIC | ACC_SYNCHRONIZED)) != (ACC_PRIVATE | ACC_STATIC)
					|| !method.tryCatchBlocks.isEmpty()) continue;
			boolean wide = method.desc.equals("(JJ)J");
			if (!wide && !method.desc.equals("(II)I")) continue;
			AbstractInsnNode[] code = Arrays.stream(method.instructions.toArray())
					.filter(insn -> insn.getOpcode() >= 0).toArray(AbstractInsnNode[]::new);
			int load = wide ? LLOAD : ILOAD;
			int xor = wide ? LXOR : IXOR;
			if (code.length == 4 && code[0] instanceof VarInsnNode first && first.getOpcode() == load && first.var == 0
					&& code[1] instanceof VarInsnNode second && second.getOpcode() == load && second.var == (wide ? 2 : 1)
					&& code[2].getOpcode() == xor && code[3].getOpcode() == (wide ? LRETURN : IRETURN))
				helpers.put(method.name + method.desc, xor);
		}
		if (helpers.isEmpty()) return;
		boolean changed = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || call.itf
						|| !call.owner.equals(node.name)) continue;
				Integer opcode = helpers.get(call.name + call.desc);
				if (opcode == null) continue;
				method.instructions.set(call, new InsnNode(opcode));
				changed = true;
			}
		}
		if (changed) context.setNode(bundle, initialClassState, node);
	}

	@Nonnull @Override public String identifier() { return IDENTIFIER; }
	@Nonnull @Override public Set<Class<? extends ClassTransformer>> recommendedSuccessors() {
		return Set.of(OpaqueConstantFoldingTransformer.class);
	}
	@Nonnull @Override public String beforeExample() { return "return hiddenXor(seed, 123); // private static int hiddenXor(int a, int b) { return a ^ b; }"; }
	@Nonnull @Override public String afterExample() { return "return seed ^ 123;"; }
}
