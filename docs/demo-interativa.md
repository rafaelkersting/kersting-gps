# Demonstração interativa

## Arquitetura

A demonstração usa o mesmo caminho de dados de um rastreador OsmAnd real:

```text
DemoSimulatorService -> HTTP interno 127.0.0.1:5055 -> decoder OsmAnd
-> posição/eventos do Traccar -> WebSocket -> mapa e notificações web
```

O navegador nunca escolhe `userId`, `groupId`, `deviceId`, `uniqueId`, coordenadas, rota bruta ou
permissões. Ele envia apenas o cenário pertencente ao catálogo fechado e comandos de
iniciar/pausar/retomar/parar. O backend resolve a sessão pelo usuário autenticado e valida novamente
a relação explícita entre `DemoSession` e dispositivo antes de transmitir.

Cada criação provisiona usuário temporário, grupo, dispositivo, geocerca, notificações e vínculos
nativos exclusivos. O usuário recebe o perfil RBAC `Demonstração`; chamadas fora do catálogo de
permissões continuam sendo recusadas pelo controle de acesso comum da API.

## Configuração

| Chave | Padrão | Finalidade |
|---|---:|---|
| `demo.enabled` | `true` | Permite novas demonstrações; definir `false` é o kill switch |
| `demo.sessionDurationMinutes` | `60` | Validade do usuário e da sessão |
| `demo.maxConcurrentSessions` | `20` | Limite global de sessões ativas |
| `demo.maxSessionsPerIp` | `3` | Limite diário por IP pseudonimizado |
| `demo.maxSessionsPerEmail` | `2` | Limite diário por e-mail pseudonimizado |
| `demo.rateLimitMaxAttempts` | `8` | Tentativas curtas por IP |
| `demo.rateLimitWindowSeconds` | `60` | Janela do limitador curto |
| `demo.cleanupInterval` | `60` | Frequência do job de recuperação |
| `demo.defaultScenario` | `urban` | Cenário inicialmente selecionado |
| `demo.simulatorUrl` | `http://127.0.0.1:5055/` | Receptor OsmAnd interno |
| `demo.simulatorIntervalMillis` | `3000` | Intervalo dos pontos fora do roteiro completo |
| `demo.offlineDurationSeconds` | `12` | Pausa controlada do cenário offline |
| `demo.completeDurationSeconds` | `540` | Duração do roteiro completo |
| `demo.hashSecret` | gerado no banco | Segredo HMAC opcional, mínimo de 32 caracteres; se ausente, uma chave exclusiva é gerada e persistida |
| `demo.trustProxy` | `false` | Aceita o primeiro IP de `X-Forwarded-For` somente atrás de proxy confiável |
| `demo.auditRetentionDays` | `7` | Retenção da linha pseudonimizada após limpeza |

Em produção, `DEMO_HASH_SECRET` pode ser definido pelo gerenciador de segredos do pipeline. Quando ele
não existe, o backend gera com `SecureRandom` uma chave exclusiva de 256 bits e a mantém na tabela
`tc_demo_configuration`; a chave não aparece em respostas nem em logs. Definir `DEMO_ENABLED=false`
bloqueia novas criações sem interromper rastreamento real; sessões já existentes expiram e são limpas
pelo job.

## Privacidade e limpeza

Nome e e-mail fornecidos não viram lead. O nome existe apenas no usuário temporário e o endereço real
nunca é salvo: o usuário usa um endereço aleatório `@demo.invalid`, enquanto e-mail e IP são guardados
como HMAC para os limites de abuso. Empresa não é persistida.

Na expiração ou abandono, o job para o simulador e remove, pelos IDs registrados na sessão, posições,
eventos, notificações, geocerca, dispositivo, grupo, perfil atribuído e usuário. A operação é
idempotente. Permanece por sete dias apenas a linha de auditoria pseudonimizada da `DemoSession`, sem
nome, e-mail, cookie ou token.

No reinício do backend, sessões em criação, execução, pausa ou limpeza são encerradas e limpas. O
simulador não retoma automaticamente uma transmissão interrompida.

## Homologação descartável

O arquivo `debug-demo.xml` aponta para banco e mídia dentro de `target/`, escuta somente em loopback e
usa tempos acelerados. Ele exercita deliberadamente a geração persistente da chave HMAC. Remover
`target/demo-database*` e `target/demo-media` para repetir a migration desde zero.

Para validar compatibilidade real, executar o mesmo schema em MariaDB descartável, iniciar o servidor
com driver MariaDB e repetir a criação, transmissão OsmAnd, eventos, expiração e limpeza. Nenhum SQL
manual deve ser executado no banco de produção.

## Evolução de infraestrutura

Se volume ou risco operacional crescerem, a mesma API pode ser movida para
`demo.gps.kersting.net.br`, com serviço e banco próprios. Isso não é necessário na primeira versão;
rotas personalizadas também permanecem fora do escopo até que o catálogo controlado esteja estável.
