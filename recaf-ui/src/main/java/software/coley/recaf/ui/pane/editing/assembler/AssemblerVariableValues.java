package software.coley.recaf.ui.pane.editing.assembler;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import me.darknet.assembler.analysis.Value;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.primitive.ASTIdentifier;
import me.darknet.assembler.ast.primitive.ASTInstruction;
import me.darknet.assembler.ast.specific.ASTMethod;
import me.darknet.assembler.backend.dalvik.DalvikTargetContext;
import me.darknet.assembler.backend.dalvik.compile.DalvikCompileResult;
import me.darknet.assembler.backend.dalvik.compile.analysis.DalvikAnalysisResults;
import me.darknet.assembler.backend.dalvik.compile.analysis.DalvikRegisterState;
import me.darknet.assembler.backend.dalvik.instructions.DalvikOperands;
import me.darknet.assembler.backend.jvm.JvmTargetContext;
import me.darknet.assembler.backend.jvm.compile.JavaCompileResult;
import me.darknet.assembler.backend.jvm.compile.analysis.MethodAnalysisResult;
import me.darknet.assembler.backend.jvm.compile.analysis.ValuedLocal;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.ValuedFrame;
import me.darknet.assembler.backend.jvm.compile.analysis.frame.WideValue;
import me.darknet.assembler.compiler.ClassResult;
import me.darknet.assembler.instructions.InstructionTrait;
import me.darknet.assembler.query.AssemblyQueries;
import me.darknet.assembler.query.AssemblyUtils;
import me.darknet.assembler.query.VariableAccessKind;
import me.darknet.assembler.query.resolution.VariableResolution;
import me.darknet.dex.tree.definitions.instructions.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.coley.recaf.ui.control.richtext.VariableValueHover;

import java.util.List;

/** Resolves local and register previews from the analysis of the current assembly. */
final class AssemblerVariableValues {
	private AssemblerVariableValues() {}

	@Nullable
	static VariableValueHover.Preview resolve(@Nonnull List<ASTElement> ast, @Nonnull ClassResult<?> result, int offset) {
		return switch (result) {
			case JavaCompileResult jvm -> resolveJvm(ast, jvm, offset);
			case DalvikCompileResult dalvik -> resolveDalvik(ast, dalvik, offset);
			default -> null;
		};
	}

	@Nullable
	private static VariableValueHover.Preview resolveJvm(List<ASTElement> ast, JavaCompileResult result, int offset) {
		if (!(AssemblyQueries.resolveAt(ast, offset, JvmTargetContext.INSTANCE) instanceof VariableResolution reference)
				|| !contains(reference.identifier(), offset))
			return null;
		ASTInstruction source = AssemblyUtils.findEnclosingInstruction(reference.identifier());
		if (source == null)
			return null;
		ASTMethod method = reference.method();
		if (!(result.analysisLookup().results(method.getName().literal(), method.getDescriptor().literal())
				instanceof MethodAnalysisResult analysis))
			return null;

		AbstractInsnNode instruction = analysis.getAstToExecutableInstructionMap().get(source);
		Integer index = analysis.getInstructionIndex(instruction);
		if (index == null || !(analysis.frames().get(index) instanceof ValuedFrame frame))
			return null;

		Value value;
		if (instruction instanceof VarInsnNode variable) {
			if (AssemblyUtils.variableAccessKind(JvmTargetContext.INSTANCE, source) == VariableAccessKind.WRITE) {
				// The store's input is specific to this instruction even when its successor merges control flow.
				var stack = frame.getStack().iterator();
				value = stack.hasNext() ? stack.next() : null;
				if (value == WideValue.INSTANCE)
					value = stack.hasNext() ? stack.next() : null;
			} else {
				ValuedLocal local = frame.getLocal(variable.var);
				value = local == null ? null : local.value();
			}
		} else if (instruction instanceof IincInsnNode increment) {
			ValuedLocal local = frame.getLocal(increment.var);
			value = local != null && local.value() instanceof Value.KnownIntValue(int prior)
					? new Value.KnownIntValue(prior + increment.incr) : null;
		} else {
			return null;
		}
		return preview(reference.identifier().literal(), value);
	}

	@Nullable
	private static VariableValueHover.Preview resolveDalvik(List<ASTElement> ast, DalvikCompileResult result, int offset) {
		if (!(AssemblyUtils.pickElementAt(ast, offset) instanceof ASTIdentifier identifier) || !contains(identifier, offset))
			return null;
		ASTInstruction instruction = AssemblyUtils.findEnclosingInstruction(identifier);
		if (instruction == null || !(instruction.parent() != null && instruction.parent().parent() instanceof ASTMethod method))
			return null;
		var definition = DalvikTargetContext.INSTANCE.instructions().get(instruction.identifier().content());
		if (definition == null)
			return null;
		ASTElement operand = identifier;
		while (operand.parent() != instruction)
			operand = operand.parent();
		int operandIndex = instruction.arguments().indexOf(operand);
		if (operandIndex < 0 || operandIndex >= definition.operandCount())
			return null;
		var kind = definition.operand(operandIndex);
		if (kind != DalvikOperands.REGISTER.getOperand() && kind != DalvikOperands.REGISTER_WIDE.getOperand()
				&& kind != DalvikOperands.REGISTER_OR_LITERAL.getOperand() && kind != DalvikOperands.REGISTER_ARRAY.getOperand()
				&& kind != DalvikOperands.INVOKE_REGISTERS.getOperand())
			return null;

		DalvikAnalysisResults analysis = result.analysisLookup().getResults(method.getName().literal(), method.getDescriptor().literal());
		if (analysis == null)
			return null;
		boolean write = operandIndex == 0 && definition.hasTrait(InstructionTrait.REGISTER_WRITE);
		var instructions = analysis.getCodeInstructions();
		for (int index = 0; index < instructions.size(); index++) {
			if (analysis.getSource(instructions.get(index)) != instruction)
				continue;
			DalvikRegisterState state = analysis.getStateBefore(write ? index + 1 : index);
			if (state == null || analysis.getStateBefore(index) == null)
				return null;
			String name = identifier.literal();
			int register = registerIndex(method, state.registerCount(), name);
			if (register < 0)
				register = namedRegisterIndex(analysis, name);
			if (register >= 0 && register < state.registerCount()
					&& state.slot(register) instanceof DalvikRegisterState.ValueHead(Value value))
				return preview(name, value);
			return null;
		}
		return null;
	}

	private static int namedRegisterIndex(DalvikAnalysisResults analysis, String name) {
		for (Instruction compiled : analysis.getCodeInstructions()) {
			ASTInstruction source = analysis.getSource(compiled);
			if (source == null || source.arguments().isEmpty() ||
					!(source.arguments().getFirst() instanceof ASTIdentifier destination) || !name.equals(destination.literal()))
				continue;
			var definition = DalvikTargetContext.INSTANCE.instructions().get(source.identifier().content());
			if (definition == null || !definition.hasTrait(InstructionTrait.REGISTER_WRITE))
				continue;

			int register = writtenRegister(compiled);
			if (register >= 0)
				return register;
		}
		return -1;
	}

	// The first source operand of a register-writing instruction is its destination.
	private static int writtenRegister(Instruction instruction) {
		return switch (instruction) {
			case ArrayInstruction array -> array.value();
			case ArrayLengthInstruction length -> length.dest();
			case Binary2AddrInstruction binary -> binary.a();
			case BinaryInstruction binary -> binary.dest();
			case BinaryLiteralInstruction binary -> binary.dest();
			case CheckCastInstruction cast -> cast.register();
			case CompareInstruction compare -> compare.dest();
			case ConstInstruction constant -> constant.register();
			case ConstMethodHandleInstruction constant -> constant.destination();
			case ConstMethodTypeInstruction constant -> constant.destination();
			case ConstStringInstruction constant -> constant.register();
			case ConstTypeInstruction constant -> constant.register();
			case ConstWideInstruction constant -> constant.register();
			case InstanceFieldInstruction field -> field.value();
			case InstanceOfInstruction check -> check.destination();
			case MoveExceptionInstruction move -> move.register();
			case MoveInstruction move -> move.to();
			case MoveObjectInstruction move -> move.to();
			case MoveResultInstruction move -> move.to();
			case MoveWideInstruction move -> move.to();
			case NewArrayInstruction array -> array.dest();
			case NewInstanceInstruction instance -> instance.dest();
			case StaticFieldInstruction field -> field.value();
			case UnaryInstruction unary -> unary.dest();
			default -> -1;
		};
	}

	private static int registerIndex(ASTMethod method, int count, String name) {
		boolean isStatic = method.getModifiers().hasModifier("static");
		Type[] arguments = Type.getArgumentTypes(method.getDescriptor().literal());
		int parameterBase = count - (isStatic ? 0 : 1);
		for (Type argument : arguments)
			parameterBase -= argument.getSize();
		if (name.matches("[vp][0-9]+")) {
			try {
				int index = Integer.parseInt(name.substring(1));
				return name.charAt(0) == 'v' ? index : parameterBase + index;
			} catch (NumberFormatException ignored) {
				return -1;
			}
		}
		if (!isStatic && name.equals("this"))
			return parameterBase;
		int index = parameterBase + (isStatic ? 0 : 1);
		List<ASTIdentifier> parameters = method.getParameters();
		for (int i = 0; i < Math.min(parameters.size(), arguments.length); i++) {
			if (parameters.get(i).literal().equals(name))
				return index;
			index += arguments[i].getSize();
		}
		return -1;
	}

	private static boolean contains(ASTElement element, int offset) {
		return offset >= element.range().start() && offset < element.range().end();
	}

	@Nullable
	private static VariableValueHover.Preview preview(String name, @Nullable Value value) {
		if (value == null || !value.isKnown())
			return null;
		String text = value instanceof Value.KnownStringValue(String string)
				? VariableValueHover.quote(string, '"') : value.valueAsString();
		if (text == null)
			return null;
		var type = value.type();
		String typeName = type == null ? null : Type.getType(type.descriptor()).getClassName();
		return new VariableValueHover.Preview(name, typeName, text);
	}
}
