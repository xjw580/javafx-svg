#!/usr/bin/env python3
import argparse,csv,hashlib,json,os,pathlib,platform,subprocess
p=argparse.ArgumentParser();p.add_argument('--project',type=pathlib.Path,required=True);p.add_argument('--classpath-file',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--seconds',type=int,default=300);a=p.parse_args()
assert 30<=a.seconds<=1800
project=a.project.resolve();a.output.mkdir(parents=True,exist_ok=True);jar=project/'target/javafx-svg-1.0-SNAPSHOT.jar'
cp=os.pathsep.join([str(jar),str(project/'target/test-classes'),a.classpath_file.resolve().read_text().strip()])
cmd=['java','-Xms64m','-Xmx256m','-Dglass.platform=Monocle','-Dmonocle.platform=Headless','-Dprism.order=sw',f'-Djavafx.cachedir={a.output.resolve()/"javafx-cache"}','-cp',cp,'club.xiaojiawei.svg.SvgRoadmapSustainedEdit',str(a.seconds)]
manifest={'command':cmd,'cwd':str(project),'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'host':platform.platform(),'environment':{k:os.environ.get(k) for k in ['XDG_CACHE_HOME','JAVA_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','DISPLAY']},'meaning':'Bounded continuous-edit lifecycle and heap observation. No forced GC, not a proof of leak freedom or a GPU/Windows result.'}
(a.output/'manifest.json').write_text(json.dumps(manifest,indent=2))
r=subprocess.run(cmd,cwd=project,capture_output=True,text=True,timeout=a.seconds+120)
(a.output/'stdout.txt').write_text(r.stdout);(a.output/'stderr.txt').write_text(r.stderr)
assert r.returncode==0,(r.returncode,r.stderr[-2000:])
rows=[]
for line in r.stdout.splitlines():
    fields=line.split(',')
    if len(fields)==7:
        try:rows.append({'elapsed_seconds':float(fields[0]),'edits':int(fields[1]),'heap_used_bytes':int(fields[2]),'heap_committed_bytes':int(fields[3]),'gc_count':int(fields[4]),'max_scene_nodes':int(fields[5]),'checksum':int(fields[6])})
        except ValueError:pass
assert rows and 'completed,' in r.stdout
with (a.output/'observations.csv').open('w')as f:
    w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(rows)
result={'duration_requested_seconds':a.seconds,'last_observation_seconds':rows[-1]['elapsed_seconds'],'edits':rows[-1]['edits'],'heap_min_bytes':min(x['heap_used_bytes']for x in rows),'heap_max_bytes':max(x['heap_used_bytes']for x in rows),'heap_last_bytes':rows[-1]['heap_used_bytes'],'gc_count':rows[-1]['gc_count'],'max_scene_nodes':max(x['max_scene_nodes']for x in rows),'assertions_passed':['retired cache flags cleared','detached cache flags cleared','scene nodes below150','strict diagnostics empty','minimum edit throughput'],'caveat':'Heap fluctuation and no observed failure during this finite run do not prove absence of leaks.'}
(a.output/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
