# Local development sign-in

Keycloak 26.7.4 runs on `http://127.0.0.1:8180`. The CBS portal runs on
`http://127.0.0.1:8080/admin/index.html` and continues to use Neon for its
administration records. The realm is `cbs-dev` and the portal client is
`cbs-administration`.

## First login

1. Open the CBS portal and click **Sign in**.
2. Use username `cbs-admin` and the temporary password in
   `.local/FIRST-LOGIN.txt`.
3. Follow Keycloak's prompts to choose a password and enroll an authenticator app.
   Scan the QR code on your own device and enter the current six-digit code.
4. Return to CBS. If the initial enrollment does not supply completed-login MFA
   evidence, sign in again using your new password and authenticator code.

The administrator's provider subject is already registered in Neon. No bank
registration is needed before first login. Its `PLATFORM_ADMIN` role comes from
Keycloak. Never use the separate `keycloak-admin` management account as your CBS
staff login. Management credentials are in `.local/identity-credentials.json`.

## Restart after closing the applications

In one PowerShell terminal:

```powershell
./scripts/run-local-identity.ps1
```

In another:

```powershell
./scripts/run.ps1
```

The setup step, for a fresh workstation, is `./scripts/setup-local-identity.ps1`.
It downloads the pinned official release and checks its SHA-256 digest against
the release metadata. Java 21 is used from the workspace when available.
Setup generates unique local credentials and a realm import using
`config/keycloak/cbs-dev-realm.json`. Restart imports only missing realms;
it does not overwrite existing passwords, users, or MFA enrollment. Template
changes must be applied deliberately to an existing realm.

## What is configured

- Authorization code flow with required S256 PKCE, a public client, exact redirect
  URI and exact portal CORS origin. Implicit and password grants are disabled for
  the portal client.
- A required password step followed by a required OTP step, with no cookie-only
  alternative. Self-registration is disabled and login failures are rate limited.
- A 15-minute access token carrying the `cbs-api` audience, the user's real realm
  roles, session ID and original authentication time (the standard `basic` scope).
- An AMR mapper based on completed authenticator executions. `pwd` is assigned
  to the password step and `mfa` to successful OTP execution after that step;
  no unconditional MFA claim mapper is installed. AMR evidence expires in
  30 minutes, matching CBS's administrator policy.
- The local administrator must change the temporary password and enroll OTP.
  Only the user should choose the permanent password and enroll their own device.

## Storage and development boundary

CBS records remain in Neon. Keycloak uses its persistent development H2 database
under `.tools/keycloak-26.7.4/data/h2`; identity signing keys, passwords and OTP
enrollments are local to this computer. Preserve that directory to retain logins.
It is not a production identity deployment or a shared team identity provider.

`.local/development-identity.json` holds CBS connection settings; secret credentials
are in separate ignored local files. The setup import under `.tools` also contains
the initial temporary password, so keep these ignored directories private.

`CBS_ALLOW_LOCAL_IDENTITY_HTTP` defaults to false. The development run script
enables it for this local configuration. Its exception accepts only exact
loopback hosts and refuses a non-loopback CBS bind address. The portal CSP adds
only the configured loopback identity origin. HTTPS remains required for remote
identity URLs. Keycloak also binds only to `127.0.0.1`.

To use another provider, start with `./scripts/run.ps1 -UseEnvironmentIdentity`
and supply the settings in the Module 02 guide; keep the local HTTP flag false.
The saved local identity file is then bypassed.

References: [Keycloak development server](https://www.keycloak.org/getting-started/getting-started-zip)
and [protocol mappers](https://www.keycloak.org/admin-api/protocol-mappers).
