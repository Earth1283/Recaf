package software.coley.recaf.services.analysis.plugin.view;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableInteger;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Config for {@link PluginViewService}.
 */
@ApplicationScoped
public class PluginViewConfig extends BasicConfigContainer implements ServiceConfig {
	private final ObservableBoolean annotateStructure = new ObservableBoolean(true);
	private final ObservableBoolean annotateSecurity = new ObservableBoolean(true);
	private final ObservableBoolean nameParameters = new ObservableBoolean(true);
	private final ObservableBoolean deobfuscatedView = new ObservableBoolean(false);
	private final ObservableInteger maxNotesPerMember = new ObservableInteger(6);
	private final ObservableInteger deobfuscationPasses = new ObservableInteger(2);

	@Inject
	public PluginViewConfig() {
		super(ConfigGroups.SERVICE_ANALYSIS, PluginViewService.SERVICE_ID + CONFIG_SUFFIX);

		addValue(new BasicConfigValue<>("annotate-structure", boolean.class, annotateStructure));
		addValue(new BasicConfigValue<>("annotate-security", boolean.class, annotateSecurity));
		addValue(new BasicConfigValue<>("name-parameters", boolean.class, nameParameters));
		addValue(new BasicConfigValue<>("deobfuscated-view", boolean.class, deobfuscatedView));
		addValue(new BasicConfigValue<>("max-notes-per-member", int.class, maxNotesPerMember));
		addValue(new BasicConfigValue<>("deobfuscation-passes", int.class, deobfuscationPasses));
	}

	/**
	 * @return {@code true} to add comments to decompiled plugin code saying what the plugin API is used for there:
	 * commands, event handlers, config paths, permissions, and so on.
	 */
	@Nonnull
	public ObservableBoolean getAnnotateStructure() {
		return annotateStructure;
	}

	/**
	 * Only uses a security report that has already been made. Decompiling never starts a scan.
	 *
	 * @return {@code true} to add comments to decompiled plugin code where the security triage found something.
	 */
	@Nonnull
	public ObservableBoolean getAnnotateSecurity() {
		return annotateSecurity;
	}

	/**
	 * @return {@code true} to give parameters meaningful names in decompiled plugin code when the class has no
	 * variable names of its own.
	 */
	@Nonnull
	public ObservableBoolean getNameParameters() {
		return nameParameters;
	}

	/**
	 * Off by default. Turning it on transforms the whole plugin once in the background, which can take a while for
	 * large plugins, and then decompiles the transformed classes. The classes in the workspace are not changed.
	 *
	 * @return {@code true} to decompile a deobfuscated copy of each class.
	 */
	@Nonnull
	public ObservableBoolean getDeobfuscatedView() {
		return deobfuscatedView;
	}

	/**
	 * @return Most comment lines to add to one class, method or field. Extra notes are summarized as a count.
	 */
	@Nonnull
	public ObservableInteger getMaxNotesPerMember() {
		return maxNotesPerMember;
	}

	/**
	 * @return Times to repeat the deobfuscation transformers when building the deobfuscated view.
	 */
	@Nonnull
	public ObservableInteger getDeobfuscationPasses() {
		return deobfuscationPasses;
	}
}
