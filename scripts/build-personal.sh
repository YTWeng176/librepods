#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
if [[ ! -f android/local.properties ]]; then
  echo '请按 docs/PERSONAL.md 配置 SDK 与个人签名到 android/local.properties。' >&2
  exit 1
fi
python3 scripts/check-personal-resources.py
if ! python3 - <<'PY'
from pathlib import Path
text = Path('android/local.properties').read_text()
required = ('RELEASE_STORE_FILE', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD')
values = dict(line.split('=', 1) for line in text.splitlines() if '=' in line and not line.lstrip().startswith('#'))
raise SystemExit(0 if all(values.get(key, '').strip() for key in required) else 1)
PY
then
  echo '缺少个人签名配置，停止构建，避免产出无法安装的 unsigned APK。' >&2
  exit 1
fi
(
  cd android
  ./gradlew :core:testDebugUnitTest :app:testFossDebugUnitTest :app:assembleFossRelease --console=plain "$@"
)
mkdir -p dist
cp android/app/build/outputs/apk/foss/release/app-foss-release.apk dist/LibrePods-Personal-2.0.0-alpha.4-personal.1.apk
(
  cd dist
  python3 - <<'PY'
from pathlib import Path
import hashlib
apk = Path('LibrePods-Personal-2.0.0-alpha.4-personal.1.apk')
Path('SHA256SUMS').write_text(f'{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}\n')
PY
)
echo "产物：$repo_root/dist/LibrePods-Personal-2.0.0-alpha.4-personal.1.apk"
