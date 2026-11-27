package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.analysis.android.AndroidAnalysisService;
import software.coley.recaf.services.analysis.antitamper.AntiReversalAnalysisResult;
import software.coley.recaf.services.analysis.antitamper.AntiReversalAnalysisService;
import software.coley.recaf.services.analysis.antitamper.AntiReversalAnalyzer;
import software.coley.recaf.services.analysis.antitamper.TransformerImpactAnalysis;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationReport;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationService;
import software.coley.recaf.services.analysis.bytecode.ObfuscationTechnologyDetector;
import software.coley.recaf.services.analysis.bytecode.ObfuscatorFingerprinting;
import software.coley.recaf.services.analysis.entry.EntryAnalysisService;
import software.coley.recaf.services.analysis.metadata.FileMetadataAnalysisService;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityReport;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityService;
import software.coley.recaf.services.analysis.plugin.security.SecurityCategory;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.services.analysis.structure.AreaAnalysisService;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.mcp.McpValues;
import software.coley.recaf.services.transform.TransformResult;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * Tools wrapping Recaf's analysis services.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class AnalysisTools implements McpToolProvider {
	private static final int SAMPLE_LIMIT = 15;
	private static final int DEFAULT_BOOTSTRAP_LIMIT = 10;
	private static final int MAX_BOOTSTRAP_LIMIT = 50;
	private static final int NATIVE_CAP = 50;
	private static final int FAILURE_CAP = 50;
	private final McpSupport support;
	private final AntiReversalAnalysisService antiReversal;
	private final EntryAnalysisService entries;
	private final AreaAnalysisService areas;
	private final FileMetadataAnalysisService metadata;
	private final AndroidAnalysisService android;
	private final BytecodeInvestigationService investigation;
	private final ObfuscatorFingerprinting fingerprinting;
	private final ObfuscationTechnologyDetector technologyDetector;
	private final MinecraftPluginAnalysisService pluginAnalysis;
	private final PluginSecurityService pluginSecurity;

	@Inject
	public AnalysisTools(@Nonnull McpSupport support, @Nonnull AntiReversalAnalysisService antiReversal,
	                     @Nonnull EntryAnalysisService entries, @Nonnull AreaAnalysisService areas,
	                     @Nonnull FileMetadataAnalysisService metadata, @Nonnull AndroidAnalysisService android,
	                     @Nonnull BytecodeInvestigationService investigation,
	                     @Nonnull ObfuscatorFingerprinting fingerprinting,
	                     @Nonnull ObfuscationTechnologyDetector technologyDetector,
	                     @Nonnull MinecraftPluginAnalysisService pluginAnalysis,
	                     @Nonnull PluginSecurityService pluginSecurity) {
		this.support = support;
		this.antiReversal = antiReversal;
		this.entries = entries;
		this.areas = areas;
		this.metadata = metadata;
		this.android = android;
		this.investigation = investigation;
		this.fingerprinting = fingerprinting;
		this.technologyDetector = technologyDetector;
		this.pluginAnalysis = pluginAnalysis;
		this.pluginSecurity = pluginSecurity;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("analysis_anti_reversal",
				"Detect anti-reversing measures: illegal class/member names, and what Recaf's transformers would change " +
						"(a sign of obfuscation).",
				McpAccess.READ, McpToolSchema.create(), this::antiReversal);
		registry.add("analysis_entry_points",
				"Find entry points: main methods, plugin main classes (Bukkit, Paper, Bungee, Velocity, Fabric, Forge), " +
						"and Android activities.",
				McpAccess.READ, McpToolSchema.create(), args -> McpToolResult.json(McpValues.toJson(
						entries.findEntryPoints(support.workspace(), support.workspace().getPrimaryResource()))));
		registry.add("analysis_areas",
				"Group classes into areas of tightly coupled code, and report links between areas. " +
						"Useful for mapping out an unfamiliar application.",
				McpAccess.READ, McpToolSchema.create(), args -> McpToolResult.json(McpValues.toJson(
						areas.analyze(support.workspace(), support.workspace().getPrimaryResource()))));
		registry.add("analysis_hashes", "Hashes of the primary file and embedded archives.", McpAccess.READ,
				McpToolSchema.create(), args -> McpToolResult.json(McpValues.toJson(
						metadata.computeHashes(support.workspace(), support.workspace().getPrimaryResource()))));
		registry.add("analysis_jar_signing", "Jar signing report: signature files and signer certificates.", McpAccess.READ,
				McpToolSchema.create(), args -> McpToolResult.json(McpValues.toJson(
						metadata.analyzeJarSigning(support.workspace(), support.workspace().getPrimaryResource()))));
		registry.add("analysis_android_permissions", "Permissions requested by an Android manifest.", McpAccess.READ,
				McpToolSchema.create(), args -> McpToolResult.json(McpValues.toJson(
						android.findRequestedPermissionDetails(support.workspace(), support.workspace().getPrimaryResource()))));
		registry.add("analysis_bytecode_hotspots",
				"Rank methods by how unusual their bytecode is (invokedynamic, reflection, dynamic loading), and summarize " +
						"custom bootstrap groups and native methods/libraries. Good for finding obfuscation machinery.",
				McpAccess.READ,
				McpToolSchema.create()
						.integer("min_score", "Hide methods below this score. Default 0.")
						.string("class", "Filter methods by exact class name, substring (case-insensitive), or 're:<regex>'.")
						.string("package", "Filter methods by package (e.g. 'com/example' or 'com/example/*').")
						.string("resource", "Resource label or embedded path to scan. Defaults to the primary resource.")
						.integer("bootstrap_limit", "Maximum bootstrap groups to include (0..50). Default " + DEFAULT_BOOTSTRAP_LIMIT + ".")
						.bool("include_standard_bootstraps", "Include standard JDK LambdaMetafactory/StringConcatFactory bootstraps. Default false.")
						.paging(),
				this::hotspots);
		registry.add("analysis_obfuscation",
				"Fingerprint obfuscators (Allatori, Bozar, DashO, dProtect, JObf, native-obfuscator, R8, Radon, " +
						"Skidfuscator, Stringer, ZKM) and detect obfuscation techniques (string concealment, numeric noise, " +
						"opaque branches, fake try-catch, dynamic linkage, unusual names), plus suggest transform_run presets.",
				McpAccess.READ,
				McpToolSchema.create()
						.bool("in_depth_control_flow", "Run data-flow analysis on control-flow candidates (slower, detects unreachable code). Default false.")
						.string("resource", "Resource label or embedded path to scan. Defaults to the primary resource."),
				this::obfuscation);
		registry.add("plugin_manifest",
				"Minecraft server plugin manifests (plugin.yml, paper-plugin.yml, bungee.yml, velocity-plugin.json) found in the workspace.",
				McpAccess.READ, McpToolSchema.create(), args -> McpToolResult.json(McpValues.toJson(
						pluginAnalysis.findManifestsRecursive(support.workspace(), support.workspace().getPrimaryResource()))));
		registry.add("plugin_security_scan",
				"Security triage of a Minecraft plugin: backdoor patterns, remote code loading, command execution, " +
						"data exfiltration, obfuscated payloads. Findings are sorted most severe first.",
				McpAccess.READ,
				McpToolSchema.create()
						.enumeration("min_severity", "Hide findings below this severity. Default LOW.",
								List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"))
						.string("category", "Only findings in this SecurityCategory (e.g. EXECUTION, DYNAMIC_CODE, NETWORK, PRIVILEGE, EVASION).")
						.bool("include_libraries", "Include findings inside well known bundled libraries. Default false.")
						.paging(),
				this::securityScan);
	}

	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private McpToolResult antiReversal(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		JsonObject json = new JsonObject();
		for (AntiReversalAnalyzer<?> analyzer : antiReversal.getAnalyzers()) {
			try {
				AntiReversalAnalysisResult result = antiReversal.analyze(workspace, workspace.getPrimaryResource(),
						(AntiReversalAnalyzer) analyzer);
				if (result instanceof TransformerImpactAnalysis impact)
					json.add(analyzer.getServiceId(), describeImpact(impact));
				else
					json.add(analyzer.getServiceId(), McpValues.toJson(result));
			} catch (RuntimeException ex) {
				json.addProperty(analyzer.getServiceId(), "failed: " + ex);
			}
		}
		return McpToolResult.json(json);
	}

	@Nonnull
	private static JsonObject describeImpact(@Nonnull TransformerImpactAnalysis impact) {
		JsonObject json = new JsonObject();
		json.add("jvm", describeTransformSection(impact.jvm().transformerClasses(), impact.jvm().result(),
				impact.jvm().transformError()));
		json.add("android", describeTransformSection(impact.android().transformerClasses(), impact.android().result(),
				impact.android().transformError()));
		return json;
	}

	@Nonnull
	private static JsonObject describeTransformSection(@Nonnull List<? extends Class<?>> transformerClasses,
	                                                 @Nullable TransformResult<?, ?> result,
	                                                 @Nullable Throwable error) {
		JsonObject json = new JsonObject();
		JsonArray transformers = new JsonArray();
		transformerClasses.forEach(c -> transformers.add(c.getSimpleName()));
		json.add("transformers", transformers);
		if (error != null)
			json.addProperty("error", String.valueOf(error));
		if (result != null) {
			json.addProperty("classesTransformed", result.getTransformedClasses().size());
			json.addProperty("classesRemoved", result.getClassesToRemove().size());
			json.addProperty("renames", result.getMappingsToApply().getClasses().size());

			JsonObject perTransformer = new JsonObject();
			for (Map.Entry<?, ? extends Collection<ClassPathNode>> entry :
					result.getModifiedClassesPerTransformer().entrySet()) {
				Class<?> key = (Class<?>) entry.getKey();
				Collection<ClassPathNode> paths = entry.getValue();
				JsonObject detail = new JsonObject();
				detail.addProperty("count", paths.size());
				JsonArray sample = new JsonArray();
				paths.stream()
						.map(p -> p.getValue().getName())
						.sorted()
						.limit(SAMPLE_LIMIT)
						.forEach(sample::add);
				detail.add("sampleClasses", sample);
				perTransformer.add(key.getSimpleName(), detail);
			}
			json.add("classesModifiedPerTransformer", perTransformer);

			int[] failureCount = {0};
			JsonArray sampleFailures = new JsonArray();
			result.getTransformerFailures().forEach((path, perClass) -> perClass.forEach((type, ex) -> {
				failureCount[0]++;
				if (sampleFailures.size() < SAMPLE_LIMIT)
					sampleFailures.add(path.getValue().getName() + " / " + ((Class<?>) type).getSimpleName() + ": " + ex);
			}));
			json.addProperty("failureCount", failureCount[0]);
			if (!sampleFailures.isEmpty())
				json.add("sampleFailures", sampleFailures);
		}
		return json;
	}

	@Nonnull
	private McpToolResult hotspots(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		WorkspaceResource root = args.has("resource")
				? support.resolveResource(args.requireString("resource"))
				: workspace.getPrimaryResource();
		BytecodeInvestigationReport report = investigation.analyze(workspace, root, () -> false);

		int minScore = Math.max(0, args.integer("min_score", 0));
		Predicate<String> classFilter = support.classNameFilter(args.string("class", null));
		String pkgFilter = args.string("package", null);
		int bootstrapLimit = Math.max(0, Math.min(MAX_BOOTSTRAP_LIMIT, args.integer("bootstrap_limit", DEFAULT_BOOTSTRAP_LIMIT)));
		boolean includeStandardBootstraps = args.bool("include_standard_bootstraps", false);

		JsonObject json = McpSupport.page(report.methods().stream()
						.filter(m -> m.score() >= minScore)
						.filter(m -> {
							String owner = m.path().getParent().getValue().getName();
							if (classFilter != null && !classFilter.test(owner))
								return false;
							return pkgFilter == null || McpSupport.matchesPackage(owner, true, pkgFilter);
						})
						.sorted(Comparator.comparingInt(BytecodeInvestigationReport.MethodProfile::score).reversed()),
				args, 50, method -> {
					JsonObject entry = new JsonObject();
					entry.add("method", McpSupport.describePath(method.path()));
					entry.addProperty("resource", method.resource());
					entry.addProperty("score", method.score());
					entry.addProperty("instructions", method.instructions());
					entry.addProperty("branches", method.branches());
					entry.addProperty("handlers", method.handlers());
					entry.addProperty("bitwise", method.bitwise());
					entry.addProperty("dynamicCalls", method.dynamicCalls());
					entry.addProperty("encodedStrings", method.encodedStrings());
					entry.addProperty("cryptoCalls", method.cryptoCalls());
					entry.addProperty("reflectionCalls", method.reflectionCalls());
					entry.addProperty("loadingCalls", method.loadingCalls());
					entry.addProperty("stringHelper", method.stringHelper());
					return entry;
				});
		json.addProperty("classes", report.classes());
		json.addProperty("totalCallSites", report.callSites().size());
		json.addProperty("totalBootstraps", report.bootstraps().size());
		long customBootstrapCount = report.bootstraps().stream()
				.filter(g -> g.sites().isEmpty() || !g.sites().getFirst().standardBootstrap())
				.count();
		json.addProperty("customBootstraps", customBootstrapCount);

		JsonArray bootstraps = new JsonArray();
		for (BytecodeInvestigationReport.BootstrapGroup group : report.bootstraps().stream()
				.filter(g -> includeStandardBootstraps || (!g.sites().isEmpty() && !g.sites().getFirst().standardBootstrap()))
				.limit(bootstrapLimit)
				.toList()) {
			JsonObject g = new JsonObject();
			g.addProperty("target", group.target().owner() + "." + group.target().name() + group.target().desc());
			g.addProperty("resource", group.resource());
			boolean standard = !group.sites().isEmpty() && group.sites().getFirst().standardBootstrap();
			g.addProperty("standard", standard);
			g.addProperty("siteCount", group.sites().size());
			if (group.declaration() != null)
				g.add("declaration", McpSupport.describePath(group.declaration()));
			JsonArray sampleSites = new JsonArray();
			for (BytecodeInvestigationReport.CallSite site : group.sites().stream().limit(3).toList()) {
				JsonObject s = new JsonObject();
				s.addProperty("class", site.method().path().getParent().getValue().getName());
				s.addProperty("member", site.method().path().getValue().getName());
				s.addProperty("descriptor", site.method().path().getValue().getDescriptor());
				s.addProperty("instruction", site.instruction());
				s.addProperty("callName", site.name());
				s.addProperty("callDescriptor", site.descriptor());
				sampleSites.add(s);
			}
			g.add("sampleSites", sampleSites);
			bootstraps.add(g);
		}
		json.add("bootstraps", bootstraps);

		JsonArray nativeLibs = new JsonArray();
		report.nativeLibraries().forEach(nativeLibs::add);
		json.add("nativeLibraries", nativeLibs);

		JsonArray nativeMethods = new JsonArray();
		for (BytecodeInvestigationReport.NativeMethod nm : report.nativeMethods().stream().limit(NATIVE_CAP).toList()) {
			JsonObject entry = new JsonObject();
			entry.add("method", McpSupport.describePath(nm.method().path()));
			entry.addProperty("resource", nm.method().resource());
			entry.addProperty("callerCount", nm.callers().size());
			JsonArray sampleCallers = new JsonArray();
			for (BytecodeInvestigationReport.NativeCaller caller : nm.callers().stream().limit(5).toList()) {
				JsonObject c = McpSupport.describePath(caller.method().path());
				c.addProperty("instruction", caller.instruction());
				sampleCallers.add(c);
			}
			entry.add("sampleCallers", sampleCallers);
			nativeMethods.add(entry);
		}
		json.add("nativeMethods", nativeMethods);

		JsonArray failures = new JsonArray();
		report.failures().stream().limit(FAILURE_CAP).forEach(failures::add);
		json.add("failures", failures);
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult obfuscation(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		WorkspaceResource root = args.has("resource")
				? support.resolveResource(args.requireString("resource"))
				: workspace.getPrimaryResource();
		boolean inDepth = args.bool("in_depth_control_flow", false);

		ObfuscatorFingerprinting.Result fp = fingerprinting.scan(root, () -> false);
		ObfuscationTechnologyDetector.Result tech = technologyDetector.scan(root, inDepth, () -> false);

		JsonObject fpJson = new JsonObject();
		fpJson.addProperty("classesRead", fp.classesRead());
		fpJson.addProperty("classesSkipped", fp.classesSkipped());
		JsonArray fpMatches = new JsonArray();
		Set<String> suggestedPresets = new LinkedHashSet<>();
		for (ObfuscatorFingerprinting.Match match : fp.matches()) {
			JsonObject m = new JsonObject();
			m.addProperty("resource", match.resource());
			m.addProperty("family", match.family().name());
			m.addProperty("familyName", match.family().displayName());
			m.addProperty("confidencePercent", match.confidencePercent());
			m.addProperty("confidence", match.confidence().name());
			JsonArray ev = new JsonArray();
			for (ObfuscatorFingerprinting.Evidence e : match.evidence()) {
				JsonObject ej = new JsonObject();
				ej.addProperty("indicator", e.indicator().name());
				ej.addProperty("occurrences", e.occurrences());
				JsonArray ex = new JsonArray();
				e.examples().forEach(ex::add);
				ej.add("examples", ex);
				ev.add(ej);
			}
			m.add("evidence", ev);
			fpMatches.add(m);
			if (match.family() == ObfuscatorFingerprinting.Family.SKIDFUSCATOR)
				suggestedPresets.add("skidfuscator");
		}
		fpJson.add("matches", fpMatches);

		JsonObject techJson = new JsonObject();
		techJson.addProperty("classesRead", tech.classesRead());
		techJson.addProperty("classesSkipped", tech.classesSkipped());
		JsonObject cf = new JsonObject();
		cf.addProperty("methodsAnalyzed", tech.controlFlow().methodsAnalyzed());
		cf.addProperty("methodsSkipped", tech.controlFlow().methodsSkipped());
		techJson.add("controlFlow", cf);
		JsonArray techMatches = new JsonArray();
		for (ObfuscationTechnologyDetector.Match match : tech.matches()) {
			JsonObject m = new JsonObject();
			m.addProperty("resource", match.resource());
			m.addProperty("technology", match.technology().name());
			m.addProperty("occurrences", match.occurrences());
			JsonArray ex = new JsonArray();
			match.examples().forEach(ex::add);
			m.add("examples", ex);
			techMatches.add(m);
			switch (match.technology()) {
				case FAKE_TRY_CATCH, ALGEBRAIC_OPAQUE_PREDICATES, STRING_CONCEALMENT, DYNAMIC_LINKAGE ->
						suggestedPresets.add("obfuscated-plugin");
				case OPAQUE_BRANCHES, UNREACHABLE_CODE -> suggestedPresets.add("control-flow");
				case NUMERIC_OBFUSCATION -> suggestedPresets.add("bitwise");
				default -> {}
			}
		}
		techJson.add("matches", techMatches);

		JsonObject json = new JsonObject();
		json.add("fingerprints", fpJson);
		json.add("technologies", techJson);
		JsonArray presets = new JsonArray();
		suggestedPresets.forEach(presets::add);
		json.add("suggestedPresets", presets);
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult securityScan(@Nonnull McpArgs args) throws InterruptedException {
		Workspace workspace = support.workspace();
		PluginSecurityReport report;
		try {
			report = pluginSecurity.scanAsync(workspace).get(5, TimeUnit.MINUTES);
		} catch (TimeoutException ex) {
			throw new McpToolException("The security scan timed out");
		} catch (ExecutionException ex) {
			throw new McpToolException("The security scan failed: " + ex.getCause());
		}
		Severity min = Severity.LOW;
		if (args.has("min_severity")) {
			try {
				min = Severity.valueOf(args.requireString("min_severity").toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ex) {
				throw new McpToolException("Unknown severity '" + args.string("min_severity", "") + "'");
			}
		}
		SecurityCategory category = null;
		if (args.has("category")) {
			try {
				category = SecurityCategory.valueOf(args.requireString("category").toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ex) {
				throw new McpToolException("Unknown category '" + args.string("category", "") +
						"'. Options: " + java.util.Arrays.toString(SecurityCategory.values()));
			}
		}
		Severity finalMin = min;
		SecurityCategory finalCategory = category;
		boolean libraries = args.bool("include_libraries", false);
		JsonObject json = McpSupport.page(report.findings().stream()
						.filter(f -> f.severity().isAtLeast(finalMin)
								&& (libraries || !f.library())
								&& (finalCategory == null || f.rule().category() == finalCategory)),
				args, 50,
				(SecurityFinding finding) -> (JsonElement) describeFinding(finding));
		json.addProperty("classesScanned", report.classesScanned());
		json.addProperty("filesScanned", report.filesScanned());
		JsonObject counts = new JsonObject();
		report.counts(libraries).forEach((severity, count) -> counts.addProperty(severity.name(), count));
		json.add("countsBySeverity", counts);
		json.add("embeddedJars", McpValues.toJson(report.embeddedJars()));
		return McpToolResult.json(json);
	}

	@Nonnull
	private static JsonObject describeFinding(@Nonnull SecurityFinding finding) {
		JsonObject json = new JsonObject();
		json.addProperty("rule", finding.rule().name());
		json.addProperty("severity", finding.severity().name());
		json.addProperty("category", finding.rule().category().name());
		json.addProperty("combined", finding.rule().isCombined());
		json.addProperty("library", finding.library());
		if (finding.location() != null)
			json.add("location", McpValues.toJson(finding.location()));
		if (finding.detail() != null)
			json.addProperty("detail", finding.detail());
		if (finding.file() != null)
			json.addProperty("file", finding.file());
		if (finding.embeddedIn() != null)
			json.addProperty("embeddedIn", finding.embeddedIn());
		if (!finding.evidence().isEmpty()) {
			JsonArray evidence = new JsonArray();
			for (SecurityFinding part : finding.evidence())
				evidence.add(describeFinding(part));
			json.add("evidence", evidence);
		}
		return json;
	}
}
