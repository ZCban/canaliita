@echo off
setlocal

:: Percorso SDK Android predefinito
set SDK_PATH=%USERPROFILE%\AppData\Local\Android\Sdk

:: File da creare
set PROPERTIES_FILE=local.properties

:: Controlla se local.properties esiste
if not exist %PROPERTIES_FILE% (
    echo sdk.dir=%SDK_PATH% > %PROPERTIES_FILE%
    echo [INFO] File local.properties creato con sdk.dir=%SDK_PATH%
) else (
    echo [INFO] File local.properties già esistente.
)

:: Pulizia del progetto prima della build
echo [INFO] Pulizia del progetto...
call gradlew.bat clean

:: Avvio della compilazione
echo [INFO] Avvio build con Gradle...
call gradlew.bat build

pause
