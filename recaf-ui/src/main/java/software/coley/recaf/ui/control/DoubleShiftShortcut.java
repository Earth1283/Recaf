package software.coley.recaf.ui.control;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/** Detects two short, unmodified Shift taps in the same focused window. */
public final class DoubleShiftShortcut {
	private static final long INTERVAL_NS = 450_000_000;
	private long pressedAt;
	private long lastTap;

	private DoubleShiftShortcut() {}

	public static void install(Stage stage, Runnable action) {
		DoubleShiftShortcut shortcut = new DoubleShiftShortcut();
		stage.focusedProperty().addListener((obs, old, focused) -> shortcut.reset());
		stage.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> shortcut.reset());
		stage.addEventFilter(KeyEvent.ANY, event -> {
			if (shortcut.matches(event, System.nanoTime())) {
				event.consume();
				action.run();
			}
		});
	}

	private boolean matches(KeyEvent event, long now) {
		if (event.getEventType() == KeyEvent.KEY_TYPED)
			return false;
		if (event.getCode() != KeyCode.SHIFT || event.isAltDown() || event.isControlDown() || event.isMetaDown()) {
			reset();
			return false;
		}
		if (event.getEventType() == KeyEvent.KEY_PRESSED) {
			if (pressedAt == 0)
				pressedAt = now;
			return false;
		}
		boolean tapped = pressedAt != 0 && now - pressedAt <= INTERVAL_NS;
		boolean twice = tapped && lastTap != 0 && now - lastTap <= INTERVAL_NS;
		pressedAt = 0;
		lastTap = tapped && !twice ? now : 0;
		return twice;
	}

	private void reset() {
		pressedAt = 0;
		lastTap = 0;
	}
}
