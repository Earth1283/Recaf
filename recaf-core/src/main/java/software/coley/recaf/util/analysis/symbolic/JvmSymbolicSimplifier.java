package software.coley.recaf.util.analysis.symbolic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.coley.recaf.util.AsmInsnUtil;
import software.coley.recaf.util.analysis.symbolic.BitVectorExpression.Comparison;
import software.coley.recaf.util.analysis.symbolic.BitVectorExpression.Op;

import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Simplifies int and long expressions in a pure, contiguous sequence in the same basic block.
 * Each local is an independent symbolic input, shared by every load of that slot and width in the sequence.
 * A branch is changed only if its operands simplify to a fixed comparison or Z3 proves the opposite outcome impossible.
 */
public final class JvmSymbolicSimplifier implements AutoCloseable {
	private static final int EXPRESSION_LIMIT = 48;
	private static final Comparison[] COMPARISONS = Comparison.values();
	private final BitVectorEGraph graph = new BitVectorEGraph();
	private final Z3BitVectorSolver solver = new Z3BitVectorSolver();

	/**
	 * @param method
	 * 		Method to transform.
	 * @param boundaries
	 * 		All branch targets and exception table boundaries in the method.
	 *
	 * @return Whether any conditional jumps or return expressions changed.
	 */
	public boolean fold(@Nonnull MethodNode method, @Nonnull Set<AbstractInsnNode> boundaries) {
		boolean changed = false;
		boolean hasHandlers = method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
			AbstractInsnNode next = insn.getNext();
			if (insn instanceof JumpInsnNode jump && !boundaries.contains(jump))
				changed |= foldJump(method.instructions, jump, boundaries, hasHandlers);
			else if (insn.getOpcode() == IRETURN || insn.getOpcode() == LRETURN)
				changed |= foldReturn(method.instructions, insn, boundaries);
			insn = next;
		}
		return changed;
	}

	private boolean foldJump(InsnList instructions, JumpInsnNode jump, Set<AbstractInsnNode> boundaries, boolean hasHandlers) {
		int opcode = jump.getOpcode();
		boolean binary = opcode >= IF_ICMPEQ && opcode <= IF_ICMPLE;
		if (!binary && (opcode < IFEQ || opcode > IFLE)) return false;
		AbstractInsnNode operand = previous(jump, boundaries);
		if (operand == null) return false;

		boolean longCompare = !binary && operand.getOpcode() == LCMP;
		// Single loads and literal zero tests belong to the regular constant-folding pass.
		if (!binary && !longCompare && unary(operand.getOpcode(), 32) == null && binary(operand.getOpcode(), 32) == null)
			return false;

		Comparison comparison = COMPARISONS[opcode - (binary ? IF_ICMPEQ : IFEQ)];
		graph.clear();
		Cursor cursor = new Cursor(longCompare ? previous(operand, boundaries) : operand, boundaries, graph);
		int bits = longCompare ? 64 : 32;
		int right = binary || longCompare ? cursor.read(bits) : graph.leaf(Op.CONSTANT, bits, 0);
		if (right < 0) return false;
		int left = cursor.read(bits);
		if (left < 0) return false;
		if (graph.find(left) != graph.find(right) && !cursor.hasOperation)
			return false;

		if (left != right) graph.saturate();
		boolean taken;
		if (graph.find(left) == graph.find(right)) {
			taken = comparison.test(0, 0);
		} else {
			BitVectorExpression[] operands = graph.extract(left, right);
			BitVectorExpression a = operands[0];
			BitVectorExpression b = operands[1];
			taken = comparison.test(a.sample(), b.sample());
			if (a.op() != Op.CONSTANT || b.op() != Op.CONSTANT) {
				// A concrete counterexample costs less than a native solver call.
				if (comparison.test(a.sample(-1), b.sample(-1)) != taken
						|| comparison.test(a.sample(0x8000_0000_8000_0000L), b.sample(0x8000_0000_8000_0000L)) != taken
						|| !solver.proves(comparison, a, b, taken))
					return false;
			}
		}

		removeOperands(instructions, cursor.first, jump);
		if (taken)
			instructions.set(jump, new JumpInsnNode(GOTO, jump.label));
		else if (hasHandlers)
			// A fall-through NOP keeps the protected range valid when it held only this predicate.
			instructions.set(jump, new InsnNode(NOP));
		else
			instructions.remove(jump);
		return true;
	}

	private boolean foldReturn(InsnList instructions, AbstractInsnNode returnInsn, Set<AbstractInsnNode> boundaries) {
		int bits = returnInsn.getOpcode() == IRETURN ? 32 : 64;
		AbstractInsnNode operand = previous(returnInsn, boundaries);
		if (operand == null) return false;
		int opcode = operand.getOpcode();
		if (unary(opcode, bits) == null && binary(opcode, bits) == null) return false;

		graph.clear();
		Cursor cursor = new Cursor(operand, boundaries, graph);
		int root = cursor.read(bits);
		if (root < 0) return false;
		graph.saturate();
		BitVectorExpression simplest = graph.extractLeaf(root);
		if (simplest == null) return false;
		AbstractInsnNode replacement;
		if (simplest.op() == Op.LOCAL)
			replacement = new VarInsnNode(bits == 32 ? ILOAD : LLOAD, (int) simplest.value());
		else
			replacement = bits == 32 ? AsmInsnUtil.intToInsn((int) simplest.value()) : AsmInsnUtil.longToInsn(simplest.value());
		removeOperands(instructions, cursor.first, returnInsn);
		instructions.insertBefore(returnInsn, replacement);
		return true;
	}

	private static void removeOperands(InsnList instructions, AbstractInsnNode first, AbstractInsnNode end) {
		// Keep labels and source lines in place.
		for (AbstractInsnNode node = first; node != end; ) {
			AbstractInsnNode next = node.getNext();
			if (node.getOpcode() >= 0) instructions.remove(node);
			node = next;
		}
	}

	@Nullable
	private static AbstractInsnNode previous(AbstractInsnNode from, Set<AbstractInsnNode> boundaries) {
		for (AbstractInsnNode prev = from.getPrevious(); prev != null; prev = prev.getPrevious()) {
			if (boundaries.contains(prev) || prev instanceof FrameNode) return null;
			if (prev.getOpcode() >= 0 && prev.getOpcode() != NOP) return prev;
		}
		return null;
	}

	@Nullable
	private static Op unary(int opcode, int width) {
		if (width == 64) return switch (opcode) {
			case LNEG -> Op.NEG;
			case I2L -> Op.I2L;
			default -> null;
		};
		return switch (opcode) {
			case INEG -> Op.NEG;
			case L2I -> Op.L2I;
			case I2B -> Op.I2B;
			case I2C -> Op.I2C;
			case I2S -> Op.I2S;
			default -> null;
		};
	}

	@Nullable
	private static Op binary(int opcode, int width) {
		if (width == 64) return switch (opcode) {
			case LADD -> Op.ADD;
			case LSUB -> Op.SUB;
			case LMUL -> Op.MUL;
			case LAND -> Op.AND;
			case LOR -> Op.OR;
			case LXOR -> Op.XOR;
			case LSHL -> Op.SHL;
			case LSHR -> Op.ASHR;
			case LUSHR -> Op.LSHR;
			default -> null;
		};
		return switch (opcode) {
			case IADD -> Op.ADD;
			case ISUB -> Op.SUB;
			case IMUL -> Op.MUL;
			case IAND -> Op.AND;
			case IOR -> Op.OR;
			case IXOR -> Op.XOR;
			case ISHL -> Op.SHL;
			case ISHR -> Op.ASHR;
			case IUSHR -> Op.LSHR;
			default -> null;
		};
	}

	@Override
	public void close() {
		solver.close();
	}

	private static final class Cursor {
		private final Set<AbstractInsnNode> boundaries;
		private final BitVectorEGraph graph;
		private int remaining = EXPRESSION_LIMIT;
		private AbstractInsnNode current;
		private AbstractInsnNode first;
		private boolean hasOperation;

		private Cursor(AbstractInsnNode current, Set<AbstractInsnNode> boundaries, BitVectorEGraph graph) {
			this.current = current;
			this.boundaries = boundaries;
			this.graph = graph;
		}

		private int read(int bits) {
			if (current == null || remaining-- <= 0) return -1;
			AbstractInsnNode insn = current;
			first = insn;
			current = previous(insn, boundaries);

			int opcode = insn.getOpcode();
			Number literal = AsmInsnUtil.getNumericLiteral(insn);
			if ((bits == 32 && literal instanceof Integer) || (bits == 64 && literal instanceof Long))
				return graph.leaf(Op.CONSTANT, bits, literal.longValue());
			if (opcode == (bits == 32 ? ILOAD : LLOAD))
				return graph.leaf(Op.LOCAL, bits, ((VarInsnNode) insn).var);

			Op unary = unary(opcode, bits);
			if (unary != null) {
				int input = read(unary == Op.I2L ? 32 : unary == Op.L2I ? 64 : bits);
				if (input < 0) return -1;
				hasOperation = true;
				return graph.operation(unary, bits, input, -1);
			}
			Op binary = binary(opcode, bits);
			if (binary == null) return -1;
			int right = read(binary.shift() ? 32 : bits);
			if (right < 0) return -1;
			int left = read(bits);
			if (left < 0) return -1;
			hasOperation = true;
			return graph.operation(binary, bits, left, right);
		}
	}
}
