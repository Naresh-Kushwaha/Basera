# Production checklist

Tick every item before real students use it. Items marked **GAP** are known limitations of this codebase, not oversights.

## Before first deploy
- [ ] Domain + wildcard DNS (`*.<domain>`) and wildcard TLS (cert-manager DNS-01). `auth.` and `admin.` are reserved slugs.
- [ ] Google Cloud OAuth client created; the **only** redirect URI is `https://auth.<domain>/api/v1/auth/google/callback`.
- [ ] Real secrets created out-of-band (never in git): `BASERA_JWT_SECRET` (>=32 random bytes), DB passwords, Google client secret.
- [ ] `BASERA_SUPER_ADMIN_EMAILS` set to **verified** Google emails you control.
- [ ] Managed PostgreSQL 16 with **PostGIS**, backups + point-in-time recovery enabled, restore tested.
- [ ] `db-init` Job, then `migrate` Job, succeeded. App pods run with `SPRING_FLYWAY_ENABLED=false` (they never hold the owner password).
- [ ] Image names pinned to immutable tags/digests; images scanned (CI runs Trivy).
- [ ] The `dev` Spring profile is **not** active (`/api/v1/dev/**` must answer 404). Swagger disabled (`BASERA_SWAGGER_ENABLED` unset).

## Security
- [ ] Ingress overwrites `X-Forwarded-For` (`use-forwarded-headers: "false"`), then `BASERA_TRUST_FORWARDED_FOR=true` is correct.
- [ ] Backend reachable only through the ingress (NetworkPolicies applied).
- [ ] SMTP configured (`SPRING_MAIL_*`) with SPF/DKIM on the sending domain; otherwise emails stay queued.
- [ ] Rotate `BASERA_JWT_SECRET` plan documented (all sessions refresh after a rotation; refresh tokens are unaffected).
- [ ] Privacy policy and terms published; college-admin data-handling expectations agreed. Basera never stores ID document images;
      keep it that way. Take legal advice on India's DPDP Act obligations for student personal data (this repository is not legal advice).
- [ ] Verification process owners at each college trained on the four-point checklist.

## Operations
- [ ] Health probes green (`/actuator/health/readiness`); alerts on 5xx rate, p95 latency, DB connections, failed emails (`email_outbox.status='FAILED'`).
- [ ] Log shipping with the `X-Request-Id` / `traceId` field; no personal data in logs.
- [ ] `audit_log` retention policy decided.
- [ ] City guide content seeded for each city (transport, clinics, **emergency numbers**) and a named person owns keeping it current.

## Known gaps (be honest with users and colleges)
- **GAP: photo storage is local disk.** With more than one backend replica the volume must be ReadWriteMany (NFS/EFS/Filestore),
  or replace `LocalStorageService` with an S3/MinIO implementation of `StorageService` (one class).
- **GAP: rate limiting is per pod** (up to N x the limit with N replicas). Put a shared limiter (Redis, or ingress rate limits) in front for real abuse protection.
- **GAP: chat is polling-based** (5 s). Fine for this scale; revisit for thousands of concurrent chats.
- **GAP: invitation emails are not sent**; admins share the sign-in link. College-email OTP verification is not implemented
  (Google accounts on the college domain are verified automatically).
- **GAP: role/suspension changes apply at the next token refresh** (up to 15 minutes).
- **GAP: no SMS.** SOS prepares a message and opens the phone's own call/SMS/share; it never contacts services itself.
- Distances are straight-line, not walking routes. Utility details are owner-reported. Safe-area notes are opinions with dates.
