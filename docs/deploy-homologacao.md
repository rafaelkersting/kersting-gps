# Publicação manual da homologação

Este processo publica o Kersting GPS somente quando um usuário autorizado aciona manualmente o workflow **Publicar Homologação**. Não existe gatilho de `push` ou `pull_request` e não existe botão de deploy dentro do Kersting GPS.

## Onde está o botão

Depois que o workflow estiver integrado à branch padrão do GitHub:

1. Acesse o repositório `rafaelkersting/kersting-gps` no GitHub.
2. Abra **Actions**.
3. Selecione **Publicar Homologação**.
4. Clique em **Run workflow**.
5. Selecione a branch que contém o workflow.
6. Mantenha a referência `release/v0.1.0-homologacao`.
7. Escolha `frontend` ou `completo`.
8. Digite exatamente `PUBLICAR` e confirme.

O GitHub só exibe um workflow manual na interface depois que o arquivo existe na branch padrão. Enquanto esta implementação estiver apenas na branch `feature/deploy-manual-github-actions`, o botão pode não aparecer.

O workflow usa o ambiente GitHub `homologacao`. É recomendado configurar revisores obrigatórios nesse ambiente em **Settings > Environments > homologacao**.

## Tipos de publicação

- `frontend`: compila o `traccar-web`, cria backup de `/opt/traccar/web`, sobrepõe a nova interface sem apagar imediatamente os assets antigos com hash e reinicia somente o serviço `traccar`.
- `completo`: compila frontend e backend, cria backup do JAR, `lib` e `web`, publica esses três componentes e reinicia somente o serviço `traccar`.

Nenhum tipo altera `/opt/traccar/conf/traccar.xml`, MariaDB, certificados SSL, OpenLiteSpeed, CyberPanel ou firewall.

## Secrets necessários

Cadastre em **Settings > Secrets and variables > Actions > New repository secret**:

| Secret | Conteúdo |
| --- | --- |
| `VPS_HOST` | Host DNS ou endereço da VPS. |
| `VPS_PORT` | Porta SSH, somente números. |
| `VPS_USER` | Deve ser exatamente `deploy`. |
| `VPS_SSH_KEY` | Chave privada SSH dedicada ao GitHub Actions. |
| `VPS_KNOWN_HOSTS` | Linha completa e verificada da chave pública do servidor SSH. |

Não reutilize chave pessoal. Gere um par exclusivo:

```bash
ssh-keygen -t ed25519 -C "github-actions-kersting-gps" -f kersting-gps-deploy
```

Cadastre o conteúdo de `kersting-gps-deploy` em `VPS_SSH_KEY`. Transfira somente `kersting-gps-deploy.pub` para a VPS por um canal administrativo confiável.

Para `VPS_KNOWN_HOSTS`, obtenha a chave do host e compare sua impressão digital com a exibida no console do provedor da VPS antes de cadastrar o valor. Nunca use `StrictHostKeyChecking=no`.

## Preparação única da VPS

Esses comandos devem ser executados manualmente como root depois que os arquivos desta implementação estiverem disponíveis em `/srv/kersting-gps`:

```bash
cd /srv/kersting-gps
git fetch origin
git switch release/v0.1.0-homologacao
git pull --ff-only origin release/v0.1.0-homologacao
git submodule sync --recursive
git submodule update --init --recursive
sudo bash tools/deploy/install-deploy-user.sh /root/kersting-gps-deploy.pub
sudo visudo -cf /etc/sudoers.d/kersting-gps-deploy
sudo ls -ld /var/tmp/kersting-gps-deploy /var/backups/kersting-gps
sudo ls -l /usr/local/sbin/deploy-kersting-gps
```

O instalador:

- cria ou reutiliza o usuário `deploy`;
- bloqueia autenticação por senha para esse usuário;
- instala a chave pública informada;
- cria `/var/tmp/kersting-gps-deploy` com escrita apenas para `deploy`;
- instala `/usr/local/sbin/deploy-kersting-gps` como `root:root`;
- concede via sudoers somente a execução controlada desse script para `frontend` ou `completo`;
- cria `/var/backups/kersting-gps` e `/var/log/kersting-gps-deploy.log`.

Confirme a autenticação antes do primeiro deploy:

```bash
ssh -i kersting-gps-deploy -p PORTA deploy@HOST
sudo -n -l
```

O usuário `deploy` não recebe acesso ao MariaDB e não possui sudo irrestrito.

## Processo correto para publicar uma melhoria visual

1. Alterar o `traccar-web`.
2. Testar no localhost.
3. Fazer commit e push da branch do frontend.
4. Integrar o frontend conforme o processo de revisão adotado.
5. Atualizar o gitlink no projeto principal para o commit integrado do frontend.
6. Confirmar o gitlink:

   ```bash
   git ls-tree HEAD traccar-web
   git -C traccar-web rev-parse HEAD
   ```

7. Fazer commit e push da `release/v0.1.0-homologacao`.
8. Executar manualmente **Actions > Publicar Homologação > Run workflow**.

O workflow interrompe a execução se o commit real do submódulo for diferente do gitlink ou se esse commit ainda não estiver disponível no repositório remoto.

## O que acontece durante a publicação

1. Os três campos do formulário são validados antes de qualquer conexão SSH.
2. A referência permitida é obtida sem persistir credenciais Git.
3. O submódulo é inicializado exatamente no gitlink registrado.
4. O frontend é compilado com Node.js 22.
5. No tipo `completo`, o backend é compilado com Java 21 usando `./gradlew clean assemble`.
6. O runner gera um pacote e seu SHA-256.
7. O pacote é enviado por OpenSSH com verificação obrigatória de `known_hosts`.
8. O script controlado valida tipo, nome, commit, SHA-256 e conteúdo do pacote.
9. Um backup é criado antes da primeira alteração.
10. Somente o runtime autorizado é atualizado e somente o serviço `traccar` é reiniciado.
11. Serviço, API interna, domínio público, HTML e estado do processo são validados.
12. Qualquer falha após a alteração aciona rollback automático e uma nova validação.

## Backups e rollback

Os backups ficam em `/var/backups/kersting-gps`:

```text
web-antes-<commit>-<data>-<hora>.tar.gz
traccar-antes-<commit>-<data>-<hora>.tar.gz
```

O rollback automático é registrado no log do workflow e em `/var/log/kersting-gps-deploy.log`.

Para um rollback manual de frontend, escolha primeiro o backup correto e execute como root:

```bash
systemctl stop traccar
mv /opt/traccar/web /opt/traccar/web-falha-$(date +%Y%m%d%H%M%S)
tar -C /opt/traccar -xzf /var/backups/kersting-gps/web-antes-COMMIT-DATA-HORA.tar.gz
systemctl start traccar
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/
curl -fsS -o /dev/null -w '%{http_code}\n' https://gps.kersting.net.br/
```

Para uma publicação completa, preserve os componentes com falha e extraia o backup em uma pasta temporária. Copie de volta somente `tracker-server.jar`, `lib`, `web` e, se existir, `.kersting-deploy-info`:

```bash
mkdir -p /root/kersting-rollback
tar -C /root/kersting-rollback -xzf /var/backups/kersting-gps/traccar-antes-COMMIT-DATA-HORA.tar.gz
systemctl stop traccar
rm -f /opt/traccar/tracker-server.jar
rm -rf /opt/traccar/lib /opt/traccar/web
cp -a /root/kersting-rollback/traccar/tracker-server.jar /opt/traccar/
cp -a /root/kersting-rollback/traccar/lib /opt/traccar/
cp -a /root/kersting-rollback/traccar/web /opt/traccar/
systemctl start traccar
```

Não copie `conf`, banco, certificados ou configurações do servidor web durante o rollback manual.

## Logs e commit instalado

No GitHub, abra **Actions > Publicar Homologação > execução desejada** para consultar cada etapa.

Na VPS:

```bash
sudo tail -n 200 /var/log/kersting-gps-deploy.log
sudo journalctl -u traccar --since "30 minutes ago" --no-pager
cat /opt/traccar/.kersting-deploy-info
systemctl show traccar -p ActiveState -p SubState -p MainPID -p NRestarts
```

O arquivo `.kersting-deploy-info` registra os commits principal e frontend, o tipo e o horário de build.

## Riscos e cuidados operacionais

- A indisponibilidade temporária de `gps.kersting.net.br` durante a validação provoca rollback, mesmo que a API interna esteja saudável.
- O deploy de frontend mantém assets antigos com hash para proteger navegadores com cache. Eles devem ser limpos futuramente por uma política de retenção, nunca durante uma publicação ativa.
- `NRestarts` diferente de zero reprova a publicação para evidenciar reinicializações inesperadas.
- Backups precisam de monitoramento de espaço e política de retenção fora deste primeiro workflow.
- A chave privada do GitHub Actions deve ser rotacionada se houver qualquer suspeita de exposição.
- O workflow não publica automaticamente e não deve ser alterado para aceitar referências arbitrárias sem nova revisão de segurança.
