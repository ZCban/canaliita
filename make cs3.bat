@echo off
setlocal enabledelayedexpansion

:: Imposta la cartella corrente (dove si trova questo file .bat)
set "SCRIPT_DIR=%~dp0"
set "OUTPUT_DIR=%SCRIPT_DIR%cs3"

echo 🛠️ Avvio build dei plugin Cloudstream...
call gradlew.bat make

if errorlevel 1 (
    echo ❌ Errore durante la compilazione. Interrotto.
    pause
    exit /b
)

:: Crea la cartella di output se non esiste
if not exist "%OUTPUT_DIR%" (
    mkdir "%OUTPUT_DIR%"
)

:: Cerca i file .cs3 e copiali
echo 📦 Copia dei file .cs3 in %OUTPUT_DIR%
for /r %%f in (*.cs3) do (
    echo ➕ Copio: %%~nxf
    copy "%%f" "%OUTPUT_DIR%" >nul
)

echo ✅ Completato! I plugin .cs3 sono in:
echo %OUTPUT_DIR%
pause
