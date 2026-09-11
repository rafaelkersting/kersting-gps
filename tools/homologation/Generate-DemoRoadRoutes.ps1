param(
    [string] $RouterUrl = 'https://router.project-osrm.org'
)

$ErrorActionPreference = 'Stop'

function Get-DistanceMeters {
    param([double] $Latitude1, [double] $Longitude1, [double] $Latitude2, [double] $Longitude2)
    $earthRadius = 6371000.0
    $lat1 = $Latitude1 * [Math]::PI / 180
    $lat2 = $Latitude2 * [Math]::PI / 180
    $latDelta = ($Latitude2 - $Latitude1) * [Math]::PI / 180
    $lonDelta = ($Longitude2 - $Longitude1) * [Math]::PI / 180
    $a = [Math]::Sin($latDelta / 2) * [Math]::Sin($latDelta / 2) +
        [Math]::Cos($lat1) * [Math]::Cos($lat2) *
        [Math]::Sin($lonDelta / 2) * [Math]::Sin($lonDelta / 2)
    return $earthRadius * 2 * [Math]::Atan2([Math]::Sqrt($a), [Math]::Sqrt(1 - $a))
}

function Get-Course {
    param([double] $Latitude1, [double] $Longitude1, [double] $Latitude2, [double] $Longitude2)
    $lat1 = $Latitude1 * [Math]::PI / 180
    $lat2 = $Latitude2 * [Math]::PI / 180
    $lonDelta = ($Longitude2 - $Longitude1) * [Math]::PI / 180
    $y = [Math]::Sin($lonDelta) * [Math]::Cos($lat2)
    $x = [Math]::Cos($lat1) * [Math]::Sin($lat2) -
        [Math]::Sin($lat1) * [Math]::Cos($lat2) * [Math]::Cos($lonDelta)
    return ([Math]::Atan2($y, $x) * 180 / [Math]::PI + 360) % 360
}

function Expand-RoadGeometry {
    param([object[]] $Coordinates, [double] $MaximumSegmentMeters)
    $result = [System.Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt $Coordinates.Count - 1; $index++) {
        $from = $Coordinates[$index]
        $to = $Coordinates[$index + 1]
        $fromLatitude = [double] $from[1]
        $fromLongitude = [double] $from[0]
        $toLatitude = [double] $to[1]
        $toLongitude = [double] $to[0]
        if ($index -eq 0) {
            $result.Add([pscustomobject]@{ Latitude = $fromLatitude; Longitude = $fromLongitude })
        }
        $distance = Get-DistanceMeters $fromLatitude $fromLongitude $toLatitude $toLongitude
        $steps = [Math]::Max(1, [Math]::Ceiling($distance / $MaximumSegmentMeters))
        for ($step = 1; $step -le $steps; $step++) {
            $ratio = $step / $steps
            $result.Add([pscustomobject]@{
                Latitude = $fromLatitude + ($toLatitude - $fromLatitude) * $ratio
                Longitude = $fromLongitude + ($toLongitude - $fromLongitude) * $ratio
            })
        }
    }
    return $result.ToArray()
}

function New-Milestone {
    param(
        [double] $Fraction,
        [double] $SpeedKph,
        [bool] $Ignition,
        [bool] $Motion,
        [string] $Stage,
        [string] $Action
    )
    return [ordered]@{
        fraction = $Fraction
        speedKph = $SpeedKph
        ignition = $Ignition
        motion = $Motion
        stage = $Stage
        action = $Action
    }
}

function Get-GeofenceRange {
    param([object[]] $Points)
    $inside = for ($index = 0; $index -lt $Points.Count; $index++) {
        $distance = Get-DistanceMeters `
            $Points[$index].Latitude $Points[$index].Longitude -28.2912 -53.4996
        if ($distance -le 170) { $index }
    }
    if (-not $inside) {
        throw 'A rota não atravessa a geocerca de demonstração'
    }
    return @($inside[0], $inside[-1])
}

function Add-RouteMetadata {
    param([object[]] $Points, [object[]] $Milestones, [double] $BaseAltitude)
    $indexedMilestones = @{}
    foreach ($milestone in $Milestones) {
        $index = [Math]::Min($Points.Count - 1, [Math]::Round($milestone.fraction * ($Points.Count - 1)))
        $indexedMilestones[[int] $index] = $milestone
    }

    $state = $Milestones[0]
    $result = [System.Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt $Points.Count; $index++) {
        $stage = $null
        $action = $null
        if ($indexedMilestones.ContainsKey($index)) {
            $state = $indexedMilestones[$index]
            $stage = $state.stage
            $action = $state.action
        }
        $courseFrom = if ($index -lt $Points.Count - 1) { $Points[$index] } else { $Points[$index - 1] }
        $courseTo = if ($index -lt $Points.Count - 1) { $Points[$index + 1] } else { $Points[$index] }
        $point = [ordered]@{
            latitude = [Math]::Round([double] $Points[$index].Latitude, 6)
            longitude = [Math]::Round([double] $Points[$index].Longitude, 6)
            altitude = [Math]::Round($BaseAltitude + 8 * $index / [Math]::Max(1, $Points.Count - 1), 1)
            course = [Math]::Round((Get-Course `
                $courseFrom.Latitude $courseFrom.Longitude $courseTo.Latitude $courseTo.Longitude), 1)
            speedKph = [double] $state.speedKph
            ignition = [bool] $state.ignition
            motion = [bool] $state.motion
        }
        if ($stage) { $point.stage = $stage }
        if ($action) { $point.action = $action }
        $result.Add($point)
    }
    return $result.ToArray()
}

$routeSpecs = [ordered]@{
    urban = [ordered]@{
        name = 'Percurso urbano viário em Panambi'
        waypoints = '-53.501834,-28.292621;-53.499000,-28.292650;-53.499000,-28.290700;-53.501800,-28.290700;-53.501834,-28.292621'
        maximumSegmentMeters = 25
        altitude = 415
        milestones = @(
            (New-Milestone 0.00 0 $true $false 'Ignição ligada'),
            (New-Milestone 0.04 24 $true $true 'Veículo em movimento'),
            (New-Milestone 0.25 34 $true $true 'Curvas urbanas'),
            (New-Milestone 0.52 0 $true $false 'Parada'),
            (New-Milestone 0.58 30 $true $true 'Retomada'),
            (New-Milestone 0.92 18 $true $true 'Desaceleração'),
            (New-Milestone 1.00 0 $false $false 'Ignição desligada')
        )
    }
    highway = [ordered]@{
        name = 'Trecho rodoviário viário em Panambi'
        waypoints = '-53.52900,-28.27820;-53.50590,-28.26540'
        maximumSegmentMeters = 60
        altitude = 430
        milestones = @(
            (New-Milestone 0.00 0 $true $false 'Ignição ligada'),
            (New-Milestone 0.04 52 $true $true 'Acesso à rodovia'),
            (New-Milestone 0.18 86 $true $true 'Velocidade de cruzeiro'),
            (New-Milestone 0.58 82 $true $true 'Curva suave'),
            (New-Milestone 0.86 62 $true $true 'Desaceleração'),
            (New-Milestone 1.00 0 $false $false 'Percurso concluído')
        )
    }
    geofence = [ordered]@{
        name = 'Travessia viária de cerca virtual'
        waypoints = '-53.50230,-28.29130;-53.49960,-28.29116;-53.49680,-28.29100'
        maximumSegmentMeters = 20
        altitude = 416
        dynamicGeofence = $true
    }
    complete = [ordered]@{
        name = 'Demonstração completa viária Kersting GPS'
        waypoints = '-53.50300,-28.29200;-53.50030,-28.29125;-53.49960,-28.29095;-53.49750,-28.29020;-53.49490,-28.28950'
        maximumSegmentMeters = 20
        altitude = 415
        dynamicComplete = $true
    }
}

$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$outputDirectory = Join-Path $repositoryRoot 'src\main\resources\demo\routes'

foreach ($entry in $routeSpecs.GetEnumerator()) {
    $routeId = $entry.Key
    $spec = $entry.Value
    $url = "$RouterUrl/route/v1/driving/$($spec.waypoints)?overview=full&geometries=geojson&steps=false"
    $response = Invoke-RestMethod -Uri $url -Method Get
    if ($response.code -ne 'Ok' -or -not $response.routes[0].geometry.coordinates) {
        throw "Não foi possível calcular a rota $routeId"
    }
    $points = Expand-RoadGeometry $response.routes[0].geometry.coordinates $spec.maximumSegmentMeters

    if ($spec.dynamicGeofence) {
        $range = Get-GeofenceRange $points
        $entryFraction = $range[0] / ($points.Count - 1)
        $exitFraction = [Math]::Min(0.98, ($range[1] + 1) / ($points.Count - 1))
        $middleFraction = ($entryFraction + $exitFraction) / 2
        $spec.milestones = @(
            (New-Milestone 0.00 0 $true $false 'Fora da cerca'),
            (New-Milestone 0.04 30 $true $true 'Veículo em movimento'),
            (New-Milestone $entryFraction 38 $true $true 'Entrada na geocerca'),
            (New-Milestone $middleFraction 0 $true $false 'Permanência na geocerca'),
            (New-Milestone ($middleFraction + ($exitFraction - $middleFraction) * 0.35) 82 $true $true 'Excesso de velocidade'),
            (New-Milestone $exitFraction 46 $true $true 'Saída da geocerca'),
            (New-Milestone 1.00 0 $false $false 'Percurso concluído')
        )
    }

    if ($spec.dynamicComplete) {
        $range = Get-GeofenceRange $points
        $entryFraction = $range[0] / ($points.Count - 1)
        $exitFraction = [Math]::Min(0.78, ($range[1] + 1) / ($points.Count - 1))
        $middleFraction = ($entryFraction + $exitFraction) / 2
        $spec.milestones = @(
            (New-Milestone 0.00 0 $false $false 'Dispositivo online'),
            (New-Milestone 0.03 0 $true $false 'Ignição ligada'),
            (New-Milestone 0.08 28 $true $true 'Veículo em movimento'),
            (New-Milestone $entryFraction 38 $true $true 'Entrada na geocerca'),
            (New-Milestone $middleFraction 0 $true $false 'Parada na geocerca'),
            (New-Milestone ($middleFraction + ($exitFraction - $middleFraction) * 0.35) 82 $true $true 'Excesso de velocidade'),
            (New-Milestone $exitFraction 48 $true $true 'Saída da geocerca'),
            (New-Milestone 0.79 0 $true $false 'Parada'),
            (New-Milestone 0.83 0 $false $false 'Ignição desligada'),
            (New-Milestone 0.87 0 $false $false 'Dispositivo offline' 'offline'),
            (New-Milestone 0.91 24 $true $true 'Dispositivo online'),
            (New-Milestone 1.00 0 $false $false 'Demonstração concluída')
        )
    }

    $routePoints = Add-RouteMetadata $points $spec.milestones $spec.altitude
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('{')
    $lines.Add("  `"id`": `"$routeId`",")
    $lines.Add("  `"name`": $($spec.name | ConvertTo-Json),")
    $lines.Add('  "source": "OpenStreetMap/OSRM",')
    $lines.Add('  "generatedAt": "2026-09-10",')
    $lines.Add("  `"maximumSegmentMeters`": $($spec.maximumSegmentMeters),")
    $lines.Add('  "points": [')
    for ($index = 0; $index -lt $routePoints.Count; $index++) {
        $suffix = if ($index -lt $routePoints.Count - 1) { ',' } else { '' }
        $lines.Add("    $($routePoints[$index] | ConvertTo-Json -Compress)$suffix")
    }
    $lines.Add('  ]')
    $lines.Add('}')
    $outputPath = Join-Path $outputDirectory "$routeId.json"
    [IO.File]::WriteAllText(
        $outputPath, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output "$routeId`: $($routePoints.Count) pontos, $([Math]::Round($response.routes[0].distance)) m"
}
