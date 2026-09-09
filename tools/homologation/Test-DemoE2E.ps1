param(
    [string] $BaseUrl = 'http://127.0.0.1:8082/api'
)

$ErrorActionPreference = 'Stop'

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) {
        throw "Falha de homologação: $Message"
    }
}

function Invoke-Api {
    param(
        [string] $Method,
        [string] $Path,
        [Microsoft.PowerShell.Commands.WebRequestSession] $Session,
        $Body
    )
    $arguments = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        SkipHttpErrorCheck = $true
        Headers = @{Accept = 'application/json'}
    }
    if ($null -ne $Session) {
        $arguments.WebSession = $Session
    }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = $Body | ConvertTo-Json -Depth 12
    } elseif ($Method -in @('POST', 'PUT')) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = '{}'
    }
    Invoke-WebRequest @arguments
}

function Read-Json {
    param($Response)
    $Response.Content | ConvertFrom-Json
}

function Assert-Status {
    param($Response, [int] $Expected, [string] $Label)
    Assert-True ($Response.StatusCode -eq $Expected) "$Label retornou HTTP $($Response.StatusCode), esperado $Expected"
}

function Connect-DemoSocket {
    param([Microsoft.PowerShell.Commands.WebRequestSession] $Session)
    $socket = [System.Net.WebSockets.ClientWebSocket]::new()
    [void] ($socket.Options.Cookies = $Session.Cookies)
    $uri = [Uri]($BaseUrl.Replace('http://', 'ws://').Replace('https://', 'wss://') + '/socket')
    $cancellation = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(10))
    [void] $socket.ConnectAsync($uri, $cancellation.Token).GetAwaiter().GetResult()
    return $socket
}

function Receive-DemoSocket {
    param([System.Net.WebSockets.ClientWebSocket] $Socket, [int] $TimeoutSeconds = 5)
    $buffer = [byte[]]::new(65536)
    $segment = [ArraySegment[byte]]::new($buffer)
    $stream = [IO.MemoryStream]::new()
    $cancellation = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
    try {
        do {
            $result = $Socket.ReceiveAsync($segment, $cancellation.Token).GetAwaiter().GetResult()
            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                return $null
            }
            $stream.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
        $json = [Text.Encoding]::UTF8.GetString($stream.ToArray())
        return $json | ConvertFrom-Json
    } catch [OperationCanceledException] {
        return $null
    } finally {
        $stream.Dispose()
        $cancellation.Dispose()
    }
}

function Wait-DemoState {
    param($Session, [string[]] $States, [int] $TimeoutSeconds)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $response = Invoke-Api GET '/demo/session' $Session $null
        Assert-Status $response 200 'Consulta da sessão Demo'
        $current = Read-Json $response
        if ($States -contains $current.status) {
            return $current
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Falha de homologação: sessão não alcançou $($States -join '/')"
}

$suffix = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
$adminEmail = 'admin-homolog@example.invalid'
$adminPassword = 'local-test-password'
$loginBody = "email=$([Uri]::EscapeDataString($adminEmail))&password=$([Uri]::EscapeDataString($adminPassword))"
$login = Invoke-WebRequest -Method POST -Uri "$BaseUrl/session" -ContentType 'application/x-www-form-urlencoded' `
    -Body $loginBody -SkipHttpErrorCheck -SessionVariable adminSession
if ($login.StatusCode -ne 200) {
    $createAdmin = Invoke-Api POST '/users' $null @{
        name = 'Administrador Homologação'
        email = $adminEmail
        password = $adminPassword
    }
    Assert-Status $createAdmin 200 'Criação do administrador descartável'
    $login = Invoke-WebRequest -Method POST -Uri "$BaseUrl/session" -ContentType 'application/x-www-form-urlencoded' `
        -Body $loginBody -SkipHttpErrorCheck -SessionVariable adminSession
}
Assert-Status $login 200 'Login do administrador descartável'

$realUser = Read-Json (Invoke-Api POST '/users' $adminSession @{
    name = 'Cliente Real Homologação'
    email = "cliente-real-$suffix@example.invalid"
    password = 'local-client-password'
})
$realDevice = Read-Json (Invoke-Api POST '/devices' $adminSession @{
    name = 'Veículo Cliente Real'
    uniqueId = "REAL-HOMO-$suffix"
    attributes = @{}
})
Assert-Status (Invoke-Api POST '/permissions' $adminSession ([ordered]@{
    userId = $realUser.id
    deviceId = $realDevice.id
})) 204 'Vínculo do cliente real'

$demoAResponse = Invoke-WebRequest -Method POST -Uri "$BaseUrl/demo/session" -ContentType 'application/json' `
    -Body (@{name = 'Demo A'; email = "demo-a-$suffix@example.invalid"; company = 'Kersting'} | ConvertTo-Json) `
    -SkipHttpErrorCheck -SessionVariable demoASession
$demoBResponse = Invoke-WebRequest -Method POST -Uri "$BaseUrl/demo/session" -ContentType 'application/json' `
    -Body (@{name = 'Demo B'; email = "demo-b-$suffix@example.invalid"; company = 'Kersting'} | ConvertTo-Json) `
    -SkipHttpErrorCheck -SessionVariable demoBSession
Assert-Status $demoAResponse 200 'Criação da Demo A'
Assert-Status $demoBResponse 200 'Criação da Demo B'
$demoA = Read-Json $demoAResponse
$demoB = Read-Json $demoBResponse

$cookieA = $demoASession.Cookies.GetCookies([Uri]$BaseUrl) | Select-Object -First 1
Assert-True ($null -ne $cookieA -and $cookieA.HttpOnly) 'cookie de sessão não está marcado como HttpOnly'
Assert-True ($demoA.user.email.EndsWith('@demo.invalid')) 'e-mail real foi exposto no usuário temporário'
Assert-True ($demoA.session.deviceId -ne $demoB.session.deviceId) 'as duas sessões compartilham dispositivo'

$devicesA = @(Read-Json (Invoke-Api GET '/devices' $demoASession $null))
$devicesB = @(Read-Json (Invoke-Api GET '/devices' $demoBSession $null))
Assert-True ($devicesA.Count -eq 1 -and $devicesA[0].id -eq $demoA.session.deviceId) 'escopo de dispositivos da Demo A'
Assert-True ($devicesB.Count -eq 1 -and $devicesB[0].id -eq $demoB.session.deviceId) 'escopo de dispositivos da Demo B'
Assert-True ($devicesA[0].id -ne $realDevice.id -and $devicesB[0].id -ne $realDevice.id) 'dispositivo real vazou'

foreach ($probe in @(
    @{Method = 'GET'; Path = '/users'; Body = $null; Label = 'listar usuários'},
    @{Method = 'GET'; Path = '/server'; Body = $null; Label = 'ler servidor'},
    @{Method = 'GET'; Path = '/access/profiles'; Body = $null; Label = 'listar perfis'},
    @{Method = 'GET'; Path = "/devices/$($realDevice.id)"; Body = $null; Label = 'ler dispositivo real'},
    @{Method = 'POST'; Path = '/commands/send?confirmed=true'; Body = @{
            deviceId = $demoA.session.deviceId; type = 'engineStop'; attributes = @{}
        }; Label = 'enviar comando perigoso'}
)) {
    Assert-Status (Invoke-Api $probe.Method $probe.Path $demoASession $probe.Body) 403 $probe.Label
}

$ownUser = Read-Json (Invoke-Api GET "/users/$($demoA.user.id)" $demoASession $null)
$ownUser.expirationTime = [DateTime]::UtcNow.AddDays(1).ToString('o')
Assert-Status (Invoke-Api PUT "/users/$($demoA.user.id)" $demoASession $ownUser) 403 'estender expiresAt'
$ownDevice = $devicesA[0]
$ownDevice.uniqueId = "REAL-INJECTION-$suffix"
Assert-Status (Invoke-Api PUT "/devices/$($ownDevice.id)" $demoASession $ownDevice) 403 'alterar uniqueId'
Assert-Status (Invoke-Api POST '/demo/scenarios/not-approved' $demoASession $null) 400 'iniciar rota arbitrária'

$socketA = Connect-DemoSocket $demoASession
$socketB = Connect-DemoSocket $demoBSession
Receive-DemoSocket $socketA 2 | Out-Null
Receive-DemoSocket $socketB 2 | Out-Null

$startA = Read-Json (Invoke-Api POST "/demo/scenarios/urban?deviceId=$($realDevice.id)" $demoASession $null)
$startB = Read-Json (Invoke-Api POST '/demo/scenarios/highway' $demoBSession $null)
Assert-True ($startA.deviceId -eq $demoA.session.deviceId) 'injeção de deviceId alterou o dispositivo da Demo A'
Assert-True ($startB.deviceId -eq $demoB.session.deviceId) 'Demo B iniciou dispositivo incorreto'

$wsDevicesA = [Collections.Generic.HashSet[long]]::new()
$wsDevicesB = [Collections.Generic.HashSet[long]]::new()
for ($index = 0; $index -lt 16; $index++) {
    foreach ($entry in @(
        @{Socket = $socketA; Devices = $wsDevicesA},
        @{Socket = $socketB; Devices = $wsDevicesB}
    )) {
        $message = Receive-DemoSocket $entry.Socket 3
        foreach ($position in @($message.positions)) {
            if ($null -ne $position.deviceId) {
                $entry.Devices.Add([long]$position.deviceId) | Out-Null
            }
        }
        foreach ($event in @($message.events)) {
            if ($null -ne $event.deviceId) {
                $entry.Devices.Add([long]$event.deviceId) | Out-Null
            }
        }
    }
}
Assert-True ($wsDevicesA.Count -eq 1 -and $wsDevicesA.Contains([long]$demoA.session.deviceId)) 'WebSocket da Demo A vazou dados'
Assert-True ($wsDevicesB.Count -eq 1 -and $wsDevicesB.Contains([long]$demoB.session.deviceId)) 'WebSocket da Demo B vazou dados'

Wait-DemoState $demoASession @('COMPLETED') 30 | Out-Null
Wait-DemoState $demoBSession @('COMPLETED') 30 | Out-Null

$from = [Uri]::EscapeDataString([DateTime]::UtcNow.AddMinutes(-5).ToString('o'))
$to = [Uri]::EscapeDataString([DateTime]::UtcNow.AddMinutes(5).ToString('o'))
$routeA = @(Read-Json (Invoke-Api GET "/reports/route?deviceId=$($demoA.session.deviceId)&from=$from&to=$to" $demoASession $null))
Assert-True ($routeA.Count -gt 5) 'relatório da Demo A não contém a rota'
Assert-True (@($routeA | Where-Object {$_.deviceId -ne $demoA.session.deviceId}).Count -eq 0) 'relatório próprio contém outro dispositivo'
Assert-Status (Invoke-Api GET "/reports/route?deviceId=$($demoB.session.deviceId)&from=$from&to=$to" $demoASession $null) 403 'relatório da Demo B pela Demo A'

$notificationsA = @(Read-Json (Invoke-Api GET '/notifications' $demoASession $null))
$notificationsB = @(Read-Json (Invoke-Api GET '/notifications' $demoBSession $null))
Assert-True ($notificationsA.Count -eq 7 -and $notificationsB.Count -eq 7) 'regras de notificação não estão isoladas'

$complete = Read-Json (Invoke-Api POST '/demo/scenarios/complete' $demoASession $null)
Assert-True ($complete.deviceId -eq $demoA.session.deviceId) 'roteiro completo iniciou dispositivo incorreto'
Wait-DemoState $demoASession @('COMPLETED') 50 | Out-Null
$eventsA = @(Read-Json (Invoke-Api GET "/reports/events?deviceId=$($demoA.session.deviceId)&from=$from&to=$to" $demoASession $null))
$eventTypesA = @($eventsA.type | Sort-Object -Unique)
foreach ($expectedType in @(
    'deviceOnline', 'deviceOffline', 'ignitionOn', 'ignitionOff',
    'geofenceEnter', 'geofenceExit', 'deviceOverspeed'
)) {
    Assert-True ($eventTypesA -contains $expectedType) "evento real $expectedType não foi gerado"
}
Assert-True (@($eventsA | Where-Object {$_.deviceId -ne $demoA.session.deviceId}).Count -eq 0) 'eventos da Demo A contêm outro dispositivo'

[void] $socketA.CloseAsync(
    [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
    'homologacao concluida',
    [Threading.CancellationToken]::None).GetAwaiter().GetResult()
[void] $socketB.CloseAsync(
    [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
    'homologacao concluida',
    [Threading.CancellationToken]::None).GetAwaiter().GetResult()
$socketA.Dispose()
$socketB.Dispose()

[pscustomobject]@{
    result = 'PASS'
    demoA = @{userId = $demoA.user.id; deviceId = $demoA.session.deviceId; routePoints = $routeA.Count}
    demoB = @{userId = $demoB.user.id; deviceId = $demoB.session.deviceId}
    realClient = @{userId = $realUser.id; deviceId = $realDevice.id}
    forbiddenProbes = 8
    websocketADeviceIds = @($wsDevicesA)
    websocketBDeviceIds = @($wsDevicesB)
    notificationsPerDemo = 7
    eventTypes = $eventTypesA
} | ConvertTo-Json -Depth 5
