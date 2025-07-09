# CibSeven Script Engine Integration

This document explains how to use CibSeven's ClassLoader with Groovy and other script engines without compile-time dependencies.

## Overview

CibSeven provides a special ClassLoader (`CibSevenClassLoader`) that automatically translates Camunda namespace classes (`org.camunda.*`) to CibSeven namespace classes (`org.cibseven.*`). This allows scripts to continue using Camunda API references while running against CibSeven engine.

## Using CibSevenClassLoader with Groovy

### Option 1: Using the Utility Class (Recommended)

The easiest way to create a Groovy script engine with CibSeven support:

```java
import org.cibseven.bpm.engine.impl.scripting.util.CibSevenScriptEngineUtil;
import javax.script.ScriptEngine;

// Create a Groovy script engine with CibSeven namespace support
ScriptEngine groovyEngine = CibSevenScriptEngineUtil.createGroovyScriptEngine();

if (groovyEngine != null) {
    // Use the script engine - it will automatically handle namespace translation
    Object result = groovyEngine.eval("execution.setVariable('test', 'value')");
}
```

### Option 2: Manual Integration

If you need more control, you can create the script engine manually:

```java
import org.cibseven.bpm.engine.impl.scripting.util.CibSevenScriptEngineUtil;
import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.jsr223.GroovyScriptEngineImpl;

// Get the CibSeven ClassLoader
ClassLoader cibSevenClassLoader = CibSevenScriptEngineUtil.getCibSevenClassLoader();

// Create GroovyClassLoader with CibSeven support
GroovyClassLoader groovyClassLoader = new GroovyClassLoader(cibSevenClassLoader);

// Create the script engine
ScriptEngine groovyEngine = new GroovyScriptEngineImpl(groovyClassLoader);
```

### Option 3: Using CamundaScriptEngineManager

For getting any script engine with CibSeven support:

```java
import org.cibseven.bpm.engine.impl.scripting.util.CibSevenScriptEngineUtil;
import javax.script.ScriptEngineManager;

// Create a ScriptEngineManager with CibSeven support
ScriptEngineManager manager = CibSevenScriptEngineUtil.createCibSevenScriptEngineManager();

// Get any script engine - it will have CibSeven namespace support when available
ScriptEngine groovyEngine = manager.getEngineByName("groovy");
ScriptEngine jsEngine = manager.getEngineByName("graal.js");
```

## Configuration

To enable CibSeven namespace translation, ensure the following configuration is set:

```java
ProcessEngineConfiguration config = ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration();
config.setUseCibSevenNamespaceInScripting(true);
```

Or in XML configuration:
```xml
<property name="useCibSevenNamespaceInScripting">true</property>
```

## Dependencies

### Runtime Dependencies
- For Groovy support: `org.apache.groovy:groovy-jsr223` (runtime scope)
- For JavaScript support: `org.graalvm.js:js` and `org.graalvm.js:js-scriptengine` (runtime scope)

### No Compile-Time Dependencies Required
The CibSeven engine uses reflection to create script engines, so you don't need to add script engine dependencies to your compile classpath.

## Example: Process Application with Custom Script Engine

```java
import org.cibseven.bpm.application.impl.ServletProcessApplication;
import org.cibseven.bpm.engine.impl.scripting.util.CibSevenScriptEngineUtil;
import javax.script.ScriptEngine;

public class MyProcessApplication extends ServletProcessApplication {
    
    @Override
    public ScriptEngine getScriptEngineForName(String scriptEngineName, boolean cache) {
        if ("groovy".equalsIgnoreCase(scriptEngineName)) {
            // Use CibSeven utility to create Groovy engine with namespace support
            ScriptEngine engine = CibSevenScriptEngineUtil.createGroovyScriptEngine(
                getProcessApplicationClassloader()
            );
            if (engine != null) {
                return engine;
            }
        }
        
        // Fall back to default behavior
        return super.getScriptEngineForName(scriptEngineName, cache);
    }
}
```

## Benefits

1. **No Compile-Time Dependencies**: Script engine libraries are only needed at runtime
2. **Automatic Namespace Translation**: Scripts can use Camunda API references that get translated to CibSeven
3. **Flexible Integration**: Multiple ways to integrate based on your needs
4. **Graceful Degradation**: Falls back to standard script engines if CibSeven-specific features aren't available

## Troubleshooting

### Script Engine Not Found
If `CibSevenScriptEngineUtil.createGroovyScriptEngine()` returns `null`, ensure that:
1. `org.apache.groovy:groovy-jsr223` is available on the runtime classpath
2. The Groovy classes can be loaded by the current ClassLoader

### Namespace Translation Not Working
Ensure that:
1. `useCibSevenNamespaceInScripting` is set to `true` in your process engine configuration
2. You're using one of the CibSeven utility methods or the `CamundaScriptEngineManager`
