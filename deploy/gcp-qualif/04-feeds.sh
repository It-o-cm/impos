#!/usr/bin/env bash
# Loads the referential CSV feeds into the qualification environment.
# Run LOCALLY. The files never leave your computer as files: curl posts each
# one over HTTPS to the node's import door.
#
# Usage: ./04-feeds.sh            -> the demonstration set (demo/feeds/*.csv)
#        ./04-feeds.sh -07039     -> the real 07039 catalogue (109 204 articles)
#
# WHY THE NODE AND NOT THE REGISTER. Feeding the node feeds everything: it
# keeps the verbatim copies and delivers them to the valuation engine on its
# own, and a register wired to it pulls from there. The register of this
# demonstrator does not even need them — it runs under the demo profile and
# reseeds its own demonstration referential at every boot.
#
# ONE GRAMMAR: POST /feeds/import/{CODE}, the same door and the same codes as
# the register's, authenticated with HTTP Basic against the employees table.
set -euo pipefail

HOST="${HOST:-https://imedge-qualif.it-o-cm.fr}"
ADMIN_USER="${ADMIN_USER:-admin}"
# The demonstrator's administrator: the node runs the demo profile, whose
# bootstrap account is admin/admin -- the same credentials as locally, and
# the same ones employees.csv carries for that account.
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FEEDS="${FEEDS:-$SCRIPT_DIR/../../demo/feeds}"

SUFFIX=""
if [ "${1:-}" = "-07039" ]; then
  SUFFIX="-07039"
fi

# Pushes one feed and prints the answer with the time it took. A feed of a
# hundred thousand articles is slow, not stuck, and seeing which one spends
# the time is what tells the two apart.
push_feed() {
  local file start
  file="$1"
  if [ ! -f "$FEEDS/$file" ]; then
    printf '%-38s %s\n' "$file" "ABSENT — ignore"
    return 0
  fi
  start=$SECONDS
  printf '%-38s ' "$file"
  curl -s -w ' [HTTP %{http_code}]' -u "$ADMIN_USER:$ADMIN_PASSWORD" \
    -H 'Content-Type: text/plain' --data-binary "@$FEEDS/$file" "$HOST$2"
  printf ' %ss\n' "$((SECONDS - start))"
}

echo "Chargement du referentiel dans $HOST"

# EMPLOYEES goes LAST, and that order is not cosmetic: the feed rewrites the
# administrator account, so the password authenticating this very run stops
# being valid the moment it is applied. Loading it first is what turned every
# following call into a 401.
# EGALIM_REGIMES before the articles, VAT_RATES before PRICES, a price
# attaching to the regime that carries its rate. FAMILIES after PRODUCTS, a family line naming the articles it holds.
# NOMENCLATURE carries its scheme on the query, alone in doing so: the
# published lines number nodes INSIDE a scheme and never name the scheme.
push_feed "egalim-regimes.csv"                   /feeds/import/EGALIM_REGIMES
push_feed "stores.csv"                           /feeds/import/STORES
push_feed "store-groups.csv"                     /feeds/import/STORE_GROUPS
push_feed "products${SUFFIX}.csv"                /feeds/import/PRODUCTS
push_feed "product-families${SUFFIX}.csv"        /feeds/import/FAMILIES
push_feed "product-category-storages${SUFFIX}.csv" /feeds/import/CATEGORY_STORAGES
push_feed "vat-rates.csv"                        /feeds/import/VAT_RATES
push_feed "prices${SUFFIX}.csv"                  /feeds/import/PRICES
push_feed "budget-groups.csv"                    /feeds/import/BUDGET_GROUPS
push_feed "offers${SUFFIX}.csv"                  /feeds/import/OFFERS
push_feed "hierarchies.csv" "/feeds/import/NOMENCLATURE?scheme=ITM_FR&label=Nomenclature+ITM+France&enseigne=ITM"

echo
echo "Etat des flux moteur (ce que le noeud a livre a imvaluation) :"
curl -s -u "$ADMIN_USER:$ADMIN_PASSWORD" "$HOST/feeds/import/status"; echo

# The referential is in; the people come last. From here on the back office
# answers to the passwords carried by employees.csv, no longer to the
# bootstrap one this script used.
echo
push_feed "employees.csv"                        /feeds/import/EMPLOYEES
echo "Le mot de passe back-office de 'admin' est desormais celui de employees.csv."
