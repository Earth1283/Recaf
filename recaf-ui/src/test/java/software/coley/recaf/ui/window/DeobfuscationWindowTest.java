package software.coley.recaf.ui.window;

import jakarta.enterprise.inject.Instance;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.config.ConfigComponentManager;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
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
import software.coley.recaf.ui.control.richtext.search.SearchBar;
import software.coley.recaf.ui.pane.editing.text.TextConfig;
import software.coley.recaf.util.Lang;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

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
	@SuppressWarnings("unchecked")
	void previewOnlyNeverCommitsAndCanBeSwitchedBackToWorkspace() throws Exception {
		assumeTrue(fxAvailable, "JavaFX display unavailable");
		TransformationApplierService service = mock(TransformationApplierService.class);
		TransformationApplier applier = mock(TransformationApplier.class);
		JvmTransformResult result = mock(JvmTransformResult.class);
		when(service.newApplierForCurrentWorkspace()).thenReturn(applier);
		when(applier.transformJvm(anyList(), any(TransformationParameters.class), any(TransformationFeedback.class)))
				.thenReturn(result);
		when(result.getTransformedClasses()).thenReturn(Map.of());
		when(result.getTransformerFailures()).thenReturn(Map.of());
		DecompilerManager decompiler = mock(DecompilerManager.class);
		when(decompiler.decompile(any(), any(JvmClassInfo.class)))
				.thenReturn(CompletableFuture.completedFuture(new DecompileResult("// preview")));
		Instance<SearchBar> searchBars = mock(Instance.class);
		when(searchBars.get()).thenAnswer(_ -> mock(SearchBar.class));
		TransformationManager manager = new TransformationManager(Map.of(BitwiseFoldingTransformer.class,
				() -> new BitwiseFoldingTransformer(mock(InheritanceGraphService.class))));
		DeobfuscationWindow window = onFx(() -> new DeobfuscationWindow(manager, service,
				mock(WorkspaceManager.class), decompiler, mock(AssemblerPipelineManager.class),
				mock(ConfigComponentManager.class), mock(TransformationPresetManager.class),
				mock(FileTypeSyntaxAssociationService.class), mock(CellConfigurationService.class),
				mock(Actions.class), new TextConfig(), mock(WorkspaceExplorerConfig.class), searchBars));
		try {
			onFx(() -> {
				window.applyPreset(new TransformationPreset(List.of(new TransformationPreset.TransformerPreset(
						BitwiseFoldingTransformer.class.getName(), Map.of())), 5, false,
						TransformationPreset.ScopeMode.NONE, List.of(), true));
				Button run = runButton(window, "deobf.preview.run");
				assertTrue(run.isDisabled(), "Preview mode requires a selected class");
				CheckBox constants = checkBox(window, "deobf." + BitwiseFoldingTransformer.KEY_CONSTANT_FOLDING);
				CheckBox repeat = checkBox(window, "deobf." + BitwiseFoldingTransformer.KEY_REPEAT_BITWISE);
				assertFalse(constants.isSelected());
				assertFalse(repeat.isSelected());
				assertTrue(repeat.isDisabled(), "The final bitwise pass follows optional constant folding");
				constants.fire();
				assertFalse(repeat.isDisabled());
				repeat.fire();
				window.setPreviewClass(TestClassUtils.createClass("Example", null));
				assertFalse(run.isDisabled());
				run.fire();
				return null;
			});
			verify(applier, timeout(5000).atLeastOnce()).transformJvm(anyList(), argThat(parameters ->
					parameters.getBoolean(BitwiseFoldingTransformer.KEY_CONSTANT_FOLDING, false)
							&& parameters.getBoolean(BitwiseFoldingTransformer.KEY_REPEAT_BITWISE, false)), any(TransformationFeedback.class));
			verify(result, never()).apply();

			onFx(() -> {
				CheckBox previewOnly = checkBox(window, "deobf.preview.only");
				assertTrue(previewOnly.isSelected(), "The preset should restore preview-only mode");
				previewOnly.fire();
				runButton(window, "deobf.apply").fire();
				return null;
			});
			verify(result, timeout(5000).times(1)).apply();
		} finally {
			onFx(() -> {
				window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_HIDDEN));
				return null;
			});
		}
	}

	private static CheckBox checkBox(DeobfuscationWindow window, String textKey) {
		return window.getScene().getRoot().lookupAll(".check-box").stream()
				.map(CheckBox.class::cast).filter(box -> Lang.get(textKey).equals(box.getText()))
				.findFirst().orElseThrow();
	}

	private static Button runButton(DeobfuscationWindow window, String textKey) {
		return window.getScene().getRoot().lookupAll(".button").stream()
				.map(Button.class::cast).filter(button -> button.getText().equals(Lang.get(textKey)))
				.findFirst().orElseThrow();
	}

	private static <T> T onFx(Callable<T> action) throws Exception {
		FutureTask<T> task = new FutureTask<>(action);
		Platform.runLater(task);
		return task.get(10, TimeUnit.SECONDS);
	}
}
