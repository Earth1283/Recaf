package software.coley.recaf;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.cdi.UiInitializationEvent;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.navigation.NavigationHistoryService;
import software.coley.recaf.services.navigation.NavigationManager;
import software.coley.recaf.services.window.WindowManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.RecafTheme;
import software.coley.recaf.ui.config.KeybindingConfig;
import software.coley.recaf.ui.config.WindowScaleConfig;
import software.coley.recaf.ui.control.ActionButton;
import software.coley.recaf.ui.control.DoubleShiftShortcut;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.ui.menubar.MainMenu;
import software.coley.recaf.ui.window.QuickNavWindow;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Icons;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.PathExportingManager;

/**
 * JavaFX application entry point.
 *
 * @author Matt Coley
 */
public class RecafApplication extends Application {
	private final Recaf recaf = Bootstrap.get();

	@Override
	public void start(Stage stage) {
		// Notify the thread util that FX has been initialized
		FxThreadUtil.onInitialize();

		// Setup global style
		setUserAgentStylesheet(new RecafTheme().getUserAgentStylesheet());

		// Initialize the navigation manager before we get the services below.
		// We want it to get a head start on initalization.
		recaf.get(NavigationManager.class).requestFocus();

		// Get services
		DockingManager dockingManager = recaf.get(DockingManager.class);
		KeybindingConfig keybindingConfig = recaf.get(KeybindingConfig.class);
		NavigationHistoryService navigationHistoryService = recaf.get(NavigationHistoryService.class);
		WindowManager windowManager = recaf.get(WindowManager.class);
		WorkspaceManager workspaceManager = recaf.get(WorkspaceManager.class);

		// Get components
		MainMenu menu = recaf.get(MainMenu.class);
		Actions actions = recaf.get(Actions.class);

		// Layout
		BorderPane wrapper = new BorderPane();
		ActionButton search = new ActionButton(CarbonIcons.SEARCH, Lang.getBinding("dialog.quicknav"),
				windowManager::showSearchNowhere);
		search.getStyleClass().add("workspace-search");
		search.withTooltip("dialog.quicknav.shortcut");
		search.contentDisplayProperty().bind(Bindings.when(wrapper.widthProperty().lessThan(790))
				.then(ContentDisplay.GRAPHIC_ONLY).otherwise(ContentDisplay.LEFT));
		HBox toolbar = new HBox(menu, search);
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.getStyleClass().add("workspace-toolbar");
		HBox.setHgrow(menu, Priority.ALWAYS);
		wrapper.setTop(toolbar);
		ActionButton project = new ActionButton(CarbonIcons.TREE_VIEW, dockingManager::toggleWorkspacePanel)
				.withTooltip("workspace.title");
		ActionButton inspect = new ActionButton(CarbonIcons.CODE, actions::openBytecodeInvestigation)
				.withTooltip("investigate.title");
		ActionButton settings = new ActionButton(CarbonIcons.SETTINGS, actions::openConfig)
				.withTooltip("menu.config");
		Region space = new Region();
		VBox.setVgrow(space, Priority.ALWAYS);
		VBox tools = new VBox(project, inspect, space, settings);
		tools.getStyleClass().add("workspace-tool-stripe");
		tools.visibleProperty().bind(menu.getAnalysisMenu().disableProperty().not());
		tools.managedProperty().bind(tools.visibleProperty());
		wrapper.setLeft(tools);
		wrapper.setCenter(dockingManager.getRoot().asRegion());
		wrapper.getStyleClass().addAll("padded", "bg-inset");

		// Display
		WindowScaleConfig scaleConfig = recaf.get(WindowScaleConfig.class);
		Scene scene = new RecafScene(wrapper);
		DoubleShiftShortcut.install(stage, windowManager::showSearchNowhere);
		scene.addEventFilter(KeyEvent.KEY_PRESSED, (KeyEvent event) -> {
			// Global keybind handling
			if (keybindingConfig.getNavigateBack().match(event) && navigationHistoryService.back()) {
				event.consume();
			} else if (keybindingConfig.getNavigateForward().match(event) && navigationHistoryService.forward()) {
				event.consume();
			} else if (keybindingConfig.getQuickNavPlugin().match(event)) {
				Stage quickNav = windowManager.getQuickNav();
				if (quickNav instanceof QuickNavWindow quickNavWindow)
					quickNavWindow.selectPluginTab();
				quickNav.show();
				quickNav.requestFocus();
			} else if (keybindingConfig.getQuickNav().match(event)) {
				windowManager.showSearchNowhere();
				event.consume();
			} else if (keybindingConfig.getExport().match(event) && workspaceManager.hasCurrentWorkspace()) {
				recaf.get(PathExportingManager.class).export(workspaceManager.getCurrent());
			} else if (keybindingConfig.getSettings().match(event)) {
				actions.openConfig();
				event.consume();
			}
		});
		stage.setMinWidth(450 / scaleConfig.getScale());
		stage.setMinHeight(200 / scaleConfig.getScale());
		stage.setWidth(900 / scaleConfig.getScale());
		stage.setHeight(620 / scaleConfig.getScale());
		stage.setScene(scene);
		stage.getIcons().add(Icons.getImage(Icons.LOGO));
		stage.setTitle("Recaf");
		stage.setOnCloseRequest(e -> ExitDebugLoggingHook.exit(0));
		stage.show();

		// Register main window
		windowManager.register(WindowManager.WIN_MAIN, stage);

		// Publish UI init event
		recaf.getContainer().getBeanContainer().getEvent().fire(new UiInitializationEvent());
	}

}
