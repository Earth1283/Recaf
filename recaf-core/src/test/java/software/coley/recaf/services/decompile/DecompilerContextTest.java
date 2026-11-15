package software.coley.recaf.services.decompile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.RecafConstants;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.decompile.cfr.CfrConfig;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.decompile.vineflower.VineflowerConfig;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for how decompilers are given the classes the target refers to.
 */
class DecompilerContextTest {
	private static String lookupClassName;

	@BeforeAll
	static void findLookupClass() throws Exception {
		// javac puts the enum switch lookup table of 'UsesSwitch' into a synthetic class of this compilation unit.
		for (int i = 1; i < 10 && lookupClassName == null; i++) {
			String name = DecompilerContextTest.class.getName() + '$' + i;
			try {
				JvmClassInfo info = TestClassUtils.fromRuntimeClass(Class.forName(name));
				if (info.getFields().stream().anyMatch(f -> f.getName().startsWith("$SwitchMap$")))
					lookupClassName = info.getName();
			} catch (ClassNotFoundException ignored) {
				// Keep looking.
			}
		}
		assertNotNull(lookupClassName, "No enum switch lookup class was generated");
	}

	@Test
	void stubsKeepDeclarationsAndWhichMethodsHaveCode() {
		JvmClassInfo info = TestClassUtils.createClass("demo/Shape", node -> {
			node.access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
			MethodNode area = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "area", "()D", null, null);
			node.methods.add(area);
			MethodNode describe = new MethodNode(Opcodes.ACC_PUBLIC, "describe", "(Ljava/lang/String;J)Ljava/lang/String;",
					"(Ljava/lang/String;J)Ljava/lang/String;", new String[]{"java/io/IOException"});
			describe.visitCode();
			describe.visitLdcInsn("a long constant only the body refers to");
			describe.visitInsn(Opcodes.ARETURN);
			describe.visitMaxs(1, 4);
			node.methods.add(describe);
		});

		ClassNode stub = new ClassNode();
		new ClassReader(ReferencedClassStubs.of(info)).accept(stub, 0);
		assertEquals(List.of("area", "describe"), stub.methods.stream().map(m -> m.name).toList());
		MethodNode area = stub.methods.get(0);
		MethodNode describe = stub.methods.get(1);
		assertEquals(0, area.instructions.size(), "Abstract methods must stay without code");
		assertEquals(List.of("java/io/IOException"), describe.exceptions);
		assertEquals("(Ljava/lang/String;J)Ljava/lang/String;", describe.signature);
		assertTrue(describe.instructions.size() > 0, "Concrete methods must keep a body");
		assertEquals(4, describe.maxLocals);
		assertFalse(new String(ReferencedClassStubs.of(info)).contains("a long constant"));
		assertSame(ReferencedClassStubs.of(info), ReferencedClassStubs.of(info));
	}

	@Test
	void stubOfUnreadableClassIsTheOriginal() {
		JvmClassInfo valid = TestClassUtils.createEmptyClass("demo/Broken");
		byte[] truncated = Arrays.copyOf(valid.getBytecode(), 12);
		JvmClassInfo broken = new JvmClassInfoBuilder(valid).withBytecode(truncated).build();
		assertSame(truncated, ReferencedClassStubs.of(broken));
	}

	@Test
	void scopeKeepsRenamedLookupTablesButStubsOtherReferences() throws Exception {
		Workspace workspace = obfuscatedSwitchWorkspace();
		JvmClassInfo target = workspace.findJvmClass("obf/Target").getValue().asJvmClass();
		TargetCodeScope scope = TargetCodeScope.of(workspace, target);
		assertTrue(scope.needsCode("obf/Lut"), "Enum switch lookup class must be given in full");
		assertFalse(scope.needsCode("obf/Color"), "Classes only consulted for declarations are stubbed");
		assertFalse(scope.needsCode("java/lang/String"));
		assertTrue(scope.needsCode("obf/Target$Helper"), "Classes named as nested in the target are given in full");
	}

	@Test
	void cfrRestoresEnumSwitchThroughRenamedLookupClass() throws Exception {
		Workspace workspace = obfuscatedSwitchWorkspace();
		CfrDecompiler cfr = new CfrDecompiler(new BasicWorkspaceManager(new WorkspaceManagerConfig()), new CfrConfig());
		JvmClassInfo target = workspace.findJvmClass("obf/Target").getValue().asJvmClass();
		String text = cfr.decompile(workspace, target).getText();
		assertNotNull(text);
		assertTrue(text.contains("case RED:"), text);
		assertFalse(text.contains("Lut.") || text.contains("ordinal()"), text);
	}

	@Test
	void vineflowerResolvesRuntimeTypesWithoutScanningTheRuntime() throws Exception {
		Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(UsesGenerics.class));
		VineflowerDecompiler vineflower = new VineflowerDecompiler(new VineflowerConfig());
		JvmClassInfo target = workspace.findJvmClass(UsesGenerics.class.getName().replace('.', '/')).getValue().asJvmClass();
		String text = vineflower.decompile(workspace, target).getText();
		assertNotNull(text);
		// The diamond is only printed when Vineflower can read the generic signature of the runtime's 'ArrayList'.
		assertTrue(text.contains("new ArrayList<>()"), text);
	}

	/**
	 * @return Workspace mimicking obfuscated output: the target, its enum, and the enum switch lookup class are renamed
	 * and their inner class and nest attributes removed, so nothing links the lookup class to the target.
	 */
	private static Workspace obfuscatedSwitchWorkspace() throws Exception {
		Map<String, String> names = Map.of(
				UsesSwitch.class.getName().replace('.', '/'), "obf/Target",
				SwitchSampleColor.class.getName().replace('.', '/'), "obf/Color",
				lookupClassName, "obf/Lut");
		SimpleRemapper remapper = new SimpleRemapper(RecafConstants.getAsmVersion(), names);
		JvmClassInfo[] classes = new JvmClassInfo[3];
		Class<?>[] sources = {UsesSwitch.class, SwitchSampleColor.class, Class.forName(lookupClassName.replace('/', '.'))};
		for (int i = 0; i < sources.length; i++) {
			ClassWriter writer = new ClassWriter(0);
			ClassVisitor stripNesting = new ClassVisitor(RecafConstants.getAsmVersion(), writer) {
				@Override
				public void visitInnerClass(String name, String outerName, String innerName, int access) {}

				@Override
				public void visitOuterClass(String owner, String name, String descriptor) {}

				@Override
				public void visitNestHost(String nestHost) {}

				@Override
				public void visitNestMember(String nestMember) {}
			};
			new ClassReader(TestClassUtils.fromRuntimeClass(sources[i]).getBytecode())
					.accept(new ClassRemapper(stripNesting, remapper), 0);
			classes[i] = new JvmClassInfoBuilder(writer.toByteArray()).build();
		}
		return TestClassUtils.fromBundle(TestClassUtils.fromClasses(classes));
	}

	static class UsesSwitch {
		static String name(SwitchSampleColor color) {
			switch (color) {
				case RED:
					return "red";
				case GREEN:
					return "green";
				default:
					return "other";
			}
		}
	}

	static class UsesGenerics {
		final List<String> names = new ArrayList<>();
	}
}
