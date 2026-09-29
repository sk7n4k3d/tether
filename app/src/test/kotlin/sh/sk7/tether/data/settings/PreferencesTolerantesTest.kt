package sh.sk7.tether.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * **Un fichier de preferences douteux ne doit pas fermer l'application.**
 *
 * Le defaut a ete rencontre en production, pas imagine : un outil avait ecrit une chaine
 * dans la cle booleenne de l'accueil. Le fichier restait un protobuf valide, mais
 * `prefs[cle]` levait une `ClassCastException` au demarrage — **avant le premier ecran**,
 * donc avant tout moyen de reparer.
 *
 * ### Pourquoi un vrai fichier, et pas un faux `Preferences`
 *
 * `Preferences` est une classe, pas une interface : on ne peut pas la simuler. Et c'est
 * tant mieux — un faux objet testerait ma comprehension du bug, alors qu'un fichier
 * protobuf ecrit a la main teste **exactement** ce qui s'est passe : le type stocke ne
 * correspond pas au type demande, et le DataStore le rend tel quel.
 *
 * Le fichier est construit au niveau des octets, parce que c'est ce que l'API typée
 * interdit : `mutablePreferencesOf` refuse d'ecrire une chaine sous une cle booleenne,
 * et c'est precisement l'etat incoherent qu'on veut eprouver.
 */
class PreferencesTolerantesTest {

    /**
     * Une entree du `PreferenceMap`, au format protobuf de DataStore.
     *
     * `PreferenceMap { map<string, Value> preferences = 1 }`
     * `MapEntry { string key = 1 ; Value value = 2 }`
     * `Value { Type type = 1 ; string string = 5 }`
     *
     * ⚠️ `type = 1` est `STRING`, quel que soit le type que le code attendra a la
     * lecture. C'est tout l'interet : on ecrit un type, l'app en demande un autre.
     */
    private fun fichierAvecChaine(cle: String, valeur: String): ByteArray {
        val k = cle.toByteArray()
        val v = valeur.toByteArray()
        val value = byteArrayOf(0x08, 0x01) + byteArrayOf(0x2A, v.size.toByte()) + v
        val entree = byteArrayOf(0x0A, k.size.toByte()) + k +
            byteArrayOf(0x12, value.size.toByte()) + value
        return byteArrayOf(0x0A, entree.size.toByte()) + entree
    }

    private fun storeAvec(octets: ByteArray): DataStore<Preferences> {
        val fichier = File.createTempFile("tether-test", ".preferences_pb")
        fichier.deleteOnExit()
        fichier.writeBytes(octets)
        return PreferenceDataStoreFactory.create(produceFile = { fichier })
    }

    @Test
    fun `une chaine sous la cle booleenne de l accueil ne ferme pas l app`() = runBlocking {
        // Le cas exact du plantage : l'app attend un Boolean, le fichier porte un String.
        // Avant le correctif, cette lecture levait et l'activity terminait.
        val store = storeAvec(fichierAvecChaine("appearance.accueilVu", "true"))
        val apparence = AppearanceStore(store)

        assertFalse(
            apparence.accueilVu.first(),
            "la cle fautive perd sa valeur, l'app continue",
        )
    }

    @Test
    fun `les autres cles restent lisibles malgre une cle fautive`() = runBlocking {
        // ⚠️ Le point qui distingue ce correctif d'un `catch` global : une seule cle
        // douteuse ne doit pas faire retomber **toutes** les preferences a leur defaut.
        // Quelqu'un dont l'accueil a ete mal ecrit garderait sinon son accent perdu.
        val octets = fichierAvecChaine("appearance.accueilVu", "true") +
            fichierAvecChaine("appearance.accent", "azur")
        val apparence = AppearanceStore(storeAvec(octets))

        assertFalse(apparence.accueilVu.first())
        assertEquals(
            "azur",
            apparence.accent.first().cle,
            "l'accent, ecrit correctement, doit survivre a la cle fautive",
        )
    }

    @Test
    fun `un accent parfaitement lisible est rendu tel quel`() = runBlocking {
        // Le durcissement ne rend pas tout illisible : une preference valide reste une
        // preference. Un `catch` trop large aurait transforme l'app en coquille vide.
        val apparence = AppearanceStore(storeAvec(fichierAvecChaine("appearance.accent", "rouge")))

        assertEquals("rouge", apparence.accent.first().cle)
        assertTrue(apparence.accueilVu.first().not())
    }
}
