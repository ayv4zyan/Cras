# GitHub data repo is the store; no merge UI

Cras MVP keeps Tasks in the Operator’s private GitHub **data** repo (not the Cras source repo). Each client pushes on save. There is no Cras backend and no local replica as the system of record. Android may hold an **Outbox** while offline. Two creates are two Task ids (Todoist). A same-id write that loses the Git compare-and-swap fails and the client reloads; we do not ship a merge editor or merge-two-tasks.

Local-first on every client was rejected: the Operator did not want a second copy of the list. A custom server was rejected on audience grounds. Title-as-identity was rejected because Monday’s pool and Friday’s pool must stay two Tasks.

Details: [Where do tasks live?](https://github.com/ayv4zyan/Cras/issues/13).
