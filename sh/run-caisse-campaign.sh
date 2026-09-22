#!/bin/bash
# Headless till-side build campaign — one run per lot, one commit per lot.
# The lot's contract is the §7 of caisse-campaign.md; the requirement text is
# the lot 4 table of docs/backoffice-lots.md. Both are read by the agent.
set -eo pipefail
LOTS="${@:?usage: run-caisse-campaign.sh <lot> [lot...] e.g. C1 C2 C5a}"

# 1) Validate arguments BEFORE invoking claude: each lot must be a unique
#    known token. C5 is sliced into C5a/C5b/C5c (one session each), so the
#    bare C5 is rejected rather than silently taken for one of them.
seen=""
for n in $LOTS; do
  case "$n" in
    C1|C2|C3|C4|C5a|C5b|C5c) ;;
    C5) echo "ABORT: lot C5 is sliced — use C5a, C5b and C5c."; exit 1 ;;
    *) echo "ABORT: invalid lot '$n' — expected C1..C4, C5a, C5b or C5c."; exit 1 ;;
  esac
  case " $seen " in
    *" $n "*) echo "ABORT: duplicate lot '$n' — each lot must appear once."; exit 1 ;;
  esac
  seen="$seen $n"
done

# 2) A token carrying whitespace is the classic copy-paste wound: the
#    terminal wrapped it on display and the wrap became a real newline, so
#    the Authorization header is malformed. Caught here rather than three
#    lots into the night.
if [ -n "$CLAUDE_CODE_OAUTH_TOKEN" ] && \
   printf '%s' "$CLAUDE_CODE_OAUTH_TOKEN" | grep -q '[[:space:]]'; then
  echo "ABORT: CLAUDE_CODE_OAUTH_TOKEN contient un espace ou un retour a la ligne."
  echo "  export CLAUDE_CODE_OAUTH_TOKEN=\"\$(printf '%s' '<jeton>' | tr -d '[:space:]')\""
  exit 1
fi

# 3) Fail fast on credentials. An expired OAuth session does not recover on
#    its own, and discovering it at 19:05 costs the night. One trivial call
#    proves the whole path — authentication, model, quota — before the
#    campaign commits to anything.
if ! preflight="$(claude -p 'Reponds exactement: PRET' 2>&1)" || \
   ! printf '%s' "$preflight" | grep -q 'PRET'; then
  echo "ABORT: claude ne repond pas."
  printf '%s\n' "$preflight"
  echo
  echo "Si c'est l'authentification :"
  echo "  claude setup-token          # une fois, sur une machine avec navigateur"
  echo "  export CLAUDE_CODE_OAUTH_TOKEN=<jeton>   # dans le shell qui lance, pas dans ce script"
  exit 1
fi

for n in $LOTS; do
  echo "=== Lot $n — $(date '+%H:%M:%S') ==="
  # HEAD before the lot: a REPLAYED lot already has the expected message at
  # HEAD, so comparing the message alone would call a session that committed
  # nothing a success. The commit must be NEW.
  before="$(git rev-parse HEAD)"
  claude -p "Lis caisse-campaign.md et applique-le intégralement avec LOT = $n. Le contrat du lot est sa fiche au paragraphe 7 ; le texte des exigences est la table du lot 4 de docs/backoffice-lots.md. Ne commite pas : la session suivante s'en charge." \
    2>&1 | tee -a campaign-caisse.log || { echo "FAILED: lot $n — stopping."; exit 1; }

  # 3) The commit session commits, and ONLY commits. It must never produce a
  #    report nor re-score a requirement: a session that built nothing cannot
  #    score better than the one that built. Asking both sessions for the
  #    report is how lot 1 came back with two counts for the same code.
  claude -p "Lance mvn -q verify. Si tout est vert : stage les fichiers du lot $n et commite avec EXACTEMENT ce message : feat(caisse): lot $n — jamais git push. Tu ne produis AUCUN rapport et tu ne recotes AUCUNE exigence : le rapport de la session precedente fait foi. Si quelque chose est rouge : ne commite rien et explique ce qui bloque." \
    2>&1 | tee -a campaign-caisse.log || true

  # 4) Success is measured on the commit, not on claude's politeness: verify
  #    mechanically that the expected commit landed, whatever claude returned.
  expected="feat(caisse): lot $n"
  actual="$(git log -1 --format=%s)"
  if [ "$actual" != "$expected" ]; then
    echo "FAILED: lot $n — expected commit '$expected' not found (HEAD is '$actual')."
    exit 1
  fi
  if [ "$(git rev-parse HEAD)" = "$before" ]; then
    echo "FAILED: lot $n — HEAD n'a pas bougé: aucun commit produit par cette session."
    exit 1
  fi
  echo "=== Lot $n committed — $(date '+%H:%M:%S') ==="
done
echo "Campaign done."
