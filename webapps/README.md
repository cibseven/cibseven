# Legacy webapps (tests only)

The legacy web applications (Cockpit, Tasklist, Admin) are no longer part of CIB seven; the
CIB seven webclient replaces them. Their sources were removed, and nothing built from this
repository contains them.

What remains is `assembly`: the webapp unit tests, which exercise the engine through the
webapp REST resources and plugin queries. They run against the classes of the last released
legacy webapp (`cibseven-webapp-jakarta`, version `version.legacy-webapps` in the root POM).
The module is neither installed nor deployed.

The `legacy-webapps` Maven profile adds the released legacy webapps back to the webjar, Run
and the Tomcat/WildFly distros, so that the integration tests driving the engine through
them can still run in CI. A build with this profile never deploys anything.

For Spring Boot 4, the code that runs the legacy webapps inside a Spring Boot application is
in two modules that are never published, `spring-boot-4-starter/starter-webapp-legacy` and
`starter-security-legacy`, together with the tests that need it. The released starters
contain none of it. The Spring Boot QA webapp tests depend on them, and Run 4 adds them
with the `legacy-webapps` profile.

All of this goes away once these tests are ported to the webclient.
