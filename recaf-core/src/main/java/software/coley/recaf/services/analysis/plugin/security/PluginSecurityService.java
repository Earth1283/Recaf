package software.coley.recaf.services.analysis.plugin.security;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.analysis.plugin.semantic.PluginIndex;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Security triage of the plugin in the current workspace. See {@link PluginSecurityScanner}.
 */
@ApplicationScoped
public class PluginSecurityService implements Service {
	public static final String SERVICE_ID = "minecraft-plugin-security";
	private static final Logger logger = Logging.get(PluginSecurityService.class);
	private final List<Consumer<Workspace>> reportListeners = new CopyOnWriteArrayList<>();
	private final ExecutorService scanPool = ThreadPoolFactory.newSingleThreadExecutor("plugin-security");
	private final PluginSecurityConfig config;
	private final PluginSemanticService semanticService;
	// Guarded by 'this'. The plugin index is rebuilt whenever classes change, so it doubles as the cache key.
	private Workspace cachedWorkspace;
	private PluginIndex cachedIndex;
	private boolean cachedSkipLibraries;
	private PluginSecurityReport cachedReport;

	@Inject
	public PluginSecurityService(@Nonnull PluginSecurityConfig config,
	                             @Nonnull PluginSemanticService semanticService) {
		this.config = config;
		this.semanticService = semanticService;
	}

	/**
	 * @param workspace
	 * 		Workspace containing the plugin as its primary resource.
	 *
	 * @return Future of the report. Reports are cached until the workspace's classes change.
	 */
	@Nonnull
	public CompletableFuture<PluginSecurityReport> scanAsync(@Nonnull Workspace workspace) {
		return semanticService.getIndexAsync(workspace).thenApplyAsync(index -> reportFor(workspace, index), scanPool);
	}

	/**
	 * @param workspace
	 * 		Some workspace.
	 *
	 * @return The cached report of the workspace, or {@code null} if it has not been scanned since its classes last
	 * changed. Safe to call on the UI thread.
	 */
	@Nullable
	public synchronized PluginSecurityReport getCachedReport(@Nonnull Workspace workspace) {
		if (workspace != cachedWorkspace || cachedSkipLibraries != skipLibraries())
			return null;
		PluginIndex current = semanticService.getCachedIndex(workspace);
		return current != null && current == cachedIndex ? cachedReport : null;
	}

	/**
	 * @param listener
	 * 		Listener told when a scan finishes with a report that differs from the one cached before it. Called on the
	 * 		scanning thread.
	 */
	public void addReportListener(@Nonnull Consumer<Workspace> listener) {
		reportListeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removeReportListener(@Nonnull Consumer<Workspace> listener) {
		reportListeners.remove(listener);
	}

	@Nonnull
	private PluginSecurityReport reportFor(@Nonnull Workspace workspace, @Nonnull PluginIndex index) {
		boolean skipLibraries = skipLibraries();
		synchronized (this) {
			if (workspace == cachedWorkspace && index == cachedIndex && skipLibraries == cachedSkipLibraries)
				return cachedReport;
		}
		long start = System.currentTimeMillis();
		PluginSecurityReport report = PluginSecurityScanner.scan(workspace, skipLibraries);
		logger.debug("Scanned plugin for security triage in {}ms: {} findings", System.currentTimeMillis() - start,
				report.findings().size());
		synchronized (this) {
			cachedWorkspace = workspace;
			cachedIndex = index;
			cachedSkipLibraries = skipLibraries;
			cachedReport = report;
		}
		for (Consumer<Workspace> listener : reportListeners) {
			try {
				listener.accept(workspace);
			} catch (Throwable t) {
				logger.error("Security report listener failed", t);
			}
		}
		return report;
	}

	private boolean skipLibraries() {
		return config.getSkipBundledLibraries().getValue();
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public PluginSecurityConfig getServiceConfig() {
		return config;
	}
}
