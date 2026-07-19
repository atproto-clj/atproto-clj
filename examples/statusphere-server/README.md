# Statusphere — server-side Clojure example

The [Statusphere tutorial app](https://atproto.com/guides/statusphere-tutorial)
built with this repo's atproto SDK on a server-rendered stack:

- **Datomic Pro** as the app's local read model / index
- **Sierra components** (`com.stuartsierra/component`) for system structure
- **Pedestal 0.8** in async mode
- **Hiccup** for server-side rendering — zero client-side JavaScript

Design doc: [`docs/planning/12-statusphere-app.md`](../../docs/planning/12-statusphere-app.md).
(The SPA + XRPC-server variant of this app lives at [`../statusphere`](../statusphere).)

What it does: sign in with any atproto account (OAuth), pick an emoji, and the
app writes an `xyz.statusphere.status` record to *your* PDS — the source of
truth. A Jetstream consumer ingests everyone's statuses into Datomic, which the
home page renders as a feed. The local database is a disposable index: wipe it
and the firehose rebuilds it.

## Running

### 1. Datomic

The peer library comes from Maven Central (no license key). For a durable
database, run a local [dev transactor](https://docs.datomic.com/setup/pro-setup.html#get-datomic):

```
curl -O https://datomic-pro-downloads.s3.amazonaws.com/1.0.7705/datomic-pro-1.0.7705.zip
unzip datomic-pro-1.0.7705.zip && cd datomic-pro-1.0.7705
bin/transactor config/samples/dev-transactor-template.properties
```

Wait for `System started`. (Zero-setup alternative: set `:db-uri` to
`"datomic:mem://statusphere"` — no transactor, nothing survives a restart.)

### 2. Config

```
cp config-sample.edn config.edn
```

Fill in `:cookie-secret` (`openssl rand -base64 16`). The defaults run the app
at `http://127.0.0.1:8080` in the OAuth spec's loopback-client mode: real
sign-in against real PDSes with no public URL, no HTTPS, and no hosted client
metadata. Use `127.0.0.1`, not `localhost`, in the address bar — the loopback
rules require it in the redirect URI, and the session cookie is bound to the
host.

### 3. Run

```
clojure -M:run            # serves http://127.0.0.1:8080
clojure -M:test           # unit tests; datomic:mem only, no network
```

Sign in with a real handle (e.g. `alice.bsky.social`), pick an emoji, and it
appears in the feed — as do statuses posted by anyone else on the network
(e.g. from the hosted tutorial app), within seconds, via Jetstream.

## REPL workflow

```
clj -M:dev
user=> (start)   ;; needs config.edn, as above
user=> (reset)   ;; stop, reload changed namespaces, start fresh
user=> (stop)
```

`(reset)` comes from `com.stuartsierra.component.repl`; the system map lives
in `statusphere.system`, and every component's lifecycle is defined there.

## How it's put together

```
src/statusphere/
├── main.clj      ;; -main: read + spec-validate config, start system, block
├── system.clj    ;; ALL component lifecycles: :datomic :oauth-client
│                 ;;   :handle-resolver :ingester :http
├── db.clj        ;; Datomic schema (data), queries, tx builders
├── auth.clj      ;; OAuth client metadata + Datomic-backed token stores
├── ingester.clj  ;; Jetstream event handling + throttled cursor persistence
├── handles.clj   ;; DID → handle via the SDK's identity cache
├── routes.clj    ;; Pedestal routes, interceptors, async handlers
└── views.clj     ;; pure hiccup
```

Components hold resources; logic lives in plain functions over plain values —
`db.clj`, `views.clj`, and `ingester/handle-event!` are all callable from a
bare REPL with no system running.

**Async model.** Handlers are `request → channel-of-response` go blocks;
Pedestal parks the interceptor chain on the returned channel. Two idioms cover
every wait, inline at each call site:

```clojure
(a/<! (oauth-client/restore client did :channel (a/promise-chan))) ;; SDK call
(a/<! (a/io-thread @(d/transact conn tx)))                         ;; Datomic write
```

Nothing on a request path blocks a shared thread; blocking code lives only in
`a/io-thread` bodies, the ingester's dedicated consumer thread, and the
synchronous-by-contract OAuth `Store` implementations.

## Production notes

Set `:public-url` to the app's HTTPS origin: the OAuth client switches from
loopback mode to hosted client metadata (served at `/client-metadata.json`),
and the server binds `0.0.0.0`. Datomic moves to a storage-backed transactor
by changing `:db-uri`.
