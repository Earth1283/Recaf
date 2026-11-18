package software.coley.recaf.ui.control;

import javafx.animation.Animation;
import javafx.animation.TranslateTransition;
import javafx.scene.layout.Pane;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/** A 16px Java archive glyph. Only the steam moves while the cup is hovered. */
public class CoffeeIcon extends Pane {
	public CoffeeIcon() {
		setMinSize(16, 16);
		setPrefSize(16, 16);
		setMaxSize(16, 16);
		setPickOnBounds(true);
		SVGPath cup = path("M2 7h9v2.5a4.5 4.5 0 0 1-9 0z M11 7.5h1.5a2 2 0 0 1 0 4H11 M1.5 15h11");
		SVGPath steam = path("M4.5 5.5c2-1.5-1.5-2.1.4-3.6 M8 5.5c2-1.5-1.5-2.1.4-3.6");
		getChildren().addAll(cup, steam);
		TranslateTransition drift = new TranslateTransition(Duration.millis(620), steam);
		drift.setFromY(0);
		drift.setToY(-1.3);
		drift.setAutoReverse(true);
		drift.setCycleCount(Animation.INDEFINITE);
		sceneProperty().addListener((obs, old, scene) -> {
			if (scene == null) {
				drift.stop();
				steam.setTranslateY(0);
			}
		});
		hoverProperty().addListener((obs, old, active) -> {
			if (active && getScene() != null) {
				drift.playFromStart();
			} else {
				drift.stop();
				steam.setTranslateY(0);
			}
		});
	}

	private static SVGPath path(String data) {
		SVGPath path = new SVGPath();
		path.setContent(data);
		path.setManaged(false);
		path.getStyleClass().add("coffee-stroke");
		return path;
	}
}
