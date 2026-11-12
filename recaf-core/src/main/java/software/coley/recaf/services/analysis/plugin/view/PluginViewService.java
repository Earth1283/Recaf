package software.coley.recaf.services.analysis.plugin.view;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.cdi.EagerInitialization;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityReport;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityService;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.services.analysis.plugin.semantic.PluginIndex;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticConfig;
import software.coley.recaf.services.analysis.plugin.semantic.PluginSemanticService;
import software.coley.recaf.services.analysis.plugin.semantic.SemanticNameSuggester;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.filter.JvmBytecodeFilter;
import software.coley.recaf.services.decompile.filter.OutputTextFilter;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.CancellableTransformationFeedback;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Makes decompiled plugin code easier to read, without changing the plugin:
 * <ul>
 *     <li>Comments above classes, methods and fields saying what the plugin API is used for there, from the
 *     {@link PluginIndex}, and where the security triage found something.</li>
 *     <li>Meaningful names for parameters of methods that have no variable table, as event handlers and command
 *     methods are called by the server with known parameters.</li>
 *     <li>Optionally, decompiling a deobfuscated copy of each class instead of the class itself.</li>
 * </ul>
 * Decompiling never waits for any of the inputs. A decompilation made before an input is ready simply lacks what it
 * would add, and the cached results of the classes it affects are dropped, and listeners told, once the input is ready.
 */
@EagerInitialization
@ApplicationScoped
public class PluginViewService implements Service {
	public static final String SERVICE_ID = "minecraft-plugin-view";
	private static final Logger logger = Logging.get(PluginViewService.class);
	private final List<Consumer<ViewRefresh>> refreshListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<Workspace>> statusListeners = new CopyOnWriteArrayList<>();
	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	private final ExecutorService deobfuscationPool = ThreadPoolFactory.newSingleThreadExecutor("plugin-view");
	private final PluginViewConfig config;
	private final PluginSemanticConfig semanticConfig;
	private final PluginSemanticService semanticService;
	private final PluginSecurityService securityService;
	private final TransformationApplierService applierService;
	private final InheritanceGraphService graphService;
	private final WorkspaceManager workspaceManager;
	private State state;

	@Inject
	public PluginViewService(@Nonnull PluginViewConfig config,
	                         @Nonnull PluginSemanticConfig semanticConfig,
	                         @Nonnull DecompilerManager decompilerManager,
	                         @Nonnull PluginSemanticService semanticService,
	                         @Nonnull PluginSecurityService securityService,
	                         @Nonnull TransformationApplierService applierService,
	                         @Nonnull InheritanceGraphService graphService,
	                         @Nonnull WorkspaceManager workspaceManager) {
		this.config = config;
		this.semanticConfig = semanticConfig;
		this.semanticService = semanticService;
		this.securityService = securityService;
		this.applierService = applierService;
		this.graphService = graphService;
		this.workspaceManager = workspaceManager;

		ViewBytecodeFilter bytecodeFilter = new ViewBytecodeFilter();
		decompilerManager.addJvmBytecodeFilterFirst(bytecodeFilter);
		decompilerManager.addNestedClassFilter(bytecodeFilter);
		decompilerManager.addOutputTextFilter(new NoteRewritingFilter());

		semanticService.addIndexListener(this::onIndexChanged);
		securityService.addReportListener(this::onReportChanged);
		workspaceManager.addWorkspaceCloseListener(this::onWorkspaceClosed);
		config.getAnnotateStructure().addChangeListener((ob, old, cur) -> onStructureToggled());
		config.getAnnotateSecurity().addChangeListener((ob, old, cur) -> onSecurityToggled());
		config.getNameParameters().addChangeListener((ob, old, cur) -> onNamesToggled());
		config.getDeobfuscatedView().addChangeListener((ob, old, cur) -> onDeobfuscationToggled(cur));
		config.getMaxNotesPerMember().addChangeListener((ob, old, cur) -> onStructureToggled());
	}

	/**
	 * @param listener
	 * 		Listener told when decompilations of some classes are out of date. The cached results are already dropped.
	 * 		Called on a background thread.
	 */
	public void addRefreshListener(@Nonnull Consumer<ViewRefresh> listener) {
		refreshListeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removeRefreshListener(@Nonnull Consumer<ViewRefresh> listener) {
		refreshListeners.remove(listener);
	}

	/**
	 * @param listener
	 * 		Listener told when the progress of building the deobfuscated view changes. Called on a background thread.
	 */
	public void addStatusListener(@Nonnull Consumer<Workspace> listener) {
		statusListeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removeStatusListener(@Nonnull Consumer<Workspace> listener) {
		statusListeners.remove(listener);
	}

	/**
	 * @param workspace
	 * 		Some workspace.
	 *
	 * @return Progress of building the deobfuscated view of the workspace.
	 */
	@Nonnull
	public synchronized DeobfuscationStatus getDeobfuscationStatus(@Nonnull Workspace workspace) {
		if (state == null || state.workspace != workspace)
			return new DeobfuscationStatus(DeobfuscationStatus.Phase.IDLE, 0);
		return new DeobfuscationStatus(state.phase, state.view == null ? 0 : state.view.size());
	}

	/**
	 * Starts building the deobfuscated view if the workspace is a plugin and it is not already built or building.
	 * Does nothing when the view is turned off.
	 *
	 * @param workspace
	 * 		Some workspace.
	 */
	public void requestDeobfuscatedView(@Nonnull Workspace workspace) {
		State current = stateFor(workspace);
		if (current != null && config.getDeobfuscatedView().getValue())
			startDeobfuscation(current);
	}

	@Nullable
	private synchronized State stateFor(@Nonnull Workspace workspace) {
		if (state != null && state.workspace == workspace)
			return state;
		if (!semanticService.isTracked(workspace))
			return null;
		cancel(state);
		state = new State(workspace);
		pending.clear();
		return state;
	}

	private void onWorkspaceClosed(@Nonnull Workspace workspace) {
		synchronized (this) {
			if (state != null && state.workspace == workspace) {
				cancel(state);
				state = null;
				pending.clear();
			}
		}
	}

	private static void cancel(@Nullable State state) {
		if (state != null && state.feedback != null)
			state.feedback.cancel();
	}

	// ==================== Invalidation ==================== //

	private void onIndexChanged(@Nonnull Workspace workspace) {
		PluginIndex index = semanticService.getCachedIndex(workspace);
		State current = stateFor(workspace);
		if (index == null || current == null)
			return;
		if (config.getAnnotateSecurity().getValue() && current.hadReport)
			securityService.scanAsync(workspace);
		if (!(config.getAnnotateStructure().getValue() || config.getNameParameters().getValue()))
			return;
		PluginIndex previous = current.indexed;
		current.indexed = index;
		Set<String> names = new HashSet<>(drainPending());
		Set<String> candidates = new HashSet<>(index.classNames());
		if (previous != null)
			candidates.addAll(previous.classNames());
		for (String name : candidates)
			if (previous == null || !previous.inClass(name).equals(index.inClass(name)))
				names.add(name);
		invalidate(workspace, names);
	}

	private void onReportChanged(@Nonnull Workspace workspace) {
		State current = stateFor(workspace);
		PluginSecurityReport report = securityService.getCachedReport(workspace);
		if (current != null && report != null)
			current.hadReport = true;
		if (current == null || report == null || !config.getAnnotateSecurity().getValue())
			return;
		Set<String> names = new HashSet<>(current.flagged);
		current.flagged = Set.copyOf(PluginNotes.groupSecurity(report, Severity.MEDIUM).keySet());
		names.addAll(current.flagged);
		invalidate(workspace, names);
	}

	private void onStructureToggled() {
		State current = currentState();
		PluginIndex index = current == null ? null : semanticService.getCachedIndex(current.workspace);
		if (index != null)
			invalidate(current.workspace, index.classNames());
	}

	private void onSecurityToggled() {
		State current = currentState();
		if (current != null)
			invalidate(current.workspace, current.flagged);
	}

	private void onNamesToggled() {
		State current = currentState();
		if (current != null)
			invalidate(current.workspace, null);
	}

	private void onDeobfuscationToggled(boolean enabled) {
		State current = currentState();
		if (current == null)
			return;
		if (enabled) {
			startDeobfuscation(current);
			return;
		}
		cancel(current);
		DeobfuscatedView view;
		synchronized (this) {
			view = current.view;
			current.view = null;
			current.build = null;
			current.feedback = null;
			current.phase = DeobfuscationStatus.Phase.IDLE;
		}
		notifyStatus(current.workspace);
		Set<String> names = new HashSet<>(drainPending());
		if (view != null)
			names.addAll(view.classNames());
		if (!names.isEmpty())
			invalidate(current.workspace, names);
	}

	@Nullable
	private synchronized State currentState() {
		Workspace current = workspaceManager.getCurrent();
		return current == null ? null : stateFor(current);
	}

	@Nonnull
	private Set<String> drainPending() {
		Set<String> names = new HashSet<>(pending);
		pending.removeAll(names);
		return names;
	}

	/**
	 * @param workspace
	 * 		Workspace to drop cached decompilations in.
	 * @param names
	 * 		Classes to drop the cached decompilation of, or {@code null} for every class of the plugin.
	 */
	private void invalidate(@Nonnull Workspace workspace, @Nullable Set<String> changed) {
		if (changed != null && changed.isEmpty())
			return;
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
		Set<String> names = changed == null ? null : withEnclosingClasses(changed, bundle);
		if (names == null) {
			bundle.forEach(CachedDecompileProperty::remove);
		} else {
			for (String name : names) {
				JvmClassInfo info = bundle.get(name);
				if (info != null)
					CachedDecompileProperty.remove(info);
			}
		}
		ViewRefresh refresh = new ViewRefresh(workspace, names == null ? null : Set.copyOf(names));
		for (Consumer<ViewRefresh> listener : refreshListeners) {
			try {
				listener.accept(refresh);
			} catch (Throwable t) {
				logger.error("Plugin view refresh listener failed", t);
			}
		}
	}

	/**
	 * Decompilers print inner classes inside the class that contains them, so a decompilation is also out of date when
	 * a class nested in it changes.
	 */
	@Nonnull
	private static Set<String> withEnclosingClasses(@Nonnull Set<String> names, @Nonnull JvmClassBundle bundle) {
		Set<String> all = new HashSet<>(names);
		for (String name : names) {
			for (int i = name.indexOf('$'); i > 0; i = name.indexOf('$', i + 1)) {
				String enclosing = name.substring(0, i);
				if (bundle.containsKey(enclosing))
					all.add(enclosing);
			}
		}
		return all;
	}

	// ==================== Deobfuscated view ==================== //

	private void startDeobfuscation(@Nonnull State current) {
		CancellableTransformationFeedback feedback;
		synchronized (this) {
			if (current.build != null || current.view != null || current.phase == DeobfuscationStatus.Phase.FAILED)
				return;
			feedback = new CancellableTransformationFeedback();
			current.feedback = feedback;
			current.phase = DeobfuscationStatus.Phase.BUILDING;
			int passes = Math.max(1, config.getDeobfuscationPasses().getValue());
			current.build = CompletableFuture.supplyAsync(() -> {
				long start = System.currentTimeMillis();
				try {
					DeobfuscatedView built = DeobfuscatedView.build(applierService, graphService, current.workspace, passes, feedback);
					logger.debug("Built deobfuscated view in {}ms: {} classes changed", System.currentTimeMillis() - start, built.size());
					return built;
				} catch (Throwable t) {
					throw new IllegalStateException(t);
				}
			}, deobfuscationPool);
		}
		notifyStatus(current.workspace);
		CompletableFuture<DeobfuscatedView> build = current.build;
		build.whenComplete((view, error) -> {
			boolean usable;
			synchronized (this) {
				if (current.build != build)
					return;
				usable = error == null && !feedback.hasRequestedCancellation();
				if (usable) {
					current.view = view;
				} else {
					current.build = null;
					current.phase = error != null ? DeobfuscationStatus.Phase.FAILED : DeobfuscationStatus.Phase.IDLE;
				}
			}
			if (error != null)
				logger.error("Failed to build the deobfuscated view", error);
			if (usable) {
				Set<String> names = new HashSet<>(view.classNames());
				names.addAll(drainPending());
				invalidate(current.workspace, names);
				synchronized (this) {
					if (current.view == view)
						current.phase = DeobfuscationStatus.Phase.READY;
				}
			}
			notifyStatus(current.workspace);
		});
	}

	private void notifyStatus(@Nonnull Workspace workspace) {
		for (Consumer<Workspace> listener : statusListeners) {
			try {
				listener.accept(workspace);
			} catch (Throwable t) {
				logger.error("Plugin view status listener failed", t);
			}
		}
	}

	// ==================== Filters ==================== //

	@Nonnull
	private byte[] viewOf(@Nonnull Workspace workspace, @Nonnull JvmClassInfo initial, @Nonnull byte[] bytecode) {
		if (!semanticService.isTracked(workspace))
			return bytecode;
		String className = initial.getName();
		if (workspace.getPrimaryResource().getJvmClassBundle().get(className) == null)
			return bytecode;
		State current = stateFor(workspace);
		if (current == null)
			return bytecode;

		JvmClassInfo model = initial;
		byte[] working = bytecode;
		if (config.getDeobfuscatedView().getValue()) {
			DeobfuscatedView view = readyView(current);
			if (view == null) {
				pending.add(className);
				startDeobfuscation(current);
			} else if (bytecode == initial.getBytecode()) {
				JvmClassInfo transformed = view.transformedFor(initial);
				if (transformed != null) {
					model = transformed;
					working = transformed.getBytecode();
				}
			}
		}

		boolean structure = config.getAnnotateStructure().getValue();
		boolean names = config.getNameParameters().getValue();
		PluginIndex index = structure || names ? semanticService.getCachedIndex(workspace) : null;
		if ((structure || names) && index == null)
			pending.add(className);

		Map<String, List<SecurityFinding>> security = null;
		if (config.getAnnotateSecurity().getValue()) {
			PluginSecurityReport report = securityService.getCachedReport(workspace);
			if (report != null)
				security = groupedSecurity(current, report);
		}

		PluginNotes notes = structure || security != null
				? PluginNotes.of(model, structure ? index : null, security, Math.max(1, config.getMaxNotesPerMember().getValue()))
				: PluginNotes.EMPTY;
		Map<String, String[]> parameterNames = names && index != null ? parameterNames(current, index, model) : Map.of();
		if (notes.isEmpty() && parameterNames.isEmpty())
			return working;

		try {
			ClassReader reader = new ClassReader(working);
			ClassWriter writer = new ClassWriter(reader, 0);
			reader.accept(new PluginViewVisitor(notes, parameterNames, writer), 0);
			return writer.toByteArray();
		} catch (Throwable t) {
			logger.error("Failed to add the plugin view to class '{}'", className, t);
			return working;
		}
	}

	@Nullable
	private synchronized DeobfuscatedView readyView(@Nonnull State current) {
		return current.view;
	}

	@Nonnull
	private Map<String, String[]> parameterNames(@Nonnull State current, @Nonnull PluginIndex index, @Nonnull JvmClassInfo model) {
		Map<String, String[]> result = null;
		SemanticNameSuggester namer = null;
		for (MethodMember method : model.getMethods()) {
			if (!needsParameterNames(method))
				continue;
			if (namer == null)
				namer = namerFor(current, index);
			String[] names = namer.parameterNames(model.getName(), method);
			for (String name : names) {
				if (name != null) {
					if (result == null)
						result = new HashMap<>();
					result.put(PluginNotes.key(method.getName(), method.getDescriptor()), names);
					break;
				}
			}
		}
		return result == null ? Map.of() : result;
	}

	private static boolean needsParameterNames(@Nonnull MethodMember method) {
		return method.getDescriptor().charAt(1) != ')' &&
				method.getLocalVariables().isEmpty() &&
				!method.hasAbstractModifier() && !method.hasNativeModifier() &&
				!method.hasSyntheticModifier() && !method.hasBridgeModifier();
	}

	@Nonnull
	private SemanticNameSuggester namerFor(@Nonnull State current, @Nonnull PluginIndex index) {
		synchronized (current) {
			if (current.namerIndex != index || current.namer == null) {
				current.namer = SemanticNameSuggester.forParameters(current.workspace, index, semanticConfig.getSkipShadedLibraries().getValue());
				current.namerIndex = index;
			}
			return current.namer;
		}
	}

	@Nonnull
	private Map<String, List<SecurityFinding>> groupedSecurity(@Nonnull State current, @Nonnull PluginSecurityReport report) {
		synchronized (current) {
			if (current.groupedReport != report || current.grouped == null) {
				current.grouped = PluginNotes.groupSecurity(report, Severity.MEDIUM);
				current.groupedReport = report;
				current.flagged = Set.copyOf(current.grouped.keySet());
			}
			return current.grouped;
		}
	}

	private final class ViewBytecodeFilter implements JvmBytecodeFilter {
		@Nonnull
		@Override
		public byte[] filter(@Nonnull Workspace workspace, @Nonnull JvmClassInfo initialClassInfo, @Nonnull byte[] bytecode) {
			try {
				return viewOf(workspace, initialClassInfo, bytecode);
			} catch (Throwable t) {
				logger.error("Plugin view failed for class '{}'", initialClassInfo.getName(), t);
				return bytecode;
			}
		}
	}

	private static final class NoteRewritingFilter implements OutputTextFilter {
		@Nonnull
		@Override
		public String filter(@Nonnull Workspace workspace, @Nonnull ClassInfo classInfo, @Nonnull String code) {
			return NoteMarker.rewrite(code);
		}
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public PluginViewConfig getServiceConfig() {
		return config;
	}

	/**
	 * Decompilations of classes of a workspace that are out of date.
	 *
	 * @param workspace
	 * 		Workspace the classes are in.
	 * @param classNames
	 * 		Internal names of the classes, or {@code null} when every class of the plugin is.
	 */
	public record ViewRefresh(@Nonnull Workspace workspace, @Nullable Set<String> classNames) {}

	/**
	 * Progress of building the deobfuscated view.
	 *
	 * @param phase
	 * 		Where the build is.
	 * @param changedClasses
	 * 		Number of classes that the transformers changed. Zero until the build is ready.
	 */
	public record DeobfuscationStatus(@Nonnull Phase phase, int changedClasses) {
		/**
		 * Where the build is.
		 */
		public enum Phase {
			/** Not started, or turned off. */
			IDLE,
			/** Transformers are running. */
			BUILDING,
			/** Finished, and being used. */
			READY,
			/** Failed. It is not retried until the view is turned off and on again. */
			FAILED
		}
	}

	private static final class State {
		private final Workspace workspace;
		private PluginIndex indexed;
		private boolean hadReport;
		private Set<String> flagged = Set.of();
		private PluginIndex namerIndex;
		private SemanticNameSuggester namer;
		private PluginSecurityReport groupedReport;
		private Map<String, List<SecurityFinding>> grouped;
		private CompletableFuture<DeobfuscatedView> build;
		private CancellableTransformationFeedback feedback;
		private DeobfuscatedView view;
		private DeobfuscationStatus.Phase phase = DeobfuscationStatus.Phase.IDLE;

		private State(@Nonnull Workspace workspace) {
			this.workspace = workspace;
		}
	}
}
