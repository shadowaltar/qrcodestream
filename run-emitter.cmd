@echo off
setlocal

set "JAVA_HOME=C:\Programs\.jdks\azul-17.0.15"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "ROOT=%~dp0"
set "EMITTER=%ROOT%emitter\build\install\emitter\bin\emitter.bat"

if not exist "%EMITTER%" (
    echo Emitter not built yet ^(missing %EMITTER%^). Building...
    call "%ROOT%gradlew.bat" :emitter:installDist
    if errorlevel 1 (
        echo Build failed.
        exit /b 1
    )
)

call "%EMITTER%" %*
endlocal
