#!/usr/bin/env bash
#
# Refuses to let a credential reach source control (CLAUDE.md §3 rule 5).
#
# This repository is public, so the rule is not a formality. The check is
# deliberately grep and nothing else: a scanner with a dependency, an API key
# and an update cadence is a thing to maintain, and this only has to catch the
# shapes that are unmistakable.
#
# What it does NOT do, stated plainly so nobody reads more into a green run:
# it recognises a handful of well-known credential formats. A password in a
# config file, a token in a format nobody has seen, or a secret in a shape it
# does not know all pass. It raises the floor; it is not a proof.
set -euo pipefail

cd "$(dirname "$0")/.."

# Unmistakable, and none of them appear in a legitimate source file.
PATTERNS='ghp_[A-Za-z0-9]{20,}'                    # GitHub personal access token
PATTERNS+='|github_pat_[A-Za-z0-9_]{20,}'          # GitHub fine-grained token
PATTERNS+='|gh[susro]_[A-Za-z0-9]{20,}'            # other GitHub token kinds
PATTERNS+='|AKIA[0-9A-Z]{16}'                      # AWS access key id
PATTERNS+='|-----BEGIN [A-Z ]*PRIVATE KEY-----'    # any private key
PATTERNS+='|xox[baprs]-[A-Za-z0-9-]{10,}'          # Slack token
PATTERNS+='|sk-[A-Za-z0-9]{32,}'                   # OpenAI-style key
PATTERNS+='|eyJ[A-Za-z0-9_-]{20,}\.eyJ[A-Za-z0-9_-]{20,}\.'  # signed JWT


found=0

# The working tree, excluding everything generated or vendored.
if git grep --untracked -InE "$PATTERNS" -- \
    ':!node_modules' ':!**/node_modules' ':!**/build' ':!**/.next' ':!**/dist' \
    ':!pnpm-lock.yaml' ':!**/go.sum' ':!scripts/check-secrets.sh'; then
    echo ""
    echo "A credential-shaped string is in the working tree (see above)."
    found=1
fi

# This system's own service credentials, which need a second pass: test
# fixtures have to look like keys to be useful, so they are marked EXAMPLE and
# skipped - the same convention AWS uses for AKIAIOSFODNN7EXAMPLE. A real key
# from `make observer-key` is random and will not carry the marker.
if git grep --untracked -InE 'opsatlas_sk_[A-Za-z0-9_-]{32,}' -- \
    ':!node_modules' ':!**/node_modules' ':!**/build' ':!**/.next' ':!**/dist' \
    ':!scripts/check-secrets.sh' | grep -v 'EXAMPLE'; then
    echo ""
    echo "An OpsAtlas service credential is in the working tree (see above)."
    echo "If it is a fixture, put EXAMPLE in it. If it is real, rotate it."
    found=1
fi

# Tracked files that should never be tracked at all.
if git ls-files | grep -E '(^|/)\.env$|(^|/)\.env\.[^.]*$|\.pem$|\.p12$|\.pfx$|\.key$|id_rsa' \
    | grep -v '\.env\.example$'; then
    echo ""
    echo "A file that should never be committed is tracked (see above)."
    found=1
fi

if [ "$found" -ne 0 ]; then
    echo ""
    echo "Remove it, rotate the credential, and remember that deleting it in a new"
    echo "commit does not remove it from the history of a public repository."
    exit 1
fi

echo "No credential-shaped strings, and no key or .env files are tracked."
