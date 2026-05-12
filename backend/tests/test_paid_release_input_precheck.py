"""Behavioral regression test for ``scripts/check_paid_release_inputs.sh``.

The precheck must:

* Exit non-zero when paid-release env vars hold placeholder/example values
  (the Gradle ``verifyPaidReleaseConfig`` task only rejects emptiness, so
  placeholder strings would otherwise slip through).
* Identify which variable failed by name so operators can fix the right one.
* Never echo the literal env value into stdout/stderr - some of these vars
  hold signing/payment URLs and contact emails that must not leak into CI
  logs even when wrong.

Fixture values use distinctive markers that appear nowhere else in the
codebase, so a regex slip that interpolates the value into the error
message would be unambiguous in the assertion failure. They are not real
secrets.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import textwrap
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PRECHECK_SCRIPT = REPO_ROOT / "scripts" / "check_paid_release_inputs.sh"
VERIFY_SCRIPT = REPO_ROOT / "scripts" / "verify_paid_release_config.sh"
BUILD_RELEASE_SCRIPT = REPO_ROOT / "TianXianQuant" / "scripts" / "build_release_artifacts.sh"
README_DOC = REPO_ROOT / "README.md"
RELEASE_ENV_EXAMPLE = REPO_ROOT / "release.env.example"
RELEASE_SIGNING_DOC = REPO_ROOT / "docs" / "RELEASE_SIGNING.md"
RELEASE_GATE_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "release-gate.yml"
GRADLE_BUILD_FILE = REPO_ROOT / "TianXianQuant" / "app" / "build.gradle.kts"

RELEASE_SIGNING_ARGV_GUARD_FILES = (
    VERIFY_SCRIPT,
    BUILD_RELEASE_SCRIPT,
    RELEASE_SIGNING_DOC,
    README_DOC,
    RELEASE_ENV_EXAMPLE,
    GRADLE_BUILD_FILE,
)

RELEASE_SIGNING_DOC_FILES = (
    RELEASE_SIGNING_DOC,
    README_DOC,
    RELEASE_ENV_EXAMPLE,
)

DOC_SIGNING_SECRET_ASSIGNMENT_RE = re.compile(
    r"\b(?P<name>TIANXIAN_RELEASE_(?:KEYSTORE|STORE_PASSWORD|KEY_PASSWORD))"
    r"\s*=\s*(?P<quote>['\"]?)(?P<value>[^'\"\\\s]+)(?P=quote)"
)
DOC_SIGNING_SYNTHETIC_VALUES = {
    "TIANXIAN_RELEASE_KEYSTORE": {
        "/secure/path/release.keystore",
        "/secure/path/tianxian-upload.jks",
    },
    "TIANXIAN_RELEASE_STORE_PASSWORD": {
        "***",
        "<store-password>",
        "replace-with-store-password",
    },
    "TIANXIAN_RELEASE_KEY_PASSWORD": {
        "***",
        "<key-password>",
        "replace-with-key-password",
    },
}

PLACEHOLDER_API_URL = "https://example.com/api-precheck-fixture-marker"
PLACEHOLDER_SUPPORT_EMAIL = "support-precheck-fixture-marker@example.com"

# A URL whose host part is a plausible production domain (no placeholder
# substring) but which carries inline ``user:password@`` credentials in the
# authority component. The host is intentionally invented so it cannot match
# the placeholder-host blocklist; the only reason for the precheck to fail
# this fixture is the embedded userinfo. The password marker is unique to
# this test so a leak in stdout/stderr would be unmistakable.
USERINFO_LEAK_PASSWORD = "userinfo-precheck-fixture-marker"
USERINFO_API_URL = (
    f"https://realops:{USERINFO_LEAK_PASSWORD}@api.tianxianquant-prod.invalid/v1/"
)

# A keystore path that cannot exist on the runner. The unique marker lets a
# regression that interpolates the value into the error message be detected
# unambiguously, the same way the URL/email fixtures above work.
KEYSTORE_LEAK_MARKER = "keystore-precheck-fixture-marker"
NONEXISTENT_KEYSTORE = (
    f"/tmp/tianxianquant-{KEYSTORE_LEAK_MARKER}-DO-NOT-CREATE.jks"
)

WRAPPER_KEYSTORE_MARKER = "keystore-wrapper-[fixture]-marker"
WRAPPER_STORE_PASSWORD_MARKER = "store-password-wrapper-[fixture]-marker?"
WRAPPER_KEY_ALIAS_MARKER = "key-alias-wrapper-[fixture]"
WRAPPER_KEY_PASSWORD_TAIL_MARKER = "password-tail-*suffix?"
WRAPPER_KEY_PASSWORD_MARKER = f"{WRAPPER_KEY_ALIAS_MARKER}-{WRAPPER_KEY_PASSWORD_TAIL_MARKER}"

# Vars the precheck reads. Cleared from the env before each run so a stray
# value in the developer's shell can't mask a regression.
PRECHECK_INPUT_VARS = (
    "TIANXIAN_PRODUCTION_API_BASE_URL",
    "TIANXIAN_PRIVACY_POLICY_URL",
    "TIANXIAN_TERMS_URL",
    "TIANXIAN_DATA_DISCLAIMER_URL",
    "TIANXIAN_SUPPORT_EMAIL",
    "TIANXIAN_RELEASE_KEYSTORE",
)

VERIFY_WRAPPER_INPUT_VARS = PRECHECK_INPUT_VARS + (
    "TIANXIAN_RELEASE_STORE_PASSWORD",
    "TIANXIAN_RELEASE_KEY_ALIAS",
    "TIANXIAN_RELEASE_KEY_PASSWORD",
    "ORG_GRADLE_PROJECT_tianxianReleaseKeystore",
    "ORG_GRADLE_PROJECT_tianxianReleaseStorePassword",
    "ORG_GRADLE_PROJECT_tianxianReleaseKeyAlias",
    "ORG_GRADLE_PROJECT_tianxianReleaseKeyPassword",
)


def _run_precheck(env_overrides: dict[str, str]) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    for key in PRECHECK_INPUT_VARS:
        env.pop(key, None)
    env.update(env_overrides)
    return subprocess.run(
        ["bash", str(PRECHECK_SCRIPT)],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )


def _copy_release_gate_scripts(target_root: Path) -> Path:
    scripts_dir = target_root / "scripts"
    scripts_dir.mkdir(parents=True)
    copied_precheck = scripts_dir / "check_paid_release_inputs.sh"
    copied_verify = scripts_dir / "verify_paid_release_config.sh"
    shutil.copy2(PRECHECK_SCRIPT, copied_precheck)
    shutil.copy2(VERIFY_SCRIPT, copied_verify)
    copied_precheck.chmod(0o755)
    copied_verify.chmod(0o755)
    return copied_verify


def _copy_build_release_script(target_root: Path) -> Path:
    scripts_dir = target_root / "TianXianQuant" / "scripts"
    scripts_dir.mkdir(parents=True)
    copied_build = scripts_dir / "build_release_artifacts.sh"
    shutil.copy2(BUILD_RELEASE_SCRIPT, copied_build)
    copied_build.chmod(0o755)
    return copied_build


def _valid_release_gate_env() -> dict[str, str]:
    return {
        "TIANXIAN_PRODUCTION_API_BASE_URL": "https://api.tianxianquant-prod.invalid/v1/",
        "TIANXIAN_PRIVACY_POLICY_URL": "https://legal.tianxianquant-prod.invalid/privacy",
        "TIANXIAN_TERMS_URL": "https://legal.tianxianquant-prod.invalid/terms",
        "TIANXIAN_DATA_DISCLAIMER_URL": "https://legal.tianxianquant-prod.invalid/data",
        "TIANXIAN_SUPPORT_EMAIL": "support@tianxianquant-prod.invalid",
    }


def test_precheck_script_exists_and_is_executable() -> None:
    assert PRECHECK_SCRIPT.is_file(), f"missing precheck script: {PRECHECK_SCRIPT}"
    assert os.access(PRECHECK_SCRIPT, os.X_OK), f"precheck script is not executable: {PRECHECK_SCRIPT}"


def test_precheck_rejects_placeholder_url_and_email() -> None:
    result = _run_precheck(
        {
            "TIANXIAN_PRODUCTION_API_BASE_URL": PLACEHOLDER_API_URL,
            "TIANXIAN_SUPPORT_EMAIL": PLACEHOLDER_SUPPORT_EMAIL,
        }
    )
    assert result.returncode != 0, (
        "precheck exited 0 with placeholder inputs; "
        f"stdout={result.stdout!r}"
    )
    assert "TIANXIAN_PRODUCTION_API_BASE_URL" in result.stderr, (
        "operator-facing error must name the failing URL variable; "
        f"stderr={result.stderr!r}"
    )
    assert "TIANXIAN_SUPPORT_EMAIL" in result.stderr, (
        "operator-facing error must name the failing email variable; "
        f"stderr={result.stderr!r}"
    )


def test_precheck_does_not_echo_literal_env_values() -> None:
    result = _run_precheck(
        {
            "TIANXIAN_PRODUCTION_API_BASE_URL": PLACEHOLDER_API_URL,
            "TIANXIAN_SUPPORT_EMAIL": PLACEHOLDER_SUPPORT_EMAIL,
        }
    )
    combined = result.stdout + result.stderr
    assert PLACEHOLDER_API_URL not in combined, (
        "precheck leaked the URL value into output - error messages must "
        "name only the variable, never its contents, so signing/payment "
        "URLs cannot end up in CI logs."
    )
    assert PLACEHOLDER_SUPPORT_EMAIL not in combined, (
        "precheck leaked the email value into output - error messages must "
        "name only the variable, never its contents."
    )


def test_precheck_rejects_url_with_embedded_userinfo() -> None:
    # An https:// URL with ``user:password@`` in the authority is syntactically
    # valid and would pass the existing scheme + placeholder-host checks, but
    # ``verify_paid_release_config.sh`` then forwards the value to Gradle on
    # the command line. That command line is echoed verbatim into CI logs and
    # the credentials would also end up baked into BuildConfig at compile
    # time. Reject the URL before Gradle sees it.
    result = _run_precheck(
        {"TIANXIAN_PRODUCTION_API_BASE_URL": USERINFO_API_URL}
    )
    assert result.returncode != 0, (
        "precheck accepted an https:// URL with embedded userinfo - "
        "credentials in the authority would be forwarded to Gradle and "
        "leak into CI logs/BuildConfig. "
        f"stdout={result.stdout!r}"
    )
    assert "TIANXIAN_PRODUCTION_API_BASE_URL" in result.stderr, (
        "operator-facing error must name the failing variable so the right "
        f"URL is fixed; stderr={result.stderr!r}"
    )
    combined = result.stdout + result.stderr
    assert USERINFO_LEAK_PASSWORD not in combined, (
        "precheck leaked the embedded password into output - the rejection "
        "message must not echo the credential portion of the URL or it "
        "defeats the purpose of catching the leak."
    )
    assert USERINFO_API_URL not in combined, (
        "precheck leaked the full URL (including credentials) into output."
    )


def test_precheck_redacts_keystore_path_on_missing_file() -> None:
    # Symmetric with the URL/email redaction guard: when the keystore path is
    # bad, the error must name the variable but never echo the path itself.
    # Local keystore paths embed developer or CI-runner directory layouts,
    # and verify_paid_release_config.sh streams stderr straight into job
    # logs - a "helpful" refactor that interpolated $TIANXIAN_RELEASE_KEYSTORE
    # into the error would leak that layout. Lock the redaction in.
    from pathlib import Path as _Path

    assert not _Path(NONEXISTENT_KEYSTORE).exists(), (
        "fixture path unexpectedly exists; pick a different marker so the "
        "test exercises the missing-file branch"
    )
    result = _run_precheck({"TIANXIAN_RELEASE_KEYSTORE": NONEXISTENT_KEYSTORE})
    assert result.returncode != 0, (
        "precheck accepted a TIANXIAN_RELEASE_KEYSTORE pointing at a "
        f"nonexistent file. stdout={result.stdout!r}"
    )
    assert "TIANXIAN_RELEASE_KEYSTORE" in result.stderr, (
        "operator-facing error must name the failing keystore variable; "
        f"stderr={result.stderr!r}"
    )
    combined = result.stdout + result.stderr
    assert KEYSTORE_LEAK_MARKER not in combined, (
        "precheck leaked the keystore path marker into output - keystore "
        "paths must be redacted from precheck stderr the same way URL and "
        "email values are."
    )
    assert NONEXISTENT_KEYSTORE not in combined, (
        "precheck leaked the full keystore path into output."
    )


def test_release_gate_materializes_keystore_secret_before_precheck() -> None:
    text = RELEASE_GATE_WORKFLOW.read_text(encoding="utf-8")
    assert "TIANXIAN_RELEASE_KEYSTORE_BASE64" in text, (
        "GitHub release gate must accept a base64-encoded keystore secret and "
        "materialize it to a runner-local file before the shell precheck runs."
    )
    assert "base64 --decode" in text
    assert "${{ runner.temp }}/tianxian-release.jks" in text
    assert "TIANXIAN_RELEASE_KEYSTORE: ${{ runner.temp }}/tianxian-release.jks" in text
    assert "TIANXIAN_RELEASE_KEYSTORE: ${{ secrets.TIANXIAN_RELEASE_KEYSTORE }}" not in text, (
        "the precheck expects TIANXIAN_RELEASE_KEYSTORE to be a file path, not "
        "raw secret contents."
    )


def test_verify_wrapper_does_not_template_keystore_path_into_error_text() -> None:
    text = VERIFY_SCRIPT.read_text(encoding="utf-8")
    assert "missing file: $TIANXIAN_RELEASE_KEYSTORE" not in text, (
        "verify wrapper must not interpolate the local keystore path into "
        "missing-file errors."
    )


def test_build_release_wrapper_does_not_template_keystore_path_into_error_text() -> None:
    text = BUILD_RELEASE_SCRIPT.read_text(encoding="utf-8")
    assert "missing file: $TIANXIAN_RELEASE_KEYSTORE" not in text, (
        "release artifact wrapper must not interpolate the local keystore path "
        "into missing-file errors."
    )


def test_release_signing_values_are_not_gradle_argv_examples() -> None:
    for path in RELEASE_SIGNING_ARGV_GUARD_FILES:
        text = path.read_text(encoding="utf-8")
        assert "-PtianxianRelease" not in text, (
            f"{path.relative_to(REPO_ROOT)} must not document or pass release "
            "signing values as Gradle -P argv properties."
        )


def test_release_docs_keep_signing_secret_examples_synthetic() -> None:
    expected = {
        (path, name)
        for path in RELEASE_SIGNING_DOC_FILES
        for name in DOC_SIGNING_SYNTHETIC_VALUES
    }
    seen: set[tuple[Path, str]] = set()

    for path in RELEASE_SIGNING_DOC_FILES:
        text = path.read_text(encoding="utf-8")
        for match in DOC_SIGNING_SECRET_ASSIGNMENT_RE.finditer(text):
            name = match.group("name")
            value = match.group("value")
            seen.add((path, name))
            assert value in DOC_SIGNING_SYNTHETIC_VALUES[name], (
                f"{path.relative_to(REPO_ROOT)} must keep {name} examples "
                "synthetic; use one of the documented placeholder values "
                "instead of a real-looking signing value."
            )

    missing = expected - seen
    assert not missing, (
        "release signing docs must keep explicit synthetic examples for "
        f"{sorted((path.relative_to(REPO_ROOT).as_posix(), name) for path, name in missing)}"
    )


def test_verify_wrapper_keeps_release_signing_values_out_of_gradle_argv(
    tmp_path: Path,
) -> None:
    verify_script = _copy_release_gate_scripts(tmp_path)
    app_dir = tmp_path / "TianXianQuant"
    app_dir.mkdir()

    args_file = tmp_path / "gradle-args.txt"
    env_file = tmp_path / "gradle-env.txt"
    gradlew = app_dir / "gradlew"
    gradlew.write_text(
        textwrap.dedent(
            """\
            #!/usr/bin/env bash
            set -euo pipefail

            : > "$VERIFY_WRAPPER_ARGS_FILE"
            for arg in "$@"; do
              printf 'argv=%s\\n' "$arg" >&2
              printf '%s\\n' "$arg" >> "$VERIFY_WRAPPER_ARGS_FILE"
            done

            {
              if [[ "${ORG_GRADLE_PROJECT_tianxianReleaseKeystore:-}" == "${TIANXIAN_RELEASE_KEYSTORE:-}" ]]; then
                echo "keystore=ok"
              else
                echo "keystore=bad"
              fi
              if [[ "${ORG_GRADLE_PROJECT_tianxianReleaseStorePassword:-}" == "${TIANXIAN_RELEASE_STORE_PASSWORD:-}" ]]; then
                echo "store_password=ok"
              else
                echo "store_password=bad"
              fi
              if [[ "${ORG_GRADLE_PROJECT_tianxianReleaseKeyAlias:-}" == "${TIANXIAN_RELEASE_KEY_ALIAS:-}" ]]; then
                echo "key_alias=ok"
              else
                echo "key_alias=bad"
              fi
              if [[ "${ORG_GRADLE_PROJECT_tianxianReleaseKeyPassword:-}" == "${TIANXIAN_RELEASE_KEY_PASSWORD:-}" ]]; then
                echo "key_password=ok"
              else
                echo "key_password=bad"
              fi
            } > "$VERIFY_WRAPPER_ENV_FILE"

            echo "stub gradle failure after argv capture: $TIANXIAN_RELEASE_KEYSTORE" >&2
            echo "stdout leak probe: $TIANXIAN_RELEASE_STORE_PASSWORD $TIANXIAN_RELEASE_KEY_ALIAS $TIANXIAN_RELEASE_KEY_PASSWORD"
            exit 42
            """
        ),
        encoding="utf-8",
    )
    gradlew.chmod(0o755)

    keystore = tmp_path / f"{WRAPPER_KEYSTORE_MARKER}.jks"
    keystore.write_text("synthetic keystore fixture", encoding="utf-8")

    env = os.environ.copy()
    for key in VERIFY_WRAPPER_INPUT_VARS:
        env.pop(key, None)
    env.update(
        _valid_release_gate_env()
        | {
            "TIANXIAN_RELEASE_KEYSTORE": str(keystore),
            "TIANXIAN_RELEASE_STORE_PASSWORD": WRAPPER_STORE_PASSWORD_MARKER,
            "TIANXIAN_RELEASE_KEY_ALIAS": WRAPPER_KEY_ALIAS_MARKER,
            "TIANXIAN_RELEASE_KEY_PASSWORD": WRAPPER_KEY_PASSWORD_MARKER,
            "VERIFY_WRAPPER_ARGS_FILE": str(args_file),
            "VERIFY_WRAPPER_ENV_FILE": str(env_file),
        }
    )

    result = subprocess.run(
        ["bash", str(verify_script)],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 42, (
        "fixture gradlew should have been reached after precheck; "
        f"stdout={result.stdout!r}; stderr={result.stderr!r}"
    )
    assert env_file.read_text(encoding="utf-8").splitlines() == [
        "keystore=ok",
        "store_password=ok",
        "key_alias=ok",
        "key_password=ok",
    ]

    gradle_argv = args_file.read_text(encoding="utf-8")
    combined = result.stdout + result.stderr + gradle_argv
    for marker in (
        WRAPPER_KEYSTORE_MARKER,
        WRAPPER_STORE_PASSWORD_MARKER,
        WRAPPER_KEY_ALIAS_MARKER,
        WRAPPER_KEY_PASSWORD_MARKER,
        WRAPPER_KEY_PASSWORD_TAIL_MARKER,
    ):
        assert marker not in combined, (
            "verify wrapper leaked a signing fixture marker into Gradle argv "
            "or failure output."
        )
    assert "tianxianReleaseStorePassword" not in gradle_argv
    assert "tianxianReleaseKeyPassword" not in gradle_argv


def test_build_release_wrapper_keeps_release_signing_values_out_of_gradle_argv(
    tmp_path: Path,
) -> None:
    build_script = _copy_build_release_script(tmp_path)
    app_dir = tmp_path / "TianXianQuant"

    args_file = tmp_path / "gradle-args.txt"
    env_file = tmp_path / "gradle-env.txt"
    gradlew = app_dir / "gradlew"
    gradlew.write_text(
        textwrap.dedent(
            """\
            #!/usr/bin/env bash
            set -euo pipefail

            : > "$BUILD_WRAPPER_ARGS_FILE"
            for arg in "$@"; do
              printf 'argv=%s\\n' "$arg" >&2
              printf '%s\\n' "$arg" >> "$BUILD_WRAPPER_ARGS_FILE"
            done

            {
              if [[ "${ORG_GRADLE_PROJECT_tianxianReleaseKeystore:-}" == "${TIANXIAN_RELEASE_KEYSTORE:-}" ]]; then
                echo "keystore=ok"
              else
                echo "keystore=bad"
              fi
              if [[ "${ORG_GRADLE_PROJECT_tianxianReleaseStorePassword:-}" == "${TIANXIAN_RELEASE_STORE_PASSWORD:-}" ]]; then
                echo "store_password=ok"
              else
                echo "store_password=bad"
              fi
              if [[ "${ORG_GRADLE_PROJECT_tianxianReleaseKeyAlias:-}" == "${TIANXIAN_RELEASE_KEY_ALIAS:-}" ]]; then
                echo "key_alias=ok"
              else
                echo "key_alias=bad"
              fi
              if [[ "${ORG_GRADLE_PROJECT_tianxianReleaseKeyPassword:-}" == "${TIANXIAN_RELEASE_KEY_PASSWORD:-}" ]]; then
                echo "key_password=ok"
              else
                echo "key_password=bad"
              fi
            } > "$BUILD_WRAPPER_ENV_FILE"

            echo "stub release gradle failure after argv capture: $TIANXIAN_RELEASE_KEYSTORE" >&2
            echo "stdout leak probe: $TIANXIAN_RELEASE_STORE_PASSWORD $TIANXIAN_RELEASE_KEY_ALIAS $TIANXIAN_RELEASE_KEY_PASSWORD"
            exit 42
            """
        ),
        encoding="utf-8",
    )
    gradlew.chmod(0o755)

    keystore = tmp_path / f"{WRAPPER_KEYSTORE_MARKER}.jks"
    keystore.write_text("synthetic keystore fixture", encoding="utf-8")

    env = os.environ.copy()
    for key in VERIFY_WRAPPER_INPUT_VARS:
        env.pop(key, None)
    env.update(
        {
            "JAVA_HOME": str(tmp_path / "synthetic-jdk17"),
            "TIANXIAN_RELEASE_KEYSTORE": str(keystore),
            "TIANXIAN_RELEASE_STORE_PASSWORD": WRAPPER_STORE_PASSWORD_MARKER,
            "TIANXIAN_RELEASE_KEY_ALIAS": WRAPPER_KEY_ALIAS_MARKER,
            "TIANXIAN_RELEASE_KEY_PASSWORD": WRAPPER_KEY_PASSWORD_MARKER,
            "BUILD_WRAPPER_ARGS_FILE": str(args_file),
            "BUILD_WRAPPER_ENV_FILE": str(env_file),
        }
    )

    result = subprocess.run(
        ["bash", str(build_script)],
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 42, (
        "fixture gradlew should have been reached by the release artifact wrapper; "
        f"stdout={result.stdout!r}; stderr={result.stderr!r}"
    )
    assert env_file.read_text(encoding="utf-8").splitlines() == [
        "keystore=ok",
        "store_password=ok",
        "key_alias=ok",
        "key_password=ok",
    ]

    gradle_argv = args_file.read_text(encoding="utf-8")
    combined = result.stdout + result.stderr + gradle_argv
    for marker in (
        WRAPPER_KEYSTORE_MARKER,
        WRAPPER_STORE_PASSWORD_MARKER,
        WRAPPER_KEY_ALIAS_MARKER,
        WRAPPER_KEY_PASSWORD_MARKER,
        WRAPPER_KEY_PASSWORD_TAIL_MARKER,
    ):
        assert marker not in combined, (
            "release artifact wrapper leaked a signing fixture marker into "
            "Gradle argv or failure output."
        )
    assert "tianxianReleaseStorePassword" not in gradle_argv
    assert "tianxianReleaseKeyPassword" not in gradle_argv
