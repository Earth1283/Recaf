package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.Severity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Decides which findings the security triage lists and counts what the current view leaves out.
 */
public final class SecurityFilter {
	private SecurityFilter() {}

	/**
	 * @param findings
	 * 		All findings of a report.
	 * @param scope
	 * 		Which findings to list.
	 * @param minimum
	 * 		Least severe finding to list.
	 * @param words
	 * 		Lower-cased words that all have to appear in a finding's search text. Empty to list everything.
	 * @param searchText
	 * 		Lower-cased text of a finding that the words are looked up in.
	 *
	 * @return What the view lists and what it leaves out.
	 */
	@Nonnull
	public static Result apply(@Nonnull List<SecurityFinding> findings, @Nonnull SecurityScope scope,
	                           @Nonnull Severity minimum, @Nonnull String[] words,
	                           @Nonnull Function<SecurityFinding, String> searchText) {
		List<SecurityFinding> shown = new ArrayList<>();
		Map<Severity, Integer> hiddenSerious = new EnumMap<>(Severity.class);
		int inScope = 0;
		int otherScopeMatches = 0;
		for (SecurityFinding finding : findings) {
			boolean matches = finding.severity().isAtLeast(minimum) && matchesAll(words, searchText.apply(finding));
			if (scope.includes(finding)) {
				inScope++;
				if (matches)
					shown.add(finding);
			} else {
				if (finding.severity().isAtLeast(Severity.HIGH))
					hiddenSerious.merge(finding.severity(), 1, Integer::sum);
				if (matches)
					otherScopeMatches++;
			}
		}
		if (scope == SecurityScope.EVERYTHING)
			shown = withLibrariesLast(shown);
		return new Result(shown, inScope, findings.size() - inScope, hiddenSerious, otherScopeMatches);
	}

	/**
	 * @param shown
	 * 		Findings in the order of the report.
	 *
	 * @return The same findings with those in bundled libraries after the plugin's own, otherwise in the same order.
	 */
	@Nonnull
	private static List<SecurityFinding> withLibrariesLast(@Nonnull List<SecurityFinding> shown) {
		List<SecurityFinding> ordered = new ArrayList<>(shown.size());
		for (SecurityFinding finding : shown)
			if (!finding.library())
				ordered.add(finding);
		for (SecurityFinding finding : shown)
			if (finding.library())
				ordered.add(finding);
		return ordered;
	}

	private static boolean matchesAll(@Nonnull String[] words, @Nonnull String text) {
		for (String word : words)
			if (!text.contains(word))
				return false;
		return true;
	}

	/**
	 * @param shown
	 * 		Findings to list, within the scope, at least as severe as the minimum and matching the search.
	 * @param inScope
	 * 		Number of findings in the scope, before the minimum severity and the search.
	 * @param outOfScope
	 * 		Number of findings the scope leaves out.
	 * @param hiddenSerious
	 * 		Number of {@link Severity#HIGH} and {@link Severity#CRITICAL} findings the scope leaves out, by severity.
	 * @param otherScopeMatches
	 * 		Number of findings the scope leaves out that pass the minimum severity and the search.
	 */
	public record Result(@Nonnull List<SecurityFinding> shown, int inScope, int outOfScope,
	                     @Nonnull Map<Severity, Integer> hiddenSerious, int otherScopeMatches) {
		/**
		 * @return Number of serious findings the scope leaves out.
		 */
		public int hiddenSeriousCount() {
			return hiddenSerious.values().stream().mapToInt(Integer::intValue).sum();
		}
	}
}
