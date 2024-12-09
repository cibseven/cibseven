# CIB seven - The open source BPMN platform

[![cibseven manual latest](https://img.shields.io/badge/manual-latest-brown.svg)](https://docs.cibseven.org/manual/latest/) [![License](https://img.shields.io/github/license/cibseven/cibseven?color=blue&logo=apache)](https://github.com/cibseven/cibseven/blob/master/LICENSE) [![Discussions](https://img.shields.io/badge/discussions-cibseven-green)](https://github.com/orgs/cibseven/discussions)

CIB seven is a flexible framework for workflow and process automation. Its core is a native BPMN 2.0 process engine that runs inside the Java Virtual Machine. It can be embedded inside any Java application and any Runtime Container. It integrates with Java EE 6 and is a perfect match for the Spring Framework. On top of the process engine, you can choose from a stack of tools for human workflow management, operations and monitoring.

- Web Site: https://cibseven.org
- Getting Started: https://docs.cibseven.org/get-started/
- Discussions: https://github.com/orgs/cibseven/discussions
- Issue Tracker: https://github.com/cibseven/cibseven/issues

## Components

CIB seven provides a rich set of components centered around the BPM lifecycle.

#### Process Implementation and Execution

- Engine - The core component responsible for executing BPMN 2.0 processes.
- REST API - The REST API provides remote access to running processes.
- Spring, CDI Integration - Programming model integration that allows developers to write Java Applications that interact with running processes.

#### Process Design

- Camunda Modeler - A [standalone desktop application](https://github.com/camunda/camunda-modeler) that allows business users and developers to design & configure processes.

#### Process Operations

- Engine - JMX and advanced Runtime Container Integration for process engine monitoring.
- Cockpit - Web application tool for process operations.
- Admin - Web application for managing users, groups, and their access permissions.

#### Human Task Management

- Tasklist - Web application for managing and completing user tasks in the context of processes.

#### And there's more...

- [bpmn.io](https://bpmn.io/) - Toolkits for BPMN, CMMN, and DMN in JavaScript (rendering, modeling)
- [Community Extensions](https://docs.cibseven.org/manual/latest/introduction/extensions/) - Extensions on top of CIB seven provided and maintained by our great open source community

## A Framework

In contrast to other vendor BPM platforms, CIB seven strives to be highly integrable and embeddable. We seek to deliver a great experience to developers that want to use BPM technology in their projects.

### Highly Integrable

Out of the box, CIB seven provides infrastructure-level integration with Java EE Application Servers and Servlet Containers.

### Embeddable

Most of the components that make up the platform can even be completely embedded inside an application. For instance, you can add the process engine and the REST API as a library to your application and assemble your custom BPM platform configuration.

## Contributing

Please see our [contribution guidelines](CONTRIBUTING.md) for how to raise issues and how to contribute code to our project.

## Tests

To run the tests in this repository, please see our [testing tips and tricks](TESTING.md).


## License

The source files in this repository are made available under the [Apache License Version 2.0](./LICENSE).

CIB seven uses and includes third-party dependencies published under various licenses. By downloading and using CIB seven artifacts, you agree to their terms and conditions. Refer to https://docs.cibseven.org/manual/latest/introduction/third-party-libraries/ for an overview of third-party libraries and particularly important third-party licenses we want to make you aware of.
