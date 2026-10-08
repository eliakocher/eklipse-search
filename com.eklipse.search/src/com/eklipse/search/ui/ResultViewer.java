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
 * with 20 matches each took 8 s instead of 40 ms.
 */
final class ResultViewer extends TreeViewer {

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
		Tree tree = getTree();
		tree.setRedraw(false);
		try {
			for (Item item : items) {
				createChildren(item);
			}
			// bottom up, in tree order that's another 4 times faster on macOS with thousands of files
			for (int i = items.size() - 1; i >= 0; i--) {
				setExpanded(items.get(i), true);
			}
		} finally {
			tree.setRedraw(true);
		}
	}
}
