package software.coley.recaf.ui.config;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import software.coley.recaf.util.PlatformType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BindingHint}.
 */
class BindingHintTest {
	@Test
	void neverMatchesAnyEvent() {
		Binding binding = Binding.newBind("find", KeyCode.META, KeyCode.F);
		BindingHint hint = new BindingHint(() -> binding, PlatformType.MAC);
		assertFalse(hint.match(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, false, false, true)));
		assertFalse(hint.match(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false)));
		assertFalse(hint.match(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A, false, false, false, false)));
	}

	@Test
	void showsBindingText() {
		Binding binding = Binding.newBind("find", KeyCode.META, KeyCode.SHIFT, KeyCode.G);
		assertEquals("Cmd+Shift+G", new BindingHint(() -> binding, PlatformType.MAC).getDisplayText());
		assertEquals("Meta+Shift+G", new BindingHint(() -> binding, PlatformType.LINUX).getDisplayText());
	}

	@Test
	void reflectsRebindInPlace() {
		Binding binding = Binding.newBind("find", KeyCode.META, KeyCode.F);
		BindingHint hint = new BindingHint(() -> binding, PlatformType.MAC);
		assertEquals("Cmd+F", hint.getDisplayText());
		binding.clear();
		binding.addAll(Binding.newBind("find", KeyCode.META, KeyCode.H));
		assertEquals("Cmd+H", hint.getDisplayText());
	}

	@Test
	void emptyBindingShowsNothing() {
		assertEquals("", new BindingHint(() -> new Binding("none"), PlatformType.MAC).getDisplayText());
	}
}
