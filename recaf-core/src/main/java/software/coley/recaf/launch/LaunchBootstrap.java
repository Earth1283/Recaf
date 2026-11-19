package software.coley.recaf.launch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.inject.spi.Bean;
import org.slf4j.Logger;
import software.coley.recaf.Bootstrap;
import software.coley.recaf.Recaf;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.cdi.EagerInitializationExtension;
import software.coley.recaf.cdi.InitializationEvent;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.services.plugin.PluginContainer;
import software.coley.recaf.services.plugin.PluginException;
import software.coley.recaf.services.plugin.PluginManager;
import software.coley.recaf.services.plugin.discovery.DirectoryPluginDiscoverer;
import software.coley.recaf.services.script.ScriptEngine;
import software.coley.recaf.services.script.ScriptResult;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.util.threading.ThreadUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Common launch bootstrap shared by core-headless and UI startup flows.
 *
 * @author Matt Coley
 */
public class LaunchBootstrap {
	private static final Logger logger = Logging.get(LaunchBootstrap.class);
	private final LaunchCommand launchCommand;
	private final String[] args;
	private Recaf recaf;
	private LaunchArguments launchArgs;

	public LaunchBootstrap(@Nonnull LaunchCommand launchCommand, @Nonnull String[] args) {
		this.launchCommand = launchCommand;
		this.args = args;
	}

	/**
	 * Create the Recaf container and expose launch arguments to CDI.
	 *
	 * @return Recaf instance.
	 */
	@Nonnull
	public Recaf bootstrap() {
		Path logFile = initFileLogging();
		logger.info("Starting Recaf in {} mode (PID {}, Java {} from {}, {} {} {})",
				launchCommand.isHeadless() ? "headless" : "desktop",
				ProcessHandle.current().pid(), System.getProperty("java.runtime.version"), System.getProperty("java.vendor"),
				System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));
		recaf = Bootstrap.get();
		if (logFile != null) {
			RecafDirectoriesConfig directories = recaf.get(RecafDirectoriesConfig.class);
			directories.initCurrentLogPath(logFile);
			archiveOldLogs(logFile, directories.getLogsDirectory());
		}
		launchArgs = recaf.get(LaunchArguments.class);
		launchArgs.setCommand(launchCommand);
		launchArgs.setRawArgs(args);
		return recaf;
	}

	/**
	 * Register the startup input/script handler bean for the current launch mode.
	 *
	 * @param headless
	 *        {@code true} when running without the UI.
	 */
	public void setupLaunchHandler(boolean headless) {
		// Set up the launch-handler bean to load inputs if specified by the launch arguments.
		Bean<?> bean = recaf.getContainer().getBeanContainer().getBeans(LaunchHandler.class).iterator().next();
		if (headless) {
			LaunchHandler.task = this::handleInputs;
			addIfMissing(EagerInitializationExtension.getApplicationScopedEagerBeans(), bean);
		} else {
			// Run input handling in the background so that it does not block the UI thread.
			LaunchHandler.task = () -> CompletableFuture.runAsync(this::handleInputs, ThreadUtil.executor());
			addIfMissing(EagerInitializationExtension.getApplicationScopedEagerBeansForUi(), bean);
		}
	}

	/**
	 * Initialize the headless runtime.
	 */
	public void initializeHeadless() {
		initPlugins();
		fireInitEvent();
		blockIfIdle();
	}

	/**
	 * Publishes the initialization event to trigger eager services.
	 */
	public void fireInitEvent() {
		recaf.getContainer().getBeanContainer().getEvent().fire(new InitializationEvent());
	}

	@Nullable
	private static Path initFileLogging() {
		Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
				logger.error("Uncaught exception on thread '{}' (ID {})", thread.getName(), thread.threadId(), error));
		Path baseDirectory = RecafDirectoriesConfig.createBaseDirectory().toAbsolutePath().normalize();
		Path logFile = baseDirectory.resolve("log-" + LocalDate.now() + ".txt");
		try {
			Files.createDirectories(baseDirectory);
			Logging.addFileAppender(logFile);
			return logFile;
		} catch (IOException | UncheckedIOException | SecurityException ex) {
			logger.warn("Could not open log file '{}'", logFile, ex);
			return null;
		}
	}

	private static void archiveOldLogs(@Nonnull Path logFile, @Nonnull Path logsDirectory) {
		Path baseDirectory = logFile.getParent();
		try {
			Files.createDirectories(logsDirectory);
			List<Path> oldLogs;
			try (Stream<Path> paths = Files.list(baseDirectory)) {
				oldLogs = paths.filter(path -> !path.equals(logFile))
						.filter(path -> path.getFileName().toString().matches("log-\\d+-\\d+-\\d+\\.txt"))
						.toList();
			}

			logger.trace("Compressing {} old log files", oldLogs.size());
			for (Path oldLog : oldLogs) {
				String originalFileName = oldLog.getFileName().toString();
				String archiveFileName = originalFileName.replace(".txt", ".zip");
				Path archivedLog = logsDirectory.resolve(archiveFileName);

				// Compress the log into a zip
				try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archivedLog))) {
					zos.putNextEntry(new ZipEntry(originalFileName));
					Files.copy(oldLog, zos);
					zos.closeEntry();
				}

				// Remove the old file
				Files.delete(oldLog);
			}
		} catch (IOException | UncheckedIOException | SecurityException ex) {
			logger.warn("Failed to archive old logs from '{}' to '{}'", baseDirectory, logsDirectory, ex);
		}
	}

	/**
	 * Load plugins from the configured plugin directories.
	 */
	public void initPlugins() {
		PluginManager pluginManager = recaf.get(PluginManager.class);

		// Load from the plugin directory
		try {
			RecafDirectoriesConfig dirConfig = recaf.get(RecafDirectoriesConfig.class);
			Path pluginDirectory = dirConfig.getPluginDirectory();
			Path extraPluginDirectory = dirConfig.getExtraPluginDirectory();
			pluginManager.loadPlugins(new DirectoryPluginDiscoverer(pluginDirectory));
			if (extraPluginDirectory != null)
				pluginManager.loadPlugins(new DirectoryPluginDiscoverer(extraPluginDirectory));
		} catch (PluginException ex) {
			logger.error("Failed to initialize plugins", ex);
		}

		// Log the discovered plugins
		Collection<PluginContainer<?>> plugins = pluginManager.getPlugins();
		if (plugins.isEmpty()) {
			logger.info("Initialization: No plugins found");
		} else {
			String split = "\n - ";
			logger.info("Initialization: {} plugins found:" + split + "{}",
					plugins.size(),
					plugins.stream().map(PluginContainer::info)
							.map(info -> info.name() + " - " + info.version())
							.collect(Collectors.joining(split)));
		}
	}

	/**
	 * @return Launch arguments bean populated during {@link #bootstrap()}.
	 */
	@Nonnull
	public LaunchArguments getLaunchArguments() {
		return launchArgs;
	}

	/**
	 * @return Recaf instance created during {@link #bootstrap()}.
	 */
	@Nonnull
	public Recaf getRecaf() {
		return recaf;
	}

	private void blockIfIdle() {
		if (!launchCommand.isIdle())
			return;

		logger.info("Headless idle mode enabled, waiting for shutdown");
		try {
			awaitIdleShutdown();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			logger.warn("Headless idle wait interrupted", ex);
		}
	}

	private void handleInputs() {
		File input = launchArgs.getInput();
		if (input != null) {
			try {
				if (input.exists()) {
					long start = System.nanoTime();
					logger.info("Loading startup workspace from '{}'", input);
					ResourceImporter importer = recaf.get(ResourceImporter.class);
					WorkspaceManager workspaceManager = recaf.get(WorkspaceManager.class);
					if (workspaceManager.setCurrent(new BasicWorkspace(importer.importResource(input)))) {
						logger.info("Loaded startup workspace from '{}' in {} ms", input,
								TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
					} else {
						logger.warn("Recaf could not switch to the startup workspace from '{}'", input);
					}
				} else {
					logger.warn("Startup workspace input '{}' does not exist", input);
				}
			} catch (Throwable ex) {
				logger.error("Failed to load startup workspace from '{}'", input, ex);
			}
		}

		File requestedScript = launchArgs.getScript();
		if (requestedScript == null)
			return;
		File script = requestedScript;
		try {
			if (!script.isFile())
				script = launchArgs.getScriptInScriptsDirectory();
			if (script != null && script.isFile()) {
				long start = System.nanoTime();
				logger.info("Running startup script '{}'", script);
				ScriptResult result = recaf.get(ScriptEngine.class)
						.run(Files.readString(script.toPath()))
						.get();
				if (result.wasRuntimeError()) {
					logger.error("Startup script '{}' failed at runtime", script, result.getRuntimeThrowable());
				} else if (result.wasCompileFailure()) {
					logger.error("Could not compile startup script '{}':\n{}", script, result.getCompileDiagnostics().stream()
							.map(CompilerDiagnostic::toString)
							.collect(Collectors.joining("\n")));
				} else if (result.wasCancelled()) {
					logger.info("Startup script '{}' was cancelled", script);
				} else {
					logger.info("Finished startup script '{}' in {} ms", script,
							TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
				}
			} else {
				logger.warn("Startup script '{}' was not found (also checked '{}')", requestedScript, script);
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			logger.warn("Interrupted while waiting for startup script '{}'", script, ex);
		} catch (Throwable ex) {
			logger.error("Failed to run startup script '{}'", script, ex);
		}
	}

	private static void addIfMissing(@Nonnull List<Bean<?>> beans, @Nonnull Bean<?> bean) {
		if (!beans.contains(bean))
			beans.add(bean);
	}

	private static void awaitIdleShutdown() throws InterruptedException {
		// Just block the main thread indefinitely until the process is killed.
		new CountDownLatch(1).await();
	}
}
