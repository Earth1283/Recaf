package software.coley.recaf.services.analysis.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import software.coley.recaf.services.analysis.compare.JarComparison.EntryChange;
import software.coley.recaf.services.analysis.compare.JarComparison.Kind;
import software.coley.recaf.services.analysis.compare.JarComparison.MemberChange;
import software.coley.recaf.services.analysis.compare.JarComparison.Status;
import software.coley.recaf.util.io.ZipCreationUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class JarComparisonTest {
	private static final String EXAMPLE = "example/Example";
	private static final String CLASS_PATH = EXAMPLE + ".class";

	@TempDir
	Path directory;

	@Test
	void strictModeMatchesLiteralValuesAtTheirBytecodeLocations() throws IOException {
		Path before = jar("before.jar", Map.of(CLASS_PATH, example("Hello", "world", 10, ICONST_0)));
		Path after = jar("after.jar", Map.of(CLASS_PATH, example("world", "Hello", 10, ICONST_0)));
		assertTrue(JarComparison.compare(before, after, false).isEmpty());

		List<EntryChange> changes = JarComparison.compare(before, after, true);
		assertEquals(1, changes.size());
		EntryChange change = changes.getFirst();
		assertEquals(Status.CHANGED, change.status());
		assertEquals(Kind.CLASS, change.kind());
		assertEquals(List.of(
				new MemberChange(Kind.FIELD, "TEXT", "Ljava/lang/String;", Status.CHANGED),
				new MemberChange(Kind.METHOD, "bootstrap", "()Ljava/lang/String;", Status.CHANGED),
				new MemberChange(Kind.METHOD, "literal", "()Ljava/lang/String;", Status.CHANGED)), change.members());
		MemberChange method = change.members().getLast();
		assertTrue(JarComparison.preview(before, change, method, true).text().contains("LDC \"Hello\""));
		assertTrue(JarComparison.preview(after, change, method, false).text().contains("LDC \"world\""));
	}

	@Test
	void ignoresDebugLinesAndDetectsInstructionChangesInBothModes() throws IOException {
		Path before = jar("before.jar", Map.of(CLASS_PATH, example("Hello", "world", 10, ICONST_0)));
		Path debugChange = jar("debug.jar", Map.of(CLASS_PATH, example("Hello", "world", 40, ICONST_0)));
		Path instructionChange = jar("instruction.jar", Map.of(CLASS_PATH, example("Hello", "world", 40, ICONST_1)));
		for (boolean strict : List.of(false, true)) {
			assertTrue(JarComparison.compare(before, debugChange, strict).isEmpty());
			EntryChange change = JarComparison.compare(before, instructionChange, strict).getFirst();
			assertEquals(List.of(new MemberChange(Kind.METHOD, "number", "()I", Status.CHANGED)), change.members());
		}
	}

	@Test
	void comparesResourcesAndKeepsMultiReleaseEntriesSeparate() throws IOException {
		String versioned = "META-INF/versions/17/" + CLASS_PATH;
		byte[] commonClass = example("Hello", "world", 10, ICONST_0);
		Path before = jar("before.jar", Map.of(CLASS_PATH, commonClass, versioned, commonClass,
				"old.txt", "removed".getBytes(UTF_8), "config.txt", "before".getBytes(UTF_8)));
		Path after = jar("after.jar", Map.of(CLASS_PATH, commonClass, versioned, example("Hello", "world", 10, ICONST_1),
				"new.txt", "added".getBytes(UTF_8), "config.txt", "after".getBytes(UTF_8)));

		List<EntryChange> changes = JarComparison.compare(before, after, false);
		assertEquals(List.of(versioned, "config.txt", "new.txt", "old.txt"), changes.stream().map(EntryChange::path).toList());
		assertEquals(List.of(Status.CHANGED, Status.CHANGED, Status.ADDED, Status.REMOVED), changes.stream().map(EntryChange::status).toList());
		assertEquals(Kind.CLASS, changes.getFirst().kind());
		EntryChange resource = changes.get(1);
		assertEquals(Kind.RESOURCE, resource.kind());
		assertEquals("before", JarComparison.preview(before, resource, null, true).text());
		assertEquals("after", JarComparison.preview(after, resource, null, false).text());
		assertNull(JarComparison.preview(before, changes.get(2), null, true));
		assertNull(JarComparison.preview(after, changes.get(3), null, false));
	}

	@Test
	void listsMembersOfAddedClassesAndRejectsStalePreviews() throws IOException {
		Path before = jar("before.jar", Map.of());
		Path after = jar("after.jar", Map.of(CLASS_PATH, example("Hello", "world", 10, ICONST_0)));
		EntryChange change = JarComparison.compare(before, after, false).getFirst();
		assertEquals(Status.ADDED, change.status());
		assertEquals(4, change.members().size());
		assertTrue(change.members().stream().allMatch(member -> member.status() == Status.ADDED));
		jar("after.jar", Map.of(CLASS_PATH, example("Hello", "world", 10, ICONST_1)));
		assertThrows(IOException.class, () -> JarComparison.preview(after, change, null, false));
	}

	@Test
	void comparesTheEffectiveEntryWhenPathsAreDuplicated() throws IOException {
		Path before = Files.write(directory.resolve("before.jar"), ZipCreationUtils.builder()
				.add("config.txt", "shadowed".getBytes(UTF_8))
				.add("config.txt", "effective".getBytes(UTF_8))
				.bytes());
		Path after = jar("after.jar", Map.of("config.txt", "effective".getBytes(UTF_8)));
		assertTrue(JarComparison.compare(before, after, true).isEmpty());
	}

	private Path jar(String name, Map<String, byte[]> entries) throws IOException {
		return Files.write(directory.resolve(name), ZipCreationUtils.createZip(entries));
	}

	private static byte[] example(String first, String second, int line, int numberOpcode) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(V17, ACC_PUBLIC, EXAMPLE, null, "java/lang/Object", null);
		writer.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "TEXT", "Ljava/lang/String;", null, first).visitEnd();

		MethodVisitor literal = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "literal", "()Ljava/lang/String;", null, null);
		literal.visitCode();
		Label start = new Label();
		literal.visitLabel(start);
		literal.visitLineNumber(line, start);
		literal.visitLdcInsn(first);
		literal.visitInsn(POP);
		literal.visitLdcInsn(second);
		literal.visitInsn(ARETURN);
		literal.visitMaxs(1, 0);
		literal.visitEnd();

		Handle bootstrap = new Handle(H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "explicitCast",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
		MethodVisitor dynamic = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "bootstrap", "()Ljava/lang/String;", null, null);
		dynamic.visitCode();
		dynamic.visitLdcInsn(new ConstantDynamic("text", "Ljava/lang/String;", bootstrap, second));
		dynamic.visitInsn(ARETURN);
		dynamic.visitMaxs(1, 0);
		dynamic.visitEnd();

		MethodVisitor number = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "number", "()I", null, null);
		number.visitCode();
		number.visitInsn(numberOpcode);
		number.visitInsn(IRETURN);
		number.visitMaxs(1, 0);
		number.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
