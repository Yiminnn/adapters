package com.example.service;

import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.Map;

/**
 * Dynamic metric calculation service.
 * Formulas run in a sandboxed Nashorn engine: Java interop is disabled
 * ("--no-java") and a deny-all ClassFilter blocks every host class lookup,
 * so formulas can only perform pure JavaScript computation.
 */
public class DynamicMetricService {

    private static final NashornScriptEngineFactory FACTORY = new NashornScriptEngineFactory();

    public Object calculate(String formula, Map<String, Object> context) throws ScriptException {
        if (formula == null) {
            throw new ScriptException("formula must not be null");
        }
        ScriptEngine engine = FACTORY.getScriptEngine(
                new String[]{"--no-java", "--no-syntax-extensions"},
                DynamicMetricService.class.getClassLoader(),
                className -> false);
        Bindings bindings = engine.createBindings();
        if (context != null) {
            bindings.putAll(context);
        }
        engine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        return engine.eval(formula);
    }
}
