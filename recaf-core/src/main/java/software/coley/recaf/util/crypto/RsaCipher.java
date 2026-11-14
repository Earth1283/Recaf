package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.encodings.OAEPEncoding;
import org.bouncycastle.crypto.encodings.PKCS1Encoding;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSA encryption and decryption with keys in PEM or DER form.
 */
public final class RsaCipher {
	private static final Pattern PEM = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s:\\-,]*?)-----END \\1-----");

	private RsaCipher() {}

	/**
	 * RSA paddings.
	 */
	public enum Padding {
		PKCS1("PKCS#1 v1.5"),
		OAEP_SHA1("OAEP (SHA-1)"),
		OAEP_SHA256("OAEP (SHA-256)"),
		NONE("None (raw RSA)");

		private final String displayName;

		Padding(@Nonnull String displayName) {
			this.displayName = displayName;
		}

		@Override
		public String toString() {
			return displayName;
		}
	}

	/**
	 * @param key
	 * 		Public or private key bytes, PEM text or DER.
	 * @param padding
	 * 		Padding.
	 * @param input
	 * 		Data to process. Data longer than one block is processed block by block.
	 * @param encrypt
	 * 		{@code true} to encrypt, {@code false} to decrypt.
	 * 		Decrypting with a public key recovers data "encrypted" with the private key, a common licensing pattern.
	 *
	 * @return Output data.
	 *
	 * @throws CryptoException
	 * 		When the key cannot be read or the data fails to decrypt.
	 */
	@Nonnull
	public static byte[] process(@Nonnull byte[] key, @Nonnull Padding padding, @Nonnull byte[] input, boolean encrypt) throws CryptoException {
		AsymmetricKeyParameter keyParam = parseKey(key);
		AsymmetricBlockCipher cipher = switch (padding) {
			case PKCS1 -> new PKCS1Encoding(new RSAEngine());
			case OAEP_SHA1 -> new OAEPEncoding(new RSAEngine(), new SHA1Digest());
			case OAEP_SHA256 -> new OAEPEncoding(new RSAEngine(), new SHA256Digest());
			case NONE -> new RSAEngine();
		};
		try {
			cipher.init(encrypt, keyParam);
			int blockSize = cipher.getInputBlockSize();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (int offset = 0; offset < input.length; offset += blockSize) {
				int length = Math.min(blockSize, input.length - offset);
				out.writeBytes(cipher.processBlock(input, offset, length));
			}
			return out.toByteArray();
		} catch (InvalidCipherTextException ex) {
			throw new CryptoException("RSA " + (encrypt ? "encryption" : "decryption") + " failed: " + ex.getMessage(), ex);
		} catch (RuntimeException ex) {
			throw new CryptoException(String.valueOf(ex.getMessage()), ex);
		}
	}

	/**
	 * @param key
	 * 		Key bytes: PEM text <i>(PKCS#8, PKCS#1, X.509 SubjectPublicKeyInfo or a certificate)</i>, or the same as DER.
	 *
	 * @return RSA key parameters.
	 *
	 * @throws CryptoException
	 * 		When the bytes are not a supported RSA key.
	 */
	@Nonnull
	public static AsymmetricKeyParameter parseKey(@Nonnull byte[] key) throws CryptoException {
		String text = new String(key, StandardCharsets.ISO_8859_1);
		Matcher matcher = PEM.matcher(text);
		if (matcher.find()) {
			String type = matcher.group(1);
			String body = matcher.group(2);
			if (body.contains("Proc-Type") || type.contains("ENCRYPTED"))
				throw new CryptoException("Encrypted PEM keys are not supported, decrypt the key first");
			byte[] der = ByteFormat.parseBase64(body);
			try {
				return switch (type) {
					case "PRIVATE KEY" -> requireRsa(PrivateKeyFactory.createKey(der));
					case "PUBLIC KEY" -> requireRsa(PublicKeyFactory.createKey(der));
					case "RSA PRIVATE KEY" -> pkcs1Private(der);
					case "RSA PUBLIC KEY" -> pkcs1Public(der);
					case "CERTIFICATE" -> requireRsa(PublicKeyFactory.createKey(Certificate.getInstance(der).getSubjectPublicKeyInfo()));
					default -> throw new CryptoException("Unsupported PEM block '" + type + "'");
				};
			} catch (CryptoException ex) {
				throw ex;
			} catch (Exception ex) {
				throw new CryptoException("Malformed " + type + ": " + ex.getMessage(), ex);
			}
		}

		byte[] der = key;
		String trimmed = text.trim();
		if (!trimmed.isEmpty() && trimmed.matches("[A-Za-z0-9+/=_\\-\\s]+") && (key.length == 0 || key[0] != 0x30)) {
			try {
				der = ByteFormat.parseBase64(trimmed);
			} catch (CryptoException ignored) {
				// Not Base64, try the bytes as DER directly.
			}
		}
		try {
			return requireRsa(PrivateKeyFactory.createKey(der));
		} catch (Exception ignored) {
			// Fall through to the next encoding.
		}
		try {
			return requireRsa(PublicKeyFactory.createKey(der));
		} catch (Exception ignored) {
			// Fall through to the next encoding.
		}
		try {
			return pkcs1Private(der);
		} catch (Exception ignored) {
			// Fall through to the next encoding.
		}
		try {
			return pkcs1Public(der);
		} catch (Exception ex) {
			throw new CryptoException("Key is not a PEM or DER encoded RSA key", ex);
		}
	}

	@Nonnull
	private static AsymmetricKeyParameter requireRsa(@Nonnull AsymmetricKeyParameter key) throws CryptoException {
		if (!(key instanceof RSAKeyParameters))
			throw new CryptoException("Key is not an RSA key (" + key.getClass().getSimpleName() + ")");
		return key;
	}

	@Nonnull
	private static AsymmetricKeyParameter pkcs1Private(@Nonnull byte[] der) throws Exception {
		RSAPrivateKey k = RSAPrivateKey.getInstance(ASN1Primitive.fromByteArray(der));
		return new RSAPrivateCrtKeyParameters(k.getModulus(), k.getPublicExponent(), k.getPrivateExponent(),
				k.getPrime1(), k.getPrime2(), k.getExponent1(), k.getExponent2(), k.getCoefficient());
	}

	@Nonnull
	private static AsymmetricKeyParameter pkcs1Public(@Nonnull byte[] der) throws Exception {
		RSAPublicKey k = RSAPublicKey.getInstance(ASN1Primitive.fromByteArray(der));
		return new RSAKeyParameters(false, k.getModulus(), k.getPublicExponent());
	}
}
