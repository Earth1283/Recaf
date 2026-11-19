package software.coley.recaf.services.analysis.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;
import static software.coley.recaf.services.analysis.bytecode.ObfuscationTechnologyDetector.Technology.*;

class ObfuscationTechnologyDetectorTest {
	private final ObfuscationTechnologyDetector detector = new ObfuscationTechnologyDetector();

	@Test
	void reportsTechniquesWithoutFamilyEvidenceAndPreservesResourceProvenance() {
		var original = techniqueFixture();
		var version = new BasicVersionedJvmClassBundle(17);
		version.initialPut(original);
		var inner = new WorkspaceFileResourceBuilder().withFileInfo(TestClassUtils.createFile("inner.jar", new byte[0]))
				.withJvmClassBundle(TestClassUtils.fromClasses(ordinaryFixture())).build();
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(original))
				.withVersionedJvmClassBundles(new TreeMap<>(Map.of(17, version))).withEmbeddedResources(Map.of("inner.jar", inner)).build();
		try {
			assertTrue(new ObfuscatorFingerprinting().scan(root, () -> false).matches().isEmpty());
			var result = detector.scan(root, () -> false);
			assertEquals(3, result.classesRead());
			assertEquals(0, result.classesSkipped());
			assertTrue(result.matches().stream().allMatch(match -> match.resource().isEmpty()));
			var matches = result.matches().stream().collect(Collectors.toMap(ObfuscationTechnologyDetector.Match::technology, match -> match));
			assertEquals(Map.of(STRING_CONCEALMENT, 2L, NUMERIC_OBFUSCATION, 2L, OPAQUE_BRANCHES, 2L, DYNAMIC_LINKAGE, 4L,
					NATIVE_CODE, 2L, CLASS_PACKING, 2L, UNUSUAL_IDENTIFIERS, 2L), matches.values().stream()
					.collect(Collectors.toMap(ObfuscationTechnologyDetector.Match::technology, ObfuscationTechnologyDetector.Match::occurrences)));
			assertTrue(matches.get(UNUSUAL_IDENTIFIERS).examples().contains("Unattributed.zero\\u200bwidth (Java 17)"));
			assertSame(original, root.getJvmClassBundle().get("Unattributed"));
			assertThrows(CancellationException.class, () -> detector.scan(root, () -> true));
		} finally {
			root.close();
		}
	}

	@Test
	void inDepthScanTracksValuesAndReachabilityAndPreservesQuickEvidenceWhenBounded() {
		var resource = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(flowFixture())).build();
		try {
			var quick = detector.scan(resource, () -> false);
			assertEquals(new ObfuscationTechnologyDetector.ControlFlowCoverage(0, 0), quick.controlFlow());
			assertEquals(List.of(new ObfuscationTechnologyDetector.Match("", OPAQUE_BRANCHES, 1, List.of("Flow.bounded()V"))), quick.matches());

			var deep = detector.scan(resource, true, () -> false);
			assertEquals(0, deep.classesSkipped());
			assertEquals(new ObfuscationTechnologyDetector.ControlFlowCoverage(4, 1), deep.controlFlow());
			assertEquals(List.of(
					new ObfuscationTechnologyDetector.Match("", OPAQUE_BRANCHES, 4,
							List.of("Flow.bounded()V", "Flow.dispatch()V", "Flow.fixedComparison()V", "Flow.singleDestination(I)V")),
					new ObfuscationTechnologyDetector.Match("", UNREACHABLE_CODE, 2, List.of("Flow.dispatch()V"))), deep.matches());
		} finally {
			resource.close();
		}
	}

	private static JvmClassInfo flowFixture() {
		return TestClassUtils.createClass("Flow", node -> {
			var comparison = node.visitMethod(ACC_STATIC, "fixedComparison", "()V", null, null);
			Label done = new Label();
			comparison.visitLdcInsn(Integer.MAX_VALUE);
			comparison.visitIntInsn(NEWARRAY, T_INT);
			comparison.visitInsn(POP);
			comparison.visitIntInsn(BIPUSH, 7);
			comparison.visitVarInsn(ISTORE, 0);
			comparison.visitIincInsn(0, 2);
			comparison.visitVarInsn(ILOAD, 0);
			comparison.visitIntInsn(BIPUSH, 9);
			comparison.visitJumpInsn(IF_ICMPEQ, done);
			comparison.visitInsn(NOP);
			comparison.visitLabel(done);
			comparison.visitInsn(RETURN);
			comparison.visitMaxs(2, 1);

			var dispatch = node.visitMethod(ACC_STATIC, "dispatch", "()V", null, null);
			Label selector = new Label(), first = new Label(), fallback = new Label();
			dispatch.visitInsn(ICONST_1);
			dispatch.visitVarInsn(ISTORE, 0);
			dispatch.visitJumpInsn(GOTO, selector);
			dispatch.visitInsn(ICONST_2);
			dispatch.visitInsn(POP);
			dispatch.visitLabel(selector);
			dispatch.visitVarInsn(ILOAD, 0);
			dispatch.visitTableSwitchInsn(1, 1, fallback, first);
			dispatch.visitLabel(first);
			dispatch.visitInsn(RETURN);
			dispatch.visitLabel(fallback);
			dispatch.visitInsn(RETURN);
			dispatch.visitMaxs(1, 1);

			var single = node.visitMethod(ACC_STATIC, "singleDestination", "(I)V", null, null);
			Label target = new Label();
			single.visitVarInsn(ILOAD, 0);
			single.visitLookupSwitchInsn(target, new int[]{7}, new Label[]{target});
			single.visitLabel(target);
			single.visitInsn(RETURN);
			single.visitMaxs(1, 1);

			var unequal = node.visitMethod(ACC_STATIC, "unknownAtJoin", "(I)V", null, null);
			Label other = new Label(), join = new Label(), exit = new Label();
			unequal.visitVarInsn(ILOAD, 0);
			unequal.visitJumpInsn(IFEQ, other);
			unequal.visitInsn(ICONST_1);
			unequal.visitJumpInsn(GOTO, join);
			unequal.visitLabel(other);
			unequal.visitInsn(ICONST_0);
			unequal.visitLabel(join);
			unequal.visitJumpInsn(IFEQ, exit);
			unequal.visitInsn(NOP);
			unequal.visitLabel(exit);
			unequal.visitInsn(RETURN);
			unequal.visitMaxs(1, 1);

			var bounded = node.visitMethod(ACC_STATIC, "bounded", "()V", null, null);
			Label finish = new Label();
			bounded.visitInsn(ICONST_0);
			bounded.visitJumpInsn(IFEQ, finish);
			bounded.visitInsn(NOP);
			bounded.visitLabel(finish);
			bounded.visitInsn(RETURN);
			bounded.visitMaxs(1, 65_000);
		});
	}

	private static JvmClassInfo techniqueFixture() {
		return TestClassUtils.createClass("Unattributed", node -> {
			node.version = V11;
			node.visitField(ACC_PUBLIC, "zero\u200bwidth", "I", null, null);
			node.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "bridge", "()V", null, null);
			var method = node.visitMethod(ACC_STATIC, "decode", "()V", null, null);
			Label branch = new Label();
			method.visitInsn(ICONST_1);
			method.visitJumpInsn(IFEQ, branch);
			method.visitInsn(ICONST_2);
			method.visitInsn(ICONST_3);
			method.visitInsn(IXOR);
			method.visitInsn(POP);
			method.visitLabel(branch);
			method.visitInvokeDynamicInsn("call", "()V", new Handle(H_INVOKESTATIC, "UnknownBootstrap", "link",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false));
			method.visitLdcInsn(new ConstantDynamic("hidden", "Ljava/lang/String;", new Handle(H_INVOKESTATIC, "UnknownBootstrap", "constant",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/String;", false)));
			method.visitInsn(POP);
			method.visitInsn(RETURN);
			method.visitMaxs(2, 0);

			var loader = node.visitMethod(ACC_STATIC, "load", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljavax/crypto/Cipher;[B)Ljava/lang/Class;", null, null);
			loader.visitVarInsn(ALOAD, 0);
			loader.visitVarInsn(ALOAD, 1);
			loader.visitVarInsn(ALOAD, 2);
			loader.visitMethodInsn(INVOKEVIRTUAL, "javax/crypto/Cipher", "doFinal", "([B)[B", false);
			loader.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "defineClass", "([B)Ljava/lang/Class;", false);
			loader.visitInsn(ARETURN);
			loader.visitMaxs(3, 3);

			var strings = node.visitMethod(ACC_STATIC, "text", "(Ljavax/crypto/Cipher;[B)Ljava/lang/String;", null, null);
			strings.visitTypeInsn(NEW, "java/lang/String");
			strings.visitInsn(DUP);
			strings.visitVarInsn(ALOAD, 0);
			strings.visitVarInsn(ALOAD, 1);
			strings.visitMethodInsn(INVOKEVIRTUAL, "javax/crypto/Cipher", "doFinal", "([B)[B", false);
			strings.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([B)V", false);
			strings.visitInsn(ARETURN);
			strings.visitMaxs(4, 2);
		});
	}

	private static JvmClassInfo ordinaryFixture() {
		return TestClassUtils.createClass("Ordinary", node -> {
			var method = node.visitMethod(ACC_STATIC, "concat", "(I)Ljava/lang/String;", null, null);
			method.visitVarInsn(ILOAD, 0);
			method.visitInsn(ICONST_1);
			method.visitInsn(IXOR);
			method.visitInvokeDynamicInsn("makeConcatWithConstants", "(I)Ljava/lang/String;",
					new Handle(H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
							"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false),
					"value: \u0001");
			method.visitInsn(ARETURN);
			method.visitMaxs(2, 1);
		});
	}
}
