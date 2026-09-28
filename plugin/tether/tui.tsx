/**
 * Le plugin TUI de Tether — l'appairage par QR, et la liste des appareils.
 *
 * ## Ce que ce fichier fait, et ce qu'il ne fait pas
 *
 * Deux choses, accessibles par `/tether` et par la palette de commandes :
 *
 *  1. **Appairer un telephone.** Le serveur emet un jeton a usage unique, on l'affiche
 *     en QR, l'utilisateur le scanne. Le telephone ouvre `opencode://pair?s=…&t=…`,
 *     demande confirmation, puis enregistre son endpoint.
 *  2. **Retirer un appareil.** La liste est la seule vue qui existe, et elle ne montre
 *     jamais l'endpoint ni les cles : ce sont des capacites d'ecriture.
 *
 * Toute la logique qui **decide** vit dans `ui-model.ts`, teste sans TUI. Ici : de la
 * mise en page, et les appels RPC.
 *
 * ## Le jeton ne transige que par le QR
 *
 * Il transite aussi par la reponse du RPC `pair` — mais vers **ce dialogue**, sur la
 * machine ou l'utilisateur a deja entre le mot de passe. Il n'est journalise nulle part,
 * et aucun autre RPC ne le renvoie : `pairingStatus` ne dit que si un jeton existe.
 * C'est une consequence du canal sens unique : le serveur ne redescend jamais de
 * configuration vers l'app.
 *
 * ## Ce qui n'est pas verifie
 *
 * ⚠️ **Aucun TUI reel n'a execute ce fichier.** La forme du module a ete lue dans la
 * source d'opencode — `packages/tui/src/plugin/context.tsx` (`isPlugin` exige
 * `{ id, setup }`) et `packages/plugin/src/host.ts` (`Host.resolve` resout
 * `<dossier>/tui`), version 2.0.x — et l'appel RPC reprend la forme mesuree par
 * `index.test.mjs`. Mais l'affichage lui-meme n'est pas observe. Ce qui est teste :
 * `ui-model.ts`, et le serveur contre un vrai `opencode serve`.
 */

import { createSignal, onCleanup } from "solid-js"

import { qrText } from "./qr.js"
import { rpc, adresseServeur, declarationsCommandes } from "./tui-logic.js"
import { OPTIONS, valider, afficher, appliquer, configInitiale } from "./tui-config.js"
import type { ConfigStockee } from "./tui-config.js"
import {
  nomAppareil,
  optionsAppareils,
  compteARebours,
  AUCUN_APPAREIL,
  type Appairage,
} from "./ui-model.js"

/**
 * La forme du contexte telle qu'on l'utilise. Le plugin n'en depend qu'a travers elle :
 * c'est ce qui rend le fichier lisible sans connaitre tout le SDK.
 */
type Ctx = {
  client: { getConfig?: () => { baseUrl?: string; auth?: unknown } }
  location?: { directory?: string } | undefined
  options?: Record<string, any> | undefined
  theme: any
  keymap: { layer: (input: () => any) => void }
  ui: {
    dialog: {
      show: (render: () => any, onClose?: () => void) => void
      set: (options: { size?: "medium" | "large" | "xlarge"; centered?: boolean }) => void
      clear: () => void
      alert: (options: { title: string; message: string }) => Promise<void>
      confirm: (options: { title: string; message: string; label?: { confirm?: string; cancel?: string } }) => Promise<boolean | undefined>
    }
    select: <Value>(options: any) => Promise<Value | undefined>
    toast: { show: (input: { message: string; variant?: "info" | "success" | "warning" | "error" }) => void }
  }
}


export default {
  id: "tether",

  setup(ctx: Ctx) {
    const theme = ctx.theme

    /** L'ecran d'appairage : QR, compte a rebours, et le lien en clair. */
    const appairer = async () => {
      let emis: { link: string; expiresInMs: number }
      try {
        emis = await rpc<{ link: string; expiresInMs: number }>(ctx, "pair", { server: adresseServeur(ctx, ctx.options) })
      } catch (cause) {
        ctx.ui.toast.show({ message: `Appairage impossible : ${(cause as Error).message}`, variant: "error" })
        return
      }

      const appairage: Appairage = {
        link: emis.link,
        qr: qrText(emis.link),
        expiresAt: Date.now() + emis.expiresInMs,
        ttlMs: emis.expiresInMs,
      }

      // ⚠️ Un seul `show`. Rouvrir le dialogue pour rafraichir l'horloge empilerait les
      // dialogues au-dessus les uns des autres — et c'est le defaut qu'on write quand on
      // croit que le TUI rerend tout seul. Il rerend : c'est Solid, et le composant
      // ci-dessous tient l'horloge dans un signal.
      ctx.ui.dialog.show(() => <AppairageVue theme={theme} appairage={appairage} />)
      ctx.ui.dialog.set({ size: "large", centered: true })
    }

    /**
     * La configuration, editable depuis le TUI.
     *
     * ## Ce que le TUI ecrit, et pourquoi pas dans `opencode.jsonc`
     *
     * `ctx.storage.store("config")` — un etat JSON durable, partage entre instances de TUI
     * ouvertes. Ecrire la config de l'utilisateur serait une faute de principe : un plugin qui
     * modifie le fichier de configuration de celui qui l'a installe n'est plus un plugin,
     * c'est une edition a distance. La resolution lit cette couche **en premier**, devant la
     * config et l'environnement, parce qu'un geste fait dans l'interface est intentionnel
     * et recent.
     *
     * ## Ce que le TUI ne fait pas
     *
     * Il ne valide pas : [valider] est teste, et l'appeler ici serait dupliquer une regle.
     * Il ne rend pas non plus la forme — `tui-config.ts` en porte la description.
     */
    const config = async () => {
      const [store, setStore] = ctx.storage.store<ConfigStockee>("config", { initial: configInitiale() })

      for (const option of OPTIONS) {
        const courante = afficher(store[option.cle])
        // ⚠️ On **ne** demande pas le bon d'etre ou la valeur courante dans le message :
        // `prompt` a un `placeholder` et une `value`, et y mettre la valeur courante
        // transforme un champ de saisie en document — l'utilisateur voit son reglage et
        // doit decider s'il le remplace ou l'annule.
        const saisie = await ctx.ui.dialog.prompt({
          title: `${option.titre} — ${courante}`,
          description: option.description,
          placeholder: option.defaut ?? "(non définie)",
        })
        // `undefined` = fermee sans valider. On ne touche a rien : c'est le comportement
        // par defaut d'une annulation, et l'ecrire rendrait « annuler » different de
        // « ne rien changer », qui est deja la meme chose ici.
        if (saisie === undefined) continue

        const r = valider(option, saisie)
        if (!r.ok) {
          // On refuse en **redemandant**, pas en fenetre d'erreur : l'utilisateur a
          // tape une valeur, la dismisser lui ferait perdre ce qu'il venait d'ecrire.
          await ctx.ui.dialog.alert({
            title: option.titre,
            message: `${r.refus.raison}\n\nReprise, sans changement.`,
          })
          continue
        }
        await setStore((brouillon) => appliquer(brouillon, option, r.valeur))
      }

      // Un resume, parce que « j'ai regle six choses » ne laisse aucun souvenir, et la
      // question suivante sera toujours « et ca a marche ? ». On dit ce qui reste actif
      // et ce qui manque, sinon l'utilisateur ne peut pas juger.
      const resume = OPTIONS.map((o) => `${o.titre} : ${afficher(store[o.cle])}`).join("\n")
      await ctx.ui.dialog.alert({
        title: "Configuration Tether",
        message: `${resume}\n\nCes reglages priment sur la config opencode et sur les variables d'environnement.`,
      })
    }

    /** La liste des appareils, et le retrait de l'un d'eux. */
    const appareils = async () => {
      let liste: { devices: any[] }
      try {
        liste = await rpc<{ devices: any[] }>(ctx, "devices", {})
      } catch (cause) {
        ctx.ui.toast.show({ message: `Appareils illisibles : ${(cause as Error).message}`, variant: "error" })
        return
      }

      if (liste.devices.length === 0) {
        await ctx.ui.dialog.alert({ title: "Tether", message: AUCUN_APPAREIL })
        return
      }

      const choisi = await ctx.ui.select({
        title: "Appareils appairés — choisir celui à retirer",
        options: [...optionsAppareils(liste.devices), { title: "(annuler)", value: "" }],
      })
      if (!choisi) return

      // Retirer est definitif et invisible pour l'app : elle ne le saura pas tant qu'elle
      // n'aura pas de push. D'ou la confirmation, qui **nomme** l'appareil — « cette
      // action » ne dit pas sur quoi elle porte.
      const appareil = liste.devices.find((d) => d.deviceId === choisi)
      const nom = appareil ? nomAppareil(appareil) : choisi
      const confirme = await ctx.ui.dialog.confirm({
        title: "Retirer l'appareil",
        message: `« ${nom} » ne recevra plus aucune notification. Il faudra rescanner un QR pour le réappairer.`,
        label: { confirm: "Retirer", cancel: "Garder" },
      })
      if (!confirme) return

      try {
        await rpc<{ ok: boolean; total: number }>(ctx, "unsubscribe", { deviceId: choisi })
        ctx.ui.toast.show({ message: `${nom} retiré.`, variant: "success" })
      } catch (cause) {
        ctx.ui.toast.show({ message: `Retrait impossible : ${(cause as Error).message}`, variant: "error" })
      }
    }

    // Les declarations viennent de `tui-logic.ts`, qui les teste. Ici on ne fait que
    // brancher le `run` de chacune — une declaration sans implementation donnerait au
    // TUI une commande visible dans la palette qui ne fait strictement rien.
    const executions: Record<string, () => void> = {
      "tether.pair": () => void appairer(),
      "tether.config": () => void config(),
      "tether.devices": () => void appareils(),
    }

    ctx.keymap.layer(() => ({
      mode: "global",
      commands: declarationsCommandes().map((declaration) => ({
        ...declaration,
        run: executions[declaration.id],
      })),
    }))

    return () => {}
  },
}

/**
 * Le dialogue d'appairage.
 *
 * L'horloge vit dans un signal mis a jour par un intervalle **interne au composant**,
 * enregistre sur `onCleanup`. Deux raisons :
 *
 *  - un signal rerend ce composant, donc le TUI n'a rien a rerouter ;
 *  - `onCleanup` garantit que l'intervalle meurt avec le dialogue. Un minuteur cree dans
 *    `setup` survivrait a la fermeture et continuerait de maintenir un signal orphelin.
 */
function AppairageVue(props: { theme: any; appairage: Appairage }) {
  const { theme, appairage } = props
  const [reste, setReste] = createSignal(Date.now())
  const minuteur = setInterval(() => setReste(Date.now()), 1000)
  onCleanup(() => clearInterval(minuteur))

  return (
    <box gap={1} flexDirection="column">
      <box flexDirection="row" justifyContent="space-between">
        <text fg={theme.text.base}>Appairer un téléphone</text>
        <text fg={theme.text.muted}>esc</text>
      </box>
      <text fg={theme.text.muted} wrapMode="word">
        Scannez ce QR avec l'app Tether. Le jeton ne sert qu'une fois.
      </text>
      <text fg={theme.text.base}>{appairage.qr}</text>
      <text fg={theme.text.muted}>{compteARebours(appairage.expiresAt, reste())}</text>
      <text fg={theme.text.muted} wrapMode="word">
        Sans caméra : {appairage.link}
      </text>
    </box>
  )
}
