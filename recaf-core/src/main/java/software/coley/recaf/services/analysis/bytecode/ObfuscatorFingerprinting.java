package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.deobfuscation.transform.specific.skid.SkidInvokeDynamicResolver;
import software.coley.recaf.services.deobfuscation.transform.specific.zkm.ZkmInvokeDynamicResolver;
import software.coley.recaf.util.AsmInsnUtil;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static org.objectweb.asm.Opcodes.*;

/** On-demand, read-only identification of obfuscator patterns in JVM class files. */
@ApplicationScoped
public class ObfuscatorFingerprinting {
	private static final int EXAMPLE_LIMIT = 4;

	public enum Family {
		BOZAR("Bozar"), RADON("Radon"), SKIDFUSCATOR("Skidfuscator"), ZELIX("Zelix KlassMaster");

		private final String displayName;

		Family(String displayName) { this.displayName = displayName; }

		public String displayName() { return displayName; }
	}

	public enum Confidence { STRONG, POSSIBLE }

	public enum Indicator {
		BOZAR_STRING_ARRAY(Family.BOZAR), RADON_INT_SHUFFLE(Family.RADON), RADON_LONG_SHUFFLE(Family.RADON),
		SKID_REFERENCE_BOOTSTRAP(Family.SKIDFUSCATOR), SKID_NATIVE_DRIVER(Family.SKIDFUSCATOR),
		ZELIX_REFERENCE_METADATA(Family.ZELIX);

		private final Family family;

		Indicator(Family family) { this.family = family; }
	}

	public record Evidence(Indicator indicator, long occurrences, List<String> examples) {}
	/** An empty resource name identifies the selected root. Embedded resource names are relative to it. */
	public record Match(String resource, Family family, Confidence confidence, List<Evidence> evidence) {}
	public record Result(int classesRead, int classesSkipped, List<Match> matches) {}

	/**
	 * @param root
	 * 		Selected resource. Embedded archives are reported individually, and workspace libraries are outside the scan.
	 * @param cancelled
	 * 		Signal to abandon the scan.
	 * @return Matches and the number of readable and unreadable JVM classes.
	 */
	@Nonnull
	public Result scan(@Nonnull WorkspaceResource root, @Nonnull BooleanSupplier cancelled) {
		Set<WorkspaceResource> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Deque<ResourceEntry> pending = new ArrayDeque<>();
		pending.add(new ResourceEntry("", root));
		List<Match> matches = new ArrayList<>();
		int classesRead = 0;
		int classesSkipped = 0;
		while (!pending.isEmpty()) {
			checkCancelled(cancelled);
			ResourceEntry entry = pending.removeFirst();
			if (!seen.add(entry.resource())) continue;
			Map<Indicator, Tally> findings = new EnumMap<>(Indicator.class);
			for (JvmClassBundle bundle : entry.resource().jvmAllClassBundleStream().toList()) {
				String version = bundle instanceof VersionedJvmClassBundle versioned ? " (Java " + versioned.version() + ")" : "";
				for (JvmClassInfo info : bundle.values()) {
					checkCancelled(cancelled);
					ClassNode node = new ClassNode();
					try {
						info.getClassReader().accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
						inspect(node, version, findings, cancelled);
					} catch (CancellationException ex) {
						throw ex;
					} catch (RuntimeException ex) {
						classesSkipped++;
						continue;
					}
					classesRead++;
				}
			}
			for (String file : entry.resource().getFileBundle().keySet()) {
				checkCancelled(cancelled);
				if (file.startsWith("lock/driver-") && file.endsWith(".skid"))
					record(findings, Indicator.SKID_NATIVE_DRIVER, file);
			}
			for (Family family : Family.values()) {
				List<Evidence> evidence = findings.entrySet().stream().filter(finding -> finding.getKey().family == family)
						.map(finding -> finding.getValue().toEvidence(finding.getKey())).toList();
				if (!evidence.isEmpty())
					matches.add(new Match(entry.path(), family, confidence(family, evidence), evidence));
			}
			entry.resource().getEmbeddedResources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(child -> {
				String path = entry.path().isEmpty() ? child.getKey() : entry.path() + " > " + child.getKey();
				pending.addLast(new ResourceEntry(path, child.getValue()));
			});
		}
		matches.sort(Comparator.comparing(Match::confidence).thenComparing(Match::resource).thenComparing(Match::family));
		return new Result(classesRead, classesSkipped, List.copyOf(matches));
	}

	private static Confidence confidence(Family family, List<Evidence> evidence) {
		return switch (family) {
			case BOZAR, ZELIX -> Confidence.STRONG;
			case RADON -> evidence.stream().mapToLong(Evidence::occurrences).sum() > 1 ? Confidence.STRONG : Confidence.POSSIBLE;
			case SKIDFUSCATOR -> evidence.stream().anyMatch(item -> item.indicator() == Indicator.SKID_REFERENCE_BOOTSTRAP)
					? Confidence.STRONG : Confidence.POSSIBLE;
		};
	}

	private static void inspect(ClassNode node, String version, Map<Indicator, Tally> findings, BooleanSupplier cancelled) {
		boolean objectCache = node.fields.stream().anyMatch(field -> (field.access & ACC_STATIC) != 0 && "[Ljava/lang/Object;".equals(field.desc));
		boolean descriptorCache = node.fields.stream().anyMatch(field -> (field.access & ACC_STATIC) != 0 && "[Ljava/lang/String;".equals(field.desc));
		if (objectCache && descriptorCache && ZkmInvokeDynamicResolver.hasReferenceMetadata(node))
			record(findings, Indicator.ZELIX_REFERENCE_METADATA, node.name + version);
		for (MethodNode method : node.methods) {
			checkCancelled(cancelled);
			String location = node.name + "." + method.name + method.desc + version;
			if (SkidInvokeDynamicResolver.matchesBootstrap(method))
				record(findings, Indicator.SKID_REFERENCE_BOOTSTRAP, location);
			for (AbstractInsnNode insn : method.instructions) {
				switch (insn.getOpcode()) {
					case IXOR -> {
						if (matchesRadonInt(insn)) record(findings, Indicator.RADON_INT_SHUFFLE, location);
					}
					case LXOR -> {
						if (matchesRadonLong(insn)) record(findings, Indicator.RADON_LONG_SHUFFLE, location);
					}
					case NEWARRAY -> {
						if (matchesBozarArray((IntInsnNode) insn)) record(findings, Indicator.BOZAR_STRING_ARRAY, location);
					}
				}
			}
		}
	}

	// These two recipes include Radon's dead stack operands and their category-specific shuffles.
	private static boolean matchesRadonInt(AbstractInsnNode xor) {
		AbstractInsnNode key = xor.getPrevious();
		AbstractInsnNode pop = previous(key, POP2);
		AbstractInsnNode dup = previous(pop, DUP_X1);
		AbstractInsnNode swap = previous(dup, SWAP);
		return isIntLiteral(key) && swap != null && isIntLiteral(swap.getPrevious())
				&& isIntLiteral(swap.getPrevious().getPrevious());
	}

	private static boolean matchesRadonLong(AbstractInsnNode xor) {
		AbstractInsnNode key = xor.getPrevious();
		AbstractInsnNode pop = previous(previous(key, POP2), POP2);
		AbstractInsnNode dup = previous(pop, DUP2_X2);
		return isLongLiteral(key) && dup != null && isLongLiteral(dup.getPrevious())
				&& isLongLiteral(dup.getPrevious().getPrevious());
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn, int opcode) {
		AbstractInsnNode prev = insn == null ? null : insn.getPrevious();
		return prev != null && prev.getOpcode() == opcode ? prev : null;
	}

	private static boolean isIntLiteral(AbstractInsnNode insn) {
		return insn != null && AsmInsnUtil.isConstIntValue(insn);
	}

	private static boolean isLongLiteral(AbstractInsnNode insn) {
		return insn != null && (insn.getOpcode() == LCONST_0 || insn.getOpcode() == LCONST_1
				|| insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long);
	}

	// Bozar fills a temporary byte array in shuffled order. The initial store writes junk at the
	// first index, then the actual stores overwrite that index and populate every remaining byte.
	private static boolean matchesBozarArray(IntInsnNode array) {
		if (array.operand != T_BYTE || !(array.getNext() instanceof VarInsnNode store) || store.getOpcode() != ASTORE)
			return false;
		AbstractInsnNode cursor = store.getNext();
		int firstIndex = -1;
		BitSet indexes = new BitSet();
		boolean encoded = false;
		while (isLoad(cursor, store.var)) {
			Numeric index = readBozarNumber(cursor.getNext());
			Numeric value = index == null ? null : readBozarNumber(index.next());
			if (value == null || index.value() < 0 || index.value() >= 65535
					|| value.next() == null || value.next().getOpcode() != BASTORE)
				return false;
			if (firstIndex < 0) {
				firstIndex = index.value();
			} else {
				if ((indexes.isEmpty() && index.value() != firstIndex) || indexes.get(index.value()))
					return false;
				indexes.set(index.value());
			}
			encoded |= index.encoded() || value.encoded();
			cursor = value.next().getNext();
		}
		if (!encoded || indexes.isEmpty() || indexes.nextClearBit(0) != indexes.length()
				|| !(cursor instanceof TypeInsnNode allocation) || allocation.getOpcode() != NEW
				|| !"java/lang/String".equals(allocation.desc))
			return false;
		Numeric length = readBozarNumber(numberStartBefore(array));
		if (length == null || length.next() != array || length.value() != indexes.length())
			return false;
		AbstractInsnNode dup = allocation.getNext();
		return dup != null && dup.getOpcode() == DUP && isLoad(dup.getNext(), store.var)
				&& dup.getNext().getNext() instanceof MethodInsnNode constructor && constructor.getOpcode() == INVOKESPECIAL
				&& "java/lang/String".equals(constructor.owner) && "<init>".equals(constructor.name) && "([B)V".equals(constructor.desc);
	}

	private static boolean isLoad(AbstractInsnNode insn, int slot) {
		return insn instanceof VarInsnNode load && load.getOpcode() == ALOAD && load.var == slot;
	}

	@Nullable
	private static AbstractInsnNode numberStartBefore(AbstractInsnNode instruction) {
		AbstractInsnNode end = instruction.getPrevious();
		if (end != null && (end.getOpcode() == IXOR || end.getOpcode() == IUSHR)) {
			AbstractInsnNode right = operandStart(end.getPrevious());
			return right == null ? null : operandStart(right.getPrevious());
		}
		return operandStart(end);
	}

	@Nullable
	private static AbstractInsnNode operandStart(AbstractInsnNode end) {
		// A length() operand begins at the preceding string literal. The forward reader validates the pair.
		return end instanceof MethodInsnNode ? end.getPrevious() : end;
	}

	@Nullable
	private static Numeric readBozarNumber(AbstractInsnNode insn) {
		Numeric left = readBozarOperand(insn);
		if (left == null) return null;
		Numeric right = readBozarOperand(left.next());
		if (right == null || right.next() == null) return left;
		return switch (right.next().getOpcode()) {
			case IXOR -> new Numeric(left.value() ^ right.value(), right.next().getNext(), true);
			case IUSHR -> new Numeric(left.value() >>> right.value(), right.next().getNext(), true);
			default -> left;
		};
	}

	@Nullable
	private static Numeric readBozarOperand(AbstractInsnNode insn) {
		if (isIntLiteral(insn)) {
			int value = switch (insn) {
				case IntInsnNode push -> push.operand;
				case LdcInsnNode ldc -> (Integer) ldc.cst;
				default -> insn.getOpcode() - ICONST_0;
			};
			return new Numeric(value, insn.getNext(), false);
		}
		if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String literal && literal.length() <= Byte.MAX_VALUE
				&& insn.getNext() instanceof MethodInsnNode call && call.getOpcode() == INVOKEVIRTUAL
				&& "java/lang/String".equals(call.owner) && "length".equals(call.name) && "()I".equals(call.desc)
				&& literal.chars().allMatch(c -> c == 0))
			return new Numeric(literal.length(), call.getNext(), true);
		return null;
	}

	private static void record(Map<Indicator, Tally> findings, Indicator indicator, String example) {
		Tally tally = findings.computeIfAbsent(indicator, _ -> new Tally());
		tally.occurrences++;
		tally.examples.add(example);
		if (tally.examples.size() > EXAMPLE_LIMIT) tally.examples.pollLast();
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
	}

	private record ResourceEntry(String path, WorkspaceResource resource) {}
	private record Numeric(int value, AbstractInsnNode next, boolean encoded) {}

	private static final class Tally {
		private long occurrences;
		private final NavigableSet<String> examples = new TreeSet<>();

		private Evidence toEvidence(Indicator indicator) {
			return new Evidence(indicator, occurrences, List.copyOf(examples));
		}
	}
}
