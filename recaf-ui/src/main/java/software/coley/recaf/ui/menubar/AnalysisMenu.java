package software.coley.recaf.ui.menubar;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginPlatform;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.transform.BuiltinTransformationPresets;
import software.coley.recaf.services.transform.TransformationPreset;
import software.coley.recaf.services.transform.TransformationPresetManager;
import software.coley.recaf.services.window.WindowManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.control.ActionMenuItem;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.ui.pane.crypto.CryptoTools;
import software.coley.recaf.ui.pane.plugin.PluginNavigation;
import software.coley.recaf.ui.window.DeobfuscationWindow;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.workspace.model.Workspace;

import java.util.EnumMap;
import java.util.Map;

import static software.coley.recaf.util.Lang.getBinding;
import static software.coley.recaf.util.Menus.*;

/**
 * Analysis menu component for {@link MainMenu}.
 *
 * @author Matt Coley
 */
@Dependent
public class AnalysisMenu extends WorkspaceAwareMenu {
	private final WindowManager windowManager;
	private final Instance<DeobfuscationWindow> deobfuscationWindowProvider;
	private final TransformationPresetManager presetManager;
	private final MinecraftPluginAnalysisService pluginAnalysisService;
	private final WorkspaceManager workspaceManager;
	private final ToggleGroup pluginOverrideGroup = new ToggleGroup();
	private final RadioMenuItem pluginOverrideAuto = new RadioMenuItem();
	private final Map<MinecraftPluginPlatform, RadioMenuItem> pluginOverrideItems = new EnumMap<>(MinecraftPluginPlatform.class);
	private final BooleanProperty hasPresets = new SimpleBooleanProperty();

	@Inject
	public AnalysisMenu(@Nonnull WorkspaceManager workspaceManager,
	                    @Nonnull DockingManager dockingManager,
	                    @Nonnull WindowManager windowManager,
	                    @Nonnull Instance<DeobfuscationWindow> deobfuscationWindowProvider,
	                    @Nonnull TransformationPresetManager presetManager,
	                    @Nonnull PluginNavigation pluginNavigation,
	                    @Nonnull MinecraftPluginAnalysisService pluginAnalysisService,
	                    @Nonnull Actions actions,
	                    @Nonnull CryptoTools cryptoTools) {
		super(workspaceManager);

		this.workspaceManager = workspaceManager;
		this.pluginAnalysisService = pluginAnalysisService;

		this.windowManager = windowManager;
		this.deobfuscationWindowProvider = deobfuscationWindowProvider;
		this.presetManager = presetManager;

		disableProperty().bind(hasWorkspace.not());
		textProperty().bind(getBinding("menu.analysis"));
		setGraphic(new FontIconView(CarbonIcons.CHART_CUSTOM));

		MenuItem itemViewSummary = action("menu.analysis.summary", CarbonIcons.INFORMATION, actions::openSummary);
		itemViewSummary.disableProperty().bind(hasWorkspace.not());
		getItems().add(itemViewSummary);

		MenuItem itemViewAreas = action("menu.analysis.areas", CarbonIcons.CHART_CUSTOM, actions::openAreaAnalysis);
		itemViewAreas.disableProperty().bind(hasWorkspace.not());
		getItems().add(itemViewAreas);

		MenuItem itemInvestigate = action("investigate.title", CarbonIcons.SEARCH, actions::openBytecodeInvestigation);
		itemInvestigate.disableProperty().bind(hasWorkspace.not());
		getItems().add(itemInvestigate);
		getItems().add(action("flow.trace.title", CarbonIcons.SEARCH, pluginNavigation::openInfluenceTrace));

		ActionMenuItem itemListComments = action("menu.analysis.list-comments", CarbonIcons.CHAT, actions::openCommentList);
		itemListComments.disableProperty().bind(hasWorkspace.or(hasAgentWorkspace).not());
		getItems().add(itemListComments);

		getItems().addAll(separator(), createDeobfuscationMenu(), createMinecraftPluginMenu(pluginNavigation),
				action("menu.analysis.crypto-tools", CarbonIcons.PASSWORD, cryptoTools::open));
	}

	private Menu createDeobfuscationMenu() {
		Menu deobfuscation = menu("menu.analysis.deobfuscation", CarbonIcons.DEVELOPMENT);
		deobfuscation.disableProperty().bind(hasWorkspace.or(hasAgentWorkspace).not());

		Menu presets = menu("menu.analysis.deobfuscation-presets", CarbonIcons.SAVE);
		presets.disableProperty().bind(hasPresets.not());
		rebuildPresetMenu(presets);
		presetManager.getPresets().addChangeListener((ob, old, cur) -> FxThreadUtil.run(() -> rebuildPresetMenu(presets)));

		deobfuscation.getItems().addAll(
				action("menu.analysis.deobfuscation.open", CarbonIcons.DEVELOPMENT, this::openDeobfuscation),
				separator(),
				action("menu.analysis.simplify-bitwise", CarbonIcons.DEVELOPMENT,
						() -> openPreset(BuiltinTransformationPresets.BITWISE_SIMPLIFICATION)),
				action("menu.analysis.simplify-skid", CarbonIcons.DEVELOPMENT,
						() -> openPreset(BuiltinTransformationPresets.SKIDFUSCATOR_SIMPLIFICATION)),
				action("flow.simplify", CarbonIcons.DEVELOPMENT,
						() -> openPreset(BuiltinTransformationPresets.CONTROL_FLOW_SIMPLIFICATION)),
				action("menu.analysis.clean-obfuscated-plugin", CarbonIcons.DEVELOPMENT,
						() -> openPreset(BuiltinTransformationPresets.OBFUSCATED_PLUGIN_CLEANUP)),
				presets);
		return deobfuscation;
	}

	private Menu createMinecraftPluginMenu(@Nonnull PluginNavigation pluginNavigation) {
		Menu plugin = menu("mcplugin.menu", CarbonIcons.CATEGORIES);
		plugin.disableProperty().bind(hasWorkspace.not());
		plugin.getItems().addAll(
				action("mcplugin.navigator.title", CarbonIcons.CATEGORIES, () -> pluginNavigation.openNavigator(null)),
				action("menu.analysis.plugin-security", CarbonIcons.SECURITY, pluginNavigation::openSecurityTriage),
				action("flow.explorer.title", CarbonIcons.CHART_CUSTOM, () -> pluginNavigation.openExecutionExplorer(null)),
				separator(),
				createPluginOverrideMenu());
		return plugin;
	}

	/**
	 * @return Menu for marking the workspace as a Minecraft plugin, for when its manifest is missing or obfuscated.
	 */
	@Nonnull
	private Menu createPluginOverrideMenu() {
		Menu menu = menu("menu.analysis.plugin-override", CarbonIcons.CATEGORIES);
		menu.disableProperty().bind(hasWorkspace.not());

		pluginOverrideAuto.textProperty().bind(getBinding("menu.analysis.plugin-override.auto"));
		pluginOverrideAuto.setToggleGroup(pluginOverrideGroup);
		pluginOverrideAuto.setOnAction(e -> setPluginOverride(null));
		menu.getItems().add(pluginOverrideAuto);
		menu.getItems().add(separator());
		for (MinecraftPluginPlatform platform : MinecraftPluginPlatform.values()) {
			RadioMenuItem item = new RadioMenuItem(platform.displayName());
			item.setToggleGroup(pluginOverrideGroup);
			item.setOnAction(e -> setPluginOverride(platform));
			pluginOverrideItems.put(platform, item);
			menu.getItems().add(item);
		}

		// Keep the selection in sync with the workspace, including changes made outside of this menu.
		menu.setOnShowing(e -> syncPluginOverrideSelection());
		pluginAnalysisService.addPlatformOverrideListener((workspace, platform) -> FxThreadUtil.run(this::syncPluginOverrideSelection));
		syncPluginOverrideSelection();
		return menu;
	}

	private void setPluginOverride(@Nullable MinecraftPluginPlatform platform) {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace != null)
			pluginAnalysisService.setPlatformOverride(workspace, platform);
	}

	private void syncPluginOverrideSelection() {
		Workspace workspace = workspaceManager.getCurrent();
		MinecraftPluginPlatform override = workspace == null ? null : pluginAnalysisService.getPlatformOverride(workspace);
		RadioMenuItem selected = override == null ? pluginOverrideAuto : pluginOverrideItems.get(override);
		selected.setSelected(true);
	}

	@Override
	protected void workspaceStateChanged() {
		syncPluginOverrideSelection();
	}

	/**
	 * Rebuild the preset submenu to reflect the currently saved presets.
	 */
	private void rebuildPresetMenu(@Nonnull Menu presetMenu) {
		presetMenu.getItems().clear();

		for (String name : presetManager.getPresetNames()) {
			ActionMenuItem item = actionLiteral(name, CarbonIcons.SAVE, () -> applyPreset(name));
			presetMenu.getItems().add(item);
		}

		hasPresets.set(!presetMenu.getItems().isEmpty());
	}

	/**
	 * Display the deobfuscation window.
	 */
	private void openDeobfuscation() {
		DeobfuscationWindow deobfuscationWindow = deobfuscationWindowProvider.get();
		deobfuscationWindow.show();
		deobfuscationWindow.requestFocus();
		deobfuscationWindow.setOnCloseRequest(e -> deobfuscationWindowProvider.destroy(deobfuscationWindow));
		windowManager.registerAnonymous(deobfuscationWindow);
	}

	/**
	 * Resolve a saved preset and open its preview.
	 */
	private void applyPreset(@Nonnull String name) {
		TransformationPreset preset = presetManager.getPreset(name);
		if (preset == null)
			return;

		openPreset(preset);
	}

	/**
	 * Open the deobfuscation preview with a preselected profile.
	 *
	 * @param preset
	 * 		Profile to preview and apply.
	 */
	private void openPreset(@Nonnull TransformationPreset preset) {
		DeobfuscationWindow deobfuscationWindow = deobfuscationWindowProvider.get();
		deobfuscationWindow.setOnCloseRequest(e -> deobfuscationWindowProvider.destroy(deobfuscationWindow));
		windowManager.registerAnonymous(deobfuscationWindow);

		// Apply before showing so the preview opens with the preset selection.
		deobfuscationWindow.applyPreset(preset);
		deobfuscationWindow.show();
		deobfuscationWindow.requestFocus();
	}
}
