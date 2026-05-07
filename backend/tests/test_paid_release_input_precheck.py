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
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PRECHECK_SCRIPT = REPO_ROOT / "scripts" / "check_paid_release_inputs.sh"

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
