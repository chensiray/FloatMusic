#Requires -Version 7.4
<#
.SYNOPSIS
Exports the approved FloatMusic bitmap designs to application icon formats.
.NOTES
Only scales/formats the approved sources. The round source's almost transparent
outer fragments are removed by a documented alpha-only circular clip. No RGB
painting, recoloring, sharpening or generative reconstruction is performed.
#>
[CmdletBinding()]
param(
    [string]$AppSource,
    [string]$RoundSource,
    [string]$OutputDir
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if (-not $AppSource) { $AppSource = Join-Path $repoRoot 'artifacts/icon-design-2026-10-19/floatmusic-pearl-icon-v1.png' }
if (-not $RoundSource) { $RoundSource = Join-Path $repoRoot 'artifacts/icon-design-2026-10-19/floatmusic-round-icon-v1.png' }
if (-not $OutputDir) { $OutputDir = Join-Path $repoRoot 'assets/icons' }
$AppSource = [IO.Path]::GetFullPath($AppSource)
$RoundSource = [IO.Path]::GetFullPath($RoundSource)
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
foreach ($source in @($AppSource,$RoundSource)) {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Approved source is missing: $source" }
    if (([IO.Path]::GetDirectoryName($source)).Equals($OutputDir,[StringComparison]::OrdinalIgnoreCase)) { throw 'Keep original sources separate from derived assets.' }
}
Add-Type -AssemblyName System.Drawing
if (-not ('FloatMusicIconPixels' -as [type])) {
    $drawingReferences = @('System.Drawing.Common','System.Drawing.Primitives','System.Private.Windows.GdiPlus','System.Private.Windows.Core','System.Runtime','System.Runtime.InteropServices') |
        ForEach-Object { [Reflection.Assembly]::Load($_).Location }
    Add-Type -ReferencedAssemblies $drawingReferences -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
public static class FloatMusicIconPixels {
    // Distances are in original source-pixel coordinates, at pixel centres.
    // Preserve alpha for r <= 583.5; fade for 583.5 < r < 584.5; zero outside.
    public static long[] ClipRound(Bitmap bitmap, double cx, double cy) {
        var area = new Rectangle(0, 0, bitmap.Width, bitmap.Height);
        var locked = bitmap.LockBits(area, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
        long changed = 0, zeroed = 0, opaqueChanged = 0, maxRemovedAlpha = 0;
        try {
            int stride = locked.Stride;
            if (stride <= 0) throw new InvalidOperationException("Expected a top-down bitmap.");
            byte[] pixels = new byte[stride * bitmap.Height];
            Marshal.Copy(locked.Scan0, pixels, 0, pixels.Length);
            for (int y = 0; y < bitmap.Height; ++y) for (int x = 0; x < bitmap.Width; ++x) {
                double dx = x - cx, dy = y - cy, distance = Math.Sqrt(dx * dx + dy * dy);
                if (distance <= 583.5) continue;
                int alphaIndex = y * stride + x * 4 + 3;
                byte original = pixels[alphaIndex];
                double coverage = Math.Max(0.0, Math.Min(1.0, 584.5 - distance));
                byte clipped = (byte)Math.Round(original * coverage, MidpointRounding.AwayFromZero);
                if (clipped == original) continue;
                pixels[alphaIndex] = clipped;
                ++changed;
                if (original > 250) ++opaqueChanged;
                if (clipped == 0) { ++zeroed; maxRemovedAlpha = Math.Max(maxRemovedAlpha, original); }
            }
            Marshal.Copy(pixels, 0, locked.Scan0, pixels.Length);
        } finally { bitmap.UnlockBits(locked); }
        return new long[] { changed, zeroed, opaqueChanged, maxRemovedAlpha };
    }
    // Classic 32-bit DIB plus AND mask, including the 256-pixel entry.
    public static byte[] IconDib(Bitmap bitmap) {
        int size = bitmap.Width, andStride = ((size + 31) / 32) * 4;
        var area = new Rectangle(0, 0, size, size);
        var locked = bitmap.LockBits(area, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        byte[] pixels;
        try {
            pixels = new byte[locked.Stride * size];
            Marshal.Copy(locked.Scan0, pixels, 0, pixels.Length);
        } finally { bitmap.UnlockBits(locked); }
        using (var stream = new MemoryStream()) using (var writer = new BinaryWriter(stream)) {
            writer.Write(40); writer.Write(size); writer.Write(size * 2);
            writer.Write((ushort)1); writer.Write((ushort)32); writer.Write(0);
            writer.Write(size * size * 4); writer.Write(0); writer.Write(0); writer.Write(0); writer.Write(0);
            for (int y = size - 1; y >= 0; --y) writer.Write(pixels, y * size * 4, size * 4);
            for (int y = size - 1; y >= 0; --y) {
                var mask = new byte[andStride];
                for (int x = 0; x < size; ++x) if (pixels[(y * size + x) * 4 + 3] == 0) mask[x / 8] |= (byte)(0x80 >> (x % 8));
                writer.Write(mask);
            }
            return stream.ToArray();
        }
    }
}
'@
}
function New-ScaledBitmap([Drawing.Image]$Source,[int]$Size) {
    $bitmap = [Drawing.Bitmap]::new($Size,$Size,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $attributes = [Drawing.Imaging.ImageAttributes]::new()
    try {
        $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
        $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $graphics.DrawImage($Source,[Drawing.Rectangle]::new(0,0,$Size,$Size),0,0,$Source.Width,$Source.Height,[Drawing.GraphicsUnit]::Pixel,$attributes)
    } finally { $attributes.Dispose(); $graphics.Dispose() }
    return $bitmap
}
function Write-MultiSizeIcon([Drawing.Image]$Source,[string]$Path) {
    $entries = @()
    foreach ($size in @(16,24,32,48,64,128,256)) {
        $bitmap = New-ScaledBitmap $Source $size
        try {
            $payload = [FloatMusicIconPixels]::IconDib($bitmap)
            $entries += [pscustomobject]@{ Size = $size; Bytes = $payload }
        } finally { $bitmap.Dispose() }
    }
    $stream = [IO.File]::Create($Path)
    $writer = [IO.BinaryWriter]::new($stream)
    try {
        $writer.Write([ushort]0); $writer.Write([ushort]1); $writer.Write([ushort]$entries.Count)
        $offset = 6 + 16 * $entries.Count
        foreach ($entry in $entries) {
            $dimension = if ($entry.Size -eq 256) { 0 } else { $entry.Size }
            $writer.Write([byte]$dimension); $writer.Write([byte]$dimension); $writer.Write([byte]0); $writer.Write([byte]0)
            $writer.Write([ushort]1); $writer.Write([ushort]32); $writer.Write([uint32]$entry.Bytes.Length); $writer.Write([uint32]$offset)
            $offset += $entry.Bytes.Length
        }
        foreach ($entry in $entries) { $writer.Write([byte[]]$entry.Bytes) }
    } finally { $writer.Dispose(); $stream.Dispose() }
}
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$app = [Drawing.Bitmap]::new($AppSource)
$roundOriginal = [Drawing.Bitmap]::new($RoundSource)
$round = $null
try {
    if ($app.Width -ne $app.Height) { throw 'The approved application source must be square.' }
    if ($roundOriginal.Width -ne 1254 -or $roundOriginal.Height -ne 1254) { throw 'The documented round alpha clip is specific to the approved 1254 x 1254 source.' }
    $round = $roundOriginal.Clone([Drawing.Rectangle]::new(0,0,1254,1254),[Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $clipStats = [FloatMusicIconPixels]::ClipRound($round,626.5,626.5)
    if ($clipStats[2] -ne 0) { throw 'The technical alpha clip would damage an opaque disk pixel. Reinspect the source.' }
    $appExport = New-ScaledBitmap $app 1024
    $floatingExport = New-ScaledBitmap $round 512
    try {
        $appExport.Save((Join-Path $OutputDir 'floatmusic-app.png'),[Drawing.Imaging.ImageFormat]::Png)
        $floatingExport.Save((Join-Path $OutputDir 'floatmusic-floating.png'),[Drawing.Imaging.ImageFormat]::Png)
    } finally { $appExport.Dispose(); $floatingExport.Dispose() }
    Write-MultiSizeIcon $app (Join-Path $OutputDir 'floatmusic-app.ico')
    $metadata = [ordered]@{
        application = [ordered]@{ source = 'artifacts/icon-design-2026-10-19/floatmusic-pearl-icon-v1.png'; sourceSha256 = (Get-FileHash -LiteralPath $AppSource -Algorithm SHA256).Hash.ToLowerInvariant(); sourceSize = @($app.Width,$app.Height); outputSize = @(1024,1024); crop = 'none'; artEdits = 'none' }
        floating = [ordered]@{ source = 'artifacts/icon-design-2026-10-19/floatmusic-round-icon-v1.png'; sourceSha256 = (Get-FileHash -LiteralPath $RoundSource -Algorithm SHA256).Hash.ToLowerInvariant(); sourceSize = @(1254,1254); outputSize = @(512,512); circleCenter = @(626.5,626.5); circleRadius = 584; alphaTransition = @(583.5,584.5); alphaFormula = 'source alpha * clamp(584.5 - distance, 0, 1)'; rgbEdits = 'none'; changedAlphaPixels = $clipStats[0]; zeroedAlphaPixels = $clipStats[1]; changedOpaquePixels = $clipStats[2]; highestZeroedSourceAlpha = $clipStats[3] }
        scaling = 'System.Drawing HighQualityBicubic; SourceCopy; HighQuality pixel offset; TileFlipXY edge wrap'
        windowsIcon = [ordered]@{ sizes = @(16,24,32,48,64,128,256); encoding = '32-bit BGRA DIB + AND mask for every entry' }
    }
    $metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputDir 'icon-derivation.json') -Encoding utf8
    Write-Output "Prepared application PNG, floating PNG and seven-resolution ICO in: $OutputDir"
    Write-Output "Round alpha cleanup: $($clipStats[0]) pixels adjusted, $($clipStats[2]) opaque pixels affected."
} finally { if ($round) { $round.Dispose() }; $roundOriginal.Dispose(); $app.Dispose() }
