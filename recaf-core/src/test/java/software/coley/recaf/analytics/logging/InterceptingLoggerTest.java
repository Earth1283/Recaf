package software.coley.recaf.analytics.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterceptingLoggerTest {
	@Test
	void preservesFormattingAndCauseForParameterizedErrors() {
		String loggerName = getClass().getName();
		List<Entry> entries = new ArrayList<>();
		Throwable cause = new IllegalStateException("Cannot read entry");
		LogConsumer<String> recorder = new LogConsumer<>() {
			@Override
			public void accept(String name, Level level, String message) {
				accept(name, level, message, null);
			}

			@Override
			public void accept(String name, Level level, String message, Throwable throwable) {
				if (name.equals(loggerName))
					entries.add(new Entry(level, message, throwable));
			}
		};

		Logging.addLogConsumer(recorder);
		try {
			Logging.get(loggerName).error("Entry {} from {} contained \\{} and values {}",
					"a{}b", "archive.jar", new int[]{1, 2}, cause);
			assertEquals(List.of(new Entry(Level.ERROR, "Entry a{}b from archive.jar contained {} and values [1, 2]", cause)),
					entries);
		} finally {
			Logging.removeLogConsumer(recorder);
		}
	}

	private record Entry(Level level, String message, Throwable cause) {}
}
