package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.cdi.EagerInitialization;
import software.coley.recaf.cdi.InitializationStage;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.analysis.plugin.view.PluginViewService;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.navigation.NavigationManager;
import software.coley.recaf.ui.pane.editing.AbstractDecompilePane;
import software.coley.recaf.ui.pane.editing.ClassPane;
import software.coley.recaf.util.FxThreadUtil;

import java.util.Set;

/**
 * Decompiles open classes again when the plugin view changes what they should show. The service has already dropped
 * the cached results, so only classes that are open need work.
 */
@ApplicationScoped
@EagerInitialization(InitializationStage.AFTER_UI_INIT)
public class PluginViewRefresher {
	private final NavigationManager navigationManager;

	@Inject
	public PluginViewRefresher(@Nonnull PluginViewService viewService, @Nonnull NavigationManager navigationManager) {
		this.navigationManager = navigationManager;
		viewService.addRefreshListener(refresh -> FxThreadUtil.run(() -> redecompile(refresh.classNames())));
	}

	private void redecompile(Set<String> classNames) {
		for (Navigable navigable : navigationManager.getNavigableChildren()) {
			if (!(navigable instanceof ClassPane classPane) || !(classPane.getPath() instanceof ClassPathNode path))
				continue;
			if (classNames != null && !classNames.contains(path.getValue().getName()))
				continue;
			for (Navigable child : classPane.getNavigableChildren())
				if (child instanceof AbstractDecompilePane decompilePane)
					decompilePane.decompile();
		}
	}
}
