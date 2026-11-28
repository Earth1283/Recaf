package software.coley.recaf.util.analysis.symbolic;

import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Params;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import software.coley.recaf.util.analysis.symbolic.BitVectorExpression.Comparison;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** A lazily initialized solver session confined to one transformation worker and class. */
final class Z3BitVectorSolver implements AutoCloseable {
	private static final int CHECK_LIMIT = 24;
	private static volatile boolean unavailable;
	private final Map<Query, Boolean> results = new HashMap<>();
	private Context context;
	private Solver solver;
	private int remaining = CHECK_LIMIT;

	boolean proves(Comparison comparison, BitVectorExpression left, BitVectorExpression right, boolean expected) {
		if (unavailable || Thread.currentThread().isInterrupted()) return false;
		var query = new Query(comparison, left, right);
		Boolean cached = results.get(query);
		if (cached != null) return cached;
		if (remaining == 0) return false;
		remaining--;
		if (solver == null && !open()) return false;

		Map<BitVectorExpression, BitVecExpr> translated = new IdentityHashMap<>();
		BitVecExpr a = translate(left, translated);
		BitVecExpr b = translate(right, translated);
		BoolExpr condition = switch (comparison) {
			case EQ -> context.mkEq(a, b);
			case NE -> context.mkNot(context.mkEq(a, b));
			case LT -> context.mkBVSLT(a, b);
			case GE -> context.mkBVSGE(a, b);
			case GT -> context.mkBVSGT(a, b);
			case LE -> context.mkBVSLE(a, b);
		};
		try {
			solver.add(new BoolExpr[] { expected ? context.mkNot(condition) : condition });
			boolean proved = solver.check() == Status.UNSATISFIABLE;
			// Repeating an inconclusive query would consume the budget for other branches.
			results.put(query, proved);
			return proved;
		} finally {
			solver.reset();
		}
	}

	private boolean open() {
		try {
			context = new Context(Map.of("model", "false"));
			solver = context.mkSolver("QF_BV");
			Params limits = context.mkParams();
			limits.add("timeout", 50);
			limits.add("rlimit", 75_000);
			solver.setParameters(limits);
			return true;
		} catch (LinkageError error) {
			// On platforms without bundled natives, the Java rewrites can still run.
			unavailable = true;
			return false;
		}
	}

	private BitVecExpr translate(BitVectorExpression expression, Map<BitVectorExpression, BitVecExpr> translated) {
		BitVecExpr prior = translated.get(expression);
		if (prior != null) return prior;
		BitVecExpr left = expression.left() == null ? null : translate(expression.left(), translated);
		BitVecExpr right = expression.right() == null ? null : translate(expression.right(), translated);
		int bits = expression.bits();
		BitVecExpr result = switch (expression.op()) {
			case CONSTANT -> context.mkBV(expression.value(), bits);
			case LOCAL -> context.mkBVConst("local_" + bits + "_" + expression.value(), bits);
			case ADD -> context.mkBVAdd(left, right);
			case SUB -> context.mkBVSub(left, right);
			case MUL -> context.mkBVMul(left, right);
			case AND -> context.mkBVAND(left, right);
			case OR -> context.mkBVOR(left, right);
			case XOR -> context.mkBVXOR(left, right);
			case SHL -> context.mkBVSHL(left, shiftDistance(right, bits));
			case ASHR -> context.mkBVASHR(left, shiftDistance(right, bits));
			case LSHR -> context.mkBVLSHR(left, shiftDistance(right, bits));
			case NEG -> context.mkBVNeg(left);
			case I2L -> context.mkSignExt(32, left);
			case L2I -> context.mkExtract(31, 0, left);
			case I2B -> context.mkSignExt(24, context.mkExtract(7, 0, left));
			case I2C -> context.mkZeroExt(16, context.mkExtract(15, 0, left));
			case I2S -> context.mkSignExt(16, context.mkExtract(15, 0, left));
		};
		translated.put(expression, result);
		return result;
	}

	private BitVecExpr shiftDistance(BitVecExpr right, int bits) {
		BitVecExpr masked = context.mkBVAND(right, context.mkBV(bits - 1, 32));
		return bits == 64 ? context.mkZeroExt(32, masked) : masked;
	}

	@Override
	public void close() {
		if (context != null) context.close();
	}

	private record Query(Comparison comparison, BitVectorExpression left, BitVectorExpression right) {}
}
