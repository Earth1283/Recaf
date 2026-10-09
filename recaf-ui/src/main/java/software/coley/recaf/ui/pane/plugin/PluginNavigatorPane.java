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
import javafx.scene.control.Label;
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
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.path.WorkspacePathNode;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginManifest;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.analysis.plugin.semantic.NameHeuristics;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElement;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElementKind;
import software.coley.recaf.services.analysis.plugin.semantic.PluginIndex;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Searchable outline of a plugin's structure: entry points, commands, events, listeners, tasks, and the config paths,
 * permissions and other keys it uses. Every entry jumps to the code it was found in.
 *
 * @see PluginSemanticService
 */
@Dependent
public class PluginNavigatorPane extends BorderPane implements Navigable {
	private static final Logger logger = Logging.get(PluginNavigatorPane.class);
	private final TextField searchField = new TextField();
	private final TreeView<NavItem> tree = new TreeView<>();
	private final Label status = new Label();
	private final PauseTransition refreshDelay = new PauseTransition(Duration.millis(750));
	private final Consumer<Workspace> indexListener = workspace -> scheduleRefresh();
	private final PluginSemanticService semanticService;
	private final PluginNavigation navigation;
	private final CellConfigurationService cellConfigurationService;
	private final Workspace workspace;
	private final WorkspacePathNode workspacePath;
	private PluginIndex index = PluginIndex.EMPTY;
	private boolean disabled;

	@Inject
	public PluginNavigatorPane(@Nonnull PluginSemanticService semanticService,
	                           @Nonnull PluginNavigation navigation,
	                           @Nonnull CellConfigurationService cellConfigurationService,
	                           @Nonnull WorkspaceManager workspaceManager) {
		this.semanticService = semanticService;
		this.navigation = navigation;
		this.cellConfigurationService = cellConfigurationService;
		this.workspace = Objects.requireNonNull(workspaceManager.getCurrent(), "Cannot open plugin navigator without a workspace");
		this.workspacePath = PathNodes.workspacePath(workspace);

		// Search and actions at the top.
		searchField.promptTextProperty().bind(Lang.getBinding("mcplugin.navigator.search"));
		searchField.textProperty().addListener((ob, old, cur) -> rebuildTree());
		searchField.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.DOWN) {
				tree.requestFocus();
				if (tree.getSelectionModel().isEmpty())
					tree.getSelectionModel().selectFirst();
			}
		});
		Button refresh = new Button(null, new FontIconView(CarbonIcons.RENEW));
		refresh.setTooltip(new Tooltip(Lang.get("mcplugin.navigator.refresh")));
		refresh.setOnAction(e -> refresh());
		Button recover = new Button(null, new FontIconView(CarbonIcons.MAGIC_WAND));
		recover.setTooltip(new Tooltip(Lang.get("mcplugin.names.title")));
		recover.setOnAction(e -> navigation.openNameRecovery());
		HBox.setHgrow(searchField, Priority.ALWAYS);
		HBox top = new HBox(6, searchField, refresh, recover);
		top.setAlignment(Pos.CENTER_LEFT);
		top.setPadding(new Insets(6));

		// Outline in the middle.
		tree.setShowRoot(false);
		tree.setCellFactory(t -> new NavCell());
		tree.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.ENTER) {
				TreeItem<NavItem> selected = tree.getSelectionModel().getSelectedItem();
				if (selected != null && selected.getValue() != null)
					activate(selected.getValue());
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

	/**
	 * @param query
	 * 		Search text to filter the outline with.
	 */
	public void setQuery(@Nonnull String query) {
		searchField.setText(query);
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
		status.setText(Lang.get("mcplugin.navigator.indexing"));
		semanticService.getIndexAsync(workspace)
				.whenCompleteAsync((result, error) -> {
					if (disabled)
						return;
					if (error != null) {
						logger.error("Failed to index plugin structure", error);
						status.setText(Lang.get("mcplugin.navigator.failed"));
						return;
					}
					index = result;
					rebuildTree();
				}, FxThreadUtil.executor());
	}

	private void rebuildTree() {
		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
		String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
		TreeItem<NavItem> root = new TreeItem<>();
		int matches = 0;
		for (Section section : buildSections()) {
			TreeItem<NavItem> sectionItem = new TreeItem<>(NavItem.group(Lang.get(section.titleKey()), null, section.icon()));
			for (Group group : section.groups()) {
				String groupText = section.titleKey() + ' ' + Lang.get(section.titleKey()) + ' ' + group.label().text();
				TreeItem<NavItem> groupItem = new TreeItem<>(group.label());
				for (NavItem leaf : group.leaves()) {
					if (matchesAll(words, (groupText + ' ' + leaf.searchText()).toLowerCase(Locale.ROOT))) {
						groupItem.getChildren().add(new TreeItem<>(leaf));
						matches++;
					}
				}
				if (!groupItem.getChildren().isEmpty()) {
					groupItem.setExpanded(words.length > 0 || group.leaves().size() == 1);
					sectionItem.getChildren().add(groupItem);
				}
			}
			if (!sectionItem.getChildren().isEmpty()) {
				sectionItem.setExpanded(true);
				root.getChildren().add(sectionItem);
			}
		}
		tree.setRoot(root);

		if (index.isEmpty())
			status.setText(Lang.get("mcplugin.navigator.empty"));
		else if (words.length == 0)
			status.setText(String.format(Lang.get("mcplugin.navigator.count"), index.elements().size()));
		else
			status.setText(String.format(Lang.get("mcplugin.navigator.matches"), matches));
	}

	private static boolean matchesAll(@Nonnull String[] words, @Nonnull String text) {
		for (String word : words)
			if (!text.contains(word))
				return false;
		return true;
	}

	@Nonnull
	private List<Section> buildSections() {
		List<Section> sections = new ArrayList<>();
		MinecraftPluginManifest manifest = index.manifest();

		// Entry points, with their lifecycle methods.
		List<Group> entries = new ArrayList<>();
		for (PluginElementKind kind : List.of(PluginElementKind.MAIN_CLASS, PluginElementKind.BOOTSTRAPPER, PluginElementKind.LOADER)) {
			for (PluginElement entry : index.ofKind(kind)) {
				String className = entry.location().className();
				List<NavItem> leaves = new ArrayList<>();
				leaves.add(NavItem.code(entry.location().display(), null, entry));
				for (PluginElement lifecycle : index.ofKind(PluginElementKind.LIFECYCLE))
					if (lifecycle.location().className().equals(className))
						leaves.add(NavItem.code(lifecycle.location().display(), null, lifecycle));
				entries.add(new Group(NavItem.group(PluginElementDisplay.kindName(kind), entry.key(),
						PluginElementDisplay.icon(kind)), leaves));
			}
		}
		if (manifest != null)
			entries.add(new Group(NavItem.group(manifest.platform().manifestFileName(), manifest.platform().displayName(),
					CarbonIcons.DOCUMENT), List.of(NavItem.action(Lang.get("mcplugin.navigator.open-manifest"), null,
					CarbonIcons.DOCUMENT, navigation::openManifest))));
		addSection(sections, "mcplugin.navigator.section.plugin", CarbonIcons.LAUNCH, entries);

		// Commands, grouped by name. Declared commands with no code found are listed too.
		Map<String, List<NavItem>> commands = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (PluginElementKind kind : List.of(PluginElementKind.COMMAND, PluginElementKind.COMMAND_EXECUTOR, PluginElementKind.TAB_COMPLETER)) {
			for (PluginElement element : index.ofKind(kind)) {
				String label = switch (kind) {
					case COMMAND -> Lang.get("mcplugin.navigator.registered-in");
					case TAB_COMPLETER -> Lang.get("mcplugin.kind.tab_completer");
					default -> Lang.get("mcplugin.kind.command_executor");
				};
				commands.computeIfAbsent(PluginElementDisplay.keyText(element), k -> new ArrayList<>())
						.add(NavItem.code(label + ": " + element.location().display(), element.detail(), element));
			}
		}
		if (manifest != null)
			for (String command : manifest.commands())
				commands.computeIfAbsent('/' + command, k -> new ArrayList<>(List.of(NavItem.action(
						Lang.get("mcplugin.navigator.manifest-only"), null, CarbonIcons.DOCUMENT, navigation::openManifest))));
		addSection(sections, "mcplugin.navigator.section.commands", CarbonIcons.TERMINAL,
				groups(commands, CarbonIcons.TERMINAL));

		// Events, grouped by event class.
		Map<String, List<NavItem>> events = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		Map<String, Ikon> eventIcons = new HashMap<>();
		for (PluginElement handler : index.ofKind(PluginElementKind.EVENT_HANDLER))
			events.computeIfAbsent(eventLabel(handler.related()), k -> new ArrayList<>())
					.add(NavItem.code(handler.location().display(), handler.detail(), handler));
		for (PluginElement call : index.ofKind(PluginElementKind.EVENT_CALL))
			events.computeIfAbsent(eventLabel(call.related()), k -> new ArrayList<>())
					.add(NavItem.code(Lang.get("mcplugin.navigator.fired-in") + ": " + call.location().display(), null, call));
		for (PluginElement custom : index.ofKind(PluginElementKind.CUSTOM_EVENT)) {
			String label = eventLabel(custom.location());
			eventIcons.put(label, CarbonIcons.EVENTS);
			events.computeIfAbsent(label, k -> new ArrayList<>())
					.addFirst(NavItem.code(Lang.get("mcplugin.kind.custom_event") + ": " + custom.location().display(), custom.detail(), custom));
		}
		List<Group> eventGroups = new ArrayList<>();
		events.forEach((event, leaves) -> eventGroups.add(new Group(
				NavItem.group(event, String.valueOf(leaves.size()), eventIcons.getOrDefault(event, CarbonIcons.EVENT)), leaves)));
		addSection(sections, "mcplugin.navigator.section.events", CarbonIcons.EVENT, eventGroups);

		// Listeners, with the handlers they declare and where they are registered.
		Map<String, List<NavItem>> listeners = new TreeMap<>();
		for (PluginElement listener : index.ofKind(PluginElementKind.LISTENER)) {
			String className = listener.location().className();
			List<NavItem> leaves = listeners.computeIfAbsent(className, k -> {
				List<NavItem> items = new ArrayList<>();
				items.add(NavItem.code(listener.location().display(), null, listener));
				for (PluginElement handler : index.ofKind(PluginElementKind.EVENT_HANDLER))
					if (handler.location().className().equals(className))
						items.add(NavItem.code(handler.location().display(), handler.key(), handler));
				return items;
			});
			if (listener.related() != null)
				leaves.add(NavItem.related(Lang.get("mcplugin.navigator.registered-in") + ": " + listener.related().display(), null, listener));
		}
		List<Group> listenerGroups = new ArrayList<>();
		listeners.forEach((className, leaves) -> listenerGroups.add(new Group(
				NavItem.group(NameHeuristics.simpleName(className), null, CarbonIcons.HEADPHONES), leaves)));
		addSection(sections, "mcplugin.navigator.section.listeners", CarbonIcons.HEADPHONES, listenerGroups);

		// Tasks, with where they are scheduled.
		Map<String, List<NavItem>> tasks = new TreeMap<>();
		for (PluginElement task : index.ofKind(PluginElementKind.TASK)) {
			List<NavItem> leaves = tasks.computeIfAbsent(task.location().display(), k -> new ArrayList<>(
					List.of(NavItem.code(task.location().display(), null, task))));
			if (task.related() != null)
				leaves.add(NavItem.related(Lang.get("mcplugin.navigator.scheduled-in") + ": " + task.related().display(),
						task.key(), task));
		}
		addSection(sections, "mcplugin.navigator.section.tasks", CarbonIcons.TIMER, groups(tasks, CarbonIcons.TIMER));

		// Keys, grouped by value.
		Map<String, List<NavItem>> permissions = keyGroups(PluginElementKind.PERMISSION);
		if (manifest != null)
			for (String permission : manifest.permissions())
				permissions.computeIfAbsent(permission, k -> new ArrayList<>(List.of(NavItem.action(
						Lang.get("mcplugin.navigator.manifest-only"), null, CarbonIcons.DOCUMENT, navigation::openManifest))));
		addSection(sections, "mcplugin.navigator.section.config", CarbonIcons.SETTINGS_ADJUST,
				groups(keyGroups(PluginElementKind.CONFIG_KEY), CarbonIcons.SETTINGS_ADJUST));
		addSection(sections, "mcplugin.navigator.section.permissions", CarbonIcons.LOCKED,
				groups(permissions, CarbonIcons.LOCKED));
		for (PluginElementKind kind : List.of(PluginElementKind.CHANNEL, PluginElementKind.NAMESPACED_KEY,
				PluginElementKind.METADATA_KEY, PluginElementKind.PLUGIN_HOOK))
			addSection(sections, "mcplugin.kind." + kind.name().toLowerCase(Locale.ROOT), PluginElementDisplay.icon(kind),
					groups(keyGroups(kind), PluginElementDisplay.icon(kind)));

		// Other notable types.
		List<Group> types = new ArrayList<>();
		for (PluginElementKind kind : List.of(PluginElementKind.SERIALIZABLE, PluginElementKind.INVENTORY_HOLDER,
				PluginElementKind.PLACEHOLDER_EXPANSION, PluginElementKind.MESSAGE_LISTENER)) {
			List<NavItem> leaves = new ArrayList<>();
			for (PluginElement element : index.ofKind(kind))
				leaves.add(NavItem.code(element.location().display(), element.key(), element));
			if (!leaves.isEmpty())
				types.add(new Group(NavItem.group(PluginElementDisplay.kindName(kind), String.valueOf(leaves.size()),
						PluginElementDisplay.icon(kind)), leaves));
		}
		addSection(sections, "mcplugin.navigator.section.types", CarbonIcons.DATA_STRUCTURED, types);
		return sections;
	}

	@Nonnull
	private Map<String, List<NavItem>> keyGroups(@Nonnull PluginElementKind kind) {
		Map<String, List<NavItem>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (PluginElement element : index.ofKind(kind))
			groups.computeIfAbsent(element.key(), k -> new ArrayList<>())
					.add(NavItem.code(element.location().display(), element.detail(), element));
		return groups;
	}

	@Nonnull
	private static List<Group> groups(@Nonnull Map<String, List<NavItem>> map, @Nonnull Ikon icon) {
		List<Group> groups = new ArrayList<>(map.size());
		map.forEach((label, leaves) -> groups.add(new Group(NavItem.group(label, String.valueOf(leaves.size()), icon), leaves)));
		return groups;
	}

	private static void addSection(@Nonnull List<Section> sections, @Nonnull String titleKey,
	                               @Nonnull Ikon icon, @Nonnull List<Group> groups) {
		if (!groups.isEmpty())
			sections.add(new Section(titleKey, icon, groups));
	}

	@Nonnull
	private static String eventLabel(@Nullable CodeLocation event) {
		return event == null ? Lang.get("mcplugin.key.unknown") : NameHeuristics.simpleName(event.className());
	}

	private void activate(@Nonnull NavItem item) {
		if (item.action() != null)
			item.action().run();
		else if (item.location() != null)
			navigation.navigate(item.location());
		else if (item.text() != null && tree.getSelectionModel().getSelectedItem() != null) {
			TreeItem<NavItem> selected = tree.getSelectionModel().getSelectedItem();
			selected.setExpanded(!selected.isExpanded());
		}
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
	 * @param titleKey
	 * 		Translation key of the section title.
	 * @param icon
	 * 		Section icon.
	 * @param groups
	 * 		Groups in the section.
	 */
	private record Section(@Nonnull String titleKey, @Nonnull Ikon icon, @Nonnull List<Group> groups) {}

	/**
	 * @param label
	 * 		Item shown for the group.
	 * @param leaves
	 * 		Items in the group.
	 */
	private record Group(@Nonnull NavItem label, @Nonnull List<NavItem> leaves) {}

	/**
	 * An entry in the outline.
	 *
	 * @param text
	 * 		Main text.
	 * @param detail
	 * 		Secondary text, shown dimmed.
	 * @param icon
	 * 		Icon, or {@code null} to use the icon of the location.
	 * @param location
	 * 		Code to jump to.
	 * @param action
	 * 		Action to run instead of jumping to code.
	 * @param element
	 * 		Element the entry is for, used for searching.
	 */
	private record NavItem(@Nonnull String text, @Nullable String detail, @Nullable Ikon icon,
	                       @Nullable CodeLocation location, @Nullable Runnable action,
	                       @Nullable PluginElement element) {
		@Nonnull
		static NavItem group(@Nonnull String text, @Nullable String detail, @Nonnull Ikon icon) {
			return new NavItem(text, detail, icon, null, null, null);
		}

		@Nonnull
		static NavItem code(@Nonnull String text, @Nullable String detail, @Nonnull PluginElement element) {
			return new NavItem(text, detail, null, element.location(), null, element);
		}

		@Nonnull
		static NavItem related(@Nonnull String text, @Nullable String detail, @Nonnull PluginElement element) {
			return new NavItem(text, detail, CarbonIcons.JUMP_LINK, element.related(), null, element);
		}

		@Nonnull
		static NavItem action(@Nonnull String text, @Nullable String detail, @Nonnull Ikon icon, @Nonnull Runnable action) {
			return new NavItem(text, detail, icon, null, action, null);
		}

		@Nonnull
		String searchText() {
			String text = this.text + ' ' + Objects.requireNonNullElse(detail, "");
			if (element != null)
				text += ' ' + element.searchText();
			return text;
		}
	}

	private class NavCell extends TreeCell<NavItem> {
		private final Function<NavItem, Node> graphicFactory = item -> {
			if (item.icon() != null)
				return new FontIconView(item.icon());
			PathNode<?> path = navigation.resolve(item.location());
			return path == null ? new FontIconView(CarbonIcons.WARNING_ALT) : cellConfigurationService.graphicOf(path);
		};

		private NavCell() {
			setOnMouseClicked(e -> {
				NavItem item = getItem();
				if (item == null || isEmpty())
					return;
				if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && (item.location() != null || item.action() != null)) {
					activate(item);
					e.consume();
				} else if (e.getButton() == MouseButton.SECONDARY && getContextMenu() == null && item.location() != null) {
					// The regular context menu for the class or member, so all the usual actions are available.
					PathNode<?> path = navigation.resolve(item.location());
					if (path != null)
						setContextMenu(cellConfigurationService.contextMenuOf(ContextSource.REFERENCE, path));
				}
			});
		}

		@Override
		protected void updateItem(NavItem item, boolean empty) {
			super.updateItem(item, empty);
			setContextMenu(null);
			if (empty || item == null) {
				setText(null);
				setGraphic(null);
				setTooltip(null);
				return;
			}
			Label main = new Label(item.text(), graphicFactory.apply(item));
			HBox box = new HBox(8, main);
			box.setAlignment(Pos.CENTER_LEFT);
			if (item.detail() != null && !item.detail().isBlank()) {
				Label detail = new Label(item.detail());
				detail.getStyleClass().add(Styles.TEXT_SUBTLE);
				box.getChildren().add(detail);
			}
			if (item.location() != null)
				setTooltip(new Tooltip(item.location().toString()));
			else
				setTooltip(null);
			setText(null);
			setGraphic(box);
		}
	}
}
