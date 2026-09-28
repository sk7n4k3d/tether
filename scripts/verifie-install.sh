#!/usr/bin/env bash
#
# Verifie que le plugin est installable **et que son TUI se charge**.
#
# ## Pourquoi ce fichier existe a part
#
# L'echec qu'il doit attraper a ete invisible a tous les autres controles :
#
#   - les tests unitaires n'importent **jamais** `tui.tsx` — `node:test` ne lit pas le
#     JSX, ce qui est la raison pour laquelle la logique est dans `tui-logic.ts` ;
#   - `plugin list` affiche le plugin **avant** que le TUI ne le charge ;
#   - le serveur demarre sans erreur, parce que le module TUI n'est charge qu'a
#     l'ouverture du client ;
#   - et le `tsconfig.json` du plugin ne pouvait pas fonctionner la ou Bun lit le paquet.
#
# Aucun de ces controles ne **charge** le module. C'est ce que fait celui-ci, dans un
# sandbox jetable, avec le vrai transpiler de Bun.
#
# ## Le sandbox
#
# `OPENCODE_CONFIG_DIR` — pas `XDG_CONFIG_HOME`. C'est lui que lit la commande
# (\`Global.acquire\` : \`process.env.OPENCODE_CONFIG_DIR ?? Path.config\`). Le cache
# d'installation separe n'est pas dessous : \`XDG_CACHE_HOME\`.
#
# La config reelle est comparee **avant et apres**, octet par octet. C'est le seul test
# qui dit si l'outillage est sur ou pas.
set -euo pipefail

cd "$(dirname "$0")/.."
RACINE="$PWD"

# ---------------------------------------------------------------------------
# Refus explicite de s'executer sur la machine de travail.
#
# Ce script **installe un plugin**. L'opencode d'un poste de travail a une
# configuration, des plugins, et des sessions que la personne ne perd pas de temps
# a reconstruire. L'ecrire — meme dans un sandbox — part d'une commande dont la
# cible est la config **globale** : c'est exactement l'incident qu'un test bien intentionne
# provoque.
#
# J'ai fait l'erreur deux fois : la premiere en installant le plugin dans l'opencode de
# l'utilisateur pour verifier qu'il se chargeait, la seconde en lançant ce script
# depuis son poste alors que le sandbox d'un titan etait disponible.
#
# Un garde-fou qui demande d'yReflect : « et si je me trompais de machine ? ». Ce
# controle la reponse, parce qu'un commentaire ne le fait pas.
# ---------------------------------------------------------------------------
REFUS_TITRE="Ce script ne doit pas tourner ici."
if [ -z "${TETHER_SANDBOX_ALLOW:-}" ]; then
  if [ -f "$HOME/.config/opencode/opencode.jsonc" ] || [ -f "$HOME/.config/opencode/opencode.json" ]; then
    echo "  $REFUS_TITRE" >&2
    echo >&2
    echo "  L'opencode de cet utilisateur existe ici, donc ce script installerait" >&2
    echo "  dans sa configuration reelle. Ce n'est pas un sandbox : l'ecriture" >&2
    echo "  est globale." >&2
    echo >&2
    echo "  Pour forcer, sur une machine de test assumee :" >&2
    echo "      TETHER_SANDBOX_ALLOW=1 ./scripts/verifie-install.sh" >&2
    echo >&2
    echo "  L'option previent, elle n'autorise pas. Si vous etes sur le poste de" >&2
    echo "  travail, le bon reflexe reste de ne pas lancer." >&2
    exit 3
  fi
fi
REPO="${TETHER_REPO:-git+https://github.com/sk7n4k3d/tether.git}"
SANDBOX="$(mktemp -d -t tether-iso-XXXXXX)"
REAL_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/opencode/opencode.jsonc"
BUN="${BUN:-bun}"

TEMOIN="$SANDBOX/config-reelle.avant"
[ -f "$REAL_CONFIG" ] && cp "$REAL_CONFIG" "$TEMOIN" || touch "$TEMOIN"

nettoyer() { rm -r -- "$SANDBOX"; }
trap nettoyer EXIT

export OPENCODE_CONFIG_DIR="$SANDBOX/config/opencode"
export XDG_CACHE_HOME="$SANDBOX/cache"
mkdir -p "$OPENCODE_CONFIG_DIR" "$XDG_CACHE_HOME"

command -v "$BUN" >/dev/null || { echo "bun est requis : c'est lui qui transpile le .tsx"; exit 2; }

echo "== 1. L'isolation tient-elle ? =="
# ⚠️ On ne mesure **pas** `plugin list` : opencode fusionne le repertoire
# `plugins/` global avec la config locale, donc la liste contient les plugins de
# l'utilisateur meme en sandbox. Ce n'est pas une fuite de config — c'est une
# decouverte de plugins par repertoire, qui n'ecrit rien.
#
# Ce qui compte, et qu'on mesure vraiment : **l'ecriture**. Si l'isolation tient,
# `plugin add` ecrit dans le sandbox et la config reelle reste intacte. C'est le
# test 4, et il est en byte-comparaison — pas une heuristique sur une sortie.
if [ -f "$OPENCODE_CONFIG_DIR/opencode.jsonc" ] || [ -f "$OPENCODE_CONFIG_DIR/opencode.json" ]; then
  echo "   ECHEC : la config du sandbox existe deja avant toute ecriture."
  exit 1
fi
echo "   sandbox vierge, config reelle intacte"

echo
echo "== 2. Installation =="
opencode plugin add "$REPO" 2>&1 | sed 's/^/   /'

# ⚠️ `plugin list` a deja omis un plugin fraichement installe : un « tether absent »
# mesure une fois n'est pas une preuve. On redonne sa chance, et on ne conclut
# qu'apres plusieurs lectures.
TROUVE=""
for _ in 1 2 3 4 5 6; do
  sleep 2
  if opencode plugin list 2>&1 | grep -qi tether; then TROUVE=oui; break; fi
done
if [ -n "$TROUVE" ]; then
  opencode plugin list 2>&1 | grep -i tether | sed 's/^/   /'
else
  echo "   ECHEC : tether absent de la liste apres 6 lectures."
  exit 1
fi

echo
echo "== 3. Le module TUI se charge-t-il vraiment ? =="
# C'est le test que rien d'autre ne fait. On **importe** le point d'entree du TUI avec
# le transpiler de Bun, depuis le paquet reellement installe. Une erreur JSX, un module
# manquant, une pragma absente : tout sort ici, en clair.
PKG=$(find "$XDG_CACHE_HOME" -type d -name tether -path "*/node_modules/tether" 2>/dev/null | head -1)
if [ -z "$PKG" ]; then
  echo "   ECHEC : paquet installe introuvable sous $XDG_CACHE_HOME"
  exit 1
fi
echo "   paquet : ${PKG#"$SANDBOX"/}"

# La source JSX est declaree par une pragma en tete de `tui.tsx`, pas par un
# `tsconfig.json` : le paquet installe vit sous `node_modules`, ou Bun ne lit pas de
# tsconfig (mesure au commit 80f9156). Sans la pragma, Bun transpille en React.
if grep -q '@jsxImportSource @opentui/solid' "$PKG/plugin/tether/tui.tsx" 2>/dev/null; then
  echo "   pragma JSX presente dans tui.tsx"
else
  echo "   ECHEC : aucune pragma @jsxImportSource dans tui.tsx. Bun transpille en React —"
  echo "            exactement l'erreur qu'on cherche a attraper."
  exit 1
fi

RESULTAT=$("$BUN" -e "
  import(process.argv[1]).then(
    (m) => console.log('OK exports=' + Object.keys(m).join(',')),
    (e) => console.log('ERREUR ' + e.message.split('\n')[0]),
  )
" "file://$PKG/plugin/tether/tui.tsx" 2>&1 | tail -1)
echo "   import du point d'entree TUI : $RESULTAT"

case "$RESULTAT" in
  OK*) ;;
  *)
    echo
    echo "   ECHEC — le module TUI ne se charge pas. C'est l'erreur que l'utilisateur voit."
    echo "   Message : $RESULTAT"
    exit 1
    ;;
esac

echo
echo "== 4. La config REELLE a-t-elle ete touchee ? =="
if ! cmp -s "$TEMOIN" "$REAL_CONFIG" 2>/dev/null; then
  echo "   ECHEC : la config reelle a ete MODIFIEE. Restauration depuis $TEMOIN"
  cp "$TEMOIN" "$REAL_CONFIG"
  exit 1
fi
echo "   inchangee"

echo
echo "== 5. Le depot de travail est-il propre ? =="
if [ -n "$(git status --porcelain)" ]; then
  git status --short | sed 's/^/     /'
else
  echo "   propre"
fi

echo
echo "Le module TUI se charge, et la config reelle n'a pas ete touchee."
echo "Reste non verifie : l'affichage du dialogue dans un vrai TUI, sur un terminal."
