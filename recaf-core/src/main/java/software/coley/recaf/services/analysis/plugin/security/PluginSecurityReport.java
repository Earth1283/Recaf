package software.coley.recaf.services.analysis.plugin.security;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Result of {@link PluginSecurityScanner}.
 *
 * @param findings
 * 		Findings, most severe first.
 * @param classesScanned
 * 		Number of classes read.
 * @param filesScanned
 * 		Number of files that are not classes whose content was checked.
 * @param embeddedJars
 * 		Paths of jars inside the plugin whose classes were read too. Nested jars are joined with {@code " > "}.
 */
public record PluginSecurityReport(@Nonnull List<SecurityFinding> findings, int classesScanned,
                                   int filesScanned, @Nonnull List<String> embeddedJars) {
	/** Report with nothing in it. */
	public static final PluginSecurityReport EMPTY = new PluginSecurityReport(List.of(), 0, 0, List.of());

	/**
	 * @return Severity of the most severe finding, or {@code null} when there are none.
	 */
	@Nullable
	public Severity highest() {
		return findings.isEmpty() ? null : findings.getFirst().severity();
	}

	/**
	 * @param includeLibraries
	 *        {@code false} to leave out findings in bundled libraries.
	 *
	 * @return Number of findings of each severity. Every severity is present.
	 */
	@Nonnull
	public Map<Severity, Integer> counts(boolean includeLibraries) {
		Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
		for (Severity severity : Severity.values())
			counts.put(severity, 0);
		for (SecurityFinding finding : findings)
			if (includeLibraries || !finding.library())
				counts.merge(finding.severity(), 1, Integer::sum);
		return counts;
	}

	/**
	 * @param rule
	 * 		Some rule.
	 *
	 * @return Findings of the rule.
	 */
	@Nonnull
	public List<SecurityFinding> ofRule(@Nonnull SecurityRule rule) {
		return findings.stream().filter(f -> f.rule() == rule).toList();
	}
}
