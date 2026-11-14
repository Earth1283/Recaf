package software.coley.recaf.util.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Heuristics over raw bytes: entropy, file signatures, likely encodings, and how much they look like a plaintext.
 */
public final class DataInspector {
	private static final List<Signature> SIGNATURES = List.of(
			new Signature("Java class", 0xCA, 0xFE, 0xBA, 0xBE),
			new Signature("ZIP / JAR", 'P', 'K', 3, 4),
			new Signature("Empty ZIP", 'P', 'K', 5, 6),
			new Signature("GZip", 0x1F, 0x8B, 0x08),
			new Signature("PNG image", 0x89, 'P', 'N', 'G'),
			new Signature("JPEG image", 0xFF, 0xD8, 0xFF),
			new Signature("GIF image", 'G', 'I', 'F', '8'),
			new Signature("PDF", '%', 'P', 'D', 'F'),
			new Signature("ELF binary", 0x7F, 'E', 'L', 'F'),
			new Signature("Java serialized object", 0xAC, 0xED, 0x00, 0x05),
			new Signature("Android DEX", 'd', 'e', 'x', '\n'),
			new Signature("RAR archive", 'R', 'a', 'r', '!'),
			new Signature("7z archive", '7', 'z', 0xBC, 0xAF),
			new Signature("XZ archive", 0xFD, '7', 'z', 'X', 'Z'),
			new Signature("Bzip2", 'B', 'Z', 'h'),
			new Signature("OpenSSL salted data", 'S', 'a', 'l', 't', 'e', 'd', '_', '_'),
			new Signature("Windows PE", 'M', 'Z', 0x90, 0x00),
			new Signature("SQLite database", 'S', 'Q', 'L', 'i', 't', 'e'));

	private static final String WORD_SEPARATORS = ":/.?=&,;_-";
	private static final String RARE_SYMBOLS = "!#$*\\^`|~";

	private DataInspector() {}

	/**
	 * @param data
	 * 		Data to measure.
	 *
	 * @return Shannon entropy in bits per byte, from {@code 0} to {@code 8}.
	 */
	public static double entropy(@Nonnull byte[] data) {
		if (data.length == 0)
			return 0;
		int[] counts = new int[256];
		for (byte b : data)
			counts[b & 0xFF]++;
		double entropy = 0;
		for (int count : counts) {
			if (count == 0)
				continue;
			double p = (double) count / data.length;
			entropy -= p * (Math.log(p) / Math.log(2));
		}
		return entropy;
	}

	/**
	 * @param data
	 * 		Data to check.
	 *
	 * @return Name of the file type whose signature the data starts with, or {@code null} if none match.
	 */
	@Nullable
	public static String detectSignature(@Nonnull byte[] data) {
		for (Signature signature : SIGNATURES)
			if (signature.matches(data))
				return signature.name;
		return null;
	}

	/**
	 * @param data
	 * 		Data to check.
	 *
	 * @return Codecs the data is syntactically valid for, most specific first.
	 */
	@Nonnull
	public static List<Codec> guessEncodings(@Nonnull byte[] data) {
		List<Codec> codecs = new ArrayList<>();
		String text = strictUtf8(data);
		if (text == null)
			return codecs;
		String compact = text.replaceAll("\\s", "");
		if (compact.isEmpty())
			return codecs;
		if (compact.matches("(?i)(0x)?([0-9a-f]{2})+") && compact.length() >= 4)
			codecs.add(Codec.HEX);
		boolean base64Length = compact.length() % 4 != 1 && (compact.endsWith("=") ? compact.length() % 4 == 0 : compact.length() >= 8);
		if (base64Length && compact.matches("[A-Za-z0-9+/]+={0,2}"))
			codecs.add(Codec.BASE64);
		else if (base64Length && compact.matches("[A-Za-z0-9_-]+={0,2}"))
			codecs.add(Codec.BASE64_URL);
		if (compact.length() >= 8 && compact.matches("[A-Z2-7]+=*"))
			codecs.add(Codec.BASE32);
		if (compact.length() >= 20 && compact.matches("[1-9A-HJ-NP-Za-km-z]+") && !codecs.contains(Codec.HEX))
			codecs.add(Codec.BASE58);
		if (text.startsWith("<~") && text.trim().endsWith("~>"))
			codecs.add(Codec.ASCII85);
		if (text.matches("(?s).*%[0-9A-Fa-f]{2}.*"))
			codecs.add(Codec.URL);
		if (text.matches("(?s).*\\\\(u[0-9A-Fa-f]{4}|x[0-9A-Fa-f]{2}|[ntr0-7\\\\]).*"))
			codecs.add(Codec.JAVA_ESCAPE);
		return codecs;
	}

	/**
	 * @param data
	 * 		Candidate plaintext.
	 *
	 * @return How likely the data is a meaningful plaintext rather than noise.
	 */
	@Nonnull
	public static Score score(@Nonnull byte[] data) {
		if (data.length == 0)
			return new Score(0, "Empty");
		String signature = detectSignature(data);
		if (signature != null && !signature.equals("Windows PE"))
			return new Score(0.97, signature);

		String text = strictUtf8(data);
		boolean utf8 = text != null;
		if (text == null)
			text = new String(data, StandardCharsets.ISO_8859_1);

		int printable = 0;
		int letters = 0;
		int separators = 0;
		int rare = 0;
		int length = text.length();
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			boolean isPrintable = (c >= 0x20 && c < 0x7F) || c == '\n' || c == '\r' || c == '\t'
					|| (utf8 && c >= 0xA0 && Character.isLetterOrDigit(c));
			if (isPrintable)
				printable++;
			if (Character.isLetter(c))
				letters++;
			else if (Character.isWhitespace(c) || WORD_SEPARATORS.indexOf(c) >= 0)
				separators++;
			else if (RARE_SYMBOLS.indexOf(c) >= 0)
				rare++;
		}
		double printableRatio = (double) printable / length;
		double letterRatio = (double) letters / length;
		double separatorRatio = (double) separators / length;
		double rareRatio = (double) rare / length;

		// Natural language, URLs and config text have word breaks; encoded or random printable data mostly does not,
		// and random printable data is heavy in symbols that real text rarely uses.
		double spacing = separatorRatio >= 0.04 && separatorRatio <= 0.4 ? 1 : separatorRatio > 0 ? 0.4 : 0;
		double value = Math.pow(printableRatio, 3) * (0.45 + 0.25 * Math.min(1, letterRatio / 0.6) + 0.2 * spacing);
		value *= Math.pow(1 - rareRatio, 3);
		if (utf8)
			value += 0.08;
		if (length < 24 && separators == 0)
			value += 0.1 * Math.pow(printableRatio, 3);
		if (data.length < 8)
			value *= Math.sqrt(data.length / 8.0);
		value = Math.max(0, Math.min(0.95, value));

		String kind;
		String trimmed = text.trim();
		if (printableRatio == 1 && (trimmed.startsWith("{") && trimmed.endsWith("}") || trimmed.startsWith("[") && trimmed.endsWith("]")))
			kind = "JSON-like text";
		else if (printableRatio == 1 && trimmed.startsWith("<") && trimmed.endsWith(">"))
			kind = "XML-like text";
		else if (printableRatio == 1)
			kind = "Text";
		else if (printableRatio > 0.85)
			kind = "Mostly text";
		else
			kind = "Binary";
		return new Score(value, kind);
	}

	/**
	 * @param data
	 * 		Bytes to decode.
	 *
	 * @return Text if the bytes are valid UTF-8, otherwise {@code null}.
	 */
	@Nullable
	public static String strictUtf8(@Nonnull byte[] data) {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			return decoder.decode(ByteBuffer.wrap(data)).toString();
		} catch (CharacterCodingException ex) {
			return null;
		}
	}

	/**
	 * @param data
	 * 		Bytes to preview.
	 * @param maxChars
	 * 		Maximum preview length.
	 *
	 * @return Single-line preview, with control and non-ASCII bytes shown as {@code .} unless the data is valid UTF-8.
	 */
	@Nonnull
	public static String preview(@Nonnull byte[] data, int maxChars) {
		String text = strictUtf8(data);
		StringBuilder sb = new StringBuilder(Math.min(maxChars, data.length) + 1);
		if (text != null) {
			for (int i = 0; i < text.length() && sb.length() < maxChars; i++) {
				char c = text.charAt(i);
				sb.append(c == '\n' ? '⏎' : Character.isISOControl(c) ? '.' : c);
			}
			if (text.length() > maxChars)
				sb.append('…');
		} else {
			for (int i = 0; i < data.length && sb.length() < maxChars; i++) {
				int c = data[i] & 0xFF;
				sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
			}
			if (data.length > maxChars)
				sb.append('…');
		}
		return sb.toString();
	}

	/**
	 * @param value
	 * 		Likelihood from {@code 0} to {@code 1}.
	 * @param kind
	 * 		What the data appears to be.
	 */
	public record Score(double value, @Nonnull String kind) {
		/**
		 * @return {@code true} when the data was judged to be readable text.
		 */
		public boolean isText() {
			return kind.toLowerCase(Locale.ROOT).contains("text");
		}
	}

	private record Signature(@Nonnull String name, @Nonnull int... magic) {
		boolean matches(@Nonnull byte[] data) {
			if (data.length < magic.length)
				return false;
			for (int i = 0; i < magic.length; i++)
				if ((data[i] & 0xFF) != magic[i])
					return false;
			return true;
		}
	}
}
