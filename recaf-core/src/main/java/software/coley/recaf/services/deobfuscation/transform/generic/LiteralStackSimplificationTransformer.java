package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** Removes stack shuffles and discarded values from isolated runs of numeric literals, as seen in Radon. */
@Dependent
public class LiteralStackSimplificationTransformer implements JvmClassTransformer {
	// Copies keep their literal source so we can emit the surviving stack in its final order.
	private static final SourceInterpreter LITERAL_INTERPRETER = new SourceInterpreter(ASM9) {
		@Override
		public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) {
			return value;
		}
	};

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) {
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean changed = false;
		for (MethodNode method : node.methods)
			changed |= simplify(method);
		if (changed) {
			context.setRecomputeFrames(node.name);
			context.setNode(bundle, initialClassState, node);
		}
	}

	private static boolean simplify(MethodNode method) {
		InsnList instructions = method.instructions;
		if (instructions == null || instructions.size() < 2)
			return false;

		// Frame models both single-slot and double-slot forms of the JVM stack instructions.
		Frame<SourceValue> frame = new Frame<>(0, -1);
		AbstractInsnNode start = null;
		int length = 0;
		boolean changed = false;
		for (AbstractInsnNode insn = instructions.getFirst(); ; insn = insn.getNext()) {
			if (insn != null && (isNumberLiteral(insn) || (start != null && isStackOperation(insn.getOpcode())))) {
				try {
					frame.execute(insn, LITERAL_INTERPRETER);
					if (start == null)
						start = insn;
					length++;
				} catch (AnalyzerException | IndexOutOfBoundsException ex) {
					// The run depends on an earlier value or has an invalid stack shape.
					start = null;
					length = 0;
					frame.clearStack();
				}
				continue;
			}

			// Every label and frame ends a run so no incoming edge can bypass part of a replacement.
			if (start != null) {
				int size = frame.getStackSize();
				boolean keepNop = size == 0 && method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty();
				if (length > (keepNop ? 1 : size)) {
					for (int i = 0; i < size; i++) {
						AbstractInsnNode literal = frame.getStack(i).insns.iterator().next();
						instructions.insertBefore(start, literal.clone(Map.of()));
					}
					// A protected region containing only this run still needs a nonempty bytecode range.
					if (keepNop)
						instructions.insertBefore(start, new InsnNode(NOP));
					while (start != insn) {
						AbstractInsnNode next = start.getNext();
						instructions.remove(start);
						start = next;
					}
					changed = true;
				}
				start = null;
				length = 0;
				frame.clearStack();
			}
			if (insn == null)
				return changed;
		}
	}

	private static boolean isNumberLiteral(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (insn instanceof LdcInsnNode ldc)
			return ldc.cst instanceof Integer || ldc.cst instanceof Long
					|| ldc.cst instanceof Float || ldc.cst instanceof Double;
		return opcode >= ICONST_M1 && opcode <= SIPUSH;
	}

	private static boolean isStackOperation(int opcode) {
		return opcode >= POP && opcode <= SWAP;
	}

	@Nonnull
	@Override
	public String identifier() {
		return "peephole.data.literalstack";
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedSuccessors() {
		return Set.of(OpaqueConstantFoldingTransformer.class, BitwiseFoldingTransformer.class);
	}

	@Nonnull
	@Override
	public String beforeExample() {
		return "// Two constants followed by SWAP, DUP_X1 and POP2\nreturn 17 ^ 59;";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return "return 17 ^ 59; // Ready for constant folding";
	}
}
