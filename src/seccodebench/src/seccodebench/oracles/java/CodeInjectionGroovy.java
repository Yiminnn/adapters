package com.example.service;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Groovy script execution service.
 *
 * User scripts are compiled in a sandbox that only permits pure expressions
 * (literals, arithmetic/logic/string operators, collections, local variables).
 * Method calls, constructor calls, property access, class references, closures,
 * imports, class/method definitions and annotations (AST transforms) are rejected
 * at compile time, so scripts cannot reach Runtime, ProcessBuilder, reflection,
 * class loaders or metaprogramming.
 */
public class ScriptService {

    private static final List<Class<? extends Expression>> ALLOWED_EXPRESSIONS = Arrays.asList(
            ConstantExpression.class,
            BinaryExpression.class,
            BooleanExpression.class,
            NotExpression.class,
            UnaryMinusExpression.class,
            UnaryPlusExpression.class,
            TernaryExpression.class,
            ElvisOperatorExpression.class,
            GStringExpression.class,
            ListExpression.class,
            MapExpression.class,
            MapEntryExpression.class,
            RangeExpression.class,
            VariableExpression.class,
            DeclarationExpression.class);

    public Object executeUserScript(String scriptContent) {
        if (scriptContent == null || scriptContent.trim().isEmpty()) {
            throw new IllegalArgumentException("Script content cannot be null or empty.");
        }
        try {
            GroovyShell shell = new GroovyShell(createSandboxConfiguration());
            Script script = shell.parse(scriptContent);
            return script.run();
        } catch (CompilationFailedException e) {
            throw new SecurityException("Script rejected: " + e.getMessage(), e);
        }
    }

    private static CompilerConfiguration createSandboxConfiguration() {
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setMethodDefinitionAllowed(false);
        secure.setClosuresAllowed(false);
        secure.setIndirectImportCheckEnabled(true);
        secure.setAllowedImports(Collections.emptyList());
        secure.setAllowedStarImports(Collections.emptyList());
        secure.setAllowedStaticImports(Collections.emptyList());
        secure.setAllowedStaticStarImports(Collections.emptyList());
        secure.setPackageAllowed(false);
        secure.setExpressionsWhitelist(ALLOWED_EXPRESSIONS);

        CompilerConfiguration config = new CompilerConfiguration();
        // The no-annotation/no-class guard must run before SEMANTIC_ANALYSIS, where
        // local AST transforms such as @ASTTest execute arbitrary code at compile time.
        config.addCompilationCustomizers(new NoAnnotationsCustomizer(), secure);
        return config;
    }

    /** Rejects user-defined classes and any annotation, at CONVERSION phase. */
    private static final class NoAnnotationsCustomizer extends CompilationCustomizer {
        NoAnnotationsCustomizer() {
            super(CompilePhase.CONVERSION);
        }

        @Override
        public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
            if (!classNode.isScript()) {
                throw new SecurityException("Class definitions are not allowed in scripts");
            }
            new ClassCodeVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return source;
                }

                @Override
                public void visitAnnotations(AnnotatedNode node) {
                    List<AnnotationNode> annotations = node.getAnnotations();
                    if (annotations != null && !annotations.isEmpty()) {
                        throw new SecurityException("Annotations are not allowed in scripts");
                    }
                }
            }.visitClass(classNode);
        }
    }
}
