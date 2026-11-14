package software.coley.recaf.ui.control.richtext.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link LineSearch}.
 */
class LineSearchTest {
	private static final String SOURCE = """
			class A {
			    void a() {
			        new URLClassLoader(urls);
			    }

			    void b() {
			        Runtime.getRuntime().exec(cmd);   \s
			        new URLClassLoader(other);
			    }
			}
			""";

	private static String line(LineSearch.Span span) {
		return SOURCE.substring(span.start(), span.end());
	}

	@Test
	void findsTheLineWithoutIndentationOrLineBreak() {
		int begin = SOURCE.indexOf("void b");
		LineSearch.Span span = LineSearch.find(SOURCE, begin, SOURCE.length(), List.of(".exec("));
		assertEquals("Runtime.getRuntime().exec(cmd);", line(span));
	}

	@Test
	void firstNeedleThatOccursWins() {
		int begin = SOURCE.indexOf("void b");
		LineSearch.Span span = LineSearch.find(SOURCE, begin, SOURCE.length(), List.of("missing", "new URLClassLoader", ".exec("));
		assertEquals("new URLClassLoader(other);", line(span));
	}

	@Test
	void onlySearchesInsideTheRange() {
		int begin = SOURCE.indexOf("void b");
		int end = SOURCE.indexOf("new URLClassLoader(other)");
		assertNull(LineSearch.find(SOURCE, begin, end, List.of("new URLClassLoader")));
		LineSearch.Span span = LineSearch.find(SOURCE, 0, SOURCE.indexOf("void b"), List.of("new URLClassLoader"));
		assertEquals("new URLClassLoader(urls);", line(span));
	}

	@Test
	void rejectsRangesThatDoNotFitTheText() {
		assertNull(LineSearch.find(SOURCE, -1, 10, List.of("class")));
		assertNull(LineSearch.find(SOURCE, 0, SOURCE.length() + 1, List.of("class")));
		assertNull(LineSearch.find(SOURCE, 5, 5, List.of("class")));
		assertNull(LineSearch.find(SOURCE, 0, SOURCE.length(), List.of("")));
	}
}
