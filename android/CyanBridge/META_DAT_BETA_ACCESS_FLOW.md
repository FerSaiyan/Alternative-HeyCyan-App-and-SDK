# Meta DAT beta access flow

## Goal

Avoid confusing Meta DAT pairing failures caused by users who are not registered in the Meta Developer release channel.

## Hybrid registration (current)

Every build carries the registered Meta app id. The Meta setup screen asks the user to pick
"I use Developer Mode" or "Request access with my Meta email" before Register. Meta AI ignores
the app id on its Developer Mode path, so both groups use the same build. After registration the
app records which path Meta AI took (`Wearables.isDevMode`) and shows it as "Meta AI connection: …".
The flow below still applies to the email path. Hardware checks:
`docs/refactor/HARDWARE_TEST_CHECKLIST.md` → "Meta hybrid registration".

## What DAT reports before Register

DAT 0.8 sets `UNAVAILABLE` or `AVAILABLE` before registration from Bluetooth only: `AVAILABLE` when a
bonded device advertises the Meta glasses service UUID (`8b0d2687-…`, the app's `bondedMetaDeviceCount`).
`UNAVAILABLE` therefore means "no Meta glasses paired with this phone", not "no invite"; the app sends
those users to Meta AI to pair. Meta AI decides access only after Register, and DAT reports a refusal as a
registration error (`FAILED_TO_REGISTER`/`UNKNOWN`). Only then does the app show the access form
(email path) or the Developer Mode steps (Developer Mode path).

## App-side flow

Before starting Meta DAT pairing:

1. Check whether the current CyanBridge profile/email is already registered for Meta DAT access.
2. If registration is confirmed, continue with the pairing flow.
3. If registration is missing, show a user-friendly explanation instead of a generic pairing failure.
4. Redirect the user to:

https://cyanbridge.vercel.app/beta

where they can submit their email so the developer can add them to an available Meta release channel.

## Suggested user message

"Meta Ray-Ban support is currently in a limited preview. Your account is not registered yet. Please submit your email at https://cyanbridge.vercel.app/beta and we will enable access when possible."

## Channel scaling

Meta release channels currently appear to support multiple channels with limited tester capacity. The app should not assume unlimited access and should handle:

- waiting for approval
- channel capacity reached
- unregistered accounts
- DAT pairing failures after registration

## Future backend integration

The registration state should eventually be queried from the CyanBridge backend so the app can distinguish:

- registered
- pending approval
- not registered
- rejected/expired
