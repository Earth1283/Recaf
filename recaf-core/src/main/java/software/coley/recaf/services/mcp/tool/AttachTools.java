package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.tools.attach.VirtualMachineDescriptor;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.services.attach.AttachManager;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.resource.WorkspaceRemoteVmResource;

import java.io.IOException;

/**
 * Tools for inspecting other JVM processes on this machine. Attaching loads an agent into the target process,
 * which is why these require the execute tier.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class AttachTools implements McpToolProvider {
	private final McpSupport support;
	private final AttachManager attachManager;

	@Inject
	public AttachTools(@Nonnull McpSupport support, @Nonnull AttachManager attachManager) {
		this.support = support;
		this.attachManager = attachManager;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("attach_list", "List the other JVM processes that can be attached to.", McpAccess.EXECUTE,
				McpToolSchema.create(), this::list);
		registry.add("attach_open",
				"Attach to a running JVM by pid and open its loaded classes as the current workspace. " +
						"This loads an agent into that process.",
				McpAccess.EXECUTE,
				McpToolSchema.create().requiredInteger("pid", "Process id from attach_list."), this::open);
	}

	@Nonnull
	private McpToolResult list(@Nonnull McpArgs args) {
		if (!attachManager.canAttach())
			throw new McpToolException("Attaching is not available in this runtime (needs a JDK with jdk.attach)");
		attachManager.scan();
		JsonArray array = new JsonArray();
		for (VirtualMachineDescriptor descriptor : attachManager.getVirtualMachineDescriptors()) {
			JsonObject json = new JsonObject();
			json.addProperty("pid", attachManager.getVirtualMachinePid(descriptor));
			json.addProperty("name", descriptor.displayName());
			String main = attachManager.getVirtualMachineMainClass(descriptor);
			if (main != null)
				json.addProperty("mainClass", main);
			array.add(json);
		}
		return McpToolResult.json(array);
	}

	@Nonnull
	private McpToolResult open(@Nonnull McpArgs args) throws IOException {
		if (!attachManager.canAttach())
			throw new McpToolException("Attaching is not available in this runtime (needs a JDK with jdk.attach)");
		int pid = args.requireInteger("pid");
		attachManager.scan();
		VirtualMachineDescriptor target = null;
		for (VirtualMachineDescriptor descriptor : attachManager.getVirtualMachineDescriptors())
			if (attachManager.getVirtualMachinePid(descriptor) == pid)
				target = descriptor;
		if (target == null)
			throw new McpToolException("No attachable JVM with pid " + pid + ". See attach_list.");
		WorkspaceRemoteVmResource resource = attachManager.createRemoteResource(target);
		resource.connect();
		if (!support.workspaceManager().setCurrent(new BasicWorkspace(resource)))
			throw new McpToolException("The current workspace could not be closed to open the remote VM");
		JsonObject json = new JsonObject();
		json.addProperty("attached", pid);
		return McpToolResult.json(json);
	}
}
