#!/usr/bin/env bash

set -Eeuo pipefail
export LC_ALL=C

readonly DEPLOY_USER="deploy-hml"

fail() {
  printf 'ERRO: %s\n' "$*" >&2
  exit 1
}

account_expiration() {
  chage -l "$DEPLOY_USER" | awk -F': ' '/^Account expires/ {print $2}'
}

print_status() {
  local expiration sessions
  expiration="$(account_expiration)"
  sessions="$( (pgrep -u "$DEPLOY_USER" 2>/dev/null || true) | wc -l)"
  printf 'usuario=%s\n' "$DEPLOY_USER"
  printf 'expiracao=%s\n' "$expiration"
  printf 'senha=%s\n' "$(passwd -S "$DEPLOY_USER" | awk '{print $2}')"
  printf 'sessoes=%s\n' "$sessions"
  if [[ "$expiration" == "never" ]]; then
    printf 'estado=ATIVO\n'
  else
    printf 'estado=DESATIVADO\n'
  fi
}

[[ "$EUID" -eq 0 ]] || fail "execute como root"
getent passwd "$DEPLOY_USER" >/dev/null || fail "usuário não existe"
[[ "$#" == 1 ]] || fail "uso: kersting-gps-hml-access <ativar|desativar|status>"

case "$1" in
  ativar)
    usermod --lock "$DEPLOY_USER"
    chage --expiredate -1 "$DEPLOY_USER"
    [[ "$(account_expiration)" == "never" ]] || fail "não foi possível ativar"
    print_status
    ;;
  desativar)
    chage --expiredate 1 "$DEPLOY_USER"
    loginctl terminate-user "$DEPLOY_USER" 2>/dev/null || true
    [[ "$(account_expiration)" != "never" ]] || fail "não foi possível desativar"
    print_status
    ;;
  status)
    print_status
    ;;
  *)
    fail "operação inválida"
    ;;
esac
