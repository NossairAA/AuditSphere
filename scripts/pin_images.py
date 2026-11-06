"""Épingle dans les valeurs Helm les images publiées par la CI pour un commit de main.

Seules les images dont les sources ont changé depuis le commit épinglé sont réécrites,
pour ne pas redémarrer les autres services au déploiement suivant.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VALUES = ["infra/helm/values/*.yaml", "infra/helm/web/values.yaml"]
# Bloc image de premier niveau, celui de Traefik dans le chart web reste intact.
IMAGE_BLOCK = re.compile(
    r"(?m)^image:\n  repository: (?P<repository>\S+/auditsphere-(?P<name>[a-z-]+))\n"
    r"  tag: (?P<tag>\S*)\n  digest: (?P<digest>\S*)$"
)
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA = re.compile(r"^[0-9a-f]{40}$")


def inputs(name):
    """Chemins qui entrent dans l'image, au sens de son Dockerfile."""
    if name == "web":
        return ["apps/web", ":(exclude)apps/web/tests"]
    return ["Dockerfile", ".dockerignore", "pom.xml", f"services/{name}", f":(exclude)services/{name}/src/test"]


def changed(root, tag, sha, name):
    if tag == sha:
        return False
    if not SHA.match(tag):
        return True
    result = subprocess.run(["git", "diff", "--quiet", tag, sha, "--", *inputs(name)], cwd=root, capture_output=True)
    # 1 : sources modifiées. Toute autre erreur, un commit inconnu par exemple, force l'épinglage.
    return result.returncode != 0


def pin(root, sha, digests):
    pinned = []
    for pattern in VALUES:
        for path in sorted(root.glob(pattern)):
            source = path.read_text()
            match = IMAGE_BLOCK.search(source)
            if not match:
                continue
            name, tag = match["name"], match["tag"]
            if not changed(root, tag, sha, name):
                continue
            if name not in digests:
                raise SystemExit(f"Empreinte absente pour {name}, requise par {path.relative_to(root)}.")
            block = f"image:\n  repository: {match['repository']}\n  tag: {sha}\n  digest: {digests[name]}"
            path.write_text(source[: match.start()] + block + source[match.end() :])
            pinned.append((str(path.relative_to(root)), name, tag))
    return pinned


def parse_digest(value):
    name, _, digest = value.partition("=")
    if not name or not DIGEST.match(digest):
        raise argparse.ArgumentTypeError(f"attendu nom=sha256:<64 hex>, reçu {value}")
    return name, digest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha", required=True, help="commit de main dont les images sont publiées")
    parser.add_argument("--digest", action="append", type=parse_digest, default=[], help="nom=sha256:..., répétable")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    if not SHA.match(args.sha):
        parser.error("--sha attend un SHA complet de 40 caractères")
    pinned = pin(args.root, args.sha, dict(args.digest))
    # Sortie en Markdown, reprise telle quelle comme description de la pull request.
    if pinned:
        print(f"Images publiées par `{args.sha}`, à déployer après fusion.\n")
        for path, name, tag in pinned:
            print(f"- `{path}` : `{name}`, `{tag[:7] or 'aucun'}` vers `{args.sha[:7]}`")
    return 0


if __name__ == "__main__":
    sys.exit(main())
