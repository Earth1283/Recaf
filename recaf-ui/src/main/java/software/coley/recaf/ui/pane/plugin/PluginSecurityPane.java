package software.coley.recaf.ui.pane.plugin;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
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
import javafx.scene.layout.VBox;
import javafx.util.Duration;
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
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.cell.context.ContextSource;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lists what {@link PluginSecurityService} found in the current plugin, most severe first, grouped by severity and
 * then by what was found. Each finding opens the code it is about.
 */
@Dependent
public class PluginSecurityPane extends BorderPane implements Navigable {
	private static final Logger logger = Logging.get(PluginSecurityPane.class);
	private final TextField searchField = new TextField();
	private final ComboBox<Severity> severityFilter = new ComboBox<>();
	private final TreeView<Item> tree = new TreeView<>();
	private final Label verdict = new Label();
	private final Label scanned = new Label();
	private final Label encrypted = new Label();
	private final Label status = new Label();
	private final PauseTransition refreshDelay = new PauseTransition(Duration.millis(750));
	private final Consumer<Workspace> indexListener = workspace -> scheduleRefresh();
	private final PluginSecurityService securityService;
	private final PluginSemanticService semanticService;
	private final PluginNavigation navigation;
	private final CellConfigurationService cellConfigurationService;
	private final Workspace workspace;
	private final WorkspacePathNode workspacePath;
	private PluginSecurityReport report = PluginSecurityReport.EMPTY;
	private boolean disabled;

	@Inject
	public PluginSecurityPane(@Nonnull PluginSecurityService securityService,
	                          @Nonnull PluginSemanticService semanticService,
	                          @Nonnull PluginNavigation navigation,
	                          @Nonnull CellConfigurationService cellConfigurationService,
	                          @Nonnull WorkspaceManager workspaceManager) {
		this.securityService = securityService;
		this.semanticService = semanticService;
		this.navigation = navigation;
		this.cellConfigurationService = cellConfigurationService;
		this.workspace = Objects.requireNonNull(workspaceManager.getCurrent(), "Cannot open security triage without a workspace");
		this.workspacePath = PathNodes.workspacePath(workspace);

		searchField.promptTextProperty().bind(Lang.getBinding("mcplugin.security.search"));
		searchField.textProperty().addListener((ob, old, cur) -> rebuildTree());
		searchField.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.DOWN) {
				tree.requestFocus();
				if (tree.getSelectionModel().isEmpty())
					tree.getSelectionModel().selectFirst();
			}
		});
		severityFilter.getItems().addAll(Severity.values());
		severityFilter.setValue(Severity.LOW);
		severityFilter.setButtonCell(new SeverityCell());
		severityFilter.setCellFactory(l -> new SeverityCell());
		severityFilter.setTooltip(new Tooltip(Lang.get("mcplugin.security.min-severity")));
		severityFilter.valueProperty().addListener((ob, old, cur) -> rebuildTree());
		Button refresh = new Button(null, new FontIconView(CarbonIcons.RENEW));
		refresh.setTooltip(new Tooltip(Lang.get("mcplugin.security.refresh")));
		refresh.setOnAction(e -> refresh());
		Button settings = new Button(null, new FontIconView(CarbonIcons.SETTINGS));
		settings.setTooltip(new Tooltip(Lang.get("mcplugin.settings.tip")));
		settings.setOnAction(e -> navigation.openSettings("security"));
		// Two rows, since the pane usually sits in the narrow column beside the workspace explorer.
		severityFilter.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(severityFilter, Priority.ALWAYS);
		HBox controls = new HBox(6, severityFilter, refresh, settings);
		controls.setAlignment(Pos.CENTER_LEFT);
		VBox toolbar = new VBox(6, searchField, controls);

		verdict.getStyleClass().add(Styles.TITLE_4);
		verdict.setWrapText(true);
		scanned.getStyleClass().add(Styles.TEXT_SUBTLE);
		scanned.setWrapText(true);
		encrypted.setWrapText(true);
		encrypted.setGraphic(new FontIconView(CarbonIcons.VIEW, SecurityDisplay.color(Severity.MEDIUM)));
		encrypted.managedProperty().bind(encrypted.visibleProperty());
		encrypted.setVisible(false);
		Label disclaimer = new Label(Lang.get("mcplugin.security.disclaimer"));
		disclaimer.getStyleClass().addAll(Styles.TEXT_SUBTLE, Styles.TEXT_SMALL);
		disclaimer.setWrapText(true);
		VBox top = new VBox(6, toolbar, verdict, scanned, encrypted, disclaimer);
		top.setPadding(new Insets(6));

		tree.setShowRoot(false);
		tree.setCellFactory(t -> new FindingCell());
		tree.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.ENTER) {
				TreeItem<Item> selected = tree.getSelectionModel().getSelectedItem();
				if (selected != null && selected.getValue() != null)
					activate(selected);
			}
		});

		status.getStyleClass().add(Styles.TEXT_SUBTLE);
		status.setPadding(new Insets(4, 6, 4, 6));
		setTop(top);
		setCenter(tree);
		setBottom(status);

		refreshDelay.setOnFinished(e -> refresh());
		semanticService.addIndexListener(indexListener);
		refresh();
	}

	@Override
	public void requestFocus() {
		searchField.requestFocus();
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
				? String.format(Lang.get("mcplugin.security.scanned"), report.classesScanned())
				: String.format(Lang.get("mcplugin.security.scanned-embedded"), report.classesScanned(),
				report.embeddedJars().stream().map(PluginSecurityPane::fileName).reduce((a, b) -> a + ", " + b).orElse("")));
		scanned.setTooltip(report.embeddedJars().isEmpty() ? null : new Tooltip(String.join("\n", report.embeddedJars())));
		List<SecurityFinding> encryption = report.ofRule(SecurityRule.STRING_ENCRYPTION);
		encrypted.setVisible(!encryption.isEmpty());
		if (!encryption.isEmpty())
			encrypted.setText(String.format(Lang.get("mcplugin.security.encrypted-strings"),
					encryption.stream().map(PluginSecurityPane::describeEncryption).reduce((a, b) -> a + ", " + b).orElse("")));
		rebuildTree();
	}

	@Nonnull
	private static String describeEncryption(@Nonnull SecurityFinding finding) {
		String classes = Objects.requireNonNullElse(finding.detail(), "");
		return finding.embeddedIn() == null ? classes : classes + ' ' + String.format(Lang.get("mcplugin.security.embedded"), fileName(finding.embeddedIn()));
	}

	/**
	 * @return Last part of a path inside the plugin, which is enough to tell embedded jars apart in the narrow pane.
	 */
	@Nonnull
	private static String fileName(@Nonnull String path) {
		return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('>')) + 1).trim();
	}

	private void rebuildTree() {
		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
		String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
		Severity minimum = Objects.requireNonNullElse(severityFilter.getValue(), Severity.LOW);

		Map<Severity, Map<SecurityRule, List<SecurityFinding>>> grouped = new EnumMap<>(Severity.class);
		int shown = 0;
		for (SecurityFinding finding : report.findings()) {
			if (!finding.severity().isAtLeast(minimum) || !matchesAll(words, searchText(finding)))
				continue;
			grouped.computeIfAbsent(finding.severity(), k -> new EnumMap<>(SecurityRule.class))
					.computeIfAbsent(finding.rule(), k -> new ArrayList<>()).add(finding);
			shown++;
		}

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
							findingItem.getChildren().add(new TreeItem<>(Item.evidence(part)));
					}
					ruleItem.getChildren().add(findingItem);
				}
				severityItem.getChildren().add(ruleItem);
			});
			root.getChildren().add(severityItem);
		});
		tree.setRoot(root);
		status.setText(String.format(Lang.get("mcplugin.security.matches"), shown, report.findings().size()));
	}

	@Nonnull
	private static String searchText(@Nonnull SecurityFinding finding) {
		return (finding.searchText() + ' ' + SecurityDisplay.title(finding.rule()) + ' ' +
				SecurityDisplay.name(finding.severity()) + ' ' + Objects.requireNonNullElse(finding.embeddedIn(), ""))
				.toLowerCase(Locale.ROOT);
	}

	private static boolean matchesAll(@Nonnull String[] words, @Nonnull String text) {
		return Arrays.stream(words).allMatch(text::contains);
	}

	private void activate(@Nonnull TreeItem<Item> treeItem) {
		Item item = treeItem.getValue();
		if (item.finding() != null)
			navigation.navigate(item.finding().location());
		else
			treeItem.setExpanded(!treeItem.isExpanded());
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

	@Override
	public void disable() {
		disabled = true;
		refreshDelay.stop();
		semanticService.removeIndexListener(indexListener);
		tree.setRoot(null);
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
	 * @param evidence
	 *        {@code true} for evidence entries.
	 */
	private record Item(@Nullable Severity severity, @Nullable SecurityRule rule, int count,
	                    @Nullable SecurityFinding finding, boolean evidence) {
		@Nonnull
		static Item severity(@Nonnull Severity severity, int count) {
			return new Item(severity, null, count, null, false);
		}

		@Nonnull
		static Item rule(@Nonnull SecurityRule rule, int count) {
			return new Item(null, rule, count, null, false);
		}

		@Nonnull
		static Item finding(@Nonnull SecurityFinding finding) {
			return new Item(null, null, 0, finding, false);
		}

		@Nonnull
		static Item evidence(@Nonnull SecurityFinding finding) {
			return new Item(null, null, 0, finding, true);
		}
	}

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
					navigation.navigate(item.finding().location());
					e.consume();
				} else if (e.getButton() == MouseButton.SECONDARY && getContextMenu() == null && item.finding() != null) {
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
				PathNode<?> path = navigation.resolve(finding.location());
				Node graphic = path == null ? new FontIconView(CarbonIcons.WARNING_ALT) : cellConfigurationService.graphicOf(path);
				String text = item.evidence()
						? SecurityDisplay.title(finding.rule()) + ": " + finding.location().display()
						: finding.location().display();
				box.getChildren().add(new Label(text, graphic));
				if (finding.detail() != null && !finding.detail().isBlank())
					box.getChildren().add(subtle(finding.detail()));
				if (finding.library())
					box.getChildren().add(tag(Lang.get("mcplugin.security.library")));
				if (finding.embeddedIn() != null)
					box.getChildren().add(tag(String.format(Lang.get("mcplugin.security.embedded"), fileName(finding.embeddedIn()))));
				setTooltip(new Tooltip(finding.location() + "\n\n" + SecurityDisplay.tip(finding.rule())));
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
