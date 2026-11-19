package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nullable;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import software.coley.recaf.util.AsmInsnUtil;
import software.coley.recaf.util.analysis.ReInterpreter;
import software.coley.recaf.util.analysis.value.*;

import java.util.*;
import java.util.function.BooleanSupplier;

import static org.objectweb.asm.Opcodes.*;
import static software.coley.recaf.services.analysis.bytecode.JvmEvidenceScanner.checkCancelled;

/** Intraprocedural analysis of a JVM method. Instruction numbers exclude labels, frames and line numbers. */
public final class MethodAnalysis {
	private static final int MAX_INSTRUCTIONS = 4_096;
	private static final int MAX_FRAME_SLOTS = 262_144;
	private static final int MAX_WORK = 2_000_000;
	public static final int NO_PARENT = -1;
	public static final int METHOD_EXIT = -2;

	private MethodAnalysis() {}

	public enum DefinitionKind { PARAMETER, INSTRUCTION, CAUGHT_EXCEPTION }

	public record Definition(DefinitionKind kind, int index) implements Comparable<Definition> {
		@Override
		public int compareTo(Definition other) {
			int kindOrder = kind.compareTo(other.kind);
			return kindOrder == 0 ? Integer.compare(index, other.index) : kindOrder;
		}

		@Override
		public String toString() {
			return switch (kind) {
				case PARAMETER -> "parameter local " + index;
				case INSTRUCTION -> "@" + index;
				case CAUGHT_EXCEPTION -> "exception at handler @" + index;
			};
		}
	}

	public record ValueState(int slot, @Nullable ReValue abstractValue, List<Definition> definitions) {
		public String value() { return describe(abstractValue); }
	}
	public record Handler(int target, String catchType) {}
	public record Instruction(int index, String opcode, boolean reachable, List<Integer> successors,
	                          List<Handler> handlers, boolean mayEscape, int immediateDominator,
	                          int immediatePostDominator, List<Integer> controlDependencies,
	                          List<Definition> inputs, List<ValueState> locals, List<ValueState> stack) {}
	public record Slice(List<Integer> instructions, List<Definition> entryDefinitions) {}
	public record Summary(int reachableInstructions, List<Integer> returns, List<String> returnValues,
	                      List<Integer> explicitThrows, List<Integer> escapingExceptionSites,
	                      List<String> fieldReads, List<String> fieldWrites, List<String> calls,
	                      List<String> allocations, int arrayWrites, int monitorOperations,
	                      Slice returnSlice) {}
	public record Result(List<Instruction> instructions, Map<Definition, List<Integer>> uses,
	                     Summary summary, int nonTerminatingInstructions) {
		public Slice backwardSlice(int sink) {
			Objects.checkIndex(sink, instructions.size());
			return slice(instructions, List.of(sink));
		}
	}

	public static Result analyze(String owner, MethodNode method, BooleanSupplier cancelled) throws AnalyzerException {
		checkCancelled(cancelled);
		AbstractInsnNode[] code = method.instructions.toArray();
		if (code.length == 0 || (method.access & (ACC_NATIVE | ACC_ABSTRACT)) != 0)
			throw new IllegalArgumentException("The method has no JVM bytecode.");
		if (code.length > MAX_INSTRUCTIONS || (long) code.length * (method.maxLocals + method.maxStack + 1) > MAX_FRAME_SLOTS
				|| (long) code.length * method.tryCatchBlocks.size() > MAX_FRAME_SLOTS)
			throw new IllegalArgumentException("The method exceeds the analysis size limit.");
		int realCount = 0;
		int[] ordinals = new int[code.length];
		Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
		for (int i = 0; i < code.length; i++) {
			if (code[i].getOpcode() == JSR || code[i].getOpcode() == RET)
				throw new IllegalArgumentException("The method uses a legacy JVM subroutine.");
			ordinals[i] = code[i].getOpcode() < 0 ? -1 : realCount++;
			positions.put(code[i], i);
		}
		if (realCount == 0) throw new IllegalArgumentException("The method has no JVM instructions.");
		int[] nextReal = new int[code.length];
		int next = -1;
		for (int i = code.length - 1; i >= 0; i--) {
			if (ordinals[i] >= 0) next = ordinals[i];
			nextReal[i] = next;
		}
		var budget = new WorkBudget(cancelled);
		var normal = new ArrayList<Set<Integer>>(realCount);
		var handlers = new ArrayList<Set<Handler>>(realCount);
		for (int i = 0; i < realCount; i++) {
			normal.add(new LinkedHashSet<>());
			handlers.add(new LinkedHashSet<>());
		}
		var interpreter = new Definitions(positions, ordinals, nextReal, budget);
		var sourceAnalyzer = new Analyzer<>(interpreter) {
			@Override
			protected void newControlFlowEdge(int from, int to) {
				budget.spend(1);
				if (ordinals[from] >= 0 && nextReal[to] >= 0) normal.get(ordinals[from]).add(nextReal[to]);
			}

			@Override
			protected boolean newControlFlowExceptionEdge(int index, TryCatchBlockNode block) {
				budget.spend(1);
				if (!mayThrow(code[index])) return false;
				int target = nextReal[positions.get(block.handler)];
				if (target < 0) return false;
				var targets = handlers.get(ordinals[index]);
				String type = block.type == null ? "java/lang/Throwable" : block.type;
				String thrown = knownThrownType(code[index].getOpcode());
				if (thrown != null && !catchesKnownException(type, thrown)) return false;
				for (Handler prior : targets) {
					if (coversAllExceptions(prior.catchType(), thrown)) return prior.equals(new Handler(target, type));
					if (prior.catchType().equals(type)) return prior.target() == target;
				}
				targets.add(new Handler(target, type));
				return true;
			}
		};
		Frame<SourceValue>[] sources;
		Frame<ReValue>[] values;
		try {
			sources = sourceAnalyzer.analyze(owner, method);
			values = new Analyzer<>(new ReInterpreter(null)) {
				@Override
				protected void newControlFlowEdge(int from, int to) { budget.spend(1); }

				@Override
				protected boolean newControlFlowExceptionEdge(int index, TryCatchBlockNode block) {
					budget.spend(1);
					return ordinals[index] >= 0 && handlers.get(ordinals[index]).contains(new Handler(nextReal[positions.get(block.handler)],
							block.type == null ? "java/lang/Throwable" : block.type));
				}
			}.analyze(owner, method);
		} catch (AnalyzerException error) {
			checkCancelled(cancelled);
			throw error;
		}
		var graph = new MethodDominance(realCount, cancelled);
		boolean[] escapes = new boolean[realCount];
		for (int i = 0; i < code.length; i++) {
			int ordinal = ordinals[i];
			if (ordinal < 0 || sources[i] == null) continue;
			graph.enable(ordinal);
			for (int target : normal.get(ordinal)) graph.connect(ordinal, target);
			for (Handler handler : handlers.get(ordinal)) graph.connect(ordinal, handler.target());
			String thrown = knownThrownType(code[i].getOpcode());
			escapes[ordinal] = mayThrow(code[i]) && handlers.get(ordinal).stream().noneMatch(handler -> coversAllExceptions(handler.catchType(), thrown));
			if (isReturn(code[i].getOpcode()) || escapes[ordinal]) graph.connect(ordinal, realCount);
		}
		var dominance = graph.analyze();
		var instructions = new ArrayList<Instruction>(realCount);
		Map<Definition, List<Integer>> uses = new TreeMap<>();
		Frame<SourceValue> replay = new Frame<>(method.maxLocals, method.maxStack);
		for (int i = 0; i < code.length; i++) {
			checkCancelled(cancelled);
			int ordinal = ordinals[i];
			if (ordinal < 0) continue;
			boolean reachable = sources[i] != null;
			List<Definition> inputs = List.of();
			List<ValueState> locals = List.of(), stack = List.of();
			if (reachable) {
				interpreter.inputs = new TreeSet<>();
				int opcode = code[i].getOpcode();
				if (opcode == POP || opcode == POP2) {
					int top = sources[i].getStackSize() - 1;
					SourceValue discarded = sources[i].getStack(top);
					interpreter.consume(discarded);
					if (opcode == POP2 && discarded.getSize() == 1) interpreter.consume(sources[i].getStack(top - 1));
				}
				replay.init(sources[i]).execute(code[i], interpreter);
				inputs = List.copyOf(interpreter.inputs);
				interpreter.inputs = null;
				for (Definition input : inputs) uses.computeIfAbsent(input, _ -> new ArrayList<>()).add(ordinal);
				locals = states(interpreter, sources[i], values[i], true);
				stack = states(interpreter, sources[i], values[i], false);
			}
			instructions.add(new Instruction(ordinal, BytecodeInvestigationService.instruction(code[i]), reachable,
					List.copyOf(normal.get(ordinal)), List.copyOf(handlers.get(ordinal)), escapes[ordinal],
					dominance.dominators()[ordinal], dominance.postDominators()[ordinal], dominance.controls().get(ordinal), inputs, locals, stack));
		}
		uses.replaceAll((_, ords) -> List.copyOf(ords));
		var immutableInstructions = List.copyOf(instructions);
		return new Result(immutableInstructions, Collections.unmodifiableMap(uses), summarize(code, ordinals, immutableInstructions),
				dominance.nonTerminating());
	}

	private static List<ValueState> states(Definitions interpreter, Frame<SourceValue> definitions, Frame<ReValue> values, boolean local) {
		List<ValueState> out = new ArrayList<>();
		int size = local ? definitions.getLocals() : definitions.getStackSize();
		for (int i = 0; i < size; i++) {
			var source = local ? definitions.getLocal(i) : definitions.getStack(i);
			var value = values == null ? null : local ? values.getLocal(i) : values.getStack(i);
			if (local && source.insns.isEmpty() && (value == null || value.type() == null)) continue;
			var origins = new TreeSet<Definition>();
			interpreter.addDefinitions(origins, source);
			out.add(new ValueState(i, value, List.copyOf(origins)));
		}
		return List.copyOf(out);
	}

	private static String describe(@Nullable ReValue value) {
		if (value == null || value.type() == null) return "?";
		if (value instanceof StringValue string && string.getText().isPresent()) {
			String text = string.getText().orElseThrow();
			return BytecodeInvestigationService.literal(text.length() <= 120 ? text : text.substring(0, 120) + "…");
		}
		if (value instanceof ObjectValue object) {
			if (object.isNull()) return "null";
			return value.type().getDescriptor() + (object.isNotNull() ? " (non-null)" : " (unknown nullability)");
		}
		return value.hasKnownValue() ? value.toString() : value.type().getDescriptor() + " (unknown)";
	}

	private static Summary summarize(AbstractInsnNode[] code, int[] ordinals, List<Instruction> instructions) {
		var returns = new ArrayList<Integer>();
		var throwsAt = new ArrayList<Integer>();
		var escaping = new ArrayList<Integer>();
		var returnValues = new TreeSet<String>();
		var reads = new TreeSet<String>();
		var writes = new TreeSet<String>();
		var calls = new TreeSet<String>();
		var allocations = new TreeSet<String>();
		int reachable = 0, arrayWrites = 0, monitors = 0;
		for (int i = 0; i < code.length; i++) {
			int index = ordinals[i];
			if (index < 0 || !instructions.get(index).reachable()) continue;
			Instruction fact = instructions.get(index);
			reachable++;
			if (fact.mayEscape()) escaping.add(index);
			AbstractInsnNode insn = code[i];
			int opcode = insn.getOpcode();
			if (isReturn(opcode)) {
				returns.add(index);
				returnValues.add(opcode == RETURN ? "void" : fact.stack().getLast().value());
			}
			if (opcode == ATHROW) throwsAt.add(index);
			if (opcode >= IASTORE && opcode <= SASTORE) arrayWrites++;
			if (opcode == MONITORENTER || opcode == MONITOREXIT) monitors++;
			switch (insn) {
				case FieldInsnNode field -> (opcode == GETFIELD || opcode == GETSTATIC ? reads : writes).add(field.owner + "." + field.name + " " + field.desc);
				case MethodInsnNode call -> calls.add(call.owner + "." + call.name + call.desc);
				case InvokeDynamicInsnNode call -> calls.add("invokedynamic " + call.name + call.desc + " via " + call.bsm.getOwner() + "." + call.bsm.getName());
				case TypeInsnNode type when opcode == NEW || opcode == ANEWARRAY -> allocations.add(fact.opcode());
				case IntInsnNode ignored when opcode == NEWARRAY -> allocations.add(fact.opcode());
				case MultiANewArrayInsnNode array -> allocations.add("multianewarray " + array.desc);
				default -> { }
			}
		}
		return new Summary(reachable, List.copyOf(returns), List.copyOf(returnValues), List.copyOf(throwsAt), List.copyOf(escaping),
				List.copyOf(reads), List.copyOf(writes), List.copyOf(calls), List.copyOf(allocations), arrayWrites, monitors,
				slice(instructions, returns));
	}

	private static Slice slice(List<Instruction> instructions, List<Integer> sinks) {
		var pending = new ArrayDeque<Integer>();
		var seen = new BitSet();
		for (int sink : sinks) enqueue(pending, seen, sink);
		var entries = new TreeSet<Definition>();
		Map<Integer, List<Integer>> exceptionSources = new HashMap<>();
		for (Instruction insn : instructions) {
			for (Handler handler : insn.handlers())
				exceptionSources.computeIfAbsent(handler.target(), _ -> new ArrayList<>()).add(insn.index());
		}
		while (!pending.isEmpty()) {
			int index = pending.removeFirst();
			Instruction instruction = instructions.get(index);
			for (int dependency : instruction.controlDependencies()) enqueue(pending, seen, dependency);
			for (Definition def : instruction.inputs()) {
				if (def.kind() == DefinitionKind.INSTRUCTION) enqueue(pending, seen, def.index());
				else if (entries.add(def) && def.kind() == DefinitionKind.CAUGHT_EXCEPTION)
					for (int source : exceptionSources.getOrDefault(def.index(), List.of())) enqueue(pending, seen, source);
			}
		}
		return new Slice(seen.stream().boxed().toList(), List.copyOf(entries));
	}

	private static void enqueue(Deque<Integer> pending, BitSet seen, int index) {
		if (!seen.get(index)) {
			seen.set(index);
			pending.add(index);
		}
	}

	private static boolean isReturn(int opcode) { return opcode >= IRETURN && opcode <= RETURN; }

	@Nullable
	private static String knownThrownType(int opcode) {
		return switch (opcode) {
			case IDIV, IREM, LDIV, LREM -> "java/lang/ArithmeticException";
			case ARRAYLENGTH, MONITORENTER -> "java/lang/NullPointerException";
			default -> null;
		};
	}

	private static boolean coversAllExceptions(String handler, @Nullable String thrown) {
		return thrown == null ? "java/lang/Throwable".equals(handler) : catchesKnownException(handler, thrown);
	}

	private static boolean catchesKnownException(String handler, String thrown) {
		return thrown.equals(handler) || "java/lang/RuntimeException".equals(handler)
				|| "java/lang/Exception".equals(handler) || "java/lang/Throwable".equals(handler);
	}

	private static boolean mayThrow(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode > SALOAD && opcode < IASTORE) return false;
		return AsmInsnUtil.canThrow(insn) || opcode == GETSTATIC || opcode == PUTSTATIC
				|| insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Type || ldc.cst instanceof Handle || ldc.cst instanceof ConstantDynamic);
	}

	private static final class WorkBudget {
		private final BooleanSupplier cancelled;
		private int remaining = MAX_WORK;

		private WorkBudget(BooleanSupplier cancelled) { this.cancelled = cancelled; }

		void spend(int work) {
			if ((remaining & ~127) != ((remaining - work) & ~127)) checkCancelled(cancelled);
			remaining -= work;
			if (remaining < 0) throw new IllegalArgumentException("The method exceeds the analysis work limit.");
		}
	}

	private static final class Definitions extends SourceInterpreter {
		private final Map<AbstractInsnNode, Integer> positions;
		private final Map<AbstractInsnNode, Definition> synthetic = new IdentityHashMap<>();
		private final Map<Integer, SourceValue> exceptions = new HashMap<>();
		private final int[] ordinals;
		private final int[] nextReal;
		private final WorkBudget budget;
		private Set<Definition> inputs;

		private Definitions(Map<AbstractInsnNode, Integer> positions, int[] ordinals, int[] nextReal, WorkBudget budget) {
			super(ASM9);
			this.positions = positions;
			this.ordinals = ordinals;
			this.nextReal = nextReal;
			this.budget = budget;
		}

		@Override
		public SourceValue newParameterValue(boolean instance, int slot, Type type) {
			return definition(type.getSize(), new Definition(DefinitionKind.PARAMETER, slot));
		}

		@Override
		public SourceValue newExceptionValue(TryCatchBlockNode block, Frame<SourceValue> frame, Type type) {
			int target = nextReal[positions.get(block.handler)];
			return exceptions.computeIfAbsent(target, _ -> definition(1, new Definition(DefinitionKind.CAUGHT_EXCEPTION, target)));
		}

		@Override
		public SourceValue merge(SourceValue left, SourceValue right) {
			if (left == right) return left;
			budget.spend(1 + left.insns.size() + right.insns.size());
			return super.merge(left, right);
		}

		private SourceValue definition(int size, Definition value) {
			var marker = new LabelNode();
			synthetic.put(marker, value);
			return new SourceValue(size, marker);
		}

		private void addDefinitions(Set<Definition> target, SourceValue value) {
			budget.spend(value.insns.size());
			for (AbstractInsnNode source : value.insns) {
				Definition marker = synthetic.get(source);
				if (marker != null) target.add(marker);
				else target.add(new Definition(DefinitionKind.INSTRUCTION, ordinals[positions.get(source)]));
			}
		}

		private void consume(SourceValue value) {
			if (inputs != null) addDefinitions(inputs, value);
		}

		@Override public SourceValue copyOperation(AbstractInsnNode i, SourceValue v) { consume(v); return super.copyOperation(i, v); }
		@Override public SourceValue unaryOperation(AbstractInsnNode i, SourceValue v) { consume(v); return super.unaryOperation(i, v); }
		@Override public SourceValue binaryOperation(AbstractInsnNode i, SourceValue a, SourceValue b) { consume(a); consume(b); return super.binaryOperation(i, a, b); }
		@Override public SourceValue ternaryOperation(AbstractInsnNode i, SourceValue a, SourceValue b, SourceValue c) { consume(a); consume(b); consume(c); return super.ternaryOperation(i, a, b, c); }
		@Override public SourceValue naryOperation(AbstractInsnNode i, List<? extends SourceValue> values) { values.forEach(this::consume); return super.naryOperation(i, values); }
		@Override public void returnOperation(AbstractInsnNode i, SourceValue value, SourceValue expected) { consume(value); }
	}
}
