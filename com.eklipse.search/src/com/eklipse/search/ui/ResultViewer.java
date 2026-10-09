package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Tree;

/**
 * Expands files in two passes: first the matches of all of them are created, then they are expanded. JFace does both
 * file by file, and on macOS expanding an item while new items are pending reloads the whole tree: expanding 1000 files
 * with 20 matches each took 8 s instead of 40 ms. Also keeps a big selection from slowing down changes of the tree, see
 * {@link #preservingSelection(Runnable)}.
 */
final class ResultViewer extends TreeViewer {

	/** From this many selected results on, a change of the tree clears the selection first. */
	private static final int MANY_SELECTED = 100;

	ResultViewer(Composite parent, int style) {
		super(parent, style);
	}

	@Override
	public void expandAll() {
		expand(List.of(getTree().getItems()));
	}

	/**
	 * Expands the given files, the ones not in the tree are ignored.
	 *
	 * @param files the files to expand
	 */
	void expandFiles(Collection<?> files) {
		List<Item> items = new ArrayList<>(files.size());
		for (Object file : files) {
			if (findItem(file) instanceof Item item) {
				items.add(item);
			}
		}
		expand(items);
	}

	private void expand(List<? extends Item> items) {
		if (items.isEmpty()) {
			return;
		}
		Tree tree = getTree();
		tree.setRedraw(false);
		try {
			// creating the children disposes the placeholder of each file, see preservingSelection
			preservingSelection(() -> {
				for (Item item : items) {
					createChildren(item);
				}
				// bottom up, in tree order that's another 4 times faster on macOS with thousands of files
				for (int i = items.size() - 1; i >= 0; i--) {
					setExpanded(items.get(i), true);
				}
			});
		} finally {
			tree.setRedraw(true);
		}
	}

	/**
	 * Clears a big selection of the tree while it changes, the viewer selects what's left of it afterwards. SWT on macOS
	 * reads and restores the whole selection whenever an item is disposed: dismissing a few thousand selected results
	 * took minutes. A small selection stays: selecting it again scrolls to it, an unchanged one the viewer leaves alone.
	 */
	@Override
	protected void preservingSelection(Runnable updateCode) {
		Tree tree = getTree();
		if (tree.getSelectionCount() < MANY_SELECTED) {
			super.preservingSelection(updateCode);
			return;
		}
		super.preservingSelection(() -> {
			tree.deselectAll();
			updateCode.run();
		});
	}
}
