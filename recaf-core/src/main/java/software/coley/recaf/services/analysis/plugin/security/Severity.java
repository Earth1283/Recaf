package software.coley.recaf.services.analysis.plugin.security;

/**
 * How much attention a {@link SecurityFinding} deserves. Declared most severe first.
 */
public enum Severity {
	/** Behavior that is rarely legitimate, such as running code that was downloaded or decoded. */
	CRITICAL,
	/** Powerful behavior that a plugin usually does not need, such as running programs or granting operator. */
	HIGH,
	/** Behavior with legitimate uses that is worth checking, such as downloading libraries at runtime. */
	MEDIUM,
	/** Common behavior, listed so the whole picture is visible. */
	LOW;

	/**
	 * @param other
	 * 		Another severity.
	 *
	 * @return {@code true} when this is at least as severe as the other.
	 */
	public boolean isAtLeast(Severity other) {
		return ordinal() <= other.ordinal();
	}
}
