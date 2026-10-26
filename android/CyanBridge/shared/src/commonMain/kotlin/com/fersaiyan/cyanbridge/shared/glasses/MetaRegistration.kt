package com.fersaiyan.cyanbridge.shared.glasses

import com.fersaiyan.cyanbridge.shared.prefs.AppPrefs
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set

/**
 * Hybrid Meta Wearables (DAT) registration. One build carries the registered Meta app id and
 * serves two groups of users:
 * - Developer Mode is on in Meta AI: Meta AI takes the developer path and ignores the app id;
 * - the user was invited by email to the app's release channel and has Developer Mode off.
 *
 * After a successful registration the SDK reports which path Meta AI took (Android
 * `Wearables.isDevMode`). Calling startRegistration while registered resets that value, so
 * [MetaRegistrationTracker.beginRegistration] guards every registration request.
 */
/** The beta form: collects the Meta account email, checks capacity, rejects non-Meta accounts. */
const val META_BETA_ACCESS_URL = "https://cyanbridge.vercel.app/beta"

enum class MetaRegistrationPath { UNKNOWN, DEVELOPER_MODE, RELEASE_CHANNEL }

/** The setup the user picked on the Meta pairing screen, before registering. */
enum class MetaSetupChoice { NONE, DEVELOPER_MODE, EMAIL_INVITE }

/** Platform registration states, reduced to what the shared logic needs. */
enum class MetaRegistrationPhase { NOT_REGISTERED, REGISTERING, REGISTERED, UNREGISTERING }

/** What changed after [MetaRegistrationTracker.onPhase]; the platform logs it and tells the user. */
sealed interface MetaRegistrationEvent {
    data object None : MetaRegistrationEvent
    data class PathRecorded(val path: MetaRegistrationPath) : MetaRegistrationEvent

    /** A Developer-Mode registration ended without the user unregistering in CyanBridge. */
    data object DeveloperModeLost : MetaRegistrationEvent

    /** The user unregistered from CyanBridge; the recorded path was cleared. */
    data object UserUnregistered : MetaRegistrationEvent
}

/** Where the setup choice and the recorded path are kept. */
interface MetaRegistrationStore {
    var choice: MetaSetupChoice
    var path: MetaRegistrationPath
}

/** [MetaRegistrationStore] on the typed preferences ([AppPrefs.MetaRegistration]). */
class PrefsMetaRegistrationStore : MetaRegistrationStore {
    private val prefs get() = AppPrefs.MetaRegistration.store()

    override var choice: MetaSetupChoice
        get() = enumOr(prefs[AppPrefs.MetaRegistration.setupChoice], MetaSetupChoice.NONE)
        set(value) { prefs[AppPrefs.MetaRegistration.setupChoice] = value.name }

    override var path: MetaRegistrationPath
        get() = enumOr(prefs[AppPrefs.MetaRegistration.recordedPath], MetaRegistrationPath.UNKNOWN)
        set(value) { prefs[AppPrefs.MetaRegistration.recordedPath] = value.name }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}

object MetaRegistrationPolicy {
    /** startRegistration only from a settled, unregistered state: never while registered. */
    fun canStartRegistration(phase: MetaRegistrationPhase): Boolean = phase == MetaRegistrationPhase.NOT_REGISTERED

    /**
     * The path a successful registration took. A build without an app id (`"0"`) can only
     * register through Developer Mode; otherwise the SDK's dev-mode flag decides, and null
     * (the SDK does not expose it, as on iOS DAT 1.0) leaves the path unknown.
     */
    fun pathAfterRegistration(sdkIsDevMode: Boolean?, appIdConfigured: Boolean): MetaRegistrationPath = when {
        !appIdConfigured -> MetaRegistrationPath.DEVELOPER_MODE
        sdkIsDevMode == true -> MetaRegistrationPath.DEVELOPER_MODE
        sdkIsDevMode == false -> MetaRegistrationPath.RELEASE_CHANNEL
        else -> MetaRegistrationPath.UNKNOWN
    }

    /** Whether the email/invite option can work: the release channel needs a registered app id. */
    fun releaseChannelAvailable(appIdConfigured: Boolean): Boolean = appIdConfigured
}

/**
 * Follows the registration state for one platform session: guards registration requests,
 * records the path once Meta AI has returned, and reports a Developer-Mode registration that
 * ended on its own (Developer Mode turned off, or CyanBridge disconnected in Meta AI).
 * Callers use it from the main thread.
 */
class MetaRegistrationTracker(
    private val store: MetaRegistrationStore,
    private val appIdConfigured: Boolean,
) {
    private var wasRegistered = false
    private var registrationUiOpen = false
    private var userUnregistering = false

    val recordedPath: MetaRegistrationPath get() = store.path
    val choice: MetaSetupChoice get() = store.choice

    /**
     * True when the platform may call startRegistration now. While registered (or between
     * states) it returns false, which keeps the SDK's dev-mode value intact.
     */
    fun beginRegistration(phase: MetaRegistrationPhase): Boolean {
        if (!MetaRegistrationPolicy.canStartRegistration(phase)) return false
        registrationUiOpen = true
        return true
    }

    /** True when the platform may call startUnregistration now. */
    fun beginUnregistration(phase: MetaRegistrationPhase): Boolean {
        if (phase != MetaRegistrationPhase.REGISTERED) return false
        userUnregistering = true
        return true
    }

    /**
     * Meta AI's registration screen has returned (the activity resumed or the callback arrived),
     * so the SDK's dev-mode value is final for this registration.
     */
    fun registrationUiClosed() {
        registrationUiOpen = false
    }

    /** Feed every registration state; [sdkIsDevMode] is read only for a settled registration. */
    fun onPhase(phase: MetaRegistrationPhase, sdkIsDevMode: () -> Boolean?): MetaRegistrationEvent = when (phase) {
        MetaRegistrationPhase.REGISTERED -> {
            wasRegistered = true
            userUnregistering = false
            if (registrationUiOpen) {
                MetaRegistrationEvent.None
            } else {
                val path = MetaRegistrationPolicy.pathAfterRegistration(sdkIsDevMode(), appIdConfigured)
                // An unknown reading never replaces a path that was recorded before.
                if (path == store.path || path == MetaRegistrationPath.UNKNOWN) {
                    MetaRegistrationEvent.None
                } else {
                    store.path = path
                    MetaRegistrationEvent.PathRecorded(path)
                }
            }
        }
        MetaRegistrationPhase.NOT_REGISTERED -> {
            val ended = wasRegistered
            wasRegistered = false
            when {
                !ended -> MetaRegistrationEvent.None
                userUnregistering -> {
                    userUnregistering = false
                    store.path = MetaRegistrationPath.UNKNOWN
                    MetaRegistrationEvent.UserUnregistered
                }
                store.path == MetaRegistrationPath.DEVELOPER_MODE -> MetaRegistrationEvent.DeveloperModeLost
                else -> MetaRegistrationEvent.None
            }
        }
        MetaRegistrationPhase.REGISTERING, MetaRegistrationPhase.UNREGISTERING -> MetaRegistrationEvent.None
    }
}

/** What the setup section shows, decided once for Android and iOS. */
data class MetaSetupChoicePresentation(
    /** The two setup options (and the chosen one's steps) are shown only before registration. */
    val showChoices: Boolean,
    val choice: MetaSetupChoice,
    /** False when this build has no registered app id, so only Developer Mode can work. */
    val emailInviteAvailable: Boolean,
    /** Registered through Developer Mode before and now unregistered: explain and offer Register again. */
    val showDeveloperModeLost: Boolean,
    /** The Register button inside the chosen option. */
    val canRegister: Boolean,
    /**
     * Picked the email invite but Meta AI registered through Developer Mode, which lacks
     * release-only features such as "Hey Meta, open …" voice launch.
     */
    val showInvitedOnDeveloperPath: Boolean = false,
)

fun metaSetupChoicePresentation(
    phase: MetaRegistrationPhase,
    choice: MetaSetupChoice,
    recordedPath: MetaRegistrationPath,
    appIdConfigured: Boolean,
): MetaSetupChoicePresentation {
    val emailInviteAvailable = MetaRegistrationPolicy.releaseChannelAvailable(appIdConfigured)
    val effectiveChoice = if (choice == MetaSetupChoice.EMAIL_INVITE && !emailInviteAvailable) MetaSetupChoice.NONE else choice
    val notRegistered = phase == MetaRegistrationPhase.NOT_REGISTERED
    return MetaSetupChoicePresentation(
        showChoices = notRegistered,
        choice = effectiveChoice,
        emailInviteAvailable = emailInviteAvailable,
        showDeveloperModeLost = notRegistered && recordedPath == MetaRegistrationPath.DEVELOPER_MODE,
        canRegister = MetaRegistrationPolicy.canStartRegistration(phase) && effectiveChoice != MetaSetupChoice.NONE,
        showInvitedOnDeveloperPath = phase == MetaRegistrationPhase.REGISTERED &&
            effectiveChoice == MetaSetupChoice.EMAIL_INVITE && recordedPath == MetaRegistrationPath.DEVELOPER_MODE,
    )
}
