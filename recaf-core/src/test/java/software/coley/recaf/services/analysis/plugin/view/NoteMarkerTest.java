package software.coley.recaf.services.analysis.plugin.view;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NoteMarker}.
 */
class NoteMarkerTest {
	private static final String M = "@" + NoteMarker.NAME;

	@Test
	void textWithoutMarkersIsReturnedAsTheSameInstance() {
		String code = "public class A {\n\tvoid a() {}\n}\n";
		assertSame(code, NoteMarker.rewrite(code));
	}

	@Test
	void markerOnItsOwnLineBecomesBlockCommentWithTheSameIndent() {
		String value = NoteMarker.encode(List.of("Command executor: home", "Permission: homes.admin"));
		String code = "class A {\n\t" + M + "(\"" + value + "\")\n\tpublic void a() {}\n}\n";
		assertEquals("""
				class A {
					/*
					 * Command executor: home
					 * Permission: homes.admin
					 */
					public void a() {}
				}
				""", NoteMarker.rewrite(code));
	}

	@Test
	void markerBeforeADeclarationOnTheSameLineBecomesOneLineComment() {
		String value = NoteMarker.encode(List.of("One", "Two"));
		String code = "class A {\n\t" + M + "(\"" + value + "\") public void a() {}\n}\n";
		assertEquals("class A {\n\t/* One; Two */ public void a() {}\n}\n", NoteMarker.rewrite(code));
	}

	@Test
	void valueNamedElementIsRead() {
		String code = "" + M + "(value = \"Main class: Homes\")\nclass A {}\n";
		assertEquals("/*\n * Main class: Homes\n */\nclass A {}\n", NoteMarker.rewrite(code));
	}

	@Test
	void hostileTextCannotEndTheCommentOrStartAnotherMarker() {
		String hostile = "x */ System.exit(0); /* \"quoted\" \\ " + M + "(\"y\") \n\r \u202E gnp.exe";
		String value = NoteMarker.encode(List.of(hostile));
		assertFalse(value.contains("*/"));
		assertFalse(value.contains("\""));
		assertFalse(value.contains("\\"));
		assertFalse(value.contains("@"));
		assertFalse(value.contains("\n"));
		assertFalse(value.contains("\u202E"));

		String rewritten = NoteMarker.rewrite("" + M + "(\"" + value + "\")\nclass A {}\n");
		assertEquals(1, rewritten.split("\\*/", -1).length - 1, "Exactly one comment terminator");
		assertFalse(rewritten.contains("" + M + ""));
	}

	@Test
	void textThatLooksLikeAMarkerButLacksTheNonceIsLeftAlone() {
		String code = "String s = \"@RecafPluginNote(\\\"hidden\\\"); System.exit(0);\";\n@RecafPluginNote(\"x\")\nclass A {}\n";
		assertSame(code, NoteMarker.rewrite(code));
	}

	@Test
	void anUnterminatedMarkerDoesNotSwallowTheLinesAfterIt() {
		String code = "class A {\n\t" + M + "(\"abc\"\n\tint x = foo(1);\n}\n";
		String rewritten = NoteMarker.rewrite(code);
		assertTrue(rewritten.contains("int x = foo(1);"), rewritten);
	}

	@Test
	void longTextIsCapped() {
		String value = NoteMarker.encode(List.of("a".repeat(5000)));
		assertTrue(value.length() <= 200);
	}

	@Test
	void unreadableMarkersAreRemovedWithTheirLine() {
		String code = "class A {\n\t" + M + "\n\tvoid a() {}\n\t" + M + "(5)\n\tvoid b() {}\n}\n";
		assertEquals("class A {\n\tvoid a() {}\n\tvoid b() {}\n}\n", NoteMarker.rewrite(code));
	}

	@Test
	void escapedUnicodeIsDecoded() {
		String code = "" + M + "(\"Config path: caf\\u00e9\")\nclass A {}\n";
		assertEquals("/*\n * Config path: caf\u00e9\n */\nclass A {}\n", NoteMarker.rewrite(code));
	}
}
