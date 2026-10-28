// Keep the official Caddy CLI and standard module set. Dependency patch versions
// are locked in go.mod/go.sum without adding third-party Caddy modules.
package main

import (
	_ "time/tzdata"

	caddycmd "github.com/caddyserver/caddy/v2/cmd"
	_ "github.com/caddyserver/caddy/v2/modules/standard"
)

func main() {
	caddycmd.Main()
}
