@echo off

SET BASEDIR=%~dp0
SET EXECUTABLE=%BASEDIR%internal\run.bat

REM stop CIB seven Run
call "%EXECUTABLE%" stop