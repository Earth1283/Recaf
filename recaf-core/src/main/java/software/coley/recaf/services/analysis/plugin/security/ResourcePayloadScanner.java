package software.coley.recaf.services.analysis.plugin.security;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.properties.builtin.PathOriginalNameProperty;
import software.coley.recaf.info.properties.builtin.PathPrefixProperty;
import software.coley.recaf.info.properties.builtin.PathSuffixProperty;
import software.coley.recaf.util.JavaVersion;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognizes what a file inside a plugin really is from its bytes, to find code hidden in files that are not classes:
 * class files and archives with other names, native binaries, and blobs of data that look encrypted.
 * <p>
 * Everything here reads bytes only. Nothing is loaded or run.
 */
public final class ResourcePayloadScanner {
	/** Files larger than this are not read. */
	public static final int MAX_FILE_SIZE = 64 * 1024 * 1024;
	/** Smallest file considered for {@link SecurityRule#HIGH_ENTROPY_RESOURCE}. */
	public static final int MIN_ENTROPY_SIZE = 4096;
	/** Entropy in bits per byte from which data counts as encrypted or compressed. */
	public static final double ENTROPY_THRESHOLD = 7.5;
	/** Newest class file major version accepted: a few versions past what Recaf knows, for classes of newer JDKs. */
	private static final int MAX_CLASS_MAJOR = 25 + JavaVersion.VERSION_OFFSET + 3;
	private static final int MIN_CLASS_MAJOR = 45;
	private static final int MAX_FAT_ARCHITECTURES = 30;
	private static final Set<String> ARCHIVE_EXTENSIONS = Set.of("jar", "zip", "war", "ear", "aar", "apk", "docx", "xlsx",
			"pptx", "odt", "ods", "odp", "mcpack", "mcworld", "mcaddon", "mctemplate", "litemod", "mrpack", "jmod", "epub",
			"xpi", "nupkg", "vsix");
	private static final Set<String> NATIVE_EXTENSIONS = Set.of("so", "dll", "dylib", "jnilib", "exe");
	private static final Pattern VERSIONED_SO = Pattern.compile(".*\\.so(\\.\\d+)+");
	/** Formats that are compressed or encrypted by nature, named by extension for those without a reliable header. */
	private static final Set<String> COMPRESSED_EXTENSIONS = Set.of(
			"png", "jpg", "jpeg", "gif", "webp", "avif", "heic", "ico", "cur", "ogg", "oga", "opus", "mp3", "mp4", "m4a",
			"m4v", "aac", "flac", "webm", "mkv", "avi", "mov", "wav", "gz", "tgz", "xz", "txz", "zst", "bz2", "tbz2", "7z",
			"lz4", "lzma", "br", "rar", "zip", "jar", "ttf", "otf", "woff", "woff2", "eot", "pdf", "sqlite", "sqlite3",
			"nbt", "schem", "schematic", "litematic", "mcstructure", "mca", "mcr", "class", "jks", "keystore", "p12",
			"pfx");
	private static final Set<String> EXEMPT_NAMES = Set.of("plugin.yml", "paper-plugin.yml", "bungee.yml",
			"velocity-plugin.json", "fabric.mod.json", "mods.toml", "neoforge.mods.toml", "mcmod.info", "litemod.json");

	private ResourcePayloadScanner() {}

	/**
	 * What was recognized in a file.
	 *
	 * @param rule
	 * 		Rule the file matches.
	 * @param detail
	 * 		What was recognized, in a few words.
	 */
	public record Match(@Nonnull SecurityRule rule, @Nonnull String detail) {}

	/**
	 * @param path
	 * 		Path of a file inside a plugin, using {@code /}.
	 * @param content
	 * 		Bytes of the file.
	 *
	 * @return What is suspicious about the file, or {@code null} when it is ordinary or not worth reporting.
	 */
	@Nullable
	public static Match inspect(@Nonnull String path, @Nonnull byte[] content) {
		if (isExempt(path) || content.length > MAX_FILE_SIZE)
			return null;

		int classMajor = classMajorVersion(content);
		if (classMajor > 0)
			return path.endsWith(".class") ? null : new Match(SecurityRule.DISGUISED_CLASS,
					"class file, Java " + (classMajor - JavaVersion.VERSION_OFFSET));

		String nativeKind = nativeKind(content);
		if (nativeKind != null)
			return new Match(hasNativeExtension(path) ? SecurityRule.NATIVE_BINARY : SecurityRule.DISGUISED_BINARY, nativeKind);

		if (startsWith(content, 0x50, 0x4B, 0x03, 0x04))
			return isArchiveName(path) ? null : new Match(SecurityRule.DISGUISED_ARCHIVE, "ZIP archive");

		if (content.length >= MIN_ENTROPY_SIZE && !isKnownCompressedFormat(path, content)) {
			double entropy = entropy(content);
			if (entropy >= ENTROPY_THRESHOLD)
				return new Match(SecurityRule.HIGH_ENTROPY_RESOURCE,
						String.format(Locale.ROOT, "entropy %.2f, %s", entropy, size(content.length)));
		}
		return null;
	}

	/**
	 * Used for archives the importer already unpacked, whose bytes are no longer at hand.
	 *
	 * @param path
	 * 		Path of an archive inside a plugin.
	 *
	 * @return A {@link SecurityRule#DISGUISED_ARCHIVE} match when the name does not say it is an archive.
	 */
	@Nullable
	public static Match inspectArchive(@Nonnull String path) {
		if (isExempt(path) || isArchiveName(path))
			return null;
		return new Match(SecurityRule.DISGUISED_ARCHIVE, "ZIP archive");
	}

	/**
	 * @param info
	 * 		Class the importer read.
	 *
	 * @return Path the class had in the plugin. It differs from {@code name + ".class"} when the importer found
	 * class content in a file with some other name.
	 */
	@Nonnull
	public static String originalPath(@Nonnull JvmClassInfo info) {
		String original = PathOriginalNameProperty.get(info);
		if (original != null)
			return original;
		String prefix = PathPrefixProperty.get(info);
		String suffix = PathSuffixProperty.get(info);
		return (prefix == null ? "" : prefix) + info.getName() + (suffix == null ? ".class" : suffix);
	}

	/**
	 * @param path
	 * 		Path of a file inside a plugin.
	 *
	 * @return {@code true} for files that are never reported: manifests and signatures, and Maven metadata.
	 */
	public static boolean isExempt(@Nonnull String path) {
		String lower = path.toLowerCase(Locale.ROOT);
		if (lower.startsWith("meta-inf/maven/") || lower.equals("meta-inf/manifest.mf"))
			return true;
		if (lower.startsWith("meta-inf/") && lower.indexOf('/', "meta-inf/".length()) < 0 &&
				(lower.endsWith(".sf") || lower.endsWith(".rsa") || lower.endsWith(".dsa") || lower.endsWith(".ec")))
			return true;
		return EXEMPT_NAMES.contains(lower.substring(lower.lastIndexOf('/') + 1));
	}

	/**
	 * @param path
	 * 		Path of a file.
	 *
	 * @return {@code true} when the extension is one for archives.
	 */
	public static boolean isArchiveName(@Nonnull String path) {
		return ARCHIVE_EXTENSIONS.contains(extension(path));
	}

	/**
	 * @param path
	 * 		Path of a file.
	 *
	 * @return {@code true} when the extension is one for native libraries and programs.
	 */
	public static boolean hasNativeExtension(@Nonnull String path) {
		return NATIVE_EXTENSIONS.contains(extension(path)) || VERSIONED_SO.matcher(path.toLowerCase(Locale.ROOT)).matches();
	}

	/**
	 * @param content
	 * 		Bytes of a file.
	 *
	 * @return Major version of the class file, or {@code 0} when the bytes are not a class file. Mach-O universal
	 * binaries share the class file magic, so they are told apart by what follows it.
	 */
	public static int classMajorVersion(@Nonnull byte[] content) {
		if (content.length < 10 || !startsWith(content, 0xCA, 0xFE, 0xBA, 0xBE))
			return 0;
		int major = be16(content, 6);
		if (major < MIN_CLASS_MAJOR || major > MAX_CLASS_MAJOR)
			return 0;
		return major;
	}

	/**
	 * @param content
	 * 		Bytes of a file.
	 *
	 * @return Short description of the native binary format the bytes are, or {@code null} when they are not one.
	 */
	@Nullable
	public static String nativeKind(@Nonnull byte[] content) {
		if (content.length >= 20 && startsWith(content, 0x7F, 0x45, 0x4C, 0x46) &&
				(content[4] == 1 || content[4] == 2) && (content[5] == 1 || content[5] == 2)) {
			boolean little = content[5] == 1;
			return switch (little ? le16(content, 16) : be16(content, 16)) {
				case 1 -> "ELF object";
				case 2 -> "ELF executable";
				case 3 -> "ELF shared object";
				default -> "ELF binary";
			};
		}
		if (content.length >= 64 && content[0] == 'M' && content[1] == 'Z') {
			long header = le32(content, 0x3C);
			if (header >= 64 && header + 24 <= content.length && content[(int) header] == 'P' &&
					content[(int) header + 1] == 'E' && content[(int) header + 2] == 0 && content[(int) header + 3] == 0)
				return (le16(content, (int) header + 22) & 0x2000) != 0 ? "PE DLL" : "PE executable";
		}
		if (content.length >= 16) {
			if (startsWith(content, 0xFE, 0xED, 0xFA, 0xCE) || startsWith(content, 0xFE, 0xED, 0xFA, 0xCF))
				return machOKind(be32(content, 12));
			if (startsWith(content, 0xCE, 0xFA, 0xED, 0xFE) || startsWith(content, 0xCF, 0xFA, 0xED, 0xFE))
				return machOKind(le32(content, 12));
			if (isFatMachO(content))
				return "Mach-O universal binary";
		}
		return null;
	}

	/**
	 * @param content
	 * 		Bytes of a file.
	 *
	 * @return Shannon entropy in bits per byte, from {@code 0} to {@code 8}.
	 */
	public static double entropy(@Nonnull byte[] content) {
		if (content.length == 0)
			return 0;
		int[] counts = new int[256];
		for (byte b : content)
			counts[b & 0xFF]++;
		double total = content.length;
		double entropy = 0;
		for (int count : counts) {
			if (count == 0)
				continue;
			double p = count / total;
			entropy -= p * (Math.log(p) / Math.log(2));
		}
		return entropy;
	}

	/**
	 * @param path
	 * 		Path of a file.
	 * @param content
	 * 		Bytes of the file.
	 *
	 * @return {@code true} when the file is in a format that is compressed by nature, so that its entropy says nothing.
	 * The header is checked first and the extension second, for formats whose headers are not recognizable.
	 */
	public static boolean isKnownCompressedFormat(@Nonnull String path, @Nonnull byte[] content) {
		if (startsWith(content, 0x89, 'P', 'N', 'G') || startsWith(content, 0xFF, 0xD8, 0xFF) ||
				startsWith(content, 'G', 'I', 'F', '8') || startsWith(content, 'O', 'g', 'g', 'S') ||
				startsWith(content, 'I', 'D', '3') || startsWith(content, 0x1F, 0x8B) ||
				startsWith(content, 0xFD, '7', 'z', 'X', 'Z', 0x00) || startsWith(content, 0x28, 0xB5, 0x2F, 0xFD) ||
				startsWith(content, 'B', 'Z', 'h') || startsWith(content, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C) ||
				startsWith(content, 0x04, 0x22, 0x4D, 0x18) || startsWith(content, 'P', 'K', 0x03, 0x04) ||
				startsWith(content, 'R', 'a', 'r', '!') || startsWith(content, '%', 'P', 'D', 'F') ||
				startsWith(content, 'S', 'Q', 'L', 'i', 't', 'e', ' ', 'f', 'o', 'r', 'm', 'a', 't', ' ', '3') ||
				startsWith(content, 'w', 'O', 'F', 'F') || startsWith(content, 'w', 'O', 'F', '2') ||
				startsWith(content, 'O', 'T', 'T', 'O') || startsWith(content, 0x00, 0x01, 0x00, 0x00, 0x00) ||
				startsWith(content, 't', 't', 'c', 'f') || startsWith(content, 'f', 'L', 'a', 'C') ||
				startsWith(content, 0xCA, 0xFE, 0xBA, 0xBE) || startsWith(content, 0xFF, 0xFB) ||
				startsWith(content, 0xFF, 0xF3) || startsWith(content, 0xFF, 0xF2))
			return true;
		if (startsWith(content, 'R', 'I', 'F', 'F') && content.length >= 12 && (content[8] == 'W' || content[8] == 'A'))
			return true;
		if (content.length >= 12 && content[4] == 'f' && content[5] == 't' && content[6] == 'y' && content[7] == 'p')
			return true;
		String name = path.toLowerCase(Locale.ROOT);
		name = name.substring(name.lastIndexOf('/') + 1);
		int dot = name.indexOf('.');
		while (dot >= 0 && dot < name.length() - 1) {
			if (COMPRESSED_EXTENSIONS.contains(name.substring(dot + 1)))
				return true;
			dot = name.indexOf('.', dot + 1);
		}
		return false;
	}

	private static boolean isFatMachO(@Nonnull byte[] content) {
		boolean big;
		if (startsWith(content, 0xCA, 0xFE, 0xBA, 0xBE) || startsWith(content, 0xCA, 0xFE, 0xBA, 0xBF))
			big = true;
		else if (startsWith(content, 0xBE, 0xBA, 0xFE, 0xCA) || startsWith(content, 0xBF, 0xBA, 0xFE, 0xCA))
			big = false;
		else
			return false;
		boolean wide = content[3] == (byte) 0xBF || content[0] == (byte) 0xBF;
		long count = big ? be32(content, 4) : le32(content, 4);
		if (count < 1 || count > MAX_FAT_ARCHITECTURES)
			return false;
		int entry = wide ? 32 : 20;
		if (content.length < 8 + entry)
			return false;
		long offset = big ? be32(content, wide ? 20 : 16) : le32(content, 16);
		if (offset < 8 + count * entry || offset + 4 > content.length)
			return false;
		int at = (int) offset;
		return matchesAt(content, at, 0xFE, 0xED, 0xFA, 0xCE) || matchesAt(content, at, 0xFE, 0xED, 0xFA, 0xCF) ||
				matchesAt(content, at, 0xCE, 0xFA, 0xED, 0xFE) || matchesAt(content, at, 0xCF, 0xFA, 0xED, 0xFE);
	}

	@Nonnull
	private static String machOKind(long fileType) {
		return switch ((int) fileType) {
			case 2 -> "Mach-O executable";
			case 6 -> "Mach-O dynamic library";
			case 8 -> "Mach-O bundle";
			default -> "Mach-O binary";
		};
	}

	@Nonnull
	private static String extension(@Nonnull String path) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	@Nonnull
	private static String size(int bytes) {
		if (bytes >= 1024 * 1024)
			return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
		return Math.max(1, bytes / 1024) + " KB";
	}

	private static boolean startsWith(@Nonnull byte[] content, int... prefix) {
		return matchesAt(content, 0, prefix);
	}

	private static boolean matchesAt(@Nonnull byte[] content, int offset, int... prefix) {
		if (content.length < offset + prefix.length)
			return false;
		for (int i = 0; i < prefix.length; i++)
			if ((content[offset + i] & 0xFF) != prefix[i])
				return false;
		return true;
	}

	private static int be16(@Nonnull byte[] content, int offset) {
		return ((content[offset] & 0xFF) << 8) | (content[offset + 1] & 0xFF);
	}

	private static int le16(@Nonnull byte[] content, int offset) {
		return ((content[offset + 1] & 0xFF) << 8) | (content[offset] & 0xFF);
	}

	private static long be32(@Nonnull byte[] content, int offset) {
		return ((long) (content[offset] & 0xFF) << 24) | ((content[offset + 1] & 0xFF) << 16) |
				((content[offset + 2] & 0xFF) << 8) | (content[offset + 3] & 0xFF);
	}

	private static long le32(@Nonnull byte[] content, int offset) {
		return ((long) (content[offset + 3] & 0xFF) << 24) | ((content[offset + 2] & 0xFF) << 16) |
				((content[offset + 1] & 0xFF) << 8) | (content[offset] & 0xFF);
	}
}
