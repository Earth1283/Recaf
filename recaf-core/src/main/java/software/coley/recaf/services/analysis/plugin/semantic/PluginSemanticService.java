package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginManifest;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.ClassTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.FieldTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.MethodTarget;
import software.coley.recaf.services.analysis.plugin.semantic.NameTarget.VariableTarget;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.MappingsAdapter;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.WorkspaceModificationListener;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.ResourceAndroidClassListener;
import software.coley.recaf.workspace.model.resource.ResourceJvmClassListener;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Recovers the meaning of an obfuscated Minecraft plugin from its use of the server API.
 * <ul>
 *     <li>{@link #getIndex(Workspace)} indexes the plugin's structure: entry points, commands and their executors,
 *     listeners and the events they handle, scheduled tasks, and the config paths, permissions and other keys it
 *     uses. Each is linked to where it is in the code, for navigation and searching.</li>
 *     <li>{@link #suggestNames(Workspace)} suggests meaningful names for classes, fields, methods and variables from
 *     that structure, each with the evidence it is based on.</li>
 *     <li>{@link #createMappings(Workspace, Collection)} turns chosen suggestions into mappings for
 *     {@link software.coley.recaf.services.mapping.MappingApplierService}.</li>
 * </ul>
 * The index of the current workspace is cached, and dropped when the plugin's classes or the workspace's libraries
 * change.
 */
@ApplicationScoped
public class PluginSemanticService implements Service {
	public static final String SERVICE_ID = "minecraft-plugin-semantics";
	private static final Logger logger = Logging.get(PluginSemanticService.class);
	private static final long REBUILD_DELAY_MS = 500;
	private static final int MAX_BUILD_ATTEMPTS = 3;
	private final List<Consumer<Workspace>> indexListeners = new CopyOnWriteArrayList<>();
	private final ExecutorService indexPool = ThreadPoolFactory.newSingleThreadExecutor("plugin-index");
	private final AtomicLong changeCount = new AtomicLong();
	private final AtomicLong rebuildGeneration = new AtomicLong();
	private final PluginSemanticConfig config;
	private final WorkspaceManager workspaceManager;
	private final MinecraftPluginAnalysisService analysisService;
	private final InheritanceGraphService graphService;
	// Guarded by 'this'. The cached index is volatile so it can be read without waiting on a build.
	private Workspace trackedWorkspace;
	private ChangeListener changeListener;
	private CompletableFuture<PluginIndex> pendingBuild;
	private volatile PluginIndex cachedIndex;

	@Inject
	public PluginSemanticService(@Nonnull PluginSemanticConfig config,
	                             @Nonnull WorkspaceManager workspaceManager,
	                             @Nonnull MinecraftPluginAnalysisService analysisService,
	                             @Nonnull InheritanceGraphService graphService) {
		this.config = config;
		this.workspaceManager = workspaceManager;
		this.analysisService = analysisService;
		this.graphService = graphService;

		// Index plugins as soon as they are opened, so the UI rarely has to wait for one.
		workspaceManager.addWorkspaceOpenListener(workspace -> {
			if (hasManifest(workspace))
				getIndexAsync(workspace);
		});
		workspaceManager.addWorkspaceCloseListener(workspace -> {
			synchronized (this) {
				if (workspace == trackedWorkspace)
					untrack();
			}
		});
	}

	/**
	 * @param workspace
	 * 		Workspace containing a plugin as its primary resource.
	 *
	 * @return Index of the plugin. Cached for the current workspace until its classes change. Blocks while the index
	 * is built, so do not call this on the UI thread. See {@link #getIndexAsync(Workspace)}.
	 */
	@Nonnull
	public PluginIndex getIndex(@Nonnull Workspace workspace) {
		return getIndexAsync(workspace).join();
	}

	/**
	 * @param workspace
	 * 		Workspace containing a plugin as its primary resource.
	 *
	 * @return Future of the index of the plugin. Already complete when the index is cached. Indexes are built on a
	 * background thread, and requests made while one is being built share it.
	 */
	@Nonnull
	public CompletableFuture<PluginIndex> getIndexAsync(@Nonnull Workspace workspace) {
		if (workspace != workspaceManager.getCurrent())
			return CompletableFuture.supplyAsync(() -> buildIndex(workspace), indexPool);
		synchronized (this) {
			track(workspace);
			PluginIndex cached = cachedIndex;
			if (cached != null)
				return CompletableFuture.completedFuture(cached);
			if (pendingBuild != null)
				return pendingBuild;
			CompletableFuture<PluginIndex> build = CompletableFuture.supplyAsync(() -> buildStable(workspace), indexPool);
			pendingBuild = build;
			build.whenComplete((index, error) -> {
				synchronized (this) {
					if (pendingBuild == build)
						pendingBuild = null;
				}
				if (error != null)
					logger.error("Failed to index plugin structure", error);
			});
			return build;
		}
	}

	/**
	 * Builds an index that matches the classes as they are once the build finishes. Classes changing during the build,
	 * such as while mappings are applied, make it try again. An index is only cached when nothing changed while it
	 * was built.
	 */
	@Nonnull
	private PluginIndex buildStable(@Nonnull Workspace workspace) {
		PluginIndex index;
		long before;
		int attempts = 0;
		do {
			before = changeCount.get();
			index = buildIndex(workspace);
		} while (changeCount.get() != before && ++attempts < MAX_BUILD_ATTEMPTS);

		boolean cached = false;
		synchronized (this) {
			if (workspace == trackedWorkspace && changeCount.get() == before) {
				cachedIndex = index;
				cached = true;
			}
		}
		if (cached)
			notifyListeners(workspace);
		else
			scheduleRebuild(workspace);
		return index;
	}

	/**
	 * @param workspace
	 * 		Some workspace.
	 *
	 * @return The cached index of the workspace, or {@code null} if there is none. Never builds an index or waits on
	 * a build, so it is safe to call on the UI thread.
	 */
	@Nullable
	public PluginIndex getCachedIndex(@Nonnull Workspace workspace) {
		// Read once, a build on another thread may replace it at any time.
		PluginIndex index = cachedIndex;
		synchronized (this) {
			return workspace == trackedWorkspace ? index : null;
		}
	}

	/**
	 * @param workspace
	 * 		Some workspace.
	 *
	 * @return {@code true} when the primary resource has a plugin manifest.
	 */
	public boolean hasManifest(@Nonnull Workspace workspace) {
		return !analysisService.findManifests(workspace, workspace.getPrimaryResource()).isEmpty();
	}

	/**
	 * @param workspace
	 * 		Workspace containing a plugin as its primary resource.
	 *
	 * @return New index of the plugin. Never cached.
	 */
	@Nonnull
	public PluginIndex buildIndex(@Nonnull Workspace workspace) {
		long start = System.currentTimeMillis();
		List<MinecraftPluginManifest> manifests = analysisService.findManifests(workspace, workspace.getPrimaryResource())
				.stream().map(MinecraftPluginAnalysisService.LocatedManifest::manifest).toList();
		PluginIndex index = PluginIndexer.index(workspace, manifests, config.getSkipShadedLibraries().getValue());
		logger.debug("Indexed plugin structure in {}ms: {} elements", System.currentTimeMillis() - start, index.elements().size());
		return index;
	}

	/**
	 * @param workspace
	 * 		Workspace containing a plugin as its primary resource.
	 *
	 * @return Suggested names for the plugin's classes, fields, methods and variables.
	 */
	@Nonnull
	public List<NameSuggestion> suggestNames(@Nonnull Workspace workspace) {
		InheritanceGraph graph = graphService.getOrCreateInheritanceGraph(workspace);
		return SemanticNameSuggester.suggest(workspace, getIndex(workspace), graph,
				config.getSkipShadedLibraries().getValue());
	}

	/**
	 * Creates mappings that apply the given suggestions. Suggestions that cannot be applied safely are left out and
	 * reported as problems: their target no longer exists, the name is not a valid identifier, or the name collides
	 * with another name. Methods are renamed across their whole hierarchy, unless a library declares them.
	 *
	 * @param workspace
	 * 		Workspace containing the plugin as its primary resource.
	 * @param suggestions
	 * 		Suggestions to apply, possibly with names changed by the user.
	 *
	 * @return Mappings for the suggestions, and any problems.
	 */
	@Nonnull
	public MappingPlan createMappings(@Nonnull Workspace workspace, @Nonnull Collection<NameSuggestion> suggestions) {
		InheritanceGraph graph = graphService.getOrCreateInheritanceGraph(workspace);
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
		MappingsAdapter mappings = new MappingsAdapter(true, true);
		mappings.enableHierarchyLookup(graph);
		mappings.enableClassLookup(workspace);
		List<String> problems = new ArrayList<>();
		int count = 0;

		// Classes, outer classes first so inner classes can be placed inside the outer's new name.
		List<NameSuggestion> classSuggestions = suggestions.stream()
				.filter(s -> s.target() instanceof ClassTarget)
				.sorted(Comparator.comparing(s -> ((ClassTarget) s.target()).className()))
				.toList();
		Map<String, String> classRenames = new HashMap<>();
		Set<String> newClassNames = new HashSet<>();
		for (NameSuggestion suggestion : classSuggestions) {
			String className = ((ClassTarget) suggestion.target()).className();
			JvmClassInfo info = bundle.get(className);
			String problem = checkName(suggestion);
			if (info == null)
				problem = "class no longer exists";
			if (problem == null) {
				String newName = SemanticNameSuggester.classPrefix(info, classRenames) + suggestion.suggestedName();
				boolean renamedAway = classSuggestions.stream()
						.anyMatch(s -> ((ClassTarget) s.target()).className().equals(newName));
				if (!newClassNames.add(newName) || (!newName.equals(className) && workspace.findClass(newName) != null && !renamedAway))
					problem = "a class named " + newName.replace('/', '.') + " already exists";
				else
					classRenames.put(className, newName);
			}
			if (problem != null)
				problems.add(suggestion.target().describe() + ": " + problem);
		}
		for (Map.Entry<String, String> rename : classRenames.entrySet()) {
			mappings.addClass(rename.getKey(), rename.getValue());
			count++;
		}

		// Members and variables.
		Map<String, Set<String>> newFieldNames = new HashMap<>();
		for (NameSuggestion suggestion : suggestions) {
			String problem = checkName(suggestion);
			switch (suggestion.target()) {
				case ClassTarget ignored -> {
					continue;
				}
				case FieldTarget(String owner, String name, String desc) -> {
					JvmClassInfo info = bundle.get(owner);
					FieldMember field = info == null ? null : info.getDeclaredField(name, desc);
					if (field == null) {
						problem = "field no longer exists";
					} else if (problem == null) {
						Set<String> taken = newFieldNames.computeIfAbsent(owner, k -> new HashSet<>());
						boolean clash = !taken.add(suggestion.suggestedName()) || info.getFields().stream().anyMatch(other ->
								other != field && other.getName().equals(suggestion.suggestedName()) &&
										suggestions.stream().noneMatch(s -> s.target().equals(new FieldTarget(owner, other.getName(), other.getDescriptor()))));
						if (clash)
							problem = "another field is already named " + suggestion.suggestedName();
					}
					if (problem == null) {
						mappings.addField(owner, name, desc, suggestion.suggestedName());
						count++;
					}
				}
				case MethodTarget(String owner, String name, String desc) -> {
					JvmClassInfo info = bundle.get(owner);
					MethodMember method = info == null ? null : info.getDeclaredMethod(name, desc);
					if (method == null)
						problem = "method no longer exists";
					if (problem == null)
						problem = addMethodMapping(mappings, graph, method, owner, suggestion.suggestedName());
					if (problem == null)
						count++;
				}
				case VariableTarget(String owner, String methodName, String methodDesc, String name, String desc, int index) -> {
					JvmClassInfo info = bundle.get(owner);
					MethodMember method = info == null ? null : info.getDeclaredMethod(methodName, methodDesc);
					if (method == null || method.getLocalVariables().stream()
							.noneMatch(v -> v.getIndex() == index && v.getName().equals(name)))
						problem = "variable no longer exists";
					if (problem == null) {
						mappings.addVariable(owner, methodName, methodDesc, name, desc, index, suggestion.suggestedName());
						count++;
					}
				}
			}
			if (problem != null)
				problems.add(suggestion.target().describe() + ": " + problem);
		}
		return new MappingPlan(mappings, count, problems);
	}

	@Nullable
	private static String addMethodMapping(@Nonnull MappingsAdapter mappings, @Nonnull InheritanceGraph graph,
	                                       @Nonnull MethodMember method, @Nonnull String owner, @Nonnull String newName) {
		String name = method.getName();
		String desc = method.getDescriptor();
		if (method.hasPrivateModifier() || method.hasStaticModifier()) {
			mappings.addMethod(owner, name, desc, newName);
			return null;
		}

		// Overrides must keep matching, so the whole family is renamed together. A library declaring the method
		// means the name is fixed by code we cannot change.
		InheritanceVertex vertex = graph.getVertex(owner);
		if (vertex == null) {
			mappings.addMethod(owner, name, desc, newName);
			return null;
		}
		List<InheritanceVertex> declaring = new ArrayList<>();
		for (InheritanceVertex member : graph.getVertexFamily(owner, false)) {
			if (!member.hasMethod(name, desc))
				continue;
			if (member.isLibraryVertex())
				return "overrides a method of library class " + member.getName().replace('/', '.');
			if (member.hasMethod(newName, desc) && !member.getName().equals(owner))
				return "would clash with " + member.getName().replace('/', '.') + '.' + newName;
			declaring.add(member);
		}
		for (InheritanceVertex member : declaring)
			mappings.addMethod(member.getName(), name, desc, newName);
		return null;
	}

	@Nullable
	private static String checkName(@Nonnull NameSuggestion suggestion) {
		if (!NameHeuristics.isValidIdentifier(suggestion.suggestedName()))
			return "'" + suggestion.suggestedName() + "' is not a valid name";
		return null;
	}

	/**
	 * @param listener
	 * 		Listener called with the workspace when the index of the current workspace becomes outdated, and when a new
	 * 		index is ready. Called on whichever thread caused the change.
	 */
	public void addIndexListener(@Nonnull Consumer<Workspace> listener) {
		indexListeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removeIndexListener(@Nonnull Consumer<Workspace> listener) {
		indexListeners.remove(listener);
	}

	private void onChange(@Nonnull Workspace workspace) {
		changeCount.incrementAndGet();

		// Listeners are only told once per outdated index, not for every class in a large mapping operation.
		boolean wasCached;
		synchronized (this) {
			if (workspace != trackedWorkspace)
				return;
			wasCached = cachedIndex != null;
			cachedIndex = null;
		}
		if (wasCached)
			notifyListeners(workspace);
		scheduleRebuild(workspace);
	}

	/**
	 * Rebuilds the index once changes have stopped for a moment, so it is ready when next needed.
	 */
	private void scheduleRebuild(@Nonnull Workspace workspace) {
		long generation = rebuildGeneration.incrementAndGet();
		Executor delayed = CompletableFuture.delayedExecutor(REBUILD_DELAY_MS, TimeUnit.MILLISECONDS, indexPool);
		CompletableFuture.runAsync(() -> {
			if (generation == rebuildGeneration.get() && workspace == workspaceManager.getCurrent() && hasManifest(workspace))
				getIndexAsync(workspace);
		}, delayed);
	}

	private void notifyListeners(@Nonnull Workspace workspace) {
		for (Consumer<Workspace> listener : indexListeners) {
			try {
				listener.accept(workspace);
			} catch (Throwable t) {
				logger.error("Plugin index listener failed", t);
			}
		}
	}

	/**
	 * Starts watching the workspace for changes, if it is not the one already watched. Must hold the lock.
	 */
	private void track(@Nonnull Workspace workspace) {
		if (workspace == trackedWorkspace)
			return;
		untrack();
		changeListener = new ChangeListener(workspace);
		trackedWorkspace = workspace;
	}

	/**
	 * Stops watching the current workspace and drops its index. Must hold the lock.
	 */
	private void untrack() {
		if (changeListener != null)
			changeListener.uninstall();
		changeListener = null;
		trackedWorkspace = null;
		cachedIndex = null;
		pendingBuild = null;
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public PluginSemanticConfig getServiceConfig() {
		return config;
	}

	/**
	 * Result of {@link #createMappings(Workspace, Collection)}.
	 *
	 * @param mappings
	 * 		Mappings to apply.
	 * @param count
	 * 		Number of suggestions included in the mappings.
	 * @param problems
	 * 		Readable descriptions of suggestions that were left out, and why.
	 */
	public record MappingPlan(@Nonnull Mappings mappings, int count, @Nonnull List<String> problems) {}

	/**
	 * Drops the cached index when the plugin's classes or the workspace's libraries change.
	 */
	private class ChangeListener implements ResourceJvmClassListener, ResourceAndroidClassListener,
			WorkspaceModificationListener {
		private final Workspace workspace;

		private ChangeListener(@Nonnull Workspace workspace) {
			this.workspace = workspace;
			workspace.getPrimaryResource().addResourceJvmClassListener(this);
			workspace.getPrimaryResource().addResourceAndroidClassListener(this);
			workspace.addWorkspaceModificationListener(this);
		}

		private void uninstall() {
			workspace.getPrimaryResource().removeResourceJvmClassListener(this);
			workspace.getPrimaryResource().removeResourceAndroidClassListener(this);
			workspace.removeWorkspaceModificationListener(this);
		}

		@Override
		public void onNewClass(@Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle, @Nonnull JvmClassInfo cls) {
			onChange(workspace);
		}

		@Override
		public void onUpdateClass(@Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
		                          @Nonnull JvmClassInfo oldCls, @Nonnull JvmClassInfo newCls) {
			onChange(workspace);
		}

		@Override
		public void onRemoveClass(@Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle, @Nonnull JvmClassInfo cls) {
			onChange(workspace);
		}

		@Override
		public void onNewClass(@Nonnull WorkspaceResource resource, @Nonnull AndroidClassBundle bundle, @Nonnull AndroidClassInfo cls) {
			// Android classes are not plugins
		}

		@Override
		public void onUpdateClass(@Nonnull WorkspaceResource resource, @Nonnull AndroidClassBundle bundle,
		                          @Nonnull AndroidClassInfo oldCls, @Nonnull AndroidClassInfo newCls) {
			// Android classes are not plugins
		}

		@Override
		public void onRemoveClass(@Nonnull WorkspaceResource resource, @Nonnull AndroidClassBundle bundle, @Nonnull AndroidClassInfo cls) {
			// Android classes are not plugins
		}

		@Override
		public void onAddLibrary(@Nonnull Workspace workspace, @Nonnull WorkspaceResource library) {
			onChange(this.workspace);
		}

		@Override
		public void onRemoveLibrary(@Nonnull Workspace workspace, @Nonnull WorkspaceResource library) {
			onChange(this.workspace);
		}
	}
}
