# CyanBridge licensing

CyanBridge uses a mixed-license layout.

The root [Apache License 2.0](LICENSE) applies to source code and documentation
written for CyanBridge, unless a file, directory, submodule, or accompanying
notice says otherwise. Third-party code and vendor material keep their original
licenses or terms.

## How to read the repository

Use the most specific license notice available:

1. A license or SPDX header on a file controls that file.
2. A license stored inside a third-party directory or submodule controls that
   component.
3. Vendor SDKs, binaries, firmware, model weights, and vendor documentation are
   not relicensed by the root Apache-2.0 file.
4. CyanBridge-authored material without a more specific notice is Apache-2.0.

[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists the main exceptions and
where they live.

## CyanBridge code

New CyanBridge-owned source should use:

```text
SPDX-License-Identifier: Apache-2.0
```

Existing CyanBridge source is covered by the root Apache-2.0 license unless a
more specific notice applies. Do not replace or remove upstream copyright and
license headers when adapting third-party code.

## Third-party code

Third-party material stays under its upstream license. For example:

- the MYVU submodule is MIT;
- the MoYoung SDK is GPL-3.0;
- Meta DAT is governed by Meta's developer terms;
- HeyCyan/QCSDK vendor binaries and documentation are outside the root
  Apache-2.0 grant;
- model weights keep their model-specific licenses.

The root license is not a claim that CyanBridge owns those components.

## Source licenses and distributed apps are different questions

The mixed-license layout makes the source tree clear, but it does not override
the obligations created when components are combined into an APK or another
binary.

In particular, the Android app currently links
`moyoung_glasses_sdk_0.0.7_20260624.aar`, whose upstream package is GPL-3.0.
Distribution of a build containing that component may carry GPL-3.0 obligations
for the combined work. The same build also uses vendor SDK material with
separate terms. Those obligations need to be satisfied independently of the
Apache-2.0 license on CyanBridge-owned code.

If the MoYoung integration is later removed from the default build, separately
licensed, or moved behind a legally separate distribution boundary, update this
file and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) to match the actual
shipping architecture.

## Contributing

Contributions written for CyanBridge are expected to be compatible with
Apache-2.0 unless the contribution is intentionally placed in a separately
licensed component. If you bring in third-party code, keep its original license
notice and document the source and license in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
