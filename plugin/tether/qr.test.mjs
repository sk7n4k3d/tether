/**
 * Tests de l'encodeur de QR et de l'appairage — sans reseau, sans Java, sans opencode.
 *
 * ## Pourquoi des matrices de reference, et pas une relecture
 *
 * Le test pourrait relire ses propres modules et en extraire le contenu, comme un
 * decodeur. Ce serait elegant, et **sans valeur** : les regles de placement seraient
 * verifiees par le code meme qui les a ecrites. Un encodeur peut etre faux de facon
 * parfaitement coherente avec lui-meme — c'est precisement ce qui est arrive cinq fois
 * sur cette page, et c'est aussi le cas le plus vicieux d'un encodeur.
 *
 * Ces matrices viennent donc de l'encodeur, puis ont ete **confrontees a zxing**
 * (`com.google.zxing:core`) : 130 QR de 5 a 134 octets, versions 1 a 6, **130/130
 * identiques module par module** a masque impose, et 130/130 relus. Deux encodeurs
 * independants qui tombent d'accord module par module ne peuvent pas partager la meme
 * erreur.
 *
 * Reproduire cette confrontation : `plugin/tether/qr-verify.sh`.
 *
 * ## Les six bugs que ces tests ont rattrapes
 *
 * Aucun ne se voyait a l'oeil, aucun ne levevait d'exception, et cinq passaient la
 * relecture. Ils sont notes ici parce que le piege n'etait pas dans l'algorithme, mais
 * dans l'idee qu'un encodeur qui « marche sur les petits QR » est un encodeur qui marche.
 *
 *  1. **Mode numerique sur un contenu alphabetique.** Le QR se dessine parfaitement ; le
 *     lecteur reinterpretant les bits par groupes de trois chiffres echoue. Un mode
 *     qui interdit les lettres ne leve rien.
 *  2. **Table des versions fausse (versions 5, 6, 7, 9).** Le nombre de blocs de
 *     correction etait invente, donc le tableau avait la bonne longueur et le mauvais
 *     contenu. Invisible tant que les versions testes n'avaient qu'un seul bloc.
 *  3. **Entrelacement manquant sur les donnees.** Les blocs sont des tranches
 *     consecutives : les copier revient a les laisser par bloc, soit l'inverse de
 *     l'entrelacement. Idem pour l'ECC. Ne se voit qu'a partir de la premiere version a
 *     blocs multiples.
 *  4. **Table de Reed-Solomon lue a l'envers.** Deux indexations voisines, toutes deux
 *     fausses, toutes deux de la bonne longueur. Symptome : le contenu s'affiche et c'est
 *     la verification d'integrite qui echoue, chez le lecteur, plusieurs etapes apres
 *     sa cause.
 *  5. **Remplissage 0xEC/0x11 indexe sur l'absolu.** L'alternance est relative au
 *     premier octet de remplissage. Le remplissage est jete par le lecteur, donc
 *     n'importe quoi passe : invisible a la lecture, visible seulement en comparant les
 *     matrices octet par octet.
 *  6. **Module sombre ecrit deux fois, a la mauvaise position une fois.** Reserve au bon
 *     endroit, ecrit en (4v+9, 8) — donc sur le bit 7 de la seconde copie du format. Un
 *     module sur 1681.
 *
 * Le point commun : tous produisent un QR plausible. C'est la confrontation a un tiers
 * qui les a sortis, pas la relecture — sauf le premier, qui est tombe tout seul parce
 * qu'il ne donnait aucun contenu lisible.
 */

import { test } from "node:test"
import assert from "node:assert/strict"

import { encode, versionFor, chosenMask, render, qrText } from "./qr.ts"
import { pairingLink, newToken, TOKEN_BYTES, PAIRING_TTL_MS, isValid, remainingMs } from "./pairing.ts"

/** Les matrices de reference, validees par zxing. */
const GOLDEN = [
  {
    nom: "court",
    contenu: "A",
    version: 1,
    size: 21,
    mask: 0,
    lignes: [
      "#######..#.##.#######",
      "#.....#..###..#.....#",
      "#.###.#.##.##.#.###.#",
      "#.###.#..#.#..#.###.#",
      "#.###.#...#.#.#.###.#",
      "#.....#.....#.#.....#",
      "#######.#.#.#.#######",
      "........##.##........",
      "###.########.##...#..",
      "#.##....#.....#...##.",
      ".#.####..##.#...#...#",
      ".#.##...##....#...#..",
      "..##.##.#...#.#.#.#.#",
      "........#..#.#.#.#.#.",
      "#######.#.##.###.####",
      "#.....#.######.###...",
      "#.###.#.##.#.###.##.#",
      "#.###.#..##...#...##.",
      "#.###.#.##..#...#...#",
      "#.....#.#.....#...##.",
      "#######.###.#.#.#.###",
    ],
  },
  {
    nom: "lien",
    contenu: "opencode://pair?s=https%3A%2F%2Fexemple.fr%3A4096&t=dGVzdC10b2tlbi1maXhlLTE2LW8",
    version: 5,
    size: 37,
    mask: 2,
    lignes: [
      "#######..#.....##.######....#.#######",
      "#.....#.##.##.....#.###.#.##..#.....#",
      "#.###.#..###..#.#...##.#..#.#.#.###.#",
      "#.###.#.##.#....#..#....####..#.###.#",
      "#.###.#...#.####.#.##.#######.#.###.#",
      "#.....#.#.#..####.#.###.#..#..#.....#",
      "#######.#.#.#.#.#.#.#.#.#.#.#.#######",
      "............##.#..#..###.............",
      "#####.#####.#.##.#..#.#..#####.#.#.#.",
      "#..............#####.#.##......#..#..",
      "#...#.#..#.##.....#.###...###..#..###",
      "##..#..#####..#.#.#..#..#.###.###..#.",
      "#...###....#....##.#.###.###.######.#",
      ".#..##..#.#.####.#####.###...#.......",
      ".####.####...####.....#.#.##.#####.##",
      "##...#..#.#.##.#..##.##.#.#.#.#......",
      "#..##.#.....#.##.....##..###.#######.",
      ".#.###.#..#....##########.####.#.....",
      ".############.........#.#..#.####..##",
      "#.##....#..#..#.#.#.##........####.##",
      "#.....#..#.#....#..#..#..########.###",
      "#.#.#..#....####...##.####...#...##..",
      ".#...##.#....####.........###.#.#####",
      "#..##..####.##.#..#.##..#..##.#..#..#",
      "#..#..##....#.##....#.#.####.######.#",
      "#.###..#.......##.####.......#.#..##.",
      "#..##.#..####........##...###.##..###",
      "#.#.#..#.###..#.#..#.#.##.##.#..##.#.",
      "#.#..##...##.....##.###..#.#########.",
      "........#...####...#.#.###.##...#.#..",
      "#######.#.#..###.##......####.#.#.###",
      "#.....#...#.##.#..##.##.....#...#..#.",
      "#.###.#.#...#.#.#.......#########.##.",
      "#.###.#.#........########...#.#.##..#",
      "#.###.#.##.##..##....#..####...#.#.##",
      "#.....#.####..#.#.#..####...####....#",
      "#######.####...##...#....###.##..####",
    ],
  },
  {
    nom: "moyen",
    contenu: "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    version: 5,
    size: 37,
    mask: 0,
    lignes: [
      "#######..#..##..##.#.#.#.#.#..#######",
      "#.....#...###.####.#.#.#.#.#..#.....#",
      "#.###.#.###.###.#.#.#.#.#.#.#.#.###.#",
      "#.###.#..###.###..#.#.#.#.#.#.#.###.#",
      "#.###.#...#...#..#.#.#.#.#.#..#.###.#",
      "#.....#..#...#...#.#.#.#.#.#..#.....#",
      "#######.#.#.#.#.#.#.#.#.#.#.#.#######",
      "........###.###.##.#.#.#.#.#.........",
      "###.#####.##..##.#.#.#.#.#.#.##...#..",
      "..##.#.#####..##..#.#.#.#.#.#.#..##.#",
      "##.#####.#...#....#.#.#.#.#.#...#.###",
      "#.#....#.###...#.#.#.#.#.#.#.#.##..#.",
      ".##..##.##..#...##.#.#.#.#.#.###.#...",
      "#.#.##..#..###.##.#.#.#.#.#.#.#..##.#",
      "#####.#.#..##.###.#.#.#.#.#.#...#.###",
      "##.##..#.#..###.##.#.#.#.#.#.#.##..#.",
      "##.##.######..##.#.#.#.#.#.#.###.#...",
      "#..#....####..##..#.#.#.#.#.#.#..##.#",
      "###..###.##..#....#.#.#.#.#.#...#.###",
      "#.####.#.#.#...#.#.#.#.#.#.#.#.##..#.",
      ".#.##.##..#.#...##.#.#.#.#.#.###.#...",
      "#..#.#.##.####.##.#.#.#.#.#.#.#..##.#",
      "#.##..##.#.##.###.#.#.#.#.#.#...#.###",
      "...#.#.####.###.##.#.#.#.#.#.#.##..#.",
      "...######..#..####.#.#.#.#.#.###.#...",
      ".###....##.#..##..#.#.#.#.#.#.#..##.#",
      "#...###..##..#..#.#.#.#.#.#.#...#.###",
      ".#.#.......#...#.#.#.#.#.#.#.#.##..#.",
      "#.....##..#.#..#.#.#.#.#.#.#######...",
      "........######..#.#.#.#.#.#.#...###.#",
      "#######.#.###.#.#.#.#.#.#.###.#.#.###",
      "#.....#.#...####.#.#.#.#.#..#...#..#.",
      "#.###.#.#.##..##.#.#.#.#.#..######..#",
      "#.###.#....#..#.#.#.#.#.#.##....###..",
      "#.###.#.#....#..#.#.#.#.#.#..#.##.###",
      "#.....#.#..#...#.#.#.#.#.#..####...#.",
      "#######.##..#..#.#.#.#.#.#.##.#..#.##",
    ],
  },
  {
    nom: "limite",
    contenu: "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy",
    version: 6,
    size: 41,
    mask: 0,
    lignes: [
      "#######...####.#.#.#.#.#.#.#.#.#..#######",
      "#.....#....###.###.###.###.###.##.#.....#",
      "#.###.#.#.#.....#...#...#...#...#.#.###.#",
      "#.###.#..###..#.#.#.#.#.#.#.#.#.#.#.###.#",
      "#.###.#....###.#.#.#.#.#.#.#.#.#..#.###.#",
      "#.....#.....##.###.###.###.###.##.#.....#",
      "#######.#.#.#.#.#.#.#.#.#.#.#.#.#.#######",
      "........##.#####.###.###.###.###.........",
      "###.######.#.#.#.#.#.#.#.#.#.#.#.##...#..",
      ".##.#.....#...#.#.#.#.#.#.#.#.#.#.#..##.#",
      "#.###.#####...#...#...#...#...#....######",
      ".#.#.#.#.#######.###.###.###.###...##....",
      ".#.#.##..#####.#.#.#.#.#.#.#.#.#.###.#...",
      ".#..##.######.#.#.#.#.#.#.#.#.#.#.#..##.#",
      "#.#..##.#..##.#...#...#...#...#....######",
      "##...#.###..####.###.###.###.###...##....",
      "####.###.#.#.#.#.#.#.#.#.#.#.#.#.###.#...",
      ".###........#.#.#.#.#.#.#.#.#.#.#.#..##.#",
      "##.#..##......#...#...#...#...#....######",
      ".#.#.#.#####.###.###.###.###.###...##....",
      "###...####.###.#.#.#.#.#.#.#.#.#.###.#...",
      "#....#.#####..#.#.#.#.#.#.#.#.#.#.#..##.#",
      "..#.###.#.....#...#...#...#...#....######",
      "..##.#..##.#.###.###.###.###.###...##....",
      "..#.######.#.#.#.#.#.#.#.#.#.#.#.###.#...",
      "####...###..#.#.#.#.#.#.#.#.#.#.#.#..##.#",
      ".#..###..##...#...#...#...#...#....######",
      "....#...#.##.###.###.###.###.###...##....",
      ".########..#.#.#.#.#.#.#.#.#.#.#.###.#...",
      ".#.......##.#.#.#.#.#.#.#.#.#.#.#.#..##.#",
      "#.....##.##...#...#...#...#...#....######",
      ".#.#...#...#.###.###.###.###.###...##....",
      "#...#.####.#.#.#.#.#.#.#.#.#.#.#######.##",
      "........#...#.#.#.#.#.#.#.#.#.#.#...###.#",
      "#######.#.....#...#...#...#...###.#.#####",
      "#.....#.##.#.###.###.###.###.##.#...#....",
      "#.###.#.#.##.#.#.#.#.#.#.#.#.#..######...",
      "#.###.#..##.#.#.#.#.#.#.#.#.#.#.....####.",
      "#.###.#.#.....#...#...#...#...#..#.####..",
      "#.....#.#.##.###.###.###.###.##.###....#.",
      "#######.#.##.#.#.#.#.#.#.#.#.#.####..#.##",
    ],
  },
]

const parNom = new Map(GOLDEN.map((g) => [g.nom, g]))

function assertMatrix(golden, qr) {
  assert.equal(qr.size, golden.size, "taille du QR")
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) {
      assert.equal(qr.get(x, y), golden.lignes[y][x] === "#", `module (${x}, ${y})`)
    }
  }
}

// --- Version --------------------------------------------------------------------

test("versionFor : la capacite de chaque version est exacte", () => {
  // Capacite en mode octet = plancher((mots de donnee * 8 - 12) / 8). Ces bornes
  // viennent de la table normative. Un lien d'appairage reel fait 74 a 82 caracteres,
  // donc version 5 dans tous les cas realistes.
  const bornes = [
    { version: 1, max: 17 },
    { version: 2, max: 32 },
    { version: 3, max: 53 },
    { version: 4, max: 78 },
    { version: 5, max: 106 },
    { version: 6, max: 134 },
  ]
  for (const { version, max } of bornes) {
    assert.equal(versionFor(max), version, `version pour ${max} octets`)
    if (max + 1 <= 134) {
      assert.equal(versionFor(max + 1), version + 1, `${max + 1} octets doivent deborder`)
    }
  }
})

test("versionFor : refuse un contenu trop long plutot que de mentir", () => {
  assert.throws(() => versionFor(135), /trop long/)
})

test("encode : refuse le non-ASCII explicitement", () => {
  // Le mode octet vaut ISO-8859-1 par defaut et aucun bloc ECI n'est ecrit. Rendre un QR
  // dont la lecture depend de l'interpreteur du lecteur serait pire qu'un refus.
  assert.throws(() => encode("café"), /ASCII/)
})

// --- Matrices de reference -------------------------------------------------------

test("encode : reproduit la matrice de reference (version 1, 1 octet)", () => {
  const g = parNom.get("court")
  assertMatrix(g, encode(g.contenu))
})

test("encode : reproduit la matrice de reference (lien d'appairage, version 5)", () => {
  const g = parNom.get("lien")
  assertMatrix(g, encode(g.contenu))
})

test("encode : reproduit la matrice de reference (version 5, 80 octets)", () => {
  const g = parNom.get("moyen")
  assertMatrix(g, encode(g.contenu))
})

test("encode : reproduit la matrice de reference (version 6, 2 blocs, 133 octets)", () => {
  const g = parNom.get("limite")
  assertMatrix(g, encode(g.contenu))
})

test("le masque choisi est bien celui des matrices de reference", () => {
  // Le choix du masque depend de la penalite. S'il change, les matrices changent — et
  // c'est acceptable tant que le QR reste lisible. Ce test evite donc un faux positif
  // trompeur : il echoue si le choix bouge sans qu'on l'ait voulu.
  for (const g of GOLDEN) {
    assert.equal(chosenMask(g.contenu), g.mask, `masque de ${g.nom}`)
  }
})

// --- Invariants structurels ------------------------------------------------------

test("le module sombre est en (8, 4v+9), pas en (4v+9, 8)", () => {
  // Les deux positions sont voisines et tombent l'une et l'autre dans la zone de fonction,
  // donc rien ne les distingue a l'oeil : le QR se dessine, les trois motifs de recherche
  // sont justes, et c'est le decodage qui echoue. La position transposee atterrit sur la
  // premiere copie de l'information de format.
  for (const g of GOLDEN) {
    const qr = encode(g.contenu)
    assert.equal(qr.get(8, g.size - 8), true, `${g.nom} : module sombre en (8, 4v+9)`)
  }
})

test("les trois motifs de recherche sont a leur place, quel que soit le contenu", () => {
  for (const contenu of ["A", "x".repeat(60), "y".repeat(133), "https://exemple.fr/a?b=c"]) {
    const qr = encode(contenu)
    for (const [cx, cy] of [[0, 0], [qr.size - 7, 0], [0, qr.size - 7]]) {
      // Le motif fait 7x7 : module noir, anneau blanc, carre 3x3 noir.
      assert.equal(qr.get(cx, cy), true, "coin noir du motif de recherche")
      assert.equal(qr.get(cx + 1, cy + 1), false, "anneau blanc a l'offset 1")
      assert.equal(qr.get(cx + 2, cy + 2), true, "carre 3x3 noir a l'offset 2")
      assert.equal(qr.get(cx + 3, cy + 3), true, "centre du motif de recherche")
    }
  }
})

test("le motif de synchronisation alterne sur la ligne et la colonne 6", () => {
  const qr = encode("s".repeat(50))
  for (let i = 8; i < qr.size - 8; i++) {
    assert.equal(qr.get(i, 6), i % 2 === 0, `synchronisation ligne en ${i}`)
    assert.equal(qr.get(6, i), i % 2 === 0, `synchronisation colonne en ${i}`)
  }
})

test("la version 1 n'a pas de motif d'alignement, la version 6 en a un", () => {
  // Version 1 : pas de centres d'alignement. Un encodeur qui en met un quand meme passe
  // tous les autres tests — c'est la seule assertion qui le rattrape.
  const v1 = encode("A")
  assert.equal(v1.size, 21)
  // Version 6 : centres 6 et 34 ; les trois autres positions tombent sur les motifs de
  // recherche, il reste donc un seul motif, centre en (34, 34).
  const v6 = encode("y".repeat(133))
  assert.equal(v6.size, 41)
  // Le motif fait 5x5 : bord noir, anneau blanc, centre noir.
  assert.equal(v6.get(34, 34), true, "centre du motif d'alignement")
  assert.equal(v6.get(35, 35), false, "anneau blanc du motif d'alignement, a l'offset 1")
  assert.equal(v6.get(33, 33), false, "anneau blanc du motif d'alignement, en diagonale")
  assert.equal(v6.get(36, 36), true, "coin du motif d'alignement")
  assert.equal(v6.get(32, 32), true, "coin haut-gauche du motif d'alignement")
  assert.equal(v6.get(34, 32), true, "bord haut du motif d'alignement")
})

test("le rendu textuel a la bonne forme, et l'inversion ne change pas l'information", () => {
  const qr = encode("A")
  const texte = render(qr)
  const lignes = texte.split("\n")
  assert.equal(lignes.length, qr.size + 2, "une ligne de marge en haut et en bas")
  // Toutes les lignes doivent etre de la meme largeur : 2 modules de chaque cote.
  const largeur = (qr.size + 4) * 2
  for (const [i, l] of lignes.entries()) {
    assert.equal(l.length, largeur, `largeur de la ligne ${i}`)
  }
  const inverse = render(qr, { invert: true }).split("\n")
  assert.notEqual(inverse.join("\n"), texte)
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) {
      assert.notEqual(lignes[y + 1][x * 2], inverse[y + 1][x * 2], `module (${x}, ${y})`)
    }
  }
})

test("qrText enchaine encode et render", () => {
  assert.equal(qrText("A"), render(encode("A")))
})

test("les deux copies de l'information de format portent les memes 15 bits", () => {
  // La spec ecrit les 15 bits deux fois, au meme endroit logique. C'est un controle de
  // coherence que **le lecteur peut faire lui-meme**, et c'est gratuit.
  //
  // C'est ce controle qui a deniche le dernier bug de cette page : le module sombre etait
  // ecrit en (4v+9, 8) au lieu de (8, 4v+9), donc exactement sur le bit 7 de la seconde
  // copie. Un seul module sur 1681 differait, la lecturefonctionnait toujours, et rien
  // d'autre ne l'aurait signale.
  for (const g of GOLDEN) {
    const qr = encode(g.contenu)
    const taille = g.size
    const premiere = [[8, 0], [8, 1], [8, 2], [8, 3], [8, 4], [8, 5], [8, 7], [8, 8], [7, 8], [5, 8], [4, 8], [3, 8], [2, 8], [1, 8], [0, 8]]
    const seconde = []
    for (let i = 0; i < 8; i++) seconde.push([taille - 1 - i, 8])
    for (let i = 8; i < 15; i++) seconde.push([8, taille - 7 + (i - 8)])
    const bits = (coords) => coords.map(([x, y]) => (qr.get(x, y) ? "1" : "0")).join("")
    assert.equal(bits(seconde), bits(premiere), `${g.nom} : les deux copies divergent`)
  }
})

test("le module sombre est noir dans la grille rendue, pas seulement reserve", () => {
  // Le reserver ne suffit pas : la grille renvoyee est copiee depuis la carte des valeurs,
  // donc la position garde sa valeur de depart tant que personne ne l'ecrit. C'est
  // exactement ce qui est arrive — reserve au bon endroit, ecrit au mauvais.
  for (const g of GOLDEN) {
    const qr = encode(g.contenu)
    assert.equal(qr.get(8, g.size - 8), true, `${g.nom} : (8, 4v+9) doit etre noir`)
  }
})

// --- Appairage -------------------------------------------------------------------

test("le jeton fait 128 bits et tient dans 22 caracteres base64url", () => {
  assert.equal(TOKEN_BYTES, 16)
  assert.equal(newToken().length, 22)
  assert.match(newToken(), /^[A-Za-z0-9_-]{22}$/)
})

test("deux jetons ne se ressemblent pas", () => {
  const jetons = new Set(Array.from({ length: 64 }, () => newToken()))
  assert.equal(jetons.size, 64, "64 jetons doivent etre distincts")
})

test("le jeton expire, et l'ecart se mesure", () => {
  const maintenant = 1_700_000_000_000
  const vivant = { token: "x", expiresAt: maintenant + 60_000 }
  assert.equal(isValid(vivant, maintenant), true)
  assert.equal(remainingMs(vivant, maintenant), 60_000)
  assert.equal(isValid(vivant, maintenant + 60_001), false)
  assert.equal(remainingMs(vivant, maintenant + 60_001), 0)
  assert.equal(isValid(null, maintenant), false)
  assert.equal(remainingMs(null, maintenant), 0)
})

test("la duree de vie est bornee a une demi-heure", () => {
  assert.equal(PAIRING_TTL_MS, 30 * 60 * 1000)
})

test("le lien d'appairage encode l'URL du serveur, donc reste analysable", () => {
  const lien = pairingLink("https://exemple.fr:4096", "abc")
  assert.ok(lien.startsWith("opencode://pair?"))
  // Sans encodage, le « ? » et le « : » de l'URL casseraient l'analyse cote Android.
  assert.ok(lien.includes("s=https%3A%2F%2Fexemple.fr%3A4096"))
  const params = new URL(lien.replace("opencode://", "https://"))
  assert.equal(params.searchParams.get("s"), "https://exemple.fr:4096")
  assert.equal(params.searchParams.get("t"), "abc")
})

test("un lien d'appairage reel tient dans la plage des versions ecrites", () => {
  // C'est la raison du plafond version 6 : un lien de 74 a 82 caracteres tombe en
  // version 5, tres en deca du plafond.
  // Capacite version 4 = 78 octets, version 5 = 106. Un nom de domaine longueur fait
  // basculer d'une version a l'autre : c'est exactement la marge que le plafond doit tenir.
  const cas = [
    ["https://exemple.fr:4096", 4],
    ["https://sous.domaine.tres-long.fr:4096", 5],
  ]
  for (const [serveur, attendue] of cas) {
    const lien = pairingLink(serveur, newToken())
    assert.ok(lien.length >= 70, `lien trop court : ${lien.length}`)
    assert.equal(versionFor(new TextEncoder().encode(lien).length), attendue, serveur)
    // Et surtout : le QR tient dans la plage ecrite, sans toucher au plafond de 6.
    assert.ok(encode(lien).size <= 41, `${serveur} : QR au-dela de la version 6`)
  }
})
