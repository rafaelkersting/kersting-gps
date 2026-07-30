[CmdletBinding()]
param(
    [string]$ServerAddress = 'localhost',
    [ValidateRange(1, 65535)]
    [int]$Port = 5055,
    [string]$DeviceId = '999000000000001',
    [string]$RouteFile = (Join-Path $PSScriptRoot 'sample-route.json'),
    [ValidateRange(0, 86400)]
    [double]$IntervalSeconds = 3,
    [ValidateRange(1, 2147483647)]
    [int]$RepeatCount = 1,
    [ValidateRange(0, 1000)]
    [double]$DefaultSpeedKph = 35,
    [ValidateRange(0, 100)]
    [double]$InitialBattery = 95,
    [switch]$Continuous,
    [bool]$SendStopAtEnd = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Format-InvariantNumber {
    param(
        [Parameter(Mandatory)]
        [double]$Value,
        [string]$Format = '0.######'
    )

    return $Value.ToString($Format, [System.Globalization.CultureInfo]::InvariantCulture)
}

function Test-OsmAndPort {
    param(
        [Parameter(Mandatory)]
        [string]$HostName,
        [Parameter(Mandatory)]
        [int]$TargetPort
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        try {
            $connectTask = $client.ConnectAsync($HostName, $TargetPort)
            if (-not $connectTask.Wait([TimeSpan]::FromSeconds(3)) -or -not $client.Connected) {
                throw 'Conexão não estabelecida.'
            }
        } catch {
            throw "A porta ${TargetPort} não está acessível em ${HostName}. Confirme se o backend e o protocolo OsmAnd estão ativos."
        }
    } finally {
        $client.Dispose()
    }
}

function Get-PointValue {
    param(
        [Parameter(Mandatory)]
        [psobject]$Point,
        [Parameter(Mandatory)]
        [string]$Name,
        $DefaultValue
    )

    $property = $Point.PSObject.Properties[$Name]
    if ($null -ne $property -and $null -ne $property.Value) {
        return $property.Value
    }
    return $DefaultValue
}

function New-OsmAndUri {
    param(
        [Parameter(Mandatory)]
        [psobject]$Point,
        [Parameter(Mandatory)]
        [double]$SpeedKph,
        [Parameter(Mandatory)]
        [double]$Battery,
        [Parameter(Mandatory)]
        [bool]$Ignition,
        [Parameter(Mandatory)]
        [bool]$Motion
    )

    $parameters = [ordered]@{
        id        = $DeviceId
        lat       = Format-InvariantNumber -Value ([double]$Point.latitude)
        lon       = Format-InvariantNumber -Value ([double]$Point.longitude)
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
        speed     = Format-InvariantNumber -Value ($SpeedKph / 1.852) -Format '0.###'
        bearing   = Format-InvariantNumber -Value ([double](Get-PointValue -Point $Point -Name 'bearing' -DefaultValue 0)) -Format '0.##'
        accuracy  = Format-InvariantNumber -Value ([double](Get-PointValue -Point $Point -Name 'accuracy' -DefaultValue 5)) -Format '0.##'
        batt      = Format-InvariantNumber -Value $Battery -Format '0.##'
        ignition  = $Ignition.ToString().ToLowerInvariant()
        motion    = $Motion.ToString().ToLowerInvariant()
        valid     = 'true'
    }

    if ($null -ne $Point.PSObject.Properties['altitude'] -and $null -ne $Point.altitude) {
        $parameters.altitude = Format-InvariantNumber -Value ([double]$Point.altitude) -Format '0.##'
    }

    $query = ($parameters.GetEnumerator() | ForEach-Object {
        '{0}={1}' -f [uri]::EscapeDataString([string]$_.Key), [uri]::EscapeDataString([string]$_.Value)
    }) -join '&'

    return "http://${ServerAddress}:${Port}/?${query}"
}

function Send-VehiclePosition {
    param(
        [Parameter(Mandatory)]
        [psobject]$Point,
        [Parameter(Mandatory)]
        [double]$SpeedKph,
        [Parameter(Mandatory)]
        [double]$Battery,
        [Parameter(Mandatory)]
        [bool]$Ignition,
        [Parameter(Mandatory)]
        [bool]$Motion,
        [Parameter(Mandatory)]
        [string]$Label
    )

    $uri = New-OsmAndUri -Point $Point -SpeedKph $SpeedKph -Battery $Battery -Ignition $Ignition -Motion $Motion
    try {
        $response = Invoke-WebRequest -Uri $uri -Method Get -TimeoutSec 10 -UseBasicParsing
        $script:sentCount++
        Write-Host ('[{0}] lat={1} lon={2} velocidade={3:N1} km/h direção={4}° bateria={5:N1}% HTTP {6}' -f `
                $Label, $Point.latitude, $Point.longitude, $SpeedKph,
                (Get-PointValue -Point $Point -Name 'bearing' -DefaultValue 0), $Battery, $response.StatusCode)
        return $true
    } catch {
        $script:errorCount++
        Write-Error -Message ('Falha ao enviar {0}: {1}' -f $Label, $_.Exception.Message) -ErrorAction Continue
        return $false
    }
}

$sentCount = 0
$errorCount = 0
$lastPoint = $null
$battery = $InitialBattery
$completedNormally = $false
$interrupted = $false

try {
    $resolvedRoute = (Resolve-Path -LiteralPath $RouteFile).Path
    $routeDocument = Get-Content -LiteralPath $resolvedRoute -Raw | ConvertFrom-Json
    if ($null -ne $routeDocument.PSObject.Properties['points']) {
        $points = @($routeDocument.points)
    } else {
        $points = @($routeDocument)
    }

    if ($points.Count -lt 2) {
        throw 'A rota deve conter pelo menos duas posições.'
    }

    foreach ($point in $points) {
        if ($null -eq $point.PSObject.Properties['latitude'] -or $null -eq $point.PSObject.Properties['longitude']) {
            throw 'Todos os pontos devem possuir latitude e longitude.'
        }
        if ([double]$point.latitude -lt -90 -or [double]$point.latitude -gt 90 -or
            [double]$point.longitude -lt -180 -or [double]$point.longitude -gt 180) {
            throw 'A rota contém uma latitude ou longitude inválida.'
        }
    }

    Write-Host "Validando o protocolo OsmAnd em ${ServerAddress}:${Port}..."
    Test-OsmAndPort -HostName $ServerAddress -TargetPort $Port
    Write-Host ('Rota carregada: {0} pontos. Dispositivo: {1}.' -f $points.Count, $DeviceId)
    Write-Host 'Pressione Ctrl+C para interromper. A parada final será enviada quando habilitada.'

    $repetition = 0
    do {
        $repetition++
        Write-Host ('Iniciando repetição {0}{1}.' -f $repetition, $(if ($Continuous) { ' (modo contínuo)' } else { " de ${RepeatCount}" }))
        for ($index = 0; $index -lt $points.Count; $index++) {
            $point = $points[$index]
            $lastPoint = $point
            $speedKph = [double](Get-PointValue -Point $point -Name 'speedKph' -DefaultValue $DefaultSpeedKph)
            $battery = [Math]::Max(0, $InitialBattery - ($sentCount * 0.15))
            [void](Send-VehiclePosition -Point $point -SpeedKph $speedKph -Battery $battery `
                    -Ignition $true -Motion ($speedKph -gt 0) -Label ('{0}/{1}' -f ($index + 1), $points.Count))

            if ($IntervalSeconds -gt 0 -and ($index -lt $points.Count - 1 -or $Continuous -or $repetition -lt $RepeatCount)) {
                Start-Sleep -Milliseconds ([int]($IntervalSeconds * 1000))
            }
        }
    } while ($Continuous -or $repetition -lt $RepeatCount)

    $completedNormally = $true
} catch [System.Management.Automation.PipelineStoppedException] {
    $interrupted = $true
    Write-Warning 'Simulação interrompida pelo usuário.'
} catch {
    Write-Error -Message ('Não foi possível executar a simulação: {0}' -f $_.Exception.Message) -ErrorAction Continue
} finally {
    if ($SendStopAtEnd -and $null -ne $lastPoint) {
        Write-Host 'Enviando parada final (velocidade 0, ignição desligada e sem movimento)...'
        [void](Send-VehiclePosition -Point $lastPoint -SpeedKph 0 -Battery $battery `
                -Ignition $false -Motion $false -Label 'parada-final')
    }

    Write-Host ('Resumo: {0} posições confirmadas; {1} erros.' -f $sentCount, $errorCount)
    if (-not $completedNormally -and -not $interrupted -and $sentCount -eq 0) {
        exit 1
    }
}
