package software.coley.recaf.services.deobfuscation.transform.generic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.workspace.model.Workspace;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Recovers fixed static string targets from Lookup.findStatic -> asType -> MutableCallSite bootstraps.
 * Requires literal owner/name/descriptor after ordinary constant folding, and proves the normal return's data flow.
 */
@Dependent
public class StaticStringBootstrapResolver implements InvokeDynamicResolver {
	private final Map<MethodRef, Handle> targets = new HashMap<>();

	@Override
	public void setup(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace) {
		targets.clear();
		Set<MethodRef> seen = new HashSet<>();
		workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().forEach(bundle -> {
			for (var info : bundle.values()) {
				ClassNode node = context.getNode(bundle, info);
				for (MethodNode method : node.methods) {
					if (!method.desc.equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/Object;")
							&& !method.desc.equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")) continue;
					MethodRef key = new MethodRef(node.name, method.name, method.desc);
					if (!seen.add(key)) { targets.remove(key); continue; }
					Handle target = recover(node, method, workspace);
					if (target != null) targets.put(key, target);
				}
			}
		});
	}

	@Nullable
	@Override
	public ResolvedInvokeDynamic resolve(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                                    @Nonnull ClassNode owner, @Nonnull MethodNode method,
	                                    @Nonnull InvokeDynamicInsnNode instruction, @Nonnull Frame<ReValue> frame) {
		return resolveWithoutFrame(context, workspace, owner, method, instruction);
	}

	@Nullable
	@Override
	public ResolvedInvokeDynamic resolveWithoutFrame(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                                                @Nonnull ClassNode owner, @Nonnull MethodNode method,
	                                                @Nonnull InvokeDynamicInsnNode instruction) {
		Handle bootstrap = instruction.bsm;
		Handle target = targets.get(new MethodRef(bootstrap.getOwner(), bootstrap.getName(), bootstrap.getDesc()));
		if (bootstrap.getTag() != H_INVOKESTATIC || instruction.bsmArgs.length != 0 || target == null || !instruction.desc.equals(target.getDesc())) return null;
		var targetClass = workspace.findJvmClass(target.getOwner());
		if (targetClass == null) return null;
		var targetMethod = targetClass.getValue().getDeclaredMethod(target.getName(), target.getDesc());
		if (targetMethod == null || (targetMethod.getAccess() & ACC_STATIC) == 0) return null;
		if (!owner.name.equals(target.getOwner()) && ((targetMethod.getAccess() & ACC_PUBLIC) == 0
				|| (targetClass.getValue().getAccess() & ACC_PUBLIC) == 0)) return null;
		return new ResolvedInvokeDynamic(target, 0);
	}

	@Nullable
	private static Handle recover(ClassNode owner, MethodNode method, Workspace workspace) {
		try {
			if ((method.access & (ACC_STATIC | ACC_SYNCHRONIZED)) != ACC_STATIC) return null;
			MethodInsnNode find = null, adapt = null, construct = null;
			for (AbstractInsnNode insn : method.instructions) {
				if (!isLinkageInstruction(insn)) return null;
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.owner.equals("java/lang/invoke/MethodHandles$Lookup")) {
					if (!call.name.equals("findStatic") || find != null) return null;
					find = call;
				} else if (call.owner.equals("java/lang/invoke/MethodHandle")) {
					if (!call.name.equals("asType") || adapt != null) return null;
					adapt = call;
				} else if (call.owner.equals("java/lang/invoke/MutableCallSite")) {
					if (!call.name.equals("<init>") || !call.desc.equals("(Ljava/lang/invoke/MethodHandle;)V") || construct != null) return null;
					construct = call;
				}
			}
			if (find == null || adapt == null || construct == null) return null;
			Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter(ASM9) {
				@Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) { return value; }
			});
			Frame<SourceValue>[] frames = analyzer.analyze(owner.name, method);
			Frame<SourceValue> entry = frames[0];
			Frame<SourceValue> lookup = frames[method.instructions.indexOf(find)];
			if (lookup == null || lookup.getStackSize() < 4) return null;
			if (lookup.getStack(lookup.getStackSize() - 4) != entry.getLocal(0)) return null;
			Object classValue = constant(lookup.getStack(lookup.getStackSize() - 3));
			Object nameValue = constant(lookup.getStack(lookup.getStackSize() - 2));
			AbstractInsnNode typeSource = source(lookup.getStack(lookup.getStackSize() - 1));
			if (!(classValue instanceof Type targetOwner) || targetOwner.getSort() != Type.OBJECT || !(nameValue instanceof String targetName)
					|| !(typeSource instanceof MethodInsnNode typeCall) || !typeCall.owner.equals("java/lang/invoke/MethodType")
					|| !typeCall.name.equals("fromMethodDescriptorString")) return null;
			// A direct call must retain initialization of the bootstrap's declaring class.
			if (!targetOwner.getInternalName().equals(owner.name)) return null;
			Frame<SourceValue> typeFrame = frames[method.instructions.indexOf(typeSource)];
			Object descValue = constant(typeFrame.getStack(typeFrame.getStackSize() - 2));
			if (!(descValue instanceof String descriptor) || !Type.getReturnType(descriptor).equals(Type.getType(String.class))) return null;
			AbstractInsnNode loaderSource = source(typeFrame.getStack(typeFrame.getStackSize() - 1));
			if (!(loaderSource instanceof MethodInsnNode loader) || !loader.owner.equals("java/lang/Class") || !loader.name.equals("getClassLoader")) return null;
			Frame<SourceValue> loaderFrame = frames[method.instructions.indexOf(loaderSource)];
			if (!targetOwner.equals(constant(loaderFrame.getStack(loaderFrame.getStackSize() - 1)))) return null;
			Frame<SourceValue> adaptFrame = frames[method.instructions.indexOf(adapt)];
			if (source(adaptFrame.getStack(adaptFrame.getStackSize() - 2)) != find) return null;
			if (adaptFrame.getStack(adaptFrame.getStackSize() - 1) != entry.getLocal(2)) return null;
			Frame<SourceValue> constructorFrame = frames[method.instructions.indexOf(construct)];
			if (source(constructorFrame.getStack(constructorFrame.getStackSize() - 1)) != adapt) return null;
			AbstractInsnNode newSite = source(constructorFrame.getStack(constructorFrame.getStackSize() - 2));
			if (!(newSite instanceof TypeInsnNode allocation) || !allocation.desc.equals("java/lang/invoke/MutableCallSite")) return null;
			boolean returned = false;
			for (int i = 0; i < method.instructions.size(); i++) {
				if (method.instructions.get(i).getOpcode() == ARETURN && frames[i] != null) {
					Frame<SourceValue> frame = frames[i];
					if (source(frame.getStack(frame.getStackSize() - 1)) != newSite) return null;
					returned = true;
				}
			}
			var targetClass = workspace.findJvmClass(targetOwner.getInternalName());
			return returned && targetClass != null ? new Handle(H_INVOKESTATIC, targetOwner.getInternalName(), targetName, descriptor,
					(targetClass.getValue().getAccess() & ACC_INTERFACE) != 0) : null;
		} catch (Exception ignored) {
			return null;
		}
	}

	/** Only straight-line linkage construction and an optional diagnostic exception path are accepted. */
	private static boolean isLinkageInstruction(AbstractInsnNode instruction) {
		if (instruction.getOpcode() < 0) return true;
		return switch (instruction.getOpcode()) {
			case NOP, DUP, POP, ALOAD, ASTORE, ARETURN, ATHROW -> true;
			case LDC -> ((LdcInsnNode) instruction).cst instanceof String || ((LdcInsnNode) instruction).cst instanceof Type;
			case NEW -> Set.of("java/lang/invoke/MutableCallSite", "java/lang/StringBuilder", "java/lang/RuntimeException")
					.contains(((TypeInsnNode) instruction).desc);
			case GETSTATIC -> instruction instanceof FieldInsnNode field && field.owner.equals("java/nio/charset/StandardCharsets")
					&& field.name.equals("UTF_8") && field.desc.equals("Ljava/nio/charset/Charset;");
			case INVOKESTATIC, INVOKEVIRTUAL, INVOKESPECIAL -> {
				MethodInsnNode call = (MethodInsnNode) instruction;
				String signature = call.owner + "." + call.name + call.desc;
				yield Set.of(
						"java/lang/invoke/MethodHandles$Lookup.findStatic(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
						"java/lang/invoke/MethodHandle.asType(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
						"java/lang/invoke/MutableCallSite.<init>(Ljava/lang/invoke/MethodHandle;)V",
						"java/lang/invoke/MethodType.fromMethodDescriptorString(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;",
						"java/lang/invoke/MethodType.toString()Ljava/lang/String;",
						"java/lang/Class.getClassLoader()Ljava/lang/ClassLoader;",
						"java/lang/StringBuilder.<init>()V",
						"java/lang/StringBuilder.append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
						"java/lang/StringBuilder.toString()Ljava/lang/String;",
						"java/lang/RuntimeException.<init>(Ljava/lang/String;Ljava/lang/Throwable;)V").contains(signature);
			}
			default -> false;
		};
	}

	private static AbstractInsnNode source(SourceValue value) { return value.insns.size() == 1 ? value.insns.iterator().next() : null; }
	private static Object constant(SourceValue value) { return source(value) instanceof LdcInsnNode ldc ? ldc.cst : null; }
}
