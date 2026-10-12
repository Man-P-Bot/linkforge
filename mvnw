#!/bin/sh
# ---------------------------------------------------------------------------
# Maven Wrapper startup script (distributionType=only-script, wrapperVersion=3.3.2)
#
# Required ENV vars:
#   JAVA_HOME - location of a JDK home dir
#
# Optional ENV vars:
#   MAVEN_USER_HOME - where the wrapper caches Maven distributions (default: $HOME/.m2)
#   MVNW_VERBOSE    - set to true for verbose output
#   MVNW_INSECURE   - set to true to skip TLS certificate checks when downloading
#
# Distribution is resolved in this order:
#   1. MAVEN_HOME, if it already points at a Maven installation
#   2. A cached distribution under $MAVEN_USER_HOME/wrapper/dists
#   3. A fresh download from distributionUrl in .mvn/wrapper/maven-wrapper.properties
# ---------------------------------------------------------------------------

set -e

die() {
  printf 'Error: %s\n' "$1" >&2
  exit 1
}

if [ -z "$MAVEN_USER_HOME" ]; then
  MAVEN_USER_HOME="${HOME}/.m2"
fi

WRAPPER_DIR="${MAVEN_USER_HOME}/wrapper/dists"
MAVEN_PROJECTBASEDIR=$(cd "$(dirname "$0")" && pwd)
WRAPPER_PROPERTIES="${MAVEN_PROJECTBASEDIR}/.mvn/wrapper/maven-wrapper.properties"

[ -f "$WRAPPER_PROPERTIES" ] || die "cannot find $WRAPPER_PROPERTIES"
[ -n "$JAVA_HOME" ] || die "JAVA_HOME is not set. Point it at a JDK 17 home directory."

distributionUrl=$(sed -n 's/^distributionUrl=//p' "$WRAPPER_PROPERTIES" | tr -d '\r' | head -n 1)
distributionType=$(sed -n 's/^distributionType=//p' "$WRAPPER_PROPERTIES" | tr -d '\r' | head -n 1)
wrapperVersion=$(sed -n 's/^wrapperVersion=//p' "$WRAPPER_PROPERTIES" | tr -d '\r' | head -n 1)

if [ "$distributionType" = "only-script" ]; then
  if [ -n "$MAVEN_HOME" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then
    exec "$MAVEN_HOME/bin/mvn" "$@"
  fi

  MAVEN_HOME=""
  if [ -d "$WRAPPER_DIR" ]; then
    for candidate in $(find "$WRAPPER_DIR" -maxdepth 3 -type f -name mvn -path '*/bin/mvn' 2>/dev/null); do
      MAVEN_HOME=$(dirname "$(dirname "$candidate")")
    done
  fi

  if [ -z "$MAVEN_HOME" ]; then
    [ "$MVNW_VERBOSE" = "true" ] && echo "Verbose: no cached Maven under $WRAPPER_DIR, downloading"
    mkdir -p "$WRAPPER_DIR"

    distFile=$(basename "$distributionUrl")
    distFile=${distFile%.zip}
    mavenVersion=${distFile%-bin}
    [ -n "$mavenVersion" ] || die "cannot determine Maven version from $distributionUrl"

    installDir="${WRAPPER_DIR}/apache-maven-${mavenVersion}-bin"
    archive="${installDir}/apache-maven-${mavenVersion}-bin.zip"
    mkdir -p "$installDir"

    echo "Downloading Maven $mavenVersion from $distributionUrl"
    if command -v curl >/dev/null 2>&1; then
      if [ "$MVNW_INSECURE" = "true" ]; then
        curl -fsSL -k -o "$archive" "$distributionUrl" || die "failed to download $distributionUrl"
      else
        curl -fsSL -o "$archive" "$distributionUrl" || die "failed to download $distributionUrl"
      fi
    elif command -v wget >/dev/null 2>&1; then
      wget -q -O "$archive" "$distributionUrl" || die "failed to download $distributionUrl"
    else
      die "neither curl nor wget is available to download Maven"
    fi

    command -v unzip >/dev/null 2>&1 || die "unzip is required to extract $archive"
    unzip -q -o "$archive" -d "$installDir" || die "failed to extract $archive"
    rm -f "$archive"

    MAVEN_HOME="${installDir}/apache-maven-${mavenVersion}"
    [ -x "$MAVEN_HOME/bin/mvn" ] || die "extracted distribution has no bin/mvn under $installDir"
  fi

  [ "$MVNW_VERBOSE" = "true" ] && echo "Verbose: using Maven at $MAVEN_HOME"
  exec "$MAVEN_HOME/bin/mvn" "$@"
fi

MVNW_JAR="${MVNW_JAR:-${WRAPPER_DIR}/maven-wrapper-${wrapperVersion}/maven-wrapper-${wrapperVersion}.jar"
[ -f "$MVNW_JAR" ] || die "distributionType is not \"only-script\" and $MVNW_JAR is missing"

exec "$JAVA_HOME/bin/java" \
  -classpath "$MVNW_JAR" \
  "-Dmaven.multiModuleProjectDirectory=${MAVEN_PROJECTBASEDIR}" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
