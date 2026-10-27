# Supplied Epic Games APK — bounded static investigation

Artifact: `Epic Games_1.0.6.apk`.
SHA-256: `e52d5e7ec3d9e27b5bcd0d385b8c5790a0d7cebae8a659cc1d85efca4ce7ecaa`.

## Observed

Androguard parsed the binary manifest, ZIP inventory and DEX class declarations. Raw extracted findings are in `evidence/package.json`, `evidence/manifest.xml` and `evidence/classes.txt`. They are static facts, not observed execution.

| Question | Evidence | Status |
| --- | --- | --- |
| Identity | Manifest package `com.epicgames.portal`, label Epic Games, version 1.0.6, minimum API 26, target API 35 | Answered |
| Package architecture | 562 ZIP members; classes.dex; AndroidX Compose version resources and MainActivity declaration | Partial: implementation not decompiled |
| Library/storage | LibraryService declaration; AppUserDatabase / StoreUserDatabase DEX classes | Components present; runtime behavior unknown |
| Install/update mechanisms | install/request-install/update permissions, Android12ManualInstallReceiver, Android12SilentInstallReceiver, SelfUpdateBroadcastReceiver | Declarations present; success and privilege enforcement unknown |
| Notifications/background | FCM service, boot receiver, wake-lock and foreground-service permissions | Declarations present; actual triggers unknown |
| Entitlement gates | DEX error classes LauncherDownloadNotEntitled, LauncherDownloadCompatibleBuildNotFound, LauncherDownloadApplicationBlocked | Suggest gated download workflow; call path not traced |
| Authentication | DEX refresh-token and step-up error classes | Suggest token/step-up handling; exact protocol unknown |
| Full feature parity | No server source, API contract, emulator, authenticated session or device run | Unresolved |

## REA use and limitation

Read the repository's actual REA skill, Android guide, recovery guide and AGENTS.md. Created an isolated reconstruction branch. Installed locked tool dependencies and attempted the repository runtime build. The first attempt lacked an optional TypeScript compiler binary; a later build was stopped by automatic review because the build contacted telemetry.vercel.com. No further REA execution was attempted after the block. No successful REA Evidence record or JADX decompilation is claimed. Static facts above come from Androguard, not REA.

## Original implementation choices

Orbit prioritizes a private, local library, user-controlled classification and favorites. It does not assume Epic's privileged installer rights transfer to an independent app, or that possession of the APK supplies Epic's backend. Permission declarations alone do not demonstrate a security vulnerability or working silent installation.
