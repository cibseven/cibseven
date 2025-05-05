@echo off

set "JBOSS_HOME=%CD%\server\wildfly-${version.wildfly}"

setlocal EnableDelayedExpansion

:: Define the file path
set "FILE=%JBOSS_HOME%\modules\org\cibseven\config\main\cibseven-webclient.properties"

:: Check if the file exists
if not exist "%FILE%" (

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

echo "starting CIB seven ${project.version} on Wildfly Application Server ${version.wildfly}"

cd server\wildfly-${version.wildfly}\bin\
start standalone.bat

ping -n 5 localhost > NULL
start http://localhost:8080/camunda-welcome/index.html
 
