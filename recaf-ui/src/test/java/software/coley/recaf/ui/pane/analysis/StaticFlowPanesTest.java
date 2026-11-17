package software.coley.recaf.ui.pane.analysis;

import javafx.application.Platform;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.analysis.flow.ExecutionAnalysis;
import software.coley.recaf.services.analysis.flow.ExecutionAnalysisService;
import software.coley.recaf.services.analysis.flow.FlowGraph.*;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.ui.BaseFxTest;
import software.coley.recaf.ui.pane.plugin.PluginNavigation;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.objectweb.asm.Opcodes.*;

@Execution(ExecutionMode.SAME_THREAD)
class StaticFlowPanesTest extends BaseFxTest {
	private static boolean available;
	@BeforeAll static void start() throws Exception {
		Lang.initialize();
		try { CountDownLatch latch = new CountDownLatch(1); Platform.startup(latch::countDown); available = latch.await(10, TimeUnit.SECONDS); }
		catch (IllegalStateException started) { available = true; }
		catch (UnsupportedOperationException noDisplay) { available = false; }
		if (available) Platform.setImplicitExit(false);
		if (available) fx(() -> { Application.setUserAgentStylesheet("/style/recaf.css"); return null; });
	}

	@Test void traceShowsValueEvidenceAndClearsAFilteredSelection() throws Exception {
		try (Fixture fixture = new Fixture()) {
			InfluenceTracePane pane = fx(() -> fixture.trace(new ExecutionAnalysisService()));
			await(() -> fx(() -> !list(pane, "flow-paths").getItems().isEmpty()));
			fx(() -> {
				assertTrue(((TextArea) pane.lookup("#flow-evidence")).getText().contains("Player lookup"));
				assertTrue(list(pane, "flow-paths").getItems().stream().map(item -> (Witness) item).anyMatch(w -> w.influence() == Influence.DATA));
				capture(pane, "influence-trace");
				((TextField) pane.lookup(".text-field")).setText("does not exist");
				assertTrue(list(pane, "flow-sinks").getItems().isEmpty());
				assertTrue(list(pane, "flow-paths").getItems().isEmpty());
				assertEquals("", ((TextArea) pane.lookup("#flow-evidence")).getText());
				return null;
			});
		}
	}

	@Test void executionViewNavigatesAndOpensAnIndependentTraceFeature() throws Exception {
		try (Fixture fixture = new Fixture()) {
			ExecutionExplorerPane pane = fx(() -> fixture.explorer());
			await(() -> fx(() -> !list(pane, "flow-calls").getItems().isEmpty()));
			fx(() -> {
				ListView<Call> calls = list(pane, "flow-calls");
				Call sink = calls.getItems().stream().filter(c -> c.site().sink() != null).findFirst().orElseThrow();
				calls.getSelectionModel().select(sink);
				((Button) pane.lookup("#flow-trace-call")).fire();
				verify(fixture.navigation).openInfluenceTrace(sink.site());
				pane.open(sink.site().method());
				capture(pane, "execution-explorer");
				return null;
			});
			verify(fixture.actions).gotoDeclaration(any(PathNode.class));
			fixture.workspace.getPrimaryResource().getJvmClassBundle().put(TestClassUtils.createClass("Added", null));
			fx(() -> { assertFalse(pane.current()); assertTrue(list(pane, "flow-roots").getItems().isEmpty()); return null; });
		}
	}

	@Test void closingDiscardsAnUncooperativeLateScan() throws Exception {
		try (Fixture fixture = new Fixture()) {
			ExecutionAnalysisService service = mock(ExecutionAnalysisService.class);
			ExecutionAnalysis result = ExecutionAnalysis.scan(fixture.workspace, () -> false);
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
			when(service.analyze(any(), any())).thenAnswer(_ -> {
				entered.countDown();
				while (true) { try { release.await(); break; } catch (InterruptedException ignored) {} }
				completed.countDown(); return result;
			});
			InfluenceTracePane pane = fx(() -> fixture.trace(service));
			try {
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				fx(() -> { pane.disable(); return null; });
			} finally { release.countDown(); }
			assertTrue(completed.await(5, TimeUnit.SECONDS));
			fx(() -> { assertNull(pane.analysis); assertTrue(list(pane, "flow-sinks").getItems().isEmpty()); return null; });
		}
	}

	@Test void replacedScanCannotPublishOverTheNewerResult() throws Exception {
		try (Fixture fixture = new Fixture()) {
			ExecutionAnalysisService service = mock(ExecutionAnalysisService.class);
			ExecutionAnalysis stale = mock(ExecutionAnalysis.class);
			ExecutionAnalysis current = ExecutionAnalysis.scan(fixture.workspace, () -> false);
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			when(service.analyze(any(), any())).thenAnswer(_ -> {
				entered.countDown(); while (true) { try { release.await(); break; } catch (InterruptedException ignored) {} } return stale;
			}).thenReturn(current);
			InfluenceTracePane pane = fx(() -> fixture.trace(service));
			try { assertTrue(entered.await(5, TimeUnit.SECONDS)); fx(() -> { ((Button) pane.lookup("#flow-refresh")).fire(); return null; }); }
			finally { release.countDown(); }
			await(() -> fx(() -> !list(pane, "flow-paths").getItems().isEmpty()));
			fx(() -> { assertSame(current, pane.analysis); return null; });
			verify(stale, never()).sinks();
		}
	}

	private static final class Fixture implements AutoCloseable {
		final WorkspaceManager manager = mock(WorkspaceManager.class);
		final Actions actions = mock(Actions.class);
		final PluginNavigation navigation = mock(PluginNavigation.class);
		final JvmClassInfo original = TestClassUtils.createClass("Plugin", node -> {
			node.superName = "org/java_websocket/client/WebSocketClient";
			var method = node.visitMethod(ACC_PUBLIC, "onMessage", "(Ljava/lang/String;)V", null, null);
			method.visitVarInsn(ALOAD, 1);
			method.visitMethodInsn(INVOKESTATIC, "org/bukkit/Bukkit", "getPlayer", "(Ljava/lang/String;)Lorg/bukkit/entity/Player;", false);
			method.visitInsn(ICONST_1);
			method.visitMethodInsn(INVOKEINTERFACE, "org/bukkit/entity/Player", "setOp", "(Z)V", true);
			method.visitInsn(RETURN); method.visitMaxs(2, 2); method.visitEnd();
		});
		final Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(original));
		FlowAnalysisPane pane;
		Fixture() { assumeTrue(available, "JavaFX display unavailable"); when(manager.getCurrent()).thenReturn(workspace); }
		InfluenceTracePane trace(ExecutionAnalysisService service) { return setup(new InfluenceTracePane(manager, service, actions)); }
		ExecutionExplorerPane explorer() { return setup(new ExecutionExplorerPane(manager, new ExecutionAnalysisService(), actions, navigation)); }
		<T extends FlowAnalysisPane> T setup(T view) {
			pane = view; Scene scene = new Scene(view, 1500, 900);
			scene.getStylesheets().add(StaticFlowPanesTest.class.getResource("/style/recaf.css").toExternalForm());
			view.applyCss(); view.layout(); return view;
		}
		@Override public void close() throws Exception { if (pane != null) fx(() -> { pane.disable(); return null; }); workspace.close(); }
	}

	@SuppressWarnings("unchecked") private static <T> ListView<T> list(FlowAnalysisPane pane, String id) { return (ListView<T>) pane.lookup("#" + id); }
	private static <T> T fx(Callable<T> action) throws Exception {
		if (Platform.isFxApplicationThread()) return action.call();
		FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
	}
	private static void await(Callable<Boolean> condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) { if (condition.call()) return; Thread.sleep(20); }
		fail("Timed out waiting for static flow result");
	}
	private static void capture(FlowAnalysisPane pane, String name) throws Exception {
		if (!"true".equals(System.getenv("RECAF_FLOW_SCREENSHOTS"))) return;
		pane.applyCss(); pane.layout(); WritableImage image = pane.snapshot(null, null);
		int width = (int) image.getWidth(), height = (int) image.getHeight(); int[] pixels = new int[width * height];
		image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
		BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); output.setRGB(0, 0, width, height, pixels, 0, width);
		Path directory = Path.of("build", "screenshots"); Files.createDirectories(directory); ImageIO.write(output, "png", directory.resolve(name + ".png").toFile());
	}
}
