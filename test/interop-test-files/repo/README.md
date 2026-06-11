# Repo interop test fixtures

Vendored from the TypeScript reference implementation
[`bluesky-social/atproto`](https://github.com/bluesky-social/atproto),
commit `3e977fe0ab88145f4ff26b491f371d3bade22505`.

These files come from the `@atproto/repo` package's test directory
(`packages/repo/tests/`), not from the shared `interop-test-files/`
directory at the root of that repository (which contains no repo/MST/CAR
fixtures):

- `car-file-fixtures.json` — `packages/repo/tests/car-file-fixtures.json`:
  array of `{root, blocks: [{cid, bytes(base64)}], car(base64)}`; drives
  byte-exact CAR write and structural CAR read tests.
- `commit-proof-fixtures.json` — `packages/repo/tests/commit-proof-fixtures.json`:
  cases of `{comment, leafValue, keys, adds, dels, rootBeforeCommit,
  rootAfterCommit, blocksInProof}`; drives covering-proof generation and
  proof-invertibility tests.

Base64 values use the standard alphabet without padding.
