package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.cdi.EagerInitialization;
import software.coley.recaf.cdi.InitializationStage;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.analysis.plugin.semantic.NameHeuristics;
import software.coley.recaf.services.analysis.plugin.semantic.NamingFacts;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElement;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElementKind;
import software.coley.recaf.services.analysis.plugin.semantic.PluginIndex;
import software.coley.recaf.services.cell.context.ClassContextMenuAdapter;
import software.coley.recaf.services.cell.context.ContextMenuProviderService;
import software.coley.recaf.services.cell.context.ContextSource;
import software.coley.recaf.services.cell.context.FieldContextMenuAdapter;
import software.coley.recaf.services.cell.context.MethodContextMenuAdapter;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static software.coley.recaf.util.Menus.actionLiteral;

/**
 * Adds a plugin submenu to the context menus of classes, methods and fields, including those opened from code in the
 * editor. It lists what the plugin structure links to the item: the handlers of an event, where a listener or
 * command executor is registered, where a task is scheduled, other uses of the same config path or permission, and
 * so on. Each entry jumps to the code.
 */
@ApplicationScoped
@EagerInitialization(InitializationStage.AFTER_UI_INIT)
public class PluginContextMenuAdapter implements ClassContextMenuAdapter, MethodContextMenuAdapter, FieldContextMenuAdapter {
	private static final int MAX_ENTRIES = 50;
	private static final int MAX_DESCRIBED = 5;
	private static final Set<PluginElementKind> KEY_KINDS = Set.of(PluginElementKind.CONFIG_KEY,
			PluginElementKind.PERMISSION, PluginElementKind.CHANNEL, PluginElementKind.NAMESPACED_KEY,
			PluginElementKind.METADATA_KEY, PluginElementKind.PLUGIN_HOOK);
	private final PluginNavigation navigation;
	private final WorkspaceManager workspaceManager;

	@Inject
	public PluginContextMenuAdapter(@Nonnull ContextMenuProviderService menuService,
	                                @Nonnull PluginNavigation navigation,
	                                @Nonnull WorkspaceManager workspaceManager) {
		this.navigation = navigation;
		this.workspaceManager = workspaceManager;
		menuService.addClassContextMenuAdapter(this);
		menuService.addMethodContextMenuAdapter(this);
		menuService.addFieldContextMenuAdapter(this);
	}

	@Override
	public void adaptJvmClassMenu(@Nonnull ContextMenu menu, @Nonnull ContextSource source, @Nonnull Workspace workspace,
	                              @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                              @Nonnull JvmClassInfo info) {
		PluginIndex index = indexFor(menu, workspace);
		if (index == null)
			return;
		String name = info.getName();
		List<MenuItem> items = new ArrayList<>();

		// The class is an event: who handles it, and who fires it.
		addLocations(items, Lang.get("mcplugin.menu.handlers"), CarbonIcons.EVENT, index.handlersOf(name), false);
		addLocations(items, Lang.get("mcplugin.menu.fired-in"), CarbonIcons.FIRE, index.callsOf(name), false);

		// The class has a role: where that role is wired up.
		List<PluginElement> handlersHere = new ArrayList<>();
		for (PluginElement element : index.inClass(name)) {
			if (!element.location().className().equals(name))
				continue;
			switch (element.kind()) {
				case EVENT_HANDLER -> handlersHere.add(element);
				case LISTENER, COMMAND_EXECUTOR, TAB_COMPLETER, TASK -> addRoleItems(items, index, element);
				default -> {
					// Shown on the members instead
				}
			}
		}
		addLocations(items, Lang.get("mcplugin.menu.handlers-in-class"), CarbonIcons.HEADPHONES, handlersHere, false);
		List<PluginElement> roles = index.at(CodeLocation.ofClass(name));
		finish(menu, roles, items, NameHeuristics.simpleName(name));
	}

	@Override
	public void adaptMethodContextMenu(@Nonnull ContextMenu menu, @Nonnull ContextSource source,
	                                   @Nonnull Workspace workspace, @Nonnull WorkspaceResource resource,
	                                   @Nonnull ClassBundle<? extends ClassInfo> bundle,
	                                   @Nonnull ClassInfo declaringClass, @Nonnull MethodMember method) {
		PluginIndex index = indexFor(menu, workspace);
		if (index == null)
			return;
		CodeLocation here = CodeLocation.ofMember(declaringClass.getName(), method.getName(), method.getDescriptor());
		List<MenuItem> items = new ArrayList<>();
		String query = null;

		// Elements are sorted by kind, so the query comes from the most significant one, such as an event handler.
		for (PluginElement element : index.at(here)) {
			switch (element.kind()) {
				case EVENT_HANDLER -> {
					CodeLocation event = element.related();
					if (event == null)
						break;
					String eventName = NameHeuristics.simpleName(event.className());
					if (navigation.resolve(event) != null)
						items.add(actionLiteral(String.format(Lang.get("mcplugin.menu.goto-event"), eventName),
								CarbonIcons.EVENTS, () -> navigation.navigate(event)));
					List<PluginElement> others = index.handlersOf(event.className()).stream()
							.filter(e -> !e.location().equals(here)).toList();
					addLocations(items, String.format(Lang.get("mcplugin.menu.other-handlers"), eventName),
							CarbonIcons.EVENT, others, false);
					addLocations(items, Lang.get("mcplugin.menu.fired-in"), CarbonIcons.FIRE, index.callsOf(event.className()), false);
					if (query == null)
						query = eventName;
				}
				case EVENT_CALL -> {
					if (element.related() != null) {
						addLocations(items, String.format(Lang.get("mcplugin.menu.other-handlers"), element.key()),
								CarbonIcons.EVENT, index.handlersOf(element.related().className()), false);
						if (query == null)
							query = element.key();
					}
				}
				case COMMAND -> {
					List<PluginElement> handlers = new ArrayList<>(index.withKey(PluginElementKind.COMMAND_EXECUTOR, element.key()));
					handlers.addAll(index.withKey(PluginElementKind.TAB_COMPLETER, element.key()));
					addLocations(items, String.format(Lang.get("mcplugin.menu.command"), '/' + element.key()),
							CarbonIcons.TERMINAL, handlers, false);
					if (query == null)
						query = element.key();
				}
				case COMMAND_EXECUTOR, TAB_COMPLETER, TASK, LISTENER -> {
					addRoleItems(items, index, element);
					if (query == null && !element.key().isEmpty())
						query = element.key();
				}
				default -> {
					if (KEY_KINDS.contains(element.kind())) {
						addKeyUses(items, index, element.kind(), element.key(), here);
						if (query == null)
							query = element.key();
					}
				}
			}
		}

		// The method is where other things are registered or scheduled.
		List<PluginElement> registered = index.relatedTo(here).stream()
				.filter(e -> e.kind() == PluginElementKind.LISTENER || e.kind() == PluginElementKind.COMMAND_EXECUTOR ||
						e.kind() == PluginElementKind.TAB_COMPLETER || e.kind() == PluginElementKind.TASK)
				.toList();
		addLocations(items, Lang.get("mcplugin.menu.registers"), CarbonIcons.JUMP_LINK, registered, false);
		finish(menu, index.at(here), items, query != null ? query : here.display());
	}

	@Override
	public void adaptFieldContextMenu(@Nonnull ContextMenu menu, @Nonnull ContextSource source,
	                                  @Nonnull Workspace workspace, @Nonnull WorkspaceResource resource,
	                                  @Nonnull ClassBundle<? extends ClassInfo> bundle,
	                                  @Nonnull ClassInfo declaringClass, @Nonnull FieldMember field) {
		PluginIndex index = indexFor(menu, workspace);
		if (index == null)
			return;
		CodeLocation here = CodeLocation.ofMember(declaringClass.getName(), field.getName(), field.getDescriptor());
		List<MenuItem> items = new ArrayList<>();
		String query = null;

		// Keys the field holds, either as a constant or as the value read from that key.
		Set<String> seen = new LinkedHashSet<>();
		NamingFacts facts = index.namingFacts();
		for (NamingFacts.ConstantUse use : facts.constantUses())
			if (use.field().equals(here) && seen.add(use.kind() + use.value())) {
				addKeyUses(items, index, use.kind(), use.value(), null);
				query = use.value();
			}
		for (NamingFacts.ValueFlow flow : facts.valueFlows())
			if (here.equals(flow.field()) && seen.add(flow.source() + flow.key())) {
				addKeyUses(items, index, flow.source(), flow.key(), null);
				query = flow.key();
			}
		if (query != null)
			finish(menu, List.of(), items, query);
	}

	/**
	 * Adds where a listener, executor, completer or task is registered or scheduled, or for registration sites,
	 * what is registered.
	 */
	private void addRoleItems(@Nonnull List<MenuItem> items, @Nonnull PluginIndex index, @Nonnull PluginElement element) {
		CodeLocation related = element.related();
		String what = PluginElementDisplay.kindName(element.kind());
		if (!element.key().isEmpty() && element.kind() != PluginElementKind.TASK && element.kind() != PluginElementKind.LISTENER)
			what += " " + PluginElementDisplay.keyText(element);
		if (related != null) {
			String format = element.kind() == PluginElementKind.TASK ?
					Lang.get("mcplugin.menu.scheduled-in") : Lang.get("mcplugin.menu.registered-in");
			items.add(actionLiteral(what + ": " + String.format(format, related.display()),
					PluginElementDisplay.icon(element.kind()), () -> navigation.navigate(related)));
		} else if (element.kind() == PluginElementKind.COMMAND_EXECUTOR || element.kind() == PluginElementKind.TAB_COMPLETER) {
			// Default executors and command classes have no registration site, but may share a command name.
			addLocations(items, String.format(Lang.get("mcplugin.menu.command"), PluginElementDisplay.keyText(element)),
					CarbonIcons.TERMINAL, index.withKey(PluginElementKind.COMMAND, element.key()), false);
		}
	}

	private void addKeyUses(@Nonnull List<MenuItem> items, @Nonnull PluginIndex index, @Nonnull PluginElementKind kind,
	                        @Nonnull String key, @Nullable CodeLocation exclude) {
		List<PluginElement> uses = index.withKey(kind, key).stream()
				.filter(e -> !e.location().equals(exclude))
				.toList();
		String title = String.format(Lang.get("mcplugin.menu.uses"), PluginElementDisplay.kindName(kind), key);
		if (uses.isEmpty()) {
			MenuItem none = new MenuItem(title + " (" + Lang.get("mcplugin.menu.only-here") + ")",
					new FontIconView(PluginElementDisplay.icon(kind)));
			none.setDisable(true);
			items.add(none);
		} else {
			addLocations(items, title, PluginElementDisplay.icon(kind), uses, true);
		}
	}

	/**
	 * Adds a submenu listing the locations of the elements, or nothing if there are none.
	 */
	private void addLocations(@Nonnull List<MenuItem> items, @Nonnull String title, @Nonnull Ikon icon,
	                          @Nonnull List<PluginElement> elements, boolean withDetail) {
		if (elements.isEmpty())
			return;
		Menu submenu = new Menu(title + " (" + elements.size() + ")", new FontIconView(icon));
		Set<CodeLocation> seen = new LinkedHashSet<>();
		for (PluginElement element : elements) {
			if (!seen.add(element.location()))
				continue;
			if (seen.size() > MAX_ENTRIES) {
				MenuItem more = new MenuItem(Lang.get("mcplugin.menu.more"));
				more.setDisable(true);
				submenu.getItems().add(more);
				break;
			}
			String text = element.location().display();
			String detail = withDetail ? element.detail() : element.kind() == PluginElementKind.EVENT_HANDLER ?
					element.detail() : null;
			if (element.kind() == PluginElementKind.TAB_COMPLETER)
				text = PluginElementDisplay.kindName(element.kind()) + ": " + text;
			if (detail != null)
				text += "  (" + detail + ")";
			CodeLocation target = element.location();
			submenu.getItems().add(actionLiteral(text, PluginElementDisplay.icon(element.kind()), () -> navigation.navigate(target)));
		}
		items.add(submenu);
	}

	/**
	 * Adds the plugin submenu, if there is anything to show.
	 *
	 * @param menu
	 * 		Menu to add to.
	 * @param here
	 * 		Elements located at the item the menu is for, described at the top of the submenu.
	 * @param items
	 * 		Navigation items.
	 * @param navigatorQuery
	 * 		Search to use when showing the item in the navigator.
	 */
	private void finish(@Nonnull ContextMenu menu, @Nonnull List<PluginElement> here,
	                    @Nonnull List<MenuItem> items, @Nonnull String navigatorQuery) {
		if (items.isEmpty() && here.isEmpty())
			return;
		Menu plugin = new Menu(Lang.get("mcplugin.menu"), new FontIconView(CarbonIcons.CATEGORIES));

		// Say what the item is first, such as 'Event handler: PlayerJoinEvent (HIGH)'.
		Set<String> described = new LinkedHashSet<>();
		for (PluginElement element : here) {
			String text = PluginElementDisplay.kindName(element.kind()) + ": " + PluginElementDisplay.keyText(element);
			if (element.detail() != null)
				text += "  (" + element.detail() + ")";
			if (described.size() < MAX_DESCRIBED && described.add(text)) {
				MenuItem header = new MenuItem(text, new FontIconView(PluginElementDisplay.icon(element.kind())));
				header.setDisable(true);
				plugin.getItems().add(header);
			}
		}
		if (!described.isEmpty() && !items.isEmpty())
			plugin.getItems().add(new SeparatorMenuItem());
		plugin.getItems().addAll(items);
		plugin.getItems().add(new SeparatorMenuItem());
		plugin.getItems().add(actionLiteral(Lang.get("mcplugin.menu.show-in-navigator"), CarbonIcons.SEARCH,
				() -> navigation.openNavigator(navigatorQuery)));
		menu.getItems().add(plugin);
	}

	/**
	 * @return Index of the workspace, or {@code null} if there is nothing to show. When a plugin is still being
	 * indexed, a placeholder is added to the menu instead of waiting.
	 */
	@Nullable
	private PluginIndex indexFor(@Nonnull ContextMenu menu, @Nonnull Workspace workspace) {
		if (workspace != workspaceManager.getCurrent())
			return null;
		PluginIndex index = navigation.indexForImmediateUse();
		if (index == null && navigation.isPluginWorkspace()) {
			MenuItem indexing = new MenuItem(Lang.get("mcplugin.menu.indexing"), new FontIconView(CarbonIcons.CATEGORIES));
			indexing.setDisable(true);
			menu.getItems().add(indexing);
		}
		return index == null || index.isEmpty() ? null : index;
	}
}
