package software.coley.recaf.services.decompile.cfr;

import jakarta.annotation.Nullable;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.util.Collection;
import java.util.List;

/**
 * Cfr logging/output sinker.
 *
 * @author Matt
 */
public class SinkFactoryImpl implements OutputSinkFactory {
	private static final Logger logger = Logging.get(SinkFactoryImpl.class);
	private Throwable exception;
	private String decompile;

	@Override
	public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
		if (sinkType == SinkType.EXCEPTION && collection.contains(SinkClass.EXCEPTION_MESSAGE))
			return List.of(SinkClass.EXCEPTION_MESSAGE);
		return collection.contains(SinkClass.STRING) ? List.of(SinkClass.STRING) : List.of();
	}

	@Override
	public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
		return switch (sinkType) {
			case JAVA -> this::setDecompilation;
			case EXCEPTION -> this::handleException;
			default -> t -> {
			};
		};
	}

	private <T> void handleException(@Nullable T value) {
		if (value instanceof SinkReturns.ExceptionMessage message) {
			exception = message.getThrownException();
			if (exception == null)
				exception = new IllegalStateException(message.getMessage());
			logger.error("CFR error in '{}': {}", message.getPath(), message.getMessage(), exception);
		} else if (value != null) {
			exception = new IllegalStateException(value.toString());
			logger.error("CFR error", exception);
		}
	}

	private <T> void setDecompilation(T value) {
		decompile = value.toString();
	}

	/**
	 * @return Decompiled class content.
	 */
	@Nullable
	public String getDecompilation() {
		return decompile;
	}

	/**
	 * @return Failure reason.
	 */
	@Nullable
	public Throwable getException() {
		return exception;
	}
}
