package sh.sk7.tether.ui.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PlugZap
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.WifiOff
import sh.sk7.tether.data.settings.ConnectionStatus
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * **Le serveur ne repond pas — voici pourquoi, et quoi faire.**
 *
 * ### Pourquoi cet ecran est obligatoire plutot que commode
 * Le serveur opencode vit sur une machine du homelab, joint par Tailscale ou le reseau local. Il
 * est donc **normal** qu'il soit injoignable : machine eteinte, tunnel coupe, changement de reseau.
 * Une app compagne qui montre alors un ecran vide ou un message technique ne rend aucun service —
 * c'est le reproche « les pages hors connexion sont absentes ».
 *
 * ### Ce que l'ecran fournit, et dans cet ordre
 *  1. **ce qui se passe**, en une phrase — pas un code d'erreur ;
 *  2. **la cause la plus probable**, choisie selon l'etat reel ;
 *  3. **des actions concretes**, dont un reessai qui refait un appel **reel**.
 *
 * ⚠️ Les trois pannes sont traitees separement ([ConnectionStatus]) et **ne se ressemblent pas a
 * l'ecran** : une erreur de mot de passe et un serveur eteint demandent des gestes opposes. Les
 * confondre enverrait l'utilisateur chercher un probleme qui n'existe pas.
 *
 * ⚠️ L'adresse est rappelee en clair : dans 9 cas sur 10, une panne de ce genre vient d'une
 * **mauvaise adresse** (127.0.0.1 au lieu de l'IP, mauvais port). La montrer permet de s'en
 * apercevoir en une seconde.
 */
@Composable
fun OfflineScreen(
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: OfflineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            // ⚠️ Edge-to-edge (impose par targetSdk 37) sans `Scaffold` : sans ces insets, le
            // contenu passe sous la barre d'état et la barre de navigation. `safeDrawing` couvre
            // les deux et les découpes d'écran.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val (icon, title) = when (state.status) {
            ConnectionStatus.Unauthorized -> Lucide.KeyRound to "Identifiants refusés"
            ConnectionStatus.NotConfigured -> Lucide.Settings to "Aucun serveur configuré"
            ConnectionStatus.Online -> Lucide.PlugZap to "Serveur joignable"
            else -> Lucide.WifiOff to "Serveur injoignable"
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (state.status == ConnectionStatus.Unauthorized) TetherAlert else LocalAccent.current,
            modifier = Modifier.size(36.dp),
        )

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TetherTextPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = Spacing.lg),
        )

        Text(
            text = explain(state.status),
            style = MaterialTheme.typography.bodyMedium,
            color = TetherTextSecondary,
            modifier = Modifier.padding(top = Spacing.sm),
        )

        if (state.host.isNotBlank()) {
            AddressCard(host = state.host, status = state.status)
        }

        // Le message du serveur ou de l'erreur, quand il y en a un. On le garde tel quel : c'est
        // souvent lui qui contient l'indice decisif (port, certificat, refus).
        state.health.message?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.lg),
            )
        }

        Action(
            label = if (state.checking) "Vérification…" else "Réessayer",
            icon = Lucide.RefreshCw,
            primary = true,
            enabled = !state.checking,
            loading = state.checking,
            onClick = viewModel::retry,
            modifier = Modifier.padding(top = Spacing.xl),
        )

        Action(
            label = stringResource(R.string.reglages_00d632),
            icon = Lucide.Settings,
            primary = false,
            enabled = true,
            loading = false,
            onClick = onOpenSettings,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/**
 * La cause probable, **choisie selon l'etat reel**.
 *
 * ⚠️ On ne dit pas « verifiez votre connexion » a tout le monde : c'est le message qui ne sert a
 * rien, parce qu'il est vrai de toutes les pannes et ne designe aucune action. Chaque etat a sa
 * liste de causes, ordonnee par frequence reelle.
 */
private fun explain(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Unauthorized ->
        "Le serveur répond, mais il refuse ce mot de passe. " +
            "Vérifie le mot de passe dans les réglages — il doit correspondre à " +
            "OPENCODE_SERVER_PASSWORD sur la machine."
    ConnectionStatus.NotConfigured ->
        "Renseigne l'adresse du serveur opencode et le mot de passe pour commencer."
    ConnectionStatus.Online ->
        "Le serveur répond de nouveau."
    else ->
        "Le serveur opencode est injoignable depuis ce téléphone. " +
            "Les causes les plus fréquentes : la machine est éteinte, le tunnel Tailscale est " +
            "coupé, ou l'adresse est celle de la machine locale (127.0.0.1) au lieu de son IP."
}

/**
 * L'adresse, et **ce qu'elle implique**.
 *
 * ⚠️ `127.0.0.1` sur un telephone designe **le telephone lui-meme**, pas le serveur. C'est
 * l'erreur la plus frequente de ce genre de configuration, et elle est invisible : l'adresse a
 * l'air parfaitement valide. On la signale donc explicitement.
 */
@Composable
private fun AddressCard(host: String, status: ConnectionStatus) {
    val looksLocal = host.startsWith("127.0.0.1") || host.startsWith("localhost")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg)
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(stringResource(R.string.adresse_ab87f8),
            style = TetherDataStyle,
            color = TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = host.ifBlank { "non renseignée" },
            style = TetherDataStyle,
            color = TetherTextPrimary,
        )
        if (looksLocal && status != ConnectionStatus.Online) {
            // ⚠️ Le cas qui merite d'etre dit : sur un telephone, `127.0.0.1` est le telephone.
            Text(stringResource(R.string.telephone_127_designe_d8d31a) +
                    "Utilise l'IP de la machine qui fait tourner opencode.",
                style = MaterialTheme.typography.bodySmall,
                color = TetherAlert,
            )
        }
    }
}

@Composable
private fun Action(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(
                if (primary) LocalAccent.current.copy(alpha = 0.16f) else TetherComposerSurface,
            )
            // ⚠️ 48 dp : les actions de cet écran sont les seules issues apres un echec de
            // connexion. Une cible facile a rater ici n'a pas de rattrapage.
            .heightIn(min = TetherDimensions.touchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .semantics { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = LocalAccent.current,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (primary) LocalAccent.current else TetherTextSecondary,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (primary) LocalAccent.current else TetherTextPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
}
