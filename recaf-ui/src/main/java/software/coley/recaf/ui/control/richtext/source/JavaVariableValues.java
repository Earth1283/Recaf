package software.coley.recaf.ui.control.richtext.source;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.services.source.ResolverAdapter;
import software.coley.recaf.ui.control.richtext.VariableValueHover;
import software.coley.sourcesolver.model.AssignmentExpressionModel;
import software.coley.sourcesolver.model.BinaryExpressionModel;
import software.coley.sourcesolver.model.BlockStatementModel;
import software.coley.sourcesolver.model.ClassModel;
import software.coley.sourcesolver.model.CompilationUnitModel;
import software.coley.sourcesolver.model.ExpressionStatementModel;
import software.coley.sourcesolver.model.LiteralExpressionModel;
import software.coley.sourcesolver.model.MemberSelectExpressionModel;
import software.coley.sourcesolver.model.MethodBodyModel;
import software.coley.sourcesolver.model.Model;
import software.coley.sourcesolver.model.NameExpressionModel;
import software.coley.sourcesolver.model.ParenthesizedExpressionModel;
import software.coley.sourcesolver.model.TypeModel;
import software.coley.sourcesolver.model.UnaryExpressionModel;
import software.coley.sourcesolver.model.VariableModel;
import software.coley.sourcesolver.resolve.result.FieldResolution;
import software.coley.sourcesolver.util.Range;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Infers simple values from declarations and straight-line assignments in the current source. */
final class JavaVariableValues {
	private static final int MAX_EXPRESSION_DEPTH = 12;
	private static final Object NULL_LITERAL = new Object();

	private JavaVariableValues() {}

	@Nullable
	static VariableValueHover.Preview resolve(@Nonnull CompilationUnitModel unit, @Nullable ResolverAdapter resolver, int offset) {
		Model selected = unit.getDeepestNonErroneousChildAtPosition(offset);
		if (selected instanceof NameExpressionModel name && !contains(name.getRange(), offset))
			return null;
		if (selected instanceof VariableModel declaration &&
				!contains(JavaVariableUsages.declarationNameRange(unit.getInputSource(), declaration), offset))
			return null;
		VariableModel variable = JavaVariableUsages.resolveAt(unit, offset);
		Map<VariableModel, List<NameExpressionModel>> references = new IdentityHashMap<>();
		Object value;
		if (variable != null) {
			value = evaluateLocal(unit, variable, offset, MAX_EXPRESSION_DEPTH, references);
		} else {
			variable = findField(unit, resolver, selected, offset);
			if (variable == null)
				return null;
			value = evaluate(unit, variable.getValue(), MAX_EXPRESSION_DEPTH, references);
			value = convertToDeclaredType(unit, variable, value);
			if (selected != variable && !isConstantField(unit, variable, value))
				return null;
		}
		if (value == null)
			return null;
		String type = variable.getType() == null || variable.getType().getRange().isUnknown()
				? null : variable.getType().getSource(unit);
		return new VariableValueHover.Preview(variable.getName(), type, format(value));
	}

	private static boolean isConstantField(CompilationUnitModel unit, VariableModel field, @Nullable Object value) {
		TypeModel type = field.getType();
		if (value == null || value == NULL_LITERAL || type == null || !field.getModifiers().getModifiers().contains("final"))
			return false;
		return type.getKind() == TypeModel.Kind.PRIMITIVE || "String".equals(type.getSource(unit))
				|| "java.lang.String".equals(type.getSource(unit));
	}

	@Nullable
	private static VariableModel findField(CompilationUnitModel unit, @Nullable ResolverAdapter resolver,
	                                       Model selected, int offset) {
		if (selected instanceof VariableModel field && field.getParent() instanceof ClassModel
				&& contains(JavaVariableUsages.declarationNameRange(unit.getInputSource(), field), offset))
			return field;
		String name;
		if (selected instanceof MemberSelectExpressionModel member) {
			name = member.getName();
			if (offset < member.getRange().end() - name.length() || offset >= member.getRange().end())
				return null;
		} else if (selected instanceof NameExpressionModel reference) {
			name = reference.getName();
		} else {
			return null;
		}
		if (resolver == null || !(resolver.resolveAt(offset, selected) instanceof FieldResolution resolution))
			return null;
		for (ClassModel type : unit.getRecursiveChildrenOfType(ClassModel.class)) {
			for (VariableModel field : type.getFields()) {
				if (field.getName().equals(name) && field.resolve(resolver).matches(resolution))
					return field;
			}
		}
		return null;
	}

	@Nullable
	private static Object evaluateLocal(CompilationUnitModel unit, VariableModel variable, int offset, int depth,
	                                    Map<VariableModel, List<NameExpressionModel>> references) {
		Model expression = variable.getValue();
		if (!contains(JavaVariableUsages.declarationNameRange(unit.getInputSource(), variable), offset)) {
			Model scope = variable.getParent();
			for (NameExpressionModel reference : references.computeIfAbsent(variable, JavaVariableUsages::findReferences)) {
				Model target = reference;
				while (target.getParent() instanceof ParenthesizedExpressionModel parent)
					target = parent;
				Model operation = target.getParent();
				boolean assignment = operation instanceof AssignmentExpressionModel write && write.getVariable() == target;
				if (!assignment && !isIncrement(operation, target))
					continue;
				// A conditional, repeated, or embedded write can reach uses out of source order.
				if (!(scope instanceof BlockStatementModel || scope instanceof MethodBodyModel)
						|| !(operation.getParent() instanceof ExpressionStatementModel statement) || statement.getParent() != scope)
					return null;
				if (operation.getRange().end() <= offset || contains(reference.getRange(), offset))
					expression = operation instanceof AssignmentExpressionModel write && write.getOperator() == AssignmentExpressionModel.Operator.SET
							? write.getExpression() : null;
			}
		}
		return convertToDeclaredType(unit, variable, evaluate(unit, expression, depth, references));
	}

	private static boolean isIncrement(@Nullable Model operation, Model target) {
		return operation instanceof UnaryExpressionModel unary && unary.getExpression() == target && switch (unary.getOperator()) {
			case PRE_INCREMENT, PRE_DECREMENT, POST_INCREMENT, POST_DECREMENT -> true;
			default -> false;
		};
	}

	@Nullable
	private static Object evaluate(CompilationUnitModel unit, @Nullable Model expression, int depth,
	                               Map<VariableModel, List<NameExpressionModel>> references) {
		if (depth == 0)
			return null;
		return switch (expression) {
			case LiteralExpressionModel literal -> switch (literal.getKind()) {
				case NULL -> NULL_LITERAL;
				case ERROR, VOID -> null;
				default -> literal.getContent();
			};
			case ParenthesizedExpressionModel group -> evaluate(unit, group.getExpression(), depth - 1, references);
			case NameExpressionModel name -> {
				int position = name.getRange().begin();
				VariableModel variable = JavaVariableUsages.resolveAt(unit, position);
				yield variable == null ? null : evaluateLocal(unit, variable, position, depth - 1, references);
			}
			case UnaryExpressionModel unary -> {
				Object operand = evaluate(unit, unary.getExpression(), depth - 1, references);
				yield switch (unary.getOperator()) {
					case POSITIVE -> asNumber(operand);
					case NEGATIVE -> switch (asNumber(operand)) {
						case Long number -> -number;
						case Float number -> -number;
						case Double number -> -number;
						case Number number -> -number.intValue();
						case null -> null;
					};
					case NOT -> operand instanceof Boolean bool ? !bool : null;
					default -> null;
				};
			}
			case BinaryExpressionModel binary -> evaluateBinary(binary.getOperator(),
					evaluate(unit, binary.getLeft(), depth - 1, references), evaluate(unit, binary.getRight(), depth - 1, references));
			case null, default -> null;
		};
	}

	@Nullable
	private static Object evaluateBinary(BinaryExpressionModel.Operator operator, @Nullable Object left, @Nullable Object right) {
		if (left == null || right == null)
			return null;
		if (operator == BinaryExpressionModel.Operator.PLUS && (left instanceof String || right instanceof String)) {
			String first = left == NULL_LITERAL ? "null" : left.toString();
			String second = right == NULL_LITERAL ? "null" : right.toString();
			// Keep source analysis bounded even for long chains of concatenations.
			return (long) first.length() + second.length() <= 65_536 ? first + second : null;
		}
		Number a = asNumber(left);
		Number b = asNumber(right);
		if (a == null || b == null || a instanceof Float || a instanceof Double || b instanceof Float || b instanceof Double)
			return null;
		long x = a.longValue();
		long y = b.longValue();
		if (y == 0 && (operator == BinaryExpressionModel.Operator.DIVIDE || operator == BinaryExpressionModel.Operator.REMAINDER))
			return null;
		Long result = switch (operator) {
			case PLUS -> x + y;
			case MINUS -> x - y;
			case MULTIPLY -> x * y;
			case DIVIDE -> x / y;
			case REMAINDER -> x % y;
			default -> null;
		};
		if (result == null || a instanceof Long || b instanceof Long)
			return result;
		return result.intValue();
	}

	@Nullable
	private static Number asNumber(@Nullable Object value) {
		return switch (value) {
			case Character character -> (int) character;
			case Number number -> number;
			case null, default -> null;
		};
	}

	@Nullable
	private static Object convertToDeclaredType(CompilationUnitModel unit, VariableModel variable, @Nullable Object value) {
		TypeModel type = variable.getType();
		if (type == null || type.getKind() != TypeModel.Kind.PRIMITIVE)
			return value;
		if (value instanceof Boolean)
			return "boolean".equals(type.getSource(unit)) ? value : null;
		Number number = asNumber(value);
		if (number == null)
			return null;
		return switch (type.getSource(unit)) {
			case "byte" -> number.byteValue();
			case "short" -> number.shortValue();
			case "char" -> (char) number.intValue();
			case "int" -> number.intValue();
			case "long" -> number.longValue();
			case "float" -> number.floatValue();
			case "double" -> number.doubleValue();
			default -> null;
		};
	}

	private static boolean contains(Range range, int offset) {
		return !range.isUnknown() && offset >= range.begin() && offset < range.end();
	}

	@Nonnull
	private static String format(Object value) {
		return switch (value) {
			case String string -> VariableValueHover.quote(string, '"');
			case Character character -> VariableValueHover.quote(character.toString(), '\'');
			default -> value == NULL_LITERAL ? "null" : value.toString();
		};
	}
}
