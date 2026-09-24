# plugin/ — copie de référence du plugin ntfy-opencode

`ntfy-opencode.ts` ici est une **copie de référence** du fichier qui tourne
réellement dans opencode : `~/.config/opencode/plugins/ntfy-opencode.ts`.

⚠️ **La source de vérité est le fichier de `~/.config/opencode/plugins/`** — c'est
lui qu'il faut éditer (c'est ce que le serveur opencode charge), puis recopier ici
pour versionner le delta.

Vérifier qu'il n'y a pas de dérive :

```bash
diff -q ~/.config/opencode/plugins/ntfy-opencode.ts ~/Projects/tether/plugin/ntfy-opencode.ts
```

Aucune sortie = synchronisé. `ntfy-tether.test.mjs` teste le **fichier réel** de
`~/.config` (import via `../../../.config/...`), pas cette copie.
