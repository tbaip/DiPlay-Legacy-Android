"""Run the release workflow shell against local Git and APK fixtures."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/verify-release-apk-version.yml"
TAG = "v99.0.0"


class ReleaseApkWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
        cls.bash = str(git_bash) if os.name == "nt" and git_bash.exists() else shutil.which("bash")
        if not cls.bash or not shutil.which("git"):
            raise unittest.SkipTest("Git and Bash are required")
        workflow = WORKFLOW.read_text(encoding="utf-8")
        verification = workflow.split("- name: Verify APK version and commit revision match release", 1)[1]
        cls.script = textwrap.dedent(verification.split("run: |\n", 1)[1])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="diplay-release-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.git(self.source, "init", "-q")
        self.git(self.source, "config", "user.name", "Release test")
        self.git(self.source, "config", "user.email", "release-test@example.invalid")
        code = self.source / "shared/code.txt"
        code.parent.mkdir()
        code.write_text("old code\n", encoding="utf-8")
        self.old = self.commit("old code")
        code.write_text("release code\n", encoding="utf-8")
        self.current = self.commit("release code")
        (self.source / "notes.md").write_text("release notes\n", encoding="utf-8")
        self.tagged = self.commit("release notes only")
        self.git(self.source, "tag", TAG)
        self.checkout = self.root / "checkout"
        self.git(self.root, "clone", "-q", str(self.source), str(self.checkout))
        tools = self.root / "sdk/build-tools/35.0.0"
        tools.mkdir(parents=True)
        aapt = tools / "aapt"
        aapt.write_text("#!/bin/sh\necho \"package: name='fixture' versionName='99.0.0' versionCode='999'\"\n",
                        encoding="utf-8", newline="\n")
        aapt.chmod(0o755)
        self.env = dict(os.environ, TAG=TAG, ANDROID_HOME=(self.root / "sdk").as_posix())

    @staticmethod
    def git(cwd, *args):
        return subprocess.check_output(["git", *args], cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()

    def commit(self, message):
        self.git(self.source, "add", ".")
        self.git(self.source, "commit", "-qm", message)
        return self.git(self.source, "rev-parse", "HEAD")

    def verify(self, revision=None, *, metadata=True):
        output = self.checkout / "release-apk"
        output.mkdir(exist_ok=True)
        with zipfile.ZipFile(output / "DiPlay-v99.0.0.apk", "w") as apk:
            if metadata:
                apk.writestr("META-INF/version-control-info.textproto", f'repositories {{ revision: "{revision or ""}" }}\n')
        return subprocess.run([self.bash, "-c", self.script], cwd=self.checkout, env=self.env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_checkout_provides_complete_history(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        checkout = text.index("uses: actions/checkout@")
        self.assertLess(checkout, text.index("- name: Verify APK version"))
        self.assertIn("fetch-depth: 0", text[checkout:text.index("- name: Ensure Android build tools")])

    def test_exact_source_commit_passes(self):
        result = self.verify(self.tagged)
        self.assertEqual(0, result.returncode, result.stdout)

    def test_notes_only_difference_passes(self):
        result = self.verify(self.current)
        self.assertEqual(0, result.returncode, result.stdout)

    def test_outdated_code_fails(self):
        result = self.verify(self.old)
        self.assertNotEqual(0, result.returncode, result.stdout)

    def test_missing_or_invalid_revision_fails(self):
        for revision, metadata in [(None, False), ("", True), ("invalid", True), ("f" * 40, True)]:
            with self.subTest(revision=revision, metadata=metadata):
                result = self.verify(revision, metadata=metadata)
                self.assertNotEqual(0, result.returncode, result.stdout)


if __name__ == "__main__":
    unittest.main()
