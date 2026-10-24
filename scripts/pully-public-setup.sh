#!/usr/bin/env bash
# Sets up the public Pully listener (Explore's public API and Connect GitHub) beside the existing server.
# Run it once on the server host; see scripts/README.md. Connect GitHub signs in like `gh auth login`
# (GitHub's device flow as the GitHub CLI), so there is no OAuth App to register and no secret to enter.
set -euo pipefail

origin="${PULLY_PUBLIC_ORIGIN:-https://prs.octaviantocan.com}"
listen="${PULLY_PUBLIC_LISTEN:-127.0.0.1:8794}"
config="${PULLY_PUBLIC_CONFIG_DIR:-$HOME/.config/pully-public}"
data="${PULLY_PUBLIC_DATA_DIR:-$HOME/.local/share/pully-public}"
bin="${PULLY_SERVER_BIN:-$HOME/apps/pulls-review/backend/target/debug/pulls-review-server}"
unit="$HOME/.config/systemd/user/pully-public.service"

[[ "$origin" =~ ^https://[A-Za-z0-9.-]+$ ]] || { echo "PULLY_PUBLIC_ORIGIN must be https://<host> with no path or port" >&2; exit 1; }
[[ -x "$bin" ]] || { echo "No server binary at $bin (set PULLY_SERVER_BIN)" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is needed to make the vault key" >&2; exit 1; }

umask 077
mkdir -p "$config" "$data"
chmod 700 "$config" "$data"

if [[ ! -f "$config/oauth.json" ]]; then
  echo '{"githubCli":true}' > "$config/oauth.json"
  echo "Wrote $config/oauth.json (GitHub CLI device sign-in)"
fi

if [[ ! -f "$config/vault-keys.json" ]]; then
  key="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
  printf '{"activeVersion":1,"keys":{"1":"%s"}}\n' "$key" > "$config/vault-keys.json"
  unset key
  echo "Wrote $config/vault-keys.json (keep a private copy: losing it signs everyone out of GitHub)"
fi
chmod 600 "$config/oauth.json" "$config/vault-keys.json"

mkdir -p "$(dirname "$unit")"
cat > "$unit" <<UNIT
[Unit]
Description=Pully public API (Explore and Connect GitHub)
After=network-online.target

[Service]
ExecStart=$bin --public-listen $listen --public-data-dir $data --personal-oauth-config $config/oauth.json --personal-vault-keys $config/vault-keys.json
Restart=on-failure

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now pully-public.service
sleep 2
curl -fsS "http://$listen/api/personal/v1/capabilities" && echo

cat <<NEXT

The public listener is running on $listen. Two Cloudflare steps remain:
1. In the prs tunnel's config.yml, above the prs.octaviantocan.com rule, add:
     - hostname: ${origin#https://}
       path: ^/api/(public|personal)/
       service: http://$listen
   then restart cloudflared.
2. Give ${origin#https://}/api/public/* and ${origin#https://}/api/personal/* an Access bypass,
   as /api/v1/* already has. The rest of the server stays behind Access.
NEXT
