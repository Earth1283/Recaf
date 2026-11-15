package software.coley.recaf.ui.window;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import software.coley.recaf.services.window.WindowManager;
import software.coley.recaf.ui.pane.McpPane;
import software.coley.recaf.util.Lang;

/**
 * Window wrapper for {@link McpPane}.
 *
 * @author Matt Coley
 * @see McpPane
 */
@Dependent
public class McpWindow extends AbstractIdentifiableStage {
	@Inject
	public McpWindow(@Nonnull McpPane pane) {
		super(WindowManager.WIN_MCP);
		hideOnEscape();

		titleProperty().bind(Lang.getBinding("mcp.title"));
		setMinWidth(640);
		setMinHeight(480);
		setScene(new RecafScene(pane, 820, 680));
	}
}
