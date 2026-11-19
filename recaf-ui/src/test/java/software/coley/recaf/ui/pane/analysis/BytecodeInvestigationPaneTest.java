package software.coley.recaf.ui.pane.analysis;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.services.analysis.bytecode.*;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationReport.*;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.ui.BaseFxTest;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.*;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.objectweb.asm.Opcodes.*;

@Execution(ExecutionMode.SAME_THREAD)
class BytecodeInvestigationPaneTest extends BaseFxTest {
	private static boolean fxAvailable;

	@BeforeAll
	static void startFx() throws Exception {
		Lang.initialize();
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			fxAvailable = started.await(10, TimeUnit.SECONDS);
		} catch (IllegalStateException alreadyStarted) { fxAvailable = true; }
		catch (UnsupportedOperationException noDisplay) { fxAvailable = false; }
		if (fxAvailable) Platform.setImplicitExit(false);
	}

	@Test
	void inspectsAndEvaluatesCallsWithNavigationAndClearsHiddenSelection() throws Exception {
		try (Fixture fixture = new Fixture()) {
			await(() -> onFx(() -> fixture.table(3).getItems().size() == 1));
			onFx(() -> {
				fixture.tabs().getSelectionModel().select(3);
				fixture.table(3).getSelectionModel().selectFirst();
				fixture.button("open-target").fire();
				fixture.button("evaluate").fire();
				return null;
			});
			verify(fixture.actions).gotoDeclaration(argThat((software.coley.recaf.path.ClassMemberPathNode path) -> path.getValue().getName().equals("decode")));
			await(() -> onFx(() -> fixture.pane.lookupAll(".text-area").stream().map(node -> ((TextArea) node).getText()).anyMatch(text -> text.contains("\"readable\""))));
			assertSame(fixture.original, fixture.workspace.getPrimaryResource().getJvmClassBundle().get("Fixture"));
			onFx(() -> {
				((TextField) fixture.pane.lookup("#investigate-search")).setText("missing method");
				assertTrue(fixture.table(3).getItems().isEmpty());
				assertTrue(fixture.button("evaluate").isDisabled());
				assertTrue(fixture.button("open-target").isDisabled());
				return null;
			});
		}
	}

	@Test
	void opensTheCompletedCopyAndItsReport() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Workspace copy = new BasicWorkspace(new WorkspaceResourceBuilder().build());
			copy.getPrimaryResource().getFileBundle().put(new FileInfoBuilder<>().withName("RECAF-READABILITY.txt").withRawContent("report".getBytes()).build());
			var result = new ReadableWorkspaceService.Result(copy, "report", Map.of(), 1, 0, 1, 0);
			when(fixture.readability.build(same(fixture.workspace), any(), eq(100000), eq(true), any(), any())).thenReturn(result);
			when(fixture.workspaces.setCurrent(copy)).thenReturn(true);
			onFx(() -> { fixture.button("readable-copy").fire(); return null; });
			verify(fixture.workspaces, timeout(5000)).setCurrent(copy);
			verify(fixture.actions, timeout(5000)).gotoDeclaration(copy.findFile("RECAF-READABILITY.txt"));
			copy.close();
		}
	}

	@Test
	void closingPaneDiscardsACompletedCopyInsteadOfSwitchingWorkspaces() throws Exception {
		try (Fixture fixture = new Fixture()) {
			Workspace copy = mock(Workspace.class);
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			when(fixture.readability.build(any(), any(), anyInt(), anyBoolean(), any(), any())).thenAnswer(_ -> {
				entered.countDown();
				// Simulate a provider completing after cancellation despite interruption.
				while (true) { try { release.await(); break; } catch (InterruptedException ignored) {} }
				return new ReadableWorkspaceService.Result(copy, "report", Map.of(), 0, 0, 0, 0);
			});
			onFx(() -> { fixture.button("readable-copy").fire(); return null; });
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			onFx(() -> { fixture.pane.disable(); return null; });
			release.countDown();
			verify(copy, timeout(5000)).close();
			verify(fixture.workspaces, never()).setCurrent(any());
		}
	}

	private static class Fixture implements AutoCloseable {
		final WorkspaceManager workspaces = mock(WorkspaceManager.class);
		final Actions actions = mock(Actions.class);
		final ReadableWorkspaceService readability = mock(ReadableWorkspaceService.class);
		final ObfuscatorFingerprinting fingerprinting = spy(new ObfuscatorFingerprinting());
		final ObfuscationTechnologyDetector technologyDetector = spy(new ObfuscationTechnologyDetector());
		final JvmClassInfo original = TestClassUtils.createClass("Fixture", node -> {
			var helper = node.visitMethod(ACC_PRIVATE | ACC_STATIC, "decode", "(Ljava/lang/String;B)Ljava/lang/String;", null, null);
			helper.visitVarInsn(ALOAD, 0); helper.visitInsn(ARETURN); helper.visitMaxs(1, 2); helper.visitEnd();
			var caller = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()Ljava/lang/String;", null, null);
			caller.visitLdcInsn("readable"); caller.visitInsn(ICONST_1);
			caller.visitMethodInsn(INVOKESTATIC, "Fixture", "decode", "(Ljava/lang/String;B)Ljava/lang/String;", false);
			caller.visitInsn(ARETURN); caller.visitMaxs(2, 0); caller.visitEnd();
			node.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "nativeSeed", "(Ljava/lang/String;)I", null, null).visitEnd();
			var seedCaller = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "usesSeed", "()I", null, null);
			seedCaller.visitLdcInsn("key"); seedCaller.visitMethodInsn(INVOKESTATIC, "Fixture", "nativeSeed", "(Ljava/lang/String;)I", false);
			seedCaller.visitInsn(IRETURN); seedCaller.visitMaxs(1, 0); seedCaller.visitEnd();
		});
		final Workspace workspace;
		final BytecodeInvestigationPane pane;
		Fixture() throws Exception {
			assumeTrue(fxAvailable, "JavaFX display unavailable");
			BasicJvmClassBundle bundle = new BasicJvmClassBundle(); bundle.initialPut(original);
			workspace = new BasicWorkspace(new WorkspaceResourceBuilder().withJvmClassBundle(bundle).build());
			workspace.getPrimaryResource().getFileBundle().put(new FileInfoBuilder<>().withName("lock/driver-macos.skid").withRawContent(new byte[0]).build());
			when(workspaces.getCurrent()).thenReturn(workspace);
			pane = onFx(() -> {
				var view = new BytecodeInvestigationPane(workspaces, new BytecodeInvestigationService(new BytecodeInvestigationConfig()),
						readability, fingerprinting, technologyDetector, actions);
				new Scene(view, 1300, 850); view.applyCss(); view.layout();
				return view;
			});
		}
		Button button(String key) { return (Button) pane.lookup("#investigate-" + key); }
		TabPane tabs() { return (TabPane) ((SplitPane) pane.getCenter()).getItems().getFirst(); }
		TableView<?> table(int index) { return (TableView<?>) tabs().getTabs().get(index).getContent(); }
		@Override public void close() throws Exception { onFx(() -> { pane.disable(); return null; }); workspace.close(); }
	}

	@Test
	void fingerprintingRequiresTheExplicitRunAction() throws Exception {
		try (Fixture fixture = new Fixture()) {
			await(() -> onFx(() -> !fixture.tabs().isDisabled()));
			Tab tab = onFx(() -> {
				Tab fingerprints = fixture.tabs().getTabs().get(5);
				fixture.tabs().getSelectionModel().select(fingerprints);
				fixture.button("rescan").fire();
				return fingerprints;
			});
			await(() -> onFx(() -> !fixture.tabs().isDisabled()));
			verifyNoInteractions(fixture.fingerprinting);
			onFx(() -> {
				((Button) tab.getContent().lookup("#investigate-fingerprint-run")).fire();
				return null;
			});
			TextArea report = onFx(() -> (TextArea) tab.getContent().lookup("#investigate-fingerprint-report"));
			await(() -> onFx(() -> report.getText().contains("Skidfuscator · 35% · Possible match")));
			String output = onFx(report::getText);
			for (var family : ObfuscatorFingerprinting.Family.values()) {
				int score = family == ObfuscatorFingerprinting.Family.SKIDFUSCATOR ? 35 : 0;
				assertTrue(output.contains(family.displayName() + " · " + score + "%"));
			}
			assertTrue(output.contains("lock/driver-macos.skid"));
			verify(fixture.fingerprinting).scan(same(fixture.workspace.getPrimaryResource()), any());
			verifyNoInteractions(fixture.technologyDetector);
			verifyNoInteractions(fixture.readability);
			assertSame(fixture.original, fixture.workspace.getPrimaryResource().getJvmClassBundle().get("Fixture"));
		}
	}

	@Test
	void runsAndCopiesTheSeparateTechniqueReport() throws Exception {
		try (Fixture fixture = new Fixture()) {
			await(() -> onFx(() -> !fixture.tabs().isDisabled()));
			Tab tab = onFx(() -> {
				Tab techniques = fixture.tabs().getTabs().getLast();
				fixture.tabs().getSelectionModel().select(techniques);
				return techniques;
			});
			verifyNoInteractions(fixture.technologyDetector, fixture.fingerprinting);
			TextArea report = onFx(() -> {
				assertEquals("Potential obfuscator technologies used", tab.getText());
				assertFalse(((CheckBox) tab.getContent().lookup("#investigate-technology-control-flow")).isSelected());
				((Button) tab.getContent().lookup("#investigate-technology-run")).fire();
				return (TextArea) tab.getContent().lookup("#investigate-technology-report");
			});
			await(() -> onFx(() -> report.getText().contains("Native code")));
			onFx(() -> {
				assertTrue(report.getText().contains("Fixture.nativeSeed(Ljava/lang/String;)I"));
				assertFalse(report.getText().contains("In-depth control flow"));
				((CheckBox) tab.getContent().lookup("#investigate-technology-control-flow")).setSelected(true);
				((Button) tab.getContent().lookup("#investigate-technology-run")).fire();
				return null;
			});
			await(() -> onFx(() -> report.getText().contains("In-depth control flow ·")));
			onFx(() -> {
				((Button) tab.getContent().lookup("#investigate-technology-copy")).fire();
				assertEquals(report.getText(), Clipboard.getSystemClipboard().getString());
				fixture.button("rescan").fire();
				assertTrue(report.getText().isEmpty());
				return null;
			});
			verify(fixture.technologyDetector).scan(same(fixture.workspace.getPrimaryResource()), any());
			verify(fixture.technologyDetector).scan(same(fixture.workspace.getPrimaryResource()), eq(true), any());
			verifyNoInteractions(fixture.fingerprinting, fixture.readability);
		}
	}

	@Test
	void inspectsSelectedMethodAndChangesTheSliceSink() throws Exception {
		try (Fixture fixture = new Fixture()) {
			await(() -> onFx(() -> fixture.table(3).getItems().size() == 1));
			TextArea details = onFx(() -> {
				fixture.tabs().getSelectionModel().select(3);
				fixture.table(3).getSelectionModel().selectFirst();
				fixture.button("method-analysis").fire();
				return (TextArea) fixture.pane.lookup("#bytecode-investigation-details");
			});
			await(() -> onFx(() -> details.getText().contains("@ 2  invokestatic")));
			onFx(() -> {
				assertTrue(details.getText().contains("Definitions consumed  [@0, @1]"));
				assertTrue(details.getText().contains("May exit through an uncaught exception"));
				Spinner<?> instruction = (Spinner<?>) fixture.pane.lookup("#investigate-method-instruction");
				instruction.increment();
				return null;
			});
			await(() -> onFx(() -> details.getText().contains("@ 3  areturn")));
			assertTrue(onFx(() -> details.getText().contains("Definitions consumed  [@2]")));
			assertSame(fixture.original, fixture.workspace.getPrimaryResource().getJvmClassBundle().get("Fixture"));
		}
	}

	@Test
	void displaysNativeCallersAndLibrariesWithNavigationAndFiltering() throws Exception {
		try (Fixture fixture = new Fixture()) {
			await(() -> onFx(() -> fixture.table(4).getItems().size() == 1));
			onFx(() -> {
				fixture.tabs().getSelectionModel().select(4);
				fixture.table(4).getSelectionModel().selectFirst();
				String details = ((TextArea) fixture.pane.lookup("#bytecode-investigation-details")).getText();
				assertTrue(details.contains("nativeSeed"));
				assertTrue(details.contains("usesSeed()I @ 1"));
				assertTrue(details.contains("lock/driver-macos.skid"));
				assertTrue(fixture.button("evaluate").isDisabled());
				fixture.button("open-target").fire();
				TextField search = (TextField) fixture.pane.lookup("#investigate-search");
				search.setText("usesSeed");
				assertEquals(1, fixture.table(4).getItems().size());
				search.setText("missing method");
				assertTrue(fixture.table(4).getItems().isEmpty());
				assertTrue(fixture.button("open-target").isDisabled());
				return null;
			});
			verify(fixture.actions).gotoDeclaration(argThat((software.coley.recaf.path.ClassMemberPathNode path) -> path.getValue().getName().equals("nativeSeed")));
			assertSame(fixture.original, fixture.workspace.getPrimaryResource().getJvmClassBundle().get("Fixture"));
		}
	}

	private static <T> T onFx(Callable<T> action) throws Exception {
		if (Platform.isFxApplicationThread()) return action.call();
		FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
	}

	private static void await(Callable<Boolean> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) { if (condition.call()) return; Thread.sleep(20); }
		fail("Timed out waiting for investigation result");
	}
}
