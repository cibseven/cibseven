#!/usr/bin/env bash
#
# Shared helpers for the "Dependency Version Alignment Check" scripts.
#
# The individual checks never invoke Maven. Everything they need was resolved once,
# up front, by collect-alignment-facts.sh into ${ALIGNMENT_FACTS_DIR}:
#
#   local-repo.txt   the local Maven repository this build actually uses
#   pom.properties   every property of the effective pom of parent/pom.xml
#   requires.txt     the third-party poms the checks asked to have cached
#   reports/*.txt    one report per check (written by the checks themselves)
#
# Keeping Maven out of the checks is what lets them run as parallel Jenkins matrix
# cells: a Maven start-up costs ~30s in the CI container, so paying it once instead
# of once per cell is the whole point of the split.
#
# A check-* script looks like this:
#
#   source .../lib/alignment-lib.sh
#   alignment_load_facts                          # pom_prop reads a file this writes -
#                                                  # load it before the first pom_prop call,
#                                                  # not after: --requires exits below before
#                                                  # alignment_begin would ever load it for you
#   boot="$(pom_prop version.spring-boot)"
#   alignment_handle_requires "$@" <<EOF          # poms it reads, one g:a:v per line
#   org.springframework.boot:spring-boot-dependencies:${boot}
#   EOF
#   alignment_begin slf4j
#   check_version exact version.slf4j "$ours" "some BOM" "$expected"
#   alignment_end
#
# Exit codes (shared by the collector and every check-*):
#   0  aligned
#   1  at least one mismatch
#   2  the check could not run

set -uo pipefail

ALIGNMENT_FACTS_DIR="${ALIGNMENT_FACTS_DIR:-target/alignment}"
ALIGNMENT_POM_PROPERTIES="${ALIGNMENT_FACTS_DIR}/pom.properties"
ALIGNMENT_LOCAL_REPO_FILE="${ALIGNMENT_FACTS_DIR}/local-repo.txt"
ALIGNMENT_REPORT_DIR="${ALIGNMENT_FACTS_DIR}/reports"

ALIGNMENT_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ALIGNMENT_SCRIPT_DIR="$(dirname "$ALIGNMENT_LIB_DIR")"

ALIGNMENT_RC=0
ALIGNMENT_CHECK_NAME=''
ALIGNMENT_REPORT=''

# ----------------------------------------------------------------- XML reading
#
# Deliberately textual, not XPath: the CI container has no xmllint, and every value
# read here is a plain <name>value</name> in a pom's <properties> block.

# str_element <xml-fragment> <element-name> -> trimmed text of the first match
str_element() {
  local fragment="$1" escaped
  escaped="$(printf '%s' "$2" | sed 's|[.[\*^$]|\\&|g')"
  printf '%s' "$fragment" \
    | grep -oE "<${escaped}>[^<]*</${escaped}>" \
    | head -n 1 \
    | sed -E "s|^<${escaped}>||; s|</${escaped}>$||" \
    | sed 's|^[[:space:]]*||; s|[[:space:]]*$||'
}

# xml_flatten <file> -> the file as one line, so greedy patterns can span elements
xml_flatten() {
  tr '\n' ' ' < "$1"
}

# --------------------------------------------------------------- reading facts

alignment_fatal() {
  echo "ERROR: $*" >&2
  exit 2
}

alignment_local_repo() {
  cat "$ALIGNMENT_LOCAL_REPO_FILE"
}

# Loads the collected facts, collecting them first when they are missing so a developer
# can run a single check-* straight from a clean checkout. The guard keeps the collector -
# which calls every check with --requires - from recursing into itself.
alignment_load_facts() {
  if [ -s "$ALIGNMENT_POM_PROPERTIES" ] && [ -s "$ALIGNMENT_LOCAL_REPO_FILE" ]; then
    return 0
  fi
  if [ -n "${ALIGNMENT_COLLECTING:-}" ]; then
    alignment_fatal "the collector did not produce ${ALIGNMENT_POM_PROPERTIES}"
  fi
  echo "No collected facts under ${ALIGNMENT_FACTS_DIR} - running the collector first."
  bash "${ALIGNMENT_SCRIPT_DIR}/collect-alignment-facts.sh" \
    || alignment_fatal "collect-alignment-facts.sh failed"
}

# pom_prop <property-name> -> value declared in the effective pom of parent/pom.xml
#
# Reads $ALIGNMENT_POM_PROPERTIES directly; it does not call alignment_load_facts itself.
# Callers must have called alignment_load_facts first, or this silently returns empty.
pom_prop() {
  local escaped
  escaped="$(printf '%s' "$1" | sed 's|[.[\*^$]|\\&|g')"
  sed -n "s|^${escaped}=||p" "$ALIGNMENT_POM_PROPERTIES" | head -n 1
}

# pom_prop_required <property-name> -> value, or abort when the property is gone
pom_prop_required() {
  local value
  value="$(pom_prop "$1")"
  [ -n "$value" ] || alignment_fatal "property '$1' is not declared in parent/pom.xml"
  printf '%s\n' "$value"
}

# artifact_pom_path <groupId> <artifactId> <version> -> path inside the local repository
artifact_pom_path() {
  printf '%s/%s/%s/%s/%s-%s.pom\n' \
    "$(alignment_local_repo)" "${1//.//}" "$2" "$3" "$2" "$3"
}

# artifact_prop <groupId> <artifactId> <version> <property-name>
#
# Reads a property declared by a third-party pom, 
# climbing the <parent> chain when the pom inherits it.
# Values that are still an unresolved ${placeholder} do not count as found, so the climb
# continues up to the pom that carries the literal.
artifact_prop() {
  local g="$1" a="$2" v="$3" name="$4"
  local pom flat value parent depth=0

  # if the value came back as an unresolved ${placeholder}, it climbs to <parent> and retries, up to 5 levels
  while [ "$depth" -lt 5 ]; do
    # builds the repo path
    pom="$(artifact_pom_path "$g" "$a" "$v")"
    [ -f "$pom" ] || return 1

    # flatten the POM file into one line
    flat="$(xml_flatten "$pom")"
    # find the requested XML element
    value="$(str_element "$flat" "$name")"
    # check whether a resolved value was found
    if [ -n "$value" ] && [ "${value#\$\{}" = "$value" ]; then
      printf '%s\n' "$value"
      return 0
    fi

    # if it the value is not resolved (${...}),
    # go to the POM's parent and look there instead.
    parent="$(printf '%s' "$flat" | sed -n 's|.*<parent>\(.*\)</parent>.*|\1|p')"
    [ -n "$parent" ] || return 1
    g="$(str_element "$parent" groupId)"
    a="$(str_element "$parent" artifactId)"
    v="$(str_element "$parent" version)"
    { [ -n "$g" ] && [ -n "$a" ] && [ -n "$v" ]; } || return 1

    depth=$((depth + 1))
  done

  return 1
}

# ------------------------------------------------------------- version compare

# version_cmp <a> <b> -> -1 when a < b, 0 when equal, 1 when a > b
# Compares the leading numeric fields only; qualifiers such as -SNAPSHOT are ignored.
version_cmp() {
  local left right i l r
  # remove everything after - and split on . to create arrays
  IFS='.' read -r -a left  <<< "${1%%-*}"
  IFS='.' read -r -a right <<< "${2%%-*}"
  # component 0 → major
  # component 1 → minor
  # component 2 → patch
  # component 3 → fourth component
  for i in 0 1 2 3; do
    l="${left[i]:-0}";  l="${l//[!0-9]/}";  l=$((10#${l:-0}))
    r="${right[i]:-0}"; r="${r//[!0-9]/}"; r=$((10#${r:-0}))
    if [ "$l" -gt "$r" ]; then echo 1; return; fi
    if [ "$l" -lt "$r" ]; then echo -1; return; fi
  done
  echo 0
}

version_major() {
  local major="${1%%.*}"
  printf '%s\n' "${major//[!0-9]/}"
}

# ------------------------------------------------------------------- reporting

alignment_begin() {
  ALIGNMENT_CHECK_NAME="$1"
  ALIGNMENT_RC=0
  alignment_load_facts
  mkdir -p "$ALIGNMENT_REPORT_DIR"
  ALIGNMENT_REPORT="${ALIGNMENT_REPORT_DIR}/${ALIGNMENT_CHECK_NAME}.txt"
  : > "$ALIGNMENT_REPORT"
  echo "=== ${ALIGNMENT_CHECK_NAME} version alignment" | tee -a "$ALIGNMENT_REPORT"
}

# record <status> <message...>
record() {
  local status="$1"
  shift
  printf '%-9s %s\n' "$status" "$*" | tee -a "$ALIGNMENT_REPORT"
}

alignment_worsen() {
  [ "$1" -gt "$ALIGNMENT_RC" ] && ALIGNMENT_RC="$1"
  return 0
}

# check_version <mode> <our-label> <our-version> <reference-label> <reference-version>
#
# mode=exact    the two versions must be identical. Used where any drift is a bug, such
#               as a Spring Framework pin against the Boot BOM that is meant to ship it.
# mode=atleast  we may run ahead of the reference inside the same major version - that is
#               the shape of a deliberate CVE uplift - but never behind it, and never
#               across a major boundary the reference knows nothing about.
check_version() {
  local mode="$1" ours_label="$2" ours="$3" ref_label="$4" ref="$5"
  local line comparison

  if [ -z "$ours" ] || [ -z "$ref" ]; then
    record ERROR "$(printf '%-26s = %-10s | %s = %s' \
      "$ours_label" "${ours:-<missing>}" "$ref_label" "${ref:-<missing>}")"
    alignment_worsen 2
    return
  fi

  line="$(printf '%-26s = %-10s | %s = %s' "$ours_label" "$ours" "$ref_label" "$ref")"
  comparison="$(version_cmp "$ours" "$ref")"

  if [ "$comparison" = "0" ]; then
    record OK "$line"
    return
  fi

  if [ "$mode" = "atleast" ] && [ "$comparison" = "1" ] \
     && [ "$(version_major "$ours")" = "$(version_major "$ref")" ]; then
    record NEWER "$line  (ahead of the reference - deliberate uplift)"
    return
  fi

  if [ "$comparison" = "-1" ]; then
    record MISMATCH "$line  (we are behind the reference)"
  else
    record MISMATCH "$line  (we are ahead of the reference)"
  fi
  alignment_worsen 1
}

alignment_end() {
  echo "" | tee -a "$ALIGNMENT_REPORT"
  case "$ALIGNMENT_RC" in
    0) echo "${ALIGNMENT_CHECK_NAME}: aligned." | tee -a "$ALIGNMENT_REPORT" ;;
    1) {
         echo "${ALIGNMENT_CHECK_NAME}: at least one version does not match its reference."
         echo "Either align the property in parent/pom.xml, or record there why the override is intentional."
       } | tee -a "$ALIGNMENT_REPORT" >&2 ;;
    *) echo "${ALIGNMENT_CHECK_NAME}: the check could not run." | tee -a "$ALIGNMENT_REPORT" >&2 ;;
  esac
  exit "$ALIGNMENT_RC"
}

# -------------------------------------------------------------------- requires
#
# Every check-* declares on stdin, to alignment_handle_requires, the third-party poms it
# reads. The collector runs each check with --requires and resolves the union of those
# coordinates in one pass, so a cold local repository is already filled when the matrix
# fans out and no two parallel cells ever race on the same download.

# alignment_handle_requires <script-args...>  (coordinates on stdin, one g:a:v per line)
alignment_handle_requires() {
  local coordinates
  # reads stdin until EOF
  coordinates="$(cat)"
  case "${1:-}" in
    --requires)
      printf '%s\n' "$coordinates" | sed '/^[[:space:]]*$/d'
      # --requires request handled successfully; stop executing
      exit 0
      ;;
  esac
}
