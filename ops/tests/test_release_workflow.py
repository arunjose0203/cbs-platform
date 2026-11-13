"""P-01: keeps the release gates from being removed by accident. Text checks only, so no YAML dependency."""
from pathlib import Path
import unittest

WORKFLOWS = Path(__file__).resolve().parents[2] / '.github' / 'workflows'


class ReleaseWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.release = (WORKFLOWS / 'release.yml').read_text(encoding='utf-8')
        self.promote = (WORKFLOWS / 'promote.yml').read_text(encoding='utf-8')

    def test_candidates_are_verified_before_anything_is_built(self):
        verify = self.release.index('mvn --batch-mode --no-transfer-progress verify')
        self.assertLess(verify, self.release.index('docker build'))
        self.assertIn("tags: ['v[0-9]+.[0-9]+.[0-9]+-rc.[0-9]+']", self.release)
        self.assertIn('npm test --prefix ui', self.release)
        self.assertIn('unittest discover -s ops/tests', self.release)

    def test_runtime_image_must_be_pinned_by_digest(self):
        self.assertIn('*@sha256:[0-9a-f]*)', self.release)
        self.assertLess(self.release.index('Refuse an unpinned runtime image'), self.release.index('docker build'))

    def test_image_is_scanned_before_push_and_signed_attested_after(self):
        order = [self.release.index(marker) for marker in
                 ('aquasecurity/trivy-action', 'docker push', 'cosign sign --yes', 'attest-build-provenance', 'attest-sbom', 'gh release create')]
        self.assertEqual(order, sorted(order))
        self.assertIn("exit-code: '1'", self.release)
        self.assertIn('severity: HIGH,CRITICAL', self.release)
        self.assertIn('cyclonedx-maven-plugin', self.release)
        self.assertIn('--prerelease', self.release)

    def test_promotion_is_approved_verified_and_never_rebuilds(self):
        self.assertIn('environment: production', self.promote)
        self.assertLess(self.promote.index('cosign verify'), self.promote.index('imagetools create'))
        self.assertIn('gh attestation verify', self.promote)
        self.assertIn('sha256sum --check SHA256SUMS', self.promote)
        self.assertNotRegex(self.promote, r'docker build\s')
        self.assertNotIn('mvn ', self.promote)


if __name__ == '__main__':
    unittest.main()
