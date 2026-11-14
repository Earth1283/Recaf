package software.coley.recaf.ui.pane.plugin;

import jakarta.annotation.Nonnull;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the text to look for in decompiled source to find the line a {@link SecurityFinding} is about.
 * <p>
 * Decompiled line numbers do not map to bytecode, so the finding's detail is searched for as text instead.
 */
public final class FindingNeedles {
	private static final Pattern COUNT = Pattern.compile("\\d+ classes?");
	private static final Pattern QUALIFIER = Pattern.compile("^([\\w$]+) \\([^)]*\\)$");
	private static final Pattern API = Pattern.compile("([A-Z][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(\\(.*\\))?");
	private static final Pattern CHAINED_CALL = Pattern.compile("[A-Za-z_$][\\w$]*\\(.*\\)(?:\\.[A-Za-z_$][\\w$]*\\(.*\\))+");
	private static final Pattern HOST = Pattern.compile("^[a-z][a-z0-9+.-]*://([^/:?#\\s]+)", Pattern.CASE_INSENSITIVE);
	private static final int PREFIX_LENGTH = 40;

	private FindingNeedles() {}

	/**
	 * @param finding
	 * 		Some finding.
	 *
	 * @return Candidate texts in the order they should be tried, the first one found in the code wins.
	 * Empty when nothing in the finding can appear in source.
	 */
	@Nonnull
	public static List<String> of(@Nonnull SecurityFinding finding) {
		Set<String> needles = new LinkedHashSet<>();
		collect(finding, needles);
		for (SecurityFinding part : finding.evidence())
			collect(part, needles);
		return new ArrayList<>(needles);
	}

	private static void collect(@Nonnull SecurityFinding finding, @Nonnull Set<String> needles) {
		String file = finding.file();
		if (file != null) {
			needles.add(file);
			needles.add(file.substring(file.lastIndexOf('/') + 1));
			return;
		}
		String detail = finding.detail();
		if (detail != null)
			fromDetail(detail, needles);
	}

	private static void fromDetail(@Nonnull String detail, @Nonnull Set<String> needles) {
		boolean truncated = detail.endsWith("…");
		String text = truncated ? detail.substring(0, detail.length() - 1) : detail;
		if (text.isBlank() || COUNT.matcher(text).matches())
			return;
		if (text.startsWith("new ") || text.startsWith("extends ")) {
			needles.add(text);
			return;
		}
		Matcher qualifier = QUALIFIER.matcher(text);
		if (qualifier.matches()) {
			String name = qualifier.group(1);
			needles.add('"' + name + '"');
			needles.add(name);
			return;
		}
		if (CHAINED_CALL.matcher(text).matches()) {
			needles.add(text);
			String last = text.substring(text.lastIndexOf(").") + 2);
			needles.add(last);
			needles.add(last.substring(0, last.indexOf('(') + 1));
			return;
		}
		Matcher api = API.matcher(text);
		if (!truncated && api.matches() && isApi(text)) {
			String path = api.group(1);
			String arguments = api.group(2) == null ? "" : api.group(2);
			int dot = path.lastIndexOf('.');
			if (dot >= 0) {
				String method = path.substring(dot + 1);
				if (!arguments.isEmpty())
					needles.add(method + arguments);
				needles.add('.' + method + '(');
				needles.add(method + '(');
			}
		}
		literal(text, truncated, needles);
	}

	/**
	 * @return {@code true} for text shaped like {@code Owner.method}, or a lone type name, as opposed to a string
	 * constant that happens to look like a file name.
	 */
	private static boolean isApi(@Nonnull String text) {
		return Character.isUpperCase(text.charAt(0)) && text.indexOf(' ') < 0 && text.indexOf('/') < 0;
	}

	private static void literal(@Nonnull String text, boolean truncated, @Nonnull Set<String> needles) {
		String escaped = escape(text);
		if (!truncated) {
			needles.add(escaped);
			needles.add(text);
		}
		String prefix = text.length() > PREFIX_LENGTH ? text.substring(0, PREFIX_LENGTH) : text;
		if (truncated || prefix.length() < text.length()) {
			needles.add(escape(prefix));
			needles.add(prefix);
		}
		Matcher host = HOST.matcher(text);
		if (host.find())
			needles.add(host.group(1));
	}

	/**
	 * @return Text as a decompiler writes it between the quotes of a string literal.
	 */
	@Nonnull
	private static String escape(@Nonnull String text) {
		StringBuilder out = new StringBuilder(text.length() + 8);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> out.append(c);
			}
		}
		return out.toString();
	}
}
