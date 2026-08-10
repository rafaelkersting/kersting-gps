#!/usr/bin/env bash

set -Eeuo pipefail
umask 027

readonly DEPLOY_USER="deploy"
readonly INCOMING_DIR="/var/tmp/kersting-gps-deploy"
readonly BACKUP_DIR="/var/backups/kersting-gps"
readonly CONTROLLED_SCRIPT="/usr/local/sbin/deploy-kersting-gps"
readonly SUDOERS_FILE="/etc/sudoers.d/kersting-gps-deploy"
readonly LOG_FILE="/var/log/kersting-gps-deploy.log"

public_key_file="${1:-}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  printf 'ERRO: %s\n' "$*" >&2
  exit 1
}

[[ "$EUID" -eq 0 ]] || fail "Execute este instalador como root."
[[ "$#" -le 1 ]] || fail "Uso: install-deploy-user.sh [arquivo-chave-publica]"
command -v visudo >/dev/null 2>&1 || fail "visudo não está disponível. Instale o pacote sudo."
[[ -f "$script_dir/deploy-kersting-gps.sh" ]] || fail "deploy-kersting-gps.sh não foi encontrado ao lado do instalador."

if ! getent passwd "$DEPLOY_USER" >/dev/null; then
  useradd --create-home --shell /bin/bash "$DEPLOY_USER"
fi
passwd --lock "$DEPLOY_USER" >/dev/null 2>&1 || true

deploy_home="$(getent passwd "$DEPLOY_USER" | awk -F: '{print $6}')"
[[ -n "$deploy_home" && "$deploy_home" == /* ]] || fail "Diretório home do usuário deploy inválido."

install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 0700 "$deploy_home/.ssh"
touch "$deploy_home/.ssh/authorized_keys"
chown "$DEPLOY_USER:$DEPLOY_USER" "$deploy_home/.ssh/authorized_keys"
chmod 0600 "$deploy_home/.ssh/authorized_keys"

if [[ -n "$public_key_file" ]]; then
  [[ -f "$public_key_file" ]] || fail "Arquivo de chave pública não encontrado: $public_key_file"
  key_line="$(tr -d '\r\n' < "$public_key_file")"
  [[ "$key_line" =~ ^ssh-(ed25519|rsa)[[:space:]]+[A-Za-z0-9+/=]+([[:space:]].*)?$ ]] || \
    fail "Formato de chave pública não reconhecido."
  grep -qxF "$key_line" "$deploy_home/.ssh/authorized_keys" || \
    printf '%s\n' "$key_line" >> "$deploy_home/.ssh/authorized_keys"
fi

install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 0750 "$INCOMING_DIR"
install -d -o root -g "$DEPLOY_USER" -m 0750 "$BACKUP_DIR"
install -o root -g root -m 0755 "$script_dir/deploy-kersting-gps.sh" "$CONTROLLED_SCRIPT"

touch "$LOG_FILE"
chown root:"$DEPLOY_USER" "$LOG_FILE"
chmod 0640 "$LOG_FILE"

sudoers_tmp="$(mktemp)"
trap 'rm -f "$sudoers_tmp"' EXIT
cat > "$sudoers_tmp" <<'SUDOERS'
Defaults:deploy !requiretty
deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-kersting-gps frontend *, /usr/local/sbin/deploy-kersting-gps completo *
SUDOERS
chmod 0440 "$sudoers_tmp"
visudo -cf "$sudoers_tmp"
install -o root -g root -m 0440 "$sudoers_tmp" "$SUDOERS_FILE"
visudo -cf "$SUDOERS_FILE"

printf 'Usuário %s preparado com sucesso.\n' "$DEPLOY_USER"
printf 'Script controlado: %s\n' "$CONTROLLED_SCRIPT"
printf 'Diretório de entrada: %s\n' "$INCOMING_DIR"
printf 'Diretório de backups: %s\n' "$BACKUP_DIR"
printf 'Sudoers validado: %s\n' "$SUDOERS_FILE"
if [[ -z "$public_key_file" ]]; then
  printf 'PENDÊNCIA: instale a chave pública dedicada em %s/.ssh/authorized_keys.\n' "$deploy_home"
fi
