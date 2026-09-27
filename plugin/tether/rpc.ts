/**
 * La **definition** du RPC Tether, dans son propre module.
 *
 * ## Pourquoi un fichier separe
 *
 * L'implementation (`index.ts`) et la definition (`rpc.ts`) servent deux publics
 * differents : un client HTTP appelle `/api/rpc/tether/subscribe`, et un autre plugin
 * peut appeler le meme RPC via `ctx.rpc(Tether)`. Pour que les deux Speechn la
 * **meme** forme, la definition doit etre importable seule — ce que la doc officielle
 * montre en titrant le fichier `src/rpc.ts`.
 *
 * ## JSON Schema, pas Zod
 *
 * La definition accepte JSON Schema (aucune dependance) ou un validateur Standard
 * Schema. On prend JSON Schema : le projet n'ajoute pas de dependance sans
 * necessite prouvee, et les schemas sont lus par le serveur, pas par nous.
 *
 * ⚠️ **`additionalProperties: false` est ecrit partout, et ne sera PAS applique.**
 * Mesure sur 2.0.x : une cle surnumeraire traverse jusqu'au handler. La validation
 * reelle est dans `validateSubscription()`, cote implementation. Ces `false` restent
 * pour la documentation et le typage cote client — pas pour la securite.
 */

export const Tether = {
  id: "tether",

  methods: {
    /**
     * L'app annonce son endpoint et ses cles. **Idempotent** sur `deviceId` :
     * re-enregistrer apres un redemarrage du distributeur met a jour au lieu de
     * dupliquer.
     */
    subscribe: {
      input: {
        type: "object",
        properties: {
          deviceId: { type: "string" },
          endpoint: { type: "string" },
          keys: {
            type: "object",
            properties: { p256dh: { type: "string" }, auth: { type: "string" } },
            required: ["p256dh", "auth"],
          },
          alerts: {
            type: "object",
            properties: { turnEnd: { type: "boolean" }, attention: { type: "boolean" }, progress: { type: "boolean" } },
          },
          label: { type: "string" },
          distributor: { type: "string" },
        },
        required: ["deviceId", "endpoint", "keys"],
        additionalProperties: false,
      },
      output: {
        type: "object",
        properties: { ok: { type: "boolean" }, total: { type: "number" } },
        required: ["ok"],
        additionalProperties: false,
      },
      errors: {
        // Erreurs declarees : chaque cle devient un `type` dans la reponse. Le client
        // peut donc les traiter nommement au lieu d'analyser un message libre.
        invalid: {
          type: "object",
          properties: { reason: { type: "string" } },
          required: ["reason"],
          additionalProperties: false,
        },
      },
    },

    /** Retrait volontaire, par l'app ou par l'utilisateur depuis le TUI. */
    unsubscribe: {
      input: {
        type: "object",
        properties: { deviceId: { type: "string" } },
        required: ["deviceId"],
        additionalProperties: false,
      },
      output: {
        type: "object",
        properties: { ok: { type: "boolean" }, total: { type: "number" } },
        required: ["ok"],
        additionalProperties: false,
      },
    },

    /**
     * La liste des appareils, **en vue publique**.
     *
     * Ni `endpoint` ni `keys` ne sortent du serveur par cette route : ce sont des
     * capacites d'ecriture. Le type lui-meme l'interdit, donc un oubli d'implementation
     * ne peut pas les exposer.
     */
    devices: {
      input: { type: "object", properties: {} },
      output: {
        type: "object",
        properties: {
          devices: {
            type: "array",
            items: {
              type: "object",
              properties: {
                deviceId: { type: "string" },
                label: { type: "string" },
                distributor: { type: "string" },
                registeredAt: { type: "number" },
                alerts: {
                  type: "object",
                  properties: { turnEnd: { type: "boolean" }, attention: { type: "boolean" }, progress: { type: "boolean" } },
                },
              },
              required: ["deviceId", "registeredAt"],
            },
          },
        },
        required: ["devices"],
        additionalProperties: false,
      },
    },

    /**
     * L'etat d'un appairage en cours. Ne renvoie **jamais** le jeton : il vit dans
     * le QR, que seul l'utilisateur scanne.
     */
    pairingStatus: {
      input: { type: "object", properties: {} },
      output: {
        type: "object",
        properties: { active: { type: "boolean" }, expiresInMs: { type: "number" } },
        required: ["active"],
        additionalProperties: false,
      },
    },
  },

  events: {
    /** Le registre a change : appareil ajoute, retire, ou desabonne. */
    devicesChanged: {
      schema: {
        type: "object",
        properties: { total: { type: "number" }, reason: { type: "string" } },
        required: ["total"],
        additionalProperties: false,
      },
    },
  },
}
