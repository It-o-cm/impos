#!/bin/bash
# Headless test-generation campaign — one run per class, one commit per package.
set -e
PKGS="${@:?usage: run-tests-campaign.sh <pkg> [pkg...] e.g. service.sync imports}"
for pkg in $PKGS; do
  dir="src/main/java/com/intermarche/pos/${pkg//.//}"
  for f in "$dir"/*.java; do
    c=$(basename "$f" .java)
    [ -f "src/test/java/com/intermarche/pos/${pkg//.//}/${c}Test.java" ] && continue
    echo "=== $pkg.$c ==="
    claude -p "/gen-tests $c" || { echo "FAILED: $c — stopping."; exit 1; }
  done
  claude -p "Lance mvn -q verify complet. Si tout est vert : donne la couverture de branches JaCoCo de chaque classe du package com.intermarche.pos.$pkg au format « classe : n/n (%) », signale toute classe sans test, puis stage uniquement les classes de test de ce package (plus CLAUDE.md et .claude/settings.json s'ils ont changé) et commite avec exactement ce message : test: full branch coverage for com.intermarche.pos.$pkg — jamais git push. Si quelque chose est rouge : ne commite rien et explique." || exit 1
done
echo "Campaign done."
