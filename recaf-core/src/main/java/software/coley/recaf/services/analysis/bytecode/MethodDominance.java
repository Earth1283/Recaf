package software.coley.recaf.services.analysis.bytecode;

import java.util.*;
import java.util.function.BooleanSupplier;

import static software.coley.recaf.services.analysis.bytecode.JvmEvidenceScanner.checkCancelled;

final class MethodDominance {
	private static final int MAX_WORK = 2_000_000;
	private final int exit;
	private final List<Set<Integer>> successors;
	private final List<Set<Integer>> predecessors;
	private final BitSet reachable;
	private final BooleanSupplier cancelled;
	private int work;

	MethodDominance(int size, BooleanSupplier cancelled) {
		exit = size;
		this.cancelled = cancelled;
		reachable = new BitSet(size);
		successors = adjacency(size + 1);
		predecessors = adjacency(size + 1);
	}

	void enable(int node) { reachable.set(node); }

	void connect(int from, int to) {
		if (successors.get(from).add(to)) predecessors.get(to).add(from);
	}

	Result analyze() {
		int[] dominators = immediate(0, successors, predecessors);
		List<Integer> toExit = reversePostOrder(exit, predecessors);
		BitSet nonTerminating = (BitSet) reachable.clone();
		for (int index : toExit) nonTerminating.clear(index);
		for (int index = nonTerminating.nextSetBit(0); index >= 0; index = nonTerminating.nextSetBit(index + 1)) connect(index, exit);
		int[] postDominators = immediate(exit, predecessors, successors);
		TreeIntervals postTree = treeIntervals(postDominators);
		List<List<Integer>> controls = new ArrayList<>(exit);
		for (int i = 0; i < exit; i++) controls.add(new ArrayList<>());
		for (int branch = reachable.nextSetBit(0); branch >= 0; branch = reachable.nextSetBit(branch + 1)) {
			checkCancelled(cancelled);
			Set<Integer> targets = successors.get(branch);
			int realTargets = targets.size() - (nonTerminating.get(branch) ? 1 : 0);
			if (realTargets < 2) continue;
			BitSet visited = new BitSet(exit);
			for (int target : targets) {
				if (postTree.contains(target, branch)) continue;
				int runner = target;
				while (runner >= 0 && runner != exit && runner != postDominators[branch] && !visited.get(runner)) {
					charge();
					visited.set(runner);
					controls.get(runner).add(branch);
					runner = postDominators[runner];
				}
			}
		}
		dominators[0] = MethodAnalysis.NO_PARENT;
		for (int i = 0; i < exit; i++) {
			if (nonTerminating.get(i)) postDominators[i] = MethodAnalysis.NO_PARENT;
			else if (postDominators[i] == exit) postDominators[i] = MethodAnalysis.METHOD_EXIT;
		}
		return new Result(dominators, postDominators, controls.stream().map(List::copyOf).toList(), nonTerminating.cardinality());
	}

	private TreeIntervals treeIntervals(int[] parents) {
		List<Set<Integer>> children = adjacency(exit + 1);
		for (int node = 0; node < exit; node++) {
			if (parents[node] >= 0) children.get(parents[node]).add(node);
		}
		int[] start = new int[exit + 1], end = new int[exit + 1];
		Deque<Visit> pending = new ArrayDeque<>();
		pending.push(new Visit(exit, children.get(exit).iterator()));
		int index = 1;
		while (!pending.isEmpty()) {
			Visit current = pending.peek();
			if (current.edges().hasNext()) {
				int child = current.edges().next();
				start[child] = index++;
				pending.push(new Visit(child, children.get(child).iterator()));
			} else {
				end[pending.pop().node()] = index;
			}
		}
		return new TreeIntervals(start, end);
	}

	private int[] immediate(int root, List<Set<Integer>> outgoing, List<Set<Integer>> incoming) {
		int[] number = new int[exit + 1];
		int[] vertex = new int[exit + 2], parent = new int[exit + 2];
		int size = 1;
		number[root] = size;
		vertex[size] = root;
		Deque<Visit> pending = new ArrayDeque<>();
		pending.push(new Visit(root, outgoing.get(root).iterator()));
		while (!pending.isEmpty()) {
			charge();
			Visit visit = pending.peek();
			if (!visit.edges().hasNext()) {
				pending.pop();
				continue;
			}
			int target = visit.edges().next();
			if (number[target] == 0) {
				number[target] = ++size;
				vertex[size] = target;
				parent[size] = number[visit.node()];
				pending.push(new Visit(target, outgoing.get(target).iterator()));
			}
		}
		int[] semi = new int[size + 1], label = new int[size + 1], ancestor = new int[size + 1];
		int[] idom = new int[size + 1], bucket = new int[size + 1], next = new int[size + 1], scratch = new int[size + 1];
		for (int i = 1; i <= size; i++) semi[i] = label[i] = i;
		for (int i = size; i > 1; i--) {
			for (int predecessor : incoming.get(vertex[i])) {
				charge();
				int candidate = number[predecessor];
				if (candidate != 0) semi[i] = Math.min(semi[i], semi[evaluate(candidate, ancestor, label, semi, scratch)]);
			}
			next[i] = bucket[semi[i]];
			bucket[semi[i]] = i;
			ancestor[i] = parent[i];
			for (int member = bucket[parent[i]]; member != 0; member = next[member]) {
				int candidate = evaluate(member, ancestor, label, semi, scratch);
				idom[member] = semi[candidate] < semi[member] ? candidate : parent[i];
			}
			bucket[parent[i]] = 0;
		}
		int[] result = new int[exit + 1];
		Arrays.fill(result, MethodAnalysis.NO_PARENT);
		result[root] = root;
		for (int i = 2; i <= size; i++) {
			if (idom[i] != semi[i]) idom[i] = idom[idom[i]];
			result[vertex[i]] = vertex[idom[i]];
		}
		return result;
	}

	private int evaluate(int node, int[] ancestor, int[] label, int[] semi, int[] scratch) {
		int count = 0;
		for (int current = node; ancestor[ancestor[current]] != 0; current = ancestor[current]) {
			charge();
			scratch[count++] = current;
		}
		while (count > 0) {
			int current = scratch[--count];
			int parent = ancestor[current];
			if (semi[label[parent]] < semi[label[current]]) label[current] = label[parent];
			ancestor[current] = ancestor[parent];
		}
		return label[node];
	}

	private List<Integer> reversePostOrder(int root, List<Set<Integer>> edges) {
		BitSet visited = new BitSet(exit + 1);
		ArrayList<Integer> order = new ArrayList<>();
		Deque<Visit> pending = new ArrayDeque<>();
		pending.push(new Visit(root, edges.get(root).iterator()));
		visited.set(root);
		while (!pending.isEmpty()) {
			charge();
			Visit current = pending.peek();
			if (current.edges().hasNext()) {
				int next = current.edges().next();
				if (!visited.get(next)) {
					visited.set(next);
					pending.push(new Visit(next, edges.get(next).iterator()));
				}
			} else {
				order.add(pending.pop().node());
			}
		}
		return order.reversed();
	}

	private void charge() {
		if ((++work & 127) == 0) checkCancelled(cancelled);
		if (work > MAX_WORK) throw new IllegalArgumentException("The dominance analysis exceeds the work limit.");
	}

	private static List<Set<Integer>> adjacency(int size) {
		List<Set<Integer>> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++) list.add(new LinkedHashSet<>());
		return list;
	}

	private record Visit(int node, Iterator<Integer> edges) {}
	private record TreeIntervals(int[] start, int[] end) {
		boolean contains(int ancestor, int node) { return start[ancestor] <= start[node] && start[node] < end[ancestor]; }
	}
	record Result(int[] dominators, int[] postDominators, List<List<Integer>> controls, int nonTerminating) {}
}
