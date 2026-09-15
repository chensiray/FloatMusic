param([string]$Destination = (Join-Path (Split-Path -Parent $PSScriptRoot) 'artifacts/demo-60s.wav'))
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
$rate = 16000
$seconds = 60
$samples = $rate * $seconds
$stream = [IO.File]::Create($Destination)
$writer = [IO.BinaryWriter]::new($stream)
try {
    $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF'))
    $writer.Write([uint32](36 + $samples * 2))
    $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
    $writer.Write([uint32]16)
    $writer.Write([uint16]1)
    $writer.Write([uint16]1)
    $writer.Write([uint32]$rate)
    $writer.Write([uint32]($rate * 2))
    $writer.Write([uint16]2)
    $writer.Write([uint16]16)
    $writer.Write([Text.Encoding]::ASCII.GetBytes('data'))
    $writer.Write([uint32]($samples * 2))
    for ($i = 0; $i -lt $samples; $i++) {
        $t = $i / [double]$rate
        $pulse = $t % 2
        $envelope = if ($pulse -lt 0.4) { [Math]::Sin([Math]::PI * $pulse / 0.4) } else { 0 }
        $writer.Write([int16](1200 * $envelope * [Math]::Sin(2 * [Math]::PI * 440 * $t)))
    }
} finally { $writer.Dispose() }
Write-Output "Created $Destination"
