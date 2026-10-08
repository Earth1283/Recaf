package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses which {@code paper-api} version best matches a plugin's declared {@code api-version}.
 * <p>
 * Two version schemes exist in the repository:
 * <ul>
 *     <li>{@code 1.21.4-R0.1-SNAPSHOT}, used up to and including 1.21.x</li>
 *     <li>{@code 26.1.2.build.74-stable}, used from 26.1 onward, with an {@code alpha}, {@code beta}, or
 *     {@code stable} channel</li>
 * </ul>
 * Pre-releases such as {@code 1.21.9-rc1-R0.1-SNAPSHOT} and {@code 26.3-pre-2.build.0-alpha} are never chosen.
 */
public final class PaperApiVersionSelector {
	private static final Pattern LEGACY = Pattern.compile("^(\\d+(?:\\.\\d+)*)-R\\d+(?:\\.\\d+)*-SNAPSHOT$");
	private static final Pattern BUILD = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.build\\.(\\d+)-(alpha|beta|stable)$");
	/** Highest numeric version first, then the better channel, then the newer build. */
	private static final Comparator<Candidate> ORDER = (a, b) -> {
		int cmp = compareNumeric(a.numeric, b.numeric);
		if (cmp == 0) cmp = Integer.compare(a.channel, b.channel);
		if (cmp == 0) cmp = Integer.compare(a.build, b.build);
		return cmp;
	};

	private PaperApiVersionSelector() {}

	/**
	 * @param availableVersions
	 * 		Versions published in the repository.
	 * @param requestedApiVersion
	 * 		The {@code api-version} from the plugin manifest, such as {@code 1.21}, or {@code null} for the latest.
	 *
	 * @return The best version to use. If nothing exists for the requested family <i>(old versions that are no longer
	 * published)</i> the oldest version newer than the request is used. Empty if nothing suitable exists.
	 */
	@Nonnull
	public static Optional<String> select(@Nonnull Collection<String> availableVersions, @Nullable String requestedApiVersion) {
		List<Candidate> candidates = new ArrayList<>();
		for (String version : availableVersions) {
			Candidate candidate = parse(version);
			if (candidate != null)
				candidates.add(candidate);
		}
		if (candidates.isEmpty())
			return Optional.empty();

		int[] requested = requestedApiVersion == null ? null : parseNumeric(requestedApiVersion);
		if (requested == null)
			return candidates.stream().max(ORDER).map(Candidate::version);

		// Prefer the newest version within the requested family. 1.21 matches 1.21, 1.21.1, ... 1.21.11
		Optional<Candidate> inFamily = candidates.stream()
				.filter(c -> startsWith(c.numeric, requested))
				.max(ORDER);
		if (inFamily.isPresent())
			return inFamily.map(Candidate::version);

		// Old families are not published. Those APIs only grow, so the oldest newer one is the closest.
		List<Candidate> newer = candidates.stream().filter(c -> compareNumeric(c.numeric, requested) > 0).toList();
		Optional<Candidate> lowest = newer.stream().min((a, b) -> compareNumeric(a.numeric, b.numeric));
		if (lowest.isEmpty())
			return Optional.empty();
		return newer.stream()
				.filter(c -> compareNumeric(c.numeric, lowest.get().numeric) == 0)
				.max(ORDER)
				.map(Candidate::version);
	}

	@Nullable
	private static Candidate parse(@Nonnull String version) {
		Matcher legacy = LEGACY.matcher(version);
		if (legacy.matches())
			return new Candidate(version, parseNumeric(legacy.group(1)), 3, 0);
		Matcher build = BUILD.matcher(version);
		if (build.matches()) {
			int channel = switch (build.group(3)) {
				case "stable" -> 3;
				case "beta" -> 2;
				default -> 1;
			};
			return new Candidate(version, parseNumeric(build.group(1)), channel, Integer.parseInt(build.group(2)));
		}
		return null;
	}

	@Nullable
	private static int[] parseNumeric(@Nonnull String text) {
		String[] parts = text.trim().split("\\.");
		int[] numbers = new int[parts.length];
		try {
			for (int i = 0; i < parts.length; i++)
				numbers[i] = Integer.parseInt(parts[i]);
		} catch (NumberFormatException ex) {
			return null;
		}

		// 1.21.0 and 1.21 are the same release. Drop trailing zeros so they compare as equal.
		int length = numbers.length;
		while (length > 1 && numbers[length - 1] == 0)
			length--;
		return length == numbers.length ? numbers : java.util.Arrays.copyOf(numbers, length);
	}

	private static boolean startsWith(@Nonnull int[] version, @Nonnull int[] prefix) {
		if (prefix.length > version.length)
			return false;
		for (int i = 0; i < prefix.length; i++)
			if (version[i] != prefix[i])
				return false;
		return true;
	}

	private static int compareNumeric(@Nonnull int[] a, @Nonnull int[] b) {
		int length = Math.max(a.length, b.length);
		for (int i = 0; i < length; i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y)
				return Integer.compare(x, y);
		}
		return 0;
	}

	private record Candidate(@Nonnull String version, @Nonnull int[] numeric, int channel, int build) {}
}
