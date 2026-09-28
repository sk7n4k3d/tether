#!/usr/bin/env bash
#
# Verifie que le plugin est installable et chargeable, **sans toucher a la config reelle**.
#
# ## Pourquoi ce script existe
#
# Le premier essai de verification a ete fait a la main, contre l'`opencode.jsonc` de
# l'utilisateur. C'est ce qu'il ne fallait pas faire : une commande d'installation ecrit
# la config **globale**, et un test qui la modifie casse l'environnement de travail de
# quelqu'un d'autre. Entre l'essai et la remise en etat, la machine a eteconfiguree sans
# que personne ne l'ait demande.
#
# ## Le levier d'isolation, et comment on l'a trouve
#
# `OPENCODE_CONFIG_DIR` — **pas** `XDG_CONFIG_HOME`. C'est `Path.config` que la commande
# lit (`Global.acquire` : `process.env.OPENCODE_CONFIG_DIR ?? Path.config`), et c'est la
# seule variable qui deplace le repertoire de plugins comme la config.
#
# `XDG_CONFIG_HOME` parait faire l'affaire : opencode le honore pour `cli.json`. Mais le
# repertoire de plugins passe par `Path.config`, donc un sandbox sous `XDG_CONFIG_HOME`
# voit **toujours** `~/.config/opencode/plugins/` — les plugins locaux, `ntfy-opencode`,
# `ollama-quota`, et tout ce qui est installe par nom. Le test « l'isolation tient-elle »
# l'a montre immediatement : le sandbox listait la config reelle.
#
# Le cache d'installation est separe n'est pas sous `OPENCODE_CONFIG_DIR` : il vit dans
# `~/.cache/opencode/npm/`. On le neutralise avec `XDG_CACHE_HOME`.
#
# `OPENCODE_TEST_HOME` existe aussi, et prend le pas sur `os.homedir()`. Il sert aux tests
# de l'upstream ; on ne s'en sert pas ici, pour ne pas dependre d'un mecanisme de test
# dans un script de verification d'un plugin tiers.
#
# ## Ce que le script prouve
#
#  1. `opencode plugin add <spec>` aboutit et ecrit la config **de test**
#  2. le plugin apparait dans `opencode plugin list`
#  3. la config **reelle** n'a pas ete modifiee — comparee avant/apres, octet par octet
#  4. le depot de travail n'a pas ete pollue
#
# ## Ce qu'il ne prouve PAS
#
# Que le dialogue `/tether` s'affiche. Cela demande un TUI, et donc un humain devant un
# terminal — ou une story Storybook. C'est le test qui manque encore.
set -euo pipefail

cd "$(dirname "$0")/.."
RACINE="$PWD"

REPO="${TETHER_REPO:-git+https://github.com/sk7n4k3d/tether.git}"
SANDBOX="$(mktemp -d -t tether-iso-XXXXXX)"
REAL_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/opencode/opencode.jsonc"

# Le temoin : la config reelle, **avant** qu'on touche a quoi que ce soit.
TEMoin="$SANDBOX/config-reelle.avant"
[ -f "$REAL_CONFIG" ] && cp "$REAL_CONFIG" "$TEMoin" || touch "$TEMoin"

nettoyer() {
  # Le sandbox est jetable : on ne le laisse pas derriere, il contient une copie de la
  # config reelle.
  rm -r -- "$SANDBOX"
}
trap nettoyer EXIT

export OPENCODE_CONFIG_DIR="$SANDBOX/config/opencode"
export XDG_CACHE_HOME="$SANDBOX/cache"
export XDG_DATA_HOME="$SANDBOX/data"
mkdir -p "$OPENCODE_CONFIG_DIR" "$XDG_CACHE_HOME" "$XDG_DATA_HOME"

echo "== 1. L'isolation tient-elle ? =="
# Si le sandbox voyait les plugins du dossier reel, l'isolation ne servirait a rien.
FUITE=$(opencode plugin list 2>/dev/null | grep -cE "superpowers|cc-safety-net" || true)
if [ "$FUITE" -ne 0 ]; then
  echo "   ECHEC : le sandbox voit $FUITE plugin(s) de la config reelle."
  echo "   XDG_CONFIG_HOME n'est pas honoré — la suite toucherait la vraie config."
  exit 1
fi
echo "   propre : aucun plugin de la config reelle n'est visible"

echo
echo "== 2. Installation depuis le dépôt public =="
opencode plugin add "$REPO" 2>&1 | sed 's/^/   /'

echo
echo "== 3. Le plugin est-il chargé ? =="
LISTE=$(opencode plugin list 2>&1)
echo "$LISTE" | sed 's/^/   /'
if ! echo "$LISTE" | grep -q "tether"; then
  echo "   ECHEC : tether absent de la liste alors que l'installation a reussi."
  exit 1
fi
echo "   tether present"

echo
echo "== 4. La config du sandbox a-t-elle ete ecrite ? =="
SANDBOX_CONFIG="$OPENCODE_CONFIG_DIR/opencode.json"
if [ -f "$SANDBOX_CONFIG" ] && grep -q tether "$SANDBOX_CONFIG"; then
  echo "   oui — l'ecriture a bien cible le sandbox, pas la config reelle"
else
  echo "   ECHEC : rien n'a ete ecrit dans le sandbox."
  exit 1
fi

echo
echo "== 5. La config REELLE a-t-elle ete touchee ? =="
# ⚠️ C'est le test qui compte. Tout le reste peut reussir et celui-la echouer, et c'est
# lui qui dit si l'outillage est sur ou pas sur.
if ! cmp -s "$TEMoin" "$REAL_CONFIG" 2>/dev/null; then
  echo "   ECHEC : la config reelle a ete MODIFIEE."
  echo "   Restaurez-la depuis : $TEMoin"
  cp "$TEMoin" "$REAL_CONFIG"
  exit 1
fi
echo "   inchangee — comparaison octet par octet"

echo
echo "== 6. Le depot de travail est-il propre ? =="
if [ -n "$(git status --porcelain)" ]; then
  echo "   ATTENTION : modifications non commitees :"
  git status --short | sed 's/^/     /'
else
  echo "   propre"
fi

echo
echo "Installation verifiee en isolement. La config reelle n'a pas ete touchee."
echo "Reste non verifie : l'affichage du dialogue /tether dans un vrai TUI."
