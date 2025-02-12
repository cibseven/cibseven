## Profiles

### Default Profile

By default, the project is packaged for Wildfly, assuming jakarta and resteasy are provided.

And it uses the `web.xml` located in `src/main/webapp/WEB-INF/web.xml`.

### Tomcat Profile

When using the `tomcat-rest` profile, the project will replace the default `web.xml` with the one located at `src/main/runtime/tomcat/webapp/WEB-INF/web.xml`.

And the necessary dependencies will be packaged on the `compile` scope (placed in `WEB-INF/lib/` folder).

## How to Build

To build the project using the default profile:

```sh
mvn clean compile
```

## Running in the IDE on Tomcat

To package and run this project in the IDE for Tomcat, one of the following Maven profiles should be used:

```sh
mvn clean compile -Ptomcat-rest,!jboss-rest
```

or the default build profile can be changed using the maven variable:

```sh
mvn clean compile -DrestTomcat=true
```

## Tomcat Configuration

In order to start the Tomcat in IDE locally, following configuration files should be used:
[conf/ in Tomcat distro](../../distro/tomcat/assembly/src/conf/)