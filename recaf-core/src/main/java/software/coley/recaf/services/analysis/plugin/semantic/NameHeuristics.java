package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Text utilities for deciding whether a name is obfuscated, and for turning clues into Java names.
 */
public final class NameHeuristics {
	private static final Set<String> KEYWORDS = Set.of(
			"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
			"default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
			"implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
			"protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
			"throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null", "var", "yield",
			"record", "sealed", "permits", "_");
	private static final Set<String> SHORT_REAL_NAMES = Set.of("id", "io", "ui", "db", "ok", "on", "of", "to", "is");
	private static final Pattern GENERATED = Pattern.compile(
			"^(?:class|field|method|func|var|param|arg|local|lambda|access|Class|Field|Method)[_$]?\\d+$|" +
					"^[A-Za-z]{1,2}\\d+$|^[Il1|]+$|^[O0o]+$|^[_$]+$");
	private static final Pattern NO_VOWELS = Pattern.compile("^[b-df-hj-np-tv-z]{3}$");

	private NameHeuristics() {}

	/**
	 * @param name
	 * 		Simple class name, or member or variable name.
	 *
	 * @return {@code true} when the name looks like the output of an obfuscator or decompiler rather than a name a
	 * person wrote.
	 */
	public static boolean looksObfuscated(@Nonnull String name) {
		if (name.isEmpty() || !isValidIdentifier(name))
			return true;
		for (int i = 0; i < name.length(); i++)
			if (name.charAt(i) > 127)
				return true;
		if (name.length() <= 2)
			return !SHORT_REAL_NAMES.contains(name);
		return GENERATED.matcher(name).matches() || NO_VOWELS.matcher(name.toLowerCase(Locale.ROOT)).matches();
	}

	/**
	 * @param name
	 * 		Some name.
	 *
	 * @return {@code true} if the name can be used as a Java identifier.
	 */
	public static boolean isValidIdentifier(@Nullable String name) {
		if (name == null || name.isEmpty() || KEYWORDS.contains(name))
			return false;
		if (!Character.isJavaIdentifierStart(name.codePointAt(0)))
			return false;
		for (int i = Character.charCount(name.codePointAt(0)); i < name.length(); ) {
			int cp = name.codePointAt(i);
			if (!Character.isJavaIdentifierPart(cp))
				return false;
			i += Character.charCount(cp);
		}
		return true;
	}

	/**
	 * @param text
	 * 		Text such as {@code max-homes}, {@code my_plugin} or {@code Hello World}.
	 *
	 * @return Words of the text, split on separators and case changes.
	 */
	@Nonnull
	static List<String> words(@Nonnull String text) {
		List<String> words = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (!Character.isLetterOrDigit(c) || c > 127) {
				flush(words, current);
				continue;
			}

			// Split 'fooBar' and 'FOOBar' into 'foo', 'Bar' and 'FOO', 'Bar'.
			if (Character.isUpperCase(c) && !current.isEmpty()) {
				char prev = current.charAt(current.length() - 1);
				boolean nextIsLower = i + 1 < text.length() && Character.isLowerCase(text.charAt(i + 1));
				if (Character.isLowerCase(prev) || Character.isDigit(prev) || (Character.isUpperCase(prev) && nextIsLower))
					flush(words, current);
			}
			current.append(c);
		}
		flush(words, current);
		return words;
	}

	private static void flush(@Nonnull List<String> words, @Nonnull StringBuilder current) {
		if (!current.isEmpty()) {
			words.add(current.toString());
			current.setLength(0);
		}
	}

	/**
	 * @param text
	 * 		Text to convert.
	 *
	 * @return Text as {@code PascalCase}, or empty if there is nothing usable in it.
	 */
	@Nonnull
	public static String toPascalCase(@Nonnull String text) {
		StringBuilder sb = new StringBuilder();
		for (String word : words(text))
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
		return identifierSafe(sb.toString());
	}

	/**
	 * @param text
	 * 		Text to convert.
	 *
	 * @return Text as {@code camelCase}, or empty if there is nothing usable in it.
	 */
	@Nonnull
	public static String toCamelCase(@Nonnull String text) {
		String pascal = toPascalCase(text);
		if (pascal.isEmpty() || Character.isDigit(pascal.charAt(0)) || pascal.charAt(0) == '_')
			return pascal;

		// Lowercasing can turn a valid name into a keyword, such as 'Default' into 'default'.
		return identifierSafe(lowerFirst(pascal));
	}

	/**
	 * @param text
	 * 		Text to convert.
	 *
	 * @return Text as {@code UPPER_SNAKE_CASE}, or empty if there is nothing usable in it.
	 */
	@Nonnull
	public static String toUpperSnakeCase(@Nonnull String text) {
		String joined = String.join("_", words(text)).toUpperCase(Locale.ROOT);
		return identifierSafe(joined);
	}

	/**
	 * @param name
	 * 		Some name.
	 *
	 * @return Name with the first character in lowercase. Acronyms at the start are lowercased whole, so {@code UUID}
	 * becomes {@code uuid} and {@code URLHolder} becomes {@code urlHolder}.
	 */
	@Nonnull
	public static String lowerFirst(@Nonnull String name) {
		if (name.isEmpty())
			return name;
		int upper = 0;
		while (upper < name.length() && Character.isUpperCase(name.charAt(upper)))
			upper++;
		if (upper <= 1 || upper == name.length())
			return name.substring(0, Math.max(upper, 1)).toLowerCase(Locale.ROOT) + name.substring(Math.max(upper, 1));
		// 'URLHolder': keep the 'H' as the start of the next word.
		return name.substring(0, upper - 1).toLowerCase(Locale.ROOT) + name.substring(upper - 1);
	}

	/**
	 * @param name
	 * 		Some name.
	 *
	 * @return Name with the first character in uppercase.
	 */
	@Nonnull
	public static String upperFirst(@Nonnull String name) {
		if (name.isEmpty())
			return name;
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	/**
	 * @param name
	 * 		A singular name.
	 *
	 * @return Simple plural of the name.
	 */
	@Nonnull
	public static String plural(@Nonnull String name) {
		if (name.isEmpty())
			return name;
		if (name.endsWith("s") || name.endsWith("x") || name.endsWith("ch") || name.endsWith("sh"))
			return name + "es";
		if (name.endsWith("y") && name.length() > 1 && "aeiou".indexOf(name.charAt(name.length() - 2)) < 0)
			return name.substring(0, name.length() - 1) + "ies";
		return name + "s";
	}

	/**
	 * @param simpleName
	 * 		Event class name, such as {@code PlayerJoinEvent}.
	 *
	 * @return Name without the {@code Event} suffix, such as {@code PlayerJoin}.
	 */
	@Nonnull
	public static String stripEventSuffix(@Nonnull String simpleName) {
		if (simpleName.endsWith("Event") && simpleName.length() > "Event".length())
			return simpleName.substring(0, simpleName.length() - "Event".length());
		return simpleName;
	}

	/**
	 * @param internalName
	 * 		Internal class name.
	 *
	 * @return Name without package or outer classes.
	 */
	@Nonnull
	public static String simpleName(@Nonnull String internalName) {
		String name = internalName.substring(internalName.lastIndexOf('/') + 1);
		int inner = name.lastIndexOf('$');
		if (inner >= 0 && inner < name.length() - 1)
			name = name.substring(inner + 1);
		return name;
	}

	/**
	 * @param path
	 * 		Dotted key such as a config path.
	 *
	 * @return The last part of the key.
	 */
	@Nonnull
	public static String lastSegment(@Nonnull String path) {
		String trimmed = path;
		while (trimmed.endsWith(".") || trimmed.endsWith(":"))
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		int split = Math.max(trimmed.lastIndexOf('.'), trimmed.lastIndexOf(':'));
		return split >= 0 ? trimmed.substring(split + 1) : trimmed;
	}

	@Nonnull
	private static String identifierSafe(@Nonnull String name) {
		if (name.isEmpty())
			return name;
		if (Character.isDigit(name.charAt(0)))
			name = '_' + name;
		return isValidIdentifier(name) ? name : name + '_';
	}
}
