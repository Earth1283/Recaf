package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NamingClue;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElement;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElementKind;
import software.coley.recaf.util.Lang;

import java.util.Locale;

/**
 * Shared text and icons for plugin structure in the UI.
 */
public final class PluginElementDisplay {
	private PluginElementDisplay() {}

	/**
	 * @param kind
	 * 		Kind of element.
	 *
	 * @return Icon representing the kind.
	 */
	@Nonnull
	public static Ikon icon(@Nonnull PluginElementKind kind) {
		return switch (kind) {
			case MAIN_CLASS, BOOTSTRAPPER, LOADER -> CarbonIcons.LAUNCH;
			case LIFECYCLE -> CarbonIcons.PLAY;
			case LISTENER -> CarbonIcons.HEADPHONES;
			case EVENT_HANDLER -> CarbonIcons.EVENT;
			case EVENT_CALL -> CarbonIcons.FIRE;
			case CUSTOM_EVENT -> CarbonIcons.EVENTS;
			case COMMAND, COMMAND_EXECUTOR -> CarbonIcons.TERMINAL;
			case TAB_COMPLETER -> CarbonIcons.LIST_BULLETED;
			case TASK -> CarbonIcons.TIMER;
			case CONFIG_KEY -> CarbonIcons.SETTINGS_ADJUST;
			case PERMISSION -> CarbonIcons.LOCKED;
			case CHANNEL, MESSAGE_LISTENER -> CarbonIcons.CONNECT;
			case NAMESPACED_KEY -> CarbonIcons.TAG;
			case METADATA_KEY -> CarbonIcons.BOOKMARK;
			case PLUGIN_HOOK -> CarbonIcons.PLUG;
			case SERIALIZABLE -> CarbonIcons.DATA_STRUCTURED;
			case INVENTORY_HOLDER -> CarbonIcons.APPLICATION;
			case PLACEHOLDER_EXPANSION -> CarbonIcons.STRING_TEXT;
		};
	}

	/**
	 * @param kind
	 * 		Kind of element.
	 *
	 * @return Translated name of the kind.
	 */
	@Nonnull
	public static String kindName(@Nonnull PluginElementKind kind) {
		return Lang.get("mcplugin.kind." + kind.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * @param clue
	 * 		Kind of naming evidence.
	 *
	 * @return Translated name of the clue.
	 */
	@Nonnull
	public static String clueName(@Nonnull NamingClue clue) {
		return Lang.get("mcplugin.clue." + clue.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * @param target
	 * 		Rename target.
	 *
	 * @return Translated name of the kind of target.
	 */
	@Nonnull
	public static String targetKindName(@Nonnull NameTarget target) {
		return Lang.get(switch (target) {
			case NameTarget.ClassTarget ignored -> "mcplugin.target.class";
			case NameTarget.FieldTarget ignored -> "mcplugin.target.field";
			case NameTarget.MethodTarget ignored -> "mcplugin.target.method";
			case NameTarget.VariableTarget ignored -> "mcplugin.target.variable";
		});
	}

	/**
	 * @param element
	 * 		Some element.
	 *
	 * @return Key of the element for display. Commands are prefixed with a slash, and unknown keys are described.
	 */
	@Nonnull
	public static String keyText(@Nonnull PluginElement element) {
		String key = element.key();
		if (key.isEmpty())
			return Lang.get("mcplugin.key.unknown");
		return switch (element.kind()) {
			case COMMAND, COMMAND_EXECUTOR, TAB_COMPLETER -> '/' + key;
			default -> key;
		};
	}

	/**
	 * @param element
	 * 		Some element.
	 *
	 * @return One line summary, such as {@code Event handler: PlayerJoinEvent → Listener.onJoin()}.
	 */
	@Nonnull
	public static String summary(@Nonnull PluginElement element) {
		StringBuilder sb = new StringBuilder(kindName(element.kind())).append(": ").append(keyText(element))
				.append("  →  ").append(element.location().display());
		if (element.detail() != null)
			sb.append("  (").append(element.detail()).append(')');
		return sb.toString();
	}
}
