package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.callgraph.CallGraph;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.callgraph.MethodVertex;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Tools for the inheritance and call graphs.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class GraphTools implements McpToolProvider {
	private static final int MAX_NODES = 500;
	private final McpSupport support;
	private final InheritanceGraphService inheritanceService;
	private final CallGraphService callGraphService;
	private Workspace fallbackWorkspace;
	private CallGraph fallbackGraph;

	@Inject
	public GraphTools(@Nonnull McpSupport support, @Nonnull InheritanceGraphService inheritanceService,
	                  @Nonnull CallGraphService callGraphService) {
		this.support = support;
		this.inheritanceService = inheritanceService;
		this.callGraphService = callGraphService;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("inheritance_info",
				"Class hierarchy around a class: direct and all parents, direct and all children.",
				McpAccess.READ,
				McpToolSchema.create().requiredString("name", "Class name (internal form).")
						.integer("limit", "Maximum entries per list. Default 200."),
				this::inheritance);
		registry.add("inheritance_is_assignable",
				"Whether a value of type 'from' can be assigned to type 'to'.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("to", "Target type (internal name).")
						.requiredString("from", "Source type (internal name)."),
				args -> {
					InheritanceGraph graph = graph();
					boolean assignable = graph.isAssignableFrom(McpSupport.normalizeClassName(args.requireString("to")),
							McpSupport.normalizeClassName(args.requireString("from")));
					JsonObject json = new JsonObject();
					json.addProperty("assignable", assignable);
					return McpToolResult.json(json);
				});
		registry.add("callgraph_callers",
				"Methods that call the given method, transitively up to 'depth' levels.",
				McpAccess.READ, methodSchema(), args -> walk(args, true));
		registry.add("callgraph_callees",
				"Methods called by the given method, transitively up to 'depth' levels.",
				McpAccess.READ, methodSchema(), args -> walk(args, false));
	}

	@Nonnull
	private static McpToolSchema methodSchema() {
		return McpToolSchema.create()
				.requiredString("class", "Declaring class (internal name).")
				.requiredString("method", "Method name.")
				.string("descriptor", "Method descriptor, needed when the name is overloaded.")
				.integer("depth", "Levels to follow. Default 1, at most 8.")
				.integer("limit", "Maximum methods to return. Default 200, at most " + MAX_NODES + ".");
	}

	@Nonnull
	private InheritanceGraph graph() {
		Workspace workspace = support.workspace();
		InheritanceGraph graph = inheritanceService.getOrCreateInheritanceGraph(workspace);
		if (graph == null)
			throw new McpToolException("No inheritance graph is available");
		return graph;
	}

	/**
	 * The service only tracks a graph for workspaces opened while it was running, so one is built on demand otherwise.
	 */
	@Nonnull
	private synchronized CallGraph graph(@Nonnull Workspace workspace) {
		CallGraph current = callGraphService.getCurrentWorkspaceCallGraph();
		if (current != null)
			return current;
		if (fallbackWorkspace != workspace) {
			fallbackGraph = callGraphService.newCallGraph(workspace);
			fallbackWorkspace = workspace;
		}
		if (!fallbackGraph.isInitialized())
			fallbackGraph.initialize();
		return fallbackGraph;
	}

	@Nonnull
	private McpToolResult inheritance(@Nonnull McpArgs args) {
		String name = McpSupport.normalizeClassName(args.requireString("name"));
		InheritanceVertex vertex = graph().getVertex(name);
		if (vertex == null)
			throw new McpToolException("No class named '" + name + "' in the workspace or the JDK");
		int limit = args.limit(200);
		JsonObject json = new JsonObject();
		json.addProperty("name", name);
		json.add("parents", names(vertex.getParents(), limit));
		json.add("allParents", names(vertex.getAllParents(), limit));
		json.add("children", names(vertex.getChildren(), limit));
		json.add("allChildren", names(vertex.getAllChildren(), limit));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult walk(@Nonnull McpArgs args, boolean callers) throws InterruptedException {
		Workspace workspace = support.workspace();
		ClassInfo owner = support.requireClass(args.requireString("class")).getValue();
		String methodName = args.requireString("method");
		String descriptor = args.string("descriptor", null);
		List<MethodMember> candidates = owner.getMethods().stream()
				.filter(m -> m.getName().equals(methodName) && (descriptor == null || m.getDescriptor().equals(descriptor)))
				.toList();
		if (candidates.isEmpty())
			throw new McpToolException("No method '" + methodName + "' in " + owner.getName());
		if (candidates.size() > 1)
			throw new McpToolException("'" + methodName + "' is overloaded, pass 'descriptor'. Candidates: " +
					candidates.stream().map(MethodMember::getDescriptor).toList());

		CallGraph graph = graph(workspace);
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
		while (!graph.isReady().getValue()) {
			if (System.nanoTime() > deadline)
				throw new McpToolException("The call graph is still being built, try again shortly");
			Thread.sleep(50);
		}

		MethodVertex start = graph.getClassMethodsContainer(owner).getVertex(candidates.getFirst());
		int maxDepth = Math.max(1, Math.min(8, args.integer("depth", 1)));
		int limit = Math.min(MAX_NODES, args.limit(200));
		Function<MethodVertex, Collection<MethodVertex>> next = callers ? MethodVertex::getCallers : MethodVertex::getCalls;

		JsonArray result = new JsonArray();
		Set<MethodRef> seen = new HashSet<>();
		seen.add(start.getMethod());
		Queue<MethodVertex> queue = new ArrayDeque<>();
		Queue<Integer> depths = new ArrayDeque<>();
		queue.add(start);
		depths.add(0);
		boolean truncated = false;
		while (!queue.isEmpty() && !truncated) {
			MethodVertex current = queue.poll();
			int depth = depths.poll();
			if (depth >= maxDepth)
				continue;
			List<MethodVertex> neighbors = next.apply(current).stream()
					.sorted(Comparator.comparing(v -> v.getMethod().owner() + "." + v.getMethod().name()))
					.toList();
			for (MethodVertex neighbor : neighbors) {
				MethodRef ref = neighbor.getMethod();
				if (!seen.add(ref))
					continue;
				if (result.size() >= limit) {
					truncated = true;
					break;
				}
				JsonObject json = new JsonObject();
				json.addProperty("owner", ref.owner());
				json.addProperty("name", ref.name());
				json.addProperty("descriptor", ref.desc());
				json.addProperty("depth", depth + 1);
				json.addProperty("via", current.getMethod().owner() + "." + current.getMethod().name());
				result.add(json);
				queue.add(neighbor);
				depths.add(depth + 1);
			}
		}
		JsonObject json = new JsonObject();
		json.addProperty("start", start.getMethod().owner() + "." + start.getMethod().name() + start.getMethod().desc());
		json.addProperty("truncated", truncated);
		json.add(callers ? "callers" : "callees", result);
		return McpToolResult.json(json);
	}

	@Nonnull
	private static JsonArray names(@Nonnull Collection<InheritanceVertex> vertices, int limit) {
		JsonArray array = new JsonArray();
		vertices.stream().map(InheritanceVertex::getName).sorted().limit(limit).forEach(array::add);
		return array;
	}
}
