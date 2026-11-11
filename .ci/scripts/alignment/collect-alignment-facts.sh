#!/usr/bin/env bash
#
# Preliminary step of the "Dependency Version Alignment Check" pipeline stage.
#
# Resolves, in one place and one go, everything the individual check-*-alignment.sh
# scripts need, so that those can then run as parallel Jenkins matrix cells without a
# single Maven invocation of their own.
#
# What it produces under ${ALIGNMENT_FACTS_DIR} (default: target/alignment):
#
#   local-repo.txt   the local Maven repository this build actually uses
#   pom.properties   every scalar property of the effective pom of parent/pom.xml
#   requires.txt     the third-party poms the checks asked to have cached
#
# Our own properties come from help:effective-pom rather than from the pom on disk: one
# invocation yields the fully resolved model, so a commented-out old value sitting above
# the live one is simply not there to be matched.
#
# The third-party poms are read textually out of the local repository, and are fetched
# only when they are not there already. Fetching them here, sequentially, also keeps the
# parallel cells from racing each other on the same download.
#
# Usage:  bash .ci/scripts/alignment/collect-alignment-facts.sh [path/to/parent/pom.xml]
# Exit:   0 = facts collected, 2 = they could not be

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Tell the checks they are being called from inside the collector, so alignment_load_facts
# reports a missing pom.properties instead of recursing back into this script.
export ALIGNMENT_COLLECTING=1

# shellcheck source=lib/alignment-lib.sh
. "${SCRIPT_DIR}/lib/alignment-lib.sh"

# The checks resolve the same default on their own, but an override has to reach them too.
export ALIGNMENT_FACTS_DIR

POM="${1:-parent/pom.xml}"

HELP_PLUGIN='org.apache.maven.plugins:maven-help-plugin:3.5.1'
DEPENDENCY_PLUGIN='org.apache.maven.plugins:maven-dependency-plugin:3.8.1'

if [ ! -f "$POM" ]; then
  echo "ERROR: '$POM' not found - run from the repository root" >&2
  exit 2
fi

# ---------------------------------------------------------- 1. local repository
#
# We deliberately do not guess ${HOME}/.m2/repository: a settings.xml redirecting
# localRepository elsewhere would send us looking in the wrong tree, and a
# wrong-but-existing path is harder to diagnose than one extra Maven call.
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

  # 2. exported by Jenkinsfile.cib; always set and always the repo this build uses:
  #    - USE_PRIVATE_REPO=false: the standard ~/.m2/repository
  #    - USE_PRIVATE_REPO=true:  ~/.m2/repository/cibseven-install-dir/job-name
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

mkdir -p "$ALIGNMENT_FACTS_DIR" || {
    echo "ERROR: could not create '$ALIGNMENT_FACTS_DIR'" >&2
    exit 2
}

mkdir -p "$ALIGNMENT_REPORT_DIR" || {
    echo "ERROR: could not create '$ALIGNMENT_REPORT_DIR'" >&2
    exit 2
}

printf '%s\n' "$LOCAL_REPO" > "$ALIGNMENT_LOCAL_REPO_FILE"
echo "Local Maven repository: $LOCAL_REPO"

# -------------------------------------------------------- 2. our own properties

EFFECTIVE_POM="$(mktemp)" || {
  echo "ERROR: could not create a temporary file" >&2
  exit 2
}
trap 'rm -f "$EFFECTIVE_POM"' EXIT

echo "Resolving the effective pom of $POM ..."
if ! mvn -B -q -f "$POM" "${HELP_PLUGIN}:effective-pom" \
       -Doutput="$EFFECTIVE_POM" >/dev/null 2>&1 || [ ! -s "$EFFECTIVE_POM" ]; then
  echo "ERROR: could not generate the effective pom for $POM" >&2
  exit 2
fi

# Turns simple XML properties into name=value lines.
# The project's own <properties> is the first such block in the effective pom; plugin
# configuration blocks of the same name only ever come later, inside <build>.
# Only single-line scalars are kept - multi-line values such as the OSGi import lists
# are not versions and nothing here reads them.
awk '/<properties>/ { inside = 1; next }
     inside && /<\/properties>/ { exit }
     inside { print }' "$EFFECTIVE_POM" \
  | sed -nE 's|^[[:space:]]*<([A-Za-z0-9._-]+)>(.*)</\1>[[:space:]]*$|\1=\2|p' \
  > "$ALIGNMENT_POM_PROPERTIES"

if [ ! -s "$ALIGNMENT_POM_PROPERTIES" ]; then
  echo "ERROR: no properties found in the effective pom of $POM" >&2
  exit 2
fi
echo "Collected $(wc -l < "$ALIGNMENT_POM_PROPERTIES" | tr -d ' ') properties from $POM"

# ------------------------------------------------- 3. poms the checks want cached

# find all the check-*-alignment.sh scripts in $SCRIPT_DIR
CHECKS=("$SCRIPT_DIR"/check-*-alignment.sh)
if [ ! -e "${CHECKS[0]}" ]; then
  echo "ERROR: no check-*-alignment.sh scripts next to $SCRIPT_DIR" >&2
  exit 2
fi

REQUIRED="$(mktemp)" || exit 2
trap 'rm -f "$EFFECTIVE_POM" "$REQUIRED"' EXIT

# loops over every check script and asks it to declare its requirements
for check in "${CHECKS[@]}"; do
  name="$(basename "$check" .sh)"
  if ! bash "$check" --requires >> "$REQUIRED"; then
    echo "ERROR: '$name --requires' failed - it cannot declare what it needs" >&2
    exit 2
  fi
done

sort -u "$REQUIRED" | sed '/^[[:space:]]*$/d' > "${ALIGNMENT_FACTS_DIR}/requires.txt"

# ------------------------------------------------------- 4. fill a cold repository

rc=0
# Read requires.txt line by line
while IFS= read -r coordinates; do
  [ -n "$coordinates" ] || continue

  # Split the Maven coordinates
  groupId="${coordinates%%:*}"
  rest="${coordinates#*:}"
  artifactId="${rest%%:*}"
  version="${rest##*:}"

  if [ -z "$groupId" ] || [ -z "$artifactId" ] || [ -z "$version" ]; then
    echo "ERROR: malformed requirement '$coordinates' (expected groupId:artifactId:version)" >&2
    rc=2
    continue
  fi

  pom="$(artifact_pom_path "$groupId" "$artifactId" "$version")"
  if [ -f "$pom" ]; then
    echo "cached    $coordinates"
    continue
  fi

  echo "fetching  $coordinates"
  mvn -B -q "${DEPENDENCY_PLUGIN}:get" \
      -Dartifact="${groupId}:${artifactId}:${version}:pom" >/dev/null 2>&1

  if [ ! -f "$pom" ]; then
    echo "ERROR: could not resolve ${groupId}:${artifactId}:${version}:pom" >&2
    rc=2
  fi
done < "${ALIGNMENT_FACTS_DIR}/requires.txt"

if [ "$rc" -ne 0 ]; then
  echo "" >&2
  echo "Alignment facts are incomplete; the checks would not be able to run." >&2
  exit "$rc"
fi

echo "Alignment facts ready in ${ALIGNMENT_FACTS_DIR}"
exit 0
