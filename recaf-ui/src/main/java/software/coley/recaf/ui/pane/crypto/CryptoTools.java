package software.coley.recaf.ui.pane.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.services.window.WindowManager;
import software.coley.recaf.ui.control.ActionMenuItem;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.window.CryptoToolsWindow;
import software.coley.recaf.util.ClipboardUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.crypto.AutoDecoder;
import software.coley.recaf.util.crypto.ByteFormat;
import software.coley.recaf.util.crypto.Codec;
import software.coley.recaf.util.crypto.CryptoException;
import software.coley.recaf.util.crypto.CryptoStep;
import software.coley.recaf.util.crypto.DataInspector;
import software.coley.recaf.util.crypto.HashAlgorithm;
import software.coley.recaf.util.crypto.Recipe;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Opens the crypto tools window, and builds the compact crypto submenu for context menus of strings and files.
 */
@ApplicationScoped
public class CryptoTools {
	/** Above this size, decode previews in context menus are skipped to keep the menu responsive. */
	private static final int MAX_PREVIEW_INPUT = 1024 * 1024;
	private static final int PREVIEW_CHARS = 40;
	private static final List<HashAlgorithm> MENU_HASHES = List.of(HashAlgorithm.MD5, HashAlgorithm.SHA1,
			HashAlgorithm.SHA256, HashAlgorithm.SHA512, HashAlgorithm.CRC32);
	private static final List<Codec> MENU_ENCODINGS = List.of(Codec.BASE64, Codec.HEX, Codec.URL, Codec.JAVA_ESCAPE);
	private final Instance<CryptoToolsWindow> windowProvider;
	private final WindowManager windowManager;
	private CryptoToolsWindow window;

	@Inject
	public CryptoTools(@Nonnull Instance<CryptoToolsWindow> windowProvider, @Nonnull WindowManager windowManager) {
		this.windowProvider = windowProvider;
		this.windowManager = windowManager;
	}

	/**
	 * Shows the crypto tools window, reusing it when already open.
	 *
	 * @return The window's content.
	 */
	@Nonnull
	public CryptoToolsPane open() {
		if (window == null) {
			CryptoToolsWindow created = windowProvider.get();
			created.setOnHidden(e -> {
				created.getPane().close();
				windowProvider.destroy(created);
				if (window == created)
					window = null;
			});
			windowManager.registerAnonymous(created);
			window = created;
		}
		window.show();
		window.toFront();
		window.requestFocus();
		return window.getPane();
	}

	/**
	 * @param sourceName
	 * 		Where the text came from.
	 * @param text
	 * 		Text to load as input.
	 */
	public void openText(@Nonnull String sourceName, @Nonnull String text) {
		open().setInput(sourceName, text);
	}

	/**
	 * @param sourceName
	 * 		Where the bytes came from.
	 * @param data
	 * 		Bytes to load as input.
	 */
	public void openBytes(@Nonnull String sourceName, @Nonnull byte[] data) {
		open().setInput(sourceName, data);
	}

	/**
	 * @return Enabled steps of the open window's recipe, or an empty list if the window is closed.
	 */
	@Nonnull
	public List<CryptoStep> currentRecipe() {
		return window == null ? List.of() : window.getPane().getSteps();
	}

	/**
	 * @param value
	 * 		String constant, such as a literal in decompiled code.
	 *
	 * @return Compact crypto submenu for the string.
	 */
	@Nonnull
	public Menu createStringMenu(@Nonnull String value) {
		Source source = new Source(Lang.get("crypto.input.string-literal"), () -> textToBytes(value), value);
		return createMenu(source);
	}

	/**
	 * @param name
	 * 		File name.
	 * @param data
	 * 		Supplier of the file contents, called when the menu opens.
	 *
	 * @return Compact crypto submenu for the file.
	 */
	@Nonnull
	public Menu createBytesMenu(@Nonnull String name, @Nonnull Supplier<byte[]> data) {
		return createMenu(new Source(name, data, null));
	}

	/**
	 * Encodes text the same way {@link CryptoToolsPane#setInput(String, String)} does: as Latin-1 when every char fits
	 * in a byte and some are above ASCII, which is how binary data hidden in string constants round-trips, else UTF-8.
	 */
	@Nonnull
	static byte[] textToBytes(@Nonnull String text) {
		boolean binaryChars = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c > 0xFF)
				return text.getBytes(StandardCharsets.UTF_8);
			if (c >= 0x80)
				binaryChars = true;
		}
		return binaryChars ? ByteFormat.toLatin1(text) : text.getBytes(StandardCharsets.UTF_8);
	}

	@Nonnull
	private Menu createMenu(@Nonnull Source source) {
		Menu menu = new Menu(Lang.get("crypto.menu"), new FontIconView(CarbonIcons.PASSWORD));
		menu.getItems().add(new MenuItem(Lang.get("crypto.menu.loading")));
		menu.setOnShowing(e -> populate(menu, source));
		return menu;
	}

	private void populate(@Nonnull Menu menu, @Nonnull Source source) {
		byte[] data = source.data().get();
		boolean previews = data.length <= MAX_PREVIEW_INPUT;
		List<MenuItem> items = new ArrayList<>();

		items.add(new ActionMenuItem(Lang.get("crypto.menu.open"), new FontIconView(CarbonIcons.LAUNCH), () -> openSource(source, data, List.of())));
		items.add(new ActionMenuItem(Lang.get("crypto.menu.brute"), new FontIconView(CarbonIcons.CHIP), () -> {
			openSource(source, data, List.of());
			open().showBruteForce();
		}));
		List<CryptoStep> recipe = currentRecipe();
		if (!recipe.isEmpty())
			items.add(stepsItem(Lang.get("crypto.menu.recipe"), CarbonIcons.PLAY, source, data, recipe, previews));

		List<MenuItem> decodes = new ArrayList<>();
		if (previews) {
			AutoDecoder.Result auto = AutoDecoder.decode(data);
			if (auto.found() && auto.steps().size() > 1) {
				String chain = auto.steps().stream().map(s -> ((CryptoStep.CodecStep) s).codec().displayName()).collect(Collectors.joining(" → "));
				decodes.add(stepsItem(Lang.get("crypto.menu.auto") + " (" + chain + ")", CarbonIcons.MAGIC_WAND, source, data, auto.steps(), true));
			}
			for (CryptoStep step : AutoDecoder.candidates(data))
				decodes.add(stepsItem(step.describe(), CarbonIcons.UNLOCKED, source, data, List.of(step), true));
		}
		if (!decodes.isEmpty()) {
			items.add(new SeparatorMenuItem());
			items.addAll(decodes);
		}

		items.add(new SeparatorMenuItem());
		Menu hashes = new Menu(Lang.get("crypto.menu.copy-hash"), new FontIconView(CarbonIcons.FINGERPRINT_RECOGNITION));
		for (HashAlgorithm algorithm : MENU_HASHES) {
			if (previews) {
				String hash = HexFormat.of().formatHex(algorithm.hash(data));
				hashes.getItems().add(new ActionMenuItem(algorithm.displayName() + "   " + shorten(hash, 24), () -> ClipboardUtil.copyString(hash)));
			} else {
				hashes.getItems().add(new ActionMenuItem(algorithm.displayName(),
						() -> ClipboardUtil.copyString(HexFormat.of().formatHex(algorithm.hash(data)))));
			}
		}
		items.add(hashes);
		Menu encodings = new Menu(Lang.get("crypto.menu.copy-encoded"), new FontIconView(CarbonIcons.CODE));
		for (Codec codec : MENU_ENCODINGS) {
			encodings.getItems().add(new ActionMenuItem(codec.displayName(), () -> {
				try {
					ClipboardUtil.copyString(new String(codec.encode(data), StandardCharsets.UTF_8));
				} catch (CryptoException ignored) {
					// Encoding into text cannot fail for these codecs.
				}
			}));
		}
		items.add(encodings);
		menu.getItems().setAll(items);
	}

	@Nonnull
	private MenuItem stepsItem(@Nonnull String label, @Nonnull Ikon icon, @Nonnull Source source,
	                           @Nonnull byte[] data, @Nonnull List<CryptoStep> steps, boolean preview) {
		String text = label;
		Recipe.Result result = null;
		if (preview) {
			result = Recipe.run(steps, data);
			text += result.isSuccess() ? ":  " + DataInspector.preview(result.output(), PREVIEW_CHARS) :
					"  (" + Lang.get("crypto.menu.fails") + ")";
		}
		Recipe.Result computed = result;
		return new ActionMenuItem(text, new FontIconView(icon), () -> {
			Recipe.Result shown = computed != null ? computed : Recipe.run(steps, data);
			new CryptoResultPopup(shown, label, () -> openSource(source, data, steps)).show();
		});
	}

	private void openSource(@Nonnull Source source, @Nonnull byte[] data, @Nonnull List<CryptoStep> steps) {
		CryptoToolsPane pane = open();
		if (source.text() != null)
			pane.setInput(source.name(), source.text());
		else
			pane.setInput(source.name(), data);
		pane.setSteps(steps);
	}

	@Nonnull
	private static String shorten(@Nonnull String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "…";
	}

	/**
	 * @param name
	 * 		Display name of the source.
	 * @param data
	 * 		Supplier of its bytes.
	 * @param text
	 * 		Original text for string sources, so the window can show it as typed, or {@code null} for binary sources.
	 */
	private record Source(@Nonnull String name, @Nonnull Supplier<byte[]> data, @Nullable String text) {}
}
