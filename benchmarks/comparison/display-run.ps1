param(
    [string]$NativeJar='',
    [string]$BaselineNativeJar='',
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$Output='',
    [string[]]$Providers=@('native','fxsvgimage','jsvg-fx'),
    [int]$Repeats=3,
    [int]$Count=500,
    [int]$WarmupBatches=5,
    [int]$MeasureBatches=5,
    [double]$RasterWarmupSeconds=10,
    [double]$IdleWarmupSeconds=3,
    [double]$IdleMeasureSeconds=5,
    [switch]$RecordJfr,
    [switch]$Smoke
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(!$JavaHome){throw 'Set JavaHome'}
if(!$NativeJar){$NativeJar=Join-Path $root 'target/javafx-svg-1.0-SNAPSHOT.jar'}
$NativeJar=(Resolve-Path -LiteralPath $NativeJar).Path
if($BaselineNativeJar){$BaselineNativeJar=(Resolve-Path -LiteralPath $BaselineNativeJar).Path}
if($Providers -contains 'native-before' -and !$BaselineNativeJar){throw 'native-before requires BaselineNativeJar'}
if(@($Providers|Select-Object -Unique).Count -ne $Providers.Count){throw 'Duplicate providers'}
if(!$Output){$Output=Join-Path $root ('target/svg-display-comparison/'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
$Output=[IO.Path]::GetFullPath($Output)
if(Test-Path -LiteralPath $Output){throw 'Choose a fresh output directory'}
if($Smoke){$Repeats=1;$Count=10;$WarmupBatches=1;$MeasureBatches=1;$RasterWarmupSeconds=0;$IdleWarmupSeconds=1;$IdleMeasureSeconds=1}
if($Repeats -lt 1 -or $Count -lt 1 -or $Count -gt 500){throw 'Invalid protocol'}
foreach($provider in $Providers){if($provider -notin @('native','native-before','fxsvgimage','jsvg-fx')){throw 'Unknown provider'}}
$manifest=Get-Content (Join-Path $PSScriptRoot 'dependencies.json') -Raw | ConvertFrom-Json
$libs=Join-Path $root 'target/svg-comparison-libs'
foreach($dependency in @($manifest.downloads)+@($manifest.unpacked)){
    $file=Join-Path $libs $dependency.name
    if(!(Test-Path $file) -or (Get-FileHash $file).Hash -ne $dependency.sha256){throw ('Prepare verified comparison dependencies first: '+$file)}
}
$classes=Join-Path $Output 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$jars=@($NativeJar)+@($manifest.classpath | ForEach-Object {Join-Path $libs $_})
$cp=$jars -join ';'
$baselineCp=if($BaselineNativeJar){(@($BaselineNativeJar)+@($manifest.classpath | ForEach-Object {Join-Path $libs $_})) -join ';'}else{''}
Copy-Item (Join-Path $PSScriptRoot 'SvgDisplayComparison.java'),(Join-Path $PSScriptRoot 'DisplayMain.java'),(Join-Path $PSScriptRoot 'display-run.ps1') $Output
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 --release 21 -cp $cp -d $classes (Join-Path $PSScriptRoot 'SvgDisplayComparison.java') (Join-Path $PSScriptRoot 'DisplayMain.java')
if($LASTEXITCODE -ne 0){throw 'Compilation failed'}
$jars | ForEach-Object { $file=Get-FileHash $_; $file.Hash+'  '+$file.Path } | Set-Content (Join-Path $Output 'sha256.txt') -Encoding utf8
if($BaselineNativeJar){$file=Get-FileHash $BaselineNativeJar; Add-Content (Join-Path $Output 'sha256.txt') ($file.Hash+'  '+$file.Path)}
Copy-Item (Join-Path $PSScriptRoot 'dependencies.json') $Output
@(
    "smoke=$Smoke; count=$Count; independent JVM repeats=$Repeats"
    "Raster warmup at least $WarmupBatches batches AND $RasterWarmupSeconds s; measurement=$MeasureBatches batches; idle warmup=$IdleWarmupSeconds s; idle measurement=$IdleMeasureSeconds s."
    "Sequential fresh JVMs, fixed 512 MiB heap, same full classpath, JFR=$RecordJfr; no forced GC."
    'Provider order rotates, kinds reverse on even rounds. Every warmup/measurement row is retained.'
    'native-before uses the native API with BaselineNativeJar substituted; dependencies and measurement code are identical; each native JAR hash is recorded in plan.csv.'
    'Raster readiness includes asynchronous offscreen snapshot and readback, not screen presentation.'
    'Idle has no AnimationTimer; worker samples CPU/allocation once at interval end.'
) | Set-Content (Join-Path $Output 'protocol.txt') -Encoding utf8
$plan=@()
for($r=1;$r -le $Repeats;$r++){
    for($p=0;$p -lt $Providers.Count;$p++){
        $provider=$Providers[($p+$r-1)%$Providers.Count]
        $name="r$r-$provider"
        $kinds=if($r%2){'path,gradient'}else{'gradient,path'}
        $jar=if($provider -eq 'native-before'){$BaselineNativeJar}else{$NativeJar}
        $plan+=[pscustomobject]@{round=$r;provider=$provider;run=$name;kinds=$kinds;native_jar=$jar;native_sha256=(Get-FileHash $jar).Hash}
    }
}
$plan | Export-Csv (Join-Path $Output 'plan.csv') -NoTypeInformation -Encoding utf8
$culture=[Globalization.CultureInfo]::InvariantCulture
foreach($run in $plan){
    $destination=Join-Path $Output $run.run
    Write-Host $run.run
    $runCp=if($run.provider -eq 'native-before'){$baselineCp}else{$cp}
    & (Join-Path $JavaHome 'bin/java.exe') -Xms512m -Xmx512m --enable-native-access=ALL-UNNAMED -cp ($classes+';'+$runCp) svgbench.DisplayMain ("--jfr="+$RecordJfr.ToString().ToLowerInvariant()) ("--provider="+$run.provider) ("--output="+$destination) ("--kinds="+$run.kinds) ("--count="+$Count) ("--warmups="+$WarmupBatches) ("--measurements="+$MeasureBatches) ("--raster-warmup="+$RasterWarmupSeconds.ToString($culture)) ("--idle-warmup="+$IdleWarmupSeconds.ToString($culture)) ("--idle-measure="+$IdleMeasureSeconds.ToString($culture)) *> (Join-Path $Output ($run.run+'.log'))
    if($LASTEXITCODE -ne 0 -or !(Test-Path (Join-Path $destination 'completed.txt')) -or (Test-Path (Join-Path $destination 'failure.txt'))){
        Get-Content (Join-Path $Output ($run.run+'.log')) -Tail 25
        throw ('Incomplete run: '+$run.run)
    }
}
Set-Content (Join-Path $Output 'completed.txt') 'All independent runs completed; inspect raw rows and focus flags.'
Write-Host ('Complete: '+$Output)
