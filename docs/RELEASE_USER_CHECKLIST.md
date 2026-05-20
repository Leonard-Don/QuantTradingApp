# QuantTradingApp v1.0.0 — User Action Checklist

Last reviewed: 2026-05-16
Audience: the human operator of this repo (you).
Goal: hand off `app-release.aab` to a store with every gate green.

This checklist covers **only what cannot be done autonomously** — every step requires a secret, a hosted resource, a real-device action, or a business decision. Items already prepared in the repo (legal doc text, gate scripts, store-asset generators, AAB build script) are referenced but not repeated here.

Estimated wall time end-to-end: 60–90 minutes once you have a backend host.

---

## Step 1 — Provision the production backend

The Android client refuses to ship without a real HTTPS API host.

```bash
# Option A: Render Blueprint (already in render.yaml)
#   1. Push this repo to a private GitHub repo.
#   2. Visit https://dashboard.render.com/blueprints, point at the repo.
#   3. Set the secret env vars in Render's secret store:
#        QUANTTRADING_DB_PATH=/data/quanttrading.db
#        QUANTTRADING_PAYMENT_CALLBACK_SECRET=<32+ random bytes hex>
#        QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE=1
#        QUANTTRADING_ADMIN_TOKEN=<32+ random bytes hex>
#   4. Wait for Render to assign a host like https://quanttrading-api.onrender.com/
#
# Option B: self-host the FastAPI app on your own VPS with TLS terminated.
```

Validate the backend is reachable:

```bash
curl -fsSL https://<your-api-host>/health
# expect: {"status":"ok",...}
```

Record the host. It feeds `QUANTTRADING_PRODUCTION_API_BASE_URL` in Step 3 (**must end with `/`**).

---

## Step 2 — Generate the upload keystore (run this on your laptop, never in CI)

Do not generate the keystore inside the repo. The recommended path is outside any cloud-synced folder; macOS Application Support is a reasonable default:

```bash
KEYSTORE_DIR="$HOME/Library/Application Support/quanttrading"
mkdir -p "$KEYSTORE_DIR"
chmod 700 "$KEYSTORE_DIR"

# Use the JDK 17 keytool that already drives Gradle here:
"/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/keytool" \
  -genkeypair \
  -v \
  -keystore "$KEYSTORE_DIR/release.jks" \
  -alias quanttrading-upload \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=QuantTradingApp, OU=Mobile, O=<your-legal-entity>, L=<city>, ST=<province>, C=CN"

chmod 600 "$KEYSTORE_DIR/release.jks"
```

The tool will prompt twice for a password (keystore + key). Use **different** strong passwords. Stash both in a password manager (1Password / Bitwarden) under entries named `QuantTradingApp release keystore password` and `QuantTradingApp release key password`. **Do not commit, do not paste into chat, do not put in `~/.zshrc`.**

Back up `release.jks` to encrypted offline storage immediately. If you lose this file you can never publish another update to the same app listing.

Sanity-check it:

```bash
"/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/keytool" \
  -list -v -keystore "$KEYSTORE_DIR/release.jks" -alias quanttrading-upload
# expect: Certificate fingerprints (SHA-256, ...), Valid from ... until <~2052>.
```

---

## Step 3 — Author `release.env`

```bash
cp release.env.example release.env
chmod 600 release.env
$EDITOR release.env
```

Fill it with the production values. **Replace every `example.com` and every `replace-with-*` placeholder.** A correct file looks like:

```bash
export QUANTTRADING_PRODUCTION_API_BASE_URL="https://quanttrading-api.onrender.com/"
export QUANTTRADING_PRIVACY_POLICY_URL="https://<your-github-user>.github.io/<repo-name>/legal/PRIVACY_POLICY.html"
export QUANTTRADING_TERMS_URL="https://<your-github-user>.github.io/<repo-name>/legal/TERMS_OF_SERVICE.html"
export QUANTTRADING_DATA_DISCLAIMER_URL="https://<your-github-user>.github.io/<repo-name>/legal/DATA_SOURCE_DISCLAIMER.html"
export QUANTTRADING_SUPPORT_EMAIL="support@quanttrading.cn"

export QUANTTRADING_RELEASE_KEYSTORE="$HOME/Library/Application Support/quanttrading/release.jks"
export QUANTTRADING_RELEASE_STORE_PASSWORD="<value from password manager>"
export QUANTTRADING_RELEASE_KEY_ALIAS="quanttrading-upload"
export QUANTTRADING_RELEASE_KEY_PASSWORD="<value from password manager>"

export QUANTTRADING_DB_PATH="/data/quanttrading.db"
export QUANTTRADING_PAYMENT_CALLBACK_SECRET="<32-byte hex; matches Render>"
export QUANTTRADING_REQUIRE_CALLBACK_SIGNATURE="1"
export QUANTTRADING_ADMIN_TOKEN="<32-byte hex; matches Render>"
```

`release.env` is already in `.gitignore`. Confirm with `git check-ignore -v release.env`.

---

## Step 4 — Update the operating entity in the legal docs

Open each of these and replace every `【上架前由运营方填写】` placeholder with the registered Chinese-mainland operating entity name, registered address, customer-service email, and individual-information-protection officer. The support email **must match `QUANTTRADING_SUPPORT_EMAIL` exactly** or app-store reviewers will reject the listing.

- `docs/legal/PRIVACY_POLICY.md`
- `docs/legal/TERMS_OF_SERVICE.md`
- `docs/legal/DATA_SOURCE_DISCLAIMER.md`

While editing, also fill the "生效日期" (effective date) header in each file with the date you intend to publish.

---

## Step 5 — Publish the legal docs via GitHub Pages

This repo already has a `.github/workflows/` directory. A ready-to-use workflow has been added at `.github/workflows/legal-docs-pages.yml` that converts the markdown in `docs/legal/` to HTML and publishes it to GitHub Pages on push to the default branch.

To activate it:

1. Push the repo to GitHub (private is fine).
2. In the GitHub repo settings, **Pages → Build and deployment → Source = "GitHub Actions"**.
3. Push any commit on the default branch (or trigger the workflow manually from the Actions tab). The workflow renders `docs/legal/*.md` to `legal/<NAME>.html` under the Pages site.
4. After the workflow turns green, the three legal URLs become available at:
   - `https://<user>.github.io/<repo>/legal/PRIVACY_POLICY.html`
   - `https://<user>.github.io/<repo>/legal/TERMS_OF_SERVICE.html`
   - `https://<user>.github.io/<repo>/legal/DATA_SOURCE_DISCLAIMER.html`
5. Paste those URLs into `release.env` (Step 3).

If your repo is private but you need public legal URLs, switch the repo to public **or** fork only the `docs/legal/` folder to a public mirror.

---

## Step 6 — Run the gate, build the AAB

```bash
cd /Users/leonardodon/android/android
set -a
source release.env
set +a

# JDK 17 + Android SDK env (matches the wrappers)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

bash scripts/verify_paid_release_config.sh \
  && bash QuantTradingApp/scripts/build_release_artifacts.sh
```

The first command must finish with `BUILD SUCCESSFUL` and **no listed missing items**. If it fails, cross-reference `docs/RELEASE_BLOCKERS.md`.

On success, expect:

- `QuantTradingApp/app/build/outputs/apk/release/*.apk`
- `QuantTradingApp/app/build/outputs/bundle/release/*.aab` ← upload this to the store

Verify the AAB is signed with your upload key (do not skip):

```bash
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose \
  --print-certs \
  QuantTradingApp/app/build/outputs/apk/release/app-release.apk
# expect: "Verified using v2 scheme (APK Signature Scheme v2): true"
#         SHA-256 fingerprint matches Step 2 output
```

---

## Step 7 — Capture store screenshots from the signed candidate

The repo already has `scripts/capture_store_screenshots.sh` which drives an emulator smoke run and saves PNGs to `store_assets/screenshots/`. Recapture them **from the signed build**, not the unsigned engineering build.

```bash
# Install the signed APK on a freshly wiped emulator.
$ANDROID_HOME/emulator/emulator -avd Pixel_6_API_34 -wipe-data &
adb wait-for-device
adb install -r QuantTradingApp/app/build/outputs/apk/release/app-release.apk

# Capture per the manifest.
bash scripts/capture_store_screenshots.sh
```

Required files (per `store_assets/screenshots/README.md`):

| File | Surface to capture |
| --- | --- |
| `01_stock_home.png` | 选股首页 — sample quote pool, filters, data-source status |
| `02_review.png` | 复盘页 — market overview, sector rotation, snapshot entry |
| `03_community.png` | 社区/研究纪要 |
| `04_quant.png` | 量化页 — model list, historical simulation entry, disclaimer banner |
| `05_vip.png` | VIP 页 — tier list, account entitlement, "production payment not configured" notice (or live tiers after merchant signoff) |
| `06_auth.png` | 账号页 — privacy / terms / support / delete-account / notification entries |

The capture script auto-rejects blank/black frames. If a frame fails, take it manually with `adb exec-out screencap -p > store_assets/screenshots/0X_xxx.png` from the device.

Review each on a real device (not just the emulator) before uploading. Sensitive personal data (real phone numbers, real user IDs) must not appear in screenshots.

---

## Step 8 — Capture the final app icon and feature graphic

`scripts/generate_store_assets.py` already produces:

- `store_assets/icon_preview_512x512.png`
- `store_assets/feature_graphic_1024x500.png`
- `store_assets/promo_card_1200x630.png`

These are placeholder branded assets. Before submission, either:
1. Accept them as v1.0.0 launch assets (acceptable for first store-test upload), **or**
2. Replace `store_assets/icon_preview_512x512.png` with a 512×512 PNG produced from your final adaptive icon design and the feature graphic with one tuned for your launch copy.

If you replace the icon preview, also update the in-app adaptive icon under `QuantTradingApp/app/src/main/res/mipmap-anydpi-v26/` and rebuild before re-capturing screenshots.

---

## Step 9 — Pre-upload smoke (manual QA matrix)

Open `docs/MANUAL_QA_MATRIX.md` and walk the checklist on the signed APK installed on a real device. At minimum tick:

- Fresh install
- Offline launch after cached quote exists
- VIP expired state
- VIP active state (use Render admin audit page `/admin?token=...` to bump entitlement on a test account)
- Notification permission denied / granted
- Account deletion clears local + server data
- Quant historical backtest with a 6-digit stock code and 1-year range
- Tablet layout

Capture any defects as P0 issues and re-spin the build before submitting.

---

## Step 10 — Store submission

You are responsible for the human-loop steps:

- Choose a primary store (华为应用市场 / 小米应用商店 / 应用宝 / vivo / oppo / Google Play). Each has its own console.
- Fill the listing using `docs/STORE_LISTING_DRAFT.md` (short description, full description, screenshots, feature graphic).
- Paste the three hosted legal URLs into the store's compliance fields.
- Paste `QUANTTRADING_SUPPORT_EMAIL` into the developer contact field.
- Upload `app-release.aab`.
- Submit for review.

Keep `release.jks` and both passwords offline. Losing them blocks every future update.

---

## After v1.0.0 ships

The "Paid Launch" section of `docs/RELEASE_CHECKLIST.md` lists 11 items that are intentionally still open for v1.0.0 (merchant sandbox verification, licensed data-provider contract, refund/cancel policy, monitoring, security review). They are not v1.0.0 blockers, but each must be closed before turning on real paid subscriptions in production. Re-open `docs/COMMERCIALIZATION_GAP.md` to sequence those.
