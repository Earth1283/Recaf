package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.util.AsmInsnUtil;
import software.coley.recaf.util.analysis.MethodUsageSupport;
import software.coley.recaf.util.analysis.symbolic.JvmSymbolicSimplifier;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Folds two's-complement arithmetic and opaque predicates using peephole identities,
 * equality saturation, and SMT proofs. Removes dead pure arithmetic consumed by {@code POP} / {@code POP2}.
 */
@Dependent
public class AlgebraicOpaquePredicateTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "peephole.flow.algebraicpredicate";

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean dirty = false;
		try (var symbolic = new JvmSymbolicSimplifier()) {
			for (MethodNode method : node.methods) {
				if (method.instructions == null || method.instructions.size() == 0)
					continue;
				Set<AbstractInsnNode> jumpTargets = collectJumpTargets(method);
				boolean methodDirty;
				do {
					methodDirty = foldAlgebraicIdentities(method.instructions, jumpTargets);
					methodDirty |= pruneDeadPureConstantPop(method.instructions, jumpTargets);
					dirty |= methodDirty;
				} while (methodDirty);
				dirty |= symbolic.fold(method, jumpTargets);
			}
		}
		if (dirty) {
			context.setRecomputeFrames(initialClassState.getName());
			context.setNode(bundle, initialClassState, node);
		}
	}

	private static boolean foldAlgebraicIdentities(@Nonnull InsnList instructions, @Nonnull Set<AbstractInsnNode> jumpTargets) {
		boolean dirty = false;
		AbstractInsnNode insn = instructions.getFirst();
		while (insn != null) {
			AbstractInsnNode next = insn.getNext();
			if (insn.getOpcode() == DUP) {
				AbstractInsnNode[] seq = nextRealSequence(insn, 5, jumpTargets);
				if (seq != null) {
					if (isConsecutiveParityInt(seq)) {
						// x * (x +/- 1) & 1 == 0  and  (x * (x +/- 1)) % 2 == 0
						replaceSpanWith(instructions, seq[0], seq[5], new InsnNode(POP), new InsnNode(ICONST_0));
						dirty = true;
						insn = next;
						continue;
					}
					if (seq[1].getOpcode() == ICONST_M1 && seq[2].getOpcode() == IXOR
							&& (seq[3].getOpcode() == IAND || seq[3].getOpcode() == IOR)) {
						// x & ~x == 0, x | ~x == -1
						int res = seq[3].getOpcode() == IAND ? ICONST_0 : ICONST_M1;
						replaceSpanWith(instructions, seq[0], seq[3], new InsnNode(POP), new InsnNode(res));
						dirty = true;
						insn = next;
						continue;
					}
					if (seq[1].getOpcode() == IXOR || seq[1].getOpcode() == ISUB) {
						// x ^ x == 0, x - x == 0
						replaceSpanWith(instructions, seq[0], seq[1], new InsnNode(POP), new InsnNode(ICONST_0));
						dirty = true;
						insn = next;
						continue;
					}
				} else {
					AbstractInsnNode[] shortSeq = nextRealSequence(insn, 3, jumpTargets);
					if (shortSeq != null && shortSeq[1].getOpcode() == ICONST_M1 && shortSeq[2].getOpcode() == IXOR
							&& (shortSeq[3].getOpcode() == IAND || shortSeq[3].getOpcode() == IOR)) {
						int res = shortSeq[3].getOpcode() == IAND ? ICONST_0 : ICONST_M1;
						replaceSpanWith(instructions, shortSeq[0], shortSeq[3], new InsnNode(POP), new InsnNode(res));
						dirty = true;
						insn = next;
						continue;
					}
					AbstractInsnNode[] pairSeq = nextRealSequence(insn, 1, jumpTargets);
					if (pairSeq != null && (pairSeq[1].getOpcode() == IXOR || pairSeq[1].getOpcode() == ISUB)) {
						replaceSpanWith(instructions, pairSeq[0], pairSeq[1], new InsnNode(POP), new InsnNode(ICONST_0));
						dirty = true;
						insn = next;
						continue;
					}
				}
			} else if (insn.getOpcode() == DUP2) {
				AbstractInsnNode[] seq = nextRealSequence(insn, 5, jumpTargets);
				if (seq != null && isConsecutiveParityLong(seq)) {
					replaceSpanWith(instructions, seq[0], seq[5], new InsnNode(POP2), new InsnNode(LCONST_0));
					dirty = true;
					insn = next;
					continue;
				}
				AbstractInsnNode[] pairSeq = nextRealSequence(insn, 1, jumpTargets);
				if (pairSeq != null && (pairSeq[1].getOpcode() == LXOR || pairSeq[1].getOpcode() == LSUB)) {
					replaceSpanWith(instructions, pairSeq[0], pairSeq[1], new InsnNode(POP2), new InsnNode(LCONST_0));
					dirty = true;
					insn = next;
					continue;
				}
			}
			insn = next;
		}
		return dirty;
	}

	private static boolean isConsecutiveParityInt(@Nonnull AbstractInsnNode[] seq) {
		int op1 = seq[1].getOpcode();
		int op2 = seq[2].getOpcode();
		if (op1 != ICONST_1 || (op2 != IADD && op2 != ISUB) || seq[3].getOpcode() != IMUL)
			return false;
		int op4 = seq[4].getOpcode();
		int op5 = seq[5].getOpcode();
		return (op4 == ICONST_1 && op5 == IAND) || (op4 == ICONST_2 && op5 == IREM);
	}

	private static boolean isConsecutiveParityLong(@Nonnull AbstractInsnNode[] seq) {
		int op1 = seq[1].getOpcode();
		int op2 = seq[2].getOpcode();
		if (op1 != LCONST_1 || (op2 != LADD && op2 != LSUB) || seq[3].getOpcode() != LMUL)
			return false;
		int op5 = seq[5].getOpcode();
		if (seq[4].getOpcode() == LCONST_1 && op5 == LAND)
			return true;
		return op5 == LREM && seq[4] instanceof LdcInsnNode ldc && Long.valueOf(2L).equals(ldc.cst);
	}

	/**
	 * Removes contiguous pure constant + non-throwing arithmetic expressions that are immediately
	 * discarded by {@code POP} or {@code POP2}.
	 */
	private static boolean pruneDeadPureConstantPop(@Nonnull InsnList instructions, @Nonnull Set<AbstractInsnNode> jumpTargets) {
		Deque<PureSpan> stack = new ArrayDeque<>();
		List<List<AbstractInsnNode>> removals = new ArrayList<>();

		for (AbstractInsnNode insn : instructions) {
			if (jumpTargets.contains(insn)) {
				stack.clear();
				continue;
			}
			int op = insn.getOpcode();
			if (op < 0 || op == NOP)
				continue;

			if (isPureConstantProducer(insn)) {
				int size = (op == LCONST_0 || op == LCONST_1 || op == DCONST_0 || op == DCONST_1
						|| (insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Long || ldc.cst instanceof Double))) ? 2 : 1;
				List<AbstractInsnNode> nodes = new ArrayList<>(4);
				nodes.add(insn);
				stack.push(new PureSpan(size, nodes));
				continue;
			}

			if (op == POP) {
				if (!stack.isEmpty() && stack.peek().size() == 1) {
					PureSpan span = stack.pop();
					span.insns().add(insn);
					removals.add(span.insns());
				} else {
					stack.clear();
				}
				continue;
			}
			if (op == POP2) {
				if (!stack.isEmpty() && stack.peek().size() == 2) {
					PureSpan span = stack.pop();
					span.insns().add(insn);
					removals.add(span.insns());
				} else if (stack.size() >= 2) {
					PureSpan top = stack.pop();
					if (top.size() == 1 && stack.peek().size() == 1) {
						PureSpan second = stack.pop();
						List<AbstractInsnNode> combined = new ArrayList<>(second.insns().size() + top.insns().size() + 1);
						combined.addAll(second.insns());
						combined.addAll(top.insns());
						combined.add(insn);
						removals.add(combined);
					} else {
						stack.clear();
					}
				} else {
					stack.clear();
				}
				continue;
			}

			if (insn instanceof MethodInsnNode min && op == INVOKESTATIC
					&& "java/lang/System".equals(min.owner)
					&& "identityHashCode".equals(min.name)
					&& "(Ljava/lang/Object;)I".equals(min.desc)) {
				if (!stack.isEmpty() && stack.peek().size() == 1)
					addInsnToTop(stack, insn);
				else
					stack.clear();
				continue;
			}

			if (tryApplyPureOp(op, stack))
				addInsnToTop(stack, insn);
			else
				stack.clear();
		}

		if (removals.isEmpty())
			return false;

		for (List<AbstractInsnNode> group : removals) {
			for (AbstractInsnNode node : group) {
				if (node.getPrevious() != null || node.getNext() != null || instructions.getFirst() == node)
					instructions.remove(node);
			}
		}
		return true;
	}

	private static boolean tryApplyPureOp(int op, @Nonnull Deque<PureSpan> stack) {
		return switch (op) {
			case INEG, I2B, I2C, I2S, FNEG -> !stack.isEmpty() && stack.peek().size() == 1;
			case LNEG, DNEG -> !stack.isEmpty() && stack.peek().size() == 2;
			case I2L, I2D, F2L, F2D -> popAndPushSize(stack, 1, 2);
			case L2I, L2F, D2I, D2F -> popAndPushSize(stack, 2, 1);
			case I2F, F2I -> !stack.isEmpty() && stack.peek().size() == 1;
			case L2D, D2L -> !stack.isEmpty() && stack.peek().size() == 2;
			// Explicitly exclude IDIV/IREM/LDIV/LREM because divide-by-zero can throw ArithmeticException.
			case IADD, ISUB, IMUL, IAND, IOR, IXOR, ISHL, ISHR, IUSHR,
					FADD, FSUB, FMUL, FCMPL, FCMPG -> combineTop(stack, 1, 1, 1);
			case LADD, LSUB, LMUL, LAND, LOR, LXOR,
					DADD, DSUB, DMUL -> combineTop(stack, 2, 2, 2);
			case LSHL, LSHR, LUSHR -> combineTop(stack, 1, 2, 2);
			case LCMP, DCMPL, DCMPG -> combineTop(stack, 2, 2, 1);
			default -> false;
		};
	}

	private static boolean popAndPushSize(@Nonnull Deque<PureSpan> stack, int inSize, int outSize) {
		if (stack.isEmpty() || stack.peek().size() != inSize)
			return false;
		PureSpan top = stack.pop();
		stack.push(new PureSpan(outSize, top.insns()));
		return true;
	}

	private static boolean combineTop(@Nonnull Deque<PureSpan> stack, int topSize, int secondSize, int outSize) {
		if (stack.size() < 2)
			return false;
		PureSpan top = stack.pop();
		PureSpan second = stack.peek();
		if (top.size() != topSize || second == null || second.size() != secondSize) {
			stack.push(top);
			return false;
		}
		stack.pop();
		List<AbstractInsnNode> combined = new ArrayList<>(second.insns().size() + top.insns().size() + 1);
		combined.addAll(second.insns());
		combined.addAll(top.insns());
		stack.push(new PureSpan(outSize, combined));
		return true;
	}

	private static void addInsnToTop(@Nonnull Deque<PureSpan> stack, @Nonnull AbstractInsnNode insn) {
		if (!stack.isEmpty())
			stack.peek().insns().add(insn);
	}

	private static boolean isPureConstantProducer(@Nonnull AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op >= ACONST_NULL && op <= SIPUSH)
			return true;
		if (insn instanceof LdcInsnNode ldc) {
			Object cst = ldc.cst;
			if (cst instanceof Integer || cst instanceof Long || cst instanceof Float
					|| cst instanceof Double || cst instanceof String)
				return true;
			if (cst instanceof Type t)
				return t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY
						|| (t.getSort() == Type.OBJECT && MethodUsageSupport.GUARANTEED_JDK_CLASSES.contains(t.getInternalName()));
		}
		return false;
	}

	@Nullable
	private static AbstractInsnNode[] nextRealSequence(@Nonnull AbstractInsnNode start,
	                                                   int additionalCount,
	                                                   @Nonnull Set<AbstractInsnNode> jumpTargets) {
		AbstractInsnNode[] result = new AbstractInsnNode[additionalCount + 1];
		result[0] = start;
		AbstractInsnNode cur = start.getNext();
		for (int i = 1; i <= additionalCount; i++) {
			while (cur != null && (cur.getOpcode() < 0 || cur.getOpcode() == NOP)) {
				if (jumpTargets.contains(cur))
					return null;
				cur = cur.getNext();
			}
			if (cur == null || jumpTargets.contains(cur))
				return null;
			result[i] = cur;
			cur = cur.getNext();
		}
		return result;
	}

	private static void replaceSpanWith(@Nonnull InsnList instructions,
	                                    @Nonnull AbstractInsnNode from,
	                                    @Nonnull AbstractInsnNode to,
	                                    @Nonnull AbstractInsnNode... replacements) {
		AbstractInsnNode cur = from.getNext();
		while (cur != null && cur != to) {
			AbstractInsnNode next = cur.getNext();
			if (cur.getOpcode() >= 0)
				instructions.remove(cur);
			cur = next;
		}
		for (AbstractInsnNode r : replacements)
			instructions.insertBefore(from, r);
		instructions.remove(from);
		if (to != from)
			instructions.remove(to);
	}

	@Nonnull
	private static Set<AbstractInsnNode> collectJumpTargets(@Nonnull MethodNode method) {
		Set<AbstractInsnNode> targets = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof JumpInsnNode jump) {
				targets.add(jump.label);
			} else if (insn instanceof TableSwitchInsnNode table) {
				targets.add(table.dflt);
				targets.addAll(table.labels);
			} else if (insn instanceof LookupSwitchInsnNode lookup) {
				targets.add(lookup.dflt);
				targets.addAll(lookup.labels);
			}
		}
		if (method.tryCatchBlocks != null) {
			for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
				targets.add(tcb.start);
				targets.add(tcb.end);
				targets.add(tcb.handler);
			}
		}
		return targets;
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedSuccessors() {
		return Set.of(OpaqueConstantFoldingTransformer.class, OpaquePredicateFoldingTransformer.class, DeadCodeRemovingTransformer.class);
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
				int x = unknownInput();
				if (((x * (x + 1)) & 1) != 0) {
				    unreachableBranch();
				}
				""";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return """
				int x = unknownInput();
				if (false) {
				    unreachableBranch();
				}
				""";
	}

	private record PureSpan(int size, @Nonnull List<AbstractInsnNode> insns) {}
}
