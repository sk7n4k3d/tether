/**
 * La logique du plugin TUI — **sans JSX**, donc testable.
 *
 * `tui.tsx` contient la mise en page ; tout ce qui **decide** de quelque chose est ici,
 * avec `ui-model.ts`. Un test `node:test` ne peut pas importer du `.tsx` — le
 * transtypage de Node ne retire pas le JSX — donc mettre la logique dans le `.tsx` la
 * rendrait intestable. C'est la seule raison de ce decoupage.
 *
 * Aucune dependance au TUI non plus : `rpc` et `adresseServeur` ne prennent qu'un objet
 * decrivant ce dont ils ont besoin, ce qui permet de les tester avec un client factice.
 */

import { Tether } from "./rpc.ts"

/** Ce que `rpc` attend du contexte. Le minimum, pour pouvoir le simuler. */
export interface RpcContext {
  client: object
  location?: { directory?: string } | undefined
}

/**
 * Appelle le RPC du plugin serveur.
 *
 * ## Pourquoi `client.rpc`, et pas un `fetch` maison
 *
 * La premiere version faisait un `fetch` sur `/api/rpc/tether/...` et resolvait
 * l'authentification elle-meme a partir de `ctx.client.getConfig()`. **Cette methode
 * n'existe pas** sur le client expose au TUI : `ctx.client` est l'`OpenCodeClient`, un
 * objet d'operations, sans `getConfig`. Le `fetch` partait donc **sans en-tete
 * `Authorization`**, et le serveur — qui exige l'auth basique des qu'un mot de passe est
 * pose — repondait `401`. C'etait le bug : « Appairage impossible : RPC pair : HTTP 401 ».
 *
 * Le client porte deja l'authentification de la session qui l'a construit, et expose
 * `/api/rpc` : `client.rpc(definition)` rend une fonction par methode qui appelle
 * `rpc.call` **avec les en-tetes du client**. On l'utilise plutot que de reconstruire un
 * transport qui ne peut connaitre ni l'URL du serveur ni son mot de passe.
 */
export async function rpc<T>(ctx: RpcContext, method: string, input: unknown): Promise<T> {
  const client = ctx.client as {
    rpc?: (definition: unknown) => Record<string, (input: unknown, options?: unknown) => Promise<unknown>>
  }
  const appeler = client?.rpc?.(Tether)?.[method]
  if (typeof appeler !== "function") {
    throw new Error(`le client ne sait pas appeler le RPC « ${method} »`)
  }
  const options = ctx.location?.directory ? { location: { directory: ctx.location.directory } } : undefined
  return (await appeler(input, options)) as T
}

/**
 * L'adresse a mettre dans le QR.
 *
 * C'est la seule valeur forcement joignable depuis un telephone, et le client ne peut pas
 * la fournir : `OpenCodeClient` n'expose ni son `baseUrl` ni son mot de passe. Il n'y a
 * donc pas de « detection du TUI » — il y a trois sources, et un dernier recours qui ne
 * vaut que pour un serveur local.
 */
export function adresseServeur(
  options?: Record<string, any> | undefined,
  env: NodeJS.ProcessEnv = process.env,
  stores: Record<string, string | undefined> = {},
): string {
  // Trois couches : ce que l'utilisateur a regle dans le TUI, la config opencode (forme
  // plate ou objet), puis l'environnement.
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
  // Dernier recours : l'adresse locale du serveur. ⚠️ Un telephone ne peut pas joindre
  // `127.0.0.1` : un QR qui la porte ne scanne rien. C'est pourquoi `TETHER_SERVER_URL`
  // (ou le reglage du TUI) existe, et pourquoi ce defaut n'est pas une reponse.
  return "http://127.0.0.1:4096"
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
