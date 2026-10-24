param(
    [string]$NativeJar = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Output = '',
    [ValidateSet('both','features','benchmark')][string]$Mode = 'both',
    [string[]]$Providers = @('native','fxsvgimage','jsvg-fx','jsvg-awt'),
    [string]$Counts = '100,500',
    [string]$Kinds = 'path,gradient,smil-transform',
    [ValidateSet('default','on','off')][string]$NativeRenderCache = 'default',
    [double]$WarmupSeconds = 2,
    [double]$MeasureSeconds = 5
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$JavaHome) { $JavaHome = Split-Path (Split-Path (Get-Command java -ErrorAction Stop).Source) }
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
if (!(Test-Path -LiteralPath $javac)) { throw 'A JDK 21+ is required. Set -JavaHome or JAVA_HOME.' }
if (!$NativeJar) { $NativeJar = Join-Path $root 'target/javafx-svg-1.0-SNAPSHOT.jar' }
$NativeJar = (Resolve-Path -LiteralPath $NativeJar).Path
if (!$Output) { $Output = Join-Path $root ('target/svg-library-comparison/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$Output = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $Output) { throw 'Choose a new output directory.' }
foreach ($provider in $Providers) {
    if ($provider -notin @('native','fxsvgimage','jsvg-fx','jsvg-awt')) { throw "Unknown provider: $provider" }
}
$libs = Join-Path $root 'target/svg-comparison-libs'
$classes = Join-Path $root 'target/svg-comparison-classes'
New-Item -ItemType Directory -Path $libs,$classes,$Output -Force | Out-Null
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependencies.json') -Raw | ConvertFrom-Json
foreach ($dependency in $manifest.downloads) {
    $path = Join-Path $libs $dependency.name
    if (!(Test-Path -LiteralPath $path)) { Invoke-WebRequest -Uri $dependency.url -OutFile $path }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $dependency.sha256) {
        throw "Checksum mismatch: $path. Inspect/remove this file before retrying."
    }
}
if (!(Test-Path -LiteralPath (Join-Path $libs 'fxsvgimage/fxsvgImage.jar'))) {
    Expand-Archive -LiteralPath (Join-Path $libs 'fxsvgImage-bin-1.7.3.zip') -DestinationPath (Join-Path $libs 'fxsvgimage')
}
foreach ($dependency in $manifest.unpacked) {
    $path = Join-Path $libs $dependency.name
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $dependency.sha256) { throw "Checksum mismatch: $path" }
}
$jars = @($NativeJar) + @($manifest.classpath | ForEach-Object { Join-Path $libs $_ })
$cp = $jars -join ';'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'SvgLibraryComparison.java'),(Join-Path $PSScriptRoot 'ComparisonMain.java') -Destination $Output
& $javac -encoding UTF-8 --release 21 -cp $cp -d $classes (Join-Path $PSScriptRoot 'ComparisonMain.java') (Join-Path $PSScriptRoot 'SvgLibraryComparison.java')
if ($LASTEXITCODE -ne 0) { throw 'Comparison compilation failed.' }
$cp = $classes + ';' + $cp
Get-FileHash -LiteralPath $jars -Algorithm SHA256 | ForEach-Object { $_.Hash + '  ' + $_.Path } |
    Set-Content -LiteralPath (Join-Path $Output 'jar-sha256.txt') -Encoding utf8
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'dependencies.json') -Destination $Output
$invariant = [Globalization.CultureInfo]::InvariantCulture
foreach ($provider in $Providers) {
    $modes = if ($Mode -eq 'both') { @('features','benchmark') } else { @($Mode) }
    foreach ($currentMode in $modes) {
        $destination = Join-Path $Output "$currentMode/$provider"
        $arguments = @('-Xms512m','-Xmx512m','-Djavafx.animation.pulse=60','--enable-native-access=ALL-UNNAMED',
            ('-Dsvg.nativeRenderCache=' + $NativeRenderCache), '-cp',$cp,'svgbench.ComparisonMain',"--provider=$provider","--output=$destination",
            "--counts=$Counts","--kinds=$Kinds",('--warmup-seconds=' + $WarmupSeconds.ToString($invariant)),
            ('--measure-seconds=' + $MeasureSeconds.ToString($invariant)))
        if ($currentMode -eq 'features') { $arguments += '--probes' }
        Write-Host "$currentMode / $provider"
        $log = Join-Path $Output "$currentMode-$provider.log"
        & $java @arguments *> $log
        if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath (Join-Path $destination 'completed.txt')) -or
            (Test-Path -LiteralPath (Join-Path $destination 'failure.txt'))) {
            Get-Content -LiteralPath $log -Tail 30
            throw "Incomplete run: $destination"
        }
    }
}
$featureRows = @(Get-ChildItem -LiteralPath $Output -Recurse -Filter features.csv | ForEach-Object { Import-Csv -LiteralPath $_.FullName })
if ($featureRows.Count) { $featureRows | Export-Csv -LiteralPath (Join-Path $Output 'features.csv') -NoTypeInformation -Encoding utf8 }
$performanceRows = @(Get-ChildItem -LiteralPath $Output -Recurse -Filter results.csv | ForEach-Object { Import-Csv -LiteralPath $_.FullName })
if ($performanceRows.Count) { $performanceRows | Export-Csv -LiteralPath (Join-Path $Output 'results.csv') -NoTypeInformation -Encoding utf8 }
$creationRows = @(Get-ChildItem -LiteralPath $Output -Recurse -Filter creation.csv | ForEach-Object { Import-Csv -LiteralPath $_.FullName })
if ($creationRows.Count) { $creationRows | Export-Csv -LiteralPath (Join-Path $Output 'creation.csv') -NoTypeInformation -Encoding utf8 }
Write-Host "Complete: $Output. Review feature failures and skipped scenarios before interpreting performance."
