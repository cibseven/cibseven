This is a distribution of

       CIB seven v${project.version}

visit
       http://docs.cibseven.org/

License information can be found in the LICENSE file.
 
CIB seven includes libraries developed by third
parties. For license and attribution notices for these libraries,
please refer to the documentation that accompanies this distribution
(see the LICENSE_BOOK-${project.version} file).

The packaged Apache Tomcat server is licensed under 
the Apache License v2.0 license.

==================

Contents:

  lib/
        This directory contains the java libraries for application 
        development.

  server/
        This directory contains a preconfigured distribution 
        of Apache Tomcat with CIB seven readily 
        installed. 

        run the
            server/apache-tomcat-${version.tomcat}/bin/startup.{bat/sh}
        script to start up the the server.

        After starting the server, you can access the 
        following web applications:

        http://localhost:8080/webapp
        http://localhost:8080/engine-rest

  sql/
        This directory contains the create and upgrade sql script
        for the different databases.
        The engine create script contain the engine and history tables.

        Execute the current upgrade script to make the database compatible
        with the newest CIB seven release.

==================

CIB seven version: ${project.version}
Apache Tomcat Server version: ${version.tomcat}

=================
