package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityReport;
import software.coley.recaf.services.analysis.plugin.security.SecurityCategory;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.SecurityRule;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.Lang;

import java.util.Locale;
import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Text, icons and colors for security triage results.
 */
public final class SecurityDisplay {
	private SecurityDisplay() {}

	/**
	 * @param severity
	 * 		Some severity.
	 *
	 * @return Icon of the severity, in its color.
	 */
	@Nonnull
	public static FontIconView icon(@Nonnull Severity severity) {
		Ikon icon = switch (severity) {
			case CRITICAL -> CarbonIcons.ERROR_FILLED;
			case HIGH -> CarbonIcons.WARNING_ALT_FILLED;
			case MEDIUM -> CarbonIcons.WARNING_ALT;
			case LOW -> CarbonIcons.INFORMATION;
		};
		return new FontIconView(icon, color(severity));
	}

	/**
	 * @param severity
	 * 		Some severity.
	 *
	 * @return Color that stands out on both light and dark themes.
	 */
	@Nonnull
	public static Color color(@Nonnull Severity severity) {
		return switch (severity) {
			case CRITICAL -> Color.web("#e5484d");
			case HIGH -> Color.web("#f76b15");
			case MEDIUM -> Color.web("#d6a100");
			case LOW -> Color.web("#8b8d98");
		};
	}

	/**
	 * @param severity
	 * 		Some severity.
	 *
	 * @return Translated name.
	 */
	@Nonnull
	public static String name(@Nonnull Severity severity) {
		return Lang.get("mcplugin.security.severity." + severity.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * @param rule
	 * 		Some rule.
	 *
	 * @return Translated short description of what the rule found.
	 */
	@Nonnull
	public static String title(@Nonnull SecurityRule rule) {
		return Lang.get("mcplugin.security.rule." + rule.id());
	}

	/**
	 * @param rule
	 * 		Some rule.
	 *
	 * @return Translated explanation of why the rule matters.
	 */
	@Nonnull
	public static String tip(@Nonnull SecurityRule rule) {
		return Lang.get("mcplugin.security.rule." + rule.id() + ".tip");
	}

	/**
	 * @param category
	 * 		Some category.
	 *
	 * @return Icon of the category.
	 */
	@Nonnull
	public static Ikon icon(@Nonnull SecurityCategory category) {
		return switch (category) {
			case EXECUTION -> CarbonIcons.TERMINAL;
			case DYNAMIC_CODE -> CarbonIcons.CODE;
			case NATIVE_CODE -> CarbonIcons.APPLICATION;
			case NETWORK -> CarbonIcons.EARTH_FILLED;
			case FILE_SYSTEM -> CarbonIcons.FOLDER;
			case SENSITIVE_DATA -> CarbonIcons.LOCKED;
			case PRIVILEGE -> CarbonIcons.UNLOCKED;
			case SERVER_CONTROL -> CarbonIcons.STOP_FILLED;
			case EVASION -> CarbonIcons.VIEW;
		};
	}

	/**
	 * @param report
	 * 		Some report.
	 *
	 * @return Counts of the plugin's own findings by severity, such as {@code 2 Medium, 41 Low}, followed by the
	 * counts of serious findings in bundled libraries when there are any. Or a statement that nothing was found.
	 */
	@Nonnull
	public static String verdict(@Nonnull PluginSecurityReport report) {
		if (report.findings().isEmpty())
			return Lang.get("mcplugin.security.verdict.none");
		String own = counts(report.counts(false), Severity.LOW);
		Map<Severity, Integer> libraryCounts = new EnumMap<>(Severity.class);
		report.counts(true).forEach((severity, count) -> libraryCounts.put(severity, count - report.counts(false).get(severity)));
		String libraries = counts(libraryCounts, Severity.HIGH);
		if (libraries.isEmpty())
			return own.isEmpty() ? Lang.get("mcplugin.security.verdict.none") : own;
		String librariesText = String.format(Lang.get("mcplugin.security.verdict.libraries"), libraries);
		return own.isEmpty() ? librariesText : own + "; " + librariesText;
	}

	/**
	 * @param report
	 * 		Some report.
	 *
	 * @return Severity to show for the report: that of the plugin's own most severe finding, or of a bundled
	 * library's when the plugin itself has none. {@code null} when there are no findings.
	 */
	@Nullable
	public static Severity headline(@Nonnull PluginSecurityReport report) {
		for (SecurityFinding finding : report.findings())
			if (!finding.library())
				return finding.severity();
		return report.highest();
	}

	@Nonnull
	private static String counts(@Nonnull Map<Severity, Integer> counts, @Nonnull Severity minimum) {
		StringJoiner joiner = new StringJoiner(", ");
		counts.forEach((severity, count) -> {
			if (count > 0 && severity.isAtLeast(minimum))
				joiner.add(String.format(Lang.get("mcplugin.security.verdict.count"), count, name(severity)));
		});
		return joiner.toString();
	}
}
