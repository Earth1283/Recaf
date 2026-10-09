package software.coley.recaf.ui.window;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import software.coley.recaf.ui.pane.plugin.PluginNameRecoveryPane;
import software.coley.recaf.util.Lang;

/**
 * Window wrapper for {@link PluginNameRecoveryPane}.
 *
 * @see PluginNameRecoveryPane
 */
@Dependent
public class PluginNameRecoveryWindow extends RecafStage {
	@Inject
	public PluginNameRecoveryWindow(@Nonnull PluginNameRecoveryPane pane) {
		titleProperty().bind(Lang.getBinding("mcplugin.names.title"));
		setMinWidth(600);
		setMinHeight(300);
		setScene(new RecafScene(pane, 1150, 650));
	}
}
