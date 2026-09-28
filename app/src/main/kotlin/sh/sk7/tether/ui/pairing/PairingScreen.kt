package sh.sk7.tether.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import sh.sk7.tether.push.PushSubscription
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextPrimary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * L'ecran de confirmation d'appairage.
 *
 * ## Pourquoi cet ecran existe, et pourquoi il est obligatoire
 *
 * Home Assistant, `GHSA-2xqv-hwrf-983f` (2026-07-31) : l'app Companion transmettait un scan
 * NFC ou QR **sans confirmation humaine**, ce qui permettait a un tiers de faire executer
 * des automatisations en silence. Un scan non confirme est une surface d'attaque.
 *
 * Ici, l'onglet est un QR qui porte l'adresse d'un serveur et un jeton. Un scan
 * automatique — camera de surveillance, photo, QR affiche sur un ecran — suffirait a
 * enregistrer cet appareil chez un tiers. Alors :
 *
 *  - **rien n'est envoye** tant que l'utilisateur n'a pas appuye sur « Autoriser » ;
 *  - **l'adresse du serveur est affichee en grand**, parce que c'est la seule chose que
 *    l'utilisateur puisse verifier. [sh.sk7.tether.push.PairingLink] a deja refuse les
 *    adresses en clair et les hotes piege ; il n'a pas pu — et ne peut pas — savoir si
 *    l'utilisateur s'attendait a cette adresse. Seul cet ecran permet de le verifier.
 *  - « Refuser » ne fait **rien** : pas d'appel reseau, pas d'ecriture. Un refus doit etre
 *    le chemin le plus facile, sinon il n'est jamais pris.
 *
 * ## Ce que l'app s'engage a envoyer
 *
 * L'URL du point d'acces, la cle P-256DH et le secret d'authentification. C'est une
 * **capacite d'ecriture** : celui qui les connait peut pousser une notification sur ce
 * telephone. L'ecran le dit, parce qu'un consentement a une consequence qu'il faut savoir
 * nommer.
 *
 * ## Aucun `Context` ici
 *
 * L'abonnement est charge par l'appelant ([abonnement]) et passe en parametre. C'est ce
 * qui garde cet ecran testable : il ne depend que de deux lambdas et de donnees.
 */
@Composable
fun PairingScreen(
    state: PairingUiState,
    onAuthorize: () -> Unit,
    onRefuse: () -> Unit,
    onScan: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val demande = state.demande
    val abonnement = state.abonnement

    // Sans demande, il n'y a rien a confirmer : ni adresse a verifier, ni jeton a
    // consommer. Afficher un formulaire avec une adresse vide serait pire qu'utile — la
    // carte « Serveur » est justement le controle de securite, et vide elle ne protege
    // plus de rien.
    //
    // ⚠️ Le cas est **atteignable** : le ViewModel survit a une recreation d'activite,
    // mais pas a la mort du processus. Un lien scanne puis tuee par le systeme avant la
    // decision ressuscite donc l'ecran sans demande. Il faut le dire, pas laisser un
    // ecran muet.
    if (demande == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.aucun_appairage_attente_0262ef),
                style = MaterialTheme.typography.headlineSmall,
                color = TetherTextPrimary,
            )
            Text(stringResource(R.string.lancez_tether_serveur_205305),
                style = MaterialTheme.typography.bodyMedium,
                color = TetherTextMuted,
            )
            // Le scanner integre : c'est la porte d'entree normale quand l'utilisateur a
            // le terminal sous les yeux. Le deep link `opencode://pair` reste l'autre
            // chemin, scanne par n'importe quelle application.
            Button(onClick = onScan, modifier = Modifier.padding(top = Spacing.md)) {
                Text(stringResource(R.string.scanner_qr_1f4e1d))
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ⚠️ Sans demande, il n'y a **rien a confirmer** : ni adresse a afficher, ni jeton a
        // consommer. L'ecran se.visit apres une decision, et on ne montre pas un formulaire
        // de consentement a vide — un « Autoriser » sans demande derriere, c'est un bouton
        // qui ne doit pas exister.
        if (demande == null) {
            Text(stringResource(R.string.aucun_lien_appairage_e16e7f),
                style = MaterialTheme.typography.bodyMedium,
                color = TetherTextMuted,
            )
            return@Column
        }

        Text(stringResource(R.string.appairer_cet_appareil_be875b),
            style = MaterialTheme.typography.headlineSmall,
            color = TetherTextPrimary,
        )

        Text(stringResource(R.string.serveur_demande_autoriser_46685a),
            style = MaterialTheme.typography.bodyMedium,
            color = TetherTextMuted,
        )

        // ---- L'adresse du serveur, en grand. C'est le point de verification. ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(TetherComposerSurface)
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.serveur_970701),
                style = MaterialTheme.typography.labelMedium,
                color = TetherTextMuted,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Lucide.ShieldAlert,
                    contentDescription = null,
                    tint = TetherAlert,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "  ${demande?.server.orEmpty()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = TetherTextPrimary,
                )
            }
            Text(stringResource(R.string.adresse_doit_etre_e10114) +
                      " " + stringResource(R.string.affiche_autre_vient_83d532),
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
            )
        }

        // ---- Ce que l'app va envoyer. ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(TetherComposerSurface)
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.autorisant_transmettez_serveur_b5daee),
                style = MaterialTheme.typography.labelMedium,
                color = TetherTextMuted,
            )
            Text(stringResource(R.string.adresse_point_acces_6af7b3) +
                      " " + stringResource(R.string.capacite_ecriture_serveur_915a9f) +
                     " " + stringResource(R.string.notification_tout_moment_9875e8),
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextPrimary,
            )
            Text(stringResource(R.string.recoit_rien_autre_53be98) +
                      " " + stringResource(R.string.mot_passe_opencode_cf487c),
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
            )
        }

        // ---- L'etat de l'abonnement : sans distributeur, rien a autoriser. ----
        if (abonnement == null) {
            Text(stringResource(R.string.aucun_distributeur_push_e7d8a7) +
                      " " + stringResource(R.string.installez_ntfy_unifiedpush_8bdbdc),
                style = MaterialTheme.typography.bodyMedium,
                color = TetherAlert,
            )
        } else if (state.enCours) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.enregistrement_aed5ed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TetherTextMuted,
                )
            }
        }

        state.erreur?.let { message ->
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = TetherAlert)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Refuser : le chemin le plus simple, et il ne fait rien.
            OutlinedButton(
                onClick = onRefuse,
                enabled = !state.enCours,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TetherTextPrimary),
            ) {
                Text(stringResource(R.string.refuser_628971))
            }
            Button(
                onClick = onAuthorize,
                enabled = state.peutAutoriser,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.autoriser_ff8398))
            }
        }
    }
}
