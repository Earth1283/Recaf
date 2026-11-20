package software.coley.recaf.services.analysis.compare;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.TraceClassVisitor;
import software.coley.recaf.util.StringUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compares JAR entries by archive path and JVM members by name and descriptor.
 */
public final class JarComparison {
	private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;
	private static final int MAX_TEXT_RESOURCE_BYTES = 256 * 1024;
	private static final Remapper IGNORE_STRINGS = new Remapper(Opcodes.ASM9) {
		@Override
		public Object mapValue(Object value) {
			// The superclass also maps bootstrap arguments inside constant-dynamic values.
			return value instanceof String ? "" : super.mapValue(value);
		}
	};

	private JarComparison() {
	}

	/**
	 * Compares two archives. Strict mode compares JVM string values exactly. Both modes compare resource contents
	 * exactly and disregard ZIP metadata, JVM debug information, and stack map frames.
	 *
	 * @param before Baseline JAR.
	 * @param after Updated JAR.
	 * @param strict Whether to compare JVM string values.
	 * @return Changed entries, ordered by path.
	 * @throws IOException If an archive or its entries cannot be read.
	 */
	@Nonnull
	public static List<EntryChange> compare(@Nonnull Path before, @Nonnull Path after, boolean strict) throws IOException {
		List<EntryChange> changes = new ArrayList<>();
		try (ZipFile baseline = new ZipFile(before.toFile()); ZipFile updated = new ZipFile(after.toFile())) {
			Map<String, ZipEntry> oldEntries = entries(baseline);
			Map<String, ZipEntry> newEntries = entries(updated);
			for (String path : union(oldEntries, newEntries)) {
				checkCancelled();
				ZipEntry oldEntry = oldEntries.get(path);
				ZipEntry newEntry = newEntries.get(path);
				if (sameContents(baseline, oldEntry, updated, newEntry))
					continue;
				EntryStamp oldStamp = EntryStamp.of(oldEntry);
				EntryStamp newStamp = EntryStamp.of(newEntry);
				Status status = status(oldEntry, newEntry);
				boolean compareStrings = strict || status != Status.CHANGED;
				ClassNode oldClass = readClass(baseline, oldEntry, compareStrings);
				ClassNode newClass = readClass(updated, newEntry, compareStrings);
				if ((oldEntry != null && oldClass == null) || (newEntry != null && newClass == null)) {
					changes.add(new EntryChange(path, Kind.RESOURCE, status, oldStamp, newStamp, List.of()));
					continue;
				}
				List<MemberChange> members = compareMembers(oldClass, newClass);
				if (!members.isEmpty() || status != Status.CHANGED ||
						!Arrays.equals(classHeader(oldClass), classHeader(newClass))) {
					changes.add(new EntryChange(path, Kind.CLASS, status, oldStamp, newStamp, members));
				}
			}
		}
		return List.copyOf(changes);
	}

	/**
	 * Reads a preview on demand from the same entry that was compared. String literals are displayed as stored.
	 *
	 * @param jar Archive to preview.
	 * @param change Entry to preview.
	 * @param member Member to preview, or {@code null} for the whole entry.
	 * @param before Whether to use the baseline side of the change.
	 * @return Preview, or {@code null} if that side is absent.
	 * @throws IOException If the archive cannot be read or the entry has changed since the comparison.
	 */
	@Nullable
	public static Preview preview(@Nonnull Path jar, @Nonnull EntryChange change, @Nullable MemberChange member,
	                              boolean before) throws IOException {
		EntryStamp stamp = before ? change.before() : change.after();
		if (stamp == null || (member != null && absent(member.status(), before)))
			return null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(change.path());
			if (!stamp.equals(EntryStamp.of(entry)))
				throw new IOException("Entry changed since comparison: " + change.path());
			if (change.kind() == Kind.CLASS) {
				ClassNode node = readClass(zip, entry, true);
				if (node == null)
					throw new IOException("Cannot read class: " + change.path());
				StringWriter output = new StringWriter();
				TraceClassVisitor trace = new TraceClassVisitor(new PrintWriter(output));
				if (member == null) {
					node.accept(trace);
				} else {
					MemberKey key = new MemberKey(member.kind(), member.name(), member.descriptor());
					Object target = members(node).get(key);
					if (target == null)
						throw new IOException("Member changed since comparison: " + member.name() + member.descriptor());
					acceptMember(trace, target);
					trace.visitEnd();
				}
				return new Preview(output.toString());
			}
			String text = null;
			if (stamp.size() <= MAX_TEXT_RESOURCE_BYTES) {
				byte[] content = readBounded(zip, entry, MAX_TEXT_RESOURCE_BYTES);
				if (content != null)
					text = content.length == 0 ? "" : StringUtil.decodeString(content).text();
			}
			return new Preview(text);
		}
	}

	private static Map<String, ZipEntry> entries(ZipFile zip) {
		Map<String, ZipEntry> entries = new HashMap<>();
		var enumeration = zip.entries();
		while (enumeration.hasMoreElements()) {
			checkCancelled();
			ZipEntry entry = enumeration.nextElement();
			if (!entry.isDirectory() && entries.putIfAbsent(entry.getName(), entry) != null) {
				// Match the JDK's lookup for duplicate paths in both the comparison and the preview.
				entries.put(entry.getName(), zip.getEntry(entry.getName()));
			}
		}
		return entries;
	}

	private static boolean sameContents(ZipFile baseline, ZipEntry before, ZipFile updated, ZipEntry after) throws IOException {
		if (before == null || after == null || before.getSize() != after.getSize() || before.getCrc() != after.getCrc())
			return false;
		try (InputStream left = baseline.getInputStream(before); InputStream right = updated.getInputStream(after)) {
			int bufferSize = (int) Math.clamp(before.getSize(), 1, 8192);
			byte[] leftBuffer = new byte[bufferSize];
			byte[] rightBuffer = new byte[bufferSize];
			while (true) {
				checkCancelled();
				int leftSize = left.readNBytes(leftBuffer, 0, bufferSize);
				int rightSize = right.readNBytes(rightBuffer, 0, bufferSize);
				if (!Arrays.equals(leftBuffer, 0, leftSize, rightBuffer, 0, rightSize))
					return false;
				if (leftSize < bufferSize)
					return true;
			}
		}
	}

	@Nullable
	private static ClassNode readClass(ZipFile zip, @Nullable ZipEntry entry, boolean strict) throws IOException {
		if (entry == null || !entry.getName().endsWith(".class"))
			return null;
		byte[] bytes = readBounded(zip, entry, MAX_CLASS_BYTES);
		if (bytes == null || bytes.length < 10 || (bytes[0] & 0xFF) != 0xCA || (bytes[1] & 0xFF) != 0xFE ||
				(bytes[2] & 0xFF) != 0xBA || (bytes[3] & 0xFF) != 0xBE)
			return null;
		try {
			ClassNode node = new ClassNode();
			ClassVisitor visitor = strict ? node : new ClassRemapper(node, IGNORE_STRINGS);
			new ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		} catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
			// Keep an unsupported or malformed class visible as a binary entry.
			return null;
		}
	}

	@Nullable
	private static byte[] readBounded(ZipFile zip, ZipEntry entry, int limit) throws IOException {
		if (entry.getSize() > limit)
			return null;
		try (InputStream stream = zip.getInputStream(entry)) {
			byte[] bytes = stream.readNBytes(limit + 1);
			return bytes.length <= limit ? bytes : null;
		}
	}

	private static List<MemberChange> compareMembers(@Nullable ClassNode before, @Nullable ClassNode after) {
		Map<MemberKey, Object> oldMembers = members(before);
		Map<MemberKey, Object> newMembers = members(after);
		List<MemberChange> changes = new ArrayList<>();
		for (MemberKey key : union(oldMembers, newMembers)) {
			checkCancelled();
			Object oldMember = oldMembers.get(key);
			Object newMember = newMembers.get(key);
			Status status = status(oldMember, newMember);
			if (status != Status.CHANGED || !Arrays.equals(memberBytes(before, oldMember), memberBytes(after, newMember)))
				changes.add(new MemberChange(key.kind(), key.name(), key.descriptor(), status));
		}
		return List.copyOf(changes);
	}

	private static Map<MemberKey, Object> members(@Nullable ClassNode node) {
		if (node == null)
			return Map.of();
		Map<MemberKey, Object> members = new HashMap<>();
		for (FieldNode field : node.fields)
			members.put(new MemberKey(Kind.FIELD, field.name, field.desc), field);
		for (MethodNode method : node.methods)
			members.put(new MemberKey(Kind.METHOD, method.name, method.desc), method);
		return members;
	}

	private static void acceptMember(ClassVisitor visitor, Object member) {
		if (member instanceof FieldNode field)
			field.accept(visitor);
		else if (member instanceof MethodNode method)
			method.accept(visitor);
	}

	private static byte[] memberBytes(ClassNode owner, Object member) {
		// A fresh constant pool makes encoding independent of other members and ZIP entry order.
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, 0, owner.name, null, null, null);
		acceptMember(writer, member);
		return writer.toByteArray();
	}

	private static byte[] classHeader(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return null;
			}
		});
		return writer.toByteArray();
	}

	private static <K extends Comparable<K>> TreeSet<K> union(Map<K, ?> before, Map<K, ?> after) {
		TreeSet<K> keys = new TreeSet<>(before.keySet());
		keys.addAll(after.keySet());
		return keys;
	}

	private static Status status(@Nullable Object before, @Nullable Object after) {
		return before == null ? Status.ADDED : after == null ? Status.REMOVED : Status.CHANGED;
	}

	private static boolean absent(Status status, boolean before) {
		return before ? status == Status.ADDED : status == Status.REMOVED;
	}

	private static void checkCancelled() {
		if (Thread.currentThread().isInterrupted())
			throw new CancellationException();
	}

	private record MemberKey(Kind kind, String name, String descriptor) implements Comparable<MemberKey> {
		@Override
		public int compareTo(@Nonnull MemberKey other) {
			int order = kind.compareTo(other.kind);
			if (order == 0)
				order = name.compareTo(other.name);
			return order == 0 ? descriptor.compareTo(other.descriptor) : order;
		}
	}

	/** Kind of changed entry or member. */
	public enum Kind { CLASS, RESOURCE, FIELD, METHOD }

	/** Direction of an entry or member change. */
	public enum Status { ADDED, REMOVED, CHANGED }

	/** ZIP metadata identifying the content of an entry at comparison time. */
	public record EntryStamp(long size, long crc) {
		@Nullable
		private static EntryStamp of(@Nullable ZipEntry entry) {
			return entry == null ? null : new EntryStamp(entry.getSize(), entry.getCrc());
		}
	}

	/** A changed class or resource at an archive path. */
	public record EntryChange(@Nonnull String path, @Nonnull Kind kind, @Nonnull Status status,
	                          @Nullable EntryStamp before, @Nullable EntryStamp after, @Nonnull List<MemberChange> members) {
	}

	/** A changed JVM field or method. */
	public record MemberChange(@Nonnull Kind kind, @Nonnull String name, @Nonnull String descriptor, @Nonnull Status status) {
	}

	/** Text when available, or {@code null} text for binary or oversized entries. */
	public record Preview(@Nullable String text) {
	}
}
