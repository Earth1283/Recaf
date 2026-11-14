package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds a chain of keyless decodings <i>(such as Base64 then GZip)</i> that turns data into the most plaintext-like output.
 */
public final class AutoDecoder {
	private static final int MAX_DEPTH = 5;
	private static final double MIN_GAIN = 0.02;

	private AutoDecoder() {}

	/**
	 * @param data
	 * 		Encoded data.
	 *
	 * @return Best decoding chain found, or an empty result when no decoding makes the data more readable.
	 */
	@Nonnull
	public static Result decode(@Nonnull byte[] data) {
		Search search = new Search(data);
		search.visit(data, new ArrayList<>(), 0);
		return new Result(search.bestSteps, search.bestOutput, search.bestScore);
	}

	/**
	 * @param data
	 * 		Data to decode.
	 *
	 * @return Decodings that apply to the data syntactically.
	 */
	@Nonnull
	public static List<CryptoStep> candidates(@Nonnull byte[] data) {
		List<CryptoStep> steps = new ArrayList<>();
		for (Codec codec : DataInspector.guessEncodings(data))
			steps.add(new CryptoStep.CodecStep(codec, false));
		if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B)
			steps.add(new CryptoStep.CodecStep(Codec.GZIP, false));
		if (data.length > 2 && data[0] == 0x78 && ((data[0] & 0xFF) << 8 | (data[1] & 0xFF)) % 31 == 0)
			steps.add(new CryptoStep.CodecStep(Codec.ZLIB, false));
		return steps;
	}

	/**
	 * @param steps
	 * 		Decoding chain, empty when nothing improved the input.
	 * @param output
	 * 		Output of the chain.
	 * @param score
	 * 		Plaintext score of the output.
	 */
	public record Result(@Nonnull List<CryptoStep> steps, @Nonnull byte[] output, @Nonnull DataInspector.Score score) {
		/**
		 * @return {@code true} when a decoding chain was found.
		 */
		public boolean found() {
			return !steps.isEmpty();
		}
	}

	private static final class Search {
		private List<CryptoStep> bestSteps = List.of();
		private byte[] bestOutput;
		private DataInspector.Score bestScore;

		private Search(@Nonnull byte[] input) {
			bestOutput = input;
			bestScore = score(input);
		}

		/**
		 * Compressed data scores high as a recognised format, but here it is a step to decode further, not a result.
		 */
		@Nonnull
		private static DataInspector.Score score(@Nonnull byte[] data) {
			DataInspector.Score score = DataInspector.score(data);
			if (score.kind().equals("GZip"))
				return new DataInspector.Score(0.5, score.kind());
			return score;
		}

		private void visit(@Nonnull byte[] data, @Nonnull List<CryptoStep> path, int depth) {
			if (depth >= MAX_DEPTH)
				return;
			for (CryptoStep step : candidates(data)) {
				byte[] out;
				try {
					out = step.apply(data);
				} catch (CryptoException ex) {
					continue;
				}
				if (out.length == 0 || Arrays.equals(out, data))
					continue;
				List<CryptoStep> next = new ArrayList<>(path);
				next.add(step);
				DataInspector.Score score = score(out);
				if (score.value() > bestScore.value() + MIN_GAIN) {
					bestScore = score;
					bestSteps = next;
					bestOutput = out;
				}
				visit(out, next, depth + 1);
			}
		}
	}
}
