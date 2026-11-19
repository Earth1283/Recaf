package software.coley.recaf.ui.control.richtext.source;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.sourcesolver.model.BlockStatementModel;
import software.coley.sourcesolver.model.CaseModel;
import software.coley.sourcesolver.model.CatchModel;
import software.coley.sourcesolver.model.ClassModel;
import software.coley.sourcesolver.model.CompilationUnitModel;
import software.coley.sourcesolver.model.EnhancedForLoopStatementModel;
import software.coley.sourcesolver.model.ForLoopStatementModel;
import software.coley.sourcesolver.model.LambdaExpressionModel;
import software.coley.sourcesolver.model.MethodBodyModel;
import software.coley.sourcesolver.model.MethodInvocationExpressionModel;
import software.coley.sourcesolver.model.MethodModel;
import software.coley.sourcesolver.model.Model;
import software.coley.sourcesolver.model.NameExpressionModel;
import software.coley.sourcesolver.model.NameHoldingModel;
import software.coley.sourcesolver.model.NewClassExpressionModel;
import software.coley.sourcesolver.model.TryStatementModel;
import software.coley.sourcesolver.model.TypeModel;
import software.coley.sourcesolver.model.VariableModel;
import software.coley.sourcesolver.util.Range;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Finds source references by local declaration identity. */
final class JavaVariableUsages {
	private static final Pattern DECLARATION_TOKEN = Pattern.compile(
			"(?s:/\\*.*?\\*/)|//[^\\r\\n]*|(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)");

	private JavaVariableUsages() {}

	/** @return Declaration of the selected local, or {@code null} if the selection is outside a local name. */
	@Nullable
	static VariableModel resolveAt(@Nonnull CompilationUnitModel unit, int position) {
		Model selected = unit.getDeepestNonErroneousChildAtPosition(position);
		if (selected instanceof NameExpressionModel name)
			return resolveLocal(name, new IdentityHashMap<>());
		if (selected instanceof VariableModel variable && isLocalScope(variable.getParent())
				&& declarationNameRange(unit.getInputSource(), variable).isWithin(position))
			return variable;
		return null;
	}

	/** @return References to the given local declaration, ordered by source position. */
	@Nonnull
	static List<Range> find(@Nonnull VariableModel declaration) {
		return findReferences(declaration).stream().map(Model::getRange).distinct().toList();
	}

	@Nonnull
	static List<NameExpressionModel> findReferences(@Nonnull VariableModel declaration) {
		Map<Model, List<VariableModel>> declarationsByScope = new IdentityHashMap<>();
		return declaration.getParent().getRecursiveChildrenOfType(NameExpressionModel.class).stream()
				.filter(name -> name.getName().equals(declaration.getName()) && resolveLocal(name, declarationsByScope) == declaration)
				.sorted(Comparator.comparing(Model::getRange))
				.toList();
	}

	@Nullable
	private static VariableModel resolveLocal(@Nonnull NameExpressionModel reference,
	                                        @Nonnull Map<Model, List<VariableModel>> declarationsByScope) {
		if (reference.getRange().isUnknown() || reference.getParentOfType(TypeModel.class) != null)
			return null;
		Model parent = reference.getParent();
		if ((parent instanceof NameHoldingModel named && named.getNameModel() == reference)
				|| (parent instanceof MethodInvocationExpressionModel invocation && invocation.getMethodSelect() == reference))
			return null;
		NewClassExpressionModel construction = reference.getParentOfType(NewClassExpressionModel.class);
		if (construction != null) {
			Model type = construction.getIdentifier();
			if (type == reference || (type != null && type.isParentOf(reference)))
				return null;
		}

		String name = reference.getName();
		Model child = reference;
		for (Model scope = parent; scope != null; child = scope, scope = scope.getParent()) {
			if (scope instanceof ClassModel type && type.getFields().stream().anyMatch(field -> field.getName().equals(name)))
				return null;
			if (!isLocalScope(scope) || !isInScope(scope, child))
				continue;
			// All references in a search have the same name. Scan each enclosing block's declarations once.
			List<VariableModel> candidates = declarationsByScope.computeIfAbsent(scope, key -> key.getChildren().stream()
					.filter(VariableModel.class::isInstance).map(VariableModel.class::cast)
					.filter(variable -> variable.getName().equals(name) && !variable.getRange().isUnknown()).toList());
			for (VariableModel variable : candidates) {
				if (variable != child && variable.getRange().end() <= reference.getRange().begin())
					return variable;
			}
		}
		return null;
	}

	private static boolean isLocalScope(@Nullable Model model) {
		return model instanceof MethodModel || model instanceof MethodBodyModel || model instanceof BlockStatementModel
				|| model instanceof ForLoopStatementModel || model instanceof EnhancedForLoopStatementModel
				|| model instanceof LambdaExpressionModel || model instanceof CatchModel
				|| model instanceof TryStatementModel || model instanceof CaseModel;
	}

	private static boolean isInScope(@Nonnull Model scope, @Nonnull Model child) {
		return switch (scope) {
			case EnhancedForLoopStatementModel loop -> child == loop.getStatement();
			case TryStatementModel statement -> child == statement.getBlock() || statement.getResources().contains(child);
			default -> true;
		};
	}

	@Nonnull
	static Range declarationNameRange(@Nonnull String source, @Nonnull VariableModel variable) {
		Range declaration = variable.getRange();
		if (declaration.isUnknown())
			return Range.UNKNOWN;
		TypeModel type = variable.getType();
		int start = type == null ? declaration.begin() : Math.max(declaration.begin(), type.getRange().end());
		Model initializer = variable.getValue();
		int end = initializer == null || initializer.getRange().isUnknown() ? declaration.end() : initializer.getRange().begin();
		if (start >= end || end > source.length())
			return Range.UNKNOWN;
		var matcher = DECLARATION_TOKEN.matcher(source).region(start, end);
		while (matcher.find()) {
			if (variable.getName().equals(matcher.group(1)))
				return new Range(matcher.start(), matcher.end());
		}
		return Range.UNKNOWN;
	}
}
