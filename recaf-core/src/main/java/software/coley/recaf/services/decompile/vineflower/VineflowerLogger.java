package software.coley.recaf.services.decompile.vineflower;

import jakarta.annotation.Nonnull;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import software.coley.observables.ObservableObject;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.decompile.DecompileDiagnostic;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * Logger for Vineflower
 *
 * @author therathatter
 */
public class VineflowerLogger extends IFernflowerLogger {
	private static final Logger logger = Logging.get(VineflowerLogger.class);
	private static final String VF_PREFIX = "VF: ";
	private static final Pattern METHOD_FAILURE = Pattern.compile("Method (.+?) (\\(.*\\)\\S+) in class (.+) couldn't be (?:decompiled|written)\\.");
	private final ObservableObject<Level> level;
	private final ConcurrentLinkedQueue<DecompileDiagnostic> diagnostics = new ConcurrentLinkedQueue<>();

	public VineflowerLogger(@Nonnull VineflowerConfig config) {
		this.level = config.getLoggingLevel();
	}

	@Override
	public void writeMessage(String message, Severity severity) {
		switch (severity) {
			case TRACE -> {
				if (level.getValue().compareTo(Level.TRACE) >= 0) logger.trace(VF_PREFIX + message);
			}
			case INFO -> {
				if (level.getValue().compareTo(Level.INFO) >= 0) logger.info(VF_PREFIX + message);
			}
			case WARN -> {
				if (level.getValue().compareTo(Level.WARN) >= 0) logger.warn(VF_PREFIX + message);
			}
			case ERROR -> logger.error(VF_PREFIX + message);
		}
	}

	@Override
	public void writeMessage(String message, Severity severity, Throwable throwable) {
		var match = METHOD_FAILURE.matcher(message);
		if (match.matches()) {
			var method = new DecompileDiagnostic.Method(match.group(3), match.group(1), match.group(2));
			diagnostics.add(new DecompileDiagnostic(method, message, throwable));
		} else if (severity == Severity.ERROR || message.startsWith("Class ") && message.endsWith(" couldn't be written.")) {
			diagnostics.add(new DecompileDiagnostic(null, message, throwable));
		}
		logger.error(VF_PREFIX + message, throwable);
	}

	List<DecompileDiagnostic> getDiagnostics() {
		return List.copyOf(diagnostics);
	}
}
