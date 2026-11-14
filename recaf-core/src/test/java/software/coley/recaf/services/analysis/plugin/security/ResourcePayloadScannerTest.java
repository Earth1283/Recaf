package software.coley.recaf.services.analysis.plugin.security;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.services.analysis.plugin.security.ResourcePayloadScanner.Match;
import software.coley.recaf.util.io.ZipCreationUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ResourcePayloadScanner}, on bytes built here.
 */
class ResourcePayloadScannerTest {
	@Test
	void classFileWithAnotherNameIsDisguised() {
		Match match = ResourcePayloadScanner.inspect("assets/logo.dat", classBytes(Opcodes.V17));

		assertNotNull(match);
		assertEquals(SecurityRule.DISGUISED_CLASS, match.rule());
		assertEquals("class file, Java 17", match.detail());
	}

	@Test
	void honestlyNamedClassIsNotFlagged() {
		assertNull(ResourcePayloadScanner.inspect("a/B.class", classBytes(Opcodes.V17)));
		assertNull(ResourcePayloadScanner.inspect("META-INF/versions/9/a/B.class", classBytes(Opcodes.V9)));
	}

	@Test
	void implausibleVersionIsNotAClass() {
		byte[] bytes = classBytes(Opcodes.V17);
		bytes[6] = 0x7F;
		bytes[7] = 0x7F;

		assertNull(ResourcePayloadScanner.inspect("x.dat", bytes));
	}

	@Test
	void zipWithAnotherNameIsDisguisedButArchivesAreHonest() throws IOException {
		byte[] zip = ZipCreationUtils.createZip(Map.of("a.txt", "hello".getBytes()));

		Match match = ResourcePayloadScanner.inspect("data/update.bin", zip);
		assertNotNull(match);
		assertEquals(SecurityRule.DISGUISED_ARCHIVE, match.rule());
		assertEquals("ZIP archive", match.detail());
		assertNotNull(ResourcePayloadScanner.inspect("data/update", zip), "No extension says nothing about being an archive");
		for (String name : new String[]{"lib/a.jar", "a.ZIP", "x.war", "x.ear", "x.aar", "x.apk", "x.docx", "x.xlsx", "x.pptx",
				"x.odt", "x.mcpack", "x.mcworld", "x.litemod"})
			assertNull(ResourcePayloadScanner.inspect(name, zip), name);
		assertNull(ResourcePayloadScanner.inspectArchive("lib/a.jar"));
		assertEquals(SecurityRule.DISGUISED_ARCHIVE, ResourcePayloadScanner.inspectArchive("lib/a.dat").rule());
	}

	@Test
	void elfIsNativeUnderNativeExtensionsAndDisguisedElsewhere() {
		byte[] elf = elf(3);

		Match honest = ResourcePayloadScanner.inspect("natives/libx.so", elf);
		assertNotNull(honest);
		assertEquals(SecurityRule.NATIVE_BINARY, honest.rule());
		assertEquals("ELF shared object", honest.detail());
		assertEquals(SecurityRule.NATIVE_BINARY, ResourcePayloadScanner.inspect("natives/libx.so.1.2", elf).rule());
		assertEquals(SecurityRule.NATIVE_BINARY, ResourcePayloadScanner.inspect("natives/x.exe", elf).rule());
		Match disguised = ResourcePayloadScanner.inspect("images/icon.png", elf);
		assertNotNull(disguised);
		assertEquals(SecurityRule.DISGUISED_BINARY, disguised.rule());
		assertEquals(SecurityRule.DISGUISED_BINARY, ResourcePayloadScanner.inspect("natives/libx.so.old", elf).rule());
	}

	@Test
	void peNeedsAValidHeaderOffset() {
		byte[] pe = pe(true);

		Match honest = ResourcePayloadScanner.inspect("win/x.dll", pe);
		assertNotNull(honest);
		assertEquals(SecurityRule.NATIVE_BINARY, honest.rule());
		assertEquals("PE DLL", honest.detail());
		assertEquals("PE executable", ResourcePayloadScanner.inspect("win/x.exe", pe(false)).detail());
		assertEquals(SecurityRule.DISGUISED_BINARY, ResourcePayloadScanner.inspect("win/x.txt", pe).rule());

		byte[] notPe = pe.clone();
		notPe[0x3C] = (byte) 0xFF;
		assertNull(ResourcePayloadScanner.inspect("win/x.dll", notPe), "'MZ' alone is not a program");
	}

	@Test
	void machOThinAndFatInBothEndiannesses() {
		Match thin = ResourcePayloadScanner.inspect("mac/libx.dylib", machO(false, 6));
		assertNotNull(thin);
		assertEquals(SecurityRule.NATIVE_BINARY, thin.rule());
		assertEquals("Mach-O dynamic library", thin.detail());
		assertEquals(SecurityRule.NATIVE_BINARY, ResourcePayloadScanner.inspect("mac/libx.jnilib", machO(true, 6)).rule());
		assertEquals(SecurityRule.DISGUISED_BINARY, ResourcePayloadScanner.inspect("mac/lib.bin", machO(true, 6)).rule());

		Match fat = ResourcePayloadScanner.inspect("mac/libx.dylib", fatMachO());
		assertNotNull(fat);
		assertEquals(SecurityRule.NATIVE_BINARY, fat.rule());
		assertEquals("Mach-O universal binary", fat.detail());
		assertEquals(SecurityRule.DISGUISED_BINARY, ResourcePayloadScanner.inspect("mac/lib.bin", fatMachO()).rule());
	}

	@Test
	void fatMachOIsNotMistakenForAClassAndTheReverse() {
		byte[] fat = fatMachO();

		assertEquals(0, ResourcePayloadScanner.classMajorVersion(fat));
		assertNotNull(ResourcePayloadScanner.nativeKind(fat));
		byte[] clazz = classBytes(Opcodes.V21);
		assertTrue(ResourcePayloadScanner.classMajorVersion(clazz) > 0);
		assertNull(ResourcePayloadScanner.nativeKind(clazz));
		assertEquals(SecurityRule.DISGUISED_CLASS, ResourcePayloadScanner.inspect("mac/lib.bin", clazz).rule());
	}

	@Test
	void randomLargeDataIsHighEntropy() {
		Match match = ResourcePayloadScanner.inspect("data/blob.bin", random(48 * 1024));

		assertNotNull(match);
		assertEquals(SecurityRule.HIGH_ENTROPY_RESOURCE, match.rule());
		assertTrue(match.detail().matches("entropy (7\\.9\\d|8\\.00), 48 KB"), match.detail());
	}

	@Test
	void knownCompressedFormatsAreNotHighEntropy() throws IOException {
		byte[] noise = random(32 * 1024);
		byte[] png = withMagic(noise, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
		byte[] zip = ZipCreationUtils.createZip(Map.of("noise", noise));
		ByteArrayOutputStream gz = new ByteArrayOutputStream();
		try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
			out.write(noise);
		}

		assertTrue(ResourcePayloadScanner.entropy(png) > 7.9);
		assertTrue(ResourcePayloadScanner.entropy(gz.toByteArray()) > 7.9);
		assertNull(ResourcePayloadScanner.inspect("img/a.png", png));
		assertNull(ResourcePayloadScanner.inspect("img/renamed.bin", png), "Recognized by header");
		assertNull(ResourcePayloadScanner.inspect("schematics/a.schem", gz.toByteArray()));
		assertNull(ResourcePayloadScanner.inspect("schematics/no-extension", gz.toByteArray()));
		assertNull(ResourcePayloadScanner.inspect("lib/a.jar", zip));
		assertNull(ResourcePayloadScanner.inspect("worlds/r.0.0.mca", noise), "Recognized by extension");
		assertNull(ResourcePayloadScanner.inspect("music/a.ogg", noise));
		assertNull(ResourcePayloadScanner.inspect("fonts/a.ttf", noise));
		assertNull(ResourcePayloadScanner.inspect("a.nbt", noise));
	}

	@Test
	void smallOrPlainDataIsNotHighEntropy() {
		assertNull(ResourcePayloadScanner.inspect("data/small.bin", random(2048)));
		assertNull(ResourcePayloadScanner.inspect("data/just-under.bin", random(4095)));
		assertNotNull(ResourcePayloadScanner.inspect("data/just-over.bin", random(4096)));
		assertNull(ResourcePayloadScanner.inspect("config.yml", "key: value\n".repeat(2000).getBytes()));
		assertNull(ResourcePayloadScanner.inspect("empty.bin", new byte[0]));
	}

	@Test
	void manifestsSignaturesAndMavenMetadataAreNeverReported() {
		byte[] noise = random(16 * 1024);

		assertNull(ResourcePayloadScanner.inspect("plugin.yml", noise));
		assertNull(ResourcePayloadScanner.inspect("paper-plugin.yml", classBytes(Opcodes.V17)));
		assertNull(ResourcePayloadScanner.inspect("META-INF/MANIFEST.MF", noise));
		assertNull(ResourcePayloadScanner.inspect("META-INF/CERT.RSA", noise));
		assertNull(ResourcePayloadScanner.inspect("META-INF/CERT.SF", noise));
		assertNull(ResourcePayloadScanner.inspect("META-INF/maven/a/b/pom.properties", elf(3)));
		assertNotNull(ResourcePayloadScanner.inspect("other/META-INF/x.RSA", noise));
	}

	@Test
	void entropyOfKnownInputs() {
		assertEquals(0, ResourcePayloadScanner.entropy(new byte[100]), 1e-9);
		byte[] all = new byte[256 * 4];
		for (int i = 0; i < all.length; i++)
			all[i] = (byte) i;
		assertEquals(8, ResourcePayloadScanner.entropy(all), 1e-9);
	}

	@Nonnull
	private static byte[] classBytes(int version) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(version, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	@Nonnull
	private static byte[] elf(int type) {
		byte[] bytes = new byte[64];
		bytes[0] = 0x7F;
		bytes[1] = 'E';
		bytes[2] = 'L';
		bytes[3] = 'F';
		bytes[4] = 2;
		bytes[5] = 1;
		bytes[16] = (byte) type;
		return bytes;
	}

	@Nonnull
	private static byte[] pe(boolean dll) {
		byte[] bytes = new byte[256];
		bytes[0] = 'M';
		bytes[1] = 'Z';
		bytes[0x3C] = (byte) 0x80;
		bytes[0x80] = 'P';
		bytes[0x81] = 'E';
		if (dll)
			bytes[0x80 + 23] = 0x20;
		return bytes;
	}

	@Nonnull
	private static byte[] machO(boolean littleEndian, int fileType) {
		byte[] bytes = new byte[64];
		int[] magic = littleEndian ? new int[]{0xCF, 0xFA, 0xED, 0xFE} : new int[]{0xFE, 0xED, 0xFA, 0xCF};
		for (int i = 0; i < 4; i++)
			bytes[i] = (byte) magic[i];
		bytes[littleEndian ? 12 : 15] = (byte) fileType;
		return bytes;
	}

	@Nonnull
	private static byte[] fatMachO() {
		byte[] thin = machO(true, 6);
		byte[] bytes = new byte[4096 + thin.length];
		int[] magic = {0xCA, 0xFE, 0xBA, 0xBE};
		for (int i = 0; i < 4; i++)
			bytes[i] = (byte) magic[i];
		bytes[7] = 1;
		bytes[11] = 7;
		bytes[18] = 0x10;
		System.arraycopy(thin, 0, bytes, 4096, thin.length);
		return bytes;
	}

	@Nonnull
	private static byte[] random(int size) {
		byte[] bytes = new byte[size];
		new Random(1234).nextBytes(bytes);
		return bytes;
	}

	@Nonnull
	private static byte[] withMagic(@Nonnull byte[] body, int... magic) {
		byte[] bytes = body.clone();
		for (int i = 0; i < magic.length; i++)
			bytes[i] = (byte) magic[i];
		return bytes;
	}
}
