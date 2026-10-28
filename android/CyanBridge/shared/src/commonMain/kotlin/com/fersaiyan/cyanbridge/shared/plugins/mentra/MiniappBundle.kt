package com.fersaiyan.cyanbridge.shared.plugins.mentra

import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgException
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.MmpkgZip
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.RawInflater
import com.fersaiyan.cyanbridge.shared.devices.memomind.webplugin.ZipLimits

/**
 * A downloaded, verified miniapp bundle: the ZIP `mentra-miniapp pack` builds (`miniapp.json`,
 * `background/index.js`, `ui/…`, `icon.png`, optionally `META-INF/MENTRA.SIG`).
 */
class MiniappBundle(
    val manifest: MiniappManifest,
    val manifestBytes: ByteArray,
    val files: Map<String, ByteArray>,
    val sha256: String,
    /** The bundle carries a publisher signature. It is not verified yet (see the design doc). */
    val signed: Boolean,
) {
    companion object {
        const val SIGNATURE = "META-INF/MENTRA.SIG"

        /** The Mentra host's caps (`miniappFetch.ts`: ZIP ≤ 50 MB). */
        val LIMITS = ZipLimits(
            maxPackageBytes = MiniappSource.MAX_BUNDLE_BYTES,
            maxExtractedBytes = 150L * 1024 * 1024,
            maxFileBytes = MiniappSource.MAX_BUNDLE_BYTES,
            maxFiles = 2_000,
        )

        /**
         * Verifies [archive] against the served manifest: the ZIP's SHA-256 is `bundle.sha256`,
         * the ZIP's own `miniapp.json` names the same package and version, and both entry
         * files are present. Throws [MiniappException].
         */
        fun read(archive: ByteArray, served: MiniappSource.ServedManifest, inflater: RawInflater): MiniappBundle {
            val sha = Sha256Hex.of(archive)
            if (sha != served.bundle.sha256) throw MiniappException("The bundle does not match its published checksum")
            val entries = try {
                MmpkgZip.read(archive, inflater, LIMITS)
            } catch (error: MmpkgException) {
                throw MiniappException(error.message ?: "The bundle is not a valid ZIP")
            }
            val manifestBytes = entries.remove(MiniappManifest.FILE_NAME) ?: throw MiniappException("The bundle has no miniapp.json")
            val manifest = MiniappManifest.parse(manifestBytes.decodeToString())
            if (manifest.packageName != served.manifest.packageName || manifest.version != served.manifest.version) {
                throw MiniappException("The bundle is ${manifest.packageName} ${manifest.version}, not the published ${served.manifest.packageName} ${served.manifest.version}")
            }
            if (manifest.backgroundEntry !in entries) throw MiniappException("The bundle has no ${manifest.backgroundEntry}")
            manifest.uiEntry?.let { if (it !in entries) throw MiniappException("The bundle has no $it") }
            val signed = entries.remove(SIGNATURE) != null
            return MiniappBundle(manifest, manifestBytes, entries, sha, signed)
        }
    }
}
