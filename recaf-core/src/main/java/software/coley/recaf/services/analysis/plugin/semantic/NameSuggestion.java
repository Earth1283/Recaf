package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;

import java.util.Locale;

/**
 * A suggested new name for a class, field, method, or variable, with the reason it was chosen.
 *
 * @param target
 * 		What to rename.
 * @param suggestedName
 * 		The new name. For classes this is the simple name, the package is kept.
 * @param clue
 * 		Kind of evidence the name is based on.
 * @param reason
 * 		Readable explanation of the evidence.
 * @param confidence
 * 		How likely the name is to be right, from {@code 0} to {@code 100}.
 * @param currentLooksObfuscated
 * 		{@code true} when the current name looks like it was made by an obfuscator.
 */
public record NameSuggestion(@Nonnull NameTarget target,
                             @Nonnull String suggestedName,
                             @Nonnull NamingClue clue,
                             @Nonnull String reason,
                             int confidence,
                             boolean currentLooksObfuscated) {
	/**
	 * @param name
	 * 		New name to use.
	 *
	 * @return Copy of this suggestion with a different name, for example one the user typed.
	 */
	@Nonnull
	public NameSuggestion withName(@Nonnull String name) {
		return new NameSuggestion(target, name, clue, reason, confidence, currentLooksObfuscated);
	}

	/**
	 * @return {@code true} when the suggestion should be applied unless the user says otherwise.
	 */
	public boolean isRecommended() {
		return currentLooksObfuscated && confidence >= 50;
	}

	/**
	 * @param query
	 * 		Lowercase search text.
	 *
	 * @return {@code true} when any part of the suggestion contains the query.
	 */
	public boolean matches(@Nonnull String query) {
		if (query.isEmpty())
			return true;
		String text = (target.describe() + ' ' + target.location() + ' ' + suggestedName + ' ' +
				clue.displayName() + ' ' + reason).toLowerCase(Locale.ROOT);
		return text.contains(query);
	}
}
