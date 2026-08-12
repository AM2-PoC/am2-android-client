#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
workflow=root/'.github/workflows/release-candidate.yml'
required={
'app/src/main/java/com/am2/am2/update/UpdateVerifier.kt':['com.am2.tik','APPROVED_UPDATE_SIGNER_SHA256','sha256','signingCertificateHistory','delete'],
'.github/workflows/release-candidate.yml':['workflow_dispatch','environment: android-release','apksigner verify','AM2_CLIENT_KEYSTORE_BASE64','if: ${{ always() }}','sha256','signer_sha256','source_commit'],
}
errors=[]
for path,tokens in required.items():
 p=root/path
 if not p.is_file(): errors.append(f'missing {path}'); continue
 text=p.read_text()
 for token in tokens:
  if token not in text: errors.append(f'{path}: missing {token}')
if errors:
 print('
'.join(errors),file=sys.stderr); sys.exit(1)
print('secure updater contract: PASS')
