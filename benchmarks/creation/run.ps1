param(
 [string]$BeforeJar='target/svg-verification/remediation/before.jar',
 [string]$AfterJar='target/javafx-svg-1.0-SNAPSHOT.jar',
 [string]$JavaHome='S:/jdk25.0.1_bellsoft',
 [string]$Output='', [int]$Forks=3,
 [int]$WarmupIterations=15, [int]$MeasurementIterations=5, [string]$IterationTime='1s',
 [string[]]$Kinds=@('path','gradient','smil-transform'),
 [string[]]$Phases=@('parse','buildShared','totalMounted'), [int]$Count=500, [switch]$Smoke
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$before=(Resolve-Path -LiteralPath $BeforeJar).Path
$after=(Resolve-Path -LiteralPath $AfterJar).Path
if(!$Output) { $Output=Join-Path $root ('target/svg-creation/'+(Get-Date -Format 'yyyyMMdd-HHmmss')) }
$Output=[IO.Path]::GetFullPath($Output)
if(Test-Path -LiteralPath $Output) { throw 'Choose a fresh output directory' }
if($Forks -lt 1 -or $WarmupIterations -lt 1 -or $MeasurementIterations -lt 1 -or $Count -lt 1 -or $Count -gt 500) { throw 'Invalid measurement settings' }
if($Kinds | Where-Object { $_ -notin @('path','gradient','smil-transform') }) { throw 'Invalid kind' }
if($Phases | Where-Object { $_ -notin @('parse','buildShared','totalMounted') }) { throw 'Invalid phase' }
if($Smoke) { $Forks=1; $WarmupIterations=1; $MeasurementIterations=1; $IterationTime='100ms'; $Count=10; $Kinds=@('path') }
New-Item -ItemType Directory -Path $Output -Force | Out-Null
$classes=Join-Path $Output 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$libs=Join-Path $root 'target/svg-creation-libs'
New-Item -ItemType Directory -Path $libs -Force | Out-Null
$manifest=Get-Content (Join-Path $PSScriptRoot 'dependencies.json') -Raw | ConvertFrom-Json
foreach($dep in $manifest.downloads) {
 $file=Join-Path $libs $dep.name
 if(!(Test-Path -LiteralPath $file)) { Invoke-WebRequest -Uri ('https://repo.maven.apache.org/maven2/'+$dep.path) -OutFile $file }
 if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $dep.sha256) { throw ('Dependency hash mismatch: '+$dep.name) }
}
$existingManifest=Get-Content (Join-Path $root 'benchmarks/comparison/dependencies.json') -Raw | ConvertFrom-Json
$runtimeJars=@($existingManifest.classpath | Where-Object { $_ -match '^javafx-(base|graphics)-|^kotlin-stdlib-' } | ForEach-Object { Join-Path $root ('target/svg-comparison-libs/'+$_) })
if($runtimeJars.Count -ne 3 -or ($runtimeJars | Where-Object { !(Test-Path -LiteralPath $_) })) { throw 'Run comparison/run.ps1 once to provision pinned JavaFX/Kotlin runtime' }
foreach($file in $runtimeJars) {
 $expected=@($existingManifest.downloads | Where-Object name -eq (Split-Path $file -Leaf))[0]
 if(!$expected -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $expected.sha256) { throw ('Runtime hash mismatch: '+$file) }
}
$jmhJars=@($manifest.downloads | ForEach-Object { Join-Path $libs $_.name })
$deps=@($runtimeJars)+$jmhJars
$compileCp=(@($after)+$deps)-join ';'
Copy-Item (Join-Path $PSScriptRoot 'SvgCreationBenchmark.java'),(Join-Path $PSScriptRoot 'dependencies.json'),$PSCommandPath -Destination $Output
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 --release 21 -cp $compileCp -processorpath $compileCp -processor org.openjdk.jmh.generators.BenchmarkProcessor -d $classes (Join-Path $PSScriptRoot 'SvgCreationBenchmark.java')
if($LASTEXITCODE -ne 0) { throw 'JMH benchmark compilation failed' }
Get-FileHash -LiteralPath (@($before,$after)+$deps) -Algorithm SHA256 | ForEach-Object { $_.Hash+'  '+$_.Path } | Set-Content (Join-Path $Output 'sha256.txt') -Encoding utf8
@(
 'Framework=JMH 1.37',
 "smoke=$Smoke; external repeats=$Forks; each run JMH forks=1; threads=1",
 "warmup=$WarmupIterations x $IterationTime; measurement=$MeasurementIterations x $IterationTime; batch=$Count",
 'Every kind/phase/version/repeat uses a fresh child JVM; fixed 512 MiB heap; no concurrent runs.',
 'Version pair order alternates; case order is deterministically shuffled with seed 20261024.',
 'JMH primary score includes FX dispatch, task bookkeeping and retained batch creation.',
 'Raw FX counters exclude dispatch, row bookkeeping and Invocation teardown.',
 'No Stage, autoplay, CSS/layout, paint, snapshot or JFR; not a cold-start/first-paint benchmark.',
 'buildShared reuses a pre-parsed document; totalMounted parses independent documents and includes Scene/host creation.',
 'All warmup and measurement rows, JMH JSON, stdout, sources and hashes are retained.'
) | Set-Content (Join-Path $Output 'protocol.txt') -Encoding utf8
$cases=@()
foreach($kind in $Kinds) { foreach($phase in $Phases) { $cases+=[pscustomobject]@{kind=$kind;phase=$phase} } }
$random=[Random]::new(20261024)
$plan=@()
for($round=1; $round -le $Forks; $round++) {
 $shuffled=@($cases | Sort-Object { $random.Next() })
 for($c=0; $c -lt $shuffled.Count; $c++) {
  $case=$shuffled[$c]
  $labels=if(($round+$c)%2 -eq 0) { @('after','before') } else { @('before','after') }
  foreach($label in $labels) {
   $run="r$round-"+$case.kind+'-'+$case.phase+'-'+$label
   $plan+=[pscustomobject]@{run=$run;round=$round;kind=$case.kind;phase=$case.phase;label=$label}
  }
 }
}
$plan | Export-Csv (Join-Path $Output 'plan.csv') -NoTypeInformation -Encoding utf8
$done=0
foreach($entry in $plan) {
 $runDir=Join-Path $Output $entry.run
 New-Item -ItemType Directory -Path $runDir -Force | Out-Null
 $jar=if($entry.label -eq 'before') { $before } else { $after }
 $cp=(@($classes,$jar)+$deps)-join ';'
 $append="-Xms512m -Xmx512m --enable-native-access=ALL-UNNAMED -Dsvg.create.output=$runDir -Dsvg.create.label="+$entry.label
 $args=@('-cp',$cp,'org.openjdk.jmh.Main',('svgbench.SvgCreationBenchmark.'+$entry.phase+'$'),
  '-p',('kind='+$entry.kind),'-p',("count=$Count"),'-f','1','-t','1','-wi',"$WarmupIterations",
  '-w',$IterationTime,'-i',"$MeasurementIterations",'-r',$IterationTime,'-foe','true',
  '-jvmArgsAppend',$append,'-rf','json','-rff',(Join-Path $runDir 'jmh.json'))
 Write-Host ("START "+($done+1)+"/"+$plan.Count+" "+$entry.run)
 & (Join-Path $JavaHome 'bin/java.exe') @args *> (Join-Path $runDir 'jmh.log')
 if($LASTEXITCODE -ne 0 -or !(Test-Path (Join-Path $runDir 'jmh.json'))) {
  Get-Content (Join-Path $runDir 'jmh.log') -Tail 45
  throw ('JMH run failed: '+$entry.run)
 }
 $result=Get-Content (Join-Path $runDir 'jmh.json') -Raw | ConvertFrom-Json
 if(@($result).Count -ne 1 -or ![double]::IsFinite([double]$result.primaryMetric.score)) { throw 'Invalid JMH result' }
 $raw=@(Get-ChildItem $runDir -Filter '*.csv')
 if($raw.Count -ne 1) { throw ('Missing FX measurements: '+$entry.run) }
 $measurements=@(Import-Csv $raw[0].FullName | Where-Object measurement -eq 'true')
 $iterations=@($measurements | Select-Object -ExpandProperty iteration -Unique)
 if($iterations.Count -ne $MeasurementIterations -or $measurements.Count -lt $MeasurementIterations) { throw 'Incomplete measured iterations' }
 if($measurements | Where-Object { [double]$_.fx_wall_ns -le 0 -or [double]$_.fx_alloc_bytes -le 0 }) { throw 'Invalid FX counter row' }
 $done++
 Write-Host ("DONE "+$done+"/"+$plan.Count+" "+$entry.run+" JMH="+$result.primaryMetric.score+" ms/batch")
}
[IO.File]::WriteAllText((Join-Path $Output 'completed.txt'),"Completed $done independent JMH child JVMs. Check protocol and variance before drawing conclusions."+[Environment]::NewLine)
Write-Host ("Complete: "+$Output)
