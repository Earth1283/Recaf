package software.coley.recaf.ui.window;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import software.coley.recaf.ui.pane.crypto.CryptoToolsPane;
import software.coley.recaf.util.Lang;

/**
 * Window wrapper for {@link CryptoToolsPane}.
 */
@Dependent
public class CryptoToolsWindow extends RecafStage {
	private final CryptoToolsPane pane;

	@Inject
	public CryptoToolsWindow(@Nonnull CryptoToolsPane pane) {
		this.pane = pane;
		titleProperty().bind(Lang.getBinding("crypto.title"));
		setMinWidth(760);
		setMinHeight(560);
		setScene(new RecafScene(pane, 1180, 880));
	}

	/**
	 * @return Crypto tools content.
	 */
	@Nonnull
	public CryptoToolsPane getPane() {
		return pane;
	}
}
