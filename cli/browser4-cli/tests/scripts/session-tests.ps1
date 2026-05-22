#!/usr/bin/env pwsh

cargo run -- open https://www.amazon.com/
sleep 5
cargo run -- snapshot

cargo run -- list
cargo run -- goto https://www.amazon.com/s?k=pens
sleep 5
cargo run -- snapshot
cargo run -- close
cargo run -- list

sleep 5
cargo run -- goto https://www.amazon.com/s?k=shoes
cargo run -- snapshot
cargo run -- close
cargo run -- list

cargo run -- open https://www.amazon.com/
sleep 5
cargo run -- list
