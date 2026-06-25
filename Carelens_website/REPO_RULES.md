# Repository Rules

## Branching
- `main` is protected and deployment-ready.
- Use feature branches: `feat/<short-name>`.
- Use fix branches: `fix/<short-name>`.

## Pull requests
- Include objective, scope, and risk notes.
- Include desktop and mobile screenshots for UI changes.
- Confirm legal disclosure checklist before merge.
- Link docs updates when business logic changes.

## Required checks
- `npm run lint`
- `npm run build`
- `npm run db:push`
- `npm run test:e2e:smoke` for UI, onboarding, checkout, schedule, or API flow changes

## Security and privacy
- Never commit `.env` files.
- Only keep placeholder values in `.env.example`.
- Redact personal and medical information from logs.

## Commit style
- `feat:` new feature
- `fix:` bug fix
- `docs:` documentation changes
- `chore:` maintenance work
