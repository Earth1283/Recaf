package software.coley.recaf.util.analysis.symbolic;

import jakarta.annotation.Nullable;
import software.coley.recaf.util.analysis.symbolic.BitVectorExpression.Op;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded equality saturation for JVM bit vectors. Rewrites add alternatives to equivalence
 * classes, rebuilding restores congruence, and extraction selects the lowest-cost expression.
 *
 * @see <a href="https://github.com/egraphs-good/egg">egg, the Rust equality saturation library</a>
 * @see <a href="https://doi.org/10.1145/3434304">Willsey et al., egg: Fast and Extensible Equality Saturation</a>
 */
final class BitVectorEGraph {
	private static final int MAX_NODES = 192;
	private static final int MAX_ROUNDS = 6;
	private static final int NO_CHILD = -1;
	private final List<Node> nodes = new ArrayList<>();
	private final Map<Node, Integer> memo = new HashMap<>();
	private final int[] parents = new int[MAX_NODES];
	private boolean changed;

	void clear() {
		nodes.clear();
		memo.clear();
	}

	int leaf(Op op, int bits, long value) {
		return add(new Node(op, bits, value, NO_CHILD, NO_CHILD));
	}

	int operation(Op op, int bits, int left, int right) {
		return add(new Node(op, bits, 0, left, right));
	}

	void saturate() {
		for (int round = 0; round < MAX_ROUNDS; round++) {
			changed = false;
			int count = nodes.size();
			Node[] constants = new Node[count];
			List<List<Node>> alternatives = new ArrayList<>(count);
			for (int i = 0; i < count; i++)
				alternatives.add(null);
			for (int i = 0; i < count; i++) {
				int root = find(i);
				Node node = nodes.get(i);
				if (memo.get(node) != i) continue;
				if (node.op == Op.CONSTANT) constants[root] = node;
				if (node.op.associativeCommutative() || node.op == Op.NEG || node.op == Op.I2L) {
					List<Node> choices = alternatives.get(root);
					if (choices == null) {
						choices = new ArrayList<>();
						alternatives.set(root, choices);
					}
					choices.add(node);
				}
			}
			for (int i = 0; i < count; i++) {
				Node node = nodes.get(i);
				if (node.left == NO_CHILD || memo.get(node) != i) continue;
				int left = node.left;
				int right = node.right;
				// Rewrites consult the classes as they stood at the beginning of the round.
				rewrite(i, node, constants[left], right == NO_CHILD ? null : constants[right],
						alternatives.get(left), right == NO_CHILD ? null : alternatives.get(right));
			}
			if (!changed) return;
			rebuild();
		}
	}

	int find(int id) {
		while (parents[id] != id) {
			parents[id] = parents[parents[id]];
			id = parents[id];
		}
		return id;
	}

	BitVectorExpression[] extract(int left, int right) {
		BitVectorExpression[] best = extractBest();
		return new BitVectorExpression[] { best[find(left)], best[find(right)] };
	}

	@Nullable
	BitVectorExpression extractLeaf(int id) {
		int root = find(id);
		for (int i = 0; i < nodes.size(); i++) {
			Node node = nodes.get(i);
			if (node.left == NO_CHILD && find(i) == root)
				return new BitVectorExpression(node.op, node.bits, node.value, null, null);
		}
		return null;
	}

	private BitVectorExpression[] extractBest() {
		int size = nodes.size();
		int[] costs = new int[size];
		Arrays.fill(costs, Integer.MAX_VALUE);
		BitVectorExpression[] best = new BitVectorExpression[size];
		boolean improved;
		do {
			improved = false;
			for (int i = 0; i < size; i++) {
				int root = find(i);
				Node node = nodes.get(i);
				BitVectorExpression a = node.left == NO_CHILD ? null : best[node.left];
				BitVectorExpression b = node.right == NO_CHILD ? null : best[node.right];
				if ((a == null && node.left != NO_CHILD) || (b == null && node.right != NO_CHILD)) continue;
				long cost = (node.op == Op.MUL ? 3L : 1L)
						+ (a == null ? 0 : costs[node.left]) + (b == null ? 0 : costs[node.right]);
				if (cost < costs[root]) {
					costs[root] = (int) cost;
					best[root] = new BitVectorExpression(node.op, node.bits, node.value, a, b);
					improved = true;
				}
			}
		} while (improved);
		return best;
	}

	private void rewrite(int id, Node node, Node a, Node b, List<Node> leftChoices, List<Node> rightChoices) {
		int left = node.left;
		int right = node.right;
		int bits = node.bits;
		Op op = node.op;
		if (a != null && (b != null || right == NO_CHILD)) {
			union(id, leaf(Op.CONSTANT, bits, BitVectorExpression.evaluate(op, bits, 0, a.value, b == null ? 0 : b.value)));
			return;
		}
		if (right != NO_CHILD && find(left) == find(right)) {
			switch (op) {
				case SUB, XOR -> union(id, leaf(Op.CONSTANT, bits, 0));
				case AND, OR -> union(id, left);
				default -> { }
			}
		}
		if (a != null && op.associativeCommutative())
			identity(id, op, bits, right, a.value);
		if (b != null)
			identity(id, op, bits, left, b.value);

		if (leftChoices != null) {
			for (Node choice : leftChoices) rewriteNested(id, node, choice, right);
		}
		if (rightChoices != null && op.associativeCommutative()) {
			for (Node choice : rightChoices) rewriteNested(id, node, choice, left);
		}
	}

	private void identity(int id, Op op, int bits, int other, long constant) {
		switch (op) {
			case ADD, SUB, OR, XOR -> {
				if (constant == 0) union(id, other);
				else if (op == Op.OR && constant == -1) union(id, leaf(Op.CONSTANT, bits, -1));
			}
			case SHL, ASHR, LSHR -> {
				if ((constant & (bits - 1)) == 0) union(id, other);
			}
			case MUL, AND -> {
				if (constant == 0) union(id, leaf(Op.CONSTANT, bits, 0));
				else if (constant == (op == Op.MUL ? 1 : -1)) union(id, other);
			}
			default -> { }
		}
	}

	private void rewriteNested(int id, Node outer, Node inner, int other) {
		Op op = outer.op;
		if ((op == Op.NEG && inner.op == Op.NEG) || (op == Op.L2I && inner.op == Op.I2L)) {
			union(id, inner.left);
		} else if (op == Op.SUB && inner.op == Op.ADD) {
			if (find(inner.left) == find(other)) union(id, inner.right);
			if (find(inner.right) == find(other)) union(id, inner.left);
		} else if (op == inner.op && op.associativeCommutative()) {
			if (op == Op.XOR) {
				if (find(inner.left) == find(other)) union(id, inner.right);
				if (find(inner.right) == find(other)) union(id, inner.left);
			}
			reassociate(id, op, outer.bits, inner.left, inner.right, other);
			reassociate(id, op, outer.bits, inner.right, inner.left, other);
		}
	}

	private void reassociate(int id, Op op, int bits, int retained, int grouped, int other) {
		// Avoid generating redundant self-referential alternatives after identity rewrites.
		if (find(id) == find(retained) || find(id) == find(grouped) || find(id) == find(other)) return;
		int pair = operation(op, bits, grouped, other);
		if (pair != NO_CHILD) union(id, operation(op, bits, retained, pair));
	}

	private int add(Node node) {
		node = canonical(node);
		Integer existing = memo.get(node);
		if (existing != null) return find(existing);
		int id = nodes.size();
		if (id == MAX_NODES) return NO_CHILD;
		nodes.add(node);
		parents[id] = id;
		memo.put(node, id);
		changed = true;
		return id;
	}

	private void union(int left, int right) {
		if (right == NO_CHILD) return;
		int a = find(left);
		int b = find(right);
		if (a == b) return;
		parents[Math.max(a, b)] = Math.min(a, b);
		changed = true;
	}

	private Node canonical(Node node) {
		if (node.left == NO_CHILD) return node;
		int left = find(node.left);
		int right = node.right == NO_CHILD ? NO_CHILD : find(node.right);
		if (node.op.associativeCommutative() && left > right) {
			int swap = left;
			left = right;
			right = swap;
		}
		return left == node.left && right == node.right ? node : new Node(node.op, node.bits, node.value, left, right);
	}

	private void rebuild() {
		boolean merged;
		do {
			merged = false;
			memo.clear();
			for (int i = 0; i < nodes.size(); i++) {
				Node node = canonical(nodes.get(i));
				nodes.set(i, node);
				Integer prior = memo.putIfAbsent(node, i);
				if (prior != null && find(prior) != find(i)) {
					union(i, prior);
					merged = true;
				}
			}
		} while (merged);
	}

	private record Node(Op op, int bits, long value, int left, int right) {}
}
