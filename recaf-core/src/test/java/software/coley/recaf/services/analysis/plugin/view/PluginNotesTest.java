package software.coley.recaf.services.analysis.plugin.view;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityReport;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.SecurityRule;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts;
import software.coley.recaf.services.analysis.plugin.semantic.ObfuscatedPluginFixture;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElement;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElementKind;
import software.coley.recaf.services.analysis.plugin.semantic.PluginIndex;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.test.TestBase;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PluginNotes}.
 */
class PluginNotesTest extends TestBase {
	static JvmClassInfo eventClass;

	@BeforeAll
	static void setup() {
		var workspace = ObfuscatedPluginFixture.create(recaf.get(JavacCompiler.class), false);
		eventClass = workspace.getPrimaryResource().getJvmClassBundle().get("a/f");
	}

	private static PluginIndex index(PluginElement... elements) {
		return new PluginIndex(List.of(), List.of(elements), NamingFacts.EMPTY);
	}

	@Test
	void membersWithoutADeclarationAreMovedToTheClass() {
		CodeLocation clinit = CodeLocation.ofMember("a/f", "<clinit>", "()V");
		PluginIndex index = index(new PluginElement(PluginElementKind.CONFIG_KEY, "settings.x", clinit, null, null));

		PluginNotes notes = PluginNotes.of(eventClass, index, null, 6);

		assertTrue(notes.methodLines().isEmpty());
		assertEquals(List.of("<clinit>: Config path: settings.x"), notes.classLines());
	}

	@Test
	void membersThatPrintADeclarationKeepTheirOwnNotes() {
		CodeLocation handlers = CodeLocation.ofMember("a/f", "getHandlers", "()Lorg/bukkit/event/HandlerList;");
		PluginIndex index = index(new PluginElement(PluginElementKind.EVENT_HANDLER, "PlayerJoinEvent", handlers, null, "HIGH"));

		PluginNotes notes = PluginNotes.of(eventClass, index, null, 6);

		assertEquals(List.of("Event handler: PlayerJoinEvent (priority HIGH)"),
				notes.methodLines().get("getHandlers()Lorg/bukkit/event/HandlerList;"));
		assertTrue(notes.classLines().isEmpty());
	}

	@Test
	void securityComesFirstAndExtraLinesAreCounted() {
		CodeLocation location = CodeLocation.ofClass("a/f");
		SecurityFinding finding = new SecurityFinding(SecurityRule.STRING_ENCRYPTION, location, "12 classes", List.of(), false, null, null);
		PluginElement[] elements = new PluginElement[8];
		for (int i = 0; i < elements.length; i++)
			elements[i] = new PluginElement(PluginElementKind.PERMISSION, "perm." + i, location, null, null);

		PluginNotes notes = PluginNotes.of(eventClass, index(elements), Map.of("a/f", List.of(finding)), 4);

		assertEquals(4, notes.classLines().size());
		assertTrue(notes.classLines().getFirst().startsWith("SECURITY LOW: string encryption (12 classes)"));
		assertEquals("+6 more", notes.classLines().getLast());
	}

	@Test
	void fileFindingsAreCommentedOnlyWhereCodeRefersToTheFile() {
		CodeLocation reader = CodeLocation.ofClass("a/f");
		SecurityFinding referenced = new SecurityFinding(SecurityRule.DISGUISED_CLASS, reader, "class file, Java 17", List.of(), false, null, "data/x.dat");
		SecurityFinding unreferenced = new SecurityFinding(SecurityRule.DISGUISED_BINARY, null, "ELF shared object", List.of(), false, null, "data/y.dat");

		PluginNotes notes = PluginNotes.of(eventClass, null, Map.of("a/f", List.of(referenced, unreferenced)), 6);

		assertEquals(List.of("SECURITY HIGH: disguised class 'data/x.dat' (class file, Java 17)"), notes.classLines());
		PluginSecurityReport report = new PluginSecurityReport(List.of(referenced, unreferenced), 1, 2, List.of());
		assertEquals(Map.of("a/f", List.of(referenced)), PluginNotes.groupSecurity(report, Severity.MEDIUM));
	}

	@Test
	void classesWithNothingToSayHaveEmptyNotes() {
		assertSame(PluginNotes.EMPTY, PluginNotes.of(eventClass, index(), null, 6));
		assertSame(PluginNotes.EMPTY, PluginNotes.of(eventClass, null, null, 6));
	}
}
