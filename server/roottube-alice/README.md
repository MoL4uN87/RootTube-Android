# RootTube AI+ (Alice AI) translation proxy

RootTube supports three comment/reply modes: **Original**, **RU** (on-device
ML Kit) and **AI+** (Alice AI LLM Flash). AI+ needs a **user-owned HTTPS proxy**.

The Yandex Cloud API key is **server-only**, never embedded in the Android
APK. RootTube stores only a separately revocable proxy access token, encrypted
using Android Keystore. Do not share this token with other users.

## Required Yandex Cloud setup

1. Create a Yandex Cloud / AI Studio service account and an AI Studio API key
   scoped to model execution (for example `yc.ai.foundationModels.execute`).
2. Give the service account the role **ai.languageModels.user** for the folder.
3. Copy the **folder ID** and **service account API key** from AI Studio.
4. Review AI Studio billing and establish quotas/budget alerts. Alice AI Flash
   **is not a free API**. The service includes request-count limits, not a hard
   Yandex Cloud spending cap.

Official model: `gpt://<folder-id>/aliceai-llm-flash`
Official endpoint: `https://ai.api.cloud.yandex.net/v1/chat/completions`

## Install on your Linux server

Use a separate unprivileged account and dedicated directory. Do **not**
overwrite your existing VPN, Nginx or other service configurations.

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env
chmod 600 .env
python3 -c "import secrets; print(secrets.token_urlsafe(48))"
```

Put the *generated* random token in `ALICE_PROXY_TOKEN` and fill the two
Yandex Cloud values in `.env`. Never paste the values into chat or GitHub.

Run from this directory (e.g. via a systemd unit with `EnvironmentFile=`):
```bash
set -a; . ./.env; set +a
.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8787 --no-access-log
```

Keep port **8787 bound to localhost only**. Existing Nginx can expose it
through an HTTPS reverse proxy:

```nginx
location /roottube-alice/ {
    proxy_pass http://127.0.0.1:8787/;
    proxy_connect_timeout 10s;
    proxy_read_timeout 40s;
    client_max_body_size 4k;
    proxy_set_header Host $host;
}
```

The trailing slash on `proxy_pass` is important: RootTube sends requests
to `https://YOUR_HTTPS_DOMAIN/roottube-alice/v1/translate` while FastAPI
expects `/v1/translate`. Do not expose 8787 to the Internet or create a
publicly accessible unauthenticated translation proxy.

## Configure Android

Open video comments → **AI+** → enter:

* Server URL: `https://YOUR_HTTPS_DOMAIN/roottube-alice`
* Personal token: `ALICE_PROXY_TOKEN` (from your server; **not** Yandex API key)

After configuration, **AI+** translates loaded comments and replies as you
scroll. Long-press **AI+** to change its settings. **RU** remains free, local,
and independent. **ОРИГ** restores original text.

The proxy has a maximum of **150** requests per sliding 24h period and
**12** per minute by default, shared by all users with this one token.
Limits reset on server restart. A request translates one comment (up to
1800 characters), so fast scrolling may consume quota. If the backend is
offline, AI+ shows an error without affecting RU mode.

**Privacy:** AI+ sends comment text through your server to Yandex AI Studio.
RU mode does not send comment text to this proxy. The proxy does not store
comment bodies, but follow your cloud provider's data-processing policy.

## Check health and errors

```bash
curl -fsS http://127.0.0.1:8787/health
```

Expect `{"status":"ok"}`. HTTP 401 = wrong personal token;
HTTP 429 = server-side usage limit; HTTP 502/503 = Yandex upstream issue
or cloud account setup/billing problem. Do not log API tokens or raw comments.
