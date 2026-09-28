package sh.sk7.tether.ui.server

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.data.api.McpServerDto
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.PluginDto
import sh.sk7.tether.data.api.ProjectDto
import sh.sk7.tether.data.api.ProviderDto
import sh.sk7.tether.data.api.SavedPermissionDto
import sh.sk7.tether.data.api.SkillDto
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.ui.settings.ConnectionErrors
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/** L'inventaire complet du serveur, groupe par nature. */
data class ServerUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val version: String? = null,
    val mcp: List<McpServerDto> = emptyList(),
    val plugins: List<PluginDto> = emptyList(),
    val skills: List<SkillDto> = emptyList(),
    val commands: List<CommandDto> = emptyList(),
    val providers: List<ProviderDto> = emptyList(),
    val permissions: List<SavedPermissionDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
) {
    /** Compteur de serveurs MCP connectes, pour l'en-tete. */
    val mcpConnected: Int get() = mcp.count { it.status?.status == "connected" }
}

/**
 * **Ce que le serveur a, et ce que l'app ne montrait pas.**
 *
 * ### Pourquoi cet ecran
 * Le serveur expose **21 serveurs MCP, 59 skills, 28 commandes, 90 plugins, 4 providers,
 * 232 integrations et ses permissions memorisees** (mesure 2026-09-25). L'app n'en montrait
 * **rien** : on pilotait un systeme sans jamais voir de quoi il est fait. C'est le manque le plus
 * visible d'un « compagnon » — un compagnon sait ce que l'autre a sur lui.
 *
 * ### Ce qu'on fait de ce qu'on trouve
 * Cet ecran est d'abord **informatif**, mais pas seulement : les permissions memorisees sont
 * **revoquables** ici. C'est le seul endroit ou l'utilisateur peut reprendre un droit qu'il a
 * accorde et oublie — et un droit accorde qu'on ne peut plus retirer est un probleme de securite,
 * pas une fonctionnalite manquante.
 *
 * ⚠️ **Echec partiel assume** : si `/api/mcp` tombe mais que `/api/skill` repond, on affiche ce
 * qu'on a et on signale le trou. Remplacer tout l'ecran par une erreur cacherait des donnees
 * disponibles — un inventaire qui disparait entierement parce qu'une de ses sections a echoue
 * est plus frustrant qu'utile.
 */
@HiltViewModel
class ServerViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    init {
        load()
    }

    override fun onCleared() {
        scope.cancel()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val settings = store.current()
                // ⚠️ Chaque section est isolee : une route qui echoue ne doit pas emporter les
                // autres. C'est le point important — remplacer tout l'ecran par une erreur
                // cacherait des donnees qui, elles, sont disponibles.
                val info = runCatching { gateway.info(settings) }.getOrNull()
                val failed = mutableListOf<String>()

                suspend fun <T> section(name: String, call: suspend () -> List<T>): List<T> =
                    runCatching { call() }.getOrElse {
                        failed += name
                        emptyList()
                    }

                val mcp = section("MCP") { gateway.mcpServers(settings) }
                val plugins = section("plugins") { gateway.plugins(settings) }
                val skills = section("skills") { gateway.skills(settings) }
                val commands = section("commandes") { gateway.commands(settings) }
                val providers = section("providers") { gateway.providers(settings) }
                val permissions = section("permissions") { gateway.savedPermissions(settings) }
                val projects = section("projets") { gateway.projects(settings) }

                _state.update {
                    it.copy(
                        loading = false,
                        error = if (failed.isEmpty()) null else Res.of(R.string.sections_indisponibles_abb861) +
                            failed.joinToString(", "),
                        version = info?.version,
                        mcp = mcp,
                        plugins = plugins,
                        skills = skills,
                        commands = commands,
                        providers = providers,
                        permissions = permissions,
                        projects = projects,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = ConnectionErrors.describe(e))
                }
            }
        }
    }

    /**
     * Revoque une autorisation memorisee.
     *
     * ⚠️ **Aucune confirmation dans le ViewModel** : c'est l'appelant qui demande. Le ViewModel
     * n'execute que sur ordre explicite — meme regle que la suppression de session.
     */
    fun revoke(permissionID: String) {
        scope.launch {
            val settings = store.current()
            runCatching { gateway.revokePermission(settings, permissionID) }
                .onSuccess {
                    // On retire l'entree localement : l'utilisateur voit l'effet tout de suite,
                    // et le prochain `load` confirmera cote serveur.
                    _state.update { current ->
                        current.copy(permissions = current.permissions.filterNot { it.id == permissionID })
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(error = ConnectionErrors.describe(e)) }
                }
        }
    }
}
