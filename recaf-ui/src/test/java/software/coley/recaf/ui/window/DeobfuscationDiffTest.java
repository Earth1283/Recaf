package software.coley.recaf.ui.window;

import com.github.difflib.UnifiedDiffUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DeobfuscationDiffTest {
	@Test
	void identicalTextHasNoDiff() {
		assertEquals(new DeobfuscationDiff(List.of(), 0, 0), DeobfuscationDiff.between("same\n", "same\n"));
	}

	@Test
	void replacementsHaveCountsAndCanBeApplied() throws Exception {
		String before = "class Example {\n    return Integer.reverse(1);\n}\n";
		String after = "class Example {\n    return -2147483648;\n}\n";
		DeobfuscationDiff diff = DeobfuscationDiff.between(before, after);
		assertEquals(1, diff.added());
		assertEquals(1, diff.removed());
		assertTrue(diff.lines().contains("-    return Integer.reverse(1);"));
		assertTrue(diff.lines().contains("+    return -2147483648;"));
		assertEquals(Arrays.asList(after.split("\n", -1)), UnifiedDiffUtils.parseUnifiedDiff(diff.lines())
				.applyTo(Arrays.asList(before.split("\n", -1))));
	}

	@Test
	void handlesEmptyInputsAndUnicode() throws Exception {
		String text = "\u03bb = \"\ud83d\ude00\";";
		DeobfuscationDiff insertion = DeobfuscationDiff.between("", text);
		assertEquals(1, insertion.added());
		assertEquals(0, insertion.removed());
		assertEquals(List.of(text), UnifiedDiffUtils.parseUnifiedDiff(insertion.lines()).applyTo(List.of()));
		DeobfuscationDiff removal = DeobfuscationDiff.between(text, "");
		assertEquals(0, removal.added());
		assertEquals(1, removal.removed());
		assertEquals(List.of(), UnifiedDiffUtils.parseUnifiedDiff(removal.lines()).applyTo(List.of(text)));
	}

	@Test
	void separatesDistantChangesAndOmitsUnchangedMiddle() {
		String before = IntStream.range(0, 100).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
		String after = before.replace("line 5\n", "changed 5\n").replace("line 95\n", "changed 95\n");
		DeobfuscationDiff diff = DeobfuscationDiff.between(before, after);
		assertEquals(2, diff.lines().stream().filter(line -> line.startsWith("@@")).count());
		assertFalse(diff.lines().contains(" line 50"));
		assertEquals(2, diff.added());
		assertEquals(2, diff.removed());
	}

	@Test
	void finalNewlineChangesAreVisible() {
		assertFalse(DeobfuscationDiff.between("line", "line\n").lines().isEmpty());
	}
}
