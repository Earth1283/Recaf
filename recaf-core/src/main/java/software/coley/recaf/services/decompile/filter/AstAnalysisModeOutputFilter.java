package software.coley.recaf.services.decompile.filter;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompilerManagerConfig;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.util.ReflectUtil;
import software.coley.recaf.util.analysis.MethodUsageSupport;
import software.coley.recaf.util.analysis.MethodUsageSupport.ClassMethodUsage;
import software.coley.recaf.util.analysis.MethodUsageSupport.ConstantReturnSummary;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.sourcesolver.Parser;
import software.coley.sourcesolver.model.AnnotationExpressionModel;
import software.coley.sourcesolver.model.AssignmentExpressionModel;
import software.coley.sourcesolver.model.BinaryExpressionModel;
import software.coley.sourcesolver.model.CastExpressionModel;
import software.coley.sourcesolver.model.ClassModel;
import software.coley.sourcesolver.model.CompilationUnitModel;
import software.coley.sourcesolver.model.ExpressionStatementModel;
import software.coley.sourcesolver.model.ImportModel;
import software.coley.sourcesolver.model.LiteralExpressionModel;
import software.coley.sourcesolver.model.MemberSelectExpressionModel;
import software.coley.sourcesolver.model.MethodBodyModel;
import software.coley.sourcesolver.model.MethodInvocationExpressionModel;
import software.coley.sourcesolver.model.MethodModel;
import software.coley.sourcesolver.model.MethodReferenceExpressionModel;
import software.coley.sourcesolver.model.Model;
import software.coley.sourcesolver.model.NameExpressionModel;
import software.coley.sourcesolver.model.PackageModel;
import software.coley.sourcesolver.model.ParenthesizedExpressionModel;
import software.coley.sourcesolver.model.ReturnStatementModel;
import software.coley.sourcesolver.model.TypeModel;
import software.coley.sourcesolver.model.TypeParameterModel;
import software.coley.sourcesolver.model.UnaryExpressionModel;
import software.coley.sourcesolver.util.Range;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Output text filter providing an AST-walker-backed analysis mode that performs non-mutating
 * cross-class function result inlining and redundant/unused function removal in decompiled Java source.
 */
public class AstAnalysisModeOutputFilter implements OutputTextFilter {
	private static final Logger logger = Logging.get(AstAnalysisModeOutputFilter.class);
	private static final int MAX_PASSES = 4;
	private static final Set<String> DECLARED_REFLECTION_NAMES = Set.of(
			"getDeclaredMethod", "getDeclaredMethods",
			"findStatic", "findSpecial", "findVirtual", "findPrivate", "unreflect"
	);
	private static final Set<String> PUBLIC_REFLECTION_NAMES = Set.of("getMethod", "getMethods");
	private static final Set<String> PRIMITIVE_OR_STRING_KINDS = Set.of(
			"boolean", "byte", "short", "char", "int", "long", "float", "double", "String"
	);
	private static final ThreadLocal<Parser> PARSER = ThreadLocal.withInitial(Parser::new);

	static {
		ReflectUtil.patch();
	}

	private final Supplier<DecompilerManagerConfig> configSupplier;
	private final Map<Workspace, CachedWorkspaceIndex> indexCache = Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * @param config
	 * 		Decompiler manager config controlling the option.
	 */
	public AstAnalysisModeOutputFilter(@Nonnull DecompilerManagerConfig config) {
		this(() -> config);
	}

	/**
	 * @param configSupplier
	 * 		Supplier providing the active decompiler manager config.
	 */
	public AstAnalysisModeOutputFilter(@Nonnull Supplier<DecompilerManagerConfig> configSupplier) {
		this.configSupplier = configSupplier;
	}

	@Nonnull
	@Override
	public String filter(@Nonnull Workspace workspace, @Nonnull ClassInfo classInfo, @Nonnull String code) {
		if (code.isEmpty() || !configSupplier.get().getFilterAstAnalysisMode().getValue())
			return code;

		String current = code;
		try {
			WorkspaceAnalysisIndex index = getOrBuildIndex(workspace);
			Parser parser = PARSER.get();
			AstReferenceSummary initialRefSummary = null;
			for (int pass = 0; pass < MAX_PASSES; pass++) {
				CompilationUnitModel unit = parser.parse(current);
				AstReferenceSummary refSummary = collectAstReferences(unit);
				if (initialRefSummary == null)
					initialRefSummary = refSummary;
				List<TextEdit> edits = computeEdits(workspace, index, classInfo, unit, initialRefSummary, refSummary, current);
				if (edits.isEmpty())
					break;
				String next = applyEdits(current, edits);
				if (next.equals(current))
					break;
				current = next;
			}
			return current;
		} catch (Throwable t) {
			logger.debug("Skipping AST analysis mode filter for '{}': {}", classInfo.getName(), t.getMessage());
			return code;
		}
	}

	@Nonnull
	private WorkspaceAnalysisIndex getOrBuildIndex(@Nonnull Workspace workspace) {
		BundleStamp stamp = computeStamp(workspace);
		CachedWorkspaceIndex cached = indexCache.get(workspace);
		if (cached != null && cached.stamp().equals(stamp))
			return cached.index();

		WorkspaceAnalysisIndex built = WorkspaceAnalysisIndex.build(workspace);
		indexCache.put(workspace, new CachedWorkspaceIndex(stamp, built));
		return built;
	}

	@Nonnull
	private static BundleStamp computeStamp(@Nonnull Workspace workspace) {
		int count = 0;
		long hash = 1L;
		for (JvmClassBundle bundle : workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().toList()) {
			for (JvmClassInfo info : bundle.values()) {
				count++;
				hash = 31L * hash + System.identityHashCode(info);
			}
		}
		return new BundleStamp(count, hash);
	}

	@Nonnull
	private static List<TextEdit> computeEdits(@Nonnull Workspace workspace,
	                                           @Nonnull WorkspaceAnalysisIndex index,
	                                           @Nonnull ClassInfo classInfo,
	                                           @Nonnull CompilationUnitModel unit,
	                                           @Nonnull AstReferenceSummary initialRefSummary,
	                                           @Nonnull AstReferenceSummary refSummary,
	                                           @Nonnull String source) {
		ImportContext importContext = ImportContext.from(unit, workspace);
		List<ClassModel> declaredClasses = unit.getDeclaredClasses();

		List<TextEdit> inlineEdits = new ArrayList<>();
		List<TextEdit> removeEdits = new ArrayList<>();
		for (int i = 0; i < declaredClasses.size(); i++) {
			ClassModel declared = declaredClasses.get(i);
			String internalName = resolveTopLevelClassName(declaredClasses.size(), i, classInfo, importContext.packageInternal(), declared);
			processClass(workspace, index, importContext, initialRefSummary, refSummary, declared, internalName, source, inlineEdits, removeEdits);
		}
		// Inline function calls first so subsequent re-parse sees zero local calls before removing unused methods.
		return !inlineEdits.isEmpty() ? inlineEdits : removeEdits;
	}

	@Nonnull
	private static String resolveTopLevelClassName(int topLevelCount,
	                                               int index,
	                                               @Nonnull ClassInfo classInfo,
	                                               @Nonnull String packageInternal,
	                                               @Nonnull ClassModel classModel) {
		if (topLevelCount == 1 && index == 0)
			return classInfo.getName();
		return packageInternal.isEmpty() ? classModel.getName() : packageInternal + '/' + classModel.getName();
	}

	private static void processClass(@Nonnull Workspace workspace,
	                                 @Nonnull WorkspaceAnalysisIndex index,
	                                 @Nonnull ImportContext importContext,
	                                 @Nonnull AstReferenceSummary initialRefSummary,
	                                 @Nonnull AstReferenceSummary refSummary,
	                                 @Nonnull ClassModel classModel,
	                                 @Nonnull String ownerInternalName,
	                                 @Nonnull String source,
	                                 @Nonnull List<TextEdit> inlineEdits,
	                                 @Nonnull List<TextEdit> removeEdits) {
		Map<MethodSignatureKey, AstConstantMethod> localConstants = collectLocalConstantMethods(classModel, source);
		boolean classIsFinal = classModel.getModifiers().getModifiers().contains("final");

		// Step 1: Inline calls to pure constant-returning functions (intra-class & cross-class).
		collectInlineEdits(classModel, ownerInternalName, classModel.getName(), classIsFinal,
				workspace, index, importContext, localConstants, source, inlineEdits);

		// Step 2: Remove redundant/unused functions when no more inline edits are pending.
		if (inlineEdits.isEmpty()) {
			collectRemovalEdits(classModel, ownerInternalName, index, initialRefSummary, refSummary, localConstants, source, removeEdits);
		}

		for (ClassModel inner : classModel.getInnerClasses()) {
			String innerInternalName = ownerInternalName + '$' + inner.getName();
			processClass(workspace, index, importContext, initialRefSummary, refSummary, inner, innerInternalName, source, inlineEdits, removeEdits);
		}
	}

	@Nonnull
	private static Map<MethodSignatureKey, AstConstantMethod> collectLocalConstantMethods(@Nonnull ClassModel classModel,
	                                                                                      @Nonnull String source) {
		Map<MethodSignatureKey, List<AstConstantMethod>> grouped = new HashMap<>();
		boolean classIsFinal = classModel.getModifiers().getModifiers().contains("final");
		for (MethodModel method : classModel.getMethods()) {
			if (isConstructorOrClinit(classModel, method))
				continue;
			AstConstantMethod extracted = extractAstConstantMethod(method, classIsFinal, source);
			if (extracted != null)
				grouped.computeIfAbsent(new MethodSignatureKey(method.getName(), method.getParameters().size()), k -> new ArrayList<>())
						.add(extracted);
		}

		Map<MethodSignatureKey, AstConstantMethod> unique = new HashMap<>();
		for (Map.Entry<MethodSignatureKey, List<AstConstantMethod>> entry : grouped.entrySet()) {
			List<AstConstantMethod> list = entry.getValue();
			if (list.size() == 1) {
				unique.put(entry.getKey(), list.getFirst());
			} else if (allIdenticalReplacement(list)) {
				AstConstantMethod first = list.getFirst();
				boolean anyOverridable = list.stream().anyMatch(AstConstantMethod::overridable);
				unique.put(entry.getKey(), new AstConstantMethod(first.literalText(), anyOverridable));
			}
		}
		return unique;
	}

	private static boolean allIdenticalReplacement(@Nonnull List<AstConstantMethod> list) {
		String first = list.getFirst().literalText();
		for (int i = 1; i < list.size(); i++) {
			if (!first.equals(list.get(i).literalText()))
				return false;
		}
		return true;
	}

	@Nullable
	private static AstConstantMethod extractAstConstantMethod(@Nonnull MethodModel method,
	                                                            boolean classIsFinal,
	                                                            @Nonnull String source) {
		MethodBodyModel body = method.getMethodBody();
		if (body == null || !method.getAnnotations().isEmpty())
			return null;

		String returnKind = primitiveOrStringKind(method.getReturnType());
		if (returnKind == null)
			return null;

		var statements = body.getStatements();
		if (statements.isEmpty())
			return null;

		// Allow pure discarded System.identityHashCode(...) noise before the final return.
		for (int i = 0; i < statements.size() - 1; i++) {
			if (!isPureDiscardedIdentityHashCode(statements.get(i)))
				return null;
		}

		if (!(statements.getLast() instanceof ReturnStatementModel ret) || ret.getExpression() == null)
			return null;

		String literal = extractConstantExpressionText(ret.getExpression(), returnKind, source);
		if (literal == null)
			return null;

		var mods = method.getModifiers().getModifiers();
		boolean isStatic = mods.contains("static");
		boolean isPrivate = mods.contains("private");
		boolean isFinal = mods.contains("final");
		boolean overridable = !isStatic && !isPrivate && !isFinal && !classIsFinal;
		return new AstConstantMethod(literal, overridable);
	}

	private static boolean isPureDiscardedIdentityHashCode(@Nonnull Model statement) {
		if (!(statement instanceof ExpressionStatementModel esm))
			return false;
		if (!(esm.getExpression() instanceof MethodInvocationExpressionModel call))
			return false;
		if (!"identityHashCode".equals(call.getMethodName()) || call.getArguments().size() != 1)
			return false;
		if (!(call.getMethodSelect() instanceof MemberSelectExpressionModel ms))
			return false;
		String qualifier = stringifyQualifier(ms.getContext());
		return ("System".equals(qualifier) || "java.lang.System".equals(qualifier))
				&& isPureExpression(call.getArguments().getFirst());
	}

	private static void collectInlineEdits(@Nonnull ClassModel classModel,
	                                       @Nonnull String ownerInternalName,
	                                       @Nonnull String ownerSimpleName,
	                                       boolean classIsFinal,
	                                       @Nonnull Workspace workspace,
	                                       @Nonnull WorkspaceAnalysisIndex index,
	                                       @Nonnull ImportContext importContext,
	                                       @Nonnull Map<MethodSignatureKey, AstConstantMethod> localConstants,
	                                       @Nonnull String source,
	                                       @Nonnull List<TextEdit> inlineEdits) {
		for (var field : classModel.getFields()) {
			if (field.getValue() != null)
				scanCallsInSubtree(field.getValue(), ownerInternalName, ownerSimpleName, workspace, index, importContext, localConstants, inlineEdits);
		}
		for (MethodModel method : classModel.getMethods()) {
			if (method.getMethodBody() != null)
				scanCallsInSubtree(method.getMethodBody(), ownerInternalName, ownerSimpleName, workspace, index, importContext, localConstants, inlineEdits);
		}
	}

	private static void scanCallsInSubtree(@Nonnull Model root,
	                                       @Nonnull String ownerInternalName,
	                                       @Nonnull String ownerSimpleName,
	                                       @Nonnull Workspace workspace,
	                                       @Nonnull WorkspaceAnalysisIndex index,
	                                       @Nonnull ImportContext importContext,
	                                       @Nonnull Map<MethodSignatureKey, AstConstantMethod> localConstants,
	                                       @Nonnull List<TextEdit> inlineEdits) {
		root.visit(model -> {
			if (model instanceof ClassModel)
				return false;
			if (isNonCallContext(model))
				return false;
			if (!(model instanceof MethodInvocationExpressionModel call))
				return true;

			// Never turn a standalone statement call (e.g. "foo();") into an invalid statement literal ("\"AIR\";").
			if (call.getParent() instanceof ExpressionStatementModel)
				return true;

			Range range = call.getRange();
			if (range.isUnknown())
				return true;

			for (var arg : call.getArguments()) {
				if (!isPureExpression(arg))
					return true;
			}

			CallTarget target = resolveCallTarget(call, ownerInternalName, ownerSimpleName, workspace, index, importContext, localConstants);
			if (target == null)
				return true;

			String replacement = resolveConstantReplacement(target, ownerInternalName, index, localConstants);
			if (replacement != null) {
				String wrapped = needsParensForReplacement(replacement) ? "(" + replacement + ")" : replacement;
				inlineEdits.add(new TextEdit(range.begin(), range.end(), wrapped));
				// Skip nested arguments inside this replaced call span so edits never overlap.
				return false;
			}
			return true;
		});
	}

	@Nullable
	private static CallTarget resolveCallTarget(@Nonnull MethodInvocationExpressionModel call,
	                                            @Nonnull String ownerInternalName,
	                                            @Nonnull String ownerSimpleName,
	                                            @Nonnull Workspace workspace,
	                                            @Nonnull WorkspaceAnalysisIndex index,
	                                            @Nonnull ImportContext importContext,
	                                            @Nonnull Map<MethodSignatureKey, AstConstantMethod> localConstants) {
		String methodName = call.getMethodName();
		int argCount = call.getArguments().size();
		Model select = call.getMethodSelect();

		if (select instanceof NameExpressionModel) {
			MethodSignatureKey sig = new MethodSignatureKey(methodName, argCount);
			if (localConstants.containsKey(sig) || index.lookupUnique(ownerInternalName, methodName, argCount) != null)
				return new CallTarget(ownerInternalName, methodName, argCount, true, true);
			String staticOwner = importContext.resolveStaticImport(methodName, argCount, index);
			if (staticOwner != null)
				return new CallTarget(staticOwner, methodName, argCount, true, false);
			return null;
		}

		if (select instanceof MemberSelectExpressionModel ms) {
			Model ctx = unwrapParens(ms.getContext());
			if (ctx instanceof NameExpressionModel ne) {
				String qualifier = ne.getName();
				if ("this".equals(qualifier))
					return new CallTarget(ownerInternalName, methodName, argCount, true, true);
				if (ownerSimpleName.equals(qualifier))
					return new CallTarget(ownerInternalName, methodName, argCount, false, false);
				String resolvedOwner = importContext.resolveClassName(qualifier, ownerInternalName, workspace);
				if (resolvedOwner != null)
					return new CallTarget(resolvedOwner, methodName, argCount, false, false);
				return null;
			}

			String dotted = stringifyQualifier(ctx);
			if (dotted != null) {
				String internal = dotted.replace('.', '/');
				if (index.hasClass(internal) || workspace.findJvmClass(internal) != null)
					return new CallTarget(internal, methodName, argCount, false, false);
			}
		}
		return null;
	}

	@Nullable
	private static String resolveConstantReplacement(@Nonnull CallTarget target,
	                                                 @Nonnull String currentOwnerInternalName,
	                                                 @Nonnull WorkspaceAnalysisIndex index,
	                                                 @Nonnull Map<MethodSignatureKey, AstConstantMethod> localConstants) {
		if (target.ownerInternalName().equals(currentOwnerInternalName)) {
			AstConstantMethod local = localConstants.get(new MethodSignatureKey(target.methodName(), target.argCount()));
			if (local != null && !local.overridable())
				return local.literalText();
		}

		ConstantReturnSummary summary = index.lookupUnique(target.ownerInternalName(), target.methodName(), target.argCount());
		if (summary == null || summary.overridable())
			return null;
		if (!summary.isStatic() && !target.allowInstanceReceiverThis())
			return null;
		return MethodUsageSupport.formatJavaLiteral(summary.constantValue(), summary.returnType());
	}

	private static void collectRemovalEdits(@Nonnull ClassModel classModel,
	                                        @Nonnull String ownerInternalName,
	                                        @Nonnull WorkspaceAnalysisIndex index,
	                                        @Nonnull AstReferenceSummary initialRefSummary,
	                                        @Nonnull AstReferenceSummary refSummary,
	                                        @Nonnull Map<MethodSignatureKey, AstConstantMethod> localConstants,
	                                        @Nonnull String source,
	                                        @Nonnull List<TextEdit> removeEdits) {
		boolean declaredReflection = refSummary.usesDeclaredReflection()
				|| index.hasDeclaredReflectionInNest(ownerInternalName);
		boolean publicReflection = refSummary.usesPublicReflection()
				|| index.hasPublicReflection(ownerInternalName);

		for (MethodModel method : classModel.getMethods()) {
			if (isConstructorOrClinit(classModel, method) || method.getMethodBody() == null)
				continue;
			Range range = method.getRange();
			if (range.isUnknown())
				continue;
			if (!method.getAnnotations().isEmpty())
				continue;

			String name = method.getName();
			int paramCount = method.getParameters().size();
			if (refSummary.isReferencedInUnit(name, paramCount))
				continue;

			var mods = method.getModifiers().getModifiers();
			boolean isPrivate = mods.contains("private");
			boolean isStatic = mods.contains("static");
			if (MethodUsageSupport.SERIALIZATION_HOOKS.contains(name))
				continue;
			if (isStatic && isStaticEntryPoint(name, method))
				continue;
			if (index.isProtectedBytecodeMethod(ownerInternalName, name, paramCount))
				continue;
			if (!isPrivate && !isStatic && isObjectMethod(name, paramCount))
				continue;

			if (isPrivate) {
				if (declaredReflection)
					continue;
				if (index.hasExternalReferenceThatIsNotInlineableConstant(ownerInternalName, name, paramCount))
					continue;
			} else {
				boolean isPublic = mods.contains("public");
				if (declaredReflection || (isPublic && publicReflection))
					continue;
				boolean isKnownConstant = localConstants.containsKey(new MethodSignatureKey(name, paramCount))
						|| index.lookupUnique(ownerInternalName, name, paramCount) != null;
				if (!isKnownConstant)
					continue;
				// Non-private methods are only removed if they actually had callers that were inlined away.
				boolean hadInlinedLocalCallers = initialRefSummary.isReferencedInUnit(name, paramCount);
				boolean hadInlinedExternalCallers = index.hasExternalInlineableReference(ownerInternalName, name, paramCount);
				if (!hadInlinedLocalCallers && !hadInlinedExternalCallers)
					continue;
				if (index.hasExternalReferenceThatIsNotInlineableConstant(ownerInternalName, name, paramCount))
					continue;
			}

			TextEdit deleteEdit = createLineDeleteEdit(source, range.begin(), range.end());
			if (deleteEdit != null)
				removeEdits.add(deleteEdit);
		}
	}

	private static boolean isConstructorOrClinit(@Nonnull ClassModel classModel, @Nonnull MethodModel method) {
		return method.isStaticInitializer()
				|| method.getReturnType() == null
				|| method.getName().equals(classModel.getName());
	}

	private static boolean isStaticEntryPoint(@Nonnull String name, @Nonnull MethodModel method) {
		int params = method.getParameters().size();
		return ("main".equals(name) && params == 1)
				|| ("premain".equals(name) && (params == 1 || params == 2))
				|| ("agentmain".equals(name) && (params == 1 || params == 2));
	}

	private static boolean isObjectMethod(@Nonnull String name, int paramCount) {
		return ("equals".equals(name) && paramCount == 1)
				|| ("hashCode".equals(name) && paramCount == 0)
				|| ("toString".equals(name) && paramCount == 0)
				|| ("clone".equals(name) && paramCount == 0)
				|| ("finalize".equals(name) && paramCount == 0);
	}

	@Nonnull
	private static AstReferenceSummary collectAstReferences(@Nonnull CompilationUnitModel unit) {
		Set<MethodSignatureKey> invocations = new HashSet<>();
		Set<String> methodReferenceNames = new HashSet<>();
		boolean[] declaredReflection = {false};
		boolean[] publicReflection = {false};

		unit.visit(model -> {
			if (model instanceof MethodInvocationExpressionModel call) {
				String name = call.getMethodName();
				invocations.add(new MethodSignatureKey(name, call.getArguments().size()));
				if (DECLARED_REFLECTION_NAMES.contains(name))
					declaredReflection[0] = true;
				else if (PUBLIC_REFLECTION_NAMES.contains(name))
					publicReflection[0] = true;
			} else if (model instanceof MethodReferenceExpressionModel ref) {
				methodReferenceNames.add(ref.getNameModel().getName());
			}
			return true;
		});

		return new AstReferenceSummary(invocations, methodReferenceNames, declaredReflection[0], publicReflection[0]);
	}

	private static boolean isNonCallContext(@Nonnull Model model) {
		return model instanceof ImportModel
				|| model instanceof PackageModel
				|| model instanceof AnnotationExpressionModel
				|| model instanceof TypeModel
				|| model instanceof TypeParameterModel;
	}

	private static boolean isPureExpression(@Nullable Model expr) {
		if (expr == null)
			return false;
		if (expr instanceof LiteralExpressionModel || expr instanceof NameExpressionModel)
			return true;
		if (expr instanceof ParenthesizedExpressionModel p)
			return isPureExpression(p.getExpression());
		if (expr instanceof CastExpressionModel c)
			return isPureExpression(c.getExpression());
		if (expr instanceof UnaryExpressionModel u) {
			return switch (u.getOperator()) {
				case POSITIVE, NEGATIVE, NOT, BIT_NOT -> isPureExpression(u.getExpression());
				default -> false;
			};
		}
		if (expr instanceof BinaryExpressionModel b) {
			var op = b.getOperator();
			if (op == BinaryExpressionModel.Operator.UNKNOWN)
				return false;
			return isPureExpression(b.getLeft()) && isPureExpression(b.getRight());
		}
		if (expr instanceof MemberSelectExpressionModel ms)
			return isPureQualifier(ms.getContext());
		return false;
	}

	private static boolean isPureQualifier(@Nullable Model model) {
		if (model instanceof NameExpressionModel)
			return true;
		if (model instanceof MemberSelectExpressionModel ms)
			return isPureQualifier(ms.getContext());
		if (model instanceof ParenthesizedExpressionModel p)
			return isPureQualifier(p.getExpression());
		return false;
	}

	@Nullable
	private static String stringifyQualifier(@Nullable Model model) {
		Model unwrapped = unwrapParens(model);
		if (unwrapped instanceof NameExpressionModel ne)
			return ne.getName();
		if (unwrapped instanceof MemberSelectExpressionModel ms) {
			String prefix = stringifyQualifier(ms.getContext());
			return prefix == null ? null : prefix + '.' + ms.getName();
		}
		return null;
	}

	@Nullable
	private static String extractConstantExpressionText(@Nullable Model expr,
	                                                    @Nonnull String expectedKind,
	                                                    @Nonnull String source) {
		Model current = unwrapParens(expr);
		if (current instanceof CastExpressionModel cast) {
			String castKind = primitiveOrStringKind(cast.getType());
			if (!expectedKind.equals(castKind))
				return null;
			current = unwrapParens(cast.getExpression());
		}

		boolean negative = false;
		boolean positive = false;
		if (current instanceof UnaryExpressionModel unary) {
			if (unary.getOperator() == UnaryExpressionModel.Operator.NEGATIVE) {
				negative = true;
				current = unwrapParens(unary.getExpression());
			} else if (unary.getOperator() == UnaryExpressionModel.Operator.POSITIVE) {
				positive = true;
				current = unwrapParens(unary.getExpression());
			} else {
				return null;
			}
		}

		if (!(current instanceof LiteralExpressionModel lit) || lit.getRange().isUnknown())
			return null;

		LiteralExpressionModel.Kind kind = lit.getKind();
		String raw = source.substring(lit.getRange().begin(), lit.getRange().end());
		String prefix = negative ? "-" : (positive ? "+" : "");

		return switch (expectedKind) {
			case "boolean" -> (!negative && !positive && kind == LiteralExpressionModel.Kind.BOOLEAN) ? raw : null;
			case "byte" -> kind == LiteralExpressionModel.Kind.INT ? "((byte) " + prefix + raw + ")" : null;
			case "short" -> kind == LiteralExpressionModel.Kind.INT ? "((short) " + prefix + raw + ")" : null;
			case "char" -> (!negative && !positive && kind == LiteralExpressionModel.Kind.CHAR) ? raw : null;
			case "String" -> (!negative && !positive && kind == LiteralExpressionModel.Kind.STRING) ? raw : null;
			case "int" -> kind == LiteralExpressionModel.Kind.INT ? prefix + raw : null;
			case "long" -> {
				if (kind == LiteralExpressionModel.Kind.LONG)
					yield prefix + raw;
				if (kind == LiteralExpressionModel.Kind.INT)
					yield prefix + raw + "L";
				yield null;
			}
			case "float" -> {
				if (kind == LiteralExpressionModel.Kind.FLOAT)
					yield prefix + raw;
				if (kind == LiteralExpressionModel.Kind.INT)
					yield prefix + raw + "F";
				yield null;
			}
			case "double" -> {
				if (kind == LiteralExpressionModel.Kind.DOUBLE)
					yield prefix + raw;
				if (kind == LiteralExpressionModel.Kind.INT)
					yield prefix + raw + "D";
				yield null;
			}
			default -> null;
		};
	}

	@Nullable
	private static String primitiveOrStringKind(@Nullable Model type) {
		if (type instanceof TypeModel.Primitive prim) {
			String name = prim.getName();
			return PRIMITIVE_OR_STRING_KINDS.contains(name) ? name : null;
		}
		if (type instanceof TypeModel.NamedObject named) {
			String name = named.getName();
			return ("String".equals(name) || "java.lang.String".equals(name)) ? "String" : null;
		}
		return null;
	}

	@Nullable
	private static Model unwrapParens(@Nullable Model model) {
		while (model instanceof ParenthesizedExpressionModel p)
			model = p.getExpression();
		return model;
	}

	private static boolean needsParensForReplacement(@Nonnull String replacement) {
		return replacement.startsWith("-") || replacement.startsWith("+");
	}

	@Nullable
	private static TextEdit createLineDeleteEdit(@Nonnull String source, int begin, int end) {
		if (begin < 0 || end > source.length() || begin >= end)
			return null;
		int lineStart = begin;
		while (lineStart > 0 && (source.charAt(lineStart - 1) == ' ' || source.charAt(lineStart - 1) == '\t'))
			lineStart--;
		boolean atLineStart = (lineStart == 0 || source.charAt(lineStart - 1) == '\n');

		int lineEnd = end;
		while (lineEnd < source.length() && (source.charAt(lineEnd) == ' ' || source.charAt(lineEnd) == '\t'))
			lineEnd++;
		boolean atLineEnd = false;
		if (lineEnd == source.length()) {
			atLineEnd = true;
		} else if (source.charAt(lineEnd) == '\n') {
			lineEnd++;
			atLineEnd = true;
		} else if (lineEnd + 1 < source.length() && source.charAt(lineEnd) == '\r' && source.charAt(lineEnd + 1) == '\n') {
			lineEnd += 2;
			atLineEnd = true;
		}
		if (atLineStart && atLineEnd) {
			begin = lineStart;
			end = lineEnd;
			// Consume one following blank line if present so deleted methods do not leave double blank lines.
			if (end < source.length() && source.charAt(end) == '\n')
				end++;
			else if (end + 1 < source.length() && source.charAt(end) == '\r' && source.charAt(end + 1) == '\n')
				end += 2;
		}
		return new TextEdit(begin, end, "");
	}

	@Nonnull
	private static String applyEdits(@Nonnull String source, @Nonnull List<TextEdit> edits) {
		List<TextEdit> sorted = new ArrayList<>(edits);
		sorted.sort(Comparator.comparingInt(TextEdit::begin).thenComparingInt(TextEdit::end).reversed());
		StringBuilder sb = new StringBuilder(source);
		int lastStart = Integer.MAX_VALUE;
		for (TextEdit edit : sorted) {
			if (edit.end() > lastStart || edit.begin() < 0 || edit.end() > sb.length() || edit.begin() > edit.end())
				continue;
			sb.replace(edit.begin(), edit.end(), edit.replacement());
			lastStart = edit.begin();
		}
		return sb.toString();
	}

	private record BundleStamp(int count, long identityHash) {}

	private record CachedWorkspaceIndex(@Nonnull BundleStamp stamp, @Nonnull WorkspaceAnalysisIndex index) {}

	private record TextEdit(int begin, int end, @Nonnull String replacement) {}

	private record MethodSignatureKey(@Nonnull String name, int argCount) {}

	private record OwnerMethodSignatureKey(@Nonnull String owner, @Nonnull String name, int argCount) {}

	private record AstConstantMethod(@Nonnull String literalText, boolean overridable) {}

	private record CallTarget(@Nonnull String ownerInternalName, @Nonnull String methodName, int argCount,
	                          boolean unqualifiedOrThis, boolean allowInstanceReceiverThis) {}

	private record AstReferenceSummary(@Nonnull Set<MethodSignatureKey> invocations,
	                                   @Nonnull Set<String> methodReferenceNames,
	                                   boolean usesDeclaredReflection,
	                                   boolean usesPublicReflection) {
		boolean isReferencedInUnit(@Nonnull String name, int argCount) {
			return methodReferenceNames.contains(name) || invocations.contains(new MethodSignatureKey(name, argCount));
		}
	}

	private static final class ImportContext {
		private final String packageInternal;
		private final Map<String, String> importedClasses;
		private final List<String> wildcardPackages;
		private final Map<String, List<String>> staticMethodImports;
		private final List<String> staticWildcardOwners;

		private ImportContext(@Nonnull String packageInternal,
		                      @Nonnull Map<String, String> importedClasses,
		                      @Nonnull List<String> wildcardPackages,
		                      @Nonnull Map<String, List<String>> staticMethodImports,
		                      @Nonnull List<String> staticWildcardOwners) {
			this.packageInternal = packageInternal;
			this.importedClasses = importedClasses;
			this.wildcardPackages = wildcardPackages;
			this.staticMethodImports = staticMethodImports;
			this.staticWildcardOwners = staticWildcardOwners;
		}

		@Nonnull
		static ImportContext from(@Nonnull CompilationUnitModel unit, @Nonnull Workspace workspace) {
			PackageModel pkg = unit.getPackage();
			String packageInternal = (pkg == null || pkg.getName().isBlank()) ? "" : pkg.getName().replace('.', '/');

			Map<String, String> importedClasses = new HashMap<>();
			List<String> wildcardPackages = new ArrayList<>();
			Map<String, List<String>> staticMethodImports = new HashMap<>();
			List<String> staticWildcardOwners = new ArrayList<>();

			for (ImportModel imp : unit.getImports()) {
				String raw = imp.getName();
				if (raw == null || raw.isBlank())
					continue;
				if (imp.isStatic()) {
					if (raw.endsWith(".*")) {
						String classInternal = resolveDottedClassName(raw.substring(0, raw.length() - 2), workspace);
						if (classInternal != null)
							staticWildcardOwners.add(classInternal);
					} else {
						int lastDot = raw.lastIndexOf('.');
						if (lastDot > 0) {
							String classInternal = resolveDottedClassName(raw.substring(0, lastDot), workspace);
							String methodName = raw.substring(lastDot + 1);
							if (classInternal != null)
								staticMethodImports.computeIfAbsent(methodName, k -> new ArrayList<>()).add(classInternal);
						}
					}
				} else {
					if (raw.endsWith(".*")) {
						wildcardPackages.add(raw.substring(0, raw.length() - 2).replace('.', '/'));
					} else {
						int lastDot = raw.lastIndexOf('.');
						String simple = lastDot >= 0 ? raw.substring(lastDot + 1) : raw;
						String internal = resolveDottedClassName(raw, workspace);
						importedClasses.put(simple, internal != null ? internal : raw.replace('.', '/'));
					}
				}
			}

			return new ImportContext(packageInternal, importedClasses, wildcardPackages, staticMethodImports, staticWildcardOwners);
		}

		@Nonnull
		String packageInternal() {
			return packageInternal;
		}

		@Nullable
		String resolveClassName(@Nonnull String simpleName, @Nonnull String currentClassInternal, @Nonnull Workspace workspace) {
			String innerCandidate = currentClassInternal + '$' + simpleName;
			if (workspace.findJvmClass(innerCandidate) != null)
				return innerCandidate;

			String imported = importedClasses.get(simpleName);
			if (imported != null)
				return imported;

			String samePackage = packageInternal.isEmpty() ? simpleName : packageInternal + '/' + simpleName;
			if (workspace.findJvmClass(samePackage) != null)
				return samePackage;

			for (String wildcardPkg : wildcardPackages) {
				String candidate = wildcardPkg.isEmpty() ? simpleName : wildcardPkg + '/' + simpleName;
				if (workspace.findJvmClass(candidate) != null)
					return candidate;
			}
			return null;
		}

		@Nullable
		String resolveStaticImport(@Nonnull String methodName, int argCount, @Nonnull WorkspaceAnalysisIndex index) {
			List<String> owners = staticMethodImports.get(methodName);
			if (owners != null) {
				for (String owner : owners) {
					if (index.lookupUnique(owner, methodName, argCount) != null)
						return owner;
				}
			}
			for (String owner : staticWildcardOwners) {
				if (index.lookupUnique(owner, methodName, argCount) != null)
					return owner;
			}
			return null;
		}

		@Nullable
		private static String resolveDottedClassName(@Nonnull String dotted, @Nonnull Workspace workspace) {
			String direct = dotted.replace('.', '/');
			if (workspace.findJvmClass(direct) != null)
				return direct;
			StringBuilder candidate = new StringBuilder(direct);
			int lastSlash = candidate.lastIndexOf("/");
			while (lastSlash > 0) {
				candidate.setCharAt(lastSlash, '$');
				String attempt = candidate.toString();
				if (workspace.findJvmClass(attempt) != null)
					return attempt;
				lastSlash = candidate.lastIndexOf("/", lastSlash - 1);
			}
			return direct;
		}
	}

	private static final class WorkspaceAnalysisIndex {
		private final Map<String, ClassNode> classesByName;
		private final Map<String, ConstantReturnSummary> constantByMethodKey;
		private final Map<OwnerMethodSignatureKey, ConstantReturnSummary> uniqueByOwnerNameArity;
		private final Map<String, ClassMethodUsage> usageByClass;
		private final Set<OwnerMethodSignatureKey> protectedMethods;
		private final Set<OwnerMethodSignatureKey> nonInlineableExternalRefs;
		private final Set<OwnerMethodSignatureKey> allExternalRefs;

		private WorkspaceAnalysisIndex(@Nonnull Map<String, ClassNode> classesByName,
		                               @Nonnull Map<String, ConstantReturnSummary> constantByMethodKey,
		                               @Nonnull Map<OwnerMethodSignatureKey, ConstantReturnSummary> uniqueByOwnerNameArity,
		                               @Nonnull Map<String, ClassMethodUsage> usageByClass,
		                               @Nonnull Set<OwnerMethodSignatureKey> protectedMethods,
		                               @Nonnull Set<OwnerMethodSignatureKey> nonInlineableExternalRefs,
		                               @Nonnull Set<OwnerMethodSignatureKey> allExternalRefs) {
			this.classesByName = classesByName;
			this.constantByMethodKey = constantByMethodKey;
			this.uniqueByOwnerNameArity = uniqueByOwnerNameArity;
			this.usageByClass = usageByClass;
			this.protectedMethods = protectedMethods;
			this.nonInlineableExternalRefs = nonInlineableExternalRefs;
			this.allExternalRefs = allExternalRefs;
		}

		@Nonnull
		static WorkspaceAnalysisIndex build(@Nonnull Workspace workspace) {
			Map<String, ClassNode> classesByName = new LinkedHashMap<>();
			for (JvmClassBundle bundle : workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().toList()) {
				for (JvmClassInfo info : bundle.values()) {
					ClassNode node = new ClassNode();
					info.getClassReader().accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					classesByName.putIfAbsent(node.name, node);
				}
			}

			Map<String, ConstantReturnSummary> constantByMethodKey = MethodUsageSupport.extractPureConstantReturnWithDelegation(classesByName);

			Map<OwnerMethodSignatureKey, List<ConstantReturnSummary>> groupedConstants = new HashMap<>();
			for (Map.Entry<String, ConstantReturnSummary> entry : constantByMethodKey.entrySet()) {
				String key = entry.getKey();
				int dot = key.indexOf('.');
				int paren = key.indexOf('(', dot + 1);
				if (dot < 0 || paren < 0)
					continue;
				String owner = key.substring(0, dot);
				String name = key.substring(dot + 1, paren);
				ConstantReturnSummary summary = entry.getValue();
				groupedConstants.computeIfAbsent(new OwnerMethodSignatureKey(owner, name, summary.paramCount()), k -> new ArrayList<>())
						.add(summary);
			}
			Map<OwnerMethodSignatureKey, ConstantReturnSummary> uniqueByOwnerNameArity = new HashMap<>();
			for (Map.Entry<OwnerMethodSignatureKey, List<ConstantReturnSummary>> entry : groupedConstants.entrySet()) {
				List<ConstantReturnSummary> list = entry.getValue();
				if (list.size() == 1) {
					uniqueByOwnerNameArity.put(entry.getKey(), list.getFirst());
				} else if (allIdenticalConstantSummary(list)) {
					uniqueByOwnerNameArity.put(entry.getKey(), list.getFirst());
				}
			}

			// Build primary inheritance closure so virtual/interface calls propagate to parents/children.
			Map<String, Set<String>> relatedPrimaryTypes = buildPrimaryHierarchyClosure(classesByName);
			Map<String, ClassMethodUsage> usageByClass = new HashMap<>();
			Set<OwnerMethodSignatureKey> protectedMethods = new HashSet<>();
			for (ClassNode node : classesByName.values()) {
				usageByClass.put(node.name, MethodUsageSupport.scanClassNode(node, null));
				for (MethodNode method : node.methods) {
					if (MethodUsageSupport.isAlwaysProtectedMethod(node, method, null, workspace)) {
						int paramCount = Type.getArgumentCount(method.desc);
						protectedMethods.add(new OwnerMethodSignatureKey(node.name, method.name, paramCount));
					}
				}
			}

			// Scan all external call sites to distinguish inlineable pure calls from general/non-inlineable references.
			Set<OwnerMethodSignatureKey> allExternalRefs = new HashSet<>();
			Set<OwnerMethodSignatureKey> nonInlineableExternalRefs = new HashSet<>();
			for (ClassNode caller : classesByName.values()) {
				ClassMethodUsage usage = usageByClass.get(caller.name);
				if (usage != null) {
					for (String targetKey : usage.outgoingMethodKeys()) {
						OwnerMethodSignatureKey sig = parseMethodKey(targetKey);
						if (sig == null || sig.owner().equals(caller.name))
							continue;
						allExternalRefs.add(sig);
						for (String rel : relatedPrimaryTypes.getOrDefault(sig.owner(), Set.of())) {
							OwnerMethodSignatureKey relSig = new OwnerMethodSignatureKey(rel, sig.name(), sig.argCount());
							allExternalRefs.add(relSig);
							nonInlineableExternalRefs.add(relSig);
						}
					}
				}

				for (MethodNode method : caller.methods) {
					if (method.instructions == null)
						continue;
					for (AbstractInsnNode insn : method.instructions) {
						if (insn instanceof MethodInsnNode min && !min.owner.equals(caller.name)) {
							OwnerMethodSignatureKey sig = new OwnerMethodSignatureKey(min.owner, min.name, Type.getArgumentCount(min.desc));
							String targetKey = MethodUsageSupport.methodKey(min.owner, min.name, min.desc);
							ConstantReturnSummary summary = constantByMethodKey.get(targetKey);
							if (summary == null || summary.overridable() || !isCallSiteInlineableInAst(min, summary))
								nonInlineableExternalRefs.add(sig);
						}
					}
				}
			}

			// Mark Handle/InvokeDynamic references in other classes as non-inlineable.
			for (ClassNode caller : classesByName.values()) {
				for (MethodNode method : caller.methods) {
					if (method.instructions == null)
						continue;
					for (AbstractInsnNode insn : method.instructions) {
						if (insn.getType() == AbstractInsnNode.INVOKE_DYNAMIC_INSN || insn.getType() == AbstractInsnNode.LDC_INSN) {
							Set<String> handleRefs = MethodUsageSupport.scanClassNode(singleMethodWrapper(caller.name, method), null).outgoingMethodKeys();
							for (String ref : handleRefs) {
								OwnerMethodSignatureKey sig = parseMethodKey(ref);
								if (sig != null && !sig.owner().equals(caller.name))
									nonInlineableExternalRefs.add(sig);
							}
						}
					}
				}
			}

			return new WorkspaceAnalysisIndex(classesByName, constantByMethodKey, uniqueByOwnerNameArity,
					usageByClass, protectedMethods, nonInlineableExternalRefs, allExternalRefs);
		}

		private static boolean isCallSiteInlineableInAst(@Nonnull MethodInsnNode call,
		                                                 @Nonnull ConstantReturnSummary summary) {
			if (!summary.isStatic() && call.getOpcode() != Opcodes.INVOKESTATIC) {
				// External instance calls require a non-'this' qualifier, so AST mode won't inline them safely.
				return false;
			}
			// If return value is immediately discarded by POP/POP2, AST mode keeps statement calls intact.
			AbstractInsnNode next = call.getNext();
			while (next != null && (next.getOpcode() < 0 || next.getOpcode() == Opcodes.NOP))
				next = next.getNext();
			if (next != null && (next.getOpcode() == Opcodes.POP || next.getOpcode() == Opcodes.POP2))
				return false;
			return summary.paramCount() == 0;
		}

		@Nonnull
		private static ClassNode singleMethodWrapper(@Nonnull String name, @Nonnull MethodNode method) {
			ClassNode cn = new ClassNode();
			cn.name = name;
			cn.methods.add(method);
			return cn;
		}

		@Nonnull
		private static Map<String, Set<String>> buildPrimaryHierarchyClosure(@Nonnull Map<String, ClassNode> classesByName) {
			Map<String, Set<String>> directParents = new HashMap<>();
			Map<String, Set<String>> directChildren = new HashMap<>();
			for (ClassNode cn : classesByName.values()) {
				Set<String> parents = directParents.computeIfAbsent(cn.name, k -> new HashSet<>());
				if (cn.superName != null && classesByName.containsKey(cn.superName)) {
					parents.add(cn.superName);
					directChildren.computeIfAbsent(cn.superName, k -> new HashSet<>()).add(cn.name);
				}
				if (cn.interfaces != null) {
					for (String itf : cn.interfaces) {
						if (classesByName.containsKey(itf)) {
							parents.add(itf);
							directChildren.computeIfAbsent(itf, k -> new HashSet<>()).add(cn.name);
						}
					}
				}
			}
			Map<String, Set<String>> closure = new HashMap<>();
			for (String name : classesByName.keySet()) {
				Set<String> related = new HashSet<>();
				collectClosure(name, directParents, related);
				collectClosure(name, directChildren, related);
				related.remove(name);
				if (!related.isEmpty())
					closure.put(name, related);
			}
			return closure;
		}

		private static void collectClosure(@Nonnull String start,
		                                   @Nonnull Map<String, Set<String>> edges,
		                                   @Nonnull Set<String> out) {
			Deque<String> queue = new ArrayDeque<>();
			queue.add(start);
			while (!queue.isEmpty()) {
				String cur = queue.poll();
				for (String next : edges.getOrDefault(cur, Set.of())) {
					if (out.add(next))
						queue.add(next);
				}
			}
		}

		private static boolean allIdenticalConstantSummary(@Nonnull List<ConstantReturnSummary> list) {
			ConstantReturnSummary first = list.getFirst();
			String firstText = MethodUsageSupport.formatJavaLiteral(first.constantValue(), first.returnType());
			if (firstText == null)
				return false;
			for (int i = 1; i < list.size(); i++) {
				ConstantReturnSummary other = list.get(i);
				String otherText = MethodUsageSupport.formatJavaLiteral(other.constantValue(), other.returnType());
				if (!firstText.equals(otherText) || first.isStatic() != other.isStatic() || first.overridable() != other.overridable())
					return false;
			}
			return true;
		}

		@Nullable
		private static OwnerMethodSignatureKey parseMethodKey(@Nonnull String key) {
			int dot = key.indexOf('.');
			int paren = key.indexOf('(', dot + 1);
			if (dot < 0 || paren < 0)
				return null;
			String owner = key.substring(0, dot);
			String name = key.substring(dot + 1, paren);
			String desc = key.substring(paren);
			try {
				return new OwnerMethodSignatureKey(owner, name, Type.getArgumentCount(desc));
			} catch (Throwable ignored) {
				return null;
			}
		}

		boolean hasClass(@Nonnull String internalName) {
			return classesByName.containsKey(internalName);
		}

		@Nullable
		ConstantReturnSummary lookupUnique(@Nonnull String owner, @Nonnull String name, int argCount) {
			return uniqueByOwnerNameArity.get(new OwnerMethodSignatureKey(owner, name, argCount));
		}

		boolean hasDeclaredReflectionInNest(@Nonnull String ownerInternalName) {
			ClassNode node = classesByName.get(ownerInternalName);
			if (node == null)
				return false;
			return MethodUsageSupport.hasDeclaredMethodReflectionInNest(node, usageByClass);
		}

		boolean hasPublicReflection(@Nonnull String ownerInternalName) {
			ClassMethodUsage usage = usageByClass.get(ownerInternalName);
			return usage != null && usage.usesPublicMethodReflection();
		}

		boolean isProtectedBytecodeMethod(@Nonnull String ownerInternalName, @Nonnull String name, int paramCount) {
			return protectedMethods.contains(new OwnerMethodSignatureKey(ownerInternalName, name, paramCount));
		}

		boolean hasExternalInlineableReference(@Nonnull String ownerInternalName,
		                                       @Nonnull String name,
		                                       int paramCount) {
			OwnerMethodSignatureKey sig = new OwnerMethodSignatureKey(ownerInternalName, name, paramCount);
			return allExternalRefs.contains(sig) && !nonInlineableExternalRefs.contains(sig);
		}

		boolean hasExternalReferenceThatIsNotInlineableConstant(@Nonnull String ownerInternalName,
		                                                        @Nonnull String name,
		                                                        int paramCount) {
			OwnerMethodSignatureKey sig = new OwnerMethodSignatureKey(ownerInternalName, name, paramCount);
			if (nonInlineableExternalRefs.contains(sig))
				return true;
			if (!allExternalRefs.contains(sig))
				return false;
			ConstantReturnSummary summary = uniqueByOwnerNameArity.get(sig);
			return summary == null || summary.overridable();
		}
	}
}
