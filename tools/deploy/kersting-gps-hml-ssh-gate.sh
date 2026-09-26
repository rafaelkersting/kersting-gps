#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly DEPLOY_USER="deploy-hml"
readonly INCOMING_DIR="/var/lib/deploy-hml/incoming"
readonly PUBLISHER="/usr/local/sbin/deploy-kersting-gps-hml"

fail() {
  printf 'ACESSO HML NEGADO: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -un)" == "$DEPLOY_USER" ]] || fail "usuário inesperado"
[[ -d "$INCOMING_DIR" && -w "$INCOMING_DIR" ]] || fail "entrada HML indisponível"

command="${SSH_ORIGINAL_COMMAND:-}"
case "$command" in
  status)
    printf 'environment=homologacao\n'
    printf 'user=%s\n' "$DEPLOY_USER"
    printf 'traccar_hml=%s\n' "$(systemctl is-active traccar-hml.service 2>/dev/null || true)"
    printf 'mariadb_hml=%s\n' "$(systemctl is-active mariadb-traccar-hml.service 2>/dev/null || true)"
    printf 'api_hml=%s\n' "$(systemctl is-active kersting-api-hml.service 2>/dev/null || true)"
    ;;
  deploy)
    exec sudo -n "$PUBLISHER"
    ;;
  cleanup)
    find "$INCOMING_DIR" -mindepth 1 -maxdepth 1 -type f -user "$DEPLOY_USER" \
      -name 'kersting-gps-hml-*.tar.gz' -delete
    ;;
  upload\ *)
    if [[ "$command" =~ ^upload[[:space:]]+(kersting-gps-hml-[0-9a-f]{40}-(frontend|completo)\.tar\.gz)$ ]]; then
      name="${BASH_REMATCH[1]}"
    else
      fail "nome de pacote inválido"
    fi
    destination="$INCOMING_DIR/$name"
    [[ ! -e "$destination" ]] || fail "pacote já existe"
    ulimit -f 1048576
    temporary="$INCOMING_DIR/.upload-${name}.$$"
    trap 'rm -f "$temporary"' EXIT
    timeout 600 cat > "$temporary"
    [[ -s "$temporary" ]] || fail "pacote vazio"
    chmod 0600 "$temporary"
    mv "$temporary" "$destination"
    trap - EXIT
    printf 'uploaded=%s\n' "$name"
    ;;
  *)
    fail "comando não autorizado"
    ;;
esac
