# Auth Test Page (Keycloak + Backend)

This folder contains a minimal static page to test:
- Keycloak login
- Bearer token call to backend API

## File
- `login-test.html`

## Quick Start
Run a static server from this folder:

```bash
cd tools/auth-test
python -m http.server 5500
```

Open:
- `http://localhost:5500/login-test.html`

## Required Keycloak Client Settings (for `website-client`)
- Standard Flow: ON
- Client Authentication: OFF (for public SPA)
- Valid Redirect URIs: include `http://localhost:5500/*`
- Web Origins: include `http://localhost:5500`
- (Optional) Post Logout Redirect URIs: `http://localhost:5500/*`

## Backend Auth Note
Your current `SecurityConfig` allows all requests (`anyRequest().permitAll()`).
To test true backend authentication, switch to:

```java
auth.anyRequest().authenticated();
```

and enable OAuth2 resource server JWT block in `SecurityConfig`.

Then calling a protected API from the test page should return:
- `200` when token is valid
- `401/403` when token is missing/invalid/no role

## Suggested API for testing
- `/api/user/me`
