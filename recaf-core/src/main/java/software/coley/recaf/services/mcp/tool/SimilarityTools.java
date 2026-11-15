package software.coley.recaf.services.mcp.tool;

import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.mcp.McpAccess;
import software.coley.recaf.services.mcp.McpArgs;
import software.coley.recaf.services.mcp.McpSupport;
import software.coley.recaf.services.mcp.McpToolException;
import software.coley.recaf.services.mcp.McpToolProvider;
import software.coley.recaf.services.mcp.McpToolRegistry;
import software.coley.recaf.services.mcp.McpToolResult;
import software.coley.recaf.services.mcp.McpToolSchema;
import software.coley.recaf.services.mcp.McpValues;
import software.coley.recaf.services.search.SimilaritySearchService;
import software.coley.recaf.services.search.similarity.MemberOrderMode;
import software.coley.recaf.services.search.similarity.ParameterMatchMode;
import software.coley.recaf.services.search.similarity.ReturnMatchMode;
import software.coley.recaf.services.search.similarity.SimilarClassSearchOptions;
import software.coley.recaf.services.search.similarity.SimilarClassSearchResult;
import software.coley.recaf.services.search.similarity.SimilarClassSearchScope;
import software.coley.recaf.services.search.similarity.SimilarMethodSearchOptions;
import software.coley.recaf.services.search.similarity.SimilarMethodSearchResult;

import java.util.Comparator;
import java.util.List;

/**
 * Structural similarity search, which finds classes and methods that look like a reference even when renamed.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class SimilarityTools implements McpToolProvider {
	private final McpSupport support;
	private final SimilaritySearchService service;

	@Inject
	public SimilarityTools(@Nonnull McpSupport support, @Nonnull SimilaritySearchService service) {
		this.support = support;
		this.service = service;
	}

	@Override
	public void registerTools(@Nonnull McpToolRegistry registry) {
		registry.add("similar_classes",
				"Find classes structurally similar to a reference class, ignoring names. Useful for matching an " +
						"obfuscated class against a known one in a library or an older version.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("name", "Reference class.")
						.integer("threshold", "Minimum similarity percent. Default 70.")
						.bool("all_resources", "Search libraries too, not only the reference's own resource.")
						.paging(),
				this::classes);
		registry.add("similar_methods",
				"Find methods whose bytecode is similar to a reference method, ignoring names.",
				McpAccess.READ,
				McpToolSchema.create()
						.requiredString("class", "Declaring class of the reference method.")
						.requiredString("method", "Reference method name.")
						.string("descriptor", "Descriptor if the name is overloaded.")
						.integer("threshold", "Minimum similarity percent. Default 70.")
						.bool("primary_only", "Only search the primary resource.")
						.paging(),
				this::methods);
	}

	@Nonnull
	private McpToolResult classes(@Nonnull McpArgs args) {
		ClassPathNode reference = support.requireClass(args.requireString("name"));
		SimilarClassSearchScope scope = args.bool("all_resources", false)
				? SimilarClassSearchScope.allNonInternal() : SimilarClassSearchScope.selfResource();
		SimilarClassSearchOptions options = new SimilarClassSearchOptions(args.integer("threshold", 70),
				ParameterMatchMode.EXACT_COUNT_AND_ORDER, ReturnMatchMode.EXACT_TYPE, MemberOrderMode.IGNORE_ORDER,
				MemberOrderMode.IGNORE_ORDER, SimilarClassSearchOptions.DEFAULT_METHOD_WEIGHT,
				SimilarClassSearchOptions.DEFAULT_FIELD_WEIGHT, scope);
		List<SimilarClassSearchResult> results = service.searchClasses(reference, options).stream()
				.sorted(Comparator.comparingDouble(SimilarClassSearchResult::similarity).reversed()).toList();
		return McpToolResult.json(McpSupport.page(results.stream(), args, 25, result -> {
			JsonObject json = new JsonObject();
			json.addProperty("class", result.path().getValue().getName());
			json.addProperty("similarity", Math.round(result.similarity() * 100.0) / 100.0);
			json.add("breakdown", McpValues.toJson(result.breakdown()));
			return json;
		}));
	}

	@Nonnull
	private McpToolResult methods(@Nonnull McpArgs args) {
		ClassPathNode owner = support.requireClass(args.requireString("class"));
		String name = args.requireString("method");
		String descriptor = args.string("descriptor", null);
		List<ClassMember> candidates = owner.getValue().getMethods().stream()
				.filter(m -> m.getName().equals(name) && (descriptor == null || m.getDescriptor().equals(descriptor)))
				.map(m -> (ClassMember) m).toList();
		if (candidates.isEmpty())
			throw new McpToolException("No method '" + name + "' in " + owner.getValue().getName());
		if (candidates.size() > 1)
			throw new McpToolException("'" + name + "' is overloaded, pass 'descriptor'");
		ClassMemberPathNode reference = owner.child(candidates.getFirst());
		SimilarMethodSearchOptions options = new SimilarMethodSearchOptions(args.integer("threshold", 70),
				ParameterMatchMode.ANYTHING, ReturnMatchMode.ANY_ASSIGNABLE, args.bool("primary_only", false));
		List<SimilarMethodSearchResult> results = service.searchMethods(reference, options).stream()
				.sorted(Comparator.comparingDouble(SimilarMethodSearchResult::similarity).reversed()).toList();
		return McpToolResult.json(McpSupport.page(results.stream(), args, 25, result -> {
			JsonObject json = McpSupport.describePath(result.path());
			json.addProperty("similarity", Math.round(result.similarity() * 100.0) / 100.0);
			return json;
		}));
	}
}
