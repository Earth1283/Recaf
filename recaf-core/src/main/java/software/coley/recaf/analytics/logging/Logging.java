package software.coley.recaf.analytics.logging;

import io.jstach.rainbowgum.LogAppender;
import io.jstach.rainbowgum.LogConfig;
import io.jstach.rainbowgum.LogEncoder;
import io.jstach.rainbowgum.LogEvent;
import io.jstach.rainbowgum.LogFormatter;
import io.jstach.rainbowgum.LogOutput;
import io.jstach.rainbowgum.LogRouter.Router.RouterFactory;
import io.jstach.rainbowgum.MetaLog;
import io.jstach.rainbowgum.RainbowGum;
import io.jstach.rainbowgum.output.FileOutput;
import io.jstach.rainbowgum.output.FileOutputBuilder;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import software.coley.recaf.util.ExcludeFromJacocoGeneratedReport;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * {@link LoggerFactory} wrapper that lets us intercept all logged messages.
 *
 * @author Matt Coley
 */
@ExcludeFromJacocoGeneratedReport(justification = "Logging not relevant for test coverage")
public class Logging {
	private static final Object CONFIG_LOCK = new Object();
	private static final Map<String, DebuggingLogger> loggers = new ConcurrentHashMap<>();
	private static final NavigableSet<String> loggerKeys = Collections.synchronizedNavigableSet(new TreeSet<>());
	private static final List<LogConsumer<String>> logConsumers = new CopyOnWriteArrayList<>();
	private static final Map<Path, FileLogSink> fileSinks = new ConcurrentHashMap<>();
	private static final LogConfig FILE_OUTPUT_CONFIG = LogConfig.builder().build();
	private static final LogEncoder CONSOLE_ENCODER = createConsoleEncoder();
	private static final LogEncoder FILE_ENCODER = LogFormatter.builder()
			.timeStamp(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault()))
			.text(" [").threadName().text("#").threadId().text("] ")
			.add(LogFormatter.LevelFormatter.ofRightPadded())
			.text(" ").loggerName().text(": ").message().newline().throwable().encoder();
	private static volatile boolean configured;
	private static volatile boolean consoleOnStandardError;
	private static volatile Level interceptLevel = Level.INFO;

	/**
	 * @return Set of the current logger keys.
	 */
	@Nonnull
	public static NavigableSet<String> loggerKeys() {
		// We track the keys in this separate set so that we can retrieve them
		// in sorted order without needing to wrap in 'new TreeSet' every time.
		return Collections.unmodifiableNavigableSet(loggerKeys);
	}

	/**
	 * @param name
	 * 		Logger name.
	 *
	 * @return Logger associated with name.
	 */
	@Nonnull
	public static DebuggingLogger get(@Nonnull String name) {
		ensureConfigured();
		return loggers.computeIfAbsent(name, k -> intercept(k, getLogger(k)));
	}

	/**
	 * @param cls
	 * 		Logger class key.
	 *
	 * @return Logger associated with class.
	 */
	@Nonnull
	public static DebuggingLogger get(@Nonnull Class<?> cls) {
		return get(cls.getName());
	}

	/**
	 * @param consumer
	 * 		New log message consumer.
	 */
	public static void addLogConsumer(@Nonnull LogConsumer<String> consumer) {
		logConsumers.add(consumer);
	}

	/**
	 * @param consumer
	 * 		Log message consumer to remove.
	 */
	public static void removeLogConsumer(@Nonnull LogConsumer<String> consumer) {
		logConsumers.remove(consumer);
	}

	/**
	 * Sets the target level for log interception. This affects what messages {@link LogConsumer}s receive.
	 *
	 * @param level
	 * 		New target level.
	 */
	public static void setInterceptLevel(@Nonnull Level level) {
		interceptLevel = level;
	}

	/**
	 * Registers a file appender for Recaf log events at DEBUG and above, or TRACE with RECAF_DEBUG enabled.
	 *
	 * @param path
	 * 		Path to file to append to.
	 */
	@SuppressWarnings("resource")
	public static void addFileAppender(@Nonnull Path path) {
		ensureConfigured();
		Path normalized = path.toAbsolutePath().normalize();
		fileSinks.computeIfAbsent(normalized, FileLogSink::new);
	}

	/**
	 * Sends console logging to standard error instead of standard out, for when standard out carries a protocol.
	 * <p/>
	 * The console appender is bound when logging is first used, so this only takes effect if called before that.
	 *
	 * @return {@code true} when the console will log to standard error.
	 * {@code false} when logging was already configured, in which case the caller must silence the console instead.
	 */
	public static boolean redirectConsoleToStandardError() {
		synchronized (CONFIG_LOCK) {
			if (configured)
				return false;
			consoleOnStandardError = true;
			return true;
		}
	}

	/**
	 * Lazily initialize logging configuration.
	 */
	private static void ensureConfigured() {
		if (configured)
			return;
		synchronized (CONFIG_LOCK) {
			if (configured)
				return;
			RainbowGum.set(Logging::createRainbowGum);
			LoggerFactory.getILoggerFactory();
			configured = true;
		}
	}

	/**
	 * Set up a 'log-everything' configuration that we can then filter down in the appender factories.
	 *
	 * @return Configured logger instance.
	 */
	@Nonnull
	private static RainbowGum createRainbowGum() {
		return RainbowGum.builder()
				.route(route -> {
					route.level(System.Logger.Level.TRACE);
					route.appender("console", appender -> {
						appender.output(consoleOnStandardError ? LogOutput.ofStandardErr() : LogOutput.ofStandardOut());
						appender.encoder(CONSOLE_ENCODER);
						appender.flag(LogAppender.AppenderFlag.IMMEDIATE_FLUSH);
					});
					route.factory(RouterFactory.of(event -> {
						if (!fileSinks.isEmpty() && RecafLoggingFilter.allowsFile(event.loggerName(), event.level()))
							fileSinks.values().forEach(sink -> sink.write(event));
						return RecafLoggingFilter.allowsConsole(event.loggerName(), event.level()) ? event : null;
					}));
				})
				.build();
	}

	/**
	 * @return Console log encoder.
	 */
	@Nonnull
	private static LogEncoder createConsoleEncoder() {
		// HH:mm:ss.SSS [Class/Thread] LEVEL : MESSAGE
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
				.withZone(ZoneId.systemDefault());
		return LogFormatter.builder()
				.add(LogFormatter.TimestampFormatter.of(formatter))
				.text(" [")
				.add(LogFormatter.of((output, event) -> {
					String loggerName = event.loggerName();
					int separator = loggerName.lastIndexOf('.');
					if (separator >= 0 && separator + 1 < loggerName.length())
						output.append(loggerName, separator + 1, loggerName.length());
					else
						output.append(loggerName);
				}))
				.text("/")
				.threadName()
				.text("] ")
				.add(LogFormatter.LevelFormatter.ofRightPadded())
				.text(": ")
				.message()
				.newline()
				.throwable()
				.encoder();
	}

	/**
	 * @param name
	 * 		Logger name.
	 * @param logger
	 * 		Logger to wrap with interception.
	 *
	 * @return Wrapped intercepting logger.
	 */
	@Nonnull
	private static DebuggingLogger intercept(@Nonnull String name, @Nonnull Logger logger) {
		loggerKeys.add(name);
		return new InterceptingLogger(logger) {
			@Override
			public void intercept(@Nonnull Level level, String message) {
				if (interceptLevel.toInt() <= level.toInt())
					logConsumers.forEach(consumer -> consumer.accept(name, level, message));
			}

			@Override
			public void intercept(@Nonnull Level level, String message, Throwable t) {
				if (interceptLevel.toInt() <= level.toInt())
					logConsumers.forEach(consumer -> consumer.accept(name, level, message, t));
			}
		};
	}

	/**
	 * Log sink that writes to a file.
	 */
	private static final class FileLogSink implements AutoCloseable {
		private final Path path;
		private final FileOutput output;
		private final Thread shutdownHook;

		/**
		 * @param path
		 * 		File to write to.
		 */
		private FileLogSink(@Nonnull Path path) {
			this.path = path;

			output = new FileOutputBuilder("recaf-file")
					.fileName(path.toString())
					.append(true)
					.prudent(true)
					.bufferSize(FileOutput.DEFAULT_BUFFER_SIZE)
					.build();
			output.start(FILE_OUTPUT_CONFIG);

			// Ensure we close the file output when the JVM shuts down.
			shutdownHook = new Thread(this::close, "recaf-log-file-" + Math.abs(path.hashCode()));
			Runtime.getRuntime().addShutdownHook(shutdownHook);
		}

		public synchronized void write(@Nonnull LogEvent event) {
			if (fileSinks.get(path) != this)
				return;
			try (LogEncoder.Buffer buffer = FILE_ENCODER.buffer(output.bufferHints())) {
				FILE_ENCODER.encode(event, buffer);
				output.write(event, buffer);
				output.flush();
			} catch (UncheckedIOException ex) {
				MetaLog.error(Logging.class, "Failed to write log file " + path, ex);
			}
		}

		@Override
		public synchronized void close() {
			if (!fileSinks.remove(path, this))
				return;
			output.close();
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM is already shutting down.
			}
		}
	}
}
