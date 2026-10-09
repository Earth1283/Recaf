package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Answers "where did this value come from" for values used in a method.
 * <p>
 * Uses ASM's {@link SourceInterpreter}, which needs no class hierarchy and so works on any valid method, then follows
 * copies of values <i>(through {@code DUP}, local variables, casts and {@code Objects.requireNonNull})</i> back to the
 * instruction that created the value.
 */
final class MethodFlow implements Opcodes {
	private static final int MAX_DEPTH = 24;
	private final String owner;
	private final MethodNode method;
	private final Frame<SourceValue>[] frames;

	private MethodFlow(@Nonnull String owner, @Nonnull MethodNode method, @Nonnull Frame<SourceValue>[] frames) {
		this.owner = owner;
		this.method = method;
		this.frames = frames;
	}

	/**
	 * @param owner
	 * 		Internal name of the class declaring the method.
	 * @param method
	 * 		Method to analyze.
	 *
	 * @return Flow of the method, or {@code null} if the method could not be analyzed.
	 */
	@Nullable
	static MethodFlow analyze(@Nonnull String owner, @Nonnull MethodNode method) {
		if (method.instructions.size() == 0)
			return null;
		try {
			Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
			return new MethodFlow(owner, method, frames);
		} catch (AnalyzerException | RuntimeException ex) {
			// Invalid or unusual code. Callers fall back to not knowing where values come from.
			return null;
		}
	}

	/**
	 * @return The analyzed method.
	 */
	@Nonnull
	MethodNode method() {
		return method;
	}

	/**
	 * @param call
	 * 		Method call instruction.
	 * @param index
	 * 		Index of the argument, not counting the receiver.
	 *
	 * @return Instruction that created the argument value, or {@code null} if not known.
	 */
	@Nullable
	AbstractInsnNode argument(@Nonnull MethodInsnNode call, int index) {
		int count = Type.getArgumentCount(call.desc);
		if (index < 0 || index >= count)
			return null;
		return stackOrigin(call, count - 1 - index);
	}

	/**
	 * @param call
	 * 		Method call instruction, which must not be static.
	 *
	 * @return Instruction that created the receiver value, or {@code null} if not known.
	 */
	@Nullable
	AbstractInsnNode receiver(@Nonnull MethodInsnNode call) {
		if (call.getOpcode() == INVOKESTATIC)
			return null;
		return stackOrigin(call, Type.getArgumentCount(call.desc));
	}

	/**
	 * @param insn
	 * 		Some instruction.
	 * @param depth
	 * 		Depth on the stack before the instruction runs, {@code 0} being the top.
	 *
	 * @return Instruction that created the value, or {@code null} if not known.
	 */
	@Nullable
	AbstractInsnNode stackOrigin(@Nonnull AbstractInsnNode insn, int depth) {
		Frame<SourceValue> frame = frameAt(insn);
		if (frame == null)
			return null;
		int index = frame.getStackSize() - 1 - depth;
		if (index < 0)
			return null;
		return resolve(frame.getStack(index), 0);
	}

	/**
	 * @param origin
	 * 		Origin of some value.
	 *
	 * @return {@code true} if the value is {@code this}.
	 */
	boolean isThis(@Nullable AbstractInsnNode origin) {
		return origin instanceof VarInsnNode var && var.getOpcode() == ALOAD && var.var == 0 &&
				(method.access & ACC_STATIC) == 0;
	}

	/**
	 * @param origin
	 * 		Origin of some value.
	 *
	 * @return Internal name of the type the value is created or declared as, or {@code null} if not known.
	 * Lambdas give {@code null}, see {@link #lambdaImplementation(AbstractInsnNode)}.
	 */
	@Nullable
	String typeOf(@Nullable AbstractInsnNode origin) {
		if (origin == null)
			return null;
		if (isThis(origin))
			return owner;
		Type type = switch (origin) {
			case TypeInsnNode typeInsn when typeInsn.getOpcode() == NEW -> Type.getObjectType(typeInsn.desc);
			case FieldInsnNode field when field.getOpcode() == GETFIELD || field.getOpcode() == GETSTATIC ->
					Type.getType(field.desc);
			case MethodInsnNode call -> Type.getReturnType(call.desc);
			case VarInsnNode var when var.getOpcode() == ALOAD -> parameterType(var.var);
			default -> null;
		};
		return type != null && type.getSort() == Type.OBJECT ? type.getInternalName() : null;
	}

	/**
	 * @param origin
	 * 		Origin of some value.
	 *
	 * @return Location of the method a lambda value is implemented by, or {@code null} if the value is not a lambda.
	 */
	@Nullable
	static CodeLocation lambdaImplementation(@Nullable AbstractInsnNode origin) {
		if (origin instanceof InvokeDynamicInsnNode indy &&
				"java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner()) &&
				indy.bsmArgs.length >= 2 && indy.bsmArgs[1] instanceof Handle impl)
			return CodeLocation.ofMember(impl.getOwner(), impl.getName(), impl.getDesc());
		return null;
	}

	@Nullable
	private Type parameterType(int var) {
		// Map the variable slot back to a parameter, if it is one.
		Type[] args = Type.getArgumentTypes(method.desc);
		int slot = (method.access & ACC_STATIC) == 0 ? 1 : 0;
		for (Type arg : args) {
			if (slot == var)
				return arg;
			slot += arg.getSize();
		}
		return null;
	}

	@Nullable
	private Frame<SourceValue> frameAt(@Nonnull AbstractInsnNode insn) {
		int index = method.instructions.indexOf(insn);
		return index >= 0 && index < frames.length ? frames[index] : null;
	}

	@Nullable
	private AbstractInsnNode resolve(@Nonnull SourceValue value, int depth) {
		if (depth > MAX_DEPTH || value.insns.isEmpty())
			return null;

		// All sources have to agree, otherwise the value depends on the path taken and we do not know it.
		AbstractInsnNode found = null;
		for (AbstractInsnNode insn : value.insns) {
			AbstractInsnNode resolved = resolveInsn(insn, depth + 1);
			if (resolved == null || (found != null && found != resolved))
				return null;
			found = resolved;
		}
		return found;
	}

	@Nullable
	private AbstractInsnNode resolveInsn(@Nonnull AbstractInsnNode insn, int depth) {
		int op = insn.getOpcode();
		switch (op) {
			case DUP, DUP_X1, DUP_X2, DUP2, DUP2_X1, DUP2_X2, CHECKCAST,
			     ISTORE, LSTORE, FSTORE, DSTORE, ASTORE -> {
				// Copies of the value on the top of the stack.
				return stackTop(insn, depth);
			}
			case ILOAD, LLOAD, FLOAD, DLOAD, ALOAD -> {
				Frame<SourceValue> frame = frameAt(insn);
				if (frame == null)
					return null;
				SourceValue local = frame.getLocal(((VarInsnNode) insn).var);

				// No stores means it is a parameter or 'this', and the load itself is as far back as we can go.
				if (local.insns.isEmpty())
					return insn;
				return resolve(local, depth);
			}
			case INVOKESTATIC -> {
				MethodInsnNode call = (MethodInsnNode) insn;
				if ("java/util/Objects".equals(call.owner) && call.name.startsWith("requireNonNull")) {
					Frame<SourceValue> frame = frameAt(insn);
					if (frame == null)
						return null;
					int argCount = Type.getArgumentCount(call.desc);
					int index = frame.getStackSize() - argCount;
					return index >= 0 ? resolve(frame.getStack(index), depth) : null;
				}
				return insn;
			}
			default -> {
				return insn;
			}
		}
	}

	@Nullable
	private AbstractInsnNode stackTop(@Nonnull AbstractInsnNode insn, int depth) {
		Frame<SourceValue> frame = frameAt(insn);
		if (frame == null || frame.getStackSize() == 0)
			return null;
		return resolve(frame.getStack(frame.getStackSize() - 1), depth);
	}
}
