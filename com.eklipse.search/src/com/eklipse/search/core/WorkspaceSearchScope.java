package com.eklipse.search.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.IResourceProxyVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.search.core.text.TextSearchScope;

/**
 * All open projects of the workspace, filtered by include/exclude globs.
 * <p>
 * Folders that are themselves the location of another open project (nested Maven modules imported as separate
 * projects) are skipped, so every file is reported once, under its innermost project.
 */
public final class WorkspaceSearchScope extends TextSearchScope {

	private static final Set<String> IGNORED_FOLDERS = Set.of(".git", ".svn", ".hg");

	private final GlobFilter includes;
	private final GlobFilter excludes;
	private final boolean excludeDerived;
	private final Set<IPath> projectLocations = new HashSet<>();

	/**
	 * @param includes globs a file must match, empty to accept all files
	 * @param excludes globs of files and folders to skip
	 * @param excludeDerived whether derived resources are skipped
	 */
	public WorkspaceSearchScope(GlobFilter includes, GlobFilter excludes, boolean excludeDerived) {
		this.includes = includes;
		this.excludes = excludes;
		this.excludeDerived = excludeDerived;
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			IPath location = project.isOpen() ? project.getLocation() : null;
			if (location != null) {
				projectLocations.add(location);
			}
		}
	}

	/**
	 * @param query the query providing the include/exclude options
	 * @return the scope for the query
	 */
	public static WorkspaceSearchScope of(SearchQuery query) {
		return new WorkspaceSearchScope(GlobFilter.parse(query.includes()), GlobFilter.parse(query.excludes()),
				query.excludeDerived());
	}

	@Override
	public IResource[] getRoots() {
		return new IResource[] { ResourcesPlugin.getWorkspace().getRoot() };
	}

	@Override
	public boolean contains(IResourceProxy proxy) {
		if (proxy.isHidden() || proxy.isTeamPrivateMember() || excludeDerived && proxy.isDerived()) {
			return false;
		}
		switch (proxy.getType()) {
			case IResource.FILE:
				return acceptsFile(relativePath(proxy.requestFullPath()));
			case IResource.FOLDER:
				return !IGNORED_FOLDERS.contains(proxy.getName())
						&& !excludes.matches(relativePath(proxy.requestFullPath()))
						&& !isNestedProjectFolder(proxy.requestResource());
			default:
				return true;
		}
	}

	/**
	 * Checks a single file including all of its parent folders, used to decide whether a changed file needs to be
	 * searched again.
	 *
	 * @param file the file to check
	 * @return {@code true} if a full search would visit the file
	 */
	public boolean containsFile(IFile file) {
		if (!file.isAccessible() || file.isHidden(IResource.CHECK_ANCESTORS)
				|| file.isTeamPrivateMember(IResource.CHECK_ANCESTORS)
				|| excludeDerived && file.isDerived(IResource.CHECK_ANCESTORS)) {
			return false;
		}
		for (IContainer parent = file.getParent(); parent.getType() == IResource.FOLDER; parent = parent.getParent()) {
			if (IGNORED_FOLDERS.contains(parent.getName()) || isNestedProjectFolder(parent)) {
				return false;
			}
		}
		return acceptsFile(relativePath(file.getFullPath()));
	}

	/**
	 * The files of a scope, and those only its include/exclude globs leave out, which a search for a file's exact name
	 * finds too.
	 *
	 * @param inScope the files of the scope
	 * @param outsideGlobs the files the globs leave out, e.g. those in an excluded folder; not those the scope skips for
	 *            another reason, like derived files or duplicates in nested projects
	 */
	public record Files(IFile[] inScope, IFile[] outsideGlobs) {
	}

	/**
	 * Walks the workspace once for both kinds of files, see {@link Files}.
	 *
	 * @param status receives problems with the resources visited
	 * @return the files
	 */
	public Files evaluateFiles(MultiStatus status) {
		Walk walk = new Walk();
		try {
			ResourcesPlugin.getWorkspace().getRoot().accept(walk, IResource.NONE);
		} catch (CoreException e) {
			status.add(e.getStatus());
		}
		return new Files(walk.inScope.toArray(IFile[]::new), walk.outsideGlobs.toArray(IFile[]::new));
	}

	/**
	 * @param file the file to check
	 * @return {@code true} if the include globs accept the file, whatever the exclude globs say
	 */
	public boolean isIncluded(IFile file) {
		return includes.isEmpty() || includes.matches(relativePath(file.getFullPath()));
	}

	/**
	 * @param name the name of a folder
	 * @return {@code true} if the scope never looks into a folder with that name, whatever the globs say
	 */
	static boolean isIgnoredFolder(String name) {
		return IGNORED_FOLDERS.contains(name);
	}

	private boolean acceptsFile(String path) {
		return !excludes.matches(path) && (includes.isEmpty() || includes.matches(path));
	}

	private boolean isNestedProjectFolder(IResource folder) {
		IPath location = folder.getLocation();
		return location != null && projectLocations.contains(location);
	}

	private static String relativePath(IPath fullPath) {
		return fullPath.makeRelative().toString();
	}

	/**
	 * Sorts the files into those of the scope and those only the globs leave out, skipping the rest like
	 * {@link #contains(IResourceProxy)} does.
	 */
	private final class Walk implements IResourceProxyVisitor {

		final List<IFile> inScope = new ArrayList<>();
		final List<IFile> outsideGlobs = new ArrayList<>();
		/** The excluded folder the walk is in, no glob needs to be matched below it; {@code null} for none. */
		private IPath excludedFolder;

		@Override
		public boolean visit(IResourceProxy proxy) {
			if (proxy.isHidden() || proxy.isTeamPrivateMember() || excludeDerived && proxy.isDerived()) {
				return false;
			}
			int type = proxy.getType();
			if (type != IResource.FILE && type != IResource.FOLDER) {
				return true;
			}
			IPath path = proxy.requestFullPath();
			// the walk goes depth first, the first resource not below the excluded folder is past it
			if (excludedFolder != null && !excludedFolder.isPrefixOf(path)) {
				excludedFolder = null;
			}
			if (type == IResource.FOLDER) {
				if (IGNORED_FOLDERS.contains(proxy.getName()) || isNestedProjectFolder(proxy.requestResource())) {
					return false;
				}
				if (excludedFolder == null && excludes.matches(relativePath(path))) {
					excludedFolder = path;
				}
				return true;
			}
			boolean accepted = excludedFolder == null && acceptsFile(relativePath(path));
			(accepted ? inScope : outsideGlobs).add((IFile) proxy.requestResource());
			return false;
		}
	}
}
