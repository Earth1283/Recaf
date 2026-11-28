package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.weld.util.LazyValueHolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import software.coley.observables.ObservableObject;
import software.coley.observables.ObservableString;
import software.coley.recaf.analytics.logging.DebuggingLogger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.decompile.filter.AstAnalysisModeOutputFilter;
import software.coley.recaf.services.decompile.filter.ConstantDefinitionRemovingOutputFilter;
import software.coley.recaf.services.decompile.filter.DecompilerBytecodeRepairFilter;
import software.coley.recaf.services.decompile.filter.JvmBytecodeFilter;
import software.coley.recaf.services.decompile.filter.OutputTextFilter;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.util.visitors.BogusNameRemovingVisitor;
import software.coley.recaf.util.visitors.ClassHollowingVisitor;
import software.coley.recaf.util.visitors.DuplicateAnnotationRemovingVisitor;
import software.coley.recaf.util.visitors.IllegalAnnotationRemovingVisitor;
import software.coley.recaf.util.visitors.IllegalSignatureRemovingVisitor;
import software.coley.recaf.util.visitors.LongAnnotationRemovingVisitor;
import software.coley.recaf.util.visitors.LongExceptionRemovingVisitor;
import software.coley.recaf.util.visitors.SyntheticRemovingVisitor;
import software.coley.recaf.workspace.model.Workspace;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * Manager of multiple {@link Decompiler} instances.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class DecompilerManager implements Service {
	public static final String SERVICE_ID = "decompilers";
	private static final DebuggingLogger logger = Logging.get(DecompilerManager.class);
	private static final int EXPORT_WORKERS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
	private static final NoopJvmDecompiler NO_OP_JVM = NoopJvmDecompiler.getInstance();
	private static final NoopAndroidDecompiler NO_OP_ANDROID = NoopAndroidDecompiler.getInstance();
	private final JvmBytecodeFilter layeredJvmFilter = new LayeredJvmBytecodeFilter();
	private final ExecutorService decompileThreadPool = ThreadPoolFactory.newFixedThreadPool(SERVICE_ID);
	private final ExecutorService exportCoordinator = ThreadPoolFactory.newSingleThreadExecutor(SERVICE_ID + "-export");
	private final ExecutorService exportWorkers = ThreadPoolFactory.newFixedThreadPool(SERVICE_ID + "-export-worker", EXPORT_WORKERS, true);
	private final List<JvmBytecodeFilter> bytecodeFilters = new CopyOnWriteArrayList<>();
	private final List<OutputTextFilter> outputTextFilters = new CopyOnWriteArrayList<>();
	private final List<JvmBytecodeFilter> nestedClassFilters = new CopyOnWriteArrayList<>();
	private final Map<String, JvmDecompiler> jvmDecompilers = new TreeMap<>();
	private final Map<String, AndroidDecompiler> androidDecompilers = new TreeMap<>();
	private final DecompilerManagerConfig config;
	private final ObservableObject<JvmDecompiler> targetJvmDecompiler;
	private final ObservableObject<AndroidDecompiler> targetAndroidDecompiler;
	private final DecompilerBytecodeRepairFilter repairFilter;
	private final AstAnalysisModeOutputFilter astAnalysisFilter;
	private final ConstantDefinitionRemovingOutputFilter constantDefinitionFilter;

	/**
	 * @param config
	 * 		Config to pull values from.
	 * @param implementations
	 * 		CDI provider of decompiler implementations.
	 */
	@Inject
	public DecompilerManager(@Nonnull DecompilerManagerConfig config,
	                         @Nonnull Instance<Decompiler> implementations) {
		this.config = config;
		this.repairFilter = new DecompilerBytecodeRepairFilter(this::getServiceConfig);
		this.astAnalysisFilter = new AstAnalysisModeOutputFilter(this::getServiceConfig);
		this.constantDefinitionFilter = new ConstantDefinitionRemovingOutputFilter(this::getServiceConfig);
		addNestedClassFilter(repairFilter);
		addOutputTextFilter(astAnalysisFilter);
		addOutputTextFilter(constantDefinitionFilter);

		// Register implementations
		for (Decompiler implementation : implementations) {
			if (implementation instanceof JvmDecompiler jvmDecompiler) {
				register(jvmDecompiler);
			} else if (implementation instanceof AndroidDecompiler androidDecompiler) {
				register(androidDecompiler);
			}
		}

		ObservableString preferredJvmDecompiler = config.getPreferredJvmDecompiler();
		ObservableString preferredAndroidDecompiler = config.getPreferredAndroidDecompiler();

		// Mirror properties from config, mapped to instances
		targetJvmDecompiler = preferredJvmDecompiler
				.mapObject(key -> jvmDecompilers.getOrDefault(key == null ? "" : key, NO_OP_JVM));
		targetAndroidDecompiler = preferredAndroidDecompiler
				.mapObject(key -> androidDecompilers.getOrDefault(key == null ? "" : key, NO_OP_ANDROID));

		// Select first item if no value is present
		if (preferredJvmDecompiler.getValue() == null) {
			JvmDecompiler decompiler = jvmDecompilers.isEmpty() ?
					NO_OP_JVM : jvmDecompilers.values().iterator().next();
			preferredJvmDecompiler.setValue(decompiler.getName());
		}
		if (preferredAndroidDecompiler.getValue() == null) {
			AndroidDecompiler decompiler = androidDecompilers.isEmpty() ?
					NO_OP_ANDROID : androidDecompilers.values().iterator().next();
			preferredAndroidDecompiler.setValue(decompiler.getName());
		}
	}

	/**
	 * Uses the built-in thread-pool to schedule the decompilation with the {@link #getTargetJvmDecompiler()}.
	 *
	 * @param workspace
	 * 		Workspace to pull additional information from.
	 * @param classInfo
	 * 		Class to decompile.
	 *
	 * @return Future of decompilation result.
	 */
	@Nonnull
	public CompletableFuture<DecompileResult> decompile(@Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo) {
		return decompile(getTargetJvmDecompiler(), workspace, classInfo);
	}

	/**
	 * Uses the built-in thread-pool to schedule the decompilation.
	 *
	 * @param decompiler
	 * 		Decompiler implementation to use.
	 * @param workspace
	 * 		Workspace to pull additional information from.
	 * @param classInfo
	 * 		Class to decompile.
	 *
	 * @return Future of decompilation result.
	 */
	@Nonnull
	public CompletableFuture<DecompileResult> decompile(@Nonnull JvmDecompiler decompiler, @Nonnull Workspace workspace, @Nonnull JvmClassInfo classInfo) {
		return CompletableFuture.supplyAsync(() ->
				decompileJvm(decompiler, workspace, classInfo, config.getCacheDecompilations().getValue()), decompileThreadPool);
	}

	/**
	 * Decompiles a batch using workers separate from interactive requests. Only a small window of classes is
	 * submitted at a time, and exported results are not retained in the editor cache.
	 *
	 * @param decompiler
	 * 		Decompiler implementation to use for the entire batch.
	 * @param workspace
	 * 		Workspace to pull additional information from.
	 * @param classes
	 * 		Classes to decompile.
	 * @param timeout
	 * 		Per-class time budget, starting when an export worker picks up the class.
	 * @param consumer
	 * 		Receives results (including failures) serially in completion order, off the caller's thread.
	 *
	 * @return Future that completes after all results have been consumed, or fails if the consumer fails.
	 * Cancelling it stops scheduling new classes and discards outstanding results.
	 */
	@Nonnull
	public CompletableFuture<Void> decompileBatch(@Nonnull JvmDecompiler decompiler, @Nonnull Workspace workspace,
	                                             @Nonnull List<JvmClassInfo> classes, @Nonnull Duration timeout,
	                                             @Nonnull BiConsumer<JvmClassInfo, DecompileResult> consumer) {
		List<JvmClassInfo> targets = List.copyOf(classes);
		long timeoutNanos = timeout.toNanos();
		BlockingQueue<ExportResult> ready = new ArrayBlockingQueue<>(EXPORT_WORKERS + 1);
		CompletableFuture<Void> batch = new CompletableFuture<>();
		batch.whenComplete((ignored, error) -> {
			// Wake the coordinator if it is waiting for a class result.
			if (batch.isCancelled())
				ready.add(new ExportResult(null, null, null));
		});
		exportCoordinator.execute(() -> {
			try {
				int submitted = 0;
				for (int completed = 0; completed < targets.size() && !batch.isCancelled(); completed++) {
					while (submitted - completed < EXPORT_WORKERS && submitted < targets.size() && !batch.isCancelled())
						submitExportClass(decompiler, workspace, targets.get(submitted++), timeoutNanos, ready, batch);
					if (batch.isCancelled())
						break;
					ExportResult item = ready.take();
					if (batch.isCancelled())
						break;
					DecompileResult result = item.error() == null ? item.result() :
							new DecompileResult(item.error(), decompiler.getConfig().getHash());
					consumer.accept(item.info(), result);
				}
				batch.complete(null);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				batch.completeExceptionally(ex);
			} catch (Throwable t) {
				batch.completeExceptionally(t);
			}
		});
		return batch;
	}

	private void submitExportClass(@Nonnull JvmDecompiler decompiler, @Nonnull Workspace workspace,
	                               @Nonnull JvmClassInfo info, long timeoutNanos,
	                               @Nonnull BlockingQueue<ExportResult> ready, @Nonnull CompletableFuture<Void> batch) {
		CompletableFuture<DecompileResult> task = new CompletableFuture<>();
		FutureTask<Void> worker = new FutureTask<>(() -> {
			if (batch.isDone()) {
				task.cancel(false);
				return;
			}
			task.orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
			try {
				task.complete(decompileJvm(decompiler, workspace, info, false));
			} catch (Throwable t) {
				task.completeExceptionally(t);
			}
		}, null);
		task.whenComplete((result, error) -> {
			if (error instanceof TimeoutException)
				worker.cancel(true);
			ready.add(new ExportResult(info, result, error));
		});
		exportWorkers.execute(worker);
	}

	@Nonnull
	private DecompileResult decompileJvm(@Nonnull JvmDecompiler decompiler, @Nonnull Workspace workspace,
	                                     @Nonnull JvmClassInfo classInfo, boolean doCache) {
		int effectiveHash = decompiler.getConfig().getHash() ^ (31 * config.computeFilterHash());
		if (doCache) {
			DecompileResult cachedResult = CachedDecompileProperty.get(classInfo, decompiler);
			if (cachedResult != null) {
				if (cachedResult.getConfigHash() == effectiveHash)
					return cachedResult;
				CachedDecompileProperty.remove(classInfo);
			}
		}

		JvmClassInfo filteredClass = JvmBytecodeFilter.applyFilters(workspace, classInfo, Collections.singletonList(layeredJvmFilter));
		DecompileResult result = decompiler.decompile(workspace, filteredClass);
		String decompilation = result.getText();
		if (result.getType() == DecompileResult.ResultType.SUCCESS && decompilation != null && !outputTextFilters.isEmpty()) {
			for (OutputTextFilter textFilter : outputTextFilters)
				decompilation = textFilter.filter(workspace, classInfo, decompilation);
			result = result.withText(decompilation);
		}
		result = result.withConfigHash(effectiveHash);
		if (doCache)
			CachedDecompileProperty.set(classInfo, decompiler, result);
		return result;
	}

	private record ExportResult(JvmClassInfo info, DecompileResult result, Throwable error) {}

	/**
	 * Uses the built-in thread-pool to schedule the decompilation with the {@link #getTargetAndroidDecompiler()}.
	 *
	 * @param workspace
	 * 		Workspace to pull additional information from.
	 * @param classInfo
	 * 		Class to decompile.
	 *
	 * @return Future of decompilation result.
	 */
	@Nonnull
	public CompletableFuture<DecompileResult> decompile(@Nonnull Workspace workspace, @Nonnull AndroidClassInfo classInfo) {
		return decompile(getTargetAndroidDecompiler(), workspace, classInfo);
	}

	/**
	 * Uses the built-in thread-pool to schedule the decompilation.
	 *
	 * @param decompiler
	 * 		Decompiler implementation to use.
	 * @param workspace
	 * 		Workspace to pull additional information from.
	 * @param classInfo
	 * 		Class to decompile.
	 *
	 * @return Future of decompilation result.
	 */
	@Nonnull
	public CompletableFuture<DecompileResult> decompile(@Nonnull AndroidDecompiler decompiler, @Nonnull Workspace workspace, @Nonnull AndroidClassInfo classInfo) {
		return CompletableFuture.supplyAsync(() -> decompiler.decompile(workspace, classInfo), decompileThreadPool);
	}

	/**
	 * Adds an input bytecode filter to all {@link JvmDecompiler} instances.
	 *
	 * @param filter
	 * 		Filter to add.
	 */
	public void addJvmBytecodeFilter(@Nonnull JvmBytecodeFilter filter) {
		bytecodeFilters.add(filter);
	}

	/**
	 * Adds a filter that also applies to the classes a decompiler loads because the class it is decompiling refers to
	 * them, such as inner classes. Only this filter is applied to those, not the other bytecode filters.
	 *
	 * @param filter
	 * 		Filter to add to all {@link JvmDecompiler} instances.
	 */
	public void addNestedClassFilter(@Nonnull JvmBytecodeFilter filter) {
		nestedClassFilters.add(filter);
		for (JvmDecompiler decompiler : jvmDecompilers.values())
			decompiler.addNestedClassFilter(filter);
	}

	/**
	 * Adds an input bytecode filter that runs before every filter added with {@link #addJvmBytecodeFilter(JvmBytecodeFilter)}.
	 * Meant for filters that replace the bytecode wholesale, since filters after them then see the replacement.
	 *
	 * @param filter
	 * 		Filter to add.
	 */
	public void addJvmBytecodeFilterFirst(@Nonnull JvmBytecodeFilter filter) {
		bytecodeFilters.addFirst(filter);
	}

	/**
	 * Removes an input bytecode filter from all {@link JvmDecompiler} instances.
	 *
	 * @param filter
	 * 		Filter to remove.
	 */
	public void removeJvmBytecodeFilter(@Nonnull JvmBytecodeFilter filter) {
		bytecodeFilters.remove(filter);
	}

	/**
	 * Adds an output text filter to all {@link Decompiler} instances.
	 *
	 * @param filter
	 * 		Filter to add.
	 */
	public void addOutputTextFilter(@Nonnull OutputTextFilter filter) {
		outputTextFilters.add(filter);
	}

	/**
	 * Removes an output text filter from all {@link Decompiler} instances.
	 *
	 * @param filter
	 * 		Filter to remove.
	 */
	public void removeOutputTextFilter(@Nonnull OutputTextFilter filter) {
		outputTextFilters.remove(filter);
	}

	/**
	 * @return Preferred JVM decompiler.
	 */
	@Nonnull
	public JvmDecompiler getTargetJvmDecompiler() {
		return targetJvmDecompiler.getValue();
	}

	/**
	 * @return Preferred Android decompiler.
	 */
	@Nonnull
	public AndroidDecompiler getTargetAndroidDecompiler() {
		return targetAndroidDecompiler.getValue();
	}

	/**
	 * @param decompiler
	 * 		JVM decompiler to add.
	 */
	public void register(@Nonnull JvmDecompiler decompiler) {
		jvmDecompilers.put(decompiler.getName(), decompiler);
		for (JvmBytecodeFilter filter : nestedClassFilters)
			decompiler.addNestedClassFilter(filter);
	}

	/**
	 * @param decompiler
	 * 		Android decompiler to add.
	 */
	public void register(@Nonnull AndroidDecompiler decompiler) {
		androidDecompilers.put(decompiler.getName(), decompiler);
	}

	/**
	 * @param name
	 * 		Name of decompiler.
	 *
	 * @return Decompiler instance, or {@code null} if nothing by the ID was found.
	 */
	@Nullable
	public JvmDecompiler getJvmDecompiler(@Nonnull String name) {
		return jvmDecompilers.get(name);
	}

	/**
	 * @param name
	 * 		Name of decompiler.
	 *
	 * @return Decompiler instance, or {@code null} if nothing by the ID was found.
	 */
	@Nullable
	public AndroidDecompiler getAndroidDecompiler(@Nonnull String name) {
		return androidDecompilers.get(name);
	}

	/**
	 * @return Available JVM class decompilers.
	 */
	@Nonnull
	public Collection<JvmDecompiler> getJvmDecompilers() {
		return jvmDecompilers.values();
	}

	/**
	 * @return Available android class decompilers.
	 */
	@Nonnull
	public Collection<AndroidDecompiler> getAndroidDecompilers() {
		return androidDecompilers.values();
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public DecompilerManagerConfig getServiceConfig() {
		return config;
	}

	/**
	 * JVM bytecode filter that applies multiple other filters:
	 * <ol>
	 *     <li>Any values in {@link #bytecodeFilters}</li>
	 *     <li>Any filters based on the current values in {@link #config}</li>
	 * </ol>
	 */
	private class LayeredJvmBytecodeFilter implements JvmBytecodeFilter {
		@Nonnull
		@Override
		public byte[] filter(@Nonnull Workspace workspace, @Nonnull JvmClassInfo initialClassInfo, @Nonnull byte[] bytecode) {
			// Apply filters to the input bytecode first
			for (JvmBytecodeFilter filter : bytecodeFilters)
				bytecode = filter.filter(workspace, initialClassInfo, bytecode);

			// Setup filtering based on config
			byte[] filteredBytecode = bytecode;
			LazyValueHolder<ClassReader> reader = LazyValueHolder.forSupplier(() -> new ClassReader(filteredBytecode));
			LazyValueHolder<ClassWriter> cw = LazyValueHolder.forSupplier(() -> {
				// In most cases we want to pass the class-reader along to the class-writer.
				// This will allow some operations to be sped up internally by ASM.
				//
				// However, we can't do this if we're filtering debug information since it blanket copies all
				// debug attribute information without checking what the class-reader flags are. We also need to
				// avoid it when filtering long exceptions for the same reason.
				ClassReader backing = config.getFilterDebug().getValue() || config.getFilterLongExceptions().getValue() ? null : reader.get();
				return new ClassWriter(backing, 0);
			});
			ClassVisitor cv = null;

			// The things you want to 'filter' first need to appear last in this chain since we're building a chain
			// of visitors which delegate from one onto another.
			if (config.getFilterNonAsciiNames().getValue()) {
				cv = cw.get();
				cv = BogusNameRemovingVisitor.create(workspace, cv);
			}
			if (config.getFilterLongAnnotations().getValue()) {
				if (cv == null) cv = cw.get();
				cv = new LongAnnotationRemovingVisitor(cv, config.getFilterLongAnnotationsLength().getValue());
			}
			if (config.getFilterLongExceptions().getValue()) {
				if (cv == null) cv = cw.get();
				cv = new LongExceptionRemovingVisitor(cv, config.getFilterLongExceptionsLength().getValue());
			}
			if (config.getFilterDuplicateAnnotations().getValue()) {
				if (cv == null) cv = cw.get();
				cv = new DuplicateAnnotationRemovingVisitor(cv);
			}
			if (config.getFilterIllegalAnnotations().getValue()) {
				if (cv == null) cv = cw.get();
				cv = new IllegalAnnotationRemovingVisitor(cv);
			}
			if (config.getFilterHollow().getValue()) {
				if (cv == null) cv = cw.get();
				cv = new ClassHollowingVisitor(cv, EnumSet.allOf(ClassHollowingVisitor.Item.class));
			}
			if (config.getFilterSignatures().getValue()) {
				if (cv == null) cv = cw.get();
				cv = new IllegalSignatureRemovingVisitor(cv);
			}
			if (config.getFilterSynthetics().getValue()) {
				if (cv == null) cv = cw.get();
				cv = new SyntheticRemovingVisitor(cv);
			}
			if (config.getFilterDebug().getValue() && cv == null)
				cv = cw.get();

			// If no visitor-based filtering has been requested, skip reader/writer and run only the bytecode repair filter.
			if (cv == null)
				return repairFilter.filter(workspace, initialClassInfo, bytecode);

			try {
				int readFlags = config.getFilterDebug().getValue() ? ClassReader.SKIP_DEBUG : 0;
				reader.get().accept(cv, readFlags);
				return repairFilter.filter(workspace, initialClassInfo, cw.get().toByteArray());
			} catch (Throwable t) {
				logger.error("Error applying filters to class '{}'", initialClassInfo.getName(), t);
				return bytecode;
			}
		}
	}
}
