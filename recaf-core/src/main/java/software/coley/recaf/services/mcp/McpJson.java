package software.coley.recaf.services.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Nonnull;

import java.util.Map;

/**
 * JSON helpers shared by tools and the transport adapters.
 * <p/>
 * The MCP SDK models JSON as plain maps, tools use Gson trees. These conversions are the only place the two meet.
 *
 * @author Matt Coley
 */
public final class McpJson {
	/** Gson that does not escape HTML characters, which would otherwise mangle decompiled source and descriptors. */
	public static final Gson GSON = new GsonBuilder()
			.disableHtmlEscaping()
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.create();

	private McpJson() {}

	/**
	 * @param map
	 * 		SDK arguments map.
	 *
	 * @return Equivalent JSON object.
	 */
	@Nonnull
	public static JsonObject toObject(Map<String, Object> map) {
		if (map == null)
			return new JsonObject();
		return GSON.toJsonTree(map).getAsJsonObject();
	}

	/**
	 * @param object
	 * 		JSON object.
	 *
	 * @return Equivalent map, with integral numbers as {@code long}.
	 */
	@Nonnull
	public static Map<String, Object> toMap(@Nonnull JsonObject object) {
		return GSON.fromJson(object, new TypeToken<Map<String, Object>>() {}.getType());
	}

	/**
	 * @return New empty array.
	 */
	@Nonnull
	public static JsonArray array() {
		return new JsonArray();
	}

	/**
	 * @param element
	 * 		Value to serialize.
	 *
	 * @return Compact JSON text.
	 */
	@Nonnull
	public static String toString(@Nonnull JsonElement element) {
		return GSON.toJson(element);
	}
}
