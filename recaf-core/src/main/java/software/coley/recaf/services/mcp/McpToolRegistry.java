package software.coley.recaf.services.mcp;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the tools from every {@link McpToolProvider}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class McpToolRegistry {
	private static final Logger logger = Logging.get(McpToolRegistry.class);
	private final Instance<McpToolProvider> providers;
	private Map<String, McpTool> tools;

	@Inject
	public McpToolRegistry(@Nonnull Instance<McpToolProvider> providers) {
		this.providers = providers;
	}

	/**
	 * @param name
	 * 		Tool name.
	 * @param description
	 * 		Description shown to the model.
	 * @param access
	 * 		Capability tier.
	 * @param schema
	 * 		Argument schema.
	 * @param handler
	 * 		Implementation.
	 */
	public void add(@Nonnull String name, @Nonnull String description, @Nonnull McpAccess access,
	                @Nonnull McpToolSchema schema, @Nonnull McpTool.Handler handler) {
		add(new McpTool(name, description, access, schema.build(), handler));
	}

	/**
	 * @param tool
	 * 		Tool to add.
	 */
	public synchronized void add(@Nonnull McpTool tool) {
		if (tools == null)
			throw new IllegalStateException("Tools can only be added from McpToolProvider#registerTools");
		if (tools.putIfAbsent(tool.name(), tool) != null)
			logger.warn("Ignoring duplicate MCP tool '{}'", tool.name());
	}

	/**
	 * @param allowed
	 * 		Tiers the server permits.
	 *
	 * @return All tools in the permitted tiers, sorted by name.
	 */
	@Nonnull
	public synchronized List<McpTool> getTools(@Nonnull java.util.Set<McpAccess> allowed) {
		if (tools == null) {
			tools = new LinkedHashMap<>();
			for (McpToolProvider provider : providers) {
				try {
					provider.registerTools(this);
				} catch (Throwable t) {
					logger.error("MCP tool provider '{}' failed to register", provider.getClass().getName(), t);
				}
			}
		}
		List<McpTool> list = new ArrayList<>();
		for (McpTool tool : tools.values())
			if (allowed.contains(tool.access()))
				list.add(tool);
		list.sort(Comparator.comparing(McpTool::name));
		return list;
	}
}
