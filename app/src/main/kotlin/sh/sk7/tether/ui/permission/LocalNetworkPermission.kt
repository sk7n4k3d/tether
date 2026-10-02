package sh.sk7.tether.ui.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * Permission d'acces au reseau local (Android 16+ runtime, **obligatoire** pour les apps
 * ciblant SDK 37).
 *
 * ⚠️ Sans elle, Android **abandonne silencieusement** les paquets vers une adresse privee
 * (`10.x`, `192.168.x`) : l'app voit une `ConnectException` alors que le meme `nc` depuis
 * le shell fonctionne. La boucle locale (`127.0.0.1`) est exemptee — ce qui rend le
 * diagnostic particulierement trompeur.
 */
object LocalNetworkPermission {

    /** Nom litteral : la constante `Manifest.permission.ACCESS_LOCAL_NETWORK` n'existe pas
     *  dans le SDK compile si celui-ci est anterieur, et une chaine evite une dependance. */
    const val NAME: String = "android.permission.ACCESS_LOCAL_NETWORK"

    /** La permission n'est appliquee qu'a partir d'Android 16 (API 36). */
    val isApplicable: Boolean get() = Build.VERSION.SDK_INT >= 36

    fun isGranted(context: android.content.Context): Boolean =
        !isApplicable || ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}

/**
 * Porte d'entree : demande la permission au premier affichage et n'affiche [content]
 * que lorsqu'elle est accordee. En cas de refus, un ecran explique quoi faire — l'app ne
 * peut pas joindre le serveur LAN sans elle.
 */
@Composable
fun LocalNetworkGate(content: @Composable () -> Unit) {
    if (!LocalNetworkPermission.isApplicable) {
        content()
        return
    }

    val context = LocalContext.current
    var granted by remember { mutableStateOf(LocalNetworkPermission.isGranted(context)) }
    var requested by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        if (!result) permanentlyDenied = true
    }

    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            launcher.launch(LocalNetworkPermission.NAME)
        }
    }

    if (granted) {
        content()
        return
    }

    PermissionRefused(
        onRetry = { launcher.launch(LocalNetworkPermission.NAME) },
        onOpenSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

@Composable
private fun PermissionRefused(onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.acces_reseau_local_73c212),
            style = MaterialTheme.typography.titleMedium,
            color = TetherTextPrimary,
        )
        Text(stringResource(R.string.android_bloque_defaut_1992b8) +
                  " " + stringResource(R.string.sans_autorisation_tether_efa72b) +
                 " " + stringResource(R.string.t_192_ca76d6),
            style = MaterialTheme.typography.bodyMedium,
            color = TetherTextSecondary,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) {
            // ⚠️ Pas de couleur explicite : le Button M3 pose deja un `containerColor`
            // d'accent ; forcer le libelle en couleur d'accent mettait la meme teinte
            // sur la meme teinte — un texte fantomme illisible (contraste ~1:1).
            Text(stringResource(R.string.autoriser_ff8398))
        }
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.ouvrir_reglages_systeme_dc1d26)) }
        Text(stringResource(R.string.systeme_propose_demande_3449aa) +
                 " " + stringResource(R.string.permissions_application_be85c6),
            style = MaterialTheme.typography.bodySmall,
            color = TetherAlert,
            textAlign = TextAlign.Center,
        )
    }
}
