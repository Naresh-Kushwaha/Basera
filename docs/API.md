# Basera API reference

Generated from the controllers by `python3 scripts/gen_api_doc.py`. Paths are relative to the college host, e.g. `https://gec-jabalpur.basera.app`; the tenant is resolved from the Host header. `/api/v1/platform/**` is served on `admin.<domain>` and the Google login endpoints on `auth.<domain>`.

"Any member" means any signed-in role of that college. Roles are enforced on the server and Row Level Security enforces the tenant boundary underneath. Errors always use the same JSON body: `{timestamp, status, code, message, path, traceId, fieldErrors[]}`. Lists are paginated with `page` and `size` (max 50).

| Method | Path | Who |
|---|---|---|
| GET | `/api/v1/admin/analytics/activity` | TENANT_ADMIN |
| GET | `/api/v1/admin/analytics/listings` | TENANT_ADMIN |
| GET | `/api/v1/admin/analytics/overview` | TENANT_ADMIN |
| GET | `/api/v1/admin/announcements` | TENANT_ADMIN |
| POST | `/api/v1/admin/announcements` | TENANT_ADMIN |
| DELETE | `/api/v1/admin/announcements/{id}` | TENANT_ADMIN |
| PUT | `/api/v1/admin/announcements/{id}` | TENANT_ADMIN |
| GET | `/api/v1/admin/audit-log` | TENANT_ADMIN |
| GET | `/api/v1/admin/guide/checklist-items` | TENANT_ADMIN |
| POST | `/api/v1/admin/guide/checklist-items` | TENANT_ADMIN |
| DELETE | `/api/v1/admin/guide/checklist-items/{id}` | TENANT_ADMIN |
| PUT | `/api/v1/admin/guide/checklist-items/{id}` | TENANT_ADMIN |
| GET | `/api/v1/admin/guide/cost-items` | TENANT_ADMIN |
| POST | `/api/v1/admin/guide/cost-items` | TENANT_ADMIN |
| DELETE | `/api/v1/admin/guide/cost-items/{id}` | TENANT_ADMIN |
| PUT | `/api/v1/admin/guide/cost-items/{id}` | TENANT_ADMIN |
| GET | `/api/v1/admin/interactions` | TENANT_ADMIN |
| POST | `/api/v1/admin/interactions/{id}/confirm` | TENANT_ADMIN |
| GET | `/api/v1/admin/invitations` | TENANT_ADMIN |
| POST | `/api/v1/admin/invitations` | TENANT_ADMIN |
| DELETE | `/api/v1/admin/invitations/{id}` | TENANT_ADMIN |
| GET | `/api/v1/admin/members` | TENANT_ADMIN |
| POST | `/api/v1/admin/members/{id}/grant-owner` | TENANT_ADMIN |
| POST | `/api/v1/admin/members/{id}/reactivate` | TENANT_ADMIN |
| POST | `/api/v1/admin/members/{id}/revoke-owner` | TENANT_ADMIN |
| POST | `/api/v1/admin/members/{id}/suspend` | TENANT_ADMIN |
| GET | `/api/v1/admin/moderation/queue` | TENANT_ADMIN |
| POST | `/api/v1/admin/moderation/{reportId}/dismiss` | TENANT_ADMIN |
| POST | `/api/v1/admin/moderation/{reportId}/hide` | TENANT_ADMIN |
| POST | `/api/v1/admin/owners/{userId}/suspend` | TENANT_ADMIN |
| POST | `/api/v1/admin/roommate-posts/{id}/hide` | TENANT_ADMIN |
| GET | `/api/v1/admin/safety/issues` | TENANT_ADMIN |
| PATCH | `/api/v1/admin/safety/issues/{id}` | TENANT_ADMIN |
| GET | `/api/v1/admin/scam-reports` | TENANT_ADMIN |
| POST | `/api/v1/admin/scam-reports/{id}/confirm` | TENANT_ADMIN |
| POST | `/api/v1/admin/scam-reports/{id}/dismiss` | TENANT_ADMIN |
| PUT | `/api/v1/admin/tenant/settings` | TENANT_ADMIN |
| GET | `/api/v1/admin/utility-reports` | TENANT_ADMIN |
| PATCH | `/api/v1/admin/utility-reports/{id}` | TENANT_ADMIN |
| GET | `/api/v1/admin/visibility-requests` | TENANT_ADMIN |
| POST | `/api/v1/admin/visibility/{id}/approve` | TENANT_ADMIN |
| POST | `/api/v1/admin/visibility/{id}/reject` | TENANT_ADMIN |
| POST | `/api/v1/admin/visibility/{id}/suspend` | TENANT_ADMIN |
| POST | `/api/v1/admin/visibility/{id}/verification` | TENANT_ADMIN |
| GET | `/api/v1/announcements` | Any member |
| GET | `/api/v1/auth/google/callback` | Public |
| GET | `/api/v1/auth/login` | Public |
| POST | `/api/v1/auth/logout` | Public |
| POST | `/api/v1/auth/refresh` | Public |
| POST | `/api/v1/auth/token` | Public |
| GET | `/api/v1/budget/expenses` | Any member |
| POST | `/api/v1/budget/expenses` | Any member |
| DELETE | `/api/v1/budget/expenses/{id}` | Any member |
| PUT | `/api/v1/budget/expenses/{id}` | Any member |
| GET | `/api/v1/budget/summary` | Any member |
| GET | `/api/v1/budget/typical-costs` | Any member |
| GET | `/api/v1/conversations` | Any member |
| POST | `/api/v1/conversations` | Any member |
| GET | `/api/v1/conversations/{id}/messages` | Any member |
| POST | `/api/v1/conversations/{id}/messages` | Any member |
| POST | `/api/v1/dev/login` | Public |
| GET | `/api/v1/guide/emergency` | Any member |
| GET | `/api/v1/guide/first-week-checklist` | Any member |
| GET | `/api/v1/guide/health` | Any member |
| GET | `/api/v1/guide/kyc` | Any member |
| GET | `/api/v1/guide/safe-areas` | Any member |
| GET | `/api/v1/guide/transport` | Any member |
| GET | `/api/v1/listings` | Any member |
| GET | `/api/v1/listings/agreement-checklist` | Any member |
| GET | `/api/v1/listings/{id}` | Any member |
| POST | `/api/v1/listings/{id}/interactions` | STUDENT |
| GET | `/api/v1/listings/{id}/reviews` | Any member |
| POST | `/api/v1/listings/{id}/reviews` | STUDENT |
| POST | `/api/v1/listings/{id}/scam-reports` | STUDENT |
| GET | `/api/v1/listings/{id}/utility-reports` | Any member |
| GET | `/api/v1/me` | Any member |
| PUT | `/api/v1/me/checklist/{itemId}` | Any member |
| GET | `/api/v1/me/interactions` | Any member |
| GET | `/api/v1/me/notification-preferences` | Any member |
| PUT | `/api/v1/me/notification-preferences` | Any member |
| GET | `/api/v1/media/{key}` | Public |
| GET | `/api/v1/notifications` | Any member |
| POST | `/api/v1/notifications/read-all` | Any member |
| GET | `/api/v1/notifications/unread-count` | Any member |
| POST | `/api/v1/notifications/{id}/read` | Any member |
| GET | `/api/v1/owner/dashboard` | OWNER |
| GET | `/api/v1/owner/interactions` | OWNER |
| POST | `/api/v1/owner/interactions/{id}/confirm` | OWNER |
| POST | `/api/v1/owner/interactions/{id}/reject` | OWNER |
| GET | `/api/v1/owner/listings` | OWNER |
| POST | `/api/v1/owner/listings` | OWNER |
| GET | `/api/v1/owner/listings/{id}` | OWNER |
| PUT | `/api/v1/owner/listings/{id}` | OWNER |
| PUT | `/api/v1/owner/listings/{id}/active` | OWNER |
| PUT | `/api/v1/owner/listings/{id}/availability` | OWNER |
| PUT | `/api/v1/owner/listings/{id}/menu` | OWNER |
| POST | `/api/v1/owner/listings/{id}/photos` | OWNER |
| DELETE | `/api/v1/owner/listings/{id}/photos/{photoId}` | OWNER |
| PUT | `/api/v1/owner/listings/{id}/utilities` | OWNER |
| POST | `/api/v1/owner/listings/{id}/visibility-requests` | OWNER |
| GET | `/api/v1/owner/tenants-in-city` | OWNER |
| GET | `/api/v1/owner/utility-reports` | OWNER |
| PATCH | `/api/v1/owner/utility-reports/{id}` | OWNER |
| GET | `/api/v1/platform/cities` | SUPER_ADMIN |
| POST | `/api/v1/platform/cities` | SUPER_ADMIN |
| GET | `/api/v1/platform/cities/{cityId}/content/{type}` | SUPER_ADMIN |
| POST | `/api/v1/platform/cities/{cityId}/content/{type}` | SUPER_ADMIN |
| DELETE | `/api/v1/platform/cities/{cityId}/content/{type}/{id}` | SUPER_ADMIN |
| PUT | `/api/v1/platform/cities/{cityId}/content/{type}/{id}` | SUPER_ADMIN |
| GET | `/api/v1/platform/me` | SUPER_ADMIN |
| GET | `/api/v1/platform/tenants` | SUPER_ADMIN |
| POST | `/api/v1/platform/tenants` | SUPER_ADMIN |
| GET | `/api/v1/platform/tenants/{id}` | SUPER_ADMIN |
| PATCH | `/api/v1/platform/tenants/{id}` | SUPER_ADMIN |
| POST | `/api/v1/platform/tenants/{id}/admin-invitations` | SUPER_ADMIN |
| PUT | `/api/v1/platform/tenants/{id}/modules` | SUPER_ADMIN |
| DELETE | `/api/v1/reviews/{id}` | STUDENT |
| PUT | `/api/v1/reviews/{id}` | STUDENT |
| POST | `/api/v1/reviews/{id}/reports` | STUDENT |
| GET | `/api/v1/roommate-posts` | Any member |
| POST | `/api/v1/roommate-posts` | STUDENT |
| GET | `/api/v1/roommate-posts/matches` | Any member |
| GET | `/api/v1/roommate-posts/mine` | Any member |
| DELETE | `/api/v1/roommate-posts/{id}` | STUDENT |
| PUT | `/api/v1/roommate-posts/{id}` | STUDENT |
| POST | `/api/v1/roommate-posts/{id}/close` | STUDENT |
| GET | `/api/v1/safety/contacts` | Any member |
| POST | `/api/v1/safety/contacts` | Any member |
| DELETE | `/api/v1/safety/contacts/{id}` | Any member |
| PUT | `/api/v1/safety/contacts/{id}` | Any member |
| GET | `/api/v1/safety/issues` | Any member |
| POST | `/api/v1/safety/issues` | Any member |
| POST | `/api/v1/safety/sos` | Any member |
| GET | `/api/v1/tenant-config` | Public |
| POST | `/api/v1/utility-reports` | STUDENT |
| GET | `/api/v1/utility-reports/mine` | Any member |

136 endpoints.
