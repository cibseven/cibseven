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
# Our own properties are read through maven-help-plugin rather than by matching text, because
# help:evaluate returns the *effective* value: profile activation, parent inheritance, ${...}
# indirection and -D overrides are all accounted for, and - most importantly on a version-bump
# branch - a commented-out old value left above the live one cannot be picked up by mistake.
# The Boot BOM is still read textually: it is machine-generated, declares the property exactly
# once with a literal value, and carries no comments, so there is nothing there to get wrong.
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

# evaluate <pom-file> <expression> -> effective value on stdout; non-zero if undefined
evaluate() {
  local value
  value="$(mvn -B -q -f "$1" "${HELP_PLUGIN}:evaluate" \
             -Dexpression="$2" -DforceStdout 2>/dev/null | tail -n 1)"
  # help:evaluate reports an unknown expression as a message rather than failing
  case "$value" in
    ''|*'null object or invalid expression'*) return 1 ;;
  esac
  printf '%s\n' "$value"
}

# bom_property <property-name> <bom-pom-file> -> first textual match
# Only ever applied to spring-boot-dependencies-<version>.pom (see header note).
bom_property() {
  sed -n "s|.*<$1>\([^<]*\)</$1>.*|\1|p" "$2" | head -n 1
}

# Honour -Dmaven.repo.local (Jenkins redirects it via MAVEN_OPTS) instead of assuming ~/.m2
LOCAL_REPO="$(evaluate "$POM" settings.localRepository)" || LOCAL_REPO=''
if [ -z "$LOCAL_REPO" ] || [ ! -d "$LOCAL_REPO" ]; then
  echo "ERROR: could not determine the local Maven repository" >&2
  exit 2
fi
echo "Local Maven repository: $LOCAL_REPO"

rc=0

# check_pair <spring-boot property> <spring-framework property>
check_pair() {
  local boot_prop="$1" fw_prop="$2" boot fw bom_pom declared

  if ! boot="$(evaluate "$POM" "$boot_prop")"; then
    echo "ERROR: property '$boot_prop' is not defined in $POM" >&2
    return 2
  fi
  if ! fw="$(evaluate "$POM" "$fw_prop")"; then
    echo "ERROR: property '$fw_prop' is not defined in $POM" >&2
    return 2
  fi

  mvn -B -q "${DEPENDENCY_PLUGIN}:get" \
      -Dartifact="org.springframework.boot:spring-boot-dependencies:${boot}:pom" >/dev/null 2>&1

  bom_pom="${LOCAL_REPO}/org/springframework/boot/spring-boot-dependencies/${boot}/spring-boot-dependencies-${boot}.pom"
  if [ ! -f "$bom_pom" ]; then
    echo "ERROR: could not resolve spring-boot-dependencies:${boot}:pom" >&2
    return 2
  fi

  declared="$(bom_property 'spring-framework.version' "$bom_pom")"
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
