package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.util.AsmInsnUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** Removes empty control-flow paths while preserving observable evaluation and termination. */
@Dependent
public class EmptyControlFlowRemovingTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "peephole.flow.empty";

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) {
		ClassNode node = context.getNode(bundle, initialClassState);
		// ASM cannot recompute frames for a class containing legacy subroutines.
		if ((node.version & 0xFFFF) <= V1_6 && node.methods.stream().anyMatch(EmptyControlFlowRemovingTransformer::hasSubroutine))
			return;
		boolean dirty = false;
		for (MethodNode method : node.methods)
			if (method.instructions != null && method.instructions.size() > 0)
				dirty |= new MethodFlow(method).simplify();
		if (dirty) {
			context.setRecomputeFrames(initialClassState.getName());
			context.setNode(bundle, initialClassState, node);
		}
	}

	private static boolean hasSubroutine(MethodNode method) {
		if (method.instructions == null)
			return false;
		for (AbstractInsnNode insn : method.instructions)
			if (insn.getOpcode() == JSR || insn.getOpcode() == RET)
				return true;
		return false;
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedPredecessors() {
		return Set.of(OpaquePredicateFoldingTransformer.class);
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedSuccessors() {
		return Set.of(DeadCodeRemovingTransformer.class, GotoInliningTransformer.class);
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
				if (advance()) {
				} else {
				}
				finish();
				""";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return """
				advance();
				finish();
				""";
	}

	private static final class MethodFlow {
		private final InsnList instructions;
		private final boolean hasHandlers;
		private final Set<LabelNode> entryLabels = new HashSet<>();
		private final Map<AbstractInsnNode, AbstractInsnNode> overPadding = new IdentityHashMap<>();
		private final Map<AbstractInsnNode, AbstractInsnNode> overGotos = new IdentityHashMap<>();
		private final List<Runnable> edits = new ArrayList<>();

		private MethodFlow(MethodNode method) {
			instructions = method.instructions;
			hasHandlers = method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty();
			if (hasHandlers)
				method.tryCatchBlocks.forEach(block -> entryLabels.add(block.handler));
			AbstractInsnNode next = null;
			for (AbstractInsnNode insn = instructions.getLast(); insn != null; insn = insn.getPrevious()) {
				if (isPadding(insn))
					overPadding.put(insn, next);
				else
					next = insn;
				if (insn instanceof JumpInsnNode jump) {
					entryLabels.add(jump.label);
				} else if (insn instanceof TableSwitchInsnNode table) {
					entryLabels.add(table.dflt);
					entryLabels.addAll(table.labels);
				} else if (insn instanceof LookupSwitchInsnNode lookup) {
					entryLabels.add(lookup.dflt);
					entryLabels.addAll(lookup.labels);
				}
			}
		}

		private boolean simplify() {
			for (AbstractInsnNode insn : instructions) {
				if (insn instanceof JumpInsnNode jump) {
					int opcode = jump.getOpcode();
					if (opcode == GOTO) {
						if (targetIsNext(jump, jump.label))
							replace(jump, new InsnNode(NOP));
					} else if (opcode != JSR) {
						simplifyConditional(jump);
					}
				} else if (insn instanceof TableSwitchInsnNode table) {
					simplifySwitch(table, table.dflt, table.labels);
				} else if (insn instanceof LookupSwitchInsnNode lookup) {
					simplifySwitch(lookup, lookup.dflt, lookup.labels);
				}
			}
			// Plan against the original control-flow graph so every test sees consistent entry points.
			edits.forEach(Runnable::run);
			return !edits.isEmpty();
		}

		private void simplifyConditional(JumpInsnNode jump) {
			AbstractInsnNode taken = continuation(jump.label);
			AbstractInsnNode skipped = continuation(jump.getNext());
			int opcode = jump.getOpcode();
			int operands = opcode >= IF_ICMPEQ && opcode <= IF_ACMPNE ? 2 : 1;
			if (taken != null && taken == skipped) {
				dropBranch(jump, operands, null);
				return;
			}

			AbstractInsnNode producer = previousInput(jump);
			Boolean decision = constantDecision(opcode, producer);
			if (Boolean.FALSE.equals(decision) && taken == producer && skipped != null && skipped != producer) {
				// A bottom-tested empty loop whose sole condition evaluation falls through.
				dropBranch(jump, 1, null);
				return;
			}

			JumpInsnNode trampoline = privateFallthroughGoto(jump);
			if (trampoline == null || !targetIsNext(trampoline, jump.label))
				return;
			if (Boolean.TRUE.equals(decision) && skipped == producer && taken != null && taken != producer) {
				// The condition exits immediately and the only loop-body instruction jumps back to it.
				dropBranch(jump, 1, null);
				replace(trampoline, new InsnNode(NOP));
			} else if (instructions.indexOf(trampoline.label) > instructions.indexOf(trampoline)) {
				// The fallthrough arm only skips over the other arm. Invert the test to make that arm fall through.
				replace(jump, new JumpInsnNode(invert(opcode), trampoline.label));
				replace(trampoline, new InsnNode(NOP));
			}
		}

		private void simplifySwitch(AbstractInsnNode insn, LabelNode defaultLabel, List<LabelNode> labels) {
			AbstractInsnNode common = continuation(defaultLabel);
			if (common != null && labels.stream().allMatch(label -> continuation(label) == common))
				dropBranch(insn, 1, targetIsNext(insn, defaultLabel) ? null : defaultLabel);
		}

		private boolean targetIsNext(AbstractInsnNode source, LabelNode target) {
			// Falling through previously skipped instructions can change the locals merged at a handler.
			AbstractInsnNode next = hasHandlers ? AsmInsnUtil.getNextInsn(source) : firstOperation(source.getNext());
			AbstractInsnNode destination = hasHandlers ? AsmInsnUtil.getNextInsn(target) : firstOperation(target);
			return next != null && next == destination;
		}

		private void dropBranch(AbstractInsnNode branch, int inputs, @Nullable LabelNode destination) {
			AbstractInsnNode cursor = branch;
			int remaining = inputs;
			while (remaining > 0) {
				AbstractInsnNode producer = previousInput(cursor);
				if (producer == null)
					break;
				replace(producer, new InsnNode(NOP));
				cursor = producer;
				remaining--;
			}
			// Discard operands whose producers may be reached through another edge or may have effects.
			int discard = remaining == 2 ? POP2 : remaining == 1 ? POP : NOP;
			if (destination == null) {
				replace(branch, new InsnNode(discard));
			} else {
				if (remaining > 0)
					edits.add(() -> instructions.insertBefore(branch, new InsnNode(discard)));
				replace(branch, new JumpInsnNode(GOTO, destination));
			}
		}

		@Nullable
		private AbstractInsnNode previousInput(AbstractInsnNode consumer) {
			for (AbstractInsnNode insn = consumer.getPrevious(); insn != null; insn = insn.getPrevious()) {
				if (entryLabels.contains(insn))
					break;
				if (!isPadding(insn)) {
					int opcode = insn.getOpcode();
					return opcode == ILOAD || opcode == ALOAD || opcode == ACONST_NULL || AsmInsnUtil.isConstIntValue(insn) ? insn : null;
				}
			}
			return null;
		}

		@Nullable
		private JumpInsnNode privateFallthroughGoto(JumpInsnNode branch) {
			for (AbstractInsnNode insn = branch.getNext(); insn != null; insn = insn.getNext()) {
				if (entryLabels.contains(insn))
					break;
				if (!isPadding(insn))
					return insn.getOpcode() == GOTO ? (JumpInsnNode) insn : null;
			}
			return null;
		}

		@Nullable
		private AbstractInsnNode firstOperation(@Nullable AbstractInsnNode node) {
			return node != null && isPadding(node) ? overPadding.get(node) : node;
		}

		@Nullable
		private AbstractInsnNode continuation(@Nullable AbstractInsnNode start) {
			AbstractInsnNode node = firstOperation(start);
			if (node == null || node.getOpcode() != GOTO)
				return node;
			List<AbstractInsnNode> path = new ArrayList<>();
			while (node != null && node.getOpcode() == GOTO && !overGotos.containsKey(node)) {
				path.add(node);
				overGotos.put(node, null); // A revisit while walking this path indicates a cycle.
				node = firstOperation(((JumpInsnNode) node).label);
			}
			AbstractInsnNode target = overGotos.getOrDefault(node, node);
			for (AbstractInsnNode step : path)
				overGotos.put(step, target);
			return target;
		}

		private void replace(AbstractInsnNode original, AbstractInsnNode replacement) {
			// NOP replacements retain labels and any protected range around the original instruction.
			edits.add(() -> instructions.set(original, replacement));
		}
	}

	private static boolean isPadding(AbstractInsnNode node) {
		return node.getOpcode() == NOP || AsmInsnUtil.isMetaData(node);
	}

	@Nullable
	private static Boolean constantDecision(int opcode, @Nullable AbstractInsnNode producer) {
		if (producer == null)
			return null;
		if (producer.getOpcode() == ACONST_NULL)
			return switch (opcode) {
				case IFNULL -> true;
				case IFNONNULL -> false;
				default -> null;
			};
		if (AsmInsnUtil.getNumericLiteral(producer) instanceof Integer value)
			return switch (opcode) {
				case IFEQ -> value == 0;
				case IFNE -> value != 0;
				case IFLT -> value < 0;
				case IFGE -> value >= 0;
				case IFGT -> value > 0;
				case IFLE -> value <= 0;
				default -> null;
			};
		return null;
	}

	private static int invert(int opcode) {
		return switch (opcode) {
			case IFEQ -> IFNE;
			case IFNE -> IFEQ;
			case IFLT -> IFGE;
			case IFGE -> IFLT;
			case IFGT -> IFLE;
			case IFLE -> IFGT;
			case IF_ICMPEQ -> IF_ICMPNE;
			case IF_ICMPNE -> IF_ICMPEQ;
			case IF_ICMPLT -> IF_ICMPGE;
			case IF_ICMPGE -> IF_ICMPLT;
			case IF_ICMPGT -> IF_ICMPLE;
			case IF_ICMPLE -> IF_ICMPGT;
			case IF_ACMPEQ -> IF_ACMPNE;
			case IF_ACMPNE -> IF_ACMPEQ;
			case IFNULL -> IFNONNULL;
			case IFNONNULL -> IFNULL;
			default -> throw new IllegalArgumentException("Unsupported conditional opcode: " + opcode);
		};
	}
}
