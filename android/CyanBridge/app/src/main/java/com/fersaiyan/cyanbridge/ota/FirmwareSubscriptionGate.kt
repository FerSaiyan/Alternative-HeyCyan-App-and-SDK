package com.fersaiyan.cyanbridge.ota

import com.fersaiyan.cyanbridge.shared.billing.SubscriptionGateCopy
import com.fersaiyan.cyanbridge.shared.billing.subscriptionGateCopy

internal typealias FirmwareSubscriptionGateCopy = SubscriptionGateCopy

internal fun firmwareSubscriptionGateCopy(currentPlan: String): FirmwareSubscriptionGateCopy = subscriptionGateCopy(
    currentPlan = currentPlan,
    featureTitle = "Patched LED OTA",
    featureSentenceSubject = "Patched LED OTA firmware",
    excludedThing = "this firmware",
)
