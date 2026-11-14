package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.generators.SCrypt;
import org.bouncycastle.crypto.params.KeyParameter;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * How key bytes are produced from the key material the user supplies <i>(raw key, passphrase, key file)</i>.
 *
 * @param type
 * 		Derivation function.
 * @param digest
 * 		Digest for {@link Type#DIGEST}, {@link Type#PBKDF2}, {@link Type#OPENSSL_EVP} and {@link Type#OPENSSL_PBKDF2}.
 * @param salt
 * 		Salt, or {@code null} for none. OpenSSL derivations read the salt from a {@code Salted__} header when present.
 * @param iterations
 * 		Iteration count. For {@link Type#DIGEST} this is how many times the digest is applied. For {@link Type#SCRYPT} it is the cost {@code N}.
 * @param blockSize
 * 		Scrypt block size {@code r}.
 * @param parallelism
 * 		Scrypt parallelism {@code p}.
 */
public record KeyDerivation(@Nonnull Type type, @Nonnull HashAlgorithm digest, @Nullable byte[] salt,
                            int iterations, int blockSize, int parallelism) {
	/** Key bytes are used exactly as given. */
	public static final KeyDerivation RAW = new KeyDerivation(Type.RAW, HashAlgorithm.SHA256, null, 1, 0, 0);
	/** Key bytes are zero-padded or truncated to the key size, like {@code Arrays.copyOf(key, 16)}. */
	public static final KeyDerivation FIT = new KeyDerivation(Type.FIT, HashAlgorithm.SHA256, null, 1, 0, 0);

	/**
	 * Derivation functions.
	 */
	public enum Type {
		RAW("Raw key"),
		FIT("Raw key, zero-padded/truncated"),
		DIGEST("Digest of passphrase"),
		PBKDF2("PBKDF2"),
		OPENSSL_EVP("OpenSSL EVP_BytesToKey"),
		OPENSSL_PBKDF2("OpenSSL -pbkdf2"),
		SCRYPT("scrypt");

		private final String displayName;

		Type(@Nonnull String displayName) {
			this.displayName = displayName;
		}

		/**
		 * @return {@code true} when the derivation also yields an IV.
		 */
		public boolean derivesIv() {
			return this == PBKDF2 || this == OPENSSL_EVP || this == OPENSSL_PBKDF2 || this == SCRYPT;
		}

		/**
		 * @return {@code true} for OpenSSL formats, which use the {@code Salted__} header.
		 */
		public boolean isOpenSsl() {
			return this == OPENSSL_EVP || this == OPENSSL_PBKDF2;
		}

		@Override
		public String toString() {
			return displayName;
		}
	}

	/**
	 * @param type
	 * 		Derivation function.
	 * @param digest
	 * 		Digest to use.
	 * @param salt
	 * 		Salt, or {@code null} for none.
	 * @param iterations
	 * 		Iteration count, or scrypt cost.
	 * @param blockSize
	 * 		Scrypt block size.
	 * @param parallelism
	 * 		Scrypt parallelism.
	 */
	public KeyDerivation {
		Objects.requireNonNull(type);
		Objects.requireNonNull(digest);
		if (salt != null)
			salt = salt.clone();
	}

	/**
	 * @param digest
	 * 		Digest to apply to the passphrase.
	 * @param rounds
	 * 		Times to apply the digest.
	 *
	 * @return Derivation of {@code digest(passphrase)}, zero-padded or truncated to the key size.
	 */
	@Nonnull
	public static KeyDerivation digest(@Nonnull HashAlgorithm digest, int rounds) {
		return new KeyDerivation(Type.DIGEST, digest, null, rounds, 0, 0);
	}

	/**
	 * @param digest
	 * 		HMAC digest.
	 * @param salt
	 * 		Salt.
	 * @param iterations
	 * 		Iteration count.
	 *
	 * @return PBKDF2 derivation.
	 */
	@Nonnull
	public static KeyDerivation pbkdf2(@Nonnull HashAlgorithm digest, @Nullable byte[] salt, int iterations) {
		return new KeyDerivation(Type.PBKDF2, digest, salt, iterations, 0, 0);
	}

	/**
	 * @param digest
	 * 		Digest, {@code openssl enc -md}. OpenSSL defaults to MD5 before 1.1.0 and SHA-256 after.
	 * @param salt
	 * 		Salt used when the data has no {@code Salted__} header.
	 *
	 * @return OpenSSL {@code EVP_BytesToKey} derivation with one iteration.
	 */
	@Nonnull
	public static KeyDerivation openSsl(@Nonnull HashAlgorithm digest, @Nullable byte[] salt) {
		return new KeyDerivation(Type.OPENSSL_EVP, digest, salt, 1, 0, 0);
	}

	/**
	 * @param digest
	 * 		HMAC digest. OpenSSL defaults to SHA-256.
	 * @param salt
	 * 		Salt used when the data has no {@code Salted__} header.
	 * @param iterations
	 * 		Iteration count. OpenSSL defaults to 10000.
	 *
	 * @return OpenSSL {@code -pbkdf2} derivation.
	 */
	@Nonnull
	public static KeyDerivation openSslPbkdf2(@Nonnull HashAlgorithm digest, @Nullable byte[] salt, int iterations) {
		return new KeyDerivation(Type.OPENSSL_PBKDF2, digest, salt, iterations, 0, 0);
	}

	/**
	 * @param salt
	 * 		Salt.
	 * @param cost
	 * 		CPU/memory cost {@code N}, a power of two.
	 * @param blockSize
	 * 		Block size {@code r}.
	 * @param parallelism
	 * 		Parallelism {@code p}.
	 *
	 * @return Scrypt derivation.
	 */
	@Nonnull
	public static KeyDerivation scrypt(@Nullable byte[] salt, int cost, int blockSize, int parallelism) {
		return new KeyDerivation(Type.SCRYPT, HashAlgorithm.SHA256, salt, cost, blockSize, parallelism);
	}

	/**
	 * @param salt
	 * 		New salt.
	 *
	 * @return Copy of this derivation with the given salt.
	 */
	@Nonnull
	public KeyDerivation withSalt(@Nullable byte[] salt) {
		return new KeyDerivation(type, digest, salt, iterations, blockSize, parallelism);
	}

	/**
	 * @param material
	 * 		Key material: raw key bytes, or a passphrase.
	 * @param keySize
	 * 		Wanted key size in bytes. Ignored for {@link Type#RAW}.
	 * @param ivSize
	 * 		Wanted IV size in bytes, for derivations that {@link Type#derivesIv() derive an IV}.
	 * @param effectiveSalt
	 * 		Salt to use, which for OpenSSL formats may come from the data header instead of {@link #salt()}.
	 *
	 * @return Derived key, and IV when the derivation produces one.
	 *
	 * @throws CryptoException
	 * 		When derivation parameters are invalid.
	 */
	@Nonnull
	public DerivedKey derive(@Nonnull byte[] material, int keySize, int ivSize, @Nullable byte[] effectiveSalt) throws CryptoException {
		byte[] saltBytes = effectiveSalt == null ? new byte[0] : effectiveSalt;
		try {
			return switch (type) {
				case RAW -> new DerivedKey(material.clone(), null);
				case FIT -> new DerivedKey(Arrays.copyOf(material, keySize), null);
				case DIGEST -> {
					if (digest.isChecksum())
						throw new CryptoException("Checksums cannot derive keys");
					byte[] hash = material;
					for (int i = 0; i < Math.max(1, iterations); i++)
						hash = digest.hash(hash);
					yield new DerivedKey(Arrays.copyOf(hash, keySize), null);
				}
				case PBKDF2, OPENSSL_PBKDF2 -> {
					if (digest.isChecksum())
						throw new CryptoException("Checksums cannot back PBKDF2");
					if (iterations < 1)
						throw new CryptoException("PBKDF2 needs at least one iteration");
					PKCS5S2ParametersGenerator generator = new PKCS5S2ParametersGenerator(digest.newDigest());
					generator.init(material, saltBytes, iterations);
					byte[] out = ((KeyParameter) generator.generateDerivedParameters((keySize + ivSize) * 8)).getKey();
					yield split(out, keySize, ivSize);
				}
				case OPENSSL_EVP -> {
					if (digest.isChecksum())
						throw new CryptoException("Checksums cannot back EVP_BytesToKey");
					yield split(evpBytesToKey(digest.newDigest(), material, effectiveSalt, Math.max(1, iterations), keySize + ivSize), keySize, ivSize);
				}
				case SCRYPT -> {
					if (iterations < 2 || Integer.bitCount(iterations) != 1)
						throw new CryptoException("scrypt cost must be a power of two above 1");
					if (blockSize < 1 || parallelism < 1)
						throw new CryptoException("scrypt block size and parallelism must be positive");
					yield split(SCrypt.generate(material, saltBytes, iterations, blockSize, parallelism, keySize + ivSize), keySize, ivSize);
				}
			};
		} catch (IllegalArgumentException ex) {
			throw new CryptoException("Key derivation failed: " + ex.getMessage(), ex);
		}
	}

	@Nonnull
	private static DerivedKey split(@Nonnull byte[] out, int keySize, int ivSize) {
		byte[] key = Arrays.copyOfRange(out, 0, keySize);
		byte[] iv = ivSize > 0 ? Arrays.copyOfRange(out, keySize, keySize + ivSize) : null;
		return new DerivedKey(key, iv);
	}

	@Nonnull
	private static byte[] evpBytesToKey(@Nonnull Digest digest, @Nonnull byte[] password, @Nullable byte[] salt,
	                                    int count, int length) {
		byte[] out = new byte[length];
		byte[] previous = new byte[0];
		int written = 0;
		byte[] buffer = new byte[digest.getDigestSize()];
		while (written < length) {
			digest.update(previous, 0, previous.length);
			digest.update(password, 0, password.length);
			if (salt != null)
				digest.update(salt, 0, Math.min(8, salt.length));
			digest.doFinal(buffer, 0);
			for (int i = 1; i < count; i++) {
				digest.update(buffer, 0, buffer.length);
				digest.doFinal(buffer, 0);
			}
			int copy = Math.min(buffer.length, length - written);
			System.arraycopy(buffer, 0, out, written, copy);
			written += copy;
			previous = buffer.clone();
		}
		return out;
	}

	/**
	 * @return Short description, such as {@code PBKDF2-SHA-256 x10000}.
	 */
	@Nonnull
	public String describe() {
		String saltText = salt == null ? "" : ", salt " + HexFormat.of().formatHex(salt);
		return switch (type) {
			case RAW -> "raw key";
			case FIT -> "raw key (fitted)";
			case DIGEST -> digest.displayName() + "(passphrase)" + (iterations > 1 ? " x" + iterations : "");
			case PBKDF2 -> "PBKDF2-" + digest.displayName() + " x" + iterations + saltText;
			case OPENSSL_EVP -> "OpenSSL EVP " + digest.displayName() + saltText;
			case OPENSSL_PBKDF2 -> "OpenSSL PBKDF2-" + digest.displayName() + " x" + iterations + saltText;
			case SCRYPT -> "scrypt N=" + iterations + " r=" + blockSize + " p=" + parallelism + saltText;
		};
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof KeyDerivation other && type == other.type && digest == other.digest
				&& Arrays.equals(salt, other.salt) && iterations == other.iterations
				&& blockSize == other.blockSize && parallelism == other.parallelism;
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, digest, Arrays.hashCode(salt), iterations, blockSize, parallelism);
	}

	@Override
	public byte[] salt() {
		return salt == null ? null : salt.clone();
	}

	/**
	 * Output of {@link #derive}.
	 *
	 * @param key
	 * 		Derived key.
	 * @param iv
	 * 		Derived IV, or {@code null} when the derivation does not produce one.
	 */
	public record DerivedKey(@Nonnull byte[] key, @Nullable byte[] iv) {}
}
