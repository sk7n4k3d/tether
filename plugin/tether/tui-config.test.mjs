import { test } from "node:test"
import assert from "node:assert/strict"
import { OPTIONS, valider, afficher, appliquer, configInitiale } from "./tui-config.ts"

const opt = (cle) => OPTIONS.find((o) => o.cle === cle)

test("les options declarees sont uniques et ont un defaut lisible", () => {
  const cles = OPTIONS.map((o) => o.cle)
  assert.equal(new Set(cles).size, cles.length, "pas de cle en double")
  for (const o of OPTIONS) {
    assert.ok(o.titre, `${o.cle} a un titre`)
    assert.ok(o.description, `${o.cle} a une description`)
  }
})

test("serverUrl passe tel quel, sans contrainte de forme", () => {
  // Une URL trop stricte refuserait des adresses legitimes — un port implicite, un
  // chemin, un `localhost`. La validation de la forme est le travail de l'app, a l'ecran.
  const r = valider(opt("serverUrl"), "https://exemple.fr")
  assert.equal(r.ok, true)
})

test("un nombre invalide est refuse avec une raison lisible", () => {
  const r = valider(opt("minSeconds"), "douze")
  assert.equal(r.ok, false)
  assert.match(r.refus.raison, /nombre entier/i)
})

test("un nombre negatif ou decimal est refuse", () => {
  // Le TUI ne doit pas ecrire "-5" : la resolution le prendrait pour une valeur, et le
  // comportement "negatif = toujours notifier" n'existe pas dans le code.
  for (const saisie of ["-5", "1.5", "1e3", " 12a"]) {
    assert.equal(valider(opt("maxBytes"), saisie).ok, false, `${saisie} doit etre refuse`)
  }
})

test("un nombre valide est normalise (les zeros de devant disparaissent)", () => {
  const r = valider(opt("maxBytes"), "003800")
  assert.equal(r.ok, true)
  assert.equal(r.valeur, "3800")
})

test("vider un champ numerique rend le defaut", () => {
  const r = valider(opt("maxBytes"), "   ")
  assert.equal(r.ok, true)
  assert.equal(r.valeur, "3800")
})

test("vider un champ sans defaut donne une chaine vide", () => {
  // serverUrl n'a pas de defaut : vider doit supprimer, pas reminded "null" ni "0".
  const r = valider(opt("serverUrl"), "")
  assert.equal(r.ok, true)
  assert.equal(r.valeur, "")
})

test("debug accepte les ecritures humaines, refuse le reste", () => {
  for (const saisie of ["oui", "OUI", "1", "yes", "on", "true"]) {
    const r = valider(opt("debug"), saisie)
    assert.equal(r.ok, true, `${saisie} devrait passer`)
    assert.equal(r.valeur, "1", `${saisie} -> 1`)
  }
  for (const saisie of ["peut-etre", "2", "vrai"]) {
    assert.equal(valider(opt("debug"), saisie).ok, false, `${saisie} devrait etre refuse`)
  }
  // « non » doit se normaliser en 0, pas en « non » : la resolution attend un booleen.
  const r = valider(opt("debug"), "non")
  assert.equal(r.ok, true)
  assert.equal(r.valeur, "0")
})

test("un chemin relatif est refuse", () => {
  // Un chemin relatif depend du cwd du serveur — qui n'est pas forcement le repertoire
  // de l'utilisateur. L'accepter en silence donnerait une erreur « fichier introuvable »
  // incomprehensible plus tard.
  assert.equal(valider(opt("vapidPrivateKeyFile"), "cle/vapid.pem").ok, false)
  assert.equal(valider(opt("vapidPrivateKeyFile"), "/etc/cle.pem").ok, true)
  assert.equal(valider(opt("vapidPrivateKeyFile"), "~/cle.pem").ok, true)
})

test("afficher distingue « non reglee » de « reglee a zero »", () => {
  // Le piege : 0 est une valeur legitime pour maxBytes (pas de troncature) et pour
  // debug (journal off). L'afficher comme « non definie » ferait croire a une absence.
  assert.equal(afficher("3800"), "3800")
  assert.equal(afficher(""), "(non définie)")
  assert.equal(afficher(undefined), "(non définie)")
})

test("remettre une option a son defaut la retire du stockage", () => {
  // C'est ce qui fait qu'un reset redonne l'etat « rien de regle » — donc exactement le
  // comportement d'une installation fraiche, et pas un etat intermediaire etrange.
  let c = configInitiale()
  c = appliquer(c, opt("maxBytes"), "500")
  assert.equal(c.maxBytes, "500")
  c = appliquer(c, opt("maxBytes"), "3800")
  assert.equal("maxBytes" in c, false, "la valeur revenue au defaut doit disparaitre")
})

test("appliquer ne mute pas l'objet recu", () => {
  const avant = { maxBytes: "500" }
  const apres = appliquer(avant, opt("serverUrl"), "https://x.fr")
  assert.equal(avant.serverUrl, undefined, "l'objet d'origine ne doit pas changer")
  assert.equal(apres.serverUrl, "https://x.fr")
  assert.equal(apres.maxBytes, "500", "les autres options sont preservees")
})

test("vider une option sans defaut la retire aussi", () => {
  const c = appliquer({ serverUrl: "https://x.fr" }, opt("serverUrl"), "")
  assert.equal("serverUrl" in c, false)
})
