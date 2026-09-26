# Publicação segura da homologação Kersting GPS

Este documento define o procedimento permanente para publicar exclusivamente em homologação. O ambiente de produção não participa deste fluxo.

## Ambiente oficial

| Recurso | Homologação |
| --- | --- |
| Runtime | `/opt/traccar-hml` |
| Releases | `/opt/traccar-hml/releases` |
| Release ativa | `/opt/traccar-hml/current` |
| Serviço | `traccar-hml.service` |
| Backend | `127.0.0.1:18082` |
| Banco | `gps_kersting_hml` |
| MariaDB | `127.0.0.1:3308` |
| Socket | `/run/mariadb-traccar-hml/mariadb.sock` |
| API | `kersting-api-hml.service` |
| URL | `https://gps-hml.kersting.net.br/` |
| Backups | `/var/backups/traccar-hml` |

Não deve ser criada uma segunda HML sem autorização explícita.

## Regra permanente de acesso

O usuário `deploy-hml` é permanente, autentica somente pela chave dedicada e permanece desativado por expiração de conta. A senha continua bloqueada mesmo durante a janela autorizada.

Antes de acessar a VPS para uma publicação, o responsável pelo deploy deve solicitar:

> Preciso que você ative o usuário de homologação deploy-hml.

O acesso só pode ocorrer depois da confirmação `usuário HML ativado`. Encerrados deploy, smoke e validação, deve ser solicitada a desativação.

Comandos root:

```bash
/usr/local/sbin/kersting-gps-hml-access ativar
/usr/local/sbin/kersting-gps-hml-access desativar
/usr/local/sbin/kersting-gps-hml-access status
```

`passwd -l` não é usado como único bloqueio. A operação `desativar` expira a conta e encerra processos/sessões remanescentes do usuário.

## Limites do usuário

A chave possui `restrict` e um comando forçado. Não existe shell SSH livre. O gate aceita somente:

- `status`;
- `upload <pacote-validado>`;
- `deploy`;
- `cleanup`.

O sudoers autoriza exatamente, sem argumentos ou curingas:

```text
deploy-hml ALL=(root) NOPASSWD: /usr/local/sbin/deploy-kersting-gps-hml
```

O usuário não pode chamar o publicador de produção, reiniciar `traccar.service`, alterar `/opt/traccar`, acessar `gps_kersting` ou escolher destinos por argumentos.

## Proteções do publicador HML

Antes de qualquer alteração, `/usr/local/sbin/deploy-kersting-gps-hml` valida:

- constantes exatas de runtime, serviços, banco, portas e URLs HML;
- `traccar.xml` apontando para `127.0.0.1:3308/gps_kersting_hml`;
- ausência de `gps_kersting` na instância MariaDB HML;
- configuração da API HML sem referências operacionais de produção;
- portas `5601` e `15023` fechadas no firewall;
- serviços HML ativos;
- estado inicial do serviço de produção.

O PID e `NRestarts` de `traccar.service` são comparados antes e depois. Qualquer mudança reprova a publicação.

## Backup, release e rollback

Toda publicação, inclusive somente frontend:

1. preserva o release apontado por `current`;
2. cria backup compactado do runtime atual;
3. cria dump de `gps_kersting_hml` pelo socket exclusivo;
4. monta um novo diretório em `releases/<commit-principal>`;
5. valida os arquivos antes de trocar `current`;
6. troca o link de forma atômica;
7. reinicia somente `traccar-hml.service`;
8. valida backend e URL HML.

Em falha após a troca, o publicador restaura o link anterior. Em publicação completa, também restaura exclusivamente `gps_kersting_hml`. O release rejeitado é preservado com sufixo `.failed-<data>`.

## Manifesto

Cada release recebe `.kersting-deploy-info` com:

- `main_commit`;
- `frontend_commit`;
- `type`;
- `ref`;
- `built_at`;
- `workflow`;
- `run_id`;
- `run_attempt`;
- `run_url`;
- `release`.

## Workflow manual

O workflow `.github/workflows/deploy-homologacao.yml` não possui gatilho de push. Ele exige:

- ambiente GitHub `homologacao`;
- confirmação literal `PUBLICAR HML`;
- referência presente na lista explícita `ALLOWED_REFS`;
- usuário SSH exatamente `deploy-hml`;
- secrets exclusivos da HML;
- gitlink do frontend resolvido exatamente;
- validações de segurança, lint e build antes da conexão.

O checkout que contém os scripts HML permanece separado da referência da
aplicação a publicar. A referência autorizada é obtida em `source/`, evitando
que uma branch de funcionalidade substitua o publicador seguro.

Secrets esperados, nunca compartilhados com produção:

```text
HML_VPS_HOST
HML_VPS_PORT
HML_VPS_USER
HML_VPS_SSH_KEY
HML_VPS_KNOWN_HOSTS
```

O pacote é enviado pela entrada padrão para o gate forçado. Não há `scp`, SFTP ou shell remoto genérico.

## Instalação inicial

Após os arquivos serem revisados e disponibilizados na VPS por um canal administrativo autorizado:

```bash
cd /srv/kersting-gps
bash tools/deploy/install-deploy-hml-user.sh /root/kersting-gps-deploy-hml.pub
```

O instalador valida os scripts e os recursos HML antes de criar ou atualizar o usuário. Ao final, `deploy-hml` permanece desativado.

## Branch de geocercas

A referência `codex/melhorar-associacao-geocercas` está autorizada no workflow, mas não deve ser publicada enquanto os scripts não estiverem instalados, os secrets HML não estiverem separados e o usuário não tiver sido ativado manualmente para a janela de publicação.

## Produção

O script `/usr/local/sbin/deploy-kersting-gps` não é alterado nem chamado por este fluxo. Merge e deploy de produção são etapas independentes e exigem autorização própria.
