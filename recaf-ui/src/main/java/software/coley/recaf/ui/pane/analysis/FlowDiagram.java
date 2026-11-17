package software.coley.recaf.ui.pane.analysis;

import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Text;

import java.util.*;
import java.util.function.Consumer;

/** Small bounded, pannable graph shared by the two independent static investigation views. */
final class FlowDiagram extends ScrollPane {
	record Vertex(String id, String label, String detail, int level, boolean accent, Object value) {}
	record Link(String from, String to, String label, boolean assumed, String detail) {
		Link(String from, String to, String label, boolean assumed) { this(from, to, label, assumed, label); }
	}
	private final Pane plot = new Pane();
	private final Group zoom = new Group(plot);

	FlowDiagram() {
		setId("flow-diagram"); setContent(new Group(zoom)); setPannable(true); setPadding(new Insets(8));
		setOnScroll(event -> {
			if (!event.isControlDown()) return;
			double scale = Math.max(0.35, Math.min(1.8, zoom.getScaleX() * (event.getDeltaY() > 0 ? 1.1 : 0.9)));
			zoom.setScaleX(scale); zoom.setScaleY(scale); event.consume();
		});
	}

	void fit() {
		double scale = Math.max(0.35, Math.min(1, Math.min(getViewportBounds().getWidth() / plot.getPrefWidth(), getViewportBounds().getHeight() / plot.getPrefHeight())));
		zoom.setScaleX(scale); zoom.setScaleY(scale); setHvalue(0); setVvalue(0);
	}

	void show(List<Vertex> vertices, List<Link> links, Consumer<Vertex> selected) {
		plot.getChildren().clear();
		Map<String, double[]> positions = new HashMap<>();
		Map<Integer, Integer> rows = new HashMap<>();
		double width = 300, height = 180;
		for (Vertex vertex : vertices) {
			int row = rows.merge(vertex.level(), 1, Integer::sum) - 1;
			double x = 32 + vertex.level() * 330.0, y = 32 + row * 132.0;
			positions.put(vertex.id(), new double[]{x, y}); width = Math.max(width, x + 290); height = Math.max(height, y + 130);
		}
		for (Link link : links) {
			double[] from = positions.get(link.from()), to = positions.get(link.to());
			if (from == null || to == null) continue;
			double x1 = from[0] + 250, y1 = from[1] + 45, x2 = to[0], y2 = to[1] + 45;
			CubicCurve curve = new CubicCurve(x1, y1, x1 + 55, y1, x2 - 55, y2, x2, y2);
			curve.setFill(Color.TRANSPARENT); curve.setStroke(Color.web(link.assumed() ? "#b88d52" : "#75889c")); curve.setStrokeWidth(1.5);
			if (link.assumed()) curve.getStrokeDashArray().addAll(6.0, 4.0);
			Polygon arrow = new Polygon(x2, y2, x2 - 7, y2 - 4, x2 - 7, y2 + 4); arrow.setFill(curve.getStroke());
			Tooltip.install(curve, new Tooltip(link.detail()));
			Text label = new Text(link.label().length() > 28 ? link.label().substring(0, 25) + "…" : link.label());
			label.setFill(Color.web("#8797a7")); label.setStyle("-fx-font-size: 10px;"); label.setX((x1 + x2) / 2 - 28); label.setY((y1 + y2) / 2 - 9);
			plot.getChildren().addAll(curve, arrow, label);
		}
		for (Vertex vertex : vertices) {
			double[] position = positions.get(vertex.id());
			Button node = new Button(vertex.label()); node.setWrapText(true); node.setPrefSize(250, 90);
			node.setLayoutX(position[0]); node.setLayoutY(position[1]); node.setUserData(vertex.value());
			node.getStyleClass().add(vertex.accent() ? "accent" : "flat");
			node.setStyle("-fx-border-color: " + (vertex.accent() ? "-color-accent-emphasis" : "-color-border-default") + "; -fx-border-radius: 5; -fx-alignment: center-left; -fx-padding: 12;");
			node.setTooltip(new Tooltip(vertex.detail())); node.setOnAction(_ -> selected.accept(vertex));
			plot.getChildren().add(node);
		}
		plot.setPrefSize(width, height);
	}
}
