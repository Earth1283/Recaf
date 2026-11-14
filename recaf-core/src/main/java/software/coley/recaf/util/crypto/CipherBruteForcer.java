package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Tries many cipher configurations against one ciphertext and ranks the outputs by how much they look like plaintext.
 * <p>
 * Covers every combination of algorithm, mode, padding, key derivation, key size and IV source for the given keys,
 * plus XOR, so a key found in decompiled code can be matched to its cipher setup without knowing that setup.
 */
public final class CipherBruteForcer {
	private static final double MIN_SCORE = 0.2;
	private static final List<CipherMode> COMMON_MODES = List.of(CipherMode.ECB, CipherMode.CBC, CipherMode.CTR, CipherMode.CFB, CipherMode.GCM);

	private CipherBruteForcer() {}

	/**
	 * @param input
	 * 		Ciphertext.
	 * @param keys
	 * 		Key material candidates: raw keys or passphrases.
	 * @param iv
	 * 		Known IV, or {@code null}.
	 * @param salt
	 * 		Known salt for PBKDF2 and scrypt, or {@code null}.
	 * @param iterations
	 * 		Known PBKDF2 iteration count, or {@code 0} to try common counts.
	 * @param exhaustive
	 * 		{@code true} to try every algorithm, mode and derivation, {@code false} for the ones common in the wild.
	 * @param tryInputDecodings
	 * 		{@code true} to also try the input after Base64 and hex decoding.
	 * @param includeXor
	 * 		{@code true} to try repeating-key XOR with each key and single-byte XOR with every byte.
	 * @param maxResults
	 * 		Maximum number of candidates to return.
	 */
	public record Options(@Nonnull byte[] input, @Nonnull List<byte[]> keys, @Nullable byte[] iv, @Nullable byte[] salt,
	                      int iterations, boolean exhaustive, boolean tryInputDecodings, boolean includeXor,
	                      int maxResults) {}

	/**
	 * @param score
	 * 		Plaintext likelihood.
	 * @param kind
	 * 		What the output appears to be.
	 * @param steps
	 * 		Steps that reproduce the output from the original input.
	 * @param output
	 * 		Output bytes.
	 */
	public record Candidate(double score, @Nonnull String kind, @Nonnull List<CryptoStep> steps, @Nonnull byte[] output) {
		/**
		 * @return Description of the steps.
		 */
		@Nonnull
		public String describe() {
			return steps.stream().map(CryptoStep::describe).collect(Collectors.joining(" → "));
		}
	}

	/**
	 * Progress callback.
	 */
	public interface Listener {
		/**
		 * @param done
		 * 		Attempts made.
		 * @param total
		 * 		Total attempts planned.
		 */
		void onProgress(long done, long total);
	}

	/**
	 * @param options
	 * 		Search options.
	 * @param listener
	 * 		Progress callback, called from worker threads.
	 * @param cancelled
	 * 		Polled to stop the search early.
	 *
	 * @return Best candidates, highest score first.
	 */
	@Nonnull
	public static List<Candidate> run(@Nonnull Options options, @Nonnull Listener listener, @Nonnull BooleanSupplier cancelled) {
		List<Attempt> attempts = plan(options);
		long total = attempts.size();
		AtomicLong done = new AtomicLong();
		Map<ByteBuffer, Candidate> best = new ConcurrentHashMap<>();
		attempts.parallelStream().forEach(attempt -> {
			if (cancelled.getAsBoolean())
				return;
			try {
				byte[] output = attempt.execute();
				if (attempt.isRedundant(output))
					return;
				DataInspector.Score score = DataInspector.score(output);
				if (attempt instanceof StepAttempt && attempt.input().readable()) {
					// Byte-wise XOR/add of data that is already readable mostly yields readable noise.
					score = new DataInspector.Score(score.value() * 0.8, score.kind());
					if (score.value() <= attempt.input().score())
						return;
				}
				if (score.value() >= MIN_SCORE) {
					Candidate candidate = new Candidate(score.value(), score.kind(), attempt.displaySteps(), output);
					best.merge(ByteBuffer.wrap(output), candidate, CipherBruteForcer::better);
				}
			} catch (CryptoException | RuntimeException ignored) {
				// A failed attempt (bad padding, wrong length) just means the configuration is wrong.
			}
			long count = done.incrementAndGet();
			if (count % 256 == 0 || count == total)
				listener.onProgress(count, total);
		});

		PriorityQueue<Candidate> queue = new PriorityQueue<>(Comparator.comparingDouble(Candidate::score));
		for (Candidate candidate : best.values()) {
			queue.add(candidate);
			if (queue.size() > options.maxResults())
				queue.poll();
		}
		List<Candidate> results = new ArrayList<>(queue);
		results.sort(Comparator.comparingDouble(Candidate::score).reversed()
				.thenComparingInt(c -> c.steps().size())
				.thenComparing(Candidate::describe));
		return results;
	}

	/**
	 * Keeps the higher scoring candidate, or the simpler one when two configurations produce the same output.
	 */
	@Nonnull
	private static Candidate better(@Nonnull Candidate a, @Nonnull Candidate b) {
		if (a.score() != b.score())
			return a.score() > b.score() ? a : b;
		int complexity = Integer.compare(complexity(a), complexity(b));
		if (complexity != 0)
			return complexity < 0 ? a : b;
		return a.describe().compareTo(b.describe()) <= 0 ? a : b;
	}

	private static int complexity(@Nonnull Candidate candidate) {
		int value = candidate.steps().size() * 100;
		for (CryptoStep step : candidate.steps())
			if (step instanceof CryptoStep.CipherStep cipher)
				value += cipher.spec().kdf().type().ordinal() * 10 + cipher.spec().mode().ordinal()
						+ cipher.spec().ivSource().ordinal();
		return value;
	}

	/**
	 * @param options
	 * 		Search options.
	 *
	 * @return Number of attempts the options produce.
	 */
	public static int countAttempts(@Nonnull Options options) {
		return plan(options).size();
	}

	@Nonnull
	private static List<Attempt> plan(@Nonnull Options options) {
		List<InputVariant> inputs = new ArrayList<>();
		inputs.add(new InputVariant(options.input(), List.of()));
		if (options.tryInputDecodings()) {
			for (Codec codec : List.of(Codec.BASE64, Codec.HEX)) {
				if (!DataInspector.guessEncodings(options.input()).contains(codec))
					continue;
				try {
					byte[] decoded = codec.decode(options.input());
					if (decoded.length > 0)
						inputs.add(new InputVariant(decoded, List.of(new CryptoStep.CodecStep(codec, false))));
				} catch (CryptoException ignored) {
					// Not decodable with this codec.
				}
			}
		}

		List<Attempt> attempts = new ArrayList<>();
		Map<DerivationKey, LazyDerivation> derivationCache = new HashMap<>();
		for (InputVariant input : inputs) {
			if (options.includeXor())
				planXor(options, input, attempts);
			for (byte[] key : options.keys()) {
				if (key.length == 0)
					continue;
				for (CipherAlgorithm algorithm : CipherAlgorithm.values()) {
					if (!options.exhaustive() && !algorithm.isCommon())
						continue;
					planAlgorithm(options, input, key, algorithm, derivationCache, attempts);
				}
			}
		}
		return attempts;
	}

	private static void planXor(@Nonnull Options options, @Nonnull InputVariant input, @Nonnull List<Attempt> attempts) {
		List<CryptoStep.ByteKeyStep.Operation> operations = options.exhaustive() ?
				List.of(CryptoStep.ByteKeyStep.Operation.values()) : List.of(CryptoStep.ByteKeyStep.Operation.XOR);
		for (CryptoStep.ByteKeyStep.Operation operation : operations) {
			for (byte[] key : options.keys())
				if (key.length > 0)
					attempts.add(new StepAttempt(input, new CryptoStep.ByteKeyStep(key, operation)));
			for (int b = 1; b < 256; b++)
				attempts.add(new StepAttempt(input, new CryptoStep.ByteKeyStep(new byte[]{(byte) b}, operation)));
		}
	}

	private static void planAlgorithm(@Nonnull Options options, @Nonnull InputVariant input, @Nonnull byte[] key,
	                                  @Nonnull CipherAlgorithm algorithm,
	                                  @Nonnull Map<DerivationKey, LazyDerivation> derivationCache,
	                                  @Nonnull List<Attempt> attempts) {
		byte[] data = input.data();
		boolean salted = data.length >= 16 && Arrays.equals(data, 0, 8, SymmetricCipher.OPENSSL_MAGIC, 0, 8);
		byte[] headerSalt = salted ? Arrays.copyOfRange(data, 8, 16) : null;
		byte[] unsalted = salted ? Arrays.copyOfRange(data, 16, data.length) : data;

		List<CipherMode> modes;
		if (!algorithm.isBlockCipher())
			modes = List.of(CipherMode.ECB);
		else
			modes = (options.exhaustive() ? Arrays.asList(CipherMode.values()) : COMMON_MODES).stream()
					.filter(m -> m.supports(algorithm)).toList();

		for (KeyDerivation kdf : derivations(options, salted)) {
			byte[] execData = kdf.type().isOpenSsl() ? unsalted : data;
			byte[] effectiveSalt = kdf.type().isOpenSsl() && salted ? headerSalt : kdf.salt();
			for (int keySize : keySizes(options, algorithm, kdf, key)) {
				for (CipherMode mode : modes) {
					int ivSize = algorithm.isBlockCipher() ? mode.defaultIvSize(algorithm) : algorithm.nonceSize();
					DerivationKey cacheKey = new DerivationKey(kdf, keySize, ivSize, ByteBuffer.wrap(key),
							effectiveSalt == null ? null : ByteBuffer.wrap(effectiveSalt));
					LazyDerivation derivation = derivationCache.computeIfAbsent(cacheKey, LazyDerivation::new);
					for (PaddingScheme padding : paddings(algorithm, mode, execData.length)) {
						for (IvSource ivSource : ivSources(options, ivSize, kdf)) {
							byte[] explicitIv = ivSource == IvSource.EXPLICIT ? options.iv() : null;
							SymmetricCipherSpec display = new SymmetricCipherSpec(algorithm, mode, padding, key, kdf,
									kdf.type() == KeyDerivation.Type.RAW ? 0 : keySize, ivSource, explicitIv, 0, 128, null);
							attempts.add(new CipherAttempt(input, execData, derivation, display));
						}
					}
				}
			}
		}
	}

	@Nonnull
	private static List<KeyDerivation> derivations(@Nonnull Options options, boolean salted) {
		boolean exhaustive = options.exhaustive();
		List<KeyDerivation> list = new ArrayList<>();
		list.add(KeyDerivation.RAW);
		list.add(KeyDerivation.FIT);
		List<HashAlgorithm> digests = exhaustive ?
				List.of(HashAlgorithm.MD5, HashAlgorithm.SHA1, HashAlgorithm.SHA256, HashAlgorithm.SHA384, HashAlgorithm.SHA512, HashAlgorithm.SHA3_256) :
				List.of(HashAlgorithm.MD5, HashAlgorithm.SHA1, HashAlgorithm.SHA256, HashAlgorithm.SHA512);
		for (HashAlgorithm digest : digests)
			list.add(KeyDerivation.digest(digest, 1));
		if (salted || exhaustive) {
			list.add(KeyDerivation.openSsl(HashAlgorithm.MD5, null));
			list.add(KeyDerivation.openSsl(HashAlgorithm.SHA256, null));
			list.add(KeyDerivation.openSslPbkdf2(HashAlgorithm.SHA256, null, options.iterations() > 0 ? options.iterations() : 10_000));
		}
		if (options.salt() != null || exhaustive) {
			List<Integer> counts = options.iterations() > 0 ? List.of(options.iterations()) :
					exhaustive ? List.of(1000, 10_000, 65_536) : List.of(1000, 65_536);
			List<HashAlgorithm> prfs = exhaustive ?
					List.of(HashAlgorithm.SHA1, HashAlgorithm.SHA256, HashAlgorithm.SHA512) :
					List.of(HashAlgorithm.SHA1, HashAlgorithm.SHA256);
			for (HashAlgorithm prf : prfs)
				for (int count : counts)
					list.add(KeyDerivation.pbkdf2(prf, options.salt(), count));
		}
		return list;
	}

	@Nonnull
	private static int[] keySizes(@Nonnull Options options, @Nonnull CipherAlgorithm algorithm,
	                              @Nonnull KeyDerivation kdf, @Nonnull byte[] key) {
		if (kdf.type() == KeyDerivation.Type.RAW)
			return algorithm.isValidKeySize(key.length) ? new int[]{key.length} : new int[0];
		int[] sizes = algorithm.keySizes();
		if (!options.exhaustive() && algorithm != CipherAlgorithm.AES)
			return new int[]{sizes[0]};
		return sizes;
	}

	@Nonnull
	private static List<PaddingScheme> paddings(@Nonnull CipherAlgorithm algorithm, @Nonnull CipherMode mode, int length) {
		if (!algorithm.isBlockCipher() || !mode.usesPadding())
			return List.of(PaddingScheme.NONE);
		if (length == 0 || length % algorithm.blockSize() != 0)
			return List.of();
		return List.of(PaddingScheme.PKCS7, PaddingScheme.NONE);
	}

	@Nonnull
	private static List<IvSource> ivSources(@Nonnull Options options, int ivSize, @Nonnull KeyDerivation kdf) {
		if (ivSize == 0)
			return List.of(IvSource.ZERO);
		List<IvSource> sources = new ArrayList<>(List.of(IvSource.ZERO, IvSource.PREFIX, IvSource.KEY));
		if (options.iv() != null)
			sources.add(IvSource.EXPLICIT);
		if (kdf.type().derivesIv())
			sources.add(IvSource.DERIVED);
		return sources;
	}

	private record InputVariant(@Nonnull byte[] data, @Nonnull List<CryptoStep> steps, double score, boolean readable) {
		private InputVariant(@Nonnull byte[] data, @Nonnull List<CryptoStep> steps) {
			this(data, steps, DataInspector.score(data));
		}

		private InputVariant(@Nonnull byte[] data, @Nonnull List<CryptoStep> steps, @Nonnull DataInspector.Score score) {
			this(data, steps, score.value(), score.isText());
		}
	}

	private record DerivationKey(@Nonnull KeyDerivation kdf, int keySize, int ivSize, @Nonnull ByteBuffer key, @Nullable ByteBuffer salt) {}

	/**
	 * Derives a key on first use and shares it between every attempt with the same derivation inputs,
	 * so slow derivations such as PBKDF2 run once, on a worker thread, and only if the search is not cancelled.
	 */
	private static final class LazyDerivation {
		private final DerivationKey params;
		private KeyDerivation.DerivedKey value;
		private CryptoException error;

		private LazyDerivation(@Nonnull DerivationKey params) {
			this.params = params;
		}

		@Nonnull
		synchronized KeyDerivation.DerivedKey get() throws CryptoException {
			if (value == null && error == null) {
				byte[] salt = params.salt() == null ? null : toArray(params.salt());
				try {
					value = params.kdf().derive(toArray(params.key()), params.keySize(), params.ivSize(), salt);
				} catch (CryptoException ex) {
					error = ex;
				}
			}
			if (error != null)
				throw error;
			return value;
		}

		@Nonnull
		private static byte[] toArray(@Nonnull ByteBuffer buffer) {
			byte[] out = new byte[buffer.remaining()];
			buffer.duplicate().get(out);
			return out;
		}
	}

	private sealed interface Attempt {
		@Nonnull
		InputVariant input();

		@Nonnull
		byte[] execute() throws CryptoException;

		@Nonnull
		List<CryptoStep> displaySteps();

		/**
		 * @param output
		 * 		Output of this attempt.
		 *
		 * @return {@code true} when another attempt covers the same result in a more accurate form.
		 */
		default boolean isRedundant(@Nonnull byte[] output) {
			return false;
		}
	}

	private record StepAttempt(@Nonnull InputVariant input, @Nonnull CryptoStep step) implements Attempt {
		@Nonnull
		@Override
		public byte[] execute() throws CryptoException {
			return step.apply(input.data());
		}

		@Nonnull
		@Override
		public List<CryptoStep> displaySteps() {
			List<CryptoStep> steps = new ArrayList<>(input.steps());
			steps.add(step);
			return steps;
		}
	}

	private record CipherAttempt(@Nonnull InputVariant input, @Nonnull byte[] data,
	                             @Nonnull LazyDerivation derivation, @Nonnull SymmetricCipherSpec display) implements Attempt {
		@Nonnull
		@Override
		public byte[] execute() throws CryptoException {
			KeyDerivation.DerivedKey derived = derivation.get();
			boolean fromKdf = display.ivSource() == IvSource.DERIVED;
			SymmetricCipherSpec exec = new SymmetricCipherSpec(display.algorithm(), display.mode(), display.padding(),
					derived.key(), KeyDerivation.RAW, 0, fromKdf ? IvSource.EXPLICIT : display.ivSource(),
					fromKdf ? derived.iv() : display.iv(), 0, 128, null);
			return SymmetricCipher.decrypt(exec, data);
		}

		@Nonnull
		@Override
		public List<CryptoStep> displaySteps() {
			List<CryptoStep> steps = new ArrayList<>(input.steps());
			steps.add(new CryptoStep.CipherStep(display, false));
			return steps;
		}

		@Override
		public boolean isRedundant(@Nonnull byte[] output) {
			// Unpadded output ending in valid PKCS#7 padding is the PKCS#7 attempt's plaintext plus its padding.
			if (display.padding() != PaddingScheme.NONE || !display.algorithm().isBlockCipher()
					|| !display.mode().usesPadding() || output.length == 0)
				return false;
			int pad = output[output.length - 1] & 0xFF;
			if (pad == 0 || pad > display.algorithm().blockSize() || pad > output.length)
				return false;
			for (int i = output.length - pad; i < output.length; i++)
				if ((output[i] & 0xFF) != pad)
					return false;
			return true;
		}
	}
}
