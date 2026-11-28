package software.coley.recaf.util.analysis;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared cross-class method usage, nestmate, reflection, safety, and constant-return analysis helpers.
 */
public final class MethodUsageSupport {
	public static final Set<String> SERIALIZATION_HOOKS = Set.of(
			"readObject", "writeObject", "readObjectNoData", "readResolve", "writeReplace"
	);
	public static final Set<String> GUARANTEED_JDK_CLASSES = Set.of(
			"java/lang/Object", "java/lang/String", "java/lang/Class", "java/lang/System",
			"java/lang/Integer", "java/lang/Long", "java/lang/Double", "java/lang/Float",
			"java/lang/Boolean", "java/lang/Byte", "java/lang/Short", "java/lang/Character",
			"java/lang/Void", "java/lang/Number", "java/lang/Throwable", "java/lang/Exception",
			"java/lang/RuntimeException"
	);
	private static final Set<String> OBJECT_OVERRIDES = Set.of(
			"equals(Ljava/lang/Object;)Z",
			"hashCode()I",
			"toString()Ljava/lang/String;",
			"clone()Ljava/lang/Object;",
			"finalize()V"
	);
	private static final Set<String> STATIC_ENTRY_POINTS = Set.of(
			"main([Ljava/lang/String;)V",
			"premain(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V",
			"premain(Ljava/lang/String;)V",
			"agentmain(Ljava/lang/String;Ljava/lang/instrument/Instrumentation;)V",
			"agentmain(Ljava/lang/String;)V"
	);
	private static final Object OPAQUE_PURE_INT = new Object();
	private static final int MAX_DELEGATION_PASSES = 6;

	private MethodUsageSupport() {}

	/**
	 * @param owner
	 * 		Internal class name.
	 * @param name
	 * 		Method name.
	 * @param desc
	 * 		Method descriptor.
	 *
	 * @return Canonical key {@code owner.name+desc}.
	 */
	@Nonnull
	public static String methodKey(@Nonnull String owner, @Nonnull String name, @Nonnull String desc) {
		return owner + '.' + name + desc;
	}

	/**
	 * Scans a class node for outgoing method references, reflection usage, and nest metadata.
	 *
	 * @param node
	 * 		Class node to inspect.
	 * @param inheritanceGraph
	 * 		Optional inheritance graph used to propagate virtual/interface/special call targets across workspace hierarchies.
	 *
	 * @return Scanned usage summary for the class.
	 */
	@Nonnull
	public static ClassMethodUsage scanClassNode(@Nonnull ClassNode node, @Nullable InheritanceGraph inheritanceGraph) {
		Set<String> outgoing = new HashSet<>();
		boolean declaredReflection = false;
		boolean publicReflection = false;

		for (MethodNode method : node.methods) {
			if (method.instructions == null)
				continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					outgoing.add(methodKey(call.owner, call.name, call.desc));
					int op = call.getOpcode();
					if (inheritanceGraph != null && (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE
							|| (op == Opcodes.INVOKESPECIAL && !"<init>".equals(call.name)))) {
						InheritanceVertex vertex = inheritanceGraph.getVertex(call.owner);
						if (vertex != null && !vertex.isLibraryVertex()) {
							for (InheritanceVertex parent : vertex.getAllParents()) {
								if (!parent.isLibraryVertex())
									outgoing.add(methodKey(parent.getName(), call.name, call.desc));
							}
							for (InheritanceVertex child : vertex.getAllChildren()) {
								if (!child.isLibraryVertex())
									outgoing.add(methodKey(child.getName(), call.name, call.desc));
							}
						}
					}
					if ("java/lang/Class".equals(call.owner)) {
						if ("getDeclaredMethod".equals(call.name) || "getDeclaredMethods".equals(call.name))
							declaredReflection = true;
						else if ("getMethod".equals(call.name) || "getMethods".equals(call.name))
							publicReflection = true;
					} else if ("java/lang/invoke/MethodHandles$Lookup".equals(call.owner)) {
						if ("findStatic".equals(call.name) || "findSpecial".equals(call.name)
								|| "findVirtual".equals(call.name) || "findPrivate".equals(call.name)
								|| "unreflect".equals(call.name))
							declaredReflection = true;
					}
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					recordHandle(indy.bsm, outgoing);
					if (indy.bsmArgs != null) {
						for (Object arg : indy.bsmArgs)
							recordConstant(arg, outgoing);
					}
				} else if (insn instanceof LdcInsnNode ldc) {
					recordConstant(ldc.cst, outgoing);
				}
			}
		}

		Set<String> nestMembers = node.nestMembers == null || node.nestMembers.isEmpty()
				? Set.of()
				: Set.copyOf(node.nestMembers);
		return new ClassMethodUsage(Set.copyOf(outgoing), declaredReflection, publicReflection, node.nestHostClass, nestMembers);
	}

	private static void recordConstant(@Nullable Object cst, @Nonnull Set<String> refs) {
		if (cst instanceof Handle handle) {
			recordHandle(handle, refs);
		} else if (cst instanceof ConstantDynamic cd) {
			recordHandle(cd.getBootstrapMethod(), refs);
			for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++)
				recordConstant(cd.getBootstrapMethodArgument(i), refs);
		}
	}

	private static void recordHandle(@Nullable Handle handle, @Nonnull Set<String> refs) {
		if (handle != null && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL && handle.getTag() <= Opcodes.H_INVOKEINTERFACE)
			refs.add(methodKey(handle.getOwner(), handle.getName(), handle.getDesc()));
	}

	/**
	 * Checks whether two classes belong to the same Java 11+ nest.
	 */
	public static boolean isInSameNest(@Nonnull String classA, @Nonnull String classB,
	                                   @Nonnull Map<String, ClassMethodUsage> usageByClass) {
		if (classA.equals(classB))
			return true;
		String hostA = resolveNestHost(classA, usageByClass);
		String hostB = resolveNestHost(classB, usageByClass);
		if (!hostA.equals(hostB))
			return false;
		ClassMethodUsage usageA = usageByClass.get(classA);
		ClassMethodUsage usageB = usageByClass.get(classB);
		if (classA.equals(hostA))
			return (usageA != null && usageA.nestMembers().contains(classB))
					|| (usageB != null && hostA.equals(usageB.nestHost()));
		if (classB.equals(hostB))
			return (usageB != null && usageB.nestMembers().contains(classA))
					|| (usageA != null && hostB.equals(usageA.nestHost()));
		return true;
	}

	/**
	 * Checks whether any class in the given class's nest uses declared/lookup method reflection.
	 */
	public static boolean hasDeclaredMethodReflectionInNest(@Nonnull ClassNode node,
	                                                        @Nonnull Map<String, ClassMethodUsage> usageByClass) {
		ClassMethodUsage selfUsage = usageByClass.get(node.name);
		if (selfUsage != null && selfUsage.usesDeclaredMethodReflection())
			return true;

		String host = node.nestHostClass != null ? node.nestHostClass
				: (selfUsage != null && selfUsage.nestHost() != null ? selfUsage.nestHost() : node.name);

		ClassMethodUsage hostUsage = usageByClass.get(host);
		if (hostUsage != null) {
			if (hostUsage.usesDeclaredMethodReflection())
				return true;
			for (String member : hostUsage.nestMembers()) {
				ClassMethodUsage memberUsage = usageByClass.get(member);
				if (memberUsage != null && memberUsage.usesDeclaredMethodReflection())
					return true;
			}
		}

		if (node.nestMembers != null) {
			for (String member : node.nestMembers) {
				ClassMethodUsage memberUsage = usageByClass.get(member);
				if (memberUsage != null && memberUsage.usesDeclaredMethodReflection())
					return true;
			}
		}

		for (Map.Entry<String, ClassMethodUsage> entry : usageByClass.entrySet()) {
			ClassMethodUsage usage = entry.getValue();
			if (usage.usesDeclaredMethodReflection() && host.equals(usage.nestHost()))
				return true;
		}
		return false;
	}

	@Nonnull
	private static String resolveNestHost(@Nonnull String className, @Nonnull Map<String, ClassMethodUsage> usageByClass) {
		ClassMethodUsage usage = usageByClass.get(className);
		return usage != null && usage.nestHost() != null ? usage.nestHost() : className;
	}

	/**
	 * Determines whether a method must always be preserved by automatic dead-method pruning
	 * (constructors, static initializers, abstract/native methods, serialization hooks, annotated methods,
	 * JVM/agent entry points, enum/record contract methods, and supertype/interface overrides).
	 */
	public static boolean isAlwaysProtectedMethod(@Nonnull ClassNode owner,
	                                              @Nonnull MethodNode method,
	                                              @Nullable InheritanceGraph graph,
	                                              @Nonnull Workspace workspace) {
		if (method.name.startsWith("<"))
			return true;
		if (AccessFlag.isAbstract(method.access) || AccessFlag.isNative(method.access))
			return true;
		if (SERIALIZATION_HOOKS.contains(method.name))
			return true;
		if (method.visibleAnnotations != null && !method.visibleAnnotations.isEmpty())
			return true;
		if (method.invisibleAnnotations != null && !method.invisibleAnnotations.isEmpty())
			return true;

		String nameDesc = method.name + method.desc;
		boolean isStatic = AccessFlag.isStatic(method.access);
		if (isStatic && STATIC_ENTRY_POINTS.contains(nameDesc))
			return true;

		if (AccessFlag.isEnum(owner.access) && isStatic) {
			String ownerType = "L" + owner.name + ";";
			if (("values".equals(method.name) && method.desc.equals("()[" + ownerType))
					|| ("valueOf".equals(method.name) && method.desc.equals("(Ljava/lang/String;)" + ownerType)))
				return true;
		}

		if ("java/lang/Record".equals(owner.superName) || (owner.recordComponents != null && !owner.recordComponents.isEmpty())) {
			if (!isStatic && OBJECT_OVERRIDES.contains(nameDesc))
				return true;
			if (!isStatic && owner.recordComponents != null) {
				for (RecordComponentNode rc : owner.recordComponents) {
					if (method.name.equals(rc.name) && method.desc.equals("()" + rc.descriptor))
						return true;
				}
			}
		}

		if (!AccessFlag.isPrivate(method.access) && !isStatic) {
			if (OBJECT_OVERRIDES.contains(nameDesc))
				return true;
			if (overridesSupertypeMethod(owner, method, graph, workspace))
				return true;
		}

		return false;
	}

	private static boolean overridesSupertypeMethod(@Nonnull ClassNode owner,
	                                                @Nonnull MethodNode method,
	                                                @Nullable InheritanceGraph graph,
	                                                @Nonnull Workspace workspace) {
		Set<String> visited = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		if (owner.superName != null && !"java/lang/Object".equals(owner.superName) && !"java/lang/Record".equals(owner.superName))
			queue.add(owner.superName);
		if (owner.interfaces != null)
			queue.addAll(owner.interfaces);

		while (!queue.isEmpty()) {
			String typeName = queue.poll();
			if ("java/lang/Object".equals(typeName) || "java/lang/Record".equals(typeName) || !visited.add(typeName))
				continue;

			ClassInfo superInfo = resolveClassInfo(typeName, graph, workspace);
			if (superInfo == null) {
				// Unresolvable external supertype/interface (e.g., Bukkit JavaPlugin or library listener):
				// conservatively keep non-private instance methods because they may override external methods.
				return true;
			}

			MethodMember superMethod = superInfo.getDeclaredMethod(method.name, method.desc);
			if (superMethod != null && !superMethod.hasPrivateModifier() && !superMethod.hasStaticModifier())
				return true;

			String nextSuper = superInfo.getSuperName();
			if (nextSuper != null && !"java/lang/Object".equals(nextSuper) && !"java/lang/Record".equals(nextSuper))
				queue.add(nextSuper);
			queue.addAll(superInfo.getInterfaces());
		}
		return false;
	}

	@Nullable
	private static ClassInfo resolveClassInfo(@Nonnull String internalName,
	                                          @Nullable InheritanceGraph graph,
	                                          @Nonnull Workspace workspace) {
		if (graph != null) {
			InheritanceVertex vertex = graph.getVertex(internalName);
			if (vertex != null)
				return vertex.getValue();
		}
		ClassPathNode path = workspace.findClass(true, internalName);
		return path == null ? null : path.getValue();
	}

	/**
	 * Extracts a constant return summary if the method straight-line executes with no side effects
	 * and returns a single primitive or {@link String} constant.
	 */
	@Nullable
	public static ConstantReturnSummary extractPureConstantReturn(@Nonnull ClassNode owner, @Nonnull MethodNode method) {
		return extractPureConstantReturn(owner, method, Map.of());
	}

	/**
	 * Extracts constant return summaries across a map of workspace classes, resolving multi-hop
	 * constant-returning call chains up to a fixed point.
	 *
	 * @param classesByName
	 * 		Map of internal class names to class nodes.
	 *
	 * @return Map from {@link #methodKey(String, String, String)} to {@link ConstantReturnSummary}.
	 */
	@Nonnull
	public static Map<String, ConstantReturnSummary> extractPureConstantReturnWithDelegation(@Nonnull Map<String, ClassNode> classesByName) {
		Map<String, ConstantReturnSummary> results = new HashMap<>();
		for (int pass = 0; pass < MAX_DELEGATION_PASSES; pass++) {
			boolean changed = false;
			for (ClassNode owner : classesByName.values()) {
				for (MethodNode method : owner.methods) {
					String key = methodKey(owner.name, method.name, method.desc);
					if (results.containsKey(key))
						continue;
					ConstantReturnSummary summary = extractPureConstantReturn(owner, method, results);
					if (summary != null) {
						results.put(key, summary);
						changed = true;
					}
				}
			}
			if (!changed)
				break;
		}
		return results;
	}

	@Nullable
	private static ConstantReturnSummary extractPureConstantReturn(@Nonnull ClassNode owner,
	                                                               @Nonnull MethodNode method,
	                                                               @Nonnull Map<String, ConstantReturnSummary> knownTargets) {
		if (AccessFlag.isAbstract(method.access) || AccessFlag.isNative(method.access) || method.name.startsWith("<"))
			return null;
		if (method.instructions == null || method.instructions.size() == 0)
			return null;
		if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty())
			return null;

		Type methodType = Type.getMethodType(method.desc);
		Type returnType = methodType.getReturnType();
		int sort = returnType.getSort();
		if (sort == Type.VOID || sort == Type.ARRAY)
			return null;
		if (sort == Type.OBJECT && !"java/lang/String".equals(returnType.getInternalName()))
			return null;

		boolean isStatic = AccessFlag.isStatic(method.access);
		int paramCount = methodType.getArgumentCount();
		int paramSlotLimit = isStatic ? 0 : 1;
		for (Type argType : methodType.getArgumentTypes())
			paramSlotLimit += argType.getSize();

		boolean ignoresParameters = true;
		Deque<StackEntry> stack = new ArrayDeque<>();
		for (AbstractInsnNode insn : method.instructions) {
			int op = insn.getOpcode();
			if (op < 0 || op == Opcodes.NOP)
				continue;

			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode)
				return null;

			if (insn instanceof VarInsnNode varInsn) {
				if (varInsn.var < paramSlotLimit)
					ignoresParameters = false;
				return null;
			}

			switch (op) {
				case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
				     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
						stack.push(new StackEntry(op - Opcodes.ICONST_0, 1));
				case Opcodes.BIPUSH, Opcodes.SIPUSH ->
						stack.push(new StackEntry(((IntInsnNode) insn).operand, 1));
				case Opcodes.LCONST_0, Opcodes.LCONST_1 ->
						stack.push(new StackEntry((long) (op - Opcodes.LCONST_0), 2));
				case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 ->
						stack.push(new StackEntry((float) (op - Opcodes.FCONST_0), 1));
				case Opcodes.DCONST_0, Opcodes.DCONST_1 ->
						stack.push(new StackEntry((double) (op - Opcodes.DCONST_0), 2));
				case Opcodes.LDC -> {
					Object cst = ((LdcInsnNode) insn).cst;
					if (cst instanceof Integer || cst instanceof Float || cst instanceof String) {
						stack.push(new StackEntry(cst, 1));
					} else if (cst instanceof Long || cst instanceof Double) {
						stack.push(new StackEntry(cst, 2));
					} else if (cst instanceof Type t && isSafeClassLiteral(t)) {
						stack.push(new StackEntry(OPAQUE_PURE_INT, 1));
					} else {
						return null;
					}
				}
				case Opcodes.POP -> {
					if (stack.isEmpty() || stack.peek().slots() != 1)
						return null;
					stack.pop();
				}
				case Opcodes.POP2 -> {
					if (stack.isEmpty())
						return null;
					StackEntry top = stack.pop();
					if (top.slots() == 1) {
						if (stack.isEmpty() || stack.peek().slots() != 1)
							return null;
						stack.pop();
					}
				}
				case Opcodes.DUP -> {
					if (stack.isEmpty() || stack.peek().slots() != 1)
						return null;
					stack.push(stack.peek());
				}
				case Opcodes.INVOKESTATIC -> {
					if (!(insn instanceof MethodInsnNode min))
						return null;
					if ("java/lang/System".equals(min.owner) && "identityHashCode".equals(min.name)
							&& "(Ljava/lang/Object;)I".equals(min.desc)) {
						if (stack.isEmpty() || stack.peek().slots() != 1)
							return null;
						stack.pop();
						stack.push(new StackEntry(OPAQUE_PURE_INT, 1));
					} else {
						ConstantReturnSummary target = knownTargets.get(methodKey(min.owner, min.name, min.desc));
						if (target == null || !target.isStatic() || !target.ignoresParameters())
							return null;
						if (!popArguments(stack, Type.getMethodType(min.desc)))
							return null;
						stack.push(new StackEntry(target.constantValue(), target.returnType().getSize()));
					}
				}
				case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> {
					if (stack.size() != 1)
						return null;
					Object raw = stack.pop().value();
					Object normalized = normalizeConstantForReturn(raw, returnType);
					if (normalized == null)
						return null;
					boolean overridable = !isStatic && !AccessFlag.isPrivate(method.access)
							&& !AccessFlag.isFinal(method.access) && !AccessFlag.isFinal(owner.access);
					return new ConstantReturnSummary(normalized, returnType, isStatic, overridable, paramCount, ignoresParameters);
				}
				default -> {
					return null;
				}
			}
		}
		return null;
	}

	private static boolean popArguments(@Nonnull Deque<StackEntry> stack, @Nonnull Type methodType) {
		Type[] args = methodType.getArgumentTypes();
		for (int i = args.length - 1; i >= 0; i--) {
			if (stack.isEmpty())
				return false;
			StackEntry entry = stack.pop();
			if (entry.slots() != args[i].getSize())
				return false;
		}
		return true;
	}

	private static boolean isSafeClassLiteral(@Nonnull Type t) {
		return t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY
				|| (t.getSort() == Type.OBJECT && GUARANTEED_JDK_CLASSES.contains(t.getInternalName()));
	}

	@Nullable
	private static Object normalizeConstantForReturn(@Nullable Object raw, @Nonnull Type returnType) {
		if (raw == null || raw == OPAQUE_PURE_INT)
			return null;
		return switch (returnType.getSort()) {
			case Type.BOOLEAN -> raw instanceof Integer i ? (i != 0) : (raw instanceof Boolean ? raw : null);
			case Type.BYTE -> raw instanceof Integer i ? i.byteValue() : (raw instanceof Byte ? raw : null);
			case Type.CHAR -> raw instanceof Integer i ? (char) i.intValue() : (raw instanceof Character ? raw : null);
			case Type.SHORT -> raw instanceof Integer i ? i.shortValue() : (raw instanceof Short ? raw : null);
			case Type.INT -> raw instanceof Integer ? raw : null;
			case Type.LONG -> raw instanceof Long ? raw : null;
			case Type.FLOAT -> raw instanceof Float ? raw : null;
			case Type.DOUBLE -> raw instanceof Double ? raw : null;
			case Type.OBJECT -> raw instanceof String && "java/lang/String".equals(returnType.getInternalName()) ? raw : null;
			default -> null;
		};
	}

	/**
	 * Formats a normalized constant return value as valid Java source literal text.
	 *
	 * @param value
	 * 		Normalized constant value.
	 * @param returnType
	 * 		Method return type.
	 *
	 * @return Java source expression text, or {@code null} if unsupported.
	 */
	@Nullable
	public static String formatJavaLiteral(@Nonnull Object value, @Nonnull Type returnType) {
		return switch (returnType.getSort()) {
			case Type.BOOLEAN -> {
				if (value instanceof Boolean b) yield Boolean.toString(b);
				if (value instanceof Integer i) yield Boolean.toString(i != 0);
				yield null;
			}
			case Type.BYTE -> value instanceof Number n ? "((byte) " + n.byteValue() + ")" : null;
			case Type.SHORT -> value instanceof Number n ? "((short) " + n.shortValue() + ")" : null;
			case Type.CHAR -> {
				if (value instanceof Character c) yield formatCharLiteral(c);
				if (value instanceof Number n) yield formatCharLiteral((char) n.intValue());
				yield null;
			}
			case Type.INT -> {
				if (!(value instanceof Number n)) yield null;
				int i = n.intValue();
				yield i == Integer.MIN_VALUE ? "Integer.MIN_VALUE" : Integer.toString(i);
			}
			case Type.LONG -> {
				if (!(value instanceof Number n)) yield null;
				long l = n.longValue();
				yield l == Long.MIN_VALUE ? "Long.MIN_VALUE" : l + "L";
			}
			case Type.FLOAT -> {
				if (!(value instanceof Number n)) yield null;
				float f = n.floatValue();
				if (Float.isNaN(f)) yield "Float.NaN";
				if (f == Float.POSITIVE_INFINITY) yield "Float.POSITIVE_INFINITY";
				if (f == Float.NEGATIVE_INFINITY) yield "Float.NEGATIVE_INFINITY";
				yield f + "F";
			}
			case Type.DOUBLE -> {
				if (!(value instanceof Number n)) yield null;
				double d = n.doubleValue();
				if (Double.isNaN(d)) yield "Double.NaN";
				if (d == Double.POSITIVE_INFINITY) yield "Double.POSITIVE_INFINITY";
				if (d == Double.NEGATIVE_INFINITY) yield "Double.NEGATIVE_INFINITY";
				yield d + "D";
			}
			case Type.OBJECT -> value instanceof String s && "java/lang/String".equals(returnType.getInternalName())
					? formatStringLiteral(s) : null;
			default -> null;
		};
	}

	@Nonnull
	private static String formatCharLiteral(char c) {
		return switch (c) {
			case '\'' -> "'\\''";
			case '\\' -> "'\\\\'";
			case '\n' -> "'\\n'";
			case '\r' -> "'\\r'";
			case '\t' -> "'\\t'";
			case '\b' -> "'\\b'";
			case '\f' -> "'\\f'";
			default -> (c >= 0x20 && c < 0x7f) ? "'" + c + "'" : String.format("'\\u%04x'", (int) c);
		};
	}

	@Nonnull
	private static String formatStringLiteral(@Nonnull String s) {
		StringBuilder sb = new StringBuilder(s.length() + 2);
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c >= 0x20 && c < 0x7f)
						sb.append(c);
					else
						sb.append(String.format("\\u%04x", (int) c));
				}
			}
		}
		sb.append('"');
		return sb.toString();
	}

	private record StackEntry(@Nonnull Object value, int slots) {}

	/**
	 * Scanned per-class usage, reflection, and nestmate information.
	 */
	public record ClassMethodUsage(@Nonnull Set<String> outgoingMethodKeys,
	                               boolean usesDeclaredMethodReflection,
	                               boolean usesPublicMethodReflection,
	                               @Nullable String nestHost,
	                               @Nonnull Set<String> nestMembers) {}

	/**
	 * Summary of a method whose body provably returns a constant without side effects.
	 */
	public record ConstantReturnSummary(@Nonnull Object constantValue,
	                                    @Nonnull Type returnType,
	                                    boolean isStatic,
	                                    boolean overridable,
	                                    int paramCount,
	                                    boolean ignoresParameters) {}
}
