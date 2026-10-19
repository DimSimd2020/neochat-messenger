// Package migrations embeds the PostgreSQL schema migrations.
package migrations

import "embed"

//go:embed *.sql
var FS embed.FS
