#!/bin/bash
# Headless e2e-generation campaign — one run per group letter, one commit per group.
set -eo pipefail
GRPS="${@:?usage: run-e2e-campaign.sh <group> [group...] e.g. A B F}"
# 1) Validate arguments BEFORE invoking claude: each group must be a unique
#    letter A-O. Any offender stops the script immediately with a message.
seen=""
for g in $GRPS; do
  case "$g" in
    [A-O]) ;;
    *) echo "ABORT: invalid group '$g' — expected a single letter A-O."; exit 1 ;;
  esac
  case " $seen " in
    *" $g "*) echo "ABORT: duplicate group '$g' — each letter must appear once."; exit 1 ;;
  esac
  seen="$seen $g"
done
for g in $GRPS; do
  f="src/test/java/com/intermarche/pos/e2e/Group${g}IT.java"
  [ -f "$f" ] || true
  echo "=== Group $g ==="
  claude -p "/gen-e2e Group${g}IT" 2>&1 | tee -a campaign-e2e.log || { echo "FAILED: Group $g — stopping."; exit 1; }
  claude -p "Lance mvn -q verify -Dit.test=Group${g}IT -DskipITs=false. Si tout est vert : signale les scénarios couverts et le résidu justifié ([V]/[N] ignorés), puis stage uniquement src/test/java/com/intermarche/pos/e2e/Group${g}IT.java (plus CLAUDE.md et .claude/ s'ils ont changé) et commite avec exactement ce message : test: e2e scenarios group $g — jamais git push. Si quelque chose est rouge : ne commite rien et explique." 2>&1 | tee -a campaign-e2e.log || true
  # 2) Success is measured on the commit, not on claude's politeness: verify
  #    mechanically that the expected commit landed, whatever claude returned.
  expected="test: e2e scenarios group $g"
  actual="$(git log -1 --format=%s)"
  if [ "$actual" != "$expected" ]; then
    echo "FAILED: Group $g — expected commit '$expected' not found (HEAD is '$actual')."
    exit 1
  fi
done
echo "Campaign done."
