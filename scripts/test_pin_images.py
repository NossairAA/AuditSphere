import subprocess
import tempfile
import unittest
from pathlib import Path

from pin_images import pin

OLD = "sha256:" + "a" * 64
NEW = {name: "sha256:" + c * 64 for name, c in [("audit-service", "b"), ("api-gateway", "c"), ("web", "d")]}


def values(name, tag, digest=OLD, image=None):
    image = image or name
    return f"# commentaire\nimage:\n  repository: ghcr.io/owner/auditsphere-{image}\n  tag: {tag}\n  digest: {digest}\nport: 8080\n"


class PinImagesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.git("init", "-q")
        self.write("Dockerfile", "FROM base\n")
        self.write("pom.xml", "<project/>\n")
        for name in ("audit-service", "api-gateway"):
            self.write(f"services/{name}/src/main/App.java", "class App {}\n")
            self.write(f"services/{name}/src/test/AppTest.java", "class AppTest {}\n")
        self.write("apps/web/src/page.tsx", "export {}\n")
        self.write("apps/web/tests/page.spec.ts", "test()\n")
        self.base = self.commit()
        self.write("infra/helm/values/audit-service.yaml", values("audit-service", self.base))
        self.write("infra/helm/values/workflow-worker.yaml", values("workflow-worker", self.base, image="audit-service"))
        self.write("infra/helm/values/api-gateway.yaml", values("api-gateway", self.base))
        self.write(
            "infra/helm/web/values.yaml",
            values("web", self.base) + f"edge:\n  image:\n    repository: traefik\n    tag: v3\n    digest: {OLD}\n",
        )
        self.commit()

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout.strip()

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def commit(self):
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@example.test", "commit", "-qm", "c")
        return self.git("rev-parse", "HEAD")

    def read(self, path):
        return (self.root / path).read_text()

    def test_only_images_with_changed_sources_are_pinned(self):
        self.write("services/api-gateway/src/main/App.java", "class App { int x; }\n")
        sha = self.commit()
        pinned = pin(self.root, sha, NEW)
        self.assertEqual([name for _, name, _ in pinned], ["api-gateway"])
        self.assertIn(f"tag: {sha}\n  digest: {NEW['api-gateway']}", self.read("infra/helm/values/api-gateway.yaml"))
        self.assertIn(f"tag: {self.base}", self.read("infra/helm/values/audit-service.yaml"))
        self.assertIn("# commentaire\n", self.read("infra/helm/values/api-gateway.yaml"))
        self.assertTrue(self.read("infra/helm/values/api-gateway.yaml").endswith("port: 8080\n"))

    def test_shared_image_pins_every_values_file_using_it(self):
        self.write("services/audit-service/src/main/App.java", "class App { int y; }\n")
        sha = self.commit()
        pinned = pin(self.root, sha, NEW)
        self.assertEqual(sorted(path for path, _, _ in pinned), [
            "infra/helm/values/audit-service.yaml", "infra/helm/values/workflow-worker.yaml"])
        self.assertIn(NEW["audit-service"], self.read("infra/helm/values/workflow-worker.yaml"))

    def test_dockerfile_change_pins_every_java_image(self):
        self.write("Dockerfile", "FROM base2\n")
        sha = self.commit()
        self.assertEqual(len(pin(self.root, sha, NEW)), 3)
        self.assertIn(f"tag: {self.base}", self.read("infra/helm/web/values.yaml"))

    def test_test_and_documentation_changes_pin_nothing(self):
        self.write("services/audit-service/src/test/AppTest.java", "class AppTest { int z; }\n")
        self.write("apps/web/tests/page.spec.ts", "test(2)\n")
        self.write("README.md", "doc\n")
        sha = self.commit()
        self.assertEqual(pin(self.root, sha, NEW), [])

    def test_web_pin_leaves_edge_image_intact(self):
        self.write("apps/web/src/page.tsx", "export const a = 1\n")
        sha = self.commit()
        pin(self.root, sha, NEW)
        web = self.read("infra/helm/web/values.yaml")
        self.assertIn(f"tag: {sha}\n  digest: {NEW['web']}", web)
        self.assertIn(f"    tag: v3\n    digest: {OLD}\n", web)

    def test_unknown_pinned_commit_is_repinned(self):
        self.write("infra/helm/values/api-gateway.yaml", values("api-gateway", "f" * 40))
        sha = self.commit()
        self.assertEqual([name for _, name, _ in pin(self.root, sha, NEW)], ["api-gateway"])

    def test_missing_digest_for_changed_image_fails(self):
        self.write("services/api-gateway/src/main/App.java", "class App { int w; }\n")
        sha = self.commit()
        with self.assertRaises(SystemExit):
            pin(self.root, sha, {"web": NEW["web"]})


if __name__ == "__main__":
    unittest.main()
