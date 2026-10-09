package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.Comparator;

/**
 * A class, or a field or method in a class, that something in a plugin was found at.
 * <p>
 * Names are internal names <i>({@code com/example/Main})</i> as they are at the time of indexing.
 *
 * @param className
 * 		Internal name of the class.
 * @param memberName
 * 		Name of the field or method, or {@code null} when the location is the class itself.
 * @param memberDescriptor
 * 		Descriptor of the field or method, or {@code null} when the location is the class itself.
 */
public record CodeLocation(@Nonnull String className,
                           @Nullable String memberName,
                           @Nullable String memberDescriptor) implements Comparable<CodeLocation> {
	private static final Comparator<CodeLocation> ORDER = Comparator.comparing(CodeLocation::className)
			.thenComparing(CodeLocation::memberName, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(CodeLocation::memberDescriptor, Comparator.nullsFirst(Comparator.naturalOrder()));

	/**
	 * @param className
	 * 		Internal name of the class.
	 *
	 * @return Location of the class itself.
	 */
	@Nonnull
	public static CodeLocation ofClass(@Nonnull String className) {
		return new CodeLocation(className, null, null);
	}

	/**
	 * @param className
	 * 		Internal name of the declaring class.
	 * @param name
	 * 		Field or method name.
	 * @param descriptor
	 * 		Field or method descriptor.
	 *
	 * @return Location of the member.
	 */
	@Nonnull
	public static CodeLocation ofMember(@Nonnull String className, @Nonnull String name, @Nonnull String descriptor) {
		return new CodeLocation(className, name, descriptor);
	}

	/**
	 * @return {@code true} when this is the location of a class, not one of its members.
	 */
	public boolean isClass() {
		return memberName == null;
	}

	/**
	 * @return {@code true} when this is the location of a method.
	 */
	public boolean isMethod() {
		return memberDescriptor != null && memberDescriptor.startsWith("(");
	}

	/**
	 * @return {@code true} when this is the location of a field.
	 */
	public boolean isField() {
		return memberName != null && !isMethod();
	}

	/**
	 * @return Location of the declaring class.
	 */
	@Nonnull
	public CodeLocation classLocation() {
		return isClass() ? this : ofClass(className);
	}

	/**
	 * @return Class name without the package.
	 */
	@Nonnull
	public String simpleClassName() {
		return className.substring(className.lastIndexOf('/') + 1);
	}

	/**
	 * @return Short readable text, such as {@code Main}, {@code Main.onEnable()} or {@code Main.config}.
	 */
	@Nonnull
	public String display() {
		if (isClass())
			return simpleClassName();
		return simpleClassName() + '.' + memberName + (isMethod() ? "()" : "");
	}

	@Override
	public int compareTo(@Nonnull CodeLocation other) {
		return ORDER.compare(this, other);
	}

	@Override
	public String toString() {
		if (isClass())
			return className;
		return className + '.' + memberName + memberDescriptor;
	}
}
