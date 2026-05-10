import json
import sys
# Echo back argv (excluding script path) as JSON for stable assertions
print(json.dumps({"args": sys.argv[1:]}))
