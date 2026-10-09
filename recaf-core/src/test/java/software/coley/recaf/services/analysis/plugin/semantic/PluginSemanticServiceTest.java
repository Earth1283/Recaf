package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginMappingListener;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.ClassTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.FieldTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.MethodTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.VariableTarget;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService.MappingPlan;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PluginSemanticService}, using {@link ObfuscatedPluginFixture}.
 */
class PluginSemanticServiceTest extends TestBase {
	static PluginSemanticService service;
	static JavacCompiler javac;

	@BeforeAll
	static void setup() {
		service = recaf.get(PluginSemanticService.class);
		javac = recaf.get(JavacCompiler.class);
	}

	@Nonnull
	private static Workspace open(boolean withApi) {
		Workspace workspace = ObfuscatedPluginFixture.create(javac, withApi);
		workspaceManager.setCurrentIgnoringConditions(workspace);
		return workspace;
	}

	@Nested
	class Index {
		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		void findsEntryPointsListenersAndHandlers(boolean withApi) {
			PluginIndex index = service.getIndex(open(withApi));

			assertEquals("Homes", index.pluginName());
			assertElement(index, PluginElementKind.MAIN_CLASS, "Homes", "a/a");
			assertElement(index, PluginElementKind.LIFECYCLE, "onEnable", "a/a.onEnable()V");

			PluginElement listener = assertElement(index, PluginElementKind.LISTENER, "b", "a/b");
			assertEquals("a/a.f()V", String.valueOf(listener.related()), "Registration site should be linked");

			PluginElement join = assertElement(index, PluginElementKind.EVENT_HANDLER, "PlayerJoinEvent",
					"a/b.a(Lorg/bukkit/event/player/PlayerJoinEvent;)V");
			assertEquals("HIGH", join.detail());
			assertEquals("org/bukkit/event/player/PlayerJoinEvent", join.related().className());
			assertElement(index, PluginElementKind.EVENT_HANDLER, "PlayerQuitEvent",
					"a/b.b(Lorg/bukkit/event/player/PlayerQuitEvent;)V");
			assertEquals(1, index.handlersOf("org/bukkit/event/player/PlayerJoinEvent").size());

			assertElement(index, PluginElementKind.CUSTOM_EVENT, "f", "a/f");
			PluginElement fired = assertElement(index, PluginElementKind.EVENT_CALL, "f",
					"a/b.a(Lorg/bukkit/event/player/PlayerJoinEvent;)V");
			assertEquals("a/f", fired.related().className());
		}

		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		void findsCommandsAndTheirExecutors(boolean withApi) {
			PluginIndex index = service.getIndex(open(withApi));

			assertElement(index, PluginElementKind.COMMAND, "home", "a/a.g()V");
			assertElement(index, PluginElementKind.COMMAND, "sethome", "a/a.g()V");
			PluginElement home = assertElement(index, PluginElementKind.COMMAND_EXECUTOR, "home",
					"a/c.onCommand" + PluginIndexer.ON_COMMAND_DESC);
			assertEquals("a/a.g()V", String.valueOf(home.related()));

			// The executor for 'sethome' is set through local variables, which have to be followed.
			assertElement(index, PluginElementKind.COMMAND_EXECUTOR, "sethome", "a/d.onCommand" + PluginIndexer.ON_COMMAND_DESC);
			assertElement(index, PluginElementKind.TAB_COMPLETER, "sethome", "a/d.onTabComplete" + PluginIndexer.ON_TAB_COMPLETE_DESC);
			assertTrue(index.ofKind(PluginElementKind.COMMAND_EXECUTOR).stream().noneMatch(e -> e.key().isEmpty()),
					"Executors found by role should be replaced by the registered ones");
		}

		@Test
		void findsTasksAndKeys() {
			PluginIndex index = service.getIndex(open(false));

			PluginElement task = assertElement(index, PluginElementKind.TASK, "runTaskTimer", "a/e.run()V");
			assertEquals("a/a.onEnable()V", String.valueOf(task.related()));
			assertElement(index, PluginElementKind.CONFIG_KEY, "settings.max-homes", "a/a.onEnable()V");
			assertElement(index, PluginElementKind.CONFIG_KEY, "messages.prefix", "a/a.j()Ljava/lang/String;");
			assertElement(index, PluginElementKind.NAMESPACED_KEY, "home_count", "a/a.onEnable()V");
			assertElement(index, PluginElementKind.PERMISSION, "homes.sethome", "a/d.onCommand" + PluginIndexer.ON_COMMAND_DESC);

			// Passed through a static field, not a literal.
			assertElement(index, PluginElementKind.PERMISSION, "homes.admin", "a/c.onCommand" + PluginIndexer.ON_COMMAND_DESC);
		}

		@Test
		void searchMatchesAllWords() {
			PluginIndex index = service.getIndex(open(false));
			List<PluginElement> results = index.search("permission sethome");
			assertEquals(1, results.size());
			assertEquals("homes.sethome", results.getFirst().key());
			assertFalse(index.search("PlayerJoin").isEmpty(), "Search should ignore case");
			assertTrue(index.search("no-such-thing").isEmpty());
		}

		@Test
		void indexIsCachedUntilClassesChange() {
			Workspace workspace = open(false);
			PluginIndex first = service.getIndex(workspace);
			assertSame(first, service.getIndex(workspace));

			var bundle = workspace.getPrimaryResource().getJvmClassBundle();
			bundle.put(bundle.get("a/e"));
			assertNotSame(first, service.getIndex(workspace), "Changing a class should drop the cached index");
		}

		@Test
		void pluginsAreIndexedInTheBackgroundWhenOpened() {
			Workspace workspace = open(false);
			PluginIndex index = awaitCachedIndex(workspace);
			assertEquals("Homes", index.pluginName());
		}

		@Test
		void indexIsRebuiltInTheBackgroundAfterChanges() {
			Workspace workspace = open(false);
			PluginIndex first = service.getIndex(workspace);

			// Without anyone asking for it, a new index should be ready shortly after the change.
			var bundle = workspace.getPrimaryResource().getJvmClassBundle();
			bundle.put(bundle.get("a/e"));
			PluginIndex rebuilt = awaitCachedIndex(workspace);
			assertNotSame(first, rebuilt);
		}

		@Test
		void concurrentRequestsShareOneBuild() {
			Workspace workspace = open(false);
			service.getIndex(workspace);
			var bundle = workspace.getPrimaryResource().getJvmClassBundle();
			bundle.put(bundle.get("a/e"));

			// Both requests are made before any build could finish, so they get the same build.
			var first = service.getIndexAsync(workspace);
			var second = service.getIndexAsync(workspace);
			assertSame(first.join(), second.join());
		}

		@Test
		void cachedIndexIsNotBuiltForOtherWorkspaces() {
			Workspace current = open(false);
			Workspace other = ObfuscatedPluginFixture.create(javac, false);
			assertNull(service.getCachedIndex(other), "Only the current workspace is cached");
			assertFalse(service.getIndex(other).isEmpty(), "Other workspaces can still be indexed on request");
			assertNotNull(awaitCachedIndex(current));
		}

		@Test
		void emptyForNonPluginWorkspace() {
			Workspace workspace = new software.coley.recaf.workspace.model.BasicWorkspace(
					new software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder().build());
			assertTrue(service.buildIndex(workspace).isEmpty());
		}
	}

	@Nested
	class Suggestions {
		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		void namesClasses(boolean withApi) {
			Map<NameTarget, NameSuggestion> suggestions = suggestionsByTarget(open(withApi));

			assertName(suggestions, new ClassTarget("a/a"), "HomesPlugin", NamingClue.MANIFEST);
			assertName(suggestions, new ClassTarget("a/b"), "PlayerListener", NamingClue.EVENT);
			assertName(suggestions, new ClassTarget("a/c"), "HomeCommand", NamingClue.MANIFEST);
			assertName(suggestions, new ClassTarget("a/d"), "SethomeCommand", NamingClue.MANIFEST);
			assertName(suggestions, new ClassTarget("a/e"), "RepeatingTask", NamingClue.BEHAVIOR);
			assertName(suggestions, new ClassTarget("a/f"), "CustomPlayerEvent", NamingClue.API_TYPE);
		}

		@Test
		void namesFields() {
			Map<NameTarget, NameSuggestion> suggestions = suggestionsByTarget(open(false));

			assertName(suggestions, new FieldTarget("a/a", "a", "La/a;"), "instance", NamingClue.ACCESSOR);
			assertName(suggestions, new FieldTarget("a/a", "b", "La/b;"), "playerListener", NamingClue.MEMBER_TYPE);
			assertName(suggestions, new FieldTarget("a/a", "c", "I"), "maxHomes", NamingClue.STRING_CONSTANT);
			assertName(suggestions, new FieldTarget("a/a", "d", "Lorg/bukkit/NamespacedKey;"), "homeCountKey", NamingClue.STRING_CONSTANT);
			assertName(suggestions, new FieldTarget("a/a", "e", "Ljava/lang/String;"), "permissionAdmin", NamingClue.STRING_CONSTANT);
			assertName(suggestions, new FieldTarget("a/b", "a", "La/a;"), "plugin", NamingClue.MEMBER_TYPE);
			assertName(suggestions, new FieldTarget("a/f", "a", "Lorg/bukkit/event/HandlerList;"), "HANDLERS", NamingClue.MEMBER_TYPE);
			assertName(suggestions, new FieldTarget("a/f", "b", "Lorg/bukkit/entity/Player;"), "player", NamingClue.MEMBER_TYPE);
		}

		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		void namesMethods(boolean withApi) {
			Map<NameTarget, NameSuggestion> suggestions = suggestionsByTarget(open(withApi));

			assertName(suggestions, new MethodTarget("a/b", "a", "(Lorg/bukkit/event/player/PlayerJoinEvent;)V"), "onPlayerJoin", NamingClue.EVENT);
			assertName(suggestions, new MethodTarget("a/b", "b", "(Lorg/bukkit/event/player/PlayerQuitEvent;)V"), "onPlayerQuit", NamingClue.EVENT);
			assertName(suggestions, new MethodTarget("a/a", "h", "()La/a;"), "getInstance", NamingClue.ACCESSOR);
			assertName(suggestions, new MethodTarget("a/a", "i", "()I"), "getMaxHomes", NamingClue.ACCESSOR);
			assertName(suggestions, new MethodTarget("a/a", "j", "()Ljava/lang/String;"), "getPrefix", NamingClue.STRING_CONSTANT);
			assertName(suggestions, new MethodTarget("a/a", "f", "()V"), "registerListeners", NamingClue.BEHAVIOR);
			assertName(suggestions, new MethodTarget("a/a", "g", "()V"), "registerCommands", NamingClue.BEHAVIOR);

			// Methods the server calls by name must never be renamed.
			assertTrue(suggestions.keySet().stream().noneMatch(t -> t instanceof MethodTarget m &&
					(m.name().equals("onEnable") || m.name().equals("onCommand") || m.name().equals("run") ||
							m.name().equals("getHandlers") || m.name().equals("getHandlerList"))));
		}

		@Test
		void namesVariables() {
			Map<NameTarget, NameSuggestion> suggestions = suggestionsByTarget(open(false));
			String onCommand = PluginIndexer.ON_COMMAND_DESC;

			assertName(suggestions, new VariableTarget("a/b", "a", "(Lorg/bukkit/event/player/PlayerJoinEvent;)V",
					"m", "Lorg/bukkit/event/player/PlayerJoinEvent;", 1), "event", NamingClue.EVENT);
			assertName(suggestions, new VariableTarget("a/c", "onCommand", onCommand, "m", "Lorg/bukkit/command/CommandSender;", 1), "sender", NamingClue.API_TYPE);
			assertName(suggestions, new VariableTarget("a/c", "onCommand", onCommand, "n", "Lorg/bukkit/command/Command;", 2), "command", NamingClue.API_TYPE);
			assertName(suggestions, new VariableTarget("a/c", "onCommand", onCommand, "o", "Ljava/lang/String;", 3), "label", NamingClue.API_TYPE);
			assertName(suggestions, new VariableTarget("a/c", "onCommand", onCommand, "p", "[Ljava/lang/String;", 4), "args", NamingClue.API_TYPE);
			assertName(suggestions, new VariableTarget("a/a", "g", "()V", "n", "La/d;", 2), "sethomeCommand", NamingClue.MEMBER_TYPE);
		}

		@Test
		void obfuscatedNamesAreRecommended() {
			for (NameSuggestion suggestion : service.suggestNames(open(false)))
				assertTrue(suggestion.currentLooksObfuscated(), "Every name in the fixture is obfuscated: " + suggestion);
		}
	}

	@Nested
	class Apply {
		@Test
		void appliedSuggestionsRenameEverythingAndKeepManifestValid() {
			recaf.get(MinecraftPluginMappingListener.class).toString(); // Ensure the manifest listener is registered
			Workspace workspace = open(true);
			List<NameSuggestion> suggestions = service.suggestNames(workspace);
			MappingPlan plan = service.createMappings(workspace, suggestions);
			assertEquals(List.of(), plan.problems());
			assertEquals(suggestions.size(), plan.count());

			recaf.get(MappingApplierService.class).inCurrentWorkspace().applyToPrimaryResource(plan.mappings()).apply();

			var bundle = workspace.getPrimaryResource().getJvmClassBundle();
			var main = bundle.get("a/HomesPlugin");
			assertNotNull(main, "Main class should be renamed");
			assertNotNull(main.getDeclaredMethod("onEnable", "()V"), "Lifecycle methods must keep their names");
			assertNotNull(main.getDeclaredMethod("registerCommands", "()V"));
			assertNotNull(main.getDeclaredField("maxHomes", "I"));
			assertNotNull(bundle.get("a/PlayerListener").getDeclaredMethod("onPlayerJoin", "(Lorg/bukkit/event/player/PlayerJoinEvent;)V"));
			assertNotNull(bundle.get("a/HomeCommand"));

			FileInfo yml = workspace.getPrimaryResource().getFileBundle().get("plugin.yml");
			assertTrue(new String(yml.getRawContent(), StandardCharsets.UTF_8).contains("main: a.HomesPlugin"),
					"The manifest should point at the renamed main class");

			// The index reflects the new names afterwards.
			PluginIndex index = service.getIndex(workspace);
			assertElement(index, PluginElementKind.EVENT_HANDLER, "PlayerJoinEvent",
					"a/PlayerListener.onPlayerJoin(Lorg/bukkit/event/player/PlayerJoinEvent;)V");
			assertTrue(service.suggestNames(workspace).stream().noneMatch(NameSuggestion::currentLooksObfuscated),
					"Nothing obfuscated should be left to suggest");
		}

		@Test
		void userEditedNamesAreUsed() {
			Workspace workspace = open(false);
			NameSuggestion main = service.suggestNames(workspace).stream()
					.filter(s -> s.target().equals(new ClassTarget("a/a")))
					.findFirst().orElseThrow();
			MappingPlan plan = service.createMappings(workspace, List.of(main.withName("Core")));
			assertEquals(1, plan.count());
			assertEquals("a/Core", plan.mappings().getMappedClassName("a/a"));
		}

		@Test
		void invalidAndClashingNamesAreReported() {
			Workspace workspace = open(false);
			List<NameSuggestion> suggestions = service.suggestNames(workspace);
			NameSuggestion main = find(suggestions, new ClassTarget("a/a"));
			NameSuggestion listener = find(suggestions, new ClassTarget("a/b"));
			NameSuggestion field = find(suggestions, new FieldTarget("a/a", "c", "I"));

			MappingPlan plan = service.createMappings(workspace, List.of(
					main.withName("Same"), listener.withName("Same"), // Clash with each other
					field.withName("not valid")));
			assertEquals(1, plan.count(), "Only the first of the clashing classes can be applied");
			assertEquals(2, plan.problems().size(), plan.problems().toString());
		}
	}

	/**
	 * Waits for the service to have a cached index, as it builds them in the background.
	 */
	@Nonnull
	private static PluginIndex awaitCachedIndex(@Nonnull Workspace workspace) {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			PluginIndex index = service.getCachedIndex(workspace);
			if (index != null)
				return index;
			try {
				Thread.sleep(25);
			} catch (InterruptedException ex) {
				throw new AssertionError(ex);
			}
		}
		throw new AssertionError("No index was built in the background");
	}

	@Nonnull
	private static Map<NameTarget, NameSuggestion> suggestionsByTarget(@Nonnull Workspace workspace) {
		return service.suggestNames(workspace).stream()
				.collect(Collectors.toMap(NameSuggestion::target, Function.identity()));
	}

	@Nonnull
	private static NameSuggestion find(@Nonnull List<NameSuggestion> suggestions, @Nonnull NameTarget target) {
		return suggestions.stream().filter(s -> s.target().equals(target)).findFirst()
				.orElseThrow(() -> new AssertionError("No suggestion for " + target));
	}

	private static void assertName(@Nonnull Map<NameTarget, NameSuggestion> suggestions, @Nonnull NameTarget target,
	                               @Nonnull String expected, @Nonnull NamingClue clue) {
		NameSuggestion suggestion = suggestions.get(target);
		assertNotNull(suggestion, () -> "No suggestion for " + target + ", have:\n" + describe(suggestions));
		assertEquals(expected, suggestion.suggestedName(), () -> "Wrong name for " + target + ": " + suggestion);
		assertEquals(clue, suggestion.clue(), () -> "Wrong clue for " + target + ": " + suggestion);
	}

	@Nonnull
	private static PluginElement assertElement(@Nonnull PluginIndex index, @Nonnull PluginElementKind kind,
	                                           @Nonnull String key, @Nonnull String location) {
		return index.ofKind(kind).stream()
				.filter(e -> e.key().equals(key) && e.location().toString().equals(location))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing " + kind + " '" + key + "' at " + location + ", have:\n" +
						index.ofKind(kind).stream().map(Object::toString).collect(Collectors.joining("\n"))));
	}

	@Nonnull
	private static String describe(@Nonnull Map<NameTarget, NameSuggestion> suggestions) {
		return suggestions.values().stream().map(Object::toString).collect(Collectors.joining("\n"));
	}
}
