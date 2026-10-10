# Third-party notices

CyanBridge is a mixed-license repository. The root Apache-2.0 license covers
CyanBridge-authored material unless a file or directory carries a different
license or copyright notice. Third-party material keeps its original terms.

The entries below call out the components that are easy to mistake for
CyanBridge-owned code. This is a licensing map, not a replacement for the
upstream license texts.

| Component | Where it appears | License or terms | Upstream |
| --- | --- | --- | --- |
| Meizu MYVU Client by Panny777 | `android/CyanBridge/app/src/main/myvu-upstream/` | MIT. The submodule keeps the upstream license and copyright notice. | [Panny777/Meizu-Myvu-Client](https://github.com/Panny777/Meizu-Myvu-Client) |
| MoYoung glasses SDK | `android/CyanBridge/app/libs/moyoung_glasses_sdk_0.0.7_20260624.aar` and `android/CyanBridge/third_party/moyoung_glasses_sdk/` | GPL-3.0 upstream. The GPL text is kept beside the component. | [liangqian609/moyoung_glasses_ble_plugin](https://github.com/liangqian609/moyoung_glasses_ble_plugin) |
| HeyCyan Android SDK | `android/glasses_sdk_20250723_v01.aar` and `android/CyanBridge/app/libs/glasses_sdk_20250723_v01.aar` | Vendor material. It is not relicensed under Apache-2.0 by this repository. | Vendor-supplied SDK |
| QCSDK iOS framework | `ios/QCSDK.framework/` | Vendor material with upstream copyright notices. It is not relicensed under Apache-2.0 by this repository. | Vendor-supplied SDK |
| Vendor SDK guides | `android/Android_SDK_Development_Guide_CN.pdf` and `ios/iOS_SDK_Development_Guide.pdf` | Vendor documentation. It is not covered by the CyanBridge Apache-2.0 license. | Vendor-supplied documentation |
| Meta Wearables Device Access Toolkit | Resolved as Android dependencies when Meta support is enabled | Subject to the Meta Wearables Developer Terms and related policies, not the CyanBridge license. | [facebook/meta-wearables-dat-android](https://github.com/facebook/meta-wearables-dat-android) |
| Downloadable AI models | Downloaded or imported through CyanBridge model features | Each model keeps its upstream model license. A model download is not relicensed as Apache-2.0. | Model-specific |

## Referenced projects

CyanBridge also names projects that informed parts of the design without making
their code part of the CyanBridge license:

- [OpenVision](https://github.com/rayl15/OpenVision) is MIT-licensed.
- [private-agent](https://github.com/orailnoor/private-agent) was reviewed as
  architectural inspiration for the Local Agent work. No explicit root license
  was found during the licensing review, so its code should not be copied into
  CyanBridge without separate permission or a clear license.

Other package-manager dependencies keep their upstream licenses. Their presence
in an Android, iOS, or desktop build does not make them Apache-2.0.

## GPL note

The MoYoung AAR is linked directly by the Android app today. A mixed-license
source tree does not cancel GPL obligations that may apply to a distributed
combined work. The Apache-2.0 license covers CyanBridge-owned code; it does not
turn the MoYoung SDK into Apache-2.0 code or settle the licensing of a binary
that combines it with vendor SDKs.

For the repository-wide rules, see [LICENSING.md](LICENSING.md).
