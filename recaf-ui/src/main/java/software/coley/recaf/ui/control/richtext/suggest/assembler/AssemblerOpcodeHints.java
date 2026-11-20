package software.coley.recaf.ui.control.richtext.suggest.assembler;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import me.darknet.assembler.ast.primitive.ASTInstruction;
import me.darknet.assembler.backend.dalvik.DalvikTargetContext;
import me.darknet.assembler.backend.dalvik.instructions.DalvikOperands;
import me.darknet.assembler.backend.jvm.instructions.JvmOperands;
import me.darknet.assembler.instructions.DefaultOperands;
import me.darknet.assembler.instructions.Instruction;
import me.darknet.assembler.instructions.Operand;
import me.darknet.assembler.instructions.Operands;
import me.darknet.assembler.query.AssemblyUtils;
import me.darknet.assembler.target.TargetContext;
import software.coley.recaf.util.Types;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import static me.darknet.assembler.instructions.InstructionTrait.*;

/** Instruction documentation shared by the assembler editor and its completion popup. */
public final class AssemblerOpcodeHints {
	private static final Map<Operand, String> OPERANDS = new IdentityHashMap<>();
	private static final Map<String, String> ARITHMETIC = Map.ofEntries(
			Map.entry("add", "Add"), Map.entry("sub", "Subtract"), Map.entry("rsub", "Subtract from a literal"),
			Map.entry("mul", "Multiply"), Map.entry("div", "Divide"), Map.entry("rem", "Compute remainder"),
			Map.entry("and", "Bitwise AND"), Map.entry("or", "Bitwise OR"), Map.entry("xor", "Bitwise XOR"),
			Map.entry("shl", "Shift left"), Map.entry("shr", "Shift right"), Map.entry("ushr", "Shift right unsigned"),
			Map.entry("neg", "Negate"), Map.entry("not", "Bitwise complement"));

	static {
		register(DefaultOperands.values());
		register(JvmOperands.values());
		register(DalvikOperands.values());
		OPERANDS.put(DefaultOperands.MEMBER_PATH.getOperand(), "owner.member");
		OPERANDS.put(JvmOperands.ARGS.getOperand(), "bootstrap arguments");
		OPERANDS.put(DalvikOperands.ARGS_ARRAY.getOperand(), "bootstrap arguments");
	}

	private AssemblerOpcodeHints() {}

	private static <E extends Enum<E> & Operands> void register(E[] values) {
		for (E value : values)
			OPERANDS.put(value.getOperand(), value.name().toLowerCase(Locale.ROOT).replace('_', ' '));
	}

	/** @return Documentation for the mnemonic in the selected dialect, if recognized. */
	@Nullable
	public static Hint get(@Nonnull TargetContext target, @Nonnull String mnemonic) {
		Instruction<?> instruction = target.instructions().get(mnemonic);
		if (instruction == null)
			return null;
		boolean dalvik = target == DalvikTargetContext.INSTANCE;
		var operands = new ArrayList<String>(instruction.operandCount());
		boolean reads = false;
		boolean writes = false;
		for (int i = 0; i < instruction.operandCount(); i++) {
			Operand operand = instruction.operand(i);
			String name = OPERANDS.getOrDefault(operand, "operand");
			if (dalvik && name.contains("register")) {
				if (i == 0 && instruction.hasTrait(REGISTER_WRITE)) {
					writes = true;
					boolean readWrite = mnemonic.contains("/2addr") || mnemonic.equals("check-cast");
					reads |= readWrite;
					name = (readWrite ? "read/write " : "destination ") + name;
				} else if (instruction.hasTrait(REGISTER_READ) || instruction.hasTrait(INVOKE)) {
					reads = true;
					name = "source " + name;
				}
			}
			operands.add(name);
		}
		String syntax = operands.isEmpty() ? mnemonic : mnemonic + "  " + String.join(", ", operands);
		Effect effect = dalvik ? dalvikEffect(mnemonic, instruction, reads, writes) : jvmEffect(mnemonic, instruction);
		return new Hint(syntax, effect.description(), effect.state(), instruction.unavailableReason());
	}

	/** @return Readable signature of the instruction's member operand, if present. */
	@Nullable
	public static String memberSignature(@Nonnull TargetContext target, @Nonnull ASTInstruction instruction) {
		var member = AssemblyUtils.resolveMemberReference(target, instruction);
		if (member == null)
			return null;
		String type = Types.pretty(member.descriptor());
		return member.owner().replace('/', '.') + "." + member.name() + (member.isMethod() ? "" : "  ") + type;
	}

	/** @return The opcode under the pointer, including while another part of the buffer has a syntax error. */
	@Nullable
	public static Hint at(@Nonnull TargetContext target, @Nonnull String source, int offset) {
		if (offset < 0 || offset >= source.length())
			return null;
		int start = source.lastIndexOf('\n', offset - 1) + 1;
		while (start < source.length() && (source.charAt(start) == ' ' || source.charAt(start) == '\t'))
			start++;
		int end = start;
		while (end < source.length() && isMnemonicCharacter(source.charAt(end)))
			end++;
		if (offset < start || offset >= end)
			return null;
		int next = end;
		while (next < source.length() && (source.charAt(next) == ' ' || source.charAt(next) == '\t'))
			next++;
		if (next < source.length() && source.charAt(next) == ':')
			return null;
		Hint hint = get(target, source.substring(start, end));
		return hint != null && AssemblerOperandLocator.isCaretInCodeBlock(source, start) ? hint : null;
	}

	static boolean isMnemonicCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '-' || character == '_' || character == '/';
	}

	private static Effect jvmEffect(String name, Instruction<?> instruction) {
		String type = jvmType(name.charAt(0));
		String operation = name.substring(1);
		String arithmetic = ARITHMETIC.get(operation);
		if (type != null && arithmetic != null) {
			String input = switch (operation) {
				case "neg" -> type;
				case "shl", "shr", "ushr" -> type + ", int";
				default -> type + ", " + type;
			};
			return stack(arithmetic, input, type);
		}
		if (type != null) {
			if (operation.equals("load") || operation.startsWith("load_"))
				return stack("Load " + type + " local", "—", type);
			if (operation.equals("store") || operation.startsWith("store_"))
				return stack("Store " + type + " local", type, "—");
			if (operation.equals("aload"))
				return stack("Read " + type + " array element", "array, int", stackType(type));
			if (operation.equals("astore"))
				return stack("Write " + type + " array element", "array, int, " + stackType(type), "—");
			if (operation.startsWith("const_"))
				return stack("Push constant", "—", type);
			if (operation.length() == 2 && operation.charAt(0) == '2') {
				String converted = jvmType(operation.charAt(1));
				if (converted != null)
					return stack("Convert to " + converted, type, stackType(converted));
			}
			if (operation.startsWith("cmp"))
				return stack("Compare values", type + ", " + type, "int");
			if (operation.equals("return"))
				return stack("Return " + type, type, "—");
		}
		return switch (name) {
			case "nop" -> stack("No operation", "—", "—");
			case "return" -> stack("Return without a value", "—", "—");
			case "bipush", "sipush" -> stack("Push integer literal", "—", "int");
			case "ldc", "ldc_w", "ldc2_w" -> stack("Push constant", "—", "constant");
			case "iinc" -> stack("Increment integer local", "—", "—");
			case "pop" -> stack("Discard top value", "single-slot value", "—");
			case "pop2" -> stack("Discard two slots", "two slots", "—");
			case "dup" -> stack("Duplicate top value", "value", "value, value");
			case "dup_x1" -> stack("Duplicate beneath one slot", "two slots", "three slots");
			case "dup_x2" -> stack("Duplicate beneath two slots", "three slots", "four slots");
			case "dup2" -> stack("Duplicate top two slots", "two slots", "four slots");
			case "dup2_x1" -> stack("Duplicate two slots beneath one", "three slots", "five slots");
			case "dup2_x2" -> stack("Duplicate two slots beneath two", "four slots", "six slots");
			case "swap" -> stack("Swap top two values", "value 1, value 2", "value 2, value 1");
			case "getstatic" -> stack("Read static field", "—", "field value");
			case "putstatic" -> stack("Write static field", "field value", "—");
			case "getfield" -> stack("Read instance field", "receiver", "field value");
			case "putfield" -> stack("Write instance field", "receiver, field value", "—");
			case "invokevirtual", "invokespecial", "invokeinterface" ->
					stack("Call instance method", "receiver, arguments", "result if non-void");
			case "invokestatic" -> stack("Call static method", "arguments", "result if non-void");
			case "invokedynamic" -> stack("Call dynamic call site", "arguments", "result if non-void");
			case "new" -> stack("Allocate object", "—", "uninitialized reference");
			case "newarray", "anewarray" -> stack("Allocate array", "length", "array");
			case "multianewarray" -> stack("Allocate multidimensional array", "dimension lengths", "array");
			case "arraylength" -> stack("Read array length", "array", "int");
			case "checkcast" -> stack("Check and cast reference", "reference", "reference");
			case "instanceof" -> stack("Test reference type", "reference", "int");
			case "monitorenter" -> stack("Acquire monitor", "reference", "—");
			case "monitorexit" -> stack("Release monitor", "reference", "—");
			case "athrow" -> stack("Throw exception", "reference", "—");
			case "jsr", "jsr_w" -> stack("Jump to subroutine", "—", "return address");
			case "ret" -> stack("Return from subroutine using a local", "—", "—");
			case "goto", "goto_w" -> stack("Jump unconditionally", "—", "—");
			case "lookupswitch", "tableswitch" -> stack("Jump by integer key", "int", "—");
			default -> {
				if (name.startsWith("if_icmp"))
					yield stack("Branch if left int is " + comparison(name.substring(7)) + " right int", "int, int", "—");
				if (name.startsWith("if_acmp"))
					yield stack(name.endsWith("eq") ? "Branch if references match" : "Branch if references differ",
							"reference, reference", "—");
				if (name.equals("ifnull") || name.equals("ifnonnull"))
					yield stack(name.equals("ifnull") ? "Branch if null" : "Branch if non-null", "reference", "—");
				if (instruction.hasTrait(CONDITIONAL_BRANCH))
					yield stack("Branch if int is " + comparison(name.substring(2)) + " zero", "int", "—");
				if (instruction.hasTrait(DEBUG_METADATA))
					yield stack("Set debugging metadata", "—", "—");
				yield new Effect("JVM instruction", "");
			}
		};
	}

	@Nullable
	private static String jvmType(char prefix) {
		return switch (prefix) {
			case 'a' -> "reference";
			case 'b' -> "byte";
			case 'c' -> "char";
			case 'd' -> "double";
			case 'f' -> "float";
			case 'i' -> "int";
			case 'l' -> "long";
			case 's' -> "short";
			default -> null;
		};
	}

	private static Effect stack(String description, String input, String output) {
		return new Effect(description, "Stack  " + input + " → " + output);
	}

	private static String stackType(String type) {
		return switch (type) {
			case "byte", "char", "short" -> "int";
			default -> type;
		};
	}

	private static String comparison(String suffix) {
		return switch (suffix) {
			case "eq" -> "equal to";
			case "ne" -> "unequal to";
			case "lt" -> "less than";
			case "le" -> "at most";
			case "gt" -> "greater than";
			case "ge" -> "at least";
			default -> "compared with";
		};
	}

	private static Effect dalvikEffect(String name, Instruction<?> instruction, boolean reads, boolean writes) {
		String base = name.split("/", 2)[0];
		String operation = base.split("-", 2)[0];
		String description = ARITHMETIC.get(operation);
		if (description == null) {
			description = switch (operation) {
				case "move" -> switch (base) {
					case "move-result" -> "Move pending result";
					case "move-result-wide" -> "Move pending wide result";
					case "move-result-object" -> "Move pending reference result";
					case "move-exception" -> "Move caught exception";
					case "move-wide" -> "Copy wide value";
					case "move-object" -> "Copy reference";
					default -> "Copy register value";
				};
				case "const" -> "Load constant";
				case "aget" -> "Read array element";
				case "aput" -> "Write array element";
				case "iget" -> "Read instance field";
				case "iput" -> "Write instance field";
				case "sget" -> "Read static field";
				case "sput" -> "Write static field";
				case "cmp", "cmpg", "cmpl" -> "Compare values";
				case "return" -> base.equals("return-void") ? "Return without a value" : "Return register value";
				case "throw" -> "Throw exception";
				case "invoke" -> "Call method";
				case "if" -> "Test register values and branch";
				case "goto" -> "Jump unconditionally";
				default -> switch (base) {
					case "nop" -> "No operation";
					case "packed-switch", "sparse-switch" -> instruction.hasTrait(PAYLOAD) ? "Define switch targets" : "Jump by integer key";
					case "new-instance" -> "Allocate object";
					case "new-array", "filled-new-array" -> "Allocate array";
					case "fill-array-data" -> "Copy data into array";
					case "array-length" -> "Read array length";
					case "check-cast" -> "Check and cast reference";
					case "instance-of" -> "Test reference type";
					case "monitor-enter" -> "Acquire monitor";
					case "monitor-exit" -> "Release monitor";
					default -> base.contains("-to-") ? "Convert to " + base.substring(base.indexOf("-to-") + 4)
							: instruction.hasTrait(DEBUG_METADATA) ? "Set debugging metadata"
							: instruction.hasTrait(PAYLOAD) ? "Define data payload" : "Dalvik instruction";
				};
			};
		}
		String state = writes ? (reads ? "Registers  Read sources, write destination" : "Registers  Write destination")
				: (reads ? "Registers  Read sources" : "Registers  No direct reads or writes");
		if (base.startsWith("move-result"))
			state += " from pending result";
		else if (instruction.hasTrait(INVOKE) || base.equals("filled-new-array"))
			state += " · sets pending result if present";
		return new Effect(description, state);
	}

	public record Hint(String syntax, String description, String effect, @Nullable String unavailableReason) {
		/** @return The short description and effect shown beneath the opcode syntax. */
		public String documentation() {
			String text = effect.isEmpty() ? description : description + "\n" + effect;
			return unavailableReason == null ? text : text + "\n" + unavailableReason;
		}
	}

	private record Effect(String description, String state) {}
}
