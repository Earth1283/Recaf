package software.coley.recaf.ui.pane.editing.assembler;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import me.darknet.assembler.backend.dalvik.DalvikTargetContext;
import me.darknet.assembler.backend.jvm.JvmTargetContext;
import me.darknet.assembler.query.AssemblyQueries;
import me.darknet.assembler.target.TargetContext;
import me.darknet.assembler.query.resolution.Resolution;
import org.fxmisc.richtext.CharacterHit;
import org.fxmisc.richtext.CodeArea;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.analytics.logging.DebuggingLogger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.path.AssemblerPathData;
import software.coley.recaf.path.AssemblerPathNode;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.cell.context.ContextMenuProviderService;
import software.coley.recaf.services.cell.context.ContextSource;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.source.JavaContextActionSupport;
import software.coley.recaf.util.Lang;

import java.util.function.IntFunction;

/**
 * Enables context actions on an {@link Editor} within an {@link AssemblerPane}.
 * The AST of the last successful parse from the assembler is used to query for menus offered by {@link ContextMenuProviderService}.
 *
 * @author Matt Coley
 * @see JavaContextActionSupport Alternative for context actions on Java source.
 */
@Dependent
public class AssemblerContextActionSupport extends AstBuildConsumerComponent {
	private static final DebuggingLogger logger = Logging.get(AssemblerContextActionSupport.class);
	private final CellConfigurationService cellConfigurationService;
	private IntFunction<Runnable> labelRename = offset -> null;
	private ContextMenu menu;

	@Inject
	public AssemblerContextActionSupport(@Nonnull CellConfigurationService cellConfigurationService) {
		this.cellConfigurationService = cellConfigurationService;
	}

	void setLabelRename(@Nonnull IntFunction<Runnable> labelRename) {
		this.labelRename = labelRename;
	}

	@Override
	public void install(@Nonnull Editor editor) {
		super.install(editor);
		CodeArea area = editor.getCodeArea();
		area.setOnContextMenuRequested(e -> {
			if (menu != null) {
				menu.hide();
				menu = null;
			}

			// Check AST model has been generated
			if (astElements == null || path == null) {
				logger.warn("Could not request context menu, AST model not available");
				return;
			}

			// Convert the event position to line/column
			CharacterHit hit = area.hit(e.getX(), e.getY());

			// Sync caret
			area.moveTo(hit.getInsertionIndex());

			// Create menu
			ClassInfo classInfo = path.getValueOfType(ClassInfo.class);
			TargetContext target = classInfo != null && classInfo.isAndroidClass()
					? DalvikTargetContext.INSTANCE
					: JvmTargetContext.INSTANCE;
			Resolution resolution = AssemblyQueries.resolveAt(astElements, hit.getInsertionIndex(), target);
			AssemblerPathData data = new AssemblerPathData(editor, resolution);
			menu = cellConfigurationService.contextMenuOf(ContextSource.DECLARATION, new AssemblerPathNode(path, data));

			Runnable rename = labelRename.apply(hit.getInsertionIndex());
			if (rename != null) {
				if (menu == null)
					menu = new ContextMenu();
				var item = new MenuItem(Lang.get("assembler.rename-label.title"), new FontIconView(CarbonIcons.EDIT));
				item.setOnAction(event -> rename.run());
				menu.getItems().add(item);
			}

			// Show menu
			if (menu != null && !menu.getItems().isEmpty()) {
				menu.setAutoHide(true);
				menu.setHideOnEscape(true);
				menu.show(area.getScene().getWindow(), e.getScreenX(), e.getScreenY());
				menu.requestFocus();
			}
		});
	}

	@Override
	public void uninstall(@Nonnull Editor editor) {
		super.uninstall(editor);
		editor.getCodeArea().setOnContextMenuRequested(null);
	}
}
