package software.coley.recaf.analytics.logging;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;
import software.coley.recaf.util.ExcludeFromJacocoGeneratedReport;

/**
 * A forwarding logger that lets us intercept compiled messages.
 *
 * @author Matt Coley
 */
@ExcludeFromJacocoGeneratedReport(justification = "Logging not relevant for test coverage")
public abstract class InterceptingLogger implements DebuggingLogger {
	private final Logger backing;

	/**
	 * @param backing
	 * 		Backing logger to send info to.
	 */
	protected InterceptingLogger(@Nonnull Logger backing) {
		this.backing = backing;
	}

	/**
	 * Intercept logging.
	 *
	 * @param level
	 * 		Level logged.
	 * @param message
	 * 		Message logged.
	 */
	public abstract void intercept(Level level, String message);

	/**
	 * Intercept throwable logging.
	 *
	 * @param level
	 * 		Level logged.
	 * @param message
	 * 		Message logged.
	 * @param t
	 * 		Item thrown.
	 */
	public abstract void intercept(Level level, String message, Throwable t);

	@Override
	public String getName() {
		return backing.getName();
	}

	@Override
	public boolean isTraceEnabled() {
		return backing.isTraceEnabled();
	}

	@Override
	public boolean isTraceEnabled(Marker marker) {
		return backing.isTraceEnabled(marker);
	}

	@Override
	public void trace(String msg) {
		backing.trace(msg);
		if (isTraceEnabled()) intercept(Level.TRACE, msg);
	}

	@Override
	public void trace(String format, Object arg) {
		trace(format, new Object[]{arg});
	}

	@Override
	public void trace(String format, Object arg1, Object arg2) {
		trace(format, new Object[]{arg1, arg2});
	}

	@Override
	public void trace(String format, Object... arguments) {
		backing.trace(format, arguments);
		if (isTraceEnabled()) interceptFormatted(Level.TRACE, format, arguments);
	}

	@Override
	public void trace(String msg, Throwable t) {
		backing.trace(msg, t);
		if (isTraceEnabled()) intercept(Level.TRACE, msg, t);
	}

	@Override
	public void trace(Marker marker, String msg) {
		backing.trace(marker, msg);
		if (isTraceEnabled(marker)) intercept(Level.TRACE, msg);
	}

	@Override
	public void trace(Marker marker, String format, Object arg) {
		trace(marker, format, new Object[]{arg});
	}

	@Override
	public void trace(Marker marker, String format, Object arg1, Object arg2) {
		trace(marker, format, new Object[]{arg1, arg2});
	}

	@Override
	public void trace(Marker marker, String format, Object... arguments) {
		backing.trace(marker, format, arguments);
		if (isTraceEnabled(marker)) interceptFormatted(Level.TRACE, format, arguments);
	}

	@Override
	public void trace(Marker marker, String msg, Throwable t) {
		backing.trace(marker, msg, t);
		if (isTraceEnabled(marker)) intercept(Level.TRACE, msg, t);
	}

	@Override
	public boolean isDebugEnabled() {
		return backing.isDebugEnabled();
	}

	@Override
	public boolean isDebugEnabled(Marker marker) {
		return backing.isDebugEnabled(marker);
	}

	@Override
	public void debug(String msg) {
		backing.debug(msg);
		if (isDebugEnabled()) intercept(Level.DEBUG, msg);
	}

	@Override
	public void debug(String format, Object arg) {
		debug(format, new Object[]{arg});
	}

	@Override
	public void debug(String format, Object arg1, Object arg2) {
		debug(format, new Object[]{arg1, arg2});
	}

	@Override
	public void debug(String format, Object... arguments) {
		backing.debug(format, arguments);
		if (isDebugEnabled()) interceptFormatted(Level.DEBUG, format, arguments);
	}

	@Override
	public void debug(String msg, Throwable t) {
		backing.debug(msg, t);
		if (isDebugEnabled()) intercept(Level.DEBUG, msg, t);
	}

	@Override
	public void debug(Marker marker, String msg) {
		backing.debug(marker, msg);
		if (isDebugEnabled(marker)) intercept(Level.DEBUG, msg);
	}

	@Override
	public void debug(Marker marker, String format, Object arg) {
		debug(marker, format, new Object[]{arg});
	}

	@Override
	public void debug(Marker marker, String format, Object arg1, Object arg2) {
		debug(marker, format, new Object[]{arg1, arg2});
	}

	@Override
	public void debug(Marker marker, String format, Object... arguments) {
		backing.debug(marker, format, arguments);
		if (isDebugEnabled(marker)) interceptFormatted(Level.DEBUG, format, arguments);
	}

	@Override
	public void debug(Marker marker, String msg, Throwable t) {
		backing.debug(marker, msg, t);
		if (isDebugEnabled(marker)) intercept(Level.DEBUG, msg, t);
	}

	@Override
	public boolean isInfoEnabled() {
		return backing.isInfoEnabled();
	}

	@Override
	public boolean isInfoEnabled(Marker marker) {
		return backing.isInfoEnabled(marker);
	}

	@Override
	public void info(String msg) {
		backing.info(msg);
		if (isInfoEnabled()) intercept(Level.INFO, msg);
	}

	@Override
	public void info(String format, Object arg) {
		info(format, new Object[]{arg});
	}

	@Override
	public void info(String format, Object arg1, Object arg2) {
		info(format, new Object[]{arg1, arg2});
	}

	@Override
	public void info(String format, Object... arguments) {
		backing.info(format, arguments);
		if (isInfoEnabled()) interceptFormatted(Level.INFO, format, arguments);
	}

	@Override
	public void info(String msg, Throwable t) {
		backing.info(msg, t);
		if (isInfoEnabled()) intercept(Level.INFO, msg, t);
	}

	@Override
	public void info(Marker marker, String msg) {
		backing.info(marker, msg);
		if (isInfoEnabled(marker)) intercept(Level.INFO, msg);
	}

	@Override
	public void info(Marker marker, String format, Object arg) {
		info(marker, format, new Object[]{arg});
	}

	@Override
	public void info(Marker marker, String format, Object arg1, Object arg2) {
		info(marker, format, new Object[]{arg1, arg2});
	}

	@Override
	public void info(Marker marker, String format, Object... arguments) {
		backing.info(marker, format, arguments);
		if (isInfoEnabled(marker)) interceptFormatted(Level.INFO, format, arguments);
	}

	@Override
	public void info(Marker marker, String msg, Throwable t) {
		backing.info(marker, msg, t);
		if (isInfoEnabled(marker)) intercept(Level.INFO, msg, t);
	}

	@Override
	public boolean isWarnEnabled() {
		return backing.isWarnEnabled();
	}

	@Override
	public boolean isWarnEnabled(Marker marker) {
		return backing.isWarnEnabled(marker);
	}

	@Override
	public void warn(String msg) {
		backing.warn(msg);
		if (isWarnEnabled()) intercept(Level.WARN, msg);
	}

	@Override
	public void warn(String format, Object arg) {
		warn(format, new Object[]{arg});
	}

	@Override
	public void warn(String format, Object arg1, Object arg2) {
		warn(format, new Object[]{arg1, arg2});
	}

	@Override
	public void warn(String format, Object... arguments) {
		backing.warn(format, arguments);
		if (isWarnEnabled()) interceptFormatted(Level.WARN, format, arguments);
	}

	@Override
	public void warn(String msg, Throwable t) {
		backing.warn(msg, t);
		if (isWarnEnabled()) intercept(Level.WARN, msg, t);
	}

	@Override
	public void warn(Marker marker, String msg) {
		backing.warn(marker, msg);
		if (isWarnEnabled(marker)) intercept(Level.WARN, msg);
	}

	@Override
	public void warn(Marker marker, String format, Object arg) {
		warn(marker, format, new Object[]{arg});
	}

	@Override
	public void warn(Marker marker, String format, Object arg1, Object arg2) {
		warn(marker, format, new Object[]{arg1, arg2});
	}

	@Override
	public void warn(Marker marker, String format, Object... arguments) {
		backing.warn(marker, format, arguments);
		if (isWarnEnabled(marker)) interceptFormatted(Level.WARN, format, arguments);
	}

	@Override
	public void warn(Marker marker, String msg, Throwable t) {
		backing.warn(marker, msg, t);
		if (isWarnEnabled(marker)) intercept(Level.WARN, msg, t);
	}

	@Override
	public boolean isErrorEnabled() {
		return backing.isErrorEnabled();
	}

	@Override
	public boolean isErrorEnabled(Marker marker) {
		return backing.isErrorEnabled(marker);
	}

	@Override
	public void error(String msg) {
		backing.error(msg);
		if (isErrorEnabled()) intercept(Level.ERROR, msg);
	}

	@Override
	public void error(String format, Object arg) {
		error(format, new Object[]{arg});
	}

	@Override
	public void error(String format, Object arg1, Object arg2) {
		error(format, new Object[]{arg1, arg2});
	}

	@Override
	public void error(String format, Object... arguments) {
		backing.error(format, arguments);
		if (isErrorEnabled()) interceptFormatted(Level.ERROR, format, arguments);
	}

	@Override
	public void error(String msg, Throwable t) {
		backing.error(msg, t);
		if (isErrorEnabled()) intercept(Level.ERROR, msg, t);
	}

	@Override
	public void error(Marker marker, String msg) {
		backing.error(marker, msg);
		if (isErrorEnabled(marker)) intercept(Level.ERROR, msg);
	}

	@Override
	public void error(Marker marker, String format, Object arg) {
		error(marker, format, new Object[]{arg});
	}

	@Override
	public void error(Marker marker, String format, Object arg1, Object arg2) {
		error(marker, format, new Object[]{arg1, arg2});
	}

	@Override
	public void error(Marker marker, String format, Object... arguments) {
		backing.error(marker, format, arguments);
		if (isErrorEnabled(marker)) interceptFormatted(Level.ERROR, format, arguments);
	}

	@Override
	public void error(Marker marker, String msg, Throwable t) {
		backing.error(marker, msg, t);
		if (isErrorEnabled(marker)) intercept(Level.ERROR, msg, t);
	}

	private void interceptFormatted(Level level, String format, Object[] arguments) {
		FormattingTuple formatted = MessageFormatter.arrayFormat(format, arguments);
		if (formatted.getThrowable() == null)
			intercept(level, formatted.getMessage());
		else
			intercept(level, formatted.getMessage(), formatted.getThrowable());
	}
}
