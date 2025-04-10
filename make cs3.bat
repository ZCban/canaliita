@echo off
setlocal enabledelayedexpansion

:: Imposta la cartella corrente (dove si trova questo file .bat)
set "SCRIPT_DIR=%~dp0"
set "OUTPUT_DIR=%SCRIPT_DIR%cs3"

:: Rimuove la cartella cs3 se esiste già
if exist "%OUTPUT_DIR%" (
    echo ♻️ Rimozione cartella esistente 'cs3'...
    rmdir /s /q "%OUTPUT_DIR%"
)

echo 🛠️ Avvio build dei plugin Cloudstream...
call gradlew.bat clean
call gradlew.bat make --info

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

@echo off
setlocal EnableDelayedExpansion

:: === CONFIG ===
set "REPO_URL=https://github.com/ZCban/canaliita"
set "RAW_URL=https://raw.githubusercontent.com/ZCban/canaliita/builds"
set "LANGUAGE=        "language": "it","
set "AUTHORS=        "ZCban""
set "ICON_DEFAULT=https://www.google.com/s2/favicons?domain=github.com&sz=%%size%%"

:: === DOMAINS PERSONALIZZATI PER ICONE ===
set "DOMAINS_AnimeUnity=https://www.animeunity.so/apple-touch-icon.png"

:: === TV TYPES + DESCRIPTION ===
set "TVTYPES_AnimeUnity=AnimeMovie\", \"Anime\", \"OVA"
set "DESCRIPTION_AnimeUnity=        \"description\": \"Anime from AnimeUnity\","

:: === Directory CS3 ===
if not exist "%~dp0cs3" (
    echo La cartella 'cs3' non esiste nella directory corrente.
    pause
    exit /b
)

cd /d "%~dp0cs3"
echo [ > plugins.json
set "FIRST=1"

for %%F in (*.cs3) do (
    set "NAME=%%~nF"
    set "FILESIZE=%%~zF"
    set "ICONURL=%ICON_DEFAULT%"
    set "TVTYPES=Live"
    set "DESCRIPTION="

    :: Controllo se ha dominio custom per l'icona
    call set "ICONURL=%%DOMAINS_!NAME!%%"
    if "!ICONURL!"=="" set "ICONURL=%ICON_DEFAULT%"

    :: Controllo tipi TV personalizzati
    call set "TVTYPES=%%TVTYPES_!NAME!%%"
    if "!TVTYPES!"=="" set "TVTYPES=Live"

    :: Controllo descrizione personalizzata
    call set "DESCRIPTION=%%DESCRIPTION_!NAME!%%"

    if !FIRST! NEQ 1 echo , >> plugins.json
    set "FIRST=0"

    >> plugins.json echo     {
    >> plugins.json echo         "iconUrl": "!ICONURL!",
    >> plugins.json echo         "apiVersion": 1,
    >> plugins.json echo         "repositoryUrl": "%REPO_URL%",
    >> plugins.json echo         "fileSize": !FILESIZE!,
    >> plugins.json echo         "status": 1,
    >> plugins.json echo %LANGUAGE%
    >> plugins.json echo         "authors": [
    >> plugins.json echo %AUTHORS%
    >> plugins.json echo         ],
    >> plugins.json echo         "tvTypes": [ "!TVTYPES!" ],
    >> plugins.json echo         "version": 1,
    >> plugins.json echo         "internalName": "!NAME!",
    if defined DESCRIPTION echo !DESCRIPTION!>> plugins.json
    >> plugins.json echo         "url": "%RAW_URL%/%%F",
    >> plugins.json echo         "name": "!NAME!"
    >> plugins.json echo     }
)

echo ] >> plugins.json
echo plugins.json creato correttamente in 'cs3'!

echo ✅ Completato! I plugin .cs3 sono in:
echo %OUTPUT_DIR%
pause
