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

    def test_dockerfile_base_images_are_digest_pinned(self):
        source = (ROOT / "Dockerfile").read_text()
        for image in re.findall(r"^FROM\s+(\S+)", source, re.MULTILINE):
            with self.subTest(image=image.split("@")[0]):
                self.assertRegex(image, SHA256)

    def test_ci_actions_and_scanner_are_immutable(self):
        source = (ROOT / ".github/workflows/ci.yml").read_text()
        for action in re.findall(r"^\s*- uses:\s*(\S+)", source, re.MULTILINE):
            with self.subTest(action=action.split("@")[0]):
                self.assertRegex(action, GIT_SHA)
        scanner = re.search(r"zricethezav/gitleaks:[^\s]+", source)
        self.assertIsNotNone(scanner)
        self.assertRegex(scanner.group(), SHA256)


if __name__ == "__main__":
    unittest.main()
