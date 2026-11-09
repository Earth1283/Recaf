package software.coley.recaf.services.attach;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the parts of {@link BasicAttachManager} that do not need another VM to attach to.
 */
class BasicAttachManagerTest {
	@Nested
	class MainClass {
		@TempDir
		Path dir;

		@Test
		void displayNameWithArguments() {
			assertEquals("com.example.Main", BasicAttachManager.resolveMainClass("com.example.Main --nogui", null));
		}

		@Test
		void unknownWhenNothingIsKnown() {
			assertEquals("<unknown main-class>", BasicAttachManager.resolveMainClass(null, null));
			assertEquals("<unknown main-class>", BasicAttachManager.resolveMainClass(" ", new Properties()));
		}

		@Test
		void relativeJarIsResolvedAgainstTheRemoteWorkingDirectory() throws IOException {
			jar(dir.resolve("server.jar"), "io.papermc.paperclip.Main");
			Properties properties = properties("server.jar --nogui", dir.toString());

			assertEquals("io.papermc.paperclip.Main", BasicAttachManager.resolveMainClass("server.jar --nogui", properties));
		}

		@Test
		void absoluteJarPathIsKeptAsIs() throws IOException {
			Path jar = dir.resolve("sub").resolve("app.jar");
			Files.createDirectories(jar.getParent());
			jar(jar, "com.example.App");
			Properties properties = properties(jar + " arg", "/does/not/exist");

			assertEquals("com.example.App", BasicAttachManager.resolveMainClass(jar + " arg", properties));
		}

		@Test
		void missingWorkingDirectoryFallsBackToTheJarName() {
			Properties properties = new Properties();
			properties.setProperty("sun.java.command", "server.jar --nogui");

			assertEquals("server.jar", BasicAttachManager.resolveMainClass("server.jar --nogui", properties));
		}

		@Test
		void jarWithoutManifestFallsBackToTheJarName() throws IOException {
			try (OutputStream out = Files.newOutputStream(dir.resolve("plain.jar"));
			     JarOutputStream ignored = new JarOutputStream(out)) {
				// An empty jar has no manifest.
			}
			Properties properties = properties("plain.jar", dir.toString());

			assertEquals("plain.jar", BasicAttachManager.resolveMainClass("plain.jar", properties));
		}

		@Test
		void textBeforeJarThatIsNotAPathIsIgnored() {
			Properties properties = properties("com.example.Main foo\0.jar", dir.toString());

			assertEquals("com.example.Main", BasicAttachManager.resolveMainClass("com.example.Main foo\0.jar", properties));
		}

		private static Properties properties(String command, String userDir) {
			Properties properties = new Properties();
			properties.setProperty("sun.java.command", command);
			properties.setProperty("user.dir", userDir);
			return properties;
		}

		private static void jar(Path path, String mainClass) throws IOException {
			Manifest manifest = new Manifest();
			manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
			manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
			try (OutputStream out = Files.newOutputStream(path);
			     JarOutputStream ignored = new JarOutputStream(out, manifest)) {
				// Only the manifest is needed.
			}
		}
	}

	@Nested
	class Attempts {
		@Test
		void successIsReported() {
			var attempt = BasicAttachManager.attemptWithTimeout(() -> "vm", 5000, late -> fail("Not late")).join();

			assertEquals("vm", attempt.value());
			assertNull(attempt.failure());
		}

		@Test
		void failureCompletesNormally() {
			CompletableFuture<BasicAttachManager.Attempt<String>> future = BasicAttachManager.attemptWithTimeout(() -> {
				throw new IllegalStateException("boom");
			}, 5000, late -> fail("Not late"));

			var attempt = assertDoesNotThrow(future::join);
			assertNull(attempt.value());
			assertInstanceOf(IllegalStateException.class, attempt.failure());
		}

		@Test
		void oneFailureDoesNotCancelOthers() {
			CountDownLatch release = new CountDownLatch(1);
			var failing = BasicAttachManager.<String>attemptWithTimeout(() -> {
				throw new IllegalStateException("boom");
			}, 5000, late -> {});
			var slow = BasicAttachManager.attemptWithTimeout(() -> {
				await(release);
				return "slow";
			}, 5000, late -> {});

			failing.join();
			release.countDown();

			assertEquals("slow", slow.join().value(), "A failure elsewhere must not cancel this attempt");
		}

		@Test
		void lateResultIsHandedBackForRelease() throws InterruptedException {
			CountDownLatch release = new CountDownLatch(1);
			List<String> abandoned = new CopyOnWriteArrayList<>();
			CountDownLatch abandonedLatch = new CountDownLatch(1);
			var attempt = BasicAttachManager.attemptWithTimeout(() -> {
				await(release);
				return "late-vm";
			}, 50, late -> {
				abandoned.add(late);
				abandonedLatch.countDown();
			}).join();

			assertNull(attempt.value());
			assertInstanceOf(TimeoutException.class, attempt.failure());

			release.countDown();
			assertTrue(abandonedLatch.await(5, TimeUnit.SECONDS), "Late result should be handed back");
			assertEquals(List.of("late-vm"), abandoned);
		}

		private static void await(CountDownLatch latch) {
			try {
				latch.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	@Nested
	class Beans {
		@Test
		void attributesAreKeyedByNameAndArraysExpanded() throws Exception {
			JmxBeanServerConnection connection = new JmxBeanServerConnection(ManagementFactory.getPlatformMBeanServer());
			Map<String, String> values = connection.getRuntimeBeanInfo().readAttributeValues(connection);

			assertTrue(values.containsKey("Uptime"), "Keys should be attribute names: " + values.keySet());
			assertTrue(values.get("InputArguments").startsWith("["), "Arrays should list their elements");
			assertFalse(values.get("InputArguments").startsWith("[Ljava"), "Arrays should list their elements");
		}

		@Test
		void everyPlatformBeanCanBeRead() throws Exception {
			JmxBeanServerConnection connection = new JmxBeanServerConnection(ManagementFactory.getPlatformMBeanServer());
			for (NamedMBeanInfo info : List.of(connection.getClassloadingBeanInfo(), connection.getCompilationBeanInfo(),
					connection.getOperatingSystemBeanInfo(), connection.getRuntimeBeanInfo(), connection.getThreadBeanInfo()))
				assertEquals(info.getAttributes().length, info.readAttributeValues(connection).size(), info.getClassName());
		}
	}
}
