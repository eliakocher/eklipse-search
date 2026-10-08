package com.eklipse.search.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.YieldStatement;
import org.eclipse.jdt.internal.ui.javaeditor.PositionCollectorCore;
import org.eclipse.jdt.internal.ui.javaeditor.SemanticHighlighting;
import org.eclipse.jdt.internal.ui.javaeditor.SemanticHighlightings;
import org.eclipse.jdt.internal.ui.javaeditor.SemanticHighlightingsCore;
import org.eclipse.jdt.internal.ui.javaeditor.SemanticToken;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.editors.text.EditorsUI;

/**
 * The semantic highlighting of the Java editor: fields, static members, local variables, parameters, types, ...
 * Computed like the editor does it, with JDT's own rules on an AST with bindings. These are JDT internals (the same
 * from 2024-03 to 2026-09); a JDT without them throws a {@link LinkageError}.
 */
final class JavaSemanticColors {

	private record Token(int offset, int length, SemanticHighlighting highlighting) {
	}

	private JavaSemanticColors() {
	}

	/**
	 * Parses the source and finds what to color, in a background thread.
	 *
	 * @param file the file of the source, its project's class path resolves the bindings
	 * @param preferences the preferences of the Java editor
	 * @return creates the styles in the UI thread, sorted and without overlaps, {@code null} if all semantic
	 *         highlightings are switched off or the monitor was canceled
	 */
	static Supplier<StyleRange[]> compute(IFile file, String source, IPreferenceStore preferences,
			IProgressMonitor monitor) {
		List<SemanticHighlighting> enabled = new ArrayList<>();
		for (SemanticHighlighting highlighting : SemanticHighlightings.getSemanticHighlightings()) {
			if (preferences.getBoolean(SemanticHighlightings.getEnabledPreferenceKey(highlighting))) {
				enabled.add(highlighting);
			}
		}
		if (enabled.isEmpty()) {
			return null;
		}
		CompilationUnit ast = parse(file, source, monitor);
		if (monitor.isCanceled()) {
			return null;
		}
		Collector collector = new Collector(enabled);
		ast.accept(collector);
		List<Token> tokens = collector.tokens;
		tokens.sort(Comparator.comparingInt(Token::offset));
		return () -> toStyles(tokens, preferences);
	}

	private static CompilationUnit parse(IFile file, String source, IProgressMonitor monitor) {
		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setSource(source.toCharArray());
		parser.setUnitName(file.getFullPath().toString());
		IJavaProject project = JavaCore.create(file.getProject());
		if (project.exists()) {
			parser.setProject(project);
		} else {
			// outside of Java projects only the JDK and the file itself are known
			parser.setEnvironment(new String[0], new String[0], null, true);
			Map<String, String> options = JavaCore.getOptions();
			JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
			parser.setCompilerOptions(options);
		}
		// like the editor's AST
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setStatementsRecovery(true);
		return (CompilationUnit) parser.createAST(monitor);
	}

	private static StyleRange[] toStyles(List<Token> tokens, IPreferenceStore preferences) {
		Map<SemanticHighlighting, StyleRange> templates = new HashMap<>();
		List<StyleRange> styles = new ArrayList<>(tokens.size());
		int previousEnd = 0;
		for (Token token : tokens) {
			if (token.offset() < previousEnd) {
				continue;
			}
			StyleRange style = (StyleRange) templates.computeIfAbsent(token.highlighting(),
					highlighting -> template(highlighting, preferences)).clone();
			style.start = token.offset();
			style.length = token.length();
			styles.add(style);
			previousEnd = token.offset() + token.length();
		}
		return styles.toArray(StyleRange[]::new);
	}

	/**
	 * @return the style of a highlighting like the editor's {@code SemanticHighlightingManager} creates it
	 */
	private static StyleRange template(SemanticHighlighting highlighting, IPreferenceStore preferences) {
		StyleRange style = new StyleRange();
		RGB color = PreferenceConverter.getColor(preferences, SemanticHighlightings.getColorPreferenceKey(highlighting));
		style.foreground = EditorsUI.getSharedTextColors().getColor(color);
		if (preferences.getBoolean(SemanticHighlightings.getBoldPreferenceKey(highlighting))) {
			style.fontStyle |= SWT.BOLD;
		}
		if (preferences.getBoolean(SemanticHighlightings.getItalicPreferenceKey(highlighting))) {
			style.fontStyle |= SWT.ITALIC;
		}
		style.strikeout = preferences.getBoolean(SemanticHighlightings.getStrikethroughPreferenceKey(highlighting));
		style.underline = preferences.getBoolean(SemanticHighlightings.getUnderlinePreferenceKey(highlighting));
		return style;
	}

	/**
	 * Mirrors the {@code PositionCollector} of the editor's {@code SemanticHighlightingReconciler}: the first enabled
	 * highlighting that consumes a name or literal colors it.
	 */
	private static final class Collector extends PositionCollectorCore {

		private final List<SemanticHighlighting> highlightings;
		private final SemanticHighlighting deprecated;
		private final SemanticHighlighting restrictedKeyword;
		private final SemanticToken token = new SemanticToken();
		private final List<Token> tokens = new ArrayList<>();

		Collector(List<SemanticHighlighting> highlightings) {
			this.highlightings = highlightings;
			deprecated = find(SemanticHighlightingsCore.DEPRECATED_MEMBER);
			restrictedKeyword = find(SemanticHighlightingsCore.RESTRICTED_KEYWORDS);
		}

		private SemanticHighlighting find(String preferenceKey) {
			return highlightings.stream().filter(h -> preferenceKey.equals(h.getPreferenceKey())).findFirst()
					.orElse(null);
		}

		@Override
		protected boolean visitLiteral(Expression node) {
			token.update(node);
			for (SemanticHighlighting highlighting : highlightings) {
				if (highlighting.consumesLiteral(token)) {
					add(node.getStartPosition(), node.getLength(), highlighting);
					break;
				}
			}
			token.clear();
			return false;
		}

		@Override
		public boolean visit(SimpleName node) {
			token.update(node);
			for (SemanticHighlighting highlighting : highlightings) {
				if (highlighting.consumes(token)) {
					add(node.getStartPosition(), node.getLength(), highlighting);
					break;
				}
			}
			token.clear();
			return false;
		}

		@Override
		public boolean visit(ConstructorInvocation node) {
			addIfDeprecated(node.resolveConstructorBinding(), node.getStartPosition(), "this".length());
			return true;
		}

		@Override
		public boolean visit(SuperConstructorInvocation node) {
			addIfDeprecated(node.resolveConstructorBinding(), node.getStartPosition(), "super".length());
			return true;
		}

		@Override
		public boolean visit(SimpleType node) {
			if (node.isVar()) {
				add(node.getStartPosition(), node.getLength(), restrictedKeyword);
				return false;
			}
			return true;
		}

		@Override
		public boolean visit(YieldStatement node) {
			if (!node.isImplicit()) {
				add(node.getStartPosition(), "yield".length(), restrictedKeyword);
			}
			return true;
		}

		@Override
		public boolean visit(RecordDeclaration node) {
			add(node.getRestrictedIdentifierStartPosition(), "record".length(), restrictedKeyword);
			return true;
		}

		@Override
		public boolean visit(TypeDeclaration node) {
			if (!node.permittedTypes().isEmpty()) {
				add(node.getRestrictedIdentifierStartPosition(), "permits".length(), restrictedKeyword);
			}
			return true;
		}

		@Override
		public boolean visit(Modifier node) {
			if (node.getKeyword() == ModifierKeyword.SEALED_KEYWORD) {
				add(node.getStartPosition(), "sealed".length(), restrictedKeyword);
			} else if (node.getKeyword() == ModifierKeyword.NON_SEALED_KEYWORD) {
				add(node.getStartPosition(), "non-sealed".length(), restrictedKeyword);
			}
			return true;
		}

		@Override
		protected void retainPositions(int offset, int length) {
			// the editor keeps the previous colors of malformed code, the preview has none
		}

		private void addIfDeprecated(IMethodBinding constructor, int offset, int length) {
			if (constructor != null && constructor.isDeprecated()) {
				add(offset, length, deprecated);
			}
		}

		private void add(int offset, int length, SemanticHighlighting highlighting) {
			if (highlighting != null && offset >= 0 && length > 0) {
				tokens.add(new Token(offset, length, highlighting));
			}
		}
	}
}
