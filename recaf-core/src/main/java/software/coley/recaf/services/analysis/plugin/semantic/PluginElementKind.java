package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;

/**
 * The kinds of plugin structure that {@link PluginIndexer} recognizes.
 * <p>
 * Each kind documents what the {@link PluginElement#key() key}, {@link PluginElement#location() location} and
 * {@link PluginElement#related() related location} of its elements are.
 */
public enum PluginElementKind {
	/** Key: plugin name. Location: the main class. */
	MAIN_CLASS("Main class", "main"),
	/** Key: plugin name. Location: the Paper bootstrapper class. */
	BOOTSTRAPPER("Bootstrapper", "bootstrap"),
	/** Key: plugin name. Location: the Paper loader class. */
	LOADER("Loader", "loader"),
	/** Key: method name. Location: a method the server calls on the main, bootstrapper or loader class. */
	LIFECYCLE("Lifecycle method", "lifecycle"),
	/** Key: class name. Location: the listener class. Related: where it is registered, if found. */
	LISTENER("Listener", "listener"),
	/** Key: event name. Location: the handler method. Related: the event class. */
	EVENT_HANDLER("Event handler", "event handler"),
	/** Key: event name. Location: the method that fires the event. Related: the event class. */
	EVENT_CALL("Event fired", "event call fire"),
	/** Key: class name. Location: an event class declared by the plugin. */
	CUSTOM_EVENT("Custom event", "event custom"),
	/** Key: command name. Location: the method that registers the command. */
	COMMAND("Command registration", "command register"),
	/** Key: command name, or empty if unknown. Location: executor class or method. Related: where it is registered. */
	COMMAND_EXECUTOR("Command executor", "command executor"),
	/** Key: command name, or empty if unknown. Location: completer class or method. Related: where it is registered. */
	TAB_COMPLETER("Tab completer", "command tab complete"),
	/** Key: scheduling method. Location: the task class or method. Related: where it is scheduled, if found. */
	TASK("Scheduled task", "task schedule runnable"),
	/** Key: config path. Location: the method reading or writing it. */
	CONFIG_KEY("Config path", "config"),
	/** Key: permission node. Location: the method checking or declaring it. */
	PERMISSION("Permission", "permission perm"),
	/** Key: channel name. Location: the method using it. */
	CHANNEL("Plugin channel", "channel message"),
	/** Key: namespaced key. Location: the method creating it. */
	NAMESPACED_KEY("Namespaced key", "namespacedkey pdc"),
	/** Key: metadata key. Location: the method using it. */
	METADATA_KEY("Metadata key", "metadata"),
	/** Key: other plugin, or service type. Location: the method looking it up. */
	PLUGIN_HOOK("Plugin hook", "hook depend soft service"),
	/** Key: class name. Location: a class stored in config files. */
	SERIALIZABLE("Serializable type", "serializable"),
	/** Key: class name. Location: an inventory holder, usually backing a GUI. */
	INVENTORY_HOLDER("Inventory holder", "gui menu inventory"),
	/** Key: expansion identifier or class name. Location: the PlaceholderAPI expansion class. */
	PLACEHOLDER_EXPANSION("Placeholder expansion", "placeholder papi"),
	/** Key: class name. Location: the plugin message listener class. */
	MESSAGE_LISTENER("Plugin message listener", "channel message");

	private final String displayName;
	private final String searchTerms;

	PluginElementKind(@Nonnull String displayName, @Nonnull String searchTerms) {
		this.displayName = displayName;
		this.searchTerms = searchTerms;
	}

	/**
	 * @return English name of the kind.
	 */
	@Nonnull
	public String displayName() {
		return displayName;
	}

	/**
	 * @return Lowercase words that searches for this kind are likely to use.
	 */
	@Nonnull
	public String searchTerms() {
		return searchTerms;
	}
}
