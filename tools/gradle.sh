#!/usr/bin/env bash
# Serialises Gradle across every HiveCore checkout and worktree on this machine (15.6 GB RAM: one build at a time).
# Usage: tools/gradle.sh <gradle args...>   e.g. tools/gradle.sh :engine:testDebugUnitTest --tests '*WorkQueueTest*'
# The lock is a directory under the user's home so every worktree shares it; a lock older than 45 minutes is
# treated as stale (a build that died without cleanup) and taken over.
set -u
LOCK="${HIVECORE_GRADLE_LOCK:-$HOME/.hivecore-gradle.lock}"
MAX_AGE_S=2700
waited=0
while ! mkdir "$LOCK" 2>/dev/null; do
  if [ -f "$LOCK/stamp" ]; then
    age=$(( $(date +%s) - $(cat "$LOCK/stamp" 2>/dev/null || echo 0) ))
    if [ "$age" -gt "$MAX_AGE_S" ]; then echo "gradle.sh: breaking stale lock (${age}s old)"; rm -rf "$LOCK"; continue; fi
  fi
  if [ $((waited % 60)) -eq 0 ]; then echo "gradle.sh: waiting for lock $LOCK (held by: $(cat "$LOCK/owner" 2>/dev/null || echo '?'))"; fi
  sleep 5; waited=$((waited + 5))
done
date +%s > "$LOCK/stamp"; echo "$$ $(pwd) $*" > "$LOCK/owner"
trap 'rm -rf "$LOCK"' EXIT INT TERM
cd "$(dirname "$0")/.." || exit 1
./gradlew "$@"
