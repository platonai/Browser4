#!/usr/bin/env pwsh

cargo run -- open
cargo run -- swarm create
cargo run -- swarm submit "https://example.com"
cargo run -- swarm status "https://example.com"
