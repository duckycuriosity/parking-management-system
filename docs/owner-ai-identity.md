# Owner and driver Core AI identity synchronization

`OwnerAccountAiIdentityService` uses the existing `AiFaceApiClient`. Profile
creation still posts multipart data to `/faces`; updates use `/faces/{profileId}`.
External `profileId` and MQTT contracts retain their current names and integer
range. Internal owner responses expose `aiProfileId`; profile save requests do
not assign AI identifiers.

## Operations and transaction boundaries

- `syncOwner(ownerId, images)` synchronizes the already persisted owner.
- `syncDriver(ownerId, customerId, images)` verifies ownership before synchronizing
  the driver. For an explicitly linked owner-as-driver, it synchronizes the owner
  and applies the same identity to both records.
- `linkOwnerAsDriver(ownerId, customerId)` declares the same person explicitly in
  a database transaction. Matching names or phone numbers never creates this link.

The sync operations reject an inherited database transaction. Images are read
and checked before creating a durable attempt. A short transaction locks and
commits the person's attempt token and `IN_PROGRESS` state. The HTTP request then
runs without database locks. A second short transaction verifies the token and
original local name/image data before applying the backend-confirmed AI response.
Ordinary profile edits never invoke Core AI.

Only one to five non-empty readable images are accepted. The response must contain
one consistent positive profile ID and usable face image URLs. An existing ID
must match the requested ID. A changed confirmed provider UUID is rejected. The
stored ID/image fields are never overwritten by a stale response. Its known ID is
retained separately in `ai_sync_profile_id` so a later deliberate image sync can
update that identity rather than creating another one.

## States and retry behavior

| State | Meaning and permitted next step |
| --- | --- |
| `NOT_SYNCED` | A local person exists but no successful AI sync is recorded. An explicit face sync may create the profile. |
| `IN_PROGRESS` | A committed attempt is running. A second sync is rejected, rather than creating another profile. |
| `SYNCED` | Backend applied a matching AI result. Existing numeric IDs are updated, never recreated during normal edits. |
| `RECONCILIATION_REQUIRED` with known ID | Retry an explicit face sync; it updates the backend-held provider ID. Identity collisions or UUID changes still require operator resolution. |
| `RECONCILIATION_REQUIRED` without known ID | A create may have reached Core AI, but its result is unknown. Do not blindly POST again. |

The current provider contract has no demonstrated idempotency key or lookup by a
PMS attempt token. Therefore an unknown CREATE cannot safely be resolved by name
or phone matching. A process crash may also leave `IN_PROGRESS`. These situations
need an operator to review Core AI and the durable attempt before any controlled
resolution; there is deliberately no frontend raw-ID attachment or reset that
would allow a second unverified CREATE. Full automatic recovery requires an
agreed provider correlation/idempotency contract, not a local promise of
exactly-once delivery.

## Recognition and existing data

Face/plate verification only uses customers in `SYNCED` state. `ACTIVE` and a paid
subscription alone do not make a new person's face ready. The legacy mapper sets
`SYNCED` after a validated profile create. A recognized MQTT event may create a
known AI customer in `SYNCED` state, but does not attach it to an owner or grant
vehicle permission. Existing records with a numeric AI ID are retained as
`SYNCED` by migration for compatibility; this is not a fresh provider verification
or an assertion that every legacy record has a complete local photo history.

V58 makes owner and customer AI profile IDs unique within each table, and validates
legacy duplicate customer IDs before adding the index. It stops with an actionable
error on duplicates and does not merge people, vehicle permissions or parking
session history automatically. Owner and the explicitly linked driver may share
an ID across the two tables.

For a PMS-owned vehicle, FACE_PLATE entry also requires the recognized customer
to belong to that owner, an ACTIVE vehicle-user grant, and no INACTIVE owner,
vehicle or customer. Revoking a grant affects entry. Existing open-session exit
rules and ownerless legacy vehicles retain their current behavior; this guard
does not introduce a separate PAID entitlement or hardware payment policy.

If MQTT creates a competing customer while a profile create is in flight, the
sync operation records the known provider ID for reconciliation instead of
assigning it to a second person. Database uniqueness protects the remaining race
between checking availability and committing the result.

`parking_customer.ai_profile_id` is BIGINT since V14. The converter maps the
current Integer Java/wire model through the actual Long JDBC type and fails on
unsupported out-of-range stored values. Owner IDs are stored as Long. No PMS
primary key is replaced by an AI identifier.

The shared RestTemplate now actually uses its configured 5-second connection and
15-second read timeouts. HTTP exceptions preserve their original causes; profile
response parsing no longer disguises every transport error as malformed JSON.

## Verification

Unit and mock HTTP tests cover input handling, backend-only IDs, no-transaction IO,
unknown create retries, provider UUID/ID mismatches, owner-as-driver reuse, stale
responses and collisions. Disposable PostgreSQL integration tests apply V58 and
exercise actual BIGINT mappings, committed claims, row-lock release before IO,
concurrent sync, uniqueness and stale response handling. No test contacts a
production Core AI server.
