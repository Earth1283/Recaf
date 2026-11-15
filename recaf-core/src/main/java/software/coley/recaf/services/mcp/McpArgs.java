package software.coley.recaf.services.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed view of the arguments a client passed to a tool.
 * <p/>
 * Clients, especially language models, are loose with types, so numeric and boolean values are accepted in their
 * string form too.
 *
 * @author Matt Coley
 */
public class McpArgs {
	/** Hard ceiling for any paged result so one call cannot flood the model's context. */
	public static final int MAX_LIMIT = 2000;
	private final JsonObject json;

	/**
	 * @param json
	 * 		Raw arguments.
	 */
	public McpArgs(@Nonnull JsonObject json) {
		this.json = json;
	}

	/**
	 * @return Raw arguments.
	 */
	@Nonnull
	public JsonObject raw() {
		return json;
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return {@code true} when the argument was passed and is not {@code null}.
	 */
	public boolean has(@Nonnull String name) {
		JsonElement element = json.get(name);
		return element != null && !element.isJsonNull();
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return String value.
	 *
	 * @throws McpToolException
	 * 		When the argument is missing.
	 */
	@Nonnull
	public String requireString(@Nonnull String name) {
		String value = string(name, null);
		if (value == null)
			throw new McpToolException("Missing required argument '" + name + "'");
		return value;
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param fallback
	 * 		Value when absent.
	 *
	 * @return String value.
	 */
	@Nullable
	public String string(@Nonnull String name, @Nullable String fallback) {
		if (!has(name))
			return fallback;
		JsonElement element = json.get(name);
		if (element.isJsonPrimitive())
			return element.getAsString();
		return McpJson.toString(element);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param fallback
	 * 		Value when absent.
	 *
	 * @return Integer value.
	 */
	public int integer(@Nonnull String name, int fallback) {
		if (!has(name))
			return fallback;
		try {
			JsonElement element = json.get(name);
			return (int) Math.round(element.getAsDouble());
		} catch (RuntimeException ex) {
			throw new McpToolException("Argument '" + name + "' must be an integer");
		}
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return Integer value.
	 *
	 * @throws McpToolException
	 * 		When the argument is missing.
	 */
	public int requireInteger(@Nonnull String name) {
		if (!has(name))
			throw new McpToolException("Missing required argument '" + name + "'");
		return integer(name, 0);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param fallback
	 * 		Value when absent.
	 *
	 * @return Boolean value.
	 */
	public boolean bool(@Nonnull String name, boolean fallback) {
		if (!has(name))
			return fallback;
		try {
			return json.get(name).getAsBoolean();
		} catch (RuntimeException ex) {
			throw new McpToolException("Argument '" + name + "' must be a boolean");
		}
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return List of strings, empty when absent. A lone string is treated as a one element list.
	 */
	@Nonnull
	public List<String> strings(@Nonnull String name) {
		List<String> list = new ArrayList<>();
		if (!has(name))
			return list;
		JsonElement element = json.get(name);
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			for (JsonElement item : array)
				list.add(item.isJsonPrimitive() ? item.getAsString() : McpJson.toString(item));
		} else {
			list.add(element.getAsString());
		}
		return list;
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return Object value, empty when absent.
	 */
	@Nonnull
	public JsonObject object(@Nonnull String name) {
		if (!has(name))
			return new JsonObject();
		JsonElement element = json.get(name);
		if (!element.isJsonObject())
			throw new McpToolException("Argument '" + name + "' must be an object");
		return element.getAsJsonObject();
	}

	/**
	 * @return Value of the {@code offset} argument, at least zero.
	 */
	public int offset() {
		return Math.max(0, integer("offset", 0));
	}

	/**
	 * @param fallback
	 * 		Limit when the client did not specify one.
	 *
	 * @return Value of the {@code limit} argument, clamped to {@code 1..}{@link #MAX_LIMIT}.
	 */
	public int limit(int fallback) {
		return Math.max(1, Math.min(MAX_LIMIT, integer("limit", fallback)));
	}
}
