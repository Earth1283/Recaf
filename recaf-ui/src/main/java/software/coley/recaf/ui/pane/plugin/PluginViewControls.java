package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import software.coley.observables.ChangeListener;
import software.coley.recaf.services.analysis.plugin.view.PluginViewConfig;
import software.coley.recaf.services.analysis.plugin.view.PluginViewService;
import software.coley.recaf.services.analysis.plugin.view.PluginViewService.DeobfuscationStatus;
import software.coley.recaf.ui.control.ObservableCheckBox;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;

import java.util.function.Consumer;

/**
 * Switches for what the plugin view adds to decompiled code, with the progress of the deobfuscated view.
 * Keeps in sync with the config while it is shown, since other places can change it too.
 */
public class PluginViewControls extends VBox {
	private final PluginViewService viewService;
	private final Workspace workspace;
	private final CheckBox structure;
	private final CheckBox security;
	private final CheckBox names;
	private final CheckBox deobfuscated;
	private final Label status = new Label();
	private final ChangeListener<Boolean> structureSync;
	private final ChangeListener<Boolean> securitySync;
	private final ChangeListener<Boolean> namesSync;
	private final ChangeListener<Boolean> deobfuscatedSync;
	private final Consumer<Workspace> statusListener;

	/**
	 * @param viewService
	 * 		Plugin view service.
	 * @param workspace
	 * 		Workspace the plugin is in.
	 */
	public PluginViewControls(@Nonnull PluginViewService viewService, @Nonnull Workspace workspace) {
		super(4);
		this.viewService = viewService;
		this.workspace = workspace;
		PluginViewConfig config = viewService.getServiceConfig();

		structure = new ObservableCheckBox(config.getAnnotateStructure(), Lang.getBinding("mcplugin.view.structure"));
		security = new ObservableCheckBox(config.getAnnotateSecurity(), Lang.getBinding("mcplugin.view.security"));
		names = new ObservableCheckBox(config.getNameParameters(), Lang.getBinding("mcplugin.view.names"));
		deobfuscated = new ObservableCheckBox(config.getDeobfuscatedView(), Lang.getBinding("mcplugin.view.deobfuscated"));
		deobfuscated.setTooltip(new Tooltip(Lang.get("mcplugin.view.deobfuscated.tip")));
		status.getStyleClass().add("text-subtle");
		status.setWrapText(true);
		status.managedProperty().bind(status.visibleProperty());

		structureSync = sync(structure);
		securitySync = sync(security);
		namesSync = sync(names);
		deobfuscatedSync = sync(deobfuscated);
		statusListener = changed -> FxThreadUtil.run(this::updateStatus);

		getChildren().addAll(structure, security, names, deobfuscated, status);
		setPadding(new Insets(2, 0, 0, 0));
		updateStatus();

		sceneProperty().addListener((ob, old, cur) -> {
			if (cur == null)
				detach(config);
			else
				attach(config);
		});
	}

	private static ChangeListener<Boolean> sync(@Nonnull CheckBox box) {
		return (ob, old, cur) -> FxThreadUtil.run(() -> box.setSelected(cur));
	}

	private void attach(@Nonnull PluginViewConfig config) {
		structure.setSelected(config.getAnnotateStructure().getValue());
		security.setSelected(config.getAnnotateSecurity().getValue());
		names.setSelected(config.getNameParameters().getValue());
		deobfuscated.setSelected(config.getDeobfuscatedView().getValue());
		config.getAnnotateStructure().addChangeListener(structureSync);
		config.getAnnotateSecurity().addChangeListener(securitySync);
		config.getNameParameters().addChangeListener(namesSync);
		config.getDeobfuscatedView().addChangeListener(deobfuscatedSync);
		viewService.addStatusListener(statusListener);
		updateStatus();
	}

	private void detach(@Nonnull PluginViewConfig config) {
		config.getAnnotateStructure().removeChangeListener(structureSync);
		config.getAnnotateSecurity().removeChangeListener(securitySync);
		config.getNameParameters().removeChangeListener(namesSync);
		config.getDeobfuscatedView().removeChangeListener(deobfuscatedSync);
		viewService.removeStatusListener(statusListener);
	}

	private void updateStatus() {
		DeobfuscationStatus current = viewService.getDeobfuscationStatus(workspace);
		String text = switch (current.phase()) {
			case BUILDING -> Lang.get("mcplugin.view.status.building");
			case READY -> String.format(Lang.get("mcplugin.view.status.ready"), current.changedClasses());
			case FAILED -> Lang.get("mcplugin.view.status.failed");
			case IDLE -> null;
		};
		status.setText(text);
		status.setVisible(text != null);
	}
}
