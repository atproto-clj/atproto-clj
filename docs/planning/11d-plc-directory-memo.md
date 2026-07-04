# WS-11D: PLC Directory Service — Decision Memo

| | |
|---|---|
| **Status** | Decided |
| **Priority** | P3 |
| **Parent** | WS-11 (Service Pieces) |
| **Deliverable** | This memo (no code) |
| **Recommendation** | **Defer implementation indefinitely.** A minimal Clojure PDS needs a PLC *client* (operation signing/submission), not a PLC *directory service*. |

## Question

Should the SDK implement a [did:plc](https://github.com/did-method-plc/did-method-plc)-directory-compatible
identity service (the ⭕ "Identity Directory" row, `README.md:31`) as part of
the service-pieces effort?

## Recommendation

**No — defer indefinitely.** Nothing currently chartered consumes a
self-hosted directory, and the long-horizon WS-11 goal (a minimal Clojure PDS)
does not require the SDK to *be* a directory. What that PDS actually needs from
the PLC system is the *client* side — creating, signing, and submitting
`did:plc` operations to a directory (by default `https://plc.directory`). That
work belongs to the "PLC Operations" README row (`README.md:27`, already 🟢 for
resolution; operation *submission* is the gap), outside WS-11's service-pieces
scope.

Revisit only if a concrete requirement appears — e.g. an air-gapped or
self-contained deployment that must run its own directory with no dependency on
the canonical `plc.directory`.

## What a PLC directory service requires

The reference server lives in `github.com/did-method-plc/did-method-plc`
(`@did-plc/server`), not the atproto monorepo; the PDS consumes it as a library
(`@did-plc/lib`) and runs the server only in tests. A compatible service would
need:

1. **An append-only operation log per DID.** The DID is derived from the hash
   of its genesis operation; every subsequent operation references the CID of
   the previous one, forming a hash chain. Storage is a single ordered log
   keyed by DID (a natural fit for the 11B SQLite conventions — one table,
   `did`, `cid`, `operation` (DAG-CBOR bytes), `nullified`, `createdAt`).

2. **Operation validation.** For each submitted operation:
   - verify the DAG-CBOR structure and the operation's self-CID;
   - verify the signature against one of the DID's currently-authorized
     **rotation keys** (WS-03 already provides secp256k1/P-256 verification and
     did:key handling);
   - enforce the `prev` chaining (must point at the current head, or trigger
     the recovery path below);
   - recompute and confirm the derived DID for genesis operations.

3. **Rotation-key recovery semantics.** A higher-priority rotation key may fork
   the log within a **72-hour recovery window**, nullifying operations signed by
   a lower-priority key. This is the subtlest part of the spec and the main
   source of implementation risk: it requires ordering by wall-clock receipt
   time, a "nullified" flag, and careful handling of races between the fork and
   in-window operations.

4. **The HTTP service surface** (from the PLC spec / reference server):
   - `GET /:did` — the current DID document;
   - `GET /:did/data` — the current operation data (resolved log state);
   - `GET /:did/log` and `GET /:did/log/audit` — the operation log;
   - `POST /:did` — submit a signed operation;
   - `GET /export` — a paginated firehose of all operations (for mirrors/relays).

## What it would reuse from this SDK

- **11B's SQLite conventions** (`atproto.pds.sql`): WAL mode, the tiny
  migrations runner, and the write-transaction/busy-retry helpers map directly
  onto an append-only op-log table.
- **The XRPC/Ring HTTP server** (`atproto.xrpc.server`, `…/ring`): the
  directory's routes are plain JSON HTTP and would mount alongside the existing
  handler. (The PLC routes are not XRPC/NSID-shaped, so they'd be a sibling
  Ring handler rather than `handle` methods.)
- **WS-03 crypto** (`atproto.crypto`): rotation-key signature verification,
  did:key parsing, and the secp256k1/P-256 primitives already exist.
- **WS-02 DAG-CBOR + CID** (`atproto.data.cbor`, `atproto.data`): operation
  encoding and self-CID computation.

In short, the building blocks exist; the missing pieces are the op-log
validation state machine and the recovery-window rules — a self-contained,
well-specified but genuinely fiddly workstream.

## Why defer

1. **No consumer.** A minimal Clojure PDS registers accounts on the *existing*
   `plc.directory` (or uses did:web); it does not host a directory. No
   chartered or planned workstream depends on a self-hosted one.
2. **Centralization reality.** The canonical `plc.directory` is the network's
   directory of record. A self-hosted directory is only useful for isolated
   deployments or as a mirror — neither of which is a stated SDK goal
   (`README.md:43` scopes out appview/relay-class infrastructure).
3. **Risk vs. value.** The 72-hour recovery-window logic is the highest-risk
   part of the PLC spec, and getting it subtly wrong has security
   consequences (key-recovery bypass). Building it without a consumer that
   exercises it is a poor risk/value trade.
4. **The real gap is the client.** Account creation on the live network needs
   signed `did:plc` genesis/update operations *submitted* to a directory. That
   is a bounded, high-value addition (sign an operation with a rotation key,
   POST it, handle the response) and belongs to the "PLC Operations" README row
   — a separate, smaller charter than a directory service.

## Action for the orchestrator

Flag to the future PDS-assembly workstream that **PLC client operations**
(genesis/update operation construction, rotation-key signing, submission to a
directory endpoint) need chartering — the reference is `@did-plc/lib`, consumed
by the PDS at `packages/pds/package.json`. That, not this directory service, is
on the critical path to a minimal Clojure PDS.
