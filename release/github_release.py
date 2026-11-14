#!/usr/bin/env python3
"""Bounded tag-release helper. Only the explicit publish command mutates GitHub."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

REPOSITORY = 'xjw580/javafx-svg'
VERSION = '1.0.0-rc'
ROOT = Path(__file__).resolve().parents[1]
API = 'https://api.github.com/repos/' + REPOSITORY
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


class ReleaseError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(root, *args):
    result = subprocess.run(['git', *args], cwd=root, text=True, capture_output=True, timeout=30)
    require(result.returncode == 0, 'Git verification failed: ' + args[0])
    return result.stdout.strip()


def context(root, tag):
    pom = ET.parse(root / 'pom.xml').getroot()
    version = pom.findtext('m:version', namespaces=NS)
    require(pom.findtext('m:groupId', namespaces=NS) == 'club.xiaojiawei'
            and pom.findtext('m:artifactId', namespaces=NS) == 'javafx-svg', 'Unexpected Maven coordinates')
    require(version == VERSION, 'Release helper is scoped to version ' + VERSION)
    require(tag == 'v' + version, 'Tag must exactly match v' + VERSION)
    require(os.environ.get('GITHUB_REPOSITORY', REPOSITORY) == REPOSITORY, 'Unexpected repository')
    head = git(root, 'rev-parse', 'HEAD')
    require(re.fullmatch('[0-9a-f]{40}', head) is not None, 'Unexpected commit SHA')
    event_sha = os.environ.get('GITHUB_SHA')
    if event_sha:
        require(re.fullmatch('[0-9a-f]{40}', event_sha) is not None, 'Invalid event commit SHA')
        require(git(root, 'rev-parse', event_sha + '^{commit}') == head, 'Checkout differs from event commit')
    return {'repository': REPOSITORY, 'tag': tag, 'version': version, 'commit': head}


class GitHub:
    def __init__(self):
        self.token = os.environ.get('GITHUB_TOKEN')
        require(bool(self.token), 'GITHUB_TOKEN is required for GitHub checks/publication')

    def request(self, method, path, payload=None, allow_missing=False):
        require(path.startswith('/') and not path.startswith('//'), 'Invalid API path')
        headers = {'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + self.token,
                   'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'javafx-svg-rc-release'}
        body = None if payload is None else json.dumps(payload).encode()
        if body is not None:
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(API + path, data=body, headers=headers, method=method)
        try:
            # Redirects are not followed: never forward the token to another origin.
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *args, **kwargs):
                    return None
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404 and allow_missing:
                return None
            raise ReleaseError('GitHub request failed with HTTP ' + str(error.code)) from None
        except (OSError, ValueError):
            raise ReleaseError('GitHub request failed; outcome may be uncertain for a mutation') from None


def remote_tag_commit(api, tag):
    reference = api.request('GET', '/git/ref/tags/' + tag)
    require(isinstance(reference, dict) and isinstance(reference.get('object'), dict), 'Missing tag reference')
    obj = reference['object']
    seen = set()
    for _ in range(8):
        sha = obj.get('sha')
        require(isinstance(sha, str) and re.fullmatch('[0-9a-f]{40}', sha), 'Invalid remote tag SHA')
        if obj.get('type') == 'commit':
            return sha
        require(obj.get('type') == 'tag' and sha not in seen, 'Invalid/cyclic annotated tag')
        seen.add(sha)
        annotated = api.request('GET', '/git/tags/' + sha)
        require(isinstance(annotated, dict) and isinstance(annotated.get('object'), dict), 'Invalid annotated tag')
        obj = annotated['object']
    raise ReleaseError('Annotated tag chain is too deep')


def remote_preflight(api, meta):
    require(remote_tag_commit(api, meta['tag']) == meta['commit'], 'Remote tag differs from checkout')
    existing = api.request('GET', '/releases/tags/' + meta['tag'], allow_missing=True)
    require(existing is None, 'Release already exists; refusing to overwrite or unpublish it')
    # get-by-tag can return 404 for drafts. The publish job must run this with its
    # contents:write token, which can see drafts in the paginated collection.
    for page in range(1, 11):
        releases = api.request('GET', '/releases?per_page=100&page=' + str(page))
        require(isinstance(releases, list) and all(isinstance(x, dict) for x in releases),
                'Invalid release listing')
        require(not any(x.get('tag_name') == meta['tag'] for x in releases),
                'Release or draft already exists; refusing to overwrite or unpublish it')
        if len(releases) < 100:
            return
    raise ReleaseError('Release listing exceeded bounded preflight; inspect existing drafts manually')


def test_counts(root):
    reports = list((root / 'target/surefire-reports').glob('TEST-*.xml'))
    require(bool(reports), 'Missing test reports')
    counts = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for path in reports:
        attrs = ET.parse(path).getroot().attrib
        for key in counts:
            counts[key] += int(attrs.get(key, '0'))
    require(counts['tests'] >= 811, 'Expected at least 811 tests for this RC')
    require(all(counts[key] == 0 for key in ('failures', 'errors', 'skipped')), 'Tests failed, errored or skipped')
    return counts


def validate_consumers(path, jar):
    if path.is_dir():
        path = path / 'summary.json'
    summary = json.loads(path.read_text())
    require(summary.get('jar_sha256') == sha256(jar), 'Consumer result does not match candidate JAR')
    require(summary.get('production_only_classpath') is True, 'Consumer production classpath not verified')
    required = {'java-compile', 'java-run', 'kotlin-compile', 'kotlin-run', 'module-compile',
                'module-run', 'java-fx-run', 'api-javap', 'kotlin-internal-rejected'}
    commands = summary.get('commands', [])
    by_name = {x.get('name'): x for x in commands}
    require(required <= by_name.keys(), 'Missing consumer verification commands')
    for name in required:
        code = by_name[name].get('exit_code')
        require(code == 1 if name == 'kotlin-internal-rejected' else code == 0,
                'Unexpected consumer result: ' + name)
    return {'jar_sha256': summary['jar_sha256'], 'production_only_classpath': True,
            'java_compile_mode': summary.get('java_compile_mode'), 'fx_backend': summary.get('fx_backend'),
            'selected_binary_compatibility': summary.get('selected_binary_compatibility'),
            'commands': sorted(required)}


def prepare(root, meta, consumers):
    require(not git(root, 'status', '--porcelain'), 'Source must be committed and clean before source archiving')
    # Isolated mode ignores PYTHONOPTIMIZE; stage.py assertions cannot be disabled by runner environment.
    result = subprocess.run([sys.executable, '-I', str(root / 'release/stage.py')], cwd=root,
                            text=True, capture_output=True, timeout=60)
    require(result.returncode == 0, 'Candidate artifact staging/validation failed')
    staging = json.loads((root / 'target/rc-staging/validation.json').read_text())
    require(staging.get('version') == meta['version'], 'Staging version mismatch')
    base = 'javafx-svg-' + meta['version']
    jar = root / 'target' / (base + '.jar')
    counts = test_counts(root)
    checked_consumers = validate_consumers(consumers, jar)
    output = root / 'target/github-release'
    require(not output.exists(), 'Release asset directory already exists; use a fresh clean build')
    output.mkdir()
    for suffix in ('.jar', '-sources.jar'):
        shutil.copy2(root / 'target' / (base + suffix), output / (base + suffix))
    shutil.copy2(root / 'pom.xml', output / (base + '.pom'))
    archive = output / (base + '-source.zip')
    proc = subprocess.run(['git', 'archive', '--format=zip', '--prefix=' + base + '/',
                           '--output=' + str(archive), meta['commit']], cwd=root, timeout=30)
    require(proc.returncode == 0, 'Source archive generation failed')
    verification = {**meta, 'tests': counts, 'consumers': checked_consumers,
                    'license': 'MPL-2.0; third-party test-corpus licenses retained',
                    'scope': 'Linux Java21 software rendering; not Windows/macOS/GPU or actual javafx-ui integration',
                    'performance': 'No new performance claims; historical timing instability remains documented'}
    (output / 'verification.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2) + '\n')
    names = sorted(p.name for p in output.iterdir())
    (output / 'SHA256SUMS').write_text(''.join(sha256(output / name) + '  ' + name + '\n' for name in names))
    assets = {p.name: {'size': p.stat().st_size, 'sha256': sha256(p)} for p in sorted(output.iterdir())}
    manifest = {**meta, 'assets': assets}
    (root / 'target/github-release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    notes = f'''# javafx-svg {meta['version']} (pre-release)

Candidate library and Kotlin sources, POM, complete source ZIP, verification record and SHA-256 checksums are attached.

- Maven coordinates: `club.xiaojiawei:javafx-svg:{meta['version']}`. GitHub assets are not a Maven Central deployment.
- Commit: `{meta['commit']}`; MPL-2.0 with original third-party test-fixture notices retained.
- This run: {counts['tests']} tests passed with no failures/errors/skips; packaged-JAR Java/Kotlin/JPMS/FX consumers passed.
- Automatic module name: `club.xiaojiawei.svg` (migrate old `requires javafx.svg`). This is not a fully encapsulated explicit module.
- Requires Java 21 and matching JavaFX runtime dependencies; the library JAR is not a standalone demo application.
- Linux software validation only. Actual javafx-ui business integration, Windows/macOS/GPU/HiDPI remain unverified.
- No performance gain claim; prior same-version timing was unstable. Complex text shaping/embedded fonts and other documented SVG boundaries remain.

See `release/1.0.0-rc.md`, `release/VALIDATION.md`, and `benchmarks/release-api.md` in the source archive for scope and historical evidence. The workflow record and attached verification describe this run, not all platforms or all historical ABI behavior.
'''
    (root / 'target/github-release-notes.md').write_text(notes)
    return manifest


def asset_names(version):
    base = 'javafx-svg-' + version
    return {base + suffix for suffix in ('.jar', '-sources.jar', '.pom', '-source.zip')} | {'verification.json', 'SHA256SUMS'}


def publish(api, root, meta, release_id):
    require(re.fullmatch('[1-9][0-9]*', release_id) is not None, 'Invalid release id')
    manifest = json.loads((root / 'target/github-release-manifest.json').read_text())
    require(all(manifest.get(key) == meta[key] for key in ('repository', 'tag', 'version', 'commit')),
            'Prepared manifest differs from checkout/tag')
    require(remote_tag_commit(api, meta['tag']) == meta['commit'], 'Remote tag moved or differs from checkout')
    release = api.request('GET', '/releases/' + release_id)
    require(isinstance(release, dict) and release.get('id') == int(release_id), 'Release id mismatch')
    require(release.get('tag_name') == meta['tag'], 'Release tag mismatch')
    require(release.get('draft') is True and release.get('prerelease') is True,
            'Only a draft prerelease may be finalized')
    assets = api.request('GET', '/releases/' + release_id + '/assets?per_page=100')
    require(isinstance(assets, list) and len(assets) < 100, 'Unexpected or paginated release asset list')
    require(all(isinstance(x, dict) for x in assets), 'Invalid release asset metadata')
    actual = {x.get('name'): x for x in assets}
    expected = manifest.get('assets')
    require(isinstance(expected, dict) and set(expected) == asset_names(meta['version']), 'Invalid prepared asset allowlist')
    require(len(actual) == len(assets) and set(actual) == set(expected), 'Release assets missing, duplicated or unexpected')
    for name, spec in expected.items():
        require(isinstance(spec, dict) and type(spec.get('size')) is int and spec['size'] > 0
                and isinstance(spec.get('sha256'), str) and re.fullmatch('[0-9a-f]{64}', spec['sha256']),
                'Invalid prepared asset metadata: ' + name)
        local = root / 'target/github-release' / name
        require(local.is_file() and local.stat().st_size == spec['size'] and sha256(local) == spec['sha256'],
                'Local prepared asset changed: ' + name)
        remote = actual[name]
        require(remote.get('state') == 'uploaded' and remote.get('size') == spec['size'], 'Remote asset incomplete: ' + name)
        require(remote.get('digest') == 'sha256:' + spec['sha256'], 'Remote asset digest missing or mismatched: ' + name)
    payload = {'draft': False, 'prerelease': True, 'make_latest': 'false'}
    # Do not retry this mutation automatically: timeout may mean GitHub already published it.
    updated = api.request('PATCH', '/releases/' + release_id, payload)
    require(isinstance(updated, dict) and updated.get('id') == int(release_id) and updated.get('tag_name') == meta['tag']
            and updated.get('draft') is False and updated.get('prerelease') is True,
            'Publication returned an uncertain/unexpected state; inspect GitHub before retrying')
    return {'release_id': int(release_id), 'tag': meta['tag'], 'commit': meta['commit'],
            'published': True, 'prerelease': True, 'assets_verified': len(assets)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('preflight', 'prepare', 'publish'):
        entry = sub.add_parser(command)
        entry.add_argument('--tag', required=True)
        if command == 'preflight':
            entry.add_argument('--check-remote', action='store_true')
        elif command == 'prepare':
            entry.add_argument('--consumers', type=Path, required=True)
        else:
            entry.add_argument('--release-id', required=True)
    args = parser.parse_args()
    meta = context(ROOT, args.tag)
    if args.command == 'preflight':
        if args.check_remote:
            remote_preflight(GitHub(), meta)
        result = {**meta, 'remote_checked': args.check_remote}
    elif args.command == 'prepare':
        result = prepare(ROOT, meta, args.consumers)
    else:
        result = publish(GitHub(), ROOT, meta, args.release_id)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ReleaseError, OSError, ValueError, ET.ParseError, subprocess.SubprocessError) as error:
        print('Release verification failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
