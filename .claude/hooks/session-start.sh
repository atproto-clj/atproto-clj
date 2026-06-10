#!/bin/bash
set -euo pipefail

# Only needed in remote (Claude Code on the web) environments.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# Install the Clojure CLI if it isn't already present.
if ! command -v clojure >/dev/null 2>&1; then
  curl -sL https://download.clojure.org/install/linux-install.sh -o /tmp/clj-install.sh
  bash /tmp/clj-install.sh
  rm -f /tmp/clj-install.sh
fi

# Pre-fetch project dependencies (idempotent; uses ~/.m2 cache).
cd "$CLAUDE_PROJECT_DIR"
clojure -P -A:dev:test 2>&1 | grep -v 'Use of :main-opts with -A is deprecated' || true
