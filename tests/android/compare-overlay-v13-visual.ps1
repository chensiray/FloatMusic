param(
    [Parameter(Mandatory = $true)][string]$BaselineDir,
    [Parameter(Mandatory = $true)][string]$CandidateDir,
    [string]$OutputFile
)
$ErrorActionPreference = 'Stop'
$BaselineDir = [IO.Path]::GetFullPath($BaselineDir)
$CandidateDir = [IO.Path]::GetFullPath($CandidateDir)
if (-not $OutputFile) { $OutputFile = Join-Path $CandidateDir 'visual-comparison.json' }
Add-Type -AssemblyName System.Drawing
$scenes = @(foreach ($theme in @('light','dark')) { foreach ($page in @('compact','lyrics','playlist','settings','popup','long-list')) { "$theme-$page" } })
$results = @()
function Test-ParticipatesInLayout([string]$Path, [hashtable]$Nodes) {
    $ancestor = $Path
    while ($ancestor) {
        if ($Nodes.ContainsKey($ancestor) -and $Nodes[$ancestor].visibility -eq 8) { return $false }
        $separator = $ancestor.LastIndexOf('/')
        if ($separator -lt 0) { break }
        $ancestor = $ancestor.Substring(0,$separator)
    }
    return $true
}
foreach ($scene in $scenes) {
    $baseline = Get-Content -LiteralPath (Join-Path $BaselineDir "$scene.geometry.json") -Raw | ConvertFrom-Json -AsHashtable
    $candidate = Get-Content -LiteralPath (Join-Path $CandidateDir "$scene.geometry.json") -Raw | ConvertFrom-Json -AsHashtable
    $changes = [System.Collections.Generic.List[string]]::new()
    $cachedGoneBounds = [System.Collections.Generic.List[string]]::new()
    $beforeByPath = @{}
    $afterByPath = @{}
    foreach ($node in $baseline.nodes) { $beforeByPath[$node.path] = $node }
    foreach ($node in $candidate.nodes) { $afterByPath[$node.path] = $node }
    foreach ($key in @('density','scaledDensity','scale')) {
        if ($baseline[$key] -ne $candidate[$key]) { $changes.Add("metadata.$key") }
    }
    if ($baseline.nodes.Count -ne $candidate.nodes.Count) { $changes.Add("node count: $($baseline.nodes.Count) -> $($candidate.nodes.Count)") }
    for ($index = 0; $index -lt [Math]::Min($baseline.nodes.Count,$candidate.nodes.Count); $index++) {
        $before = $baseline.nodes[$index]
        $after = $candidate.nodes[$index]
        foreach ($key in @('path','class','bounds','padding','minimum','layout','margins','visibility','clickable','longClickable','enabled','focusable','scroll','textSize','gravity','maxLines','fontPadding')) {
            $beforeValue = ConvertTo-Json -InputObject $before[$key] -Compress -Depth 20
            $afterValue = ConvertTo-Json -InputObject $after[$key] -Compress -Depth 20
            if ($beforeValue -cne $afterValue) {
                $description = "$($before.path).${key}: $beforeValue -> $afterValue"
                # Android skips layout of GONE subtrees; their last bounds are cached.
                # Keep every declared layout/touch property strict, with no tolerance.
                if ($key -eq 'bounds' -and -not (Test-ParticipatesInLayout $before.path $beforeByPath) -and -not (Test-ParticipatesInLayout $after.path $afterByPath)) { $cachedGoneBounds.Add($description) }
                else { $changes.Add($description) }
            }
        }
    }
    $pixels = $null
    $beforePng = Join-Path $BaselineDir "$scene.png"
    $afterPng = Join-Path $CandidateDir "$scene.png"
    if ((Test-Path -LiteralPath $beforePng) -and (Test-Path -LiteralPath $afterPng)) {
        $beforeImage = [Drawing.Bitmap]::new($beforePng)
        $afterImage = [Drawing.Bitmap]::new($afterPng)
        try {
            if ($beforeImage.Width -ne $afterImage.Width -or $beforeImage.Height -ne $afterImage.Height) { $changes.Add('native bitmap dimensions changed') }
            $sampleCount = 0
            $changedSamples = 0
            [double]$difference = 0
            for ($y = 8; $y -lt [Math]::Min($beforeImage.Height,$afterImage.Height); $y += 16) {
                for ($x = 8; $x -lt [Math]::Min($beforeImage.Width,$afterImage.Width); $x += 16) {
                    $a = $beforeImage.GetPixel($x,$y)
                    $b = $afterImage.GetPixel($x,$y)
                    if ($a.ToArgb() -ne $b.ToArgb()) { $changedSamples++ }
                    $difference += [Math]::Abs([int]$a.R-$b.R) + [Math]::Abs([int]$a.G-$b.G) + [Math]::Abs([int]$a.B-$b.B)
                    $sampleCount++
                }
            }
            $pixels = [ordered]@{ width=$afterImage.Width; height=$afterImage.Height; sampleStep=16; samples=$sampleCount; changedSamples=$changedSamples; changedFraction=[Math]::Round($changedSamples/[double]$sampleCount,6); meanAbsoluteRgbDifference=[Math]::Round($difference/($sampleCount*3),3) }
        } finally { $beforeImage.Dispose(); $afterImage.Dispose() }
    }
    $result = [ordered]@{ scene=$scene; geometryUnchanged=($changes.Count -eq 0); strictIncludingGoneCachedBoundsUnchanged=($changes.Count -eq 0 -and $cachedGoneBounds.Count -eq 0); baselineNodes=$baseline.nodes.Count; candidateNodes=$candidate.nodes.Count; changes=@($changes); goneCachedBoundsChanges=@($cachedGoneBounds); sampledPixels=$pixels }
    $results += $result
    Write-Output "$scene geometryUnchanged=$($result.geometryUnchanged) nodes=$($result.candidateNodes) goneCachedBoundsChanges=$($cachedGoneBounds.Count) changedSamples=$($pixels.changedSamples)/$($pixels.samples)"
}
$allGeometryUnchanged = @($results | Where-Object { -not $_.geometryUnchanged }).Count -eq 0
[ordered]@{ baseline=$BaselineDir; candidate=$CandidateDir; comparisonPolicy='Exact properties; only cached bounds of subtrees GONE in both captures are reported separately. All layout declarations and touch properties remain strict.'; allGeometryUnchanged=$allGeometryUnchanged; scenes=$results } | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $OutputFile -Encoding utf8
if (-not $allGeometryUnchanged) { throw "Native geometry or touch configuration changed. See $OutputFile" }
Write-Output "All twelve native view trees retain identical participating geometry and declared touch/layout configuration. GONE cached bounds are listed separately. Report: $OutputFile"
