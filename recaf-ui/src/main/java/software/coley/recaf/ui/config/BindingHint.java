package software.coley.recaf.ui.config;

import jakarta.annotation.Nonnull;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import software.coley.recaf.util.PlatformType;

import java.util.function.Supplier;

/**
 * A {@link KeyCombination} that only displays a {@link Binding} as the accelerator text of a {@link MenuItem}.
 * <p/>
 * Recaf's keybindings are handled by scene level key handlers. A real accelerator would fire the menu action
 * a second time, so this never matches any event.
 */
public class BindingHint extends KeyCombination {
	private final Supplier<Binding> bindingSupplier;
	private final PlatformType platform;

	/**
	 * @param bindingSupplier
	 * 		Supplier of the binding to display. Queried each time the text is requested,
	 * 		so that rebinding in the settings is reflected without a restart.
	 */
	public BindingHint(@Nonnull Supplier<Binding> bindingSupplier) {
		this(bindingSupplier, null);
	}

	/**
	 * @param bindingSupplier
	 * 		Supplier of the binding to display.
	 * @param platform
	 * 		Platform to display the binding for, or {@code null} for the current platform.
	 */
	BindingHint(@Nonnull Supplier<Binding> bindingSupplier, PlatformType platform) {
		this.bindingSupplier = bindingSupplier;
		this.platform = platform;
	}

	/**
	 * Sets a display-only accelerator on the given item.
	 *
	 * @param item
	 * 		Item to show the binding on.
	 * @param bindingSupplier
	 * 		Supplier of the binding to display.
	 * @param <T>
	 * 		Item type.
	 *
	 * @return The given item.
	 */
	@Nonnull
	public static <T extends MenuItem> T applyTo(@Nonnull T item, @Nonnull Supplier<Binding> bindingSupplier) {
		item.setAccelerator(new BindingHint(bindingSupplier));
		return item;
	}

	/**
	 * Replaces the accelerator of the given item with a fresh hint, if it is a hint. The menu skin only re-reads the
	 * displayed text when the accelerator instance changes, so this is how a rebind shows up in an already built menu.
	 *
	 * @param item
	 * 		Item to refresh.
	 */
	public static void refresh(@Nonnull MenuItem item) {
		if (item.getAccelerator() instanceof BindingHint hint)
			item.setAccelerator(new BindingHint(hint.bindingSupplier, hint.platform));
	}

	@Override
	public boolean match(@Nonnull KeyEvent event) {
		return false;
	}

	@Nonnull
	@Override
	public String getDisplayText() {
		Binding binding = bindingSupplier.get();
		if (binding.isEmpty())
			return "";
		return platform == null ? binding.displayText() : binding.displayText(platform);
	}

	@Nonnull
	@Override
	public String getName() {
		return getDisplayText();
	}

	@Override
	public boolean equals(Object o) {
		return this == o;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}
}
