import { test } from "node:test"
import assert from "node:assert/strict"

import {
  nomAppareil,
  distributeurLisible,
  depuis,
  compteARebours,
  seuilDeRafraichissement,
  optionsAppareils,
  AUCUN_APPAREIL,
} from "./ui-model.ts"

const pixel = {
  deviceId: "pixel-8-a1b2c3",
  label: "Pixel 8",
  distributor: "org.unifiedpush.distributor.ntfy",
  registeredAt: 1_700_000_000_000,
}

test("le nom affiche vient du label, et tombe sur l'identifiant a defaut", () => {
  assert.equal(nomAppareil(pixel), "Pixel 8")
  assert.equal(nomAppareil({ ...pixel, label: undefined }), "pixel-8-a1b2c3")
  assert.equal(nomAppareil({ ...pixel, label: "   " }), "pixel-8-a1b2c3")
  // Un appareil totalement anonyme reste trouvable, plutot que de disparaitre.
  assert.equal(nomAppareil({ deviceId: "", registeredAt: 0 }), "(sans identifiant)")
})

test("le distributeur est abrege en nom lisible", () => {
  assert.equal(distributeurLisible(pixel), "ntfy")
  assert.equal(distributeurLisible({ ...pixel, distributor: "org.unifiedpush.distributor.sunup" }), "sunup")
  // Conversations n'a pas de point dans le nom de paquet : il ne doit pas disparaitre.
  assert.equal(distributeurLisible({ ...pixel, distributor: "Conversations" }), "Conversations")
  assert.equal(distributeurLisible({ ...pixel, distributor: undefined }), "distributeur inconnu")
})

test("l'anciennete passe des secondes aux jours", () => {
  const base = 1_700_000_000_000
  const ilYA = (ms) => depuis(pixel, base + ms)
  assert.equal(ilYA(5_000), "il y a 5 s")
  assert.equal(ilYA(59_000), "il y a 59 s")
  assert.equal(ilYA(60_000), "il y a 1 min")
  assert.equal(ilYA(59 * 60_000), "il y a 59 min")
  assert.equal(ilYA(60 * 60_000), "il y a 1 h")
  assert.equal(ilYA(23 * 3_600_000), "il y a 23 h")
  assert.equal(ilYA(24 * 3_600_000), "il y a 1 j")
  // Une horloge en retard ne doit pas afficher un age negatif.
  assert.equal(ilYA(-10_000), "il y a 0 s")
})

test("le compte a rebours se lit, et dit l'heurstament quand c'est fini", () => {
  const base = 1_700_000_000_000
  assert.equal(compteARebours(base + 30 * 60_000, base), "expire dans 30 min 00 s")
  assert.equal(compteARebours(base + 125_000, base), "expire dans 2 min 05 s")
  assert.equal(compteARebours(base + 9_000, base), "expire dans 9 s")
  assert.equal(compteARebours(base, base), "expiré — relancez l'appairage")
  assert.equal(compteARebours(base - 1, base), "expiré — relancez l'appairage")
})

test("le rafraichissement ne se declenche que sur un changement de minute", () => {
  // C'est ce qui evite un dialogue qui scintille : le TUI rerend a chaque etat change.
  const base = 1_700_000_000_000
  const dans = (ms) => seuilDeRafraichissement(base + ms, base)
  assert.equal(dans(30 * 60_000), 30)
  assert.equal(dans(125_000), 2)
  assert.equal(dans(59_000), 0)
  // Dans la meme minute affichee, le seuil ne bouge pas — donc rien a rerendre.
  assert.equal(dans(125_000), dans(121_000))
  // Un changement de minute, si.
  assert.notEqual(dans(125_000), dans(115_000))
  assert.equal(dans(-1), 0, "un jeton expire ne demande aucun rafraichissement")
})

test("les appareils sont listes du plus ancien au plus recent", () => {
  const autres = [
    { deviceId: "tablette", label: "Tablette", registeredAt: 1_600_000_000_000 },
    { deviceId: "watch", label: "Montre", registeredAt: 1_650_000_000_000 },
  ]
  const options = optionsAppareils([pixel, ...autres])
  assert.deepEqual(
    options.map((o) => o.value),
    ["tablette", "watch", "pixel-8-a1b2c3"],
  )
  assert.equal(options[0].title, "Tablette")
  assert.match(options[2].description, /ntfy · /)
})

test("une liste vide ne casse pas, et le dit", () => {
  assert.deepEqual(optionsAppareils([]), [])
  assert.equal(AUCUN_APPAREIL.includes("/tether"), true, "le message dit quoi faire ensuite")
})
