package software.coley.recaf.services.analysis.plugin.view;

import jakarta.annotation.Nonnull;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carries comment text through a decompiler. Notes are written into the bytecode as an annotation on the class,
 * method or field they describe, which every decompiler prints directly above that declaration. {@link #rewrite(String)}
 * then turns the printed annotation into a block comment.
 * <p>
 * The text comes from the plugin being examined, so it is hostile input: it is limited to printable characters that
 * cannot end a comment or a string, and is limited in length.
 */
final class NoteMarker {
	static final String PREFIX = "RecafPluginNote";
	static final String NAME = PREFIX + '_' + nonce();
	static final String DESCRIPTOR = 'L' + NAME + ';';
	private static final String SEPARATOR = " | ";
	private static final int MAX_LINE_LENGTH = 160;
	private static final Pattern MARKER = Pattern.compile('@' + NAME + "\\b[ \\t]*(?:\\((?:[^)\"\\r\\n]|\"(?:[^\"\\\\\\r\\n]|\\\\.)*\")*\\))?");
	private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

	private NoteMarker() {}

	/**
	 * The marker is only read back if it carries this process's nonce, which a plugin cannot know. Otherwise a string
	 * in the plugin that looks like a marker could make real code disappear from the decompiled text.
	 */
	private static String nonce() {
		byte[] bytes = new byte[12];
		new SecureRandom().nextBytes(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes)
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		return sb.toString();
	}

	/**
	 * @param lines
	 * 		Note lines.
	 *
	 * @return Annotation value holding the lines.
	 */
	@Nonnull
	static String encode(@Nonnull List<String> lines) {
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			if (!sb.isEmpty())
				sb.append(SEPARATOR);
			sb.append(sanitize(line));
		}
		return sb.toString();
	}

	/**
	 * @param text
	 * 		Some text taken from a plugin.
	 *
	 * @return Text that is safe to put inside a string literal and a block comment.
	 */
	@Nonnull
	static String sanitize(@Nonnull String text) {
		StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_LINE_LENGTH));
		for (int i = 0; i < text.length() && sb.length() < MAX_LINE_LENGTH; i++) {
			char c = text.charAt(i);
			if (c == '"' || c == '\\' || c == '|' || c == '@')
				sb.append('\'');
			else if (isPrintable(c))
				sb.append(c);
			else
				sb.append('?');
		}
		String clean = sb.toString();
		while (clean.contains("*/") || clean.contains("/*"))
			clean = clean.replace("*/", "* /").replace("/*", "/ *");
		return clean.strip();
	}

	private static boolean isPrintable(char c) {
		if (c == ' ')
			return true;
		return switch (Character.getType(c)) {
			case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
			     Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
			default -> true;
		};
	}

	/**
	 * @param code
	 * 		Decompiled code, possibly containing markers.
	 *
	 * @return The code with every marker replaced by a block comment holding its text, or removed when it holds none.
	 * The same instance when there are no markers.
	 */
	@Nonnull
	static String rewrite(@Nonnull String code) {
		if (code.indexOf('@' + NAME) < 0)
			return code;
		Matcher matcher = MARKER.matcher(code);
		StringBuilder out = new StringBuilder(code.length());
		int last = 0;
		while (matcher.find()) {
			int lineStart = code.lastIndexOf('\n', matcher.start()) + 1;
			String indent = code.substring(lineStart, matcher.start());
			int end = matcher.end();
			int after = end;
			while (after < code.length() && (code.charAt(after) == ' ' || code.charAt(after) == '\t'))
				after++;
			boolean ownLine = indent.isBlank() && (after >= code.length() || code.charAt(after) == '\n' || code.charAt(after) == '\r');
			List<String> lines = decode(matcher.group());

			out.append(code, last, ownLine ? lineStart : matcher.start());
			if (lines.isEmpty()) {
				int skip = end;
				while (skip < code.length() && (code.charAt(skip) == ' ' || code.charAt(skip) == '\t'))
					skip++;
				if (ownLine && skip < code.length() && (code.charAt(skip) == '\n' || code.charAt(skip) == '\r')) {
					if (code.charAt(skip) == '\r' && skip + 1 < code.length() && code.charAt(skip + 1) == '\n')
						skip++;
					skip++;
				} else if (!ownLine) {
					skip = end;
				}
				last = skip;
				continue;
			}
			if (ownLine) {
				out.append(indent).append("/*");
				for (String line : lines)
					out.append('\n').append(indent).append(" * ").append(line);
				out.append('\n').append(indent).append(" */");
			} else {
				out.append("/* ").append(String.join("; ", lines)).append(" */");
			}
			last = end;
		}
		out.append(code, last, code.length());
		return out.toString();
	}

	@Nonnull
	private static List<String> decode(@Nonnull String marker) {
		Matcher literal = LITERAL.matcher(marker);
		if (!literal.find())
			return List.of();
		List<String> lines = new ArrayList<>();
		for (String part : unescape(literal.group(1)).split("\\Q" + SEPARATOR + "\\E")) {
			String clean = sanitize(part);
			if (!clean.isEmpty())
				lines.add(clean);
		}
		return lines;
	}

	@Nonnull
	private static String unescape(@Nonnull String text) {
		if (text.indexOf('\\') < 0)
			return text;
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c != '\\' || i + 1 >= text.length()) {
				sb.append(c);
				continue;
			}
			char next = text.charAt(++i);
			if (next == 'u' && isHex(text, i + 1)) {
				sb.append((char) Integer.parseInt(text, i + 1, i + 5, 16));
				i += 4;
			} else if (next == '"' || next == '\\' || next == '\'') {
				sb.append('\'');
			}
		}
		return sb.toString();
	}

	private static boolean isHex(@Nonnull String text, int start) {
		if (start + 4 > text.length())
			return false;
		for (int i = start; i < start + 4; i++)
			if (Character.digit(text.charAt(i), 16) < 0)
				return false;
		return true;
	}
}
