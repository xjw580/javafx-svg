#!/usr/bin/env python3
"""Offline external-consumer check. Uses only the packaged JAR, never project output class dirs.
Dependencies must already exist in the given Maven cache; no network or publishing is performed.
"""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--project', type=pathlib.Path, default=pathlib.Path('.'))
p.add_argument('--baseline-jar', type=pathlib.Path, help='Optional previous JAR for selected binary compatibility and public descriptor comparison')
p.add_argument('--jar', type=pathlib.Path, required=True)
p.add_argument('--maven-repository', type=pathlib.Path, required=True)
p.add_argument('--javafx-platform', required=True, help='For example linux; select real installed native binaries')
p.add_argument('--output', type=pathlib.Path, required=True)
p.add_argument('--native-jdk21', action='store_true', help='Use source/target21 on an actual JDK21 when stripped runtime lacks ct.sym; never on a newer JDK')
p.add_argument('--fx', action='store_true', help='Also run the JavaFX consumer with the test-only Monocle backend')
a = p.parse_args()
a.project = a.project.resolve(); a.jar = a.jar.resolve(); a.output = a.output.resolve(); a.maven_repository = a.maven_repository.resolve()
a.output.mkdir(parents=True, exist_ok=False)
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
pom = ET.parse(a.project / 'pom.xml').getroot()
props = {x.tag.split('}')[-1]: x.text for x in pom.find('m:properties', ns)}
kv, fv = props['kotlin.version'], props['javafx.version']

def dep(group, artifact, version, classifier=None):
    f = a.maven_repository / group.replace('.', '/') / artifact / version / f'{artifact}-{version}{"-" + classifier if classifier else ""}.jar'
    if not f.is_file(): raise SystemExit(f'Missing cached dependency: {f}')
    return f

def digest(f): return hashlib.sha256(f.read_bytes()).hexdigest()
def cp(paths): return os.pathsep.join(str(f) for f in paths)

production = [a.jar, dep('org.openjfx', 'javafx-base', fv, a.javafx_platform),
              dep('org.openjfx', 'javafx-graphics', fv, a.javafx_platform),
              dep('org.jetbrains.kotlin', 'kotlin-stdlib', kv), dep('org.jetbrains', 'annotations', '13.0')]
assert not any('junit' in f.name or 'javafx-controls' in f.name or 'javafx-fxml' in f.name for f in production)
compiler = [dep('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', kv),
            dep('org.jetbrains.kotlin', 'kotlin-stdlib', kv), dep('org.jetbrains.kotlin', 'kotlin-script-runtime', kv),
            dep('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'), dep('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', kv),
            dep('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), dep('org.jetbrains', 'annotations', '13.0')]
commands = []
java_flags = ['--release', '21']
if a.native_jdk21:
    settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
    (a.output / 'jdk-settings.log').write_text(settings)
    if not any(line.strip() == 'java.specification.version = 21' for line in settings.splitlines()):
        raise SystemExit('--native-jdk21 requires an actual Java21 runtime')
    java_flags = ['-source', '21', '-target', '21']
def run(name, command, expected=0):
    if command[0] in {'javac', 'javap'} and shutil.which(command[0]) is None:
        module = {'javac': 'jdk.compiler/com.sun.tools.javac.Main', 'javap': 'jdk.jdeps/com.sun.tools.javap.Main'}[command[0]]
        command = ['java', '-m', module] + command[1:]
    result = subprocess.run(command, cwd=a.project, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    (a.output / (name + '.log')).write_text(result.stdout)
    commands.append({'name': name, 'command': [str(x) for x in command], 'exit_code': result.returncode})
    if (result.returncode == 0) != (expected == 0):
        raise RuntimeError(f'{name} returned {result.returncode}; see {a.output / (name + ".log")}')
    return result.stdout

sources = a.project / 'release/consumers'
java_out, kotlin_out, module_out = [a.output / x for x in ['java', 'kotlin', 'module']]
for f in [java_out, kotlin_out, module_out]: f.mkdir()
run('java-compile', ['javac'] + java_flags + ['-cp', cp(production), '-d', str(java_out), str(sources / 'java/rc/consumer/JavaApiConsumer.java')])
run('java-run', ['java', '-cp', cp([java_out] + production), 'rc.consumer.JavaApiConsumer'])
kotlinc = ['java', '-cp', cp(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', cp(production)]
run('kotlin-compile', kotlinc + ['-d', str(kotlin_out), str(sources / 'kotlin/rc/consumer/KotlinApiConsumer.kt')])
run('kotlin-run', ['java', '-cp', cp([kotlin_out] + production), 'rc.consumer.KotlinApiConsumerKt'])
negative = a.output / 'InternalApiMustNotCompile.kt'
negative.write_text('import club.xiaojiawei.svg.SvgFilterPixels\nfun forbidden(value: SvgFilterPixels) = value\n')
negative_output = run('kotlin-internal-rejected', kotlinc + ['-d', str(a.output / 'negative'), str(negative)], expected=1)
assert 'internal' in negative_output and 'SvgFilterPixels' in negative_output
run('module-compile', ['javac'] + java_flags + ['--module-path', cp(production), '-d', str(module_out), str(sources / 'jpms/module-info.java'), str(sources / 'jpms/rc/consumer/ModuleApiConsumer.java')])
run('module-run', ['java', '--module-path', cp([module_out] + production), '-m', 'rc.consumer/rc.consumer.ModuleApiConsumer'])
if a.fx:
    monocle = dep('org.testfx', 'openjfx-monocle', '21.0.2')
    run('java-fx-run', ['java', '-Dglass.platform=Monocle', '-Dmonocle.platform=Headless', '-Dprism.order=sw', '-Djavafx.cachedir=' + str(a.output / 'javafx-cache'), '-cp', cp([java_out] + production + [monocle]), 'rc.consumer.JavaApiConsumer', '--fx'])
api = ['SvgOptions', 'SvgException', 'SvgDiagnostic', 'SvgElement', 'SvgDocument', 'SvgLoader', 'SvgView', 'SvgResourceCache', 'SvgResourceKind', 'SvgResourceRequest', 'SvgResourceResolver', 'SvgResourceResolvers']
candidate_api = run('api-javap', ['javap', '-classpath', cp(production), '-public', '-s'] + ['club.xiaojiawei.svg.' + n for n in api])
compatibility = None
if a.baseline_jar:
    baseline = a.baseline_jar.resolve()
    if not baseline.is_file(): raise SystemExit('Baseline JAR does not exist')
    old_cp = [baseline] + production[1:]
    old_java = a.output / 'baseline-java'
    old_kotlin = a.output / 'baseline-kotlin'
    old_java.mkdir(); old_kotlin.mkdir()
    compatible_java = (sources / 'java/rc/consumer/JavaApiConsumer.java').read_text().replace('new SvgDiagnostic("diagnostic")', 'new SvgDiagnostic("diagnostic", null)').replace('new SvgException("failure")', 'new SvgException("failure", null)')
    old_source = a.output / 'JavaApiConsumer.java'
    old_source.write_text(compatible_java)
    run('baseline-java-compile', ['javac'] + java_flags + ['-cp', cp(old_cp), '-d', str(old_java), str(old_source)])
    run('baseline-java-run-on-rc', ['java', '-cp', cp([old_java] + production), 'rc.consumer.JavaApiConsumer'])
    old_kotlinc = ['java', '-cp', cp(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', cp(old_cp)]
    run('baseline-kotlin-compile', old_kotlinc + ['-d', str(old_kotlin), str(sources / 'kotlin/rc/consumer/KotlinApiConsumer.kt')])
    run('baseline-kotlin-run-on-rc', ['java', '-cp', cp([old_kotlin] + production), 'rc.consumer.KotlinApiConsumerKt'])
    old_api = run('baseline-api-javap', ['javap', '-classpath', cp(old_cp), '-public', '-s'] + ['club.xiaojiawei.svg.' + n for n in api])
    def descriptors(output):
        result = set(); owner = None; member = None
        for raw in output.splitlines():
            line = raw.strip()
            if line.startswith('public ') and line.endswith('{'): owner = line; member = None
            elif line.startswith('public ') or line.startswith('protected '): member = line
            elif line.startswith('descriptor:') and member: result.add((owner, member, line))
        return result
    before, after = descriptors(old_api), descriptors(candidate_api)
    compatibility = {'baseline_jar': str(baseline), 'baseline_sha256': digest(baseline), 'removed_public_descriptors': sorted(before - after), 'added_public_descriptors': sorted(after - before), 'old_java_and_kotlin_bytecode_run_on_rc': True, 'scope': '12 documented public types; includes JVM-visible internal members, excludes implementation classes. Not exhaustive behavioral/ABI certification.'}
    assert not compatibility['removed_public_descriptors'], compatibility
    (a.output / 'compatibility.json').write_text(json.dumps(compatibility, indent=2) + '\n')
summary = {'jar': str(a.jar), 'jar_sha256': digest(a.jar), 'production_dependencies': {str(f): digest(f) for f in production[1:]},
           'selected_binary_compatibility': compatibility, 'compiler_dependencies': {str(f): digest(f) for f in compiler}, 'commands': commands,
           'java_compile_mode': 'native JDK21 source/target21' if a.native_jdk21 else '--release21', 'production_only_classpath': True, 'fx_backend': 'Monocle software' if a.fx else 'not run',
           'claims': 'External Java classpath, Kotlin classpath and named Java module consumers passed; not full ABI or platform certification.'}
(a.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
