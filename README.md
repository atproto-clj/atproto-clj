# ATProto Clojure SDK

The SDK was designed to work in Clojure, ClojureScript, and ClojureDart.

> [!CAUTION]
> Work very much in progress. Do not use in production. The API will likely change before the first release.

## Progress

From [What goes in to a Bluesky or atproto SDK?](https://github.com/bluesky-social/atproto/discussions/2415):

| Component           | Clojure | ClojureScript | ClojureDart |
| ------------------- | ------- | ------------- | ----------- |
| **Basic**           | | | |
| API Client          | 🟢 | 🟢 | ❓ |
| Lexicon Types       | 🟢 | 🟢 | 🟢 |
| Identifier Syntax   | 🟢 | 🟢 | 🟢 |
| **Protocol + Data** | | | |
| Keys and Crypto     | 🟢 | ⭕ | ⭕ |
| MST and Repo        | ⭕ | ⭕ | ⭕ |
| Data model          | 🟡 (no CBOR) | 🟡 (no CBOR) | ❓ |
| Lex Validation      | 🟢 | 🟢 | ❓ |
| Identity Resolution | 🟡 | 🟡 | 🟡 |
| Stream client       | 🟡 (Jetstream only) | ⭕ | ⭕ |
| Service Auth        | 🟡 | 🟡 | ❓ |
| Lex Codegen         | N/A | N/A | N/A |
| PLC Operations      | ⭕ | ⭕ | ⭕ |
| OAuth Backend       | ⭕ | ⭕ | ⭕ |
| **Service Pieces**  | | | |
| HTTP Server         | 🟡 | 🟡 | ⭕ |
| Identity Directory  | ⭕ | ⭕ | ⭕ |
| Repo Storage        | ⭕ | ⭕ | ⭕ |
| Stream Server       | ⭕ | ⭕ | ⭕ |

- ✅ great! complete, documented, examples, accessible to new devs with no atproto experience
- 🟢 decent. mostly implemented, could point experienced devs at it
- 🟡 partial progress: incomplete, undocumented, not ergonomic
- 🚧 early work in progress, but not usable yet
- ⭕ nothing started
- 🟣 something exists; not assessed
- ❓ unknown (need to check status)

We're not currently planning on supporting Bluesky-specific functionalities: post helpers, social graph helpers, label behaviors, preferences.

## Usage

Most calls to the APIs are asynchronous, and return immediately. The return value depends on the platform:

- Clojure: a Clojure promise.
- ClojureScript: a core.async channel.
- ClojureDart: a Dart Watchable.

You can also provide a `:channel`, `:callback` or `:promise` keyword option to receive the return value. Not all options are supported on all platforms.

### ATProto Client

The ATProto client supports 3 authentication modes:
- Unauthenticated to make query/procedure calls to public ATProto endpoints
- Credentials-based authentication to use with your own username/password for CLI tools
- OAuth to make query/procedure calls on your users' behalf.

You specify the mode when initializing the client.

```clojure
(require '[atproto.client :as at])

;; Unauthenticated client to public endpoint
(def client @(at/init {:service "https://public.api.bsky.app"}))

;; Bluesky endpoints and their query params can be found here:
;; https://docs.bsky.app/docs/category/http-reference

;; Credentials-based authenticated client
(def client @(at/init {:credentials {:identifier "<me.bsky.social>"
                                     :password "SECRET"}}))

;; For OAuth, see the Statusphere example app
```

Once the client has been initialized, `query` and procedure `calls` can be made against the ATProto service endpoint.

```clojure
;; Issue a query with the client
@(at/query client {:nsid "app.bsky.actor.getProfile"
                   :params {:actor "<me.bsky.social>"}})

;; => {:handle "<me.bsky.social>" ... }

;; Using core.async
(def result (async/chan))
(at/query client
          {:nsid "app.bsky.actor.getProfile"
           :params {:actor "<me.bsky.social>"}}
          :channel result)
(async/<!! result)
```

If an error occurs, the response map will contain an `:error` key containing a short name describing the error. A human-readable message is usually added as well under the `:message` key.

### Jetstream

Connect to Bluesky's [Jetstream service](https://docs.bsky.app/blog/jetstream) to get real-time updates of public network data. Jetstream provides a JSON-based alternative to the binary CBOR firehose, making it easier to work with post streams, likes, follows, and other events.

The Jetstream implementation is currently only supported for JVM Clojure.

```clojure
(require '[atproto.jetstream :as jet])
(require '[clojure.core.async :as a]))

;; Define a channel to recieve events
(def events-ch (a/chan))

;; Subscribe to the jetstream
(def control-ch (jet/consume events-ch :wanted-collections ["app.bsky.feed.post"]))

;; Consume events
(a/go-loop [count 0]
  (if-let [event (a/<! events-ch)]
    (do
      (when (zero? (rem count 100)) (println (format "Got %s posts" count)))
      (recur (inc count)))
    (println "event channel closed")))

;; Stop processing
(a/close! control-ch)
```

See the [examples](/examples) directory for more examples.

## SDK Organization

The platform-specific functions are under the `atproto.runtime` namespace. Most of the rest of the code is platform-agnostic.

| Namespace      | Purpose |
| -------------- | ------- |
| `client`       | ATProto client to make query and procedure calls to ATProto services. |
| `credentials`  | Credentials-based session to use with the ATProto client. |
| `oauth.client` | OAuth 2.0 client for ATProto profile. Create a session that can be used with the ATProto client. |
| `data`         | ATProto data model. |
| `data.json`    | JSON-representation of the ATProto data model. |
| `identity`     | Identity resolution (handles and DIDs). |
| `jetstream`    | Clojure client for Bluesky's Jetstream service. |
| `lexicon`      | Schema definition and validation functions for records, queries, and procedure calls. |
| `runtime`      | Runtime-specific functions. |
| `tid`          | Timestamp identifiers for records. |
| `xrpc.client`  | To make queries and procedure calls to XRPC servers. |
| `xrpc.server`  | To implement XRPC servers. |

## Contribute

Help is very much welcomed!

Before submitting a pull request, please take a look at the [Issues](https://github.com/goshatch/atproto-clojure/issues) to see if the topic you are interested in is already being discussed, and if it is not, please create an Issue to discuss it before making a PR.

## License

MIT, see LICENSE file
