package software.coley.recaf.services.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Converts the result models returned by Recaf's services into JSON.
 * <p/>
 * Most analysis results are records of plain values and workspace paths, so a generic conversion covers them
 * without a hand written mapping per result type. Workspace content is reduced to names, and size is bounded so one
 * result cannot flood the model's context.
 *
 * @author Matt Coley
 */
public final class McpValues {
	private static final int MAX_DEPTH = 7;
	private static final int MAX_COLLECTION = 500;
	private static final int MAX_STRING = 2000;

	private McpValues() {}

	/**
	 * @param value
	 * 		Any value.
	 *
	 * @return JSON form.
	 */
	@Nonnull
	public static JsonElement toJson(@Nullable Object value) {
		return convert(value, 0);
	}

	@Nonnull
	private static JsonElement convert(@Nullable Object value, int depth) {
		if (value == null)
			return JsonNull.INSTANCE;
		if (value instanceof JsonElement element)
			return element;
		if (value instanceof CharSequence text)
			return new JsonPrimitive(clip(text.toString()));
		if (value instanceof Number number)
			return new JsonPrimitive(number);
		if (value instanceof Boolean bool)
			return new JsonPrimitive(bool);
		if (value instanceof Character c)
			return new JsonPrimitive(c.toString());
		if (value instanceof Enum<?> e)
			return new JsonPrimitive(e.name());
		if (value instanceof PathNode<?> path)
			return McpSupport.describePath(path);
		if (value instanceof ClassInfo info)
			return new JsonPrimitive(info.getName());
		if (value instanceof FileInfo info)
			return new JsonPrimitive(info.getName());
		if (value instanceof ClassMember member)
			return new JsonPrimitive(member.getName() + member.getDescriptor());
		if (value instanceof WorkspaceResource)
			return new JsonPrimitive(value.getClass().getSimpleName());
		if (depth >= MAX_DEPTH)
			return new JsonPrimitive(clip(String.valueOf(value)));
		if (value instanceof Optional<?> optional)
			return optional.map(o -> convert(o, depth)).orElse(JsonNull.INSTANCE);
		if (value instanceof Map<?, ?> map) {
			JsonObject json = new JsonObject();
			int count = 0;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (count++ >= MAX_COLLECTION) {
					json.addProperty("...", "truncated");
					break;
				}
				json.add(String.valueOf(entry.getKey()), convert(entry.getValue(), depth + 1));
			}
			return json;
		}
		if (value instanceof Iterable<?> iterable)
			return array(iterable, depth, iterable instanceof Collection<?> c ? c.size() : -1);
		if (value.getClass().isArray()) {
			int length = Array.getLength(value);
			JsonArray array = new JsonArray();
			for (int i = 0; i < Math.min(length, MAX_COLLECTION); i++)
				array.add(convert(Array.get(value, i), depth + 1));
			return array;
		}
		if (value.getClass().isRecord()) {
			JsonObject json = new JsonObject();
			for (RecordComponent component : value.getClass().getRecordComponents()) {
				try {
					var accessor = component.getAccessor();
					accessor.setAccessible(true);
					json.add(component.getName(), convert(accessor.invoke(value), depth + 1));
				} catch (ReflectiveOperationException | RuntimeException ex) {
					json.addProperty(component.getName(), "<unavailable>");
				}
			}
			return json;
		}
		return new JsonPrimitive(clip(String.valueOf(value)));
	}

	@Nonnull
	private static JsonArray array(@Nonnull Iterable<?> iterable, int depth, int size) {
		JsonArray array = new JsonArray();
		for (Object item : iterable) {
			if (array.size() >= MAX_COLLECTION) {
				array.add("... " + (size < 0 ? "more items" : (size - MAX_COLLECTION) + " more") + " truncated");
				break;
			}
			array.add(convert(item, depth + 1));
		}
		return array;
	}

	@Nonnull
	private static String clip(@Nonnull String text) {
		return text.length() <= MAX_STRING ? text : text.substring(0, MAX_STRING) + "... [" + text.length() + " chars]";
	}
}
