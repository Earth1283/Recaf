package software.coley.recaf.services.analysis.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.MethodVisitor;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting.Confidence;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting.Family;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting.Match;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ObfuscatorFingerprintingTest {
	private final ObfuscatorFingerprinting fingerprinting = new ObfuscatorFingerprinting();

	@Test
	void keepsObfuscatorsInEmbeddedResourcesSeparateAndIncludesVersionedClasses() {
		var original = TestClassUtils.createClass("Sample", node -> {
			var method = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "number", "()I", null, null);
			method.visitIntInsn(BIPUSH, 17);
			method.visitIntInsn(BIPUSH, 42);
			method.visitInsn(SWAP);
			method.visitInsn(DUP_X1);
			method.visitInsn(POP2);
			method.visitIntInsn(BIPUSH, 59);
			method.visitInsn(IXOR);
			method.visitInsn(IRETURN);
			method.visitMaxs(3, 0);
			method.visitEnd();
		});
		var version = new BasicVersionedJvmClassBundle(17);
		version.initialPut(original);
		var inner = new WorkspaceFileResourceBuilder().withFileInfo(TestClassUtils.createFile("inner.jar", new byte[0]))
				.withJvmClassBundle(TestClassUtils.fromClasses(arrayString(true))).build();
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(original))
				.withVersionedJvmClassBundles(new TreeMap<>(Map.of(17, version))).withEmbeddedResources(Map.of("inner.jar", inner)).build();
		try {
			var result = fingerprinting.scan(root, () -> false);
			assertEquals(3, result.classesRead());
			assertEquals(0, result.classesSkipped());
			assertEquals(List.of(Family.RADON, Family.BOZAR), result.matches().stream().map(Match::family).toList());
			Match radon = result.matches().getFirst();
			assertEquals(Confidence.STRONG, radon.confidence());
			assertEquals(2, radon.evidence().getFirst().occurrences());
			assertTrue(radon.evidence().getFirst().examples().contains("Sample.number()I (Java 17)"));
			assertEquals("inner.jar", result.matches().getLast().resource());
			assertSame(original, root.getJvmClassBundle().get("Sample"));
			assertThrows(CancellationException.class, () -> fingerprinting.scan(root, () -> true));
		} finally {
			root.close();
		}
	}

	@Test
	void ordinaryArrayConstructionAndPlainXorDoNotAttributeAnObfuscator() {
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(arrayString(false))).build();
		try {
			assertTrue(fingerprinting.scan(root, () -> false).matches().isEmpty());
		} finally {
			root.close();
		}
	}

	@Test
	void identifiesGeneratedZelixMetadataInTheExistingSpecimen() throws Exception {
		var classes = new BasicJvmClassBundle();
		try (var sample = getClass().getResourceAsStream("/samples/obf-sample-zkm-26.jar")) {
			assertNotNull(sample);
			try (var zip = new ZipInputStream(sample)) {
				for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
					if (entry.getName().endsWith(".class"))
						classes.initialPut(new JvmClassInfoBuilder(zip.readAllBytes()).build());
				}
			}
		}
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(classes).build();
		try {
			var match = fingerprinting.scan(root, () -> false).matches().stream()
					.filter(candidate -> candidate.family() == Family.ZELIX).findFirst().orElseThrow();
			assertEquals(Confidence.STRONG, match.confidence());
			assertFalse(match.evidence().getFirst().examples().isEmpty());
		} finally {
			root.close();
		}
	}

	private static JvmClassInfo arrayString(boolean junkFirst) {
		return TestClassUtils.createClass("ArrayText", node -> {
			var method = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "text", "()Ljava/lang/String;", null, null);
			method.visitInsn(ICONST_1);
			method.visitIntInsn(NEWARRAY, T_BYTE);
			method.visitVarInsn(ASTORE, 0);
			if (junkFirst) writeByte(method, 17);
			writeByte(method, 65);
			method.visitTypeInsn(NEW, "java/lang/String");
			method.visitInsn(DUP);
			method.visitVarInsn(ALOAD, 0);
			method.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([B)V", false);
			method.visitInsn(ARETURN);
			method.visitMaxs(4, 1);
			method.visitEnd();
		});
	}

	private static void writeByte(MethodVisitor method, int value) {
		method.visitVarInsn(ALOAD, 0);
		method.visitLdcInsn("");
		method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
		method.visitIntInsn(BIPUSH, 5);
		method.visitIntInsn(BIPUSH, value ^ 5);
		method.visitInsn(IXOR);
		method.visitInsn(BASTORE);
	}
}
