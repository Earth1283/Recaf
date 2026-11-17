package software.coley.recaf.services.analysis.flow;

import jakarta.enterprise.context.ApplicationScoped;
import software.coley.recaf.workspace.model.Workspace;

import java.util.function.BooleanSupplier;

/** Stateless entry point. Call off the UI thread; each result owns its analyses and exact input snapshot. */
@ApplicationScoped
public class ExecutionAnalysisService {
	public ExecutionAnalysis analyze(Workspace workspace, BooleanSupplier cancelled) {
		return ExecutionAnalysis.scan(workspace, cancelled);
	}
}
