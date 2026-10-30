# go-jose/v3 security backport

Caddy's standard PKI modules still depend on `go-jose/v3 v3.0.5`, which has no
upstream patched v3 release for CVE-2026-116587 / GHSA-mgcc-cww5-fp2q.

`go-jose-v3-CVE-2026-116587.patch` backports the input validation from upstream
commit [`a0111f82742fa92bceec9ede40845e73319e6230`](https://github.com/go-jose/go-jose/commit/a0111f82742fa92bceec9ede40845e73319e6230):

- Reject private/symmetric JWK material in remote JWS/JWE and EPK headers before
  invoking private-key validation.
- Limit parsed RSA moduli to 4096 bits and reject inconsistent/oversized primes
  before RSA validation can perform expensive arithmetic.

The v4 algorithm-allowlist API changes are not applicable to the v3 parser API.
The patch preserves v3's API and legitimate local private-key imports. The build
applies it to the checksum-verified v3.0.5 module in an isolated directory, runs
the complete upstream module tests plus the regression file here, and compiles
Caddy against that directory using a local module replacement. No advisory is
ignored and no dependency version is falsely raised. Version-only scanners may
still identify the original v3 version; inspect the build replacement and tests
alongside such findings. Remove this patch when Caddy's dependency chain has a
maintained upstream fix.

Upstream go-jose source and this derived patch retain its Apache-2.0 license:
<https://github.com/go-jose/go-jose/blob/v3.0.5/LICENSE>.
