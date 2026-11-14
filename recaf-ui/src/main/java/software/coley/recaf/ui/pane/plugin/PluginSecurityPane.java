package software.coley.recaf.ui.pane.plugin;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.path.WorkspacePathNode;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityReport;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityService;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.SecurityRule;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.services.analysis.plugin.view.PluginViewService;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.cell.context.ContextSource;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.config.KeybindingConfig;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.pane.editing.jvm.JvmDecompilerPane;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Lists what {@link PluginSecurityService} found in the current plugin, most severe first, grouped by severity and
 * then by what was found. The entry selected in the list is explained next to it, together with the code it is
 * about, and each finding opens that code in the editor.
 * <p>
 * Findings in bundled libraries are left out of the list by default, see {@link SecurityScope}, but a notice says
 * so when serious ones are among them.
 */
@Dependent
public class PluginSecurityPane extends BorderPane implements Navigable {
	private static final Logger logger = Logging.get(PluginSecurityPane.class);
	private static final double LIST_SHARE = 0.45;
	private static final double COMBO_CHROME = 62;
	/** A tree that was just filled keeps a scroll position of its own unless it is scrolled after its first layout. */
	private static final int SCROLL_DELAY_MS = 100;
	private final TextField searchField = new TextField();
	private final ComboBox<SecurityScope> scopeFilter = new ComboBox<>();
	private final ComboBox<Severity> severityFilter = new ComboBox<>();
	private final TreeView<Item> tree = new TreeView<>();
	private final Label verdict = new Label();
	private final Label hiddenText = new Label();
	private final HBox hiddenNotice = new HBox(6, hiddenText);
	private final Label scanned = new Label();
	private final Label encrypted = new Label();
	private final Button offerDeobfuscation = new Button(Lang.get("mcplugin.view.deobfuscate-offer"));
	private final Label status = new Label();
	private final Label treePlaceholder = new Label();
	private final SecurityDetailPane detail;
	private final PauseTransition refreshDelay = new PauseTransition(Duration.millis(750));
	private final PauseTransition previewDelay = new PauseTransition(Duration.millis(250));
	private final Consumer<Workspace> indexListener = workspace -> scheduleRefresh();
	private final PluginSecurityService securityService;
	private final PluginViewService viewService;
	private final PluginSemanticService semanticService;
	private final PluginNavigation navigation;
	private final CellConfigurationService cellConfigurationService;
	private final Workspace workspace;
	private final WorkspacePathNode workspacePath;
	private PluginSecurityReport report = PluginSecurityReport.EMPTY;
	private SecurityFinding pendingPreview;
	private boolean rebuilding;
	private boolean disabled;

	@Inject
	public PluginSecurityPane(@Nonnull PluginSecurityService securityService,
	                          @Nonnull PluginViewService viewService,
	                          @Nonnull PluginSemanticService semanticService,
	                          @Nonnull PluginNavigation navigation,
	                          @Nonnull CellConfigurationService cellConfigurationService,
	                          @Nonnull KeybindingConfig keys,
	                          @Nonnull Instance<JvmDecompilerPane> decompilerProvider,
	                          @Nonnull WorkspaceManager workspaceManager) {
		this.securityService = securityService;
		this.viewService = viewService;
		this.semanticService = semanticService;
		this.navigation = navigation;
		this.cellConfigurationService = cellConfigurationService;
		this.workspace = Objects.requireNonNull(workspaceManager.getCurrent(), "Cannot open security triage without a workspace");
		this.workspacePath = PathNodes.workspacePath(workspace);
		this.detail = new SecurityDetailPane(new SecurityCodePreview(decompilerProvider, navigation, keys), this::open);

		searchField.promptTextProperty().bind(Lang.getBinding("mcplugin.security.search"));
		searchField.setMinWidth(120);
		searchField.textProperty().addListener((ob, old, cur) -> rebuildTree(false));
		searchField.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.DOWN) {
				tree.requestFocus();
				if (tree.getSelectionModel().isEmpty())
					tree.getSelectionModel().selectFirst();
			}
		});
		scopeFilter.getItems().addAll(SecurityScope.values());
		scopeFilter.setValue(SecurityScope.PLUGIN);
		scopeFilter.setConverter(new StringConverter<>() {
			@Override
			public String toString(SecurityScope scope) {
				return scope == null ? "" : scope.displayName();
			}

			@Override
			public SecurityScope fromString(String string) {
				return null;
			}
		});
		scopeFilter.setTooltip(new Tooltip(Lang.get("mcplugin.security.scope.tip")));
		scopeFilter.valueProperty().addListener((ob, old, cur) -> rebuildTree(false));
		severityFilter.getItems().addAll(Severity.values());
		severityFilter.setValue(Severity.LOW);
		severityFilter.setButtonCell(new SeverityCell());
		severityFilter.setCellFactory(l -> new SeverityCell());
		severityFilter.setTooltip(new Tooltip(Lang.get("mcplugin.security.min-severity")));
		severityFilter.valueProperty().addListener((ob, old, cur) -> rebuildTree(false));
		Button refresh = new Button(null, new FontIconView(CarbonIcons.RENEW));
		refresh.setTooltip(new Tooltip(Lang.get("mcplugin.security.refresh")));
		refresh.setOnAction(e -> refresh());
		Button settings = new Button(null, new FontIconView(CarbonIcons.SETTINGS));
		settings.setTooltip(new Tooltip(Lang.get("mcplugin.settings.tip")));
		settings.setOnAction(e -> navigation.openSettings("security"));
		HBox.setHgrow(searchField, Priority.ALWAYS);
		HBox toolbar = new HBox(6, searchField, scopeFilter, severityFilter, refresh, settings);
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.sceneProperty().addListener((ob, old, cur) -> {
			if (cur != null && old == null) {
				fitToEntries(toolbar, scopeFilter, Arrays.stream(SecurityScope.values()).map(SecurityScope::displayName).toList());
				fitToEntries(toolbar, severityFilter, List.of(Lang.get("mcplugin.security.severity.all"),
						String.format(Lang.get("mcplugin.security.severity.at-least"), SecurityDisplay.name(Severity.CRITICAL))));
			}
		});

		verdict.getStyleClass().add(Styles.TITLE_4);
		verdict.setWrapText(true);
		Hyperlink showHidden = new Hyperlink(Lang.get("mcplugin.security.hidden.show"));
		showHidden.setOnAction(e -> scopeFilter.setValue(SecurityScope.EVERYTHING));
		hiddenText.setWrapText(true);
		hiddenText.setGraphic(new FontIconView(CarbonIcons.VIEW_OFF, SecurityDisplay.color(Severity.HIGH)));
		hiddenNotice.getChildren().add(showHidden);
		hiddenNotice.setAlignment(Pos.CENTER_LEFT);
		hiddenNotice.managedProperty().bind(hiddenNotice.visibleProperty());
		hiddenNotice.setVisible(false);
		scanned.getStyleClass().add(Styles.TEXT_SUBTLE);
		scanned.setWrapText(true);
		encrypted.setWrapText(true);
		encrypted.setGraphic(new FontIconView(CarbonIcons.VIEW, SecurityDisplay.color(Severity.MEDIUM)));
		encrypted.managedProperty().bind(encrypted.visibleProperty());
		encrypted.setVisible(false);
		Label disclaimer = new Label(Lang.get("mcplugin.security.disclaimer"));
		disclaimer.getStyleClass().addAll(Styles.TEXT_SUBTLE, Styles.TEXT_SMALL);
		disclaimer.setWrapText(true);
		offerDeobfuscation.setGraphic(new FontIconView(CarbonIcons.MAGIC_WAND));
		offerDeobfuscation.setTooltip(new Tooltip(Lang.get("mcplugin.view.deobfuscated.tip")));
		offerDeobfuscation.managedProperty().bind(offerDeobfuscation.visibleProperty());
		offerDeobfuscation.setVisible(false);
		offerDeobfuscation.setOnAction(e -> {
			viewService.getServiceConfig().getDeobfuscatedView().setValue(true);
			offerDeobfuscation.setVisible(false);
		});
		VBox top = new VBox(6, toolbar, verdict, hiddenNotice, scanned, encrypted, offerDeobfuscation, disclaimer);
		top.setPadding(new Insets(6));

		tree.setShowRoot(false);
		tree.setCellFactory(t -> new FindingCell());
		treePlaceholder.getStyleClass().add(Styles.TEXT_SUBTLE);
		treePlaceholder.setWrapText(true);
		treePlaceholder.setPadding(new Insets(12));
		treePlaceholder.setMouseTransparent(true);
		treePlaceholder.setMaxWidth(Region.USE_PREF_SIZE);
		StackPane.setAlignment(treePlaceholder, Pos.TOP_LEFT);
		StackPane treeHolder = new StackPane(tree, treePlaceholder);
		treeHolder.setMinWidth(220);
		tree.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.ENTER) {
				TreeItem<Item> selected = tree.getSelectionModel().getSelectedItem();
				if (selected != null && selected.getValue() != null)
					activate(selected);
			}
		});
		tree.getSelectionModel().selectedItemProperty().addListener((ob, old, cur) -> {
			if (!rebuilding)
				showSelection();
		});
		previewDelay.setOnFinished(e -> {
			if (!disabled)
				detail.showPreview(pendingPreview);
		});

		detail.setMinWidth(260);
		SplitPane split = new SplitPane(treeHolder, detail);
		split.setDividerPositions(LIST_SHARE);

		status.getStyleClass().add(Styles.TEXT_SUBTLE);
		status.setPadding(new Insets(4, 6, 4, 6));
		setTop(top);
		setCenter(split);
		setBottom(status);

		refreshDelay.setOnFinished(e -> refresh());
		semanticService.addIndexListener(indexListener);
		refresh();
	}

	@Override
	public void requestFocus() {
		searchField.requestFocus();
	}

	/**
	 * A combo box is only as wide as the entry it shows until its list has been opened once, which would make the
	 * toolbar jump around. So it is given the width of its widest entry up front.
	 *
	 * @param host
	 * 		Node in the scene to measure the text in, with the styling of the toolbar.
	 * @param combo
	 * 		Combo box to size.
	 * @param entries
	 * 		Texts of its entries, or at least of the longest ones.
	 */
	private static void fitToEntries(@Nonnull HBox host, @Nonnull ComboBox<?> combo, @Nonnull List<String> entries) {
		Label probe = new Label();
		probe.setManaged(false);
		host.getChildren().add(probe);
		double widest = 0;
		for (String entry : entries) {
			probe.setText(entry);
			probe.applyCss();
			widest = Math.max(widest, probe.prefWidth(-1));
		}
		host.getChildren().remove(probe);
		combo.setMinWidth(widest + COMBO_CHROME);
	}

	private void scheduleRefresh() {
		// Mapping a whole plugin changes many classes in a row, so wait for things to settle.
		FxThreadUtil.run(() -> {
			if (!disabled)
				refreshDelay.playFromStart();
		});
	}

	private void refresh() {
		if (disabled)
			return;
		verdict.setGraphic(null);
		verdict.setText(Lang.get("mcplugin.security.scanning"));
		securityService.scanAsync(workspace).whenCompleteAsync((result, error) -> {
			if (disabled)
				return;
			if (error != null) {
				logger.error("Failed to scan plugin for security triage", error);
				verdict.setText(Lang.get("mcplugin.security.failed"));
				return;
			}
			report = result;
			showReport();
		}, FxThreadUtil.executor());
	}

	private void showReport() {
		Severity highest = SecurityDisplay.headline(report);
		verdict.setText(SecurityDisplay.verdict(report));
		verdict.setGraphic(highest == null ? new FontIconView(CarbonIcons.CHECKMARK) : SecurityDisplay.icon(highest));
		scanned.setText(report.embeddedJars().isEmpty()
				? String.format(Lang.get("mcplugin.security.scanned"), report.classesScanned(), report.filesScanned())
				: String.format(Lang.get("mcplugin.security.scanned-embedded"), report.classesScanned(), report.filesScanned(),
				report.embeddedJars().stream().map(PluginSecurityPane::fileName).reduce((a, b) -> a + ", " + b).orElse("")));
		scanned.setTooltip(report.embeddedJars().isEmpty() ? null : new Tooltip(String.join("\n", report.embeddedJars())));
		List<SecurityFinding> encryption = report.ofRule(SecurityRule.STRING_ENCRYPTION);
		encrypted.setVisible(!encryption.isEmpty());
		offerDeobfuscation.setVisible(!encryption.isEmpty() && !viewService.getServiceConfig().getDeobfuscatedView().getValue());
		if (!encryption.isEmpty())
			encrypted.setText(String.format(Lang.get("mcplugin.security.encrypted-strings"),
					encryption.stream().map(PluginSecurityPane::describeEncryption).reduce((a, b) -> a + ", " + b).orElse("")));
		rebuildTree(true);
	}

	@Nonnull
	private static String describeEncryption(@Nonnull SecurityFinding finding) {
		String classes = Objects.requireNonNullElse(finding.detail(), "");
		return finding.embeddedIn() == null ? classes : classes + ' ' + String.format(Lang.get("mcplugin.security.embedded"), fileName(finding.embeddedIn()));
	}

	/**
	 * @return Last part of a path inside the plugin, which is enough to tell embedded jars apart.
	 */
	@Nonnull
	private static String fileName(@Nonnull String path) {
		return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('>')) + 1).trim();
	}

	/**
	 * @param keepExpansion
	 *        {@code true} when the report was refreshed and entries that are still there keep the expanded state
	 * 		they had. Otherwise the filters changed and the entries are expanded by their defaults.
	 */
	private void rebuildTree(boolean keepExpansion) {
		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
		String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
		Severity minimum = Objects.requireNonNullElse(severityFilter.getValue(), Severity.LOW);
		SecurityScope scope = Objects.requireNonNullElse(scopeFilter.getValue(), SecurityScope.PLUGIN);
		SecurityFilter.Result result = SecurityFilter.apply(report.findings(), scope, minimum, words, PluginSecurityPane::searchText);

		TreeState previous = TreeState.of(tree.getRoot(), tree.getSelectionModel().getSelectedItem());
		Map<Severity, Map<SecurityRule, List<SecurityFinding>>> grouped = new EnumMap<>(Severity.class);
		for (SecurityFinding finding : result.shown())
			grouped.computeIfAbsent(finding.severity(), k -> new EnumMap<>(SecurityRule.class))
					.computeIfAbsent(finding.rule(), k -> new ArrayList<>()).add(finding);

		TreeItem<Item> root = new TreeItem<>();
		boolean searching = words.length > 0;
		grouped.forEach((severity, rules) -> {
			int count = rules.values().stream().mapToInt(List::size).sum();
			TreeItem<Item> severityItem = new TreeItem<>(Item.severity(severity, count));
			severityItem.setExpanded(searching || severity != Severity.LOW);
			rules.forEach((rule, findings) -> {
				TreeItem<Item> ruleItem = new TreeItem<>(Item.rule(rule, findings.size()));
				ruleItem.setExpanded(searching || (severity.isAtLeast(Severity.MEDIUM) && findings.size() <= 10));
				for (SecurityFinding finding : findings) {
					TreeItem<Item> findingItem = new TreeItem<>(Item.finding(finding));
					if (!finding.evidence().isEmpty()) {
						findingItem.setExpanded(severity == Severity.CRITICAL);
						for (SecurityFinding part : finding.evidence())
							findingItem.getChildren().add(new TreeItem<>(Item.evidence(part, finding)));
					}
					ruleItem.getChildren().add(findingItem);
				}
				severityItem.getChildren().add(ruleItem);
			});
			root.getChildren().add(severityItem);
		});

		rebuilding = true;
		try {
			if (keepExpansion)
				previous.restoreExpansion(root);
			tree.setRoot(root);
			TreeItem<Item> selected = previous.selected() == null ? null : find(root, previous.selected());
			if (selected != null) {
				tree.getSelectionModel().select(selected);
				FxThreadUtil.delayedRun(SCROLL_DELAY_MS, () -> tree.scrollTo(Math.max(0, tree.getRow(selected) - 2)));
			} else {
				FxThreadUtil.delayedRun(SCROLL_DELAY_MS, () -> tree.scrollTo(0));
			}
		} finally {
			rebuilding = false;
		}
		showSelection();
		showHiddenNotice(scope, result);
		showStatus(scope, result);
		treePlaceholder.setVisible(root.getChildren().isEmpty());
		treePlaceholder.setText(Lang.get(report.findings().isEmpty() ? "mcplugin.security.verdict.none"
				: result.inScope() == 0 ? "mcplugin.security.tree.empty-scope" : "mcplugin.security.tree.no-match"));
	}

	private void showHiddenNotice(@Nonnull SecurityScope scope, @Nonnull SecurityFilter.Result result) {
		boolean libraries = scope == SecurityScope.PLUGIN;
		String where = Lang.get(libraries ? "mcplugin.security.hidden.in-libraries" : "mcplugin.security.hidden.in-plugin");
		String text = null;
		if (scope != SecurityScope.EVERYTHING && result.inScope() == 0 && result.outOfScope() > 0)
			text = String.format(Lang.get(libraries ? "mcplugin.security.hidden.none-in-plugin" : "mcplugin.security.hidden.none-in-libraries"),
					result.outOfScope());
		else if (result.hiddenSeriousCount() > 0)
			text = String.format(Lang.get("mcplugin.security.hidden.serious"),
					SecurityDisplay.counts(result.hiddenSerious(), Severity.HIGH), where);
		hiddenText.setText(text);
		hiddenNotice.setVisible(text != null);
	}

	private void showStatus(@Nonnull SecurityScope scope, @Nonnull SecurityFilter.Result result) {
		String text = String.format(Lang.get("mcplugin.security.matches"), result.shown().size(), result.inScope());
		if (result.shown().isEmpty() && result.otherScopeMatches() > 0)
			text = String.format(Lang.get("mcplugin.security.matches.elsewhere"), text, result.otherScopeMatches(),
					Lang.get(scope == SecurityScope.PLUGIN ? "mcplugin.security.hidden.in-libraries" : "mcplugin.security.hidden.in-plugin"));
		status.setText(text);
	}

	@Nullable
	private static TreeItem<Item> find(@Nonnull TreeItem<Item> item, @Nonnull List<Object> key) {
		List<Object> own = key(item);
		if (own.equals(key))
			return item;
		if (own.size() >= key.size() || !key.subList(0, own.size()).equals(own))
			return null;
		for (TreeItem<Item> child : item.getChildren()) {
			TreeItem<Item> found = find(child, key);
			if (found != null)
				return found;
		}
		return null;
	}

	/**
	 * @return Identity of an entry among those of a tree, made of what it is and what it is under. Stays the same
	 * when the tree is rebuilt from a new report as long as the same thing is found at the same place.
	 */
	@Nonnull
	private static List<Object> key(@Nonnull TreeItem<Item> item) {
		List<Object> key = new ArrayList<>();
		for (TreeItem<Item> current = item; current != null && current.getValue() != null; current = current.getParent())
			key.addFirst(current.getValue().identity());
		return key;
	}

	@Nonnull
	private static String searchText(@Nonnull SecurityFinding finding) {
		return (finding.searchText() + ' ' + SecurityDisplay.title(finding.rule()) + ' ' +
				SecurityDisplay.name(finding.severity()) + ' ' + Objects.requireNonNullElse(finding.embeddedIn(), ""))
				.toLowerCase(Locale.ROOT);
	}

	private void showSelection() {
		previewDelay.stop();
		TreeItem<Item> selected = tree.getSelectionModel().getSelectedItem();
		Item item = selected == null ? null : selected.getValue();
		if (item == null) {
			detail.showNothing();
		} else if (item.severity() != null) {
			detail.showSeverity(item.severity(), item.count());
		} else if (item.rule() != null) {
			detail.showRule(item.rule(), item.count());
		} else {
			SecurityFinding finding = Objects.requireNonNull(item.finding());
			detail.showFinding(finding, item.parent());
			pendingPreview = finding;
			previewDelay.playFromStart();
		}
	}

	private void activate(@Nonnull TreeItem<Item> treeItem) {
		Item item = treeItem.getValue();
		if (item.finding() != null)
			open(item.finding());
		else
			treeItem.setExpanded(!treeItem.isExpanded());
	}

	private void open(@Nonnull SecurityFinding finding) {
		navigation.navigate(finding);
	}

	@Nonnull
	@Override
	public PathNode<?> getPath() {
		return workspacePath;
	}

	@Nonnull
	@Override
	public Collection<Navigable> getNavigableChildren() {
		return Collections.emptyList();
	}

	@Override
	public boolean isTrackable() {
		// Navigable only so that it gets closed with the workspace.
		return false;
	}

	@PreDestroy
	private void destroy() {
		disable();
	}

	@Override
	public void disable() {
		if (disabled)
			return;
		disabled = true;
		refreshDelay.stop();
		previewDelay.stop();
		semanticService.removeIndexListener(indexListener);
		tree.setRoot(null);
		detail.release();
	}

	/**
	 * What of the tree is kept when it is rebuilt: which entries were expanded, which existed, and the selection.
	 *
	 * @param known
	 * 		Keys of all entries that have children.
	 * @param expanded
	 * 		Keys of the entries with children that were expanded.
	 * @param selected
	 * 		Key of the selected entry, if any.
	 */
	private record TreeState(@Nonnull Set<List<Object>> known, @Nonnull Set<List<Object>> expanded,
	                         @Nullable List<Object> selected) {
		@Nonnull
		static TreeState of(@Nullable TreeItem<Item> root, @Nullable TreeItem<Item> selected) {
			Set<List<Object>> known = new HashSet<>();
			Set<List<Object>> expanded = new HashSet<>();
			if (root != null)
				collect(root, known, expanded);
			return new TreeState(known, expanded, selected == null || selected.getValue() == null ? null : key(selected));
		}

		private static void collect(@Nonnull TreeItem<Item> item, @Nonnull Set<List<Object>> known, @Nonnull Set<List<Object>> expanded) {
			if (item.getValue() != null && !item.isLeaf()) {
				List<Object> key = key(item);
				known.add(key);
				if (item.isExpanded())
					expanded.add(key);
			}
			for (TreeItem<Item> child : item.getChildren())
				collect(child, known, expanded);
		}

		void restoreExpansion(@Nonnull TreeItem<Item> item) {
			if (item.getValue() != null && !item.isLeaf()) {
				List<Object> key = key(item);
				if (known.contains(key))
					item.setExpanded(expanded.contains(key));
			}
			for (TreeItem<Item> child : item.getChildren())
				restoreExpansion(child);
		}
	}

	/**
	 * An entry in the tree: a severity group, a rule group, a finding, or a piece of evidence for a combined finding.
	 *
	 * @param severity
	 * 		Severity of a severity group.
	 * @param rule
	 * 		Rule of a rule group.
	 * @param count
	 * 		Number of findings in a group.
	 * @param finding
	 * 		The finding of a finding or evidence entry.
	 * @param parent
	 * 		The combined finding an evidence entry is part of, {@code null} for other entries.
	 */
	private record Item(@Nullable Severity severity, @Nullable SecurityRule rule, int count,
	                    @Nullable SecurityFinding finding, @Nullable SecurityFinding parent) {
		@Nonnull
		static Item severity(@Nonnull Severity severity, int count) {
			return new Item(severity, null, count, null, null);
		}

		@Nonnull
		static Item rule(@Nonnull SecurityRule rule, int count) {
			return new Item(null, rule, count, null, null);
		}

		@Nonnull
		static Item finding(@Nonnull SecurityFinding finding) {
			return new Item(null, null, 0, finding, null);
		}

		@Nonnull
		static Item evidence(@Nonnull SecurityFinding finding, @Nonnull SecurityFinding parent) {
			return new Item(null, null, 0, finding, parent);
		}

		boolean evidence() {
			return parent != null;
		}

		/**
		 * @return What the entry is, ignoring the counts that change with the filters.
		 */
		@Nonnull
		Object identity() {
			if (severity != null)
				return severity;
			if (rule != null)
				return rule;
			SecurityFinding found = Objects.requireNonNull(finding);
			return new FindingKey(evidence(), found.rule(), found.location(), found.file(), found.detail(), found.embeddedIn());
		}
	}

	private record FindingKey(boolean evidence, @Nonnull SecurityRule rule, @Nullable CodeLocation location,
	                          @Nullable String file, @Nullable String detail, @Nullable String embeddedIn) {}

	private static class SeverityCell extends ListCell<Severity> {
		@Override
		protected void updateItem(Severity item, boolean empty) {
			super.updateItem(item, empty);
			if (empty || item == null) {
				setText(null);
				setGraphic(null);
			} else if (item == Severity.LOW) {
				setText(Lang.get("mcplugin.security.severity.all"));
				setGraphic(null);
			} else {
				setText(String.format(Lang.get("mcplugin.security.severity.at-least"), SecurityDisplay.name(item)));
				setGraphic(SecurityDisplay.icon(item));
			}
		}
	}

	private class FindingCell extends TreeCell<Item> {
		private FindingCell() {
			setOnMouseClicked(e -> {
				Item item = getItem();
				if (item == null || isEmpty())
					return;
				if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && item.finding() != null) {
					open(item.finding());
					e.consume();
				} else if (e.getButton() == MouseButton.SECONDARY && getContextMenu() == null && item.finding() != null
						&& item.finding().location() != null) {
					// The regular context menu for the class or member, so all the usual actions are available.
					PathNode<?> path = navigation.resolve(item.finding().location());
					if (path != null)
						setContextMenu(cellConfigurationService.contextMenuOf(ContextSource.REFERENCE, path));
				}
			});
		}

		@Override
		protected void updateItem(Item item, boolean empty) {
			super.updateItem(item, empty);
			setContextMenu(null);
			setText(null);
			if (empty || item == null) {
				setGraphic(null);
				setTooltip(null);
				return;
			}
			HBox box = new HBox(8);
			box.setAlignment(Pos.CENTER_LEFT);
			if (item.severity() != null) {
				Label main = new Label(SecurityDisplay.name(item.severity()), SecurityDisplay.icon(item.severity()));
				main.getStyleClass().add(Styles.TEXT_BOLD);
				box.getChildren().addAll(main, subtle(String.valueOf(item.count())));
				setTooltip(null);
			} else if (item.rule() != null) {
				Label main = new Label(SecurityDisplay.title(item.rule()), new FontIconView(SecurityDisplay.icon(item.rule().category())));
				box.getChildren().addAll(main, subtle(String.valueOf(item.count())));
				setTooltip(new Tooltip(SecurityDisplay.tip(item.rule())));
			} else {
				SecurityFinding finding = Objects.requireNonNull(item.finding());
				CodeLocation location = finding.location();
				PathNode<?> path = location == null ? null : navigation.resolve(location);
				Node graphic = location == null ? new FontIconView(CarbonIcons.DOCUMENT)
						: path == null ? new FontIconView(CarbonIcons.WARNING_ALT) : cellConfigurationService.graphicOf(path);
				String name = location == null ? Objects.requireNonNull(finding.file()) : location.display();
				String text = item.evidence() ? SecurityDisplay.title(finding.rule()) + ": " + name : name;
				box.getChildren().add(new Label(text, graphic));
				if (location != null && finding.file() != null)
					box.getChildren().add(subtle(finding.file()));
				if (finding.detail() != null && !finding.detail().isBlank())
					box.getChildren().add(subtle(finding.detail()));
				if (finding.library())
					box.getChildren().add(tag(Lang.get("mcplugin.security.library")));
				if (finding.embeddedIn() != null)
					box.getChildren().add(tag(String.format(Lang.get("mcplugin.security.embedded"), fileName(finding.embeddedIn()))));
				setTooltip(new Tooltip((location == null ? finding.file() : location.toString()) + "\n\n" + SecurityDisplay.tip(finding.rule())));
			}
			setGraphic(box);
		}

		@Nonnull
		private static Label subtle(@Nonnull String text) {
			Label label = new Label(text);
			label.getStyleClass().add(Styles.TEXT_SUBTLE);
			return label;
		}

		@Nonnull
		private static Label tag(@Nonnull String text) {
			Label label = new Label(text);
			label.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
			label.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 3; -fx-padding: 0 4 0 4;");
			return label;
		}
	}
}
