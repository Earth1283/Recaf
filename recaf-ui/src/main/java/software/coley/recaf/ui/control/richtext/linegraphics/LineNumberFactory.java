package software.coley.recaf.ui.control.richtext.linegraphics;

import jakarta.annotation.Nonnull;
import javafx.scene.text.Text;
import org.fxmisc.richtext.CodeArea;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.util.StringUtil;

/**
 * Graphic factory to draw line numbers.
 *
 * @author Matt Coley
 */
public class LineNumberFactory extends AbstractLineGraphicFactory {
	private CodeArea codeArea;

	/**
	 * New line number factory.
	 */
	public LineNumberFactory() {
		super(P_LINE_NUMBERS);
	}

	@Override
	public void install(@Nonnull Editor editor) {
		codeArea = editor.getCodeArea();
	}

	@Override
	public void uninstall(@Nonnull Editor editor) {
		codeArea = null;
	}

	@Override
	public void apply(@Nonnull LineContainer container, int paragraph) {
		if (codeArea == null) return;

		int digits = computeDigits(codeArea.getParagraphs().size());
		Text number = new Text(StringUtil.fillLeft(digits, " ", String.valueOf(paragraph + 1)));
		number.getStyleClass().add("text");
		container.addHorizontal(number);
	}

	private static int computeDigits(int size) {
		return (int) Math.floor(Math.log10(size)) + 1;
	}
}
