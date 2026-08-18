# Can a GitHub repo be the task store?

**Status (2026-08-18):** historical. GitHub as the Task store was withdrawn — [Where do tasks live if the store is Supabase?](https://github.com/ayv4zyan/Cras/issues/24). The current backend lock is Supabase-only — [Does Cras need a separate Cloudflare/Hono backend?](https://github.com/ayv4zyan/Cras/issues/25), `docs/adr/0005-supabase-platform.md`. This note is the primary-source paper trail for [Can a GitHub repo be the task store?](https://github.com/ayv4zyan/Cras/issues/4) and the superseded lock on [Where do tasks live?](https://github.com/ayv4zyan/Cras/issues/13). Clients are TypeScript web + Kotlin Android ([Which client stack do we lock for web and Android?](https://github.com/ayv4zyan/Cras/issues/12)).

Question from [Can a GitHub repo be the task store?](https://github.com/ayv4zyan/Cras/issues/4): the leaning is no app backend; a separate GitHub repo holds all task data; web and Android talk to it; that is the multi-device sync story for MVP.

**Verdict.** A private GitHub repository can be a *personal file store* that both clients write through the REST/GraphQL Git APIs. Primary sources do **not** describe it as a sync service, a database, or an offline-capable backend. Concurrent device writes are SHA/ref races that GitHub rejects rather than merges. A static webapp cannot complete the documented OAuth web flow without a `client_secret` (which GitHub forbids putting on a user device). Offline, queueing, and field-level merge are entirely the client's job.

---

## 1. Write paths GitHub actually documents

Three first-party ways to commit task files:

### Contents API — one file, one commit

`PUT /repos/{owner}/{repo}/contents/{path}` “Creates a new file or **replaces an existing file**.” Updates require the current blob `sha`. Documented statuses include **409 Conflict** and **422**. GitHub says if you use this endpoint and Delete a file “in parallel, the concurrent requests will conflict and you will receive errors. You **must use these endpoints serially** instead.” OAuth/classic PAT needs the `repo` scope.

Get-contents limits: **1,000 files per directory**; for more, use the Git Trees API. File size: **≤ 1 MB** gets full JSON (including `content`); **1–100 MB** only raw/object media types (object `content` is empty); **> 100 MB** “This endpoint is not supported.”

Source: [REST API endpoints for repository contents](https://docs.github.com/en/rest/repos/contents).

### Git Database REST API — raw objects + ref update

GitHub’s own guide: you can “reimplement a lot of Git functionality with the REST API.” The documented commit-a-file sequence is seven steps: get current commit → get tree → get blob → post new blob → post new tree → post new commit (parent = current commit) → **update the branch ref** to the new commit SHA.

Empty or unavailable repos return **409 Conflict** on Git Database calls; initialize with the Contents `PUT` first.

Source: [Using the REST API to interact with your Git database](https://docs.github.com/en/rest/guides/using-the-rest-api-to-interact-with-your-git-database).

`PATCH /repos/{owner}/{repo}/git/refs/{ref}` updates a branch to a new SHA. `force` “Indicates whether to force the update or to make sure the update is a **fast-forward** update. Leaving this out or setting it to `false` will make sure **you’re not overwriting work**.” Default `force: false`. Non-fast-forward without force is a validation failure (**422**). Empty repos cannot create refs.

Source: [REST API endpoints for Git references](https://docs.github.com/en/rest/git/refs).

Creating a tree: `base_tree` is “normally … the SHA1 of the Git tree object of the **current latest commit** on the branch.” Entries overwrite same-path items. Get-tree with `recursive`: **100,000 entries, max 7 MB**; if `truncated` is true, walk subtrees instead.

Source: [REST API endpoints for Git trees](https://docs.github.com/en/rest/git/trees).

Get-blob: “This endpoint supports blobs up to **100 megabytes**.”

Source: [REST API endpoints for Git blobs](https://docs.github.com/en/rest/git/blobs).

### GraphQL `createCommitOnBranch` — one mutation, required expected HEAD

“Appends a commit to the given branch … parent is the **HEAD of the provided branch** and also updates that branch … similar to `git commit`.” Input includes **required** `expectedHeadOid`: “The git commit oid expected at the head of the branch **prior to the commit**.” Authorship is the credential owner; for full author/committer control use the Git Database REST API.

This is GitHub’s first-party optimistic-concurrency primitive: if another device moved `HEAD`, the mutation does not silently clobber it.

Source: [GraphQL Commits — `createCommitOnBranch`](https://docs.github.com/en/graphql/reference/commits#mutation-createcommitonbranch).

---

## 2. Auth from a static webapp and from Android

### What tokens exist

| Mechanism | GitHub’s stated purpose | Least privilege for a private task repo |
| --- | --- | --- |
| Fine-grained PAT | Preferred PAT; one user/org; selected repos; permission bits | `contents: write` on that one repo |
| Classic PAT | Broader; some APIs still classic-only | `repo` scope (all repos the user can access) |
| GitHub App user access token (`ghu_`) | Preferred long-lived integration; fine-grained permissions; short-lived | App + user both need Contents write on that repo |
| GitHub App installation token | Acts as the app, not the user | Scales rate limit; not “on behalf of you” |
| OAuth app token | Legacy; broad scopes | `repo` |

GitHub: PATs are “intended to access GitHub resources **on behalf of yourself**.” For “long-lived integrations” use a GitHub App. Fine-grained PATs are recommended over classic. Fine-grained PATs: limited to one owner, selected repos, fine-grained permissions. Classic: “can access **every repository that you can access**.” Unused PATs are removed after a year; expiration is “highly recommend[ed].” Cap: **50 fine-grained PATs** per user.

Sources: [Managing your personal access tokens](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens); [Deciding when to build a GitHub App](https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/deciding-when-to-build-a-github-app).

### Static webapp (pages / SPA, no server)

`api.github.com` sends `Access-Control-Allow-Origin: *` (and exposes `ETag`, `X-RateLimit-*`, etc.). A browser **may call the REST API** with a user-held token.

Source: documented response headers in [Getting started with the REST API](https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api).

The **web application OAuth flow cannot complete in the browser alone**:

- Implicit grant is **not supported**.
- Token exchange is `POST https://github.com/login/oauth/access_token` with **`client_secret` Required**.
- “CORS pre-flight requests (OPTIONS) are **not supported** at this time” on the authorize endpoint.
- PKCE (`code_challenge` / `S256`) is “Strongly recommended” but does **not** replace `client_secret` on the documented web token exchange.

Source: [Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps). Same `client_secret` **Required** on GitHub App user-token exchange: [Generating a user access token for a GitHub App](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app).

GitHub forbids putting that secret in the client:

- Rate-limit page: “**Never include your app’s client secret in client-side code or in code that runs on a user device.** The client secret can be used to generate OAuth access tokens for users who have authorized your app.”
- Credentials page: “Never hardcode authentication credentials … into your code.” Don’t push tokens to any repo, even private.

Sources: [Rate limits for the REST API](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api#primary-rate-limit-for-oauth-apps); [Keeping your API credentials secure](https://docs.github.com/en/rest/authentication/keeping-your-api-credentials-secure).

**Device flow does not need `client_secret`.** GitHub lists it for “headless apps, such as CLI tools” and “Git Credential Manager.” Token poll uses `client_id` + `device_code` + `grant_type=urn:ietf:params:oauth:grant-type:device_code`. Error `incorrect_client_credentials`: “The `client_secret` is **not needed** for the device flow.” Must be enabled in app settings. Refresh of a device-flow token: `client_secret` is “Required **unless** the token was generated using the device flow.”

Source: [Authorizing OAuth apps — Device flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow).

**Workable static-web patterns GitHub actually documents:**

1. Operator pastes a **fine-grained PAT** (`contents: write` on the data repo) and the SPA stores it locally. Personal use is the documented PAT audience. The token is a password; localStorage/XSS is the app’s problem, not GitHub’s.
2. Device flow from the SPA (no secret). Awkward UX (user types a code at `https://github.com/login/device`) but first-party.
3. A tiny **token-exchange backend** that holds `client_secret` and runs the web flow + PKCE. That is a backend — it contradicts the leaning.

### Kotlin Android

GitHub’s device-flow audience is “CLI tools, simple Raspberry Pis, and **desktop applications**” and apps that “do not have access to a browser” — the user still completes auth **in a browser** at `/login/device`. That maps cleanly to a Kotlin Android app (Chrome Custom Tab / in-app browser for the user code). The same GitHub rules applied when Android was assumed to be React Native.

Web flow from a native app still requires `client_secret` on token exchange. Loopback `redirect_uri` (`http://127.0.0.1:port/path`) is documented for **desktop** native apps, not as an Android custom-scheme story. Non-HTTP redirect URIs make the account picker appear; they do not remove the secret.

User access tokens expire in **8 hours**; refresh tokens in **6 months**. GitHub “strongly encourages” expiring tokens.

Sources: [Generating a user access token](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app); [Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps).

GitHub Apps vs PATs for this product: GitHub prefers Apps for anything long-lived (fine-grained permissions, short-lived tokens, install scoped to one repo). For a **single-operator** MVP, a fine-grained PAT is the documented “personal use / short-lived scripts” path and avoids shipping an App client secret. Two devices can each hold the same PAT or each hold its own (50-token cap).

---

## 3. Conflict / merge if two devices write

GitHub does **not** merge concurrent file edits on these APIs. It compares identifiers and rejects.

| Path | Concurrency check | Losing writer sees |
| --- | --- | --- |
| Contents `PUT`/`DELETE` | Blob `sha` of the file being replaced; parallel Contents calls “will conflict” | **409** / errors; GitHub says use **serially** |
| Git Database + `update ref` | Fast-forward of the **branch** (`force: false` default: “not overwriting work”) | **422** if not a fast-forward |
| `createCommitOnBranch` | Required `expectedHeadOid` must be current branch HEAD | Mutation fails; HEAD unchanged |

Two devices checking off different tasks:

- **One JSON file.** Both read the same blob SHA / HEAD. First commit wins. Second’s `sha` / `expectedHeadOid` is stale. GitHub will **not** three-way-merge JSON. The client must re-GET, merge in-app, retry.
- **One file per task.** Different paths can both apply if the writer builds on the **latest tree** (`base_tree` / latest HEAD). If both started from the same HEAD and each updates the ref, the second is not a fast-forward unless it first incorporates the first commit. Contents `PUT` on two different paths in parallel is still forbidden (“use these endpoints serially”). GraphQL can add/delete multiple files in **one** commit — still one `expectedHeadOid`.
- **Force-push / omit SHA.** `force: true` or a Contents replace without the current SHA overwrites the other device. GitHub’s default is the opposite.

There is no documented server-side CRDT, operational transform, or per-field merge for repository files.

`git notes` *does* have merge strategies (`manual`, `ours`, `theirs`, `union`, `cat_sort_uniq`), but notes are extra blobs hung off **Git objects** (typically commits), default ref `refs/notes/commits`, “without touching the objects themselves.” They are not a task table. GitHub’s refs API will *list* notes refs if present (“including notes and stashes if they exist on the server”). There is **no** GitHub REST “notes” resource.

Sources: [Contents](https://docs.github.com/en/rest/repos/contents); [Git refs](https://docs.github.com/en/rest/git/refs); [`createCommitOnBranch`](https://docs.github.com/en/graphql/reference/commits#mutation-createcommitonbranch); [git-notes](https://git-scm.com/docs/git-notes).

---

## 4. Offline: what GitHub cannot do

Nothing in the REST, GraphQL, or OAuth docs offers an offline mode. The API is HTTPS to `api.github.com`. Documented gaps:

- **No write without the network.** Every Contents PUT, blob/tree/commit/ref, or `createCommitOnBranch` is a live request.
- **No server-side write queue.** If the phone is offline, GitHub never sees the check-off.
- **No merge-on-reconnect.** Reconnect is just another commit attempt; stale `sha` / `expectedHeadOid` fails as above.
- **No webhook delivery to a static webapp or a phone.** GitHub: “You should subscribe to webhook events instead of polling.” Webhooks need an HTTPS endpoint you operate — a backend. Best-practice alternative is **conditional GET** (`ETag` / `If-None-Match`); a `304` “does not count against your primary rate limit.” Conditional requests “for unsafe methods (`POST`, `PUT`, `PATCH`, `DELETE`) are **not supported**” unless an endpoint says otherwise.

Source: [Best practices for using the REST API](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api).

- **Local `git` clone is the only first-party offline store**, and it is a full Git working copy, not the Contents API. Offline edits become local commits; sync is `fetch` + merge/rebase + `push`, with the same fast-forward rules. That is a different architecture than “web and Android talk to the Contents API.”
- **Download URLs expire** (“meant to be used just once”). Private tarball/zipball links “expire after **five minutes**.” Not a cache protocol.

Source: [Repository contents](https://docs.github.com/en/rest/repos/contents).

---

## 5. Rate limits and latency for “checking off a task”

### Primary (hourly)

Authenticated user (PAT, OAuth token, GitHub App *user* token): **5,000 REST requests/hour**, shared across all of those methods. Unauthenticated: **60/hour**. GitHub App *installation* tokens start at 5,000/hour and can scale (not the user-token path).

Source: [Rate limits for the REST API](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api).

### Secondary (the one that bites a check-off loop)

GitHub also enforces unpublished-changeable secondary limits. Documented triggers that matter here:

- **≤ 100 concurrent** requests (REST + GraphQL).
- **≤ 900 points/minute** on REST. Most `GET` = 1 point; most `POST`/`PATCH`/`PUT`/`DELETE` = **5 points**. GraphQL mutation = 5 points.
- **≤ 80 content-generating requests/minute** and **≤ 500/hour** (“Create too much content on GitHub in a short amount of time”). This includes the website, REST, and GraphQL. “Some endpoints have lower content creation limits.”
- CPU-time budget (~90s CPU / 60s real).
- “You may also encounter a secondary rate limit for **undisclosed reasons**.”

Best practices that are binding for a task-check pattern:

- “**Avoid concurrent requests** … make requests **serially**.”
- “If you are making a large number of `POST`, `PATCH`, `PUT`, or `DELETE` requests, **wait at least one second between each request**.”
- On `429`/`403`: honor `retry-after` or `x-ratelimit-reset`; else wait ≥ 1 minute, then exponential backoff. Continuing while limited “may result in the **banning of your integration**.”

Sources: [Rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api); [Best practices](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api).

### What one check-off costs

| Approach | Requests per check-off | Notes |
| --- | --- | --- |
| Contents: GET file + PUT | 2 (GET 1 pt, PUT 5 pt) | PUT is a **content-generating** commit. Need current `sha`. |
| Git Database recipe | ~7 | Each mutative step counts; last is the ref update. |
| `createCommitOnBranch` | 1 GraphQL mutation (5 pts) | Still one Git commit / content-creating event. Need `expectedHeadOid` (often a prior query). |

Ten rapid check-offs as ten commits: GitHub’s published guidance is **≥ 1 s between mutative calls** and **≤ 80 content-creating calls/minute**. The hourly content-creating cap is **500**. Primary 5,000/hour is not the first wall.

There is **no published p50/p99** for these endpoints. Lower bound is one (or several) Internet RTTs to `api.github.com` plus Git object + ref update. Conditional GET helps **reads** (`304` is free on the primary limit). Writes always pay.

Polling the other device’s changes without webhooks consumes the primary budget unless you use `ETag`. GitHub’s advice is webhooks, which a no-backend app cannot receive.

---

## 6. File shapes

Constraints that come from GitHub/Git, not folklore:

| Constraint | Source |
| --- | --- |
| Contents directory listing caps at **1,000** entries | [Contents](https://docs.github.com/en/rest/repos/contents) |
| Recursive trees: **100k entries / 7 MB**, else `truncated` | [Trees](https://docs.github.com/en/rest/git/trees) |
| Contents GET: 1 MB full JSON; 100 MB hard stop | [Contents](https://docs.github.com/en/rest/repos/contents) |
| Blobs via Git API: **100 MB** | [Blobs](https://docs.github.com/en/rest/git/blobs) |
| Browser upload: **25 MiB**; `git push` warns **50 MiB**, blocks **100 MiB** | [About large files on GitHub](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github) |
| Repos “ideally **less than 1 GB**”, “**less than 5 GB** is strongly recommended” | Same page |
| “**Git is not designed to handle large SQL files**.” “**Git is not designed to serve as a backup tool.**” | Same page |
| Huge directories are expensive: every file change rewrites every tree on the path; “Avoid creating directories with more than a **couple of thousand** entries”; shard if you must | [github/git-sizer](https://github.com/github/git-sizer) (first-party) |
| “Avoid storing log files and **database dumps** in Git.” “Consider using a **database instead**” for giant, frequently modified data files | git-sizer README |
| git-sizer suggests notes *instead of tags* for auxiliary commit info (CI results) — not as a row store | Same |
| Notes default ref is not ordinary file history; every notes change is a commit on `refs/notes/…` | [git-notes](https://git-scm.com/docs/git-notes) |

**One JSON file (e.g. `tasks.json`).** Fits Contents GET/PUT. One read loads the world. Every check-off rewrites the blob and races on one SHA. Fine for a few thousand small tasks if the client serializes writes and retries on 409. Becomes a hot blob: git-sizer flags “many versions of large text files, each one slightly changed” as expensive to reconstruct/diff.

**One file per task (e.g. `tasks/{id}.json`).** Isolates blob SHAs so two devices editing *different* tasks can both succeed **if** the writer always parents the latest branch commit (Git Database / `createCommitOnBranch` with fresh `expectedHeadOid`). Contents PUT still must be serial. A flat directory hits the **1,000-file listing cap** and git-sizer’s “couple of thousand entries” warning — shard (`tasks/ab/cd/{id}.json`). Listing all tasks is a recursive tree walk (100k / 7 MB) or many Contents GETs.

**Git notes.** First-party purpose: annotate existing objects (usually commits) without rewriting them. No GitHub Notes REST API. Default notes ref is easy to miss on clone/fetch. Merge strategies exist but operate on note blobs keyed by object id, not on a task schema. Wrong primitive.

**Sensible shape if the store is Git anyway:** sharded one-file-per-task (or one file per day/inbox slice) so independent edits are independent blobs; a small index/manifest only if you accept that *that* file is a single-SHA lock; always commit with `expectedHeadOid` / fast-forward; never parallel Contents PUTs.

---

## 7. First-party caveats (not blog folklore)

GitHub’s own words on using Git as a data dump:

- Repository health “is a function of various interacting factors, including **size, commit frequency, contents, and structure**.” High commit frequency is an explicit health signal. A check-off-per-commit personal app is frequent-commit by design.
- “Git is not designed to handle large SQL files.” “Git is not designed to serve as a backup tool.”
- git-sizer (github/git-sizer): Git’s “sweet spot” is not “very many tiny files,” not “gigantic trees,” not frequently rewritten large text/data files; for the last, “Consider using a database instead.”
- Contents API authors tell you concurrent file writes **will conflict** and to go serial.
- Ref updates default to **fast-forward only** so you don’t “overwrite work.”
- `createCommitOnBranch` **requires** `expectedHeadOid` — GitHub chose compare-and-swap, not merge.

**Adjacent first-party primitive (not a committed file format):** [About issues](https://docs.github.com/en/issues/tracking-your-work-with-issues/about-issues) — GitHub’s own task object (“track ideas, feedback, **tasks**, or bugs”), with REST/GraphQL, sub-issues, and Projects. That is a different store (Issues API, not a data repo). Out of scope for the leaning, but it is the product GitHub actually built for tasks.

---

## 8. What this means for the MVP leaning

The leaning (no backend; data repo; web + Android; that is sync) is **partially supported**:

- **Yes:** a private repo can hold JSON/Markdown the two clients commit. CORS allows the SPA to call `api.github.com` with a token. Fine-grained PAT or device-flow GitHub App can authorize Android without embedding a client secret. `createCommitOnBranch` + `expectedHeadOid` is a clean compare-and-swap. For one operator who is rarely on two devices at once, last-writer-retry is enough.
- **No, not as stated:** GitHub is not the multi-device **sync story**. It is a remote Git database with optimistic locking. The app must implement serial writes, conflict retry, local cache, and offline queue. A static webapp cannot do the documented OAuth web flow without a secret-holding backend or a pasted PAT. Cross-device live update without a backend is **polling + ETag**, not webhooks. Check-off-per-commit sits under **content-creation** secondary limits and GitHub’s “commit frequency” health metric.

The map **did** lock GitHub as system of record on [Where do tasks live?](https://github.com/ayv4zyan/Cras/issues/13), then **withdrew** it. Current backend: Supabase only — [Does Cras need a separate Cloudflare/Hono backend?](https://github.com/ayv4zyan/Cras/issues/25), `docs/adr/0005-supabase-platform.md`. What that old lock had specified: fine-grained PAT (not secret-in-SPA); sharded one-file-per-Task; Android Outbox; **no** merge UI (first push wins, second save fails and reloads). GraphQL `createCommitOnBranch` / non-force ref update was the honest Git write path; fire-and-forget Contents PUTs were not.

---

## Sources

- https://docs.github.com/en/rest/repos/contents — Contents GET/PUT/DELETE; `sha`; 409; serial use; 1,000-file dirs; 1 / 100 MB limits
- https://docs.github.com/en/rest/guides/using-the-rest-api-to-interact-with-your-git-database — seven-step commit; 409 on empty repo
- https://docs.github.com/en/rest/git/refs — fast-forward vs `force`; notes refs visible on matching-refs
- https://docs.github.com/en/rest/git/trees — `base_tree`; 100k / 7 MB recursive trees
- https://docs.github.com/en/rest/git/blobs — 100 MB blob GET
- https://docs.github.com/en/graphql/reference/commits — `createCommitOnBranch`, required `expectedHeadOid`
- https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps — no implicit grant; `client_secret` required on web exchange; no CORS OPTIONS on authorize; device flow without secret; PKCE; 8 h / 6 mo tokens
- https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app — App user tokens; device flow; `client_secret` required on web exchange
- https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens — fine-grained vs classic; 50-token cap; treat as passwords
- https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/deciding-when-to-build-a-github-app — Apps vs PAT vs OAuth
- https://docs.github.com/en/rest/authentication/keeping-your-api-credentials-secure — no secrets in client code
- https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api — 5,000/h; secondary limits; 80/min and 500/h content creation; never ship client secret
- https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api — no polling if you can webhook; serial mutates; ≥ 1 s between writes; ETag; no conditional PUT
- https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api — `Access-Control-Allow-Origin: *`
- https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github — size limits; Git is not SQL / not a backup tool
- https://github.com/github/git-sizer — first-party size/structure caveats; database-instead-of-rewritten-data-files
- https://git-scm.com/docs/git-notes — notes model and merge strategies
- https://docs.github.com/en/issues/tracking-your-work-with-issues/about-issues — GitHub’s own task object (adjacent, not a file store)
