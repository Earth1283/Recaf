package software.coley.recaf.services.analysis.plugin.view;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import software.coley.recaf.RecafConstants;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * Adds what the plugin view shows to a class: parameter names for methods without a variable table, and note
 * annotations that {@link NoteMarker#rewrite(String)} turns into comments.
 * <p>
 * A parameter is only named when its slot is never written to. Optimized code reuses slots for other values, and one
 * name for the whole method would then be wrong.
 * <p>
 * Only methods that get something added are wrapped. ASM copies the others as they are, which is much faster.
 */
final class PluginViewVisitor extends ClassVisitor {
	private final PluginNotes notes;
	private final Map<String, String[]> parameterNames;

	/**
	 * @param notes
	 * 		Notes to add.
	 * @param parameterNames
	 * 		Parameter names to add, per method keyed by {@link PluginNotes#key(String, String)}.
	 * @param cv
	 * 		Visitor to write to.
	 */
	PluginViewVisitor(@Nonnull PluginNotes notes, @Nonnull Map<String, String[]> parameterNames, @Nonnull ClassVisitor cv) {
		super(RecafConstants.getAsmVersion(), cv);
		this.notes = notes;
		this.parameterNames = parameterNames;
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		super.visit(version, access, name, signature, superName, interfaces);
		addNote(notes.classLines(), (descriptor, visible) -> super.visitAnnotation(descriptor, visible));
	}

	@Override
	public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
		FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
		List<String> lines = notes.fieldLines().get(PluginNotes.key(name, descriptor));
		if (fv != null && lines != null)
			addNote(lines, fv::visitAnnotation);
		return fv;
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
		MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
		String key = PluginNotes.key(name, descriptor);
		List<String> lines = notes.methodLines().get(key);
		String[] names = parameterNames.get(key);
		if (mv == null || (lines == null && names == null))
			return mv;
		return new ViewMethodVisitor(mv, access, descriptor, lines, names);
	}

	private static void addNote(@Nonnull List<String> lines, @Nonnull AnnotationSink sink) {
		if (lines.isEmpty())
			return;
		AnnotationVisitor av = sink.visit(NoteMarker.DESCRIPTOR, true);
		if (av != null) {
			av.visit("value", NoteMarker.encode(lines));
			av.visitEnd();
		}
	}

	private interface AnnotationSink {
		AnnotationVisitor visit(String descriptor, boolean visible);
	}

	private static final class ViewMethodVisitor extends MethodVisitor {
		private final int access;
		private final String descriptor;
		private final List<String> lines;
		private final String[] names;
		private Label start;
		private boolean noted;
		private final BitSet written = new BitSet();
		private boolean sawVariables;

		private ViewMethodVisitor(@Nonnull MethodVisitor mv, int access, @Nonnull String descriptor,
		                          @Nullable List<String> lines, @Nullable String[] names) {
			super(RecafConstants.getAsmVersion(), mv);
			this.access = access;
			this.descriptor = descriptor;
			this.lines = lines;
			this.names = names;
		}

		private void note() {
			if (noted)
				return;
			noted = true;
			if (lines != null)
				addNote(lines, super::visitAnnotation);
		}

		@Override
		public void visitParameter(String name, int access) {
			note();
			super.visitParameter(name, access);
		}

		@Override
		public AnnotationVisitor visitAnnotationDefault() {
			note();
			return super.visitAnnotationDefault();
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			note();
			return super.visitAnnotation(descriptor, visible);
		}

		@Override
		public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
			note();
			return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
		}

		@Override
		public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
			note();
			super.visitAnnotableParameterCount(parameterCount, visible);
		}

		@Override
		public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
			note();
			return super.visitParameterAnnotation(parameter, descriptor, visible);
		}

		@Override
		public void visitAttribute(Attribute attribute) {
			note();
			super.visitAttribute(attribute);
		}

		@Override
		public void visitCode() {
			note();
			super.visitCode();
			if (names != null) {
				start = new Label();
				super.visitLabel(start);
			}
		}

		@Override
		public void visitVarInsn(int opcode, int varIndex) {
			if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE)
				written.set(varIndex);
			super.visitVarInsn(opcode, varIndex);
		}

		@Override
		public void visitIincInsn(int varIndex, int increment) {
			written.set(varIndex);
			super.visitIincInsn(varIndex, increment);
		}

		@Override
		public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
			sawVariables = true;
			super.visitLocalVariable(name, descriptor, signature, start, end, index);
		}

		@Override
		public void visitMaxs(int maxStack, int maxLocals) {
			if (names != null && start != null && !sawVariables) {
				Label end = new Label();
				super.visitLabel(end);
				int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
				Type[] arguments = Type.getArgumentTypes(descriptor);
				for (int i = 0; i < arguments.length && i < names.length; i++) {
					if (names[i] != null && !written.get(slot))
						super.visitLocalVariable(names[i], arguments[i].getDescriptor(), null, start, end, slot);
					slot += arguments[i].getSize();
				}
			}
			super.visitMaxs(maxStack, maxLocals);
		}

		@Override
		public void visitEnd() {
			note();
			super.visitEnd();
		}
	}
}
