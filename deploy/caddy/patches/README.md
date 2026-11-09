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
Caddy against that directory using a local module replacement. The dependency version remains v3.0.5 with the local replacement applied. Review
version-based findings together with the build replacement and test results. Remove this patch when Caddy's dependency chain has a
maintained upstream fix.

Upstream go-jose source and this derived patch retain its Apache-2.0 license:
<https://github.com/go-jose/go-jose/blob/v3.0.5/LICENSE>.
Copyright 2014 Square Inc. The complete license is retained in
[licenses/Apache-2.0.txt](../../../licenses/Apache-2.0.txt).

The modified `jwk.go` and `shared.go` carry explicit AI-Order-Assistant change
notices in their original copyright headers, as required by Apache-2.0
section 4(b). These notices do not change the security backport behavior.
Historical CI binaries were built using the earlier patch without these
additional comments. Their original patch and exact patched-source reconstruction
are retained with the historical distribution materials; the current patch is
not presented as the byte-for-byte historical build input.
