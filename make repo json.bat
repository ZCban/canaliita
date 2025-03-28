:: Genera repo.json dentro la cartella cs3
(
    echo {
    echo     "name": "canali-ita",
    echo     "description": "Cloudstream canali-ita repository",
    echo     "manifestVersion": 1,
    echo     "pluginLists": [
    echo         "https://raw.githubusercontent.com/ZCban/canaliita/builds/plugins.json"
    echo     ]
    echo }
) > "%~dp0\cs3\repo.json"

echo repo.json generato correttamente in: %~dp0\cs3\


