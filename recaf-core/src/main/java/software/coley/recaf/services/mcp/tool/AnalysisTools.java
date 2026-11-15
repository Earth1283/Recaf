package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.services.analysis.android.AndroidAnalysisService;
import software.coley.recaf.services.analysis.antitamper.AntiReversalAnalysisResult;
import software.coley.recaf.services.analysis.antitamper.AntiReversalAnalysisService;
import software.coley.recaf.services.analysis.antitamper.AntiReversalAnalyzer;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationReport;
import software.coley.recaf.services.analysis.bytecode.BytecodeInvestigationService;
import software.coley.recaf.services.analysis.entry.EntryAnalysisService;
import software.coley.recaf.services.analysis.metadata.FileMetadataAnalysisService;
import software.coley.recaf.services.analysis.plugin.MinecraftPluginAnalysisService;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityReport;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityService;
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
import software.coley.recaf.workspace.model.Workspace;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tools wrapping Recaf's analysis services.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class AnalysisTools implements McpToolProvider {
	private final McpSupport support;
	private final AntiReversalAnalysisService antiReversal;
	private final EntryAnalysisService entries;
	private final AreaAnalysisService areas;
	private final FileMetadataAnalysisService metadata;
	private final AndroidAnalysisService android;
	private final BytecodeInvestigationService investigation;
	private final MinecraftPluginAnalysisService pluginAnalysis;
	private final PluginSecurityService pluginSecurity;

	@Inject
	public AnalysisTools(@Nonnull McpSupport support, @Nonnull AntiReversalAnalysisService antiReversal,
	                     @Nonnull EntryAnalysisService entries, @Nonnull AreaAnalysisService areas,
	                     @Nonnull FileMetadataAnalysisService metadata, @Nonnull AndroidAnalysisService android,
	                     @Nonnull BytecodeInvestigationService investigation,
	                     @Nonnull MinecraftPluginAnalysisService pluginAnalysis,
	                     @Nonnull PluginSecurityService pluginSecurity) {
		this.support = support;
		this.antiReversal = antiReversal;
		this.entries = entries;
		this.areas = areas;
		this.metadata = metadata;
		this.android = android;
		this.investigation = investigation;
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
				"Rank methods by how unusual their bytecode is (invokedynamic, reflection, dynamic loading), and list " +
						"custom bootstrap groups. Good for finding obfuscation machinery.",
				McpAccess.READ, McpToolSchema.create().paging(), this::hotspots);
		registry.add("plugin_manifest",
				"Minecraft plugin manifests (plugin.yml, paper-plugin.yml, bungee.yml, velocity-plugin.json, " +
						"fabric.mod.json, mods.toml) found in the workspace.",
				McpAccess.READ, McpToolSchema.create(), args -> McpToolResult.json(McpValues.toJson(
						pluginAnalysis.findManifestsRecursive(support.workspace(), support.workspace().getPrimaryResource()))));
		registry.add("plugin_security_scan",
				"Security triage of a Minecraft plugin: backdoor patterns, remote code loading, command execution, " +
						"data exfiltration, obfuscated payloads. Findings are sorted most severe first.",
				McpAccess.READ,
				McpToolSchema.create()
						.enumeration("min_severity", "Hide findings below this severity. Default LOW.",
								List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"))
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
				json.add(analyzer.getServiceId(), McpValues.toJson(result));
			} catch (RuntimeException ex) {
				json.addProperty(analyzer.getServiceId(), "failed: " + ex);
			}
		}
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult hotspots(@Nonnull McpArgs args) {
		Workspace workspace = support.workspace();
		BytecodeInvestigationReport report = investigation.analyze(workspace, workspace.getPrimaryResource(), () -> false);
		JsonObject json = McpSupport.page(report.methods().stream()
						.sorted(Comparator.comparingInt(BytecodeInvestigationReport.MethodProfile::score).reversed()),
				args, 50, method -> {
					JsonObject entry = new JsonObject();
					entry.add("method", McpValues.toJson(method.path()));
					entry.addProperty("score", method.score());
					return entry;
				});
		json.addProperty("classes", report.classes());
		json.add("bootstraps", McpValues.toJson(report.bootstraps()));
		json.add("failures", McpValues.toJson(report.failures()));
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
		Severity finalMin = min;
		boolean libraries = args.bool("include_libraries", false);
		JsonObject json = McpSupport.page(report.findings().stream()
				.filter(f -> f.severity().isAtLeast(finalMin) && (libraries || !f.library())), args, 50,
				(SecurityFinding finding) -> (JsonElement) McpValues.toJson(finding));
		json.addProperty("classesScanned", report.classesScanned());
		json.addProperty("filesScanned", report.filesScanned());
		JsonObject counts = new JsonObject();
		report.counts(libraries).forEach((severity, count) -> counts.addProperty(severity.name(), count));
		json.add("countsBySeverity", counts);
		json.add("embeddedJars", McpValues.toJson(report.embeddedJars()));
		return McpToolResult.json(json);
	}
}
