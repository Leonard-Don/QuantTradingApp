"""Static checks for the Android P0 workflow.

The P0 workflow is the fastest CI signal that the Android app remains
buildable. Keep the demo APK assembly wired here so VIP/demo builds cannot
silently drift away from local verification.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_P0_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "android-p0.yml"
DEMO_ASSEMBLE_COMMAND = "./gradlew :app:assembleDemo --console=plain"


def _verify_job_body() -> str:
    text = ANDROID_P0_WORKFLOW.read_text(encoding="utf-8")
    match = re.search(r"(?ms)^  verify:\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:|\Z)", text)
    assert match, "android-p0.yml must keep the Android verification job named `verify`."
    return match.group("body")


def _step_body(job_body: str, step_name: str) -> str:
    match = re.search(
        rf"(?ms)^      - name: {re.escape(step_name)}\n(?P<body>.*?)(?=^      - name: |\Z)",
        job_body,
    )
    assert match, f"android-p0.yml verify job is missing the `{step_name}` step."
    return match.group("body")


def test_android_p0_workflow_builds_demo_apk() -> None:
    step = _step_body(_verify_job_body(), "Build demo APK")

    assert "cd QuantTradingApp" in step, (
        "the demo APK gate should run from the Android project root so Gradle "
        "uses the same wrapper and settings as local verification."
    )
    assert DEMO_ASSEMBLE_COMMAND in step, (
        "android-p0.yml must assemble the demo APK with "
        f"`{DEMO_ASSEMBLE_COMMAND}` so VIP/demo builds stay CI-covered."
    )
