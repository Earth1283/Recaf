package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableAnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** Collapses temporary byte/char array initialization into a literal backing a fresh String. */
@Dependent
public class LiteralArrayStringTransformer implements JvmClassTransformer {
	private static final String STRING = "java/lang/String";
	// A classfile UTF-8 entry has room for up to 65535 bytes, at most three per UTF-16 code unit.
	private static final int MAX_LITERAL_LENGTH = 65535 / 3;

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) {
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean supportsStandardCharsets = (node.version & 0xffff) >= V1_7;
		boolean changed = false;
		for (MethodNode method : node.methods)
			changed |= simplify(method, supportsStandardCharsets);
		if (changed) {
			context.setRecomputeFrames(node.name);
			context.setNode(bundle, initialClassState, node);
		}
	}

	private static boolean simplify(MethodNode method, boolean supportsStandardCharsets) {
		InsnList instructions = method.instructions;
		if (instructions == null)
			return false;
		List<StringArray> matches = new ArrayList<>();
		for (AbstractInsnNode insn : instructions) {
			if (insn instanceof IntInsnNode array && array.getOpcode() == NEWARRAY
					&& (array.operand == T_CHAR || (array.operand == T_BYTE && supportsStandardCharsets && method.maxStack < 65535))) {
				StringArray match = match(array);
				if (match != null)
					matches.add(match);
			}
		}
		if (matches.isEmpty())
			return false;

		// The scratch slot can be reused across matched strings. Reject it if any other instruction or
		// debugging information uses it, including loads reached through exception handlers.
		Map<Integer, Integer> uses = new HashMap<>();
		for (AbstractInsnNode insn : instructions) {
			if (insn instanceof VarInsnNode var)
				uses.merge(var.var, 1, Integer::sum);
			else if (insn instanceof IincInsnNode inc)
				uses.merge(inc.var, 1, Integer::sum);
		}
		if (method.localVariables != null)
			method.localVariables.forEach(var -> uses.remove(var.index));
		excludeAnnotatedLocals(uses, method.visibleLocalVariableAnnotations);
		excludeAnnotatedLocals(uses, method.invisibleLocalVariableAnnotations);
		for (StringArray match : matches)
			uses.computeIfPresent(match.slot(), (slot, count) -> count - match.uses());

		boolean changed = false;
		boolean needsExtraStack = false;
		for (StringArray match : matches) {
			if (uses.getOrDefault(match.slot(), -1) != 0)
				continue;
			for (AbstractInsnNode insn = match.start(); insn != match.allocation(); ) {
				AbstractInsnNode next = insn.getNext();
				instructions.remove(insn);
				insn = next;
			}
			instructions.set(match.constructor().getPrevious(), new LdcInsnNode(match.literal()));
			if (match.bytes()) {
				// ISO-8859-1 maps all byte values exactly. Keep String(byte[]) so the target runtime
				// still picks the default charset, even when Recaf runs with a different one.
				InsnList encode = new InsnList();
				encode.add(new FieldInsnNode(GETSTATIC, "java/nio/charset/StandardCharsets", "ISO_8859_1", "Ljava/nio/charset/Charset;"));
				encode.add(new MethodInsnNode(INVOKEVIRTUAL, STRING, "getBytes", "(Ljava/nio/charset/Charset;)[B", false));
				instructions.insertBefore(match.constructor(), encode);
				needsExtraStack = true;
			} else {
				match.constructor().desc = "(Ljava/lang/String;)V";
			}
			changed = true;
		}
		if (needsExtraStack)
			method.maxStack++;
		return changed;
	}

	private static void excludeAnnotatedLocals(Map<Integer, Integer> uses, List<LocalVariableAnnotationNode> annotations) {
		if (annotations != null)
			annotations.forEach(annotation -> annotation.index.forEach(uses::remove));
	}

	@Nullable
	private static StringArray match(IntInsnNode array) {
		if (array.visibleTypeAnnotations != null || array.invisibleTypeAnnotations != null)
			return null;
		boolean bytes = array.operand == T_BYTE;
		AbstractInsnNode start = array.getPrevious();
		Integer size = readInt(start);
		if (size == null || size < 0 || size > MAX_LITERAL_LENGTH
				|| !(array.getNext() instanceof VarInsnNode store) || store.getOpcode() != ASTORE)
			return null;

		int uses = 2; // ASTORE and the final ALOAD feeding the constructor.
		int elementStore = bytes ? BASTORE : CASTORE;
		AbstractInsnNode cursor = store.getNext();
		// Match one uninterrupted run. Labels, branches and other uses invalidate the match.
		while (isLoad(cursor, store.var)) {
			AbstractInsnNode indexInsn = cursor.getNext();
			Integer index = readInt(indexInsn);
			if (index == null || index < 0 || index >= size)
				return null;
			AbstractInsnNode valueInsn = indexInsn.getNext();
			Integer value = readInt(valueInsn);
			if (value == null || valueInsn.getNext() == null || valueInsn.getNext().getOpcode() != elementStore)
				return null;
			cursor = valueInsn.getNext().getNext();
			uses++;
		}
		if (!(cursor instanceof TypeInsnNode allocation) || allocation.getOpcode() != NEW || !STRING.equals(allocation.desc))
			return null;
		AbstractInsnNode dup = allocation.getNext();
		if (dup == null || dup.getOpcode() != DUP || !isLoad(dup.getNext(), store.var))
			return null;
		if (!(dup.getNext().getNext() instanceof MethodInsnNode constructor) || constructor.getOpcode() != INVOKESPECIAL
				|| constructor.itf || !STRING.equals(constructor.owner) || !"<init>".equals(constructor.name)
				|| !(bytes ? "([B)V" : "([C)V").equals(constructor.desc))
			return null;
		char[] literal = new char[size];
		for (cursor = store.getNext(); cursor != allocation; ) {
			AbstractInsnNode indexInsn = cursor.getNext();
			AbstractInsnNode valueInsn = indexInsn.getNext();
			int index = readInt(indexInsn);
			int value = readInt(valueInsn);
			// The JVM narrows the value on store. Later writes to the same index take precedence.
			literal[index] = (char) (bytes ? value & 0xff : value);
			cursor = valueInsn.getNext().getNext();
		}
		return new StringArray(start, allocation, constructor, store.var, uses, new String(literal), bytes);
	}

	private static boolean isLoad(AbstractInsnNode insn, int slot) {
		return insn instanceof VarInsnNode load && load.getOpcode() == ALOAD && load.var == slot;
	}

	@Nullable
	private static Integer readInt(AbstractInsnNode insn) {
		if (insn == null)
			return null;
		return switch (insn.getOpcode()) {
			case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> insn.getOpcode() - ICONST_0;
			case BIPUSH, SIPUSH -> ((IntInsnNode) insn).operand;
			case LDC -> ((LdcInsnNode) insn).cst instanceof Integer value ? value : null;
			default -> null;
		};
	}

	private record StringArray(AbstractInsnNode start, TypeInsnNode allocation, MethodInsnNode constructor,
	                           int slot, int uses, String literal, boolean bytes) {}

	@Nonnull
	@Override
	public String identifier() {
		return "peephole.data.literalarraystring";
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedPredecessors() {
		return Set.of(OpaqueConstantFoldingTransformer.class, DeadCodeRemovingTransformer.class);
	}

	@Nonnull
	@Override
	public String beforeExample() {
		return "byte[] data = new byte[2];\ndata[1] = 105;\ndata[0] = 72;\nreturn new String(data);";
	}

	@Nonnull
	@Override
	public String afterExample() {
		return "return new String(\"Hi\".getBytes(StandardCharsets.ISO_8859_1));";
	}
}
