#!/bin/sh
# Maven Wrapper bootstrap script
# Downloads Maven if needed, then runs it.

set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="$(command -v java || true)"
fi
if [ ! -x "$JAVACMD" ]; then
    echo "Error: JAVA_HOME not set and java not found on PATH" >&2
    exit 1
fi

if [ -f "$WRAPPER_PROPERTIES" ]; then
    DIST_URL=$(grep 'distributionUrl' "$WRAPPER_PROPERTIES" | cut -d'=' -f2-)
fi

MAVEN_HOME="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-dist"

if [ ! -f "$MAVEN_HOME/bin/mvn" ]; then
    echo "Downloading Maven..."
    mkdir -p "$MAVEN_HOME"
    TMPFILE=$(mktemp)
    if command -v curl > /dev/null 2>&1; then
        curl -fsSL "$DIST_URL" -o "$TMPFILE"
    elif command -v wget > /dev/null 2>&1; then
        wget -q "$DIST_URL" -O "$TMPFILE"
    else
        echo "Error: curl or wget required" >&2
        exit 1
    fi
    TMPDIR=$(mktemp -d)
    unzip -q "$TMPFILE" -d "$TMPDIR"
    mv "$TMPDIR"/apache-maven-*/* "$MAVEN_HOME/"
    rm -rf "$TMPFILE" "$TMPDIR"
    echo "Maven downloaded to $MAVEN_HOME"
fi

export JAVA_HOME
exec "$MAVEN_HOME/bin/mvn" "$@"
