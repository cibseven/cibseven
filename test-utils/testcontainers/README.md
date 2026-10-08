# Usage of the Testcontainers wrapper

1. Add a `testcontainers.properties` file to the root of your test resources directory ([example file](./testing/src/test/resources/testcontainers.properties));
1. Add the repository names of the Docker images you would like to use. The following custom properties are available:
   * `postgresql.container.image`
   * `mariadb.container.image`
   * `mysql.container.image`
   * `mssql.container.image`
   * `db2.container.image`
   * `oracle.container.image` (Oracle XE), `oraclefree.container.image` (Oracle Free / 26ai), `oracle19.container.image` (Oracle 19c EE, requires a login to container-registry.oracle.com)
   * The defaults pull via the proxy projects of harbor.cib.de (`docker login harbor.cib.de`); without a Harbor account override them with the public images noted in [testcontainers.properties](./src/main/resources/testcontainers.properties), e.g. `TESTCONTAINERS_POSTGRESQL_CONTAINER_IMAGE=postgres`
1. If using MS-SQL, add a `container-license-acceptance.txt` file to the root of your test resources directory [example file](./testing/src/test/resources/container-license-acceptance.txt). 
   * Add the repository names of the MS-SQL Docker images your are planning to use.
1. Modify your JDBC url to contain the `tc:cib[DB_NAME]:[DB_VERSION]` segment. E.g. `jdbc:tc:cibpostgresql:17:///process-engine` 
   More details [here](https://www.testcontainers.org/modules/databases/jdbc/).