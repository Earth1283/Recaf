package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.SecurityRule;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FindingNeedles}.
 */
class FindingNeedlesTest {
	private static final CodeLocation METHOD = CodeLocation.ofMember("a/Main", "run", "()V");

	private static SecurityFinding finding(SecurityRule rule, @Nullable String detail) {
		return new SecurityFinding(rule, METHOD, detail, List.of(), false, null, null);
	}

	@Test
	void constructorUsesTheDetailAsWritten() {
		assertEquals(List.of("new URLClassLoader"), FindingNeedles.of(finding(SecurityRule.CLASS_LOADER, "new URLClassLoader")));
	}

	@Test
	void apiCallsTryTheMethodWithAndWithoutReceiver() {
		List<String> needles = FindingNeedles.of(finding(SecurityRule.PROCESS_EXEC, "Runtime.exec"));
		assertEquals(List.of(".exec(", "exec(", "Runtime.exec"), needles);
		needles = FindingNeedles.of(finding(SecurityRule.PROCESS_EXEC, "ProcessBuilder.start"));
		assertEquals(".start(", needles.get(0));
		assertEquals("start(", needles.get(1));
	}

	@Test
	void qualifiedApiCallsKeepTheLastTwoNamesOnly() {
		List<String> needles = FindingNeedles.of(finding(SecurityRule.DECODE, "Base64.Decoder.decode"));
		assertEquals(".decode(", needles.get(0));
		assertEquals("decode(", needles.get(1));
	}

	@Test
	void argumentsOfApiCallsAreTriedFirst() {
		List<String> needles = FindingNeedles.of(finding(SecurityRule.WILDCARD_PERMISSION, "Player.addAttachment(\"*\")"));
		assertEquals("addAttachment(\"*\")", needles.get(0));
		assertTrue(needles.contains(".addAttachment("));
	}

	@Test
	void chainedCallsAreTriedWholeThenByTheirLastCall() {
		List<String> needles = FindingNeedles.of(finding(SecurityRule.PLUGINS_FOLDER, "getDataFolder().getParentFile()"));
		assertEquals(List.of("getDataFolder().getParentFile()", "getParentFile()", "getParentFile("), needles);
	}

	@Test
	void urlsAreTriedAsTextThenByHost() {
		List<String> needles = FindingNeedles.of(finding(SecurityRule.SUSPICIOUS_URL, "https://discord.com/api/webhooks/1/x"));
		assertEquals("https://discord.com/api/webhooks/1/x", needles.get(0));
		assertEquals("discord.com", needles.getLast());
	}

	@Test
	void stringLiteralsAreTriedEscapedFirst() {
		List<String> needles = FindingNeedles.of(finding(SecurityRule.PRIVILEGE_COMMAND, "op \"Steve\"\n"));
		assertEquals("op \\\"Steve\\\"\\n", needles.get(0));
		assertEquals("op \"Steve\"\n", needles.get(1));
	}

	@Test
	void truncatedLiteralsAreTriedByTheirPrefix() {
		String prefix = "x".repeat(160);
		List<String> needles = FindingNeedles.of(finding(SecurityRule.URL, prefix + "…"));
		assertTrue(needles.stream().noneMatch(n -> n.contains("…")));
		assertEquals("x".repeat(40), needles.get(0));
	}

	@Test
	void reflectiveStringIsTriedQuoted() {
		List<String> needles = FindingNeedles.of(finding(SecurityRule.CLASS_LOADER, "addURL (reflection)"));
		assertEquals(List.of("\"addURL\"", "addURL"), needles);
	}

	@Test
	void fileNamesLookLikeLiteralsRatherThanCalls() {
		List<String> needles = FindingNeedles.of(finding(SecurityRule.SENSITIVE_FILE, "ops.json"));
		assertEquals(List.of("ops.json"), needles);
	}

	@Test
	void detailsThatCannotAppearInSourceGiveNothing() {
		assertEquals(List.of(), FindingNeedles.of(finding(SecurityRule.STRING_ENCRYPTION, "12 classes")));
		assertEquals(List.of(), FindingNeedles.of(finding(SecurityRule.PROCESS_EXEC, null)));
		assertEquals(List.of(), FindingNeedles.of(finding(SecurityRule.PROCESS_EXEC, "  ")));
	}

	@Test
	void fileFindingsUseTheFileNotTheDescription() {
		SecurityFinding file = new SecurityFinding(SecurityRule.DISGUISED_ARCHIVE, METHOD, "ZIP archive", List.of(),
				false, null, "assets/data.bin");
		assertEquals(List.of("assets/data.bin", "data.bin"), FindingNeedles.of(file));
	}

	@Test
	void combinedFindingsFallBackToTheirEvidence() {
		SecurityFinding define = finding(SecurityRule.DEFINE_CLASS, "ClassLoader.defineClass");
		SecurityFinding combined = new SecurityFinding(SecurityRule.REMOTE_CODE, METHOD, "evil.example, other.example",
				List.of(define), false, null, null);
		List<String> needles = FindingNeedles.of(combined);
		assertEquals("evil.example, other.example", needles.get(0));
		assertTrue(needles.indexOf(".defineClass(") > 0);
	}
}
