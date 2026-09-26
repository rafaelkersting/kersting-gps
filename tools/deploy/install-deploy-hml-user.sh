#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly DEPLOY_USER="deploy-hml"
readonly DEPLOY_HOME="/var/lib/deploy-hml"
readonly INCOMING_DIR="$DEPLOY_HOME/incoming"
readonly BACKUP_DIR="/var/backups/traccar-hml"
readonly PUBLISHER="/usr/local/sbin/deploy-kersting-gps-hml"
readonly SSH_GATE="/usr/local/sbin/kersting-gps-hml-ssh-gate"
readonly ACCESS_CONTROL="/usr/local/sbin/kersting-gps-hml-access"
readonly SUDOERS_FILE="/etc/sudoers.d/kersting-gps-hml-deploy"
readonly LOG_FILE="/var/log/kersting-gps-hml-deploy.log"

public_key_file="${1:-}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  printf 'ERRO: %s\n' "$*" >&2
  exit 1
}

[[ "$EUID" -eq 0 ]] || fail "execute como root"
[[ "$#" == 1 ]] || fail "uso: install-deploy-hml-user.sh <chave-publica-ed25519>"
[[ -f "$public_key_file" ]] || fail "chave pública não encontrada"
command -v visudo >/dev/null 2>&1 || fail "visudo não encontrado"

for file in deploy-kersting-gps-hml.sh kersting-gps-hml-ssh-gate.sh kersting-gps-hml-access.sh; do
  [[ -f "$script_dir/$file" ]] || fail "$file ausente ao lado do instalador"
  bash -n "$script_dir/$file"
done
python3 "$script_dir/test_deploy_hml_safety.py" "$script_dir/../.."

[[ -d /opt/traccar-hml && -L /opt/traccar-hml/current ]] || fail "runtime HML não encontrado"
[[ -f /etc/traccar-hml/traccar.xml ]] || fail "configuração HML não encontrada"
[[ -S /run/mariadb-traccar-hml/mariadb.sock ]] || fail "socket MariaDB HML não encontrado"
[[ -f /etc/kersting-api-hml/app.env ]] || fail "configuração API HML não encontrada"

[[ "$(grep -cve '^[[:space:]]*$' "$public_key_file")" == "1" ]] || \
  fail "o arquivo deve conter exatamente uma chave pública"
key_line="$(tr -d '\r\n' < "$public_key_file")"
[[ "$key_line" =~ ^ssh-ed25519[[:space:]]+[A-Za-z0-9+/=]+([[:space:]].*)?$ ]] || \
  fail "a chave deve ser ED25519 e ocupar uma única linha"

if getent passwd "$DEPLOY_USER" >/dev/null; then
  IFS=: read -r _ _ _ _ _ existing_home existing_shell < <(getent passwd "$DEPLOY_USER")
  [[ "$existing_home" == "$DEPLOY_HOME" && "$existing_shell" == "/bin/bash" ]] || \
    fail "usuário existente possui home ou shell inesperado"
else
  useradd --home-dir "$DEPLOY_HOME" --create-home --shell /bin/bash "$DEPLOY_USER"
fi
usermod --lock "$DEPLOY_USER"
chage --expiredate 1 "$DEPLOY_USER"

install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 0700 "$DEPLOY_HOME" "$DEPLOY_HOME/.ssh"
install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 0700 "$INCOMING_DIR"
authorized_tmp="$(mktemp)"
sudoers_tmp="$(mktemp)"
trap 'rm -f "$authorized_tmp" "$sudoers_tmp"' EXIT
printf 'restrict,command="%s" %s\n' "$SSH_GATE" "$key_line" > "$authorized_tmp"
install -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 0600 "$authorized_tmp" "$DEPLOY_HOME/.ssh/authorized_keys"

install -o root -g root -m 0755 "$script_dir/deploy-kersting-gps-hml.sh" "$PUBLISHER"
install -o root -g root -m 0755 "$script_dir/kersting-gps-hml-ssh-gate.sh" "$SSH_GATE"
install -o root -g root -m 0755 "$script_dir/kersting-gps-hml-access.sh" "$ACCESS_CONTROL"
install -d -o root -g "$DEPLOY_USER" -m 0750 "$BACKUP_DIR"
touch "$LOG_FILE"
chown root:"$DEPLOY_USER" "$LOG_FILE"
chmod 0640 "$LOG_FILE"

cat > "$sudoers_tmp" <<'SUDOERS'
Defaults:deploy-hml !requiretty
deploy-hml ALL=(root) NOPASSWD: /usr/local/sbin/deploy-kersting-gps-hml
SUDOERS
chmod 0440 "$sudoers_tmp"
visudo -cf "$sudoers_tmp"
install -o root -g root -m 0440 "$sudoers_tmp" "$SUDOERS_FILE"
visudo -cf "$SUDOERS_FILE"

[[ "$(stat -c '%U:%G:%a' "$PUBLISHER")" == "root:root:755" ]] || fail "permissão do publicador inválida"
[[ "$(stat -c '%U:%G:%a' "$SSH_GATE")" == "root:root:755" ]] || fail "permissão do gate inválida"
[[ "$(stat -c '%U:%G:%a' "$DEPLOY_HOME/.ssh/authorized_keys")" == "$DEPLOY_USER:$DEPLOY_USER:600" ]] || \
  fail "permissão de authorized_keys inválida"

printf '\nINSTALAÇÃO HML CONCLUÍDA; USUÁRIO DESATIVADO POR PADRÃO\n'
"$ACCESS_CONTROL" status
printf '\nAtivar:    %s ativar\n' "$ACCESS_CONTROL"
printf 'Desativar: %s desativar\n' "$ACCESS_CONTROL"
printf 'Status:    %s status\n' "$ACCESS_CONTROL"
