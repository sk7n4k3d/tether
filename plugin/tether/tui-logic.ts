/**
 * La logique du plugin TUI — **sans JSX**, donc testable.
 *
 * `tui.tsx` contient la mise en page ; tout ce qui **decide** de quelque chose est ici,
 * avec `ui-model.ts`. Un test `node:test` ne peut pas importer du `.tsx` — le
 * transtypage de Node ne retire pas le JSX — donc mettre la logique dans le `.tsx` la
 * rendrait intestable. C'est la seule raison de ce decoupage.
 *
 * Aucune dependance au TUI non plus : `rpc` et `adresseServeur` ne Takes qu'un objet
 * decrivant ce dont ils ont besoin, ce qui permet de les tester avec un `fetch` factice.
 */

/** Ce que `rpc` attend du contexte. Le minimum, pour pouvoir le simuler. */
export interface RpcContext {
  client: { getConfig?: () => { baseUrl?: string; auth?: unknown } }
  location?: { directory?: string } | undefined
}

/**
 * Appelle le RPC du plugin serveur.
 *
 * Le SDK genere ne connait pas `/api/rpc` — la route n'est pas dans son schema — donc
 * `client.get()` ne lui mettrait pas d'authentification. On passe par `fetch` et on
 * **resout nous-memes** le jeton, avec la meme convention que le SDK : la fonction
 * `auth` renvoie des identifiants bruts, et le schema decide du prefixe.
 *
 * `fetch` est injectable : c'est ce qui rend la fonction testable, et c'est aussi ce qui
 * evite d'avoir a lever un serveur pour verifier une URL.
 */
export async function rpc<T>(
  ctx: RpcContext,
  method: string,
  input: unknown,
  options: { fetch?: typeof fetch } = {},
): Promise<T> {
  const config = ctx.client?.getConfig?.() ?? {}
  const base = String(config.baseUrl ?? "http://127.0.0.1:4096").replace(/\/+$/, "")
  const repertoire = ctx.location?.directory
  const url = `${base}/api/rpc/tether/${method}${repertoire ? `?directory=${encodeURIComponent(repertoire)}` : ""}`

  const headers: Record<string, string> = { "Content-Type": "application/json" }
  const auth = config.auth as any
  if (typeof auth === "function") {
    const identifiants = await auth({ type: "http", in: "header", name: "Authorization", scheme: "basic" })
    if (identifiants) headers.Authorization = `Basic ${btoa(identifiants)}`
  } else if (typeof auth === "string" && auth.length > 0) {
    // Le SDK renvoie soit des identifiants bruts (a prefixer), soit un en-tete complet.
    headers.Authorization = auth.includes(" ") ? auth : `Basic ${btoa(auth)}`
  }

  const envoyer = options.fetch ?? fetch
  const reponse = await envoyer(url, { method: "POST", headers, body: JSON.stringify({ input }) })
  const corps: any = await reponse.json().catch(() => ({}))
  if (!reponse.ok) {
    // Les erreurs declarees sortent en 400 avec leur `type` et leur message : on les
    // remonte telles quelles, plutot que de les reduire a « HTTP 500 » — c'est ce qui
    // permet a l'utilisateur de distinguer « QR expire » de « serveur injoignable ».
    throw new Error(String(corps?.message ?? `RPC ${method} : HTTP ${reponse.status}`))
  }
  return corps.output as T
}

/**
 * L'adresse a mettre dans le QR.
 *
 * `options.serverUrl` gagne toujours : c'est la seule valeur forcement joignable depuis
 * un telephone. Detecter le `baseUrl` du TUI ne convient qu'en developpement local — et
 * le dire ici evite d'encoder un `127.0.0.1` dans un QR destine a un autre appareil, ce
 * qui est le genre de faute qui ne se revele qu'a la premiere tentative de scan.
 */
export function adresseServeur(
  ctx: RpcContext,
  options?: Record<string, any> | undefined,
  env: NodeJS.ProcessEnv = process.env,
  stores: Record<string, string | undefined> = {},
): string {
  // Quatre couches : ce que l'utilisateur a regle dans le TUI, la config opencode
  // (forme plate ou objet), puis l'environnement, puis la detection du TUI.
  //
  // ⚠️ Le magasin passe **avant** la config : un reglage fait dans l'interface est un
  // geste explicite et recent, il doit gagner sur un fichier ecrit il y a six mois. Mais
  // une chaine **vide** est une negation (« j'ai remis au defaut »), pas une valeur : elle
  // doit sauter et laisser la couche suivante parler.
  const depuisStore = typeof stores.serverUrl === "string" ? stores.serverUrl.trim() : ""
  const configure = depuisStore
    ? depuisStore
    : options?.serverUrl ?? options?.tether?.serverUrl ?? env.TETHER_SERVER_URL
  if (typeof configure === "string" && configure.length > 0) return configure.replace(/\/+$/, "")
  return String(ctx.client?.getConfig?.()?.baseUrl ?? "http://127.0.0.1:4096").replace(/\/+$/, "")
}

/**
 * Les deux commandes du plugin, **sans `run`**.
 *
 * Separer la declaration de l'execution permet de tester ce que le TUI voit — les
 * identifiants, les titres, la presence dans la palette et dans le prompt — sans
 * executer quoi que ce soit. Un `run` qui n'est jamais appele dans un test ne prouve
 * rien ; en revanche un identifiant de commande faux se voit immediatement.
 */
export function declarationsCommandes() {
  return [
    {
      id: "tether.pair",
      title: "Tether : appairer un téléphone",
      description: "Affiche un QR à scanner depuis l'app Tether",
      group: "Tether",
      palette: true,
      slash: { name: "tether" },
    },
    {
      id: "tether.config",
      title: "Tether : configuration",
      description: "Règle l'adresse du serveur, le seuil de notification, la troncature et la clé VAPID",
      group: "Tether",
      palette: true,
      slash: { name: "tether-config" },
    },
    {
      id: "tether.devices",
      title: "Tether : appareils appairés",
      description: "Liste les téléphones enregistrés, et permet d'en retirer un",
      group: "Tether",
      palette: true,
    },
  ]
}
