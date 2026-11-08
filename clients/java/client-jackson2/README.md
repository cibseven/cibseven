# CIB seven External Task Client on Jackson 2 (deprecated)

> **Deprecated, removed in CIB seven 3.1.0.** Use `cibseven-external-task-client`, which is based on Jackson 3 and
> writes and reads the same JSON.

`cibseven-external-task-client-jackson2` is the external task client of CIB seven 3.0 with the Jackson 2 code of
CIB seven 2.x, for applications that cannot move to Jackson 3 yet.

* Same packages and classes as `cibseven-external-task-client`. **Never put both on one class path**; the client
  refuses to start then (`TASK/CLIENT-01035`).
* `JacksonJsonDataFormat.getObjectMapper()` returns a mutable Jackson 2 `com.fasterxml.jackson.databind.ObjectMapper`,
  as in CIB seven 2.x.
* `JjwtClientAssertionProvider` uses `jjwt-jackson`.
* The client logs a deprecation warning (`TASK/CLIENT-01036`) once.

How to switch, also with the Spring Boot starter: see [../README.md](../README.md#deprecated-jackson-2-variant).

## Build

Only the classes that use Jackson are in `src/main/java`. All other sources and resources come from `../client` at
build time (copied to `target/generated-sources/client-shared`). The unit tests of `../client` (except `*Jackson3Test`)
run against this variant as well, with the same JSON fixtures.
