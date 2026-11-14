package software.coley.recaf.ui.pane.plugin;

import org.junit.jupiter.api.Test;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.SecurityRule;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SecurityFilter}.
 */
class SecurityFilterTest {
	private static final SecurityFinding OWN_HIGH = finding(SecurityRule.PROCESS_EXEC, "a/Own", "Runtime.exec", false);
	private static final SecurityFinding OWN_LOW = finding(SecurityRule.URL, "a/Own", "https://example.com", false);
	private static final SecurityFinding LIB_HIGH = finding(SecurityRule.PROCESS_EXEC, "lib/Lib", "ProcessBuilder.start", true);
	private static final SecurityFinding LIB_CRITICAL = finding(SecurityRule.REMOTE_CODE, "lib/Lib", null, true);
	private static final SecurityFinding LIB_LOW = finding(SecurityRule.URL, "lib/Lib", "https://example.org", true);
	private static final List<SecurityFinding> ALL = List.of(LIB_CRITICAL, OWN_HIGH, LIB_HIGH, LIB_LOW, OWN_LOW);
	private static final String[] NO_WORDS = new String[0];

	private static SecurityFinding finding(SecurityRule rule, String className, String detail, boolean library) {
		return new SecurityFinding(rule, CodeLocation.ofClass(className), detail, List.of(), library, null, null);
	}

	private static SecurityFilter.Result apply(SecurityScope scope, Severity minimum, String... words) {
		return SecurityFilter.apply(ALL, scope, minimum, words, SecurityFinding::searchText);
	}

	@Test
	void pluginScopeListsOnlyTheOwnFindings() {
		SecurityFilter.Result result = apply(SecurityScope.PLUGIN, Severity.LOW);
		assertEquals(List.of(OWN_HIGH, OWN_LOW), result.shown());
		assertEquals(2, result.inScope());
		assertEquals(3, result.outOfScope());
	}

	@Test
	void librariesScopeListsOnlyTheLibraryFindings() {
		SecurityFilter.Result result = apply(SecurityScope.LIBRARIES, Severity.LOW);
		assertEquals(List.of(LIB_CRITICAL, LIB_HIGH, LIB_LOW), result.shown());
		assertEquals(3, result.inScope());
	}

	@Test
	void everythingListsLibrariesAfterTheOwnFindingsKeepingTheOrderOtherwise() {
		SecurityFilter.Result result = apply(SecurityScope.EVERYTHING, Severity.LOW);
		assertEquals(List.of(OWN_HIGH, OWN_LOW, LIB_CRITICAL, LIB_HIGH, LIB_LOW), result.shown());
		assertEquals(5, result.inScope());
		assertEquals(0, result.outOfScope());
		assertEquals(0, result.otherScopeMatches());
	}

	@Test
	void minimumSeverityFiltersWithinTheScope() {
		SecurityFilter.Result result = apply(SecurityScope.PLUGIN, Severity.HIGH);
		assertEquals(List.of(OWN_HIGH), result.shown());
		assertEquals(2, result.inScope());
	}

	@Test
	void seriousFindingsOutsideTheScopeAreCountedBySeverity() {
		SecurityFilter.Result result = apply(SecurityScope.PLUGIN, Severity.LOW);
		assertEquals(Map.of(Severity.CRITICAL, 1, Severity.HIGH, 1), result.hiddenSerious());
		assertEquals(2, result.hiddenSeriousCount());
		result = apply(SecurityScope.LIBRARIES, Severity.LOW);
		assertEquals(Map.of(Severity.HIGH, 1), result.hiddenSerious());
		assertTrue(apply(SecurityScope.EVERYTHING, Severity.LOW).hiddenSerious().isEmpty());
	}

	@Test
	void hiddenSeriousFindingsIgnoreTheSeverityFilterAndSearch() {
		SecurityFilter.Result result = apply(SecurityScope.PLUGIN, Severity.CRITICAL, "nomatch");
		assertEquals(2, result.hiddenSeriousCount());
	}

	@Test
	void searchMatchesAllWordsAndCountsMatchesInTheOtherScope() {
		SecurityFilter.Result result = apply(SecurityScope.PLUGIN, Severity.LOW, "processbuilder");
		assertEquals(List.of(), result.shown());
		assertEquals(2, result.inScope());
		assertEquals(1, result.otherScopeMatches());

		result = apply(SecurityScope.PLUGIN, Severity.LOW, "example", "org");
		assertEquals(List.of(), result.shown());
		assertEquals(1, result.otherScopeMatches());

		result = apply(SecurityScope.PLUGIN, Severity.LOW, "example");
		assertEquals(List.of(OWN_LOW), result.shown());
		assertEquals(1, result.otherScopeMatches());
	}

	@Test
	void otherScopeMatchesRespectTheMinimumSeverity() {
		SecurityFilter.Result result = apply(SecurityScope.PLUGIN, Severity.CRITICAL, "url");
		assertEquals(0, result.otherScopeMatches());
		result = apply(SecurityScope.PLUGIN, Severity.CRITICAL);
		assertEquals(1, result.otherScopeMatches());
	}

	@Test
	void emptyScopeIsDistinguishedFromNoMatches() {
		SecurityFilter.Result result = SecurityFilter.apply(List.of(LIB_HIGH, LIB_LOW), SecurityScope.PLUGIN,
				Severity.LOW, NO_WORDS, SecurityFinding::searchText);
		assertEquals(0, result.inScope());
		assertEquals(2, result.outOfScope());
		assertTrue(result.shown().isEmpty());
	}
}
