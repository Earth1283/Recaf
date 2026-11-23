package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.analysis.bytecode.ObfuscationTechnologyDetector;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting;
import software.coley.recaf.services.deobfuscation.transform.generic.AlgebraicOpaquePredicateTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadPrivateMethodRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.TryCatchRepairTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ObfuscatedPluginTransformersTest extends TransformerTestBase {
	@Test
	void algebraicPredicatesFoldAndPreserveSideEffects() {
		String asm = """
				.super java/lang/Object
				.class public Example {
				    .field private static sideEffectCount I
				    .method private static tick ()I {
				        code: {
				            getstatic Example.sideEffectCount I
				            iconst_1
				            iadd
				            dup
				            putstatic Example.sideEffectCount I
				            ireturn
				        }
				    }
				    .method public static test ()I {
				        code: {
				        A:
				            // (tick() * (tick() + 1)) & 1  --> 0 while keeping tick() side effect
				            invokestatic Example.tick ()I
				            dup
				            iconst_1
				            iadd
				            imul
				            iconst_1
				            iand
				            // dead constant arithmetic-and-pop junk
				            ldc 123456
				            ldc 654321
				            ixor
				            ldc 73244475
				            iadd
				            pop
				            // x ^ x == 0 (via tick())
				            invokestatic Example.tick ()I
				            dup
				            ixor
				            iadd
				            // x & ~x == 0
				            invokestatic Example.tick ()I
				            dup
				            iconst_m1
				            ixor
				            iand
				            iadd
				            // return sideEffectCount + sum (expected 3 + 0 = 3)
				            getstatic Example.sideEffectCount I
				            iadd
				            ireturn
				        }
				    }
				}
				""";
		assemble(asm, true);
		JvmTransformResult result = assertDoesNotThrow(() -> newApplier(3).transformJvm(List.of(
				AlgebraicOpaquePredicateTransformer.class,
				OpaqueConstantFoldingTransformer.class
		)));
		assertTrue(result.getTransformerFailures().isEmpty());
		String dis = disassembleTransformed(result, true);
		assertFalse(dis.contains("123456"), () -> "Dead constant arithmetic should be pruned:\n" + dis);
		assertFalse(dis.contains("ixor"), () -> "x^x and dead xor should be removed:\n" + dis);
		assertEquals(3, invokeStatic(CLASS_NAME, "test", CLASS_NAME));
	}

	@Test
	void algebraicTransformerDoesNotFoldOverflowSensitiveSquareOrDivision() {
		String asm = """
				.method public static keepUnsafe (I)I {
				    parameters: { x },
				    code: {
				    A:
				        // x * x can overflow to negative, must NOT be touched
				        iload x
				        iload x
				        imul
				        pop
				        // 1 / 0 can throw ArithmeticException, must NOT be popped as dead pure constant
				        iconst_1
				        iconst_0
				        idiv
				        pop
				        iload x
				        ireturn
				    }
				}
				""";
		validateNoTransformation(asm, List.of(AlgebraicOpaquePredicateTransformer.class));
	}

	@Test
	void tryCatchRepairTransformerStripsIdentityRethrowAndPrunesDeadHandlers() {
		String asm = """
				.super java/lang/Object
				.class public Example {
				    .method private static id (Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException; {
				        parameters: { ex },
				        code: {
				        A:
				            aload ex
				            areturn
				        }
				    }
				    .method public static work (I)I {
				        parameters: { x },
				        exceptions: { { S, E, H, Ljava/lang/RuntimeException; } },
				        code: {
				        S:
				            iload x
				            iconst_1
				            iadd
				        E:
				            ireturn
				        H:
				            invokestatic Example.id (Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;
				            athrow
				        }
				    }
				}
				""";
		validateAfterAssembly(asm, List.of(TryCatchRepairTransformer.class), dis -> {
			assertFalse(dis.contains("exceptions:"));
			assertFalse(dis.contains("athrow"));
		});
	}

	@Test
	void deadPrivateMethodRemovalPrunesDecoysWhileRespectingDirectAndIndyRefs() {
		String asm = """
				.super java/lang/Object
				.class public Example {
				    .method private static deadDecoy ()Ljava/lang/String; {
				        code: {
				            ldc "never used"
				            areturn
				        }
				    }
				    .method private static transitiveDead ()V {
				        code: {
				            invokestatic Example.deadDecoy ()Ljava/lang/String;
				            pop
				            return
				        }
				    }
				    .method private static liveHelper ()I {
				        code: {
				            bipush 42
				            ireturn
				        }
				    }
				    .method public static entry ()I {
				        code: {
				            invokestatic Example.liveHelper ()I
				            ireturn
				        }
				    }
				}
				""";
		validateAfterRepeatedAssembly(asm, List.of(DeadPrivateMethodRemovingTransformer.class), dis -> {
			assertFalse(dis.contains("deadDecoy"));
			assertFalse(dis.contains("transitiveDead"));
			assertTrue(dis.contains("liveHelper"));
		});
	}

	@Test
	void fingerprintingAndTechDetectorRecognizeZelixFlowAndAlgebraicPredicates() {
		JvmClassInfo cls = TestClassUtils.createClass("demo/MixedObf", node -> {
			MethodNode id = new MethodNode(ACC_PRIVATE | ACC_STATIC, "id", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", null, null);
			id.visitVarInsn(ALOAD, 0);
			id.visitInsn(ARETURN);
			id.visitMaxs(1, 1);
			node.methods.add(id);

			MethodNode m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "(I)I", null, null);
			Label s = new Label(), e = new Label(), h = new Label();
			m.visitTryCatchBlock(s, e, h, "java/lang/RuntimeException");
			m.visitLabel(s);
			m.visitVarInsn(ILOAD, 0);
			m.visitInsn(DUP);
			m.visitInsn(ICONST_1);
			m.visitInsn(IADD);
			m.visitInsn(IMUL);
			m.visitInsn(ICONST_1);
			m.visitInsn(IAND);
			m.visitLabel(e);
			m.visitInsn(IRETURN);
			m.visitLabel(h);
			m.visitMethodInsn(INVOKESTATIC, "demo/MixedObf", "id", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", false);
			m.visitInsn(ATHROW);
			m.visitMaxs(3, 1);
			node.methods.add(m);
		});
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(cls)).build();
		try {
			var fp = new ObfuscatorFingerprinting().scan(root, () -> false);
			assertEquals(80, fp.confidenceScores().get(ObfuscatorFingerprinting.Family.ZELIX));

			var tech = new ObfuscationTechnologyDetector().scan(root, () -> false);
			assertTrue(tech.matches().stream().anyMatch(m -> m.technology() == ObfuscationTechnologyDetector.Technology.FAKE_TRY_CATCH));
			assertTrue(tech.matches().stream().anyMatch(m -> m.technology() == ObfuscationTechnologyDetector.Technology.ALGEBRAIC_OPAQUE_PREDICATES));
		} finally {
			root.close();
		}
	}
}
