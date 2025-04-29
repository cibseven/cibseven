@echo off

set "CATALINA_HOME=%CD%\server\apache-tomcat-${version.tomcat}"

setlocal EnableDelayedExpansion

:: Define the file path
set "FILE=%CATALINA_HOME%\lib\cibseven-webclient.properties"


:: Check if the file exists
if not exist "%FILE%" (
    :: Create the folder structure if it doesn't exist
    if not exist "%CATALINA_HOME%\lib" (
        mkdir "%CATALINA_HOME%\lib"
    )

    :: Generate a 155-character alphanumeric random string
    set "CHARS=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    set "RANDOM_STRING="

    for /L %%i in (1,1,155) do (
        set /A "IDX=!random! %% 62"
        for %%C in (!IDX!) do set "RANDOM_STRING=!RANDOM_STRING!!CHARS:~%%C,1!"
    )

    :: Write to the file with a newline at the end
    > "%FILE%" echo.authentication.jwtSecret=!RANDOM_STRING!

    echo File "%FILE%" created with random jwtSecret.
) else (
    echo File "%FILE%" already exists. No changes made.
)

endlocal

echo "starting CIB seven ${project.version} on Apache Tomcat ${version.tomcat}"

cd server\apache-tomcat-${version.tomcat}\bin\
start startup.bat

ping -n 5 localhost > NULL
start http://localhost:8080/camunda-welcome/index.html
 
