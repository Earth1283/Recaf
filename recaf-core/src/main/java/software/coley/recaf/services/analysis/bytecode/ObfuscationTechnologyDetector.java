package software.coley.recaf.services.analysis.bytecode;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.util.AsmInsnUtil;
import software.coley.recaf.util.bytecode.TryCatchRepairUtil;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.*;
import java.util.function.BooleanSupplier;

import static org.objectweb.asm.Opcodes.*;
import static software.coley.recaf.services.analysis.bytecode.JvmEvidenceScanner.checkCancelled;

/** Read-only detection of bytecode patterns associated with obfuscation techniques. */
@ApplicationScoped
public class ObfuscationTechnologyDetector {
	public enum Technology {
		STRING_CONCEALMENT, NUMERIC_OBFUSCATION, OPAQUE_BRANCHES, DYNAMIC_LINKAGE,
		NATIVE_CODE, CLASS_PACKING, UNUSUAL_IDENTIFIERS, UNREACHABLE_CODE,
		FAKE_TRY_CATCH, ALGEBRAIC_OPAQUE_PREDICATES
	}

	/** Evidence in one resource. An empty resource name identifies the selected root. */
	public record Match(String resource, Technology technology, long occurrences, List<String> examples) {}

	public record ControlFlowCoverage(int methodsAnalyzed, int methodsSkipped) {}

	public record Result(int classesRead, int classesSkipped, List<Match> matches, ControlFlowCoverage controlFlow) {
		public Result(int classesRead, int classesSkipped, List<Match> matches) {
			this(classesRead, classesSkipped, matches, new ControlFlowCoverage(0, 0));
		}
	}

	/**
	 * @param root
	 * 		Selected resource, including its embedded archives and multi-release classes.
	 * @param cancelled
	 * 		Signal to abandon the scan.
	 * @return Observed bytecode patterns and example locations scoped to each resource.
	 */
	@Nonnull
	public Result scan(@Nonnull WorkspaceResource root, @Nonnull BooleanSupplier cancelled) {
		return scan(root, false, cancelled);
	}

	/**
	 * @param root
	 * 		Selected resource, including its embedded archives and multi-release classes.
	 * @param inDepthControlFlow
	 * 		Whether to run data-flow analysis on methods with control-flow candidates.
	 * @param cancelled
	 * 		Signal to abandon the scan.
	 * @return Observed bytecode patterns, example locations, and data-flow coverage.
	 */
	@Nonnull
	public Result scan(@Nonnull WorkspaceResource root, boolean inDepthControlFlow, @Nonnull BooleanSupplier cancelled) {
		List<Match> matches = new ArrayList<>();
		var flow = inDepthControlFlow ? new ControlFlowScan(cancelled) : null;
		var counts = JvmEvidenceScanner.scan(root, cancelled, () -> new TechnologyCollector(matches, flow));
		matches.sort(Comparator.comparing(Match::resource).thenComparing(Match::technology));
		return flow == null ? new Result(counts.classesRead(), counts.classesSkipped(), List.copyOf(matches))
				: new Result(counts.classesRead(), counts.classesSkipped(), List.copyOf(matches), new ControlFlowCoverage(flow.analyzed, flow.skipped));
	}

	private static final class TechnologyCollector implements JvmEvidenceScanner.Collector {
		private final List<Match> matches;
		private final Map<Technology, JvmEvidenceScanner.Tally> findings = new EnumMap<>(Technology.class);
		@Nullable
		private final ControlFlowScan controlFlow;

		private TechnologyCollector(List<Match> matches, @Nullable ControlFlowScan controlFlow) {
			this.matches = matches;
			this.controlFlow = controlFlow;
		}

		@Override
		public void finish(String path, WorkspaceResource resource, BooleanSupplier cancelled) {
			findings.forEach((technology, tally) -> matches.add(new Match(path, technology, tally.occurrences(), tally.examples())));
		}

		@Override
		public void inspect(JvmClassInfo info, ClassNode node, String version, BooleanSupplier cancelled) {
			boolean invisibleName = containsInvisibleCharacters(node.name);
			String displayName = invisibleName ? escapeIdentifier(node.name) : node.name;
			if (invisibleName) record(Technology.UNUSUAL_IDENTIFIERS, 1, displayName + version);
			for (var field : node.fields) {
				if (containsInvisibleCharacters(field.name))
					record(Technology.UNUSUAL_IDENTIFIERS, 1, displayName + "." + escapeIdentifier(field.name) + version);
			}
			int fakeTryCatches = TryCatchRepairUtil.countFakeTryCatches(node);
			if (fakeTryCatches > 0)
				record(Technology.FAKE_TRY_CATCH, fakeTryCatches, displayName + version);
			for (MethodNode method : node.methods) {
				checkCancelled(cancelled);
				inspectMethod(node.name, displayName, method, version);
			}
		}

		private void inspectMethod(String owner, String displayName, MethodNode method, String version) {
			int numeric = 0, branches = 0, dynamic = 0, arrayWrites = 0, algebraic = 0;
			boolean xor = false, readsChar = false, rebuildsString = false, decryptsBytes = false, definesClass = false;
			boolean nativeMethod = (method.access & ACC_NATIVE) != 0;
			boolean invisibleName = containsInvisibleCharacters(method.name);
			AbstractInsnNode previous = null;
			for (AbstractInsnNode insn : method.instructions) {
				int opcode = insn.getOpcode();
				if (opcode < 0) continue;
				switch (opcode) {
					case DUP -> {
						if (isAlgebraicIdentity(insn)) algebraic++;
					}
					case IXOR, ISHL, ISHR, IUSHR, IAND, IOR -> {
						xor |= opcode == IXOR;
						if (isIntLiteral(previous) && isIntLiteral(AsmInsnUtil.getPreviousInsn(previous))) numeric++;
						else if (opcode == IXOR && isIntLiteral(previous) && previous.getPrevious() != null
								&& previous.getPrevious().getOpcode() == POP2) numeric++;
					}
					case LXOR -> {
						if (isLongLiteral(previous) && (isLongLiteral(AsmInsnUtil.getPreviousInsn(previous))
								|| previous.getPrevious() != null && previous.getPrevious().getOpcode() == POP2)) numeric++;
					}
					case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
						if (isIntLiteral(previous) && insn.getPrevious() == previous) branches++;
					}
					case BASTORE, CASTORE -> arrayWrites++;
					case INVOKEDYNAMIC -> {
						var call = (InvokeDynamicInsnNode) insn;
						if (isCustomBootstrap(call.bsm.getOwner())) dynamic++;
					}
					case LDC -> {
						if (((LdcInsnNode) insn).cst instanceof ConstantDynamic constant
								&& isCustomBootstrap(constant.getBootstrapMethod().getOwner())) dynamic++;
					}
					case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC -> {
						var call = (MethodInsnNode) insn;
						switch (call.owner) {
							case "java/lang/String" -> {
								readsChar |= "charAt".equals(call.name) || "toCharArray".equals(call.name);
								rebuildsString |= "<init>".equals(call.name) && (call.desc.startsWith("([C") || call.desc.startsWith("([B"));
							}
							case "java/lang/StringBuilder", "java/lang/StringBuffer" -> rebuildsString |= "append".equals(call.name) && call.desc.startsWith("(C)");
							case "javax/crypto/Cipher" -> decryptsBytes |= "doFinal".equals(call.name);
							default -> {
								decryptsBytes |= opcode == INVOKESTATIC && owner.equals(call.owner) && "([B[B)[B".equals(call.desc);
								if ("java/lang/invoke/MethodHandles$Lookup".equals(call.owner)) {
									definesClass |= "defineClass".equals(call.name) || "defineHiddenClass".equals(call.name);
								} else {
									definesClass |= "defineClass".equals(call.name) && call.desc.endsWith(")Ljava/lang/Class;")
											&& ("java/lang/ClassLoader".equals(call.owner) || owner.equals(call.owner));
								}
							}
						}
					}
					default -> { }
				}
				previous = insn;
			}
			boolean strings = rebuildsString && (xor && (readsChar || arrayWrites > 0) || arrayWrites >= 4 || decryptsBytes);
			boolean packing = definesClass && (xor || decryptsBytes);
			int unreachable = 0;
			if (controlFlow != null && ControlFlowTechniqueAnalysis.isCandidate(method)) {
				var flow = controlFlow.analyzer.inspect(owner, method);
				if (flow == null) {
					controlFlow.skipped++;
				} else {
					controlFlow.analyzed++;
					branches = flow.fixedBranches();
					unreachable = flow.unreachable();
				}
			}
			if (!strings && !packing && !nativeMethod && !invisibleName && numeric == 0 && branches == 0 && dynamic == 0 && unreachable == 0 && algebraic == 0) return;
			String name = invisibleName ? escapeIdentifier(method.name) : method.name;
			String location = displayName + "." + name + method.desc + version;
			if (strings) record(Technology.STRING_CONCEALMENT, 1, location);
			if (numeric > 0) record(Technology.NUMERIC_OBFUSCATION, numeric, location);
			if (branches > 0) record(Technology.OPAQUE_BRANCHES, branches, location);
			if (algebraic > 0) record(Technology.ALGEBRAIC_OPAQUE_PREDICATES, algebraic, location);
			if (dynamic > 0) record(Technology.DYNAMIC_LINKAGE, dynamic, location);
			if (nativeMethod) record(Technology.NATIVE_CODE, 1, location);
			if (packing) record(Technology.CLASS_PACKING, 1, location);
			if (invisibleName) record(Technology.UNUSUAL_IDENTIFIERS, 1, location);
			if (unreachable > 0) record(Technology.UNREACHABLE_CODE, unreachable, location);
		}

		private void record(Technology technology, int count, String example) {
			findings.computeIfAbsent(technology, _ -> new JvmEvidenceScanner.Tally()).add(count, null, example);
		}
	}

	private static final class ControlFlowScan {
		private final ControlFlowTechniqueAnalysis analyzer;
		private int analyzed;
		private int skipped;

		private ControlFlowScan(BooleanSupplier cancelled) {
			analyzer = new ControlFlowTechniqueAnalysis(cancelled);
		}
	}

	private static boolean isAlgebraicIdentity(AbstractInsnNode dup) {
		AbstractInsnNode a = AsmInsnUtil.getNextInsn(dup);
		if (a == null) return false;
		if (a.getOpcode() == IXOR || a.getOpcode() == ISUB)
			return true;
		if (a.getOpcode() == ICONST_1) {
			AbstractInsnNode b = AsmInsnUtil.getNextInsn(a);
			AbstractInsnNode c = b == null ? null : AsmInsnUtil.getNextInsn(b);
			AbstractInsnNode d = c == null ? null : AsmInsnUtil.getNextInsn(c);
			AbstractInsnNode e = d == null ? null : AsmInsnUtil.getNextInsn(d);
			return b != null && (b.getOpcode() == IADD || b.getOpcode() == ISUB)
					&& c != null && c.getOpcode() == IMUL
					&& d != null && e != null
					&& ((d.getOpcode() == ICONST_1 && e.getOpcode() == IAND)
					|| (d.getOpcode() == ICONST_2 && e.getOpcode() == IREM));
		}
		return false;
	}

	private static boolean isIntLiteral(AbstractInsnNode insn) {
		return insn != null && AsmInsnUtil.isConstIntValue(insn);
	}

	private static boolean isLongLiteral(AbstractInsnNode insn) {
		return insn != null && (insn.getOpcode() == LCONST_0 || insn.getOpcode() == LCONST_1
				|| insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long);
	}

	private static boolean isCustomBootstrap(String owner) {
		return !owner.startsWith("java/") && !owner.startsWith("javax/") && !owner.startsWith("jdk/")
				&& !owner.equals("kotlin/jvm/internal/TypeIntrinsics") && !owner.startsWith("org/codehaus/groovy/")
				&& !owner.startsWith("org/apache/groovy/") && !owner.startsWith("scala/runtime/");
	}

	private static boolean containsInvisibleCharacters(String name) {
		for (int i = 0; i < name.length();) {
			int codePoint = name.codePointAt(i);
			if (isInvisible(codePoint)) return true;
			i += Character.charCount(codePoint);
		}
		return false;
	}

	private static boolean isInvisible(int codePoint) {
		return codePoint <= ' ' || codePoint >= 127 && (Character.isISOControl(codePoint)
				|| Character.isSpaceChar(codePoint) || Character.getType(codePoint) == Character.FORMAT);
	}

	private static String escapeIdentifier(String name) {
		StringBuilder escaped = new StringBuilder(name.length());
		for (int i = 0; i < name.length();) {
			int codePoint = name.codePointAt(i);
			if (!isInvisible(codePoint)) escaped.appendCodePoint(codePoint);
			else if (Character.isBmpCodePoint(codePoint)) escaped.append("\\u").append(HexFormat.of().toHexDigits((char) codePoint));
			else escaped.append("\\U").append(HexFormat.of().toHexDigits(codePoint));
			i += Character.charCount(codePoint);
		}
		return escaped.toString();
	}
}
