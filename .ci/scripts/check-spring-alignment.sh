#!/usr/bin/env bash
#
# Verifies that the Spring Framework version pinned in parent/pom.xml matches the version
# that the pinned Spring Boot BOM declares.
#
# Complements the maven-enforcer bannedDependencies rule in parent/pom.xml: that rule checks
# the *resolved* Spring version per module, which it cannot do where a spring-framework-bom
# import outranks Spring Boot's BOM (spring-boot-4-starter pins the framework ahead of Boot,
# so the resolved version is tautologically correct there). This script compares the
# *declared* values instead, so it catches drift the enforcer rule is blind to.
#
# Design notes - every Maven invocation costs ~30s in the CI container, so the script is built
# to need exactly one:
#
#   * Our properties are read from help:effective-pom, not from the pom on disk. One invocation
#     yields the fully resolved model, so commented-out old value left above the live one
#     is simply not there to be matched.
#   * The Boot BOM is read textually from the pom in the local repository.
#   * The BOM is only fetched when it is not already in the local repository, and the
#     repository path is taken from the environment Jenkins already set up.
#
# Usage:  bash .ci/scripts/check-spring-alignment.sh [path/to/parent/pom.xml]
# Exit:   0 = aligned, 1 = mismatch, 2 = check could not run

set -uo pipefail

POM="${1:-parent/pom.xml}"

HELP_PLUGIN='org.apache.maven.plugins:maven-help-plugin:3.5.1'
DEPENDENCY_PLUGIN='org.apache.maven.plugins:maven-dependency-plugin:3.8.1'

if [ ! -f "$POM" ]; then
  echo "ERROR: '$POM' not found - run from the repository root" >&2
  exit 2
fi

# prop <element-name> <xml-file> -> first textual match
prop() {
  sed -n "s|.*<$1>\([^<]*\)</$1>.*|\1|p" "$2" | head -n 1
}

# Locate the local repository without spending a Maven invocation where possible.
# Note we deliberately do not guess ${HOME}/.m2/repository: a settings.xml that redirects
# localRepository elsewhere would send us looking in the wrong tree, and a wrong-but-existing
# path is harder to diagnose than one extra Maven call.
resolve_local_repo() {
  local from_opts evaluated

  # 1. -Dmaven.repo.local in MAVEN_OPTS is what mvn will actually honour
  from_opts="$(printf '%s' "${MAVEN_OPTS:-}" \
               | sed -n 's|.*-Dmaven\.repo\.local=\([^[:space:]]*\).*|\1|p')"
  if [ -n "$from_opts" ] && [ -d "$from_opts" ]; then
    echo "          [repo:1/MAVEN_OPTS] $from_opts" >&2
    printf '%s\n' "$from_opts"
    return 0
  fi

  # 2. exported by Jenkinsfile.cib; always set and always points to the correct repo:
  #    - if USE_PRIVATE_REPO=false: points to the standard ~/.m2/repository
  #    - if USE_PRIVATE_REPO=true:  points to ~/.m2/repository/cibseven-install-dir/job-name
  #    Either way, it's the repo Maven will actually use for this build
  if [ -n "${MAVEN_LOCAL_REPO:-}" ] && [ -d "${MAVEN_LOCAL_REPO}" ]; then
    echo "          [repo:2/MAVEN_LOCAL_REPO] $MAVEN_LOCAL_REPO" >&2
    printf '%s\n' "${MAVEN_LOCAL_REPO}"
    return 0
  fi

  # 3. last resort (local developer runs): ask Maven, at the cost of one JVM start
  evaluated="$(mvn -B -q -f "$POM" "${HELP_PLUGIN}:evaluate" \
                 -Dexpression=settings.localRepository -DforceStdout 2>/dev/null | tail -n 1)"
  if [ -n "$evaluated" ] && [ -d "$evaluated" ]; then
    echo "          [repo:3/help:evaluate] $evaluated" >&2
    printf '%s\n' "$evaluated"
    return 0
  fi

  return 1
}

LOCAL_REPO="$(resolve_local_repo)" || LOCAL_REPO=''
if [ -z "$LOCAL_REPO" ]; then
  echo "ERROR: could not determine the local Maven repository" >&2
  exit 2
fi
echo "Local Maven repository: $LOCAL_REPO"

EFFECTIVE_POM="$(mktemp)" || {
  echo "ERROR: could not create a temporary file" >&2
  exit 2
}
trap 'rm -f "$EFFECTIVE_POM"' EXIT

if ! mvn -B -q -f "$POM" "${HELP_PLUGIN}:effective-pom" \
       -Doutput="$EFFECTIVE_POM" >/dev/null 2>&1 || [ ! -s "$EFFECTIVE_POM" ]; then
  echo "ERROR: could not generate the effective pom for $POM" >&2
  exit 2
fi

rc=0

# check_pair <spring-boot property> <spring-framework property>
check_pair() {
  local boot_prop="$1" fw_prop="$2" boot fw bom_pom declared

  boot="$(prop "$boot_prop" "$EFFECTIVE_POM")"
  fw="$(prop "$fw_prop" "$EFFECTIVE_POM")"
  if [ -z "$boot" ] || [ -z "$fw" ]; then
    echo "ERROR: '$boot_prop' / '$fw_prop' not found in the effective pom of $POM" >&2
    return 2
  fi

  bom_pom="${LOCAL_REPO}/org/springframework/boot/spring-boot-dependencies/${boot}/spring-boot-dependencies-${boot}.pom"

  # Only pay for a fetch on a cold repository
  if [ ! -f "$bom_pom" ]; then
    echo "          fetching spring-boot-dependencies:${boot}:pom (not in the local repository)"
    mvn -B -q "${DEPENDENCY_PLUGIN}:get" \
        -Dartifact="org.springframework.boot:spring-boot-dependencies:${boot}:pom" >/dev/null 2>&1
  fi
  if [ ! -f "$bom_pom" ]; then
    echo "ERROR: could not resolve spring-boot-dependencies:${boot}:pom" >&2
    return 2
  fi

  declared="$(prop 'spring-framework.version' "$bom_pom")"
  if [ -z "$declared" ]; then
    echo "ERROR: spring-boot-dependencies:${boot} declares no spring-framework.version" >&2
    return 2
  fi

  if [ "$declared" = "$fw" ]; then
    printf 'OK        spring-boot %-8s declares spring-framework %-8s ; %s = %s\n' \
           "$boot" "$declared" "$fw_prop" "$fw"
    return 0
  fi

  printf 'MISMATCH  spring-boot %-8s declares spring-framework %-8s ; %s = %s\n' \
         "$boot" "$declared" "$fw_prop" "$fw"
  return 1
}

check_pair version.spring-boot  version.spring.framework6 || rc=$?
# save the result of the second check as s. If it is worse than the current result, keep the worse result.
check_pair version.spring-boot4 version.spring.framework7 || { s=$?; [ "$s" -gt "$rc" ] && rc=$s; }

if [ "$rc" -eq 0 ]; then
  echo "All Spring Framework pins match their Spring Boot BOM."
elif [ "$rc" -eq 1 ]; then
  echo "" >&2
  echo "A pinned version.spring.frameworkN does not match what its Spring Boot BOM declares." >&2
  echo "Either align the property with the BOM, or record why the override is intentional." >&2
fi

exit "$rc"
