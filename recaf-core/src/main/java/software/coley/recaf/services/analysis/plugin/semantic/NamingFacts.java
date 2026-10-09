package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Facts found while indexing that are only used to suggest names, and are not themselves plugin structure.
 *
 * @param valueFlows
 * 		Values from the plugin API that are stored in a field or variable, or returned from a method.
 * @param constantUses
 * 		Constant fields whose value is used as a plugin key, such as a permission node.
 * @param accessors
 * 		Methods that only get or set a field.
 * @param behaviors
 * 		What methods do with the plugin API, keyed by method location.
 */
public record NamingFacts(@Nonnull List<ValueFlow> valueFlows,
                          @Nonnull List<ConstantUse> constantUses,
                          @Nonnull List<Accessor> accessors,
                          @Nonnull Map<CodeLocation, Set<Behavior>> behaviors) {
	/** Facts with nothing in them. */
	public static final NamingFacts EMPTY = new NamingFacts(List.of(), List.of(), List.of(), Map.of());

	/**
	 * Where a {@link ValueFlow} goes.
	 */
	public enum FlowTarget {
		/** Stored in a field. */
		FIELD,
		/** Stored in a local variable. */
		LOCAL,
		/** Returned from the method. */
		RETURN
	}

	/**
	 * A value from the plugin API, such as {@code config.getInt("max-homes")}, that is stored or returned.
	 *
	 * @param target
	 * 		Where the value goes.
	 * @param method
	 * 		Method the value is produced in.
	 * @param field
	 * 		The field the value is stored in, for {@link FlowTarget#FIELD}.
	 * @param localIndex
	 * 		The variable index the value is stored in, for {@link FlowTarget#LOCAL}. Otherwise {@code -1}.
	 * @param source
	 * 		Kind of key the value was produced from, such as {@link PluginElementKind#CONFIG_KEY}.
	 * @param key
	 * 		The key, such as the config path.
	 * @param apiMethod
	 * 		Name of the API method that produced the value, such as {@code getInt}.
	 */
	public record ValueFlow(@Nonnull FlowTarget target,
	                        @Nonnull CodeLocation method,
	                        @Nullable CodeLocation field,
	                        int localIndex,
	                        @Nonnull PluginElementKind source,
	                        @Nonnull String key,
	                        @Nonnull String apiMethod) {}

	/**
	 * A constant field whose value is passed to the plugin API as a key.
	 *
	 * @param field
	 * 		The constant field.
	 * @param kind
	 * 		Kind of key, such as {@link PluginElementKind#PERMISSION}.
	 * @param value
	 * 		Value of the constant.
	 */
	public record ConstantUse(@Nonnull CodeLocation field, @Nonnull PluginElementKind kind, @Nonnull String value) {}

	/**
	 * Kinds of {@link Accessor}.
	 */
	public enum AccessorKind {
		/** Returns the field. */
		GETTER,
		/** Sets the field to the only parameter. */
		SETTER,
		/** Static method returning a static field holding an instance of the declaring class. */
		INSTANCE
	}

	/**
	 * A method that does nothing but get or set a field of its own class.
	 *
	 * @param method
	 * 		The method.
	 * @param field
	 * 		The field.
	 * @param kind
	 * 		How the method accesses the field.
	 */
	public record Accessor(@Nonnull CodeLocation method, @Nonnull CodeLocation field, @Nonnull AccessorKind kind) {}

	/**
	 * Things a method does that hint at what it is for.
	 */
	public enum Behavior {
		/** Registers event listeners. */
		REGISTERS_LISTENERS("registerListeners"),
		/** Registers commands. */
		REGISTERS_COMMANDS("registerCommands"),
		/** Schedules tasks. */
		SCHEDULES_TASKS("startTasks"),
		/** Loads or saves the config. */
		LOADS_CONFIG("loadConfig"),
		/** Looks up Vault's economy service. */
		HOOKS_ECONOMY("setupEconomy"),
		/** Looks up other plugins or services. */
		HOOKS_PLUGINS("setupHooks"),
		/** Starts bStats metrics. */
		SETS_UP_METRICS("setupMetrics"),
		/** Opens a database connection. */
		CONNECTS_DATABASE("connectDatabase"),
		/** Registers plugin messaging channels. */
		REGISTERS_CHANNELS("registerChannels");

		private final String methodName;

		Behavior(@Nonnull String methodName) {
			this.methodName = methodName;
		}

		/**
		 * @return Name for a method that does only this.
		 */
		@Nonnull
		public String methodName() {
			return methodName;
		}
	}
}
