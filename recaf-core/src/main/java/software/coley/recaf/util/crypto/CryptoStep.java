package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One operation in a {@link Recipe}.
 */
public sealed interface CryptoStep {
	/**
	 * @param input
	 * 		Step input.
	 *
	 * @return Step output.
	 *
	 * @throws CryptoException
	 * 		When the operation fails.
	 */
	@Nonnull
	byte[] apply(@Nonnull byte[] input) throws CryptoException;

	/**
	 * @return Short description of the step.
	 */
	@Nonnull
	String describe();

	/**
	 * Encodes or decodes with a {@link Codec}.
	 *
	 * @param codec
	 * 		Codec to use.
	 * @param encode
	 * 		{@code true} to encode, {@code false} to decode.
	 */
	record CodecStep(@Nonnull Codec codec, boolean encode) implements CryptoStep {
		@Nonnull
		@Override
		public byte[] apply(@Nonnull byte[] input) throws CryptoException {
			return encode ? codec.encode(input) : codec.decode(input);
		}

		@Nonnull
		@Override
		public String describe() {
			if (codec.isSymmetric())
				return codec.displayName();
			if (codec.isCompression())
				return (encode ? "Compress " : "Decompress ") + codec.displayName();
			return codec.displayName() + (encode ? " encode" : " decode");
		}
	}

	/**
	 * Encrypts or decrypts with a symmetric cipher.
	 *
	 * @param spec
	 * 		Cipher configuration.
	 * @param encrypt
	 * 		{@code true} to encrypt, {@code false} to decrypt.
	 */
	record CipherStep(@Nonnull SymmetricCipherSpec spec, boolean encrypt) implements CryptoStep {
		@Nonnull
		@Override
		public byte[] apply(@Nonnull byte[] input) throws CryptoException {
			return SymmetricCipher.process(spec, input, encrypt);
		}

		@Nonnull
		@Override
		public String describe() {
			return (encrypt ? "Encrypt " : "Decrypt ") + spec.describe();
		}
	}

	/**
	 * Encrypts or decrypts with RSA.
	 *
	 * @param key
	 * 		PEM or DER key bytes.
	 * @param padding
	 * 		RSA padding.
	 * @param encrypt
	 * 		{@code true} to encrypt, {@code false} to decrypt.
	 */
	record RsaStep(@Nonnull byte[] key, @Nonnull RsaCipher.Padding padding, boolean encrypt) implements CryptoStep {
		/**
		 * Copies the key.
		 */
		public RsaStep {
			key = key.clone();
		}

		@Nonnull
		@Override
		public byte[] apply(@Nonnull byte[] input) throws CryptoException {
			return RsaCipher.process(key, padding, input, encrypt);
		}

		@Nonnull
		@Override
		public String describe() {
			return (encrypt ? "RSA encrypt" : "RSA decrypt") + " (" + padding + ")";
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof RsaStep other && Arrays.equals(key, other.key) && padding == other.padding && encrypt == other.encrypt;
		}

		@Override
		public int hashCode() {
			return Objects.hash(Arrays.hashCode(key), padding, encrypt);
		}
	}

	/**
	 * Combines each byte with a repeating key: {@code out[i] = in[i] OP key[i % key.length]}.
	 *
	 * @param key
	 * 		Repeating key.
	 * @param operation
	 * 		Byte operation.
	 */
	record ByteKeyStep(@Nonnull byte[] key, @Nonnull Operation operation) implements CryptoStep {
		/**
		 * Byte operations.
		 */
		public enum Operation {
			XOR("XOR"),
			ADD("Add"),
			SUBTRACT("Subtract");

			private final String displayName;

			Operation(@Nonnull String displayName) {
				this.displayName = displayName;
			}

			@Override
			public String toString() {
				return displayName;
			}
		}

		/**
		 * Copies the key.
		 */
		public ByteKeyStep {
			key = key.clone();
		}

		@Nonnull
		@Override
		public byte[] apply(@Nonnull byte[] input) throws CryptoException {
			if (key.length == 0)
				throw new CryptoException("Key is empty");
			byte[] out = new byte[input.length];
			for (int i = 0; i < input.length; i++) {
				int k = key[i % key.length];
				out[i] = switch (operation) {
					case XOR -> (byte) (input[i] ^ k);
					case ADD -> (byte) (input[i] + k);
					case SUBTRACT -> (byte) (input[i] - k);
				};
			}
			return out;
		}

		@Nonnull
		@Override
		public String describe() {
			return operation + " with key " + HexFormat.of().formatHex(key);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof ByteKeyStep other && Arrays.equals(key, other.key) && operation == other.operation;
		}

		@Override
		public int hashCode() {
			return Objects.hash(Arrays.hashCode(key), operation);
		}
	}

	/**
	 * Hashes the input, or computes its HMAC when a key is given.
	 *
	 * @param algorithm
	 * 		Digest or checksum.
	 * @param hmacKey
	 * 		HMAC key, or {@code null} for a plain hash.
	 */
	record HashStep(@Nonnull HashAlgorithm algorithm, @Nullable byte[] hmacKey) implements CryptoStep {
		/**
		 * Copies the key.
		 */
		public HashStep {
			hmacKey = hmacKey == null ? null : hmacKey.clone();
		}

		@Nonnull
		@Override
		public byte[] apply(@Nonnull byte[] input) throws CryptoException {
			return hmacKey == null ? algorithm.hash(input) : algorithm.hmac(hmacKey, input);
		}

		@Nonnull
		@Override
		public String describe() {
			return hmacKey == null ? algorithm.displayName() : "HMAC-" + algorithm.displayName();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof HashStep other && algorithm == other.algorithm && Arrays.equals(hmacKey, other.hmacKey);
		}

		@Override
		public int hashCode() {
			return Objects.hash(algorithm, Arrays.hashCode(hmacKey));
		}
	}
}
