package software.coley.recaf.ui.pane.plugin;

import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.analysis.plugin.semantic.NameSuggestion;
import software.coley.recaf.services.analysis.plugin.semantic.NamingClue;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService.MappingPlan;
import software.coley.recaf.services.mapping.MappingApplier;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Lists names suggested for an obfuscated plugin, with the evidence for each, so the user can choose which to apply.
 * Suggestions can be searched, filtered by kind and clue, and renamed before applying. Double-clicking a row opens the
 * code it is for.
 *
 * @see PluginSemanticService#suggestNames(Workspace)
 */
@Dependent
public class PluginNameRecoveryPane extends BorderPane {
	private static final Logger logger = Logging.get(PluginNameRecoveryPane.class);
	private final ObservableList<Row> rows = FXCollections.observableArrayList();
	private final FilteredList<Row> filtered = new FilteredList<>(rows);
	private final TableView<Row> table = new TableView<>();
	private final TextField searchField = new TextField();
	private final ComboBox<NamingClue> clueFilter = new ComboBox<>();
	private final ComboBox<String> targetFilter = new ComboBox<>();
	private final CheckBox obfuscatedOnly = new CheckBox();
	private final Label status = new Label();
	private final ProgressIndicator progress = new ProgressIndicator();
	private final SimpleIntegerProperty selectedCount = new SimpleIntegerProperty();
	private final BooleanProperty busy = new SimpleBooleanProperty();
	private final PluginSemanticService semanticService;
	private final MappingApplierService mappingApplierService;
	private final PluginNavigation navigation;
	private final Workspace workspace;

	@Inject
	public PluginNameRecoveryPane(@Nonnull PluginSemanticService semanticService,
	                              @Nonnull MappingApplierService mappingApplierService,
	                              @Nonnull PluginNavigation navigation,
	                              @Nonnull WorkspaceManager workspaceManager) {
		this.semanticService = semanticService;
		this.mappingApplierService = mappingApplierService;
		this.navigation = navigation;
		this.workspace = Objects.requireNonNull(workspaceManager.getCurrent(), "Cannot recover names without a workspace");

		setTop(createFilterBar());
		setCenter(createTable());
		setBottom(createActionBar());
		load(null);
	}

	@Nonnull
	private HBox createFilterBar() {
		searchField.promptTextProperty().bind(Lang.getBinding("mcplugin.names.search"));
		HBox.setHgrow(searchField, Priority.ALWAYS);

		clueFilter.getItems().add(null);
		clueFilter.getItems().addAll(NamingClue.values());
		clueFilter.setConverter(new StringConverter<>() {
			@Override
			public String toString(NamingClue clue) {
				return clue == null ? Lang.get("mcplugin.names.filter.all-clues") : PluginElementDisplay.clueName(clue);
			}

			@Override
			public NamingClue fromString(String string) {
				return null;
			}
		});
		clueFilter.getSelectionModel().selectFirst();

		List<String> targetKinds = List.of("mcplugin.names.filter.all-targets", "mcplugin.target.class",
				"mcplugin.target.field", "mcplugin.target.method", "mcplugin.target.variable");
		targetFilter.getItems().addAll(targetKinds);
		targetFilter.setConverter(new StringConverter<>() {
			@Override
			public String toString(String key) {
				return key == null ? "" : Lang.get(key);
			}

			@Override
			public String fromString(String string) {
				return null;
			}
		});
		targetFilter.getSelectionModel().selectFirst();

		obfuscatedOnly.textProperty().bind(Lang.getBinding("mcplugin.names.filter.obfuscated"));
		obfuscatedOnly.setTooltip(new Tooltip(Lang.get("mcplugin.names.filter.obfuscated.tip")));
		obfuscatedOnly.setSelected(true);

		searchField.textProperty().addListener((ob, old, cur) -> updateFilter());
		clueFilter.valueProperty().addListener((ob, old, cur) -> updateFilter());
		targetFilter.valueProperty().addListener((ob, old, cur) -> updateFilter());
		obfuscatedOnly.selectedProperty().addListener((ob, old, cur) -> updateFilter());
		updateFilter();

		HBox bar = new HBox(8, searchField, targetFilter, clueFilter, obfuscatedOnly);
		bar.setAlignment(Pos.CENTER_LEFT);
		bar.setPadding(new Insets(8));
		return bar;
	}

	@Nonnull
	private TableView<Row> createTable() {
		TableColumn<Row, Boolean> selectColumn = new TableColumn<>();
		selectColumn.setGraphic(new FontIconView(CarbonIcons.CHECKBOX_CHECKED));
		selectColumn.setCellValueFactory(c -> c.getValue().selected);
		selectColumn.setCellFactory(CheckBoxTableCell.forTableColumn(selectColumn));
		selectColumn.setEditable(true);
		selectColumn.setSortable(false);
		selectColumn.setPrefWidth(36);

		TableColumn<Row, String> kindColumn = column("mcplugin.names.column.kind", 80,
				row -> PluginElementDisplay.targetKindName(row.suggestion.target()));
		TableColumn<Row, String> currentColumn = column("mcplugin.names.column.current", 260,
				row -> row.suggestion.target().describe());

		TableColumn<Row, String> nameColumn = new TableColumn<>();
		nameColumn.textProperty().bind(Lang.getBinding("mcplugin.names.column.new"));
		nameColumn.setCellValueFactory(c -> c.getValue().name);
		nameColumn.setCellFactory(TextFieldTableCell.forTableColumn());
		nameColumn.setOnEditCommit(e -> {
			Row row = e.getRowValue();
			String value = e.getNewValue() == null ? "" : e.getNewValue().trim();
			if (!value.isEmpty()) {
				row.name.set(value);
				row.selected.set(true);
			}
			table.refresh();
		});
		nameColumn.setEditable(true);
		nameColumn.setPrefWidth(180);

		TableColumn<Row, String> clueColumn = column("mcplugin.names.column.clue", 110,
				row -> PluginElementDisplay.clueName(row.suggestion.clue()));
		TableColumn<Row, Integer> confidenceColumn = new TableColumn<>();
		confidenceColumn.textProperty().bind(Lang.getBinding("mcplugin.names.column.confidence"));
		confidenceColumn.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().suggestion.confidence()));
		confidenceColumn.setPrefWidth(100);
		TableColumn<Row, String> reasonColumn = column("mcplugin.names.column.reason", 360,
				row -> row.suggestion.reason());

		table.getColumns().addAll(List.of(selectColumn, kindColumn, currentColumn, nameColumn, clueColumn,
				confidenceColumn, reasonColumn));
		table.setEditable(true);
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
		table.setPlaceholder(new Label(Lang.get("mcplugin.names.empty")));
		SortedList<Row> sorted = new SortedList<>(filtered);
		sorted.comparatorProperty().bind(table.comparatorProperty());
		table.setItems(sorted);
		table.setRowFactory(t -> {
			TableRow<Row> row = new TableRow<>();
			row.setOnMouseClicked(e -> {
				if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty())
					navigation.navigate(row.getItem().suggestion.target().location());
			});
			return row;
		});
		return table;
	}

	@Nonnull
	private static TableColumn<Row, String> column(@Nonnull String titleKey, double width,
	                                               @Nonnull Function<Row, String> mapper) {
		TableColumn<Row, String> column = new TableColumn<>();
		column.textProperty().bind(Lang.getBinding(titleKey));
		column.setCellValueFactory(c -> new SimpleStringProperty(mapper.apply(c.getValue())));
		column.setEditable(false);
		column.setPrefWidth(width);
		return column;
	}

	@Nonnull
	private HBox createActionBar() {
		Button selectShown = new Button(Lang.get("mcplugin.names.select-shown"));
		selectShown.setOnAction(e -> filtered.forEach(row -> row.selected.set(true)));
		Button deselectShown = new Button(Lang.get("mcplugin.names.deselect-shown"));
		deselectShown.setOnAction(e -> filtered.forEach(row -> row.selected.set(false)));
		Button recommended = new Button(Lang.get("mcplugin.names.select-recommended"));
		recommended.setTooltip(new Tooltip(Lang.get("mcplugin.names.select-recommended.tip")));
		recommended.setOnAction(e -> rows.forEach(row -> row.selected.set(row.suggestion.isRecommended())));

		Button apply = new Button(null, new FontIconView(CarbonIcons.CHECKMARK));
		apply.textProperty().bind(Lang.getBinding("mcplugin.names.apply"));
		apply.getStyleClass().add(Styles.ACCENT);
		apply.disableProperty().bind(selectedCount.isEqualTo(0).or(busy));
		apply.setOnAction(e -> apply());

		progress.setPrefSize(18, 18);
		progress.visibleProperty().bind(busy);
		status.getStyleClass().add(Styles.TEXT_SUBTLE);
		status.setWrapText(true);
		HBox.setHgrow(status, Priority.ALWAYS);
		status.setMaxWidth(Double.MAX_VALUE);

		HBox bar = new HBox(8, selectShown, deselectShown, recommended, progress, status, apply);
		bar.setAlignment(Pos.CENTER_LEFT);
		bar.setPadding(new Insets(8));
		return bar;
	}

	private void updateFilter() {
		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
		String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
		NamingClue clue = clueFilter.getValue();
		String target = targetFilter.getValue();
		boolean onlyObfuscated = obfuscatedOnly.isSelected();
		Predicate<Row> predicate = row -> {
			NameSuggestion suggestion = row.suggestion;
			if (onlyObfuscated && !suggestion.currentLooksObfuscated())
				return false;
			if (clue != null && suggestion.clue() != clue)
				return false;
			if (target != null && !target.equals("mcplugin.names.filter.all-targets") &&
					!Lang.get(target).equals(PluginElementDisplay.targetKindName(suggestion.target())))
				return false;
			return Arrays.stream(words).allMatch(word -> suggestion.matches(word) ||
					row.name.get().toLowerCase(Locale.ROOT).contains(word));
		};
		filtered.setPredicate(predicate);
		updateStatus(null);
	}

	private void updateStatus(@Nullable String message) {
		String counts = String.format(Lang.get("mcplugin.names.status"), selectedCount.get(), rows.size(), filtered.size());
		status.setText(message == null ? counts : message + "  " + counts);
	}

	/**
	 * @param message
	 * 		Message to show once loaded, or {@code null} for none.
	 */
	private void load(@Nullable String message) {
		busy.set(true);
		CompletableFuture.supplyAsync(() -> semanticService.suggestNames(workspace))
				.whenCompleteAsync((suggestions, error) -> {
					busy.set(false);
					if (error != null) {
						logger.error("Failed to suggest names", error);
						updateStatus(Lang.get("mcplugin.names.failed"));
						return;
					}
					List<Row> loaded = new ArrayList<>(suggestions.size());
					for (NameSuggestion suggestion : suggestions) {
						Row row = new Row(suggestion);
						row.selected.addListener((ob, old, cur) -> {
							selectedCount.set(selectedCount.get() + (cur ? 1 : -1));
							updateStatus(null);
						});
						loaded.add(row);
					}
					rows.setAll(loaded);
					selectedCount.set((int) loaded.stream().filter(r -> r.selected.get()).count());
					updateStatus(message);
				}, FxThreadUtil.executor());
	}

	private void apply() {
		List<NameSuggestion> chosen = rows.stream()
				.filter(row -> row.selected.get())
				.map(row -> row.suggestion.withName(row.name.get()))
				.toList();
		if (chosen.isEmpty())
			return;
		busy.set(true);
		CompletableFuture.supplyAsync(() -> {
			MappingPlan plan = semanticService.createMappings(workspace, chosen);
			MappingApplier applier = mappingApplierService.inCurrentWorkspace();
			if (applier == null)
				throw new IllegalStateException("No workspace to apply mappings to");
			if (plan.count() > 0)
				applier.applyToPrimaryResource(plan.mappings()).apply();
			return plan;
		}).whenCompleteAsync((plan, error) -> {
			busy.set(false);
			if (error != null) {
				logger.error("Failed to apply recovered names", error);
				updateStatus(Lang.get("mcplugin.names.failed"));
				return;
			}
			for (String problem : plan.problems())
				logger.warn("Skipped name: {}", problem);
			String message = plan.problems().isEmpty() ?
					String.format(Lang.get("mcplugin.names.applied"), plan.count()) :
					String.format(Lang.get("mcplugin.names.applied-skipped"), plan.count(), plan.problems().size());
			logger.info(message);

			// What is left are the suggestions that still apply after renaming.
			load(message);
		}, FxThreadUtil.executor());
	}

	/**
	 * A suggestion, whether it is chosen, and the name to use, which the user may have changed.
	 */
	private static final class Row {
		private final NameSuggestion suggestion;
		private final BooleanProperty selected;
		private final StringProperty name;

		private Row(@Nonnull NameSuggestion suggestion) {
			this.suggestion = suggestion;
			this.selected = new SimpleBooleanProperty(suggestion.isRecommended());
			this.name = new SimpleStringProperty(suggestion.suggestedName());
		}
	}
}
