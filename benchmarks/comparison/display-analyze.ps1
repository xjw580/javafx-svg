param([Parameter(Mandatory=$true)][string]$InputDirectory,[int]$ExpectedRepeats=3,[switch]$AllowSmoke,[switch]$AllowJfr)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $InputDirectory).Path
if(!(Test-Path (Join-Path $root 'completed.txt'))){throw 'Incomplete run'}
$protocol=Get-Content (Join-Path $root 'protocol.txt') -Raw
if($protocol -match 'smoke=True' -and !$AllowSmoke){throw 'Smoke cannot support performance conclusions'}
if($protocol -match 'JFR=True' -and !$AllowJfr){throw 'JFR diagnostics cannot be mixed with unrecorded performance comparisons; use AllowJfr explicitly for diagnostic summaries'}
function Median($items){
    $numbers=@($items|ForEach-Object {[double]$_}|Sort-Object)
    if(!$numbers.Count){return $null}
    $middle=[int][Math]::Floor($numbers.Count/2)
    if($numbers.Count%2){return $numbers[$middle]}
    return ($numbers[$middle-1]+$numbers[$middle])/2
}
function Mean($items){return [double](($items|Measure-Object -Average).Average)}
$plan=Import-Csv (Join-Path $root 'plan.csv')
$rasterAll=@();$idleAll=@();$forks=@();$warnings=@()
foreach($run in $plan){
    $dir=Join-Path $root $run.run
    if(!(Test-Path (Join-Path $dir 'completed.txt')) -or (Test-Path (Join-Path $dir 'failure.txt'))){throw ('Incomplete child: '+$run.run)}
    $environment=Get-Content (Join-Path $dir 'environment.txt') -Raw
    $minimumWarmup=[int]([regex]::Match($environment,'minimum warmup batches=(\d+)').Groups[1].Value)
    $measuredCount=[int]([regex]::Match($environment,'measured batches=(\d+)').Groups[1].Value)
    $raster=@(Import-Csv (Join-Path $dir 'raster.csv'))
    $idle=@(Import-Csv (Join-Path $dir 'idle.csv'))
    foreach($row in $raster){$row|Add-Member run $run.run;$row|Add-Member round $run.round;$rasterAll+=$row}
    foreach($row in $idle){
        $row|Add-Member run $run.run;$row|Add-Member round $run.round
        $row|Add-Member eligible ($row.focus_start -eq 'true' -and $row.focus_end -eq 'true' -and [int]$row.focus_losses -eq 0 -and $row.iconified_start -eq 'false' -and $row.iconified_end -eq 'false')
        $idleAll+=$row
    }
    foreach($kind in ($run.kinds -split ',')){
        $rows=@($raster|Where-Object kind -eq $kind)
        if(@($rows.provider|Select-Object -Unique).Count -ne 1 -or $rows[0].provider -ne $run.provider){throw 'Provider mismatch'}
        $warm=@($rows|Where-Object warmup -eq 'true')
        $measured=@($rows|Where-Object warmup -eq 'false')
        if($warm.Count -lt $minimumWarmup -or $measured.Count -ne $measuredCount){throw ('Batch count mismatch: '+$run.run+'/'+$kind)}
        if(@($idle|Where-Object kind -eq $kind).Count -ne 1){throw 'Idle case count mismatch'}
        if($measured.Count -ge 4){
            $early=Mean @($measured|Select-Object -First 2|ForEach-Object {[double]$_.wall_ms})
            $late=Mean @($measured|Select-Object -Last 2|ForEach-Object {[double]$_.wall_ms})
            $lastWarm=Mean @($warm|Select-Object -Last 2|ForEach-Object {[double]$_.wall_ms})
            if([Math]::Abs($late/$early-1) -gt .15 -or [Math]::Abs($early/$lastWarm-1) -gt .20){
                $warnings+=("$($run.run)/$kind : last warm=$lastWarm; early measured=$early; late measured=$late; inspect GC/JIT/noise; no rows removed.")
            }
        }
        $forks+=[pscustomobject]@{
            provider=$run.provider;run=$run.run;round=$run.round;kind=$kind;icons=$rows[0].icons
            warmup_batches=$warm.Count;measured_batches=$measured.Count
            wall_ms=(Median $measured.wall_ms)
            wall_min=($measured.wall_ms|ForEach-Object {[double]$_}|Measure-Object -Minimum).Minimum
            wall_max=($measured.wall_ms|ForEach-Object {[double]$_}|Measure-Object -Maximum).Maximum
            fx_cpu_ms=(Median $measured.fx_cpu_ms);process_cpu_ms=(Median $measured.process_cpu_ms)
            fx_alloc_mib=(Median $measured.fx_alloc_mib);total_alloc_mib=(Median $measured.total_alloc_mib)
            gc_count=($measured.gc_count|ForEach-Object {[long]$_}|Measure-Object -Sum).Sum
            gc_ms=($measured.gc_ms|ForEach-Object {[long]$_}|Measure-Object -Sum).Sum
        }
    }
}
$rasterSummary=@();$idleSummary=@()
foreach($group in ($forks|Group-Object provider,kind)){
    $rows=$group.Group
    if($rows.Count -ne $ExpectedRepeats -or @($rows.round|Select-Object -Unique).Count -ne $ExpectedRepeats){throw 'Missing independent raster repeats'}
    $rasterSummary+=[pscustomobject]@{
        provider=$rows[0].provider;kind=$rows[0].kind;icons=$rows[0].icons;processes=$rows.Count
        wall_ms=(Median $rows.wall_ms)
        wall_min=($rows.wall_ms|Measure-Object -Minimum).Minimum
        wall_max=($rows.wall_ms|Measure-Object -Maximum).Maximum
        fx_cpu_ms=(Median $rows.fx_cpu_ms);process_cpu_ms=(Median $rows.process_cpu_ms)
        fx_alloc_mib=(Median $rows.fx_alloc_mib);total_alloc_mib=(Median $rows.total_alloc_mib)
    }
}
foreach($group in ($idleAll|Group-Object provider,kind)){
    $rows=$group.Group
    if($rows.Count -ne $ExpectedRepeats -or @($rows.round|Select-Object -Unique).Count -ne $ExpectedRepeats){throw 'Missing independent idle repeats'}
    $valid=@($rows|Where-Object eligible)
    $row=[pscustomobject]@{
        provider=$rows[0].provider;kind=$rows[0].kind;icons=$rows[0].icons;valid=$valid.Count;attempts=$rows.Count
        rounds=($valid.run -join '+');fx_cpu_ms_s=$null;cpu_min=$null;cpu_max=$null
        fx_alloc_kib_s=$null;alloc_min=$null;alloc_max=$null;process_cpu_core_pct=$null
    }
    if($valid.Count){
        $row.fx_cpu_ms_s=Median $valid.fx_cpu_ms_s
        $row.cpu_min=($valid.fx_cpu_ms_s|ForEach-Object{[double]$_}|Measure-Object -Minimum).Minimum
        $row.cpu_max=($valid.fx_cpu_ms_s|ForEach-Object{[double]$_}|Measure-Object -Maximum).Maximum
        $row.fx_alloc_kib_s=Median $valid.fx_alloc_kib_s
        $row.alloc_min=($valid.fx_alloc_kib_s|ForEach-Object{[double]$_}|Measure-Object -Minimum).Minimum
        $row.alloc_max=($valid.fx_alloc_kib_s|ForEach-Object{[double]$_}|Measure-Object -Maximum).Maximum
        $row.process_cpu_core_pct=Median $valid.process_cpu_core_pct
    }
    $idleSummary+=$row
}
$rasterAll|Export-Csv (Join-Path $root 'raster-all.csv') -NoTypeInformation -Encoding utf8
$idleAll|Export-Csv (Join-Path $root 'idle-all.csv') -NoTypeInformation -Encoding utf8
$forks|Export-Csv (Join-Path $root 'raster-forks.csv') -NoTypeInformation -Encoding utf8
$rasterSummary|Export-Csv (Join-Path $root 'raster-summary.csv') -NoTypeInformation -Encoding utf8
$idleSummary|Export-Csv (Join-Path $root 'idle-summary.csv') -NoTypeInformation -Encoding utf8
[IO.File]::WriteAllLines((Join-Path $root 'warnings.txt'),[string[]]$warnings,[Text.UTF8Encoding]::new($false))
$rasterSummary|Format-Table -AutoSize
$idleSummary|Format-Table -AutoSize
Write-Host ('Trend flags retained: '+$warnings.Count)
