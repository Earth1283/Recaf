package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.NumberPredicate;
import software.coley.recaf.services.search.match.NumberPredicateProvider;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.DeclarationQuery;
import software.coley.recaf.services.search.query.InstructionQuery;
import software.coley.recaf.services.search.query.NumberQuery;
import software.coley.recaf.services.search.query.Query;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.query.StringQuery;
import software.coley.recaf.services.search.result.ClassReferenceResult;
import software.coley.recaf.services.search.result.MemberDeclaration;
import software.coley.recaf.services.search.result.MemberDeclarationResult;
import software.coley.recaf.services.search.result.MemberReference;
import software.coley.recaf.services.search.result.MemberReferenceResult;
import software.coley.recaf.services.search.result.NumberResult;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.search.result.Results;
import software.coley.recaf.services.search.result.StringResult;
import software.coley.recaf.util.AccessFlag;

import java.util.ArrayList;
import java.util.List;

/**
 * Tools wrapping Recaf's search service.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class SearchTools implements McpToolProvider {
	private static final List<String> STRING_MODES =
			List.of("contains", "equals", "starts", "ends", "regex", "regex_full");
	private static final List<String> NUMBER_MODES = List.of("equal", "not", "gt", "gte", "lt", "lte", "range");
	private final StringPredicateProvider strings = new StringPredicateProvider();
	private final NumberPredicateProvider numbers = new NumberPredicateProvider();
	private final McpSupport support;
	private final SearchService searchService;

	@Inject
	public SearchTools(@Nonnull McpSupport support, @Nonnull SearchService searchService) {
		this.support = support;
		this.searchService = searchService;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("search_strings",
				"Find string constants in class code and in text files.",
				McpAccess.READ,
				withStringMode(McpToolSchema.create().requiredString("query", "Text or pattern to look for."))
						.paging(),
				args -> run(args, new StringQuery(stringPredicate(args, "query")), result -> {
					JsonObject json = new JsonObject();
					json.addProperty("value", ((StringResult) result).getValue());
					return json;
				}));
		registry.add("search_numbers",
				"Find numeric constants (int, long, float, double) in class code.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("value", "Number to compare against. Decimal, or hex with a 0x prefix.")
						.enumeration("mode", "Comparison. Default equal.", NUMBER_MODES)
						.string("upper", "Upper bound when mode is 'range' (inclusive).")
						.paging(),
				args -> run(args, new NumberQuery(numberPredicate(args)), result -> {
					JsonObject json = new JsonObject();
					json.addProperty("value", ((NumberResult) result).getValue());
					return json;
				}));
		registry.add("search_references",
				"Find where a class, field or method is referenced from. Pass only 'owner' to find class references, " +
						"or add 'name' / 'descriptor' to find member references.",
				McpAccess.READ,
				withStringMode(McpToolSchema.create()
						.requiredString("owner", "Class the target belongs to, or the class itself (internal name).")
						.string("name", "Field or method name.")
						.string("descriptor", "Field or method descriptor."))
						.paging(),
				this::references);
		registry.add("search_declarations",
				"Find fields and methods by declared owner, name and/or descriptor.",
				McpAccess.READ,
				withStringMode(McpToolSchema.create()
						.string("owner", "Declaring class (internal name).")
						.string("name", "Member name.")
						.string("descriptor", "Member descriptor."))
						.paging(),
				this::declarations);
		registry.add("search_instructions",
				"Find sequences of bytecode instructions. Each pattern is matched, in order, against consecutive " +
						"instructions rendered as text such as 'invokestatic java/lang/Runtime.getRuntime ()Ljava/lang/Runtime;'.",
				McpAccess.READ,
				withStringMode(McpToolSchema.create()
						.requiredStringArray("patterns", "One pattern per instruction, in order.")).paging(),
				this::instructions);
	}

	@Nonnull
	private McpToolResult references(@Nonnull McpArgs args) {
		boolean member = args.has("name") || args.has("descriptor");
		StringPredicate owner = stringPredicate(args, "owner");
		Query query = member
				? new ReferenceQuery(owner, optional(args, "name"), optional(args, "descriptor"))
				: new ReferenceQuery(owner);
		return run(args, query, result -> {
			JsonObject json = new JsonObject();
			if (result instanceof MemberReferenceResult memberResult) {
				MemberReference ref = memberResult.getValue();
				json.addProperty("refOwner", ref.owner());
				json.addProperty("refName", ref.name());
				json.addProperty("refDescriptor", ref.desc());
			} else if (result instanceof ClassReferenceResult classResult) {
				json.addProperty("refClass", classResult.getValue().name());
			}
			return json;
		});
	}

	@Nonnull
	private McpToolResult declarations(@Nonnull McpArgs args) {
		DeclarationQuery query = new DeclarationQuery(optional(args, "owner"), optional(args, "name"),
				optional(args, "descriptor"));
		return run(args, query, result -> {
			JsonObject json = new JsonObject();
			MemberDeclaration declaration = ((MemberDeclarationResult) result).getValue();
			json.addProperty("owner", declaration.owner());
			json.addProperty("name", declaration.name());
			json.addProperty("descriptor", declaration.desc());
			json.addProperty("flags", McpSupport.flags(declaration.desc().startsWith("(")
					? AccessFlag.Type.METHOD : AccessFlag.Type.FIELD, declaration.access()));
			return json;
		});
	}

	@Nonnull
	private McpToolResult instructions(@Nonnull McpArgs args) {
		List<StringPredicate> predicates = new ArrayList<>();
		for (String pattern : args.strings("patterns"))
			predicates.add(stringPredicate(args.raw(), pattern));
		if (predicates.isEmpty())
			throw new McpToolException("'patterns' must not be empty");
		return run(args, new InstructionQuery(predicates), result -> {
			JsonObject json = new JsonObject();
			json.addProperty("matched", String.valueOf(result.getValue()));
			return json;
		});
	}

	@Nonnull
	private McpToolResult run(@Nonnull McpArgs args, @Nonnull Query query,
	                          @Nonnull java.util.function.Function<Result<?>, JsonObject> detail) {
		Results results = searchService.search(support.workspace(), query);
		return McpToolResult.json(McpSupport.page(results.stream(), args, 100, result -> {
			JsonObject json = McpSupport.describePath(result.getPath());
			detail.apply(result).entrySet().forEach(e -> json.add(e.getKey(), e.getValue()));
			return (JsonElement) json;
		}));
	}

	@Nonnull
	private static McpToolSchema withStringMode(@Nonnull McpToolSchema schema) {
		return schema
				.enumeration("mode", "How text is compared. Default contains.", STRING_MODES)
				.bool("ignore_case", "Compare case-insensitively. Default false.");
	}

	@Nullable
	private StringPredicate optional(@Nonnull McpArgs args, @Nonnull String name) {
		return args.has(name) ? stringPredicate(args, name) : null;
	}

	@Nonnull
	private StringPredicate stringPredicate(@Nonnull McpArgs args, @Nonnull String name) {
		return stringPredicate(args.raw(), args.requireString(name));
	}

	@Nonnull
	private StringPredicate stringPredicate(@Nonnull JsonObject raw, @Nonnull String key) {
		McpArgs args = new McpArgs(raw);
		boolean caseSensitive = !args.bool("ignore_case", false);
		String mode = args.string("mode", "contains");
		return switch (mode) {
			case "contains" -> strings.newContainsPredicate(key, caseSensitive);
			case "equals" -> strings.newEqualPredicate(key, caseSensitive);
			case "starts" -> strings.newStartsWithPredicate(key, caseSensitive);
			case "ends" -> strings.newEndsWithPredicate(key, caseSensitive);
			case "regex", "regex_full" -> {
				java.util.regex.Pattern pattern;
				try {
					pattern = java.util.regex.Pattern.compile(key, caseSensitive ? 0
							: java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
				} catch (RuntimeException ex) {
					throw new McpToolException("Invalid regex: " + ex.getMessage());
				}
				boolean full = mode.equals("regex_full");
				yield new StringPredicate(mode, text -> full ? pattern.matcher(text).matches() : pattern.matcher(text).find());
			}
			default -> throw new McpToolException("Unknown mode '" + mode + "'");
		};
	}

	@Nonnull
	private NumberPredicate numberPredicate(@Nonnull McpArgs args) {
		Number value = parseNumber(args.requireString("value"), "value");
		return switch (args.string("mode", "equal")) {
			case "equal" -> numbers.newEqualsPredicate(value);
			case "not" -> numbers.newNotEqualsPredicate(value);
			case "gt" -> numbers.newGreaterThanPredicate(value);
			case "gte" -> numbers.newGreaterThanOrEqualPredicate(value);
			case "lt" -> numbers.newLessThanPredicate(value);
			case "lte" -> numbers.newLessThanOrEqualPredicate(value);
			case "range" -> numbers.newRangePredicate(value, parseNumber(args.requireString("upper"), "upper"));
			default -> throw new McpToolException("Unknown mode '" + args.string("mode", "") + "'");
		};
	}

	@Nonnull
	private static Number parseNumber(@Nonnull String text, @Nonnull String argument) {
		String trimmed = text.trim();
		try {
			if (trimmed.startsWith("0x") || trimmed.startsWith("0X"))
				return Long.parseUnsignedLong(trimmed.substring(2), 16);
			if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("E"))
				return Double.parseDouble(trimmed);
			return Long.parseLong(trimmed);
		} catch (NumberFormatException ex) {
			throw new McpToolException("Argument '" + argument + "' is not a number: " + text);
		}
	}
}
