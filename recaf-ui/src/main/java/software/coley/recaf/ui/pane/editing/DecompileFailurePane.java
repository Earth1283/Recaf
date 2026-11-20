package software.coley.recaf.ui.pane.editing;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.decompile.DecompileDiagnostic;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.fallback.MethodBytecodeReport;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.util.EscapeUtil;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.StringUtil;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Method-level inspection of the class whose decompilation failed.
 */
final class DecompileFailurePane extends BorderPane {
	private static final ExecutorService inspectionPool = ThreadPoolFactory.newFixedThreadPool("decompile-inspection", 2, true);
	private final ComboBox<MethodTarget> methods = new ComboBox<>();
	private final TabPane tabs = new TabPane();
	private final List<CodeArea> areas = List.of(new CodeArea(), new CodeArea(), new CodeArea(), new CodeArea());
	private final String decompilerDiagnostics;
	private Future<?> inspection;
	private long inspectionId;
	private String inspectionStatus = "";
	private MethodBytecodeReport report;

	DecompileFailurePane(JvmClassInfo info, Workspace workspace, JvmDecompiler decompiler, DecompileResult result) {
		getStyleClass().add("background");
		decompilerDiagnostics = diagnostics(info, decompiler, result);
		String[] names = {"instructions", "control-flow", "exception-regions", "diagnostics"};
		for (int i = 0; i < names.length; i++) {
			CodeArea area = areas.get(i);
			area.setEditable(false);
			Tab tab = new Tab();
			tab.textProperty().bind(Lang.getBinding("java.decompile-inspection." + names[i]));
			tab.setContent(new VirtualizedScrollPane<>(area));
			tabs.getTabs().add(tab);
		}
		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tabs.getSelectionModel().selectedIndexProperty().addListener((ob, old, cur) -> updateTab());

		LinkedHashSet<MethodTarget> targets = new LinkedHashSet<>();
		for (DecompileDiagnostic diagnostic : result.getDiagnostics()) {
			var method = diagnostic.method();
			if (method == null)
				continue;
			var ownerPath = info.getName().equals(method.owner()) ? null : workspace.findJvmClass(false, method.owner());
			JvmClassInfo owner = ownerPath == null ? info : ownerPath.getValue().asJvmClass();
			if (method.owner().equals(owner.getName())) {
				MethodMember member = owner.getDeclaredMethod(method.name(), method.descriptor());
				if (member != null)
					targets.add(new MethodTarget(owner, member));
			}
		}
		info.getMethods().forEach(method -> targets.add(new MethodTarget(info, method)));
		methods.getItems().setAll(targets);
		methods.setMaxWidth(Double.MAX_VALUE);
		methods.setDisable(targets.isEmpty());
		Label label = new BoundLabel(Lang.getBinding("java.decompile-inspection.method"));
		label.setLabelFor(methods);
		HBox controls = new HBox(8, label, methods);
		controls.setAlignment(Pos.CENTER_LEFT);
		controls.setPadding(new Insets(6, 8, 6, 8));
		HBox.setHgrow(methods, Priority.ALWAYS);
		setTop(controls);
		setCenter(tabs);

		methods.valueProperty().addListener((ob, old, cur) -> inspect(cur));
		methods.getSelectionModel().selectFirst();
		if (targets.isEmpty())
			inspect(null);
	}

	void select(MethodMember method) {
		var owner = method.getDeclaringClass();
		for (MethodTarget target : methods.getItems()) {
			if (target.method() == method || owner != null && target.owner().getName().equals(owner.getName())
					&& target.method().getName().equals(method.getName())
					&& target.method().getDescriptor().equals(method.getDescriptor())) {
				methods.setValue(target);
				break;
			}
		}
	}

	@Override
	public void requestFocus() {
		int index = tabs.getSelectionModel().getSelectedIndex();
		if (index >= 0)
			areas.get(index).requestFocus();
	}

	void cancelInspection() {
		inspectionId++;
		if (inspection != null)
			inspection.cancel(true);
	}

	private void inspect(MethodTarget target) {
		cancelInspection();
		report = null;
		inspectionStatus = Lang.get("java.decompile-inspection." + (target == null ? "no-methods" : "loading"));
		updateTab();
		if (target == null)
			return;

		long request = inspectionId;
		inspection = inspectionPool.submit(() -> {
			try {
				MethodBytecodeReport inspected = MethodBytecodeReport.inspect(target.owner(), target.method());
				FxThreadUtil.run(() -> {
					if (request == inspectionId) {
						report = inspected;
						updateTab();
					}
				});
			} catch (RuntimeException ex) {
				FxThreadUtil.run(() -> {
					if (request == inspectionId) {
						inspectionStatus = Lang.get("java.decompile-inspection.unavailable") + "\n\n" + StringUtil.traceToString(ex);
						updateTab();
					}
				});
			}
		});
	}

	private void updateTab() {
		int index = tabs.getSelectionModel().getSelectedIndex();
		if (index < 0)
			return;
		String text = switch (index) {
			case 0 -> report == null ? inspectionStatus : report.instructions();
			case 1 -> report == null ? inspectionStatus : report.controlFlow();
			case 2 -> report == null ? inspectionStatus : report.exceptionRegions();
			default -> Lang.get("java.decompile-inspection.analysis") + "\n\n"
					+ (report == null ? inspectionStatus : report.diagnostics()) + "\n\n"
					+ Lang.get("java.decompile-inspection.decompiler-diagnostics") + "\n\n" + decompilerDiagnostics;
		};
		if (text.isEmpty())
			text = Lang.get(index == 2 ? "java.decompile-inspection.no-regions" : "java.decompile-inspection.no-code");
		CodeArea area = areas.get(index);
		if (!text.equals(area.getText())) {
			area.replaceText(text);
			area.getUndoManager().forgetHistory();
			area.moveTo(0);
			area.showParagraphAtTop(0);
		}
	}

	private static String diagnostics(JvmClassInfo info, JvmDecompiler decompiler, DecompileResult result) {
		StringBuilder text = new StringBuilder(decompiler.getName()).append(' ').append(decompiler.getVersion())
				.append('\n').append(EscapeUtil.escapeStandard(info.getName()));
		if (result.getException() != null)
			text.append("\n\n").append(StringUtil.traceToString(result.getException()).stripTrailing());
		for (DecompileDiagnostic diagnostic : result.getDiagnostics()) {
			text.append("\n\n").append(diagnostic.message());
			if (diagnostic.cause() != null)
				text.append('\n').append(StringUtil.traceToString(diagnostic.cause()).stripTrailing());
		}
		return text.toString();
	}

	private record MethodTarget(JvmClassInfo owner, MethodMember method) {
		@Override
		public String toString() {
			return EscapeUtil.escapeStandard(owner.getName() + "." + method.getName() + method.getDescriptor());
		}
	}
}
