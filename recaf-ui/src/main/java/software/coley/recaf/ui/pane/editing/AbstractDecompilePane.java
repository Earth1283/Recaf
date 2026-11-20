package software.coley.recaf.ui.pane.editing;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import jakarta.annotation.Nonnull;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableObject;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.info.properties.builtin.RemapOriginTaskProperty;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.IncompletePathException;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.decompile.NoopJvmDecompiler;
import software.coley.recaf.services.decompile.fallback.FallbackDecompiler;
import software.coley.recaf.services.info.association.FileTypeSyntaxAssociationService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.navigation.ClassNavigable;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.navigation.NavigationHistoryService;
import software.coley.recaf.services.navigation.UpdatableNavigable;
import software.coley.recaf.services.source.AstMapper;
import software.coley.recaf.services.source.AstResolveResult;
import software.coley.recaf.services.source.AstService;
import software.coley.recaf.services.source.ResolverAdapter;
import software.coley.recaf.services.tutorial.TutorialConfig;
import software.coley.recaf.ui.config.KeybindingConfig;
import software.coley.recaf.ui.control.BoundLabel;
import software.coley.recaf.ui.control.animation.LabelByteAnimationTransition;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.bracket.SelectedBracketTracking;
import software.coley.recaf.ui.control.richtext.highlight.SelectedWordHighlighting;
import software.coley.recaf.ui.control.richtext.problem.ProblemTracking;
import software.coley.recaf.ui.control.richtext.search.SearchBar;
import software.coley.recaf.ui.control.richtext.source.JavaContextActionSupport;
import software.coley.recaf.ui.control.richtext.suggest.TabCompletionConfig;
import software.coley.recaf.ui.control.richtext.suggest.java.JavaTabCompleter;
import software.coley.recaf.ui.control.richtext.suggest.typeindex.TypeIndexService;
import software.coley.recaf.ui.pane.editing.android.AndroidDecompilerPane;
import software.coley.recaf.ui.pane.editing.jvm.DecompilerPaneConfig;
import software.coley.recaf.ui.pane.editing.jvm.JvmDecompilerPane;
import software.coley.recaf.ui.pane.editing.text.TextConfig;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Keywords;
import software.coley.recaf.util.Lang;
import software.coley.recaf.util.StringDiff;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.sourcesolver.model.CompilationUnitModel;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Common outline for decompiler panes utilizing {@link JvmDecompiler}.
 * <br>
 * For Android content it is assumed {@link AndroidClassInfo#asJvmClass()} is implemented as a conversion process.
 *
 * @author Matt Coley
 * @see JvmDecompilerPane
 * @see AndroidDecompilerPane
 */
public class AbstractDecompilePane extends BorderPane implements ClassNavigable, UpdatableNavigable {
	private static final Logger logger = Logging.get(AbstractDecompilePane.class);
	protected final ObservableObject<JvmDecompiler> decompiler = new ObservableObject<>(NoopJvmDecompiler.getInstance());
	protected final ObservableBoolean decompileOutputErrored = new ObservableBoolean(false);
	protected final ObservableBoolean decompileInProgress = new ObservableBoolean(false);
	protected final AtomicBoolean updateLock = new AtomicBoolean();
	protected final ProblemTracking problemTracking = new ProblemTracking();
	protected final AstService astService;
	protected final JavaContextActionSupport contextActionSupport;
	protected final DecompilerManager decompilerManager;
	protected final DecompilerPaneConfig decompileConfig;
	protected final TutorialConfig tutorialConfig;
	protected final Editor editor;
	protected ClassPathNode path;
	private final Group astTools = new Group();
	private boolean recordsNavigationHistory = true;
	private boolean sourceWasEditable;
	private long decompileRequest;
	private DecompileFailurePane failurePane;

	protected AbstractDecompilePane(@Nonnull DecompilerPaneConfig decompileConfig,
	                                @Nonnull TutorialConfig tutorialConfig,
	                                @Nonnull KeybindingConfig keys,
	                                @Nonnull SearchBar searchBar,
	                                @Nonnull AstService astService,
	                                @Nonnull JavaContextActionSupport contextActionSupport,
	                                @Nonnull NavigationHistoryService navigationHistoryService,
	                                @Nonnull CellConfigurationService cellConfigurationService,
	                                @Nonnull FileTypeSyntaxAssociationService languageAssociation,
	                                @Nonnull DecompilerManager decompilerManager,
	                                @Nonnull TypeIndexService typeIndexService,
	                                @Nonnull TabCompletionConfig tabCompletionConfig,
	                                @Nonnull TextConfig textConfig,
	                                @Nonnull Actions actions) {
		this.astService = astService;
		this.contextActionSupport = contextActionSupport;
		this.decompilerManager = decompilerManager;
		this.decompileConfig = decompileConfig;
		this.tutorialConfig = tutorialConfig;

		decompiler.setValue(decompilerManager.getTargetJvmDecompiler());
		decompiler.addChangeListener((ob, old, cur) -> decompile());

		// Configure the editor
		editor = new Editor();
		languageAssociation.configureEditorSyntax("java", editor);
		if (textConfig.doHighlightWord())
			editor.setSelectedWordHighlighting(new SelectedWordHighlighting(Keywords.getKeywords()));
		if (textConfig.doTrackBrackets()) editor.setSelectedBracketTracking(new SelectedBracketTracking());
		editor.setProblemTracking(problemTracking);
		editor.getRootLineGraphicFactory().addDefaultCodeGraphicFactories();
		contextActionSupport.install(editor);
		editor.getCaretPosEventStream().addObserver(change -> {
			if (!recordsNavigationHistory)
				return;
			PathNode<?> enclosingPath = contextActionSupport.getEnclosingDeclarationPath(change.getNewValue(), false);
			if (enclosingPath != null)
				navigationHistoryService.record(enclosingPath);
		});
		if (tabCompletionConfig.isEnabledInJavaSource())
			editor.setTabCompleter(new JavaTabCompleter(contextActionSupport, cellConfigurationService, typeIndexService, tabCompletionConfig));
		searchBar.install(editor);

		// Setup common keybindings.
		addEventFilter(KeyEvent.KEY_PRESSED, e -> {
			if (isInspectingBytecode())
				return;
			if (keys.getAssembler().match(e)) {
				// Resolve what the caret position has, then handle opening the assembler for the resulting path.
				PathNode<?> enclosingPath = contextActionSupport.getEnclosingDeclarationPath(editor.getCodeArea().getCaretPosition(), true);
				if (enclosingPath != null) {
					try {
						actions.openAssembler(enclosingPath);
					} catch (IncompletePathException ex) {
						logger.warn("Cannot open assembler for the current location, path incomplete", ex);
					}
				}
			} else if (keys.getRename().match(e)) {
				// Resolve what the caret position has, then handle renaming on the generic result.
				AstResolveResult result = contextActionSupport.resolvePosition(editor.getCodeArea().getCaretPosition());
				if (result != null)
					actions.rename(result.path());
			} else if (keys.getComment().match(e)) {
				// Resolve what the caret position has, then handle comment editing on the generic result.
				AstResolveResult result = contextActionSupport.resolvePosition(editor.getCodeArea().getCaretPosition());
				if (result != null)
					actions.openCommentEditing(result.path());
			} else if (keys.getGoto().match(e)) {
				// Resolve what the caret position has, then handle navigating to the resulting path.
				AstResolveResult result = contextActionSupport.resolvePosition(editor.getCodeArea().getCaretPosition());
				if (result != null) {
					try {
						actions.gotoDeclaration(result.path());
					} catch (IncompletePathException ex) {
						// Should realistically never happen
						logger.warn("Cannot goto location, path incomplete", ex);
					}
				}
			}
		});

		// Keep shortcut-click navigation consistent across JVM and Android decompiler panes.
		addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
			if (isInspectingBytecode())
				return;
			if (e.getButton() == MouseButton.PRIMARY && e.isShortcutDown()) {
				// Resolve what the caret position has, then handle navigating to the resulting path.
				AstResolveResult result = contextActionSupport.resolvePosition(editor.getCodeArea().getCaretPosition());
				if (result != null) {
					try {
						actions.gotoDeclaration(result.path());
					} catch (IncompletePathException ex) {
						// Should realistically never happen
						logger.warn("Cannot goto location, path incomplete", ex);
					}
				}
			}
		});

		// Add overlay for when decompilation is in-progress
		DecompileProgressOverlay overlay = new DecompileProgressOverlay();
		decompileInProgress.addAsyncChangeListener((ob, old, cur) -> {
			ObservableList<Node> children = editor.getPrimaryStack().getChildren();
			if (cur) children.add(overlay);
			else children.remove(overlay);
		}, FxThreadUtil.executor());

		// Layout
		setCenter(editor);
	}

	/**
	 * @return Editor showing the decompiled code.
	 */
	@Nonnull
	public Editor getEditor() {
		return editor;
	}

	/**
	 * @param recordsNavigationHistory
	 * 		{@code false} to keep caret movement in this pane out of the back/forward navigation history,
	 * 		for panes that show code as a preview rather than as the place the user is working in.
	 */
	public void setRecordsNavigationHistory(boolean recordsNavigationHistory) {
		this.recordsNavigationHistory = recordsNavigationHistory;
	}

	@Nonnull
	@Override
	public ClassPathNode getPath() {
		return path;
	}

	@Nonnull
	@Override
	public ClassPathNode getClassPath() {
		return path;
	}

	@Override
	public void requestFocus(@Nonnull ClassMember member) {
		if (isInspectingBytecode() && member instanceof MethodMember method)
			failurePane.select(method);
		else if (!isInspectingBytecode())
			contextActionSupport.select(member);
		requestFocus();
	}

	@Override
	public void requestFocus(@Nonnull InnerClassInfo innerClass) {
		contextActionSupport.select(innerClass);
		requestFocus();
	}

	@Override
	public void requestFocus() {
		if (isInspectingBytecode())
			failurePane.requestFocus();
		else
			editor.getCodeArea().requestFocus();
	}

	@Nonnull
	@Override
	public Collection<Navigable> getNavigableChildren() {
		return Collections.singleton(contextActionSupport);
	}

	@Override
	public void disable() {
		decompileRequest++;
		clearFailureView();
		setDisable(true);
		setOnKeyPressed(null);
		editor.close();
		contextActionSupport.close();
	}

	@Override
	public void onUpdatePath(@Nonnull PathNode<?> path) {
		// Pass to children
		for (Navigable navigableChild : getNavigableChildren())
			if (navigableChild instanceof UpdatableNavigable updatableNavigable)
				updatableNavigable.onUpdatePath(path);

		// Handle updates to the decompiled code.
		if (!updateLock.get() && path instanceof ClassPathNode classPathNode) {
			this.path = classPathNode;
			ClassInfo classInfo = classPathNode.getValue();

			// Check if the class is an Android class and can be mapped.
			if (!classInfo.isJvmClass() && classInfo.isAndroidClass()) {
				AndroidClassInfo androidInfo = classInfo.asAndroidClass();
				if (androidInfo.canMapToJvmClass())
					classInfo = androidInfo.asJvmClass();
				else
					throw new IllegalStateException("Decompiler component received non-convertible Android class");
			}

			// Check if we can update the text efficiently with a remapper.
			// If not, then schedule a decompilation instead.
			if (!decompileConfig.getUseMappingAcceleration().getValue() || !handleRemapUpdate(classInfo))
				decompile();
		}
	}

	/**
	 * Associates the given {@link ToolsContainerComponent} with this decompile pane's {@link #editor}.
	 *
	 * @param toolsContainer
	 * 		Tool container to install.
	 */
	protected void installToolsContainer(@Nonnull ToolsContainerComponent toolsContainer) {
		toolsContainer.install(editor);
		astTools.getChildren().add(contextActionSupport.getAvailabilityButton());
		toolsContainer.add(astTools);
	}

	/**
	 * Attempts to update the {@link #editor}'s text with intent-specific AST operations.
	 * This includes:
	 * <ul>
	 *     <li>{@link AstMapper} when handling classes marked with {@link RemapOriginTaskProperty}</li>
	 * </ul>
	 *
	 * @param classInfo
	 * 		Modified class.
	 *
	 * @return {@code true} when we were able to handle it with an AST visitors.
	 */
	private boolean handleRemapUpdate(@Nonnull ClassInfo classInfo) {
		if (decompileInProgress.getValue() || decompileOutputErrored.getValue())
			return false;

		// Attempt to handle change with an AST mapping visitor if the change is originating
		// from a mapping application job.
		String currentText = editor.getText();
		if (!currentText.isBlank()) {
			MappingResults mappingOrigin = classInfo.getPropertyValueOrNull(RemapOriginTaskProperty.KEY);
			if (mappingOrigin != null) {
				Mappings mappings = mappingOrigin.getMappings();

				// We can handle the update with AST mapping instead of decompiling the class again.
				CompilationUnitModel unit = contextActionSupport.getUnit();
				Workspace workspace = path.getValueOfType(Workspace.class);
				if (unit != null && workspace != null) {
					ResolverAdapter resolver = astService.newJavaResolver(workspace, getPath(), unit);
					String modifiedSource = astService.applyMappings(unit, resolver, mappings);

					// If there were no changes made then the AST service failed to make dynamic changes.
					// This can happen when the classes being mapped are not in the workspace, but instead
					// belong to 3rd party libraries not present in the workspace.
					if (currentText.equals(modifiedSource))
						return false;

					// We want to get the difference between the current and modified text and update only
					// the areas of the text that are modified. In most situations this will be much faster
					// than re-assigning the whole text (which will require restyling the entire document)
					List<StringDiff.Diff> diffs = StringDiff.diff(currentText, modifiedSource);
					FxThreadUtil.run(() -> {
						// Track where caret was.
						CodeArea area = editor.getCodeArea();
						int currentParagraph = area.getCurrentParagraph();
						int currentColumn = area.getCaretColumn();

						// Apply diffs.
						for (int i = diffs.size() - 1; i >= 0; i--) {
							StringDiff.Diff diff = diffs.get(i);
							if (diff.type() == StringDiff.DiffType.CHANGE)
								area.replaceText(diff.startA(), diff.endA(), diff.textB());
							else if (diff.type() == StringDiff.DiffType.INSERT)
								area.insertText(diff.startA(), diff.textB());
							else if (diff.type() == StringDiff.DiffType.REMOVE)
								area.replaceText(diff.startA(), diff.endA(), "");
						}

						// Reset caret.
						area.moveTo(currentParagraph, currentColumn);
					});
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Decompiles the class contained by the current {@link #path} and updates the {@link #editor}'s text
	 * with the decompilation results.
	 */
	public void decompile() {
		if (path == null || isDisabled())
			return;
		long request = ++decompileRequest;
		Workspace workspace = path.getValueOfType(Workspace.class);
		JvmClassInfo classInfo = path.getValue().asJvmClass();
		JvmDecompiler selectedDecompiler = decompiler.getValue();
		int timeout = decompileConfig.getTimeoutSeconds().getValue();

		// Get current caret position so we can restore prior positions between decompilations.
		// Some actions that edit the class (like mapping, assembler edits, etc) will decompile with some changes,
		// so the actual position may drift a bit, but it's better than nothing. Can improve later if it's a problem.
		CodeArea area = editor.getCodeArea();
		int caretParagraph = area.getCurrentParagraph();
		int caretColumn = area.getCaretColumn();

		// Schedule decompilation task, update the editor's text asynchronously on the JavaFX UI thread when complete.
		clearFailureView();
		decompileInProgress.setValue(true);
		editor.setMouseTransparent(true);
		decompilerManager.decompile(selectedDecompiler, workspace, classInfo)
				.orTimeout(timeout, TimeUnit.SECONDS)
				.whenCompleteAsync((result, throwable) -> {
					if (request != decompileRequest)
						return;
					editor.setMouseTransparent(false);
					decompileInProgress.setValue(false);

					// Treat timeouts and exceptional completions like reported failures.
					if (throwable != null) {
						Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
								? throwable.getCause() : throwable;
						if (cause instanceof TimeoutException) {
							cause = new TimeoutException("Decompilation timed out after " + timeout + " seconds.");
							cause.setStackTrace(new StackTraceElement[0]);
						}
						result = new DecompileResult(cause, selectedDecompiler.getConfig().getHash());
					}

					boolean failed = result.getType() == DecompileResult.ResultType.FAILURE;
					boolean partial = !failed && !result.getDiagnostics().isEmpty();
					boolean hasIssues = failed || partial;
					if (hasIssues && !decompileOutputErrored.getValue()) {
						sourceWasEditable = area.isEditable();
						area.setEditable(false);
					} else if (!hasIssues && decompileOutputErrored.getValue()) {
						area.setEditable(sourceWasEditable);
					}
					decompileOutputErrored.setValue(hasIssues);

					String text = result.getText();
					if (text == null)
						text = "// Decompilation skipped";
					if (!Objects.equals(text, editor.getText())) {
						editor.setTextAndCaret(text, caretParagraph, caretColumn);
						if (!failed)
							contextActionSupport.scheduleAstParse();
						area.getUndoManager().forgetHistory();
					}

					if (hasIssues)
						showFailureView(classInfo, workspace, selectedDecompiler, result, partial);
				}, FxThreadUtil.executor());
	}

	private boolean isInspectingBytecode() {
		return failurePane != null && failurePane.isVisible();
	}

	private void clearFailureView() {
		setTop(null);
		if (failurePane != null) {
			failurePane.cancelInspection();
			editor.getPrimaryStack().getChildren().remove(failurePane);
			failurePane = null;
			editor.getCodeArea().setFocusTraversable(true);
		}
		astTools.setVisible(true);
	}

	private void showFailureView(JvmClassInfo info, Workspace workspace, JvmDecompiler selectedDecompiler,
	                             DecompileResult result, boolean partial) {
		String prefix = "java.decompile-inspection.";
		String messageKey = partial ? prefix + "partial" : result.getException() instanceof TimeoutException
				? prefix + "timeout" : "java.decompile-failure.brief";
		Label message = new BoundLabel(Lang.getBinding(messageKey));
		message.getStyleClass().add(Styles.WARNING);
		message.setWrapText(true);
		HBox banner = new HBox(8, message);
		banner.setAlignment(Pos.CENTER_LEFT);
		banner.setPadding(new Insets(6, 8, 6, 8));

		if (partial) {
			Button toggle = new Button();
			toggle.getStyleClass().add(Styles.SMALL);
			toggle.textProperty().bind(Lang.getBinding(prefix + "inspect"));
			toggle.setOnAction(e -> {
				boolean show = !isInspectingBytecode();
				if (show) {
					openFailurePane(info, workspace, selectedDecompiler, result);
				} else {
					failurePane.setVisible(false);
					editor.getCodeArea().setFocusTraversable(true);
					astTools.setVisible(true);
				}
				toggle.textProperty().bind(Lang.getBinding(prefix + (show ? "source" : "inspect")));
			});
			banner.getChildren().add(toggle);
		} else {
			openFailurePane(info, workspace, selectedDecompiler, result);
		}

		MenuButton retry = new MenuButton();
		retry.getStyleClass().add(Styles.SMALL);
		retry.textProperty().bind(Lang.getBinding(prefix + "retry"));
		decompilerManager.getJvmDecompilers().stream()
				.filter(candidate -> candidate != selectedDecompiler && !(candidate instanceof NoopJvmDecompiler)
						&& !(candidate instanceof FallbackDecompiler))
				.sorted(Comparator.comparing(JvmDecompiler::getName))
				.forEach(candidate -> {
					MenuItem choice = new MenuItem(candidate.getName());
					choice.setOnAction(e -> decompiler.setValue(candidate));
					retry.getItems().add(choice);
				});
		if (!retry.getItems().isEmpty())
			banner.getChildren().add(retry);
		setTop(banner);
	}

	private void openFailurePane(JvmClassInfo info, Workspace workspace, JvmDecompiler selectedDecompiler, DecompileResult result) {
		if (failurePane == null) {
			failurePane = new DecompileFailurePane(info, workspace, selectedDecompiler, result);
			failurePane.managedProperty().bind(failurePane.visibleProperty());
			// Leave the editor's tool controls accessible along the bottom.
			failurePane.setPadding(new Insets(0, 0, 36, 0));
			editor.getPrimaryStack().getChildren().add(1, failurePane);
		}
		failurePane.setVisible(true);
		if (editor.getCodeArea().isFocused())
			failurePane.requestFocus();
		editor.getCodeArea().setFocusTraversable(false);
		astTools.setVisible(false);
	}

	/**
	 * And overlay shown while a class is being decompiled.
	 */
	private class DecompileProgressOverlay extends VBox {
		private DecompileProgressOverlay() {
			Label title = new BoundLabel(Lang.getBinding("java.decompiling"));
			Label text = new Label();
			title.getStyleClass().add(Styles.TITLE_3);
			text.getStyleClass().add(Styles.TEXT_SUBTLE);
			text.setFont(new Font("JetBrains Mono", 12)); // Pulling from CSS applied to the editor.

			// Layout
			getChildren().addAll(new Spacer(Orientation.VERTICAL), title, text, new Spacer(Orientation.VERTICAL));
			getStyleClass().addAll("background");
			setFillWidth(true);
			setAlignment(Pos.CENTER);

			// Setup transition to play whenever decompilation is in progress.
			LabelByteAnimationTransition transition = new LabelByteAnimationTransition(text);
			decompileInProgress.addAsyncChangeListener((ob, old, cur) -> {
				setVisible(cur);
				if (cur) {
					transition.update(path.getValue().asJvmClass());
					transition.play();
				} else
					transition.stop();
			}, FxThreadUtil.executor());
		}

	}
}
