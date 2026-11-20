package software.coley.recaf.services.decompile.fallback;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import software.coley.recaf.RecafConstants;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.util.AsmInsnUtil;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.objectweb.asm.Opcodes.*;

/**
 * Read-only views of an original JVM method, available independently of decompilation.
 * References such as {@code @4} are zero-based instruction indices.
 */
public record MethodBytecodeReport(String instructions, String controlFlow, String exceptionRegions, String diagnostics) {
	/**
	 * @param owner
	 * 		Class declaring the method.
	 * @param member
	 * 		Method to inspect.
	 *
	 * @return Disassembly, control flow, exception table, and frame analysis diagnostics.
	 */
	@Nonnull
	public static MethodBytecodeReport inspect(@Nonnull JvmClassInfo owner, @Nonnull MethodMember member) {
		MethodNode[] selected = new MethodNode[1];
		owner.getClassReader().accept(new ClassVisitor(RecafConstants.getAsmVersion()) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				if (!member.getName().equals(name) || !member.getDescriptor().equals(descriptor))
					return null;
				return selected[0] = new MethodNode(RecafConstants.getAsmVersion(), access, name, descriptor, signature, exceptions);
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		if (selected[0] == null)
			throw new IllegalArgumentException("Method missing from class bytecode: " + member.getName() + member.getDescriptor());
		return new MethodBody(selected[0]).report(owner.getName());
	}

	private static final class MethodBody {
		private static final int FRAME_SLOT_LIMIT = 1_000_000;
		private static final int FRAME_EDGE_LIMIT = 100_000;
		private final MethodNode method;
		private final List<AbstractInsnNode> code = new ArrayList<>();
		private final Map<Label, Integer> labels = new IdentityHashMap<>();
		private final Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();

		private MethodBody(MethodNode method) {
			this.method = method;
			for (AbstractInsnNode node : method.instructions) {
				if (node instanceof LabelNode label)
					labels.put(label.getLabel(), code.size());
				else if (node.getOpcode() >= 0) {
					positions.put(node, code.size());
					code.add(node);
				}
			}
		}

		private MethodBytecodeReport report(String owner) {
			if (code.isEmpty())
				return new MethodBytecodeReport("", "", "", "This method has no JVM instructions.");

			StringBuilder instructions = new StringBuilder();
			Textifier textifier = new Textifier(RecafConstants.getAsmVersion()) {
				@Override
				protected void appendLabel(Label label) {
					stringBuilder.append('@').append(labels.get(label));
				}
			};
			TraceMethodVisitor printer = new TraceMethodVisitor(textifier);
			for (int index = 0; index < code.size(); index++) {
				code.get(index).accept(printer);
				instructions.append('@').append(index).append("  ").append(textifier.getText().getFirst().toString().strip()).append('\n');
				textifier.getText().clear();
			}

			String regions = exceptionRegions();
			return new MethodBytecodeReport(instructions.toString(), controlFlow(regions), regions, analyze(owner));
		}

		private String controlFlow(String regions) {
			TreeSet<Integer> leaders = new TreeSet<>();
			leaders.add(0);
			leaders.add(code.size());
			for (int index = 0; index < code.size(); index++) {
				AbstractInsnNode node = code.get(index);
				switch (node) {
					case JumpInsnNode jump -> leaders.add(position(jump.label));
					case TableSwitchInsnNode table -> {
						leaders.add(position(table.dflt));
						table.labels.forEach(label -> leaders.add(position(label)));
					}
					case LookupSwitchInsnNode lookup -> {
						leaders.add(position(lookup.dflt));
						lookup.labels.forEach(label -> leaders.add(position(label)));
					}
					default -> {}
				}
				if (node instanceof JumpInsnNode || node instanceof TableSwitchInsnNode || node instanceof LookupSwitchInsnNode
						|| isExit(node.getOpcode()) || node.getOpcode() == RET)
					leaders.add(index + 1);
			}
			for (TryCatchBlockNode handler : method.tryCatchBlocks) {
				leaders.add(position(handler.start));
				leaders.add(position(handler.end));
				leaders.add(position(handler.handler));
			}

			StringBuilder flow = new StringBuilder("Basic blocks (inclusive instruction ranges)\n\n");
			int start = leaders.pollFirst();
			for (int end : leaders) {
				flow.append('@').append(start).append("–@").append(end - 1).append('\n');
				AbstractInsnNode last = code.get(end - 1);
				switch (last) {
					case JumpInsnNode jump -> {
						int opcode = jump.getOpcode();
						edge(flow, opcode == JSR ? "subroutine" : "jump", position(jump.label));
						if (opcode != GOTO)
							edge(flow, opcode == JSR ? "subroutine return site" : "fallthrough", end);
					}
					case TableSwitchInsnNode table -> {
						for (int i = 0; i < table.labels.size(); i++)
							edge(flow, "case " + (table.min + i), position(table.labels.get(i)));
						edge(flow, "default", position(table.dflt));
					}
					case LookupSwitchInsnNode lookup -> {
						for (int i = 0; i < lookup.keys.size(); i++)
							edge(flow, "case " + lookup.keys.get(i), position(lookup.labels.get(i)));
						edge(flow, "default", position(lookup.dflt));
					}
					default -> {
						int opcode = last.getOpcode();
						if (opcode == RET)
							flow.append("  Dynamic subroutine return\n");
						else if (isExit(opcode))
							flow.append(opcode == ATHROW ? "  Throw\n" : "  Return\n");
						else
							edge(flow, "fallthrough", end);
					}
				}
				start = end;
			}
			if (!regions.isEmpty())
				flow.append("\nException handler edges (protected ranges, in table order)\n\n").append(regions);
			return flow.toString();
		}

		private static boolean isExit(int opcode) {
			return opcode == ATHROW || AsmInsnUtil.isReturn(opcode);
		}

		private void edge(StringBuilder flow, String kind, int destination) {
			flow.append("  ").append(kind).append(" → @").append(destination);
			if (destination == code.size())
				flow.append(" (past end of code)");
			flow.append('\n');
		}

		private int position(LabelNode label) {
			return labels.get(label.getLabel());
		}

		private String exceptionRegions() {
			StringBuilder regions = new StringBuilder();
			for (TryCatchBlockNode handler : method.tryCatchBlocks) {
				regions.append("[@").append(position(handler.start)).append(", @").append(position(handler.end))
						.append(") → @").append(position(handler.handler)).append("  ")
						.append(handler.type == null ? "catch-all" : handler.type).append('\n');
			}
			return regions.toString();
		}

		private String analyze(String owner) {
			String header = "%d instructions, max stack %d, max locals %d, %d exception regions.\n\n"
					.formatted(code.size(), method.maxStack, method.maxLocals, method.tryCatchBlocks.size());
			int nodes = method.instructions.size();
			if ((long) nodes * (method.maxLocals + method.maxStack + 1) > FRAME_SLOT_LIMIT
					|| (long) nodes * method.tryCatchBlocks.size() > FRAME_SLOT_LIMIT)
				return header + "Frame analysis skipped because the method exceeds the analysis size limit.";
			Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicVerifier()) {
				private int remaining = FRAME_EDGE_LIMIT;

				@Override
				protected void newControlFlowEdge(int source, int target) {
					if (--remaining < 0 || Thread.currentThread().isInterrupted())
						throw new IllegalStateException("Frame analysis stopped before completion.");
				}

				@Override
				protected boolean newControlFlowExceptionEdge(int source, int target) {
					newControlFlowEdge(source, target);
					return true;
				}
			};
			try {
				var frames = analyzer.analyze(owner, method);
				int reachable = 0;
				int index = 0;
				for (AbstractInsnNode node : method.instructions) {
					if (node.getOpcode() >= 0 && frames[index] != null)
						reachable++;
					index++;
				}
				return header + "Basic stack and local analysis completed. %d of %d instructions are reachable."
						.formatted(reachable, code.size());
			} catch (AnalyzerException failure) {
				Integer at = positions.get(failure.node);
				return header + "Frame analysis failed" + (at == null ? "" : " at @" + at) + ".\n" + failure.getMessage();
			} catch (RuntimeException failure) {
				return header + "Frame analysis failed.\n" + failure;
			}
		}
	}
}
