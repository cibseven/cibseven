@echo off

REM set constants
SET BASEDIR=%~dp0
SET PARENTDIR=%BASEDIR%..\
SET DEPLOYMENTDIR=%PARENTDIR%configuration/resources
SET WEBAPPS_PATH=%BASEDIR%webapps
SET OAUTH2_PATH=%BASEDIR%oauth2
SET REST_PATH=%BASEDIR%rest
SET EXAMPLE_PATH=%BASEDIR%example
SET APPNAME=CIB seven Run

IF [%~1]==[start] GOTO Startup
IF [%~1]==[stop] GOTO Stop
IF [%~1]==[help] GOTO Help
IF [%~1]==[] GOTO Help

:Startup
REM remove argument
SHIFT

REM setup the JVM
IF "x%JAVA_HOME%" == "x" (
  SET JAVA=java
  ECHO JAVA_HOME is not set. Unexpected results may occur.
  ECHO Set JAVA_HOME to the directory of your local JDK to avoid this message.
) ELSE (
  IF NOT EXIST "%JAVA_HOME%" (
    ECHO JAVA_HOME "%JAVA_HOME%" path doesn't exist
    GOTO :EOF
  ) ELSE (
    IF NOT EXIST "%JAVA_HOME%\bin\java.exe" (
      ECHO "%JAVA_HOME%\bin\java.exe" does not exist
      GOTO :EOF
    )
    ECHO Setting JAVA property to "%JAVA_HOME%\bin\java"
    SET "JAVA=%JAVA_HOME%\bin\java"
    SET "RESTORE_PATH=%PATH%"
    REM add temp the JAVA_HOME so this java is used in the java version check
    SET "PATH=%JAVA_HOME%\bin;%PATH%"
  )
)

SET EXPECTED_JAVA_VERSION=17
FOR /f "tokens=3" %%g IN ('java -version 2^>^&1 ^| findstr /i "version"') DO (
  SET JAVA_VERSION=%%g
)
REM Remove the surrounding quotes
SET JAVA_VERSION=%JAVA_VERSION:"=%
ECHO Java version is %JAVA_VERSION%
FOR /f "delims=. tokens=1" %%v in ("%JAVA_VERSION%") do (
  IF %%v LSS %EXPECTED_JAVA_VERSION% (
    ECHO You must use at least JDK 17 to start CIB seven Run.
    GOTO :EOF
  )
)
REM revert PATH variable to its initial value
SET "PATH=%RESTORE_PATH%"

IF NOT "x%JAVA_OPTS%" == "x" (
  ECHO JAVA_OPTS: %JAVA_OPTS%
)

REM set environment parameters
SET optionalComponentChosen=false
SET restChosen=false
SET productionChosen=false
SET detachProcess=false
SET classPath=%PARENTDIR%configuration\userlib,%PARENTDIR%configuration\keystore
SET configuration=%PARENTDIR%configuration\default.yml
SET webclientProperties=%PARENTDIR%configuration\userlib\cibseven-webclient.properties

REM check and create if needed webclient properties file with jwt secret
setlocal EnableDelayedExpansion
:: Check if the file exists
if not exist "%webclientProperties%" (
    :: Generate a 155-character alphanumeric random string
    set "CHARS=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    set "RANDOM_STRING="

    for /L %%i in (1,1,155) do (
        set /A "IDX=!random! %% 62"
        for %%C in (!IDX!) do set "RANDOM_STRING=!RANDOM_STRING!!CHARS:~%%C,1!"
    )

    :: Write to the file with a newline at the end
    > "%webclientProperties%" echo.cibseven.webclient.authentication.jwtSecret=!RANDOM_STRING!

    echo File "%webclientProperties%" created with random jwtSecret.
) else (
    echo File "%webclientProperties%" already exists. No changes made.
)
endlocal

REM inspect arguments
:Loop
IF [%~1]==[] GOTO Continue

IF [%~1]==[--webapps] (
  SET optionalComponentChosen=true
  SET classPath=%WEBAPPS_PATH%,%classPath%
  ECHO WebApps enabled
)

IF [%~1]==[--oauth2] (
  SET optionalComponentChosen=true
  SET classPath=%OAUTH2_PATH%,%classPath%
  ECHO Spring Security OAuth2 enabled
)

IF [%~1]==[--rest] (
  SET optionalComponentChosen=true
  SET restChosen=true
  SET classPath=%REST_PATH%,%classPath%
  ECHO REST API enabled
)

IF [%~1]==[--example] (
  SET optionalComponentChosen=true
  SET classPath=%EXAMPLE_PATH%,%classPath%
  ECHO Invoice Example included - needs to be enabled in application configuration as well
)

IF [%~1]==[--production] (
  SET productionChosen=true
  SET configuration=%PARENTDIR%configuration\production.yml
)

IF [%~1]==[--detached] (
  SET detachProcess=true
)

IF [%~1]==[--help] (
  GOTO ArgsHelp
)

SHIFT
GOTO Loop
:Continue

REM If no optional component is chosen, enable REST and Webapps.
REM If production mode is not chosen, also enable the example application.
setlocal enabledelayedexpansion
IF [%optionalComponentChosen%]==[false] (
  SET restChosen=true
  ECHO REST API enabled
  ECHO WebApps enabled
  IF [%productionChosen%]==[false] (
    ECHO Invoice Example included - needs to be enabled in application configuration as well
    SET classPath=%EXAMPLE_PATH%,%classPath%
  )
  SET classPath=%WEBAPPS_PATH%,%REST_PATH%,!classPath!
)
setlocal disabledelayedexpansion

ECHO classpath: %classPath%

REM start the application
IF [%detachProcess%]==[true] (
  REM in the background
  start "%APPNAME%" "%JAVA%" -Dloader.path="%classPath%" -Dcamunda.deploymentDir="%DEPLOYMENTDIR%" %JAVA_OPTS% -jar "%BASEDIR%cibseven-bpm-run-core.jar" --spring.config.location=file:"%configuration%"

) ELSE (
  call "%JAVA%" -Dloader.path="%classPath%" -Dcamunda.deploymentDir="%DEPLOYMENTDIR%" %JAVA_OPTS% -jar "%BASEDIR%cibseven-bpm-run-core.jar" --spring.config.location=file:"%configuration%"
)

GOTO End

:Stop
REM remove argument
SHIFT

REM shut down CIB seven Run
ECHO CIB seven Run is shutting down.
TASKKILL /FI "WINDOWTITLE eq %APPNAME%"

GOTO End

:Help
ECHO Usage: run.bat [start^|stop] (options...)
:ArgsHelp
ECHO Options:
ECHO   --webapps    - Enables the CIB seven Webapps
ECHO   --oauth2     - Enables the CIB seven Spring Security OAuth2 integration
ECHO   --rest       - Enables the REST API
ECHO   --example    - Enables the example application
ECHO   --production - Applies the production.yaml configuration file
ECHO   --detached   - Starts CIB seven Run as a detached process

:End
