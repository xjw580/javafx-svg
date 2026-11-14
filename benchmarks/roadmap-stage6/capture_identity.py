#!/usr/bin/env python3
"""Capture reproducible class/source identities and offline Surefire dependency classpath."""
import argparse,hashlib,json,pathlib,xml.etree.ElementTree as ET,zipfile
p=argparse.ArgumentParser();p.add_argument('--project',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
project=a.project.resolve();a.output.mkdir(parents=True,exist_ok=True)
def sha(data):return hashlib.sha256(data).hexdigest()
jar=project/'target/javafx-svg-1.0-SNAPSHOT.jar'
with zipfile.ZipFile(jar) as z:
    classes={n:sha(z.read(n)) for n in sorted(z.namelist()) if n.endswith('.class')}
sources={str(f.relative_to(project)):sha(f.read_bytes()) for f in sorted((project/'src').rglob('*')) if f.is_file()}
test_classes={str(f.relative_to(project/'target/test-classes')):sha(f.read_bytes()) for f in sorted((project/'target/test-classes').rglob('*')) if f.is_file()}
report=project/'target/surefire-reports/TEST-club.xiaojiawei.svg.SvgRoadmapConvergenceTest.xml'
properties={x.attrib['name']:x.attrib['value'] for x in ET.parse(report).getroot().findall('properties/property')}
paths=properties['java.class.path'].split(':')
paths=[x for x in paths if x and pathlib.Path(x).resolve() not in [project/'target/classes',project/'target/test-classes']]
assert paths and all(pathlib.Path(x).is_file() for x in paths)
assert all(pathlib.Path(x).suffix=='.jar' for x in paths)
(a.output/'test-classpath.txt').write_text(':'.join(paths))
(a.output/'measured-identity.json').write_text(json.dumps({'jar_sha256':sha(jar.read_bytes()),'jar_classes':classes,'source_files':sources,'test_classes_resources':test_classes,'dependencies':{x:sha(pathlib.Path(x).read_bytes()) for x in paths},'meaning':'Compare jar_classes with delivered final JAR; ZIP timestamps may change whole-JAR hash without class changes.'},indent=2)+'\n')
print(json.dumps({'jar_sha256':sha(jar.read_bytes()),'jar_class_count':len(classes),'source_files':len(sources),'dependency_count':len(paths)}))
