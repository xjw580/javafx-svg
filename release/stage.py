#!/usr/bin/env python3
"""Validate and stage a local RC; never uploads, signs, tags, or licenses anything."""
from pathlib import Path
import hashlib, json, shutil, struct, zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
pom = ET.parse(root / 'pom.xml').getroot()
def value(name):
    result = pom.findtext('m:' + name, namespaces=ns)
    assert result, name
    return result
group, artifact, version = (value(x) for x in ('groupId', 'artifactId', 'version'))
assert version == '1.0.0-rc', 'This staging protocol is scoped to 1.0.0-rc'
base = artifact + '-' + version
target = root / 'target'
jar, sources = target / (base + '.jar'), target / (base + '-sources.jar')
expected_sources = {str(p.relative_to(root / 'src/main/kotlin')): p.read_bytes()
                    for p in (root / 'src/main/kotlin').rglob('*.kt')}
with zipfile.ZipFile(jar) as z:
    assert z.testzip() is None
    for notice in ('LICENSE', 'NOTICE'):
        assert z.read('META-INF/' + notice) == (root / notice).read_bytes()
    manifest = z.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '')
    assert 'Automatic-Module-Name: club.xiaojiawei.svg' in manifest
    assert 'Implementation-Version: ' + version in manifest
    classes = [n for n in z.namelist() if n.endswith('.class')]
    assert classes and all(n.startswith('club/xiaojiawei/svg/') for n in classes)
    assert not any('/demo/' in n or n.endswith('Test.class') for n in classes)
    assert not any(n.startswith(('org/junit/', 'javafx/', 'kotlin/')) for n in classes)
    assert all(struct.unpack('>H', z.read(n)[6:8])[0] == 65 for n in classes), 'Expected Java 21 bytecode'
    assert z.read('META-INF/maven/' + group + '/' + artifact + '/pom.xml') == (root / 'pom.xml').read_bytes()
with zipfile.ZipFile(sources) as z:
    assert z.testzip() is None
    for notice in ('LICENSE', 'NOTICE'):
        assert z.read('META-INF/' + notice) == (root / notice).read_bytes()
    actual_sources = {n: z.read(n) for n in z.namelist() if n.endswith('.kt')}
    assert actual_sources == expected_sources, 'Sources JAR must exactly match all production Kotlin sources'
output = target / 'rc-staging'
layout = output / group.replace('.', '/') / artifact / version
layout.mkdir(parents=True, exist_ok=True)
files = [jar, sources, root / 'pom.xml']
results = []
for source in files:
    name = base + '.pom' if source.name == 'pom.xml' else source.name
    dest = layout / name
    shutil.copy2(source, dest)
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    (layout / (name + '.sha256')).write_text(digest + '\n', encoding='ascii')
    results.append({'file': str(dest.relative_to(output)), 'bytes': dest.stat().st_size, 'sha256': digest})
report = {'version': version, 'module': 'club.xiaojiawei.svg', 'main_classes': len(classes),
          'kotlin_sources': len(expected_sources), 'files': results,
          'local_candidate_only': True, 'published': False,
          'license': 'MPL-2.0 (owner-selected to match javafx-ui)',
          'gates': ['Publication destination and credentials/signing',
                    'Explicit permission for tag/release/push', 'Actual downstream javafx-ui acceptance',
                    'Platform coverage remains Linux software only']}
(output / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False, indent=2))
