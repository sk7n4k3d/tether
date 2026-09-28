#!/usr/bin/env bash
#
# Verifie l'encodeur de QR de Tether contre zxing, la reference du monde reel.
#
# `qr.test.mjs` fige des matrices de reference, ce qui garantit qu'un changement ne passe
# pas inapercu. Cela ne garantit pas que ces matrices sont *bonnes* : elles viennent de
# l'encodeur sous test. Ce script confronte deux encodeurs independants.
#
# ## Les deux tests, et pourquoi il en faut deux
#
# **1. Modules identiques, a masque impose.** On demande a zxing quel masque il a choisi,
#   on impose ce masque a Tether, et on compare les 33x33 modules un a un. Deux encodeurs
#   independants qui tombent d'accord module par module — motifs, placement en zigzag,
#   Reed-Solomon, entrelacement, information de format — ne peuvent pas partager la meme
#   erreur. C'est le test le plus fort possible.
#
#   Le masque doit etre impose parce que son **choix** est un arbitrage, pas une
#   correction : la regle 3 de la penalite a plusieurs lectures legitimes, et zxing et
#   Tether n'en font pas la meme. Deux QR valides peuvent differer de la seule face a
#   choisir un masque different. Comparer sans l'imposer testerait l'arbitrage, pas
#   l'exactitude.
#
# **2. Lecture par zxing.** On redessine la matrice imposee et on la fait relire :
#   binarisation, detection, correction Reed-Solomon. C'est le chemin complet.
#
# Le second ne rend pas le premier inutile : la comparaison module par module peut
# tolerer un ecart indifferent (les bits de reste) pendant que la relecture, elle, le
# sanctionne. Les deux mesures disent des choses differentes sur des fautes differentes.
#
# Il faut un JDK, `javac`, `curl` et le reseau (une fois, pour le jar). Sans eux, le
# script sort proprement : ce n'est pas un echec du code, c'est un environnement qui ne
# permet pas de conclure.
#
#   Usage : plugin/tether/qr-verify.sh [debut] [fin]     (octets de contenu, 5 a 134)

set -euo pipefail

ICI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RACINE="$(cd "$ICI/../.." && pwd)"
DEBUT="${1:-5}"
FIN="${2:-134}"
ATELIER="$(mktemp -d "${TMPDIR:-/tmp}/tether-qr-verify-XXXXXX")"
trap 'rm -rf "$ATELIER"' EXIT

for outil in java javac curl; do
  if ! command -v "$outil" >/dev/null 2>&1; then
    echo "SKIP : $outil absent, verification externe impossible"
    exit 0
  fi
done

JAR="$ATELIER/core.jar"
if [ ! -f "$JAR" ]; then
  echo "Telechargement de com.google.zxing:core:3.5.3..."
  curl -fsSL -o "$JAR" "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar"
fi

# --- 1. zxing encode, et dit quel masque il a choisi -------------------------------
cat > "$ATELIER/Choix.java" <<'JAVA'
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import java.nio.file.*;
import java.util.*;

/** Encode chaque contenu avec zxing et note le masque qu'il a retenu. */
public class Choix {
  public static void main(String[] args) throws Exception {
    Path dir = Paths.get(args[0]);
    List<Path> cas = new ArrayList<>();
    try (var s = Files.list(dir)) {
      s.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(cas::add);
    }
    StringBuilder sortie = new StringBuilder();
    for (Path p : cas) {
      var qr = Encoder.encode(Files.readString(p), ErrorCorrectionLevel.L);
      sortie.append(p.getFileName().toString().replace(".txt", ""))
          .append('\t').append(qr.getVersion().getVersionNumber())
          .append('\t').append(qr.getMaskPattern())
          .append('\n');
    }
    Files.writeString(Paths.get(args[1]), sortie.toString());
  }
}
JAVA
javac -cp "$JAR" -d "$ATELIER" "$ATELIER/Choix.java"

# --- 2. Tether encode avec le masque impose ---------------------------------------
# Node ne charge des modules ES que depuis un fichier : les generateurs sont donc ecrits
# sur disque plutot que passes a `node -e`.
cat > "$ATELIER/contenus.mjs" <<'GEN'
import { versionFor } from "MODULE"
import { writeFileSync } from "node:fs"

const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~:/?#[]@!$&()*+,;=%"
const debut = Number(process.argv[2])
const fin = Number(process.argv[3])
const dossier = process.argv[4]

// Un contenu par longueur, avec des caracteres qui tlient les deux cas piege du mode
// octet : le « % » (encode en dur) et les separateurs de chemin.
//
// Le contenu est **deterministe** : une graine deduite de la longueur. Un script de
// verification dont le resultat change a chaque execution ne verifie rien — on ne peut
// pas distinguer « le code a bouge » du « hasard a change ».
for (let n = debut; n <= fin; n++) {
  let graine = (n * 2654435761) >>> 0
  const suivant = () => {
    graine = (graine * 1664525 + 1013904223) >>> 0
    return graine
  }
  let contenu = ""
  for (let i = 0; i < n; i++) contenu += alphabet[suivant() % alphabet.length]
  const v = versionFor(new TextEncoder().encode(contenu).length)
  writeFileSync(`${dossier}/${String(n).padStart(3, "0")}-v${v}.txt`, contenu)
}
GEN

cat > "$ATELIER/matrices.mjs" <<'GEN'
import { encode } from "MODULE"
import { readFileSync, writeFileSync } from "node:fs"

const dossier = process.argv[2]
const choix = readFileSync(process.argv[3], "utf8").trim().split("\n")

const parTaille = {}
for (const ligne of choix) {
  const [nom, version, masque] = ligne.split("\t")
  const contenu = readFileSync(`${dossier}/${nom}.txt`, "utf8")
  const qr = encode(contenu, { forceMask: Number(masque) })
  let plat = ""
  for (let y = 0; y < qr.size; y++) for (let x = 0; x < qr.size; x++) plat += qr.get(x, y) ? "#" : " "
  writeFileSync(`${dossier}/${nom}.qr`, qr.size + "\n" + plat)
  parTaille[qr.size] = (parTaille[qr.size] ?? 0) + 1
}
console.log("QR par taille (21 = version 1, 41 = version 6) :", JSON.stringify(parTaille))
GEN
sed -i "s|MODULE|$RACINE/plugin/tether/qr.ts|" "$ATELIER/contenus.mjs" "$ATELIER/matrices.mjs"

# --- 3. comparaison module par module, et relecture ---------------------------------
cat > "$ATELIER/Verdict.java" <<'JAVA'
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

public class Verdict {
  /** La carte des modules de fonction, pour retrouver l'ordre de placement. */
  private static boolean[][] fonction(int size, int version) {
    boolean[][] R = new boolean[size][size];
    int[][] f = {{0, 0}, {size - 7, 0}, {0, size - 7}};
    for (int[] a : f) for (int d = 0; d < 7; d++) for (int e = 0; e < 7; e++) R[a[1] + d][a[0] + e] = true;
    // Separateurs : 8 modules a l'horizontale, 7 a la verticale, pour chacun des trois
    // motifs de recherche. Le separateur horizontal du motif haut-droit est
    // (size-8 .. size-1, 7) : l'oublier decale tout l'ordre de placement, et le test
    // accuse alors un module « different » qui ne l'est pas.
    for (int i = 0; i < 8; i++) {
      R[7][i] = true; R[i][7] = true;                            // haut-gauche
      R[size - 8][i] = true; R[size - 8 + i][7] = true;          // haut-droit
      R[7][size - 8 + i] = true; R[i][size - 8] = true;          // bas-gauche
    }
    for (int y = 0; y < 7; y++) {
      R[y][7] = true; R[y][size - 8] = true; R[size - 7 + y][7] = true;
    }
    for (int y = 0; y < 7; y++) R[y][size - 8] = true;
    R[size - 8][8] = true;
    for (int i = 8; i < size - 8; i++) { R[6][i] = true; R[i][6] = true; }
    for (int i = 0; i <= 8; i++) if (i != 6) { R[8][i] = true; if (i < 8) R[i][8] = true; }
    for (int i = 0; i < 8; i++) R[8][size - 1 - i] = true;
    for (int i = 8; i < 15; i++) R[size - 15 + i][8] = true;
    int[][] centres = {{}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}};
    for (int cy : centres[version - 1]) {
      for (int cx : centres[version - 1]) {
        if ((cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)) continue;
        for (int d = -2; d <= 2; d++) for (int e = -2; e <= 2; e++) R[cy + d][cx + e] = true;
      }
    }
    return R;
  }

  /** L'ordre de placement des modules de donnee, comme dans l'encodeur. */
  private static List<int[]> parcours(boolean[][] R, int size) {
    List<int[]> ordre = new ArrayList<>();
    int x = size - 1, y = size - 1, d = -1;
    while (x > 0) {
      if (x == 6) x -= 1;
      while (y >= 0 && y < size) {
        for (int i = 0; i < 2; i++) {
          int xx = x - i;
          if (!R[y][xx]) ordre.add(new int[] {xx, y});
        }
        y += d;
      }
      d = -d; y += d; x -= 2;
    }
    return ordre;
  }

  public static void main(String[] args) throws Exception {
    Path dir = Paths.get(args[0]);
    // Le fichier de choix tient une ligne par cas : nom, version, masque — tabules.
    List<String> cas = new ArrayList<>(
        Arrays.asList(Files.readString(dir.resolve("choix.tsv")).trim().split("\n")));
    QRCodeReader lecteur = new QRCodeReader();
    int identiques = 0, lus = 0, bitsReste = 0;
    List<String> ecarts = new ArrayList<>();
    List<String> refus = new ArrayList<>();

    for (String ligne : cas) {
      if (ligne.isBlank()) continue;
      String[] champs = ligne.split("\t");
      String nom = champs[0];
      int version = Integer.parseInt(champs[1]);
      String contenu = Files.readString(dir.resolve(nom + ".txt"));
      int masque = Integer.parseInt(champs[2]);
      var qr = Encoder.encode(contenu, ErrorCorrectionLevel.L);
      var m = qr.getMatrix();
      int size = m.getWidth();

      String[] lignes = Files.readString(dir.resolve(nom + ".qr")).split("\\n");
      int notreTaille = Integer.parseInt(lignes[0].trim());
      String plat = lignes[1].replace("\r", "");

      if (notreTaille != size) {
        ecarts.add(nom + " : taille " + notreTaille + " contre " + size + " chez zxing");
        continue;
      }

      // Les bits de reste sont les derniers modules du parcours : ils ne portent aucun
      // mot de code, donc ils ne sont pas comptes comme un ecart.
      List<int[]> ordre = parcours(fonction(size, version), size);
      int[] totalParVersion = {26, 44, 70, 100, 134, 172};
      int bitsUtiles = totalParVersion[version - 1] * 8;
      boolean[][] reste = new boolean[size][size];
      for (int i = bitsUtiles; i < ordre.size(); i++) reste[ordre.get(i)[1]][ordre.get(i)[0]] = true;
      bitsReste += ordre.size() - bitsUtiles;

      int differents = 0;
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          if (reste[y][x]) continue;
          if ((m.get(x, y) != 0) != (plat.charAt(y * size + x) == '#')) differents++;
        }
      }
      if (differents == 0) identiques++;
      else ecarts.add(nom + " (masque " + masque + ") : " + differents + " modules sur " + (size * size) + " differentent de zxing");

      int scale = 4, quiet = 4;
      int w = (size + quiet * 2) * scale, h = (size + quiet * 2) * scale;
      BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          int mx = x / scale - quiet, my = y / scale - quiet;
          boolean noir = (mx >= 0 && my >= 0 && mx < size && my < size) && plat.charAt(my * size + mx) == '#';
          img.setRGB(x, y, noir ? 0x000000 : 0xFFFFFF);
        }
      }
      var src = new RGBLuminanceSource(w, h, img.getRGB(0, 0, w, h, null, w, w));
      try {
        var r = lecteur.decode(new BinaryBitmap(new HybridBinarizer(src)));
        if (r.getText().equals(contenu)) lus++;
        else refus.add(nom + " : texte relu incorrect");
      } catch (Exception e) {
        refus.add(nom + " : " + e.getClass().getSimpleName());
      }
    }

    System.out.println("Modules identiques a zxing, a masque impose : " + identiques + "/" + cas.size());
    System.out.println("  (bits de reste exclus : " + bitsReste + ", ils ne portent aucun mot de code)");
    System.out.println("Relues par zxing                            : " + lus + "/" + cas.size());
    if (!refus.isEmpty()) {
      System.out.println("Matrices non relues par zxing : " + refus.size());
    }
    for (String e : ecarts) System.out.println("  ECART " + e);
    for (String r : refus) System.out.println("  REFUS " + r);
    if (!ecarts.isEmpty()) System.exit(1);
  }
}
JAVA
javac -cp "$JAR" -d "$ATELIER" "$ATELIER/Verdict.java"

# Enchainement : les contenus, puis zxing pour les choix de masque, puis Tether avec le
# masque impose, puis la comparaison et la relecture.
echo "Verification externe, $((FIN - DEBUT + 1)) cas :"
node --experimental-strip-types "$ATELIER/contenus.mjs" "$DEBUT" "$FIN" "$ATELIER" 2>&1 | grep -v ExperimentalWarning || true
java -cp "$JAR:$ATELIER" Choix "$ATELIER" "$ATELIER/choix.tsv"
node --experimental-strip-types "$ATELIER/matrices.mjs" "$ATELIER" "$ATELIER/choix.tsv" 2>&1 | grep -v ExperimentalWarning || true
java -cp "$JAR:$ATELIER" Verdict "$ATELIER"
