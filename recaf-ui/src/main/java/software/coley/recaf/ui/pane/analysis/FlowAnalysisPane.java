package software.coley.recaf.ui.pane.analysis;

import jakarta.annotation.Nonnull;
import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.analysis.flow.ExecutionAnalysis;
import software.coley.recaf.services.analysis.flow.ExecutionAnalysisService;
import software.coley.recaf.services.analysis.flow.FlowGraph.MethodId;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owns one snapshot and worker; obsolete scans/traces cannot publish into a replaced or closed view. */
abstract class FlowAnalysisPane extends BorderPane implements Navigable {
	protected final Workspace workspace;
	protected final Label status = new Label();
	protected ExecutionAnalysis analysis;
	private final WorkspaceManager workspaces;
	private final ExecutionAnalysisService service;
	private final Actions actions;
	private final ExecutorService worker = ThreadPoolFactory.newSingleThreadExecutor("static-flow");
	private final AtomicLong generation = new AtomicLong();
	private Future<?> pending;
	private volatile boolean disabled;

	FlowAnalysisPane(WorkspaceManager workspaces, ExecutionAnalysisService service, Actions actions) {
		this.workspaces = workspaces; this.service = service; this.actions = actions;
		workspace = Objects.requireNonNull(workspaces.getCurrent());
		setPadding(new Insets(10));
	}

	protected void toolbar(Node... controls) {
		Button refresh = new Button(Lang.get("flow.refresh")); refresh.setId("flow-refresh"); refresh.setOnAction(_ -> refresh());
		HBox bar = new HBox(10, refresh); bar.getChildren().addAll(controls);
		status.setWrapText(true);
		Label legend = new Label(Lang.get("flow.static-note")); legend.setWrapText(true);
		VBox header = new VBox(8, bar, legend, status); header.setPadding(new Insets(0, 0, 10, 0)); setTop(header);
	}

	protected void refresh() {
		if (disabled) return;
		analysis = null; clearView();
		background(cancelled -> service.analyze(workspace, cancelled), result -> { analysis = result; scanned(result); });
	}
	protected abstract void scanned(ExecutionAnalysis result);
	protected abstract void clearView();

	protected boolean current() {
		if (analysis == null) return false;
		if (disabled || workspaces.getCurrent() != workspace || !analysis.isCurrent()) {
			analysis = null; clearView(); status.setText(Lang.get("flow.stale")); return false;
		}
		return true;
	}

	protected <T> void background(Function<BooleanSupplier, T> task, Consumer<T> publish) {
		if (disabled) return;
		long request = generation.incrementAndGet();
		if (pending != null) pending.cancel(true);
		status.setText(Lang.get("flow.working"));
		pending = worker.submit(() -> {
			try {
				T result = task.apply(() -> disabled || generation.get() != request);
				Platform.runLater(() -> {
					if (disabled || generation.get() != request) return;
					if (workspaces.getCurrent() != workspace) { analysis = null; clearView(); status.setText(Lang.get("flow.stale")); return; }
					publish.accept(result);
				});
			} catch (CancellationException ignored) {
			} catch (Exception ex) {
				Platform.runLater(() -> { if (!disabled && generation.get() == request) status.setText(Lang.get("flow.failed") + ": " + ex.getMessage()); });
			}
		});
	}

	protected void cancelPending() {
		generation.incrementAndGet(); if (pending != null) pending.cancel(true);
	}

	protected void open(MethodId id) {
		if (!current()) return;
		var method = analysis.method(id);
		if (method == null) return;
		try { actions.gotoDeclaration(method.path()); }
		catch (Exception ex) { status.setText(Lang.get("flow.failed") + ": " + ex.getMessage()); }
	}

	@Override public boolean isTrackable() { return false; }
	@Nonnull @Override public PathNode<?> getPath() { return PathNodes.workspacePath(workspace); }
	@Nonnull @Override public Collection<Navigable> getNavigableChildren() { return List.of(); }
	@Override @PreDestroy public void disable() {
		disabled = true; cancelPending(); worker.shutdownNow(); analysis = null; setDisable(true);
	}
}
