import { test } from "node:test"
import assert from "node:assert/strict"

/**
 * Le `setup` du plugin TUI, avec un contexte factice.
 *
 * ## Ce que ce test attrape
 *
 * Le bug qu'il verrouille s'est produit **sans qu'aucun test ne le voie** : le plugin
 * declarait sa couche de keymap depuis `setup`, alors que `setup` est appele apres un
 * `await` et donc hors de l'arbre de composants. L'appel levait
 * `Keymap.Provider is missing`, et le TUI ne se chargeait pas.
 *
 * Un test qui appelle `setup` avec un contexte factice reproduit exactement la
 * condition : pas de contexte de composants, pas de rendu. Si `setup` touche a
 * `keymap.layer`, il leve ici aussi.
 *
 * ## Ce que le contexte factice ne prouve pas
 *
 * Que le slot soit reellement monte et la couche reellement declaree dans un TUI qui
 * tourne. Ca, seul un vrai TUI le montre. Ce test garantit l'absence de regresion —
 * que personne ne reintroduit un appel direct dans `setup`.
 */

/** Un contexte qui reproduit la forme de l'API, sans aucun composant. */
function contexteFactice(options = {}) {
  const slots = []
  return {
    slots,
    ctx: {
      client: { getConfig: () => ({ baseUrl: "http://127.0.0.1:4096" }) },
      options: {},
      location: undefined,
      theme: {},
      keymap: {
        // Le point du test : si `setup` appelle cela, il **devrait** lever, comme le
        // fait le vrai `Keymap.createLayer` hors de l'arbre. `surLayer` permet
        // d'enregistrer la couche a la place, pour verifier son contenu ensuite.
        layer:
          options.surLayer ??
          (() => {
            throw new Error("Keymap.Provider is missing")
          }),
      },
      storage: {
        store: () => {
          throw new Error("storage indisponible dans ce test")
        },
      },
      ui: {
        slot(claim) {
          slots.push(claim)
          return () => {
            const i = slots.indexOf(claim)
            if (i >= 0) slots.splice(i, 1)
          }
        },
        toast: { show() {} },
        dialog: { show() {}, set() {}, clear() {} },
      },
    },
  }
}

const plugin = (await import("./tui.tsx")).default

test("setup ne declare pas sa couche de keymap hors de l'arbre", () => {
  const { ctx, slots } = contexteFactice()
  // Si `setup` appelait `keymap.layer`, le contexte factice leverait ici.
  const nettoyer = plugin.setup(ctx)
  assert.equal(typeof nettoyer, "function", "setup doit renvoyer une fonction de nettoyage")
})

test("setup réclame un slot app, seul porteur de la declaration", () => {
  const { ctx, slots } = contexteFactice()
  plugin.setup(ctx)
  assert.equal(slots.length, 1, "exactement un slot doit etre reclame")
  assert.equal(slots[0].prepend, "app", "le slot doit etre app, monte partout")
  assert.equal(typeof slots[0].render, "function")
})

test("le slot rend sans rien afficher", () => {
  // Le composant ne porte qu'une declaration. Rendre quoi que ce soit
  // ajouterait de l'interface pour rien : la liste des appareils est deja un dialogue.
  // `layer` neutre : on teste ici **ce que le slot rend**, pas la declaration qu'il
  // porte — c'est le test suivant. Avec le `layer` qui leve, ce test echouerait sur
  // une autre raison que celle qu'il annonce.
  const { ctx, slots } = contexteFactice({ surLayer: () => {} })
  plugin.setup(ctx)
  const rendu = slots[0].render()
  assert.equal(rendu, null, "le slot ne doit rien rendre")
})

test("la couche declaree contient les trois commandes", () => {
  // La declaration vit dans le `render` du slot : c'est le seul moment ou l'arbre
  // existe. On simule ce moment en donnant a `keymap.layer` un enregistreur.
  //
  // `layer` est passe **au `setup`** et non au `render` : le composant est une
  // fermeture sur `ctx`, donc un `this` remplace ne changerait rien.
  let couche = null
  const { ctx, slots } = contexteFactice({ surLayer: (fn) => (couche = fn()) })
  plugin.setup(ctx)
  slots[0].render()
  assert.ok(couche, "la couche doit etre declaree")
  const ids = couche.commands.map((c) => c.id).sort()
  assert.deepEqual(ids, ["tether.config", "tether.devices", "tether.pair"])
  assert.equal(couche.mode, "global", "les commandes doivent etre globales")
  for (const commande of couche.commands) {
    assert.equal(typeof commande.run, "function", `${commande.id} doit etre exécutable`)
  }
})

test("le nettoyage desabonne le slot", () => {
  const { ctx, slots } = contexteFactice()
  const nettoyer = plugin.setup(ctx)
  assert.equal(slots.length, 1)
  nettoyer()
  assert.equal(slots.length, 0, "le slot doit etre libere a la destruction")
})
