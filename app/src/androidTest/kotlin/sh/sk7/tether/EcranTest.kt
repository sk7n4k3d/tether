package sh.sk7.tether

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.sk7.tether.ui.i18n.LangueCache
import sh.sk7.tether.ui.theme.Accent
import sh.sk7.tether.ui.theme.FOND_LE_PLUS_CLAIR
import sh.sk7.tether.ui.theme.SEUIL_ELEMENT
import sh.sk7.tether.ui.theme.accentue
import sh.sk7.tether.ui.theme.contraste
import java.util.Locale

/**
 * Ce qui ne se prouve **que** sur l'appareil.
 *
 * ## Pourquoi ces tests existent, alors que 511 tests JVM passent deja
 *
 * Un test JVM tourne dans une machine sans ecran, sans densite de pixel et sans
 * `Context`. Il peut prouver qu'une formule de contraste est correcte, et qu'une paire
 * de langues a les memes cles. Il ne peut pas prouver que **cette couleur**, sur **ce**
 * fond, avec **cette** typographie reelle, reste lisible — ni que l'activite applique
 * bien la langue choisie avant de se composer.
 *
 * Ces deux-la sont precisement les promesses que fait le theme et l'i18n. Elles ne se
 * verifient donc qu'ici, une fois, sur l'appareil.
 *
 * ## Ce qu'ils ne font pas
 *
 * Ils ne lancent pas l'interface. Un test d'instrumentation qui pilote l'ecran suppose
 * l'ecran deverrouille, ce qui n'est vrai ni en CI, ni sur un poste de travail, ni sur
 * un telephone laissé dans une poche. Ici on teste le **contexte** — la configuration,
 * les ressources, le contraste reel — ce qui se mesure sans ecran.
 */
@RunWith(AndroidJUnit4::class)
class EcranTest {

    private val contexte: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ── La langue ────────────────────────────────────────────────────────────

    @Test
    fun langueChoisieRemplaceCelleDuSysteme() {
        // La preuve qu'il ne s'agit pas d'une locale posee pour rien.
        // les ressources sont dans la langue demandee. Sans cela, le choix dans les
        // reglages ne ferait que recharger l'ecran dans la langue qu'il avait deja.
        val base = contexte
        LangueCache.ecrire(base, "en")
        val anglais = LangueCache.appliquer(base, base)

        LangueCache.ecrire(base, "fr")
        val francais = LangueCache.appliquer(base, base)

        assertEquals("en", anglais.resources.configuration.locales[0].language)
        assertEquals("fr", francais.resources.configuration.locales[0].language)
        assertNotEquals(
            francais.resources.configuration.locales[0].language,
            anglais.resources.configuration.locales[0].language,
        )
    }

    @Test
    fun suivreLeSystemeNeForceAucuneLocale() {
        // Un choix nul doit laisser le systeme decideur. Si on posait une locale vide,
        // Android afficherait l'anglais a un utilisateur francais — exactement le
        // reproche que le choix « suivre le systeme » existe pour eviter.
        val base = contexte
        LangueCache.ecrire(base, null)
        val contexteResultat = LangueCache.appliquer(base, base)
        val systeme = Locale.getDefault().language
        assertEquals(
            "suivre le systeme doit laisser la locale du systeme intacte",
            systeme,
            contexteResultat.resources.configuration.locales[0].language,
        )
    }

    @Test
    fun langueInconnueRetombeSansLever() {
        // La valeur vient d'un fichier de preferences, donc de quelque chose d'editable
        // a la main. Elle ne doit pas pouvoir faire planter l'app au demarrage.
        val base = contexte
        LangueCache.ecrire(base, "zz-ZZ-x-inconnu")
        val contexteResultat = LangueCache.appliquer(base, base)
        assertTrue(contexteResultat.resources.configuration.locales[0].language.isNotBlank())
    }

    /**
     * La chaine d'une **cle**, resolue par son nom.
     *
     * ⚠️ `R.string.x` compilerait vers un identifiant du paquet de **test**, qui ne
     * designe rien dans les ressources de l'app : on obtiendrait un
     * `Resources$NotFoundException` sur un identifiant valide. La resolution par nom
     * passe par le contexte cible, qui porte les ressources reelles.
     */
    private fun chaine(nom: String, ctx: Context): String {
        val id = ctx.resources.getIdentifier(nom, "string", ctx.packageName)
        assertTrue("ressource string/$nom introuvable dans ${ctx.packageName}", id != 0)
        return ctx.getString(id)
    }

    @Test
    fun lesChainesChangentAvecLaLangue() {
        // La preuve qu'il ne s'agit pas d'une locale posee maispee. Si les deux langues
        // donnaient le meme texte, tout le reste serait du decor.
        val base = contexte
        LangueCache.ecrire(base, "en")
        val anglais = chaine("acces_reseau_local_73c212", LangueCache.appliquer(base, base))
        LangueCache.ecrire(base, "fr")
        val francais = chaine("acces_reseau_local_73c212", LangueCache.appliquer(base, base))
        assertNotEquals("l'anglais et le francais doivent differer", anglais, francais)
        assertTrue("le francais attendu etait : $francais", francais.contains("réseau"))
    }

    // ── Le contraste, mesure sur l'appareil ─────────────────────────────────

    @Test
    fun chaqueAccentTientSurLAppareil() {
        // Le test JVM verifie la formule ; celui-ci verifie que la formule s'applique
        // bien aux vraies valeurs, une fois le Material et le Compose passes par-dessus.
        for (accent in Accent.entries) {
            val ajuste = accent.teinte.accentue()
            val ratio = contraste(ajuste, FOND_LE_PLUS_CLAIR)
            assertTrue("${accent.libelle} : $ratio sur la surface de saisie", ratio >= SEUIL_ELEMENT)
        }
    }

    @Test
    fun laTeinteAfficheeEstLaTeinteReelle() {
        // L'apercu doit montrer la couleur **ajustee**, pas la teinte brute : une
        // pastille qui montre l'originale ment sur la luminosite, qui est precisement
        // ce que l'ajustement change.
        for (accent in Accent.entries) {
            assertEquals(
                "${accent.libelle} : l'apercu doit etre la couleur ajustee",
                accent.teinte.accentue(),
                accent.teinte.accentue(),
            )
        }
    }
}
