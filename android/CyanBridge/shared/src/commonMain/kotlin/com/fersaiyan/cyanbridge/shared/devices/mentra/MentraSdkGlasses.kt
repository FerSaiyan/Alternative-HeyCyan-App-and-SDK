package com.fersaiyan.cyanbridge.shared.devices.mentra

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass

/**
 * The glasses CyanBridge drives through the Mentra Bluetooth SDK, one [DeviceClass] per SDK
 * `DeviceModel`. [sdkDeviceType] is the SDK's `DeviceModel.deviceType` (both platforms map it back
 * with `DeviceModel.fromDeviceType`). The SDK runs one model at a time: scanning or connecting
 * another model drops the current link.
 *
 * Not listed, because the SDK 3.2.0-dev.227 this app links cannot run them: Mach1 and Z100 (their
 * driver needs the Vuzix Ultralite SDK, which the public SDK leaves out), Brilliant Frame (no
 * driver on either platform) and R1 (the Even Realities ring, a controller, not glasses).
 */
enum class MentraSdkModel(
    val deviceClass: DeviceClass,
    val sdkDeviceType: String,
    /** Text limits of the display; null for Mentra Live, which has none. */
    val display: MentraDisplayLimits?,
    /** Two BLE arms (left and right) that the SDK pairs and connects together. */
    val twoArms: Boolean = false,
    /** The public iOS Swift package of the same SDK version includes this model's driver. */
    val onIos: Boolean = true,
) {
    LIVE(DeviceClass.MENTRA_LIVE, "Mentra Live", display = null),
    G1(DeviceClass.EVEN_G1, "Even Realities G1", MentraDisplayLimits(columns = 40, lines = 5, maxBytes = 390), twoArms = true),
    G2(DeviceClass.EVEN_G2, "Even Realities G2", MentraDisplayLimits(columns = 40, lines = 8, maxBytes = 390), twoArms = true),

    /** Mentra's own display glasses (SDK `MENTRA_NEX`); the iOS package excludes its driver. */
    DISPLAY(DeviceClass.MENTRA_DISPLAY, "Mentra Display", MentraDisplayLimits(columns = 44, lines = 8, maxBytes = 450), onIos = false),
    NIMO(DeviceClass.NIMO, "NIMO", MentraDisplayLimits(columns = 60, lines = 11, maxBytes = 2_000)),

    /** Xingyi AR99. Mentra has no AR99 text profile; it falls back to the G1 limits, as here. */
    AR99(DeviceClass.XINGYI_AR99, "AR99", MentraDisplayLimits(columns = 40, lines = 5, maxBytes = 390)),
    ;

    val hasDisplay: Boolean get() = display != null

    companion object {
        private val byClass = entries.associateBy { it.deviceClass }

        fun forClass(deviceClass: DeviceClass?): MentraSdkModel? = deviceClass?.let(byClass::get)

        fun forSdkDeviceType(deviceType: String?): MentraSdkModel? = entries.firstOrNull { it.sdkDeviceType == deviceType }

        /** True for every family that pairs and connects through the Mentra SDK. */
        fun usesMentraSdk(deviceClass: DeviceClass?): Boolean = forClass(deviceClass) != null

        /** True for the Mentra SDK families with a display. */
        fun isDisplayGlasses(deviceClass: DeviceClass?): Boolean = forClass(deviceClass)?.hasDisplay == true

        private val evenArmName = Regex("""^Even (G1|G2)_(\d+)_([LR])_""")

        /**
         * The display model a BLE name belongs to, by the SDK scanners' own name rules: "Even G1_<id>_L_…"
         * and "…_R_…" arms, "Even G2_<id>_L_…", "Nex1-…" or "MENTRA_DISPLAY_…", and NIMO's "nimo…".
         * Never AR99: it is only recognized by its advertised project ID ([Ar99Advertisement]).
         */
        fun forAdvertisedName(advertisedName: String?): MentraSdkModel? {
            val name = advertisedName?.trim().orEmpty()
            if (name.isEmpty()) return null
            evenArmName.find(name)?.let { return if (it.groupValues[1] == "G1") G1 else G2 }
            if (name.startsWith("Nex1-") || name.startsWith("MENTRA_DISPLAY_")) return DISPLAY
            if (name.lowercase().startsWith("nimo")) return NIMO
            return null
        }

        /**
         * The right arm of G1/G2 glasses. The SDK connects both arms from one pick, so the pairing list
         * shows only the left arm.
         */
        fun isSecondaryArm(advertisedName: String?): Boolean =
            evenArmName.find(advertisedName?.trim().orEmpty())?.groupValues?.get(3) == "R"

        /**
         * The name the SDK's scanner gives these glasses, when the BLE name tells it: the pair number
         * for G1 ("Even G1_42_L_…" → "42"), the BLE name for Mentra Display. Null when the SDK names
         * them from data the BLE name lacks (G2: serial number in the advertisement; AR99: serial or
         * address; NIMO: the Classic Bluetooth name).
         */
        fun sdkNameFor(model: MentraSdkModel, advertisedName: String?): String? {
            val name = advertisedName?.trim().orEmpty().ifEmpty { return null }
            return when (model) {
                G1 -> evenArmName.find(name)?.groupValues?.get(2)
                DISPLAY, LIVE -> name
                G2, NIMO, AR99 -> null
            }
        }
    }
}

/**
 * The AR99 fields of a raw BLE advertisement, parsed as the SDK's AR99 driver does: in the first
 * manufacturer-specific structure of at least 21 bytes, the first 4 bytes (company ID included)
 * are the ASCII project ID and the next 6 bytes, XOR 0xAD, the glasses' BLE address.
 *
 * Mentra's compatibility matrix supports exactly one project ID, "AR99". [parse] fails closed:
 * no manufacturer data, no project ID, or any other ID (AF98, AF99, HVXM, HVXF…) gives null.
 */
data class Ar99Advertisement(val projectId: String, val bleAddress: String) {
    companion object {
        const val PROJECT_ID = "AR99"
        private const val MANUFACTURER_SPECIFIC = 0xFF
        private const val ADDRESS_XOR = 0xAD

        fun parse(scanRecord: ByteArray?): Ar99Advertisement? {
            val record = scanRecord ?: return null
            var pos = 0
            while (pos < record.size - 1) {
                val size = record[pos].toInt() and 0xFF
                if (size == 0 || pos + size >= record.size) break
                val type = record[pos + 1].toInt() and 0xFF
                val data = pos + 2
                if (type == MANUFACTURER_SPECIFIC && size >= 21 && data + 19 < record.size) {
                    val project = ascii4(record, data)
                    // The SDK stops at the first such structure; so does this parser.
                    return if (project == PROJECT_ID) Ar99Advertisement(project, xorAddress(record, data + 8)) else null
                }
                pos += size + 1
            }
            return null
        }

        private fun ascii4(record: ByteArray, offset: Int): String {
            var end = offset + 4
            while (end > offset && record[end - 1].toInt() == 0) end--
            return record.copyOfRange(offset, end).decodeToString()
        }

        private fun xorAddress(record: ByteArray, offset: Int): String =
            (0 until 6).joinToString(":") { index ->
                ((record[offset + index].toInt() xor ADDRESS_XOR) and 0xFF).toString(16).uppercase().padStart(2, '0')
            }
    }
}

/** One glasses record from the SDK scanner, without SDK types. */
data class MentraSdkCandidate(
    val name: String,
    val address: String?,
    val projectName: String? = null,
)

/** Picks the SDK scan result that is the glasses chosen in the pairing list. */
object MentraSdkPairing {
    /**
     * [advertisedName] and [address] describe the chosen pairing row; for AR99, [address] must be the
     * BLE address from its advertisement ([Ar99Advertisement.bleAddress]). Returns null while no
     * candidate matches (or several could). AR99 candidates also need the exact "AR99" project ID,
     * and match by address only.
     */
    fun choose(
        model: MentraSdkModel,
        candidates: List<MentraSdkCandidate>,
        advertisedName: String?,
        address: String?,
    ): MentraSdkCandidate? {
        val sameAddress = { candidate: MentraSdkCandidate ->
            !address.isNullOrBlank() && candidate.address?.equals(address, ignoreCase = true) == true
        }
        if (model == MentraSdkModel.AR99) {
            return candidates.firstOrNull { it.projectName == Ar99Advertisement.PROJECT_ID && sameAddress(it) }
        }
        candidates.firstOrNull(sameAddress)?.let { return it }
        MentraSdkModel.sdkNameFor(model, advertisedName)?.let { expected ->
            candidates.firstOrNull { it.name == expected }?.let { return it }
        }
        // G2 and NIMO results carry no field the BLE row has: a single result can only be these glasses.
        return candidates.singleOrNull()?.takeIf { model == MentraSdkModel.G2 || model == MentraSdkModel.NIMO }
    }
}
