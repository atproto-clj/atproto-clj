// Generates jwt_fixtures.json from the TypeScript reference implementation.
//
// Usage:
//   npm install @atproto/xrpc-server@0.11.1 @atproto/crypto@0.5.0
//   node generate_fixtures.mjs > jwt_fixtures.json
//
// Tokens are minted with createServiceJwt from @atproto/xrpc-server
// (packages/xrpc-server/src/auth.ts). ECDSA signing in the reference is
// deterministic (RFC 6979 via @noble/curves), but each token embeds a
// freshly generated random `jti`, so regenerated files differ in jti and
// signature; the committed output is what the Clojure tests consume.
import { createServiceJwt, verifyJwt } from '@atproto/xrpc-server'
import { Secp256k1Keypair, P256Keypair } from '@atproto/crypto'

const ISS = 'did:example:alice'
const AUD = 'did:example:bob'
const LXM = 'com.atproto.repo.createRecord'
const IAT = 1750000000
const EXP = 32503680000 // 3000-01-01, keeps verification tests green
const PAST_EXP = 1750000060

const keys = {
  ES256K: {
    privHex: '0000000000000000000000000000000000000000000000000000000000000001',
    make: (hex) => Secp256k1Keypair.import(hex),
  },
  ES256: {
    privHex: '0000000000000000000000000000000000000000000000000000000000000002',
    make: (hex) => P256Keypair.import(hex),
  },
}

const fixtures = {
  _meta: {
    description:
      'Service JWTs minted by the TypeScript reference implementation for cross-implementation verification.',
    repository: 'https://github.com/bluesky-social/atproto',
    commit: '3e977fe0ab88145f4ff26b491f371d3bade22505',
    packages: { '@atproto/xrpc-server': '0.11.1', '@atproto/crypto': '0.5.0' },
    script: 'generate_fixtures.mjs (this directory)',
  },
  iss: ISS,
  aud: AUD,
  lxm: LXM,
  cases: [],
}

for (const [alg, { privHex, make }] of Object.entries(keys)) {
  const keypair = await make(privHex)
  const did = keypair.did()
  const common = { iss: ISS, aud: AUD, keypair }

  const withLxm = await createServiceJwt({ ...common, lxm: LXM, iat: IAT, exp: EXP })
  const noLxm = await createServiceJwt({ ...common, lxm: null, iat: IAT, exp: EXP })
  const expired = await createServiceJwt({ ...common, lxm: LXM, iat: IAT, exp: PAST_EXP })

  // sanity: the reference accepts its own tokens
  await verifyJwt(withLxm, AUD, LXM, async () => did)
  await verifyJwt(noLxm, null, null, async () => did)

  fixtures.cases.push({
    alg,
    private_key_hex: privHex,
    did_key: did,
    tokens: {
      with_lxm: { token: withLxm, claims: { iat: IAT, iss: ISS, aud: AUD, exp: EXP, lxm: LXM } },
      no_lxm: { token: noLxm, claims: { iat: IAT, iss: ISS, aud: AUD, exp: EXP } },
      expired: { token: expired, claims: { iat: IAT, iss: ISS, aud: AUD, exp: PAST_EXP, lxm: LXM } },
    },
  })
}

console.log(JSON.stringify(fixtures, null, 2))
