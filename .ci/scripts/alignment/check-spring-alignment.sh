#!/usr/bin/env bash
#
# Verifies that the Spring Framework versions pinned in parent/pom.xml match the versions
# that the pinned Spring Boot BOMs declare.
#
# Complements the maven-enforcer bannedDependencies rule in parent/pom.xml: that rule
# checks the *resolved* Spring version per module, which it cannot do where a
# spring-framework-bom import outranks Spring Boot's BOM (spring-boot-4-starter pins the
# framework ahead of Boot, so the resolved version is tautologically correct there). This
# script compares the *declared* values instead, so it catches drift the enforcer is
# blind to.
#
# The comparison is exact in both directions. Boot's autoconfiguration is written against
# the exact framework version its BOM ships.
#
# Facts come from collect-alignment-facts.sh; this script runs no Maven at all.
#
# Usage:  bash .ci/scripts/alignment/check-spring-alignment.sh
# Exit:   0 = aligned, 1 = mismatch, 2 = check could not run

set -uo pipefail

# shellcheck source=lib/alignment-lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/alignment-lib.sh"

alignment_load_facts

BOOT3="$(pom_prop version.spring-boot)"
BOOT4="$(pom_prop version.spring-boot4)"

# Everything between the two EOFs is passed to alignment_handle_requires as its standard input.
alignment_handle_requires "$@" <<EOF
org.springframework.boot:spring-boot-dependencies:${BOOT3}
org.springframework.boot:spring-boot-dependencies:${BOOT4}
EOF

alignment_begin spring

# check_boot_line <spring-boot version> <our spring framework property>
check_boot_line() {
  local boot="$1" framework_property="$2" declared ours

  if [ -z "$boot" ]; then
    record ERROR "spring-boot version property is not declared in parent/pom.xml"
    alignment_worsen 2
    return
  fi

  ours="$(pom_prop "$framework_property")"
  declared="$(artifact_prop org.springframework.boot spring-boot-dependencies \
                            "$boot" spring-framework.version)" || declared=''

  check_version exact \
    "$framework_property" "$ours" \
    "spring-boot-dependencies:${boot} spring-framework.version" "$declared"
}

check_boot_line "$BOOT3" version.spring.framework6
check_boot_line "$BOOT4" version.spring.framework7

alignment_end
