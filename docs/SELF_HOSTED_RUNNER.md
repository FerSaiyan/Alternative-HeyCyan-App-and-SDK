# Self-Hosted GitHub Actions Runner

This repository uses the Linux Mint machine runner for Android and shared-code
CI. The runner is registered for the existing Linux user; no extra account or
root service is required.

## Current Runner

- Repository: `FerSaiyan/Alternative-HeyCyan-App-and-SDK`
- Runner name: `akios-linux-mint`
- Labels: `self-hosted`, `linux`, `x64`, `homelab`, `android`
- Runner directory: `~/actions-runner`
- Service: `github-actions-runner.service` as a user-level systemd unit

The workflow is [`android-self-hosted.yml`](../.github/workflows/android-self-hosted.yml).
It runs on pushes to any branch and manual dispatches. It runs Android unit tests,
shared portability tests, and creates a debug APK artifact.

The repository also needs an Actions secret named `META_GITHUB_TOKEN` with
`read:packages` access to the Meta Wearables package. The secret is passed only
to Gradle test/build steps and is never stored in the repository or runner unit.

The iOS workflow remains on hosted macOS because this Linux runner cannot build
or launch the iOS simulator.

## Start or Stop the Runner

The user systemd session needs these environment variables when commands are run
from a shell without a desktop session:

```bash
export XDG_RUNTIME_DIR="/run/user/$(id -u)"
export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
```

Install the checked-in unit and start it without `sudo`:

```bash
mkdir -p "$HOME/.config/systemd/user"
cp docs/github-actions-runner.service \
  "$HOME/.config/systemd/user/github-actions-runner.service"
systemctl --user daemon-reload
systemctl --user enable --now github-actions-runner.service
systemctl --user status github-actions-runner.service
```

Useful commands:

```bash
systemctl --user stop github-actions-runner.service
systemctl --user start github-actions-runner.service
journalctl --user -u github-actions-runner.service -f
```

The runner account already has systemd lingering enabled, so the user unit can
start after reboot without a system-wide service. If lingering is disabled on a
different machine, enabling it may require an administrator and is outside this
no-`sudo` setup.

## Re-registering

Registration tokens are temporary. Generate one only when needed and never put
it in a file or commit it:

```bash
RUNNER_TOKEN="$(gh api --method POST \
  repos/FerSaiyan/Alternative-HeyCyan-App-and-SDK/actions/runners/registration-token \
  --jq .token)"
"$HOME/actions-runner/config.sh" \
  --unattended \
  --url "https://github.com/FerSaiyan/Alternative-HeyCyan-App-and-SDK" \
  --token "$RUNNER_TOKEN" \
  --name "akios-linux-mint" \
  --labels "homelab,android" \
  --work "_work" \
  --replace
unset RUNNER_TOKEN
```

If the runner directory is missing, download the current Linux x64 archive
from the repository's GitHub **Settings → Actions → Runners → New self-hosted
runner** page before running the registration command.

## Security Boundary

The repository is public. This workflow intentionally does not use
`pull_request`, so code from forked pull requests is not executed on the
persistent homelab machine. Keep the runner limited to repositories and
branches whose changes you review. A self-hosted runner can access the files,
network, and credentials available to its Linux user while a job is running.
