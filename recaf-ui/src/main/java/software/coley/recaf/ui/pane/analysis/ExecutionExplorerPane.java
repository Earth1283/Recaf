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
import software.coley.recaf.ui.pane.plugin.PluginNavigation;
import software.coley.recaf.util.Lang;

import java.util.*;

/** A separate, expandable static execution map. Graph reachability is never presented as taint evidence. */
@Dependent
public class ExecutionExplorerPane extends FlowAnalysisPane {
	private final ListView<Method> roots = new ListView<>();
	private final ListView<Call> calls = new ListView<>();
	private final TextField search = new TextField();
	private final CheckBox allMethods = new CheckBox(Lang.get("flow.all-methods"));
	private final CheckBox external = new CheckBox(Lang.get("flow.external"));
	private final TextArea detail = new TextArea();
	private final FlowDiagram diagram = new FlowDiagram();
	private final Set<MethodId> expanded = new LinkedHashSet<>();
	private final PluginNavigation navigation;
	private MethodId root, selected;
	private CodeLocation requested;

	@Inject
	public ExecutionExplorerPane(@Nonnull WorkspaceManager workspaces, @Nonnull ExecutionAnalysisService service,
	                             @Nonnull Actions actions, @Nonnull PluginNavigation navigation) {
		super(workspaces, service, actions); this.navigation = navigation;
		setId("execution-explorer"); roots.setId("flow-roots"); calls.setId("flow-calls"); detail.setId("flow-detail");
		search.setPromptText(Lang.get("flow.search"));
		Button collapse = new Button(Lang.get("flow.collapse")); collapse.setOnAction(_ -> { expanded.clear(); if (root != null) expanded.add(root); render(); });
		Button open = new Button(Lang.get("flow.open-method")); open.setOnAction(_ -> { if (selected != null) open(selected); });
		Button fit = new Button(Lang.get("flow.fit")); fit.setOnAction(_ -> diagram.fit());
		Button trace = new Button(Lang.get("flow.trace-call")); trace.setId("flow-trace-call"); trace.setOnAction(_ -> {
			Call call = calls.getSelectionModel().getSelectedItem(); if (call != null && current()) navigation.openInfluenceTrace(call.site());
		});
		toolbar(collapse, fit, open, trace, external);
		VBox browser = new VBox(8, search, allMethods, roots); VBox.setVgrow(roots, Priority.ALWAYS);
		detail.setEditable(false); detail.setWrapText(true);
		SplitPane evidence = new SplitPane(calls, detail); evidence.setDividerPositions(0.45);
		SplitPane graph = new SplitPane(diagram, evidence); graph.setOrientation(Orientation.VERTICAL); graph.setDividerPositions(0.72);
		SplitPane content = new SplitPane(browser, graph); content.setDividerPositions(0.24); setCenter(content);
		roots.setCellFactory(_ -> new ListCell<>() {
			@Override protected void updateItem(Method item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.toString() + (item.entryReason() == null ? "" : "\n" + item.entryReason())); }
		});
		calls.setCellFactory(_ -> new ListCell<>() {
			@Override protected void updateItem(Call item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : "@" + item.site().instruction() + " " + item.kind() + "\n" + item.site().target()); }
		});
		roots.getSelectionModel().selectedItemProperty().addListener((_, _, method) -> { if (method != null && current()) focus(method.id()); });
		calls.getSelectionModel().selectedItemProperty().addListener((_, _, call) -> { if (call != null) detail.setText(call.site() + "\n\n" + call.explanation() + (call.site().sink() == null ? "" : "\n\n" + call.site().sink())); });
		search.textProperty().addListener((_, _, _) -> filter()); allMethods.selectedProperty().addListener((_, _, _) -> filter());
		external.selectedProperty().addListener((_, _, _) -> render());
		refresh();
	}

	public void request(CodeLocation method) {
		requested = method;
		if (analysis != null && current()) selectRequested();
	}

	@Override protected void scanned(ExecutionAnalysis result) {
		if (!current()) return;
		filter();
		if (requested != null) selectRequested();
		else if (!roots.getItems().isEmpty()) roots.getSelectionModel().selectFirst();
		else status.setText(Lang.get("flow.no-entries"));
	}
	private void selectRequested() {
		List<Method> matches = analysis.methods().stream().filter(m -> m.id().location().equals(requested)).toList();
		if (matches.size() == 1) { Method match = matches.getFirst(); allMethods.setSelected(true); search.clear(); filter(); roots.getSelectionModel().select(match); roots.scrollTo(match); }
		else status.setText(Lang.get("flow.missing-method"));
	}
	private void filter() {
		if (analysis == null) return;
		String query = search.getText().toLowerCase(Locale.ROOT);
		List<Method> candidates = allMethods.isSelected() ? analysis.methods() : analysis.entries();
		roots.getItems().setAll(candidates.stream().filter(m -> m.toString().toLowerCase(Locale.ROOT).contains(query) ||
				m.id().location().className().toLowerCase(Locale.ROOT).contains(query)).toList());
	}
	private void focus(MethodId method) { root = method; expanded.clear(); expanded.add(method); select(method); render(); }
	private void select(MethodId method) {
		selected = method;
		calls.getItems().setAll(analysis.calls(method));
		Method item = analysis.method(method);
		detail.setText(item + "\n\n" + Objects.requireNonNullElse(item.entryReason(), Lang.get("flow.internal-method")) +
				"\n\n" + Lang.get("flow.graph-help") + (analysis.problems().isEmpty() ? "" : "\n\n" + String.join("\n", analysis.problems())));
	}
	private void render() {
		if (!current() || root == null) return;
		Map<String, FlowDiagram.Vertex> vertices = new LinkedHashMap<>(); List<FlowDiagram.Link> links = new ArrayList<>();
		Map<MethodId, Integer> levels = new LinkedHashMap<>(); Deque<MethodId> queue = new ArrayDeque<>();
		levels.put(root, 0); queue.add(root); boolean bounded = false;
		while (!queue.isEmpty()) {
			MethodId id = queue.removeFirst(); int level = levels.get(id); Method method = analysis.method(id);
			vertices.putIfAbsent(id.toString(), vertex(method, level));
			if (!expanded.contains(id)) continue;
			if (level >= 10) { bounded = true; continue; }
			for (Call call : analysis.calls(id)) {
				if (vertices.size() >= 120 || links.size() >= 240) { bounded = true; break; }
				String target;
				if (call.target() == null) {
					if (!external.isSelected() && call.site().sink() == null) continue;
					target = "external:" + call.site().target();
					String qualified = call.site().target().split("\\(", 2)[0];
					String label = qualified.substring(qualified.lastIndexOf('/') + 1) + (call.site().sink() == null ? "" : "\n" + call.site().sink());
					vertices.putIfAbsent(target, new FlowDiagram.Vertex(target, label, call.site().target() + "\n" + call.explanation(), level + 1, call.site().sink() != null, call));
				} else {
					target = call.target().toString();
					if (!levels.containsKey(call.target())) { levels.put(call.target(), level + 1); queue.addLast(call.target()); }
					vertices.putIfAbsent(target, vertex(analysis.method(call.target()), levels.get(call.target())));
				}
				links.add(new FlowDiagram.Link(id.toString(), target, call.kind() + " @" + call.site().instruction(), call.kind() != CallKind.DIRECT));
			}
		}
		diagram.show(List.copyOf(vertices.values()), links, vertex -> {
			if (!current()) return;
			if (vertex.value() instanceof Method method) { expanded.add(method.id()); select(method.id()); render(); }
			else if (vertex.value() instanceof Call call) detail.setText(call.site() + "\n\n" + call.explanation());
		});
		status.setText(analysis.methods().size() + " " + Lang.get("flow.methods") + " · " + vertices.size() + " " + Lang.get("flow.visible") +
				" · " + analysis.problems().size() + " " + Lang.get("flow.gaps") + (bounded ? " · " + Lang.get("flow.graph-limit") : ""));
	}
	private FlowDiagram.Vertex vertex(Method method, int level) {
		boolean sink = analysis.sinks().stream().anyMatch(site -> site.method().equals(method.id()));
		return new FlowDiagram.Vertex(method.id().toString(), method.id().location().display() + "\n" + method.id().location().memberDescriptor(),
				method + "\n" + Objects.requireNonNullElse(method.entryReason(), ""), level, sink || method.networkEntry(), method);
	}
	@Override protected void clearView() {
		roots.getItems().clear(); calls.getItems().clear(); detail.clear(); root = selected = null; expanded.clear(); diagram.show(List.of(), List.of(), _ -> {});
	}
}
