package software.coley.recaf.services.analysis.plugin.semantic;

import jakarta.annotation.Nonnull;

/**
 * Something that can be renamed.
 */
public sealed interface NameTarget {
	/**
	 * @return Name of the target as it is now. For classes this is the simple name, without package.
	 */
	@Nonnull
	String currentName();

	/**
	 * @return Where the target is, for navigation. Variables give the location of their method.
	 */
	@Nonnull
	CodeLocation location();

	/**
	 * @return Readable description of the target, including its owner.
	 */
	@Nonnull
	String describe();

	/**
	 * @param className
	 * 		Internal name of the class.
	 */
	record ClassTarget(@Nonnull String className) implements NameTarget {
		@Nonnull
		@Override
		public String currentName() {
			return NameHeuristics.simpleName(className);
		}

		@Nonnull
		@Override
		public CodeLocation location() {
			return CodeLocation.ofClass(className);
		}

		@Nonnull
		@Override
		public String describe() {
			return className.replace('/', '.');
		}
	}

	/**
	 * @param owner
	 * 		Internal name of the declaring class.
	 * @param name
	 * 		Field name.
	 * @param descriptor
	 * 		Field descriptor.
	 */
	record FieldTarget(@Nonnull String owner, @Nonnull String name, @Nonnull String descriptor) implements NameTarget {
		@Nonnull
		@Override
		public String currentName() {
			return name;
		}

		@Nonnull
		@Override
		public CodeLocation location() {
			return CodeLocation.ofMember(owner, name, descriptor);
		}

		@Nonnull
		@Override
		public String describe() {
			return NameHeuristics.simpleName(owner) + '.' + name;
		}
	}

	/**
	 * @param owner
	 * 		Internal name of the declaring class.
	 * @param name
	 * 		Method name.
	 * @param descriptor
	 * 		Method descriptor.
	 */
	record MethodTarget(@Nonnull String owner, @Nonnull String name, @Nonnull String descriptor) implements NameTarget {
		@Nonnull
		@Override
		public String currentName() {
			return name;
		}

		@Nonnull
		@Override
		public CodeLocation location() {
			return CodeLocation.ofMember(owner, name, descriptor);
		}

		@Nonnull
		@Override
		public String describe() {
			return NameHeuristics.simpleName(owner) + '.' + name + "()";
		}
	}

	/**
	 * @param owner
	 * 		Internal name of the class declaring the method.
	 * @param methodName
	 * 		Name of the method declaring the variable.
	 * @param methodDescriptor
	 * 		Descriptor of the method declaring the variable.
	 * @param name
	 * 		Variable name.
	 * @param descriptor
	 * 		Variable descriptor.
	 * @param index
	 * 		Variable slot index.
	 */
	record VariableTarget(@Nonnull String owner, @Nonnull String methodName, @Nonnull String methodDescriptor,
	                      @Nonnull String name, @Nonnull String descriptor, int index) implements NameTarget {
		@Nonnull
		@Override
		public String currentName() {
			return name;
		}

		@Nonnull
		@Override
		public CodeLocation location() {
			return CodeLocation.ofMember(owner, methodName, methodDescriptor);
		}

		@Nonnull
		@Override
		public String describe() {
			return NameHeuristics.simpleName(owner) + '.' + methodName + "(): " + name;
		}
	}
}
