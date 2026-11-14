package software.coley.recaf.ui.config;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import software.coley.recaf.util.PlatformType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Binding}.
 */
class BindingTest {
	private static KeyEvent press(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
		return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta);
	}

	@Test
	void metaEventMatchesMetaBindingOnly() {
		KeyEvent cmdF = press(KeyCode.F, false, false, false, true);
		assertTrue(Binding.newBind("find", KeyCode.META, KeyCode.F).match(cmdF));
		assertFalse(Binding.newBind("find", KeyCode.CONTROL, KeyCode.F).match(cmdF));
	}

	@Test
	void controlEventMatchesControlBindingOnly() {
		KeyEvent ctrlF = press(KeyCode.F, false, true, false, false);
		assertTrue(Binding.newBind("find", KeyCode.CONTROL, KeyCode.F).match(ctrlF));
		assertFalse(Binding.newBind("find", KeyCode.META, KeyCode.F).match(ctrlF));
	}

	@Test
	void extraModifiersDoNotMatch() {
		KeyEvent cmdShiftF = press(KeyCode.F, true, false, false, true);
		assertFalse(Binding.newBind("find", KeyCode.META, KeyCode.F).match(cmdShiftF));
		assertTrue(Binding.newBind("find", KeyCode.META, KeyCode.SHIFT, KeyCode.F).match(cmdShiftF));
	}

	@Test
	void displayTextOnMac() {
		assertEquals("Cmd+F", Binding.newBind("a", KeyCode.META, KeyCode.F).displayText(PlatformType.MAC));
		assertEquals("Cmd+Shift+G", Binding.newBind("a", KeyCode.META, KeyCode.SHIFT, KeyCode.G).displayText(PlatformType.MAC));
		assertEquals("Option+Left", Binding.newBind("a", KeyCode.ALT, KeyCode.LEFT).displayText(PlatformType.MAC));
		assertEquals("Cmd+,", Binding.newBind("a", KeyCode.META, KeyCode.COMMA).displayText(PlatformType.MAC));
		assertEquals("Ctrl+F", Binding.newBind("a", KeyCode.CONTROL, KeyCode.F).displayText(PlatformType.MAC));
	}

	@Test
	void displayTextOnOtherPlatforms() {
		for (PlatformType platform : new PlatformType[]{PlatformType.WINDOWS, PlatformType.LINUX}) {
			assertEquals("Ctrl+F", Binding.newBind("a", KeyCode.CONTROL, KeyCode.F).displayText(platform));
			assertEquals("Ctrl+Shift+G", Binding.newBind("a", KeyCode.CONTROL, KeyCode.SHIFT, KeyCode.G).displayText(platform));
			assertEquals("Alt+Left", Binding.newBind("a", KeyCode.ALT, KeyCode.LEFT).displayText(platform));
			assertEquals("Meta+F", Binding.newBind("a", KeyCode.META, KeyCode.F).displayText(platform));
			assertEquals("F3", Binding.newBind("a", KeyCode.F3).displayText(platform));
		}
	}

	@Test
	void displayTextOrdersModifiersFirstRegardlessOfStoredOrder() {
		Binding binding = new Binding("a");
		binding.add("g");
		binding.add("shift");
		binding.add("alt");
		binding.add("meta");
		assertEquals("Cmd+Option+Shift+G", binding.displayText(PlatformType.MAC));
		assertEquals("Meta+Alt+Shift+G", binding.displayText(PlatformType.LINUX));
	}

	@Test
	void toStringIsUnchanged() {
		assertEquals("meta + f", Binding.newBind("a", KeyCode.META, KeyCode.F).toString());
	}
}
