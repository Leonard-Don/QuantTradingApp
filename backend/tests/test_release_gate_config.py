"""Static consistency checks for the paid-release configuration gate.

The release gate spans three sources that must agree on the same set of
``TIANXIAN_*`` environment variables:

* ``release.env.example`` - operator-facing template
* ``scripts/verify_paid_release_config.sh`` - what the local/CI gate reads
* ``.github/workflows/release-gate.yml`` - what GitHub Actions injects

Drift between these surfaces is silent: a missing secret in CI fails late,
and an unused secret invites stale documentation. These tests parse each
file with regular expressions and use only the variable *names* - never
their values - so they need no real secrets to run.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ENV_EXAMPLE = REPO_ROOT / "release.env.example"
VERIFY_SCRIPT = REPO_ROOT / "scripts" / "verify_paid_release_config.sh"
RELEASE_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "release-gate.yml"

# Vars documented in release.env.example for backend deployment that the
# Android paid-release gate intentionally does not consume.
BACKEND_ONLY_EXAMPLE_VARS = frozenset(
    {
        "TIANXIAN_DB_PATH",
        "TIANXIAN_PAYMENT_CALLBACK_SECRET",
        "TIANXIAN_REQUIRE_CALLBACK_SIGNATURE",
        "TIANXIAN_ADMIN_TOKEN",
    }
)


def _example_exports() -> set[str]:
    text = ENV_EXAMPLE.read_text(encoding="utf-8")
    return set(re.findall(r"^export\s+(TIANXIAN_[A-Z_]+)=", text, flags=re.MULTILINE))


def _script_references() -> set[str]:
    text = VERIFY_SCRIPT.read_text(encoding="utf-8")
    return set(re.findall(r"TIANXIAN_[A-Z_]+", text))


def _workflow_env_keys() -> set[str]:
    text = RELEASE_WORKFLOW.read_text(encoding="utf-8")
    return set(re.findall(r"^\s+(TIANXIAN_[A-Z_]+):\s*\$\{\{", text, flags=re.MULTILINE))


def test_release_gate_sources_exist() -> None:
    for path in (ENV_EXAMPLE, VERIFY_SCRIPT, RELEASE_WORKFLOW):
        assert path.is_file(), f"missing release-gate source: {path}"


def test_release_env_example_declares_every_script_variable() -> None:
    referenced = _script_references()
    declared = _example_exports()
    missing = referenced - declared
    assert not missing, (
        "verify_paid_release_config.sh references TIANXIAN_ vars not declared in "
        f"release.env.example: {sorted(missing)}. Add a placeholder export to the "
        "example so operators know to populate it."
    )


def test_release_env_example_has_no_unknown_android_vars() -> None:
    declared = _example_exports()
    referenced = _script_references()
    extras = declared - referenced - BACKEND_ONLY_EXAMPLE_VARS
    assert not extras, (
        f"release.env.example declares TIANXIAN_ vars that are neither referenced "
        f"by verify_paid_release_config.sh nor on the backend-only allowlist: "
        f"{sorted(extras)}. Add them to BACKEND_ONLY_EXAMPLE_VARS if intentional, "
        "or remove them if stale."
    )


def test_release_workflow_env_matches_script_references() -> None:
    workflow_keys = _workflow_env_keys()
    referenced = _script_references()
    only_in_workflow = workflow_keys - referenced
    only_in_script = referenced - workflow_keys
    assert not only_in_workflow, (
        "release-gate.yml exposes TIANXIAN_ secrets that the verify script never "
        f"reads: {sorted(only_in_workflow)}. Either reference them in the script "
        "or drop them from the workflow."
    )
    assert not only_in_script, (
        "verify_paid_release_config.sh reads TIANXIAN_ vars that release-gate.yml "
        f"does not inject: {sorted(only_in_script)}. Add the matching `secrets.*` "
        "entries to the workflow env block so CI gate runs see them."
    )


def test_script_references_a_nonempty_set() -> None:
    # Sanity check: regression guard against a future refactor that breaks the
    # regex (e.g. quoting changes) and silently makes the consistency tests
    # vacuously pass.
    assert _script_references(), (
        "no TIANXIAN_* references parsed from verify_paid_release_config.sh - "
        "the regex in this test may be out of date with the script."
    )
