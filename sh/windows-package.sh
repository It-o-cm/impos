#!/usr/bin/env bash
# windows-package.sh — builds impos (register) and imedge (store node) as two
# self-contained uber-jars and assembles a folder ready to copy to a Windows
# machine, zipped next to it:
#
#   target/impos-imedge-windows/
#     impos.jar               the register, port 8080
#     imedge.jar              the store node + back office, port 8070
#     feeds/                  the demo/feeds CSV files (small set and 07039)
#     simulateur.html         the hardware simulator pages (static, they talk
#     simulateur-07039.html   to the register on 8080)
#     demarrer.bat            starts the node, then the register linked to it,
#                             and feeds the stack THROUGH THE NODE
#   target/impos-imedge-windows.zip
#
# Demo build, never production: both jars are built with the DEV build profile
# (the hardware simulator MockHardwareResource and the DataInitializer seed are
# @IfBuildProfile and only enter a dev-built jar), they also run with the dev
# profile, H2 in memory recreated at every start (drop-and-create).
#
# The valuation engine is not part of the package: the register runs with an
# empty engine URL, i.e. LOCAL valuation (catalogue prices, no promotions).
#
# Usage (from anywhere):
#   ./sh/windows-package.sh
# Layout assumption (override by env): imedge is a sibling of impos —
#   IMEDGE_DIR (default: <impos>/../imedge)
#   MVN        (default: ./mvnw, the project's Maven wrapper)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IMPOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
IMEDGE_DIR="${IMEDGE_DIR:-$IMPOS_DIR/../imedge}"
if [ ! -f "$IMEDGE_DIR/pom.xml" ]; then
  echo "✗ Racine imedge introuvable ($IMEDGE_DIR sans pom.xml) — exporter IMEDGE_DIR."; exit 1
fi
IMEDGE_DIR="$(cd "$IMEDGE_DIR" && pwd)"
MVN="${MVN:-./mvnw}"
NAME="impos-imedge-windows"
DIST="$IMPOS_DIR/target/$NAME"
ZIP="$IMPOS_DIR/target/$NAME.zip"

# Builds one project as a dev-profile uber-jar and prints the jar's path.
# Stale runner jars are removed first so the newest build is the only match.
build_jar() { # project_dir label
  local dir="$1" label="$2" jar
  echo "· Construction de $label (profil dev, uber-jar, sans tests)…" >&2
  rm -f "$dir"/target/*-runner.jar
  if ! ( cd "$dir" && $MVN -q -DskipTests -Dquarkus.profile=dev \
      -Dquarkus.package.jar.type=uber-jar package ) >&2; then
    echo "✗ Échec de la construction de $label" >&2; exit 1
  fi
  jar="$(ls "$dir"/target/*-runner.jar 2>/dev/null | head -1)"
  if [ -z "$jar" ]; then
    echo "✗ Aucun *-runner.jar produit dans $dir/target" >&2; exit 1
  fi
  echo "  · $(basename "$jar")" >&2
  echo "$jar"
}

IMPOS_JAR="$(build_jar "$IMPOS_DIR" impos)"
IMEDGE_JAR="$(build_jar "$IMEDGE_DIR" imedge)"

echo "· Assemblage de ${DIST}…"
rm -rf "$DIST" "$ZIP"
mkdir -p "$DIST/feeds"
cp "$IMPOS_JAR" "$DIST/impos.jar"
cp "$IMEDGE_JAR" "$DIST/imedge.jar"
cp "$IMPOS_DIR"/demo/feeds/*.csv "$DIST/feeds/"
for page in simulateur.html simulateur-07039.html; do
  if [ -f "$IMPOS_DIR/docs/$page" ]; then cp "$IMPOS_DIR/docs/$page" "$DIST/"; fi
done

# The launcher is written with CRLF line endings: cmd.exe misreads labels and
# multi-line blocks of an LF-only batch file.
cat > "$DIST/demarrer.bat" <<'BAT'
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  impos + imedge - pile de DEMONSTRATION sous Windows.
rem
rem      demarrer.bat        arrete la pile en cours, demarre le noeud magasin
rem                          (8070) puis la caisse (8080) reliee au noeud, et
rem                          injecte le socle et le catalogue 07039 AU NOEUD
rem      demarrer.bat -p     idem avec le petit jeu de demonstration
rem      demarrer.bat -n     demarre seulement, sans rien injecter
rem      demarrer.bat -s     arrete la pile et rend la main
rem
rem  Les bases sont EN MEMOIRE et recreees a chaque demarrage : rien ne survit
rem  a l'arret. La caisse tire son referentiel du noeud ; elle valorise en
rem  local (prix du catalogue, sans promotions) : le moteur n'est pas fourni.
rem ---------------------------------------------------------------------------

set "EDGE_PORT=8070"
set "POS_PORT=8080"
set "EDGE=http://localhost:%EDGE_PORT%"
set "POS=http://localhost:%POS_PORT%"
set "ADMIN_PASSWORD=admin"
set "SUFFIX=-07039"
set "IMPORT=1"

:args
if "%~1"=="" goto argsdone
if /i "%~1"=="-s" (
  call :stopall
  echo Pile arretee.
  goto stop
)
if /i "%~1"=="-p" (
  set "SUFFIX="
  shift
  goto args
)
if /i "%~1"=="-n" (
  set "IMPORT=0"
  shift
  goto args
)
echo [ERREUR] Option inconnue : %~1
echo Usage : demarrer.bat [-p] [-n] ^| demarrer.bat -s
goto stop
:argsdone

rem The 07039 catalogue (109 204 articles) needs long transactions on the node
rem and a slow pull on the register; the small set runs with fast cycles.
if defined SUFFIX (
  set "CATALOGUE=07039"
  set "NODE_TX=900"
  set "PULL_SECONDS=60"
  set "PULL_TIMEOUT=300"
) else (
  set "CATALOGUE=jeu de demonstration"
  set "NODE_TX=60"
  set "PULL_SECONDS=5"
  set "PULL_TIMEOUT=30"
)

where java >nul 2>&1
if errorlevel 1 (
  echo [ERREUR] Java n'est pas dans le PATH.
  goto stop
)
where curl >nul 2>&1
if errorlevel 1 (
  echo [ERREUR] curl.exe est absent ^(Windows 10 1803 ou plus recent^).
  goto stop
)
if not exist "imedge.jar" (
  echo [ERREUR] imedge.jar introuvable dans %CD%
  goto stop
)
if not exist "impos.jar" (
  echo [ERREUR] impos.jar introuvable dans %CD%
  goto stop
)

call :stopall

rem One launcher per application, written next to the jars: no nested quotes,
rem and each can be rerun by hand. The bootstrap password goes in a variable,
rem not in a -D option, so it does not show in the process command line.
> run-imedge.cmd echo @echo off
>>run-imedge.cmd echo cd /d "%%~dp0"
>>run-imedge.cmd echo set "POS_BOOTSTRAP_ADMIN_PASSWORD=%ADMIN_PASSWORD%"
>>run-imedge.cmd echo java -Dquarkus.profile=dev -Dquarkus.http.port=%EDGE_PORT% -Dquarkus.transaction-manager.default-transaction-timeout=%NODE_TX% -Dpos.valuation.url= -jar imedge.jar

> run-impos.cmd echo @echo off
>>run-impos.cmd echo cd /d "%%~dp0"
>>run-impos.cmd echo set "POS_BOOTSTRAP_ADMIN_PASSWORD=%ADMIN_PASSWORD%"
>>run-impos.cmd echo java -Dquarkus.profile=dev -Dquarkus.http.port=%POS_PORT% -Dpos.scanner.enabled=false -Dquarkus.rest-client.hardware-api.url=%POS% -Dpos.sync.store-url=%EDGE% -Dpos.referential.pull-seconds=%PULL_SECONDS% -Dpos.referential.request-timeout-seconds=%PULL_TIMEOUT% -Dquarkus.transaction-manager.default-transaction-timeout=%NODE_TX% -Dpos.valuation.url= -jar impos.jar

echo Demarrage du noeud magasin...
start "imedge" /min cmd /c "run-imedge.cmd > imedge.log 2>&1"
call :waitfor "%EDGE%/" imedge.log
if errorlevel 1 goto stop

echo Demarrage de la caisse...
start "impos" /min cmd /c "run-impos.cmd > impos.log 2>&1"
call :waitfor "%POS%/lock" impos.log
if errorlevel 1 goto stop

if "%IMPORT%"=="0" goto fin

echo.
echo --- Injection au noeud magasin (catalogue %CATALOGUE%) ---
if defined SUFFIX echo ^(products-07039.csv fait 7,4 Mo : comptez plusieurs minutes^)
call :push stores.csv                               STORES
call :push store-groups.csv                         STORE_GROUPS
call :push egalim-regimes.csv                       EGALIM_REGIMES
call :push products%SUFFIX%.csv                     PRODUCTS
call :push product-families%SUFFIX%.csv             FAMILIES
call :push product-category-storages%SUFFIX%.csv    CATEGORY_STORAGES
call :push vat-rates.csv                            VAT_RATES
call :push prices%SUFFIX%.csv                       PRICES
call :push offers%SUFFIX%.csv                       OFFERS
call :push hierarchies.csv                          "NOMENCLATURE?scheme=ITM_FR&label=Nomenclature+ITM+France&enseigne=ITM"
echo.
echo La caisse tire le referentiel du noeud toutes les %PULL_SECONDS% s.

:fin
echo.
echo Caisse        : %POS%/
echo Ecran client  : %POS%/customer
echo Simulateur    : %CD%\simulateur.html  ^(ouvrir dans le navigateur^)
echo Noeud magasin : %EDGE%/
echo Back-office   : %EDGE%/admin  ^(admin / %ADMIN_PASSWORD%^)
echo Dashboard     : %EDGE%/dashboard
echo Journaux      : %CD%\imedge.log, %CD%\impos.log
echo Arret         : demarrer.bat -s
start "" "%POS%/"

:stop
echo.
pause
endlocal
exit /b 0

rem --- Stops whatever listens on the node and register ports. ---------------
:stopall
set "killed="
for %%q in (%EDGE_PORT% %POS_PORT%) do (
  for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%%q " ^| findstr LISTENING') do (
    taskkill /PID %%p /F >nul 2>&1
    set "killed=1"
  )
)
if defined killed (
  echo Instance precedente arretee.
  timeout /t 2 /nobreak >nul
)
goto :eof

rem --- Waits up to 180 s for an URL to answer; shows the log tail if not. ---
:waitfor
set /a tries=0
:waitloop
set "code=000"
for /f %%c in ('curl -s -o nul -w "%%{http_code}" --max-time 3 "%~1" 2^>nul') do set "code=%%c"
if not "%code%"=="000" (
  echo   repond ^(HTTP %code%^) sur %~1
  exit /b 0
)
set /a tries+=1
if !tries! geq 90 (
  echo [ERREUR] Pas de reponse de %~1 apres 180 s.
  if exist "%~2" (
    echo --- 20 dernieres lignes de %~2 ---
    powershell -NoProfile -Command "Get-Content '%~2' -Tail 20"
  )
  exit /b 1
)
timeout /t 2 /nobreak >nul
goto waitloop

rem --- Pushes one feed file to the node. A missing file is reported. --------
:push
if not exist "feeds\%~1" (
  echo %~1 absent, ignore
  goto :eof
)
<nul set /p "=%~1 "
curl -s -o nul -w "[HTTP %%{http_code}]" -u "admin:%ADMIN_PASSWORD%" -H "Content-Type: text/plain; charset=utf-8" --data-binary "@feeds\%~1" "%EDGE%/feeds/import/%~2"
echo.
goto :eof
BAT
perl -pi -e 's/\r?\n/\r\n/' "$DIST/demarrer.bat"

echo "· Archive ${ZIP}…"
( cd "$IMPOS_DIR/target" && zip -qr "$NAME.zip" "$NAME" )

cat <<DONE

════════════════════════════════════════════════════════
  PAQUET WINDOWS PRÊT
    Dossier  $DIST
    Archive  $ZIP
  Sur le poste Windows (Java 21 dans le PATH) : dézipper, puis
    demarrer.bat        noeud + caisse, catalogue 07039 injecté au noeud
    demarrer.bat -p     petit jeu de démonstration
    demarrer.bat -s     arrêt
════════════════════════════════════════════════════════
DONE
