# Android Product Analytics

CyanBridge sends minimal first-party product analytics to the CyanBridge website so usage and product hypotheses can be measured without requiring an email address for normal app use.

## Installation identity

`InstallationIdentity` reuses the existing random UUID stored under `pro_feature_feedback / installation_id`. It is not the Pro API token, Android ID, advertising ID, IMEI, serial number, or email address.

The server HMACs the UUID before storing it in the analytics namespace.

## Daily activity heartbeat

`AnalyticsClient.recordDailyHeartbeat()` is invoked from Android activity lifecycle callbacks registered by `MyApplication`. This deliberately counts foreground activity rather than background services.

Before the user saves the analytics choice, `AnalyticsPreferences.isSharingEnabled()` returns false and no heartbeat is sent. The acquisition dialog explains the payload and presents the sharing switch. After the choice is saved, an enabled installation sends at most one successful heartbeat per UTC day.

Payload fields:

- random installation UUID
- CyanBridge app version
- platform (`android`)
- distribution (`google_play` or `direct`)

If a CyanBridge Pro relay token already exists, it is sent only in the HTTP Authorization header so the website can associate the installation with the existing relay account. The token is not used as the analytics identifier.

## Data not included

The product analytics client does not send:

- photos or video
- microphone audio or recordings
- prompts or AI responses
- transcripts
- contacts
- local files
- local-model input/output
- smart-glasses media contents

## Acquisition hypothesis survey

The lightweight dialog asks for one primary reason and up to two optional secondary reasons:

- local/offline AI
- model/provider choice
- privacy/data control
- open-source transparency
- accessibility/visual assistance
- smart-glasses compatibility
- curiosity/experimentation
- other

The question can be skipped. A submitted survey is queued locally first, so a temporary network outage does not lose the response. Pending responses are retried on a later foreground session.

The accessibility option is intentionally phrased as a product use case; it does not ask the user to disclose a medical diagnosis or disability status.

## Endpoint contract

Production endpoints are hosted at `https://cyanbridge.vercel.app`:

- `POST /api/analytics/heartbeat`
- `POST /api/analytics/acquisition`

Analytics intentionally use the canonical CyanBridge service URL instead of the user-configurable AI relay URL. Custom/local AI routing therefore does not redirect product-analytics requests to a third party.
