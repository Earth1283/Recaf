package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.path.DockablePath;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.path.IncompletePathException;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginPlatform;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.analysis.plugin.semantic.PluginIndex;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.window.WindowManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.ui.window.PluginNameRecoveryWindow;
import software.coley.recaf.util.Animations;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.workspace.model.Workspace;

import static software.coley.recaf.util.Menus.action;

/**
 * Jumps between plugin structure and the code, and opens the plugin tools.
 */
@ApplicationScoped
public class PluginNavigation {
	private static final Logger logger = Logging.get(PluginNavigation.class);
	private final WorkspaceManager workspaceManager;
	private final PluginSemanticService semanticService;
	private final Actions actions;
	private final DockingManager dockingManager;
	private final WindowManager windowManager;
	private final Instance<PluginNavigatorPane> navigatorProvider;
	private final Instance<PluginNameRecoveryWindow> recoveryWindowProvider;

	@Inject
	public PluginNavigation(@Nonnull WorkspaceManager workspaceManager,
	                        @Nonnull PluginSemanticService semanticService,
	                        @Nonnull Actions actions,
	                        @Nonnull DockingManager dockingManager,
	                        @Nonnull WindowManager windowManager,
	                        @Nonnull Instance<PluginNavigatorPane> navigatorProvider,
	                        @Nonnull Instance<PluginNameRecoveryWindow> recoveryWindowProvider) {
		this.workspaceManager = workspaceManager;
		this.semanticService = semanticService;
		this.actions = actions;
		this.dockingManager = dockingManager;
		this.windowManager = windowManager;
		this.navigatorProvider = navigatorProvider;
		this.recoveryWindowProvider = recoveryWindowProvider;
	}

	/**
	 * @param location
	 * 		Location in the current workspace.
	 *
	 * @return Path to the class or member, or {@code null} if it no longer exists.
	 */
	@Nullable
	public PathNode<?> resolve(@Nullable CodeLocation location) {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null || location == null)
			return null;
		ClassPathNode classPath = workspace.findClass(location.className());
		if (classPath == null || location.isClass())
			return classPath;
		return classPath.child(location.memberName(), location.memberDescriptor());
	}

	/**
	 * Opens the class or member at the location.
	 *
	 * @param location
	 * 		Location in the current workspace.
	 *
	 * @return {@code true} when the location could be opened.
	 */
	public boolean navigate(@Nullable CodeLocation location) {
		PathNode<?> path = resolve(location);
		if (path == null) {
			logger.warn("Cannot open {}, it no longer exists. Refresh the plugin navigator.", location);
			return false;
		}
		try {
			actions.gotoDeclaration(path);
			return true;
		} catch (IncompletePathException ex) {
			logger.error("Cannot open {}", location, ex);
			return false;
		}
	}

	/**
	 * Opens the plugin manifest of the primary resource.
	 */
	public void openManifest() {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null)
			return;
		for (MinecraftPluginPlatform platform : MinecraftPluginPlatform.values()) {
			if (workspace.getPrimaryResource().getFileBundle().get(platform.manifestFileName()) == null)
				continue;
			FilePathNode path = workspace.findFile(platform.manifestFileName());
			if (path == null)
				continue;
			try {
				actions.gotoDeclaration(path);
			} catch (IncompletePathException ex) {
				logger.error("Cannot open {}", platform.manifestFileName(), ex);
			}
			return;
		}
	}

	/**
	 * For callers on the UI thread, which must never wait for an index to be built. Plugins are indexed in the
	 * background when opened and after their classes change, so the index is usually ready. When it is not, a build
	 * is started for next time, but only for workspaces with a plugin manifest, since other workspaces may be large.
	 *
	 * @return Index of the current workspace, or {@code null} if it is not ready right now.
	 */
	@Nullable
	public PluginIndex indexForImmediateUse() {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null)
			return null;
		PluginIndex cached = semanticService.getCachedIndex(workspace);
		if (cached == null && semanticService.hasManifest(workspace))
			semanticService.getIndexAsync(workspace);
		return cached;
	}

	/**
	 * @return {@code true} when the current workspace has a plugin manifest.
	 */
	public boolean isPluginWorkspace() {
		Workspace workspace = workspaceManager.getCurrent();
		return workspace != null && semanticService.hasManifest(workspace);
	}

	/**
	 * Opens the plugin navigator, or brings it to the front if it is already open.
	 *
	 * @param query
	 * 		Search text to show in the navigator, or {@code null} to keep the current search.
	 */
	public void openNavigator(@Nullable String query) {
		if (!workspaceManager.hasCurrentWorkspace())
			return;
		FxThreadUtil.run(() -> {
			for (DockablePath path : dockingManager.getBento().search().allDockables()) {
				Dockable dockable = path.dockable();
				Node node = dockable.nodeProperty().get();
				if (node instanceof PluginNavigatorPane navigator) {
					path.leafContainer().selectDockable(dockable);
					if (query != null)
						navigator.setQuery(query);
					navigator.requestFocus();
					Animations.animateNotice(navigator, 1000);
					return;
				}
			}

			// Open next to the workspace explorer so it can stay visible beside the code.
			DockContainerLeaf container = toolsContainer();
			PluginNavigatorPane navigator = navigatorProvider.get();
			Dockable dockable = dockingManager.newToolDockable("mcplugin.navigator.title", CarbonIcons.HEADPHONES, navigator);
			dockable.setClosable(true);
			dockable.addCloseListener((_, _) -> navigatorProvider.destroy(navigator));
			dockable.setContextMenuFactory(d -> {
				ContextMenu menu = new ContextMenu();
				menu.getItems().add(action("menu.tab.close", CarbonIcons.CLOSE, () -> d.inContainer(c -> c.closeDockable(d))));
				return menu;
			});
			if (query != null)
				navigator.setQuery(query);
			container.addDockable(dockable);
			container.selectDockable(dockable);
			navigator.requestFocus();
		});
	}

	/**
	 * Opens a window listing suggested names for the current workspace's plugin.
	 */
	public void openNameRecovery() {
		if (!workspaceManager.hasCurrentWorkspace())
			return;
		FxThreadUtil.run(() -> {
			PluginNameRecoveryWindow window = recoveryWindowProvider.get();
			window.setOnCloseRequest(e -> recoveryWindowProvider.destroy(window));
			window.show();
			window.requestFocus();
			windowManager.registerAnonymous(window);
		});
	}

	@Nonnull
	private DockContainerLeaf toolsContainer() {
		var path = dockingManager.getBento().search().container(DockingManager.ID_CONTAINER_WORKSPACE_TOOLS);
		if (path != null && path.tailContainer() instanceof DockContainerLeaf leaf)
			return leaf;
		return dockingManager.getPrimaryDockingContainer();
	}
}
