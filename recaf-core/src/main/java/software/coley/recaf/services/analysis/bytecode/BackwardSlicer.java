package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nullable;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis.Definition;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis.Instruction;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis.Slice;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import static org.objectweb.asm.Opcodes.*;
import static software.coley.recaf.services.analysis.bytecode.JvmEvidenceScanner.checkCancelled;
import static software.coley.recaf.services.analysis.bytecode.MethodAnalysis.DefinitionKind.*;

final class BackwardSlicer {
	private static final MemoryKey ALL = new MemoryKey(MemoryKind.ALL, null, null);
	private static final MemoryKey ARRAY = new MemoryKey(MemoryKind.ARRAY, null, null);
	private static final Access ARRAY_READ = new Access(ARRAY, true, false);
	private static final Access ARRAY_WRITE = new Access(ARRAY, false, true);
	private static final Access OPAQUE = new Access(ALL, true, true);
	private final List<Instruction> instructions;
	private final Access[] memory;
	private final Set<MemoryKey> writes = new HashSet<>();
	private final int[][] predecessors;
	private final int[][] exceptionSources;

	BackwardSlicer(List<Instruction> instructions, Access[] memory) {
		this.instructions = instructions;
		this.memory = memory;
		for (Instruction instruction : instructions) {
			Access access = memory[instruction.index()];
			if (access != null && access.write)
				writes.add(access.key);
		}
		predecessors = writes.isEmpty() ? new int[0][] : reverse(instructions, true);
		exceptionSources = reverse(instructions, false);
	}

	@Nullable
	static Access access(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op >= IALOAD && op <= SALOAD) return ARRAY_READ;
		if (op >= IASTORE && op <= SASTORE) return ARRAY_WRITE;
		if (insn instanceof FieldInsnNode field) {
			boolean read = op == GETFIELD || op == GETSTATIC;
			MemoryKind kind = op == GETSTATIC || op == PUTSTATIC ? MemoryKind.STATIC_FIELD : MemoryKind.INSTANCE_FIELD;
			// A field reference through a subclass may resolve to the same inherited field.
			return new Access(new MemoryKey(kind, field.name, field.desc), read, !read);
		}
		if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode ||
				insn instanceof LdcInsnNode ldc && ldc.cst instanceof ConstantDynamic)
			return OPAQUE;
		return null;
	}

	private static int[][] reverse(List<Instruction> instructions, boolean includeNormal) {
		int size = instructions.size();
		int[] counts = new int[size];
		for (Instruction instruction : instructions) {
			if (includeNormal)
				for (int successor : instruction.successors()) counts[successor]++;
			for (var handler : instruction.handlers()) counts[handler.target()]++;
		}
		int[][] result = new int[size][];
		int[] empty = new int[0];
		for (int i = 0; i < size; i++)
			result[i] = counts[i] == 0 ? empty : new int[counts[i]];
		for (Instruction instruction : instructions) {
			if (includeNormal)
				for (int successor : instruction.successors()) result[successor][--counts[successor]] = instruction.index();
			for (var handler : instruction.handlers()) result[handler.target()][--counts[handler.target()]] = instruction.index();
		}
		return result;
	}

	Slice slice(List<Integer> sinks, BooleanSupplier cancelled) {
		var walk = new Walk(cancelled);
		for (int sink : sinks) walk.enqueue(sink);
		return walk.finish(-1);
	}

	Slice sliceValue(int sink, List<Definition> definitions, BooleanSupplier cancelled) {
		var walk = new Walk(cancelled);
		for (int control : instructions.get(sink).controlDependencies()) walk.enqueue(control);
		for (Definition definition : definitions) walk.add(definition);
		return walk.finish(sink);
	}

	private final class Walk {
		private final BooleanSupplier cancelled;
		private final BitSet seen = new BitSet(instructions.size());
		private final ArrayDeque<Integer> pending = new ArrayDeque<>();
		private final Set<Definition> entries = new TreeSet<>();
		private final BitSet allVisited = new BitSet();
		private final Map<MemoryKey, BitSet> heapVisited = new HashMap<>();
		private int[] heapPending;
		private int heapSize;
		private boolean dependsOnInitialHeap;

		private Walk(BooleanSupplier cancelled) {
			this.cancelled = cancelled;
		}

		private void enqueue(int index) {
			if (!seen.get(index)) {
				seen.set(index);
				pending.addLast(index);
			}
		}

		private void add(Definition definition) {
			if (definition.kind() == INSTRUCTION) {
				enqueue(definition.index());
			} else if (entries.add(definition) && definition.kind() == CAUGHT_EXCEPTION) {
				for (int source : exceptionSources[definition.index()]) enqueue(source);
			}
		}

		private Slice finish(int observation) {
			while (!pending.isEmpty()) {
				checkCancelled(cancelled);
				int index = pending.removeFirst();
				Instruction instruction = instructions.get(index);
				for (int control : instruction.controlDependencies()) enqueue(control);
				for (Definition definition : instruction.inputs()) add(definition);
				Access access = memory[index];
				if (access != null && access.read) {
					dependsOnInitialHeap = true;
					includeWrites(index, access.key);
				}
			}
			if (observation >= 0) seen.set(observation);
			return new Slice(seen.stream().boxed().toList(), List.copyOf(entries), dependsOnInitialHeap);
		}

		private void includeWrites(int reader, MemoryKey key) {
			if (writes.isEmpty() || (key != ALL && !writes.contains(key) && !writes.contains(ALL))) return;
			BitSet visited = key == ALL ? allVisited : heapVisited.computeIfAbsent(key, _ -> new BitSet());
			if (heapPending == null) heapPending = new int[instructions.size()];

			includePredecessors(reader, visited);
			int checked = 0;
			while (heapSize > 0) {
				if ((checked++ & 127) == 0) checkCancelled(cancelled);
				int index = heapPending[--heapSize];
				Access access = memory[index];
				if (access != null && access.write && (key == ALL || access.key == ALL || key.equals(access.key)))
					enqueue(index);
				includePredecessors(index, visited);
			}
		}

		private void includePredecessors(int index, BitSet visited) {
			for (int predecessor : predecessors[index]) {
				// A previous unrestricted walk already included every writer upstream of this node.
				if (!visited.get(predecessor) && !allVisited.get(predecessor)) {
					visited.set(predecessor);
					heapPending[heapSize++] = predecessor;
				}
			}
		}
	}

	enum MemoryKind { STATIC_FIELD, INSTANCE_FIELD, ARRAY, ALL }
	record MemoryKey(MemoryKind kind, @Nullable String name, @Nullable String descriptor) {}
	record Access(MemoryKey key, boolean read, boolean write) {}
}
