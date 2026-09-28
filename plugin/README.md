# plugin/ — le plugin opencode de Tether

`tether/` est la source du plugin : le module serveur (`index.ts`), le RPC (`rpc.ts`),
le TUI (`tui.tsx` — les entrees `/tether` et `/tether-config`), et leurs tests.

L'installation, et c'est la seule forme verifiee (voir `scripts/verifie-install.sh`) :

```bash
opencode plugin add git+https://github.com/sk7n4k3d/tether.git
```

Le `package.json` a la racine du depot declare les points d'entree que lit
`PluginHost.resolve` : `./server`, `./rpc`, `./tui`. Le TUI importe `@opentui/solid` et
`solid-js` ; `plugin add` les installe, un simple `cp -r` non.

La source JSX est declaree par une pragma en tete de `tui.tsx`
(`/** @jsxImportSource @opentui/solid */`), pas par un `tsconfig.json` : Bun n'en lit pas
un situe sous `node_modules`, ou vit le paquet installe.

```bash
# tests du plugin, sans dependance a installer
node --experimental-strip-types --test plugin/tether/index.test.mjs \
  plugin/tether/qr.test.mjs plugin/tether/registry.test.mjs \
  plugin/tether/tui-config.test.mjs plugin/tether/tui-logic.test.mjs \
  plugin/tether/ui-model.test.mjs plugin/tether/vapid.test.mjs \
  plugin/tether/webpush.mutation.test.mjs plugin/tether/webpush.test.mjs

# le TUI seul, qui a besoin du transpiler de Bun (JSX)
bun add --no-save @opentui/solid solid-js
bun test plugin/tether/tui-setup.test.mjs
```
