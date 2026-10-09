package software.coley.recaf.services.analysis.plugin.semantic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NameHeuristics}.
 */
class NameHeuristicsTest {
	@ParameterizedTest
	@ValueSource(strings = {"a", "x", "aa", "Ab", "a1", "IlIlI", "O0O0", "___", "class_123", "field_42", "method_7",
			"var3", "bcd", "if", "аб"})
	void obfuscatedNames(String name) {
		assertTrue(NameHeuristics.looksObfuscated(name), name);
	}

	@ParameterizedTest
	@ValueSource(strings = {"id", "on", "map", "key", "plugin", "onEnable", "PlayerListener", "MAX_HOMES", "getConfig"})
	void readableNames(String name) {
		assertFalse(NameHeuristics.looksObfuscated(name), name);
	}

	@Test
	void caseConversion() {
		assertEquals("MaxHomes", NameHeuristics.toPascalCase("max-homes"));
		assertEquals("maxHomes", NameHeuristics.toCamelCase("max-homes"));
		assertEquals("homeCountKey", NameHeuristics.toCamelCase("home_count key"));
		assertEquals("EssentialsX", NameHeuristics.toPascalCase("EssentialsX"));
		assertEquals("PERMISSION_COMMAND_HEAL", NameHeuristics.toUpperSnakeCase("PERMISSION command.heal"));
		assertEquals("onPlayerJoin", NameHeuristics.toCamelCase("onPlayerJoin"));
		assertEquals("_123", NameHeuristics.toCamelCase("123"), "Names cannot start with a digit");
		assertEquals("class_", NameHeuristics.toCamelCase("class"), "Keywords are made usable");
		assertEquals("", NameHeuristics.toCamelCase("..."));
	}

	@Test
	void lowerFirstHandlesAcronyms() {
		assertEquals("uuid", NameHeuristics.lowerFirst("UUID"));
		assertEquals("player", NameHeuristics.lowerFirst("Player"));
		assertEquals("urlHolder", NameHeuristics.lowerFirst("URLHolder"));
	}

	@Test
	void helpers() {
		assertEquals("players", NameHeuristics.plural("player"));
		assertEquals("entries", NameHeuristics.plural("entry"));
		assertEquals("boxes", NameHeuristics.plural("box"));
		assertEquals("PlayerJoin", NameHeuristics.stripEventSuffix("PlayerJoinEvent"));
		assertEquals("Event", NameHeuristics.stripEventSuffix("Event"));
		assertEquals("Inner", NameHeuristics.simpleName("com/example/Outer$Inner"));
		assertEquals("max-homes", NameHeuristics.lastSegment("settings.max-homes"));
		assertEquals("prefix", NameHeuristics.lastSegment("messages.prefix."));
	}
}
