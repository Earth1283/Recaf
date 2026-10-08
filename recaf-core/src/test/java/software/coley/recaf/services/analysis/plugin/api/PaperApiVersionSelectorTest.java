package software.coley.recaf.services.analysis.plugin.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link PaperApiVersionSelector}, using version names as published in the Paper repository.
 */
class PaperApiVersionSelectorTest {
	private static final List<String> PUBLISHED = List.of(
			"1.17.1-R0.1-SNAPSHOT",
			"1.18-R0.1-SNAPSHOT", "1.18.1-R0.1-SNAPSHOT", "1.18.2-R0.1-SNAPSHOT",
			"1.20.4-R0.1-SNAPSHOT",
			"1.21-R0.1-SNAPSHOT", "1.21.1-R0.1-SNAPSHOT", "1.21.3-R0.1-SNAPSHOT", "1.21.4-R0.1-SNAPSHOT",
			"1.21.5-no-moonrise-SNAPSHOT",
			"1.21.9-rc1-R0.1-SNAPSHOT", "1.21.9-pre2-R0.1-SNAPSHOT", "1.21.9-R0.1-SNAPSHOT",
			"1.21.10-R0.1-SNAPSHOT", "1.21.11-rc1-R0.1-SNAPSHOT", "1.21.11-R0.1-SNAPSHOT",
			"26.1.1.build.8-alpha", "26.1.1.build.25-alpha",
			"26.1.2.build.44-alpha", "26.1.2.build.5-beta", "26.1.2.build.72-stable", "26.1.2.build.74-stable",
			"26.2-rc-2.build.8-alpha", "26.2.build.46-alpha", "26.2.build.24-beta", "26.2.build.46-stable",
			"26.3-pre-2.build.0-alpha", "26.3.build.47-alpha", "26.3.build.159-beta");

	private static Optional<String> select(String requested) {
		return PaperApiVersionSelector.select(PUBLISHED, requested);
	}

	@Test
	void picksNewestInTheRequestedFamily() {
		assertEquals(Optional.of("1.21.11-R0.1-SNAPSHOT"), select("1.21"), "Numeric order, not text order: 11 > 4");
		assertEquals(Optional.of("1.18.2-R0.1-SNAPSHOT"), select("1.18"));
		assertEquals(Optional.of("1.20.4-R0.1-SNAPSHOT"), select("1.20"));
	}

	@Test
	void exactPatchRequestStaysWithinThatPatch() {
		assertEquals(Optional.of("1.21.4-R0.1-SNAPSHOT"), select("1.21.4"));
	}

	@Test
	void trailingZeroComponentsAreTheSameRelease() {
		// Mojang calls the first 1.21 release "1.21.0" but Paper publishes it as "1.21".
		assertEquals(select("1.21"), select("1.21.0"));
		assertEquals(Optional.of("1.21-R0.1-SNAPSHOT"),
				PaperApiVersionSelector.select(List.of("1.21-R0.1-SNAPSHOT", "1.20.4-R0.1-SNAPSHOT"), "1.21.0"));
	}

	@Test
	void doesNotMatchPartialNumbers() {
		// Family "1.2" must not match 1.21.x or 1.20.x
		assertEquals(Optional.of("1.17.1-R0.1-SNAPSHOT"), select("1.2"),
				"Nothing published for 1.2.*, so the oldest newer version is used");
	}

	@Test
	void ignoresPreReleases() {
		assertEquals(Optional.of("1.21.11-R0.1-SNAPSHOT"), select("1.21"));
		assertEquals(Optional.of("26.2.build.46-stable"), select("26.2"));
	}

	@Test
	void prefersBetterChannelThenNewerBuild() {
		// For 26.1.2: stable 74 beats stable 72 beats alpha 44
		assertEquals(Optional.of("26.1.2.build.74-stable"), select("26.1.2"));
		assertEquals(Optional.of("26.1.2.build.74-stable"), select("26.1"));
		// Only alpha and beta exist for 26.3
		assertEquals(Optional.of("26.3.build.159-beta"), select("26.3"));
	}

	@Test
	void fallsBackToTheOldestNewerVersionForUnpublishedFamilies() {
		assertEquals(Optional.of("1.17.1-R0.1-SNAPSHOT"), select("1.16"));
		assertEquals(Optional.of("1.17.1-R0.1-SNAPSHOT"), select("1.13"));
		assertEquals(Optional.of("1.20.4-R0.1-SNAPSHOT"), select("1.19"), "1.19 is not published, 1.20.4 is the next");
		assertEquals(Optional.empty(), select("27.0"), "Nothing newer than the request exists");
	}

	@Test
	void noRequestMeansLatest() {
		assertEquals(Optional.of("26.3.build.159-beta"), select(null));
	}

	@Test
	void junkRequestMeansLatest() {
		assertEquals(Optional.of("26.3.build.159-beta"), select("latest"));
	}

	@Test
	void emptyAndUnparseableListsGiveNothing() {
		assertEquals(Optional.empty(), PaperApiVersionSelector.select(List.of(), "1.21"));
		assertEquals(Optional.empty(), PaperApiVersionSelector.select(List.of("junk", "1.21-rc1"), null));
	}
}
