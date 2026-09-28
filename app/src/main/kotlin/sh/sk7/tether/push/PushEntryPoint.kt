package sh.sk7.tether.push

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Une coroutine qui **survit au redemarrage du distributeur**.
 *
 * `TetherPushService` est detruit et recree par le systeme ; une coroutine lancee dans
 * son propre scope disparaitrait avec lui — au pire moment, puisque c'est justement le
 * redemarrage du distributeur qui declenche le re-enregistrement dont on a besoin. Celle
 * ci vit au niveau de l'application, qui ne part qu'avec le processus.
 *
 * ⚠️ `SupervisorJob` : un echec de re-declaration ne doit pas annuler les suivantes. Une
 * coroutine mere serait annulee par le premier `IOException` du reseau, et l'appareil
 * resterait desormais muet pour toujours.
 */
@Singleton
class PushScope @Inject constructor() {
    private val supervisor = SupervisorJob()

    val coroutines: CoroutineScope = CoroutineScope(supervisor + Dispatchers.IO)
}
