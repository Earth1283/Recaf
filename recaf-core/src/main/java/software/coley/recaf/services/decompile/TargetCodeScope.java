package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Decides which classes a decompiler needs in full while decompiling one class. Every other class it loads is only
 * consulted for declarations, and is given as a {@link ReferencedClassStubs stub}.
 * <p>
 * Decompilers read method bodies of other classes in these cases:
 * <ul>
 *     <li>Classes nested with the target: inner and anonymous classes are printed inline, and synthetic accessors
 *     are followed into the outer class.</li>
 *     <li>Enum switch lookup tables: {@code switch (Lookup.table[value.ordinal()])} is turned back into an enum switch
 *     by reading the static initializer of the lookup class. Obfuscators often rename that class and drop its inner
 *     class attributes, so lookup classes are found by the {@code int[]} fields the nest refers to instead.</li>
 * </ul>
 * When any class of the nest cannot be read, nothing is stubbed.
 */
public final class TargetCodeScope {
	private static final int CONSTANT_FIELDREF = 9;
	private final Workspace workspace;
	private final String targetName;
	private final byte[] targetBytecode;
	private final JvmClassInfo targetInfo;
	private final String topLevelPrefix;
	private Set<String> fullClasses;
	private boolean stubNothing;

	private TargetCodeScope(@Nonnull Workspace workspace, @Nonnull String targetName, @Nonnull byte[] targetBytecode,
	                        @Nullable JvmClassInfo targetInfo) {
		this.workspace = workspace;
		this.targetName = targetName;
		this.targetBytecode = targetBytecode;
		this.targetInfo = targetInfo;
		int split = targetName.indexOf('$');
		topLevelPrefix = (split < 0 ? targetName : targetName.substring(0, split));
	}

	/**
	 * @param workspace
	 * 		Workspace holding the classes the decompiler may load.
	 * @param target
	 * 		Class being decompiled, as given to the decompiler.
	 *
	 * @return Scope of the target.
	 */
	@Nonnull
	public static TargetCodeScope of(@Nonnull Workspace workspace, @Nonnull JvmClassInfo target) {
		return new TargetCodeScope(workspace, target.getName(), target.getBytecode(), target);
	}

	/**
	 * @param workspace
	 * 		Workspace holding the classes the decompiler may load.
	 * @param targetName
	 * 		Name of the class being decompiled.
	 * @param targetBytecode
	 * 		Bytecode of the class, as given to the decompiler.
	 *
	 * @return Scope of the target.
	 */
	@Nonnull
	public static TargetCodeScope of(@Nonnull Workspace workspace, @Nonnull String targetName, @Nonnull byte[] targetBytecode) {
		return new TargetCodeScope(workspace, targetName, targetBytecode, null);
	}

	/**
	 * @param info
	 * 		Class the decompiler asks for, other than the target.
	 * @param nestedClassView
	 * 		View applied to classes given in full.
	 *
	 * @return Bytecode to give the decompiler: the class in full when it {@link #needsCode(String) needs its code},
	 * otherwise a stub.
	 */
	@Nonnull
	public byte[] bytecodeFor(@Nonnull JvmClassInfo info, @Nonnull UnaryOperator<JvmClassInfo> nestedClassView) {
		return needsCode(info.getName()) ? nestedClassView.apply(info).getBytecode() : ReferencedClassStubs.of(info);
	}

	/**
	 * @param name
	 * 		Internal name of a class.
	 *
	 * @return {@code true} when the decompiler may read method bodies of the class.
	 */
	public synchronized boolean needsCode(@Nonnull String name) {
		if (name.equals(topLevelPrefix) || name.startsWith(topLevelPrefix + '$'))
			return true;
		if (fullClasses == null)
			collect();
		return stubNothing || fullClasses.contains(name);
	}

	private void collect() {
		Set<String> found = new HashSet<>();
		Deque<JvmClassInfo> queue = new ArrayDeque<>();
		found.add(targetName);
		fullClasses = found;
		if (!addLookupTableOwners(targetBytecode, found)) {
			stubNothing = true;
			return;
		}
		JvmClassInfo target = targetInfo;
		if (target == null) {
			ClassPathNode path = workspace.findClass(targetName);
			if (path != null && path.getValue().isJvmClass())
				target = path.getValue().asJvmClass();
		}
		if (target != null)
			queue.add(target);
		while (!queue.isEmpty()) {
			JvmClassInfo current = queue.poll();
			for (String name : nestLinks(current)) {
				if (!found.add(name))
					continue;
				ClassPathNode path = workspace.findClass(name);
				if (path == null || !path.getValue().isJvmClass())
					continue;
				JvmClassInfo linked = path.getValue().asJvmClass();
				if (!addLookupTableOwners(linked.getBytecode(), found)) {
					stubNothing = true;
					return;
				}
				queue.add(linked);
			}
		}
	}

	/**
	 * @param info
	 * 		Some class.
	 *
	 * @return Names of its outer class, and of the classes it declares as its own inner classes.
	 */
	@Nonnull
	private static List<String> nestLinks(@Nonnull JvmClassInfo info) {
		String name = info.getName();
		List<String> linked = new ArrayList<>();
		if (info.getOuterClassName() != null)
			linked.add(info.getOuterClassName());
		for (InnerClassInfo inner : info.getInnerClasses()) {
			String innerName = inner.getInnerClassName();
			String outerName = inner.getOuterClassName();
			if (name.equals(outerName) || (outerName == null && innerName.startsWith(name + '$')))
				linked.add(innerName);
			else if (name.equals(innerName) && outerName != null)
				linked.add(outerName);
		}
		return linked;
	}

	/**
	 * Adds the owners of every {@code int[]} field the class refers to, which covers all enum switch lookup tables.
	 *
	 * @param bytecode
	 * 		Class bytecode.
	 * @param owners
	 * 		Set to add owner names to.
	 *
	 * @return {@code false} when the constant pool cannot be read.
	 */
	private static boolean addLookupTableOwners(@Nonnull byte[] bytecode, @Nonnull Set<String> owners) {
		try {
			ClassReader reader = new ClassReader(bytecode);
			char[] buffer = new char[reader.getMaxStringLength()];
			for (int i = 1; i < reader.getItemCount(); i++) {
				int offset = reader.getItem(i);
				if (offset == 0 || reader.b[offset - 1] != CONSTANT_FIELDREF)
					continue;
				int nameAndType = reader.getItem(reader.readUnsignedShort(offset + 2));
				if ("[I".equals(reader.readUTF8(nameAndType + 2, buffer)))
					owners.add(reader.readClass(offset, buffer));
			}
			return true;
		} catch (Throwable t) {
			return false;
		}
	}
}
