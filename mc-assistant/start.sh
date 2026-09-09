#!/usr/bin/env bash
# ---------------------------------------------------------------
#  mc-assistant one-click start (Mac / Linux)
#  Needs Node.js installed once: https://nodejs.org (LTS version)
# ---------------------------------------------------------------
cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1; then
  echo
  echo "Node.js is not installed. Get the LTS version from https://nodejs.org"
  echo "then run this again."
  exit 1
fi

if [ ! -d node_modules ]; then
  echo "First run — installing dependencies, give it a minute..."
  npm install --no-audit --no-fund
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo
  echo "Created your .env config — set MC_HOST to your server address and"
  echo "MC_OWNER to your Minecraft name, then run this again."
  echo "  (edit with: nano .env)"
  exit 0
fi

exec node src/index.js
