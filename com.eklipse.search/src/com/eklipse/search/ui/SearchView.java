package com.eklipse.search.ui;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.commands.ActionHandler;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceColors;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.jface.util.Util;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.ide.undo.WorkspaceUndoUtil;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.operations.RedoActionHandler;
import org.eclipse.ui.operations.UndoActionHandler;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;
import org.eclipse.ui.texteditor.ITextEditor;

import com.eklipse.search.core.FileMatch;
import com.eklipse.search.core.FileNameMatch;
import com.eklipse.search.core.LineMatch;
import com.eklipse.search.core.PreserveCase;
import com.eklipse.search.core.ReplacementTemplate;
import com.eklipse.search.core.Replacer;
import com.eklipse.search.core.SearchPatterns;
import com.eklipse.search.core.SearchQuery;
import com.eklipse.search.core.SearchResult;
import com.eklipse.search.core.TextSearcher;
import com.eklipse.search.core.WorkspaceSearchScope;

/**
 * A VS Code style search view: search as you type across the workspace, results grouped by file, replace in place.
 */
public class SearchView extends ViewPart {

	public static final String ID = "com.eklipse.search.view";

	private static final String TITLE = "Search";
	private static final int SEARCH_DELAY_MS = 250;
	private static final int UPDATE_INTERVAL_MS = 100;
	/** How long the previous results stay when a new search finds nothing at first. */
	private static final int STALE_RESULTS_MS = 500;
	/** Faster searches, e.g. while typing more, would only flash the progress bar. */
	private static final int PROGRESS_DELAY_MS = 300;
	private static final int PROGRESS_STEPS = 1000;
	private static final int PROGRESS_WIDTH = 80;
	private static final int LABEL_REFRESH_DELAY_MS = 100;
	private static final int FILE_REFRESH_DELAY_MS = 300;
	/** Short enough to feel instant on a click, long enough to skip the results passed when holding an arrow key. */
	private static final int PREVIEW_DELAY_MS = 50;
	private static final int AUTO_EXPAND_LIMIT = 2_000;
	/** Enough for the file looked for, few enough not to bury the matches in the content under file names. */
	private static final int MAX_NAME_MATCHES = 10;
	private static final int NARROW_WIDTH = 260;
	private static final int MIN_PREVIEW_CONTEXT = 8;
	private static final int MAX_PREVIEW_CONTEXT = 40;
	/** Width taken by the tree's indentation and expand arrows before a match line starts. */
	private static final int PREVIEW_INDENT = 40;
	private static final String DEFAULT_INCLUDES = "*.java";
	private static final String DEFAULT_EXCLUDES = "testbundle.*, *.webapi.*, **/node_modules";
	/** A view saved with it gets the current default, a value changed by hand stays. */
	private static final String PREVIOUS_DEFAULT_EXCLUDES = "testbundle.*, **/node_modules";
	/** The context of the view's key bindings, see {@link #activateShortcuts()}. */
	private static final String CONTEXT_ID = "com.eklipse.search.context";
	private static final int[] DEFAULT_PREVIEW_WEIGHTS = { 3, 2 };
	private static final int SASH_WIDTH = 5;
	/** How much of the text color is mixed into the background for the line between results and preview. */
	private static final double SASH_LINE_TEXT_SHARE = 0.25;

	private static final String KEY_QUERY = "query";
	private static final String KEY_REPLACE = "replace";
	private static final String KEY_INCLUDES = "includes";
	private static final String KEY_EXCLUDES = "excludes";
	private static final String KEY_SEARCH_HISTORY = "searchHistory";
	private static final String KEY_INCLUDE_HISTORY = "includeHistory";
	private static final String KEY_EXCLUDE_HISTORY = "excludeHistory";
	private static final String KEY_CASE = "caseSensitive";
	private static final String KEY_WORD = "wholeWord";
	private static final String KEY_REGEX = "regex";
	private static final String KEY_PRESERVE_CASE = "preserveCase";
	private static final String KEY_DERIVED = "excludeDerived";
	private static final String KEY_WILDCARDS = "wildcards";
	private static final String KEY_FILE_NAMES = "fileNames";
	private static final String KEY_REPLACE_VISIBLE = "replaceVisible";
	private static final String KEY_PREVIEW = "preview";
	private static final String KEY_PREVIEW_WEIGHTS = "previewWeights";

	private static final ILog LOG = ILog.of(SearchView.class);

	private IMemento memento;
	private Display display;
	private Composite root;

	private Text searchText;
	private Text replaceText;
	private Combo includeField;
	private Combo excludeField;
	private SearchHistory searchHistory;
	private ComboHistory includeHistory;
	private ComboHistory excludeHistory;
	private ToolItem replaceToggleItem;
	private ToolItem caseItem;
	private ToolItem wordItem;
	private ToolItem preserveCaseItem;
	private ToolItem replaceAllItem;
	private Composite inputFields;
	private ToolBar optionsBar;
	private ToolBar replaceBar;
	private Label summaryLabel;
	private ProgressBar progressBar;
	private Label durationLabel;
	private SashForm resultSash;
	private ResultViewer viewer;
	private PreviewPane preview;
	private EmptyState emptyState;
	private EditorMatchMarks editorMarks;

	private Action refreshAction;
	private Action clearAction;
	private Action expandAllAction;
	private Action collapseAllAction;
	private Action previewAction;
	private Action regexAction;
	private Action derivedAction;
	private Action wildcardAction;
	private Action fileNamesAction;
	private List<Shortcut> shortcuts;
	private IPartListener2 activationListener;
	private Action openAction;
	private Action replaceSelectionAction;
	private Action dismissAction;
	private Action copyAction;
	private Action copyPathAction;
	private Action selectAllAction;
	private UndoActionHandler undoHandler;
	private RedoActionHandler redoHandler;

	private SearchResult result = new SearchResult();
	/** The current search, read by the resource listener thread. */
	private volatile SearchSession session;
	private final Set<IFile> pendingRefresh = new HashSet<>();
	private IResourceChangeListener resourceListener;
	private final IFileBufferListener bufferListener = new BufferListener();
	private boolean searchScheduled;
	private LocalResourceManager resources;
	private Icons icons;
	private boolean narrowLayout;
	private double averageCharWidth;
	private int lastPreviewContextChars;

	private final Runnable searchTrigger = this::startSearch;
	private final Runnable labelRefreshTrigger = this::refreshLabels;
	private final Runnable fileRefreshTrigger = this::flushPendingRefresh;
	private final Runnable previewTrigger = this::updatePreview;

	/**
	 * An action with a key binding.
	 *
	 * @param action the action the key runs
	 * @param text the label without the key
	 * @param commandId the command the key runs
	 * @param defaultKey the key the command is bound to by default, shown until the view is first active
	 */
	private record Shortcut(Action action, String text, String commandId, String defaultKey) {
	}

	/**
	 * One run of the search. Matches are produced by worker threads and moved into the tree by the UI thread.
	 */
	private static final class SearchSession {

		final SearchQuery query;
		final Pattern pattern;
		final WorkspaceSearchScope scope;
		/** Whether only the files of the previous search were searched, see {@link SearchQuery#isNarrowedBy}. */
		final boolean narrowed;
		final long startedAt = System.currentTimeMillis();
		/** Set when the search is done. */
		volatile long finishedAt;
		/** The files to search as the search engine reports them, and how many it has searched so far. */
		volatile double totalWork;
		final DoubleAdder worked = new DoubleAdder();
		final ConcurrentLinkedQueue<LineMatch> incoming = new ConcurrentLinkedQueue<>();
		final ConcurrentLinkedQueue<FileNameMatch> incomingNames = new ConcurrentLinkedQueue<>();
		/** The files with matches, also those dismissed later. */
		final Set<IFile> hitFiles = ConcurrentHashMap.newKeySet();
		/** Files added or changed since the search started, a search narrowing this one must look at them again. */
		final Set<IFile> changedFiles = ConcurrentHashMap.newKeySet();
		/** Set when files may have come into scope without being changed, e.g. by opening a project. */
		volatile boolean scopeChanged;
		volatile TextSearcher.Result outcome;
		/** Set when the search looked at all files, without being canceled or stopped at the limit. */
		volatile boolean complete;
		volatile boolean done;
		/** Whether the tree shows the results of this search, until then it shows those of the previous one. */
		boolean shown;
		/** The matches in the files expanded so far, at most {@link #AUTO_EXPAND_LIMIT}. */
		int expandedMatches;
		Job job;

		SearchSession(SearchQuery query, Pattern pattern, WorkspaceSearchScope scope, boolean narrowed) {
			this.query = query;
			this.pattern = pattern;
			this.scope = scope;
			this.narrowed = narrowed;
		}

		boolean canBeNarrowedTo(SearchQuery next) {
			return complete && !scopeChanged && query.isNarrowedBy(next);
		}
	}

	@Override
	public void init(IViewSite site, IMemento memento) throws PartInitException {
		super.init(site, memento);
		this.memento = memento;
	}

	@Override
	public void createPartControl(Composite parent) {
		display = parent.getDisplay();
		root = parent;
		GridLayoutFactory.fillDefaults().margins(4, 4).spacing(0, 4).applyTo(parent);
		createInputArea(parent);
		createFilterArea(parent);
		createSummary(parent);
		createViewer(parent);
		editorMarks = new EditorMatchMarks(getSite().getPage(), () -> result);
		createActions();
		contributeToActionBars();
		activateShortcuts();
		hookContextMenu();
		restoreState();
		hookListeners();
		root.addListener(SWT.Resize, e -> updateResponsiveLayout());
		viewer.getTree().addListener(SWT.Resize, e -> {
			int contextChars = previewContextChars();
			if (contextChars != lastPreviewContextChars) {
				lastPreviewContextChars = contextChars;
				scheduleLabelRefresh();
			}
		});

		resourceListener = this::resourceChanged;
		ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
		FileBuffers.getTextFileBufferManager().addFileBufferListener(bufferListener);
		if (!searchText.getText().isEmpty()) {
			scheduleSearch(0);
		}
	}

	// ---------------------------------------------------------------------------------------------------- widgets

	private void createInputArea(Composite parent) {
		resources = new LocalResourceManager(JFaceResources.getResources(), parent);
		icons = new Icons(parent.getBackground().getRGB(),
				parent.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB());
		Composite area = new Composite(parent, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(area);
		GridLayoutFactory.fillDefaults().numColumns(2).spacing(2, 0).applyTo(area);

		ToolBar toggleBar = new ToolBar(area, SWT.FLAT);
		GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.BEGINNING).applyTo(toggleBar);
		replaceToggleItem = new ToolItem(toggleBar, SWT.PUSH);
		updateReplaceToggle(false);
		replaceToggleItem.setToolTipText("Toggle Replace");

		inputFields = new Composite(area, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(inputFields);
		GridLayoutFactory.fillDefaults().numColumns(2).spacing(2, 4).applyTo(inputFields);

		// a plain field like the replace field: the native macOS search field is almost invisible in the dark theme
		searchText = createField(inputFields, "Search");
		searchHistory = new SearchHistory(searchText);
		optionsBar = createRowToolBar(inputFields);
		caseItem = createToggle(optionsBar, overlayIcon("case_sensitive"), "Aa", "Match Case", 'C');
		wordItem = createToggle(optionsBar, overlayIcon("whole_word"), "ab", "Match Whole Word", 'W');

		replaceText = createField(inputFields, "Replace");
		replaceBar = createRowToolBar(inputFields);
		preserveCaseItem = createToggle(replaceBar, icons.preserveCase(), "AB", "Preserve Case", 'P');
		replaceAllItem = new ToolItem(replaceBar, SWT.PUSH);
		setIcon(replaceAllItem, overlayIcon("replace_all"), "All");
		replaceAllItem.setToolTipText("Replace All (" + (Util.isMac() ? "⌘↩" : "Ctrl+Enter") + ")");
	}

	private void updateReplaceToggle(boolean expanded) {
		replaceToggleItem.setImage(resources.create(icons.chevron(expanded)));
	}

	/**
	 * Tells in the empty search field that a star matches any text, so searching one doesn't come as a surprise.
	 */
	private void updateSearchHint() {
		boolean wildcards = wildcardAction.isChecked() && !regexAction.isChecked();
		searchText.setMessage(wildcards ? "Search, * for any text" : "Search");
	}

	private static Text createField(Composite parent, String hint) {
		Text text = new Text(parent, SWT.SINGLE | SWT.BORDER);
		text.setMessage(hint);
		GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(text);
		return text;
	}

	private static ToolBar createRowToolBar(Composite row) {
		ToolBar bar = new ToolBar(row, SWT.FLAT);
		GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(bar);
		return bar;
	}

	/**
	 * @param icon {@code null} for the compact text
	 */
	private ToolItem createToggle(ToolBar bar, ImageDescriptor icon, String text, String description, char key) {
		ToolItem item = new ToolItem(bar, SWT.CHECK);
		if (icon != null) {
			item.setData(new ImageDescriptor[] { icons.toggle(icon, false), icons.toggle(icon, true) });
			updateToggle(item);
			item.addListener(SWT.Selection, e -> updateToggle(item));
		} else {
			item.setText(text);
		}
		item.setToolTipText(description + " (" + (Util.isMac() ? "⌥" : "Alt+") + key + ")");
		return item;
	}

	/**
	 * Shows whether a toggle is on in its image, needed after changing its selection without an event.
	 */
	private void updateToggle(ToolItem item) {
		if (item.getData() instanceof ImageDescriptor[] images) {
			item.setImage(resources.create(images[item.getSelection() ? 1 : 0]));
		}
	}

	/**
	 * @return the icon of Eclipse's own find/replace overlay, {@code null} if the installed Eclipse doesn't ship it
	 *         (2026-09 does, 2024-03 doesn't)
	 */
	private ImageDescriptor overlayIcon(String name) {
		return icons.adapt(ResourceLocator.imageDescriptorFromBundle("org.eclipse.ui.workbench.texteditor",
				"icons/full/elcl16/" + name + ".png").orElse(null));
	}

	/**
	 * @param icon {@code null} for the compact text
	 */
	private void setIcon(ToolItem item, ImageDescriptor icon, String fallbackText) {
		if (icon != null) {
			item.setImage(resources.create(icon));
		} else {
			item.setText(fallbackText);
		}
	}

	/**
	 * Below {@link #NARROW_WIDTH} the option buttons move below their text field, so the search and replace fields
	 * stay usable in a slim side bar.
	 */
	private void updateResponsiveLayout() {
		int width = root.getClientArea().width;
		boolean narrow = width > 0 && width < NARROW_WIDTH;
		if (narrow == narrowLayout) {
			return;
		}
		narrowLayout = narrow;
		((GridLayout) inputFields.getLayout()).numColumns = narrow ? 1 : 2;
		for (Control bar : new Control[] { optionsBar, replaceBar }) {
			((GridData) bar.getLayoutData()).horizontalAlignment = narrow ? SWT.END : SWT.BEGINNING;
		}
		root.layout(true, true);
	}

	/**
	 * Include and exclude below the search field, starting at the left edge below the replace toggle, so the fields get
	 * as much of a narrow view as possible. Both fields share their edges: the buttons are in one column.
	 */
	private void createFilterArea(Composite parent) {
		Composite area = new Composite(parent, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(area);
		GridLayoutFactory.fillDefaults().numColumns(2).spacing(2, 4).applyTo(area);
		includeHistory = createFilterField(area, "Include:", "e.g. *.java, src/main/**");
		includeField = includeHistory.getCombo();
		excludeHistory = createFilterField(area, "Exclude:", "e.g. **/node_modules");
		excludeField = excludeHistory.getCombo();
	}

	private ComboHistory createFilterField(Composite parent, String label, String hint) {
		Label title = new Label(parent, SWT.NONE);
		title.setText(label);
		GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(title);
		// a combo box has no hint text, the tooltip gives the example
		Combo combo = new Combo(parent, SWT.DROP_DOWN);
		combo.setToolTipText(hint);
		GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).indent(2, 0).applyTo(combo);
		return new ComboHistory(combo);
	}

	private void createSummary(Composite parent) {
		Composite row = new Composite(parent, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(row);
		GridLayoutFactory.fillDefaults().numColumns(3).spacing(6, 0).applyTo(row);
		summaryLabel = new Label(row, SWT.WRAP);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(summaryLabel);
		// the progress while searching, then how long it took, in one place on the right
		progressBar = new ProgressBar(row, SWT.HORIZONTAL);
		progressBar.setMaximum(PROGRESS_STEPS);
		GridDataFactory.fillDefaults().align(SWT.END, SWT.CENTER).hint(PROGRESS_WIDTH, SWT.DEFAULT).exclude(true)
				.applyTo(progressBar);
		progressBar.setVisible(false);
		durationLabel = new Label(row, SWT.NONE);
		durationLabel.setForeground(JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR));
		GridDataFactory.fillDefaults().align(SWT.END, SWT.BEGINNING).applyTo(durationLabel);
	}

	private void createViewer(Composite parent) {
		// the preview goes below the results, which suits a tall, narrow side bar
		resultSash = new SashForm(parent, SWT.VERTICAL | SWT.SMOOTH);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(resultSash);
		// no horizontal scrolling like in VS Code, long lines are shortened around the match and shown in the tooltip
		viewer = new ResultViewer(resultSash, SWT.MULTI | SWT.V_SCROLL | SWT.FULL_SELECTION);
		Tree tree = viewer.getTree();
		viewer.setUseHashlookup(true);
		viewer.setContentProvider(new ResultContentProvider());
		// a single column kept as wide as the view; without it the tree grows to the longest line and scrolls sideways
		TreeViewerColumn column = new TreeViewerColumn(viewer, SWT.NONE);
		Font pathFont = resources.create(FontDescriptor.createFrom(tree.getFont()).increaseHeight(-2));
		column.setLabelProvider(new ResultLabelProvider(this::previewReplacement, this::previewContextChars,
				() -> result.getNameMatchCount(), pathFont));
		// the selection is lighter while the results have the focus
		tree.addListener(SWT.FocusIn, e -> tree.redraw());
		tree.addListener(SWT.FocusOut, e -> tree.redraw());
		tree.addListener(SWT.Resize, e -> fitColumn());
		// expanding can show the vertical scroll bar, which narrows the tree without a resize event
		Listener refit = e -> display.asyncExec(this::fitColumn);
		tree.addListener(SWT.Expand, refit);
		tree.addListener(SWT.Collapse, refit);
		viewer.setComparator(new ResultComparator());
		ColumnViewerToolTipSupport.enableFor(viewer);
		viewer.setInput(result);
		getSite().setSelectionProvider(viewer);
		preview = new PreviewPane(resultSash);
		resultSash.setWeights(DEFAULT_PREVIEW_WEIGHTS);
		drawSashLines();
		emptyState = new EmptyState(parent, tree, resources, this::activateSearch);
		GridDataFactory.fillDefaults().grab(true, true).exclude(true).applyTo(emptyState.getControl());
		emptyState.getControl().setVisible(false);
		setEmptyStateVisible(true);
		GC gc = new GC(viewer.getTree());
		try {
			averageCharWidth = gc.getFontMetrics().getAverageCharacterWidth();
		} finally {
			gc.dispose();
		}
	}

	/**
	 * A line across the gap between the results and the preview, so it's visible that the gap can be dragged.
	 */
	private void drawSashLines() {
		resultSash.setSashWidth(SASH_WIDTH);
		// the sash is created by the first layout with a size, after the resize event
		resultSash.addListener(SWT.Resize, e -> display.asyncExec(() -> {
			if (resultSash.isDisposed()) {
				return;
			}
			for (Control child : resultSash.getChildren()) {
				if (child instanceof Sash sash && sash.getListeners(SWT.Paint).length == 0) {
					sash.addListener(SWT.Paint, this::drawSashLine);
					sash.redraw();
				}
			}
		}));
	}

	private void drawSashLine(Event e) {
		Tree tree = viewer.getTree();
		RGB background = tree.getBackground().getRGB();
		RGB text = tree.getForeground().getRGB();
		e.gc.setForeground(new Color(mix(background.red, text.red), mix(background.green, text.green),
				mix(background.blue, text.blue)));
		Point size = ((Control) e.widget).getSize();
		e.gc.drawLine(0, size.y / 2, size.x, size.y / 2);
	}

	private static int mix(int background, int text) {
		return background + (int) ((text - background) * SASH_LINE_TEXT_SHARE);
	}

	/**
	 * Keeps the single column exactly as wide as the visible area, so there's never a horizontal scroll bar.
	 */
	private void fitColumn() {
		if (isDisposed() || viewer.getTree().getColumnCount() == 0) {
			return;
		}
		Tree tree = viewer.getTree();
		int width = tree.getClientArea().width;
		if (width > 0 && tree.getColumn(0).getWidth() != width) {
			tree.getColumn(0).setWidth(width);
		}
	}

	/**
	 * @return how many characters before a match are shown, about a third of what fits into the view
	 */
	private int previewContextChars() {
		int width = viewer.getTree().getClientArea().width;
		if (width <= 0 || averageCharWidth <= 0) {
			return MAX_PREVIEW_CONTEXT;
		}
		int visibleChars = (int) ((width - PREVIEW_INDENT) / averageCharWidth);
		return Math.max(MIN_PREVIEW_CONTEXT, Math.min(MAX_PREVIEW_CONTEXT, visibleChars / 3));
	}

	private void createActions() {
		ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
		refreshAction = createAction("Refresh", searchImage("refresh.png"), () -> scheduleSearch(0));
		clearAction = createAction("Clear Search Results", images.getImageDescriptor(ISharedImages.IMG_ELCL_REMOVEALL),
				this::clear);
		// ISharedImages.IMG_ELCL_EXPANDALL only exists since Eclipse 2024-06
		expandAllAction = createAction("Expand All", searchImage("expandall.png"), () -> viewer.expandAll());
		collapseAllAction = createAction("Collapse All",
				images.getImageDescriptor(ISharedImages.IMG_ELCL_COLLAPSEALL), () -> viewer.collapseAll());
		regexAction = createOptionAction("Use Regular Expression", 'R', overlayIcon("regex"));
		// the build output Eclipse marks as derived, e.g. Maven target/ folders
		derivedAction = createOptionAction("Skip Derived Resources", 'D', icons.funnel());
		// final*size instead of the regular expression final.*?size
		wildcardAction = createOptionAction("Use Wildcards", 'A', null);
		fileNamesAction = createOptionAction("Search File Names", 'F', null);
		shortcuts = List.of(
				new Shortcut(refreshAction, "Refresh", IWorkbenchCommandConstants.FILE_REFRESH, "F5"),
				new Shortcut(clearAction, "Clear Search Results", "com.eklipse.search.clear", "M1+M2+BS"),
				new Shortcut(expandAllAction, "Expand All", "com.eklipse.search.expandAll", "M1+="),
				new Shortcut(collapseAllAction, "Collapse All", "com.eklipse.search.collapseAll", "M1+-"));
		// a regular expression has wildcards of its own
		regexAction.addPropertyChangeListener(e -> {
			if (IAction.CHECKED.equals(e.getProperty())) {
				wildcardAction.setEnabled(!regexAction.isChecked());
				updateSearchHint();
			}
		});
		wildcardAction.addPropertyChangeListener(e -> {
			if (IAction.CHECKED.equals(e.getProperty())) {
				updateSearchHint();
			}
		});
		previewAction = new Action("Show Preview", IAction.AS_CHECK_BOX) {
			@Override
			public void run() {
				setPreviewVisible(isChecked());
			}
		};
		previewAction.setToolTipText(
				"Show Preview: a click on a result shows its file below the results, a double-click opens it. When off, a click opens the editor.");
		ImageDescriptor previewImage = searchImage("verticalOrientation.png");
		if (previewImage != null) {
			previewAction.setImageDescriptor(previewImage);
		}
		openAction = createAction("Open", null, () -> openSelection(true));
		replaceSelectionAction = createAction("Replace", null, () -> replace(selectedMatches(), false));
		// the preview is read-only: Delete there must not dismiss the selected results
		dismissAction = createAction("Dismiss", images.getImageDescriptor(ISharedImages.IMG_ELCL_REMOVE), () -> {
			if (!preview.hasFocus()) {
				dismissSelection();
			}
		});
		dismissAction.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_DELETE);
		copyAction = createAction("Copy", images.getImageDescriptor(ISharedImages.IMG_TOOL_COPY), () -> {
			if (preview.hasFocus()) {
				preview.copy();
			} else {
				copySelection();
			}
		});
		copyAction.setActionDefinitionId(IWorkbenchCommandConstants.EDIT_COPY);
		copyPathAction = createAction("Copy Path", null, this::copyPaths);
		selectAllAction = createAction("Select All", null, () -> {
			if (preview.hasFocus()) {
				preview.selectAll();
			} else {
				viewer.getTree().selectAll();
				updateSelectionActions();
			}
		});
		undoHandler = new UndoActionHandler(getSite(), WorkspaceUndoUtil.getWorkspaceUndoContext());
		redoHandler = new RedoActionHandler(getSite(), WorkspaceUndoUtil.getWorkspaceUndoContext());
		updateSelectionActions();
	}

	/**
	 * @return an icon of the File Search view, {@code null} if it can't be found (the action then shows its text)
	 */
	private static ImageDescriptor searchImage(String name) {
		return ResourceLocator.imageDescriptorFromBundle("org.eclipse.search", "icons/full/elcl16/" + name).orElse(null);
	}

	/**
	 * @return a search option for the view menu, toggled with Alt and {@code key} in the text fields too
	 */
	private Action createOptionAction(String text, char key, ImageDescriptor image) {
		Action action = new Action(text + " (" + (Util.isMac() ? "⌥" : "Alt+") + key + ")", IAction.AS_CHECK_BOX) {
			@Override
			public void run() {
				scheduleSearch(0);
			}
		};
		if (image != null) {
			action.setImageDescriptor(image);
		}
		return action;
	}

	private static Action createAction(String text, ImageDescriptor image, Runnable runnable) {
		Action action = new Action(text) {
			@Override
			public void run() {
				runnable.run();
			}
		};
		action.setToolTipText(text);
		if (image != null) {
			action.setImageDescriptor(image);
		}
		return action;
	}

	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		IToolBarManager toolBar = bars.getToolBarManager();
		toolBar.add(refreshAction);
		toolBar.add(clearAction);
		toolBar.add(new Separator());
		toolBar.add(expandAllAction);
		toolBar.add(collapseAllAction);
		IMenuManager menu = bars.getMenuManager();
		menu.add(regexAction);
		menu.add(wildcardAction);
		menu.add(fileNamesAction);
		menu.add(derivedAction);
		menu.add(new Separator());
		menu.add(previewAction);

		// Copy, Delete and Select All work on the fields while they have focus, on the results otherwise
		FieldActionHandler fieldActionHandler = new FieldActionHandler(bars, copyAction, dismissAction,
				selectAllAction);
		for (Control field : fields()) {
			fieldActionHandler.addField(field);
		}
		setUndoHandlersEnabled(true);
	}

	/**
	 * Clear, Expand All and Collapse All are commands bound to keys in a context of the view's own, active while the
	 * view is: there the keys win over the window's bindings, and they can be changed in the Keys preferences. Refresh
	 * is the workbench's own, usually F5.
	 */
	private void activateShortcuts() {
		getSite().getService(IContextService.class).activateContext(CONTEXT_ID);
		IHandlerService handlers = getSite().getService(IHandlerService.class);
		for (Shortcut shortcut : shortcuts) {
			if (shortcut.action() != refreshAction) {
				handlers.activateHandler(shortcut.commandId(), new ActionHandler(shortcut.action()));
			}
		}
		getViewSite().getActionBars().setGlobalActionHandler(ActionFactory.REFRESH.getId(), refreshAction);
		activationListener = new IPartListener2() {
			@Override
			public void partActivated(IWorkbenchPartReference partRef) {
				if (partRef.getPart(false) == SearchView.this) {
					// once the context is active, which happens on the same activation
					display.asyncExec(SearchView.this::updateShortcutKeys);
				}
			}
		};
		getSite().getPage().addPartListener(activationListener);
		updateShortcutKeys();
	}

	/**
	 * Shows the key of each shortcut in its tooltip and menu label. The bindings of the view's context only count
	 * while it's active, so until then the default key is shown, after that the one last seen, which stays correct
	 * after a change in the Keys preferences.
	 */
	private void updateShortcutKeys() {
		if (isDisposed()) {
			return;
		}
		IBindingService bindings = getSite().getService(IBindingService.class);
		boolean active = getSite().getPage().getActivePart() == this;
		for (Shortcut shortcut : shortcuts) {
			String key = active && bindings != null ? bindings.getBestActiveBindingFormattedFor(shortcut.commandId())
					: formatKey(shortcut.defaultKey());
			String text = key == null || key.isEmpty() ? shortcut.text() : shortcut.text() + " (" + key + ")";
			shortcut.action().setText(text);
			shortcut.action().setToolTipText(text);
		}
	}

	private static String formatKey(String sequence) {
		try {
			return KeySequence.getInstance(sequence).format();
		} catch (ParseException e) {
			return null;
		}
	}

	private void hookContextMenu() {
		MenuManager menuManager = new MenuManager("#PopupMenu");
		menuManager.setRemoveAllWhenShown(true);
		menuManager.addMenuListener(this::fillContextMenu);
		viewer.getControl().setMenu(menuManager.createContextMenu(viewer.getControl()));
	}

	private void fillContextMenu(IMenuManager menu) {
		menu.add(openAction);
		menu.add(new Separator());
		if (isReplaceVisible()) {
			menu.add(replaceSelectionAction);
		}
		menu.add(dismissAction);
		menu.add(new Separator());
		menu.add(copyAction);
		menu.add(copyPathAction);
		menu.add(new Separator());
		menu.add(expandAllAction);
		menu.add(collapseAllAction);
	}

	private void hookListeners() {
		replaceToggleItem.addListener(SWT.Selection, e -> setReplaceVisible(!isReplaceVisible()));
		for (ToolItem item : new ToolItem[] { caseItem, wordItem }) {
			item.addListener(SWT.Selection, e -> scheduleSearch(0));
		}
		preserveCaseItem.addListener(SWT.Selection, e -> scheduleLabelRefresh());
		replaceAllItem.addListener(SWT.Selection, e -> replaceAll(true));

		searchText.addModifyListener(e -> scheduleSearch(SEARCH_DELAY_MS));
		searchText.addListener(SWT.DefaultSelection, e -> scheduleSearch(0));
		// ↑ and ↓ browse the recent searches like in a terminal, the focus stays in the field
		searchText.addListener(SWT.KeyDown, e -> {
			if ((e.stateMask & SWT.MODIFIER_MASK) == 0 && (e.keyCode == SWT.ARROW_UP && searchHistory.showOlder()
					|| e.keyCode == SWT.ARROW_DOWN && searchHistory.showNewer())) {
				e.doit = false;
			}
		});
		replaceText.addModifyListener(e -> scheduleLabelRefresh());
		replaceText.addListener(SWT.KeyDown, e -> {
			if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.MOD1) != 0) {
				e.doit = false;
				replaceAll(true);
			}
		});
		includeField.addModifyListener(e -> scheduleSearch(SEARCH_DELAY_MS));
		excludeField.addModifyListener(e -> scheduleSearch(SEARCH_DELAY_MS));

		// typing in a text field must not undo the last replace
		Listener focusTracker = e -> setUndoHandlersEnabled(e.type == SWT.FocusOut);
		for (Control field : fields()) {
			field.addListener(SWT.KeyDown, this::handleOptionKey);
			field.addListener(SWT.FocusIn, focusTracker);
			field.addListener(SWT.FocusOut, focusTracker);
		}

		Tree tree = viewer.getTree();
		viewer.addOpenListener(e -> openSelection(true));
		viewer.addSelectionChangedListener(e -> {
			updateSelectionActions();
			schedulePreview();
		});
		tree.addListener(SWT.MouseUp, this::previewOnClick);
		tree.addListener(SWT.KeyDown, e -> {
			if (e.keyCode == SWT.DEL || e.keyCode == SWT.BS) {
				e.doit = false;
				dismissSelection();
			}
		});
	}

	private Control[] fields() {
		return new Control[] { searchText, replaceText, includeField, excludeField };
	}

	/**
	 * Alt+C / Alt+W / Alt+R / Alt+P / Alt+D / Alt+A / Alt+F toggle the options like in VS Code (Option on macOS, where Cmd+Option+W is
	 * already bound by Eclipse).
	 */
	private void handleOptionKey(Event e) {
		if ((e.stateMask & SWT.MODIFIER_MASK) != SWT.ALT) {
			return;
		}
		char key = Character.toLowerCase((char) e.keyCode);
		Action action = switch (key) {
			case 'r' -> regexAction;
			case 'd' -> derivedAction;
			case 'a' -> wildcardAction;
			case 'f' -> fileNamesAction;
			default -> null;
		};
		if (action != null) {
			e.doit = false;
			action.setChecked(!action.isChecked());
			action.run();
			return;
		}
		ToolItem item = switch (key) {
			case 'c' -> caseItem;
			case 'w' -> wordItem;
			case 'p' -> preserveCaseItem;
			default -> null;
		};
		if (item != null) {
			e.doit = false;
			item.setSelection(!item.getSelection());
			item.notifyListeners(SWT.Selection, new Event());
		}
	}

	private void setUndoHandlersEnabled(boolean enabled) {
		IActionBars bars = getViewSite().getActionBars();
		bars.setGlobalActionHandler(ActionFactory.UNDO.getId(), enabled ? undoHandler : null);
		bars.setGlobalActionHandler(ActionFactory.REDO.getId(), enabled ? redoHandler : null);
		bars.updateActionBars();
	}

	private boolean isReplaceVisible() {
		return replaceText.getVisible();
	}

	private void setReplaceVisible(boolean visible) {
		setVisible(visible, replaceText, replaceBar);
		updateReplaceToggle(visible);
		if (visible) {
			replaceText.setFocus();
		}
		scheduleLabelRefresh();
	}

	private void setVisible(boolean visible, Control... controls) {
		for (Control control : controls) {
			((GridData) control.getLayoutData()).exclude = !visible;
			control.setVisible(visible);
		}
		root.layout(true, true);
	}

	private boolean isPreviewVisible() {
		return resultSash.getMaximizedControl() == null;
	}

	/**
	 * Shows or hides the preview. Without it, a click on a match opens the editor instead.
	 */
	void setPreviewVisible(boolean visible) {
		previewAction.setChecked(visible);
		resultSash.setMaximizedControl(visible ? null : viewer.getControl());
		if (visible) {
			updatePreview();
		} else {
			display.timerExec(-1, previewTrigger);
			preview.clear();
		}
	}

	private void schedulePreview() {
		if (isPreviewVisible()) {
			display.timerExec(-1, previewTrigger);
			display.timerExec(PREVIEW_DELAY_MS, previewTrigger);
		}
	}

	private void updatePreview() {
		if (isDisposed() || !isPreviewVisible()) {
			return;
		}
		Object first = viewer.getStructuredSelection().getFirstElement();
		// a file found by its name is what was looked for, it's shown from the start
		if (first instanceof FileNameMatch nameMatch) {
			FileMatch fileMatch = result.get(nameMatch.getFile());
			preview.show(nameMatch.getFile(), fileMatch != null ? fileMatch.getMatches() : List.of());
			return;
		}
		LineMatch target = first instanceof LineMatch match ? match
				: first instanceof FileMatch fileMatch && fileMatch.getMatchCount() > 0 ? fileMatch.getMatches().get(0)
						: null;
		FileMatch fileMatch = target != null ? result.get(target.getFile()) : null;
		if (fileMatch != null) {
			preview.show(target, fileMatch.getMatches());
		} else {
			preview.clear();
		}
	}

	// ---------------------------------------------------------------------------------------------------- search

	private SearchQuery currentQuery() {
		return new SearchQuery(searchText.getText(), caseItem.getSelection(), wordItem.getSelection(),
				regexAction.isChecked(), includeField.getText(), excludeField.getText(), derivedAction.isChecked(),
				wildcardAction.isChecked(), fileNamesAction.isChecked());
	}

	private void scheduleSearch(int delayMs) {
		searchScheduled = true;
		display.timerExec(-1, searchTrigger);
		display.timerExec(delayMs, searchTrigger);
	}

	private void startSearch() {
		if (isDisposed()) {
			return;
		}
		display.timerExec(-1, searchTrigger);
		searchScheduled = false;
		SearchSession previous = session;
		cancelSearch();
		pendingRefresh.clear();
		setProgressVisible(false);
		setDuration("");

		SearchQuery query = currentQuery();
		if (query.isEmpty()) {
			session = null;
			showResult(new SearchResult());
			setSummary("", false);
			return;
		}
		Pattern pattern;
		try {
			pattern = query.createPattern();
		} catch (PatternSyntaxException e) {
			session = null;
			showResult(new SearchResult());
			setSummary("Invalid regular expression: " + e.getDescription(), true);
			return;
		}
		WorkspaceSearchScope scope = WorkspaceSearchScope.of(query);
		// typing more only needs to look at the files the previous search found something in, like Quick Search
		IFile[] files = previous != null && previous.canBeNarrowedTo(query) ? narrowingFiles(previous, scope) : null;
		SearchSession s = new SearchSession(query, pattern, scope, files != null);
		session = s;
		// the previous results stay until the first new ones arrive, so the tree doesn't blink empty while typing
		setSummary("Searching…", false);
		updateSelectionActions();

		s.job = Job.create("Search: " + query.text(), jobMonitor -> {
			Consumer<LineMatch> collector = match -> {
				s.hitFiles.add(match.getFile());
				s.incoming.add(match);
			};
			// a search narrowing this one has to look at these files again too, for their name
			Consumer<FileNameMatch> names = !query.fileNames() ? null : name -> {
				s.hitFiles.add(name.getFile());
				s.incomingNames.add(name);
			};
			// the search engine reports the files to search and the ones searched, for the progress bar
			IProgressMonitor monitor = new ProgressMonitorWrapper(jobMonitor) {
				@Override
				public void beginTask(String name, int totalWork) {
					s.totalWork = totalWork;
					super.beginTask(name, totalWork);
				}

				@Override
				public void worked(int work) {
					s.worked.add(work);
					super.worked(work);
				}

				@Override
				public void internalWorked(double work) {
					s.worked.add(work);
					super.internalWorked(work);
				}
			};
			int max = TextSearcher.DEFAULT_MAX_RESULTS;
			TextSearcher.Result outcome = files != null
					? TextSearcher.search(files, s.pattern, max, collector, names, monitor)
					: TextSearcher.search(s.scope, s.pattern, max, collector, names, monitor);
			s.outcome = outcome;
			s.complete = !monitor.isCanceled() && !outcome.limitReached()
					&& !outcome.status().matches(IStatus.CANCEL);
			return Status.OK_STATUS;
		});
		s.job.addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void done(IJobChangeEvent event) {
				s.finishedAt = System.currentTimeMillis();
				s.done = true;
			}
		});
		IWorkbenchSiteProgressService progressService = getSite().getService(IWorkbenchSiteProgressService.class);
		if (progressService != null) {
			progressService.schedule(s.job);
		} else {
			s.job.schedule();
		}
		display.timerExec(UPDATE_INTERVAL_MS, () -> pump(s));
	}

	/**
	 * @return the files a search narrowing the previous one has to look at: those with matches, those changed since the
	 *         previous search started, and those with unsaved changes in an editor, which the search reads too
	 */
	private static IFile[] narrowingFiles(SearchSession previous, WorkspaceSearchScope scope) {
		Set<IFile> files = new LinkedHashSet<>(previous.hitFiles);
		files.addAll(previous.changedFiles);
		for (IFileBuffer buffer : FileBuffers.getTextFileBufferManager().getFileBuffers()) {
			IFile file = buffer.isDirty() ? fileOf(buffer) : null;
			if (file != null) {
				files.add(file);
			}
		}
		files.removeIf(file -> !scope.containsFile(file));
		return files.toArray(IFile[]::new);
	}

	private static IFile fileOf(IFileBuffer buffer) {
		IPath location = buffer.getLocation();
		return location != null ? FileBuffers.getWorkspaceFileAtLocation(location) : null;
	}

	/**
	 * Replaces the results in the tree.
	 */
	private void showResult(SearchResult newResult) {
		result = newResult;
		viewer.setInput(result);
		updateEmptyState();
		preview.clear();
		editorMarks.scheduleUpdate();
		updateSelectionActions();
	}

	private void cancelSearch() {
		SearchSession s = session;
		if (s != null && s.job != null) {
			s.job.cancel();
		}
	}

	/**
	 * Periodically moves the matches found so far into the tree, so results show up while the search runs.
	 */
	private void pump(SearchSession s) {
		if (s != session || isDisposed()) {
			return;
		}
		// read the flag before draining: everything was queued before the job finished
		boolean finished = s.done;
		drain(s);
		boolean slow = System.currentTimeMillis() - s.startedAt >= PROGRESS_DELAY_MS;
		// like the summary drain just updated: gone as soon as the search is done
		if (!s.done && slow && s.totalWork > 0) {
			progressBar.setSelection((int) Math.min(PROGRESS_STEPS, s.worked.sum() * PROGRESS_STEPS / s.totalWork));
			setProgressVisible(true);
		} else {
			setProgressVisible(false);
		}
		if (finished) {
			updateSummary();
			updateSelectionActions();
			searchHistory.searched(s.query.text());
			// the search may have read these files before they changed, see resourceChanged
			if (!s.changedFiles.isEmpty()) {
				pendingRefresh.addAll(s.changedFiles);
				display.timerExec(-1, fileRefreshTrigger);
				display.timerExec(FILE_REFRESH_DELAY_MS, fileRefreshTrigger);
			}
		} else {
			display.timerExec(UPDATE_INTERVAL_MS, () -> pump(s));
		}
	}

	private void drain(SearchSession s) {
		boolean replacePrevious = !s.shown;
		if (s.incoming.isEmpty() && s.incomingNames.isEmpty()) {
			// the previous results stay a moment longer, the next matches may be about to arrive
			if (replacePrevious && !s.done && System.currentTimeMillis() - s.startedAt < STALE_RESULTS_MS) {
				return;
			}
			if (!replacePrevious) {
				updateSummary();
				return;
			}
		}
		Tree tree = viewer.getTree();
		tree.setRedraw(false);
		try {
			if (replacePrevious) {
				s.shown = true;
				showResult(new SearchResult());
			}
			addNameMatches(s);
			Set<FileMatch> newFiles = new LinkedHashSet<>();
			Map<FileMatch, List<LineMatch>> newMatches = new LinkedHashMap<>();
			LineMatch match;
			while ((match = s.incoming.poll()) != null) {
				boolean known = result.get(match.getFile()) != null;
				FileMatch fileMatch = result.add(match);
				if (!known) {
					newFiles.add(fileMatch);
				} else if (!newFiles.contains(fileMatch)) {
					newMatches.computeIfAbsent(fileMatch, f -> new ArrayList<>()).add(match);
				}
			}
			if (!newFiles.isEmpty()) {
				viewer.add(result, newFiles.toArray());
				// decided per file, a fast search delivering more than the limit in one batch still gets the first
				// files expanded
				List<FileMatch> expand = new ArrayList<>();
				for (FileMatch fileMatch : newFiles) {
					if (s.expandedMatches + fileMatch.getMatchCount() <= AUTO_EXPAND_LIMIT) {
						s.expandedMatches += fileMatch.getMatchCount();
						expand.add(fileMatch);
					}
				}
				viewer.expandFiles(expand);
			}
			for (Map.Entry<FileMatch, List<LineMatch>> entry : newMatches.entrySet()) {
				viewer.add(entry.getKey(), entry.getValue().toArray());
				viewer.update(entry.getKey(), null);
			}
		} finally {
			tree.setRedraw(true);
			fitColumn();
		}
		editorMarks.scheduleUpdate();
		updateSummary();
	}

	/**
	 * Moves the files found by their name into the result and the tree, only the most relevant ones are kept.
	 */
	private void addNameMatches(SearchSession s) {
		List<FileNameMatch> added = new ArrayList<>();
		FileNameMatch name;
		while ((name = s.incomingNames.poll()) != null) {
			SearchResult.NameMatchChange change = result.addNameMatch(name, MAX_NAME_MATCHES);
			if (change.added() != null) {
				added.add(change.added());
			}
			if (change.dropped() != null && !added.remove(change.dropped())) {
				viewer.remove(change.dropped());
			}
		}
		if (!added.isEmpty()) {
			viewer.add(result, added.toArray());
		}
	}

	private void updateSummary() {
		updateEmptyState();
		SearchSession s = session;
		if (s == null) {
			return;
		}
		if (!s.shown) {
			setSummary("Searching…", false);
			return;
		}
		String counts = formatCounts(result.getNameMatchCount(), result.getMatchCount(), result.getFileCount());
		if (!s.done) {
			setSummary(result.isEmpty() ? "Searching…" : "Searching… " + counts, false);
			return;
		}
		TextSearcher.Result outcome = s.outcome;
		StringBuilder summary = new StringBuilder(result.isEmpty() ? "No results found." : counts);
		String tooltip = null;
		if (outcome != null && outcome.limitReached()) {
			summary.append(" (stopped at ").append(TextSearcher.DEFAULT_MAX_RESULTS)
					.append(" results, narrow the search)");
		}
		if (outcome != null && outcome.status().matches(IStatus.ERROR | IStatus.WARNING)) {
			summary.append(" Some files could not be read.");
			tooltip = describe(outcome.status());
		}
		setSummary(summary.toString(), false);
		summaryLabel.setToolTipText(tooltip);
		setDuration(formatDuration(s.finishedAt - s.startedAt));
	}

	private static String describe(IStatus status) {
		StringBuilder text = new StringBuilder();
		for (IStatus child : status.isMultiStatus() ? status.getChildren() : new IStatus[] { status }) {
			if (child.matches(IStatus.ERROR | IStatus.WARNING)) {
				text.append(child.getMessage()).append('\n');
			}
		}
		return text.toString().trim();
	}

	private static String formatCounts(int names, int matches, int files) {
		String content = matches + (matches == 1 ? " result in " : " results in ") + files
				+ (files == 1 ? " file" : " files");
		if (names == 0) {
			return content;
		}
		String found = names + (names == 1 ? " file name" : " file names");
		return matches == 0 ? found : found + ", " + content;
	}

	private void setSummary(String text, boolean error) {
		summaryLabel.setText(text);
		summaryLabel.setToolTipText(null);
		summaryLabel.setForeground(error ? JFaceColors.getErrorText(display) : null);
	}

	/**
	 * Shows the empty state instead of the results and the preview while both would be empty: nothing is searched, or
	 * the search found nothing. A search without results so far keeps what's shown, so the tree doesn't blink empty.
	 */
	private void updateEmptyState() {
		SearchSession s = session;
		if (s == null) {
			boolean nothingSearched = searchText.getText().isEmpty();
			if (nothingSearched && (!emptyState.getControl().getVisible() || !emptyState.isNothingSearched())) {
				emptyState.showNothingSearched();
			}
			setEmptyStateVisible(nothingSearched);
		} else if (s.shown && !result.isEmpty()) {
			setEmptyStateVisible(false);
		} else if (s.shown && s.done && s.incoming.isEmpty() && s.incomingNames.isEmpty()) {
			emptyState.showNoResults(s.query);
			setEmptyStateVisible(true);
		}
	}

	private void setEmptyStateVisible(boolean visible) {
		Control control = emptyState.getControl();
		if (control.getVisible() != visible) {
			((GridData) control.getLayoutData()).exclude = !visible;
			control.setVisible(visible);
			((GridData) resultSash.getLayoutData()).exclude = visible;
			resultSash.setVisible(!visible);
			control.getParent().layout(true);
		}
	}

	private void setProgressVisible(boolean visible) {
		if (progressBar.getVisible() != visible) {
			((GridData) progressBar.getLayoutData()).exclude = !visible;
			progressBar.setVisible(visible);
			progressBar.getParent().layout(true);
		}
	}

	private void setDuration(String text) {
		if (!durationLabel.getText().equals(text)) {
			durationLabel.setText(text);
			durationLabel.getParent().layout(true);
		}
	}

	private static String formatDuration(long millis) {
		return millis < 1000 ? millis + " ms" : String.format("%.1f s", millis / 1000.0);
	}

	private void clear() {
		searchHistory.commit();
		searchText.setText("");
		replaceText.setText("");
		startSearch();
		searchText.setFocus();
	}

	// ---------------------------------------------------------------------------------------------------- live update

	/**
	 * Searches changed files again so the results stay in sync with saved edits, like VS Code does.
	 */
	private void resourceChanged(IResourceChangeEvent event) {
		SearchSession s = session;
		IResourceDelta delta = event.getDelta();
		if (s == null || delta == null) {
			return;
		}
		Set<IFile> files = new HashSet<>();
		try {
			delta.accept(d -> {
				IResource resource = d.getResource();
				if (resource.getType() != IResource.FILE) {
					// files that come into scope this way aren't reported as changed
					if ((d.getFlags() & (IResourceDelta.OPEN | IResourceDelta.DERIVED_CHANGED)) != 0
							|| resource.getType() == IResource.PROJECT && d.getKind() == IResourceDelta.ADDED) {
						s.scopeChanged = true;
					}
					return !(s.query.excludeDerived() && resource.isDerived());
				}
				IFile file = (IFile) resource;
				if (d.getKind() == IResourceDelta.REMOVED) {
					files.add(file);
				} else if ((d.getKind() == IResourceDelta.ADDED || (d.getFlags()
						& (IResourceDelta.CONTENT | IResourceDelta.REPLACED | IResourceDelta.DERIVED_CHANGED)) != 0)
						&& s.scope.containsFile(file)) {
					files.add(file);
				}
				return false;
			});
		} catch (CoreException e) {
			LOG.log(e.getStatus());
		}
		s.changedFiles.addAll(files);
		// a running search may or may not have seen the change, its results are only refreshed once it is done
		if (s.done && !files.isEmpty() && !display.isDisposed()) {
			display.asyncExec(() -> {
				if (s == session && !isDisposed()) {
					pendingRefresh.addAll(files);
					display.timerExec(-1, fileRefreshTrigger);
					display.timerExec(FILE_REFRESH_DELAY_MS, fileRefreshTrigger);
				}
			});
		}
	}

	/**
	 * Notes editor content that changed without a resource change, e.g. an editor closed without saving: a search
	 * narrowing the current one must look at these files again.
	 */
	private final class BufferListener implements IFileBufferListener {

		private void changed(IFileBuffer buffer) {
			SearchSession s = session;
			IFile file = s != null ? fileOf(buffer) : null;
			if (file != null) {
				s.changedFiles.add(file);
			}
		}

		@Override
		public void bufferDisposed(IFileBuffer buffer) {
			changed(buffer);
		}

		@Override
		public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) {
			changed(buffer);
		}

		@Override
		public void bufferContentReplaced(IFileBuffer buffer) {
			changed(buffer);
		}

		@Override
		public void bufferCreated(IFileBuffer buffer) {
			// the content is the one on disk
		}

		@Override
		public void bufferContentAboutToBeReplaced(IFileBuffer buffer) {
			// see bufferContentReplaced
		}

		@Override
		public void stateChanging(IFileBuffer buffer) {
			// not a content change
		}

		@Override
		public void stateValidationChanged(IFileBuffer buffer, boolean isStateValidated) {
			// not a content change
		}

		@Override
		public void underlyingFileMoved(IFileBuffer buffer, IPath path) {
			// reported as a resource change
		}

		@Override
		public void underlyingFileDeleted(IFileBuffer buffer) {
			// reported as a resource change
		}

		@Override
		public void stateChangeFailed(IFileBuffer buffer) {
			// not a content change
		}
	}

	private void flushPendingRefresh() {
		if (isDisposed() || pendingRefresh.isEmpty()) {
			return;
		}
		SearchSession s = session;
		if (s != null && !s.shown) {
			// the tree still shows the previous results, refreshing them would be lost
			display.timerExec(FILE_REFRESH_DELAY_MS, fileRefreshTrigger);
			return;
		}
		Set<IFile> files = new HashSet<>(pendingRefresh);
		pendingRefresh.clear();
		refreshFiles(files);
	}

	private void refreshFiles(Collection<IFile> files) {
		SearchSession s = session;
		if (s == null || !s.done || files.isEmpty()) {
			return;
		}
		List<IFile> searchable = new ArrayList<>();
		for (IFile file : files) {
			if (s.scope.containsFile(file)) {
				searchable.add(file);
			}
		}
		Job job = Job.create("Search: refreshing results", monitor -> {
			ConcurrentLinkedQueue<LineMatch> found = new ConcurrentLinkedQueue<>();
			if (!searchable.isEmpty()) {
				TextSearcher.search(searchable.toArray(IFile[]::new), s.pattern, TextSearcher.DEFAULT_MAX_RESULTS,
						found::add, monitor);
			}
			if (!monitor.isCanceled() && !display.isDisposed()) {
				display.asyncExec(() -> applyRefresh(s, files, found));
			}
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		job.schedule();
	}

	private void applyRefresh(SearchSession s, Collection<IFile> files, Collection<LineMatch> found) {
		if (s != session || isDisposed()) {
			return;
		}
		Map<IFile, List<LineMatch>> byFile = groupByFile(found);
		preview.invalidate(files);
		// the tree is changed at once, see dismissSelection
		List<Object> removed = new ArrayList<>();
		List<FileMatch> added = new ArrayList<>();
		List<FileMatch> expand = new ArrayList<>();
		for (IFile file : files) {
			FileMatch old = result.get(file);
			boolean expanded = old == null || viewer.getExpandedState(old);
			if (old != null) {
				result.remove(file);
				removed.add(old);
			}
			// a deleted file can't be opened from its name anymore either
			FileNameMatch name = file.exists() ? null : result.removeNameMatch(file);
			if (name != null) {
				removed.add(name);
			}
			List<LineMatch> matches = byFile.get(file);
			if (matches == null) {
				continue;
			}
			FileMatch fileMatch = null;
			for (LineMatch match : matches) {
				fileMatch = result.add(match);
			}
			added.add(fileMatch);
			if (expanded) {
				expand.add(fileMatch);
			}
		}
		Tree tree = viewer.getTree();
		tree.setRedraw(false);
		try {
			viewer.remove(removed.toArray());
			if (!added.isEmpty()) {
				viewer.add(result, added.toArray());
			}
			viewer.expandFiles(expand);
		} finally {
			tree.setRedraw(true);
			fitColumn();
		}
		editorMarks.scheduleUpdate();
		updateSummary();
		updateSelectionActions();
	}

	// ---------------------------------------------------------------------------------------------------- results

	/**
	 * Without the preview pane, a click opens the match in an editor without activating it, like in VS Code.
	 */
	private void previewOnClick(Event e) {
		if (isPreviewVisible() || e.button != 1 || e.count != 1 || (e.stateMask & SWT.MODIFIER_MASK) != 0) {
			return;
		}
		TreeItem item = viewer.getTree().getItem(new Point(e.x, e.y));
		if (item != null && item.getData() instanceof LineMatch match) {
			open(match, false);
		} else if (item != null && item.getData() instanceof FileNameMatch nameMatch) {
			open(nameMatch.getFile(), false);
		}
	}

	private void openSelection(boolean activate) {
		Object first = viewer.getStructuredSelection().getFirstElement();
		if (first instanceof LineMatch match) {
			open(match, activate);
		} else if (first instanceof FileNameMatch nameMatch) {
			open(nameMatch.getFile(), activate);
		} else if (first instanceof FileMatch fileMatch) {
			viewer.setExpandedState(fileMatch, !viewer.getExpandedState(fileMatch));
		}
	}

	private void open(LineMatch match, boolean activate) {
		IEditorPart editor = open(match.getFile(), activate);
		ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
		if (textEditor != null) {
			textEditor.selectAndReveal(match.getOffset(), match.getLength());
		}
	}

	/**
	 * @return the editor, {@code null} if it couldn't be opened
	 */
	private IEditorPart open(IFile file, boolean activate) {
		try {
			return IDE.openEditor(getSite().getPage(), file, activate);
		} catch (PartInitException e) {
			ErrorDialog.openError(getSite().getShell(), TITLE, "Could not open " + file.getName(), e.getStatus());
			return null;
		}
	}

	private List<LineMatch> selectedMatches() {
		Set<LineMatch> matches = new LinkedHashSet<>();
		for (Object element : viewer.getStructuredSelection()) {
			if (element instanceof FileMatch fileMatch) {
				matches.addAll(fileMatch.getMatches());
			} else if (element instanceof LineMatch match) {
				matches.add(match);
			}
		}
		return new ArrayList<>(matches);
	}

	private void dismissSelection() {
		IStructuredSelection selection = viewer.getStructuredSelection();
		if (selection.isEmpty()) {
			return;
		}
		List<Object> candidates = selectionCandidatesAfterRemoval(selection);
		// removed from the tree at once: every removal saves and restores the selection, removing one element after
		// the other took minutes for a few thousand selected results
		List<Object> removed = new ArrayList<>();
		Set<FileMatch> shrunk = new LinkedHashSet<>();
		for (Object element : selection) {
			if (element instanceof FileMatch fileMatch) {
				if (result.remove(fileMatch.getFile()) != null) {
					removed.add(fileMatch);
				}
			} else if (element instanceof FileNameMatch nameMatch) {
				if (result.removeNameMatch(nameMatch.getFile()) != null) {
					removed.add(nameMatch);
				}
			} else if (element instanceof LineMatch match) {
				FileMatch parent = result.get(match.getFile());
				if (parent == null) {
					continue;
				}
				if (result.remove(match)) {
					removed.add(parent);
				} else {
					removed.add(match);
					shrunk.add(parent);
				}
			}
		}
		Tree tree = viewer.getTree();
		tree.setRedraw(false);
		try {
			viewer.remove(removed.toArray());
			// the match counts, a file removed after all its matches were is ignored
			viewer.update(shrunk.toArray(), null);
		} finally {
			tree.setRedraw(true);
			fitColumn();
		}
		editorMarks.scheduleUpdate();
		for (Object candidate : candidates) {
			if (isInResult(candidate)) {
				viewer.setSelection(new StructuredSelection(candidate), true);
				break;
			}
		}
		updateSummary();
		updateSelectionActions();
	}

	/**
	 * @return the visible elements after the selection, then the ones before it in reverse order, so dismissing
	 *         repeatedly walks down the results like in VS Code
	 */
	private List<Object> selectionCandidatesAfterRemoval(IStructuredSelection selection) {
		List<Object> visible = new ArrayList<>();
		collectVisible(viewer.getTree().getItems(), visible);
		Set<Object> selected = new HashSet<>(selection.toList());
		int first = -1;
		int last = -1;
		for (int i = 0; i < visible.size(); i++) {
			if (selected.contains(visible.get(i))) {
				first = first < 0 ? i : first;
				last = i;
			}
		}
		List<Object> candidates = new ArrayList<>();
		if (last >= 0) {
			candidates.addAll(visible.subList(last + 1, visible.size()));
			for (int i = first - 1; i >= 0; i--) {
				candidates.add(visible.get(i));
			}
		}
		return candidates;
	}

	private static void collectVisible(TreeItem[] items, List<Object> out) {
		for (TreeItem item : items) {
			if (item.getData() != null) {
				out.add(item.getData());
				if (item.getExpanded()) {
					collectVisible(item.getItems(), out);
				}
			}
		}
	}

	private boolean isInResult(Object element) {
		if (element instanceof FileMatch fileMatch) {
			return result.get(fileMatch.getFile()) == fileMatch;
		}
		if (element instanceof FileNameMatch nameMatch) {
			return result.getNameMatch(nameMatch.getFile()) == nameMatch;
		}
		if (element instanceof LineMatch match) {
			FileMatch parent = result.get(match.getFile());
			return parent != null && parent.getMatches().contains(match);
		}
		return false;
	}

	private void copySelection() {
		StringBuilder text = new StringBuilder();
		for (Object element : viewer.getStructuredSelection()) {
			if (element instanceof FileMatch fileMatch) {
				text.append(fileMatch.getFile().getFullPath().makeRelative()).append('\n');
				for (LineMatch match : fileMatch.getMatches()) {
					text.append("  ").append(match.getLineNumber()).append(": ").append(match.getPreview()).append('\n');
				}
			} else if (element instanceof FileNameMatch nameMatch) {
				text.append(nameMatch.getFile().getFullPath().makeRelative()).append('\n');
			} else if (element instanceof LineMatch match) {
				text.append(match.getFile().getFullPath().makeRelative()).append(':').append(match.getLineNumber())
						.append(": ").append(match.getPreview()).append('\n');
			}
		}
		copyToClipboard(text.toString());
	}

	private void copyPaths() {
		Set<String> paths = new LinkedHashSet<>();
		for (Object element : viewer.getStructuredSelection()) {
			IFile file = element instanceof FileMatch fileMatch ? fileMatch.getFile()
					: element instanceof FileNameMatch nameMatch ? nameMatch.getFile()
							: element instanceof LineMatch match ? match.getFile() : null;
			if (file != null) {
				IPath location = file.getLocation();
				paths.add(location != null ? location.toOSString() : file.getFullPath().toString());
			}
		}
		copyToClipboard(String.join("\n", paths));
	}

	private void copyToClipboard(String text) {
		if (text.isEmpty()) {
			return;
		}
		Clipboard clipboard = new Clipboard(display);
		try {
			clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			clipboard.dispose();
		}
	}

	private void updateSelectionActions() {
		if (openAction == null || isDisposed()) {
			return;
		}
		boolean hasSelection = !viewer.getStructuredSelection().isEmpty();
		openAction.setEnabled(hasSelection);
		dismissAction.setEnabled(hasSelection);
		copyAction.setEnabled(hasSelection);
		copyPathAction.setEnabled(hasSelection);
		SearchSession s = session;
		boolean canReplace = s != null && s.done && result.getMatchCount() > 0;
		replaceSelectionAction.setEnabled(canReplace && hasSelection);
		replaceAllItem.setEnabled(canReplace);
	}

	// ---------------------------------------------------------------------------------------------------- replace

	/**
	 * @return the replacement shown in the results while the replace field is in use, {@code null} otherwise
	 */
	private String previewReplacement(LineMatch match) {
		if (replaceText == null || !isReplaceVisible() || replaceText.getText().isEmpty()) {
			return null;
		}
		String value = replaceText.getText();
		SearchSession s = session;
		if (s != null && s.query.regex()) {
			// matching the match alone is good enough for a preview; the real replace sees the whole file
			Matcher matcher = s.pattern.matcher(match.getMatchedText());
			if (matcher.matches()) {
				value = ReplacementTemplate.expand(value, matcher);
			}
		}
		return preserveCaseItem.getSelection() ? PreserveCase.apply(match.getMatchedText(), value) : value;
	}

	private void scheduleLabelRefresh() {
		display.timerExec(-1, labelRefreshTrigger);
		display.timerExec(LABEL_REFRESH_DELAY_MS, labelRefreshTrigger);
	}

	/**
	 * Updates the labels of the matches, the only ones showing the replacement and the context before the match. A
	 * refresh of the whole tree would also fetch and sort all files again, which made typing a replacement lag with
	 * thousands of expanded results.
	 */
	private void refreshLabels() {
		if (isDisposed()) {
			return;
		}
		Tree tree = viewer.getTree();
		List<Object> matches = new ArrayList<>();
		for (TreeItem file : tree.getItems()) {
			// a file never expanded has a placeholder without data, its matches get the current labels once created
			for (TreeItem item : file.getItems()) {
				if (item.getData() instanceof LineMatch match) {
					matches.add(match);
				}
			}
		}
		tree.setRedraw(false);
		try {
			viewer.update(matches.toArray(), null);
		} finally {
			tree.setRedraw(true);
		}
		fitColumn();
		// the label provider doesn't redraw cells itself
		tree.redraw();
	}

	void replaceAll(boolean confirm) {
		if (!isReplaceVisible()) {
			setReplaceVisible(true);
			return;
		}
		replace(result.getAllMatches(), confirm);
	}

	private void replace(List<LineMatch> matches, boolean confirm) {
		SearchSession s = session;
		if (s == null || matches.isEmpty()) {
			return;
		}
		if (!s.done) {
			MessageDialog.openInformation(getSite().getShell(), TITLE, "Please wait until the search has finished.");
			return;
		}
		String replacement = replaceText.getText();
		boolean preserveCase = preserveCaseItem.getSelection();
		Map<IFile, List<LineMatch>> byFile = groupByFile(matches);
		if (confirm && !MessageDialog.openConfirm(getSite().getShell(), "Replace All",
				MessageFormat.format("Replace {0} {1} across {2} {3} with ''{4}''?", matches.size(),
						matches.size() == 1 ? "occurrence" : "occurrences", byFile.size(),
						byFile.size() == 1 ? "file" : "files", replacement))) {
			return;
		}
		String undoLabel = "Replace '" + abbreviate(s.query.text()) + "' with '" + abbreviate(replacement) + "'";
		AtomicReference<Replacer.Outcome> outcome = new AtomicReference<>();
		try {
			PlatformUI.getWorkbench().getProgressService().busyCursorWhile(monitor -> {
				try {
					outcome.set(Replacer.replace(byFile, s.pattern, s.query.regex(), replacement, preserveCase,
							undoLabel, monitor));
				} catch (CoreException e) {
					throw new InvocationTargetException(e);
				}
			});
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			IStatus status = cause instanceof CoreException coreException ? coreException.getStatus()
					: Status.error("Replace failed", cause);
			LOG.log(status);
			ErrorDialog.openError(getSite().getShell(), TITLE, "The replace failed.", status);
		} catch (InterruptedException e) {
			// canceled by the user
		}
		// show what is left: replaced matches disappear, new occurrences (e.g. foo -> foobar) show up
		refreshFiles(byFile.keySet());

		Replacer.Outcome done = outcome.get();
		if (done == null) {
			return;
		}
		if (done.status().hasError()) {
			MessageDialog.openError(getSite().getShell(), TITLE,
					done.status().getMessageMatchingSeverity(done.status().getSeverity()));
		}
		if (!done.staleFiles().isEmpty()) {
			MessageDialog.openWarning(getSite().getShell(), TITLE, MessageFormat.format(
					"{0} {1} changed since the search and {2} skipped. The results have been refreshed, review them and replace again.",
					done.staleFiles().size(), done.staleFiles().size() == 1 ? "file" : "files",
					done.staleFiles().size() == 1 ? "was" : "were"));
		}
	}

	private static Map<IFile, List<LineMatch>> groupByFile(Collection<LineMatch> matches) {
		Map<IFile, List<LineMatch>> byFile = new LinkedHashMap<>();
		for (LineMatch match : matches) {
			byFile.computeIfAbsent(match.getFile(), f -> new ArrayList<>()).add(match);
		}
		return byFile;
	}

	private static String abbreviate(String text) {
		return text.length() <= 40 ? text : text.substring(0, 39) + "…";
	}

	// ---------------------------------------------------------------------------------------------------- lifecycle

	/**
	 * Focuses the search field, optionally replacing the search text.
	 *
	 * @param initialText the text to search for, {@code null} to keep the current text
	 */
	public void activateSearch(String initialText) {
		if (initialText != null && !initialText.isEmpty()) {
			searchText.setText(regexAction.isChecked() ? SearchPatterns.escapeRegex(initialText)
					: wildcardAction.isChecked() ? SearchPatterns.escapeWildcards(initialText) : initialText);
			scheduleSearch(0);
		}
		searchText.setFocus();
		searchText.selectAll();
	}

	@Override
	public void setFocus() {
		searchText.setFocus();
	}

	// ---------------------------------------------------------------------------------------------------- UI tests

	Composite getRoot() {
		return root;
	}

	Text getSearchText() {
		return searchText;
	}

	Combo getIncludeField() {
		return includeField;
	}

	Combo getExcludeField() {
		return excludeField;
	}

	FieldHistory getSearchHistory() {
		return searchHistory;
	}

	FieldHistory getIncludeHistory() {
		return includeHistory;
	}

	Text getReplaceText() {
		return replaceText;
	}

	ToolItem getCaseItem() {
		return caseItem;
	}

	Action getRegexAction() {
		return regexAction;
	}

	ToolItem getReplaceToggleItem() {
		return replaceToggleItem;
	}

	ResultViewer getViewer() {
		return viewer;
	}

	SearchResult getResult() {
		return result;
	}

	String getSummary() {
		return summaryLabel.getText();
	}

	/**
	 * @return {@code true} while a search runs or its last matches are not yet in the tree
	 */
	boolean isSearching() {
		SearchSession s = session;
		return searchScheduled
				|| s != null && (!s.done || !s.shown || !s.incoming.isEmpty() || !s.incomingNames.isEmpty())
				|| getSummary().startsWith("Searching");
	}

	/**
	 * @return {@code true} if the last search only looked at the files of the one before
	 */
	boolean isNarrowed() {
		SearchSession s = session;
		return s != null && s.narrowed;
	}

	void openSelectionInEditor() {
		openSelection(true);
	}

	PreviewPane getPreview() {
		return preview;
	}

	private boolean isDisposed() {
		return viewer == null || viewer.getControl().isDisposed();
	}

	private void restoreState() {
		IMemento m = memento;
		searchText.setText(string(m, KEY_QUERY, ""));
		replaceText.setText(string(m, KEY_REPLACE, ""));
		includeField.setText(string(m, KEY_INCLUDES, DEFAULT_INCLUDES));
		String excludes = string(m, KEY_EXCLUDES, DEFAULT_EXCLUDES);
		excludeField.setText(PREVIOUS_DEFAULT_EXCLUDES.equals(excludes) ? DEFAULT_EXCLUDES : excludes);
		searchHistory.restore(string(m, KEY_SEARCH_HISTORY, null));
		includeHistory.restore(string(m, KEY_INCLUDE_HISTORY, null));
		excludeHistory.restore(string(m, KEY_EXCLUDE_HISTORY, null));
		caseItem.setSelection(bool(m, KEY_CASE, false));
		wordItem.setSelection(bool(m, KEY_WORD, false));
		regexAction.setChecked(bool(m, KEY_REGEX, false));
		preserveCaseItem.setSelection(bool(m, KEY_PRESERVE_CASE, false));
		for (ToolItem item : new ToolItem[] { caseItem, wordItem, preserveCaseItem }) {
			updateToggle(item);
		}
		derivedAction.setChecked(bool(m, KEY_DERIVED, true));
		wildcardAction.setChecked(bool(m, KEY_WILDCARDS, true));
		fileNamesAction.setChecked(bool(m, KEY_FILE_NAMES, true));
		boolean replaceVisible = bool(m, KEY_REPLACE_VISIBLE, false);
		setVisible(replaceVisible, replaceText, replaceBar);
		updateReplaceToggle(replaceVisible);
		resultSash.setWeights(weights(string(m, KEY_PREVIEW_WEIGHTS, null)));
		setPreviewVisible(bool(m, KEY_PREVIEW, true));
	}

	private static int[] weights(String value) {
		if (value != null) {
			String[] parts = value.split(",");
			try {
				int[] weights = { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
				if (parts.length == 2 && weights[0] > 0 && weights[1] > 0) {
					return weights;
				}
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				// use the default
			}
		}
		return DEFAULT_PREVIEW_WEIGHTS;
	}

	@Override
	public void saveState(IMemento m) {
		if (isDisposed()) {
			if (memento != null) {
				m.putMemento(memento);
			}
			return;
		}
		m.putString(KEY_QUERY, searchText.getText());
		m.putString(KEY_REPLACE, replaceText.getText());
		m.putString(KEY_INCLUDES, includeField.getText());
		m.putString(KEY_EXCLUDES, excludeField.getText());
		m.putString(KEY_SEARCH_HISTORY, searchHistory.save());
		m.putString(KEY_INCLUDE_HISTORY, includeHistory.save());
		m.putString(KEY_EXCLUDE_HISTORY, excludeHistory.save());
		m.putBoolean(KEY_CASE, caseItem.getSelection());
		m.putBoolean(KEY_WORD, wordItem.getSelection());
		m.putBoolean(KEY_REGEX, regexAction.isChecked());
		m.putBoolean(KEY_PRESERVE_CASE, preserveCaseItem.getSelection());
		m.putBoolean(KEY_DERIVED, derivedAction.isChecked());
		m.putBoolean(KEY_WILDCARDS, wildcardAction.isChecked());
		m.putBoolean(KEY_FILE_NAMES, fileNamesAction.isChecked());
		m.putBoolean(KEY_REPLACE_VISIBLE, isReplaceVisible());
		m.putBoolean(KEY_PREVIEW, isPreviewVisible());
		int[] weights = resultSash.getWeights();
		m.putString(KEY_PREVIEW_WEIGHTS, weights[0] + "," + weights[1]);
	}

	private static String string(IMemento m, String key, String defaultValue) {
		String value = m != null ? m.getString(key) : null;
		return value != null ? value : defaultValue;
	}

	private static boolean bool(IMemento m, String key, boolean defaultValue) {
		Boolean value = m != null ? m.getBoolean(key) : null;
		return value != null ? value : defaultValue;
	}

	@Override
	public void dispose() {
		if (activationListener != null) {
			getSite().getPage().removePartListener(activationListener);
		}
		if (resourceListener != null) {
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
			FileBuffers.getTextFileBufferManager().removeFileBufferListener(bufferListener);
		}
		cancelSearch();
		session = null;
		if (editorMarks != null) {
			editorMarks.dispose();
		}
		if (undoHandler != null) {
			undoHandler.dispose();
			redoHandler.dispose();
		}
		super.dispose();
	}
}
