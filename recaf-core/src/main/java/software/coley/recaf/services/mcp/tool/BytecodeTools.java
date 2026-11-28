package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationReport;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationService;
import software.coley.recaf.services.analysis.bytecode.MethodAnalysis;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.decompile.fallback.MethodBytecodeReport;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Tools for per-method bytecode inspection, dataflow/dominance/slicing, call sites and safe constant evaluation.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class BytecodeTools implements McpToolProvider {
	private static final int DEFAULT_MAX_CHARS = 60_000;
	private static final int SECTION_CAP = 20_000;
	private static final int DEFAULT_EVAL_STEPS = 50_000;
	private final McpSupport support;
	private final BytecodeInvestigationService investigation;

	@Inject
	public BytecodeTools(@Nonnull McpSupport support, @Nonnull BytecodeInvestigationService investigation) {
		this.support = support;
		this.investigation = investigation;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("method_bytecode",
				"Inspect a JVM method's raw bytecode instructions, basic-block control flow, exception table, and frame diagnostics. " +
						"Works even when decompilation fails.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("class", "Declaring class name (internal form).")
						.requiredString("method", "Method name.")
						.string("descriptor", "Method descriptor, needed when overloaded.")
						.string("resource", "Resource label or embedded path.")
						.enumeration("section", "Which section to return. Default all.",
								List.of("all", "instructions", "control_flow", "exceptions", "diagnostics"))
						.integer("start_line", "1-based line to start at (for instructions/control_flow/exceptions).")
						.integer("max_lines", "Maximum number of lines to return.")
						.integer("max_chars", "Maximum characters to return. Default " + DEFAULT_MAX_CHARS + ".")
						.bool("line_numbers", "Prefix lines with 1-based line numbers. Default false."),
				this::methodBytecode);
		registry.add("method_analyze",
				"Intraprocedural dataflow, dominance, control-dependence, summary and backward slicing of a JVM method.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("class", "Declaring class name (internal form).")
						.requiredString("method", "Method name.")
						.string("descriptor", "Method descriptor, needed when overloaded.")
						.string("resource", "Resource label or embedded path.")
						.integer("slice_from", "Optional 0-based instruction ordinal to compute a backward slice from.")
						.integer("slice_local", "Slice this JVM local slot before slice_from.")
						.integer("slice_stack", "Slice this operand stack slot before slice_from, counting from the bottom.")
						.bool("include_instructions", "Include paged per-instruction frames, dominators and inputs. Default false.")
						.paging(),
				this::methodAnalyze);
		registry.add("bytecode_call_sites",
				"List invokedynamic and static string-helper call sites with instruction ordinals for use with bytecode_evaluate.",
				McpAccess.READ,
				McpToolSchema.create()
						.string("class", "Filter by exact caller class name, substring (case-insensitive), or 're:<regex>'.")
						.string("package", "Filter by caller package (e.g. 'com/example' or 'com/example/*').")
						.string("method", "Filter by caller method name substring (case-insensitive) or 're:<regex>'.")
						.enumeration("kind", "Call site kind. Default all.",
								List.of("all", "dynamic", "custom_dynamic", "string_call"))
						.string("target", "Filter by target/bootstrap owner.name substring (case-insensitive) or 're:<regex>'.")
						.string("resource", "Resource label or embedded path. Defaults to primary.")
						.paging(),
				this::callSites);
		registry.add("bytecode_evaluate",
				"Safely interpret static string-helper calls in a method without loading classes into the JVM. " +
						"Pass 'instruction' for a single call site, or omit it to evaluate all constant-input string helper calls in the method.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("class", "Caller class name (internal form).")
						.requiredString("method", "Caller method name.")
						.string("descriptor", "Caller method descriptor, needed when overloaded.")
						.integer("instruction", "0-based instruction ordinal of an INVOKESTATIC call. Omit to evaluate all string calls in the method.")
						.integer("max_steps", "Evaluator instruction budget (100..1000000). Default " + DEFAULT_EVAL_STEPS + ".")
						.bool("initialize_classes", "Run static class initializers when interpreting. Default false.")
						.string("resource", "Resource label or embedded path.")
						.integer("limit", "Maximum call sites to evaluate when 'instruction' is omitted. Default 25."),
				this::evaluate);
	}

	@Nonnull
	private McpToolResult methodBytecode(@Nonnull McpArgs args) {
		ClassPathNode classPath = support.requireClass(args.requireString("class"), args.string("resource", null));
		if (!classPath.getValue().isJvmClass())
			throw new McpToolException("'" + classPath.getValue().getName() + "' is not a JVM class");
		JvmClassInfo jvmClass = classPath.getValue().asJvmClass();
		ClassMemberPathNode memberPath = support.requireMethod(classPath, args.requireString("method"),
				args.string("descriptor", null));
		MethodMember method = (MethodMember) memberPath.getValue();

		MethodBytecodeReport report;
		try {
			report = MethodBytecodeReport.inspect(jvmClass, method);
		} catch (IllegalArgumentException ex) {
			throw new McpToolException(ex.getMessage());
		}

		String section = args.string("section", "all");
		JsonObject json = new JsonObject();
		json.addProperty("class", jvmClass.getName());
		json.addProperty("method", method.getName());
		json.addProperty("descriptor", method.getDescriptor());
		json.addProperty("section", section);

		switch (section) {
			case "instructions" -> merge(json, McpSupport.boundedLines(report.instructions(), args, DEFAULT_MAX_CHARS));
			case "control_flow" -> merge(json, McpSupport.boundedLines(report.controlFlow(), args, DEFAULT_MAX_CHARS));
			case "exceptions" -> merge(json, McpSupport.boundedLines(report.exceptionRegions(), args, DEFAULT_MAX_CHARS));
			case "diagnostics" -> merge(json, McpSupport.boundedLines(report.diagnostics(), args, DEFAULT_MAX_CHARS));
			case "all" -> {
				json.addProperty("diagnostics", report.diagnostics());
				json.add("exceptionRegions", McpSupport.bounded(report.exceptionRegions(), SECTION_CAP));
				json.add("controlFlow", McpSupport.bounded(report.controlFlow(), SECTION_CAP));
				json.add("instructions", McpSupport.boundedLines(report.instructions(), args, DEFAULT_MAX_CHARS));
			}
			default -> throw new McpToolException("Unknown section '" + section + "'");
		}
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult methodAnalyze(@Nonnull McpArgs args) {
		boolean sliceLocal = args.has("slice_local");
		boolean sliceStack = args.has("slice_stack");
		if ((sliceLocal || sliceStack) && !args.has("slice_from"))
			throw new McpToolException("'slice_from' is required when selecting a local or stack slot");
		if (sliceLocal && sliceStack)
			throw new McpToolException("Select one of 'slice_local' and 'slice_stack'");
		ClassPathNode classPath = support.requireClass(args.requireString("class"), args.string("resource", null));
		if (!classPath.getValue().isJvmClass())
			throw new McpToolException("'" + classPath.getValue().getName() + "' is not a JVM class");
		ClassMemberPathNode memberPath = support.requireMethod(classPath, args.requireString("method"),
				args.string("descriptor", null));

		MethodAnalysis.Result result;
		try {
			result = investigation.analyzeMethod(memberPath, () -> false);
		} catch (IllegalArgumentException | AnalyzerException | IllegalStateException ex) {
			throw new McpToolException("Method analysis failed: " + ex.getMessage() +
					" (use method_bytecode for raw instructions/CFG)");
		}

		JsonObject json = new JsonObject();
		json.addProperty("class", classPath.getValue().getName());
		json.addProperty("method", memberPath.getValue().getName());
		json.addProperty("descriptor", memberPath.getValue().getDescriptor());
		json.addProperty("totalInstructions", result.instructions().size());
		json.addProperty("nonTerminatingInstructions", result.nonTerminatingInstructions());
		json.add("summary", describeSummary(result.summary()));

		if (args.has("slice_from")) {
			int sink = args.requireInteger("slice_from");
			if (sink < 0 || sink >= result.instructions().size())
				throw new McpToolException("'slice_from' (" + sink + ") out of bounds for " +
						result.instructions().size() + " instructions");
			JsonObject sliceJson = new JsonObject();
			sliceJson.addProperty("sink", sink);
			MethodAnalysis.Slice slice;
			try {
				if (sliceLocal || sliceStack) {
					int slot = args.requireInteger(sliceLocal ? "slice_local" : "slice_stack");
					slice = sliceLocal ? result.backwardSliceLocal(sink, slot) : result.backwardSliceStack(sink, slot);
					sliceJson.addProperty("slotKind", sliceLocal ? "local" : "stack");
					sliceJson.addProperty("slot", slot);
				} else {
					slice = result.backwardSlice(sink);
				}
			} catch (IllegalArgumentException ex) {
				throw new McpToolException(ex.getMessage());
			}
			sliceJson.add("entryDefinitions", definitionArray(slice.entryDefinitions()));
			sliceJson.addProperty("dependsOnInitialHeap", slice.dependsOnInitialHeap());
			JsonArray sliceInsns = new JsonArray();
			for (int idx : slice.instructions()) {
				MethodAnalysis.Instruction insn = result.instructions().get(idx);
				JsonObject entry = new JsonObject();
				entry.addProperty("index", insn.index());
				entry.addProperty("opcode", insn.opcode());
				entry.add("inputs", definitionArray(insn.inputs()));
				entry.add("controlDependencies", intArray(insn.controlDependencies()));
				entry.add("stack", valueStateArray(insn.stack()));
				sliceInsns.add(entry);
			}
			sliceJson.add("instructions", sliceInsns);
			json.add("slice", sliceJson);
		}

		if (args.bool("include_instructions", false)) {
			json.add("instructions", McpSupport.page(result.instructions().stream(), args, 100, insn -> {
				JsonObject entry = new JsonObject();
				entry.addProperty("index", insn.index());
				entry.addProperty("opcode", insn.opcode());
				entry.addProperty("reachable", insn.reachable());
				entry.add("successors", intArray(insn.successors()));
				if (!insn.handlers().isEmpty()) {
					JsonArray handlers = new JsonArray();
					for (MethodAnalysis.Handler h : insn.handlers()) {
						JsonObject hj = new JsonObject();
						hj.addProperty("target", h.target());
						hj.addProperty("catchType", h.catchType());
						handlers.add(hj);
					}
					entry.add("handlers", handlers);
				}
				entry.addProperty("mayEscape", insn.mayEscape());
				entry.addProperty("immediateDominator", insn.immediateDominator());
				entry.addProperty("immediatePostDominator", insn.immediatePostDominator());
				entry.add("controlDependencies", intArray(insn.controlDependencies()));
				entry.add("inputs", definitionArray(insn.inputs()));
				entry.add("stack", valueStateArray(insn.stack()));
				entry.add("locals", valueStateArray(insn.locals()));
				return entry;
			}));
		}
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult callSites(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		WorkspaceResource root = args.has("resource")
				? support.resolveResource(args.requireString("resource"))
				: workspace.getPrimaryResource();
		BytecodeInvestigationReport report = investigation.analyze(workspace, root, () -> false);

		Predicate<String> classFilter = support.classNameFilter(args.string("class", null));
		String pkgFilter = args.string("package", null);
		Predicate<String> methodFilter = McpSupport.textFilter(args.string("method", null));
		Predicate<String> targetFilter = McpSupport.textFilter(args.string("target", null));
		String kind = args.string("kind", "all");

		return McpToolResult.json(McpSupport.page(report.callSites().stream()
						.filter(site -> switch (kind) {
							case "all" -> true;
							case "dynamic" -> site.dynamic();
							case "custom_dynamic" -> site.dynamic() && !site.standardBootstrap();
							case "string_call" -> !site.dynamic();
							default -> throw new McpToolException("Unknown kind '" + kind + "'");
						})
						.filter(site -> {
							String owner = site.method().path().getParent().getValue().getName();
							if (classFilter != null && !classFilter.test(owner))
								return false;
							return pkgFilter == null || McpSupport.matchesPackage(owner, true, pkgFilter);
						})
						.filter(site -> methodFilter == null || methodFilter.test(site.method().path().getValue().getName()))
						.filter(site -> targetFilter == null || targetFilter.test(site.targetName())),
				args, 100, site -> {
					JsonObject entry = new JsonObject();
					entry.addProperty("callerClass", site.method().path().getParent().getValue().getName());
					entry.addProperty("callerMethod", site.method().path().getValue().getName());
					entry.addProperty("callerDescriptor", site.method().path().getValue().getDescriptor());
					entry.addProperty("resource", site.method().resource());
					entry.addProperty("instruction", site.instruction());
					entry.addProperty("dynamic", site.dynamic());
					entry.addProperty("standardBootstrap", site.standardBootstrap());
					entry.addProperty("name", site.name());
					entry.addProperty("descriptor", site.descriptor());
					entry.addProperty("target", site.targetName());
					if (!site.bootstrapArguments().isEmpty())
						entry.add("bootstrapArguments", stringArray(site.bootstrapArguments()));
					return entry;
				}));
	}

	@Nonnull
	private McpToolResult evaluate(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		ClassPathNode classPath = support.requireClass(args.requireString("class"), args.string("resource", null));
		if (!classPath.getValue().isJvmClass())
			throw new McpToolException("'" + classPath.getValue().getName() + "' is not a JVM class");
		JvmClassInfo jvmClass = classPath.getValue().asJvmClass();
		ClassMemberPathNode memberPath = support.requireMethod(classPath, args.requireString("method"),
				args.string("descriptor", null));
		MethodMember methodMember = (MethodMember) memberPath.getValue();

		int maxSteps = Math.max(100, Math.min(1_000_000, args.integer("max_steps", DEFAULT_EVAL_STEPS)));
		boolean initializeClasses = args.bool("initialize_classes", false);

		MethodNode methodNode = findMethodNode(jvmClass, methodMember.getName(), methodMember.getDescriptor());
		String resourceLabel = support.resourceLabel(classPath.getValueOfType(WorkspaceResource.class));
		BytecodeInvestigationReport.MethodProfile dummyProfile = new BytecodeInvestigationReport.MethodProfile(
				memberPath, resourceLabel, 0, 0, methodNode.tryCatchBlocks.size(), 0, 0, 0, 0, 0, 0, false);

		if (args.has("instruction")) {
			int targetOrdinal = args.requireInteger("instruction");
			MethodInsnNode call = findCallAtOrdinal(methodNode, targetOrdinal);
			if (call == null)
				throw new McpToolException("Instruction @" + targetOrdinal + " in " + jvmClass.getName() + "." +
						methodMember.getName() + methodMember.getDescriptor() + " is not a method invocation");
			if (call.getOpcode() != Opcodes.INVOKESTATIC)
				throw new McpToolException("Instruction @" + targetOrdinal + " is not INVOKESTATIC (" +
						call.owner + "." + call.name + call.desc + ")");
			BytecodeInvestigationReport.CallSite site = new BytecodeInvestigationReport.CallSite(
					dummyProfile, targetOrdinal, call.name, call.desc,
					new MethodRef(call.owner, call.name, call.desc), List.of(), false, false);
			BytecodeInvestigationReport.Evaluation eval = investigation.evaluate(workspace, site, maxSteps, initializeClasses);
			return McpToolResult.json(describeEvaluation(jvmClass.getName(), methodMember, site, eval, initializeClasses));
		}

		int limit = Math.max(1, Math.min(200, args.integer("limit", 25)));
		List<BytecodeInvestigationReport.CallSite> candidates = new ArrayList<>();
		int ordinal = 0;
		for (AbstractInsnNode insn : methodNode.instructions) {
			if (insn.getOpcode() < 0)
				continue;
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && isStringCall(call)) {
				candidates.add(new BytecodeInvestigationReport.CallSite(
						dummyProfile, ordinal, call.name, call.desc,
						new MethodRef(call.owner, call.name, call.desc), List.of(), false, false));
			}
			ordinal++;
		}

		JsonObject json = new JsonObject();
		json.addProperty("caller", jvmClass.getName() + "." + methodMember.getName() + methodMember.getDescriptor());
		json.addProperty("totalCandidates", candidates.size());
		JsonArray results = new JsonArray();
		for (BytecodeInvestigationReport.CallSite site : candidates.stream().limit(limit).toList()) {
			BytecodeInvestigationReport.Evaluation eval = investigation.evaluate(workspace, site, maxSteps, initializeClasses);
			results.add(describeEvaluation(jvmClass.getName(), methodMember, site, eval, initializeClasses));
		}
		json.addProperty("evaluated", results.size());
		json.add("results", results);
		return McpToolResult.json(json);
	}

	@Nonnull
	private static JsonObject describeEvaluation(@Nonnull String owner, @Nonnull MethodMember caller,
	                                             @Nonnull BytecodeInvestigationReport.CallSite site,
	                                             @Nonnull BytecodeInvestigationReport.Evaluation eval,
	                                             boolean initializeClasses) {
		JsonObject json = new JsonObject();
		json.addProperty("caller", owner + "." + caller.getName() + caller.getDescriptor());
		json.addProperty("instruction", site.instruction());
		json.addProperty("target", site.targetName());
		json.addProperty("status", eval.status().name());
		json.add("arguments", stringArray(eval.arguments()));
		if (eval.value() != null)
			json.addProperty("value", eval.value());
		String explanation = eval.explanation();
		if (eval.status() == BytecodeInvestigationReport.Evaluation.Status.FAILED && !initializeClasses
				&& explanation.toLowerCase(Locale.ROOT).contains("unknown value")) {
			explanation += " (hint: retry with initialize_classes=true if the helper reads static fields initialized in <clinit>)";
		}
		json.addProperty("explanation", explanation);
		json.addProperty("instructionsExecuted", eval.instructions());
		return json;
	}

	@Nonnull
	private static MethodNode findMethodNode(@Nonnull JvmClassInfo jvmClass, @Nonnull String name, @Nonnull String desc) {
		ClassNode node = new ClassNode();
		jvmClass.getClassReader().accept(node, ClassReader.SKIP_DEBUG);
		return node.methods.stream()
				.filter(m -> m.name.equals(name) && m.desc.equals(desc))
				.findFirst()
				.orElseThrow(() -> new McpToolException("Method missing from class bytecode: " + name + desc));
	}

	@Nullable
	private static MethodInsnNode findCallAtOrdinal(@Nonnull MethodNode method, int targetOrdinal) {
		int ordinal = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() < 0)
				continue;
			if (ordinal == targetOrdinal)
				return insn instanceof MethodInsnNode call ? call : null;
			ordinal++;
		}
		throw new McpToolException("Instruction ordinal @" + targetOrdinal + " out of range (method has " + ordinal + " instructions)");
	}

	private static boolean isStringCall(@Nonnull MethodInsnNode call) {
		return !call.owner.startsWith("java/") && !call.owner.startsWith("javax/")
				&& Type.getReturnType(call.desc).equals(Type.getType(String.class))
				&& Type.getArgumentCount(call.desc) > 0;
	}

	@Nonnull
	private static JsonObject describeSummary(@Nonnull MethodAnalysis.Summary summary) {
		JsonObject json = new JsonObject();
		json.addProperty("reachableInstructions", summary.reachableInstructions());
		json.add("returns", intArray(summary.returns()));
		json.add("returnValues", stringArray(summary.returnValues()));
		json.add("explicitThrows", intArray(summary.explicitThrows()));
		json.add("escapingExceptionSites", intArray(summary.escapingExceptionSites()));
		json.add("fieldReads", stringArray(summary.fieldReads()));
		json.add("fieldWrites", stringArray(summary.fieldWrites()));
		json.add("calls", stringArray(summary.calls()));
		json.add("allocations", stringArray(summary.allocations()));
		json.addProperty("arrayWrites", summary.arrayWrites());
		json.addProperty("monitorOperations", summary.monitorOperations());
		JsonObject returnSlice = new JsonObject();
		returnSlice.add("instructions", intArray(summary.returnSlice().instructions()));
		returnSlice.add("entryDefinitions", definitionArray(summary.returnSlice().entryDefinitions()));
		returnSlice.addProperty("dependsOnInitialHeap", summary.returnSlice().dependsOnInitialHeap());
		json.add("returnSlice", returnSlice);
		return json;
	}

	@Nonnull
	private static JsonArray valueStateArray(@Nonnull List<MethodAnalysis.ValueState> states) {
		JsonArray array = new JsonArray();
		for (MethodAnalysis.ValueState state : states) {
			JsonObject item = new JsonObject();
			item.addProperty("slot", state.slot());
			item.addProperty("value", state.value());
			item.add("definitions", definitionArray(state.definitions()));
			array.add(item);
		}
		return array;
	}

	@Nonnull
	private static JsonArray definitionArray(@Nonnull List<MethodAnalysis.Definition> definitions) {
		JsonArray array = new JsonArray();
		definitions.forEach(d -> array.add(d.toString()));
		return array;
	}

	@Nonnull
	private static JsonArray intArray(@Nonnull List<Integer> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	@Nonnull
	private static JsonArray stringArray(@Nonnull List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	private static void merge(@Nonnull JsonObject target, @Nonnull JsonObject source) {
		source.entrySet().forEach(e -> target.add(e.getKey(), e.getValue()));
	}
}
