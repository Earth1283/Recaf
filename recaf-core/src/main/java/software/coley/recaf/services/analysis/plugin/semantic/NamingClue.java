package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;

/**
 * The kind of evidence a {@link NameSuggestion} is based on.
 */
public enum NamingClue {
	/** The plugin manifest, such as the plugin name or a declared command. */
	MANIFEST("Manifest"),
	/** An API type the class extends or implements, such as {@code Listener}. */
	API_TYPE("API type"),
	/** The event an event handler receives. */
	EVENT("Event"),
	/** The type of a field or variable. */
	MEMBER_TYPE("Type"),
	/** A method only gets or sets a field. */
	ACCESSOR("Accessor"),
	/** What a method does with the API, such as registering listeners. */
	BEHAVIOR("Behavior"),
	/** A string passed to the API, such as a config path, permission node, or command name. */
	STRING_CONSTANT("String constant");

	private final String displayName;

	NamingClue(@Nonnull String displayName) {
		this.displayName = displayName;
	}

	/**
	 * @return English name of the clue.
	 */
	@Nonnull
	public String displayName() {
		return displayName;
	}
}
