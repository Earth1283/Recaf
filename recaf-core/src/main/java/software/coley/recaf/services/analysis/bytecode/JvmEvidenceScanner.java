package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Shared resource traversal and bounded evidence sampling for bytecode scans. */
final class JvmEvidenceScanner {
	private JvmEvidenceScanner() {}

	static Counts scan(WorkspaceResource root, BooleanSupplier cancelled, Supplier<? extends Collector> factory) {
		Set<WorkspaceResource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<ResourceEntry> pending = new ArrayDeque<>();
		pending.add(new ResourceEntry("", root));
		int classesRead = 0;
		int classesSkipped = 0;
		while (!pending.isEmpty()) {
			checkCancelled(cancelled);
			ResourceEntry entry = pending.removeFirst();
			if (!seen.add(entry.resource())) continue;
			Collector collector = factory.get();
			for (JvmClassBundle bundle : entry.resource().jvmAllClassBundleStream().toList()) {
				String version = bundle instanceof VersionedJvmClassBundle versioned ? " (Java " + versioned.version() + ")" : "";
				for (JvmClassInfo info : bundle.values()) {
					checkCancelled(cancelled);
					ClassNode node = new ClassNode();
					try {
						info.getClassReader().accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
						collector.inspect(info, node, version, cancelled);
					} catch (CancellationException ex) {
						throw ex;
					} catch (RuntimeException ex) {
						classesSkipped++;
						continue;
					}
					classesRead++;
				}
			}
			collector.finish(entry.path(), entry.resource(), cancelled);
			entry.resource().getEmbeddedResources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(child -> {
				String path = entry.path().isEmpty() ? child.getKey() : entry.path() + " > " + child.getKey();
				pending.addLast(new ResourceEntry(path, child.getValue()));
			});
		}
		return new Counts(classesRead, classesSkipped);
	}

	static void checkCancelled(BooleanSupplier cancelled) {
		if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
	}

	interface Collector {
		void inspect(JvmClassInfo info, ClassNode node, String version, BooleanSupplier cancelled);

		void finish(String path, WorkspaceResource resource, BooleanSupplier cancelled);
	}

	record Counts(int classesRead, int classesSkipped) {}

	private record ResourceEntry(String path, WorkspaceResource resource) {}

	static final class Tally {
		private static final int EXAMPLE_LIMIT = 4;
		private static final int DISTINCT_CLASS_LIMIT = 3;

		private long occurrences;
		private Set<String> classNames;
		private final NavigableSet<String> examples = new TreeSet<>();

		void add(long count, @Nullable String className, String example) {
			occurrences += count;
			if (className != null) {
				if (classNames == null) classNames = new HashSet<>();
				if (classNames.size() < DISTINCT_CLASS_LIMIT) classNames.add(className);
			}
			if (examples.size() < EXAMPLE_LIMIT || example.compareTo(examples.last()) < 0) {
				examples.add(example);
				if (examples.size() > EXAMPLE_LIMIT) examples.pollLast();
			}
		}

		long occurrences() { return occurrences; }

		int distinctClasses() { return classNames == null ? 0 : classNames.size(); }

		List<String> examples() { return List.copyOf(examples); }
	}
}
