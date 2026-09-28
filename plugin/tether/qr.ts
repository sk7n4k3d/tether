/**
 * Le rendu d'un QR code **en texte**, pour le terminal.
 *
 * ## Ce que zxing ne fait pas
 *
 * `com.google.zxing:core` **decode** : il sait lire un QR. Il ne sait pas l'afficher. La
 * generation de QR en Kotlin passe par des bindings JNI ou par une bibliotheque qui
 * encode elle-meme le bitmap. Ni l'un ni l'autre n'est acceptable ici :
 *
 *  - une dependance native dans un plugin qui tourne dans le serveur opencode, c'est une
 *    surface d'attaque et une difficulte de deploiement de plus ;
 *  - le README doit dire « copiez ce fichier dans ce dossier », pas « compilez ceci ».
 *
 * Donc on encode le QR nous-memes. C'est de la manipulation de bits, et c'est long — mais
 * la sortie est du **texte** : deux caracteres par module, un module par ligne, et le
 * terminal sait deja dessiner.
 *
 * ## Ce que ce module ne fait pas
 *
 * Il ne verifie rien. Un QR affiche ne prouve pas qu'il a ete scanne. La preuve, c'est que
 * l'endpoint est arrive dans le registre — et ca, c'est `subscribe`, teste ailleurs.
 *
 * ## La verification, elle, est externe
 *
 * `qr-verify.sh` fait rendre un QR par ce module, le redessine en image, et le fait
 * **relire par zxing** (la reference du monde reel). Un encodeur ecrit de memoire ne
 * prouve rien : c'est le decodeur d'un tiers qui dit si les bits sont justes.
 */

/** Un module = un point du QR. `true` = noir. */
export interface BitMatrix {
  readonly size: number
  get(x: number, y: number): boolean
}

const ECC_L = 0b01 // recuperation 7 %

/** Indicateur de mode : octet, sur 4 bits. */
const MODE_BYTE = 0b0100

/** Taille de l'indicateur de longueur, en bits, pour les versions 1 a 9. */
const CHAR_COUNT_BITS = 8

/**
 * Version, de 1 a 6, niveau L.
 *
 * `data` est le nombre de mots de code de donnees, `ecPerBlock` le nombre d'octets de
 * correction **par bloc**, `blocks` le nombre de blocs. Les trois sont indissociables :
 * `data + ecPerBlock * blocks` doit valoir `total`, le nombre de mots de code de la
 * version, lui-meme egal a `plancher(modules de donnees / 8)`.
 *
 * ⚠️ **`total` n'est pas un champ decoratif : c'est le garde-fou.** Une version ou seul
 * `blocks` est faux produit un tableau de la **bonne longueur** — 160 mots au lieu de 134
 * pour la version 5 — dont le contenu ne veut rien dire. L'encodeur ne leve rien,
 * n'echoue sur rien, et le QR est dessine : c'est le lecteur qui refuse, des versions plus
 * tardives. C'est exactement ce qui est arrive ici, sur les versions 5, 6, 7 et 9.
 *
 * Ces valeurs viennent de la table normative, extraite de l'API de zxing et recopiee
 * dans `qr.test.mjs` sous forme de test d'integrite.
 *
 * ⚠️ **La table s'arrete a la version 6, volontairement.** A partir de la version 7, la
 * spec ajoute un bloc d'information de version de 18 bits, encode en BCH(18,6), que cet
 * encodeur n'ecrit pas. Sans lui, un lecteur identifie le QR et echoue a le decoder.
 * Plutot que de laisser une erreur silencieuse, la version 6 est un plafond explicite :
 * 134 octets de contenu, contre les ~74 d'un lien d'appairage.
 */
const VERSIONS: ReadonlyArray<{ data: number; ecPerBlock: number; blocks: number; total: number }> = [
  { data: 19, ecPerBlock: 7, blocks: 1, total: 26 }, // 1  — 21 modules
  { data: 34, ecPerBlock: 10, blocks: 1, total: 44 }, // 2  — 25
  { data: 55, ecPerBlock: 15, blocks: 1, total: 70 }, // 3  — 29
  { data: 80, ecPerBlock: 20, blocks: 1, total: 100 }, // 4  — 33
  { data: 108, ecPerBlock: 26, blocks: 1, total: 134 }, // 5  — 37
  { data: 136, ecPerBlock: 18, blocks: 2, total: 172 }, // 6  — 41
]

/** Le nombre de blocs de correction : `total` impose la repartition. */
function blocksFor(version: number): number {
  return VERSIONS[version - 1].blocks
}

/**
 * La plus petite version qui porte `length` octets en mode octet.
 *
 * Le compte est exact, pas approximatif : l'en-tete coute `4 + CHAR_COUNT_BITS` bits,
 * donc un contenu de `n` octets tient si `4 + 8 + 8n <= 8 * data`, soit
 * `n <= data - 1.5`, donc `n <= data - 2` pour un entier. La formule est ecrite en
 * bits plutot qu'en « - 2 » en dur : le - 2 est correct pour un compte sur 8 bits, et
 * faux des que le compte passe sur 16 bits. Calculer, c'est ne pas avoir a y revenir.
 */
export function versionFor(length: number): number {
  const headerBits = 4 + CHAR_COUNT_BITS
  for (let i = 0; i < VERSIONS.length; i++) {
    if (Math.floor((VERSIONS[i].data * 8 - headerBits) / 8) >= length) return i + 1
  }
  throw new Error(`contenu trop long pour un QR de version <= ${VERSIONS.length} : ${length} octets`)
}

// --- Reeds-Solomon sur GF(256), primitive 0x11D ------------------------------

function gfMul(a: number, b: number): number {
  let result = 0
  let x = a
  let y = b
  while (y) {
    if (y & 1) result ^= x
    y >>= 1
    x <<= 1
    if (x & 0x100) x ^= 0x11d
  }
  return result
}

/**
 * Le polynome generateur de Reed-Solomon de degre `degree` :
 * `g(x) = PROD (x + a^i)` pour i de 0 a degree-1, ou `a = 0x02` est le generateur de
 * GF(256) sous le polynome primitif 0x11D.
 *
 * ⚠️ Le facteur est **`a^i`, pas `a`**. Ecrire `a` a chaque tour donne `(x+a)^n`, qui
 * n'est pas un polynome generateur : les corrections produites sont fausses, mais leur
 * nombre est juste et la longueur du QR est elle-meme correcte. Le QR se dessine
 * normalement, se lit, et echoue a la verification de Reed-Solomon — c'est-a-dire apres
 * avoir affiche le contenu, au moment ou le lecteur controle l'integrite. Le seul
 * symptome est un echec de correction, tres facile a confondre avec une erreur de
 * transmission alors qu'elle est ici dans l'encodeur.
 */
function generatorPoly(degree: number): number[] {
  let poly = [1]
  let alpha = 1 // a^0 : le premier facteur est (x + 1), pas (x + a)
  for (let d = 0; d < degree; d++) {
    const next = new Array<number>(poly.length + 1).fill(0)
    for (let i = 0; i < poly.length; i++) {
      // (x + a^d) * g : la part constante est a^d*g_i, la part x est g_i decale d'un degre.
      next[i] ^= gfMul(poly[i], alpha)
      next[i + 1] ^= poly[i]
    }
    poly = next
    alpha = gfMul(alpha, 0x02) // passer de a^d a a^(d+1)
  }
  return poly
}

/**
 * Les `count` octets de correction d'erreur d'un bloc : le reste de
 * `data * x^count` divise par le polynome generateur.
 *
 * ⚠️ **Le generateur se lit a l'envers ici, et sans son coefficient dominant.**
 * [generatorPoly] renvoie les coefficients en powers croissantes : `generator[0]` est le
 * terme constant, `generator[count]` vaut 1. Le registre de la division synthetique
 * consomme, a l'etape `i`, le coefficient de `x^(count-1-i)` — c'est `generator[count-1-i]`.
 *
 * Deux erreurs voisines, deux corrections fausse et **de la meme longueur** :
 *  - `generator[i + 1]` : le bon nombre d'octets, une correction plausible, fausse ;
 *  - `generator[count - i]` : decale d'un rang, toujours la bonne longueur, toujours faux.
 *
 * Symptome commun : le contenu du QR s'affiche correctement et c'est la **verification
 * d'integrite** qui echoue. L'erreur se manifeste chez le lecteur, plusieurs etapes apres
 * sa cause, ce qui la rend tres difficile a rattacher a l'encodeur.
 *
 * Verifie contre `ReedSolomonEncoder` de zxing sur 2 puis sur 80 mots de donnees : sortie
 * identique octet pour octet.
 */
function ecFor(data: Uint8Array, count: number): Uint8Array {
  const generator = generatorPoly(count)
  const result = new Uint8Array(count)
  for (const byte of data) {
    const factor = byte ^ result[0]
    result.copyWithin(0, 1)
    result[count - 1] = 0
    for (let i = 0; i < count; i++) result[i] ^= gfMul(generator[count - 1 - i] ?? 0, factor)
  }
  return result
}

// --- Encodage ------------------------------------------------------------------

/**
 * Encode en **mode octet**, niveau L, version choisie pour que le contenu tienne.
 *
 * ## Pourquoi le mode octet, et pas le mode numerique
 *
 * Le mode numerique est plus compact : trois chiffres par dix bits. Il ne peut
 * representer que des chiffres. Or le contenu d'un lien d'appairage est une URL —
 * `opencode://pair?s=…` — donc plein de lettres.
 *
 * Declarer le mode numerique sur un contenu alphabetique ne leve **aucune erreur** :
 * les octets sont ecrits tels quels et le QR est parfaitement dessine. C'est le
 * **lecteur** qui casse, en reinterpretant les bits par groupes de trois chiffres. Le
 * decodeur echoue donc sur un QR visuellement irreprochable, ce qui est le pire
 * endroit pour chercher la panne. Le mode octet, lui, porte n'importe quel ASCII.
 *
 * Le mode octet coute 12 bits d'en-tete (indicateur sur 4 bits + longueur sur 8),
 * contre 14 pour le numerique. Pour une URL, ce surcout est noyé.
 *
 * Le decoupage en blocs suit la regle de la spec : on donne a chaque bloc `data / blocks`
 * octets, et les blocs dont la division a un reste en recoivent un de plus.
 */
function buildCodewords(bytes: Uint8Array, spec: { data: number; ecPerBlock: number; blocks: number }): Uint8Array {
  const bits: boolean[] = []
  const push = (value: number, width: number) => {
    for (let i = width - 1; i >= 0; i--) bits.push(((value >> i) & 1) === 1)
  }

  push(MODE_BYTE, 4)
  push(bytes.length, CHAR_COUNT_BITS)
  for (const byte of bytes) push(byte, 8)

  // Terminateur : jusqu'a 4 zéros, puis alignement sur un octet.
  for (let i = 0; i < 4 && bits.length < spec.data * 8; i++) bits.push(false)
  while (bits.length % 8 !== 0) bits.push(false)

  const codewords = new Uint8Array(spec.data)
  for (let i = 0; i < bits.length; i++) {
    if (bits[i]) codewords[Math.floor(i / 8)] |= 1 << (7 - (i % 8))
  }
  // Remplissage : les motifs 0xEC et 0x11 alternes de la spec (8.4.9).
  //
  // ⚠️ L'alternance est **relative au premier octet de remplissage**, pas a l'index
  // absolu du mot. Indexer par `i % 2` fait commencer par 0x11 quand le contenu occupe un
  // nombre impair de mots, donc une version sur deux commence a l'envers.
  //
  // Ce bug ne se voit **d'aucune facon par la lecture** : le remplissage est jette des que
  // la longueur declaree est atteinte, donc n'importe quelle suite d'octets passe. Un
  // encodeur peut donc emettre n'importe quoi la-dessus pendant des mois sans qu'aucun
  // test ne bronche — c'est la comparaison module par module avec un encodeur de
  // reference qui l'a fait sortir.
  for (let i = bits.length / 8, rang = 0; i < spec.data; i++, rang++) {
    codewords[i] = rang % 2 === 0 ? 0xec : 0x11
  }

  // Decoupage en blocs : les premiers recoivent un octet de plus si la division a un reste.
  const base = Math.floor(spec.data / spec.blocks)
  const extra = spec.data % spec.blocks
  const sizes: number[] = []
  for (let i = 0; i < spec.blocks; i++) sizes.push(base + (i < extra ? 1 : 0))

  const dataBlocks: Uint8Array[] = []
  const eccBlocks: Uint8Array[] = []
  let cursor = 0
  for (const size of sizes) {
    const block = codewords.subarray(cursor, cursor + size)
    dataBlocks.push(block)
    eccBlocks.push(ecFor(block, spec.ecPerBlock))
    cursor += size
  }

  // L'entrelacement (spec 8.6) porte sur les donnees **et** sur les corrections :
  // on prend le premier octet de chaque bloc, puis le deuxieme de chaque bloc, etc.
  //
  // ⚠️ Les donnees ne sont PAS deja entrelacees. Les blocs sont des tranches
  // consecutives de `codewords`, donc les copier telles quelles revient a les laisser
  // **par bloc** — ce qui est l'inverse de l'entrelacement. Avec des blocs de tailles
  // egales, l'erreur ne se voit pas du tout sur les octets, seulement sur leur ordre.
  //
  // Ce n'est visible qu'a partir de la premiere version a blocs multiples : les
  // versions 1 a 5 n'en ont qu'un, ou l'entrelacement est l'identite. Un encodeur qui
  // « marche sur les petits QR » et echoue sur les gros, c'est ce symptome-la.
  const out = new Uint8Array(spec.total)

  let cursorOut = 0
  for (let i = 0; i < base + (extra > 0 ? 1 : 0); i++) {
    for (const block of dataBlocks) {
      if (i < block.length) out[cursorOut++] = block[i]
    }
  }
  for (let i = 0; i < spec.ecPerBlock; i++) {
    for (const ecc of eccBlocks) out[cursorOut++] = ecc[i]
  }
  return out
}

// --- Placement -----------------------------------------------------------------

/**
 * Coordonnees centrales des motifs d'alignement, par version.
 *
 * ⚠️ Ce ne **sont pas** les memes coordonnees pour toutes les versions : la version 4
 * place son motif en (26, 26), pas en (22, 22). Utiliser une table unique « grande
 * version » decale silencieusement le motif — le QR reste un carre de modules avec trois
 * coins corrects, donc **un lecteur le reconnait et echoue a decoder**, sans lever la
 * moindre erreur. C'est le symptome exact qu'on a observe avec zxing.
 */
const ALIGNMENT_CENTERS: ReadonlyArray<readonly number[]> = [
  [], // 1 — aucun motif
  [6, 18], // 2
  [6, 22], // 3
  [6, 26], // 4
  [6, 30], // 5
  [6, 34], // 6
]

/** Les modules de fonction, separes de leur valeur. */
interface Function {
  /** 1 = module de fonction (jamais de donnees), 0 = donnees. */
  reserved: Uint8Array
  /** La valeur fixe des modules de fonction. */
  values: Uint8Array
}

function buildFunction(version: number): Function {
  const size = version * 4 + 17
  const reserved = new Uint8Array(size * size)
  const values = new Uint8Array(size * size)
  const at = (x: number, y: number) => y * size + x

  /**
   * Reserve un module de fonction **et** fixe sa valeur.
   *
   * ⚠️ Les deux tableaux ne peuvent pas etre un seul. L'anneau blanc d'un motif de
   * recherche vaut 0 ; si 0 signifiait « module de donnees », le placement viendrait
   * ecraser l'anneau et le motif serait destroy. `reserved` et `values` sont donc
   * deux tableaux, et c'est la premiere version de ce fichier qui les confondait.
   */
  const set = (x: number, y: number, value: number) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return
    reserved[at(x, y)] = 1
    values[at(x, y)] = value
  }

  // Motif de recherche : anneau noir, anneau blanc, carre noir 3x3.
  const finder = (x: number, y: number) => {
    for (let dy = 0; dy < 7; dy++) {
      for (let dx = 0; dx < 7; dx++) {
        const ring = Math.max(Math.abs(dx - 3), Math.abs(dy - 3))
        set(x + dx, y + dy, ring === 2 ? 0 : 1)
      }
    }
  }
  finder(0, 0)
  finder(size - 7, 0)
  finder(0, size - 7)

  /**
   * Separateurs : la bande claire d'un module qui entoure chaque motif de recherche, du
   * cote des donnees. 15 modules par motif, toujours blancs.
   *
   * ⚠️ **C'est le bug qui a fait decrocher le decodage, et il est invisible.** Reserve
   * les motifs sans les separateurs donne un QR qui a l'air parfaiteument correct a
   * l'oeil : les trois coins sont justes, les motifs de synchronisation sont justes.
   * Le lecteur trouve les trois motifs de recherche, lit le format, demasque, et decale
   * son flux de 45 bits — il rend donc 45 bits de parasites au milieu du message, et
   * echoue. Sans les separateurs, la version 4 compte 852 modules de donnees au lieu
   * de 807 ; 807 = 800 bits de codewords + 7 bits de reste. C'est ce compte-la qui
   * permet de le voir.
   *
   * Le separateur se met du cote des donnees : a droite et en bas pour le motif
   * haut-gauche, a gauche et en bas pour le motif haut-droit, a droite et en haut pour
   * le motif bas-gauche.
   */
  const separator = (fx: number, fy: number, vertical: -1 | 1, horizontal: -1 | 1) => {
    for (let i = 0; i <= 7; i++) {
      set(fx + (vertical < 0 ? -1 : 7), fy + i - (horizontal < 0 ? 1 : 0), 0)
      set(fx + i - (vertical < 0 ? 1 : 0), fy + (horizontal < 0 ? -1 : 7), 0)
    }
  }
  separator(0, 0, 1, 1)
  separator(size - 7, 0, -1, 1)
  separator(0, size - 7, 1, -1)

  // Motif de synchronisation : alternance depart/fin sur ligne et colonne 6.
  for (let i = 8; i < size - 8; i++) {
    set(i, 6, i % 2 === 0 ? 1 : 0)
    set(6, i, i % 2 === 0 ? 1 : 0)
  }
  // Le module « sombre », toujours noir. Il n'appartient pas a l'information de format.
  //
  // ⚠️ Ses coordonnees sont (8, 4*version+9) en (x, y) — la **8e colonne**, pas la
  // 8e ligne. L'inversion est tentante, parce que la spec parle de « l'intersection de
  // la huitieme rangee et de la huitieme colonne », et parce que l'information de format,
  // elle, commence bien en (8, 8). Transpose, le module sombre atterrit en (4v+9, 8),
  // c'est-a-dire **exactement sur la premiere copie de l'information de format**. Le
  // QR reste dessine, les trois motifs sont justes, et le decodage echoue.
  set(8, size - 8, 1)

  for (const cy of ALIGNMENT_CENTERS[version - 1]) {
    for (const cx of ALIGNMENT_CENTERS[version - 1]) {
      // Les trois coins sont occupes par les motifs de recherche, sauf celui du
      // bas-droit, qui recoit bien un motif d'alignement supplementaire.
      const overlapsFinder = (cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)
      if (overlapsFinder) continue
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          // Seul le centre est noir : le reste est un anneau blanc sur fond noir.
          set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) === 1 ? 0 : 1)
        }
      }
    }
  }

  // Zone d'information de format : 15 modules, reserves ici, ecrits apres le choix
  // du masque. La colonne 6 et la ligne 6 restent au motif de synchronisation.
  for (let i = 0; i <= 8; i++) if (i !== 6) set(i, 8, 0)
  for (let i = 0; i < 8; i++) if (i !== 6) set(8, i, 0)
  for (let i = 0; i < 8; i++) set(size - 1 - i, 8, 0)
  for (let i = 8; i < 15; i++) set(8, size - 15 + i, 0)

  return { reserved, values }
}

const MASKS: ReadonlyArray<(x: number, y: number) => boolean> = [
  (x, y) => (x + y) % 2 === 0,
  (_x, y) => y % 2 === 0,
  (x) => x % 3 === 0,
  (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0,
  (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
  (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
  (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
]

/** Le motif de format, pour le niveau L et le masque donne (15 bits, BCH). */
function formatBits(mask: number): number {
  const data = (ECC_L << 3) | mask
  let bch = data << 10
  for (let i = 4; i >= 0; i--) {
    if ((bch >> (i + 10)) & 1) bch ^= 0b10100110111 << i
  }
  return ((data << 10) | (bch & 0x3ff)) ^ 0b101010000010010
}

function placeData(codewords: Uint8Array, version: number, fn: Function, mask: number): Uint8Array {
  const size = version * 4 + 17
  const at = (x: number, y: number) => y * size + x
  const grid = Uint8Array.from(fn.values)

  const bits: boolean[] = []
  for (const byte of codewords) for (let i = 7; i >= 0; i--) bits.push(((byte >> i) & 1) === 1)

  let index = 0
  for (let right = size - 1; right >= 1; right -= 2) {
    // La colonne 6 est un motif de synchronisation : la paire de colonnes saute donc
    // de 7 a 5, sans quoi on y ecrirait des donnees.
    if (right === 6) right = 5
    // Le sens de lecture est **derive de la colonne**, pas alterne : la spec dit
    // `((right + 1) & 2) == 0`. Un simple drapeau qui bascule donnerait la meme suite
    // ici, mais pas apres le saut 6 -> 5.
    const upward = ((right + 1) & 2) === 0
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j
        const y = upward ? size - 1 - vert : vert
        if (fn.reserved[at(x, y)] === 1) continue
        const bit = index < bits.length ? bits[index] : false
        // Le masque s'applique aux seules donnees, jamais aux modules de fonction.
        grid[at(x, y)] = (bit !== MASKS[mask](x, y) ? 1 : 0) as number
        index++
      }
    }
  }
  return grid
}

function writeFormat(grid: Uint8Array, version: number, mask: number) {
  const size = version * 4 + 17
  const at = (x: number, y: number) => y * size + x
  const bits = formatBits(mask)

  for (let i = 0; i < 15; i++) {
    const bit = ((bits >> i) & 1) === 1
    if (i < 6) grid[at(8, i)] = bit ? 1 : 0
    else if (i < 8) grid[at(8, i + 1)] = bit ? 1 : 0
    else if (i === 8) grid[at(7, 8)] = bit ? 1 : 0
    else grid[at(14 - i, 8)] = bit ? 1 : 0
  }
  for (let i = 0; i < 8; i++) grid[at(size - 1 - i, 8)] = ((bits >> i) & 1) === 1 ? 1 : 0
  for (let i = 8; i < 15; i++) grid[at(8, size - 15 + i)] = ((bits >> i) & 1) === 1 ? 1 : 0

  // Le module sombre, en (8, 4v+9) — cf. [buildFunction] pour pourquoi la transpose est
  // tentante.
  //
  // ⚠️ **Il faut l'ecrire ici aussi.** [buildFunction] le reserve, ce qui suffit a
  // l'encoder pour qu'il ne le confonde pas avec une donnee, mais la grille qu'on renvoie
  // est copiee depuis `values` : la position garde sa valeur de depart tant que
  // personne ne l'ecrit. L'ecrire ici, et a la bonne position, garantit que les deux
  // copies de l'information de format portent les memes 15 bits — ce qu'un lecteur peut
  // verifier, et qui met immediatement en evidence une collision.
  grid[at(8, size - 8)] = 1
}

/** Penalite de la regle 4 de la spec, sur les 4 versions > 1. */
function penalty(grid: Uint8Array, size: number): number {
  let score = 0
  // Regle 1 : series de 5 modules identiques.
  for (let y = 0; y < size; y++) {
    for (const horizontal of [true, false]) {
      let run = 1
      for (let i = 1; i < size; i++) {
        const a = horizontal ? grid[y * size + i] : grid[i * size + y]
        const b = horizontal ? grid[y * size + i - 1] : grid[(i - 1) * size + y]
        if (a === b) {
          run++
          if (run === 5) score += 3
          else if (run > 5) score += 1
        } else run = 1
      }
    }
  }
  // Regle 2 : blocs 2x2 de meme couleur.
  for (let y = 0; y < size - 1; y++) {
    for (let x = 0; x < size - 1; x++) {
      const v = grid[y * size + x]
      if (v === grid[y * size + x + 1] && v === grid[(y + 1) * size + x] && v === grid[(y + 1) * size + x + 1]) {
        score += 3
      }
    }
  }
  // Regle 3 : rapport noir/blanc approche de 50 %.
  let dark = 0
  for (const cell of grid) dark += cell
  const percent = (dark * 100) / grid.length
  score += Math.floor(Math.abs(percent - 50) / 5) * 10
  // Regle 4 : motif 1:1:3:1:1.
  const pattern = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0]
  for (let y = 0; y < size; y++) {
    for (let x = 0; x + 11 <= size; x++) {
      let hit = true
      for (let i = 0; i < 11; i++) if (grid[y * size + x + i] !== pattern[i]) { hit = false; break }
      if (hit) score += 40
    }
  }
  return score
}

/**
 * Encode un texte en QR, en choisissant le masque le moins penalise.
 *
 * `forceMask` n'existe que pour les tests : il permet de decoder les huit masques et de
 * verifier que chacun produit un QR lisible. Un encodeur dont le choix de masque casse
 * le decoding ne se diagnose pas tout seul — il faut pouvoir forcer.
 */
export function encode(text: string, options: { forceMask?: number } = {}): BitMatrix {
  const bytes = new TextEncoder().encode(text)
  if (bytes.some((b) => b > 127)) {
    // Le mode octet de la spec vaut ISO-8859-1 par defaut, et aucun bloc ECI n'est
    // ecrit. On refuse donc explicitement le non-ASCII plutot que de produire un QR
    // dont le contenu depend de l'interpreteur du lecteur.
    throw new Error("contenu non ASCII : le mode octet n'ecrit pas d'ECI, ISO-8859-1 par defaut")
  }

  const version = versionFor(bytes.length)
  const spec = VERSIONS[version - 1]
  const codewords = buildCodewords(bytes, spec)
  const fn = buildFunction(version)
  const size = version * 4 + 17

  let best: { grid: Uint8Array; penalty: number; mask: number } | null = null
  for (let mask = 0; mask < 8; mask++) {
    if (options.forceMask !== undefined && options.forceMask !== mask) continue
    const grid = placeData(codewords, version, fn, mask)
    writeFormat(grid, version, mask)
    const score = penalty(grid, size)
    if (best === null || score < best.penalty) best = { grid, penalty: score, mask }
  }

  const chosen = best!.grid
  return { size, get: (x, y) => chosen[y * size + x] === 1 }
}

/** Le masque retenu par [encode], pour le diagnostic. */
export function chosenMask(text: string): number {
  const version = versionFor(new TextEncoder().encode(text).length)
  const spec = VERSIONS[version - 1]
  const bytes = new TextEncoder().encode(text)
  const codewords = buildCodewords(bytes, spec)
  const fn = buildFunction(version)
  const size = version * 4 + 17
  let best = { score: Infinity, mask: -1 }
  for (let mask = 0; mask < 8; mask++) {
    const grid = placeData(codewords, version, fn, mask)
    writeFormat(grid, version, mask)
    const score = penalty(grid, size)
    if (score < best.score) best = { score, mask }
  }
  return best.mask
}

// --- Rendu ---------------------------------------------------------------------

/** Deux caracteres par module : les demi-blocs couvrent la cellule entiere. */
const BLACK = "██"
const WHITE = "  "

/** Marge claire de chaque cote, en modules. La spec en demande 4 ; ici 2 suffisent. */
const MARGIN_MODULES = 2

/**
 * Rend le QR en texte.
 *
 * `invert` est necessaire selon le fond du terminal : sur un terminal sombre, des blocs
 * sombres se fondent. Inverser ne change pas l'information — un QR reste lisible dans les
 * deux sens, c'est la camera qui lit les modules, pas l'oeil.
 *
 * ⚠️ **Toutes les lignes doivent avoir exactement la meme largeur.** Une marge calculee
 * en nombre de *caracteres* au lieu de nombre de *modules* donne une ligne du double de
 * la largeur des autres : le QR s'affiche, l'oeil s'y habitue, et le rendu n'est plus
 * carre — donc plus un carre de modules, donc plus un QR. Aucun decodeur ne dira rien ;
 * la camera dira seulement « je ne lis pas ».
 */
export function render(qr: BitMatrix, options: { invert?: boolean } = {}): string {
  const [on, off] = options.invert ? [WHITE, BLACK] : [BLACK, WHITE]
  const marge = off.repeat(MARGIN_MODULES)
  const ligneVide = off.repeat(qr.size + MARGIN_MODULES * 2)

  const lines: string[] = [ligneVide]
  for (let y = 0; y < qr.size; y++) {
    let line = marge
    for (let x = 0; x < qr.size; x++) line += qr.get(x, y) ? on : off
    lines.push(line + marge)
  }
  lines.push(ligneVide)
  return lines.join("\n")
}

/** Le QR complet, encode et rendu. */
export function qrText(text: string, options: { invert?: boolean } = {}): string {
  return render(encode(text), options)
}
