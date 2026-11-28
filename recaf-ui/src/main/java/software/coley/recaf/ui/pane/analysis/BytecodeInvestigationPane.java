package software.coley.recaf.ui.pane.analysis;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationReport;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationReport.*;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationService;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis;
import software.coley.recaf.services.analysis.bytecode.ObfuscationTechnologyDetector;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting;
import software.coley.recaf.services.analysis.bytecode.ReadableWorkspaceService;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Resource-aware investigation of obfuscation, bootstrap families, and string-helper call sites. */
@Dependent
public class BytecodeInvestigationPane extends BorderPane implements Navigable {
	private final Workspace workspace;
	private final BytecodeInvestigationService service;
	private final ObfuscatorFingerprinting fingerprinting;
	private final ObfuscationTechnologyDetector technologyDetector;
	private final ReadableWorkspaceService readability;
	private final WorkspaceManager workspaces;
	private final Actions actions;
	private final ExecutorService worker = ThreadPoolFactory.newSingleThreadExecutor("bytecode-investigation");
	private final AtomicInteger generation = new AtomicInteger();
	private final AtomicInteger detailGeneration = new AtomicInteger();
	private final ComboBox<ResourceChoice> resource = new ComboBox<>();
	private final TextField search = new TextField();
	private final CheckBox includeStandard = new CheckBox();
	private final Label summary = new Label();
	private final Label status = new Label();
	private final TextArea details = new TextArea();
	private final TextArea evaluation = new TextArea();
	private final Spinner<Integer> methodInstruction = new Spinner<>();
	private final ComboBox<SliceChoice> sliceCriterion = new ComboBox<>();
	private final FlowPane methodControls = new FlowPane(8, 8, new Label(text("method-analysis.instruction")), methodInstruction,
			new Label(text("method-analysis.slice")), sliceCriterion);
	private final ScanReport fingerprints = new ScanReport("fingerprint", (selection, _, cancelled) -> scanFingerprints(selection, cancelled), null);
	private final ScanReport technologies = new ScanReport("technology", this::scanTechnologies, new CheckBox(text("technology.control-flow")));
	private final TabPane tabs = new TabPane();
	private final TableView<MethodProfile> methods = new TableView<>();
	private final TableView<CallSite> dynamicCalls = new TableView<>();
	private final TableView<CallSite> stringCalls = new TableView<>();
	private final TableView<BootstrapGroup> bootstraps = new TableView<>();
	private final TableView<NativeMethod> nativeMethods = new TableView<>();
	private final ObservableList<MethodProfile> allMethods = FXCollections.observableArrayList();
	private final ObservableList<CallSite> allDynamic = FXCollections.observableArrayList();
	private final ObservableList<CallSite> allStrings = FXCollections.observableArrayList();
	private final ObservableList<BootstrapGroup> allBootstraps = FXCollections.observableArrayList();
	private final ObservableList<NativeMethod> allNatives = FXCollections.observableArrayList();
	private final FilteredList<MethodProfile> methodItems = new FilteredList<>(allMethods);
	private final FilteredList<CallSite> dynamicItems = new FilteredList<>(allDynamic);
	private final FilteredList<CallSite> stringItems = new FilteredList<>(allStrings);
	private final FilteredList<BootstrapGroup> bootstrapItems = new FilteredList<>(allBootstraps);
	private final FilteredList<NativeMethod> nativeItems = new FilteredList<>(allNatives);
	private final SimpleObjectProperty<ClassMemberPathNode> selectedCaller = new SimpleObjectProperty<>();
	private final SimpleObjectProperty<ClassMemberPathNode> selectedTarget = new SimpleObjectProperty<>();
	private final SimpleObjectProperty<CallSite> selectedCall = new SimpleObjectProperty<>();
	private final Spinner<Integer> budget = new Spinner<>(100, 1_000_000, 100_000, 10_000);
	private final CheckBox initializeClasses = new CheckBox();
	private final Button evaluate = button("evaluate", this::evaluate);
	private final Button familySites = button("family-sites", this::showFamily);
	private final Button readableCopy = button("readable-copy", this::openReadableCopy);
	private final CheckBox readableAliases = new CheckBox();
	private BytecodeInvestigationReport report = BytecodeInvestigationReport.EMPTY;
	private BootstrapGroup selectedFamily;
	private BootstrapGroup familyFilter;
	private boolean rebuilding;
	private volatile boolean disabled;
	private boolean evaluating;
	private boolean updatingSliceChoices;
	private MethodAnalysis.Result analyzedMethod;

	@Inject
	public BytecodeInvestigationPane(@Nonnull WorkspaceManager workspaces, @Nonnull BytecodeInvestigationService service,
	                                 @Nonnull ReadableWorkspaceService readability, @Nonnull ObfuscatorFingerprinting fingerprinting,
	                                 @Nonnull ObfuscationTechnologyDetector technologyDetector, @Nonnull Actions actions) {
		workspace = Objects.requireNonNull(workspaces.getCurrent(), "No workspace is open");
		this.service = service;
		this.readability = readability;
		this.fingerprinting = fingerprinting;
		this.technologyDetector = technologyDetector;
		this.workspaces = workspaces;
		this.actions = actions;
		setPadding(new Insets(8));
		search.setId("investigate-search");
		resource.setId("investigate-resource");
		includeStandard.setId("investigate-standard");
		search.setPromptText(text("search"));
		search.textProperty().addListener((_, _, _) -> filter());
		includeStandard.setText(text("standard"));
		includeStandard.selectedProperty().addListener((_, _, _) -> filter());
		resource.setConverter(new StringConverter<>() {
			@Override public String toString(ResourceChoice choice) { return choice == null ? "" : choice.name(); }
			@Override public ResourceChoice fromString(String value) { return null; }
		});
		resource.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(resource, Priority.ALWAYS);
		resource.getItems().addAll(resourceChoices(workspace.getPrimaryResource()));
		resource.getSelectionModel().selectFirst();
		resource.valueProperty().addListener((_, _, _) -> rescan());
		HBox resources = new HBox(8, resource, button("rescan", this::rescan), button("copy-report", () -> copy(summaryReport())));
		HBox filters = new HBox(8, search, includeStandard, button("all-sites", () -> { familyFilter = null; search.clear(); filter(); }));
		HBox.setHgrow(search, Priority.ALWAYS);
		summary.getStyleClass().add(Styles.TITLE_4);
		summary.setWrapText(true);
		status.getStyleClass().add(Styles.TEXT_SUBTLE);
		readableAliases.setText(text("readable-aliases"));
		readableAliases.setSelected(true);
		Label copyHint = new Label(text("readable-hint"));
		copyHint.setWrapText(true);
		copyHint.getStyleClass().add(Styles.TEXT_SUBTLE);
		FlowPane readabilityTools = new FlowPane(8, 8, readableCopy, readableAliases, button("cancel", () -> {
			generation.incrementAndGet();
			detailGeneration.incrementAndGet();
			fingerprints.reset();
			technologies.reset();
			tabs.setDisable(false);
			status.setText(text("cancelled"));
		}));
		VBox header = new VBox(8, resources, summary, filters, readabilityTools, copyHint, status);
		header.setPadding(new Insets(0, 0, 8, 0));
		setTop(header);

		configureTables();
		details.setEditable(false);
		details.setWrapText(false);
		details.setStyle("-fx-font-family: 'JetBrains Mono';");
		details.setId("bytecode-investigation-details");
		evaluation.setEditable(false);
		evaluation.setPrefRowCount(5);
		evaluation.setPromptText(text("evaluation-hint"));
		Button openCaller = button("open-caller", () -> open(selectedCaller.get(), false));
		Button openTarget = button("open-target", () -> open(selectedTarget.get(), false));
		Button bytecode = button("bytecode", () -> open(selectedCaller.get(), true));
		Button graph = button("callgraph", this::openGraph);
		Button analyzeMethod = button("method-analysis.run", this::analyzeSelectedMethod);
		analyzeMethod.setId("investigate-method-analysis");
		openCaller.disableProperty().bind(selectedCaller.isNull());
		openTarget.disableProperty().bind(selectedTarget.isNull());
		bytecode.disableProperty().bind(selectedCaller.isNull());
		graph.disableProperty().bind(selectedCaller.isNull());
		analyzeMethod.disableProperty().bind(selectedCaller.isNull());
		FlowPane navigation = new FlowPane(8, 8, openCaller, openTarget, bytecode, graph, analyzeMethod,
				button("copy-details", () -> copy(details.getText() + "\n\n" + evaluation.getText())), familySites);
		familySites.setDisable(true);
		initializeClasses.setText(text("initializers"));
		budget.setEditable(false);
		budget.setPrefWidth(125);
		FlowPane evaluationControls = new FlowPane(8, 8, evaluate, new Label(text("budget")), budget, initializeClasses);
		evaluate.setDisable(true);
		methodInstruction.setId("investigate-method-instruction");
		methodInstruction.setEditable(true);
		methodInstruction.getEditor().setTextFormatter(new TextFormatter<>(change -> change.getControlNewText().matches("[0-9]{0,9}") ? change : null));
		methodInstruction.setPrefWidth(100);
		methodInstruction.valueProperty().addListener((_, _, value) -> { if (value != null) selectMethodInstruction(value); });
		sliceCriterion.setId("investigate-slice-criterion");
		sliceCriterion.valueProperty().addListener((_, _, value) -> {
			Integer index = methodInstruction.getValue();
			if (!updatingSliceChoices && index != null && value != null) showMethodAnalysis(index, value);
		});
		methodControls.managedProperty().bind(methodControls.visibleProperty());
		methodControls.setVisible(false);
		VBox inspector = new VBox(8, navigation, methodControls, details, evaluationControls, evaluation);
		VBox.setVgrow(details, Priority.ALWAYS);
		SplitPane split = new SplitPane(tabs, inspector);
		split.setDividerPositions(0.57);
		setCenter(split);
		rescan();
	}

	private void openReadableCopy() {
		if (disabled || resource.getValue() == null) return;
		int version = generation.incrementAndGet();
		detailGeneration.incrementAndGet();
		WorkspaceResource target = resource.getValue().resource();
		int steps = budget.getValue();
		boolean aliases = readableAliases.isSelected();
		readableCopy.setDisable(true);
		status.setText(text("building-copy"));
		worker.execute(() -> {
			try {
				var result = readability.build(workspace, target, steps, aliases, () -> disabled || version != generation.get(), progress ->
						Platform.runLater(() -> { if (!disabled && version == generation.get()) status.setText(localizeProgress(progress)); }));
				Platform.runLater(() -> {
					readableCopy.setDisable(false);
					if (disabled || version != generation.get()) { result.workspace().close(); return; }
					if (workspaces.setCurrent(result.workspace())) {
						try { actions.gotoDeclaration(result.workspace().findFile("RECAF-READABILITY.txt")); }
						catch (Exception ignored) { /* The report remains in the file tree. */ }
					} else {
						result.workspace().close();
						status.setText(text("open-copy-failed"));
					}
				});
			} catch (Exception error) {
				Platform.runLater(() -> {
					readableCopy.setDisable(false);
					if (!disabled && version == generation.get()) status.setText(text("failed") + ": " + error.getMessage());
				});
			}
		});
	}

	private void configureTables() {
		column(methods, "method", MethodProfile::methodName, 360);
		column(methods, "score", MethodProfile::score, 65);
		column(methods, "dynamic-count", MethodProfile::dynamicCalls, 70);
		column(methods, "bitwise", MethodProfile::bitwise, 70);
		column(methods, "crypto", MethodProfile::cryptoCalls, 70);
		column(methods, "instructions", MethodProfile::instructions, 95);
		column(methods, "resource", MethodProfile::resource, 220);
		installItems(methods, methodItems);
		methods.getSelectionModel().selectedItemProperty().addListener((_, _, value) -> { if (!rebuilding && value != null) select(value); });
		for (TableView<CallSite> table : List.of(dynamicCalls, stringCalls)) {
			column(table, "caller", site -> site.method().methodName(), 340);
			column(table, "instruction", CallSite::instruction, 80);
			column(table, "target", CallSite::targetName, 380);
			column(table, "resource", site -> site.method().resource(), 220);
			table.getSelectionModel().selectedItemProperty().addListener((_, _, value) -> { if (!rebuilding && value != null) select(value); });
		}
		installItems(dynamicCalls, dynamicItems);
		installItems(stringCalls, stringItems);
		column(bootstraps, "target", group -> group.target().owner() + "." + group.target().name() + group.target().desc(), 470);
		column(bootstraps, "sites", group -> group.sites().size(), 80);
		column(bootstraps, "resource", BootstrapGroup::resource, 220);
		installItems(bootstraps, bootstrapItems);
		bootstraps.getSelectionModel().selectedItemProperty().addListener((_, _, group) -> { if (!rebuilding && group != null) select(group); });
		nativeMethods.setId("investigate-native-methods");
		column(nativeMethods, "method", item -> item.method().methodName(), 470);
		column(nativeMethods, "native-callers", item -> item.callers().size(), 100);
		column(nativeMethods, "resource", item -> item.method().resource(), 220);
		installItems(nativeMethods, nativeItems);
		nativeMethods.getSelectionModel().selectedItemProperty().addListener((_, _, item) -> { if (!rebuilding && item != null) select(item); });
		tabs.getTabs().addAll(tab("hotspots", methods), tab("dynamic", dynamicCalls), tab("families", bootstraps),
				tab("strings", stringCalls), tab("native", nativeMethods), fingerprints.tab, technologies.tab);
		tabs.getSelectionModel().selectedItemProperty().addListener((_, _, tab) -> {
			clearSelection();
			detailGeneration.incrementAndGet();
			if (tab == null || !(tab.getContent() instanceof TableView<?> table)) return;
			Object value = table.getSelectionModel().getSelectedItem();
			if (value instanceof MethodProfile method) select(method);
			else if (value instanceof CallSite site) select(site);
			else if (value instanceof BootstrapGroup group) select(group);
			else if (value instanceof NativeMethod item) select(item);
		});
		for (TableView<?> table : List.of(methods, dynamicCalls, stringCalls, bootstraps, nativeMethods)) {
			table.setPlaceholder(new Label(text("empty")));
			table.setOnMouseClicked(event -> { if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) open(selectedCaller.get(), false); });
			table.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) open(selectedCaller.get(), false); });
		}
	}

	private void rescan() {
		if (disabled || resource.getValue() == null) return;
		fingerprints.reset();
		technologies.reset();
		int version = generation.incrementAndGet();
		detailGeneration.incrementAndGet();
		familyFilter = null;
		clearSelection();
		tabs.setDisable(true);
		status.setText(text("scanning"));
		WorkspaceResource target = resource.getValue().resource();
		worker.execute(() -> {
			try {
				BytecodeInvestigationReport scanned = service.analyze(workspace, target, () -> disabled || version != generation.get());
				Platform.runLater(() -> {
					if (disabled || version != generation.get()) return;
					report = scanned;
					rebuilding = true;
					allMethods.setAll(report.methods());
					allDynamic.setAll(report.callSites().stream().filter(CallSite::dynamic).toList());
					allStrings.setAll(report.callSites().stream().filter(site -> !site.dynamic()).toList());
					allBootstraps.setAll(report.bootstraps());
					allNatives.setAll(report.nativeMethods());
					rebuilding = false;
					summary.setText(String.format(text("summary"), report.classes(), report.methods().size(),
							report.callSites().stream().filter(site -> site.dynamic() && !site.standardBootstrap()).count(), report.bootstraps().stream().filter(group -> group.sites().stream().anyMatch(site -> !site.standardBootstrap())).count())
							+ " · " + String.format(text("native-summary"), report.nativeMethods().size(), report.nativeLibraries().size()));
					tabs.setDisable(false);
					filter();
					details.setText(summaryReport());
				});
			} catch (CancellationException ignored) {
				// A newer scan or a closed pane owns the result now.
			} catch (Exception error) {
				Platform.runLater(() -> {
					if (disabled || version != generation.get()) return;
					tabs.setDisable(false);
					status.setText(text("failed") + ": " + error.getMessage());
				});
			}
		});
	}

	private String scanFingerprints(ResourceChoice selection, BooleanSupplier cancelled) {
		return formatFingerprints(selection.name(), fingerprinting.scan(selection.resource(), cancelled));
	}

	private String scanTechnologies(ResourceChoice selection, boolean inDepthControlFlow, BooleanSupplier cancelled) {
		var result = inDepthControlFlow ? technologyDetector.scan(selection.resource(), true, cancelled)
				: technologyDetector.scan(selection.resource(), cancelled);
		String root = selection.name();
		StringBuilder report = new StringBuilder(root).append('\n')
				.append(String.format(text("fingerprint.summary"), result.classesRead(), result.classesSkipped()));
		if (inDepthControlFlow) report.append('\n').append(String.format(text("technology.control-flow-summary"),
				result.controlFlow().methodsAnalyzed(), result.controlFlow().methodsSkipped()));
		report.append("\n\n").append(text("technology.note"));
		if (result.matches().isEmpty()) return report.append("\n\n").append(text("technology.unknown")).toString();
		String previousResource = null;
		for (var match : result.matches()) {
			if (!match.resource().equals(previousResource)) {
				previousResource = match.resource();
				report.append("\n\n").append(previousResource.isEmpty() ? root : root + " > " + previousResource);
			}
			report.append('\n').append(match.occurrences()).append(" × ")
					.append(text("technology.evidence." + match.technology().name().toLowerCase(Locale.ROOT)));
			for (String example : match.examples()) report.append("\n    ").append(example);
		}
		return report.toString();
	}

	private static String formatFingerprints(String root, ObfuscatorFingerprinting.Result result) {
		StringBuilder report = new StringBuilder(root).append('\n')
				.append(String.format(text("fingerprint.summary"), result.classesRead(), result.classesSkipped()))
				.append("\n\n").append(text("fingerprint.scores"));
		result.confidenceScores().entrySet().stream()
				.sorted(Map.Entry.<ObfuscatorFingerprinting.Family, Integer>comparingByValue().reversed()
						.thenComparing(Map.Entry.comparingByKey()))
				.forEach(score -> report.append('\n').append(score.getKey().displayName()).append(" · ")
						.append(score.getValue()).append('%'));
		report.append("\n\n").append(text("fingerprint.score-note"));
		if (result.matches().isEmpty())
			return report.append("\n\n").append(text("fingerprint.unknown")).toString();
		report.append("\n\n").append(text("fingerprint.details"));
		for (var match : result.matches()) {
			String location = match.resource().isEmpty() ? root : root + " > " + match.resource();
			report.append("\n\n").append(match.family().displayName()).append(" · ")
					.append(match.confidencePercent()).append("% · ")
					.append(text("fingerprint.confidence." + match.confidence().name().toLowerCase(Locale.ROOT)))
					.append('\n').append(location);
			for (var evidence : match.evidence()) {
				report.append('\n').append(evidence.occurrences()).append(" × ")
						.append(text("fingerprint.evidence." + evidence.indicator().name().toLowerCase(Locale.ROOT)));
				for (String example : evidence.examples())
					report.append("\n    ").append(example);
			}
			String cleanup = "fingerprint.recommend." + match.family().name().toLowerCase(Locale.ROOT);
			if (Lang.has("investigate." + cleanup)) report.append('\n').append(text("fingerprint.cleanup")).append(" · ").append(text(cleanup));
		}
		return report.toString();
	}

	@FunctionalInterface
	private interface ReportScan {
		String scan(ResourceChoice selection, boolean inDepthControlFlow, BooleanSupplier cancelled);
	}

	private final class ScanReport {
		private final AtomicInteger scanGeneration = new AtomicInteger();
		private final TextArea output = new TextArea();
		private final Button run;
		private final Tab tab;

		private ScanReport(String key, ReportScan scan, @Nullable CheckBox option) {
			run = button(key + ".run", () -> start(key, scan, option != null && option.isSelected()));
			run.setId("investigate-" + key + "-run");
			output.setId("investigate-" + key + "-report");
			output.setEditable(false);
			output.setWrapText(true);
			output.setPromptText(text(key + ".idle"));
			Button copy = button(key + ".copy", () -> copy(output.getText()));
			copy.setId("investigate-" + key + "-copy");
			copy.disableProperty().bind(output.textProperty().isEmpty().or(run.disableProperty()));
			FlowPane controls = new FlowPane(8, 8, run, copy);
			controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
			if (option != null) {
				option.setId("investigate-" + key + "-control-flow");
				option.disableProperty().bind(run.disableProperty());
				controls.getChildren().add(option);
			}
			VBox content = new VBox(8, controls, output);
			content.setPadding(new Insets(8));
			VBox.setVgrow(output, Priority.ALWAYS);
			tab = tab(key + ".tab", content);
		}

		private void reset() {
			scanGeneration.incrementAndGet();
			output.clear();
			run.setDisable(false);
		}

		private void start(String key, ReportScan scan, boolean inDepthControlFlow) {
			ResourceChoice selection = resource.getValue();
			if (disabled || selection == null) return;
			int version = scanGeneration.incrementAndGet();
			BooleanSupplier cancelled = () -> disabled || version != scanGeneration.get();
			run.setDisable(true);
			output.setText(text(key + ".scanning"));
			worker.execute(() -> {
				String result;
				try {
					result = scan.scan(selection, inDepthControlFlow, cancelled);
				} catch (CancellationException ignored) {
					return;
				} catch (RuntimeException error) {
					result = text(key + ".failed") + "\n" + error.getMessage();
				}
				String report = result;
				Platform.runLater(() -> {
					if (cancelled.getAsBoolean()) return;
					output.setText(report);
					run.setDisable(false);
				});
			});
		}
	}

	private void filter() {
		detailGeneration.incrementAndGet();
		rebuilding = true;
		for (TableView<?> table : List.of(methods, dynamicCalls, stringCalls, bootstraps, nativeMethods)) table.getSelectionModel().clearSelection();
		clearSelection();
		String query = search.getText().strip().toLowerCase(Locale.ROOT);
		methodItems.setPredicate(method -> contains(query, method.methodName(), method.resource()));
		dynamicItems.setPredicate(site -> (includeStandard.isSelected() || !site.standardBootstrap())
				&& (familyFilter == null || (site.target().equals(familyFilter.target()) && site.method().resource().equals(familyFilter.resource())))
				&& contains(query, site.method().methodName(), site.targetName(), site.name(), site.descriptor(), site.method().resource(), site.bootstrapArguments().toString()));
		stringItems.setPredicate(site -> contains(query, site.method().methodName(), site.targetName(), site.method().resource()));
		bootstrapItems.setPredicate(group -> (includeStandard.isSelected() || group.sites().stream().anyMatch(site -> !site.standardBootstrap()))
				&& contains(query, group.target().owner(), group.target().name(), group.resource()));
		nativeItems.setPredicate(item -> contains(query, item.method().methodName(), item.method().resource())
				|| item.callers().stream().anyMatch(caller -> contains(query, caller.method().methodName(), caller.method().resource())));
		rebuilding = false;
		status.setText(String.format(text("shown"), methodItems.size(), dynamicItems.size(), stringItems.size(), report.failures().size())
				+ (familyFilter == null ? "" : " · " + text("family-filter")));
	}

	private void clearSelection() {
		analyzedMethod = null;
		methodControls.setVisible(false);
		sliceCriterion.getItems().clear();
		sliceCriterion.setValue(null);
		selectedCaller.set(null);
		selectedTarget.set(null);
		selectedCall.set(null);
		selectedFamily = null;
		familySites.setDisable(true);
		evaluate.setDisable(true);
		details.clear();
		evaluation.clear();
	}

	private void analyzeSelectedMethod() {
		ClassMemberPathNode path = selectedCaller.get();
		if (path == null || disabled) return;
		int preferred = selectedCall.get() == null ? -1 : selectedCall.get().instruction();
		int request = detailGeneration.incrementAndGet();
		analyzedMethod = null;
		methodControls.setVisible(false);
		details.setText(text("method-analysis.scanning"));
		worker.execute(() -> {
			try {
				MethodAnalysis.Result result = service.analyzeMethod(path, () -> disabled || detailGeneration.get() != request);
				Platform.runLater(() -> {
					if (disabled || detailGeneration.get() != request) return;
					int initial = preferred >= 0 && preferred < result.instructions().size() ? preferred
							: result.summary().returns().isEmpty() ? 0 : result.summary().returns().getLast();
					methodInstruction.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, result.instructions().size() - 1, initial));
					analyzedMethod = result;
					methodControls.setVisible(true);
					selectMethodInstruction(initial);
				});
			} catch (CancellationException ignored) {
			} catch (Exception error) {
				Platform.runLater(() -> {
					if (!disabled && detailGeneration.get() == request)
						details.setText(text("method-analysis.failed") + "\n" + error.getMessage());
				});
			}
		});
	}

	private void selectMethodInstruction(int index) {
		MethodAnalysis.Result result = analyzedMethod;
		if (result == null) return;
		var instruction = result.instructions().get(index);
		var choices = new ArrayList<SliceChoice>(1 + instruction.locals().size() + instruction.stack().size());
		choices.add(new SliceChoice(MethodAnalysis.SliceCriterion.INSTRUCTION, 0));
		for (var local : instruction.locals())
			choices.add(new SliceChoice(MethodAnalysis.SliceCriterion.LOCAL, local.slot()));
		for (var stack : instruction.stack())
			choices.add(new SliceChoice(MethodAnalysis.SliceCriterion.STACK, stack.slot()));
		SliceChoice selected = sliceCriterion.getValue();
		if (!choices.contains(selected)) selected = choices.getFirst();
		updatingSliceChoices = true;
		sliceCriterion.getItems().setAll(choices);
		sliceCriterion.setValue(selected);
		updatingSliceChoices = false;
		showMethodAnalysis(index, selected);
	}

	private void showMethodAnalysis(int index, SliceChoice choice) {
		ClassMemberPathNode path = selectedCaller.get();
		MethodAnalysis.Result result = analyzedMethod;
		if (disabled || path == null || result == null) return;
		int request = detailGeneration.incrementAndGet();
		worker.execute(() -> {
			try {
				var slice = result.backwardSlice(index, choice.kind(), choice.slot(),
						() -> disabled || detailGeneration.get() != request);
				String content = MethodAnalysisText.format(path, result, index, choice.toString(), slice);
				Platform.runLater(() -> {
					if (!disabled && detailGeneration.get() == request) details.setText(content);
				});
			} catch (CancellationException ignored) {
			}
		});
	}

	private void select(MethodProfile method) {
		clearSelection();
		detailGeneration.incrementAndGet();
		selectedCaller.set(method.path());
		details.setText(method.methodName() + "\nResource: " + method.resource() + "\n\nInstructions: " + method.instructions()
				+ "\nBranches/switches: " + method.branches() + "\nException handlers: " + method.handlers()
				+ "\nBitwise operations/helpers: " + method.bitwise() + "\nDynamic calls: " + method.dynamicCalls()
				+ "\nEncoded-looking literals: " + method.encodedStrings() + "\nCrypto API calls: " + method.cryptoCalls()
				+ "\nReflection/method-handle API calls: " + method.reflectionCalls() + "\nClass-loading API calls: " + method.loadingCalls()
				+ "\nString-helper candidate: " + method.stringHelper() + "\n\n" + text("indicators"));
	}

	private void select(CallSite site) {
		clearSelection();
		selectedCall.set(site);
		selectedCaller.set(site.method().path());
		selectedTarget.set(BytecodeInvestigationService.resolve(workspace, site.method().path(), site.target()));
		evaluate.setDisable(site.dynamic() || evaluating);
		int version = detailGeneration.incrementAndGet();
		details.setText(text("loading-details"));
		worker.execute(() -> {
			try {
				String content = service.describe(site);
				Platform.runLater(() -> { if (!disabled && version == detailGeneration.get()) details.setText(content); });
			} catch (Exception error) {
				Platform.runLater(() -> { if (!disabled && version == detailGeneration.get()) details.setText(error.toString()); });
			}
		});
	}

	private void select(BootstrapGroup group) {
		clearSelection();
		detailGeneration.incrementAndGet();
		selectedFamily = group;
		selectedCaller.set(group.declaration());
		selectedTarget.set(group.declaration());
		familySites.setDisable(false);
		StringBuilder text = new StringBuilder(group.target().owner() + "." + group.target().name() + group.target().desc())
				.append("\nResource: ").append(group.resource()).append("\nCall sites: ").append(group.sites().size()).append("\n\nCallers:\n");
		group.sites().stream().map(site -> site.method().methodName()).distinct().limit(40).forEach(name -> text.append(name).append('\n'));
		text.append("\n").append(text("bootstrap-hint"));
		details.setText(text.toString());
	}

	private void select(NativeMethod item) {
		clearSelection();
		detailGeneration.incrementAndGet();
		selectedCaller.set(item.method().path());
		selectedTarget.set(item.method().path());
		StringBuilder content = new StringBuilder(item.method().methodName())
				.append("\nResource: ").append(item.method().resource()).append("\n\n").append(text("native-hint"))
				.append("\n\n").append(text("native-callers")).append(": ").append(item.callers().size()).append('\n');
		for (NativeCaller caller : item.callers())
			content.append(caller.method().methodName()).append(" @ ").append(caller.instruction())
					.append(" · ").append(caller.method().resource()).append('\n');
		content.append("\n").append(text("native-libraries")).append(":\n");
		for (String library : report.nativeLibraries()) content.append(library).append('\n');
		details.setText(content.toString());
	}

	private void showFamily() {
		if (selectedFamily == null) return;
		familyFilter = selectedFamily;
		search.clear();
		filter();
		tabs.getSelectionModel().select(1);
	}

	private void evaluate() {
		CallSite site = selectedCall.get();
		if (site == null || site.dynamic() || evaluating) return;
		int version = detailGeneration.get();
		int steps = budget.getValue();
		boolean initialize = initializeClasses.isSelected();
		evaluating = true;
		evaluate.setDisable(true);
		evaluation.setText(text("evaluating"));
		worker.execute(() -> {
			Evaluation result = service.evaluate(workspace, site, steps, initialize);
			Platform.runLater(() -> {
				if (disabled) return;
				evaluating = false;
				evaluate.setDisable(selectedCall.get() == null || selectedCall.get().dynamic());
				if (version != detailGeneration.get()) return;
				evaluation.setText(result.status() + " · " + result.instructions() + " instructions\nInputs: " + result.arguments()
						+ (result.value() == null ? "" : "\n\n" + BytecodeInvestigationService.literal(result.value())) + "\n\n" + result.explanation());
			});
		});
	}

	private void open(ClassMemberPathNode path, boolean bytecode) {
		if (path == null) return;
		try {
			if (bytecode) actions.openAssembler(path);
			else actions.gotoDeclaration(path);
		} catch (Exception error) { status.setText(text("failed") + ": " + error.getMessage()); }
	}

	private void openGraph() {
		ClassMemberPathNode path = selectedCaller.get();
		if (path == null) return;
		actions.openMethodCallGraphTree(workspace, path.getValueOfType(WorkspaceResource.class), path.getValueOfType(ClassBundle.class),
				path.getParent().getValue(), path.getValueAsMethod());
	}

	private String summaryReport() {
		StringBuilder text = new StringBuilder("BYTECODE INVESTIGATION\nResource: ")
				.append(resource.getValue() == null ? "" : resource.getValue().name()).append('\n').append(summary.getText())
				.append("\n\n").append(text("indicators")).append("\n\nBootstrap families:\n");
		report.bootstraps().stream().limit(50).forEach(group -> text.append(group.sites().size()).append(" sites · ").append(group.target()).append(" · ").append(group.resource()).append('\n'));
		text.append("\nMethods with highest indicator counts:\n");
		report.methods().stream().limit(50).forEach(method -> text.append(method.score()).append(" · ").append(method.methodName()).append(" · ").append(method.resource()).append('\n'));
		text.append("\n").append(text("native")).append(":\n");
		report.nativeMethods().forEach(item -> text.append(item.callers().size()).append(" callers · ")
				.append(item.method().methodName()).append(" · ").append(item.method().resource()).append('\n'));
		text.append("\n").append(text("native-libraries")).append(":\n");
		report.nativeLibraries().forEach(library -> text.append(library).append('\n'));
		if (!report.failures().isEmpty()) text.append("\nScan failures:\n").append(String.join("\n", report.failures()));
		return text.toString();
	}

	private static <T> void installItems(TableView<T> table, FilteredList<T> items) {
		SortedList<T> sorted = new SortedList<>(items);
		sorted.comparatorProperty().bind(table.comparatorProperty());
		table.setItems(sorted);
	}

	private static <T, V> void column(TableView<T> table, String key, Function<T, V> value, double width) {
		TableColumn<T, V> column = new TableColumn<>(text(key));
		column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
		column.setPrefWidth(width);
		table.getColumns().add(column);
	}

	private static Tab tab(String key, javafx.scene.Node content) {
		Tab tab = new Tab(text(key), content);
		tab.setClosable(false);
		return tab;
	}

	private static boolean contains(String query, String... fields) {
		return query.isEmpty() || Arrays.stream(fields).anyMatch(field -> field.toLowerCase(Locale.ROOT).contains(query));
	}

	private static Button button(String key, Runnable action) {
		Button button = new Button(text(key));
		button.setId("investigate-" + key);
		button.setOnAction(_ -> action.run());
		return button;
	}

	private static String text(String key) { return Lang.get("investigate." + key); }

	private static String localizeProgress(String progress) {
		String[] parts = progress.split(" · ");
		if (parts.length > 1 && Lang.has("deobf." + parts[1])) parts[1] = Lang.get("deobf." + parts[1]);
		return String.join(" · ", parts);
	}

	private static void copy(String text) {
		ClipboardContent content = new ClipboardContent();
		content.putString(text);
		Clipboard.getSystemClipboard().setContent(content);
	}

	private static List<ResourceChoice> resourceChoices(WorkspaceResource root) {
		List<ResourceChoice> choices = new ArrayList<>();
		Set<WorkspaceResource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<ResourceChoice> queue = new ArrayDeque<>();
		queue.add(new ResourceChoice(root, root instanceof WorkspaceFileResource file ? file.getFileInfo().getName() : Lang.get("investigate.primary")));
		while (!queue.isEmpty()) {
			ResourceChoice choice = queue.removeFirst();
			if (!seen.add(choice.resource())) continue;
			choices.add(choice);
			choice.resource().getEmbeddedResources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
					queue.addLast(new ResourceChoice(entry.getValue(), choice.name() + " > " + entry.getKey())));
		}
		return choices;
	}

	private record ResourceChoice(WorkspaceResource resource, String name) {}

	private record SliceChoice(MethodAnalysis.SliceCriterion kind, int slot) {
		@Override
		public String toString() {
			return switch (kind) {
				case INSTRUCTION -> text("method-analysis.slice.instruction");
				case LOCAL -> text("method-analysis.slice.local") + " " + slot;
				case STACK -> text("method-analysis.slice.stack") + " " + slot;
			};
		}
	}

	@Override public boolean isTrackable() { return false; }
	@Nullable @Override public PathNode<?> getPath() { return PathNodes.workspacePath(workspace); }
	@Nonnull @Override public Collection<Navigable> getNavigableChildren() { return List.of(); }
	@Override @PreDestroy public void disable() {
		disabled = true;
		generation.incrementAndGet();
		detailGeneration.incrementAndGet();
		worker.shutdownNow();
	}
}
