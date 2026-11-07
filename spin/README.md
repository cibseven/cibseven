cibseven-spin
============

<p>
  <a href="https://cibseven.org/">Home</a> |
  <a href="https://docs.cibseven.org/manual/1.1/reference/spin/">Documentation</a> |
  <a href="https://github.com/orgs/cibseven/discussions">Forum</a> |
  <a href="https://github.com/cibseven/cibseven/issues">Issues</a> |
  <a href="../LICENSE">License</a> |
  <a href="../CONTRIBUTING.md">Contribute</a>
</p>

Library for simple XML and JSON processing on the JVM (Java Virtual Machine), targeting Java and
JVM-based scripting languages such as Groovy, JRuby, Jython, Javascript and Java Expression
Language.

# Why Spin?

Spin is useful when you need to work with complex, text-based data formats (such as XML or JSON)
without mapping to Java Objects.

Spin provides a comprehensible fluent API for working with different data formats through 
lightweight wrapper objects:

Given the following XML document as `String` or `InputStream`:

```xml
<?xml version=“1.0“ encoding=“UTF-8“ ?>
<customers>
  <customer id="0001"><name>Jonny P</name></customer>
  <customer id="0002"><name>Bobby G</name></customer>
  <customer id="0003"><name>Mary T</name></customer>
</customers>
```

It can directly be worked on with Spin:

```java
import static org.cibseven.spin.Spin.*;

// get id of first customer
XML( xmlInput ).childElements("customer")
  .get(0)
  .attr("id")
  .value();

// create new customer
SpinXmlTreeElement  kate = XML( "<customer />" )
  .attr("id", "0004")
  .append( XML("<name>Kate S</name>") );

// append to existing list
XML( xmlInput ).append(kate);

// query by Id:
XML( xmlInput ).xPath("/customers/customer[@id='0002']").element();
```


# JSON: Jackson 3 and the deprecated Jackson 2 variant

Since CIB seven 3.0.0 the JSON data format is built on Jackson 3 (`tools.jackson`):

| Artifact | Jackson | Status |
|---|---|---|
| `cibseven-spin-dataformat-json-jackson` | 3 | current |
| `cibseven-spin-dataformat-all` (shaded, Jackson relocated to `spinjar.*`) | 3 | current |
| `cibseven-spin-dataformat-json-jackson2` | 2 | deprecated, removed in 3.1.0 |
| `cibseven-spin-dataformat-all-jackson2` (shaded) | 2 | deprecated, removed in 3.1.0 |

The `-jackson2` artifacts keep the Jackson 2 implementation of CIB seven 2.x with the same packages and
class names, as an escape hatch for applications that cannot move to Jackson 3 yet. Never put a
`-jackson2` artifact and its unsuffixed counterpart on one classpath. The class
`org.cibseven.spin.impl.json.jackson.Jackson2Variant` exists only in the Jackson 2 variant.

Jackson 3 mappers are immutable: a `DataFormatConfigurator` replaces the mapper instead of changing it,
for example `format.setObjectMapper(format.getObjectMapper().rebuild().addModule(module).build())`.
The default mapper is built with `JsonMapper.builderWithJackson2Defaults()`, so JSON written by
CIB seven 2.x is read and written unchanged.


# FAQ

## Why should I use Spin instead of the Java DOM API?

Java provides a built-in Dom API along with a Parser. Spin provides a lightweight Wrapper around 
the Dom API which makes it easier to work with than the Dom API. You can access the underlying Dom object 
at any time by unwrapping it: 

```java
Element e = XML( xmlSource ).childElement("customer").unwrap();
```


# Contributing

Have a look at our [contribution guide](https://github.com/cibseven/cibseven/blob/main/CONTRIBUTING.md) for how to contribute to this repository.


# License:

The source files in this repository are made available under the [Apache License Version 2.0](../LICENSE).
