package software.coley.recaf.util.analysis.symbolic;

/** A JVM int or long expression. The sample is the value when every local is zero. */
record BitVectorExpression(Op op, int bits, long value, BitVectorExpression left, BitVectorExpression right, long sample) {
	BitVectorExpression(Op op, int bits, long value, BitVectorExpression left, BitVectorExpression right) {
		this(op, bits, value, left, right,
				evaluate(op, bits, value, left == null ? 0 : left.sample, right == null ? 0 : right.sample));
	}

	long sample(long localValue) {
		if (op == Op.LOCAL) return bits == 32 ? (int) localValue : localValue;
		return evaluate(op, bits, value, left == null ? 0 : left.sample(localValue), right == null ? 0 : right.sample(localValue));
	}

	static long evaluate(Op op, int bits, long value, long left, long right) {
		long result = switch (op) {
			case CONSTANT -> value;
			case LOCAL -> 0;
			case ADD -> left + right;
			case SUB -> left - right;
			case MUL -> left * right;
			case AND -> left & right;
			case OR -> left | right;
			case XOR -> left ^ right;
			case SHL -> left << (right & (bits - 1));
			case ASHR -> left >> (right & (bits - 1));
			case LSHR -> bits == 32 ? (int) left >>> (right & 31) : left >>> (right & 63);
			case NEG -> -left;
			case I2L, L2I -> left;
			case I2B -> (byte) left;
			case I2C -> (char) left;
			case I2S -> (short) left;
		};
		return bits == 32 ? (int) result : result;
	}

	enum Op {
		CONSTANT, LOCAL, ADD, SUB, MUL, AND, OR, XOR, SHL, ASHR, LSHR, NEG, I2L, L2I, I2B, I2C, I2S;

		boolean associativeCommutative() {
			return switch (this) {
				case ADD, MUL, AND, OR, XOR -> true;
				default -> false;
			};
		}

		boolean shift() {
			return this == SHL || this == ASHR || this == LSHR;
		}
	}

	enum Comparison {
		EQ, NE, LT, GE, GT, LE;

		boolean test(long left, long right) {
			return switch (this) {
				case EQ -> left == right;
				case NE -> left != right;
				case LT -> left < right;
				case GE -> left >= right;
				case GT -> left > right;
				case LE -> left <= right;
			};
		}
	}
}
