package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableInteger;
import software.coley.observables.ObservableString;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Config for {@link DecompilerManager}
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class DecompilerManagerConfig extends BasicConfigContainer implements ServiceConfig {
	public static final String KEY_PREF_JVM_DECOMPILER = "pref-jvm-decompiler";
	public static final String KEY_PREF_ANDROID_DECOMPILER = "pref-android-decompiler";
	private final ObservableString preferredJvmDecompiler = new ObservableString(null);
	private final ObservableString preferredAndroidDecompiler = new ObservableString(null);
	private final ObservableBoolean cacheDecompilations = new ObservableBoolean(true);
	private final ObservableBoolean filterDebug = new ObservableBoolean(false);
	private final ObservableBoolean filterHollow = new ObservableBoolean(false);
	private final ObservableBoolean filterIllegalAnnotations = new ObservableBoolean(false);
	private final ObservableBoolean filterDuplicateAnnotations = new ObservableBoolean(false);
	private final ObservableBoolean filterLongAnnotations = new ObservableBoolean(false);
	private final ObservableInteger filterLongAnnotationsLength = new ObservableInteger(256);
	private final ObservableBoolean filterLongExceptions = new ObservableBoolean(false);
	private final ObservableInteger filterLongExceptionsLength = new ObservableInteger(256);
	private final ObservableBoolean filterSignatures = new ObservableBoolean(false);
	private final ObservableBoolean filterSynthetics = new ObservableBoolean(false);
	private final ObservableBoolean filterNonAsciiNames = new ObservableBoolean(false);
	private final ObservableBoolean filterRepairTryCatch = new ObservableBoolean(true);
	private final ObservableBoolean filterRepairDebugRanges = new ObservableBoolean(true);
	private final ObservableBoolean filterRemoveConstantDefinitions = new ObservableBoolean(false);

	@Inject
	public DecompilerManagerConfig() {
		super(ConfigGroups.SERVICE_DECOMPILE, DecompilerManager.SERVICE_ID + CONFIG_SUFFIX);
		// Add values
		addValue(new BasicConfigValue<>(KEY_PREF_JVM_DECOMPILER, String.class, preferredJvmDecompiler));
		addValue(new BasicConfigValue<>(KEY_PREF_ANDROID_DECOMPILER, String.class, preferredAndroidDecompiler));
		addValue(new BasicConfigValue<>("cache-decompilations", boolean.class, cacheDecompilations));
		addValue(new BasicConfigValue<>("filter-strip-debug", boolean.class, filterDebug));
		addValue(new BasicConfigValue<>("filter-hollow", boolean.class, filterHollow));
		addValue(new BasicConfigValue<>("filter-annotations-illegal", boolean.class, filterIllegalAnnotations));
		addValue(new BasicConfigValue<>("filter-annotations-duplicate", boolean.class, filterDuplicateAnnotations));
		addValue(new BasicConfigValue<>("filter-annotations-long", boolean.class, filterLongAnnotations));
		addValue(new BasicConfigValue<>("filter-annotations-long-limit", int.class, filterLongAnnotationsLength));
		addValue(new BasicConfigValue<>("filter-exceptions-long", boolean.class, filterLongExceptions));
		addValue(new BasicConfigValue<>("filter-exceptions-long-limit", int.class, filterLongExceptionsLength));
		addValue(new BasicConfigValue<>("filter-illegal-signatures", boolean.class, filterSignatures));
		addValue(new BasicConfigValue<>("filter-synthetics", boolean.class, filterSynthetics));
		addValue(new BasicConfigValue<>("filter-names-ascii", boolean.class, filterNonAsciiNames));
		addValue(new BasicConfigValue<>("filter-repair-try-catch", boolean.class, filterRepairTryCatch));
		addValue(new BasicConfigValue<>("filter-repair-debug-ranges", boolean.class, filterRepairDebugRanges));
		addValue(new BasicConfigValue<>("filter-remove-constant-definitions", boolean.class, filterRemoveConstantDefinitions));
	}

	/**
	 * @return {@link JvmDecompiler#getName()} for preferred JVM decompiler to use in {@link DecompilerManager}.
	 */
	@Nonnull
	public ObservableString getPreferredJvmDecompiler() {
		return preferredJvmDecompiler;
	}

	/**
	 * @return {@link AndroidDecompiler#getName()} for preferred JVM decompiler to use in {@link DecompilerManager}.
	 */
	@Nonnull
	public ObservableString getPreferredAndroidDecompiler() {
		return preferredAndroidDecompiler;
	}

	/**
	 * @return {@code true} to cache the results of decompilation tasks in via the {@link DecompilerManager}.
	 */
	@Nonnull
	public ObservableBoolean getCacheDecompilations() {
		return cacheDecompilations;
	}

	/**
	 * @return {@code true} to filter out <i>all</i> debug information including generics, line numbers, variable names, etc.
	 */
	@Nonnull
	public ObservableBoolean getFilterDebug() {
		return filterDebug;
	}

	/**
	 * @return {@code true}
	 */
	@Nonnull
	public ObservableBoolean getFilterHollow() {
		return filterHollow;
	}

	/**
	 * @return {@code true} to filter out illegally typed annotations.
	 */
	@Nonnull
	public ObservableBoolean getFilterIllegalAnnotations() {
		return filterIllegalAnnotations;
	}

	/**
	 * @return {@code true} to filter out duplicate annotations applied to classes/fields/methods.
	 */
	@Nonnull
	public ObservableBoolean getFilterDuplicateAnnotations() {
		return filterDuplicateAnnotations;
	}

	/**
	 * @return {@code true} to filter out long named annotations.
	 */
	@Nonnull
	public ObservableBoolean getFilterLongAnnotations() {
		return filterLongAnnotations;
	}

	/**
	 * @return Max name length to allowed for {@link #getFilterLongAnnotations()}.
	 */
	@Nonnull
	public ObservableInteger getFilterLongAnnotationsLength() {
		return filterLongAnnotationsLength;
	}

	/**
	 * @return {@code true} to filter out long named exceptions.
	 */
	@Nonnull
	public ObservableBoolean getFilterLongExceptions() {
		return filterLongExceptions;
	}

	/**
	 * @return Max name length to allowed for {@link #getFilterLongExceptions()}.
	 */
	@Nonnull
	public ObservableInteger getFilterLongExceptionsLength() {
		return filterLongExceptionsLength;
	}

	/**
	 * @return {@code true} to strip out illegal signatures from classes.
	 */
	@Nonnull
	public ObservableBoolean getFilterSignatures() {
		return filterSignatures;
	}

	/**
	 * @return {@code true} to strip out synthetic/bridge modifiers from classes, fields, and methods.
	 */
	@Nonnull
	public ObservableBoolean getFilterSynthetics() {
		return filterSynthetics;
	}

	/**
	 * @return {@code true} to filter out any non-ascii referenced name.
	 */
	@Nonnull
	public ObservableBoolean getFilterNonAsciiNames() {
		return filterNonAsciiNames;
	}

	/**
	 * @return {@code true} to repair no-op identity-rethrow and degenerate try-catch blocks before decompiling.
	 */
	@Nonnull
	public ObservableBoolean getFilterRepairTryCatch() {
		return filterRepairTryCatch;
	}

	/**
	 * @return {@code true} to repair malformed local-variable debug ranges before decompiling.
	 */
	@Nonnull
	public ObservableBoolean getFilterRepairDebugRanges() {
		return filterRepairDebugRanges;
	}

	/**
	 * @return {@code true} to propagate inlineable constants and remove dead constant definitions in decompiled Java text.
	 */
	@Nonnull
	public ObservableBoolean getFilterRemoveConstantDefinitions() {
		return filterRemoveConstantDefinitions;
	}

	/**
	 * @return Hash of the active filter configuration for decompilation cache invalidation.
	 */
	public int computeFilterHash() {
		int h = Boolean.hashCode(filterDebug.getValue());
		h = 31 * h + Boolean.hashCode(filterHollow.getValue());
		h = 31 * h + Boolean.hashCode(filterIllegalAnnotations.getValue());
		h = 31 * h + Boolean.hashCode(filterDuplicateAnnotations.getValue());
		h = 31 * h + Boolean.hashCode(filterLongAnnotations.getValue());
		h = 31 * h + filterLongAnnotationsLength.getValue();
		h = 31 * h + Boolean.hashCode(filterLongExceptions.getValue());
		h = 31 * h + filterLongExceptionsLength.getValue();
		h = 31 * h + Boolean.hashCode(filterSignatures.getValue());
		h = 31 * h + Boolean.hashCode(filterSynthetics.getValue());
		h = 31 * h + Boolean.hashCode(filterNonAsciiNames.getValue());
		h = 31 * h + Boolean.hashCode(filterRepairTryCatch.getValue());
		h = 31 * h + Boolean.hashCode(filterRepairDebugRanges.getValue());
		h = 31 * h + Boolean.hashCode(filterRemoveConstantDefinitions.getValue());
		return h;
	}
}
