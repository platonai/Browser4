#!/bin/bash

script_dir=$(cd "$(dirname "$0")" > /dev/null || exit 1; pwd)

pass=0
fail=0

for i in $(seq 1 100); do
    echo "=== Run $i/100 ==="
    if "$script_dir/test.sh" cli; then
        pass=$((pass + 1))
    else
        fail=$((fail + 1))
        echo "FAILED on run $i"
    fi
done

echo ""
echo "=== Results: $pass passed, $fail failed ==="
