# Identity & PLC test fixtures

Provenance:

- `did-doc-bad.json`, `did-doc-legacy-key.json`, `did-doc-multikey.json` —
  vendored verbatim from the inline documents in the TypeScript reference
  implementation at `bluesky-social/atproto:packages/identity/tests/did-document.test.ts`
  (commit `b9ef557`).
- `audit-log-legacy.json`, `data-legacy.json` — one-time fetch (2026-06-11) of
  `https://plc.directory/did:plc:yk4dd2qkboz2yv6tpubpc6co/log/audit` and `/data`:
  an old DID whose genesis is a legacy `create` operation (the same DID used by
  the reference did-document tests).
- `audit-log-modern.json`, `data-modern.json` — one-time fetch (2026-06-11) of
  `https://plc.directory/did:plc:ewvi7nxzyoun6zhxrhs64oiz/log/audit` and `/data`:
  a recent DID with a `plc_operation` genesis and several updates.

The audit-log fixtures are golden data: tests recompute every operation's CID
and the genesis DID from the operation bytes and require exact equality with
the recorded `cid`/`did` fields, and validate the full signature chain against
the rotation keys, with the final state matching the `data-*.json` documents.
