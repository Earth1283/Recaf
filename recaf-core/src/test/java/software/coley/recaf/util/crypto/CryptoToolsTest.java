package software.coley.recaf.util.crypto;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the crypto tool engine in {@link software.coley.recaf.util.crypto}.
 */
class CryptoToolsTest {
	private static final byte[] PLAIN = "The quick brown fox jumps over the lazy dog, twice!".getBytes(StandardCharsets.UTF_8);
	private static final HexFormat HEX = HexFormat.of();

	private static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] bytes(int length, long seed) {
		byte[] out = new byte[length];
		new Random(seed).nextBytes(out);
		return out;
	}

	@Nested
	class Formats {
		@Test
		void parsesLenientHex() throws CryptoException {
			assertArrayEquals(new byte[]{0x0A, (byte) 0xFF, 0x10}, ByteFormat.HEX.parse("0x0a, 0xFF 10"));
			assertArrayEquals(new byte[]{0x0A, (byte) 0xFF}, ByteFormat.HEX.parse("\\x0a\\xff"));
			assertThrows(CryptoException.class, () -> ByteFormat.HEX.parse("abc"));
		}

		@Test
		void parsesBase64EitherAlphabetWithoutPadding() throws CryptoException {
			assertArrayEquals(utf8("hello"), ByteFormat.BASE64.parse("aGVsbG8"));
			assertArrayEquals(new byte[]{(byte) 0xFB, (byte) 0xFF}, ByteFormat.BASE64.parse("-_8="));
		}

		@Test
		void parsesDecompiledByteArrays() throws CryptoException {
			assertArrayEquals(new byte[]{1, -2, 0x7F, (byte) 0xFF},
					ByteFormat.BYTE_ARRAY.parse("new byte[]{1, -2, (byte) 0x7F, (byte)255}"));
			assertEquals("{1, -2}", ByteFormat.BYTE_ARRAY.format(new byte[]{1, -2}));
			assertThrows(CryptoException.class, () -> ByteFormat.BYTE_ARRAY.parse("{1, 300}"));
		}

		@Test
		void latin1KeepsLowByteOfEveryChar() {
			assertArrayEquals(new byte[]{(byte) 0xE9, 0x41, 0x34}, ByteFormat.toLatin1("éAሴ"));
		}
	}

	@Nested
	class Codecs {
		@ParameterizedTest
		@EnumSource(Codec.class)
		void roundTripsBinary(Codec codec) throws CryptoException {
			byte[] data = codec == Codec.JAVA_ESCAPE ? utf8("line\n\"quoted\"\tü€") : bytes(300, codec.ordinal());
			assertArrayEquals(data, codec.decode(codec.encode(data)), codec.name());
		}

		@Test
		void knownVectors() throws CryptoException {
			assertEquals("aGVsbG8=", new String(Codec.BASE64.encode(utf8("hello"))));
			assertEquals("MZXW6YTBOI======", new String(Codec.BASE32.encode(utf8("foobar"))));
			assertEquals("StV1DL6CwTryKyV", new String(Codec.BASE58.encode(utf8("hello world"))));
			assertArrayEquals(new byte[]{0, 0, 1}, Codec.BASE58.decode(utf8("112")));
			assertEquals("<~9jqo^~>", new String(Codec.ASCII85.encode(utf8("Man "))));
			assertArrayEquals(utf8("a b/c"), Codec.URL.decode(utf8("a+b%2Fc")));
			assertArrayEquals(utf8("Aé\n"), Codec.JAVA_ESCAPE.decode(utf8("\\u0041\\u00e9\\n")));
			assertArrayEquals(utf8("Uryyb"), Codec.ROT13.encode(utf8("Hello")));
		}

		@Test
		void rejectsMalformedInput() {
			assertThrows(CryptoException.class, () -> Codec.BASE32.decode(utf8("not base32!")));
			assertThrows(CryptoException.class, () -> Codec.GZIP.decode(utf8("not gzip")));
			assertThrows(CryptoException.class, () -> Codec.URL.decode(utf8("%G1")));
		}
	}

	@Nested
	class Hashes {
		@Test
		void knownVectors() throws CryptoException {
			assertEquals("900150983cd24fb0d6963f7d28e17f72", HEX.formatHex(HashAlgorithm.MD5.hash(utf8("abc"))));
			assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
					HEX.formatHex(HashAlgorithm.SHA256.hash(utf8("abc"))));
			assertEquals("cbf43926", HEX.formatHex(HashAlgorithm.CRC32.hash(utf8("123456789"))));
			assertEquals("e3069283", HEX.formatHex(HashAlgorithm.CRC32C.hash(utf8("123456789"))));
			assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
					HEX.formatHex(HashAlgorithm.SHA256.hmac(utf8("key"), utf8("The quick brown fox jumps over the lazy dog"))));
		}

		@ParameterizedTest
		@EnumSource(HashAlgorithm.class)
		void everyAlgorithmMatchesItsDigestSize(HashAlgorithm algorithm) {
			byte[] hash = algorithm.hash(PLAIN);
			if (algorithm.isChecksum())
				assertEquals(4, hash.length);
			else
				assertEquals(algorithm.newDigest().getDigestSize(), hash.length);
		}

		@Test
		void checksumsRejectHmac() {
			assertThrows(CryptoException.class, () -> HashAlgorithm.CRC32.hmac(utf8("k"), PLAIN));
		}
	}

	@Nested
	class Symmetric {
		@Test
		void aesCbcMatchesJce() throws Exception {
			byte[] key = bytes(16, 1);
			byte[] iv = bytes(16, 2);
			Cipher jce = Cipher.getInstance("AES/CBC/PKCS5Padding");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
			byte[] ct = jce.doFinal(PLAIN);

			SymmetricCipherSpec spec = SymmetricCipherSpec.of(CipherAlgorithm.AES, CipherMode.CBC, PaddingScheme.PKCS7, key, IvSource.EXPLICIT, iv);
			assertArrayEquals(PLAIN, SymmetricCipher.decrypt(spec, ct));
			assertArrayEquals(ct, SymmetricCipher.encrypt(spec, PLAIN));
		}

		@Test
		void aesGcmMatchesJce() throws Exception {
			byte[] key = bytes(32, 3);
			byte[] iv = bytes(12, 4);
			byte[] aad = utf8("header");
			Cipher jce = Cipher.getInstance("AES/GCM/NoPadding");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
			jce.updateAAD(aad);
			byte[] ct = jce.doFinal(PLAIN);

			SymmetricCipherSpec spec = new SymmetricCipherSpec(CipherAlgorithm.AES, CipherMode.GCM, PaddingScheme.NONE,
					key, KeyDerivation.RAW, 0, IvSource.EXPLICIT, iv, 0, 128, aad);
			assertArrayEquals(PLAIN, SymmetricCipher.decrypt(spec, ct));

			ct[3] ^= 1;
			CryptoException ex = assertThrows(CryptoException.class, () -> SymmetricCipher.decrypt(spec, ct));
			assertTrue(ex.getMessage().startsWith("Authentication failed"));
		}

		@Test
		void desedeEcbBlowfishCtrAndRc4MatchJce() throws Exception {
			byte[] desKey = bytes(24, 5);
			Cipher jce = Cipher.getInstance("DESede/ECB/PKCS5Padding");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(desKey, "DESede"));
			assertArrayEquals(PLAIN, SymmetricCipher.decrypt(
					SymmetricCipherSpec.of(CipherAlgorithm.DESEDE, CipherMode.ECB, PaddingScheme.PKCS7, desKey, IvSource.ZERO, null),
					jce.doFinal(PLAIN)));

			byte[] bfKey = bytes(16, 6);
			byte[] bfIv = bytes(8, 7);
			jce = Cipher.getInstance("Blowfish/CTR/NoPadding");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(bfKey, "Blowfish"), new IvParameterSpec(bfIv));
			assertArrayEquals(PLAIN, SymmetricCipher.decrypt(
					SymmetricCipherSpec.of(CipherAlgorithm.BLOWFISH, CipherMode.CTR, PaddingScheme.NONE, bfKey, IvSource.EXPLICIT, bfIv),
					jce.doFinal(PLAIN)));

			byte[] rc4Key = utf8("Secret");
			jce = Cipher.getInstance("ARCFOUR");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rc4Key, "ARCFOUR"));
			assertArrayEquals(PLAIN, SymmetricCipher.decrypt(
					SymmetricCipherSpec.of(CipherAlgorithm.RC4, CipherMode.ECB, PaddingScheme.NONE, rc4Key, IvSource.ZERO, null),
					jce.doFinal(PLAIN)));
		}

		@Test
		void chaCha20Poly1305MatchesJce() throws Exception {
			byte[] key = bytes(32, 8);
			byte[] nonce = bytes(12, 9);
			Cipher jce = Cipher.getInstance("ChaCha20-Poly1305");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
			assertArrayEquals(PLAIN, SymmetricCipher.decrypt(
					SymmetricCipherSpec.of(CipherAlgorithm.CHACHA20_POLY1305, CipherMode.ECB, PaddingScheme.NONE, key, IvSource.EXPLICIT, nonce),
					jce.doFinal(PLAIN)));
		}

		@Test
		void everyAlgorithmAndModeRoundTrips() throws CryptoException {
			List<String> failures = new ArrayList<>();
			for (CipherAlgorithm algorithm : CipherAlgorithm.values()) {
				List<CipherMode> modes = algorithm.isBlockCipher() ?
						Arrays.stream(CipherMode.values()).filter(m -> m.supports(algorithm)).toList() : List.of(CipherMode.ECB);
				for (CipherMode mode : modes) {
					for (int keySize : algorithm.keySizes()) {
						SymmetricCipherSpec spec = SymmetricCipherSpec.of(algorithm, mode, PaddingScheme.PKCS7,
								bytes(keySize, keySize), IvSource.PREFIX, null);
						try {
							byte[] ct = SymmetricCipher.encrypt(spec, PLAIN);
							if (!Arrays.equals(PLAIN, SymmetricCipher.decrypt(spec, ct)))
								failures.add(spec.describe() + ": mismatch");
						} catch (CryptoException ex) {
							failures.add(spec.describe() + ": " + ex.getMessage());
						}
					}
				}
			}
			assertEquals(List.of(), failures);
		}

		@Test
		void everyPaddingRoundTrips() throws CryptoException {
			for (PaddingScheme padding : PaddingScheme.values()) {
				byte[] data = padding == PaddingScheme.NONE ? Arrays.copyOf(PLAIN, 48) : PLAIN;
				SymmetricCipherSpec spec = SymmetricCipherSpec.of(CipherAlgorithm.AES, CipherMode.CBC, padding,
						bytes(16, 10), IvSource.ZERO, null);
				byte[] out = SymmetricCipher.decrypt(spec, SymmetricCipher.encrypt(spec, data));
				if (padding == PaddingScheme.ZERO_BYTE)
					assertArrayEquals(data, Arrays.copyOf(out, data.length), padding.name());
				else
					assertArrayEquals(data, out, padding.name());
			}
		}

		@Test
		void rejectsInvalidConfigurations() {
			assertThrows(CryptoException.class, () -> SymmetricCipher.decrypt(
					SymmetricCipherSpec.of(CipherAlgorithm.AES, CipherMode.CBC, PaddingScheme.PKCS7, new byte[5], IvSource.ZERO, null), new byte[16]));
			assertThrows(CryptoException.class, () -> SymmetricCipher.decrypt(
					SymmetricCipherSpec.of(CipherAlgorithm.DES, CipherMode.GCM, PaddingScheme.NONE, new byte[8], IvSource.ZERO, null), new byte[16]));
			assertThrows(CryptoException.class, () -> SymmetricCipher.decrypt(
					SymmetricCipherSpec.of(CipherAlgorithm.AES, CipherMode.ECB, PaddingScheme.NONE, new byte[16], IvSource.ZERO, null), new byte[15]));
		}

		@Test
		void ivSameAsKeyAndDigestKeys() throws Exception {
			byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(utf8("passphrase")), 16);
			Cipher jce = Cipher.getInstance("AES/CBC/PKCS5Padding");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
			SymmetricCipherSpec spec = new SymmetricCipherSpec(CipherAlgorithm.AES, CipherMode.CBC, PaddingScheme.PKCS7,
					utf8("passphrase"), KeyDerivation.digest(HashAlgorithm.SHA256, 1), 16, IvSource.KEY, null, 0, 128, null);
			assertArrayEquals(PLAIN, SymmetricCipher.decrypt(spec, jce.doFinal(PLAIN)));
		}
	}

	@Nested
	class Derivation {
		@Test
		void pbkdf2MatchesJce() throws Exception {
			byte[] salt = bytes(16, 11);
			byte[] jce = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
					.generateSecret(new PBEKeySpec("hunter2".toCharArray(), salt, 1000, 256)).getEncoded();
			KeyDerivation.DerivedKey derived = KeyDerivation.pbkdf2(HashAlgorithm.SHA256, salt, 1000).derive(utf8("hunter2"), 32, 0, salt);
			assertArrayEquals(jce, derived.key());
		}

		@Test
		void decryptsOpenSslWithExplicitSalt() throws CryptoException {
			byte[] ct = Base64.getDecoder().decode("Oq8gX/Af5vVTL7kui0efv6oZ84nWArHbTLzW/Bcaj4s=");
			SymmetricCipherSpec spec = new SymmetricCipherSpec(CipherAlgorithm.AES, CipherMode.CBC, PaddingScheme.PKCS7,
					utf8("secret"), KeyDerivation.openSsl(HashAlgorithm.MD5, HEX.parseHex("0102030405060708")), 32,
					IvSource.DERIVED, null, 0, 128, null);
			assertEquals("hello openssl world", new String(SymmetricCipher.decrypt(spec, ct), StandardCharsets.UTF_8));
		}

		@Test
		void decryptsOpenSslSaltedHeader() throws CryptoException {
			byte[] ct = Base64.getDecoder().decode("U2FsdGVkX19vFZqTdS2ilfargIUDjJz31+4xnmFtxVM+quWsvjJf7JNbzaMHpvhG");
			SymmetricCipherSpec spec = new SymmetricCipherSpec(CipherAlgorithm.AES, CipherMode.CBC, PaddingScheme.PKCS7,
					utf8("secret"), KeyDerivation.openSsl(HashAlgorithm.SHA256, null), 32, IvSource.DERIVED, null, 0, 128, null);
			assertEquals("hello openssl world", new String(SymmetricCipher.decrypt(spec, ct), StandardCharsets.UTF_8));
		}

		@Test
		void decryptsOpenSslPbkdf2() throws CryptoException {
			byte[] ct = Base64.getDecoder().decode("U2FsdGVkX18DafsY2We9EaSaIxC8r6iKRbcVzyXn+2BkWnl98r4eFUHWczUZY3bh");
			SymmetricCipherSpec spec = new SymmetricCipherSpec(CipherAlgorithm.AES, CipherMode.CBC, PaddingScheme.PKCS7,
					utf8("secret"), KeyDerivation.openSslPbkdf2(HashAlgorithm.SHA256, null, 10_000), 32, IvSource.DERIVED, null, 0, 128, null);
			assertEquals("hello openssl world", new String(SymmetricCipher.decrypt(spec, ct), StandardCharsets.UTF_8));
		}

		@Test
		void openSslEncryptionRoundTripsThroughHeader() throws CryptoException {
			SymmetricCipherSpec spec = new SymmetricCipherSpec(CipherAlgorithm.AES, CipherMode.CBC, PaddingScheme.PKCS7,
					utf8("pw"), KeyDerivation.openSsl(HashAlgorithm.SHA256, null), 16, IvSource.DERIVED, null, 0, 128, null);
			byte[] ct = SymmetricCipher.encrypt(spec, PLAIN);
			assertArrayEquals(SymmetricCipher.OPENSSL_MAGIC, Arrays.copyOf(ct, 8));
			assertArrayEquals(PLAIN, SymmetricCipher.decrypt(spec, ct));
		}

		@Test
		void scryptRejectsBadCost() {
			assertThrows(CryptoException.class, () -> KeyDerivation.scrypt(null, 1000, 8, 1).derive(utf8("x"), 16, 0, null));
		}
	}

	@Nested
	class Rsa {
		@Test
		void decryptsJcePkcs1AndOaepWithPemKeys() throws Exception {
			KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
			byte[] privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
			byte[] publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());

			Cipher jce = Cipher.getInstance("RSA/ECB/PKCS1Padding");
			jce.init(Cipher.ENCRYPT_MODE, pair.getPublic());
			assertArrayEquals(PLAIN, RsaCipher.process(privatePem, RsaCipher.Padding.PKCS1, jce.doFinal(PLAIN), false));

			jce = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
			jce.init(Cipher.ENCRYPT_MODE, pair.getPublic());
			assertArrayEquals(PLAIN, RsaCipher.process(privatePem, RsaCipher.Padding.OAEP_SHA1, jce.doFinal(PLAIN), false));

			jce = Cipher.getInstance("RSA/ECB/PKCS1Padding");
			jce.init(Cipher.ENCRYPT_MODE, pair.getPrivate());
			byte[] signedLike = jce.doFinal(PLAIN);
			assertArrayEquals(PLAIN, RsaCipher.process(publicPem, RsaCipher.Padding.PKCS1, signedLike, false));

			byte[] base64Der = Base64.getEncoder().encode(pair.getPublic().getEncoded());
			byte[] ours = RsaCipher.process(base64Der, RsaCipher.Padding.PKCS1, PLAIN, true);
			jce = Cipher.getInstance("RSA/ECB/PKCS1Padding");
			jce.init(Cipher.DECRYPT_MODE, pair.getPrivate());
			assertArrayEquals(PLAIN, jce.doFinal(ours));
		}

		@Test
		void rejectsNonKeys() {
			assertThrows(CryptoException.class, () -> RsaCipher.parseKey(utf8("definitely not a key")));
		}

		private static byte[] pem(String type, byte[] der) {
			return ("-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type + "-----\n")
					.getBytes(StandardCharsets.US_ASCII);
		}
	}

	@Nested
	class Recipes {
		@Test
		void chainsDecodeDecryptDecompress() throws Exception {
			ByteArrayOutputStream gz = new ByteArrayOutputStream();
			try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
				out.write(PLAIN);
			}
			byte[] key = bytes(16, 12);
			SymmetricCipherSpec spec = SymmetricCipherSpec.of(CipherAlgorithm.AES, CipherMode.CTR, PaddingScheme.NONE, key, IvSource.PREFIX, null);
			byte[] encoded = Base64.getEncoder().encode(SymmetricCipher.encrypt(spec, gz.toByteArray()));

			Recipe.Result result = Recipe.run(List.of(
					new CryptoStep.CodecStep(Codec.BASE64, false),
					new CryptoStep.CipherStep(spec, false),
					new CryptoStep.CodecStep(Codec.GZIP, false)), encoded);
			assertTrue(result.isSuccess(), result.error());
			assertArrayEquals(PLAIN, result.output());
		}

		@Test
		void stopsAtFailingStep() {
			Recipe.Result result = Recipe.run(List.of(
					new CryptoStep.CodecStep(Codec.HEX, false),
					new CryptoStep.CodecStep(Codec.BASE64, true)), utf8("zz"));
			assertFalse(result.isSuccess());
			assertEquals(1, result.steps().size());
			assertNotNull(result.error());
		}

		@Test
		void byteKeyOperationsInvert() throws CryptoException {
			byte[] key = utf8("k3y");
			byte[] added = new CryptoStep.ByteKeyStep(key, CryptoStep.ByteKeyStep.Operation.ADD).apply(PLAIN);
			assertArrayEquals(PLAIN, new CryptoStep.ByteKeyStep(key, CryptoStep.ByteKeyStep.Operation.SUBTRACT).apply(added));
			byte[] xored = new CryptoStep.ByteKeyStep(key, CryptoStep.ByteKeyStep.Operation.XOR).apply(PLAIN);
			assertArrayEquals(PLAIN, new CryptoStep.ByteKeyStep(key, CryptoStep.ByteKeyStep.Operation.XOR).apply(xored));
		}
	}

	@Nested
	class BruteForce {
		@Test
		void findsAesWithHashedPassphraseAndPrefixedIv() throws Exception {
			byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(utf8("hunter2")), 16);
			byte[] iv = bytes(16, 13);
			Cipher jce = Cipher.getInstance("AES/CBC/PKCS5Padding");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
			byte[] ct = jce.doFinal(PLAIN);
			byte[] input = Base64.getEncoder().encode(concat(iv, ct));

			List<CipherBruteForcer.Candidate> results = CipherBruteForcer.run(new CipherBruteForcer.Options(
					input, List.of(utf8("hunter2")), null, null, 0, false, true, true, 20), (d, t) -> {}, () -> false);
			assertFalse(results.isEmpty());
			CipherBruteForcer.Candidate best = results.getFirst();
			assertArrayEquals(PLAIN, best.output(), best.describe());
			assertTrue(best.describe().contains("SHA-1(passphrase)"), best.describe());
			assertTrue(best.describe().contains("IV prefixed"), best.describe());

			Recipe.Result replay = Recipe.run(best.steps(), input);
			assertArrayEquals(PLAIN, replay.output());
		}

		@Test
		void ranksSpacelessPlaintextAboveXorNoiseOfBase64Input() throws Exception {
			byte[] plain = utf8("jdbc:mysql://db.example.net:3306/licenses?user=admin&password=s3cr3t");
			byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(utf8("hunter2")), 16);
			byte[] iv = bytes(16, 18);
			Cipher jce = Cipher.getInstance("AES/CBC/PKCS5Padding");
			jce.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
			byte[] input = Base64.getEncoder().encode(concat(iv, jce.doFinal(plain)));

			List<CipherBruteForcer.Candidate> results = CipherBruteForcer.run(new CipherBruteForcer.Options(
					input, List.of(utf8("hunter2")), null, null, 0, false, true, true, 20), (d, t) -> {}, () -> false);
			assertArrayEquals(plain, results.getFirst().output(), results.getFirst().describe());
		}

		@Test
		void findsSingleByteXor() throws CryptoException {
			byte[] xored = new CryptoStep.ByteKeyStep(new byte[]{0x5A}, CryptoStep.ByteKeyStep.Operation.XOR).apply(PLAIN);
			List<CipherBruteForcer.Candidate> results = CipherBruteForcer.run(new CipherBruteForcer.Options(
					xored, List.of(), null, null, 0, false, false, true, 5), (d, t) -> {}, () -> false);
			assertArrayEquals(PLAIN, results.getFirst().output());
		}

		@Test
		void stopsWhenCancelled() {
			List<CipherBruteForcer.Candidate> results = CipherBruteForcer.run(new CipherBruteForcer.Options(
					bytes(64, 14), List.of(utf8("key")), null, null, 0, true, false, true, 5), (d, t) -> {}, () -> true);
			assertTrue(results.isEmpty());
		}

		private static byte[] concat(byte[] a, byte[] b) {
			byte[] out = Arrays.copyOf(a, a.length + b.length);
			System.arraycopy(b, 0, out, a.length, b.length);
			return out;
		}
	}

	@Nested
	class Inspection {
		@Test
		void scoresTextAboveNoise() {
			assertTrue(DataInspector.score(PLAIN).value() > 0.8);
			assertTrue(DataInspector.score(bytes(64, 15)).value() < 0.3);
			assertEquals("Java class", DataInspector.score(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0}).kind());
		}

		@Test
		void guessesEncodings() {
			assertTrue(DataInspector.guessEncodings(utf8("aGVsbG8gd29ybGQ=")).contains(Codec.BASE64));
			assertTrue(DataInspector.guessEncodings(utf8("deadbeef")).contains(Codec.HEX));
			assertTrue(DataInspector.guessEncodings(utf8("caf\\u00e9")).contains(Codec.JAVA_ESCAPE));
			assertEquals(List.of(), DataInspector.guessEncodings(bytes(32, 16)));
		}

		@Test
		void autoDecodesNestedEncodings() throws CryptoException {
			byte[] encoded = Codec.BASE64.encode(Codec.GZIP.encode(Codec.HEX.encode(PLAIN)));
			AutoDecoder.Result result = AutoDecoder.decode(encoded);
			assertTrue(result.found());
			assertArrayEquals(PLAIN, result.output());
			assertEquals(List.of(Codec.BASE64, Codec.GZIP, Codec.HEX), result.steps().stream()
					.map(s -> ((CryptoStep.CodecStep) s).codec()).toList());
			assertFalse(AutoDecoder.decode(PLAIN).found());
		}

		@Test
		void entropyBounds() {
			assertEquals(0, DataInspector.entropy(new byte[100]));
			assertTrue(DataInspector.entropy(bytes(4096, 17)) > 7.9);
		}
	}
}
