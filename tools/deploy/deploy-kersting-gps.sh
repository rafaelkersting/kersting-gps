#!/usr/bin/env bash

set -Eeuo pipefail
umask 027

readonly RUNTIME_DIR="/opt/traccar"
readonly BACKUP_DIR="/var/backups/kersting-gps"
readonly INCOMING_DIR="/var/tmp/kersting-gps-deploy"
readonly SERVICE_NAME="traccar"
readonly INTERNAL_URL="http://127.0.0.1:8082/"
readonly PUBLIC_URL="https://gps.kersting.net.br/"
readonly INFO_FILE="$RUNTIME_DIR/.kersting-deploy-info"
readonly LOG_FILE="/var/log/kersting-gps-deploy.log"
readonly LOCK_FILE="/run/lock/kersting-gps-deploy.lock"

deploy_type="${1:-}"
artifact_name="${2:-}"
commit="${3:-}"
expected_sha256="${4:-}"
artifact_path=""
work_dir=""
backup_file=""
runtime_modified=0

log() {
  local current_time
  current_time="$(date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null || printf 'horário-indisponível')"
  printf '[%s] %s\n' "$current_time" "$*"
}

fail() {
  log "ERRO: $*"
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Comando obrigatório não encontrado: $1"
}

wait_for_http_200() {
  local url="$1"
  local output_file="$2"
  local attempt http_code

  for attempt in {1..15}; do
    http_code="$(curl --silent --show-error --location --max-time 20 \
      --output "$output_file" --write-out '%{http_code}' "$url" || true)"
    if [[ "$http_code" == "200" ]]; then
      return 0
    fi
    log "Aguardando HTTP 200 de $url (tentativa $attempt, resultado ${http_code:-indisponível})"
    sleep 2
  done
  return 1
}

validate_runtime() {
  local phase="$1"
  local internal_html="$work_dir/internal-${phase}.html"
  local public_html="$work_dir/public-${phase}.html"
  local service_state restart_count

  systemctl is-active --quiet "$SERVICE_NAME" || {
    systemctl status "$SERVICE_NAME" --no-pager || true
    return 1
  }

  wait_for_http_200 "$INTERNAL_URL" "$internal_html" || {
    log "A API interna não retornou HTTP 200."
    return 1
  }
  wait_for_http_200 "$PUBLIC_URL" "$public_html" || {
    log "O domínio público não retornou HTTP 200."
    return 1
  }

  if grep -Eiq 'CyberPanel|OpenLiteSpeed[^<]*(default|welcome)' "$public_html"; then
    log "O domínio público respondeu com uma página padrão do servidor."
    return 1
  fi
  if ! grep -Eiq 'Kersting GPS|Traccar' "$public_html"; then
    log "O HTML público não foi reconhecido como Kersting GPS/Traccar."
    return 1
  fi

  service_state="$(systemctl show "$SERVICE_NAME" \
    -p ActiveState -p SubState -p MainPID -p NRestarts)"
  printf '%s\n' "$service_state"
  grep -qx 'ActiveState=active' <<< "$service_state" || return 1
  grep -qx 'SubState=running' <<< "$service_state" || return 1
  restart_count="$(awk -F= '$1 == "NRestarts" {print $2}' <<< "$service_state")"
  [[ "$restart_count" == "0" ]] || {
    log "NRestarts deveria ser 0, mas é ${restart_count:-desconhecido}."
    return 1
  }

  log "Validação $phase aprovada: serviço ativo, API interna e domínio público com HTTP 200."
}

restore_backup() {
  local rollback_failed=0
  local rollback_dir

  log "Iniciando rollback automático com $backup_file"
  systemctl stop "$SERVICE_NAME" || rollback_failed=1

  if [[ "$deploy_type" == "frontend" ]]; then
    rm -rf "$RUNTIME_DIR/web"
    rm -f "$INFO_FILE"
    tar -C "$RUNTIME_DIR" -xzf "$backup_file" || rollback_failed=1
  else
    rm -f "$RUNTIME_DIR/tracker-server.jar"
    rm -rf "$RUNTIME_DIR/lib" "$RUNTIME_DIR/web"
    rm -f "$INFO_FILE"
    rollback_dir="$work_dir/rollback-runtime"
    mkdir -p "$rollback_dir"
    tar -C "$rollback_dir" -xzf "$backup_file" || rollback_failed=1
    if [[ -d "$rollback_dir/traccar" ]]; then
      install -o root -g root -m 0644 "$rollback_dir/traccar/tracker-server.jar" \
        "$RUNTIME_DIR/tracker-server.jar" || rollback_failed=1
      cp -a "$rollback_dir/traccar/lib" "$RUNTIME_DIR/lib" || rollback_failed=1
      cp -a "$rollback_dir/traccar/web" "$RUNTIME_DIR/web" || rollback_failed=1
      if [[ -f "$rollback_dir/traccar/.kersting-deploy-info" ]]; then
        install -o root -g root -m 0644 "$rollback_dir/traccar/.kersting-deploy-info" \
          "$INFO_FILE" || rollback_failed=1
      fi
      chown -R root:root "$RUNTIME_DIR/lib" "$RUNTIME_DIR/web" || rollback_failed=1
    else
      rollback_failed=1
    fi
  fi
  systemctl start "$SERVICE_NAME" || rollback_failed=1

  if (( rollback_failed == 0 )) && validate_runtime "após rollback"; then
    log "Rollback concluído e validado com sucesso."
    return 0
  fi

  log "FALHA CRÍTICA: o rollback não pôde ser validado. Consulte $LOG_FILE e journalctl -u $SERVICE_NAME."
  return 1
}

handle_error() {
  local exit_code="$1"
  local line="$2"
  local command="$3"
  trap - ERR
  set +e
  log "Falha na linha $line durante: $command"

  if (( runtime_modified == 1 )) && [[ -n "$backup_file" && -f "$backup_file" ]]; then
    restore_backup || true
  else
    log "A falha ocorreu antes de qualquer modificação no runtime; rollback não foi necessário."
  fi
  exit "$exit_code"
}

cleanup() {
  [[ -z "$work_dir" || ! -d "$work_dir" ]] || rm -rf "$work_dir"
  [[ -z "$artifact_path" || ! -f "$artifact_path" ]] || rm -f "$artifact_path"
}

trap 'handle_error $? $LINENO "$BASH_COMMAND"' ERR
trap cleanup EXIT

[[ "$EUID" -eq 0 ]] || fail "Este script deve ser executado via sudo pelo usuário deploy."
[[ "$#" -eq 4 ]] || fail "Uso: deploy-kersting-gps <frontend|completo> <artefato> <commit> <sha256>"
[[ "$deploy_type" == "frontend" || "$deploy_type" == "completo" ]] || fail "Tipo de publicação inválido."
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "Commit inválido."
[[ "$expected_sha256" =~ ^[0-9a-f]{64}$ ]] || fail "SHA-256 inválido."
[[ "$artifact_name" == "kersting-gps-${commit}-${deploy_type}.tar.gz" ]] || fail "Nome de artefato inválido."
[[ "$artifact_name" != */* ]] || fail "O artefato deve ser informado somente pelo nome."

for required in awk curl date find flock grep install realpath sha256sum systemctl tar tee; do
  require_command "$required"
done

touch "$LOG_FILE"
chown root:deploy "$LOG_FILE"
chmod 0640 "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

install -d -o root -g deploy -m 0750 "$BACKUP_DIR"
[[ -d "$RUNTIME_DIR" ]] || fail "Runtime não encontrado em $RUNTIME_DIR."
[[ -d "$RUNTIME_DIR/web" ]] || fail "Diretório web atual não encontrado."
[[ -f "$RUNTIME_DIR/tracker-server.jar" ]] || fail "JAR atual não encontrado."
[[ -d "$RUNTIME_DIR/lib" ]] || fail "Diretório lib atual não encontrado."

exec 9> "$LOCK_FILE"
flock -n 9 || fail "Já existe outra publicação em andamento."

artifact_path="$INCOMING_DIR/$artifact_name"
[[ -f "$artifact_path" ]] || fail "Artefato não encontrado em $INCOMING_DIR."
[[ "$(dirname "$(realpath -e "$artifact_path")")" == "$INCOMING_DIR" ]] || fail "Artefato fora da pasta autorizada."
[[ "$(sha256sum "$artifact_path" | awk '{print $1}')" == "$expected_sha256" ]] || fail "SHA-256 do artefato não confere."

if tar -tzf "$artifact_path" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  fail "O pacote contém caminho inseguro."
fi

work_dir="$(mktemp -d "$INCOMING_DIR/extract.XXXXXXXX")"
tar --no-same-owner --no-same-permissions -C "$work_dir" -xzf "$artifact_path"
if [[ -n "$(find "$work_dir" ! -type f ! -type d -print -quit)" ]]; then
  fail "O pacote contém um tipo de arquivo não autorizado."
fi

[[ -s "$work_dir/deploy-info" ]] || fail "Metadados do pacote ausentes."
grep -qx "main_commit=$commit" "$work_dir/deploy-info" || fail "Commit do pacote não confere."
grep -qx "type=$deploy_type" "$work_dir/deploy-info" || fail "Tipo registrado no pacote não confere."
[[ -s "$work_dir/web/index.html" ]] || fail "Frontend inválido: web/index.html ausente."

if [[ "$deploy_type" == "completo" ]]; then
  [[ -s "$work_dir/tracker-server.jar" ]] || fail "Pacote completo sem tracker-server.jar."
  [[ -d "$work_dir/lib" && -n "$(find "$work_dir/lib" -mindepth 1 -maxdepth 1 -print -quit)" ]] || \
    fail "Pacote completo sem bibliotecas."
fi

timestamp="$(date +%Y-%m-%d-%H%M%S)"
short_commit="${commit:0:7}"
backup_items=(web)
[[ -f "$INFO_FILE" ]] && backup_items+=(.kersting-deploy-info)

if [[ "$deploy_type" == "frontend" ]]; then
  backup_file="$BACKUP_DIR/web-antes-${short_commit}-${timestamp}.tar.gz"
  log "Criando backup em $backup_file"
  tar -C "$RUNTIME_DIR" -czf "$backup_file" "${backup_items[@]}"
else
  backup_file="$BACKUP_DIR/traccar-antes-${short_commit}-${timestamp}.tar.gz"
  log "Criando backup completo do runtime em $backup_file"
  tar -C "$(dirname "$RUNTIME_DIR")" -czf "$backup_file" "$(basename "$RUNTIME_DIR")"
fi

chown root:deploy "$backup_file"
chmod 0640 "$backup_file"

runtime_modified=1
if [[ "$deploy_type" == "frontend" ]]; then
  log "Publicando frontend sem remover assets antigos com hash."
  cp -a "$work_dir/web/." "$RUNTIME_DIR/web/"
  chown -R root:root "$RUNTIME_DIR/web"
  install -o root -g root -m 0644 "$work_dir/deploy-info" "$INFO_FILE"
  systemctl restart "$SERVICE_NAME"
else
  log "Publicando backend, bibliotecas e frontend."
  systemctl stop "$SERVICE_NAME"
  install -o root -g root -m 0644 "$work_dir/tracker-server.jar" \
    "$RUNTIME_DIR/tracker-server.jar.new"
  mv -f "$RUNTIME_DIR/tracker-server.jar.new" "$RUNTIME_DIR/tracker-server.jar"
  rm -rf "$RUNTIME_DIR/lib"
  cp -a "$work_dir/lib" "$RUNTIME_DIR/lib"
  cp -a "$work_dir/web/." "$RUNTIME_DIR/web/"
  chown -R root:root "$RUNTIME_DIR/lib" "$RUNTIME_DIR/web"
  install -o root -g root -m 0644 "$work_dir/deploy-info" "$INFO_FILE"
  systemctl start "$SERVICE_NAME"
fi

validate_runtime "após deploy"
runtime_modified=0
log "Publicação $deploy_type concluída com sucesso. Commit instalado: $commit"
log "Backup preservado: $backup_file"
