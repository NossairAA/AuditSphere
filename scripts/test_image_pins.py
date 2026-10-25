import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SHA256 = re.compile(r"@sha256:[0-9a-f]{64}$")
GIT_SHA = re.compile(r"@[0-9a-f]{40}$")


class ImagePinTest(unittest.TestCase):
    def test_external_compose_images_are_digest_pinned(self):
        for filename in ("compose.yaml", "deploy/garage/compose.yaml"):
            source = (ROOT / filename).read_text()
            for image in re.findall(r"^\s*image:\s*(\S+)", source, re.MULTILINE):
                if image.endswith(":local"):
                    continue
                with self.subTest(file=filename, image=image.split("@")[0]):
                    self.assertRegex(image, SHA256)

    def test_k3s_manifest_images_are_digest_pinned(self):
        manifests = sorted((ROOT / "deploy/k3s").rglob("*.yaml"))
        self.assertTrue(manifests)
        for path in manifests:
            source = path.read_text()
            for image in re.findall(r"^[ \t]*(?:-[ \t]+)?image:[ \t]*(\S+)", source, re.MULTILINE):
                with self.subTest(file=str(path.relative_to(ROOT)), image=image.split("@")[0]):
                    self.assertRegex(image, SHA256)
            for digest in re.findall(r"^[ \t]*(?:-[ \t]+)?digest:[ \t]*(\S+)", source, re.MULTILINE):
                with self.subTest(file=str(path.relative_to(ROOT)), digest=digest):
                    self.assertRegex(digest, r"^sha256:[0-9a-f]{64}$")

    def test_helm_values_images_are_digest_pinned(self):
        values = sorted((ROOT / "infra/helm/values").glob("*.yaml"))
        self.assertTrue(values)
        for path in values:
            source = path.read_text()
            with self.subTest(file=str(path.relative_to(ROOT))):
                self.assertRegex(source, r"(?m)^[ \t]*digest:[ \t]*sha256:[0-9a-f]{64}$")
                self.assertNotRegex(source, r"(?m)^[ \t]*digest:[ \t]*(\"\"|'')?[ \t]*$")

    def test_dockerfile_base_images_are_digest_pinned(self):
        for filename in ("Dockerfile", "apps/web/Dockerfile"):
            source = (ROOT / filename).read_text()
            for image in re.findall(r"^FROM\s+(\S+)", source, re.MULTILINE):
                with self.subTest(file=filename, image=image.split("@")[0]):
                    self.assertRegex(image, SHA256)

    def test_ci_actions_and_scanner_are_immutable(self):
        source = (ROOT / ".github/workflows/ci.yml").read_text()
        for action, comment in re.findall(r"^\s*- uses:\s*(\S+)(.*)$", source, re.MULTILINE):
            with self.subTest(action=action.split("@")[0]):
                self.assertRegex(action, GIT_SHA)
                self.assertRegex(comment, r"^\s*# v\d+")
        runners = re.findall(r"^\s*runs-on:\s*(\S+)", source, re.MULTILINE)
        self.assertTrue(runners)
        for runner in runners:
            with self.subTest(runner=runner):
                self.assertNotRegex(runner, r"-latest$")
        scanner = re.search(r"zricethezav/gitleaks:[^\s]+", source)
        self.assertIsNotNone(scanner)
        self.assertRegex(scanner.group(), SHA256)
        trivy = re.search(r"aquasec/trivy:[^\s]+", source)
        self.assertIsNotNone(trivy)
        self.assertRegex(trivy.group(), SHA256)


if __name__ == "__main__":
    unittest.main()
