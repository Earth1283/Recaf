package software.coley.recaf.services.deobfuscation.transform.specific.skid;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.util.Printer;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicResolver;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.workspace.model.Workspace;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * Decodes Skidfuscator's four-literal reference bootstrap, including renamed bootstrap classes.
 * The entire bootstrap body and branch destinations must match the known linkage-only template.
 * Input classes are never loaded or executed. Native predicate drivers are not evaluated.
 */
@Dependent
public class SkidInvokeDynamicResolver implements InvokeDynamicResolver {
	public static final String BOOTSTRAP_DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
	// The manager shares resolver instances across previews and runs. Keep immutable metadata per context,
	// with weak keys so finished runs do not retain their workspaces.
	private final Map<JvmTransformerContext, Metadata> metadata = Collections.synchronizedMap(new WeakHashMap<>());
	// Instruction ordinals exclude debug data and frames. Names of the declaring class and method are irrelevant.
	private static final List<String> TEMPLATE = """
			ALOAD 4
			CHECKCAST java/lang/String
			INVOKESTATIC java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;
			ASTORE 7
			ALOAD 7
			INVOKEVIRTUAL java/lang/Class.getClassLoader()Ljava/lang/ClassLoader;
			ASTORE 8
			ALOAD 6
			CHECKCAST java/lang/String
			ALOAD 8
			INVOKESTATIC java/lang/invoke/MethodType.fromMethodDescriptorString(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;
			ASTORE 9
			ALOAD 3
			CHECKCAST java/lang/Integer
			INVOKEVIRTUAL java/lang/Integer.intValue()I
			SIPUSH 184
			IF_ICMPNE 29
			NEW java/lang/invoke/MutableCallSite
			DUP
			ALOAD 0
			ALOAD 7
			ALOAD 5
			CHECKCAST java/lang/String
			ALOAD 9
			INVOKEVIRTUAL java/lang/invoke/MethodHandles$Lookup.findStatic(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;
			ALOAD 2
			INVOKEVIRTUAL java/lang/invoke/MethodHandle.asType(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;
			INVOKESPECIAL java/lang/invoke/MutableCallSite.<init>(Ljava/lang/invoke/MethodHandle;)V
			GOTO 40
			NEW java/lang/invoke/MutableCallSite
			DUP
			ALOAD 0
			ALOAD 7
			ALOAD 5
			CHECKCAST java/lang/String
			ALOAD 9
			INVOKEVIRTUAL java/lang/invoke/MethodHandles$Lookup.findVirtual(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;
			ALOAD 2
			INVOKEVIRTUAL java/lang/invoke/MethodHandle.asType(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;
			INVOKESPECIAL java/lang/invoke/MutableCallSite.<init>(Ljava/lang/invoke/MethodHandle;)V
			ARETURN
			""".lines().toList();

	@Override
	public void setup(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace) {
		Set<MethodRef> bootstraps = new HashSet<>();
		Set<String> ambiguousOwners = new HashSet<>();
		Set<MethodRef> seen = new HashSet<>();
		Set<String> owners = new HashSet<>();
		workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().forEach(bundle -> {
			for (JvmClassInfo info : bundle.values()) {
				if (!owners.add(info.getName())) ambiguousOwners.add(info.getName());
				if (info.getMethods().stream().noneMatch(m -> m.getDescriptor().equals(BOOTSTRAP_DESCRIPTOR))) continue;
				ClassNode node = context.getNode(bundle, info);
				// Removing bootstrap invocation must not discard class initialization effects.
				if ((node.access & ACC_INTERFACE) != 0 || !"java/lang/Object".equals(node.superName) || !node.interfaces.isEmpty()
						|| node.methods.stream().anyMatch(m -> m.name.equals("<clinit>"))) continue;
				for (MethodNode method : node.methods) {
					MethodRef key = new MethodRef(node.name, method.name, method.desc);
					if (!seen.add(key)) { bootstraps.remove(key); continue; }
					if (matchesBootstrap(method)) bootstraps.add(key);
				}
			}
		});
		bootstraps.removeIf(key -> ambiguousOwners.contains(key.owner()));
		metadata.put(context, new Metadata(Set.copyOf(bootstraps), Set.copyOf(ambiguousOwners)));
	}

	/** Exact linkage template recognition; arbitrary bootstraps with similar constants are left intact. */
	public static boolean matchesBootstrap(@Nonnull MethodNode method) {
		if (!method.desc.equals(BOOTSTRAP_DESCRIPTOR) || !method.tryCatchBlocks.isEmpty()
				|| (method.access & (ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED)) != (ACC_PUBLIC | ACC_STATIC)) return false;
		List<AbstractInsnNode> code = Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		if (code.size() != TEMPLATE.size()) return false;
		for (int i = 0; i < code.size(); i++) {
			AbstractInsnNode insn = code.get(i);
			String operand = switch (insn) {
				case VarInsnNode var -> " " + var.var;
				case IntInsnNode integer -> " " + integer.operand;
				case TypeInsnNode type -> " " + type.desc;
				case MethodInsnNode call -> " " + call.owner + "." + call.name + call.desc + (call.itf ? " interface" : "");
				case JumpInsnNode jump -> {
					AbstractInsnNode target = jump.label;
					while (target != null && target.getOpcode() < 0) target = target.getNext();
					yield " " + code.indexOf(target);
				}
				case InsnNode ignored -> "";
				default -> " unsupported";
			};
			if (!TEMPLATE.get(i).equals(Printer.OPCODES[insn.getOpcode()] + operand)) return false;
		}
		return true;
	}

	@Nullable @Override
	public ResolvedInvokeDynamic resolve(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                                    @Nonnull ClassNode owner, @Nonnull MethodNode method,
	                                    @Nonnull InvokeDynamicInsnNode instruction, @Nonnull Frame<ReValue> frame) {
		return resolveWithoutFrame(context, workspace, owner, method, instruction);
	}

	@Nullable @Override
	public ResolvedInvokeDynamic resolveWithoutFrame(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                                                @Nonnull ClassNode owner, @Nonnull MethodNode method,
	                                                @Nonnull InvokeDynamicInsnNode instruction) {
		Handle bootstrap = instruction.bsm;
		Object[] args = instruction.bsmArgs;
		Metadata state = metadata.get(context);
		if (state == null || bootstrap.getTag() != H_INVOKESTATIC || bootstrap.isInterface()
				|| !state.bootstraps().contains(new MethodRef(bootstrap.getOwner(), bootstrap.getName(), bootstrap.getDesc()))
				|| args.length != 4 || !(args[0] instanceof Integer opcode)
				|| (opcode != INVOKESTATIC && opcode != INVOKEVIRTUAL && opcode != INVOKEINTERFACE && opcode != INVOKESPECIAL)
				|| !(args[1] instanceof String className) || className.indexOf('/') >= 0 || !(args[2] instanceof String name)
				|| !(args[3] instanceof String descriptor) || name.isEmpty() || name.startsWith("<")) return null;
		try {
			String targetOwner = className.replace('.', '/');
			if (state.ambiguousOwners().contains(targetOwner)) return null;
			var targetPath = workspace.findJvmClass(targetOwner);
			if (targetPath == null) return null;
			JvmClassInfo targetClass = targetPath.getValue().asJvmClass();
			boolean itf = targetClass.hasInterfaceModifier();
			MethodMember targetMethod = findMethod(workspace, targetClass, name, descriptor, new HashSet<>());
			if (targetMethod == null || targetMethod.hasStaticModifier() != (opcode == INVOKESTATIC)) return null;
			if (targetMethod.hasPrivateModifier() && targetClass.getDeclaredMethod(name, descriptor) == null) return null;
			// Protected cross-package and inaccessible members require additional lookup semantics; retain those sites.
			boolean sameClass = owner.name.equals(targetOwner);
			boolean samePackage = packageName(owner.name).equals(packageName(targetOwner));
			// Metadata can retain 183 after access widening. The proven bootstrap uses findVirtual
			// for every non-static site, so emit virtual dispatch rather than an invokespecial super call.
			if (!sameClass && ((!targetClass.hasPublicModifier() && !samePackage) || targetMethod.hasPrivateModifier()
					|| (!targetMethod.hasPublicModifier() && !samePackage))) return null;
			Type[] siteTypes = Type.getArgumentTypes(instruction.desc);
			Type[] targetTypes = Type.getArgumentTypes(descriptor);
			int receiver = opcode == INVOKESTATIC ? 0 : 1;
			if (siteTypes.length != targetTypes.length + receiver) return null;
			if (receiver == 1 && !reference(siteTypes[0])) return null;
			for (int i = 0; i < targetTypes.length; i++)
				if (!compatible(siteTypes[i + receiver], targetTypes[i])) return null;
			if (!compatible(Type.getReturnType(descriptor), Type.getReturnType(instruction.desc))) return null;
			int tag = opcode == INVOKESTATIC ? H_INVOKESTATIC : itf ? H_INVOKEINTERFACE : H_INVOKEVIRTUAL;
			return new ResolvedInvokeDynamic(new Handle(tag, targetOwner, name, descriptor, itf), 0);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	@Nullable
	private static MethodMember findMethod(Workspace workspace, JvmClassInfo owner, String name, String desc, Set<String> seen) {
		if (!seen.add(owner.getName())) return null;
		MethodMember declared = owner.getDeclaredMethod(name, desc);
		if (declared != null) return declared;
		List<String> parents = new ArrayList<>(owner.getInterfaces());
		if (owner.getSuperName() != null) parents.addFirst(owner.getSuperName());
		for (String parent : parents) {
			var path = workspace.findJvmClass(parent);
			if (path == null) continue;
			MethodMember inherited = findMethod(workspace, path.getValue().asJvmClass(), name, desc, seen);
			if (inherited != null) return inherited;
		}
		return null;
	}

	private static String packageName(String name) { return name.substring(0, name.lastIndexOf('/') + 1); }
	private static boolean reference(Type type) { return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY; }
	private static boolean compatible(Type from, Type to) { return from.equals(to) || (reference(from) && reference(to)); }
	private record Metadata(Set<MethodRef> bootstraps, Set<String> ambiguousOwners) {}
}
