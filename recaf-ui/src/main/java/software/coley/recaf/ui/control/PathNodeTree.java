package software.coley.recaf.ui.control;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import jakarta.annotation.Nonnull;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import software.coley.recaf.path.IncompletePathException;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.cell.context.ContextSource;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.ui.control.tree.TreeItems;
import software.coley.recaf.ui.control.tree.WorkspaceTreeCell;

import java.util.List;
import java.util.function.Function;

/**
 * A {@link TreeView} handling {@link PathNode} content. Pre-configures the following:
 * <ul>
 *     <li>{@link TreeView#cellFactoryProperty()} - Text / graphic / context menu</li>
 *     <li>{@link TreeView#onKeyPressedProperty()} - Recursive expansion/closure, open selected paths with enter</li>
 *     <li>{@link TreeView#getStyleClass()} - Some additional css styles</li>
 * </ul>
 *
 * @author Matt Coley
 */
public class PathNodeTree extends TreeView<PathNode<?>> {
	private static final String EXTENDED_MENU_KEY = "path-tree-extended";
	protected final ObjectProperty<ContextSource> contextSourceObjectProperty = new SimpleObjectProperty<>(ContextSource.REFERENCE);
	protected final ObjectProperty<Function<PathNode<?>, List<MenuItem>>> contextMenuExtension = new SimpleObjectProperty<>();

	/**
	 * @param configurationService
	 * 		Cell service to configure tree cell rendering and population.
	 * @param actions
	 * 		Actions service to handle opening {@link PathNode} items.
	 */
	public PathNodeTree(@Nonnull CellConfigurationService configurationService, @Nonnull Actions actions) {
		setShowRoot(false);
		setCellFactory(param -> buildCell(configurationService));
		getStyleClass().addAll(Tweaks.EDGE_TO_EDGE, Styles.DENSE);
		setOnKeyPressed(e -> {
			KeyCode code = e.getCode();
			if (code == KeyCode.RIGHT || code == KeyCode.KP_RIGHT) {
				TreeItem<PathNode<?>> selected = getSelectionModel().getSelectedItem();
				if (selected != null)
					TreeItems.recurseOpen(selected);
			} else if (code == KeyCode.LEFT || code == KeyCode.KP_LEFT) {
				TreeItem<PathNode<?>> selected = getSelectionModel().getSelectedItem();
				if (selected != null)
					TreeItems.recurseClose(this, selected);
			} else if (code == KeyCode.ENTER) {
				TreeItem<PathNode<?>> selected = getSelectionModel().getSelectedItem();
				if (selected != null)
					handleEnter(actions, selected);
			}
		});
	}

	/**
	 * Called when the user presses {@link KeyCode#ENTER} on a given path.
	 *
	 * @param actions
	 * 		Actions service to handle opening {@link PathNode} items.
	 * @param selected
	 * 		Selected item holding a {@link PathNode}.
	 */
	protected void handleEnter(@Nonnull Actions actions, @Nonnull TreeItem<PathNode<?>> selected) {
		if (selected.isLeaf()) {
			try {
				actions.gotoDeclaration(selected.getValue());
			} catch (IncompletePathException ignored) {
				// ignored
			}
		} else {
			if (selected.isExpanded())
				TreeItems.recurseClose(this, selected);
			else
				TreeItems.recurseOpen(selected);
		}
	}

	/**
	 * @param configurationService
	 * 		Cell service to configure tree cell rendering and population.
	 *
	 * @return New tree cell to represent an item in this tree.
	 */
	@Nonnull
	protected WorkspaceTreeCell buildCell(@Nonnull CellConfigurationService configurationService) {
		ContextSource source = contextSourceObjectProperty.get();
		WorkspaceTreeCell cell = new WorkspaceTreeCell(source, configurationService);
		cell.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> extendContextMenu(cell, configurationService, source));
		return cell;
	}

	/**
	 * Appends {@link #contextMenuExtensionProperty() extension items} to the cell's context menu before it shows,
	 * creating the menu when the path type has none of its own.
	 */
	private void extendContextMenu(@Nonnull WorkspaceTreeCell cell, @Nonnull CellConfigurationService configurationService,
	                               @Nonnull ContextSource source) {
		Function<PathNode<?>, List<MenuItem>> extension = contextMenuExtension.get();
		PathNode<?> path = cell.getItem();
		if (extension == null || path == null)
			return;
		ContextMenu menu = cell.getContextMenu();
		if (menu != null && menu.getProperties().containsKey(EXTENDED_MENU_KEY))
			return;
		List<MenuItem> items = extension.apply(path);
		if (items.isEmpty())
			return;
		if (menu == null)
			menu = configurationService.contextMenuOf(source, path);
		if (menu == null)
			menu = new ContextMenu();
		if (!menu.getItems().isEmpty())
			menu.getItems().add(new SeparatorMenuItem());
		menu.getItems().addAll(items);
		menu.getProperties().put(EXTENDED_MENU_KEY, true);
		cell.setContextMenu(menu);
	}

	/**
	 * @return Property of a function giving extra context menu items for a path, appended to the path's own menu.
	 */
	@Nonnull
	public ObjectProperty<Function<PathNode<?>, List<MenuItem>>> contextMenuExtensionProperty() {
		return contextMenuExtension;
	}

	/**
	 * @return Property of the {@link ContextSource} passed to newly created {@link WorkspaceTreeCell} instances.
	 */
	@Nonnull
	public ObjectProperty<ContextSource> contextSourceObjectPropertyProperty() {
		return contextSourceObjectProperty;
	}
}
