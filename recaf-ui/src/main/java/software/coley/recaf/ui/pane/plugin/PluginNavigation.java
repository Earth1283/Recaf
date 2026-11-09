package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import org.kordamp.ikonli.Ikon;
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

import java.util.function.Consumer;

import static software.coley.recaf.util.Menus.action;

/**
 * Jumps between plugin structure and the code, and opens the plugin tools.
 */
@ApplicationScoped
public class PluginNavigation {
	/** Matches the translated settings page names of all Minecraft plugin features. */
	private static final String SETTINGS_QUERY = "minecraft plugin";
	private static final Logger logger = Logging.get(PluginNavigation.class);
	private final WorkspaceManager workspaceManager;
	private final PluginSemanticService semanticService;
	private final Actions actions;
	private final DockingManager dockingManager;
	private final WindowManager windowManager;
	private final Instance<PluginNavigatorPane> navigatorProvider;
	private final Instance<PluginNameRecoveryWindow> recoveryWindowProvider;
	private final Instance<PluginSecurityPane> securityProvider;

	@Inject
	public PluginNavigation(@Nonnull WorkspaceManager workspaceManager,
	                        @Nonnull PluginSemanticService semanticService,
	                        @Nonnull Actions actions,
	                        @Nonnull DockingManager dockingManager,
	                        @Nonnull WindowManager windowManager,
	                        @Nonnull Instance<PluginNavigatorPane> navigatorProvider,
	                        @Nonnull Instance<PluginNameRecoveryWindow> recoveryWindowProvider,
	                        @Nonnull Instance<PluginSecurityPane> securityProvider) {
		this.workspaceManager = workspaceManager;
		this.semanticService = semanticService;
		this.actions = actions;
		this.dockingManager = dockingManager;
		this.windowManager = windowManager;
		this.navigatorProvider = navigatorProvider;
		this.recoveryWindowProvider = recoveryWindowProvider;
		this.securityProvider = securityProvider;
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
		openTool(PluginNavigatorPane.class, navigatorProvider, "mcplugin.navigator.title", CarbonIcons.HEADPHONES, navigator -> {
			if (query != null)
				navigator.setQuery(query);
		});
	}

	/**
	 * Opens the settings, filtered to those of the Minecraft plugin features.
	 *
	 * @param page
	 * 		Word naming one feature's page, such as {@code security}, or {@code null} to show all of them.
	 */
	public void openSettings(@Nullable String page) {
		String query = page == null ? SETTINGS_QUERY : SETTINGS_QUERY + ' ' + page;
		FxThreadUtil.run(() -> actions.openConfig(query));
	}

	/**
	 * Opens the security triage of the current workspace's plugin, or brings it to the front if it is already open.
	 */
	public void openSecurityTriage() {
		openTool(PluginSecurityPane.class, securityProvider, "mcplugin.security.title", CarbonIcons.SECURITY, pane -> {});
	}

	/**
	 * Opens a pane next to the workspace explorer, so it can stay visible beside the code it links to.
	 * If one is already open it is brought to the front instead.
	 */
	private <T extends Node> void openTool(@Nonnull Class<T> type, @Nonnull Instance<T> provider,
	                                       @Nonnull String titleKey, @Nonnull Ikon icon, @Nonnull Consumer<T> onShow) {
		if (!workspaceManager.hasCurrentWorkspace())
			return;
		FxThreadUtil.run(() -> {
			for (DockablePath path : dockingManager.getBento().search().allDockables()) {
				Dockable dockable = path.dockable();
				Node node = dockable.nodeProperty().get();
				if (type.isInstance(node)) {
					T pane = type.cast(node);
					path.leafContainer().selectDockable(dockable);
					onShow.accept(pane);
					pane.requestFocus();
					Animations.animateNotice(pane, 1000);
					return;
				}
			}

			DockContainerLeaf container = toolsContainer();
			T pane = provider.get();
			Dockable dockable = dockingManager.newToolDockable(titleKey, icon, pane);
			dockable.setClosable(true);
			dockable.addCloseListener((_, _) -> provider.destroy(pane));
			dockable.setContextMenuFactory(d -> {
				ContextMenu menu = new ContextMenu();
				menu.getItems().add(action("menu.tab.close", CarbonIcons.CLOSE, () -> d.inContainer(c -> c.closeDockable(d))));
				return menu;
			});
			onShow.accept(pane);
			container.addDockable(dockable);
			container.selectDockable(dockable);
			pane.requestFocus();
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
