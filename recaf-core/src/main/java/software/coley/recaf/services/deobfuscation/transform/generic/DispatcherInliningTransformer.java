package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.util.analysis.value.IntValue;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.HashSet;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Threads constant writes to a local dispatcher state directly to their switch destination.
 * No blocks are copied, and state writes are retained: other readers, exception handlers, loops, and
 * runtime-dependent transitions keep their original semantics. Cleanup passes remove dead bookkeeping.
 */
@Dependent
public class DispatcherInliningTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "peephole.flow.dispatcher";
	private final InheritanceGraphService graphService;
	private InheritanceGraph graph;

	@Inject
	public DispatcherInliningTransformer(@Nonnull InheritanceGraphService graphService) {
		this.graphService = graphService;
	}

	@Override
	public void setup(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace) {
		graph = graphService.getOrCreateInheritanceGraph(workspace);
	}

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.instructions.size() == 0 || method.instructions.size() > 30_000) continue;
			// Subsequent variable cleanup must preserve verifier-visible local initialization at every
			// protected instruction, even non-throwing ones. Leave protected dispatchers intact for now.
			if (!method.tryCatchBlocks.isEmpty()) continue;
			// Analyze before any edits. The inserted jumps only bypass non-throwing loads/jumps/switches.
			Frame<ReValue>[] frames;
			try {
				frames = context.newAnalyzer(graph, node, method).analyze(node.name, method);
			} catch (Exception ex) {
				continue;
			}
			AbstractInsnNode[] instructions = method.instructions.toArray();
			boolean dirty = false;
			for (int i = 0; i < instructions.length; i++) {
				if (!(instructions[i] instanceof VarInsnNode store) || store.getOpcode() != ISTORE) continue;
				Frame<ReValue> frame = i < frames.length ? frames[i] : null;
				if (frame == null || frame.getStackSize() == 0) continue;
				if (!(frame.getStack(frame.getStackSize() - 1) instanceof IntValue value) || !value.hasKnownValue()) continue;
				LabelNode target = destination(store, value.value().getAsInt());
				if (target == null) continue;
				method.instructions.insert(store, new JumpInsnNode(GOTO, target));
				dirty = true;
			}
			// javac can merge `condition ? 321 : 69` on the stack before a single ISTORE.
			// Specialize each literal-producing predecessor by retaining its own state write.
			// Other incoming edges still use the shared store and dispatcher.
			for (int i = 0; i < instructions.length; i++) {
				Integer state = literal(instructions[i]);
				if (state == null || i >= frames.length || frames[i] == null) continue;
				AbstractInsnNode next = follow(instructions[i].getNext(), new HashSet<>());
				if (!(next instanceof VarInsnNode store) || store.getOpcode() != ISTORE) continue;
				LabelNode target = destination(store, state);
				if (target == null) continue;
				InsnList transition = new InsnList();
				transition.add(new VarInsnNode(ISTORE, store.var)); transition.add(new JumpInsnNode(GOTO, target));
				method.instructions.insert(instructions[i], transition);
				dirty = true;
			}
			if (dirty) {
				context.pruneDeadCode(node, method);
				changed = true;
			}
		}
		if (changed) {
			context.setRecomputeFrames(node.name);
			context.setNode(bundle, initialClassState, node);
		}
	}

	private static Integer literal(AbstractInsnNode instruction) {
		int op = instruction.getOpcode();
		if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
		if (instruction instanceof IntInsnNode number && (op == BIPUSH || op == SIPUSH)) return number.operand;
		if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer number) return number;
		return null;
	}

	private static LabelNode destination(VarInsnNode store, int state) {
		Set<AbstractInsnNode> seen = new HashSet<>();
		AbstractInsnNode next = follow(store.getNext(), seen);
		if (!(next instanceof VarInsnNode load) || load.getOpcode() != ILOAD || load.var != store.var) return null;
		next = follow(load.getNext(), seen);
		if (next instanceof TableSwitchInsnNode table)
			return state < table.min || state > table.max ? table.dflt : table.labels.get(state - table.min);
		if (next instanceof LookupSwitchInsnNode lookup) {
			int index = lookup.keys.indexOf(state);
			return index < 0 ? lookup.dflt : lookup.labels.get(index);
		}
		return null;
	}

	private static AbstractInsnNode follow(AbstractInsnNode instruction, Set<AbstractInsnNode> seen) {
		while (instruction != null && seen.size() < 512 && seen.add(instruction)) {
			if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == GOTO) instruction = jump.label;
			else if (instruction.getOpcode() < 0 || instruction.getOpcode() == NOP) instruction = instruction.getNext();
			else return instruction;
		}
		return null;
	}

	@Nonnull @Override public String identifier() { return IDENTIFIER; }
	@Nonnull @Override public Set<Class<? extends ClassTransformer>> recommendedPredecessors() {
		return Set.of(OpaqueConstantFoldingTransformer.class);
	}
	@Nonnull @Override public Set<Class<? extends ClassTransformer>> recommendedSuccessors() {
		return Set.of(OpaquePredicateFoldingTransformer.class, DeadCodeRemovingTransformer.class, GotoInliningTransformer.class, VariableFoldingTransformer.class);
	}
	@Override public String beforeExample() {
		return "int state = 123; while (true) { switch (state) { case 123: state = 321; break; case 321: work(); return; } }";
	}
	@Override public String afterExample() { return "work(); return;"; }
}
