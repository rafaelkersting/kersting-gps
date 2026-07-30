# Simulador de veículo para testes locais

Esta ferramenta envia posições ao Kersting GPS pelo protocolo OsmAnd HTTP do Traccar. Ela não usa usuário, senha, cookie ou token e não modifica o backend nem o frontend.

## Pré-requisitos e serviços

- Java 21 para o backend.
- Node.js e npm compatíveis com o `traccar-web` para o frontend.
- Backend na porta 8082 e protocolo OsmAnd HTTP disponível na porta 5055.
- Frontend na porta 3000.

Na raiz do repositório, compile e inicie o backend:

```powershell
.\gradlew.bat build --no-daemon --stacktrace
java -jar .\target\tracker-server.jar .\debug.xml
```

Em outro terminal, inicie o frontend:

```powershell
Set-Location .\traccar-web
npm.cmd ci
npm.cmd start
```

## Cadastrar o veículo de teste

1. Acesse `http://localhost:3000` e faça login.
2. Abra **Configurações > Dispositivos** e adicione um dispositivo.
3. Use o nome **Veículo de Teste**.
4. Use exatamente o identificador único **999000000000001**.
5. Salve o dispositivo antes de iniciar o simulador.

O nome é cadastrado na interface; o rastreador é associado ao dispositivo pelo identificador único.

## Executar o simulador

Na raiz do repositório:

```powershell
.\tools\simulator\SimulateVehicle.ps1
```

Se a política local bloquear scripts, execute sem alterar a política do computador:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\simulator\SimulateVehicle.ps1
```

Exemplo com todos os parâmetros principais:

```powershell
.\tools\simulator\SimulateVehicle.ps1 `
  -ServerAddress localhost `
  -Port 5055 `
  -DeviceId 999000000000001 `
  -RouteFile .\tools\simulator\sample-route.json `
  -IntervalSeconds 3 `
  -RepeatCount 2 `
  -DefaultSpeedKph 35 `
  -InitialBattery 95 `
  -SendStopAtEnd $true
```

Use `-Continuous` para repetir a rota até pressionar Ctrl+C. Use `-IntervalSeconds 0` somente para testes rápidos. Para não transmitir a parada automática ao terminar, informe `-SendStopAtEnd $false`.

Cada posição contém identificador, coordenadas, horário atual, velocidade, direção, precisão, bateria, ignição, movimento e altitude quando definida na rota. A velocidade do arquivo é expressa em km/h e convertida para nós ao enviar, conforme esperado pelo protocolo OsmAnd.

## Observar o movimento e o WebSocket

Abra o mapa em `http://localhost:3000`. Após a primeira posição, o **Veículo de Teste** deve aparecer on-line. Durante a execução, acompanhe a mudança de posição, velocidade, direção, ignição e bateria no card do dispositivo. As atualizações em tempo real recebidas sem recarregar a página comprovam a comunicação pelo WebSocket.

O terminal mostra cada posição, suas coordenadas, velocidade e o status HTTP retornado. Respostas HTTP 200 indicam que o Traccar aceitou a mensagem.

## Consultar o histórico

1. Abra **Relatórios**.
2. Selecione um relatório de rota ou viagens.
3. Escolha **Veículo de Teste** e um período que inclua o horário da simulação.
4. Gere o relatório e confira os pontos e o percurso no mapa.

## Testar cerca virtual

Crie uma cerca envolvendo parte da rota próxima a Panambi, RS, vincule-a ao dispositivo e execute o simulador. Uma cerca pequena cruzada pelo circuito permite testar eventos de entrada e saída.

## Testar excesso de velocidade

Configure no dispositivo ou no atributo calculado o limite desejado abaixo de uma das velocidades da rota. O arquivo de exemplo chega a 42 km/h. Também é possível informar outra velocidade padrão; ela será usada nos pontos que não definirem `speedKph`.

## Interromper e remover os dados de teste

Pressione Ctrl+C para interromper. Por padrão, o bloco de finalização tenta enviar a última coordenada com velocidade zero, ignição desligada e movimento falso.

Para remover o veículo, abra **Configurações > Dispositivos**, selecione **Veículo de Teste** e use a opção de exclusão. A ferramenta não exclui dispositivos nem acessa diretamente o banco de dados.
