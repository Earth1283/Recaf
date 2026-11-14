package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.deobfuscation.transform.generic.*;
import software.coley.recaf.services.mapping.BasicMappingsRemapper;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.util.EscapeUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.*;
import software.coley.recaf.workspace.model.resource.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

/** Builds an isolated analysis workspace with simplified code and reversible, explicitly synthetic aliases. */
@ApplicationScoped
public class ReadableWorkspaceService {
	private final TransformationApplierService appliers;

	@Inject
	public ReadableWorkspaceService(@Nonnull TransformationApplierService appliers) { this.appliers = appliers; }

	public record Result(Workspace workspace, String report, Map<String, String> classAliases,
	                     int dynamicStringsBefore, int dynamicStringsAfter, int helperCallsBefore, int helperCallsAfter) {}

	/**
	 * Copies source resources before transforming anything. Original classes are retained in a separate supporting
	 * resource so the analysis copy remains independent when the original workspace is closed.
	 */
	@Nonnull
	public Result build(@Nonnull Workspace source, @Nonnull WorkspaceResource selected, int maxSteps, boolean aliases,
	                    @Nonnull BooleanSupplier cancelled, @Nonnull Consumer<String> progress) throws TransformationException {
		if (maxSteps < 100 || maxSteps > 1_000_000) throw new IllegalArgumentException("Invalid instruction budget");
		check(cancelled);
		progress.accept("Copying selected resource and original reference classes");
		WorkspaceResource primary = copy(selected, cancelled);
		List<WorkspaceResource> support = new ArrayList<>();
		support.add(copy(source.getPrimaryResource(), cancelled));
		for (WorkspaceResource library : source.getSupportingResources()) support.add(copy(library, cancelled));
		Workspace workspace = new BasicWorkspace(primary, support);
		try {
			Counts before = count(primary);
			List<String> warnings = new ArrayList<>();
			TransformationApplier applier = appliers.newApplier(workspace);
			applier.setDropFaultyClasses(true);
			int classCount = primary.jvmAllClassBundleStreamRecursive().mapToInt(Map::size).sum();
			AtomicReference<String> phaseName = new AtomicReference<>("");
			AtomicLong lastProgress = new AtomicLong();
			Map<String, AtomicInteger> started = new ConcurrentHashMap<>();
			TransformationFeedback feedback = new TransformationFeedback() {
				@Override public boolean hasRequestedCancellation() { return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted(); }
				@Override public boolean shouldTransform(Workspace workspace, WorkspaceResource resource, ClassBundle<?> bundle,
				                                         ClassInfo info, ClassTransformer transformer, int pass) {
					if (hasRequestedCancellation()) return false;
					int count = started.computeIfAbsent(phaseName.get() + transformer.identifier() + pass, _ -> new AtomicInteger()).incrementAndGet();
					long now = System.nanoTime(), previous = lastProgress.get();
					if (now - previous > 250_000_000L && lastProgress.compareAndSet(previous, now))
						progress.accept(phaseName.get() + " · " + transformer.identifier() + " · pass " + pass + " · class " + count + "/" + classCount);
					return true;
				}
			};
			Map<String, Object> values = Map.of(
					OpaqueConstantFoldingTransformer.KEY_INLINE_STRING_CTORS, true,
					CallResultInliningTransformer.KEY_MAX_STEPS, maxSteps,
					CallResultInliningTransformer.KEY_ALLOW_UNREACHABLE_UNSUPPORTED, true,
					StaticValueCollectionTransformer.KEY_MAX_STEPS, maxSteps);
			List<TransformationPhase> phases = List.of(
					new TransformationPhase("Fold numeric noise and encoded literals", 2,
							List.of(OpaqueConstantFoldingTransformer.class, CallResultInliningTransformer.class, VariableFoldingTransformer.class), new TransformationParameters(values)),
					new TransformationPhase("Resolve proven static string bootstraps", 1, InvokeDynamicInliningTransformer.class),
					new TransformationPhase("Decode strings and simplify control flow", 2,
							List.of(StaticValueCollectionTransformer.class, StaticValueInliningTransformer.class, CallResultInliningTransformer.class,
									OpaqueConstantFoldingTransformer.class, OpaquePredicateFoldingTransformer.class, DeadCodeRemovingTransformer.class, VariableFoldingTransformer.class),
							new TransformationParameters(withInitializers(values))));
			for (TransformationPhase phase : phases) {
				check(cancelled);
				phaseName.set(phase.id());
				lastProgress.set(0);
				progress.accept(phase.id());
				applier.setMaxPasses(phase.maxPasses());
				JvmTransformResult result = applier.transformJvm(phase.jvmTransformers(), phase.parameters(), feedback);
				check(cancelled);
				// Keep failed classes at their previous state instead of exposing a partially rewritten method.
				Set<String> failed = new HashSet<>();
				result.getTransformerFailures().forEach((path, failures) -> {
					failed.add(path.getValue().getName());
					failures.forEach((transformer, error) -> warnings.add(phase.id() + ": " + path.getValue().getName() + ": " + transformer.getSimpleName() + ": " + error.getMessage()));
				});
				// With dropFaultyClasses, serialization failures are logged but omitted from the result's failure map.
				// This pipeline edits class nodes, so a modified class without an emitted result was retained unchanged.
				Map<JvmClassBundle, Set<String>> emitted = new IdentityHashMap<>();
				result.getTransformedClasses().keySet().forEach(path -> emitted.computeIfAbsent(path.getValueOfType(JvmClassBundle.class), _ -> new HashSet<>()).add(path.getValue().getName()));
				Map<JvmClassBundle, Set<String>> missing = new IdentityHashMap<>();
				result.getModifiedClassesPerTransformer().values().forEach(paths -> paths.forEach(path -> {
					JvmClassBundle bundle = path.getValueOfType(JvmClassBundle.class);
					String name = path.getValue().getName();
					if (!failed.contains(name) && !emitted.getOrDefault(bundle, Set.of()).contains(name)
							&& missing.computeIfAbsent(bundle, _ -> new HashSet<>()).add(name))
						warnings.add(phase.id() + ": " + name + ": Modified bytecode could not be emitted; previous bytecode retained. See the transformation log.");
				}));
				result.getTransformedClasses().forEach((path, info) -> {
					if (!failed.contains(path.getValue().getName())) {
						JvmClassBundle bundle = path.getValueOfType(JvmClassBundle.class);
						if (bundle != null) bundle.put(info);
					}
				});
			}
			check(cancelled);
			Counts after = count(primary);
			progress.accept("Creating readable aliases and origin map");
			AliasResult names = aliases ? alias(primary, cancelled) : new AliasResult(Map.of(), "Original names retained\n");
			String report = "READABLE ANALYSIS COPY\n\n"
					+ "This workspace is an analysis copy, not a replacement plugin binary. The source JAR was not changed.\n"
					+ "Original classes are available under supporting resources. Aliases are synthetic labels, not recovered author names.\n\n"
					+ "Custom dynamic string calls: " + before.dynamicStrings + " -> " + after.dynamicStrings + "\n"
					+ "Static workspace string-helper calls: " + before.helperCalls + " -> " + after.helperCalls + "\n"
					+ "Integer/Long bit helpers and bitwise operations: " + before.bitwise + " -> " + after.bitwise + "\n"
					+ "Classes given readable aliases: " + names.classes().size() + "\n"
					+ "Transformation warnings: " + warnings.size() + "\n\n"
					+ "Unresolved calls and runtime-dependent values remain in the code. The counts do not imply complete deobfuscation.\n"
					+ "Use Bytecode investigation to inspect remaining bootstrap families and helper inputs.\n\n"
					+ String.join("\n", warnings.stream().limit(200).toList());
			putText(primary, "RECAF-READABILITY.txt", report);
			putText(primary, "RECAF-ALIASES.tsv", names.table());
			return new Result(workspace, report, names.classes(), before.dynamicStrings, after.dynamicStrings, before.helperCalls, after.helperCalls);
		} catch (Exception error) {
			workspace.close();
			if (error instanceof TransformationException transformation) throw transformation;
			if (error instanceof CancellationException cancellation) throw cancellation;
			throw new TransformationException("Could not build readable analysis copy", error);
		}
	}

	private static Map<String, Object> withInitializers(Map<String, Object> values) {
		Map<String, Object> result = new HashMap<>(values);
		result.put(CallResultInliningTransformer.KEY_EVALUATE_CLASS_INITIALIZERS, true);
		return result;
	}

	private static AliasResult alias(WorkspaceResource resource, BooleanSupplier cancelled) {
		List<JvmClassInfo> classes = resource.jvmAllClassBundleStreamRecursive().flatMap(bundle -> bundle.values().stream())
				.sorted(Comparator.comparing(JvmClassInfo::getName)).toList();
		Map<String, Integer> occurrences = new HashMap<>();
		classes.forEach(info -> occurrences.merge(info.getName(), 1, Integer::sum));
		Set<String> reserved = new HashSet<>(occurrences.keySet());
		IntermediateMappings mappings = new IntermediateMappings();
		Map<String, String> classNames = new LinkedHashMap<>();
		StringBuilder table = new StringBuilder("kind\toriginal\talias\n");
		int classIndex = 1;
		for (JvmClassInfo info : classes) {
			check(cancelled);
			if (occurrences.get(info.getName()) != 1) continue;
			String simple = info.getName().substring(info.getName().lastIndexOf('/') + 1);
			if (!simple.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
				String prefix = info.getPackageName() == null ? "" : info.getPackageName() + "/";
				String mapped;
				do { mapped = prefix + "ReadableClass_" + String.format(Locale.ROOT, "%04d", classIndex++); } while (!reserved.add(mapped));
				mappings.addClass(info.getName(), mapped);
				classNames.put(info.getName(), mapped);
				table.append("class\t").append(EscapeUtil.escapeStandard(info.getName())).append('\t').append(mapped).append('\n');
			}
			ClassNode node = new ClassNode(); info.getClassReader().accept(node, ClassReader.SKIP_DEBUG);
			Set<String> methodNames = new HashSet<>(); node.methods.forEach(method -> methodNames.add(method.name));
			int methodIndex = 1;
			for (MethodNode method : node.methods.stream().sorted(Comparator.comparing((MethodNode method) -> method.name).thenComparing(method -> method.desc)).toList()) {
				if (method.name.startsWith("<") || (method.access & (ACC_PRIVATE | ACC_STATIC)) == 0
						|| (method.name.length() > 2 && method.name.matches("[A-Za-z_$][A-Za-z0-9_$]*"))) continue;
				String role = method.desc.startsWith("(Ljava/lang/invoke/MethodHandles$Lookup;") ? "bootstrapString" :
						method.desc.equals("(Ljava/lang/String;BI)Ljava/lang/String;") ? "decodeLiteral" :
						Type.getReturnType(method.desc).equals(Type.getType(String.class)) ? "stringHelper" : "method";
				String name;
				do { name = role + "_" + methodIndex++; } while (!methodNames.add(name));
				mappings.addMethod(info.getName(), method.desc, method.name, name);
				table.append("method\t").append(EscapeUtil.escapeStandard(info.getName())).append('.').append(EscapeUtil.escapeStandard(method.name)).append(EscapeUtil.escapeStandard(method.desc)).append('\t').append(name).append('\n');
			}
			Set<String> fieldNames = new HashSet<>(); info.getFields().forEach(field -> fieldNames.add(field.getName()));
			int fieldIndex = 1;
			for (var field : info.getFields()) {
				if ((field.getAccess() & ACC_PRIVATE) == 0 || (field.getName().length() > 2 && field.getName().matches("[A-Za-z_$][A-Za-z0-9_$]*"))) continue;
				String role = field.getDescriptor().equals("[Ljava/lang/String;") ? "stringTable" : field.getDescriptor().equals("[J") ? "longTable" : "field";
				String name;
				do { name = role + "_" + fieldIndex++; } while (!fieldNames.add(name));
				mappings.addField(info.getName(), field.getDescriptor(), field.getName(), name);
				table.append("field\t").append(EscapeUtil.escapeStandard(info.getName())).append('.').append(EscapeUtil.escapeStandard(field.getName())).append(':').append(EscapeUtil.escapeStandard(field.getDescriptor())).append('\t').append(name).append('\n');
			}
		}
		resource.jvmAllClassBundleStreamRecursive().forEach(bundle -> {
			List<JvmClassInfo> inputs = new ArrayList<>(bundle.values());
			Map<String, JvmClassInfo> remapped = new LinkedHashMap<>();
			for (JvmClassInfo info : inputs) {
				check(cancelled);
				ClassWriter writer = new ClassWriter(0);
				info.getClassReader().accept(new ClassRemapper(writer, new BasicMappingsRemapper(mappings)), 0);
				JvmClassInfo renamed = new JvmClassInfoBuilder(writer.toByteArray()).build();
				remapped.put(renamed.getName(), renamed);
			}
			for (JvmClassInfo info : inputs) bundle.remove(info.getName());
			remapped.values().forEach(bundle::put);
		});
		return new AliasResult(Collections.unmodifiableMap(classNames), table.toString());
	}

	private record AliasResult(Map<String, String> classes, String table) {}
	private record Counts(int dynamicStrings, int helperCalls, int bitwise) {}

	private static Counts count(WorkspaceResource resource) {
		int dynamic = 0, helpers = 0, bits = 0;
		for (JvmClassBundle bundle : resource.jvmAllClassBundleStreamRecursive().toList()) {
			for (JvmClassInfo info : bundle.values()) {
				ClassNode node = new ClassNode(); info.getClassReader().accept(node, ClassReader.SKIP_DEBUG);
				for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof InvokeDynamicInsnNode indy && Type.getReturnType(indy.desc).equals(Type.getType(String.class))
							&& !indy.bsm.getOwner().startsWith("java/")) dynamic++;
					if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && Type.getReturnType(call.desc).equals(Type.getType(String.class))
							&& !call.owner.startsWith("java/") && !call.owner.startsWith("javax/")) helpers++;
					if ((insn.getOpcode() >= ISHL && insn.getOpcode() <= LXOR) || (insn instanceof MethodInsnNode call
							&& (call.owner.equals("java/lang/Integer") || call.owner.equals("java/lang/Long"))
							&& (call.name.startsWith("reverse") || call.name.startsWith("rotate")))) bits++;
				}
			}
		}
		return new Counts(dynamic, helpers, bits);
	}

	private static WorkspaceResource copy(WorkspaceResource source, BooleanSupplier cancelled) {
		WorkspaceResourceBuilder builder = source instanceof WorkspaceFileResource file ? new WorkspaceFileResourceBuilder().withFileInfo(file.getFileInfo()) : new WorkspaceResourceBuilder();
		BasicJvmClassBundle classes = new BasicJvmClassBundle();
		source.getJvmClassBundle().values().forEach(info -> { check(cancelled); classes.initialPut(new JvmClassInfoBuilder(info.getBytecode()).build()); });
		BasicFileBundle files = new BasicFileBundle();
		source.getFileBundle().values().forEach(files::initialPut);
		NavigableMap<Integer, VersionedJvmClassBundle> versions = new TreeMap<>();
		source.getVersionedJvmClassBundles().forEach((version, bundle) -> {
			BasicVersionedJvmClassBundle copy = new BasicVersionedJvmClassBundle(version);
			bundle.values().forEach(info -> copy.initialPut(new JvmClassInfoBuilder(info.getBytecode()).build()));
			versions.put(version, copy);
		});
		Map<String, AndroidClassBundle> android = new LinkedHashMap<>();
		source.getAndroidClassBundles().forEach((name, bundle) -> {
			BasicAndroidClassBundle copy = new BasicAndroidClassBundle(bundle.getVersion(), bundle.getLinkData().clone(), bundle.getDexMetadata());
			bundle.values().forEach(info -> { check(cancelled); copy.initialPut(info); });
			android.put(name, copy);
		});
		Map<String, WorkspaceFileResource> embedded = new LinkedHashMap<>();
		source.getEmbeddedResources().forEach((name, child) -> embedded.put(name, (WorkspaceFileResource) copy(child, cancelled)));
		return builder.withJvmClassBundle(classes).withAndroidClassBundles(android).withFileBundle(files).withVersionedJvmClassBundles(versions).withEmbeddedResources(embedded).build();
	}

	private static void putText(WorkspaceResource resource, String name, String text) {
		resource.getFileBundle().put(new FileInfoBuilder<>().withName(name).withRawContent(text.getBytes(StandardCharsets.UTF_8)).build());
	}

	private static void check(BooleanSupplier cancelled) {
		if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
	}
}
