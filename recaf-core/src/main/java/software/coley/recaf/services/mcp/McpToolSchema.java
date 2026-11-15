package software.coley.recaf.services.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Builder for the JSON schema describing a tool's arguments.
 *
 * @author Matt Coley
 */
public class McpToolSchema {
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	/**
	 * @return New empty schema, for tools that take no arguments.
	 */
	@Nonnull
	public static McpToolSchema create() {
		return new McpToolSchema();
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema string(@Nonnull String name, @Nonnull String description) {
		return add(name, "string", description, false);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema requiredString(@Nonnull String name, @Nonnull String description) {
		return add(name, "string", description, true);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema integer(@Nonnull String name, @Nonnull String description) {
		return add(name, "integer", description, false);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema requiredInteger(@Nonnull String name, @Nonnull String description) {
		return add(name, "integer", description, true);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema bool(@Nonnull String name, @Nonnull String description) {
		return add(name, "boolean", description, false);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema object(@Nonnull String name, @Nonnull String description) {
		return add(name, "object", description, false);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema stringArray(@Nonnull String name, @Nonnull String description) {
		return addArray(name, description, false);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema requiredStringArray(@Nonnull String name, @Nonnull String description) {
		return addArray(name, description, true);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param description
	 * 		What the argument means.
	 * @param values
	 * 		Allowed values.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema enumeration(@Nonnull String name, @Nonnull String description, @Nonnull List<String> values) {
		add(name, "string", description, false);
		JsonArray allowed = new JsonArray();
		values.forEach(allowed::add);
		properties.getAsJsonObject(name).add("enum", allowed);
		return this;
	}

	/**
	 * Adds the standard {@code offset} and {@code limit} arguments used by tools which page their results.
	 *
	 * @return Self.
	 */
	@Nonnull
	public McpToolSchema paging() {
		return integer("offset", "Number of results to skip. Default 0.")
				.integer("limit", "Maximum number of results to return.");
	}

	/**
	 * @return JSON schema object.
	 */
	@Nonnull
	public JsonObject build() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		if (!required.isEmpty())
			schema.add("required", required);
		return schema;
	}

	@Nonnull
	private McpToolSchema add(@Nonnull String name, @Nonnull String type, @Nullable String description, boolean isRequired) {
		JsonObject property = new JsonObject();
		property.addProperty("type", type);
		if (description != null)
			property.addProperty("description", description);
		properties.add(name, property);
		if (isRequired)
			required.add(name);
		return this;
	}

	@Nonnull
	private McpToolSchema addArray(@Nonnull String name, @Nonnull String description, boolean isRequired) {
		add(name, "array", description, isRequired);
		JsonObject items = new JsonObject();
		items.addProperty("type", "string");
		properties.getAsJsonObject(name).add("items", items);
		return this;
	}
}
