package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.util.AsmInsnUtil;
import software.coley.recaf.util.analysis.ReInterpreter;
import software.coley.recaf.util.analysis.value.IntValue;
import software.coley.recaf.util.analysis.value.ReValue;

import java.util.function.BooleanSupplier;

import static org.objectweb.asm.Opcodes.*;
import static software.coley.recaf.services.analysis.bytecode.JvmEvidenceScanner.checkCancelled;

final class ControlFlowTechniqueAnalysis extends Analyzer<ReValue> {
	private static final int MAX_INSTRUCTIONS = 4096;
	private static final int MAX_FRAME_SLOTS = 262_144;
	private static final int MAX_EDGES = 65_536;
	private final BooleanSupplier cancelled;
	private int edges;

	ControlFlowTechniqueAnalysis(BooleanSupplier cancelled) {
		super(new ReInterpreter(null));
		this.cancelled = cancelled;
	}

	static boolean isCandidate(MethodNode method) {
		if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return true;
		for (AbstractInsnNode instruction : method.instructions) {
			boolean candidate = switch (instruction.getOpcode()) {
				case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
						IF_ACMPEQ, IF_ACMPNE, IFNULL, IFNONNULL, TABLESWITCH, LOOKUPSWITCH, GOTO, JSR, RET -> true;
				case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN, ATHROW -> instruction.getNext() != null;
				default -> false;
			};
			if (candidate) return true;
		}
		return false;
	}

	@Nullable
	Findings inspect(String owner, MethodNode method) {
		checkCancelled(cancelled);
		int size = method.instructions.size();
		int handlers = method.tryCatchBlocks == null ? 0 : method.tryCatchBlocks.size();
		if (size > MAX_INSTRUCTIONS || (long) size * (method.maxLocals + method.maxStack + 1) > MAX_FRAME_SLOTS
				|| (long) size * handlers > MAX_EDGES)
			return null;

		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() == JSR || instruction.getOpcode() == RET)
				return null;
		}

		int fixedBranches = 0;
		int unreachable = 0;
		edges = 0;
		try {
			Frame<ReValue>[] frames = analyze(owner, method);
			int index = 0;
			for (AbstractInsnNode instruction : method.instructions) {
				Frame<ReValue> frame = frames[index++];
				if (instruction.getOpcode() < 0) continue;
				if (frame == null) unreachable++;
				else if (fixedTarget(instruction, frame)) fixedBranches++;
			}
		} catch (AnalyzerException | RuntimeException ignored) {
			checkCancelled(cancelled);
			return null;
		}
		return new Findings(fixedBranches, unreachable);
	}

	@Override
	protected void newControlFlowEdge(int source, int target) {
		checkEdgeBudget();
	}

	@Override
	protected boolean newControlFlowExceptionEdge(int source, int target) {
		checkEdgeBudget();
		return true;
	}

	private void checkEdgeBudget() {
		if (++edges > MAX_EDGES) throw new IllegalStateException("Control flow edge limit exceeded");
		if ((edges & 127) == 0) checkCancelled(cancelled);
	}

	private static boolean fixedTarget(AbstractInsnNode instruction, Frame<ReValue> frame) {
		return switch (instruction.getOpcode()) {
			case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> knownInt(frame, 1);
			case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> knownInt(frame, 1) && knownInt(frame, 2);
			case TABLESWITCH -> knownInt(frame, 1) || AsmInsnUtil.isSwitchEffectiveGoto((TableSwitchInsnNode) instruction);
			case LOOKUPSWITCH -> knownInt(frame, 1) || AsmInsnUtil.isSwitchEffectiveGoto((LookupSwitchInsnNode) instruction);
			default -> false;
		};
	}

	private static boolean knownInt(Frame<ReValue> frame, int offset) {
		int index = frame.getStackSize() - offset;
		return index >= 0 && frame.getStack(index) instanceof IntValue value && value.hasKnownValue();
	}

	record Findings(int fixedBranches, int unreachable) {}
}
