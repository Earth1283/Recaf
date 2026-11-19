package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
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
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.objectweb.asm.Opcodes.*;
import static software.coley.recaf.services.analysis.bytecode.JvmEvidenceScanner.checkCancelled;

/** On-demand, read-only identification of obfuscator patterns in JVM class files. */
@ApplicationScoped
public class ObfuscatorFingerprinting {
	private static final int MAX_CONFIDENCE = 99;
	private static final String STRING_DECODER = "(Ljava/lang/String;)Ljava/lang/String;";
	private static final String NATIVE_REGISTRATION = "(ILjava/lang/Class;)V";
	private static final Pattern R8_SOURCE_FILE = Pattern.compile("R8_[0-9a-f]{7,64}_[0-9a-f]{64}");

	public enum Family {
		ALLATORI("Allatori"), BOZAR("Bozar"), DASHO("DashO"), DPROTECT("dProtect"), JOBF("JObf"),
		NATIVE_OBFUSCATOR("native-obfuscator"), R8("R8"), RADON("Radon"), SKIDFUSCATOR("Skidfuscator"),
		STRINGER("Stringer"), ZELIX("Zelix KlassMaster");

		private final String displayName;

		Family(String displayName) { this.displayName = displayName; }

		public String displayName() { return displayName; }
	}

	public enum Confidence { STRONG, POSSIBLE }

	public enum Indicator {
		ALLATORI_DEMO_MARKER(Family.ALLATORI, 85),
		BOZAR_STRING_ARRAY(Family.BOZAR, 85), BOZAR_DUMMY_CLASS(Family.BOZAR, 85),
		DASHO_SEED_HELPER(Family.DASHO, 60),
		DPROTECT_STRING_DECODER(Family.DPROTECT, 70),
		JOBF_CLASS_LOADER(Family.JOBF, 80),
		NATIVE_OBFUSCATOR_LOADER(Family.NATIVE_OBFUSCATOR, 90),
		R8_MAPPING_SOURCE_FILE(Family.R8, 85),
		RADON_INT_SHUFFLE(Family.RADON, 75), RADON_LONG_SHUFFLE(Family.RADON, 75),
		SKID_REFERENCE_BOOTSTRAP(Family.SKIDFUSCATOR, 90), SKID_NATIVE_DRIVER(Family.SKIDFUSCATOR, 35),
		STRINGER_STACK_TRACE_DECODER(Family.STRINGER, 70),
		ZELIX_REFERENCE_METADATA(Family.ZELIX, 90);

		private final Family family;
		private final int baseConfidence;

		Indicator(Family family, int baseConfidence) {
			this.family = family;
			this.baseConfidence = baseConfidence;
		}
	}

	public record Evidence(Indicator indicator, long occurrences, List<String> examples) {}

	/** A match within one resource. An empty resource name identifies the selected root. */
	public record Match(String resource, Family family, int confidencePercent, List<Evidence> evidence) {
		public Confidence confidence() {
			return confidencePercent >= 80 ? Confidence.STRONG : Confidence.POSSIBLE;
		}
	}

	public record Result(int classesRead, int classesSkipped, List<Match> matches) {
		/**
		 * @return Each family's strongest evidence score in any single scanned resource, or zero where no pattern matched.
		 */
		public Map<Family, Integer> confidenceScores() {
			Map<Family, Integer> scores = new EnumMap<>(Family.class);
			for (Family family : Family.values())
				scores.put(family, 0);
			for (Match match : matches)
				scores.merge(match.family(), match.confidencePercent(), Math::max);
			return Collections.unmodifiableMap(scores);
		}
	}

	/**
	 * @param root
	 * 		Selected resource. Embedded archives are reported individually, and workspace libraries are outside the scan.
	 * @param cancelled
	 * 		Signal to abandon the scan.
	 * @return Matches and the number of readable and unreadable JVM classes.
	 */
	@Nonnull
	public Result scan(@Nonnull WorkspaceResource root, @Nonnull BooleanSupplier cancelled) {
		List<Match> matches = new ArrayList<>();
		var counts = JvmEvidenceScanner.scan(root, cancelled, () -> new FingerprintCollector(matches));
		matches.sort(Comparator.comparingInt(Match::confidencePercent).reversed()
				.thenComparing(Match::resource).thenComparing(Match::family));
		return new Result(counts.classesRead(), counts.classesSkipped(), List.copyOf(matches));
	}

	private static final class FingerprintCollector implements JvmEvidenceScanner.Collector {
		private final List<Match> matches;
		private final Map<Indicator, JvmEvidenceScanner.Tally> findings = new EnumMap<>(Indicator.class);

		private FingerprintCollector(List<Match> matches) {
			this.matches = matches;
		}

		@Override
		public void finish(String path, WorkspaceResource resource, BooleanSupplier cancelled) {
			for (String file : resource.getFileBundle().keySet()) {
				checkCancelled(cancelled);
				if (file.startsWith("lock/driver-") && file.endsWith(".skid")
						&& file.length() > "lock/driver-.skid".length() && file.indexOf('/', "lock/".length()) < 0)
					record(Indicator.SKID_NATIVE_DRIVER, 1, null, file);
			}
			for (Family family : Family.values()) {
				List<Evidence> evidence = new ArrayList<>();
				int strongest = 0;
				for (var finding : findings.entrySet()) {
					Indicator indicator = finding.getKey();
					if (indicator.family != family) continue;
					var tally = finding.getValue();
					evidence.add(new Evidence(indicator, tally.occurrences(), tally.examples()));
					int repeatBonus = 5 * Math.max(0, tally.distinctClasses() - 1);
					strongest = Math.max(strongest, indicator.baseConfidence + repeatBonus);
				}
				if (!evidence.isEmpty()) {
					// Corroborating indicator types add modest support without treating them as independent probabilities.
					int score = Math.min(MAX_CONFIDENCE, strongest + 10 * (evidence.size() - 1));
					matches.add(new Match(path, family, score, List.copyOf(evidence)));
				}
			}
		}

		@Override
		public void inspect(JvmClassInfo info, ClassNode node, String version, BooleanSupplier cancelled) {
			String sourceFile = info.getSourceFileName();
			if (sourceFile != null && sourceFile.startsWith("R8_") && R8_SOURCE_FILE.matcher(sourceFile).matches())
				record(Indicator.R8_MAPPING_SOURCE_FILE, 1, node.name, node.name + version + " · " + sourceFile);
			boolean objectCache = false;
			boolean descriptorCache = false;
			for (var field : node.fields) {
				if (isAllatoriMarker(field.name))
					record(Indicator.ALLATORI_DEMO_MARKER, 1, node.name, node.name + "." + field.name + version);
				if ((field.access & ACC_STATIC) != 0) {
					objectCache |= "[Ljava/lang/Object;".equals(field.desc);
					descriptorCache |= "[Ljava/lang/String;".equals(field.desc);
					}
			}
			if (objectCache && descriptorCache && ZkmInvokeDynamicResolver.hasReferenceMetadata(node))
				record(Indicator.ZELIX_REFERENCE_METADATA, 1, node.name, node.name + version);
			if (matchesNativeLoader(node, sourceFile))
				record(Indicator.NATIVE_OBFUSCATOR_LOADER, 1, node.name, node.name + version);
			// DashO's generated helper has only a public static seed supplier and a public static string decoder.
			boolean dashoHelper = node.methods.size() == 2
					&& node.methods.stream().allMatch(method -> (method.access & (ACC_PUBLIC | ACC_STATIC)) == (ACC_PUBLIC | ACC_STATIC))
					&& node.methods.stream().anyMatch(method -> !method.desc.startsWith("()") && method.desc.endsWith(")Ljava/lang/String;"));
			for (MethodNode method : node.methods) {
				checkCancelled(cancelled);
				if (isAllatoriMarker(method.name)) recordMethod(Indicator.ALLATORI_DEMO_MARKER, 1, node, method, version);
				if (node.methods.size() == 1 && node.fields.isEmpty() && "\u0001".equals(method.name)
						&& "(\u0001/)L\u0001/;".equals(method.desc))
					record(Indicator.BOZAR_DUMMY_CLASS, 1, node.name, node.name + version);
				if (SkidInvokeDynamicResolver.matchesBootstrap(method))
					recordMethod(Indicator.SKID_REFERENCE_BOOTSTRAP, 1, node, method, version);
				if (dashoHelper && method.desc.equals("()I") && matchesDashOSeed(method))
					recordMethod(Indicator.DASHO_SEED_HELPER, 1, node, method, version);
				if ("java/lang/ClassLoader".equals(node.superName) && matchesJobfLoader(node.name, method))
					recordMethod(Indicator.JOBF_CLASS_LOADER, 1, node, method, version);
				if (matchesDProtectDecoder(method)) recordMethod(Indicator.DPROTECT_STRING_DECODER, 1, node, method, version);
				if (matchesStringerDecoder(method)) recordMethod(Indicator.STRINGER_STACK_TRACE_DECODER, 1, node, method, version);
				int radonInt = 0, radonLong = 0, bozarArrays = 0;
				for (AbstractInsnNode insn : method.instructions) {
					switch (insn.getOpcode()) {
						case IXOR -> { if (matchesRadonInt(insn)) radonInt++; }
						case LXOR -> { if (matchesRadonLong(insn)) radonLong++; }
						case NEWARRAY -> { if (matchesBozarArray((IntInsnNode) insn)) bozarArrays++; }
						default -> { }
					}
				}
				if (radonInt > 0) recordMethod(Indicator.RADON_INT_SHUFFLE, radonInt, node, method, version);
				if (radonLong > 0) recordMethod(Indicator.RADON_LONG_SHUFFLE, radonLong, node, method, version);
				if (bozarArrays > 0) recordMethod(Indicator.BOZAR_STRING_ARRAY, bozarArrays, node, method, version);
			}
		}

		private void recordMethod(Indicator indicator, int count, ClassNode node, MethodNode method, String version) {
			record(indicator, count, node.name, node.name + "." + method.name + method.desc + version);
		}

		private void record(Indicator indicator, int count, @Nullable String className, String example) {
			findings.computeIfAbsent(indicator, _ -> new JvmEvidenceScanner.Tally()).add(count, className, example);
		}
	}

	// Allatori's documented trial version marker is written to renamed members.
	private static boolean isAllatoriMarker(String name) {
		return "ALLATORI_DEMO".equals(name) || name.startsWith("ALLATORI_DEMO_");
	}

	private static boolean matchesNativeLoader(ClassNode node, @Nullable String sourceFile) {
		if (!"synthetic".equals(sourceFile) || !node.name.endsWith("/Loader")) return false;
		boolean registration = false;
		boolean loadsLibrary = false;
		for (MethodNode method : node.methods) {
			registration |= (method.access & (ACC_STATIC | ACC_NATIVE)) == (ACC_STATIC | ACC_NATIVE)
					&& "registerNativesForClass".equals(method.name) && NATIVE_REGISTRATION.equals(method.desc);
			if ("<clinit>".equals(method.name)) {
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC
							&& "java/lang/System".equals(call.owner) && "(Ljava/lang/String;)V".equals(call.desc)
							&& ("load".equals(call.name) || "loadLibrary".equals(call.name))) {
						loadsLibrary = true;
						break;
					}
				}
			}
		}
		return registration && loadsLibrary;
	}

	private static boolean matchesJobfLoader(String owner, MethodNode method) {
		if (!"findClass".equals(method.name) || !"(Ljava/lang/String;)Ljava/lang/Class;".equals(method.desc)) return false;
		int xorCalls = 0;
		boolean definesClass = false;
		boolean loadsBytes = false;
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !owner.equals(call.owner)) continue;
			if (call.getOpcode() == INVOKESTATIC && "([B[B)[B".equals(call.desc)) xorCalls++;
			if (call.getOpcode() == INVOKESTATIC && "(Ljava/lang/String;)[B".equals(call.desc)) loadsBytes = true;
			if (call.getOpcode() == INVOKEVIRTUAL && "defineClass".equals(call.name)
					&& "(Ljava/lang/String;[BII)Ljava/lang/Class;".equals(call.desc)) definesClass = true;
		}
		return xorCalls >= 2 && loadsBytes && definesClass;
	}

	private static boolean matchesDProtectDecoder(MethodNode method) {
		if ((method.access & (ACC_PUBLIC | ACC_STATIC)) != (ACC_PUBLIC | ACC_STATIC) || !STRING_DECODER.equals(method.desc)) return false;
		if (!switch (method.name) {
			case "simpleXorDecode", "simpleIndexedXorDecode", "fibonnaciLFSRDecode" -> true;
			default -> false;
		}) return false;
		boolean readsChar = false;
		boolean appendsChar = false;
		boolean xor = false;
		for (AbstractInsnNode insn : method.instructions) {
			xor |= insn.getOpcode() == IXOR;
			if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKEVIRTUAL) {
				readsChar |= "java/lang/String".equals(call.owner) && "charAt".equals(call.name) && "(I)C".equals(call.desc);
				appendsChar |= "java/lang/StringBuilder".equals(call.owner) && "append".equals(call.name)
						&& "(C)Ljava/lang/StringBuilder;".equals(call.desc);
			}
		}
		return readsChar && appendsChar && xor;
	}

	private static boolean matchesStringerDecoder(MethodNode method) {
		String desc = method.desc;
		if ((method.access & ACC_STATIC) == 0 || !desc.startsWith("(L") || !desc.endsWith(";")
				|| !desc.startsWith(";III)L", desc.indexOf(';')) || method.instructions.size() < 64) return false;
		int and = 0, xor = 0, right = 0, left = 0;
		boolean getsThread = false;
		boolean readsStack = false;
		for (AbstractInsnNode insn : method.instructions) {
			switch (insn.getOpcode()) {
				case IAND -> and++;
				case IXOR -> xor++;
				case IUSHR -> right++;
				case ISHL -> left++;
				case INVOKESTATIC, INVOKEVIRTUAL -> {
					MethodInsnNode call = (MethodInsnNode) insn;
					if ("java/lang/Thread".equals(call.owner)) {
						getsThread |= "currentThread".equals(call.name) && "()Ljava/lang/Thread;".equals(call.desc);
						readsStack |= "getStackTrace".equals(call.name) && "()[Ljava/lang/StackTraceElement;".equals(call.desc);
					}
				}
				default -> { }
			}
		}
		return getsThread && readsStack && and > 20 && xor > 20 && right > 10 && left > 10;
	}

	private static boolean matchesDashOSeed(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKEVIRTUAL
					|| !"java/util/Random".equals(call.owner) || !"nextInt".equals(call.name) || !"(I)I".equals(call.desc))
				continue;
			AbstractInsnNode bound = call.getPrevious();
			if (!isIntLiteral(bound) || !(bound.getPrevious() instanceof MethodInsnNode constructor)
					|| constructor.getOpcode() != INVOKESPECIAL || !"java/util/Random".equals(constructor.owner)
					|| !"<init>".equals(constructor.name) || !"()V".equals(constructor.desc))
				continue;
			if (!(previous(previous(constructor, DUP), NEW) instanceof TypeInsnNode allocation)
					|| !"java/util/Random".equals(allocation.desc))
				continue;
			AbstractInsnNode one = call.getNext();
			AbstractInsnNode add = one == null ? null : one.getNext();
			if (one != null && one.getOpcode() == ICONST_1 && add != null && add.getOpcode() == IADD) {
				AbstractInsnNode result = AsmInsnUtil.getNextInsn(add);
				if (result != null && result.getOpcode() == IRETURN) return true;
			}
		}
		return false;
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

	private record Numeric(int value, AbstractInsnNode next, boolean encoded) {}
}
