package software.coley.recaf.ui.control.richtext.inheritance;

import org.junit.jupiter.api.Test;
import software.coley.recaf.info.member.BasicMethodMember;
import software.coley.recaf.path.ClassMemberPathNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InheritanceTrackingTest {
	@Test
	void refreshesGutterDirectionsWhenMarkersChange() {
		InheritanceTracking tracking = new InheritanceTracking();
		var method = new ClassMemberPathNode(new BasicMethodMember("run", "()V", null, 0, List.of()));
		assertEquals(0, tracking.getDirectionCount());
		tracking.addItems(List.of(new Inheritance.Parent(1, method), new Inheritance.Child(2, method)));
		assertEquals(2, tracking.getDirectionCount());
		tracking.removeByLine(2);
		assertEquals(1, tracking.getDirectionCount());
		tracking.clear();
		assertEquals(0, tracking.getDirectionCount());
	}
}
