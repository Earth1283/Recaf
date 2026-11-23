package software.coley.recaf.util.bytecode;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.coley.recaf.util.AsmInsnUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared bytecode repair utilities for removing no-op/degenerate try-catch blocks,
 * pruning orphaned unreachable handler code, and repairing malformed local variable tables.
 */
public final class TryCatchRepairUtil implements Opcodes {
	private TryCatchRepairUtil() {}

	/**
	 * Repairs no-op identity-rethrow and degenerate try-catch blocks across all methods of {@code classNode},
	 * pruning any dead handler code left unreachable.
	 *
	 * @param classNode
	 * 		Class to repair in place.
	 *
	 * @return {@code true} if any try-catch entry or instruction was removed.
	 */
	public static boolean repairTryCatches(@Nonnull ClassNode classNode) {
		Set<String> identityHelpers = findIdentityRethrowHelpers(classNode);
		boolean dirty = false;
		for (MethodNode method : classNode.methods)
			dirty |= repairMethodTryCatches(classNode.name, method, identityHelpers);
		return dirty;
	}

	/**
	 * Counts no-op identity-rethrow try-catch handlers in {@code classNode} that invoke a static
	 * identity helper {@code (T)T} before {@code ATHROW}.
	 *
	 * @param classNode
	 * 		Class to inspect.
	 *
	 * @return Number of matching identity-helper rethrow try-catch blocks.
	 */
	public static int countHelperRethrowTryCatches(@Nonnull ClassNode classNode) {
		Set<String> identityHelpers = findIdentityRethrowHelpers(classNode);
		if (identityHelpers.isEmpty())
			return 0;
		int count = 0;
		for (MethodNode method : classNode.methods) {
			if (method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty())
				continue;
			for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
				if (tcb.handler != null && isHelperIdentityRethrowHandler(classNode.name, identityHelpers, tcb.handler))
					count++;
			}
		}
		return count;
	}

	/**
	 * Counts degenerate try-catch blocks (empty/inverted ranges, pure rethrow handlers, or self-catching
	 * non-monitor handlers).
	 *
	 * @param classNode
	 * 		Class to inspect.
	 *
	 * @return Number of fake/degenerate try-catch blocks detected.
	 */
	public static int countFakeTryCatches(@Nonnull ClassNode classNode) {
		Set<String> identityHelpers = findIdentityRethrowHelpers(classNode);
		int count = 0;
		for (MethodNode method : classNode.methods) {
			if (method.instructions == null || method.instructions.size() == 0
					|| method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty())
				continue;
			Map<AbstractInsnNode, Integer> indices = indexMap(method.instructions);
			for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
				Integer start = tcb.start == null ? null : indices.get(tcb.start);
				Integer end = tcb.end == null ? null : indices.get(tcb.end);
				Integer handler = tcb.handler == null ? null : indices.get(tcb.handler);
				if (start == null || end == null || handler == null || start >= end) {
					count++;
				} else if (isIdentityRethrowHandler(classNode.name, identityHelpers, tcb.handler)) {
					count++;
				} else if (tcb.start == tcb.handler && !handlerContainsMonitorExit(tcb.handler)) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * Repairs malformed local variable table entries in all methods of {@code classNode}.
	 *
	 * @param classNode
	 * 		Class to repair in place.
	 *
	 * @return {@code true} if any malformed local variable entry was removed.
	 */
	public static boolean repairLocalVariables(@Nonnull ClassNode classNode) {
		boolean dirty = false;
		for (MethodNode method : classNode.methods)
			dirty |= repairLocalVariables(method);
		return dirty;
	}

	/**
	 * Drops invalid or conflicting {@link LocalVariableNode} entries from {@code method}
	 * without stripping valid debug variable names.
	 *
	 * @param method
	 * 		Method to repair in place.
	 *
	 * @return {@code true} if any malformed entry was removed.
	 */
	public static boolean repairLocalVariables(@Nonnull MethodNode method) {
		if (method.localVariables == null || method.localVariables.isEmpty() || method.instructions == null)
			return false;

		Map<AbstractInsnNode, Integer> indices = indexMap(method.instructions);
		List<LocalVariableNode> kept = new ArrayList<>(method.localVariables.size());
		boolean dirty = false;

		for (LocalVariableNode lvn : method.localVariables) {
			Integer startIdx = lvn.start == null ? null : indices.get(lvn.start);
			Integer endIdx = lvn.end == null ? null : indices.get(lvn.end);
			if (startIdx == null || endIdx == null || startIdx > endIdx || lvn.index < 0 || !isValidNonVoidDesc(lvn.desc)) {
				dirty = true;
				continue;
			}

			int sort = normalizedSort(Type.getType(lvn.desc));
			int size = Type.getType(lvn.desc).getSize();
			boolean conflict = false;
			for (LocalVariableNode existing : kept) {
				int exStart = indices.get(existing.start);
				int exEnd = indices.get(existing.end);
				// Strictly overlapping ranges [startIdx, endIdx) and [exStart, exEnd)
				if (Math.max(startIdx, exStart) >= Math.min(endIdx, exEnd))
					continue;
				int exSize = Type.getType(existing.desc).getSize();
				boolean slotsOverlap = lvn.index < existing.index + exSize && existing.index < lvn.index + size;
				if (!slotsOverlap)
					continue;
				int exSort = normalizedSort(Type.getType(existing.desc));
				if (lvn.index != existing.index || sort != exSort) {
					conflict = true;
					break;
				}
			}
			if (conflict) {
				dirty = true;
				continue;
			}
			kept.add(lvn);
		}

		if (dirty) {
			method.localVariables.clear();
			method.localVariables.addAll(kept);
		}
		return dirty;
	}

	private static boolean repairMethodTryCatches(@Nonnull String owner,
	                                              @Nonnull MethodNode method,
	                                              @Nonnull Set<String> identityHelpers) {
		if (method.instructions == null || method.instructions.size() == 0
				|| method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty())
			return false;

		Map<AbstractInsnNode, Integer> indices = indexMap(method.instructions);
		Set<TryCatchKey> seen = new HashSet<>();
		boolean removedAny = false;
		Iterator<TryCatchBlockNode> it = method.tryCatchBlocks.iterator();
		while (it.hasNext()) {
			TryCatchBlockNode tcb = it.next();
			Integer start = tcb.start == null ? null : indices.get(tcb.start);
			Integer end = tcb.end == null ? null : indices.get(tcb.end);
			Integer handler = tcb.handler == null ? null : indices.get(tcb.handler);
			if (start == null || end == null || handler == null || start >= end) {
				it.remove();
				removedAny = true;
				continue;
			}
			if (!seen.add(new TryCatchKey(tcb.start, tcb.end, tcb.handler, tcb.type))) {
				it.remove();
				removedAny = true;
				continue;
			}
			if (isIdentityRethrowHandler(owner, identityHelpers, tcb.handler)) {
				it.remove();
				removedAny = true;
				continue;
			}
			// Remove degenerate self-catching ranges only when the handler is a pure immediate exit (no monitorexit).
			if (tcb.start == tcb.handler && isImmediateAthrowExit(tcb.handler) && !handlerContainsMonitorExit(tcb.handler)) {
				it.remove();
				removedAny = true;
			}
		}

		if (!removedAny)
			return false;

		if (!hasSubroutine(method))
			pruneUnreachableInstructions(method);
		repairLocalVariables(method);
		return true;
	}

	/**
	 * Finds static single-argument identity methods {@code T id(T x) { return x; }} in {@code classNode}.
	 */
	@Nonnull
	public static Set<String> findIdentityRethrowHelpers(@Nonnull ClassNode classNode) {
		Set<String> helpers = new HashSet<>();
		for (MethodNode method : classNode.methods) {
			if ((method.access & ACC_STATIC) == 0 || method.instructions == null)
				continue;
			Type type = Type.getMethodType(method.desc);
			Type[] args = type.getArgumentTypes();
			if (args.length != 1)
				continue;
			Type ret = type.getReturnType();
			if (ret.getSort() != Type.OBJECT || !args[0].equals(ret))
				continue;
			AbstractInsnNode first = firstReal(method.instructions.getFirst());
			if (!(first instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != 0)
				continue;
			AbstractInsnNode second = firstReal(first.getNext());
			if (second != null && second.getOpcode() == ARETURN)
				helpers.add(method.name + method.desc);
		}
		return helpers;
	}

	private static boolean isHelperIdentityRethrowHandler(@Nonnull String owner,
	                                                      @Nonnull Set<String> identityHelpers,
	                                                      @Nonnull LabelNode handler) {
		AbstractInsnNode cur = firstReal(handler);
		if (cur == null)
			return false;
		if (cur instanceof VarInsnNode store && store.getOpcode() == ASTORE) {
			AbstractInsnNode load = firstReal(cur.getNext());
			if (!(load instanceof VarInsnNode l) || l.getOpcode() != ALOAD || l.var != store.var)
				return false;
			cur = firstReal(load.getNext());
		}
		if (cur instanceof MethodInsnNode mi && mi.getOpcode() == INVOKESTATIC
				&& mi.owner.equals(owner) && identityHelpers.contains(mi.name + mi.desc)) {
			AbstractInsnNode after = firstReal(cur.getNext());
			return after != null && after.getOpcode() == ATHROW;
		}
		return false;
	}

	private static boolean isIdentityRethrowHandler(@Nonnull String owner,
	                                                @Nonnull Set<String> identityHelpers,
	                                                @Nonnull LabelNode handler) {
		AbstractInsnNode cur = firstReal(handler);
		if (cur == null)
			return false;
		if (cur.getOpcode() == ATHROW)
			return true;
		if (cur instanceof MethodInsnNode mi && mi.getOpcode() == INVOKESTATIC
				&& mi.owner.equals(owner) && identityHelpers.contains(mi.name + mi.desc)) {
			AbstractInsnNode next = firstReal(cur.getNext());
			return next != null && next.getOpcode() == ATHROW;
		}
		if (cur instanceof VarInsnNode store && store.getOpcode() == ASTORE) {
			AbstractInsnNode load = firstReal(cur.getNext());
			if (load instanceof VarInsnNode l && l.getOpcode() == ALOAD && l.var == store.var) {
				AbstractInsnNode next = firstReal(load.getNext());
				if (next != null && next.getOpcode() == ATHROW)
					return true;
				if (next instanceof MethodInsnNode mi && mi.getOpcode() == INVOKESTATIC
						&& mi.owner.equals(owner) && identityHelpers.contains(mi.name + mi.desc)) {
					AbstractInsnNode after = firstReal(next.getNext());
					return after != null && after.getOpcode() == ATHROW;
				}
			}
		}
		return false;
	}

	private static boolean isImmediateAthrowExit(@Nonnull LabelNode handler) {
		AbstractInsnNode cur = firstReal(handler);
		int steps = 0;
		while (cur != null && steps++ < 6) {
			int op = cur.getOpcode();
			if (op == ATHROW)
				return true;
			if (op == ASTORE || op == ALOAD || op == POP) {
				cur = firstReal(cur.getNext());
				continue;
			}
			return false;
		}
		return false;
	}

	private static boolean handlerContainsMonitorExit(@Nonnull LabelNode handler) {
		AbstractInsnNode cur = handler;
		int steps = 0;
		while (cur != null && steps++ < 24) {
			int op = cur.getOpcode();
			if (op == MONITOREXIT)
				return true;
			if (op == ATHROW || AsmInsnUtil.isReturn(op) || op == GOTO)
				return false;
			cur = cur.getNext();
		}
		return false;
	}

	private static boolean pruneUnreachableInstructions(@Nonnull MethodNode method) {
		InsnList instructions = method.instructions;
		int size = instructions.size();
		if (size == 0)
			return false;

		AbstractInsnNode[] array = instructions.toArray();
		Map<AbstractInsnNode, Integer> indices = new IdentityHashMap<>(size);
		for (int i = 0; i < size; i++)
			indices.put(array[i], i);

		boolean[] visited = new boolean[size];
		Deque<Integer> queue = new ArrayDeque<>();
		enqueue(0, size, visited, queue);
		for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
			Integer handlerIdx = indices.get(tcb.handler);
			if (handlerIdx != null)
				enqueue(handlerIdx, size, visited, queue);
		}

		while (!queue.isEmpty()) {
			int idx = queue.removeFirst();
			AbstractInsnNode insn = array[idx];
			int op = insn.getOpcode();
			if (insn instanceof JumpInsnNode jump) {
				Integer target = indices.get(jump.label);
				if (target != null)
					enqueue(target, size, visited, queue);
				if (op != GOTO)
					enqueue(idx + 1, size, visited, queue);
			} else if (insn instanceof TableSwitchInsnNode table) {
				Integer dflt = indices.get(table.dflt);
				if (dflt != null) enqueue(dflt, size, visited, queue);
				for (LabelNode label : table.labels) {
					Integer t = indices.get(label);
					if (t != null) enqueue(t, size, visited, queue);
				}
			} else if (insn instanceof LookupSwitchInsnNode lookup) {
				Integer dflt = indices.get(lookup.dflt);
				if (dflt != null) enqueue(dflt, size, visited, queue);
				for (LabelNode label : lookup.labels) {
					Integer t = indices.get(label);
					if (t != null) enqueue(t, size, visited, queue);
				}
			} else if (op != ATHROW && !AsmInsnUtil.isReturn(op)) {
				enqueue(idx + 1, size, visited, queue);
			}
		}

		Set<LabelNode> protectedLabels = Collections.newSetFromMap(new IdentityHashMap<>());
		for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
			protectedLabels.add(tcb.start);
			protectedLabels.add(tcb.end);
			protectedLabels.add(tcb.handler);
		}

		// Compute kept real-instruction prefix sums to prune try-catch ranges that become empty.
		int[] keptPrefix = new int[size + 1];
		for (int i = 0; i < size; i++) {
			AbstractInsnNode insn = array[i];
			boolean keepReal = visited[i] && insn.getOpcode() >= 0 && insn.getOpcode() != NOP;
			keptPrefix[i + 1] = keptPrefix[i] + (keepReal ? 1 : 0);
		}
		method.tryCatchBlocks.removeIf(tcb -> {
			Integer s = indices.get(tcb.start);
			Integer e = indices.get(tcb.end);
			return s == null || e == null || s >= e || keptPrefix[e] <= keptPrefix[s];
		});
		protectedLabels.clear();
		for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
			protectedLabels.add(tcb.start);
			protectedLabels.add(tcb.end);
			protectedLabels.add(tcb.handler);
		}

		boolean removedReal = false;
		for (int i = size - 1; i >= 0; i--) {
			AbstractInsnNode insn = array[i];
			if (!visited[i]) {
				if (i == size - 1 && insn instanceof LabelNode)
					continue;
				if (insn instanceof LabelNode && protectedLabels.contains(insn))
					continue;
				if (insn.getOpcode() >= 0)
					removedReal = true;
				instructions.remove(insn);
			}
		}
		if (removedReal) {
			// Drop stale FrameNodes in this method so ClassWriter(0) never emits mismatched StackMapTable offsets.
			for (AbstractInsnNode insn : instructions.toArray()) {
				if (insn instanceof FrameNode)
					instructions.remove(insn);
			}
		}
		return removedReal;
	}

	private static void enqueue(int idx, int size, boolean[] visited, Deque<Integer> queue) {
		if (idx >= 0 && idx < size && !visited[idx]) {
			visited[idx] = true;
			queue.addLast(idx);
		}
	}

	private static boolean hasSubroutine(@Nonnull MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			int op = insn.getOpcode();
			if (op == JSR || op == RET)
				return true;
		}
		return false;
	}

	@Nullable
	private static AbstractInsnNode firstReal(@Nullable AbstractInsnNode insn) {
		while (insn != null && (insn.getOpcode() < 0 || insn.getOpcode() == NOP))
			insn = insn.getNext();
		return insn;
	}

	@Nonnull
	private static Map<AbstractInsnNode, Integer> indexMap(@Nonnull InsnList instructions) {
		int size = instructions.size();
		Map<AbstractInsnNode, Integer> map = new IdentityHashMap<>(size);
		for (int i = 0; i < size; i++)
			map.put(instructions.get(i), i);
		return map;
	}

	private static boolean isValidNonVoidDesc(@Nullable String desc) {
		if (desc == null || desc.isEmpty())
			return false;
		try {
			Type type = Type.getType(desc);
			return type.getSort() != Type.VOID && type.getSort() != Type.METHOD;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static int normalizedSort(@Nonnull Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Type.INT;
			case Type.ARRAY, Type.OBJECT -> Type.OBJECT;
			default -> type.getSort();
		};
	}

	private record TryCatchKey(LabelNode start, LabelNode end, LabelNode handler, @Nullable String type) {}
}
