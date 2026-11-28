package software.coley.recaf.services.deobfuscation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.TraceClassVisitor;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.analysis.bytecode.ObfuscationTechnologyDetector;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting;
import software.coley.recaf.services.deobfuscation.transform.generic.AlgebraicOpaquePredicateTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadPrivateMethodRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.MethodUsageCollectionTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.TryCatchRepairTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationParameters;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.util.ClassDefiner;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ObfuscatedPluginTransformersTest extends TransformerTestBase {
	@Test
	void symbolicFoldingProvesSignedBranchesAndSimplifiesReassociatedExpressions() throws Exception {
		String asm = """
				.super java/lang/Object
				.class public Example {
				    .method public static sign (I)I {
				        parameters: { x },
				        code: {
				            iload x
				            ldc -2147483648
				            ior
				            ifge impossible
				            iconst_1
				            ireturn
				        impossible:
				            iconst_0
				            ireturn
				        }
				    }
				    .method public static arithmetic (J)J {
				        parameters: { x },
				        code: {
				            lload x
				            lconst_1
				            ladd
				            ldc -1L
				            ladd
				            lreturn
				        }
				    }
				    .method public static shift (JI)I {
				        parameters: { x, n },
				        code: {
				            lload x
				            iload n
				            bipush 64
				            iadd
				            lshl
				            lload x
				            iload n
				            lshl
				            lcmp
				            ifeq same
				            iconst_0
				            ireturn
				        same:
				            iconst_1
				            ireturn
				        }
				    }
				    .method public static guarded (I)I {
				        parameters: { x },
				        exceptions: { { start, end, handler, Ljava/lang/Throwable; } },
				        code: {
				        start:
				            iload x
				            ldc -2147483648
				            ior
				            ifge impossible
				        end:
				            iconst_1
				            ireturn
				        impossible:
				            iconst_0
				            ireturn
				        handler:
				            pop
				            iconst_m1
				            ireturn
				        }
				    }
				}
				""";
		assemble(asm, true);
		JvmTransformResult result = assertDoesNotThrow(() -> newApplier().transformJvm(List.of(AlgebraicOpaquePredicateTransformer.class)));
		assertTrue(result.getTransformerFailures().isEmpty());
		String disassembly = disassembleTransformed(result, true);
		assertFalse(disassembly.contains("ifge"), disassembly);
		assertFalse(disassembly.contains("ladd"), disassembly);
		assertFalse(disassembly.contains("ifeq"), disassembly);
		var transformed = new ClassDefiner(Map.of(CLASS_NAME, get(CLASS_NAME).getBytecode())).loadClass(CLASS_NAME);
		assertEquals(1, transformed.getDeclaredMethod("sign", int.class).invoke(null, 0));
		assertEquals(Long.MAX_VALUE, transformed.getDeclaredMethod("arithmetic", long.class).invoke(null, Long.MAX_VALUE));
		assertEquals(1, transformed.getDeclaredMethod("shift", long.class, int.class).invoke(null, 3L, Integer.MAX_VALUE));
		assertEquals(1, transformed.getDeclaredMethod("guarded", int.class).invoke(null, -2));
	}

	@Test
	void symbolicFoldingKeepsPossibleBranchesAndNeverRemovesThrowingOperands() {
		String asm = """
				.method public static keep (I)I {
				    parameters: { x },
				    code: {
				        iload x
				        iload x
				        imul
				        iflt negative
				        iconst_1
				        iload x
				        idiv
				        iconst_0
				        imul
				        ifne negative
				        iconst_1
				        ireturn
				    negative:
				        iconst_0
				        ireturn
				    }
				}
				""";
		validateNoTransformation(asm, List.of(AlgebraicOpaquePredicateTransformer.class));
	}

	@Test
	void symbolicFoldingKeepsValuesFromOtherPredecessorsAtAJoin() {
		String asm = """
				.method public static join (I)I {
				    parameters: { x },
				    code: {
				        iload x
				        ifeq alternate
				        iconst_0
				        goto joined
				    alternate:
				        iconst_1
				    joined:
				        iconst_0
				        ixor
				        ifne nonzero
				        iconst_0
				        ireturn
				    nonzero:
				        iconst_1
				        ireturn
				    }
				}
				""";
		validateNoTransformation(asm, List.of(AlgebraicOpaquePredicateTransformer.class));
	}

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

	@Test
	void crossClassConstantFunctionInliningAndUnusedMethodRemoval() throws Exception {
		JvmClassInfo classC = TestClassUtils.createClass("demo/ClassC", node -> {
			MethodNode inner = new MethodNode(ACC_PUBLIC | ACC_STATIC, "innerConst", "()Ljava/lang/String;", null, null);
			inner.visitLdcInsn(Type.getObjectType("java/lang/Object"));
			inner.visitMethodInsn(INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false);
			inner.visitInsn(POP);
			inner.visitLdcInsn("CROSS-CONST");
			inner.visitInsn(ARETURN);
			inner.visitMaxs(1, 0);
			node.methods.add(inner);

			MethodNode apiKeep = new MethodNode(ACC_PUBLIC | ACC_STATIC, "publicEntry", "()I", null, null);
			apiKeep.visitIntInsn(BIPUSH, 7);
			apiKeep.visitInsn(IRETURN);
			apiKeep.visitMaxs(1, 0);
			node.methods.add(apiKeep);
		});

		JvmClassInfo classB = TestClassUtils.createClass("demo/ClassB", node -> {
			MethodNode mid = new MethodNode(ACC_PUBLIC | ACC_STATIC, "midHelper", "(I)Ljava/lang/String;", null, null);
			mid.visitMethodInsn(INVOKESTATIC, "demo/ClassC", "innerConst", "()Ljava/lang/String;", false);
			mid.visitInsn(ARETURN);
			mid.visitMaxs(1, 1);
			node.methods.add(mid);
		});

		JvmClassInfo classA = TestClassUtils.createClass("demo/ClassA", node -> {
			MethodNode caller = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "(I)Ljava/lang/String;", null, null);
			caller.visitVarInsn(ILOAD, 0);
			caller.visitMethodInsn(INVOKESTATIC, "demo/ClassB", "midHelper", "(I)Ljava/lang/String;", false);
			caller.visitInsn(ARETURN);
			caller.visitMaxs(1, 1);
			node.methods.add(caller);

			MethodNode sideEffect = new MethodNode(ACC_PRIVATE | ACC_STATIC, "tick", "()I", null, null);
			sideEffect.visitFieldInsn(GETSTATIC, "demo/ClassA", "count", "I");
			sideEffect.visitInsn(ICONST_1);
			sideEffect.visitInsn(IADD);
			sideEffect.visitInsn(DUP);
			sideEffect.visitFieldInsn(PUTSTATIC, "demo/ClassA", "count", "I");
			sideEffect.visitInsn(IRETURN);
			sideEffect.visitMaxs(2, 0);
			node.methods.add(sideEffect);
			node.visitField(ACC_PRIVATE | ACC_STATIC, "count", "I", null, 0);

			MethodNode sideEffectCaller = new MethodNode(ACC_PUBLIC | ACC_STATIC, "keepSideEffect", "()I", null, null);
			sideEffectCaller.visitMethodInsn(INVOKESTATIC, "demo/ClassA", "tick", "()I", false);
			sideEffectCaller.visitInsn(IRETURN);
			sideEffectCaller.visitMaxs(1, 0);
			node.methods.add(sideEffectCaller);
		});

		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(classA, classB, classC));
		try {
			TransformationApplier applier = recaf.get(TransformationApplierService.class).newApplier(ws);
			applier.setMaxPasses(5);
			JvmTransformResult result = assertDoesNotThrow(() -> applier.transformJvm(List.of(
					AlgebraicOpaquePredicateTransformer.class,
					CallResultInliningTransformer.class,
					OpaqueConstantFoldingTransformer.class,
					DeadCodeRemovingTransformer.class,
					MethodUsageCollectionTransformer.class,
					DeadPrivateMethodRemovingTransformer.class
			)));
			assertTrue(result.getTransformerFailures().isEmpty());
			result.apply();

			JvmClassInfo aAfter = ws.findJvmClass("demo/ClassA").getValue().asJvmClass();
			JvmClassInfo bAfter = ws.findJvmClass("demo/ClassB").getValue().asJvmClass();
			JvmClassInfo cAfter = ws.findJvmClass("demo/ClassC").getValue().asJvmClass();

			assertNull(bAfter.getDeclaredMethod("midHelper", "(I)Ljava/lang/String;"),
					"Expected cross-class inlined midHelper to be removed");
			assertNull(cAfter.getDeclaredMethod("innerConst", "()Ljava/lang/String;"),
					"Expected cross-class inlined innerConst to be removed");
			assertNotNull(cAfter.getDeclaredMethod("publicEntry", "()I"),
					"Non-inlined public API method should remain when remove-unused-non-private is false");
			assertNotNull(aAfter.getDeclaredMethod("tick", "()I"),
					"Side-effecting method must not be inlined or removed");

			StringWriter sw = new StringWriter();
			aAfter.getClassReader().accept(new TraceClassVisitor(new PrintWriter(sw)), 0);
			String disA = sw.toString();
			assertTrue(disA.contains("CROSS-CONST"), () -> "Expected inlined string in ClassA:\n" + disA);
			assertFalse(disA.contains("midHelper"), () -> "Expected call to midHelper removed:\n" + disA);
			assertTrue(disA.contains("tick"), () -> "Expected side-effecting call preserved:\n" + disA);
		} finally {
			ws.close();
		}
	}

	@Test
	void deadMethodRemovalRespectsNestmatesAndGetMethodVsGetDeclaredMethodReflection() throws Exception {
		JvmClassInfo host = TestClassUtils.createClass("demo/NestHost", node -> {
			node.visitNestMember("demo/NestHost$Member");

			MethodNode usedByNest = new MethodNode(ACC_PRIVATE | ACC_STATIC, "nestSecret", "()Ljava/lang/String;", null, null);
			usedByNest.visitLdcInsn("NEST");
			usedByNest.visitInsn(ARETURN);
			usedByNest.visitMaxs(1, 0);
			node.methods.add(usedByNest);

			MethodNode unusedPriv = new MethodNode(ACC_PRIVATE | ACC_STATIC, "deadPriv", "()Ljava/lang/String;", null, null);
			unusedPriv.visitLdcInsn("DEAD");
			unusedPriv.visitInsn(ARETURN);
			unusedPriv.visitMaxs(1, 0);
			node.methods.add(unusedPriv);

			MethodNode unusedPrivInst = new MethodNode(ACC_PRIVATE, "deadInst", "()I", null, null);
			unusedPrivInst.visitInsn(ICONST_1);
			unusedPrivInst.visitInsn(IRETURN);
			unusedPrivInst.visitMaxs(1, 1);
			node.methods.add(unusedPrivInst);

			// Calling Class.getMethod only reflects public methods, so private dead methods are still pruned.
			MethodNode entry = new MethodNode(ACC_PUBLIC | ACC_STATIC, "entry", "(Ljava/lang/Class;)Ljava/lang/Object;", null, null);
			entry.visitVarInsn(ALOAD, 0);
			entry.visitLdcInsn("toString");
			entry.visitInsn(ICONST_0);
			entry.visitTypeInsn(ANEWARRAY, "java/lang/Class");
			entry.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
			entry.visitInsn(ARETURN);
			entry.visitMaxs(3, 1);
			node.methods.add(entry);
		});

		JvmClassInfo member = TestClassUtils.createClass("demo/NestHost$Member", node -> {
			node.visitNestHost("demo/NestHost");
			MethodNode callNest = new MethodNode(ACC_PUBLIC | ACC_STATIC, "readNest", "()Ljava/lang/String;", null, null);
			callNest.visitMethodInsn(INVOKESTATIC, "demo/NestHost", "nestSecret", "()Ljava/lang/String;", false);
			callNest.visitInsn(ARETURN);
			callNest.visitMaxs(1, 0);
			node.methods.add(callNest);
		});

		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(host, member));
		try {
			TransformationApplier applier = recaf.get(TransformationApplierService.class).newApplier(ws);
			applier.setMaxPasses(2);
			JvmTransformResult result = assertDoesNotThrow(() -> applier.transformJvm(List.of(
					MethodUsageCollectionTransformer.class,
					DeadPrivateMethodRemovingTransformer.class
			)));
			assertTrue(result.getTransformerFailures().isEmpty());
			result.apply();

			JvmClassInfo hostAfter = ws.findJvmClass("demo/NestHost").getValue().asJvmClass();
			assertNotNull(hostAfter.getDeclaredMethod("nestSecret", "()Ljava/lang/String;"),
					"Private method referenced by nestmate must be preserved");
			assertNull(hostAfter.getDeclaredMethod("deadPriv", "()Ljava/lang/String;"),
					"Unreferenced private static method should be removed even when Class.getMethod is called");
			assertNull(hostAfter.getDeclaredMethod("deadInst", "()I"),
					"Unreferenced private instance method should be removed");
		} finally {
			ws.close();
		}
	}

	@Test
	void deadMethodRemovalPreservesPrivateMethodsOnGetDeclaredMethodAndSupportsNonPrivateFlag() throws Exception {
		JvmClassInfo reflected = TestClassUtils.createClass("demo/Reflected", node -> {
			MethodNode priv = new MethodNode(ACC_PRIVATE | ACC_STATIC, "keepPrivate", "()I", null, null);
			priv.visitInsn(ICONST_5);
			priv.visitInsn(IRETURN);
			priv.visitMaxs(1, 0);
			node.methods.add(priv);

			MethodNode caller = new MethodNode(ACC_PUBLIC | ACC_STATIC, "lookup", "(Ljava/lang/Class;)Ljava/lang/Object;", null, null);
			caller.visitVarInsn(ALOAD, 0);
			caller.visitLdcInsn("keepPrivate");
			caller.visitInsn(ACONST_NULL);
			caller.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
			caller.visitInsn(ARETURN);
			caller.visitMaxs(3, 1);
			node.methods.add(caller);
		});

		JvmClassInfo unusedHelperHost = TestClassUtils.createClass("demo/UnusedHost", node -> {
			MethodNode deadPkg = new MethodNode(ACC_STATIC, "unusedPkg", "()V", null, null);
			deadPkg.visitInsn(RETURN);
			deadPkg.visitMaxs(0, 0);
			node.methods.add(deadPkg);

			MethodNode main = new MethodNode(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
			main.visitInsn(RETURN);
			main.visitMaxs(0, 1);
			node.methods.add(main);
		});

		Workspace ws = TestClassUtils.fromBundle(TestClassUtils.fromClasses(reflected, unusedHelperHost));
		try {
			TransformationApplier applier = recaf.get(TransformationApplierService.class).newApplier(ws);
			applier.setMaxPasses(2);
			TransformationParameters params = new TransformationParameters(Map.of(
					DeadPrivateMethodRemovingTransformer.KEY_REMOVE_UNUSED_NON_PRIVATE, true));
			JvmTransformResult result = assertDoesNotThrow(() -> applier.transformJvm(List.of(
					MethodUsageCollectionTransformer.class,
					DeadPrivateMethodRemovingTransformer.class
			), params));
			assertTrue(result.getTransformerFailures().isEmpty());
			result.apply();

			JvmClassInfo reflectedAfter = ws.findJvmClass("demo/Reflected").getValue().asJvmClass();
			assertNotNull(reflectedAfter.getDeclaredMethod("keepPrivate", "()I"),
					"Class.getDeclaredMethod must protect private methods");

			JvmClassInfo unusedAfter = ws.findJvmClass("demo/UnusedHost").getValue().asJvmClass();
			assertNull(unusedAfter.getDeclaredMethod("unusedPkg", "()V"),
					"Unused package-private helper should be removed when remove-unused-non-private is enabled");
			assertNotNull(unusedAfter.getDeclaredMethod("main", "([Ljava/lang/String;)V"),
					"main() entry point must always be preserved");
		} finally {
			ws.close();
		}
	}
}
