package software.coley.recaf.ui.pane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.observables.ObservableBoolean;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigContainer;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.util.Lang;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigPaneSearch}.
 */
class ConfigPaneSearchTest {
	@BeforeAll
	static void setup() {
		Lang.initialize();
	}

	@Test
	void blankQueryHasNoTokens() {
		assertTrue(ConfigPaneSearch.tokenizeQuery("   ").isEmpty());
		assertEquals(List.of("foo", "bar"), ConfigPaneSearch.tokenizeQuery("  Foo   BAR "));
	}

	@Test
	void emptyTokensMatchEverything() {
		ConfigContainer container = new TestContainer("plain-config");
		assertTrue(ConfigPaneSearch.matches(container, List.of()));
		assertEquals(Set.of("alpha-setting", "beta-thing"), ConfigPaneSearch.matchingValueIds(container, List.of()));
	}

	@Test
	void valueTextNarrowsToMatchingValues() {
		ConfigContainer container = new TestContainer("plain-config");
		assertEquals(Set.of("alpha-setting"), ConfigPaneSearch.matchingValueIds(container, List.of("alpha")));
		assertTrue(ConfigPaneSearch.matches(container, "alpha"));
	}

	@Test
	void headerTextMatchesAllValues() {
		ConfigContainer container = new TestContainer("plain-config");
		assertEquals(Set.of("alpha-setting", "beta-thing"), ConfigPaneSearch.matchingValueIds(container, List.of("plain-config")));
	}

	@Test
	void tokensMayMixHeaderAndValueText() {
		ConfigContainer container = new TestContainer("plain-config");
		assertEquals(Set.of("beta-thing"), ConfigPaneSearch.matchingValueIds(container, List.of("plain-config", "beta")));
	}

	@Test
	void tokensSplitAcrossDifferentValuesDoNotMatch() {
		ConfigContainer container = new TestContainer("plain-config");
		assertFalse(ConfigPaneSearch.matches(container, List.of("alpha", "beta")));
	}

	@Test
	void unknownTextDoesNotMatch() {
		ConfigContainer container = new TestContainer("plain-config");
		assertFalse(ConfigPaneSearch.matches(container, "zzz"));
		assertTrue(ConfigPaneSearch.matchingValueIds(container, List.of("zzz")).isEmpty());
	}

	@Test
	void hiddenValuesAreNeverMatched() {
		ConfigContainer container = new TestContainer("plain-config");
		assertFalse(ConfigPaneSearch.matches(container, "secret"));
		assertFalse(ConfigPaneSearch.matchingValueIds(container, List.of()).contains("secret-value"));
	}

	@Test
	void matchesTranslatedAndRawText() {
		ConfigContainer container = new TestContainer("export-config", "compression");
		assertTrue(ConfigPaneSearch.matches(container, "strategy"));
		assertTrue(ConfigPaneSearch.matches(container, "compression"));
		assertTrue(ConfigPaneSearch.matches(container, "exporting"));
		assertTrue(ConfigPaneSearch.matches(container, "export-config"));
	}

	private static class TestContainer extends BasicConfigContainer {
		private TestContainer(String id, String... valueIds) {
			super(ConfigGroups.SERVICE_IO, id);
			if (valueIds.length == 0)
				valueIds = new String[]{"alpha-setting", "beta-thing"};
			for (String valueId : valueIds)
				addValue(new BasicConfigValue<>(valueId, boolean.class, new ObservableBoolean(false)));
			addValue(new BasicConfigValue<>("secret-value", boolean.class, new ObservableBoolean(false), true));
		}
	}
}
