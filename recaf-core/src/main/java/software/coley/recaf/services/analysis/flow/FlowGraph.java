package software.coley.recaf.services.analysis.flow;

import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;

import java.util.List;

/** Immutable UI/API values. Instruction numbers count executable JVM opcodes, starting at zero. */
public final class FlowGraph {
	private FlowGraph() {}

	public record MethodId(String resource, CodeLocation location) implements Comparable<MethodId> {
		@Override public int compareTo(MethodId other) {
			int result = resource.compareTo(other.resource);
			return result == 0 ? location.compareTo(other.location) : result;
		}
		@Override public String toString() { return location + " [" + resource + "]"; }
	}
	public record Method(MethodId id, ClassMemberPathNode path, String entryReason, boolean networkEntry) {
		@Override public String toString() { return id.location().display() + " " + id.location().memberDescriptor() + " [" + id.resource() + "]"; }
	}
	public record Site(MethodId method, int instruction, String target, String sink) {
		@Override public String toString() { return method.location().display() + " @" + instruction + " → " + target; }
	}
	public enum CallKind { DIRECT, VIRTUAL, CROSS_RESOURCE, LAMBDA, SCHEDULED, EXTERNAL, UNRESOLVED }
	public record Call(Site site, MethodId target, CallKind kind, String explanation) {}
	public enum Influence { DATA, CONTROL, REACHABILITY }
	public enum EdgeKind { VALUE, ARGUMENT, RETURN, FIELD, MODEL, CONTROL, EXECUTION, CALLBACK, ASSUMED }
	public record Step(MethodId method, int instruction, String description, EdgeKind edge, boolean assumed) {}
	/** Steps are ordered source to sink. Reachability alone makes no claim about data or authorization. */
	public record Witness(Influence influence, String source, List<Step> steps) {}
	public record Trace(Site sink, List<Witness> witnesses, List<Step> conditions, List<String> gaps, int visitedStates, boolean truncated) {}
	public record Limits(int maxStates, int maxDepth, int maxPaths, int maxContext) {
		public Limits {
			if (maxStates < 1 || maxDepth < 1 || maxPaths < 1 || maxContext < 1) throw new IllegalArgumentException("Limits must be positive");
		}
		public static Limits defaults() { return new Limits(20_000, 160, 40, 16); }
	}
}
