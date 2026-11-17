package software.coley.recaf.ui.pane.analysis;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import software.coley.recaf.services.analysis.flow.ExecutionAnalysis;
import software.coley.recaf.services.analysis.flow.ExecutionAnalysisService;
import software.coley.recaf.services.analysis.flow.FlowGraph.*;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.Lang;

import java.util.*;

/** Sink-first backward slicing, separate from the whole-plugin execution explorer. */
@Dependent
public class InfluenceTracePane extends FlowAnalysisPane {
	private final ListView<Site> sites = new ListView<>();
	private final ListView<Witness> paths = new ListView<>();
	private final TextField search = new TextField();
	private final TextArea evidence = new TextArea();
	private final FlowDiagram diagram = new FlowDiagram();
	private final CheckBox bytecode = new CheckBox(Lang.get("flow.bytecode-steps"));
	private final List<Site> choices = new ArrayList<>();
	private Site requestedSite;
	private CodeLocation requestedMethod;
	private Step selected;
	private Trace trace;

	@Inject
	public InfluenceTracePane(@Nonnull WorkspaceManager workspaces, @Nonnull ExecutionAnalysisService service, @Nonnull Actions actions) {
		super(workspaces, service, actions); setId("influence-trace"); sites.setId("flow-sinks"); paths.setId("flow-paths"); evidence.setId("flow-evidence");
		search.setPromptText(Lang.get("flow.search")); evidence.setEditable(false); evidence.setWrapText(true);
		Button run = new Button(Lang.get("flow.trace-call")); run.setId("flow-run-trace"); run.setOnAction(_ -> traceSelected());
		Button open = new Button(Lang.get("flow.open-method")); open.setOnAction(_ -> {
			if (selected != null) open(selected.method()); else { Site site = sites.getSelectionModel().getSelectedItem(); if (site != null) open(site.method()); }
		});
		Button fit = new Button(Lang.get("flow.fit")); fit.setOnAction(_ -> diagram.fit());
		toolbar(run, fit, open, bytecode);
		VBox browser = new VBox(8, search, new Label(Lang.get("flow.sink-sites")), sites); VBox.setVgrow(sites, Priority.ALWAYS);
		SplitPane right = new SplitPane(diagram, evidence); right.setOrientation(Orientation.VERTICAL); right.setDividerPositions(0.65);
		SplitPane results = new SplitPane(paths, right); results.setDividerPositions(0.27);
		SplitPane content = new SplitPane(browser, results); content.setDividerPositions(0.25); setCenter(content);
		sites.setCellFactory(_ -> new ListCell<>() {
			@Override protected void updateItem(Site item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item + "\n" + Objects.requireNonNullElse(item.sink(), Lang.get("flow.selected-call")) + " [" + item.method().resource() + "]"); }
		});
		paths.setCellFactory(_ -> new ListCell<>() {
			@Override protected void updateItem(Witness item, boolean empty) {
				super.updateItem(item, empty); setText(empty || item == null ? null : item.influence() + "\n" + item.source() +
						(item.steps().stream().anyMatch(Step::assumed) ? "\n" + Lang.get("flow.assumed") : ""));
			}
		});
		sites.getSelectionModel().selectedItemProperty().addListener((_, _, site) -> {
			if (site != null) traceSelected();
			else { cancelPending(); clearTrace(); }
		});
		paths.getSelectionModel().selectedItemProperty().addListener((_, _, path) -> showPath(path));
		bytecode.selectedProperty().addListener((_, _, _) -> showPath(paths.getSelectionModel().getSelectedItem()));
		search.textProperty().addListener((_, _, _) -> filter());
		refresh();
	}

	public void request(Site site) { requestedSite = site; requestedMethod = null; if (analysis != null && current()) choose(); }
	public void request(CodeLocation method) { requestedMethod = method; requestedSite = null; if (analysis != null && current()) choose(); }

	@Override protected void scanned(ExecutionAnalysis result) { if (current()) choose(); }
	private void choose() {
		choices.clear(); choices.addAll(analysis.sinks());
		if (requestedSite != null && !choices.contains(requestedSite) &&
				analysis.calls(requestedSite.method()).stream().anyMatch(c -> c.site().equals(requestedSite))) choices.add(requestedSite);
		if (requestedMethod != null) {
			for (Method method : analysis.methods()) for (Call call : analysis.calls(method.id()))
				if ((method.id().location().equals(requestedMethod) || call.site().target().equals(requestedMethod.toString())) && !choices.contains(call.site())) choices.add(call.site());
		}
		choices.sort(Comparator.comparing(Site::method).thenComparingInt(Site::instruction));
		search.clear(); filter();
		Site match = requestedSite != null ? choices.stream().filter(s -> s.equals(requestedSite)).findFirst().orElse(null) : requestedMethod != null ?
				choices.stream().filter(s -> s.method().location().equals(requestedMethod) || s.target().equals(requestedMethod.toString())).findFirst().orElse(null) : choices.stream().findFirst().orElse(null);
		if (match != null) { sites.getSelectionModel().select(match); sites.scrollTo(match); }
		else status.setText(Lang.get("flow.no-sinks"));
	}
	private void filter() {
		String query = search.getText().toLowerCase(Locale.ROOT);
		sites.getItems().setAll(choices.stream().filter(s -> (s.toString() + s.method()).toLowerCase(Locale.ROOT).contains(query)).toList());
	}
	private void traceSelected() {
		Site sink = sites.getSelectionModel().getSelectedItem();
		if (sink == null || !current()) return;
		ExecutionAnalysis snapshot = analysis; trace = null; selected = null; paths.getItems().clear(); evidence.clear(); diagram.show(List.of(), List.of(), _ -> {});
		background(cancelled -> snapshot.trace(sink, Limits.defaults(), cancelled), result -> {
			if (!current()) return;
			trace = result; paths.getItems().setAll(result.witnesses().stream().sorted(Comparator.comparing(Witness::influence)).toList()); evidence.setText(report(result));
			status.setText(result.witnesses().size() + " " + Lang.get("flow.paths") + " · " + result.visitedStates() + " " + Lang.get("flow.states") +
					" · " + result.gaps().size() + " " + Lang.get("flow.gaps") + (result.truncated() ? " · " + Lang.get("flow.trace-limit") : "") +
					(result.witnesses().isEmpty() ? " · " + Lang.get("flow.no-path") : ""));
			if (!result.witnesses().isEmpty()) paths.getSelectionModel().selectFirst();
		});
	}
	private void showPath(Witness path) {
		if (path == null || !current()) return;
		List<Integer> visible = new ArrayList<>();
		for (int i = 0; i < path.steps().size(); i++) {
			Step step = path.steps().get(i);
			String opcode = operation(step).split(" ", 2)[0];
			boolean copy = Set.of("ILOAD", "LLOAD", "FLOAD", "DLOAD", "ALOAD", "ISTORE", "LSTORE", "FSTORE", "DSTORE", "ASTORE",
					"DUP", "DUP2", "DUP_X1", "DUP_X2", "DUP2_X1", "DUP2_X2", "SWAP", "CHECKCAST").contains(opcode);
			if (bytecode.isSelected() || !copy || i == 0 || i == path.steps().size() - 1) visible.add(i);
		}
		List<FlowDiagram.Vertex> vertices = new ArrayList<>(); List<FlowDiagram.Link> links = new ArrayList<>();
		for (int i = 0; i < visible.size(); i++) {
			Step step = path.steps().get(visible.get(i)); String key = Integer.toString(i);
			String operation = operation(step);
			if (operation.startsWith("INVOKE") && operation.contains("(")) {
				String target = operation.substring(operation.indexOf(' ') + 1, operation.indexOf('('));
				operation = target.substring(target.lastIndexOf('/') + 1) + "()";
			}
			vertices.add(new FlowDiagram.Vertex(key, step.method().location().display() + (step.instruction() < 0 ? "" : " @" + step.instruction()) + "\n" + operation,
					step.method() + "\n" + step.edge() + "\n" + step.description(), i, i == 0 || i == visible.size() - 1, step));
			if (i > 0) {
				List<Step> segment = path.steps().subList(visible.get(i - 1), visible.get(i));
				String kinds = String.join(" / ", segment.stream().map(s -> s.edge().toString()).distinct().toList());
				String explanation = String.join("\n", segment.stream().map(Step::description).toList());
				links.add(new FlowDiagram.Link(Integer.toString(i - 1), key, kinds, segment.stream().anyMatch(Step::assumed), explanation));
			}
		}
		diagram.show(vertices, links, vertex -> {
			selected = (Step) vertex.value(); evidence.setText(selected.method() + "\nInstruction: " + selected.instruction() + "\n" + selected.description() + "\n\n" + report(trace));
		});
	}
	private static String operation(Step step) {
		int separator = step.description().lastIndexOf(" · ");
		return separator < 0 ? step.description() : step.description().substring(separator + 3);
	}
	private String report(Trace result) {
		StringBuilder text = new StringBuilder(Lang.get("flow.report-note")).append("\n\n").append(result.sink()).append("\n\n");
		text.append(Lang.get("flow.conditions")).append('\n');
		for (Step guard : result.conditions()) text.append(guard.method()).append(" @").append(guard.instruction()).append(": ").append(guard.description()).append('\n');
		text.append("\n").append(Lang.get("flow.coverage")).append('\n');
		for (String gap : result.gaps()) text.append("• ").append(gap).append('\n');
		for (Witness witness : result.witnesses()) {
			text.append("\n").append(witness.influence()).append(": ").append(witness.source()).append('\n');
			for (Step step : witness.steps()) text.append(step.assumed() ? "~ " : "  ").append(step.edge()).append(" · ").append(step.method()).append(" @")
					.append(step.instruction()).append(" · ").append(step.description()).append('\n');
		}
		return text.toString();
	}
	@Override protected void clearView() {
		choices.clear(); sites.getItems().clear(); clearTrace();
	}
	private void clearTrace() {
		paths.getItems().clear(); evidence.clear(); trace = null; selected = null; diagram.show(List.of(), List.of(), _ -> {});
	}
}
