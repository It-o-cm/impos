@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  impos - caisse de DEMONSTRATION sous Windows.
rem
rem      caisse.bat          arrete l'instance en cours, demarre, charge le
rem                          socle puis le catalogue 07039
rem      caisse.bat -n       demarre seulement, sans rien importer
rem      caisse.bat -f       demarre EN PREMIER PLAN, sans importer (diagnostic)
rem      caisse.bat -s       arrete la caisse et rend la main
rem
rem  La base est EN MEMOIRE et recreee a chaque demarrage : une demo repart
rem  toujours de l'etat initial, et le socle puis le catalogue sont repousses
rem  par ce script. Rien ne survit a l'arret, il n'y a pas de dossier a vider.
rem ---------------------------------------------------------------------------

set "POS_PORT=8080"
set "BASE=http://localhost:%POS_PORT%"
set "POS_ADMIN_PASSWORD=admin"

rem Profil dev aux DEUX moments : a la construction il decide de ce qui entre
rem dans le jar (seed, simulateur materiel), au demarrage il decide de la
rem configuration (URL du pont materiel, CORS). Le profil demo, lui, n'a pas
rem d'URL materielle declaree : l'injection du HardwareClient y echoue.
set "JAVA_OPTS=-Dquarkus.profile=dev -Dpos.scanner.enabled=false"
rem L'URL du pont materiel est posee EXPLICITEMENT : elle n'existe dans
rem application.properties que sous %dev et %prod, et une configuration
rem explicite l'emporte sur le profil quel qu'il soit.
set "JAVA_OPTS=%JAVA_OPTS% -Dquarkus.rest-client.hardware-api.url=http://localhost:%POS_PORT%"
set "JAVA_OPTS=%JAVA_OPTS% -Dquarkus.datasource.jdbc.url=jdbc:h2:mem:impos;DB_CLOSE_DELAY=-1"
set "JAVA_OPTS=%JAVA_OPTS% -Dquarkus.hibernate-orm.database.generation=drop-and-create"

if /i "%~1"=="-s" (
  call :stopserver
  echo Caisse arretee.
  goto stop
)

echo.
echo Dossier : %CD%
echo.

java -version 2>&1 | findstr /i "version" >nul
if errorlevel 1 (
  echo [ERREUR] Java ne repond pas. Tapez "java -version" pour voir pourquoi.
  goto stop
)
for /f "tokens=*" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
  echo Java    : %%v
  goto javaok
)
:javaok

set "JARNAME="
for %%f in (*-runner.jar) do if not defined JARNAME set "JARNAME=%%~nxf"
if not defined JARNAME for %%f in (impos*.jar) do if not defined JARNAME set "JARNAME=%%~nxf"
if not defined JARNAME for %%f in (*.jar) do if not defined JARNAME set "JARNAME=%%~nxf"
if not defined JARNAME (
  echo [ERREUR] Aucun .jar dans ce dossier.
  dir /b
  goto stop
)
echo Jar     : %JARNAME%

set "FEEDS="
if exist "feeds\" set "FEEDS=feeds"
if not defined FEEDS if exist "demo\feeds\" set "FEEDS=demo\feeds"
if not defined FEEDS (
  echo [ERREUR] Repertoire "feeds" introuvable ici.
  dir /b
  goto stop
)
echo Donnees : %FEEDS%

echo Base    : en memoire ^(recreee a chaque demarrage^)
echo.

call :stopserver

rem Lanceur ecrit a cote : aucun guillemet imbrique, et relancable a la main.
rem POS_BOOTSTRAP_ADMIN_PASSWORD cree le premier administrateur sur une base
rem vide (EmployeeBootstrap), et c'est lui que curl presente plus bas pour les
rem imports. Sans ce mot de passe, la base recreee a chaque demarrage n'a aucun
rem employe et chaque chargement repond 401. Il est pose en VARIABLE et non en
rem -D pour ne pas s'afficher dans la ligne de commande du processus.
> run-impos.cmd echo @echo off
>>run-impos.cmd echo cd /d "%%~dp0"
>>run-impos.cmd echo set "POS_BOOTSTRAP_ADMIN_PASSWORD=%POS_ADMIN_PASSWORD%"
>>run-impos.cmd echo java %JAVA_OPTS% -jar "%JARNAME%"

if /i "%~1"=="-f" (
  echo Demarrage en premier plan. Ctrl+C pour arreter.
  echo.
  call run-impos.cmd
  goto stop
)

echo Demarrage de la caisse...
start "impos" /min cmd /c "run-impos.cmd > impos.log 2>&1"

set /a tries=0
:wait
set "code=000"
for /f %%c in ('curl -s -o nul -w "%%{http_code}" --max-time 3 "%BASE%/lock" 2^>nul') do set "code=%%c"
if "%code%"=="000" (
  set /a tries+=1
  if !tries! geq 30 (
    echo.
    echo [ERREUR] Pas de reponse apres 60s.
    if exist impos.log (
      echo --- 20 dernieres lignes de impos.log ---
      powershell -NoProfile -Command "Get-Content impos.log -Tail 20"
    ) else (
      echo impos.log n'existe pas : java n'a pas demarre.
      echo Relancez "caisse.bat -f" pour voir l'erreur a l'ecran.
    )
    goto stop
  )
  timeout /t 2 /nobreak >nul
  goto wait
)
echo La caisse repond ^(HTTP %code%^) sur %BASE%
echo.

if /i "%~1"=="-n" goto fin

echo --- Socle ---
call :push employees.csv    EMPLOYEES
call :push stores.csv       STORES
call :push store-groups.csv STORE_GROUPS
call :push vat-rates.csv    VAT_RATES
call :push hierarchies.csv  "NOMENCLATURE?scheme=ITM_FR&label=Nomenclature+ITM+France&enseigne=ITM"
echo.

echo --- Catalogue 07039 ---
echo (products-07039.csv fait 7,4 Mo : comptez plusieurs minutes)
call :push egalim-regimes.csv                  EGALIM_REGIMES
call :push products-07039.csv                  PRODUCTS
call :push product-families-07039.csv          FAMILIES
call :push product-category-storages-07039.csv CATEGORY_STORAGES
call :push prices-07039.csv                    PRICES
call :push budget-groups.csv                   BUDGET_GROUPS
call :push offers-07039.csv                    OFFERS

:fin
echo.
echo Caisse  : %BASE%
echo Journal : %CD%\impos.log
echo Arret   : caisse.bat -s

:stop
echo.
pause
endlocal
exit /b 0

rem --- Arrete ce qui ecoute sur le port de la caisse. ------------------------
:stopserver
set "killed="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%POS_PORT%" ^| findstr LISTENING') do (
  taskkill /PID %%p /F >nul 2>&1
  set "killed=1"
)
if defined killed (
  echo Instance precedente arretee.
  timeout /t 2 /nobreak >nul
)
goto :eof

rem --- Pousse un fichier. Un fichier absent est signale et saute. ------------
:push
if not exist "%FEEDS%\%~1" (
  echo %~1 absent, ignore
  goto :eof
)
<nul set /p "=%~1 "
curl -s -w " [HTTP %%{http_code}]" -u "admin:%POS_ADMIN_PASSWORD%" -H "Content-Type: text/plain" --data-binary "@%FEEDS%\%~1" "%BASE%/feeds/import/%~2"
echo.
goto :eof
