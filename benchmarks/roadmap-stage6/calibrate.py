#!/usr/bin/env python3
"""Run identical final binaries under external blind labels; retain all samples and failed screens.
No optimizer or hypothesis about a code change is part of this calibration.
"""
import argparse,csv,hashlib,json,os,pathlib,platform,statistics,subprocess,time
p=argparse.ArgumentParser();p.add_argument('--project',type=pathlib.Path,required=True);p.add_argument('--classpath-file',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--pairs',type=int,default=4);a=p.parse_args()
assert 2<=a.pairs<=12
project=a.project.resolve();a.output.mkdir(parents=True,exist_ok=True)
jar=project/'target/javafx-svg-1.0-SNAPSHOT.jar'
assert jar.is_file()
jar_sha=hashlib.sha256(jar.read_bytes()).hexdigest()
def test_tree_hash():
    digest=hashlib.sha256()
    for f in sorted((project/'target/test-classes').rglob('*')):
        if f.is_file():
            digest.update(str(f.relative_to(project/'target/test-classes')).encode());digest.update(b'\0');digest.update(f.read_bytes())
    return digest.hexdigest()
test_sha=test_tree_hash()
cp=os.pathsep.join([str(jar),str(project/'target/test-classes'),a.classpath_file.resolve().read_text().strip()])
command=['java','-Xms256m','-Xmx768m','-Dglass.platform=Monocle','-Dmonocle.platform=Headless','-Dprism.order=sw',f'-Djavafx.cachedir={a.output.resolve()/"javafx-cache"}','-cp',cp,'club.xiaojiawei.svg.SvgRoadmapCalibration']
manifest={'jar_sha256':jar_sha,'test_classes_sha256':test_sha,'command':command,'cwd':str(project),'host':platform.platform(),'environment':{k:os.environ.get(k) for k in ['XDG_CACHE_HOME','JAVA_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','DISPLAY']},'pairs':a.pairs,'screen':'Absolute paired median difference / pair mean <= 5% for every workload/pair. Descriptive variability screen, not a significance test.','protocol':'Same JAR/path/args. Labels recorded only by runner. Alternating AB/BA pair order. No retuning or cherry-picked deletion.'}
(a.output/'manifest.json').write_text(json.dumps(manifest,indent=2))
rows=[];medians={};timings=[];noise=[];observations=[]
for pair in range(a.pairs):
    for label in (['A','B'] if pair%2==0 else ['B','A']):
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==jar_sha,'JAR changed mid-calibration'
        assert test_tree_hash()==test_sha,'Test classes changed mid-calibration'
        start=time.time();r=subprocess.run(command,cwd=project,capture_output=True,text=True,timeout=240)
        stem=f'pair-{pair+1}-{label}'
        (a.output/f'{stem}.stdout.txt').write_text(r.stdout);(a.output/f'{stem}.stderr.txt').write_text(r.stderr)
        assert r.returncode==0,(stem,r.returncode,r.stderr[-1000:])
        case={};fork_observations={}
        for line in r.stdout.splitlines():
            fields=line.split(',')
            if fields[0]=='sample':
                _,name,sample,batch,elapsed,ns=fields
                row={'pair':pair+1,'label':label,'workload':name,'sample':int(sample),'batch':int(batch),'elapsed_ns':int(elapsed),'ns_per_op':float(ns)}
                rows.append(row);case.setdefault(name,[]).append(float(ns))
            elif fields[0]=='sample_observation':
                _,name,sample,gc,heap_before,heap_after=fields
                observation={'pair':pair+1,'label':label,'workload':name,'sample':int(sample),'gc_delta':int(gc),'heap_before':int(heap_before),'heap_after':int(heap_after)}
                observations.append(observation);fork_observations[name,int(sample)]=observation
        assert set(case)=={'parse_corpus','create_snapshot','repeat_snapshot','resize_snapshot'}
        assert all(len(v)==30 for v in case.values())
        medians[pair+1,label]={name:statistics.median(v) for name,v in case.items()}
        for name,values in case.items():
            median=statistics.median(values);mad=statistics.median(abs(v-median) for v in values)
            gc_values=[v for i,v in enumerate(values) if fork_observations[name,i]['gc_delta']>0]
            no_gc_values=[v for i,v in enumerate(values) if fork_observations[name,i]['gc_delta']==0]
            noise.append({'pair':pair+1,'label':label,'workload':name,'median_ns':median,'min_ns':min(values),'max_ns':max(values),'relative_mad':mad/median,'relative_stdev':statistics.stdev(values)/statistics.mean(values),'p90_ns':sorted(values)[26],'above_twice_median':sum(v>2*median for v in values),'first_half_median_ns':statistics.median(values[:15]),'last_half_median_ns':statistics.median(values[15:]),'gc_samples':len(gc_values),'gc_sample_median_ns':statistics.median(gc_values) if gc_values else None,'no_gc_sample_median_ns':statistics.median(no_gc_values) if no_gc_values else None})
        timings.append({'pair':pair+1,'label':label,'duration_seconds':time.time()-start})
summary=[]
for pair in range(1,a.pairs+1):
    for case in medians[pair,'A']:
        x=medians[pair,'A'][case];y=medians[pair,'B'][case];diff=abs(x-y)/((x+y)/2)
        summary.append({'pair':pair,'workload':case,'A_median_ns':x,'B_median_ns':y,'relative_difference':diff,'within_5_percent':diff<=.05})
for name,data in [('raw-samples.csv',rows),('paired-screen.csv',summary),('sample-observations.csv',observations),('workload-noise.csv',noise)]:
    with (a.output/name).open('w') as f:
        w=csv.DictWriter(f,fieldnames=list(data[0]));w.writeheader();w.writerows(data)
passed=all(x['within_5_percent'] for x in summary)
result={'stable_screen_passed':passed,'paired_results':summary,'fork_timings':timings,'noise_analysis':noise,'diagnostic_caution':'GC association, tail outliers and within-fork drift are descriptive; samples are never removed from the primary screen. No causal explanation follows from correlation.','meaning':'Same-version repeatability only; not an optimization benchmark or evidence of a causal code regression.','next_step':'Screen passed: a separately designed comparison may proceed.' if passed else 'Screen failed: do not make before/after improvement or causal regression claims from this environment.'}
(a.output/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
