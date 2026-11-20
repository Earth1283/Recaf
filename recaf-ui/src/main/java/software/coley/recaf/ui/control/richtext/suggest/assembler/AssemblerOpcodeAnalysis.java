package software.coley.recaf.ui.control.richtext.suggest.assembler;

import jakarta.annotation.Nullable;
import me.darknet.assembler.analysis.Value;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.primitive.ASTInstruction;
import me.darknet.assembler.ast.primitive.ASTLabel;
import me.darknet.assembler.backend.dalvik.compile.DalvikCompileResult;
import me.darknet.assembler.backend.dalvik.compile.analysis.DalvikRegisterState;
import me.darknet.assembler.backend.jvm.compile.JavaCompileResult;
import me.darknet.assembler.backend.jvm.compile.analysis.MethodAnalysisResult;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.TypedFrame;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.ValuedFrame;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.WideValue;
import me.darknet.assembler.backend.jvm.util.JvmTypeUtils;
import me.darknet.assembler.compiler.ClassResult;
import org.objectweb.asm.Type;

import java.util.EnumSet;
import java.util.List;
import java.util.function.ToIntFunction;

/** Uses the nearest applicable analysis state from a successful build to rank opcode completions. */
record AssemblerOpcodeAnalysis(String source, List<ASTElement> ast, ClassResult<?> result) {
	private static final ToIntFunction<String> NEUTRAL = opcode -> 0;

	ToIntFunction<String> ranking(String edited, int caret) {
		int start = edited.lastIndexOf('\n', caret - 1) + 1;
		if (start > source.length() || !edited.regionMatches(0, source, 0, start))
			return NEUTRAL;

		int end = endOfLine(edited, caret);
		boolean insertedLine = end < edited.length() && sameSuffix(edited, end + 1, source, start);
		// Reuse analysis only when the rest of the document is still identical.
		if (!insertedLine && !sameSuffix(edited, end, source, endOfLine(source, start)))
			return NEUTRAL;

		var method = AssemblerOperandLocator.findMethod(ast, start);
		if (method == null || method.getCode() == null)
			return NEUTRAL;

		ASTInstruction anchor = null;
		for (ASTInstruction instruction : method.getCode()) {
			if (instruction.range().start() >= start) {
				// An inserted instruction before a branch target sees only the fallthrough state.
				// The state at the target may have been merged with branches jumping over the insertion.
				if (!(instruction instanceof ASTLabel))
					anchor = instruction;
				break;
			}
		}

		String name = method.getName().literal();
		String descriptor = method.getDescriptor().literal();
		Kind returns = kind(Type.getReturnType(descriptor));
		if (result instanceof JavaCompileResult jvm) {
			List<Kind> stack = null;
			if (anchor != null && jvm.analysisLookup().results(name, descriptor) instanceof MethodAnalysisResult analysis) {
				var instruction = analysis.getAstToExecutableInstructionMap().get(anchor);
				Integer index = analysis.getInstructionIndex(instruction);
				if (index != null) {
					stack = switch (analysis.frames().get(index)) {
						case ValuedFrame frame -> frame.getStack().stream()
								.filter(value -> value != WideValue.INSTANCE).limit(3)
								.map(AssemblerOpcodeAnalysis::kind).toList();
						case TypedFrame frame -> frame.getStack().stream()
								.filter(type -> !JvmTypeUtils.isTop(type)).limit(3)
								.map(AssemblerOpcodeAnalysis::kind).toList();
						case null, default -> null;
					};
				}
			}
			List<Kind> inputs = stack;
			return opcode -> jvmRank(opcode, inputs, returns);
		}
		if (result instanceof DalvikCompileResult dalvik) {
			DalvikRegisterState state = null;
			var analysis = dalvik.analysisLookup().getResults(name, descriptor);
			if (anchor != null && analysis != null) {
				for (var instruction : analysis.getCodeInstructions()) {
					if (analysis.getSource(instruction) == anchor) {
						state = analysis.getStateBefore(instruction);
						break;
					}
				}
			}
			EnumSet<RegisterKind> registers = EnumSet.noneOf(RegisterKind.class);
			RegisterKind pending = RegisterKind.UNKNOWN;
			if (state != null) {
				for (var slot : state.slots())
					if (slot instanceof DalvikRegisterState.ValueHead(Value value))
						registers.add(registerKind(kind(value)));
				pending = registerKind(kind(state.pendingResult()));
			}
			RegisterKind pendingResult = pending;
			return opcode -> dalvikRank(opcode, registerKind(returns), registers, pendingResult);
		}
		return NEUTRAL;
	}

	private static int endOfLine(String source, int offset) {
		int newline = source.indexOf('\n', offset);
		return newline < 0 ? source.length() : newline;
	}

	private static boolean sameSuffix(String a, int fromA, String b, int fromB) {
		int length = a.length() - fromA;
		return length == b.length() - fromB && a.regionMatches(fromA, b, fromB, length);
	}

	private static int jvmRank(String opcode, @Nullable List<Kind> stack, Kind returns) {
		Kind operand = switch (opcode.charAt(0)) {
			case 'i', 'b', 'c', 's' -> Kind.INT;
			case 'l' -> Kind.LONG;
			case 'f' -> Kind.FLOAT;
			case 'd' -> Kind.DOUBLE;
			case 'a' -> Kind.REFERENCE;
			default -> Kind.UNKNOWN;
		};
		String operation = opcode.substring(1);
		if (operation.equals("return") || opcode.equals("return")) {
			Kind expected = opcode.equals("return") ? Kind.VOID : operand;
			if (expected != returns)
				return 1;
			return expected == Kind.VOID || stack == null ? -1 : matchStack(stack, expected);
		}
		if (stack == null)
			return 0;
		if (operand != Kind.UNKNOWN) {
			switch (operation) {
				case "add", "sub", "mul", "div", "rem", "and", "or", "xor", "cmp", "cmpl", "cmpg":
					return matchStack(stack, operand, operand);
				case "shl", "shr", "ushr":
					return matchStack(stack, Kind.INT, operand);
				case "neg", "store", "2i", "2l", "2f", "2d", "2b", "2c", "2s":
					return matchStack(stack, operand);
				default:
					break;
			}
		}
		return switch (opcode) {
			case "getfield", "arraylength", "checkcast", "instanceof", "athrow", "ifnull", "ifnonnull" ->
					matchStack(stack, Kind.REFERENCE);
			case "putfield" -> matchStack(stack, Kind.UNKNOWN, Kind.REFERENCE);
			case "putstatic" -> matchStack(stack, Kind.UNKNOWN);
			case "if_acmpeq", "if_acmpne" -> matchStack(stack, Kind.REFERENCE, Kind.REFERENCE);
			case "if_icmpeq", "if_icmpne", "if_icmpge", "if_icmpgt", "if_icmple", "if_icmplt" ->
					matchStack(stack, Kind.INT, Kind.INT);
			case "ifeq", "ifne", "ifge", "ifgt", "ifle", "iflt", "lookupswitch", "tableswitch" ->
					matchStack(stack, Kind.INT);
			default -> 0;
		};
	}

	private static int matchStack(List<Kind> stack, Kind... expected) {
		if (stack.size() < expected.length)
			return 1;
		boolean known = true;
		for (int i = 0; i < expected.length; i++) {
			Kind actual = stack.get(i);
			if (expected[i] != Kind.UNKNOWN && actual != Kind.UNKNOWN && actual != expected[i])
				return 1;
			known &= actual != Kind.UNKNOWN;
		}
		return known ? -1 : 0;
	}

	private static int dalvikRank(String opcode, RegisterKind returns, EnumSet<RegisterKind> registers, RegisterKind pending) {
		String base = opcode.split("/", 2)[0];
		RegisterKind operand = base.endsWith("-wide") ? RegisterKind.WIDE
				: base.endsWith("-object") ? RegisterKind.REFERENCE : RegisterKind.SINGLE;
		return switch (base) {
			case "return-void", "return-void-barrier", "return-void-no-barrier" -> returns == RegisterKind.VOID ? -1 : 1;
			case "return", "return-wide", "return-object" -> returns == operand ? -1 : 1;
			case "move-result", "move-result-wide", "move-result-object" ->
					pending == RegisterKind.UNKNOWN ? 0 : (pending == operand ? -1 : 1);
			case "move", "move-wide", "move-object" -> registers.contains(operand) ? -1 : 0;
			default -> 0;
		};
	}

	private static Kind kind(@Nullable Value value) {
		return value == null || value.type() == null ? Kind.UNKNOWN : kind(JvmTypeUtils.toAsmType(value.type()));
	}

	private static Kind kind(@Nullable Type type) {
		if (type == null || JvmTypeUtils.isTop(type))
			return Kind.UNKNOWN;
		return switch (type.getSort()) {
			case Type.VOID -> Kind.VOID;
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Kind.INT;
			case Type.FLOAT -> Kind.FLOAT;
			case Type.LONG -> Kind.LONG;
			case Type.DOUBLE -> Kind.DOUBLE;
			case Type.OBJECT, Type.ARRAY -> Kind.REFERENCE;
			default -> Kind.UNKNOWN;
		};
	}

	private static RegisterKind registerKind(Kind kind) {
		return switch (kind) {
			case INT, FLOAT -> RegisterKind.SINGLE;
			case DOUBLE, LONG -> RegisterKind.WIDE;
			case VOID -> RegisterKind.VOID;
			case REFERENCE -> RegisterKind.REFERENCE;
			case UNKNOWN -> RegisterKind.UNKNOWN;
		};
	}

	private enum Kind { INT, FLOAT, LONG, DOUBLE, REFERENCE, VOID, UNKNOWN }

	private enum RegisterKind { SINGLE, WIDE, REFERENCE, VOID, UNKNOWN }
}
