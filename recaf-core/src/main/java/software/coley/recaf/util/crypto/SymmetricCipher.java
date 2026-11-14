package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.DataLengthException;
import org.bouncycastle.crypto.DefaultBufferedBlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.modes.AEADCipher;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.modes.CCMBlockCipher;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.modes.CTSBlockCipher;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.modes.EAXBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.OCBBlockCipher;
import org.bouncycastle.crypto.modes.OFBBlockCipher;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.paddings.BlockCipherPadding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Runs {@link SymmetricCipherSpec symmetric cipher configurations}.
 */
public final class SymmetricCipher {
	/** Header OpenSSL writes before the 8-byte salt in {@code openssl enc} output. */
	public static final byte[] OPENSSL_MAGIC = "Salted__".getBytes(StandardCharsets.US_ASCII);
	private static final SecureRandom RANDOM = new SecureRandom();

	private SymmetricCipher() {}

	/**
	 * @param spec
	 * 		Cipher configuration.
	 * @param input
	 * 		Plaintext to encrypt.
	 *
	 * @return Ciphertext, including any OpenSSL header or prefixed IV the configuration calls for.
	 *
	 * @throws CryptoException
	 * 		When the configuration or input is invalid.
	 */
	@Nonnull
	public static byte[] encrypt(@Nonnull SymmetricCipherSpec spec, @Nonnull byte[] input) throws CryptoException {
		return process(spec, input, true);
	}

	/**
	 * @param spec
	 * 		Cipher configuration.
	 * @param input
	 * 		Ciphertext to decrypt, including any OpenSSL header or prefixed IV.
	 *
	 * @return Plaintext.
	 *
	 * @throws CryptoException
	 * 		When the configuration is invalid, or the input fails padding or authentication checks.
	 */
	@Nonnull
	public static byte[] decrypt(@Nonnull SymmetricCipherSpec spec, @Nonnull byte[] input) throws CryptoException {
		return process(spec, input, false);
	}

	/**
	 * @param spec
	 * 		Cipher configuration.
	 * @param input
	 * 		Input data.
	 * @param encrypt
	 * 		{@code true} to encrypt, {@code false} to decrypt.
	 *
	 * @return Output data.
	 *
	 * @throws CryptoException
	 * 		When the configuration or input is invalid.
	 */
	@Nonnull
	public static byte[] process(@Nonnull SymmetricCipherSpec spec, @Nonnull byte[] input, boolean encrypt) throws CryptoException {
		CipherAlgorithm algorithm = spec.algorithm();
		KeyDerivation kdf = spec.kdf();
		if (algorithm.isBlockCipher() && !spec.mode().supports(algorithm))
			throw new CryptoException(spec.mode() + " requires a 128-bit block cipher");

		byte[] data = input;
		byte[] header = new byte[0];
		byte[] salt = kdf.salt();
		if (kdf.type().isOpenSsl()) {
			if (!encrypt && startsWith(data, OPENSSL_MAGIC) && data.length >= 16) {
				salt = Arrays.copyOfRange(data, 8, 16);
				data = Arrays.copyOfRange(data, 16, data.length);
			} else if (encrypt) {
				if (salt == null)
					salt = random(8);
				header = concat(OPENSSL_MAGIC, salt);
			}
		}

		int ivSize = spec.effectiveIvSize();
		KeyDerivation.DerivedKey derived = kdf.derive(spec.keyMaterial(), spec.effectiveKeySize(), ivSize, salt);
		byte[] key = derived.key();
		if (!algorithm.isValidKeySize(key.length))
			throw new CryptoException(algorithm.displayName() + " does not accept a " + key.length + "-byte key");

		byte[] iv = null;
		if (ivSize > 0) {
			iv = switch (spec.ivSource()) {
				case ZERO -> new byte[ivSize];
				case EXPLICIT -> {
					byte[] explicit = spec.iv();
					if (explicit == null || explicit.length == 0)
						throw new CryptoException("No IV given");
					yield explicit;
				}
				case PREFIX -> {
					if (encrypt) {
						byte[] generated = random(ivSize);
						header = concat(header, generated);
						yield generated;
					}
					if (data.length < ivSize)
						throw new CryptoException("Input is shorter than the " + ivSize + "-byte IV prefix");
					byte[] prefix = Arrays.copyOf(data, ivSize);
					data = Arrays.copyOfRange(data, ivSize, data.length);
					yield prefix;
				}
				case KEY -> Arrays.copyOf(key, ivSize);
				case DERIVED -> {
					if (derived.iv() == null)
						throw new CryptoException(kdf.type() + " does not derive an IV");
					yield derived.iv();
				}
			};
		}

		try {
			byte[] output = run(spec, key, iv, data, encrypt);
			return encrypt ? concat(header, output) : output;
		} catch (InvalidCipherTextException ex) {
			throw new CryptoException(spec.mode().isAead() || algorithm.isAead() ?
					"Authentication failed: " + ex.getMessage() : "Bad padding: " + ex.getMessage(), ex);
		} catch (DataLengthException | IllegalArgumentException | IllegalStateException ex) {
			throw new CryptoException(String.valueOf(ex.getMessage()), ex);
		}
	}

	@Nonnull
	private static byte[] run(@Nonnull SymmetricCipherSpec spec, @Nonnull byte[] key, @Nullable byte[] iv,
	                          @Nonnull byte[] data, boolean encrypt) throws InvalidCipherTextException {
		CipherAlgorithm algorithm = spec.algorithm();
		KeyParameter keyParam = new KeyParameter(key);
		if (algorithm.isAead()) {
			ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
			cipher.init(encrypt, new AEADParameters(keyParam, spec.tagBits(), iv, spec.aad()));
			return runAead(cipher, data);
		}
		if (!algorithm.isBlockCipher()) {
			StreamCipher cipher = algorithm.newStreamCipher();
			cipher.init(encrypt, iv == null ? keyParam : new ParametersWithIV(keyParam, iv));
			return runStream(cipher, data);
		}

		BlockCipher engine = algorithm.newBlockCipher();
		int blockBits = algorithm.blockSize() * 8;
		CipherParameters params = iv == null ? keyParam : new ParametersWithIV(keyParam, iv);
		return switch (spec.mode()) {
			case ECB -> runBuffered(buffered(engine, spec.padding()), keyParam, data, encrypt);
			case CBC -> runBuffered(buffered(CBCBlockCipher.newInstance(engine), spec.padding()), params, data, encrypt);
			case CTS -> runBuffered(new CTSBlockCipher(CBCBlockCipher.newInstance(engine)), params, data, encrypt);
			case CFB -> initStream((StreamCipher) CFBBlockCipher.newInstance(engine, blockBits), params, data, encrypt);
			case CFB8 -> initStream((StreamCipher) CFBBlockCipher.newInstance(engine, 8), params, data, encrypt);
			case OFB -> initStream(new OFBBlockCipher(engine, blockBits), params, data, encrypt);
			case CTR -> initStream(SICBlockCipher.newInstance(engine), params, data, encrypt);
			case GCM -> initAead(GCMBlockCipher.newInstance(engine), spec, keyParam, iv, data, encrypt);
			case CCM -> initAead(CCMBlockCipher.newInstance(engine), spec, keyParam, iv, data, encrypt);
			case EAX -> initAead(new EAXBlockCipher(engine), spec, keyParam, iv, data, encrypt);
			case OCB -> initAead(new OCBBlockCipher(engine, algorithm.newBlockCipher()), spec, keyParam, iv, data, encrypt);
		};
	}

	@Nonnull
	private static BufferedBlockCipher buffered(@Nonnull BlockCipher cipher, @Nonnull PaddingScheme padding) {
		BlockCipherPadding blockPadding = padding.newPadding();
		return blockPadding == null ? new DefaultBufferedBlockCipher(cipher) : new PaddedBufferedBlockCipher(cipher, blockPadding);
	}

	@Nonnull
	private static byte[] runBuffered(@Nonnull BufferedBlockCipher cipher, @Nonnull CipherParameters params,
	                                  @Nonnull byte[] data, boolean encrypt) throws InvalidCipherTextException {
		cipher.init(encrypt, params);
		byte[] out = new byte[cipher.getOutputSize(data.length)];
		int length = cipher.processBytes(data, 0, data.length, out, 0);
		length += cipher.doFinal(out, length);
		return length == out.length ? out : Arrays.copyOf(out, length);
	}

	@Nonnull
	private static byte[] initStream(@Nonnull StreamCipher cipher, @Nonnull CipherParameters params,
	                                 @Nonnull byte[] data, boolean encrypt) {
		cipher.init(encrypt, params);
		return runStream(cipher, data);
	}

	@Nonnull
	private static byte[] runStream(@Nonnull StreamCipher cipher, @Nonnull byte[] data) {
		byte[] out = new byte[data.length];
		cipher.processBytes(data, 0, data.length, out, 0);
		return out;
	}

	@Nonnull
	private static byte[] initAead(@Nonnull AEADCipher cipher, @Nonnull SymmetricCipherSpec spec,
	                               @Nonnull KeyParameter key, @Nullable byte[] iv,
	                               @Nonnull byte[] data, boolean encrypt) throws InvalidCipherTextException {
		int tagBits = cipher instanceof EAXBlockCipher ? Math.min(spec.tagBits(), spec.algorithm().blockSize() * 8) : spec.tagBits();
		cipher.init(encrypt, new AEADParameters(key, tagBits, iv, spec.aad()));
		return runAead(cipher, data);
	}

	@Nonnull
	private static byte[] runAead(@Nonnull AEADCipher cipher, @Nonnull byte[] data) throws InvalidCipherTextException {
		byte[] out = new byte[cipher.getOutputSize(data.length)];
		int length = cipher.processBytes(data, 0, data.length, out, 0);
		length += cipher.doFinal(out, length);
		return length == out.length ? out : Arrays.copyOf(out, length);
	}

	private static boolean startsWith(@Nonnull byte[] data, @Nonnull byte[] prefix) {
		return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
	}

	@Nonnull
	private static byte[] concat(@Nonnull byte[] a, @Nonnull byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	@Nonnull
	private static byte[] random(int length) {
		byte[] out = new byte[length];
		RANDOM.nextBytes(out);
		return out;
	}
}
