
atproto Interop Test Files
==========================

This directory contains reusable files for testing interoperability and specification compliance for atproto (AT Protocol).

The protocol itself is documented at <https://atproto.com/specs/atp>. If there are conflicts or ambiguity between these test files and the specs, the specs are the authority, and these test files should usually be corrected.

These files are intended to be simple (JSON, text files, etc) and mostly self-documenting.

## data-model/

`data-model/data-model-fixtures.json` is vendored verbatim from the TypeScript
reference implementation at
`bluesky-social/atproto:packages/lex/lex-cbor/tests/data-model-fixtures.json`
(commit `3e977fe`). Each fixture is `{json, cbor_base64, cid}`: the canonical
DAG-CBOR encoding (base64, unpadded) and CIDv1 (dag-cbor, sha2-256) of the
JSON-form data.
