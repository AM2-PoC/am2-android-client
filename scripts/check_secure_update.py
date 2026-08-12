#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
required={
'app/src/main/java/com/am2/am2/update/UpdateVerifier.kt':['com.am2.tik','APPROVED_UPDATE_SIGNER_SHA256','sha256','apkContentsSigners','delete'],
'app/src/main/java/com/am2/am2/AboutActivity.kt':['UpdateVerifier.verify','showVerifiedInstallDialog','FileProvider.getUriForFile','canonicalPath','followRedirects(false)'],
'app/src/main/AndroidManifest.xml':['android.permission.REQUEST_INSTALL_PACKAGES','android:usesCleartextTraffic="false"'],
'app/src/main/res/xml/file_paths.xml':['files-path name="verified_updates" path="updates/"'],
'.github/workflows/release-candidate.yml':['workflow_dispatch','environment: android-release','AM2_CLIENT_KEYSTORE_BASE64','apksigner" verify','"$ANDROID_HOME/build-tools/35.0.0/zipalign"','signer_sha256','source_commit','if: ${{ always() }}'],
}
errors=[]
for path,tokens in required.items():
 p=root/path
 if not p.is_file(): errors.append(f'missing {path}'); continue
 text=p.read_text()
 for token in tokens:
  if token not in text: errors.append(f'{path}: missing {token}')
if errors:
 print('\n'.join(errors),file=sys.stderr); sys.exit(1)
print('secure updater contract: PASS')
