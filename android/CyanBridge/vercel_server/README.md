CyanBridge Vercel deployment bundle.

This folder stages only the CyanBridge server files that Vercel needs.

Source server code:
- default: `../_local_termux_server/app.py`
- fallback: `../../../../CyanBridge/_local_termux_server_phone_current/app.py`
- override via `CYANBRIDGE_SERVER_SOURCE=/absolute/path/to/server`

How to deploy:
- Run `bash vercel_server/deploy_vercel_minimal.sh`
- The script will reuse `vercel_server/.vercel/project.json` if present.
- If that file is missing, it will fall back to `../../Carelens/.vercel/project.json` so you can replace the current Carelens Vercel project without uploading the whole Android repo.

Notes:
- Vercel serverless storage is ephemeral. SQLite, plugin submissions, and uploaded log files will only persist for the lifetime of a warm instance.
- The wrapper imports the existing Flask app into `/tmp` so the server can write its runtime files on Vercel.
- To get durable off-Vercel log retention and email alerts, configure SMTP env vars on the Vercel project:
  - `LOG_EMAIL_FROM`
  - `LOG_ALERT_EMAIL_TO`
  - `LOG_ARCHIVE_EMAIL_TO`
  - `LOG_SMTP_HOST`
  - `LOG_SMTP_PORT`
  - `LOG_SMTP_USERNAME`
  - `LOG_SMTP_PASSWORD`
  - optional: `LOG_SMTP_USE_SSL`, `LOG_SMTP_STARTTLS`, `LOG_EMAIL_REPLY_TO`, `LOG_EMAIL_SUBJECT_PREFIX`
- `LOG_ALERT_EMAIL_TO` sends a summary email when a new log arrives.
- `LOG_ARCHIVE_EMAIL_TO` sends the full log as an email attachment, which acts as the durable archive copy even when Vercel local storage is recycled.
