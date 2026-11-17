package software.coley.recaf.services.analysis.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationReport.*;
import software.coley.recaf.services.deobfuscation.TransformerTestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.*;
import software.coley.recaf.workspace.model.resource.*;

import java.util.*;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class BytecodeInvestigationTest extends TransformerTestBase {
	private final BytecodeInvestigationService service = new BytecodeInvestigationService(new BytecodeInvestigationConfig());

	@Test
	void evaluatesConstantByteArgumentsWithoutChangingClassesAndRejectsUnknownAndStaleCalls() {
		compile("""
				private static String decode(String text, byte key, int unused) {
				    if (unused != 0) return System.getenv(text);
				    return text + key;
				}
				static String known() { return decode("key", (byte) 7, 0); }
				static String unknown(byte key) { return decode("key", key, 0); }
				""");
		Workspace workspace = workspaceManager.getCurrent();
		JvmClassInfo original = get(CLASS_NAME);
		var report = service.analyze(workspace, workspace.getPrimaryResource(), () -> false);
		CallSite known = report.callSites().stream().filter(site -> site.method().path().getValue().getName().equals("known")).findFirst().orElseThrow();
		CallSite unknown = report.callSites().stream().filter(site -> site.method().path().getValue().getName().equals("unknown")).findFirst().orElseThrow();
		Evaluation value = service.evaluate(workspace, known, 1000, false);
		assertEquals(Evaluation.Status.VALUE, value.status(), value.explanation());
		assertEquals("key7", value.value());
		assertTrue(value.instructions() > 0);
		assertEquals(Evaluation.Status.UNKNOWN_INPUTS, service.evaluate(workspace, unknown, 1000, false).status());
		assertSame(original, get(CLASS_NAME));
		assertTrue(service.describe(known).contains("> " + known.instruction() + ": invokestatic"));
		workspace.getPrimaryResource().getJvmClassBundle().put(new JvmClassInfoBuilder(original.getBytecode()).build());
		assertEquals(Evaluation.Status.STALE, service.evaluate(workspace, known, 1000, false).status());
	}

	@Test
	void budgetsLoopsAndSupportsCancellation() {
		compile("""
				private static String spin(int x) { while(x == 1) {} return "done"; }
				static String call() { return spin(1); }
				""");
		Workspace workspace = workspaceManager.getCurrent();
		var report = service.analyze(workspace, workspace.getPrimaryResource(), () -> false);
		CallSite site = report.callSites().getFirst();
		Evaluation result = service.evaluate(workspace, site, 100, false);
		assertEquals(Evaluation.Status.FAILED, result.status());
		assertTrue(result.instructions() <= 101, result.toString());
		assertThrows(CancellationException.class, () -> service.analyze(workspace, workspace.getPrimaryResource(), () -> true));
	}

	@Test
	void distinguishesNestedAndMultiReleaseDefinitionsAndJdkBootstraps() {
		JvmClassInfo outerClass = dynamicClass("Fixture", "outer");
		JvmClassInfo innerClass = dynamicClass("Fixture", "inner");
		BasicJvmClassBundle outer = new BasicJvmClassBundle(); outer.initialPut(outerClass);
		BasicJvmClassBundle inner = new BasicJvmClassBundle(); inner.initialPut(innerClass);
		BasicVersionedJvmClassBundle version = new BasicVersionedJvmClassBundle(17); version.initialPut(dynamicClass("Fixture", "versioned"));
		var embedded = new WorkspaceFileResourceBuilder().withFileInfo(new FileInfoBuilder<>().withName("plugin.jar").withRawContent(new byte[0]).build())
				.withJvmClassBundle(inner).build();
		var primary = new WorkspaceResourceBuilder().withJvmClassBundle(outer).withVersionedJvmClassBundles(new TreeMap<>(Map.of(17, version)))
				.withEmbeddedResources(Map.of("plugin.jar", embedded)).build();
		Workspace workspace = new BasicWorkspace(primary);
		var report = service.analyze(workspace, primary, () -> false);
		assertEquals(3, report.classes());
		assertEquals(6, report.callSites().size());
		assertEquals(6, report.bootstraps().size());
		assertEquals(3, report.callSites().stream().filter(CallSite::standardBootstrap).count());
		assertTrue(report.methods().stream().anyMatch(method -> method.resource().contains("Java 17")));
		for (var group : report.bootstraps()) {
			if (group.sites().getFirst().standardBootstrap()) continue;
			assertNotNull(group.declaration());
			assertSame(group.sites().getFirst().method().path().getValueOfType(JvmClassBundle.class), group.declaration().getValueOfType(JvmClassBundle.class));
		}
		assertEquals(0, report.callSites().getFirst().instruction()); // Labels are excluded from opcode ordinals.
	}

	private static JvmClassInfo dynamicClass(String name, String label) {
		return TestClassUtils.createClass(name, node -> {
			String descriptor = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
			MethodNode bootstrap = new MethodNode(ACC_PUBLIC | ACC_STATIC, "bootstrap", descriptor, null, null);
			bootstrap.visitInsn(ACONST_NULL); bootstrap.visitInsn(ARETURN); bootstrap.visitMaxs(1, 3); node.methods.add(bootstrap);
			MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, label, "()V", null, null);
			method.visitLabel(new Label());
			method.visitInvokeDynamicInsn("custom", "()Ljava/lang/String;", new Handle(H_INVOKESTATIC, name, "bootstrap", descriptor, false), "argument");
			method.visitInsn(POP);
			method.visitInvokeDynamicInsn("concat", "()Ljava/lang/String;", new Handle(H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", descriptor, false), "hello");
			method.visitInsn(POP); method.visitInsn(RETURN); method.visitMaxs(1, 0); node.methods.add(method);
		});
	}

	@Test
	void inventoriesNativeBoundariesWithExactResourceCallersAndLibraryCandidates() {
		JvmClassInfo nativeClass = TestClassUtils.createClass("Driver", node -> {
			node.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "get", "(Ljava/lang/String;)I", null, null).visitEnd();
		});
		JvmClassInfo javaClass = TestClassUtils.createClass("Driver", node -> {
			var method = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "(Ljava/lang/String;)I", null, null);
			method.visitInsn(ICONST_1); method.visitInsn(IRETURN); method.visitMaxs(1, 1); method.visitEnd();
		});
		JvmClassInfo caller = TestClassUtils.createClass("Caller", node -> {
			var method = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "seed", "()I", null, null);
			method.visitLabel(new Label()); method.visitLdcInsn("key");
			method.visitMethodInsn(INVOKESTATIC, "Driver", "get", "(Ljava/lang/String;)I", false);
			method.visitInsn(IRETURN); method.visitMaxs(1, 0); method.visitEnd();
		});
		BasicJvmClassBundle outer = TestClassUtils.fromClasses(nativeClass, caller);
		BasicJvmClassBundle inner = TestClassUtils.fromClasses(javaClass, caller);
		var nested = new WorkspaceFileResourceBuilder().withFileInfo(new FileInfoBuilder<>().withName("nested.jar").withRawContent(new byte[0]).build())
				.withJvmClassBundle(inner).build();
		BasicFileBundle files = new BasicFileBundle();
		files.put(new FileInfoBuilder<>().withName("lock/driver-macos.skid").withRawContent(new byte[]{1, 2}).build());
		files.put(new FileInfoBuilder<>().withName("readme.txt").withRawContent(new byte[0]).build());
		var primary = new WorkspaceResourceBuilder().withJvmClassBundle(outer).withFileBundle(files).withEmbeddedResources(Map.of("nested.jar", nested)).build();
		Workspace view = new BasicWorkspace(primary);
		var report = service.analyze(view, primary, () -> false);
		assertTrue(report.failures().isEmpty(), report.failures().toString());
		assertEquals(1, report.nativeMethods().size());
		NativeMethod method = report.nativeMethods().getFirst();
		assertEquals("Driver.get(Ljava/lang/String;)I", method.method().methodName());
		assertEquals(1, method.callers().size());
		assertSame(outer, method.callers().getFirst().method().path().getValueOfType(JvmClassBundle.class));
		assertEquals(1, method.callers().getFirst().instruction());
		assertEquals(List.of("Selected resource > lock/driver-macos.skid"), report.nativeLibraries());
		assertSame(nativeClass, outer.get("Driver"));
		assertSame(javaClass, inner.get("Driver"));
		assertEquals(2, files.size());
		view.close();
	}
}
