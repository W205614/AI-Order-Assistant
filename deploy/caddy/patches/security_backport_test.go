package jose

import (
	"encoding/base64"
	"math/big"
	"strings"
	"testing"

	"github.com/go-jose/go-jose/v3/json"
)

func TestSecurityBackportRSABounds(t *testing.T) {
	key, err := fromRsaPrivateKey(rsaTestKey)
	if err != nil { t.Fatal(err) }
	if _, err = key.rsaPrivateKey(); err != nil { t.Fatal(err) }
	key.P = newBuffer(new(big.Int).Lsh(big.NewInt(1), 1<<20).Bytes())
	if _, err = key.rsaPrivateKey(); err == nil || !strings.Contains(err.Error(), "p * q != n") {
		t.Fatalf("unbounded prime accepted: %v", err)
	}
	key.N = newBuffer(new(big.Int).Lsh(big.NewInt(1), 4096).Bytes())
	if _, err = key.rsaPublicKey(); err == nil || !strings.Contains(err.Error(), "modulus too large") {
		t.Fatalf("oversized modulus accepted: %v", err)
	}
}

func TestSecurityBackportEmbeddedPrivateKeysRejected(t *testing.T) {
	raw, err := json.Marshal(JSONWebKey{Key: rsaTestKey})
	if err != nil { t.Fatal(err) }
	var direct JSONWebKey
	if err = json.Unmarshal(raw, &direct); err != nil { t.Fatalf("local private key import: %v", err) }
	header := json.RawMessage(raw)
	if _, err = (rawHeader{headerEPK: &header}).getEPK(); err == nil || !strings.Contains(err.Error(), "private parameters") {
		t.Fatalf("private EPK accepted: %v", err)
	}
	if _, err = (rawHeader{headerJWK: &header}).sanitized(); err == nil || !strings.Contains(err.Error(), "private parameters") {
		t.Fatalf("private JWK accepted: %v", err)
	}
	protected := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","jwk":` + string(raw) + `}`))
	if _, err = ParseSigned(protected + ".dGVzdA.dGVzdA"); err == nil || !strings.Contains(err.Error(), "private parameters") {
		t.Fatalf("private JWS header accepted: %v", err)
	}
}
