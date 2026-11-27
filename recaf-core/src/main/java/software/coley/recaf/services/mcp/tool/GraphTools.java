package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.analysis.entry.EntryAnalysisService;
import software.coley.recaf.services.analysis.entry.EntryPoint;
import software.coley.recaf.services.callgraph.CallGraph;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.callgraph.ClassMethodsContainer;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
	private static final int MAX_PATH_VISITS = 50_000;
	private final McpSupport support;
	private final InheritanceGraphService inheritanceService;
	private final CallGraphService callGraphService;
	private final EntryAnalysisService entryAnalysisService;
	private Workspace fallbackWorkspace;
	private CallGraph fallbackGraph;

	@Inject
	public GraphTools(@Nonnull McpSupport support, @Nonnull InheritanceGraphService inheritanceService,
	                  @Nonnull CallGraphService callGraphService, @Nonnull EntryAnalysisService entryAnalysisService) {
		this.support = support;
		this.inheritanceService = inheritanceService;
		this.callGraphService = callGraphService;
		this.entryAnalysisService = entryAnalysisService;
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
		registry.add("callgraph_path",
				"Find shortest call chains reaching a target class/method, either from a specific source class/method or from " +
						"any discovered entry point in the workspace. Omit 'to_method' to target any method/constructor of 'to_class'.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("to_class", "Target class (internal name).")
						.string("to_method", "Target method name. Omit to match any method or constructor of 'to_class'.")
						.string("to_descriptor", "Target method descriptor, needed when overloaded.")
						.string("from_class", "Source class (internal name). Omit to search from all workspace entry points.")
						.string("from_method", "Optional source method name (when from_class is given).")
						.string("from_descriptor", "Optional source method descriptor.")
						.integer("max_depth", "Maximum call chain depth. Default 10, at most 20.")
						.integer("max_paths", "Maximum paths to return. Default 5, at most 20."),
				this::path);
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

	private void waitReady(@Nonnull CallGraph graph) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
		while (!graph.isReady().getValue()) {
			if (System.nanoTime() > deadline)
				throw new McpToolException("The call graph is still being built, try again shortly");
			Thread.sleep(50);
		}
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
		ClassPathNode ownerPath = support.requireClass(args.requireString("class"));
		ClassMemberPathNode methodPath = support.requireMethod(ownerPath, args.requireString("method"),
				args.string("descriptor", null));
		ClassInfo owner = ownerPath.getValue();
		MethodMember method = (MethodMember) methodPath.getValue();

		CallGraph graph = graph(workspace);
		waitReady(graph);

		MethodVertex start = graph.getClassMethodsContainer(owner).getVertex(method);
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
	private McpToolResult path(@Nonnull McpArgs args) throws InterruptedException {
		Workspace workspace = support.workspace();
		ClassPathNode toClass = support.requireClass(args.requireString("to_class"));
		String toMethodName = args.string("to_method", null);
		String toDesc = args.string("to_descriptor", null);
		List<MethodMember> targetMethods;
		String targetLabel;
		if (toMethodName != null) {
			MethodMember single = (MethodMember) support.requireMethod(toClass, toMethodName, toDesc).getValue();
			targetMethods = List.of(single);
			targetLabel = toClass.getValue().getName() + "." + single.getName() + single.getDescriptor();
		} else {
			targetMethods = toClass.getValue().getMethods();
			if (targetMethods.isEmpty())
				throw new McpToolException("Class " + toClass.getValue().getName() + " declares no methods");
			targetLabel = toClass.getValue().getName() + ".*";
		}

		Set<MethodRef> startRefs = new HashSet<>();
		if (args.has("from_class")) {
			ClassPathNode fromClass = support.requireClass(args.requireString("from_class"));
			if (args.has("from_method")) {
				ClassMemberPathNode fromMethodPath = support.requireMethod(fromClass, args.requireString("from_method"),
						args.string("from_descriptor", null));
				startRefs.add(new MethodRef(fromClass.getValue().getName(),
						fromMethodPath.getValue().getName(), fromMethodPath.getValue().getDescriptor()));
			} else {
				for (MethodMember m : fromClass.getValue().getMethods())
					startRefs.add(new MethodRef(fromClass.getValue().getName(), m.getName(), m.getDescriptor()));
			}
		} else {
			for (EntryPoint ep : entryAnalysisService.findEntryPoints(workspace, workspace.getPrimaryResource())) {
				if (ep.memberPath() != null && ep.memberPath().getValue() instanceof MethodMember m) {
					startRefs.add(new MethodRef(ep.classPath().getValue().getName(), m.getName(), m.getDescriptor()));
				} else {
					ClassInfo cls = ep.classPath().getValue();
					for (MethodMember m : cls.getMethods())
						startRefs.add(new MethodRef(cls.getName(), m.getName(), m.getDescriptor()));
				}
			}
		}

		CallGraph graph = graph(workspace);
		waitReady(graph);

		ClassMethodsContainer targetContainer = graph.getClassMethodsContainer(toClass.getValue());
		int maxDepth = Math.max(1, Math.min(20, args.integer("max_depth", 10)));
		int maxPaths = Math.max(1, Math.min(20, args.integer("max_paths", 5)));

		record Chain(MethodVertex vertex, Chain next, int depth) {
			boolean contains(@Nonnull MethodRef ref) {
				for (Chain c = this; c != null; c = c.next)
					if (c.vertex.getMethod().equals(ref))
						return true;
				return false;
			}

			@Nonnull
			JsonArray toJsonArray() {
				JsonArray chain = new JsonArray();
				for (Chain c = this; c != null; c = c.next) {
					MethodRef ref = c.vertex.getMethod();
					JsonObject step = new JsonObject();
					step.addProperty("owner", ref.owner());
					step.addProperty("name", ref.name());
					step.addProperty("descriptor", ref.desc());
					chain.add(step);
				}
				return chain;
			}
		}

		Queue<Chain> queue = new ArrayDeque<>();
		Map<MethodRef, Integer> bestDepth = new HashMap<>();
		for (MethodMember tm : targetMethods) {
			MethodVertex v = targetContainer.getVertex(tm);
			if (v != null && bestDepth.putIfAbsent(v.getMethod(), 0) == null)
				queue.add(new Chain(v, null, 0));
		}

		JsonArray paths = new JsonArray();
		List<Chain> deadEnds = new ArrayList<>();
		Set<MethodRef> seenDeadEnds = new HashSet<>();
		int visits = 0;
		while (!queue.isEmpty() && paths.size() < maxPaths && visits++ < MAX_PATH_VISITS) {
			Chain current = queue.poll();
			MethodRef currentRef = current.vertex().getMethod();
			if (startRefs.contains(currentRef)) {
				paths.add(current.toJsonArray());
				continue;
			}
			List<MethodVertex> callers = current.vertex().getCallers().stream()
					.sorted(Comparator.comparing(v -> v.getMethod().owner() + "." + v.getMethod().name() + v.getMethod().desc()))
					.toList();
			if (callers.isEmpty()) {
				if (deadEnds.size() < 15 && seenDeadEnds.add(currentRef))
					deadEnds.add(current);
				continue;
			}
			if (current.depth() >= maxDepth)
				continue;
			for (MethodVertex caller : callers) {
				MethodRef callerRef = caller.getMethod();
				if (current.contains(callerRef))
					continue;
				int nextDepth = current.depth() + 1;
				Integer prevBest = bestDepth.get(callerRef);
				if (prevBest != null && nextDepth > prevBest)
					continue;
				bestDepth.put(callerRef, nextDepth);
				queue.add(new Chain(caller, current, nextDepth));
			}
		}

		JsonObject json = new JsonObject();
		json.addProperty("target", targetLabel);
		json.addProperty("startCount", startRefs.size());
		json.addProperty("found", paths.size());
		json.add("paths", paths);
		if (paths.isEmpty() && !deadEnds.isEmpty()) {
			JsonArray roots = new JsonArray();
			for (Chain dead : deadEnds) {
				MethodRef ref = dead.vertex().getMethod();
				JsonObject entry = new JsonObject();
				entry.addProperty("owner", ref.owner());
				entry.addProperty("name", ref.name());
				entry.addProperty("descriptor", ref.desc());
				entry.addProperty("depth", dead.depth());
				entry.add("pathToTarget", dead.toJsonArray());
				roots.add(entry);
			}
			json.add("rootCallers", roots);
		}
		return McpToolResult.json(json);
	}

	@Nonnull
	private static JsonArray names(@Nonnull Collection<InheritanceVertex> vertices, int limit) {
		JsonArray array = new JsonArray();
		vertices.stream().map(InheritanceVertex::getName).sorted().limit(limit).forEach(array::add);
		return array;
	}
}
