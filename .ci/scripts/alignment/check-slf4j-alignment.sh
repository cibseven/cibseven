#!/usr/bin/env bash
#
# Verifies the logging stack pinned in parent/pom.xml - version.slf4j and version.logback -
# against two references:
#
#   * the Spring Boot BOMs we import, which declare slf4j.version and logback.version;
#   * each other, via logback-classic itself: logback publishes the slf4j.version it was
#     built and tested against, and slf4j 2.x introduced a service-provider mechanism that
#     logback 1.3+ binds to, so a 1.x/2.x split between the two silently leaves the
#     application with no logging backend at all.
#
# Unlike the Spring Framework check, the comparison is "at least", not exact: version.slf4j
# and version.logback are pinned ahead of what the BOMs declare on purpose - to force
# dependency convergence and to pick up fixes earlier than Boot does - so running ahead
# inside the same major version is reported but not treated as a failure. Falling behind a
# reference, or crossing a major boundary it knows nothing about, is a failure.
#
# Facts come from collect-alignment-facts.sh; this script runs no Maven at all.
#
# Usage:  bash .ci/scripts/alignment/check-slf4j-alignment.sh
# Exit:   0 = aligned, 1 = mismatch, 2 = check could not run

set -uo pipefail

# shellcheck source=lib/alignment-lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/alignment-lib.sh"

alignment_load_facts

BOOT3="$(pom_prop version.spring-boot)"
BOOT4="$(pom_prop version.spring-boot4)"
LOGBACK="$(pom_prop version.logback)"

# logback-parent carries the slf4j.version that logback-classic inherits, so both poms
# have to be in the local repository before artifact_prop can climb from one to the other.
alignment_handle_requires "$@" <<EOF
org.springframework.boot:spring-boot-dependencies:${BOOT3}
org.springframework.boot:spring-boot-dependencies:${BOOT4}
ch.qos.logback:logback-classic:${LOGBACK}
ch.qos.logback:logback-parent:${LOGBACK}
EOF

alignment_begin slf4j

SLF4J="$(pom_prop version.slf4j)"

# ------------------------------------------------- our pins against the Boot BOMs

# check_against_boot <spring-boot version> <our property> <BOM property>
check_against_boot() {
  local boot="$1" our_property="$2" bom_property="$3" declared

  if [ -z "$boot" ]; then
    record ERROR "spring-boot version property is not declared in parent/pom.xml"
    alignment_worsen 2
    return
  fi

  declared="$(artifact_prop org.springframework.boot spring-boot-dependencies \
                            "$boot" "$bom_property")" || declared=''

  check_version atleast \
    "$our_property" "$(pom_prop "$our_property")" \
    "spring-boot-dependencies:${boot} ${bom_property}" "$declared"
}

check_against_boot "$BOOT3" version.slf4j   slf4j.version
check_against_boot "$BOOT4" version.slf4j   slf4j.version
check_against_boot "$BOOT3" version.logback logback.version
check_against_boot "$BOOT4" version.logback logback.version

# ------------------------------------- version.slf4j against version.logback itself

if [ -z "$LOGBACK" ]; then
  record ERROR "version.logback is not declared in parent/pom.xml"
  alignment_worsen 2
else
  LOGBACK_SLF4J="$(artifact_prop ch.qos.logback logback-classic "$LOGBACK" slf4j.version)" \
    || LOGBACK_SLF4J=''
  check_version atleast \
    version.slf4j "$SLF4J" \
    "logback-classic:${LOGBACK} slf4j.version" "$LOGBACK_SLF4J"
fi

alignment_end
