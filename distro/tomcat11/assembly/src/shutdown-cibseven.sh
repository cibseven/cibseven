#!/bin/sh

export CATALINA_HOME="$(dirname "$0")/server/apache-tomcat-${version.tomcat11}"

/bin/sh "$(dirname "$0")/server/apache-tomcat-${version.tomcat11}/bin/shutdown.sh"
