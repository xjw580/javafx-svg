import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('github_release', Path(__file__).resolve().parents[1] / 'github_release.py')
gh = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gh)
SHA = 'a' * 40
TAG = 'v1.0.0-rc'
META = {'repository': gh.REPOSITORY, 'tag': TAG, 'version': gh.VERSION, 'commit': SHA}


class FakeAPI:
    def __init__(self, assets=None, existing=None, draft=True, prerelease=True):
        self.assets = assets or []
        self.existing = existing
        self.draft, self.prerelease = draft, prerelease
        self.calls = []
        self.tag_sha = SHA

    def request(self, method, path, payload=None, allow_missing=False):
        self.calls.append((method, path, payload))
        if path == '/git/ref/tags/' + TAG:
            return {'object': {'type': 'commit', 'sha': self.tag_sha}}
        if path == '/releases/tags/' + TAG:
            return self.existing
        if path.startswith('/releases?per_page=100&page='):
            return []
        if path == '/releases/123/assets?per_page=100':
            return self.assets
        if path == '/releases/123':
            if method == 'PATCH':
                return {'id': 123, 'tag_name': TAG, 'draft': False, 'prerelease': True}
            return {'id': 123, 'tag_name': TAG, 'draft': self.draft, 'prerelease': self.prerelease}
        raise AssertionError((method, path))


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        (self.root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>club.xiaojiawei</groupId><artifactId>javafx-svg</artifactId><version>1.0.0-rc</version></project>')

    def fixture(self):
        target = self.root / 'target'
        out = target / 'github-release'
        out.mkdir(parents=True)
        specs, assets = {}, []
        for name in sorted(gh.asset_names(gh.VERSION)):
            f = out / name
            f.write_bytes(('content of ' + name).encode())
            specs[name] = {'size': f.stat().st_size, 'sha256': gh.sha256(f)}
            assets.append({'name': name, 'size': f.stat().st_size, 'digest': 'sha256:' + gh.sha256(f), 'state': 'uploaded'})
        manifest = {**META, 'assets': specs}
        (target / 'github-release-manifest.json').write_text(json.dumps(manifest))
        return FakeAPI(assets), manifest

    def no_patch(self, api):
        self.assertFalse(any(x[0] == 'PATCH' for x in api.calls))

    def test_correct_local_context(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(gh, 'git', return_value=SHA):
            self.assertEqual(META, gh.context(self.root, TAG))

    def test_wrong_tags_and_injection_rejected(self):
        for tag in ('v1.0.0', 'v1.0.0-rc.1', TAG + '\n', '$(echo bad)', '../../tag'):
            with self.subTest(tag=tag), self.assertRaises(gh.ReleaseError):
                gh.context(self.root, tag)

    def test_wrong_pom_version_or_coordinates_rejected(self):
        pom = self.root / 'pom.xml'
        original = pom.read_text()
        for old, new in [('1.0.0-rc', '1.0.0'), ('javafx-svg', 'another-artifact')]:
            pom.write_text(original.replace(old, new))
            with self.assertRaises(gh.ReleaseError):
                gh.context(self.root, TAG)

    def test_wrong_repository_or_event_sha_rejected(self):
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': 'someone/fork'}, clear=True), self.assertRaises(gh.ReleaseError):
            gh.context(self.root, TAG)
        with patch.dict(os.environ, {'GITHUB_SHA': 'b' * 40}, clear=True), patch.object(gh, 'git', side_effect=[SHA, 'b' * 40]), self.assertRaises(gh.ReleaseError):
            gh.context(self.root, TAG)

    def test_remote_preflight_read_only_and_existing_release_refused(self):
        api = FakeAPI()
        gh.remote_preflight(api, META)
        self.assertTrue(all(x[0] == 'GET' for x in api.calls))
        for existing in ({'draft': True}, {'draft': False}):
            api = FakeAPI(existing=existing)
            with self.assertRaises(gh.ReleaseError):
                gh.remote_preflight(api, META)
            self.no_patch(api)

    def test_draft_hidden_from_tag_lookup_is_found_in_listing(self):
        api = FakeAPI()
        original = api.request
        def request(method, path, payload=None, **kwargs):
            if path.startswith('/releases?'):
                api.calls.append((method, path, payload))
                return [{'tag_name': TAG, 'draft': True}]
            return original(method, path, payload, **kwargs)
        api.request = request
        with self.assertRaisesRegex(gh.ReleaseError, 'draft already exists'):
            gh.remote_preflight(api, META)
        self.no_patch(api)

    def test_later_page_match_exhaustion_and_invalid_listing_fail_closed(self):
        for mode in ('later', 'exhausted', 'invalid'):
            with self.subTest(mode=mode):
                api = FakeAPI()
                original = api.request
                def request(method, path, payload=None, **kwargs):
                    if path.startswith('/releases?'):
                        api.calls.append((method, path, payload))
                        page = int(path.rsplit('=', 1)[1])
                        if mode == 'invalid': return {'not': 'a list'}
                        if mode == 'later' and page == 2: return [{'tag_name': TAG, 'draft': True}]
                        return [{'tag_name': 'other-' + str(i)} for i in range(100)]
                    return original(method, path, payload, **kwargs)
                api.request = request
                with self.assertRaises(gh.ReleaseError): gh.remote_preflight(api, META)
                self.no_patch(api)
                self.assertLessEqual(sum('/releases?' in x[1] for x in api.calls), 10)

    def test_tag_mismatch_refused(self):
        api = FakeAPI()
        api.tag_sha = 'b' * 40
        with self.assertRaises(gh.ReleaseError):
            gh.remote_preflight(api, META)
        self.no_patch(api)

    def test_annotated_tag_resolution_and_cycle(self):
        class Annotated:
            def __init__(self, cycle=False): self.cycle = cycle
            def request(self, method, path, *args, **kwargs):
                if '/ref/' in path or self.cycle:
                    return {'object': {'type': 'tag', 'sha': 'b' * 40}}
                return {'object': {'type': 'commit', 'sha': SHA}}
        self.assertEqual(SHA, gh.remote_tag_commit(Annotated(), TAG))
        with self.assertRaises(gh.ReleaseError): gh.remote_tag_commit(Annotated(True), TAG)

    def test_only_verified_draft_prerelease_published(self):
        api, _ = self.fixture()
        result = gh.publish(api, self.root, META, '123')
        self.assertEqual(6, result['assets_verified'])
        self.assertEqual([('PATCH', '/releases/123', {'draft': False, 'prerelease': True, 'make_latest': 'false'})], [x for x in api.calls if x[0] == 'PATCH'])

    def test_public_or_stable_release_not_modified(self):
        api, _ = self.fixture()
        for draft, prerelease in ((False, True), (True, False)):
            api.draft, api.prerelease = draft, prerelease
            with self.assertRaises(gh.ReleaseError): gh.publish(api, self.root, META, '123')
            self.no_patch(api)

    def test_missing_extra_duplicate_assets_refused(self):
        api, _ = self.fixture()
        original = copy.deepcopy(api.assets)
        for assets in (original[:-1], original + [{'name': 'extra'}], original + [original[0]]):
            api.assets = assets
            with self.assertRaises(gh.ReleaseError): gh.publish(api, self.root, META, '123')
            self.no_patch(api)

    def test_digest_size_upload_state_refused(self):
        api, _ = self.fixture()
        original = copy.deepcopy(api.assets)
        for key, value in (('digest', None), ('digest', 'sha256:' + '0' * 64), ('size', 0), ('state', 'starter')):
            api.assets = copy.deepcopy(original)
            api.assets[0][key] = value
            with self.assertRaises(gh.ReleaseError): gh.publish(api, self.root, META, '123')
            self.no_patch(api)

    def test_changed_local_asset_and_manifest_refused(self):
        api, manifest = self.fixture()
        first = next(iter(manifest['assets']))
        (self.root / 'target/github-release' / first).write_bytes(b'tampered')
        with self.assertRaises(gh.ReleaseError): gh.publish(api, self.root, META, '123')
        self.no_patch(api)
        manifest['assets']['../unsafe'] = manifest['assets'].pop(first)
        (self.root / 'target/github-release-manifest.json').write_text(json.dumps(manifest))
        with self.assertRaises(gh.ReleaseError): gh.publish(api, self.root, META, '123')
        self.no_patch(api)

    def test_invalid_id_refused_before_remote_call(self):
        api = FakeAPI()
        for value in ('0', '-1', '123/../other', '$(bad)'):
            with self.assertRaises(gh.ReleaseError): gh.publish(api, self.root, META, value)
        self.assertEqual([], api.calls)

    def test_test_reports_must_be_complete_and_clean(self):
        folder = self.root / 'target/surefire-reports'
        folder.mkdir(parents=True)
        f = folder / 'TEST-demo.xml'
        for attrs in ('tests="810"', 'tests="811" failures="1"', 'tests="811" errors="1"', 'tests="811" skipped="1"'):
            f.write_text('<testsuite ' + attrs + '/>')
            with self.assertRaises(gh.ReleaseError): gh.test_counts(self.root)
        f.write_text('<testsuite tests="811" failures="0" errors="0" skipped="0"/>')
        self.assertEqual(811, gh.test_counts(self.root)['tests'])

    def test_consumer_failure_missing_command_or_other_jar_refused(self):
        jar = self.root / 'candidate.jar'; jar.write_bytes(b'candidate')
        names = ['java-compile', 'java-run', 'kotlin-compile', 'kotlin-run', 'module-compile', 'module-run', 'java-fx-run', 'api-javap', 'kotlin-internal-rejected']
        summary = {'jar_sha256': gh.sha256(jar), 'production_only_classpath': True,
                   'commands': [{'name': n, 'exit_code': 1 if n == 'kotlin-internal-rejected' else 0} for n in names]}
        f = self.root / 'summary.json'; f.write_text(json.dumps(summary))
        gh.validate_consumers(f, jar)
        for kind in ('hash', 'missing', 'failed'):
            bad = copy.deepcopy(summary)
            if kind == 'hash': bad['jar_sha256'] = '0' * 64
            elif kind == 'missing': bad['commands'].pop()
            else: bad['commands'][0]['exit_code'] = 1
            f.write_text(json.dumps(bad))
            with self.assertRaises(gh.ReleaseError): gh.validate_consumers(f, jar)

    def test_dirty_source_refused_before_preparation(self):
        with patch.object(gh, 'git', return_value=' M pom.xml'), self.assertRaises(gh.ReleaseError):
            gh.prepare(self.root, META, self.root / 'consumers')

    def test_unexpected_mutation_response_is_reported_without_retry(self):
        api, _ = self.fixture()
        original = api.request
        def request(method, path, payload=None, **kwargs):
            result = original(method, path, payload, **kwargs)
            return None if method == 'PATCH' else result
        api.request = request
        with self.assertRaisesRegex(gh.ReleaseError, 'uncertain/unexpected'):
            gh.publish(api, self.root, META, '123')
        self.assertEqual(1, sum(x[0] == 'PATCH' for x in api.calls))

    def test_missing_token_fails_without_network(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(gh.ReleaseError): gh.GitHub()


if __name__ == '__main__':
    unittest.main()
