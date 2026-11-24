package software.coley.recaf.ui.control.popup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.decompile.DecompileDiagnostic;
import software.coley.recaf.services.decompile.DecompileResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static software.coley.recaf.test.TestClassUtils.createEmptyClass;

class DecompileArchiveTest {
	@TempDir
	Path directory;

	@Test
	void publishesSourcesAndReportsSkippedClassesAndPartialMethods() throws IOException {
		Path output = Files.writeString(directory.resolve("sources.zip"), "existing output");
		try (var archive = new DecompileAllPopup.ArchiveSink(output)) {
			archive.accept(createEmptyClass("plugin/Main"), new DecompileResult("class Main {}", 0)
					.withDiagnostics(List.of(new DecompileDiagnostic(
							new DecompileDiagnostic.Method("plugin/Main", "onEnable", "()V"), "Incomplete method", null))));
			archive.accept(createEmptyClass("plugin/Skipped"), new DecompileResult("No source"));
			assertEquals("existing output", Files.readString(output));
			archive.publish();
		}

		try (ZipFile zip = new ZipFile(output.toFile())) {
			assertEquals("class Main {}", readEntry(zip, "plugin/Main.java"));
			assertNull(zip.getEntry("plugin/Skipped.java"));
			String report = readEntry(zip, "recaf-decompile-report.txt");
			assertTrue(report.contains("plugin/Skipped was skipped"));
			assertTrue(report.contains("onEnable()V: Incomplete method"));
		}
	}

	@Test
	void closingAnUnpublishedArchivePreservesTheDestinationAndRemovesStagingFiles() throws IOException {
		Path output = Files.writeString(directory.resolve("sources.zip"), "existing output");
		try (var archive = new DecompileAllPopup.ArchiveSink(output)) {
			archive.accept(createEmptyClass("plugin/Main"), new DecompileResult("class Main {}", 0));
		}

		assertEquals("existing output", Files.readString(output));
		try (var files = Files.list(directory)) {
			assertEquals(List.of(output), files.toList());
		}
	}

	private static String readEntry(ZipFile zip, String name) throws IOException {
		try (var input = zip.getInputStream(zip.getEntry(name))) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
