# Meta DAT beta access flow

## Goal

Avoid confusing Meta DAT pairing failures caused by users who are not registered in the Meta Developer release channel.

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
