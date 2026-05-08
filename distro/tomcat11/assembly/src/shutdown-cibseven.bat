@echo off

set "CATALINA_HOME=%CD%\server\apache-tomcat-${version.tomcat11}"

cd server\apache-tomcat-${version.tomcat11}\bin\
start shutdown.bat
