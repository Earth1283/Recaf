package software.coley.recaf.ui.pane.crypto;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.ToStringConverter;
import software.coley.recaf.util.crypto.ByteFormat;
import software.coley.recaf.util.crypto.CipherAlgorithm;
import software.coley.recaf.util.crypto.CipherMode;
import software.coley.recaf.util.crypto.Codec;
import software.coley.recaf.util.crypto.CryptoException;
import software.coley.recaf.util.crypto.CryptoStep;
import software.coley.recaf.util.crypto.HashAlgorithm;
import software.coley.recaf.util.crypto.IvSource;
import software.coley.recaf.util.crypto.KeyDerivation;
import software.coley.recaf.util.crypto.PaddingScheme;
import software.coley.recaf.util.crypto.RsaCipher;
import software.coley.recaf.util.crypto.SymmetricCipherSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Editable model of one recipe step, backing both its list cell and its editor form.
 */
public abstract sealed class StepModel permits StepModel.CodecModel, StepModel.CipherModel, StepModel.RsaModel,
		StepModel.ByteKeyModel, StepModel.HashModel {
	private final BooleanProperty enabled = new SimpleBooleanProperty(true);
	private final StringProperty title = new SimpleStringProperty();
	private final ObjectProperty<String> error = new SimpleObjectProperty<>();
	private final List<Runnable> listeners = new ArrayList<>();
	private Node editor;
	private boolean loading;

	/**
	 * Kinds of steps the user can add.
	 */
	public enum Kind {
		CODEC("crypto.step.codec"),
		CIPHER("crypto.step.cipher"),
		RSA("crypto.step.rsa"),
		BYTE_KEY("crypto.step.byte-key"),
		HASH("crypto.step.hash");

		private final String langKey;

		Kind(@Nonnull String langKey) {
			this.langKey = langKey;
		}

		/**
		 * @return Translation key of the kind's name.
		 */
		@Nonnull
		public String langKey() {
			return langKey;
		}

		/**
		 * @return New step model of this kind with default settings.
		 */
		@Nonnull
		public StepModel create() {
			return switch (this) {
				case CODEC -> new CodecModel();
				case CIPHER -> new CipherModel();
				case RSA -> new RsaModel();
				case BYTE_KEY -> new ByteKeyModel();
				case HASH -> new HashModel();
			};
		}
	}

	/**
	 * @param step
	 * 		Step to edit.
	 *
	 * @return Model holding the step's settings.
	 */
	@Nonnull
	public static StepModel of(@Nonnull CryptoStep step) {
		StepModel model = switch (step) {
			case CryptoStep.CodecStep ignored -> new CodecModel();
			case CryptoStep.CipherStep ignored -> new CipherModel();
			case CryptoStep.RsaStep ignored -> new RsaModel();
			case CryptoStep.ByteKeyStep ignored -> new ByteKeyModel();
			case CryptoStep.HashStep ignored -> new HashModel();
		};
		model.editor();
		model.loading = true;
		try {
			model.load(step);
		} finally {
			model.loading = false;
		}
		model.changed();
		return model;
	}

	/**
	 * @return Enabled state. Disabled steps are skipped.
	 */
	@Nonnull
	public BooleanProperty enabledProperty() {
		return enabled;
	}

	/**
	 * @return Current description of the step.
	 */
	@Nonnull
	public StringProperty titleProperty() {
		return title;
	}

	/**
	 * @return Error of the last run of this step, or {@code null} if it succeeded or did not run.
	 */
	@Nonnull
	public ObjectProperty<String> errorProperty() {
		return error;
	}

	/**
	 * @param listener
	 * 		Called when any setting changes.
	 */
	public void addListener(@Nonnull Runnable listener) {
		listeners.add(listener);
	}

	/**
	 * @return Editor form, created on first use.
	 */
	@Nonnull
	public Node editor() {
		if (editor == null) {
			VBox box = new VBox(8);
			box.setPadding(new Insets(10));
			loading = true;
			try {
				createEditor(box);
			} finally {
				loading = false;
			}
			editor = box;
			refreshTitle();
		}
		return editor;
	}

	/**
	 * @return Step built from the current settings.
	 *
	 * @throws CryptoException
	 * 		When a setting is invalid, such as malformed hex in a key.
	 */
	@Nonnull
	public abstract CryptoStep build() throws CryptoException;

	/**
	 * Adds this step's controls to its editor.
	 *
	 * @param box
	 * 		Editor container.
	 */
	protected abstract void createEditor(@Nonnull VBox box);

	/**
	 * @param step
	 * 		Step whose settings to copy into the controls.
	 */
	protected abstract void load(@Nonnull CryptoStep step);

	/**
	 * Called by controls when a setting changes.
	 */
	protected void changed() {
		if (loading)
			return;
		refreshTitle();
		listeners.forEach(Runnable::run);
	}

	private void refreshTitle() {
		try {
			title.set(build().describe());
		} catch (CryptoException | RuntimeException ex) {
			title.set(Lang.get("crypto.step.invalid") + ": " + ex.getMessage());
		}
	}

	@Nonnull
	protected <T> ComboBox<T> combo(@Nonnull List<T> items, @Nonnull T initial, @Nonnull Function<T, String> names) {
		ComboBox<T> box = new ComboBox<>();
		box.getItems().setAll(items);
		box.setValue(initial);
		box.setConverter(ToStringConverter.from(names));
		box.setMaxWidth(Double.MAX_VALUE);
		box.valueProperty().addListener((ob, old, cur) -> changed());
		return box;
	}

	@Nonnull
	protected <T> ComboBox<T> combo(@Nonnull T[] items, @Nonnull T initial) {
		return combo(Arrays.asList(items), initial, String::valueOf);
	}

	@Nonnull
	protected ComboBox<Boolean> direction(@Nonnull String forwardKey, @Nonnull String reverseKey, boolean forward) {
		return combo(List.of(false, true), forward, b -> Lang.get(b ? forwardKey : reverseKey));
	}

	@Nonnull
	protected BytesField bytes(@Nonnull String promptKey, @Nonnull ByteFormat format, boolean multiline) {
		BytesField field = new BytesField(Lang.get(promptKey), format, multiline);
		field.addListener(this::changed);
		return field;
	}

	@Nonnull
	protected Spinner<Integer> spinner(int min, int max, int initial) {
		Spinner<Integer> spinner = new Spinner<>(min, max, initial);
		spinner.setEditable(true);
		spinner.setMaxWidth(Double.MAX_VALUE);
		spinner.valueProperty().addListener((ob, old, cur) -> changed());
		return spinner;
	}

	@Nonnull
	protected static HBox row(@Nonnull VBox box, @Nonnull String labelKey, @Nonnull Node control) {
		Label label = new Label(Lang.get(labelKey));
		label.setMinWidth(150);
		label.setPrefWidth(150);
		HBox row = new HBox(8, label, control);
		row.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(control, Priority.ALWAYS);
		box.getChildren().add(row);
		return row;
	}

	protected static void showWhen(@Nonnull Node row, @Nonnull ObservableBooleanValue condition) {
		row.visibleProperty().bind(condition);
		row.managedProperty().bind(condition);
	}

	/**
	 * Encode/decode, compress/decompress.
	 */
	public static final class CodecModel extends StepModel {
		private ComboBox<Codec> codec;
		private ComboBox<Boolean> encode;

		@Override
		protected void createEditor(@Nonnull VBox box) {
			codec = combo(Codec.values(), Codec.BASE64);
			encode = direction("crypto.direction.encode", "crypto.direction.decode", false);
			row(box, "crypto.field.codec", codec);
			row(box, "crypto.field.direction", encode);
		}

		@Nonnull
		@Override
		public CryptoStep build() {
			return new CryptoStep.CodecStep(codec.getValue(), encode.getValue());
		}

		@Override
		protected void load(@Nonnull CryptoStep step) {
			CryptoStep.CodecStep codecStep = (CryptoStep.CodecStep) step;
			codec.setValue(codecStep.codec());
			encode.setValue(codecStep.encode());
		}
	}

	/**
	 * Symmetric cipher with every mode, padding, key derivation and IV option.
	 */
	public static final class CipherModel extends StepModel {
		private static final List<Integer> TAG_SIZES = List.of(128, 120, 112, 104, 96, 64, 32);
		private ComboBox<Boolean> encrypt;
		private ComboBox<CipherAlgorithm> algorithm;
		private ComboBox<CipherMode> mode;
		private ComboBox<PaddingScheme> padding;
		private BytesField key;
		private ComboBox<KeyDerivation.Type> kdf;
		private ComboBox<HashAlgorithm> digest;
		private BytesField salt;
		private Spinner<Integer> iterations;
		private Spinner<Integer> scryptR;
		private Spinner<Integer> scryptP;
		private ComboBox<Integer> keySize;
		private ComboBox<IvSource> ivSource;
		private BytesField iv;
		private Spinner<Integer> ivSize;
		private ComboBox<Integer> tagBits;
		private BytesField aad;

		@Override
		protected void createEditor(@Nonnull VBox box) {
			encrypt = direction("crypto.direction.encrypt", "crypto.direction.decrypt", false);
			algorithm = combo(CipherAlgorithm.values(), CipherAlgorithm.AES);
			mode = combo(CipherMode.values(), CipherMode.ECB);
			padding = combo(PaddingScheme.values(), PaddingScheme.PKCS7);
			key = bytes("crypto.prompt.key", ByteFormat.UTF8, false);
			kdf = combo(KeyDerivation.Type.values(), KeyDerivation.Type.RAW);
			digest = combo(Arrays.stream(HashAlgorithm.values()).filter(h -> !h.isChecksum()).toList(), HashAlgorithm.SHA256, HashAlgorithm::displayName);
			salt = bytes("crypto.prompt.salt", ByteFormat.HEX, false);
			iterations = spinner(1, Integer.MAX_VALUE, 1);
			scryptR = spinner(1, 1024, 8);
			scryptP = spinner(1, 1024, 1);
			keySize = combo(List.of(0), 0, CipherModel::keySizeName);
			ivSource = combo(IvSource.values(), IvSource.ZERO);
			iv = bytes("crypto.prompt.iv", ByteFormat.HEX, false);
			ivSize = spinner(0, 64, 0);
			tagBits = combo(TAG_SIZES, 128, String::valueOf);
			aad = bytes("crypto.prompt.aad", ByteFormat.UTF8, false);

			ObjectProperty<CipherAlgorithm> alg = new SimpleObjectProperty<>();
			alg.bind(algorithm.valueProperty());
			BooleanProperty isBlock = new SimpleBooleanProperty();
			BooleanProperty usesPadding = new SimpleBooleanProperty();
			BooleanProperty usesIv = new SimpleBooleanProperty();
			BooleanProperty blockUsesIv = new SimpleBooleanProperty();
			BooleanProperty aead = new SimpleBooleanProperty();
			BooleanProperty derived = new SimpleBooleanProperty();
			Runnable sync = () -> {
				CipherAlgorithm a = algorithm.getValue();
				CipherMode m = mode.getValue();
				isBlock.set(a.isBlockCipher());
				usesPadding.set(a.isBlockCipher() && m.usesPadding());
				usesIv.set(a.isBlockCipher() ? m.usesIv() : a.nonceSize() > 0);
				blockUsesIv.set(a.isBlockCipher() && m.usesIv());
				aead.set(a.isAead() || a.isBlockCipher() && m.isAead());
				derived.set(kdf.getValue() != KeyDerivation.Type.RAW);
			};
			algorithm.valueProperty().addListener((ob, old, cur) -> {
				List<CipherMode> modes = Arrays.stream(CipherMode.values()).filter(m -> !cur.isBlockCipher() || m.supports(cur)).toList();
				CipherMode previousMode = mode.getValue();
				mode.getItems().setAll(modes);
				mode.setValue(modes.contains(previousMode) ? previousMode : CipherMode.ECB);
				Integer previousSize = keySize.getValue();
				List<Integer> sizes = new ArrayList<>();
				sizes.add(0);
				for (int size : cur.keySizes())
					sizes.add(size);
				keySize.getItems().setAll(sizes);
				keySize.setValue(sizes.contains(previousSize) ? previousSize : 0);
				sync.run();
			});
			mode.valueProperty().addListener((ob, old, cur) -> {
				if (cur != null)
					sync.run();
			});
			kdf.valueProperty().addListener((ob, old, cur) -> {
				applyDerivationDefaults(cur);
				sync.run();
			});
			keySize.getItems().setAll(0, 16, 24, 32);
			sync.run();

			row(box, "crypto.field.direction", encrypt);
			row(box, "crypto.field.algorithm", algorithm);
			showWhen(row(box, "crypto.field.mode", mode), isBlock);
			showWhen(row(box, "crypto.field.padding", padding), usesPadding);
			row(box, "crypto.field.key", key);
			row(box, "crypto.field.kdf", kdf);
			showWhen(row(box, "crypto.field.key-size", keySize), derived);
			showWhen(row(box, "crypto.field.digest", digest), kdf.valueProperty().isNotEqualTo(KeyDerivation.Type.RAW)
					.and(kdf.valueProperty().isNotEqualTo(KeyDerivation.Type.FIT))
					.and(kdf.valueProperty().isNotEqualTo(KeyDerivation.Type.SCRYPT)));
			showWhen(row(box, "crypto.field.salt", salt), kdf.valueProperty().isEqualTo(KeyDerivation.Type.PBKDF2)
					.or(kdf.valueProperty().isEqualTo(KeyDerivation.Type.OPENSSL_EVP))
					.or(kdf.valueProperty().isEqualTo(KeyDerivation.Type.OPENSSL_PBKDF2))
					.or(kdf.valueProperty().isEqualTo(KeyDerivation.Type.SCRYPT)));
			showWhen(row(box, "crypto.field.iterations", iterations), kdf.valueProperty().isEqualTo(KeyDerivation.Type.DIGEST)
					.or(kdf.valueProperty().isEqualTo(KeyDerivation.Type.PBKDF2))
					.or(kdf.valueProperty().isEqualTo(KeyDerivation.Type.OPENSSL_PBKDF2))
					.or(kdf.valueProperty().isEqualTo(KeyDerivation.Type.SCRYPT)));
			showWhen(row(box, "crypto.field.scrypt-r", scryptR), kdf.valueProperty().isEqualTo(KeyDerivation.Type.SCRYPT));
			showWhen(row(box, "crypto.field.scrypt-p", scryptP), kdf.valueProperty().isEqualTo(KeyDerivation.Type.SCRYPT));
			showWhen(row(box, "crypto.field.iv-source", ivSource), usesIv);
			showWhen(row(box, "crypto.field.iv", iv), usesIv.and(ivSource.valueProperty().isEqualTo(IvSource.EXPLICIT)));
			showWhen(row(box, "crypto.field.iv-size", ivSize), blockUsesIv);
			showWhen(row(box, "crypto.field.tag-bits", tagBits), aead);
			showWhen(row(box, "crypto.field.aad", aad), aead);
		}

		/**
		 * Applies the settings each derivation conventionally uses, such as OpenSSL's 10000 PBKDF2 iterations,
		 * when the user switches to it. Loading a saved step overwrites these afterwards.
		 */
		private void applyDerivationDefaults(@Nullable KeyDerivation.Type type) {
			if (type == null)
				return;
			int current = iterations.getValue();
			int wanted = switch (type) {
				case OPENSSL_PBKDF2 -> 10_000;
				case PBKDF2 -> current > 1 ? current : 65_536;
				case SCRYPT -> 16_384;
				default -> 1;
			};
			if (type == KeyDerivation.Type.DIGEST && current > 1 && current <= 100_000)
				wanted = current;
			iterations.getValueFactory().setValue(wanted);
			if (type == KeyDerivation.Type.OPENSSL_EVP || type == KeyDerivation.Type.OPENSSL_PBKDF2) {
				if (ivSource.getValue() == IvSource.ZERO)
					ivSource.setValue(IvSource.DERIVED);
				if (keySize.getValue() == 0 && algorithm.getValue() == CipherAlgorithm.AES)
					keySize.setValue(32);
			}
		}

		@Nonnull
		private static String keySizeName(@Nonnull Integer size) {
			return size == 0 ? Lang.get("crypto.field.key-size.default") : size + " B (" + size * 8 + "-bit)";
		}

		@Nonnull
		@Override
		public CryptoStep build() throws CryptoException {
			if (algorithm.getValue() == null || mode.getValue() == null || padding.getValue() == null
					|| kdf.getValue() == null || ivSource.getValue() == null)
				throw new CryptoException(Lang.get("crypto.step.incomplete"));
			KeyDerivation.Type type = kdf.getValue();
			byte[] saltBytes = salt.getBytesOrNull();
			int iter = iterations.getValue();
			KeyDerivation derivation = switch (type) {
				case RAW -> KeyDerivation.RAW;
				case FIT -> KeyDerivation.FIT;
				case DIGEST -> KeyDerivation.digest(digest.getValue(), iter);
				case PBKDF2 -> KeyDerivation.pbkdf2(digest.getValue(), saltBytes, iter);
				case OPENSSL_EVP -> KeyDerivation.openSsl(digest.getValue(), saltBytes);
				case OPENSSL_PBKDF2 -> KeyDerivation.openSslPbkdf2(digest.getValue(), saltBytes, iter);
				case SCRYPT -> KeyDerivation.scrypt(saltBytes, iter, scryptR.getValue(), scryptP.getValue());
			};
			SymmetricCipherSpec spec = new SymmetricCipherSpec(algorithm.getValue(), mode.getValue(), padding.getValue(),
					key.getBytes(), derivation, keySize.getValue() == null ? 0 : keySize.getValue(), ivSource.getValue(),
					iv.getBytesOrNull(), ivSize.getValue(), tagBits.getValue(), aad.getBytesOrNull());
			return new CryptoStep.CipherStep(spec, encrypt.getValue());
		}

		@Override
		protected void load(@Nonnull CryptoStep step) {
			CryptoStep.CipherStep cipher = (CryptoStep.CipherStep) step;
			SymmetricCipherSpec spec = cipher.spec();
			encrypt.setValue(cipher.encrypt());
			algorithm.setValue(spec.algorithm());
			mode.setValue(spec.mode());
			padding.setValue(spec.padding());
			key.setBytes(spec.keyMaterial());
			KeyDerivation derivation = spec.kdf();
			kdf.setValue(derivation.type());
			digest.setValue(derivation.digest());
			salt.setBytes(derivation.salt());
			iterations.getValueFactory().setValue(Math.max(1, derivation.iterations()));
			if (derivation.type() == KeyDerivation.Type.SCRYPT) {
				scryptR.getValueFactory().setValue(derivation.blockSize());
				scryptP.getValueFactory().setValue(derivation.parallelism());
			}
			keySize.setValue(spec.keySize());
			ivSource.setValue(spec.ivSource());
			iv.setBytes(spec.iv());
			ivSize.getValueFactory().setValue(spec.ivSize());
			tagBits.setValue(spec.tagBits());
			aad.setBytes(spec.aad());
		}
	}

	/**
	 * RSA with a PEM or DER key.
	 */
	public static final class RsaModel extends StepModel {
		private ComboBox<Boolean> encrypt;
		private ComboBox<RsaCipher.Padding> padding;
		private BytesField key;

		@Override
		protected void createEditor(@Nonnull VBox box) {
			encrypt = direction("crypto.direction.encrypt", "crypto.direction.decrypt", false);
			padding = combo(RsaCipher.Padding.values(), RsaCipher.Padding.PKCS1);
			key = bytes("crypto.prompt.rsa-key", ByteFormat.UTF8, true);
			row(box, "crypto.field.direction", encrypt);
			row(box, "crypto.field.padding", padding);
			row(box, "crypto.field.key", key);
			Label hint = new Label(Lang.get("crypto.hint.rsa"));
			hint.setWrapText(true);
			hint.getStyleClass().add("text-muted");
			box.getChildren().add(hint);
		}

		@Nonnull
		@Override
		public CryptoStep build() throws CryptoException {
			if (key.isBlank())
				throw new CryptoException(Lang.get("crypto.error.no-key"));
			return new CryptoStep.RsaStep(key.getBytes(), padding.getValue(), encrypt.getValue());
		}

		@Override
		protected void load(@Nonnull CryptoStep step) {
			CryptoStep.RsaStep rsa = (CryptoStep.RsaStep) step;
			encrypt.setValue(rsa.encrypt());
			padding.setValue(rsa.padding());
			key.setBytes(rsa.key());
		}
	}

	/**
	 * Repeating-key XOR, add or subtract.
	 */
	public static final class ByteKeyModel extends StepModel {
		private ComboBox<CryptoStep.ByteKeyStep.Operation> operation;
		private BytesField key;

		@Override
		protected void createEditor(@Nonnull VBox box) {
			operation = combo(CryptoStep.ByteKeyStep.Operation.values(), CryptoStep.ByteKeyStep.Operation.XOR);
			key = bytes("crypto.prompt.key", ByteFormat.HEX, false);
			row(box, "crypto.field.operation", operation);
			row(box, "crypto.field.key", key);
		}

		@Nonnull
		@Override
		public CryptoStep build() throws CryptoException {
			return new CryptoStep.ByteKeyStep(key.getBytes(), operation.getValue());
		}

		@Override
		protected void load(@Nonnull CryptoStep step) {
			CryptoStep.ByteKeyStep byteKey = (CryptoStep.ByteKeyStep) step;
			operation.setValue(byteKey.operation());
			key.setBytes(byteKey.key());
		}
	}

	/**
	 * Hash or HMAC.
	 */
	public static final class HashModel extends StepModel {
		private ComboBox<HashAlgorithm> algorithm;
		private CheckBox hmac;
		private BytesField key;

		@Override
		protected void createEditor(@Nonnull VBox box) {
			algorithm = combo(HashAlgorithm.values(), HashAlgorithm.SHA256);
			hmac = new CheckBox(Lang.get("crypto.field.hmac"));
			hmac.selectedProperty().addListener((ob, old, cur) -> changed());
			key = bytes("crypto.prompt.key", ByteFormat.UTF8, false);
			row(box, "crypto.field.algorithm", algorithm);
			row(box, "crypto.field.mac", hmac);
			showWhen(row(box, "crypto.field.key", key), hmac.selectedProperty());
		}

		@Nonnull
		@Override
		public CryptoStep build() throws CryptoException {
			return new CryptoStep.HashStep(algorithm.getValue(), hmac.isSelected() ? key.getBytes() : null);
		}

		@Override
		protected void load(@Nonnull CryptoStep step) {
			CryptoStep.HashStep hash = (CryptoStep.HashStep) step;
			algorithm.setValue(hash.algorithm());
			hmac.setSelected(hash.hmacKey() != null);
			key.setBytes(hash.hmacKey());
		}
	}
}
