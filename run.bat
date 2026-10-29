@echo off
setlocal
cd /d "%~dp0"
if not exist build mkdir build
javac --add-modules jdk.httpserver -encoding UTF-8 -d build src\main\java\CantinaApp.java
if errorlevel 1 exit /b 1
java --add-modules jdk.httpserver -cp build CantinaApp
