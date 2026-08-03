#!/bin/bash
# Headless e2e-generation campaign — one run per group letter, one commit per group.
set -eo pipefail
GROUPS="${@:?usage: run-e2e-campaign.sh <group> [group...] e.g. A B F}"
for g in $GROUPS; do
  f="src/test/java/com/intermarche/pos/e2e/Group${g}IT.java"
  [ -f "$f" ] || true
  echo "=== Group $g ==="
  claude -p "/gen-e2e Group${g}IT" 2>&1 | tee -a campaign-e2e.log || { echo "FAILED: Group $g — stopping."; exit 1; }
  claude -p "Lance mvn -q verify -Dit.test=Group${g}IT -DskipITs=false. Si tout est vert : signale les scénarios couverts et le résidu justifié ([V]/[N] ignorés), puis stage uniquement src/test/java/com/intermarche/pos/e2e/Group${g}IT.java (plus CLAUDE.md et .claude/ s'ils ont changé) et commite avec exactement ce message : test(e2e): group $g scenarios — jamais git push. Si quelque chose est rouge : ne commite rien et explique." 2>&1 | tee -a campaign-e2e.log || exit 1
done
echo "Campaign done."
