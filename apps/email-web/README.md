# NeoChat email frontend

Legacy static TypeScript page hosted by Firebase Hosting. New verification emails no longer include a `continueUrl`; the page is retained only for already-sent links.

## Local build

```sh
npm install
copy .env.example .env.local
npm run dev
```

Set `VITE_NEOCHAT_API_ORIGIN` in `.env.local` to the HTTPS origin of the NeoChat API. For a production build, set the same variable before `npm run build`. It is embedded in the page's restrictive Content Security Policy, so the build must be repeated after changing the API origin.

## Firebase Hosting

The Hosting site already exists as `neochat-messenger`, with `https://neochat-messenger.firebaseapp.com` and `https://neochat-messenger.web.app` addresses. The Firebase domain is already authorized in Authentication. No custom domain or DNS changes are needed.

Firebase's standard Verify Email action page stays enabled. New emails finish there and do not redirect to this page.

To publish updates from this directory:

```powershell
$env:VITE_NEOCHAT_API_ORIGIN="https://r1270286.s2566.vm.mydomain.zone:8443"
npm run build
firebase deploy --only hosting:email-verification --project neochat-messenger
```

`NEOCHAT_EMAIL_VERIFY_BASE_URL` remains for old links and the inactive custom action handler. `NEOCHAT_EMAIL_BASE_URL` continues to serve password-reset pages.

This page is not used by new verification emails. It contains no server credentials, NeoChat passwords, or encryption keys.
