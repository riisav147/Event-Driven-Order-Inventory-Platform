#!/bin/sh
set -eu

MAVEN_VERSION=3.9.11
MAVEN_DIR="${MAVEN_USER_HOME:-${HOME}/.m2}/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="${MAVEN_DIR}/apache-maven-${MAVEN_VERSION}"

if [ ! -x "${MAVEN_HOME}/bin/mvn" ]; then
  mkdir -p "${MAVEN_DIR}"
  archive="${MAVEN_DIR}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  curl --fail --location --silent --show-error --connect-timeout 15 --max-time 180 \
    "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    --output "${archive}"
  tar -xzf "${archive}" -C "${MAVEN_DIR}"
fi

exec "${MAVEN_HOME}/bin/mvn" "$@"
