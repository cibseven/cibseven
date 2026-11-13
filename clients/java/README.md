# Camunda External Task Client (Java)


[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.cibseven.bpm/camunda-external-task-client/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.cibseven.bpm/camunda-external-task-client)

> Are you looking for the Spring Boot External Task Client? This way please: [Spring Boot External Task Client](../../spring-boot-starter/starter-client)

The **Camunda External Task Client (Java)** allows to set up remote Service Tasks for your workflow.

* [Quick Start](https://docs.camunda.org/get-started/quick-start/)
* [Documentation](https://docs.camunda.org/manual/develop/user-guide/ext-client/)
* [Examples](https://github.com/camunda/camunda-bpm-examples/tree/master/clients/java)

## Features
* Complete External Tasks
* Extend the lock duration of External Tasks
* Unlock External Tasks
* Report BPMN errors as well as failures
* Share primitive and object typed process variables with the Workflow Engine


## Configuration options
* The client can be configured with the fluent api of the [ExternalTaskClientBuilder](client/src/main/java/org/cibseven/bpm/client/ExternalTaskClientBuilder.java).
* The topic subscription can be configured with the fluent api of the [TopicSubscriptionBuilder](client/src/main/java/org/cibseven/bpm/client/topic/TopicSubscriptionBuilder.java).

## Prerequisites
* Java (supported version by the used Camunda Platform 7)
* Camunda Platform 7

## Maven coordinates
The following Maven coordinate needs to be added to the projects `pom.xml`:
```xml
<dependency>
  <groupId>org.cibseven.bpm</groupId>
  <artifactId>camunda-external-task-client</artifactId>
  <version>${version}</version>
</dependency>
```

## JSON and Jackson

Since CIB seven 3.0 the client uses Jackson 3 (`tools.jackson`) for object variables (`application/json`) and for
the requests to the engine. The JSON is the same as with the Jackson 2 based client of CIB seven 2.x: same property
order, dates as timestamps, enums by name, the same `objectTypeName`s. Unknown properties are still rejected when an
object variable is read, except in a class with an `@JsonUnwrapped` property, which ignores them as in 2.x;
nesting depth up to 1000 is accepted.

A `DataFormatConfigurator` for `JacksonJsonDataFormat` gets a Jackson 3 mapper. Jackson 3 mappers are immutable, so
replace the mapper instead of changing it:

```java
public class MyJsonConfigurator implements DataFormatConfigurator<JacksonJsonDataFormat> {

  public Class<JacksonJsonDataFormat> getDataFormatClass() {
    return JacksonJsonDataFormat.class;
  }

  public void configure(JacksonJsonDataFormat dataFormat) {
    dataFormat.setObjectMapper(dataFormat.getObjectMapper().rebuild()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build());
  }
}
```

`JjwtClientAssertionProvider` (OAuth2 client assertions) needs `io.jsonwebtoken:jjwt-api`, `jjwt-impl` and a JSON
serializer for jjwt; use `jjwt-gson` (jjwt has no Jackson 3 serializer).

### Deprecated: Jackson 2 variant

Applications that cannot move to Jackson 3 yet can use `cibseven-external-task-client-jackson2` until CIB seven 3.1.0,
where it is removed. It has the same packages and classes, so **never put both artifacts on one class path** (the
client refuses to start). With the Spring Boot starter, exclude the Jackson 3 client:

```xml
<dependency>
  <groupId>org.cibseven.bpm.springboot</groupId>
  <artifactId>cibseven-bpm-spring-boot-starter-external-task-client</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.cibseven.bpm</groupId>
      <artifactId>cibseven-external-task-client</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>org.cibseven.bpm</groupId>
  <artifactId>cibseven-external-task-client-jackson2</artifactId>
</dependency>
```

With the Jackson 2 variant, `JjwtClientAssertionProvider` uses `jjwt-jackson`.

## Contributing

Have a look at our [contribution guide](https://github.com/camunda/camunda-bpm-platform/blob/master/CONTRIBUTING.md) for how to contribute to this repository.


## License
The source files in this repository are made available under the [Apache License Version 2.0](./LICENSE).
