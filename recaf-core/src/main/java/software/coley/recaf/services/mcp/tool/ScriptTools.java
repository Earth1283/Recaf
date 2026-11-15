package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.script.ScriptEngine;
import software.coley.recaf.services.script.ScriptResult;
import software.coley.recaf.util.StringUtil;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs Recaf scripts. A script is arbitrary Java with access to every Recaf service, so these tools require the
 * execute tier.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class ScriptTools implements McpToolProvider {
	private final ScriptEngine engine;

	@Inject
	public ScriptTools(@Nonnull ScriptEngine engine) {
		this.engine = engine;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("script_run",
				"Compile and run a Recaf script: Java source whose top-level statements run with every Recaf service " +
						"injectable (see Recaf's script documentation). Arbitrary code execution inside the Recaf process.",
				McpAccess.EXECUTE,
				McpToolSchema.create()
						.requiredString("source", "Script source.")
						.integer("timeout_seconds", "Give up waiting after this long. Default 120."),
				this::run);
	}

	@Nonnull
	private McpToolResult run(@Nonnull McpArgs args) throws InterruptedException {
		int timeout = Math.max(1, args.integer("timeout_seconds", 120));
		ScriptResult result;
		try {
			result = engine.run(args.requireString("source")).get(timeout, TimeUnit.SECONDS);
		} catch (TimeoutException ex) {
			throw new McpToolException("The script did not finish within " + timeout + " seconds. It may still be running.");
		} catch (ExecutionException ex) {
			throw new McpToolException("Script engine failure: " + ex.getCause());
		}
		JsonObject json = new JsonObject();
		json.addProperty("success", result.wasSuccess());
		if (result.wasCancelled())
			json.addProperty("cancelled", true);
		if (result.wasCompileFailure()) {
			JsonArray diagnostics = new JsonArray();
			for (CompilerDiagnostic diagnostic : result.getCompileDiagnostics())
				diagnostics.add(diagnostic.toString());
			json.add("compileErrors", diagnostics);
		}
		if (result.wasRuntimeError())
			json.addProperty("runtimeError", StringUtil.traceToString(result.getRuntimeThrowable()));
		return McpToolResult.json(json);
	}
}
