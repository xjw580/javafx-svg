param([Parameter(Mandatory=$true)][string]$InputDirectory,[int]$ExpectedRepeats=3,[switch]$AllowSmoke)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $InputDirectory).Path
if(!(Test-Path (Join-Path $root 'completed.txt'))) { throw 'Run is incomplete' }
$protocol=Get-Content (Join-Path $root 'protocol.txt') -Raw
if($protocol -match 'smoke=True' -and !$AllowSmoke) { throw 'Smoke results cannot be used for performance conclusions' }
function Mean($items) { return [double](($items | Measure-Object -Average).Average) }
function Median($items) {
 $sorted=@($items | Sort-Object)
 if(!$sorted.Count) { throw 'Empty statistic' }
 $half=[int][Math]::Floor($sorted.Count/2)
 if($sorted.Count%2) { return [double]$sorted[$half] }
 return ([double]$sorted[$half-1]+[double]$sorted[$half])/2
}
$plan=Import-Csv (Join-Path $root 'plan.csv')
$forkRows=@()
$iterationRows=@()
$warnings=@()
foreach($entry in $plan) {
 $dir=Join-Path $root $entry.run
 $json=Get-Content (Join-Path $dir 'jmh.json') -Raw | ConvertFrom-Json
 $file=@(Get-ChildItem $dir -Filter '*.csv')
 if($file.Count -ne 1) { throw ('Unexpected raw files: '+$entry.run) }
 $raw=Import-Csv $file[0].FullName
 $measured=@($raw | Where-Object measurement -eq 'true')
 if(!$measured.Count) { throw 'No measured samples' }
 $perIteration=@()
 foreach($group in ($raw | Group-Object iteration)) {
  $x=$group.Group
  $row=[pscustomobject]@{
   run=$entry.run;label=$entry.label;kind=$entry.kind;phase=$entry.phase;round=$entry.round
   measurement=$x[0].measurement;iteration=$group.Name;operations=$x.Count
   fx_ms=(Mean @($x | ForEach-Object { [double]$_.fx_wall_ns }))/1e6
   fx_cpu_ms=(Mean @($x | ForEach-Object { [double]$_.fx_cpu_ns }))/1e6
   fx_alloc_mib=(Mean @($x | ForEach-Object { [double]$_.fx_alloc_bytes }))/1048576
   gc_count=($x | ForEach-Object { [long]$_.gc_count } | Measure-Object -Sum).Sum
   gc_ms=($x | ForEach-Object { [long]$_.gc_ms } | Measure-Object -Sum).Sum
  }
  $iterationRows+=$row
  if($row.measurement -eq 'true') { $perIteration+=$row }
 }
 if($perIteration.Count -ne [int]$json.measurementIterations) { throw 'Incomplete measured iterations' }
 $forkRows+=[pscustomobject]@{
  run=$entry.run;label=$entry.label;kind=$entry.kind;phase=$entry.phase;round=$entry.round
  count=$json.params.count;jmh_ms=[double]$json.primaryMetric.score;jmh_error_ms=$json.primaryMetric.scoreError
  fx_ms=(Mean @($perIteration.fx_ms));fx_cpu_ms=(Mean @($perIteration.fx_cpu_ms))
  fx_alloc_mib=(Mean @($perIteration.fx_alloc_mib));operations=$measured.Count
  measured_iterations=$perIteration.Count
 }
 $warm=@($iterationRows | Where-Object { $_.run -eq $entry.run -and $_.measurement -eq 'false' } | Sort-Object { [int]$_.iteration })
 if($warm.Count -ge 2) {
  $lastWarm=Mean @($warm | Select-Object -Last 2 | ForEach-Object fx_ms)
  $firstMeasure=Mean @($perIteration | Select-Object -First 2 | ForEach-Object fx_ms)
  $lastMeasure=Mean @($perIteration | Select-Object -Last 2 | ForEach-Object fx_ms)
  $ratio=$lastMeasure/$firstMeasure
  if([Math]::Abs($ratio-1) -gt .15 -or [Math]::Abs($firstMeasure/$lastWarm-1) -gt .20) {
   $warnings+= "$($entry.run): lastWarm=$lastWarm; firstMeasured=$firstMeasure; lastMeasured=$lastMeasure; inspect GC/compilation/noise; no samples removed."
  }
 }
}
$summary=@()
foreach($group in ($forkRows | Group-Object label,kind,phase)) {
 $items=$group.Group
 if($items.Count -ne $ExpectedRepeats -or @($items.round | Select-Object -Unique).Count -ne $ExpectedRepeats) { throw 'Missing independent repeats' }
 $summary+=[pscustomobject]@{
  label=$items[0].label;kind=$items[0].kind;phase=$items[0].phase;count=$items[0].count
  processes=$items.Count;jmh_ms_median=(Median $items.jmh_ms)
  jmh_ms_min=($items.jmh_ms | Measure-Object -Minimum).Minimum
  jmh_ms_max=($items.jmh_ms | Measure-Object -Maximum).Maximum
  fx_ms_median=(Median $items.fx_ms);fx_cpu_ms_median=(Median $items.fx_cpu_ms)
  fx_alloc_mib_median=(Median $items.fx_alloc_mib)
 }
}
$forkRows | Export-Csv (Join-Path $root 'forks.csv') -NoTypeInformation -Encoding utf8
$iterationRows | Export-Csv (Join-Path $root 'iterations.csv') -NoTypeInformation -Encoding utf8
$summary | Export-Csv (Join-Path $root 'summary.csv') -NoTypeInformation -Encoding utf8
[IO.File]::WriteAllLines((Join-Path $root 'warnings.txt'),[string[]]$warnings,[Text.UTF8Encoding]::new($false))
$summary | Sort-Object phase,kind,label | Format-Table label,kind,phase,processes,jmh_ms_median,jmh_ms_min,jmh_ms_max,fx_alloc_mib_median -AutoSize
Write-Host ("Warmup/measurement trend flags (retained for review): "+$warnings.Count)
