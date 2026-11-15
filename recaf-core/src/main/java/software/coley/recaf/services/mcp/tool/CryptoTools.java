package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.util.crypto.AutoDecoder;
import software.coley.recaf.util.crypto.ByteFormat;
import software.coley.recaf.util.crypto.Codec;
import software.coley.recaf.util.crypto.CryptoException;
import software.coley.recaf.util.crypto.DataInspector;
import software.coley.recaf.util.crypto.HashAlgorithm;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Encoding, hashing and data inspection helpers for unpicking embedded blobs and encrypted strings.
 * These are pure computations on the given input and never touch the workspace.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class CryptoTools implements McpToolProvider {
	private static final int PREVIEW_CHARS = 4000;

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		List<String> formats = Arrays.stream(ByteFormat.values()).map(Enum::name).toList();
		registry.add("crypto_inspect",
				"Describe a blob: size, entropy, file signature, likely text-ness and plausible encodings.",
				McpAccess.READ, input(formats), this::inspect);
		registry.add("crypto_auto_decode",
				"Try to peel back layers of encoding and compression (base64, hex, gzip, zlib, rot13, ...) until " +
						"readable data appears. Input that already reads as plain text is left alone, use crypto_codec to " +
						"force a specific decoding.",
				McpAccess.READ, input(formats), this::autoDecode);
		registry.add("crypto_codec",
				"Encode or decode with one codec: " + Arrays.stream(Codec.values()).map(Enum::name).toList() + ".",
				McpAccess.READ,
				input(formats)
						.enumeration("codec", "Codec name.", Arrays.stream(Codec.values()).map(Enum::name).toList())
						.enumeration("direction", "Default decode.", List.of("decode", "encode")),
				this::codec);
		registry.add("crypto_hash",
				"Hash or checksum of the input.",
				McpAccess.READ,
				input(formats).enumeration("algorithm", "Algorithm name.",
						Arrays.stream(HashAlgorithm.values()).map(Enum::name).toList()),
				this::hash);
	}

	@Nonnull
	private static McpToolSchema input(@Nonnull List<String> formats) {
		return McpToolSchema.create()
				.requiredString("input", "The data, written in 'input_format'.")
				.enumeration("input_format", "How 'input' is written. Default UTF8.", formats);
	}

	@Nonnull
	private McpToolResult inspect(@Nonnull McpArgs args) {
		byte[] data = data(args);
		JsonObject json = new JsonObject();
		json.addProperty("bytes", data.length);
		json.addProperty("entropy", DataInspector.entropy(data));
		String signature = DataInspector.detectSignature(data);
		if (signature != null)
			json.addProperty("signature", signature);
		DataInspector.Score score = DataInspector.score(data);
		json.addProperty("looksLike", score.kind());
		json.addProperty("readability", score.value());
		JsonArray encodings = new JsonArray();
		DataInspector.guessEncodings(data).forEach(c -> encodings.add(c.name()));
		json.add("plausibleEncodings", encodings);
		json.addProperty("preview", DataInspector.preview(data, 400));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult autoDecode(@Nonnull McpArgs args) {
		AutoDecoder.Result result = AutoDecoder.decode(data(args));
		JsonObject json = new JsonObject();
		json.addProperty("found", result.found());
		JsonArray steps = new JsonArray();
		result.steps().forEach(step -> steps.add(step.toString()));
		json.add("steps", steps);
		json.addProperty("kind", result.score().kind());
		json.addProperty("outputBytes", result.output().length);
		json.addProperty("preview", DataInspector.preview(result.output(), PREVIEW_CHARS));
		json.addProperty("outputHex", hex(result.output()));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult codec(@Nonnull McpArgs args) {
		Codec codec = enumValue(Codec.class, args.requireString("codec"));
		byte[] data = data(args);
		byte[] output;
		try {
			output = "encode".equals(args.string("direction", "decode")) ? codec.encode(data) : codec.decode(data);
		} catch (CryptoException ex) {
			throw new McpToolException(ex.getMessage());
		}
		JsonObject json = new JsonObject();
		json.addProperty("outputBytes", output.length);
		json.addProperty("preview", DataInspector.preview(output, PREVIEW_CHARS));
		json.addProperty("outputHex", hex(output));
		return McpToolResult.json(json);
	}

	@Nonnull
	private McpToolResult hash(@Nonnull McpArgs args) {
		HashAlgorithm algorithm = enumValue(HashAlgorithm.class, args.requireString("algorithm"));
		JsonObject json = new JsonObject();
		json.addProperty("algorithm", algorithm.displayName());
		json.addProperty("hash", hex(algorithm.hash(data(args))));
		return McpToolResult.json(json);
	}

	@Nonnull
	private static byte[] data(@Nonnull McpArgs args) {
		ByteFormat format = enumValue(ByteFormat.class, args.string("input_format", "UTF8"));
		try {
			return format.parse(args.requireString("input"));
		} catch (CryptoException ex) {
			throw new McpToolException("Could not read input as " + format.displayName() + ": " + ex.getMessage());
		}
	}

	@Nonnull
	private static String hex(@Nonnull byte[] data) {
		int length = Math.min(data.length, 2048);
		String text = java.util.HexFormat.of().formatHex(data, 0, length);
		return data.length > length ? text + "..." : text;
	}

	@Nonnull
	private static <E extends Enum<E>> E enumValue(@Nonnull Class<E> type, @Nonnull String name) {
		try {
			return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			throw new McpToolException("Unknown " + type.getSimpleName() + " '" + name + "'. Options: " +
					Arrays.toString(type.getEnumConstants()));
		}
	}
}
