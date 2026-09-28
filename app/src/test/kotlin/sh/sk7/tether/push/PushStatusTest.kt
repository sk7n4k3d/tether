package sh.sk7.tether.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **Ce que la section « Notifications » a le droit d'affirmer.**
 *
 * ### Pourquoi ces tests portent sur une fonction pure
 * ⚠️ La regle du projet est « ne jamais mentir a l'utilisateur », et cette section est l'endroit
 * ou le mensonge est le plus tentant : il suffirait d'ecrire « connecté » des qu'un distributeur
 * est retenu. Or un distributeur retenu ne prouve **rien** sur la reception — il manque peut-etre
 * la permission systeme, ou l'endpoint n'est jamais arrive. Ces trois faits se testent ici, sans
 * Android, exactement comme `decideNotification` dans [PushPolicyTest].
 */
/**
 * [describePushStatus] avec un resolveur qui renvoie l'identifiant brut.
 *
 * Les tests comparent la **cle de resource**, pas le texte : un test qui comparerait
 * du francais casserait a la premiere reecriture, et un test qui materialiserait la
 * traduction exigerait un contexte Android. La cle est ce qui compte — elle dit quel
 * message est choisi, et c'est la decision que la fonction arbitre.
 */
private fun describePushStatusIsole(status: PushStatus): PushVerdict =
    describePushStatus(status) { id -> "res:$id" }

class PushStatusTest {

    // ------------------------------------------------------------------
    // L'ORDRE DES CAS : c'est la logique, pas un detail d'ecriture
    // ------------------------------------------------------------------

    @Test
    fun `aucun distributeur interdit d annoncer connecte meme avec un endpoint`() {
        // ⚠️ Scenario reel : l'app a ete reinstallee apres avoir publie un endpoint (le
        // `SharedPreferences` survit), mais ntfy a ete desinstalle. Sans l'ordre des cas, on
        // annoncerait « connecté » sur un telephone qui ne recevra **jamais** rien.
        val verdict = describePushStatusIsole(
            PushStatus(distributor = null, notificationsAllowed = true, endpoint = "https://ntfy/x"),
        )
        assertEquals(PushStateKind.NoDistributor, verdict.kind)
        assertEquals(PushTone.Blocked, verdict.tone)
        // Rien a reessayer : il manque une application, pas une seconde tentative.
        assertFalse(verdict.retryable)
    }

    @Test
    fun `la permission refusee prime sur un endpoint present`() {
        // ⚠️ LE piege que cette section existe pour fermer : tout est « en place » sauf la
        // permission, et Android ne dit **rien** — `notify()` ne leve pas, rien ne s'affiche.
        // Un test qui passerait par l'etat « connecté » ici validerait precisement le mensonge.
        val verdict = describePushStatusIsole(
            PushStatus(
                distributor = "io.heckel.ntfy",
                notificationsAllowed = false,
                endpoint = "https://ntfy/x",
            ),
        )
        assertEquals(PushStateKind.PermissionDenied, verdict.kind)
        assertEquals(PushTone.Blocked, verdict.tone)
    }

    @Test
    fun `distributeur et permission sans endpoint restent en attente`() {
        // ⚠️ Un distributeur retenu n'est qu'une **capacite** : sans endpoint, le serveur opencode
        // n'a rien a publier. On ne dit pas « connecté », et on propose de reconnecter.
        val verdict = describePushStatusIsole(
            PushStatus(
                distributor = "io.heckel.ntfy",
                notificationsAllowed = true,
                endpoint = null,
            ),
        )
        assertEquals(PushStateKind.AwaitingEndpoint, verdict.kind)
        assertEquals(PushTone.Pending, verdict.tone)
        assertTrue(verdict.retryable, "un endpoint peut arriver apres une reconnexion")
    }

    @Test
    fun `les trois conditions reunies donnent connecte`() {
        val status = PushStatus(
            distributor = "io.heckel.ntfy",
            notificationsAllowed = true,
            endpoint = "https://ntfy.sh/upAbCdEf",
        )
        val verdict = describePushStatusIsole(status)
        assertEquals(PushStateKind.Ready, verdict.kind)
        assertEquals(PushTone.Ready, verdict.tone)
        assertTrue(status.isReady)
    }

    // ------------------------------------------------------------------
    // LE DETAIL DIT QUOI FAIRE, PAS SEULEMENT QUE CA NE VA PAS
    // ------------------------------------------------------------------

    @Test
    fun `le cas sans distributeur et le cas pret n ont pas le meme detail`() {
        // ⚠️ « aucun distributeur UnifiedPush » seul laisse l'utilisateur sans piste. La
        // garantie utile ici est que l'etat « rien a installer » ne dit pas la meme chose que
        // l'etat « pret » : un detail partage entre les deux ferait croire que tout va bien.
        //
        // On ne compare pas le **texte** : a l'execution Android il n'existe que traduit, et un
        // test qui exigerait du francais casserait a la premiere traduction. Ce qu'on garantit
        // ici, c'est la **separation** des messages. Le contenu des chaines, y compris « le
        // detail nomme ntfy », est verifie par `RessourceTest`, sur le fichier lui-meme.
        val sans = describePushStatusIsole(PushStatus(null, true, null))
        val pret = describePushStatusIsole(PushStatus("io.heckel.ntfy", true, "https://ntfy/x"))
        assertNotEquals(sans.detail, pret.detail, "les deux etats doivent avoir des details distincts")
        assertNotEquals(sans.label, pret.label, "les deux etats doivent avoir des libelles distincts")
    }

    @Test
    fun `chaque verdict porte un libelle et un detail non vides`() {
        // ⚠️ Un libelle vide serait un etat **muet** : l'utilisateur verrait une pastille sans
        // savoir ce qu'elle dit. On interdit le cas pour les quatre etats, pas seulement celui
        // qu'on vient d'ecrire.
        val statuses = listOf(
            PushStatus(null, true, null),
            PushStatus("io.heckel.ntfy", false, "https://ntfy/x"),
            PushStatus("io.heckel.ntfy", true, null),
            PushStatus("io.heckel.ntfy", true, "https://ntfy/x"),
        )
        statuses.forEach { status ->
            val verdict = describePushStatusIsole(status)
            assertTrue(verdict.label.isNotBlank(), "libelle vide pour $status")
            assertTrue(verdict.detail.isNotBlank(), "detail vide pour $status")
        }
    }

    @Test
    fun `les quatre etats sont distincts et tous atteignables`() {
        // ⚠️ Verrou contre un `when` qui replierait deux cas sur le meme resultat : ca se voit a la
        // lecture, mais pas a l'ecriture — et ca priverait l'UI d'une action.
        val kinds = listOf(
            PushStatus(null, true, null),
            PushStatus("io.heckel.ntfy", false, "https://ntfy/x"),
            PushStatus("io.heckel.ntfy", true, null),
            PushStatus("io.heckel.ntfy", true, "https://ntfy/x"),
        ).map { describePushStatusIsole(it).kind }.toSet()
        assertEquals(
            setOf(
                PushStateKind.NoDistributor,
                PushStateKind.PermissionDenied,
                PushStateKind.AwaitingEndpoint,
                PushStateKind.Ready,
            ),
            kinds,
        )
    }

    @Test
    fun `isReady exige les trois conditions`() {
        // ⚠️ On teste le predicat lui-meme : il sert hors de `describePushStatus` (l'UI s'en sert
        // pour decider si elle montre l'avertissement), donc il doit etre juste tout seul.
        assertTrue(PushStatus("d", true, "e").isReady)
        assertFalse(PushStatus(null, true, "e").isReady)
        assertFalse(PushStatus("d", false, "e").isReady)
        assertFalse(PushStatus("d", true, null).isReady)
    }

    // ------------------------------------------------------------------
    // L'ENDPOINT N'EST PAS AFFICHE EN ENTIER : c'est une capacite d'ecriture
    // ------------------------------------------------------------------

    @Test
    fun `l endpoint affiche est tronque avec un prefixe reconnaissable`() {
        // ⚠️ Qui connait l'endpoint peut publier sur le topic : c'est une capacite d'ecriture. On
        // n'en met qu'un fragment a l'ecran — assez pour reconnaitre qu'il existe, pas assez pour
        // le reutiliser. Le test verrouille les deux moities de la propriete.
        val endpoint = "https://ntfy.sh/upAbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
        val hint = PushStatus("io.heckel.ntfy", true, endpoint).endpointHint()
        requireNotNull(hint)
        assertTrue(hint.startsWith("https://ntfy.sh/up"), "le prefixe doit rester : $hint")
        assertTrue(hint.endsWith("…"), "la troncature doit se voir : $hint")
        assertTrue(endpoint.length > hint.length, "on ne doit pas exposer l'endpoint entier")
        assertFalse(hint.contains("0123456789"), "la fin de l'endpoint ne doit pas fuiter : $hint")
    }

    @Test
    fun `un endpoint court n est pas tronque a vide`() {
        // ⚠️ Cas limite : tronquer un endpoint deja court ne doit pas produire une chaine vide —
        // l'UI afficherait « endpoint : » sans rien, ce qui est pire que rien.
        val hint = PushStatus("io.heckel.ntfy", true, "up1").endpointHint()
        assertEquals("up1", hint)
    }

    @Test
    fun `sans endpoint il n y a pas d apercu`() {
        // ⚠️ `null` et non chaine vide : l'UI cache la ligne, elle n'affiche pas un libelle vide.
        assertNull(PushStatus("io.heckel.ntfy", true, null).endpointHint())
    }

    // ------------------------------------------------------------------
    // ENREGISTREMENT : NE PAS CONFONDRE « RIEN A INSTALLER » ET « ECHEC »
    // ------------------------------------------------------------------

    @Test
    fun `sans distributeur le resultat est no distributor, pas failed`() {
        // ⚠️ `registerForPush` rend le meme `false` dans les deux cas. Si on traduisait
        // directement en « Failed », l'UI dirait « echec » a quelqu'un a qui il manque juste
        // ntfy — et lui proposerait de reessayer, ce qui ne peut pas aboutir.
        assertEquals(
            PushRegistrationResult.NoDistributor,
            registrationOutcome(hasDistributor = false, success = false),
        )
    }

    @Test
    fun `un distributeur absent prime sur un succes rapporte`() {
        // ⚠️ Cas defensif : un `success = true` avec aucun distributeur est incoherent, et on ne
        // veut pas qu'il devienne « Requested ». L'absence est un fait verifie localement.
        assertEquals(
            PushRegistrationResult.NoDistributor,
            registrationOutcome(hasDistributor = false, success = true),
        )
    }

    @Test
    fun `avec distributeur le resultat suit le callback`() {
        assertEquals(
            PushRegistrationResult.Requested,
            registrationOutcome(hasDistributor = true, success = true),
        )
        // ⚠️ Distributeur present mais resolution refusee : c'est bien un echec, retentable.
        assertEquals(
            PushRegistrationResult.Failed,
            registrationOutcome(hasDistributor = true, success = false),
        )
    }

    @Test
    fun `le message sans distributeur dit quoi installer`() {
        // ⚠️ On verrouille le contenu, pas seulement sa presence : « échec » ou « aucun
        // distributeur » sans nom d'application laisse l'utilisateur sans geste a faire.
        val message = registrationMessage(PushRegistrationResult.NoDistributor)
        assertTrue(message.contains("ntfy"), "l'application a installer doit etre nommee : $message")
    }

    @Test
    fun `chaque resultat d enregistrement a un message non vide`() {
        // ⚠️ Un resultat muet remettrait l'UI dans l'etat qu'on cherche a eviter : un bouton qui
        // semble ne rien faire.
        PushRegistrationResult.entries.forEach { result ->
            assertTrue(
                registrationMessage(result).isNotBlank(),
                "message vide pour $result",
            )
        }
    }
}
