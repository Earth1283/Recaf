package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import software.coley.recaf.RecafConstants;
import software.coley.recaf.info.JvmClassInfo;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Declaration-only copies of classes that a decompiler reads while decompiling another class.
 * <p>
 * Decompilers build new state for every decompilation and fully parse each class the target refers to, mostly only to
 * read declarations such as method signatures and the type hierarchy. Large referenced classes are parsed again for
 * every class that uses them. A stub keeps every declaration but replaces method bodies with
 * {@code aconst_null; athrow}, so its constant pool only holds what the declarations need. Concrete methods keep a body
 * because CFR tells the abstract method of a functional interface apart from default methods by whether it has code.
 *
 * @see TargetCodeScope Deciding which classes a decompiler needs in full.
 */
public final class ReferencedClassStubs {
	private static final Map<JvmClassInfo, byte[]> STUBS = Collections.synchronizedMap(new WeakHashMap<>());

	private ReferencedClassStubs() {}

	/**
	 * @param info
	 * 		Class to get a stub of.
	 *
	 * @return Declaration-only bytecode of the class, or its original bytecode when it cannot be rewritten.
	 */
	@Nonnull
	public static byte[] of(@Nonnull JvmClassInfo info) {
		byte[] stub = STUBS.get(info);
		if (stub == null) {
			stub = create(info.getBytecode());
			STUBS.put(info, stub);
		}
		return stub;
	}

	@Nonnull
	private static byte[] create(@Nonnull byte[] bytecode) {
		try {
			ClassReader reader = new ClassReader(bytecode);
			ClassWriter writer = new ClassWriter(0);
			reader.accept(new StubbingVisitor(writer), ClassReader.SKIP_CODE);
			return writer.toByteArray();
		} catch (Throwable t) {
			// Obfuscated classes can be malformed in ways ASM rejects but CFR tolerates.
			return bytecode;
		}
	}

	private static final class StubbingVisitor extends ClassVisitor {
		private StubbingVisitor(@Nonnull ClassVisitor cv) {
			super(RecafConstants.getAsmVersion(), cv);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
			if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
				return mv;
			int locals = Type.getArgumentsAndReturnSizes(descriptor) >> 2;
			if ((access & Opcodes.ACC_STATIC) != 0)
				locals--;
			int maxLocals = locals;
			return new MethodVisitor(RecafConstants.getAsmVersion(), mv) {
				@Override
				public void visitEnd() {
					super.visitCode();
					super.visitInsn(Opcodes.ACONST_NULL);
					super.visitInsn(Opcodes.ATHROW);
					super.visitMaxs(1, maxLocals);
					super.visitEnd();
				}
			};
		}
	}
}
