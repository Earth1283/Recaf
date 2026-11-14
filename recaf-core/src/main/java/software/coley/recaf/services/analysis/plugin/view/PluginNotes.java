package software.coley.recaf.services.analysis.plugin.view;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.analysis.plugin.security.PluginSecurityReport;
import software.coley.recaf.services.analysis.plugin.security.SecurityFinding;
import software.coley.recaf.services.analysis.plugin.security.Severity;
import software.coley.recaf.services.analysis.plugin.semantic.CodeLocation;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElement;
import software.coley.recaf.services.analysis.plugin.semantic.PluginElementKind;
import software.coley.recaf.services.analysis.plugin.semantic.PluginIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Comment lines for the members of one class, taken from the plugin index and the security report.
 *
 * @param classLines
 * 		Lines for the class itself. Includes lines of members that print no declaration of their own, such as lambdas
 * 		and static initializers.
 * @param methodLines
 * 		Lines per method, keyed by name followed by descriptor.
 * @param fieldLines
 * 		Lines per field, keyed by name followed by descriptor.
 */
record PluginNotes(@Nonnull List<String> classLines,
                   @Nonnull Map<String, List<String>> methodLines,
                   @Nonnull Map<String, List<String>> fieldLines) {
	static final PluginNotes EMPTY = new PluginNotes(List.of(), Map.of(), Map.of());

	/**
	 * @return {@code true} when there is nothing to say about the class.
	 */
	boolean isEmpty() {
		return classLines.isEmpty() && methodLines.isEmpty() && fieldLines.isEmpty();
	}

	/**
	 * @param name
	 * 		Member name.
	 * @param descriptor
	 * 		Member descriptor.
	 *
	 * @return Key into {@link #methodLines} and {@link #fieldLines}.
	 */
	@Nonnull
	static String key(@Nonnull String name, @Nonnull String descriptor) {
		return name + descriptor;
	}

	/**
	 * @param model
	 * 		Class to describe.
	 * @param index
	 * 		Plugin index, or {@code null} to not describe the plugin's structure.
	 * @param securityByClass
	 * 		Security findings by class name, or {@code null} to not describe any.
	 * @param maxPerMember
	 * 		Most lines for one member. Further lines are replaced by a count.
	 *
	 * @return Notes for the class.
	 */
	@Nonnull
	static PluginNotes of(@Nonnull JvmClassInfo model, @Nullable PluginIndex index,
	                      @Nullable Map<String, List<SecurityFinding>> securityByClass, int maxPerMember) {
		String className = model.getName();
		List<PluginElement> elements = index == null ? List.of() : index.inClass(className);
		List<SecurityFinding> findings = securityByClass == null ? List.of() : securityByClass.getOrDefault(className, List.of());
		if (elements.isEmpty() && findings.isEmpty())
			return EMPTY;

		Map<String, Set<String>> methods = new HashMap<>();
		Map<String, Set<String>> fields = new HashMap<>();
		Set<String> classLines = new LinkedHashSet<>();

		for (SecurityFinding finding : findings) {
			CodeLocation location = finding.location();
			if (location == null || !location.className().equals(className))
				continue;
			add(model, location, securityLine(finding), methods, fields, classLines);
		}
		for (PluginElement element : elements) {
			CodeLocation location = element.location();
			if (!location.className().equals(className))
				continue;
			add(model, location, elementLine(element), methods, fields, classLines);
		}

		if (methods.isEmpty() && fields.isEmpty() && classLines.isEmpty())
			return EMPTY;
		return new PluginNotes(limit(classLines, maxPerMember), limitAll(methods, maxPerMember), limitAll(fields, maxPerMember));
	}

	private static void add(@Nonnull JvmClassInfo model, @Nonnull CodeLocation location, @Nonnull String line,
	                        @Nonnull Map<String, Set<String>> methods, @Nonnull Map<String, Set<String>> fields,
	                        @Nonnull Set<String> classLines) {
		if (location.isClass()) {
			classLines.add(line);
		} else if (location.isMethod()) {
			String name = location.memberName();
			String descriptor = location.memberDescriptor();
			MethodMember method = model.getDeclaredMethod(name, descriptor);
			if (method == null || !printsDeclaration(method))
				classLines.add(name + ": " + line);
			else
				methods.computeIfAbsent(key(name, descriptor), k -> new LinkedHashSet<>()).add(line);
		} else if (model.getDeclaredField(location.memberName(), location.memberDescriptor()) != null) {
			fields.computeIfAbsent(key(location.memberName(), location.memberDescriptor()), k -> new LinkedHashSet<>()).add(line);
		} else {
			classLines.add(location.memberName() + ": " + line);
		}
	}

	/**
	 * Static initializers print as a bare block, and lambda bodies, bridges and other synthetic methods are hidden or
	 * printed inline, so an annotation on any of them is lost.
	 */
	private static boolean printsDeclaration(@Nonnull MethodMember method) {
		String name = method.getName();
		return !name.equals("<clinit>") && !name.startsWith("lambda$") && !method.hasSyntheticModifier() &&
				!method.hasBridgeModifier();
	}

	@Nonnull
	private static String elementLine(@Nonnull PluginElement element) {
		PluginElementKind kind = element.kind();
		String detail = element.detail();
		if (kind == PluginElementKind.EVENT_HANDLER && detail != null && !detail.isBlank())
			return kind.displayName() + ": " + element.key() + " (priority " + detail + ")";
		return kind.displayName() + ": " + element.key();
	}

	@Nonnull
	private static String securityLine(@Nonnull SecurityFinding finding) {
		StringBuilder sb = new StringBuilder("SECURITY ").append(finding.severity().name())
				.append(": ").append(finding.rule().id().replace('-', ' ').toLowerCase(Locale.ROOT));
		if (finding.file() != null)
			sb.append(" '").append(finding.file()).append('\'');
		String detail = finding.detail();
		if (detail != null && !detail.isBlank() && !detail.equals(finding.file()))
			sb.append(" (").append(detail).append(')');
		if (finding.library())
			sb.append(" [bundled library]");
		return sb.toString();
	}

	@Nonnull
	private static Map<String, List<String>> limitAll(@Nonnull Map<String, Set<String>> lines, int max) {
		Map<String, List<String>> limited = new HashMap<>();
		lines.forEach((key, set) -> limited.put(key, limit(set, max)));
		return limited;
	}

	@Nonnull
	private static List<String> limit(@Nonnull Set<String> lines, int max) {
		List<String> sorted = new ArrayList<>(lines);
		sorted.sort((a, b) -> Boolean.compare(b.startsWith("SECURITY"), a.startsWith("SECURITY")));
		if (sorted.size() <= max)
			return List.copyOf(sorted);
		List<String> kept = new ArrayList<>(sorted.subList(0, Math.max(0, max - 1)));
		kept.add("+" + (sorted.size() - kept.size()) + " more");
		return List.copyOf(kept);
	}

	/**
	 * @param report
	 * 		Security report.
	 * @param minimum
	 * 		Lowest severity to include.
	 *
	 * @return Findings of the plugin's own jar by the class they are in. Findings about a file that no code is known
	 * to refer to have no class and are left out.
	 */
	@Nonnull
	static Map<String, List<SecurityFinding>> groupSecurity(@Nonnull PluginSecurityReport report, @Nonnull Severity minimum) {
		Map<String, List<SecurityFinding>> byClass = new HashMap<>();
		for (SecurityFinding finding : report.findings())
			if (finding.location() != null && finding.embeddedIn() == null && finding.severity().isAtLeast(minimum))
				byClass.computeIfAbsent(finding.location().className(), k -> new ArrayList<>()).add(finding);
		return byClass;
	}
}
