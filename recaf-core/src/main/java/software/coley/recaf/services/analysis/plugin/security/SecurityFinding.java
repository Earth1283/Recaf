package software.coley.recaf.services.analysis.plugin.security;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Something worth a look, found by {@link PluginSecurityScanner}.
 *
 * @param rule
 * 		What was found.
 * @param location
 * 		Class or method it was found in.
 * @param detail
 * 		What specifically was used, such as {@code Runtime.exec} or a URL. {@code null} when the rule says it all.
 * @param evidence
 * 		For combined rules, the findings that together make this one. Empty otherwise.
 * @param library
 * 		{@code true} when the location is in the package of a library plugins commonly bundle.
 * 		Such findings are usually the library's own behavior, but malware also hides in such packages.
 * @param embeddedIn
 * 		Path of the jar inside the plugin that the location is in, or {@code null} for the plugin jar itself.
 */
public record SecurityFinding(@Nonnull SecurityRule rule, @Nonnull CodeLocation location, @Nullable String detail,
                              @Nonnull List<SecurityFinding> evidence, boolean library, @Nullable String embeddedIn) {
	/**
	 * @return Severity of the finding.
	 */
	@Nonnull
	public Severity severity() {
		return rule.severity();
	}

	/**
	 * @return Lower-cased text to match searches against.
	 */
	@Nonnull
	public String searchText() {
		StringBuilder text = new StringBuilder()
				.append(rule.id()).append(' ')
				.append(rule.category().name()).append(' ')
				.append(rule.severity().name()).append(' ')
				.append(location.display()).append(' ')
				.append(location.className()).append(' ')
				.append(Objects.requireNonNullElse(detail, ""));
		for (SecurityFinding part : evidence)
			text.append(' ').append(part.rule().id()).append(' ').append(Objects.requireNonNullElse(part.detail(), ""));
		return text.toString().toLowerCase(Locale.ROOT);
	}
}
