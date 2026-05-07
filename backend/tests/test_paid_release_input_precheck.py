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
