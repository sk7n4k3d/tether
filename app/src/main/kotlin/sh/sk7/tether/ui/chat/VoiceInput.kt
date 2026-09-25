package sh.sk7.tether.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * **La dictee vocale, par le systeme — sans Play Services.**
 *
 * ### Pourquoi cette approche et pas une librairie
 * Tether tourne sur un Pixel sous GrapheneOS : **aucune dependance Play Services** n'est
 * acceptable. Or c'est exactement la demande la plus votee cote saisie mobile (issue #4695,
 * **207 👍**) — dicter est infiniment plus confortable que taper sur un clavier de telephone.
 *
 * ⚠️ On passe donc par `RecognizerIntent`, l'intent **standard du systeme**. Ce n'est pas un
 * contournement : c'est la fonction officielle d'Android, qui fonctionne avec n'importe quel
 * moteur de reconnaissance installe (le moteur AOSP sur GrapheneOS, ou un autre).
 *
 * ⚠️ `EXTRA_LANGUAGE` est rempli avec la **locale du systeme**, pas « fr-FR » en dur : un
 * utilisateur configure en anglais dicte en anglais, et forcer le francais donnerait des
 * transcriptions absurdes sans que rien n'explique pourquoi.
 *
 * ⚠️ **Deux sorties distinctes** : [onResult] quand une transcription arrive, [onCancel] dans tous
 * les autres cas — annulation par l'utilisateur, mais aussi **absence de moteur de dictee**. On ne
 * confond pas « l'utilisateur a change d'avis » et « ce telephone ne sait pas dicter » : le second
 * cas doit se dire, et c'est a l'appelant de le faire avec le contexte qu'il a.
 *
 * ⚠️ Un `ActivityNotFoundException` est **avale** ici et transforme en [onCancel]. C'est le seul
 * traitement possible : sans moteur installe, il n'y a rien a lancer. Le signaler par une
 * exception ferait planter l'ecran pour une fonction accessoire.
 */
/**
 * **Cet appareil sait-il dicter ?**
 *
 * ⚠️ Afficher un micro qui ne peut rien faire est un mensonge visuel — la regle tenue partout
 * ailleurs dans Tether (« aucun controle qui ne peut pas fonctionner »). Et ce n'est pas un cas
 * theorique : mesure du 2026-09-25 sur le Pixel de test, **aucun moteur de reconnaissance n'est
 * installe** (`pm resolve-activity -a android.speech.action.RECOGNIZE_SPEECH` -> « No activity
 * found »). C'est le comportement normal de GrapheneOS, qui ne livre pas les moteurs Google.
 *
 * ⚠️ On interroge le `PackageManager` plutot que de tenter le lancement : une tentative ratée
 * ferait clignoter le dialogue systeme sur certains appareils, et surtout elle ne permettrait pas
 * de **masquer** le bouton avant que l'utilisateur ne le presse.
 */
fun isVoiceInputAvailable(context: android.content.Context): Boolean = runCatching {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    context.packageManager.resolveActivity(intent, 0) != null
}.getOrDefault(false)

@Composable
fun VoiceInput(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            onCancel()
            return@rememberLauncherForActivityResult
        }
        // ⚠️ `RESULTS_RECOGNITION` est une LISTE : le moteur peut proposer plusieurs hypotheses.
        // On prend la premiere, qui est celle qu'il tient pour la plus probable — le choix parmi
        // les autres n'apporterait rien a la saisie.
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        onResult(spoken)
    }

    // ⚠️ Lancement dans un effet, sans cle : la dictee est un evenement, pas un etat. Relancer a
    // chaque recomposition ouvrirait le dialogue en boucle.
    LaunchedEffect(Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dicte ton message")
        }
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // Aucun moteur de reconnaissance installe. On rend la main proprement plutot que de
            // faire tomber l'ecran pour une fonction accessoire.
            onCancel()
        }
    }
}
