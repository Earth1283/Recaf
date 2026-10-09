package software.coley.recaf.services.mapping;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.format.SimpleMappings;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static software.coley.recaf.test.TestClassUtils.createEmptyClass;
import static software.coley.recaf.test.TestClassUtils.fromBundle;
import static software.coley.recaf.test.TestClassUtils.fromClasses;

/**
 * Tests for {@link MappingHelper}, which reads mapping files off the UI thread.
 */
class MappingHelperTest extends TestBase {
	static MappingHelper helper;
	@TempDir
	Path dir;

	@BeforeAll
	static void setup() {
		helper = new MappingHelper(recaf.get(MappingApplierService.class), recaf.get(AggregateMappingManager.class));
	}

	@Test
	void parseAsyncReadsTheFile() throws IOException {
		Path file = dir.resolve("mappings.txt");
		Files.writeString(file, "a/A b/B\n");
		IntermediateMappings mappings = helper.parseAsync(new SimpleMappings(), file).join();
		assertEquals("b/B", mappings.getMappedClassName("a/A"));
	}

	@Test
	void parseAsyncReportsMissingFiles() {
		CompletionException ex = assertThrows(CompletionException.class,
				() -> helper.parseAsync(new SimpleMappings(), dir.resolve("missing.txt")).join());
		assertInstanceOf(IOException.class, ex.getCause());
	}

	@Test
	void importMappingsParsesAndApplies() throws Exception {
		Workspace workspace = fromBundle(fromClasses(createEmptyClass("a/A")));
		workspaceManager.setCurrent(workspace);
		Path file = dir.resolve("mappings.txt");
		Files.writeString(file, "a/A b/B\n");

		helper.importMappings(new SimpleMappings(), file);

		// Both steps run on a background thread, so wait for the result to show up.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		var bundle = workspace.getPrimaryResource().getJvmClassBundle();
		while (bundle.get("b/B") == null && System.nanoTime() < deadline)
			Thread.sleep(25);
		assertNotNull(bundle.get("b/B"), "Class should be renamed by the imported mappings");
		assertNull(bundle.get("a/A"));
	}
}
