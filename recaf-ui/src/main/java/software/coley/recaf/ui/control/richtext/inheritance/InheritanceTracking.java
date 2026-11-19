package software.coley.recaf.ui.control.richtext.inheritance;

import jakarta.annotation.Nonnull;
import software.coley.collections.Unchecked;
import software.coley.recaf.ui.control.richtext.AbstractLineItemTracking;
import software.coley.recaf.ui.control.richtext.Editor;

/**
 * Tracking for method inheritance icons to display in an {@link Editor}.
 *
 * @author Matt Coley
 */
public class InheritanceTracking extends AbstractLineItemTracking<Inheritance, InheritanceInvalidationListener> {
	/** Key for {@link Editor#getComponent(java.lang.String)}. */
	public static final String COMPONENT_KEY = "inheritance-tracking";
	private volatile int directionCount = -1;

	@Override
	protected void notifyListeners(@Nonnull String failureMessage) {
		synchronized (items) {
			directionCount = -1;
		}
		Unchecked.checkedForEach(listeners, InheritanceInvalidationListener::onInheritanceInvalidation,
				(listener, t) -> logger.error(failureMessage, t));
	}

	/** @return Number of distinct inheritance directions used anywhere in the editor gutter. */
	public int getDirectionCount() {
		int cached = directionCount;
		if (cached >= 0)
			return cached;
		synchronized (items) {
			if (directionCount >= 0)
				return directionCount;
			boolean hasParent = false;
			boolean hasChild = false;
			for (var line : items.values()) {
				for (Inheritance inheritance : line) {
					hasParent |= inheritance instanceof Inheritance.Parent;
					hasChild |= inheritance instanceof Inheritance.Child;
					if (hasParent && hasChild) {
						directionCount = 2;
						return directionCount;
					}
				}
			}
			directionCount = hasParent || hasChild ? 1 : 0;
			return directionCount;
		}
	}

	@Override
	protected int getLine(@Nonnull Inheritance item) {
		return item.line();
	}

	@Override
	protected Inheritance withLine(@Nonnull Inheritance item, int newLine) {
		return item.withLine(newLine);
	}
}
