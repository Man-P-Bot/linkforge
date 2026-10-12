@REM ---------------------------------------------------------------------------
@REM Maven Wrapper startup script (distributionType=only-script, wrapperVersion=3.3.2)
@REM
@REM Required ENV vars:
@REM   JAVA_HOME - location of a JDK home dir
@REM
@REM Optional ENV vars:
@REM   MAVEN_USER_HOME  - where the wrapper caches Maven distributions (default: %USERPROFILE%\.m2)
@REM   MVNW_VERBOSE     - set to true for verbose output
@REM   MVNW_INSECURE    - set to true to skip TLS certificate checks when downloading
@REM
@REM Distribution is resolved in this order:
@REM   1. MAVEN_HOME, if it already points at a Maven installation
@REM   2. A cached distribution under %MAVEN_USER_HOME%\wrapper\dists
@REM   3. A fresh download from distributionUrl in .mvn\wrapper\maven-wrapper.properties
@REM ---------------------------------------------------------------------------

@echo off
setlocal enableextensions enabledelayedexpansion

if not defined MAVEN_USER_HOME set "MAVEN_USER_HOME=%USERPROFILE%\.m2"

set "WRAPPER_DIR=%MAVEN_USER_HOME%\wrapper\dists"
set "MAVEN_PROJECTBASEDIR=%~dp0"
if "%MAVEN_PROJECTBASEDIR:~-1%"=="\" set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"
set "WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.properties"

if not exist "%WRAPPER_PROPERTIES%" (
  echo Error: Cannot find "%WRAPPER_PROPERTIES%" 1>&2
  exit /b 1
)

if not defined JAVA_HOME (
  echo Error: JAVA_HOME is not set. Point it at a JDK 17 home directory. 1>&2
  exit /b 1
)

set "distributionUrl="
set "distributionType="
for /f "usebackq tokens=1* delims==" %%A in ("%WRAPPER_PROPERTIES%") do (
  if "%%A"=="distributionUrl" set "distributionUrl=%%B"
  if "%%A"=="distributionType" set "distributionType=%%B"
)

if /i "%distributionType%"=="only-script" goto only_script
goto with_jar

:only_script
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" goto run_maven

if exist "%WRAPPER_DIR%" (
  for /d %%D in ("%WRAPPER_DIR%\*") do (
    if exist "%%D\bin\mvn.cmd" set "MAVEN_HOME=%%D"
    for /d %%E in ("%%D\*") do (
      if exist "%%E\bin\mvn.cmd" set "MAVEN_HOME=%%E"
    )
    for /d %%E in ("%%D\*") do (
      for /d %%F in ("%%E\*") do (
        if exist "%%F\bin\mvn.cmd" set "MAVEN_HOME=%%F"
      )
    )
  )
)

if defined MAVEN_HOME goto run_maven

if "%MVNW_VERBOSE%"=="true" echo Verbose: no cached Maven under "%WRAPPER_DIR%", downloading
if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%" 2>nul

set "DIST_FILE="
for %%A in ("%distributionUrl%") do set "DIST_FILE=%%~nA"
set "MAVEN_VERSION=%DIST_FILE:-bin=%"
if not defined MAVEN_VERSION (
  echo Error: cannot determine Maven version from distributionUrl "%distributionUrl%" 1>&2
  exit /b 1
)

set "INSTALL_DIR=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%-bin"
set "ARCHIVE=%INSTALL_DIR%\apache-maven-%MAVEN_VERSION%-bin.zip"
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

echo Downloading Maven %MAVEN_VERSION% from %distributionUrl%
where curl.exe >nul 2>&1
if errorlevel 1 goto download_powershell

set "CURL_OPTS=-fsSL"
if /i "%MVNW_INSECURE%"=="true" set "CURL_OPTS=-fsSL -k"
curl.exe %CURL_OPTS% -o "%ARCHIVE%" "%distributionUrl%"
if errorlevel 1 (
  echo Error: failed to download "%distributionUrl%" 1>&2
  exit /b 1
)
goto extract_archive

:download_powershell
powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%distributionUrl%' -OutFile '%ARCHIVE%'"
if errorlevel 1 (
  echo Error: failed to download "%distributionUrl%" 1>&2
  exit /b 1
)

:extract_archive
powershell -NoProfile -Command "Expand-Archive -LiteralPath '%ARCHIVE%' -DestinationPath '%INSTALL_DIR%' -Force"
if errorlevel 1 (
  echo Error: failed to extract "%ARCHIVE%" 1>&2
  exit /b 1
)
del "%ARCHIVE%" >nul 2>&1

set "MAVEN_HOME=%INSTALL_DIR%\apache-maven-%MAVEN_VERSION%"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo Error: extracted distribution has no bin\mvn.cmd under "%INSTALL_DIR%" 1>&2
  exit /b 1
)
goto run_maven

:with_jar
if not defined MVNW_JAR set "MVNW_JAR=%WRAPPER_DIR%\maven-wrapper-%WRAPPER_VERSION%\maven-wrapper-%WRAPPER_VERSION%.jar"
if not exist "%MVNW_JAR%" (
  echo Error: distributionType is not "only-script" and "%MVNW_JAR%" is missing. 1>&2
  echo        Use distributionType=only-script, or provide the Maven Wrapper jar. 1>&2
  exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -classpath "%MVNW_JAR%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
exit /b %errorlevel%

:run_maven
if "%MVNW_VERBOSE%"=="true" echo Verbose: using Maven at "%MAVEN_HOME%"
call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %errorlevel%
