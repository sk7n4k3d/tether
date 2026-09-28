#!/usr/bin/env bash
#
# Verifie qu'aucune donnee de l'auteur n'est sur le point d'etre publiee.
#
# ## Pourquoi les motifs ne sont pas dans ce fichier
#
# Le plus evident serait d'ecrire ici le nom d'utilisateur et le domaine, pour que
# l'operateur n'ait rien a taper. Ce fichier serait alors **le seul fichier en faute** du
# depot — et il faut bien le commiter, donc le publier. Un motif sensible stocke dans le
# depot est un motif fuite. Le piege est tendu : le premier controle passe, puis signale
# son propre en-tete a la ligne 1.
#
# Les motifs reels viennent donc de l'environnement :
#
#     TETHER_GREP_FORBIDDEN='<motifs separes par des virgules>' ./scripts/check-publie.sh
#
# ## Les deux formes a couvrir, et pas une seule
#
#   - le compte utilise comme identite machine : `/home/<nom>`
#   - le compte utilise comme adresse : `<nom>@`
#
# Le compte nu, lui, est public : il est dans l'URL du depot, dans le remote git, dans
# la ligne de copyright. Un motif sur le compte entier hurle sur son propre README — et un
# controle qui hurle sur du bruit s'arrete, puis ne protege plus rien.
#
# ## Ce que le script couvre
#
#  - les fichiers **suivis** par git, a HEAD — pas les fixtures d'ecran, qui sont ignorees
#  - l'**historique complet**, avec `git rev-list --all`, parce qu'un secret supprime du
#    dernier commit reste dans l'objet et part au push
#  - un controle separe sur `docs/evidence/`, qui doit rester **non suivi**
#
# Sortie : 0 si tout est propre, 1 sinon. Aucun correctif automatique : un filtre
# qui devine est un filtre qui perd des donnees sans le dire.
set -euo pipefail

cd "$(dirname "$0")/.."

# Les motifs reels. Sans eux, on ne peut rien affirmer : le script le dit et s'arrete.
if [ -z "${TETHER_GREP_FORBIDDEN:-}" ]; then
  echo "TETHER_GREP_FORBIDDEN n'est pas defini." >&2
  echo "  Exemple : TETHER_GREP_FORBIDDEN='utilisateur,\.sk7\.sh,10\.8\.0\.' $0" >&2
  exit 2
fi

# Le motif de recherche de base : tout ce qui n'est ni du code ni du binaire.
# `-I` ecarte le binaire, qui renverrait n'importe quoi.
IFS=',' read -r -a MOTIFS <<< "$TETHER_GREP_FORBIDDEN"

code=0

echo "== 1. Arbre de travail, fichiers suivis =="
for motif in "${MOTIFS[@]}"; do
  [ -z "$motif" ] && continue
  trouve=$(git grep -IlE "$motif" -- . 2>/dev/null || true)
  if [ -n "$trouve" ]; then
    echo "   TROUVE [$motif] :"
    echo "$trouve" | sed 's/^/     /'
    code=1
  else
    echo "   propre [$motif]"
  fi
done

echo
echo "== 2. Historique complet (tous les commits) =="
for motif in "${MOTIFS[@]}"; do
  [ -z "$motif" ] && continue
  # `git log -S` est lent sur un gros depot ; `git grep` sur chaque commit l'est
  # davantage. On passe par `git rev-list` + `git grep` une seule fois, et on compte.
  trouve=$(git rev-list --all | while read -r c; do
    git grep -IlE "$motif" "$c" -- . 2>/dev/null || true
  done | sort -u | head -5 || true)
  if [ -n "$trouve" ]; then
    echo "   TROUVE [$motif] dans l'historique (exemples) :"
    echo "$trouve" | sed 's/^/     /'
    code=1
  else
    echo "   propre [$motif]"
  fi
done

echo
echo "== 3. Les captures d'ecran ne doivent pas etre suivies =="
suivis=$(git ls-files docs/evidence 2>/dev/null | head -5 || true)
if [ -n "$suivis" ]; then
  echo "   TROUVE — docs/evidence/ est versionne alors qu'il contient des sessions reelles :"
  echo "$suivis" | sed 's/^/     /'
  code=1
else
  echo "   propre — docs/evidence/ n'est pas suivi"
fi

echo
if [ "$code" -eq 0 ]; then
  echo "Tout est propre. Publication possible."
else
  echo "Fuite detectee. Publication **interdite** tant que ce n'est pas corrige."
fi
exit "$code"
