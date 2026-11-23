package software.coley.recaf.services.decompile.filter;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.services.decompile.DecompilerManagerConfig;
import software.coley.recaf.util.ReflectUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.sourcesolver.Parser;
import software.coley.sourcesolver.model.AnnotationExpressionModel;
import software.coley.sourcesolver.model.AssignmentExpressionModel;
import software.coley.sourcesolver.model.BinaryExpressionModel;
import software.coley.sourcesolver.model.BlockStatementModel;
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
import software.coley.sourcesolver.model.NamedModel;
import software.coley.sourcesolver.model.PackageModel;
import software.coley.sourcesolver.model.ParenthesizedExpressionModel;
import software.coley.sourcesolver.model.TypeModel;
import software.coley.sourcesolver.model.TypeParameterModel;
import software.coley.sourcesolver.model.UnaryExpressionModel;
import software.coley.sourcesolver.model.VariableModel;
import software.coley.sourcesolver.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Output text filter that performs source-level constant propagation/inlining and removes dead constant definitions
 * from decompiled Java code without modifying any workspace bytecode.
 */
public class ConstantDefinitionRemovingOutputFilter implements OutputTextFilter {
	private static final Logger logger = Logging.get(ConstantDefinitionRemovingOutputFilter.class);
	private static final int MAX_PASSES = 4;
	private static final Set<String> INLINEABLE_PRIMITIVES = Set.of("boolean", "int", "long", "float", "double", "char");
	private static final Set<String> ALL_PRIMITIVES = Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char");
	private static final Set<String> FIELD_REFLECTION_METHODS = Set.of("getDeclaredField", "getDeclaredFields", "getField", "getFields");
	private static final ThreadLocal<Parser> PARSER = ThreadLocal.withInitial(Parser::new);

	static {
		ReflectUtil.patch();
	}

	private final Supplier<DecompilerManagerConfig> configSupplier;

	/**
	 * @param config
	 * 		Decompiler manager config controlling the option.
	 */
	public ConstantDefinitionRemovingOutputFilter(@Nonnull DecompilerManagerConfig config) {
		this(() -> config);
	}

	/**
	 * @param configSupplier
	 * 		Supplier providing the active decompiler manager config.
	 */
	public ConstantDefinitionRemovingOutputFilter(@Nonnull Supplier<DecompilerManagerConfig> configSupplier) {
		this.configSupplier = configSupplier;
	}

	@Nonnull
	@Override
	public String filter(@Nonnull Workspace workspace, @Nonnull ClassInfo classInfo, @Nonnull String code) {
		if (code.isEmpty() || !configSupplier.get().getFilterRemoveConstantDefinitions().getValue())
			return code;

		String current = code;
		try {
			Parser parser = PARSER.get();
			for (int pass = 0; pass < MAX_PASSES; pass++) {
				CompilationUnitModel unit = parser.parse(current);
				List<TextEdit> edits = computeEdits(unit, current);
				if (edits.isEmpty())
					break;
				String next = applyEdits(current, edits);
				if (next.equals(current))
					break;
				current = next;
			}
			return current;
		} catch (Throwable t) {
			logger.debug("Skipping constant-definition output filter for '{}': {}", classInfo.getName(), t.getMessage());
			return code;
		}
	}

	@Nonnull
	private static List<TextEdit> computeEdits(@Nonnull CompilationUnitModel unit, @Nonnull String source) {
		List<TextEdit> inlineEdits = new ArrayList<>();
		List<TextEdit> removeEdits = new ArrayList<>();
		for (ClassModel declaredClass : unit.getDeclaredClasses())
			processClass(declaredClass, source, inlineEdits, removeEdits);
		// Apply propagation replacements first so subsequent re-parse sees all reads gone before pruning definitions.
		return !inlineEdits.isEmpty() ? inlineEdits : removeEdits;
	}

	private static void processClass(@Nonnull ClassModel classModel,
	                                 @Nonnull String source,
	                                 @Nonnull List<TextEdit> inlineEdits,
	                                 @Nonnull List<TextEdit> removeEdits) {
		processFieldsInClass(classModel, source, inlineEdits, removeEdits);
		for (MethodModel method : classModel.getMethods())
			processMethodLocals(classModel.getName(), method, source, inlineEdits, removeEdits);
		for (ClassModel innerClass : classModel.getInnerClasses())
			processClass(innerClass, source, inlineEdits, removeEdits);
	}

	private static void processFieldsInClass(@Nonnull ClassModel classModel,
	                                         @Nonnull String source,
	                                         @Nonnull List<TextEdit> inlineEdits,
	                                         @Nonnull List<TextEdit> removeEdits) {
		List<VariableModel> fields = classModel.getFields();
		if (fields.isEmpty() || usesFieldReflection(classModel))
			return;

		Set<VariableModel> multiDeclarators = findMultiDeclarators(fields, source);
		Map<String, VariableModel> candidateFields = new HashMap<>();
		for (VariableModel field : fields) {
			if (multiDeclarators.contains(field) || field.getRange().isUnknown())
				continue;
			if (!field.getAnnotations().isEmpty() || "serialVersionUID".equals(field.getName()))
				continue;
			var modifiers = field.getModifiers().getModifiers();
			if (!modifiers.contains("private") || !modifiers.contains("static"))
				continue;
			if (primitiveOrStringKind(field.getType()) == null)
				continue;
			candidateFields.put(field.getName(), field);
		}
		if (candidateFields.isEmpty())
			return;

		Map<String, FieldUsage> usages = collectFieldUsages(classModel, candidateFields.keySet());
		Set<ExpressionStatementModel> scheduledStaticStmtDeletes = Collections.newSetFromMap(new IdentityHashMap<>());

		for (VariableModel field : candidateFields.values()) {
			FieldUsage usage = usages.get(field.getName());
			if (usage == null || usage.hasAmbiguousReference())
				continue;

			String inlineableType = inlineableTypeName(field.getType());
			// Step 1: Propagate single-value constant fields when reads remain and no shadowed/ambiguous refs exist.
			if (inlineableType != null && !usage.reads().isEmpty() && !usage.hasCompoundOrMutation() && !usage.hasShadowedRead()) {
				String constantText = resolveSingleConstantFieldText(field, inlineableType, usage, source);
				if (constantText != null) {
					String replacement = needsParensForReplacement(constantText) ? "(" + constantText + ")" : constantText;
					for (Model read : usage.reads()) {
						Range r = read.getRange();
						if (!r.isUnknown())
							inlineEdits.add(new TextEdit(r.begin(), r.end(), replacement));
					}
					continue;
				}
			}

			// Step 2: Remove dead private static constant fields and pure static-initializer constant writes.
			if (usage.reads().isEmpty() && !usage.hasCompoundOrMutation()
					&& (field.getValue() == null || isPureConstantExpression(field.getValue(), classModel.getName(), false))
					&& allWritesArePureStaticInitStatements(usage.writes(), classModel.getName())) {
				TextEdit declDelete = createStatementDeleteEdit(source, field.getRange());
				if (declDelete != null)
					removeEdits.add(declDelete);
				for (WriteSite write : usage.writes()) {
					if (write.enclosingStatement() != null)
						scheduledStaticStmtDeletes.add(write.enclosingStatement());
				}
			}
		}

		// Delete dead static-initializer statements, or collapse an entirely dead static { ... } block cleanly.
		for (MethodModel method : classModel.getMethods()) {
			if (!method.isStaticInitializer() || method.getMethodBody() == null)
				continue;
			var stmts = method.getMethodBody().getStatements();
			if (stmts.isEmpty())
				continue;
			int matchedCount = 0;
			for (var stmt : stmts) {
				if (stmt instanceof ExpressionStatementModel esm && scheduledStaticStmtDeletes.contains(esm))
					matchedCount++;
			}
			if (matchedCount == 0)
				continue;
			if (matchedCount == stmts.size() && !method.getRange().isUnknown()) {
				TextEdit blockDelete = createLineDeleteEdit(source, method.getRange().begin(), method.getRange().end());
				if (blockDelete != null) {
					removeEdits.add(blockDelete);
					continue;
				}
			}
			for (var stmt : stmts) {
				if (stmt instanceof ExpressionStatementModel esm && scheduledStaticStmtDeletes.contains(esm)) {
					TextEdit stmtDelete = createStatementDeleteEdit(source, esm.getRange());
					if (stmtDelete != null)
						removeEdits.add(stmtDelete);
				}
			}
		}
	}

	private static void processMethodLocals(@Nonnull String ownerClassName,
	                                        @Nonnull MethodModel method,
	                                        @Nonnull String source,
	                                        @Nonnull List<TextEdit> inlineEdits,
	                                        @Nonnull List<TextEdit> removeEdits) {
		MethodBodyModel body = method.getMethodBody();
		if (body == null)
			return;

		// Count every VariableModel name in this method subtree so shadowed names are skipped conservatively.
		Map<String, Integer> allDeclaredNameCounts = new HashMap<>();
		for (VariableModel param : method.getParameters())
			allDeclaredNameCounts.merge(param.getName(), 1, Integer::sum);
		body.visit(model -> {
			if (model instanceof VariableModel vm)
				allDeclaredNameCounts.merge(vm.getName(), 1, Integer::sum);
			return true;
		});

		// Only target standalone variable declaration statements directly inside method/block statements.
		List<VariableModel> standaloneLocals = new ArrayList<>();
		Set<VariableModel> multiDeclarators = Collections.newSetFromMap(new IdentityHashMap<>());
		collectStandaloneBlockLocals(body, source, standaloneLocals, multiDeclarators);
		if (standaloneLocals.isEmpty())
			return;

		Map<String, VariableModel> candidates = new HashMap<>();
		for (VariableModel local : standaloneLocals) {
			if (multiDeclarators.contains(local) || local.getRange().isUnknown())
				continue;
			if (!local.getAnnotations().isEmpty())
				continue;
			if (allDeclaredNameCounts.getOrDefault(local.getName(), 0) != 1)
				continue;
			if (primitiveOrStringKind(local.getType()) == null)
				continue;
			candidates.put(local.getName(), local);
		}
		if (candidates.isEmpty())
			return;

		Map<String, LocalUsage> usages = collectLocalUsages(body, candidates.keySet());
		for (VariableModel local : candidates.values()) {
			LocalUsage usage = usages.get(local.getName());
			if (usage == null || usage.hasCompoundOrMutation())
				continue;

			String inlineableType = inlineableTypeName(local.getType());
			// Step 1: Propagate never-reassigned single-constant locals.
			if (inlineableType != null && !usage.reads().isEmpty() && usage.writes().isEmpty()) {
				String constantText = extractLiteralText(local.getValue(), inlineableType, source);
				if (constantText != null) {
					String replacement = needsParensForReplacement(constantText) ? "(" + constantText + ")" : constantText;
					for (NameExpressionModel read : usage.reads()) {
						Range r = read.getRange();
						if (!r.isUnknown())
							inlineEdits.add(new TextEdit(r.begin(), r.end(), replacement));
					}
					continue;
				}
			}

			// Step 2: Remove dead constant local definitions and dead pure constant reassignments.
			if (usage.reads().isEmpty()
					&& (local.getValue() == null || isPureConstantExpression(local.getValue(), ownerClassName, true))
					&& allLocalWritesArePureStatements(usage.writes(), ownerClassName)) {
				TextEdit declDelete = createStatementDeleteEdit(source, local.getRange());
				if (declDelete != null)
					removeEdits.add(declDelete);
				for (WriteSite write : usage.writes()) {
					if (write.enclosingStatement() != null) {
						TextEdit writeDelete = createStatementDeleteEdit(source, write.enclosingStatement().getRange());
						if (writeDelete != null)
							removeEdits.add(writeDelete);
					}
				}
			}
		}
	}

	private static void collectStandaloneBlockLocals(@Nonnull Model root,
	                                                 @Nonnull String source,
	                                                 @Nonnull List<VariableModel> outLocals,
	                                                 @Nonnull Set<VariableModel> outMultiDeclarators) {
		root.visit(model -> {
			if (model instanceof ClassModel)
				return false; // Nested/anonymous class methods are handled in their own processClass invocation.
			List<?> stmts = null;
			if (model instanceof MethodBodyModel body)
				stmts = body.getStatements();
			else if (model instanceof BlockStatementModel block)
				stmts = block.getStatements();
			if (stmts != null) {
				List<VariableModel> blockVars = new ArrayList<>();
				for (Object stmt : stmts) {
					if (stmt instanceof VariableModel vm) {
						blockVars.add(vm);
						outLocals.add(vm);
					}
				}
				if (blockVars.size() > 1)
					outMultiDeclarators.addAll(findMultiDeclarators(blockVars, source));
			}
			return true;
		});
	}

	@Nonnull
	private static Set<VariableModel> findMultiDeclarators(@Nonnull List<VariableModel> vars, @Nonnull String source) {
		Set<VariableModel> multi = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = 1; i < vars.size(); i++) {
			VariableModel prev = vars.get(i - 1);
			VariableModel curr = vars.get(i);
			Range pr = prev.getRange();
			Range cr = curr.getRange();
			if (pr.isUnknown() || cr.isUnknown())
				continue;
			// VariableModel.getRange() may include its trailing ';', so start scanning at pr.end() - 1.
			int from = Math.max(pr.begin(), Math.min(pr.end() - 1, source.length()));
			int to = Math.max(from, Math.min(cr.begin(), source.length()));
			int semi = source.indexOf(';', from);
			if (semi == -1 || semi >= to) {
				// No semicolon separating consecutive variable models -> e.g. int a = 1, b = 2;
				multi.add(prev);
				multi.add(curr);
			}
		}
		return multi;
	}

	private static boolean usesFieldReflection(@Nonnull ClassModel classModel) {
		boolean[] found = {false};
		classModel.visit(model -> {
			if (found[0])
				return false;
			if (model instanceof MethodInvocationExpressionModel call) {
				String name = call.getMethodName();
				if (FIELD_REFLECTION_METHODS.contains(name)) {
					found[0] = true;
					return false;
				}
			}
			return true;
		});
		return found[0];
	}

	@Nonnull
	private static Map<String, FieldUsage> collectFieldUsages(@Nonnull ClassModel classModel, @Nonnull Set<String> fieldNames) {
		Map<String, FieldUsage> map = new HashMap<>();
		for (String name : fieldNames)
			map.put(name, new FieldUsage());
		collectFieldUsagesRecursive(classModel, classModel.getName(), fieldNames, Set.of(), map);
		return map;
	}

	private static void collectFieldUsagesRecursive(@Nonnull ClassModel currentClass,
	                                                @Nonnull String targetOwnerName,
	                                                @Nonnull Set<String> fieldNames,
	                                                @Nonnull Set<String> inheritedShadowedNames,
	                                                @Nonnull Map<String, FieldUsage> out) {
		Set<String> classShadows = new HashSet<>(inheritedShadowedNames);
		if (currentClass != null && !targetOwnerName.equals(currentClass.getName())) {
			for (VariableModel f : currentClass.getFields())
				classShadows.add(f.getName());
		}

		for (VariableModel field : currentClass.getFields()) {
			if (field.getValue() != null)
				scanFieldExpression(field.getValue(), targetOwnerName, fieldNames, classShadows, null, out);
		}
		for (MethodModel method : currentClass.getMethods()) {
			if (method.getMethodBody() == null)
				continue;
			Set<String> methodShadows = new HashSet<>(classShadows);
			for (VariableModel param : method.getParameters())
				methodShadows.add(param.getName());
			method.getMethodBody().visit(m -> {
				if (m instanceof ClassModel)
					return false;
				if (m instanceof VariableModel vm)
					methodShadows.add(vm.getName());
				return true;
			});
			scanFieldExpression(method.getMethodBody(), targetOwnerName, fieldNames, methodShadows, method, out);
		}
		for (ClassModel inner : currentClass.getInnerClasses())
			collectFieldUsagesRecursive(inner, targetOwnerName, fieldNames, classShadows, out);
	}

	private static void scanFieldExpression(@Nonnull Model root,
	                                        @Nonnull String targetOwnerName,
	                                        @Nonnull Set<String> fieldNames,
	                                        @Nonnull Set<String> shadowedNames,
	                                        @Nullable MethodModel enclosingMethod,
	                                        @Nonnull Map<String, FieldUsage> out) {
		root.visit(model -> {
			if (model instanceof ClassModel)
				return false; // Inner class scanned separately with its own shadows
			if (isNonVariableReferenceContext(model))
				return false;
			if (model instanceof MemberSelectExpressionModel ms && fieldNames.contains(ms.getName())) {
				FieldUsage usage = out.get(ms.getName());
				if (isSameOwnerQualifier(ms.getContext(), targetOwnerName)) {
					classifyFieldRef(ms, enclosingMethod, usage);
				} else {
					usage.markAmbiguous();
				}
				return true;
			}
			if (model instanceof NameExpressionModel ne && fieldNames.contains(ne.getName()) && !isMethodSelect(ne) && !isMethodRefName(ne)) {
				FieldUsage usage = out.get(ne.getName());
				if (shadowedNames.contains(ne.getName())) {
					// Shadowed locally: might still mean this method had its own variable of same name; mark shadowed for inlining safety
					usage.markShadowedRead();
				} else {
					classifyFieldRef(ne, enclosingMethod, usage);
				}
			}
			return true;
		});
	}

	private static void classifyFieldRef(@Nonnull Model ref, @Nullable MethodModel enclosingMethod, @Nonnull FieldUsage usage) {
		Model parent = ref.getParent();
		if (parent instanceof AssignmentExpressionModel aem && aem.getVariable() == ref) {
			if (aem.getOperator() == AssignmentExpressionModel.Operator.SET) {
				ExpressionStatementModel stmt = aem.getParent() instanceof ExpressionStatementModel esm ? esm : null;
				usage.writes().add(new WriteSite(enclosingMethod, aem, stmt));
			} else {
				usage.markCompoundOrMutation();
			}
		} else if (parent instanceof UnaryExpressionModel uem && isMutatingUnary(uem.getOperator())) {
			usage.markCompoundOrMutation();
		} else {
			usage.reads().add(ref);
		}
	}

	@Nonnull
	private static Map<String, LocalUsage> collectLocalUsages(@Nonnull MethodBodyModel body, @Nonnull Set<String> localNames) {
		Map<String, LocalUsage> map = new HashMap<>();
		for (String name : localNames)
			map.put(name, new LocalUsage());
		body.visit(model -> {
			if (isNonVariableReferenceContext(model))
				return false;
			if (model instanceof NameExpressionModel ne && localNames.contains(ne.getName())
					&& !isMethodSelect(ne) && !isMethodRefName(ne) && !isDeclaredVariableNameNode(ne)) {
				LocalUsage usage = map.get(ne.getName());
				Model parent = ne.getParent();
				if (parent instanceof AssignmentExpressionModel aem && aem.getVariable() == ne) {
					if (aem.getOperator() == AssignmentExpressionModel.Operator.SET) {
						ExpressionStatementModel stmt = aem.getParent() instanceof ExpressionStatementModel esm ? esm : null;
						usage.writes().add(new WriteSite(null, aem, stmt));
					} else {
						usage.markCompoundOrMutation();
					}
				} else if (parent instanceof UnaryExpressionModel uem && isMutatingUnary(uem.getOperator())) {
					usage.markCompoundOrMutation();
				} else {
					usage.reads().add(ne);
				}
			}
			return true;
		});
		return map;
	}

	private static boolean isNonVariableReferenceContext(@Nonnull Model model) {
		return model instanceof ImportModel
				|| model instanceof PackageModel
				|| model instanceof AnnotationExpressionModel
				|| model instanceof TypeModel
				|| model instanceof TypeParameterModel;
	}

	private static boolean isMethodSelect(@Nonnull NameExpressionModel ne) {
		return ne.getParent() instanceof MethodInvocationExpressionModel mi && mi.getMethodSelect() == ne;
	}

	private static boolean isMethodRefName(@Nonnull NameExpressionModel ne) {
		return ne.getParent() instanceof MethodReferenceExpressionModel mr && mr.getNameModel() == ne;
	}

	private static boolean isDeclaredVariableNameNode(@Nonnull NameExpressionModel ne) {
		Model p = ne.getParent();
		return (p instanceof VariableModel vm && vm.getNameModel() == ne)
				|| (p instanceof MethodModel mm && mm.getNameModel() == ne)
				|| (p instanceof ClassModel cm && cm.getNameModel() == ne);
	}

	private static boolean isSameOwnerQualifier(@Nullable Model context, @Nonnull String ownerClassName) {
		if (context instanceof NameExpressionModel ne)
			return "this".equals(ne.getName()) || ownerClassName.equals(ne.getName());
		return false;
	}

	private static boolean isMutatingUnary(@Nonnull UnaryExpressionModel.Operator op) {
		return op == UnaryExpressionModel.Operator.PRE_INCREMENT
				|| op == UnaryExpressionModel.Operator.POST_INCREMENT
				|| op == UnaryExpressionModel.Operator.PRE_DECREMENT
				|| op == UnaryExpressionModel.Operator.POST_DECREMENT;
	}

	@Nullable
	private static String resolveSingleConstantFieldText(@Nonnull VariableModel field,
	                                                     @Nonnull String expectedType,
	                                                     @Nonnull FieldUsage usage,
	                                                     @Nonnull String source) {
		if (field.getValue() != null) {
			if (!usage.writes().isEmpty())
				return null;
			return extractLiteralText(field.getValue(), expectedType, source);
		}
		if (usage.writes().size() == 1) {
			WriteSite write = usage.writes().getFirst();
			if (write.enclosingMethod() != null && write.enclosingMethod().isStaticInitializer() && write.enclosingStatement() != null)
				return extractLiteralText(write.assignment().getExpression(), expectedType, source);
		}
		return null;
	}

	private static boolean allWritesArePureStaticInitStatements(@Nonnull List<WriteSite> writes, @Nonnull String ownerClassName) {
		for (WriteSite write : writes) {
			if (write.enclosingMethod() == null || !write.enclosingMethod().isStaticInitializer() || write.enclosingStatement() == null)
				return false;
			if (!isPureConstantExpression(write.assignment().getExpression(), ownerClassName, false))
				return false;
		}
		return true;
	}

	private static boolean allLocalWritesArePureStatements(@Nonnull List<WriteSite> writes, @Nonnull String ownerClassName) {
		for (WriteSite write : writes) {
			if (write.enclosingStatement() == null)
				return false;
			if (!isPureConstantExpression(write.assignment().getExpression(), ownerClassName, true))
				return false;
		}
		return true;
	}

	@Nullable
	private static String inlineableTypeName(@Nullable TypeModel type) {
		if (type instanceof TypeModel.Primitive prim) {
			String name = prim.getName();
			return INLINEABLE_PRIMITIVES.contains(name) ? name : null;
		}
		if (type instanceof TypeModel.NamedObject named) {
			String name = named.getName();
			return ("String".equals(name) || "java.lang.String".equals(name)) ? "String" : null;
		}
		return null;
	}

	@Nullable
	private static String primitiveOrStringKind(@Nullable TypeModel type) {
		if (type instanceof TypeModel.Primitive prim) {
			String name = prim.getName();
			return ALL_PRIMITIVES.contains(name) ? name : null;
		}
		if (type instanceof TypeModel.NamedObject named) {
			String name = named.getName();
			return ("String".equals(name) || "java.lang.String".equals(name)) ? "String" : null;
		}
		return null;
	}

	@Nullable
	private static String extractLiteralText(@Nullable Model valueModel, @Nonnull String expectedType, @Nonnull String source) {
		Model unwrapped = unwrapParens(valueModel);
		boolean negative = false;
		boolean positive = false;
		if (unwrapped instanceof UnaryExpressionModel unary) {
			if (unary.getOperator() == UnaryExpressionModel.Operator.NEGATIVE) {
				negative = true;
				unwrapped = unwrapParens(unary.getExpression());
			} else if (unary.getOperator() == UnaryExpressionModel.Operator.POSITIVE) {
				positive = true;
				unwrapped = unwrapParens(unary.getExpression());
			} else {
				return null;
			}
		}
		if (!(unwrapped instanceof LiteralExpressionModel lit) || lit.getRange().isUnknown())
			return null;

		LiteralExpressionModel.Kind kind = lit.getKind();
		String raw = source.substring(lit.getRange().begin(), lit.getRange().end());
		String prefix = negative ? "-" : (positive ? "+" : "");

		return switch (expectedType) {
			case "boolean" -> (!negative && !positive && kind == LiteralExpressionModel.Kind.BOOLEAN) ? raw : null;
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
	private static Model unwrapParens(@Nullable Model model) {
		while (model instanceof ParenthesizedExpressionModel p)
			model = p.getExpression();
		return model;
	}

	private static boolean needsParensForReplacement(@Nonnull String replacement) {
		return replacement.startsWith("-") || replacement.startsWith("+");
	}

	private static boolean isPureConstantExpression(@Nullable Model expr,
	                                                @Nonnull String ownerClassName,
	                                                boolean allowLocalNameReads) {
		if (expr == null)
			return false;
		if (expr instanceof LiteralExpressionModel)
			return true;
		if (expr instanceof ParenthesizedExpressionModel p)
			return isPureConstantExpression(p.getExpression(), ownerClassName, allowLocalNameReads);
		if (expr instanceof UnaryExpressionModel u) {
			return switch (u.getOperator()) {
				case POSITIVE, NEGATIVE, NOT, BIT_NOT -> isPureConstantExpression(u.getExpression(), ownerClassName, allowLocalNameReads);
				default -> false;
			};
		}
		if (expr instanceof CastExpressionModel c) {
			if (!(c.getType() instanceof TypeModel type) || primitiveOrStringKind(type) == null)
				return false;
			return isPureConstantExpression(c.getExpression(), ownerClassName, allowLocalNameReads);
		}
		if (expr instanceof BinaryExpressionModel b) {
			var op = b.getOperator();
			if (op == BinaryExpressionModel.Operator.UNKNOWN || op == BinaryExpressionModel.Operator.RELATION_INSTANCEOF)
				return false;
			if (op == BinaryExpressionModel.Operator.DIVIDE || op == BinaryExpressionModel.Operator.REMAINDER) {
				if (!isProvablyNonZeroNumericLiteral(b.getRight()))
					return false;
			}
			return isPureConstantExpression(b.getLeft(), ownerClassName, allowLocalNameReads)
					&& isPureConstantExpression(b.getRight(), ownerClassName, allowLocalNameReads);
		}
		if (allowLocalNameReads && expr instanceof NameExpressionModel ne) {
			String n = ne.getName();
			return !"this".equals(n) && !"super".equals(n);
		}
		if (expr instanceof MemberSelectExpressionModel ms) {
			return isSameOwnerQualifier(ms.getContext(), ownerClassName);
		}
		return false;
	}

	private static boolean isProvablyNonZeroNumericLiteral(@Nullable Model model) {
		Model unwrapped = unwrapParens(model);
		if (unwrapped instanceof UnaryExpressionModel u &&
				(u.getOperator() == UnaryExpressionModel.Operator.POSITIVE || u.getOperator() == UnaryExpressionModel.Operator.NEGATIVE)) {
			unwrapped = unwrapParens(u.getExpression());
		}
		if (!(unwrapped instanceof LiteralExpressionModel lit))
			return false;
		Object content = lit.getContent();
		if (content instanceof Number n)
			return n.doubleValue() != 0.0d;
		return false;
	}

	@Nullable
	private static TextEdit createStatementDeleteEdit(@Nonnull String source, @Nonnull Range range) {
		if (range.isUnknown())
			return null;
		int begin = Math.max(0, range.begin());
		int end = Math.min(source.length(), range.end());
		if (begin >= end)
			return null;
		if (source.charAt(end - 1) != ';') {
			int scan = end;
			while (scan < source.length() && (source.charAt(scan) == ' ' || source.charAt(scan) == '\t'))
				scan++;
			if (scan < source.length() && source.charAt(scan) == ';')
				end = scan + 1;
		}
		return createLineDeleteEdit(source, begin, end);
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

	private record TextEdit(int begin, int end, @Nonnull String replacement) {}

	private record WriteSite(@Nullable MethodModel enclosingMethod,
	                         @Nonnull AssignmentExpressionModel assignment,
	                         @Nullable ExpressionStatementModel enclosingStatement) {}

	private static final class FieldUsage {
		private final List<Model> reads = new ArrayList<>();
		private final List<WriteSite> writes = new ArrayList<>();
		private boolean compoundOrMutation;
		private boolean shadowedRead;
		private boolean ambiguousReference;

		@Nonnull
		List<Model> reads() {
			return reads;
		}

		@Nonnull
		List<WriteSite> writes() {
			return writes;
		}

		void markCompoundOrMutation() {
			compoundOrMutation = true;
		}

		boolean hasCompoundOrMutation() {
			return compoundOrMutation;
		}

		void markShadowedRead() {
			shadowedRead = true;
		}

		boolean hasShadowedRead() {
			return shadowedRead;
		}

		void markAmbiguous() {
			ambiguousReference = true;
		}

		boolean hasAmbiguousReference() {
			return ambiguousReference;
		}
	}

	private static final class LocalUsage {
		private final List<NameExpressionModel> reads = new ArrayList<>();
		private final List<WriteSite> writes = new ArrayList<>();
		private boolean compoundOrMutation;

		@Nonnull
		List<NameExpressionModel> reads() {
			return reads;
		}

		@Nonnull
		List<WriteSite> writes() {
			return writes;
		}

		void markCompoundOrMutation() {
			compoundOrMutation = true;
		}

		boolean hasCompoundOrMutation() {
			return compoundOrMutation;
		}
	}
}
