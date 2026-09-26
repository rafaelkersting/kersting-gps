#!/usr/bin/env bash

set -Eeuo pipefail
umask 027

readonly DEPLOY_USER="deploy-hml"
readonly RUNTIME_ROOT="/opt/traccar-hml"
readonly RELEASES_DIR="$RUNTIME_ROOT/releases"
readonly CURRENT_LINK="$RUNTIME_ROOT/current"
readonly BACKUP_DIR="/var/backups/traccar-hml"
readonly INCOMING_DIR="/var/lib/deploy-hml/incoming"
readonly SERVICE_NAME="traccar-hml.service"
readonly DATABASE_SERVICE="mariadb-traccar-hml.service"
readonly API_SERVICE="kersting-api-hml.service"
readonly DATABASE_NAME="gps_kersting_hml"
readonly DATABASE_PORT="3308"
readonly DATABASE_SOCKET="/run/mariadb-traccar-hml/mariadb.sock"
readonly INTERNAL_URL="http://127.0.0.1:18082/"
readonly PUBLIC_URL="https://gps-hml.kersting.net.br/"
readonly GT06_PORT="5601"
readonly CONFIG_FILE="/etc/traccar-hml/traccar.xml"
readonly API_ENV_FILE="/etc/kersting-api-hml/app.env"
readonly LOG_FILE="/var/log/kersting-gps-hml-deploy.log"
readonly LOCK_FILE="/run/lock/kersting-gps-hml-deploy.lock"

artifact_path=""
work_dir=""
new_release=""
previous_release=""
runtime_backup=""
database_backup=""
deploy_type=""
main_commit=""
frontend_commit=""
switched=0
rollback_running=0
production_pid_before=""
production_restarts_before=""

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" "$*"
}

fail() {
  log "ERRO: $*"
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Comando obrigatório não encontrado: $1"
}

assert_exact_hml_boundaries() {
  [[ "$RUNTIME_ROOT" == "/opt/traccar-hml" ]] || fail "Runtime HML inesperado."
  [[ "$SERVICE_NAME" == "traccar-hml.service" ]] || fail "Serviço HML inesperado."
  [[ "$DATABASE_SERVICE" == "mariadb-traccar-hml.service" ]] || fail "Serviço MariaDB HML inesperado."
  [[ "$DATABASE_NAME" == "gps_kersting_hml" ]] || fail "Schema HML inesperado."
  [[ "$DATABASE_PORT" == "3308" ]] || fail "Porta MariaDB HML inesperada."
  [[ "$DATABASE_SOCKET" == "/run/mariadb-traccar-hml/mariadb.sock" ]] || fail "Socket HML inesperado."
  [[ "$INTERNAL_URL" == "http://127.0.0.1:18082/" ]] || fail "URL interna HML inesperada."
  [[ "$PUBLIC_URL" == "https://gps-hml.kersting.net.br/" ]] || fail "URL pública HML inesperada."
  [[ "$GT06_PORT" == "5601" ]] || fail "Porta GT06 HML inesperada."

  [[ "$RUNTIME_ROOT" != "/opt/traccar" ]] || fail "Runtime de produção proibido."
  [[ "$SERVICE_NAME" != "traccar.service" ]] || fail "Serviço de produção proibido."
  [[ "$DATABASE_NAME" != "gps_kersting" ]] || fail "Banco de produção proibido."
  [[ "$INTERNAL_URL" != "http://127.0.0.1:8082/" ]] || fail "Backend de produção proibido."
  [[ "$PUBLIC_URL" != "https://gps.kersting.net.br/" ]] || fail "URL de produção proibida."
}

validate_config_is_hml() {
  [[ -r "$CONFIG_FILE" ]] || fail "Configuração HML não encontrada."
  python3 - "$CONFIG_FILE" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

props = {}
for entry in ET.parse(sys.argv[1]).getroot().findall('.//entry'):
    key = entry.get('key')
    if key:
        props[key] = (entry.text or '').strip()

for key, value in {'web.port': '18082', 'gt06.port': '5601'}.items():
    if props.get(key) != value:
        raise SystemExit(f'{key} não corresponde à HML')

url = props.get('database.url', '')
if not re.search(r'^jdbc:mariadb://127\.0\.0\.1:3308/gps_kersting_hml(?:[?;]|$)', url):
    raise SystemExit('database.url não corresponde à instância HML')
if re.search(r'(?<![A-Za-z0-9_])gps_kersting(?!_hml[A-Za-z0-9_]*)', url):
    raise SystemExit('referência ao banco de produção detectada')
PY
}

validate_api_is_hml() {
  [[ -r "$API_ENV_FILE" ]] || fail "Configuração da API HML não encontrada."
  if grep -Eq '(^|[^A-Za-z0-9_])gps_kersting([^A-Za-z0-9_]|$)|(^|[^0-9])8082([^0-9]|$)|https?://gps\.kersting\.net\.br([/:]|$)|(^|[=:[:space:]])/opt/traccar([/[:space:]]|$)' "$API_ENV_FILE"; then
    fail "A configuração da API HML contém uma referência operacional de produção."
  fi
}

validate_database_is_hml() {
  [[ -S "$DATABASE_SOCKET" ]] || fail "Socket do MariaDB HML não encontrado."
  systemctl is-active --quiet "$DATABASE_SERVICE" || fail "MariaDB HML não está ativo."

  local result
  result="$(mariadb --batch --skip-column-names --protocol=SOCKET --socket="$DATABASE_SOCKET" \
    -uroot --database="$DATABASE_NAME" -e "SELECT CONCAT(@@port, ':', DATABASE());" 2>/dev/null)"
  [[ "$result" == "$DATABASE_PORT:$DATABASE_NAME" ]] || fail "A conexão não confirmou porta e schema HML."

  result="$(mariadb --batch --skip-column-names --protocol=SOCKET --socket="$DATABASE_SOCKET" -uroot \
    -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = 'gps_kersting';")"
  [[ "$result" == "0" ]] || fail "O schema de produção está visível na instância HML."
}

validate_gt06_isolation() {
  local listener hml_pid
  listener="$(ss -H -lntp "sport = :$GT06_PORT" 2>/dev/null || true)"
  hml_pid="$(systemctl show "$SERVICE_NAME" -p MainPID --value)"
  [[ -n "$listener" ]] || fail "A porta GT06 HML $GT06_PORT não possui listener."
  grep -q "pid=$hml_pid," <<< "$listener" || fail "A porta $GT06_PORT não pertence ao serviço Traccar HML."
  if ss -H -lntup 2>/dev/null | awk '{print $5}' | grep -Eq '(^|:)15023$'; then
    fail "A porta candidata 15023 possui listener inesperado."
  fi
  if firewall-cmd --list-all-zones 2>/dev/null | grep -Eq "(^|[^0-9])${GT06_PORT}/(tcp|udp)([^0-9]|$)"; then
    fail "A porta GT06 HML $GT06_PORT está exposta no firewall."
  fi
  if firewall-cmd --list-all-zones 2>/dev/null | grep -Eq '(^|[^0-9])15023/(tcp|udp)([^0-9]|$)'; then
    fail "A porta candidata 15023 está exposta no firewall."
  fi
}

remember_production_state() {
  systemctl is-active --quiet traccar.service || fail "Produção não está ativa antes do deploy HML."
  production_pid_before="$(systemctl show traccar.service -p MainPID --value)"
  production_restarts_before="$(systemctl show traccar.service -p NRestarts --value)"
  [[ "$production_pid_before" =~ ^[1-9][0-9]*$ ]] || fail "PID de produção inválido."
}

assert_production_untouched() {
  local pid restarts
  systemctl is-active --quiet traccar.service || fail "Produção deixou de estar ativa durante o deploy HML."
  pid="$(systemctl show traccar.service -p MainPID --value)"
  restarts="$(systemctl show traccar.service -p NRestarts --value)"
  [[ "$pid" == "$production_pid_before" ]] || fail "PID de produção mudou durante o deploy HML."
  [[ "$restarts" == "$production_restarts_before" ]] || fail "NRestarts de produção mudou durante o deploy HML."
}

wait_for_http_200() {
  local url="$1" output_file="$2" attempt code
  for attempt in {1..20}; do
    code="$(curl --silent --show-error --location --max-time 15 \
      --output "$output_file" --write-out '%{http_code}' "$url" || true)"
    [[ "$code" == "200" ]] && return 0
    log "Aguardando HTTP 200 de $url (tentativa $attempt, resposta ${code:-indisponível})."
    sleep 2
  done
  return 1
}

validate_hml_runtime() {
  local phase="$1"
  local internal_html="$work_dir/hml-internal-${phase}.html"
  local public_html="$work_dir/hml-public-${phase}.html"
  systemctl is-active --quiet "$SERVICE_NAME" || return 1
  wait_for_http_200 "$INTERNAL_URL" "$internal_html" || return 1
  wait_for_http_200 "$PUBLIC_URL" "$public_html" || return 1
  grep -Eiq 'Kersting GPS|Traccar' "$public_html" || return 1
  ! grep -Eiq 'CyberPanel|OpenLiteSpeed[^<]*(default|welcome)' "$public_html"
}

manifest_value() {
  local key="$1" value
  value="$(sed -n "s/^${key}=//p" "$work_dir/deploy-info")"
  [[ -n "$value" && "$(grep -c "^${key}=" "$work_dir/deploy-info")" == "1" ]] || \
    fail "Campo de manifesto inválido: $key"
  printf '%s' "$value"
}

create_backups() {
  local timestamp="$1" dump_command
  runtime_backup="$BACKUP_DIR/runtime-antes-${main_commit:0:12}-${timestamp}.tar.gz"
  database_backup="$BACKUP_DIR/database-antes-${main_commit:0:12}-${timestamp}.sql.gz"

  log "Criando backup do release HML atual."
  tar -C "$RELEASES_DIR" -czf "$runtime_backup" "$(basename "$previous_release")"
  gzip -t "$runtime_backup"

  if command -v mariadb-dump >/dev/null 2>&1; then
    dump_command="mariadb-dump"
  else
    dump_command="mysqldump"
  fi
  log "Criando backup exclusivo do banco HML."
  "$dump_command" --protocol=SOCKET --socket="$DATABASE_SOCKET" -uroot \
    --single-transaction --routines --events --triggers --hex-blob \
    --default-character-set=utf8mb4 --add-drop-database --databases "$DATABASE_NAME" | \
    gzip -9 > "$database_backup"
  gzip -t "$database_backup"
  [[ -s "$runtime_backup" && -s "$database_backup" ]] || fail "Backup HML vazio."
  chown root:"$DEPLOY_USER" "$runtime_backup" "$database_backup"
  chmod 0640 "$runtime_backup" "$database_backup"
}

restore_hml() {
  local failed_release
  (( rollback_running == 0 )) || return 1
  rollback_running=1
  set +e
  log "Iniciando rollback exclusivo da HML."
  systemctl stop "$SERVICE_NAME"
  ln -s "$previous_release" "$RUNTIME_ROOT/.current.rollback"
  mv -Tf "$RUNTIME_ROOT/.current.rollback" "$CURRENT_LINK"
  if [[ "$deploy_type" == "completo" && -s "$database_backup" ]]; then
    gunzip -c "$database_backup" | mariadb --protocol=SOCKET --socket="$DATABASE_SOCKET" -uroot
  fi
  systemctl start "$SERVICE_NAME"
  if [[ -d "$new_release" ]]; then
    failed_release="${new_release}.failed-$(date -u +%Y%m%dT%H%M%SZ)"
    mv "$new_release" "$failed_release"
    log "Release com falha preservado em $failed_release"
  fi
  validate_hml_runtime rollback && log "Rollback HML validado." || \
    log "FALHA CRÍTICA: rollback HML não passou no smoke."
  assert_production_untouched || log "FALHA CRÍTICA: estado de produção divergiu durante rollback."
  set -e
  rollback_running=0
}

cleanup() {
  [[ -z "$work_dir" || ! -d "$work_dir" ]] || rm -rf "$work_dir"
  [[ -z "$artifact_path" || ! -f "$artifact_path" ]] || rm -f "$artifact_path"
}

handle_error() {
  local code="$1" line="$2" command="$3"
  trap - ERR
  log "Falha na linha $line durante: $command"
  if (( switched == 1 )); then
    restore_hml || true
  elif [[ -n "$new_release" && -d "$new_release" ]]; then
    mv "$new_release" "${new_release}.failed-$(date -u +%Y%m%dT%H%M%SZ)" || true
  fi
  exit "$code"
}

trap 'handle_error $? $LINENO "$BASH_COMMAND"' ERR
trap cleanup EXIT

[[ "$EUID" -eq 0 ]] || fail "Execute via sudo controlado do usuário deploy-hml."
[[ "$#" -eq 0 ]] || fail "Este publicador não aceita argumentos."
for command in awk basename chmod chown cp curl date dirname find firewall-cmd flock grep gunzip gzip \
  install ln mariadb mkdir mktemp mv python3 realpath rm sed sha256sum sleep sort ss systemctl tar tee wc; do
  require_command "$command"
done

assert_exact_hml_boundaries
validate_config_is_hml
validate_api_is_hml
validate_database_is_hml
validate_gt06_isolation
remember_production_state

[[ -d "$RELEASES_DIR" ]] || fail "Diretório de releases HML ausente."
[[ -L "$CURRENT_LINK" ]] || fail "current HML não é um link simbólico."
previous_release="$(realpath -e "$CURRENT_LINK")"
[[ "$(dirname "$previous_release")" == "$RELEASES_DIR" ]] || fail "current aponta para fora de releases HML."
[[ -s "$previous_release/tracker-server.jar" && -s "$previous_release/web/index.html" ]] || \
  fail "Release HML atual incompleto."
systemctl is-active --quiet "$SERVICE_NAME" || fail "Traccar HML não está ativo."
systemctl is-active --quiet "$API_SERVICE" || fail "API HML não está ativa."

exec 9> "$LOCK_FILE"
flock -n 9 || fail "Já existe uma publicação HML em andamento."
install -d -o root -g "$DEPLOY_USER" -m 0750 "$BACKUP_DIR"
touch "$LOG_FILE"
chown root:"$DEPLOY_USER" "$LOG_FILE"
chmod 0640 "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

mapfile -t artifacts < <(find "$INCOMING_DIR" -maxdepth 1 -type f \
  -name 'kersting-gps-hml-*.tar.gz' -user "$DEPLOY_USER" -print)
[[ "${#artifacts[@]}" == "1" ]] || fail "Deve existir exatamente um pacote HML na entrada."
artifact_path="${artifacts[0]}"
[[ "$(dirname "$(realpath -e "$artifact_path")")" == "$INCOMING_DIR" ]] || fail "Pacote fora da entrada HML."

archive_name="$(basename "$artifact_path")"
[[ "$archive_name" =~ ^kersting-gps-hml-([0-9a-f]{40})-(frontend|completo)\.tar\.gz$ ]] || \
  fail "Nome do pacote HML inválido."
archive_commit="${BASH_REMATCH[1]}"
archive_type="${BASH_REMATCH[2]}"
log "SHA-256 recebido: $(sha256sum "$artifact_path" | awk '{print $1}')"

if tar -tzf "$artifact_path" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  fail "Pacote contém caminho inseguro."
fi
work_dir="$(mktemp -d "$INCOMING_DIR/extract.XXXXXXXX")"
tar --no-same-owner --no-same-permissions -C "$work_dir" -xzf "$artifact_path"
[[ -z "$(find "$work_dir" ! -type f ! -type d -print -quit)" ]] || fail "Pacote contém tipo não autorizado."
[[ -s "$work_dir/deploy-info" && -s "$work_dir/web/index.html" ]] || fail "Pacote HML incompleto."
[[ "$(wc -l < "$work_dir/deploy-info")" == "10" ]] || fail "Manifesto deve conter exatamente 10 campos."
grep -Ev '^(main_commit|frontend_commit|type|ref|built_at|workflow|run_id|run_attempt|run_url|release)=' \
  "$work_dir/deploy-info" | grep -q . && fail "Manifesto contém campo não autorizado."

main_commit="$(manifest_value main_commit)"
frontend_commit="$(manifest_value frontend_commit)"
deploy_type="$(manifest_value type)"
deploy_ref="$(manifest_value ref)"
built_at="$(manifest_value built_at)"
workflow="$(manifest_value workflow)"
run_id="$(manifest_value run_id)"
run_attempt="$(manifest_value run_attempt)"
run_url="$(manifest_value run_url)"
release="$(manifest_value release)"

[[ "$main_commit" =~ ^[0-9a-f]{40}$ && "$frontend_commit" =~ ^[0-9a-f]{40}$ ]] || fail "SHAs inválidos."
[[ "$deploy_type" == "frontend" || "$deploy_type" == "completo" ]] || fail "Tipo inválido."
[[ "$deploy_ref" == "release/v0.1.0-homologacao" || \
  "$deploy_ref" == "codex/melhorar-associacao-geocercas" || \
  "$deploy_ref" == "codex/geofence-prod-clean" ]] || \
  fail "Ref não autorizada para HML."
[[ "$workflow" == "Publicar Homologação HML" ]] || fail "Workflow inesperado."
[[ "$run_id" =~ ^[0-9]+$ && "$run_attempt" =~ ^[0-9]+$ ]] || fail "Identificação do workflow inválida."
[[ "$built_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || fail "Data de build inválida."
[[ "$run_url" == "https://github.com/rafaelkersting/kersting-gps/actions/runs/$run_id" ]] || fail "URL do workflow inválida."
[[ "$release" == "$main_commit" && "$archive_commit" == "$main_commit" && "$archive_type" == "$deploy_type" ]] || \
  fail "Manifesto não corresponde ao pacote."

mapfile -t top_level < <(find "$work_dir" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort)
if [[ "$deploy_type" == "frontend" ]]; then
  [[ "${top_level[*]}" == "deploy-info web" ]] || fail "Pacote frontend contém componentes não autorizados."
else
  [[ "${top_level[*]}" == "deploy-info lib schema tracker-server.jar web" ]] || \
    fail "Pacote completo contém componentes não autorizados."
fi

if [[ "$deploy_type" == "completo" ]]; then
  [[ -s "$work_dir/tracker-server.jar" ]] || fail "JAR ausente."
  [[ -d "$work_dir/lib" && -n "$(find "$work_dir/lib" -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail "Lib ausente."
  [[ -s "$work_dir/schema/changelog-master.xml" ]] || fail "Schema ausente."
fi

new_release="$RELEASES_DIR/$main_commit"
[[ ! -e "$new_release" ]] || fail "Release $main_commit já existe."
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
create_backups "$timestamp"

log "Preparando release HML isolado $main_commit."
mkdir -m 0750 "$new_release"
cp -a --reflink=auto "$previous_release/." "$new_release/"
rm -rf "$new_release/web"
cp -a "$work_dir/web" "$new_release/web"
if [[ "$deploy_type" == "completo" ]]; then
  rm -rf "$new_release/lib" "$new_release/schema"
  install -o root -g traccar-hml -m 0640 "$work_dir/tracker-server.jar" "$new_release/tracker-server.jar"
  cp -a "$work_dir/lib" "$new_release/lib"
  cp -a "$work_dir/schema" "$new_release/schema"
fi
install -o root -g traccar-hml -m 0640 "$work_dir/deploy-info" "$new_release/.kersting-deploy-info"
chown -R root:traccar-hml "$new_release"
chmod -R u=rwX,g=rX,o= "$new_release"
[[ -s "$new_release/tracker-server.jar" && -s "$new_release/web/index.html" ]] || fail "Novo release falhou na validação estática."

ln -s "$new_release" "$RUNTIME_ROOT/.current.new"
mv -Tf "$RUNTIME_ROOT/.current.new" "$CURRENT_LINK"
switched=1
systemctl restart "$SERVICE_NAME"
validate_hml_runtime publicado || fail "Smoke HML falhou."
assert_production_untouched
switched=0

log "Publicação HML concluída."
log "main_commit=$main_commit"
log "frontend_commit=$frontend_commit"
log "ref=$deploy_ref"
log "release=$new_release"
log "runtime_backup=$runtime_backup"
log "database_backup=$database_backup"
