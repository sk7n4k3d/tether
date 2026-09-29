/**
 * **Le routage des evenements** : de quel type est ce message, et que faut-il en faire ?
 *
 * ## Pourquoi c'est un module, et pas trois fonctions dans `index.ts`
 *
 * Deux raisons, dans cet ordre :
 *
 * 1. **C'est testable.** `index.ts` importe ses voisins en `.js` (contrat du paquet installe),
 *    donc `node --experimental-strip-types` ne peut pas le charger dans un test unitaire. Ce
 *    module n'importe rien : ses decisions se verifient sans opencode, sans reseau, sans mock.
 * 2. **C'est de la specification.** Ces tables disent ce qui declenche une notification — la
 *    seule chose que l'utilisateur voit de tout ce plugin. Une erreur ici ne casse rien : elle
 *    rend le telephone muet, ou bavard, et personne ne s'en apercoit tout de suite.
 *
 * ## Les formes sont mesurees, pas supposees
 *
 * Releve sur un vrai serveur (2026-09-29), en forcant un appel d'outil et une demande
 * d'autorisation :
 *
 * ```
 * session.tool.input.started  {sessionID, assistantMessageID, id, name}
 * session.tool.called         {sessionID, assistantMessageID, id, input, executed}
 * session.tool.progress       {sessionID, assistantMessageID, id, metadata}
 * session.tool.success        {sessionID, assistantMessageID, id, content, metadata, executed}
 * permission.asked            {id, sessionID, action, resources, save, source}
 * session.execution.started   {sessionID}
 * session.execution.succeeded {sessionID}
 * ```
 */

/** Fin de tour. `session.idle` en est **exclu** : il arrive aussi en dehors d'un tour. */
export const TURN_END = new Set([
  "session.execution.succeeded",
  "session.execution.failed",
  "session.execution.interrupted",
])

/** L'agent attend une decision : c'est le seul etat qui immobilise du travail. */
export const ATTENTION = new Set(["permission.asked", "form.created"])

/** Le nom de l'outil arrive **avant** son entree, et dans un autre evenement. */
export const TOOL_NAMED = "session.tool.input.started"

/**
 * L'entree reelle de l'outil.
 *
 * ⚠️ **C'est le seul evenement d'outil qui publie.** Un appel d'outil en produit quatre
 * (`input.started`, `called`, `progress`, `success`) : publier sur tout `session.tool.*`, comme
 * la version precedente, faisait trois pushes chiffres pour une seule etape affichee.
 */
export const TOOL_CALLED = "session.tool.called"

/**
 * De quel type est cet evenement, et que faut-il en faire ?
 *
 * ⚠️ `tool` n'est renseigne que pour l'**attention**, dont le nom est dans l'evenement. Celui de
 * l'avancement vient de [TOOL_NAMED] et se resout par la memoire de l'appelant : `called` ne
 * porte pas de nom, et le lire ici donnerait toujours `undefined`.
 */
export function classify(event: any): { kind: "turnEnd" | "attention" | "progress" | null; tool?: string } {
  const type: string | undefined = event?.type

  if (ATTENTION.has(type ?? "")) {
    return { kind: "attention", tool: toolOf(event) }
  }
  if (TURN_END.has(type ?? "")) {
    return { kind: "turnEnd" }
  }
  if (type === TOOL_CALLED) {
    return { kind: "progress" }
  }
  return { kind: null }
}

/**
 * Le nom porte par l'evenement, quand il y en a un.
 *
 * ⚠️ Les trois branches ne sont pas redondantes : `permission.asked` nomme son **action**, pas un
 * outil (`{action: "shell", resources: [...]}`). Chercher `name` ou `tool` seul laissait le corps
 * de la notification d'approbation sur « approbation requise » — l'utilisateur ne savait plus ce
 * qu'il autorisait. C'est la lecture que la version precedente avait, perdue au passage.
 */
export function toolOf(event: any): string | undefined {
  const payload = event?.data ?? {}
  if (typeof payload.name === "string" && payload.name) return payload.name
  if (typeof payload.tool === "string" && payload.tool) return payload.tool
  if (typeof payload.action === "string" && payload.action) return payload.action
  return undefined
}

/** L'identifiant de session, dans la forme V2 (`data.sessionID`) ou dans les anciennes. */
export function sessionIdOf(event: any): string | undefined {
  return event?.data?.sessionID ?? event?.sessionID ?? event?.properties?.sessionID
}
