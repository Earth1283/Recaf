package software.coley.recaf.services.analysis.plugin.view;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityService;
import software.coley.recaf.services.analysis.plugin.semantic.ObfuscatedPluginFixture;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.services.analysis.plugin.view.PluginViewService.DeobfuscationStatus;
import software.coley.recaf.services.compile.CompilerResult;
import software.coley.recaf.services.compile.JavacArgumentsBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.cfr.CfrDecompiler;
import software.coley.recaf.services.decompile.filter.JvmBytecodeFilter;
import software.coley.recaf.services.decompile.procyon.ProcyonDecompiler;
import software.coley.recaf.services.decompile.vineflower.VineflowerDecompiler;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PluginViewService}, using {@link ObfuscatedPluginFixture}.
 */
class PluginViewServiceTest extends TestBase {
	static PluginViewService service;
	static PluginViewConfig config;
	static DecompilerManager decompilers;
	static PluginSemanticService semantic;
	static JavacCompiler javac;

	@BeforeAll
	static void setup() {
		service = recaf.get(PluginViewService.class);
		config = service.getServiceConfig();
		decompilers = recaf.get(DecompilerManager.class);
		semantic = recaf.get(PluginSemanticService.class);
		javac = recaf.get(JavacCompiler.class);
	}

	@BeforeEach
	@AfterEach
	void resetConfig() {
		config.getAnnotateStructure().setValue(true);
		config.getAnnotateSecurity().setValue(true);
		config.getNameParameters().setValue(true);
		config.getDeobfuscatedView().setValue(false);
	}

	private static final String NESTED = """
			package a;
			import org.bukkit.event.*;
			import org.bukkit.event.player.*;
			public class g {
				public void h(a p) { p.getServer().getScheduler().runTaskTimer(p, new Runnable() { public void run() {} }, 1L, 1L); }
				public void j(a p) { p = null; }
				public void k(a p) {}
				public void x() throws Exception { Runtime.getRuntime().exec("calc"); }
				public static class i implements Listener {
					@EventHandler public void a(PlayerJoinEvent m) {}
				}
			}
			""";

	@Nonnull
	private static Workspace stripped() {
		Workspace workspace = ObfuscatedPluginFixture.create(javac, true);
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
		CompilerResult nested = javac.compile(new JavacArgumentsBuilder().withClassSources(Map.of("a/g", NESTED))
				.withVersionTarget(17).build(), workspace, null);
		assertTrue(nested.wasSuccess(), String.valueOf(nested.getDiagnostics()));
		nested.getCompilations().forEach((name, bytecode) -> bundle.put(new JvmClassInfoBuilder(bytecode).build()));
		for (JvmClassInfo info : List.copyOf(bundle.values())) {
			ClassReader reader = new ClassReader(info.getBytecode());
			ClassWriter writer = new ClassWriter(0);
			reader.accept(writer, ClassReader.SKIP_DEBUG);
			bundle.put(new JvmClassInfoBuilder(writer.toByteArray()).build());
		}
		return workspace;
	}

	@Nonnull
	private static Workspace open(boolean withDeadCode) {
		Workspace workspace = stripped();
		if (withDeadCode)
			workspace.getPrimaryResource().getJvmClassBundle().put(deadCodeClass());
		workspaceManager.setCurrentIgnoringConditions(workspace);
		semantic.getIndex(workspace);
		return workspace;
	}

	@Nonnull
	private static JvmClassInfo deadCodeClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, "a/z", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "q", "()V", null, null);
		mv.visitCode();
		Label end = new Label();
		mv.visitJumpInsn(Opcodes.GOTO, end);
		mv.visitLdcInsn("deadjunk");
		mv.visitInsn(Opcodes.POP);
		mv.visitLabel(end);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	@Nonnull
	private static String decompile(@Nonnull String decompilerName, @Nonnull Workspace workspace, @Nonnull String className) throws Exception {
		JvmDecompiler decompiler = decompilers.getJvmDecompiler(decompilerName);
		assertNotNull(decompiler);
		JvmClassInfo info = workspace.getPrimaryResource().getJvmClassBundle().get(className);
		DecompileResult result = decompilers.decompile(decompiler, workspace, info).get(60, TimeUnit.SECONDS);
		assertEquals(DecompileResult.ResultType.SUCCESS, result.getType(), "Decompile failed: " + result.getException());
		assertNotNull(result.getText());
		assertFalse(result.getText().contains("RecafPluginNote"), "Marker leaked:\n" + result.getText());
		return result.getText();
	}

	@ParameterizedTest
	@ValueSource(strings = {CfrDecompiler.NAME, ProcyonDecompiler.NAME, VineflowerDecompiler.NAME})
	void commentsAndParameterNamesAppearInEveryDecompiler(String decompiler) throws Exception {
		Workspace workspace = open(false);

		String handler = decompile(decompiler, workspace, "a/b");
		assertTrue(handler.contains("Event handler: PlayerJoinEvent (priority HIGH)"), handler);
		assertTrue(handler.matches("(?s).*\\(\\s*(final )?PlayerJoinEvent event\\s*\\).*"), handler);

		String command = decompile(decompiler, workspace, "a/c");
		assertTrue(command.contains("Command executor"), command);
		assertTrue(command.contains("sender") && command.contains("label") && command.contains("args"), command);
	}

	@ParameterizedTest
	@ValueSource(strings = {CfrDecompiler.NAME, ProcyonDecompiler.NAME, VineflowerDecompiler.NAME})
	void turnedOffFeaturesLeaveTheOutputPlain(String decompiler) throws Exception {
		Workspace workspace = open(false);
		config.getAnnotateStructure().setValue(false);
		config.getNameParameters().setValue(false);

		String handler = decompile(decompiler, workspace, "a/b");
		assertFalse(handler.contains("Event handler:"), handler);
		assertFalse(handler.contains("PlayerJoinEvent event"), handler);
	}

	@ParameterizedTest
	@ValueSource(strings = {CfrDecompiler.NAME, ProcyonDecompiler.NAME, VineflowerDecompiler.NAME})
	void classesNestedInTheDecompiledClassGetTheViewToo(String decompiler) throws Exception {
		Workspace workspace = open(false);

		String outer = decompile(decompiler, workspace, "a/g");

		assertTrue(outer.contains("Event handler: PlayerJoinEvent"), outer);
		assertTrue(outer.matches("(?s).*\\(\\s*(final )?PlayerJoinEvent event\\s*\\).*"), outer);
	}

	@ParameterizedTest
	@ValueSource(strings = {CfrDecompiler.NAME, ProcyonDecompiler.NAME, VineflowerDecompiler.NAME})
	void aParameterThatIsWrittenToIsNotNamed(String decompiler) throws Exception {
		Workspace workspace = open(false);

		String text = decompile(decompiler, workspace, "a/g");

		assertTrue(text.matches("(?s).*void k\\((final )?a plugin\\).*"), text);
		assertFalse(text.matches("(?s).*void j\\((final )?a plugin\\).*"), text);
	}

	@ParameterizedTest
	@ValueSource(strings = {CfrDecompiler.NAME, ProcyonDecompiler.NAME, VineflowerDecompiler.NAME})
	void securityFindingsAreCommentedOnceTheReportExists(String decompiler) throws Exception {
		Workspace workspace = open(false);
		assertFalse(decompile(decompiler, workspace, "a/g").contains("SECURITY"), "No report yet, so nothing to show");

		recaf.get(PluginSecurityService.class).scanAsync(workspace).join();

		String text = decompile(decompiler, workspace, "a/g");
		assertTrue(text.contains("SECURITY HIGH"), text);
		config.getAnnotateSecurity().setValue(false);
		assertFalse(decompile(decompiler, workspace, "a/g").contains("SECURITY"));
	}

	@Test
	void togglingAFeatureDropsTheCachedDecompilation() throws Exception {
		Workspace workspace = open(false);
		config.getAnnotateStructure().setValue(false);
		assertFalse(decompile(CfrDecompiler.NAME, workspace, "a/b").contains("Event handler:"));

		config.getAnnotateStructure().setValue(true);

		assertTrue(decompile(CfrDecompiler.NAME, workspace, "a/b").contains("Event handler:"));
	}

	@Test
	void refreshListenersAreToldWhichClassesChanged() {
		Workspace workspace = open(false);
		Map<String, Boolean> seen = new ConcurrentHashMap<>();
		var listener = (java.util.function.Consumer<PluginViewService.ViewRefresh>) refresh -> {
			if (refresh.workspace() == workspace && refresh.classNames() != null)
				refresh.classNames().forEach(name -> seen.put(name, true));
		};
		service.addRefreshListener(listener);
		try {
			config.getAnnotateStructure().setValue(false);
			config.getAnnotateStructure().setValue(true);
		} finally {
			service.removeRefreshListener(listener);
		}
		assertTrue(seen.containsKey("a/b"));
		assertTrue(seen.containsKey("a/c"));
	}

	@Test
	void classesOutsideTheCurrentPluginAreLeftAlone() throws Exception {
		open(false);
		Workspace stranger = stripped();

		String text = decompile(CfrDecompiler.NAME, stranger, "a/b");

		assertFalse(text.contains("Event handler:"), text);
		assertFalse(text.contains("PlayerJoinEvent event"), text);
	}

	@Test
	void deobfuscatedViewDecompilesATransformedCopyAndLeavesTheWorkspaceAlone() throws Exception {
		Workspace workspace = open(true);
		JvmClassInfo original = workspace.getPrimaryResource().getJvmClassBundle().get("a/z");
		assertTrue(uses(original.getBytecode(), "deadjunk"));
		Map<String, byte[]> seen = new ConcurrentHashMap<>();
		JvmBytecodeFilter probe = (ws, info, bytecode) -> {
			seen.put(info.getName(), bytecode);
			return bytecode;
		};
		decompilers.addJvmBytecodeFilter(probe);
		try {
			decompile(CfrDecompiler.NAME, workspace, "a/z");
			assertTrue(uses(seen.get("a/z"), "deadjunk"), "Raw view by default");

			config.getDeobfuscatedView().setValue(true);
			awaitReady(workspace);
			decompile(CfrDecompiler.NAME, workspace, "a/z");
			assertFalse(uses(seen.get("a/z"), "deadjunk"), "Transformed copy should be decompiled");
			assertSame(original, workspace.getPrimaryResource().getJvmClassBundle().get("a/z"), "Workspace class is untouched");
			assertTrue(uses(workspace.getPrimaryResource().getJvmClassBundle().get("a/z").getBytecode(), "deadjunk"));

			config.getDeobfuscatedView().setValue(false);
			decompile(CfrDecompiler.NAME, workspace, "a/z");
			assertTrue(uses(seen.get("a/z"), "deadjunk"), "Turning the view off restores the raw view");
		} finally {
			decompilers.removeJvmBytecodeFilter(probe);
		}
	}

	@Test
	void aReplacedClassFallsBackToItsOwnCode() throws Exception {
		Workspace workspace = open(true);
		config.getDeobfuscatedView().setValue(true);
		awaitReady(workspace);

		JvmClassInfo replacement = new JvmClassInfoBuilder(deadCodeClass().getBytecode()).build();
		workspace.getPrimaryResource().getJvmClassBundle().put(replacement);
		Map<String, byte[]> seen = new ConcurrentHashMap<>();
		JvmBytecodeFilter probe = (ws, info, bytecode) -> {
			seen.put(info.getName(), bytecode);
			return bytecode;
		};
		decompilers.addJvmBytecodeFilter(probe);
		try {
			decompile(CfrDecompiler.NAME, workspace, "a/z");
			assertTrue(uses(seen.get("a/z"), "deadjunk"), "A snapshot of the old class must not be shown for the new one");
		} finally {
			decompilers.removeJvmBytecodeFilter(probe);
		}
	}

	private static void awaitReady(@Nonnull Workspace workspace) throws InterruptedException {
		for (int i = 0; i < 600; i++) {
			DeobfuscationStatus status = service.getDeobfuscationStatus(workspace);
			if (status.phase() == DeobfuscationStatus.Phase.READY)
				return;
			assertNotEquals(DeobfuscationStatus.Phase.FAILED, status.phase());
			Thread.sleep(100);
		}
		fail("Deobfuscated view did not finish");
	}

	private static boolean uses(byte[] bytecode, @Nonnull String text) {
		ClassNode node = new ClassNode();
		new ClassReader(bytecode).accept(node, 0);
		return node.methods.stream().anyMatch(method -> {
			for (AbstractInsnNode insn : method.instructions)
				if (insn instanceof LdcInsnNode ldc && text.equals(ldc.cst))
					return true;
			return false;
		});
	}
}
