package software.coley.recaf.ui.config;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import software.coley.recaf.config.ConfigValue;
import software.coley.recaf.services.json.GsonProvider;
import software.coley.recaf.services.json.GsonProviderConfig;
import software.coley.recaf.util.PlatformType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KeybindingConfig}.
 */
class KeybindingConfigTest {
	private static Map<String, Binding> defaults(PlatformType platform) {
		return KeybindingConfig.defaultBindings(platform).stream()
				.collect(Collectors.toMap(Binding::getId, b -> b));
	}

	private static List<String> keys(Binding binding) {
		return List.copyOf(binding);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static KeybindingConfig loadedConfig(PlatformType platform, String json, boolean restore) {
		GsonProvider gsonProvider = new GsonProvider(new GsonProviderConfig());
		KeybindingConfig config = new KeybindingConfig(gsonProvider, platform);
		if (json != null) {
			Gson gson = gsonProvider.getGson();
			ConfigValue bundleValue = config.getValues().get("bundle");
			bundleValue.setValue(gson.fromJson(JsonParser.parseString(json), bundleValue.getType()));
			if (restore)
				config.onRestore();
		} else
			config.onNoRestore();
		return config;
	}

	private static boolean migratedFlag(KeybindingConfig config) {
		return (Boolean) config.getValues().get("mac-defaults-migrated").getValue();
	}

	@Test
	void macDefaultsUseMetaAndOthersUseCtrl() {
		assertEquals(List.of("meta", "f"), keys(defaults(PlatformType.MAC).get("editor.find")));
		assertEquals(List.of("ctrl", "f"), keys(defaults(PlatformType.LINUX).get("editor.find")));
		assertEquals(List.of("ctrl", "f"), keys(defaults(PlatformType.WINDOWS).get("editor.find")));
		assertEquals(Set.of("meta", "shift", "g"), Set.copyOf(defaults(PlatformType.MAC).get("quicknav-plugin")));
		assertEquals(List.of("alt", "left"), keys(defaults(PlatformType.MAC).get("navigate.back")));
		assertEquals(List.of("f3"), keys(defaults(PlatformType.MAC).get("editor.goto")));
	}

	@Test
	void platformsDefineTheSameIds() {
		assertEquals(defaults(PlatformType.MAC).keySet(), defaults(PlatformType.LINUX).keySet());
	}

	@Test
	void migrationReplacesNonMacDefaultOnMac() {
		Map<String, Binding> migrated = KeybindingConfig.migrateToMacDefaults(PlatformType.MAC,
				defaults(PlatformType.LINUX), defaults(PlatformType.MAC),
				List.of(Binding.newBind("editor.find", List.of("ctrl", "f"))));
		assertEquals(Set.of("editor.find"), migrated.keySet());
		assertEquals(Set.of("meta", "f"), Set.copyOf(migrated.get("editor.find")));
	}

	@Test
	void migrationKeepsCustomisedBindings() {
		Map<String, Binding> migrated = KeybindingConfig.migrateToMacDefaults(PlatformType.MAC,
				defaults(PlatformType.LINUX), defaults(PlatformType.MAC),
				List.of(Binding.newBind("editor.find", List.of("ctrl", "shift", "f")),
						Binding.newBind("editor.replace", List.of("alt", "f")),
						Binding.newBind("editor.save", List.of("meta", "s"))));
		assertTrue(migrated.isEmpty());
	}

	@Test
	void migrationKeepsBindingsWithoutCtrlInDefault() {
		Map<String, Binding> migrated = KeybindingConfig.migrateToMacDefaults(PlatformType.MAC,
				defaults(PlatformType.LINUX), defaults(PlatformType.MAC),
				List.of(Binding.newBind("editor.goto", List.of("f3")),
						Binding.newBind("navigate.back", List.of("alt", "left"))));
		assertTrue(migrated.isEmpty());
	}

	@Test
	void migrationIgnoresUnknownIds() {
		Map<String, Binding> migrated = KeybindingConfig.migrateToMacDefaults(PlatformType.MAC,
				defaults(PlatformType.LINUX), defaults(PlatformType.MAC),
				List.of(Binding.newBind("not.a.real.id", List.of("ctrl", "f"))));
		assertTrue(migrated.isEmpty());
	}

	@Test
	void migrationDoesNothingOffMac() {
		for (PlatformType platform : new PlatformType[]{PlatformType.LINUX, PlatformType.WINDOWS}) {
			Map<String, Binding> migrated = KeybindingConfig.migrateToMacDefaults(platform,
					defaults(PlatformType.LINUX), defaults(PlatformType.MAC),
					List.copyOf(defaults(PlatformType.LINUX).values()));
			assertTrue(migrated.isEmpty());
		}
	}

	@Test
	void restoreOnMacMigratesOnceThenKeepsLaterRebind() {
		KeybindingConfig config = loadedConfig(PlatformType.MAC,
				"{\"editor.find\":[\"ctrl\",\"f\"],\"editor.replace\":[\"alt\",\"f\"]}", true);
		assertEquals(Set.of("meta", "f"), Set.copyOf(config.getFind()));
		assertEquals(Set.of("alt", "f"), Set.copyOf(config.getReplace()));
		assertEquals(Set.of("meta", "s"), Set.copyOf(config.getSave()));
		assertTrue(migratedFlag(config));

		config.getFind().clear();
		config.getFind().addAll(List.of("ctrl", "f"));
		config.onRestore();
		assertEquals(Set.of("ctrl", "f"), Set.copyOf(config.getFind()));
	}

	@Test
	void restoreOnMacWithFlagAlreadySetDoesNotMigrate() {
		KeybindingConfig config = loadedConfig(PlatformType.MAC, "{\"editor.find\":[\"ctrl\",\"f\"]}", false);
		setFlag(config, true);
		config.onRestore();
		assertEquals(Set.of("ctrl", "f"), Set.copyOf(config.getFind()));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setFlag(KeybindingConfig config, boolean value) {
		ConfigValue flag = config.getValues().get("mac-defaults-migrated");
		flag.setValue(value);
	}

	@Test
	void restoreOnLinuxChangesNothing() {
		KeybindingConfig config = loadedConfig(PlatformType.LINUX, "{\"editor.find\":[\"ctrl\",\"f\"]}", true);
		assertEquals(Set.of("ctrl", "f"), Set.copyOf(config.getFind()));
		assertFalse(migratedFlag(config));
	}

	@Test
	void noSavedConfigOnMacKeepsMacDefaultsAndSetsFlag() {
		KeybindingConfig config = loadedConfig(PlatformType.MAC, null, false);
		assertEquals(Set.of("meta", "f"), Set.copyOf(config.getFind()));
		assertTrue(migratedFlag(config));
	}

	@Test
	void missingIdsInSavedConfigFallBackToPlatformDefaults() {
		KeybindingConfig config = loadedConfig(PlatformType.MAC, "{\"editor.find\":[\"alt\",\"f\"]}", true);
		assertEquals(Set.of("alt", "f"), Set.copyOf(config.getFind()));
		assertEquals(Set.of("meta", "s"), Set.copyOf(config.getSave()));
	}
}
