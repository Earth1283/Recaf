package software.coley.recaf.ui.window;

import jakarta.enterprise.inject.Instance;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TabPane;
import javafx.stage.WindowEvent;
import me.darknet.assembler.error.Outcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.assembler.JvmAssemblerPipeline;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.config.ConfigComponentManager;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.DecompilerConfig;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.deobfuscation.transform.generic.BitwiseFoldingTransformer;
import software.coley.recaf.services.info.association.FileTypeSyntaxAssociationService;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.transform.TransformationParameters;
import software.coley.recaf.services.transform.TransformationPreset;
import software.coley.recaf.services.transform.TransformationPresetManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.ui.BaseFxTest;
import software.coley.recaf.ui.config.WorkspaceExplorerConfig;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.search.SearchBar;
import software.coley.recaf.ui.pane.editing.text.TextConfig;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.objectweb.asm.Opcodes.ACC_FINAL;

@Execution(ExecutionMode.SAME_THREAD)
class DeobfuscationWindowTest extends BaseFxTest {
	private static boolean fxAvailable;

	@BeforeAll
	static void startFx() throws InterruptedException {
		Lang.initialize();
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			fxAvailable = started.await(10, TimeUnit.SECONDS);
		} catch (IllegalStateException alreadyRunning) {
			fxAvailable = true;
		} catch (UnsupportedOperationException noDisplay) {
			fxAvailable = false;
		}
		if (fxAvailable)
			Platform.setImplicitExit(false);
	}

	@Test
	void previewOnlyNeverCommitsAndCanBeSwitchedBackToWorkspace() throws Exception {
		try (Fixture fixture = new Fixture()) {
			onFx(() -> {
				Button run = runButton(fixture.window, "deobf.preview.run");
				assertTrue(run.isDisabled(), "Preview mode requires a selected class");
				CheckBox constants = checkBox(fixture.window, "deobf." + BitwiseFoldingTransformer.KEY_CONSTANT_FOLDING);
				CheckBox repeat = checkBox(fixture.window, "deobf." + BitwiseFoldingTransformer.KEY_REPEAT_BITWISE);
				assertFalse(constants.isSelected());
				assertFalse(repeat.isSelected());
				assertTrue(repeat.isDisabled());
				constants.fire();
				assertFalse(repeat.isDisabled());
				repeat.fire();
				fixture.window.setPreviewClass(fixture.original);
				assertFalse(run.isDisabled());
				run.fire();
				return null;
			});
			verify(fixture.applier, timeout(5000).atLeastOnce()).transformJvm(anyList(), argThat(parameters ->
					parameters.getBoolean(BitwiseFoldingTransformer.KEY_CONSTANT_FOLDING, false)
							&& parameters.getBoolean(BitwiseFoldingTransformer.KEY_REPEAT_BITWISE, false)), any(TransformationFeedback.class));
			verify(fixture.result, never()).apply();
			onFx(() -> {
				checkBox(fixture.window, "deobf.preview.only").fire();
				runButton(fixture.window, "deobf.apply").fire();
				return null;
			});
			verify(fixture.result, timeout(5000).times(1)).apply();
		}
	}

	@Test
	void sourceAndBytecodeDiffReuseTheTransformationAndRenderedText() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.selectAndOpenDiff();
			await(() -> fixture.diffText().contains("+    int value = -2147483648;"));
			onFx(() -> {
				Editor diff = fixture.diffEditor();
				assertTrue(diff.getText().contains("-    int value = Integer.reverse(1);"));
				int added = diff.getText().indexOf("+    int value");
				assertTrue(diff.getCodeArea().getStyleOfChar(added).contains("preview-diff-add"));
				return null;
			});
			fixture.toggleMode();
			await(() -> fixture.diffText().contains("+ldc -2147483648"));
			assertTrue(fixture.diffText().contains("-invokestatic java/lang/Integer.reverse (I)I"));
			fixture.toggleMode();
			await(() -> fixture.diffText().contains("+    int value = -2147483648;"));
			verify(fixture.applier, times(1)).transformJvm(anyList(), any(TransformationParameters.class), any(TransformationFeedback.class));
			verify(fixture.decompiler, times(2)).decompile(eq(fixture.workspace), any(JvmClassInfo.class));
			verify(fixture.assembler, times(2)).disassemble(any(ClassPathNode.class));
			verify(fixture.result, never()).apply();

			onFx(() -> {
				checkBox(fixture.window, "deobf." + BitwiseFoldingTransformer.KEY_CONSTANT_FOLDING).fire();
				return null;
			});
			verify(fixture.applier, timeout(5000).times(2)).transformJvm(anyList(), any(TransformationParameters.class), any(TransformationFeedback.class));
			verify(fixture.decompiler, times(1)).decompile(same(fixture.workspace), same(fixture.original));
		}
	}

	@Test
	void obsoleteDecompilationCannotOverwriteTheLatestProfile() throws Exception {
		try (Fixture fixture = new Fixture()) {
			CompletableFuture<DecompileResult> old = new CompletableFuture<>();
			CountDownLatch started = new CountDownLatch(1);
			AtomicBoolean first = new AtomicBoolean(true);
			when(fixture.decompiler.decompile(same(fixture.workspace), same(fixture.modified))).thenAnswer(_ -> {
				if (first.getAndSet(false)) {
					started.countDown();
					return old;
				}
				return CompletableFuture.completedFuture(new DecompileResult("latest profile"));
			});
			fixture.selectAndOpenDiff();
			assertTrue(started.await(5, TimeUnit.SECONDS));
			onFx(() -> {
				checkBox(fixture.window, "deobf." + BitwiseFoldingTransformer.KEY_CONSTANT_FOLDING).fire();
				checkBox(fixture.window, "deobf." + BitwiseFoldingTransformer.KEY_REPEAT_BITWISE).fire();
				return null;
			});
			// The worker is waiting for one active decompilation, so edits cannot flood the shared pool.
			verify(fixture.decompiler, times(1)).decompile(same(fixture.workspace), same(fixture.modified));
			old.complete(new DecompileResult("obsolete profile"));
			await(() -> fixture.diffText().contains("+latest profile"));
			assertFalse(fixture.diffText().contains("obsolete profile"));
			verify(fixture.decompiler, times(1)).decompile(same(fixture.workspace), same(fixture.original));
			verify(fixture.result, never()).apply();
		}
	}

	@Test
	void changesToOtherWorkspaceClassesInvalidateTheCachedTransformation() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.selectAndOpenDiff();
			await(() -> fixture.diffText().contains("+    int value = -2147483648;"));
			fixture.workspace.getPrimaryResource().getJvmClassBundle().put(TestClassUtils.createClass("Dependency", null));
			fixture.toggleMode();
			await(() -> fixture.diffText().contains("+ldc -2147483648"));
			verify(fixture.applier, times(2)).transformJvm(anyList(), any(TransformationParameters.class), any(TransformationFeedback.class));
		}
	}

	@Test
	void decompilerSettingChangesRefreshTextWithoutRepeatingTransformations() throws Exception {
		try (Fixture fixture = new Fixture()) {
			JvmDecompiler target = mock(JvmDecompiler.class);
			DecompilerConfig config = mock(DecompilerConfig.class);
			AtomicInteger hash = new AtomicInteger(1);
			when(config.getHash()).thenAnswer(_ -> hash.get());
			when(target.getConfig()).thenReturn(config);
			when(fixture.decompiler.getTargetJvmDecompiler()).thenReturn(target);
			when(fixture.decompiler.decompile(eq(target), eq(fixture.workspace), any(JvmClassInfo.class))).thenAnswer(call ->
					CompletableFuture.completedFuture(new DecompileResult(call.getArgument(2) == fixture.modified ?
							"after config " + hash.get() : "before config " + hash.get())));
			fixture.selectAndOpenDiff();
			await(() -> fixture.diffText().contains("+after config 1"));
			fixture.toggleMode();
			await(() -> fixture.diffText().contains("+ldc -2147483648"));
			hash.set(2);
			fixture.toggleMode();
			await(() -> fixture.diffText().contains("+after config 2"));
			assertTrue(fixture.diffText().contains("-before config 2"));
			verify(fixture.applier, times(1)).transformJvm(anyList(), any(TransformationParameters.class), any(TransformationFeedback.class));
			verify(fixture.decompiler, times(4)).decompile(eq(target), eq(fixture.workspace), any(JvmClassInfo.class));
		}
	}

	private static class Fixture implements AutoCloseable {
		final JvmClassInfo original = TestClassUtils.createClass("Example", null);
		final JvmClassInfo modified = TestClassUtils.createClass("Example", node -> node.access |= ACC_FINAL);
		final Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(original));
		final TransformationApplierService service = mock(TransformationApplierService.class);
		final TransformationApplier applier = mock(TransformationApplier.class);
		final JvmTransformResult result = mock(JvmTransformResult.class);
		final DecompilerManager decompiler = mock(DecompilerManager.class);
		final JvmAssemblerPipeline assembler = mock(JvmAssemblerPipeline.class);
		final DeobfuscationWindow window;

		@SuppressWarnings("unchecked")
		Fixture() throws Exception {
			assumeTrue(fxAvailable, "JavaFX display unavailable");
			when(service.newApplierForCurrentWorkspace()).thenReturn(applier);
			when(service.newApplier(workspace)).thenReturn(applier);
			when(applier.transformJvm(anyList(), any(TransformationParameters.class), any(TransformationFeedback.class))).thenReturn(result);
			when(result.getTransformedClasses()).thenReturn(Map.of(workspace.findClass("Example"), modified));
			when(result.getTransformerFailures()).thenReturn(Map.of());
			when(decompiler.decompile(eq(workspace), any(JvmClassInfo.class))).thenAnswer(call ->
					CompletableFuture.completedFuture(new DecompileResult(call.getArgument(1) == modified ?
							"class Example {\n    int value = -2147483648;\n}\n" :
							"class Example {\n    int value = Integer.reverse(1);\n}\n")));
			when(assembler.disassemble(any(ClassPathNode.class))).thenAnswer(call -> Outcome.success(
					((ClassPathNode) call.getArgument(0)).getValue() == modified ? "ldc -2147483648\nireturn" :
							"iconst_1\ninvokestatic java/lang/Integer.reverse (I)I\nireturn"));
			AssemblerPipelineManager assemblerManager = mock(AssemblerPipelineManager.class);
			when(assemblerManager.newJvmAssemblerPipeline(workspace)).thenReturn(assembler);
			WorkspaceManager workspaces = mock(WorkspaceManager.class);
			when(workspaces.getCurrent()).thenReturn(workspace);
			Instance<SearchBar> searchBars = mock(Instance.class);
			when(searchBars.get()).thenAnswer(_ -> mock(SearchBar.class));
			TransformationManager manager = new TransformationManager(Map.of(BitwiseFoldingTransformer.class,
					() -> new BitwiseFoldingTransformer(mock(InheritanceGraphService.class))));
			window = onFx(() -> new DeobfuscationWindow(manager, service, workspaces, decompiler, assemblerManager,
					mock(ConfigComponentManager.class), mock(TransformationPresetManager.class),
					mock(FileTypeSyntaxAssociationService.class), mock(CellConfigurationService.class),
					mock(Actions.class), new TextConfig(), mock(WorkspaceExplorerConfig.class), searchBars));
			onFx(() -> {
				window.applyPreset(new TransformationPreset(List.of(new TransformationPreset.TransformerPreset(
						BitwiseFoldingTransformer.class.getName(), Map.of())), 5, false,
						TransformationPreset.ScopeMode.NONE, List.of(), true));
				return null;
			});
		}

		void selectAndOpenDiff() throws Exception {
			onFx(() -> {
				window.setPreviewClass(original);
				TabPane tabs = (TabPane) window.getScene().getRoot().lookup(".tab-pane");
				tabs.getSelectionModel().select(2);
				return null;
			});
		}

		void toggleMode() throws Exception {
			onFx(() -> {
				runButton(window, "deobf.preview.toggle-mode").fire();
				return null;
			});
		}

		Editor diffEditor() {
			return (Editor) window.getScene().getRoot().lookup("#deobfuscation-diff-editor");
		}

		String diffText() {
			try {
				return onFx(() -> diffEditor().getText());
			} catch (Exception error) {
				throw new AssertionError(error);
			}
		}

		@Override
		public void close() throws Exception {
			onFx(() -> {
				window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_HIDDEN));
				return null;
			});
		}
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "Preview did not become ready");
			Thread.sleep(10);
		}
	}

	private static CheckBox checkBox(DeobfuscationWindow window, String textKey) {
		return window.getScene().getRoot().lookupAll(".check-box").stream()
				.map(CheckBox.class::cast).filter(box -> Lang.get(textKey).equals(box.getText()))
				.findFirst().orElseThrow();
	}

	private static Button runButton(DeobfuscationWindow window, String textKey) {
		return window.getScene().getRoot().lookupAll(".button").stream()
				.map(Button.class::cast).filter(button -> Lang.get(textKey).equals(button.getText()))
				.findFirst().orElseThrow();
	}

	private static <T> T onFx(Callable<T> action) throws Exception {
		FutureTask<T> task = new FutureTask<>(action);
		Platform.runLater(task);
		return task.get(10, TimeUnit.SECONDS);
	}
}
