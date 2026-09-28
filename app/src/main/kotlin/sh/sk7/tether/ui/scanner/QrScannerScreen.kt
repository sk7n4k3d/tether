package sh.sk7.tether.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import sh.sk7.tether.R
import sh.sk7.tether.push.PairingLink
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextPrimary

/**
 * **Le scanner du QR d'appairage.**
 *
 * Il lit le QR que `/tether` affiche dans le terminal et rend une
 * [PairingLink.Demande] — le meme objet que le deep link `opencode://pair`. La suite ne
 * change pas : l'ecran de confirmation affiche l'adresse du serveur, et **rien n'est
 * envoye** avant un appui sur « Autoriser ».
 *
 * ## Ce que le scanner ne fait pas
 *
 * Il ne decode pas les QR d'autres formats : `opencode://` uniquement. Un QR de
 * credentials (`opencode pair`) ou un lien quelconque est ignore, avec un message.
 * Envoyer une adresse inventee a l'appairage serait exactement l'attaque que
 * [PairingLink] existe pour arreter.
 *
 * ## La camera est optionnelle
 *
 * L'app fonctionne entierement sans elle : le deep link `opencode://pair` reste un chemin
 * d'appairage complet, scanne par n'importe quelle autre application. Un refus de
 * permission n'est donc pas une impasse, et l'ecran le dit.
 */
@Composable
fun QrScannerScreen(
    onLien: (PairingLink.Demande) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var accordee by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var dejaDemande by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accordee = it }

    LaunchedEffect(Unit) {
        if (!accordee && !dejaDemande) {
            dejaDemande = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (accordee) {
            CameraApercu(onLien = onLien)
        } else {
            CameraRefusee(
                onRetry = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { ouvrirReglages(context) },
            )
        }

        // Le cadre : il ne decode rien, il dit ou viser. Le QR doit tenir dedans pour
        // etre lu de facon fiable.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(260.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, TetherTextPrimary.copy(alpha = 0.6f), RoundedCornerShape(20.dp)),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.scanner_qr_1f4e1d),
                style = MaterialTheme.typography.titleMedium,
                color = TetherTextPrimary,
            )
            Text(
                text = stringResource(R.string.scanner_consigne_7c21a8),
                style = MaterialTheme.typography.bodyMedium,
                color = TetherTextMuted,
                textAlign = TextAlign.Center,
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(
                imageVector = Lucide.ArrowLeft,
                contentDescription = stringResource(R.string.retour_e5befb),
                tint = TetherTextPrimary,
            )
        }
    }
}

/**
 * L'apercu camera et l'analyse, separes de l'ecran pour que son corps reste lisible.
 *
 * ⚠️ `onLien` est appele **une seule fois** : le drapeau `consomme` est pose avant l'appel,
 * sinon les ~30 images par seconde qui suivent rappelleraient le rappel et empileraient
 * autant de navigations.
 */
@Composable
private fun CameraApercu(onLien: (PairingLink.Demande) -> Unit) {
    val context = LocalContext.current
    val cycleDeVie = LocalLifecycleOwner.current
    val principal = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyseur = remember { Executors.newSingleThreadExecutor() }
    val consomme = remember { AtomicBoolean(false) }
    val averti = remember { AtomicBoolean(false) }
    var avertissement by remember { mutableStateOf(false) }
    var erreur by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { analyseur.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val vue = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    val provider = try {
                        future.get()
                    } catch (cause: Exception) {
                        erreur = cause.message
                        return@addListener
                    }

                    val apercu = Preview.Builder().build().also { it.setSurfaceProvider(vue.surfaceProvider) }
                    val analyse = ImageAnalysis.Builder()
                        // On jette les images en retard plutot que de les faire la queue :
                        // un QR lu sur une image d'il y a deux secondes n'interesse personne.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analyse.setAnalyzer(analyseur) { image ->
                        if (!consomme.get()) {
                            val texte = lireQr(image)
                            val demande = texte?.let { PairingLink.depuisTexte(it) }
                            if (demande != null && consomme.compareAndSet(false, true)) {
                                principal.execute { onLien(demande) }
                            } else if (texte != null && averti.compareAndSet(false, true)) {
                                principal.execute { avertissement = true }
                            }
                        }
                        image.close()
                    }

                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            cycleDeVie,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            apercu,
                            analyse,
                        )
                    } catch (cause: Exception) {
                        erreur = cause.message
                    }
                },
                principal,
            )
            vue
        },
    )

    if (avertissement) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                text = stringResource(R.string.scanner_pas_appairage_2b9c54),
                style = MaterialTheme.typography.bodyMedium,
                color = TetherAlert,
                textAlign = TextAlign.Center,
            )
        }
    }

    erreur?.let {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = TetherAlert)
        }
    }
}

@Composable
private fun CameraRefusee(onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.camera_refusee_9d3f71),
            style = MaterialTheme.typography.titleMedium,
            color = TetherTextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.camera_refusee_detail_5a8e0c),
            style = MaterialTheme.typography.bodyMedium,
            color = TetherTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.autoriser_camera_6e2b90))
        }
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.ouvrir_reglages_systeme_dc1d26))
        }
    }
}

/**
 * Extrait le plan de luminance d'une image CameraX et le decode.
 *
 * ⚠️ `rewind()` : rien ne garantit que le buffer arrive a la position 0, et lire a partir
 * d'une position avancee decalerait toute l'image — un QR parfaitement visible deviendrait
 * illisible. C'est une ligne, et c'est le genre de detail qui ne se voit qu'a l'execution.
 */
private fun lireQr(image: ImageProxy): String? {
    val plan = image.planes.firstOrNull() ?: return null
    val buffer = plan.buffer
    buffer.rewind()
    val octets = ByteArray(buffer.remaining())
    buffer.get(octets)
    return QrDecode.depuisPlanY(octets, plan.rowStride, image.width, image.height)
}

private fun ouvrirReglages(context: android.content.Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
