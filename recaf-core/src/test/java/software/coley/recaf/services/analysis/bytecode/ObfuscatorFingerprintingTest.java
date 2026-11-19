package software.coley.recaf.services.analysis.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.MethodVisitor;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting.Confidence;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting.Family;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting.Indicator;
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
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ObfuscatorFingerprintingTest {
	private final ObfuscatorFingerprinting fingerprinting = new ObfuscatorFingerprinting();

	@Test
	void keepsObfuscatorsInEmbeddedResourcesSeparateAndIncludesVersionedClasses() {
		var original = radonNumber("Sample", false);
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
			assertEquals(List.of(Family.BOZAR, Family.RADON), result.matches().stream().map(Match::family).toList());
			Match radon = result.matches().getLast();
			assertEquals(75, radon.confidencePercent());
			assertEquals(Confidence.POSSIBLE, radon.confidence());
			assertEquals(2, radon.evidence().getFirst().occurrences());
			assertTrue(radon.evidence().getFirst().examples().contains("Sample.number()I (Java 17)"));
			assertEquals("inner.jar", result.matches().getFirst().resource());
			assertEquals(85, result.confidenceScores().get(Family.BOZAR));
			assertEquals(0, result.confidenceScores().get(Family.SKIDFUSCATOR));
			assertSame(original, root.getJvmClassBundle().get("Sample"));
			assertThrows(CancellationException.class, () -> fingerprinting.scan(root, () -> true));
		} finally {
			root.close();
		}
	}

	@Test
	void corroboratesAcrossDistinctClassesAndSignaturesWithinEachResource() {
		var inner = new WorkspaceFileResourceBuilder().withFileInfo(TestClassUtils.createFile("inner.jar", new byte[0]))
				.withJvmClassBundle(TestClassUtils.fromClasses(radonNumber("Embedded", true))).build();
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(
				radonNumber("First", false), radonNumber("Second", false))).withEmbeddedResources(Map.of("inner.jar", inner)).build();
		try {
			var result = fingerprinting.scan(root, () -> false);
			assertEquals(80, result.confidenceScores().get(Family.RADON));
			assertEquals(80, result.matches().getFirst().confidencePercent());
			assertEquals(75, result.matches().getLast().confidencePercent());
			root.getJvmClassBundle().put(radonNumber("Third", true));
			assertEquals(90, fingerprinting.scan(root, () -> false).confidenceScores().get(Family.RADON));
		} finally {
			root.close();
		}
	}

	@Test
	void ordinaryArrayConstructionAndPlainXorDoNotAttributeAnObfuscator() {
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(arrayString(false), dashoHelper(false))).build();
		try {
			var result = fingerprinting.scan(root, () -> false);
			assertTrue(result.matches().isEmpty());
			assertEquals(Family.values().length, result.confidenceScores().size());
			assertTrue(result.confidenceScores().values().stream().allMatch(score -> score == 0));
		} finally {
			root.close();
		}
	}

	@Test
	void identifiesDashOHelperAndKeepsFilenameOnlyEvidencePossible() {
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(dashoHelper(true))).build();
		try {
			for (String file : List.of("lock/driver-linux.skid", "lock/driver-macos.skid", "lock/driver-.skid", "lock/driver-backup/copy.skid"))
				root.getFileBundle().put(TestClassUtils.createFile(file, new byte[0]));
			var result = fingerprinting.scan(root, () -> false);
			assertEquals(List.of(Family.DASHO, Family.SKIDFUSCATOR), result.matches().stream().map(Match::family).toList());
			Match dasho = result.matches().getFirst();
			assertEquals(60, dasho.confidencePercent());
			assertEquals(Indicator.DASHO_SEED_HELPER, dasho.evidence().getFirst().indicator());
			Match skid = result.matches().getLast();
			assertEquals(35, skid.confidencePercent());
			assertEquals(Confidence.POSSIBLE, skid.confidence());
			assertEquals(2, skid.evidence().getFirst().occurrences());
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
			assertTrue(match.confidencePercent() >= 90 && match.confidencePercent() <= 99);
			assertFalse(match.evidence().getFirst().examples().isEmpty());
		} finally {
			root.close();
		}
	}

	@Test
	void recognizesNewFingerprintsAndRejectsIncompletePatterns() {
		var allatori = TestClassUtils.createClass("Demo", node -> node.visitField(ACC_STATIC, "ALLATORI_DEMO_MINE", "I", null, null));
		var bozar = TestClassUtils.createClass("Watermark", node -> node.visitMethod(ACC_ABSTRACT, "\u0001", "(\u0001/)L\u0001/;", null, null));
		var r8 = TestClassUtils.createClass("R8Sample", node -> node.visitSource("R8_abcdef0_" + "a".repeat(64), null));
		var decoy = TestClassUtils.createClass("Decoy", node -> {
			node.visitSource("R8_abcdef0_truncated", null);
			node.visitField(ACC_STATIC | ACC_FINAL, "message", "Ljava/lang/String;", null, "ALLATORI_DEMO");
		});
		var root = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(
				allatori, bozar, r8, dprotectDecoder(true), jobfLoader(true), nativeLoader(true), stringerDecoder(true))).build();
		var lookalikes = new WorkspaceResourceBuilder().withJvmClassBundle(TestClassUtils.fromClasses(
				decoy, dprotectDecoder(false), jobfLoader(false), nativeLoader(false), stringerDecoder(false))).build();
		try {
			var result = fingerprinting.scan(root, () -> false);
			assertEquals(7, result.classesRead());
			assertEquals(Map.of(
					Family.ALLATORI, Indicator.ALLATORI_DEMO_MARKER, Family.BOZAR, Indicator.BOZAR_DUMMY_CLASS,
					Family.DPROTECT, Indicator.DPROTECT_STRING_DECODER, Family.JOBF, Indicator.JOBF_CLASS_LOADER,
					Family.NATIVE_OBFUSCATOR, Indicator.NATIVE_OBFUSCATOR_LOADER, Family.R8, Indicator.R8_MAPPING_SOURCE_FILE,
					Family.STRINGER, Indicator.STRINGER_STACK_TRACE_DECODER), result.matches().stream()
					.collect(Collectors.toMap(Match::family, match -> match.evidence().getFirst().indicator())));
			assertTrue(fingerprinting.scan(lookalikes, () -> false).matches().isEmpty());
		} finally {
			root.close();
			lookalikes.close();
		}
	}

	private static JvmClassInfo nativeLoader(boolean registersNatives) {
		return TestClassUtils.createClass("custom/Loader", node -> {
			node.visitSource("synthetic", null);
			if (registersNatives) node.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "registerNativesForClass", "(ILjava/lang/Class;)V", null, null);
			var method = node.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
			method.visitLdcInsn("native-lib");
			method.visitMethodInsn(INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V", false);
			method.visitInsn(RETURN);
			method.visitMaxs(1, 0);
		});
	}

	private static JvmClassInfo jobfLoader(boolean transformsName) {
		return TestClassUtils.createClass("Packaged", node -> {
			node.superName = "java/lang/ClassLoader";
			var method = node.visitMethod(ACC_PROTECTED, "findClass", "(Ljava/lang/String;)Ljava/lang/Class;", null, null);
			if (transformsName) {
				method.visitInsn(ACONST_NULL);
				method.visitInsn(ACONST_NULL);
				method.visitMethodInsn(INVOKESTATIC, node.name, "xor", "([B[B)[B", false);
				method.visitInsn(POP);
			}
			method.visitVarInsn(ALOAD, 0);
			method.visitVarInsn(ALOAD, 1);
			method.visitVarInsn(ALOAD, 1);
			method.visitMethodInsn(INVOKESTATIC, node.name, "read", "(Ljava/lang/String;)[B", false);
			method.visitInsn(ACONST_NULL);
			method.visitMethodInsn(INVOKESTATIC, node.name, "xor", "([B[B)[B", false);
			method.visitInsn(ICONST_0);
			method.visitInsn(ICONST_0);
			method.visitMethodInsn(INVOKEVIRTUAL, node.name, "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;", false);
			method.visitInsn(ARETURN);
			method.visitMaxs(5, 2);
		});
	}

	private static JvmClassInfo dprotectDecoder(boolean xor) {
		return TestClassUtils.createClass("DProtectSample", node -> {
			var method = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "simpleXorDecode", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
			method.visitTypeInsn(NEW, "java/lang/StringBuilder");
			method.visitInsn(DUP);
			method.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
			method.visitVarInsn(ALOAD, 0);
			method.visitInsn(ICONST_0);
			method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
			if (xor) {
				method.visitInsn(ICONST_1);
				method.visitInsn(IXOR);
				method.visitInsn(I2C);
			}
			method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false);
			method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
			method.visitInsn(ARETURN);
			method.visitMaxs(3, 1);
		});
	}

	private static JvmClassInfo stringerDecoder(boolean includesStackTrace) {
		return TestClassUtils.createClass("StringerSample", node -> {
			var method = node.visitMethod(ACC_STATIC, "decode", "(Ljava/lang/Object;III)Ljava/lang/String;", null, null);
			if (includesStackTrace) {
				method.visitMethodInsn(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
				method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false);
				method.visitInsn(POP);
			}
			for (int opcode : List.of(IAND, IXOR, IUSHR, ISHL))
				for (int i = 0; i < 21; i++) {
					method.visitVarInsn(ILOAD, 1);
					method.visitVarInsn(ILOAD, 2);
					method.visitInsn(opcode);
					method.visitInsn(POP);
				}
			method.visitInsn(ACONST_NULL);
			method.visitInsn(ARETURN);
			method.visitMaxs(2, 4);
		});
	}

	private static JvmClassInfo radonNumber(String name, boolean wide) {
		return TestClassUtils.createClass(name, node -> {
			var method = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "number", wide ? "()J" : "()I", null, null);
			if (wide) {
				method.visitLdcInsn(17L);
				method.visitLdcInsn(42L);
				method.visitInsn(DUP2_X2);
				method.visitInsn(POP2);
				method.visitInsn(POP2);
				method.visitLdcInsn(59L);
				method.visitInsn(LXOR);
				method.visitInsn(LRETURN);
			} else {
				method.visitIntInsn(BIPUSH, 17);
				method.visitIntInsn(BIPUSH, 42);
				method.visitInsn(SWAP);
				method.visitInsn(DUP_X1);
				method.visitInsn(POP2);
				method.visitIntInsn(BIPUSH, 59);
				method.visitInsn(IXOR);
				method.visitInsn(IRETURN);
			}
			method.visitMaxs(wide ? 6 : 3, 0);
			method.visitEnd();
		});
	}

	private static JvmClassInfo dashoHelper(boolean addOne) {
		return TestClassUtils.createClass("SeedHelper", node -> {
			var decoder = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "decode", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
			decoder.visitVarInsn(ALOAD, 0);
			decoder.visitInsn(ARETURN);
			decoder.visitMaxs(1, 1);
			decoder.visitEnd();
			var seed = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "seed", "()I", null, null);
			seed.visitTypeInsn(NEW, "java/util/Random");
			seed.visitInsn(DUP);
			seed.visitMethodInsn(INVOKESPECIAL, "java/util/Random", "<init>", "()V", false);
			seed.visitIntInsn(BIPUSH, 100);
			seed.visitMethodInsn(INVOKEVIRTUAL, "java/util/Random", "nextInt", "(I)I", false);
			if (addOne) {
				seed.visitInsn(ICONST_1);
				seed.visitInsn(IADD);
			}
			seed.visitInsn(IRETURN);
			seed.visitMaxs(2, 0);
			seed.visitEnd();
		});
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
