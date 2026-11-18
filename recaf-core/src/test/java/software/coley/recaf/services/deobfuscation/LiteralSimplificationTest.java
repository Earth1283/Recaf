package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.services.analysis.bytecode.ReadableWorkspaceService;
import software.coley.recaf.services.deobfuscation.transform.generic.BitwiseFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LiteralArrayStringTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LiteralStackSimplificationTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class LiteralSimplificationTest extends TransformerTestBase {
	@Test
	void foldsRadonStackShufflesBeforeBitwiseFolding() {
		assemble("""
				.super java/lang/Object
				.class public Example {
				    .method public static intValue ()I {
				        code: {
				        A:
				            bipush 17
				            bipush 42
				            swap
				            dup_x1
				            pop2
				            bipush 59
				            ixor
				            ireturn
				        B:
				        }
				    }
				    .method public static longValue ()J {
				        code: {
				        A:
				            ldc 700L
				            ldc 1000000000000L
				            dup2_x2
				            pop2
				            pop2
				            ldc 1000000000042L
				            lxor
				            lreturn
				        B:
				        }
				    }
				}
				""", true);
		assertEquals(42, invokeStatic(CLASS_NAME, "intValue", CLASS_NAME));
		assertEquals(42L, invokeStatic(CLASS_NAME, "longValue", CLASS_NAME));
		transformAndApply(List.of(LiteralStackSimplificationTransformer.class, BitwiseFoldingTransformer.class));
		assertEquals(42, invokeStatic(CLASS_NAME, "intValue", CLASS_NAME));
		assertEquals(42L, invokeStatic(CLASS_NAME, "longValue", CLASS_NAME));
		String output = disassemble(true);
		for (String opcode : List.of("swap", "dup", "pop", "ixor", "lxor"))
			assertFalse(output.contains(opcode), output);
	}

	@Test
	void preservesStackOperationsThatUseAnIncomingValue() {
		validateNoTransformation("""
				.method public static example (I)I {
				    parameters: { input },
				    code: {
				    A:
				        iload input
				        goto Shuffle
				    Shuffle:
				        iconst_2
				        swap
				        pop
				        ireturn
				    B:
				    }
				}
				""", List.of(LiteralStackSimplificationTransformer.class));
	}

	@Test
	void preservesAProtectedRangeWithOnlyDiscardedLiterals() {
		validateAfterAssembly("""
				.method public static example ()I {
				    exceptions: { { Start, End, Handler, java/lang/Exception } },
				    code: {
				    Start:
				        iconst_1
				        pop
				    End:
				        iconst_2
				        ireturn
				    Handler:
				        pop
				        iconst_m1
				        ireturn
				    }
				}
				""", List.of(LiteralStackSimplificationTransformer.class), output -> {
			assertFalse(output.contains("iconst_1"), output);
			assertEquals(2, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		});
	}

	@Test
	void reconstructsBozarArraysAndPreservesStringIdentity() {
		putClass(CLASS_NAME, node -> {
			MethodVisitor method = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "example", "()Ljava/lang/String;", null, null);
			writeString(method, T_BYTE, 2, 1, 0, 0, 72, 1, 105);
			method.visitInsn(POP);
			writeString(method, T_BYTE, 4, 3, 0x169, 2, -87, 0, 72, 1, -61);
			method.visitInsn(ARETURN);
			method.visitMaxs(3, 1);
			method.visitEnd();

			MethodVisitor chars = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "chars", "()Ljava/lang/String;", null, null);
			writeString(chars, T_CHAR, 3, 2, 0x1ffff, 0, 0x03bb);
			chars.visitInsn(ARETURN);
			chars.visitMaxs(3, 1);
			chars.visitEnd();
		});
		String expected = new String(new byte[]{72, -61, -87, 105});
		assertEquals(expected, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		assertEquals("λ\0\uffff", invokeStatic(CLASS_NAME, "chars", CLASS_NAME));
		List<Class<? extends JvmClassTransformer>> transformers = List.of(LiteralArrayStringTransformer.class);
		transformAndApply(transformers);
		Object first = invokeStatic(CLASS_NAME, "example", CLASS_NAME);
		assertEquals(expected, first);
		assertNotSame(first, invokeStatic(CLASS_NAME, "example", CLASS_NAME));
		assertEquals("λ\0\uffff", invokeStatic(CLASS_NAME, "chars", CLASS_NAME));
		String output = disassemble(true);
		assertTrue(output.contains("ISO_8859_1"), output);
		assertFalse(output.contains("newarray"), output);
		assertTrue(decompile(get(CLASS_NAME)).contains("\"Hi\""));
		var repeated = assertDoesNotThrow(() -> newApplier().transformJvm(transformers));
		assertTrue(repeated.getTransformerFailures().isEmpty());
		assertTrue(repeated.getTransformedClasses().isEmpty());
	}

	@Test
	void readableWorkspaceRemovesBozarArrayNoiseAfterNumericFolding() {
		var original = assemble("""
				.method public static example ()Ljava/lang/String; {
				    code: {
				    A:
				        bipush 22
				        bipush 23
				        ixor
				        newarray byte
				        astore array
				        aload array
				        ldc ""
				        invokevirtual java/lang/String.length ()I
				        bipush 35
				        bipush 98
				        ixor
				        bastore
				        new java/lang/String
				        dup
				        aload array
				        invokespecial java/lang/String.<init> ([B)V
				        areturn
				    B:
				    }
				}
				""", false);
		var workspace = workspaceManager.getCurrent();
		var result = assertDoesNotThrow(() -> recaf.get(ReadableWorkspaceService.class)
				.build(workspace, workspace.getPrimaryResource(), 20_000, false, () -> false, _ -> {}));
		var readable = result.workspace();
		try {
			var simplified = readable.getPrimaryResource().getJvmClassBundle().get(CLASS_NAME);
			assertNotNull(simplified);
			var node = new ClassNode();
			simplified.getClassReader().accept(node, 0);
			var method = node.methods.stream().filter(m -> m.name.equals("example")).findFirst().orElseThrow();
			assertFalse(Arrays.stream(method.instructions.toArray()).anyMatch(insn -> insn.getOpcode() == NEWARRAY));
			assertTrue(decompile(simplified).contains("\"A\""));
			assertSame(original, get(CLASS_NAME));
		} finally {
			readable.close();
		}
	}

	@Test
	void preservesArraysReadLaterAndOutOfBoundsStores() throws Exception {
		putClass(CLASS_NAME, node -> {
			MethodVisitor escape = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "escape", "()I", null, null);
			writeString(escape, T_BYTE, 1, 0, 65);
			escape.visitInsn(POP);
			escape.visitVarInsn(ALOAD, 0);
			escape.visitInsn(ICONST_0);
			escape.visitInsn(BALOAD);
			escape.visitInsn(IRETURN);
			escape.visitMaxs(3, 1);
			escape.visitEnd();

			MethodVisitor invalid = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "invalid", "()Ljava/lang/String;", null, null);
			writeString(invalid, T_BYTE, 1, 1, 65);
			invalid.visitInsn(ARETURN);
			invalid.visitMaxs(3, 1);
			invalid.visitEnd();
		});
		var result = newApplier().transformJvm(List.of(LiteralArrayStringTransformer.class));
		assertTrue(result.getTransformerFailures().isEmpty());
		assertTrue(result.getTransformedClasses().isEmpty());
		assertEquals(65, invokeStatic(CLASS_NAME, "escape", CLASS_NAME));
		assertThrows(ArrayIndexOutOfBoundsException.class, () -> invokeStatic(CLASS_NAME, "invalid", CLASS_NAME));
	}

	private static void writeString(MethodVisitor method, int arrayType, int length, int... stores) {
		boolean bytes = arrayType == T_BYTE;
		method.visitLdcInsn(length);
		method.visitIntInsn(NEWARRAY, arrayType);
		method.visitVarInsn(ASTORE, 0);
		for (int i = 0; i < stores.length; i += 2) {
			method.visitVarInsn(ALOAD, 0);
			method.visitLdcInsn(stores[i]);
			method.visitLdcInsn(stores[i + 1]);
			method.visitInsn(bytes ? BASTORE : CASTORE);
		}
		method.visitTypeInsn(NEW, "java/lang/String");
		method.visitInsn(DUP);
		method.visitVarInsn(ALOAD, 0);
		method.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", bytes ? "([B)V" : "([C)V", false);
	}
}
