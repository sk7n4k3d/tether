package sh.sk7.tether.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat

/**
 * **Demande la permission de notifier**, une seule fois, au bon moment.
 *
 * ### Pourquoi c'est indispensable
 * Mesure sur le Pixel : toute la chaine UnifiedPush fonctionnait (endpoint recu, message recu)
 * mais `notify()` **ne levait pas** et la notification ne s'affichait pas — parce que
 * `POST_NOTIFICATIONS` n'etait pas accordee. Android ne signale rien : c'est un silence, et
 * c'est le pire mode d'echec possible.
 *
 * ### Pourquoi ici et pas au demarrage brut
 * La demande est faite quand l'ecran principal est compose : l'utilisateur a l'app sous les
 * yeux, il comprend ce qu'on lui demande. Une demande avant tout affichage (« au premier
 * pixel ») a un taux de refus bien plus eleve, et **Android la refuse definitivement apres
 * deux refus** — il n'y a pas de seconde chance.
 *
 * ⚠️ On ne demande **rien** en dessous d'Android 13 : la permission n'existe pas, et
 * `checkSelfPermission` renverrait faux a tort.
 */
@Composable
fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContextCompat()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* refuse : on n'insiste pas, l'app reste utilisable sans notifications */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** `LocalContext` expose via une fonction, pour garder le composable testable. */
@Composable
private fun LocalContextCompat(): Context =
    androidx.compose.ui.platform.LocalContext.current
