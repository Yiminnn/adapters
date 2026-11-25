package com.example.service;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.BooleanExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.syntax.Types;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Groovy script execution service.
 *
 * User scripts are compiled in a sandbox that only permits pure expressions
 * (literals, arithmetic/logic/string operators, collections, local variables).
 * Method calls, constructor calls, property access, class references, closures,
 * imports, class/method definitions, annotations (AST transforms) and loops are rejected
 * at compile time, and Grape's global @Grab transformation is disabled, so scripts cannot
 * reach Runtime, ProcessBuilder, reflection, class loaders, metaprogramming or remote
 * dependencies, and always terminate (no loops, closures or methods means no recursion).
 * Size-amplifying operators (+, *, **, <<) are routed through bounded helpers so a short
 * script cannot exhaust the heap (e.g. 'x' * 1000000000); GStrings and compound
 * assignments (which could double a value per statement unchecked) are rejected.
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
            ListExpression.class,
            MapExpression.class,
            MapEntryExpression.class,
            RangeExpression.class,
            VariableExpression.class,
            DeclarationExpression.class);

    // No While/For/DoWhile/Switch/Try/Throw...: only straight-line code and conditionals.
    private static final List<Class<? extends Statement>> ALLOWED_STATEMENTS = Arrays.asList(
            BlockStatement.class,
            ExpressionStatement.class,
            ReturnStatement.class,
            IfStatement.class,
            EmptyStatement.class);

    private static final List<Integer> COMPOUND_ASSIGNMENTS = Arrays.asList(
            Types.PLUS_EQUAL, Types.MINUS_EQUAL, Types.MULTIPLY_EQUAL, Types.DIVIDE_EQUAL,
            Types.INTDIV_EQUAL, Types.MOD_EQUAL, Types.POWER_EQUAL, Types.LEFT_SHIFT_EQUAL,
            Types.RIGHT_SHIFT_EQUAL, Types.RIGHT_SHIFT_UNSIGNED_EQUAL, Types.BITWISE_OR_EQUAL,
            Types.BITWISE_AND_EQUAL, Types.BITWISE_XOR_EQUAL, Types.ELVIS_EQUAL);

    /** Max characters / elements of any string or collection a script may build. */
    static final long MAX_VALUE_SIZE = 1_000_000L;
    /** Max bit length of an integer power result. */
    static final long MAX_POWER_BITS = 1L << 20;

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
        secure.setStatementsWhitelist(ALLOWED_STATEMENTS);
        // Data-only: no typed declarations (implicit constructor/cast coercion, e.g.
        // `java.io.File f = ['/x']`), no subscript (getAt/putAt read and write arbitrary
        // host properties), no `this`/`super` (the Script object). Every reachable value
        // is then a literal-built number/string/boolean/list/map/range.
        secure.addExpressionCheckers(ScriptService::isDataOnly);

        CompilerConfiguration config = new CompilerConfiguration();
        // Grape's GrabAnnotationTransformation is a *global* AST transform that runs at
        // CONVERSION (before any customizer) and could fetch a dependency carrying its own
        // AST transform; disable it outright.
        config.setDisabledGlobalASTTransformations(
                new HashSet<>(Collections.singletonList("groovy.grape.GrabAnnotationTransformation")));
        // The no-annotation/no-class guard must run before SEMANTIC_ANALYSIS, where
        // local AST transforms such as @ASTTest execute arbitrary code at compile time.
        // BoundedOperators runs after `secure` in the same phase, so the allowlist checks the
        // user's AST and the rewritten helper calls are never subject to it.
        config.addCompilationCustomizers(new NoAnnotationsCustomizer(), secure, new BoundedOperators());
        return config;
    }

    private static boolean isDataOnly(Expression expr) {
        if (expr instanceof DeclarationExpression) {
            DeclarationExpression decl = (DeclarationExpression) expr;
            if (decl.isMultipleAssignmentDeclaration()) {
                return false;
            }
            return decl.getVariableExpression().isDynamicTyped();
        }
        if (expr instanceof BinaryExpression) {
            int op = ((BinaryExpression) expr).getOperation().getType();
            return op != Types.LEFT_SQUARE_BRACKET && !COMPOUND_ASSIGNMENTS.contains(op);
        }
        if (expr instanceof VariableExpression) {
            VariableExpression var = (VariableExpression) expr;
            return !var.isThisExpression() && !var.isSuperExpression();
        }
        return true;
    }

    private static long sizeOf(Object o) {
        if (o instanceof CharSequence) return ((CharSequence) o).length();
        if (o instanceof Collection) return ((Collection<?>) o).size();
        if (o instanceof Map) return ((Map<?, ?>) o).size();
        if (o != null && o.getClass().isArray()) return Array.getLength(o);
        return 0;
    }

    /** Called (via the AST rewrite) for every +, *, ** and << in a user script. */
    public static Object boundedOp(String method, Object left, Object right) {
        double size;
        if (method.equals("multiply") && right instanceof Number) {
            size = (double) sizeOf(left) * Math.abs(((Number) right).doubleValue());
        } else {
            size = (double) sizeOf(left) + sizeOf(right) + 1;
        }
        if (size > MAX_VALUE_SIZE) {
            throw new SecurityException("Script result exceeds the size limit");
        }
        if (method.equals("power") && left instanceof Number && right instanceof Number) {
            double bits = left instanceof BigInteger ? ((BigInteger) left).bitLength()
                    : 64 - Long.numberOfLeadingZeros(Math.abs(((Number) left).longValue()));
            if (bits * Math.abs(((Number) right).doubleValue()) > MAX_POWER_BITS) {
                throw new SecurityException("Script result exceeds the size limit");
            }
        }
        return InvokerHelper.invokeMethod(left, method, new Object[] {right});
    }

    /** Rewrites a + b, a * b, a ** b and a << b into ScriptService.boundedOp(...) calls. */
    private static final class BoundedOperators extends CompilationCustomizer {
        BoundedOperators() {
            super(CompilePhase.CANONICALIZATION);
        }

        @Override
        public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
            new ClassCodeExpressionTransformer() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return source;
                }

                @Override
                public Expression transform(Expression expr) {
                    if (expr instanceof BinaryExpression && !(expr instanceof DeclarationExpression)) {
                        BinaryExpression bin = (BinaryExpression) expr;
                        String method = methodFor(bin.getOperation().getType());
                        if (method != null) {
                            StaticMethodCallExpression call = new StaticMethodCallExpression(
                                    ClassHelper.make(ScriptService.class), "boundedOp",
                                    new ArgumentListExpression(new ConstantExpression(method),
                                            transform(bin.getLeftExpression()),
                                            transform(bin.getRightExpression())));
                            call.setSourcePosition(bin);
                            return call;
                        }
                    }
                    return super.transform(expr);
                }
            }.visitClass(classNode);
        }

        private static String methodFor(int op) {
            switch (op) {
                case Types.PLUS: return "plus";
                case Types.MULTIPLY: return "multiply";
                case Types.POWER: return "power";
                case Types.LEFT_SHIFT: return "leftShift";
                default: return null;
            }
        }
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
