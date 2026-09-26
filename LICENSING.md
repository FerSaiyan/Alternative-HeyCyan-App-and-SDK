# CyanBridge licensing plan

## Recommended project license

Use **Apache License 2.0** for CyanBridge-authored source code.

Apache-2.0 fits CyanBridge better than MIT because it stays permissive for commercial and research use while adding an explicit patent grant. That is useful for a project centered on device protocols, mobile SDKs, local AI runtimes, and hardware integrations.

The root license should apply only to code and documentation that CyanBridge contributors have the right to license. It must not relicense vendor SDKs, third-party code, model weights, firmware, or documentation that carries separate terms.

## Why the root license is not being added yet

One current Android dependency needs to be resolved first:

- `android/CyanBridge/app/libs/moyoung_glasses_sdk_0.0.7_20260624.aar` is linked directly into the Android app.
- The upstream package is marked GPL-3.0 and the repository includes the GPL-3.0 text under `android/CyanBridge/third_party/moyoung_glasses_sdk/LICENSE`.
- GPL-3.0 treats distribution of a linked combined work differently from permissive licenses. A root Apache-2.0 license would not remove those obligations.
- The same app also links vendor SDK material whose redistribution/source terms are not documented in this repository. That combination needs a clean licensing boundary before CyanBridge claims one license for the distributable APK.

The safest engineering path is to remove the GPL MoYoung AAR from the default distributable app, obtain a different license from its copyright holder, or move that integration behind a genuinely separate component whose licensing can be handled independently.

## Current third-party inventory

| Component | Current license / terms | CyanBridge use | Treatment |
| --- | --- | --- | --- |
| Meizu MYVU Client / `myvu-upstream` | MIT | Submodule and protocol implementation reference | Keep upstream MIT copyright and license notice |
| OpenVision | MIT | Architecture and Meta integration reference | Preserve MIT notice for any copied/adapted code |
| private-agent | No explicit root license found | Architectural inspiration for Local Agent | Treat as reference only; do not copy code without explicit permission/license |
| Meta Wearables DAT | Meta Wearables Developer Terms | Meta Ray-Ban integration | Keep separate from the CyanBridge license and follow Meta's current developer terms |
| HeyCyan Android SDK `glasses_sdk_20250723_v01.aar` | Vendor/proprietary terms not documented here | Android HeyCyan integration | Exclude from Apache-2.0 scope; confirm redistribution rights |
| `ios/QCSDK.framework` and SDK guide/demo material | Vendor/proprietary terms not documented here | iOS HeyCyan integration/reference | Exclude from Apache-2.0 scope; confirm redistribution rights |
| MoYoung/W620 AAR | GPL-3.0 upstream | Linked Android dependency | Resolve before applying a clean permissive license to the distributable app |
| Downloadable AI models | Model-specific licenses | Local inference / vision | Keep model licenses separate and show them in the catalog/download UI |

## Apache-2.0 and GPL-3.0

Apache-2.0 code can be incorporated into a GPL-3.0 combined work, but the resulting combined work must satisfy GPL-3.0. The reverse is not a way to make GPL-3.0 code permissive.

That means CyanBridge can license its own source under Apache-2.0, but a distributed APK that directly links a GPL-3.0 AAR may still carry GPL-3.0 obligations for the combined work.

This becomes especially important when the same APK also contains proprietary SDKs. A proprietary SDK license may impose conditions that cannot be satisfied at the same time as GPL-3.0. The MoYoung dependency should therefore be resolved before treating the current APK as a straightforward Apache-2.0 distribution.

## Proposed repository structure

After the GPL/vendor dependency issue is resolved:

1. Add a standard root `LICENSE` containing Apache License 2.0.
2. Add `THIRD_PARTY_NOTICES.md` with the upstream project, copyright holder, license, source URL, and where it appears in CyanBridge.
3. Add a short scope statement to the README:
   - CyanBridge-authored source: Apache-2.0.
   - Third-party/vendor material: its own terms.
4. Keep third-party license files beside vendored components.
5. Add `SPDX-License-Identifier: Apache-2.0` to new CyanBridge-authored source files where practical.
6. Keep model licenses visible in the model catalog. Downloading a model should not imply that the model is Apache-2.0.

## Files that should not be covered by the future root Apache-2.0 license

At minimum:

- `android/glasses_sdk_20250723_v01.aar`
- `android/CyanBridge/app/libs/glasses_sdk_20250723_v01.aar`
- `android/CyanBridge/app/libs/moyoung_glasses_sdk_0.0.7_20260624.aar`
- `android/CyanBridge/third_party/`
- `android/Android_SDK_Development_Guide_CN.pdf`
- `ios/QCSDK.framework/`
- `ios/iOS_SDK_Development_Guide.pdf`
- vendor demo/reference code where CyanBridge does not own the copyright
- model weights and other downloaded artifacts

The `android/CyanBridge/app/src/main/myvu-upstream` submodule remains under its upstream MIT license.

## Next licensing cleanup

Before publishing a root Apache-2.0 license:

- decide whether MoYoung stays in the default APK;
- verify redistribution rights for the HeyCyan Android and iOS SDK artifacts;
- classify vendor demo/reference source directories;
- generate a third-party notice inventory for binary releases;
- then add Apache-2.0 to CyanBridge-owned code only.
