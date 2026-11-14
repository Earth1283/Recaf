package software.coley.recaf.ui.pane.crypto;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Collects the string constants of every JVM class in a workspace, for picking crypto tool inputs and keys.
 */
public final class WorkspaceStrings {
	private static final Logger logger = Logging.get(WorkspaceStrings.class);
	private static final int CONSTANT_STRING = 8;

	private WorkspaceStrings() {}

	/**
	 * @param workspace
	 * 		Workspace to scan.
	 *
	 * @return Unique string constants, sorted.
	 */
	@Nonnull
	public static List<String> collect(@Nonnull Workspace workspace) {
		Set<String> strings = new TreeSet<>();
		workspace.jvmClassesStream(false).forEach(path -> {
			ClassInfo info = path.getValue();
			if (!info.isJvmClass())
				return;
			try {
				ClassReader reader = info.asJvmClass().getClassReader();
				char[] buffer = new char[reader.getMaxStringLength()];
				for (int i = 1; i < reader.getItemCount(); i++) {
					int offset = reader.getItem(i);
					if (offset > 0 && reader.b[offset - 1] == CONSTANT_STRING)
						strings.add(reader.readUTF8(offset, buffer));
				}
			} catch (RuntimeException ex) {
				logger.debug("Skipping constant pool strings of {}", info.getName(), ex);
			}
		});
		return new ArrayList<>(strings);
	}
}
