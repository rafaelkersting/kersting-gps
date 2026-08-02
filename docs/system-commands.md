# Catálogo de comandos padrão

## Objetivo e escopo

O catálogo disponibiliza comandos salvos e aprovados pelo administrador para usuários elegíveis,
sem transmitir nada automaticamente. O envio continua usando a API e as permissões nativas do
Traccar. A seleção e a confirmação final são sempre manuais.

Os comandos iniciais são:

- solicitar localização (`positionSingle`);
- reiniciar rastreador (`rebootDevice`);
- bloquear motor (`engineStop`), restrito a administrador e tratado como crítico;
- desbloquear motor (`engineResume`), restrito a administrador e tratado como crítico.

Comandos personalizados só entram no catálogo depois que um administrador cadastrar o payload
correto para o protocolo e marcar explicitamente o comando como padrão. O sistema não inventa
sintaxe proprietária.

## Funcionamento

Os metadados ficam no JSON `attributes` do comando salvo. Não existe tabela ou coluna nova. Os
vínculos são armazenados nas tabelas nativas de permissões entre usuário, comando e dispositivo.
A API filtra a lista pelo protocolo atual do dispositivo e pelas permissões efetivas, inclusive as
herdadas por grupo.

No cadastro de usuário são criados somente os vínculos autorizados para o perfil. Ao conceder um
dispositivo ao usuário, o catálogo é reconciliado novamente. Para usuários existentes, o
administrador deve abrir **Configurações > Comandos salvos**, gerar a prévia e confirmar a
aplicação. Repetir a operação é seguro: vínculos existentes são preservados e não são duplicados.

Usuários desativados, temporários ou somente leitura são ignorados. A restrição `limitCommands`
continua protegendo o envio de comandos arbitrários. Um não administrador não pode alterar os
metadados nem os vínculos de comandos padrão.

## Segurança e auditoria

Comandos críticos exigem confirmação adicional no frontend e no backend. O bloqueio é recusado se
a última posição indicar velocidade acima de 0,5 nó. Para grupos, todos os dispositivos são
verificados antes que qualquer envio seja iniciado. A interface também alerta quando a posição está
ausente ou tem mais de dez minutos.

Uma resposta HTTP 200 significa que o servidor aceitou a solicitação; HTTP 202 significa que o
comando foi enfileirado. Nenhuma das duas respostas é apresentada como confirmação física do
equipamento. Criação, alteração, exclusão, vínculos e solicitações de envio usam o log de ações
nativo do Traccar.

## Plano de teste

1. Preparar o catálogo duas vezes e confirmar que não surgem comandos duplicados.
2. Visualizar e aplicar a distribuição duas vezes; na segunda execução, o total criado deve ser zero.
3. Criar usuários administrador, gerente, cliente, somente leitura, temporário e desativado.
4. Confirmar a matriz de perfis e a filtragem por protocolo em dispositivo direto e por grupo.
5. Confirmar que nenhum comando é transmitido ao preparar ou aplicar o catálogo.
6. Validar envio manual online, fila offline, `limitCommands`, confirmação crítica e bloqueio em
   movimento usando somente equipamento de teste.
7. Validar desktop e resolução reduzida, além de testes Java, frontend, lint e builds completos.

## Riscos e rollback

O principal risco é conceder um comando crítico a perfil inadequado. Por isso o catálogo inicial
limita bloqueio e desbloqueio a administradores, e a distribuição consulta o perfil a cada
reconciliação.

Não há migration de banco. Para rollback operacional, o administrador pode desativar o comando no
catálogo e remover seus vínculos pela API de permissões. Para rollback de código, reverta os commits
do frontend e do repositório principal; os comandos salvos permanecem inertes e não são enviados
automaticamente. Se for necessário removê-los, use a tela administrativa somente depois de
confirmar que não existem dependências operacionais.
