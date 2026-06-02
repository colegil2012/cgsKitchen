# cgsKitchen

The backend for Celtech General Store's food operation — a single Spring Boot
application that is, at once, the **customer storefront**, the **admin
console**, the **POS / kitchen API**, and the **delivery + payments
integration layer**. One deployable JAR serves the public ordering site
(server-rendered), the back-office management UI, the JSON API consumed by the
POS terminal and kitchen/menu displays, and the webhook endpoints that Stripe
and Uber Direct call back into.

It is the system of record. Everything that touches an order — the website, the
POS, the kitchen board, the menu board, the admin views — reads from and writes
to this one service. Other surfaces are clients; this is the source of truth.

---

## Contents

- [Architecture at a glance](#architecture-at-a-glance)
- [Tech stack](#tech-stack)
- [The four surfaces](#the-four-surfaces)
- [Security model](#security-model)
- [Domain model](#domain-model)
- [Order lifecycle](#order-lifecycle)
- [Ordering paths: web vs POS](#ordering-paths-web-vs-pos)
- [Payments (Stripe)](#payments-stripe)
- [Delivery (Uber Direct)](#delivery-uber-direct)
- [Webhooks](#webhooks)
- [Configuration](#configuration)
- [Running locally](#running-locally)
- [Deployment](#deployment)
- [Project layout](#project-layout)

---

## Architecture at a glance

A single Spring Boot 4 / Java 21 application backed by MongoDB. It exposes two
distinct HTTP "worlds," separated at the security layer:

- A **browser world** — server-rendered Thymeleaf pages for the storefront,
  customer accounts, and the admin console. Cookie sessions, form login, CSRF
  on.
- An **API world** — stateless JSON under `/api/**`, plus inbound `/webhooks/**`
  and `/actuator/**`. API-key authenticated, CSRF off.

Around the ordering core sit two external integrations — **Stripe** (card
payments online, Terminal in person) and **Uber Direct** (last-mile delivery) —
each fronted by an interface so the rest of the app never talks to a vendor SDK
directly.

The companion repos (not in this codebase) are clients of the API world: the
**POS web terminal**, the **kitchen order board**, and the **digital menu
board**, each a static Vue app running as a Chromium kiosk on a Raspberry Pi.

---

## Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4.0.x (Web, Security, Validation, Actuator) |
| Views | Thymeleaf + `thymeleaf-extras-springsecurity6` |
| Persistence | MongoDB (Spring Data Mongo, auto index creation) |
| Payments | Stripe Java SDK 32.x (PaymentIntents, Checkout, Terminal) |
| Delivery | Uber Direct (custom HTTP client) |
| Bot defense | Cloudflare Turnstile |
| Metrics | Micrometer + Prometheus registry |
| Build | Maven (`spring-boot-maven-plugin`) |

---

## The four surfaces

### 1. Storefront (customer web ordering)

Server-rendered Thymeleaf, served from the root paths. `StorefrontController`
renders the marketing/ordering pages: home (with up to three badged "featured"
items), the full menu grouped by category, an events calendar that expands
recurring + one-time event occurrences over a 60-day horizon, plus about and
contact. Ordering itself runs through `CartController` and the cart/checkout
services.

The cart (`CartService`, `Cart` model) comes in two flavors that share one code
path: a **user cart** keyed by `userId` that persists indefinitely, and a
**guest cart** keyed by a browser cookie (`CartCookieFilter`) that TTL-reaps
after 30 days. When a guest signs in, the guest cart is merged into the user
cart and the guest row deleted. Lines with the same item *and* the same option
selections merge quantities; different selections stay as separate lines.

Checkout uses Stripe's eager-mount Elements flow: a PaymentIntent is created
when `/checkout` renders and updated in place as the total changes (fulfillment
switch, delivery fee, save-card toggle). A legacy redirect-to-Stripe-Checkout
path is retained as a fallback. Abandoned checkouts are swept
(`AbandonedCheckoutSweeper`) and their stale PaymentIntents cancelled before the
order row TTLs out of Mongo.

### 2. Admin console

A full back-office under `/admin/**`, gated to the `ADMIN` role, rendered with
Thymeleaf. Each controller is a management surface over one collection:

- **`AdminOrdersController`** — order list and detail; drives kitchen/delivery
  actions through the same transition service the API uses, so admin and POS
  behave identically.
- **`AdminMenuController`** — menu item CRUD (name, price, category, badge,
  prep time, availability).
- **`AdminOptionsController`** — option groups and their choices (the
  build-your-own modifiers: Protein, Cheese, Veggies, Sauces, etc.), including
  per-choice price deltas and availability.
- **`AdminEventsController`** — events, both one-time (explicit start/end) and
  recurring (day-of-week + time + effective window). Recurrence rules are
  expanded into concrete occurrences for the storefront calendar.
- **`AdminUsersController`** — user list/detail, enable/disable, and role
  assignment (with a guard that never strips a user down to zero roles —
  they fall back to `CUSTOMER`).

### 3. POS / kitchen API

The JSON surface under `/api/**` that the POS terminal and the kitchen/menu
displays consume. Authenticated by API key. Key controllers:

- **`PosApiController`** — `POST /api/pos/orders` (create an order from rung-up
  items), Stripe Terminal connection-token + payment-intent issuance for the
  in-person card reader, and a minimal customer-email lookup for attaching an
  order to a registered user.
- **`OrderStatusController`** — `POST /api/orders/{id}/status` (validated
  transitions), `/cash-payment` (POS confirms a cash sale), `/redispatch`
  (request a new courier), and reads: a single order, its event audit log, its
  delivery telemetry, and `GET /api/orders/active` (everything in flight — what
  the kitchen board polls).
- **`PosMenuController` / `PosEventController`** — menu reads (available and
  full/86-aware), item and choice availability toggles (86'ing), event status,
  activation, and per-event sales summaries.
- **`PublicApiController`** — the small set of endpoints under `/api/public/**`
  that are intentionally keyless.

### 4. Delivery + payments integration

Stripe and Uber Direct each sit behind an abstraction (see their sections
below) and report progress back via `/webhooks/**`.

---

## Security model

Two Spring Security filter chains, ordered, with no overlap (`SecurityConfig`):

**API chain (`@Order(1)`)** matches `/api/**`, `/webhooks/**`, `/actuator/**`.
Stateless (no session), CSRF disabled, CORS applied. Authorization:

- `/actuator/health/**`, `/actuator/info` — public
- `/webhooks/**` — public (each webhook verifies its own signature; see below)
- `/api/public/**` — public
- `OPTIONS /**` — public (CORS preflight)
- every other `/api/**` — requires authentication
- anything else in scope — denied

Authentication on this chain is an **API key**. `ApiKeyFilter` reads
`X-API-Key`, or falls back to `Authorization: Bearer <key>`, and compares it to
`app.api-key`. A match grants a synthetic `ROLE_CLIENT` principal for the
request. This is why the POS, kitchen board, and menu board all send the same
key header.

**Storefront chain (`@Order(2)`)** matches everything else. Cookie session,
form login (`/login`, username parameter `email`), CSRF enabled. Authorization:

- Public: `/`, `/menu/**`, `/about`, `/contact`, `/events`, auth pages, static
  assets, **and the entire cart/checkout/order flow** (`/cart/**`,
  `/checkout/**`, `/order/**`) — guests can order without an account
- `/account/**` — requires `CUSTOMER`
- `/admin/**` — requires `ADMIN`
- everything else — authenticated

Roles are hierarchical: `ADMIN` implies `CUSTOMER`, so an admin can reach
anything a customer can. Passwords use a `DelegatingPasswordEncoder` defaulting
to BCrypt (configurable strength, default 12). Login success/failure handling
is factored into a standalone `AuthHandlers` component to avoid a bean cycle
through `UserService`.

CORS allows the configured origins (the local kiosk/dev ports by default) with
credentials, and permits the `X-API-Key`, `Authorization`, and `Stripe-Signature`
headers among others.

---

## Domain model

MongoDB documents (`models/storefront/**`, `models/user/**`):

- **`Order`** — the unit of revenue. Carries source (`WEB | POS | KIOSK`),
  status, fulfillment (`PICKUP | DELIVERY | DINE_IN`), payment method, money
  fields (subtotal/tax/delivery/tip/total in integer cents), the line items,
  optional customer denormalization, Stripe ids, delivery provider fields, and
  a `promisedReadyAt` quote stamped at checkout. A delivery cancelled mid-flight
  does **not** auto-cancel the order; instead `deliveryAttentionRequired` is set
  so staff can redispatch, refund, or convert to pickup.
- **`Order.LineItem`** — `menuItemId`, `name`, `quantity`, `unitPriceCents`,
  **`modifiers` (`List<String>`)**, and `notes`. Modifiers are the selected
  option labels (e.g. `"Cheese: Beer cheese"`); price deltas are folded into
  `unitPriceCents`, not stored per-modifier.
- **`MenuItem`** + menu meta (`Category`, `Badge`, `OptionGroup`,
  `OptionChoice`) and the read projection `MenuItemView`. Option groups are
  `SINGLE` or `MULTI`, may be required, and carry availability + per-choice
  price deltas.
- **`Event`** / **`EventOccurrence`** — scheduled and recurring service events;
  every order binds to an `eventId`.
- **`Cart`** — persisted user/guest carts with selected-option lines.
- **`User`** / **`Address`** / **`PaymentMethod`** — accounts, with roles.
- **`OrderEvent`** / **`DeliveryEvent`** — audit/telemetry logs per order.
- **`WebhookEvent`** — inbound webhook dedupe/record.

---

## Order lifecycle

Status flow, enforced as a whitelist by `OrderStateTransitions` (any transition
not explicitly allowed is rejected):

```
PENDING_PAYMENT ──(payment system only)──► PAID
PAID             ─► IN_KITCHEN, CANCELLED
IN_KITCHEN       ─► READY, CANCELLED
READY            ─► OUT_FOR_DELIVERY   (delivery orders only)
                 ─► COMPLETED          (pickup / dine-in only)
                 ─► CANCELLED
OUT_FOR_DELIVERY ─► COMPLETED, CANCELLED
COMPLETED · CANCELLED · REFUNDED        (terminal)
```

The `READY →` branch is fulfillment-aware: delivery orders must pass through
`OUT_FOR_DELIVERY`; pickup/dine-in go straight to `COMPLETED`.
`OrderTransitionService` is the single mutation entry point — the POS API, the
admin console, and the delivery webhooks all transition orders through it, so
the matrix is enforced identically no matter who asked. Rejections come back
with a human-readable reason from `rejectionReason`.

---

## Ordering paths: web vs POS

There are two ways an order is created, and they now persist **identically**:

- **Web (storefront).** Guest or signed-in customer builds a cart, checks out,
  pays by card via Stripe Elements. The cart's selected options become the
  line item's `modifiers` list; option price deltas are folded into
  `unitPriceCents`.
- **POS (`POST /api/pos/orders`).** The terminal rings up items and submits
  `PosLineItem`s. Each carries a clean `name`, `unitPriceCents` (deltas folded
  in), and a structured `modifiers` array — the same `"Group: Choice"` label
  shape the web path produces. The order is created `PENDING_PAYMENT`; cash is
  confirmed via `/cash-payment`, card via the Terminal flow.

> **Note on a past gotcha.** The POS used to flatten modifiers into the item
> *name* (`"Shepherd's Fries (Beef, Beer cheese, …)"`) because the line-item
> DTO had no modifiers field. That is fixed: `PosApiController.PosLineItem` now
> carries `List<String> modifiers` and `createPosOrder` maps it onto
> `Order.LineItem.modifiers`, matching the web path. A POS order and a web order
> for the same items are now byte-identical in Mongo. Legacy POS orders created
> before the fix still have modifiers in their name; downstream displays handle
> those with a name-parenthetical fallback.

Every order **must** carry an `eventId`. The POS guard checks event
*existence*, not liveness — an offline cash sale flushed after its event ended
still binds to that (now-ended) event, which is correct attribution rather than
an error.

---

## Payments (Stripe)

Single-tenant, **direct charges** — all Stripe calls use the account's secret
key; no connected accounts, no application fees. (`CheckoutService` documents
exactly what would change to go multi-tenant, if that's ever wanted.)

- **Online (storefront):** eager-mount **PaymentIntents** via Stripe Elements.
  `ensurePaymentIntent` creates (or reuses) an intent when `/checkout` renders;
  `updatePaymentIntent` pushes new totals as the page changes. Registered users
  get a Stripe Customer (`ensureStripeCustomer`) and may opt to save a card
  (`setup_future_usage`), which is explicitly cleared if they opt back out.
- **In person (POS):** **Stripe Terminal** — `PosApiController` issues a
  connection token and a `card_present` PaymentIntent for the Verifone reader,
  tagging the order id and source in metadata.
- **Mock mode:** if `STRIPE_SECRET_KEY` is unset, the legacy path returns fake
  URLs and Terminal returns mock secrets, so the app runs end-to-end without
  Stripe credentials in dev.

Idempotency keys are used on intent and customer creation; payment metrics are
recorded via `PaymentMetrics`.

---

## Delivery (Uber Direct)

All delivery goes through the `DeliveryProvider` interface — `quote()` and
`dispatch()` over simple request/response records — so the storefront and POS
never see a vendor SDK. `DeliveryConfig` selects the implementation from
`app.delivery.provider`:

- **`uber`** — real Uber Direct (`UberDirectProvider` + `UberDirectClient`,
  with OAuth via `UberDirectAuthClient`). Sandbox vs prod is distinguished by
  credentials.
- **`mock`** — plausible fake quotes/dispatches with no external calls; the
  default, and the fallback for any unrecognized provider value.
- DoorDash Drive is scaffolded in config (`app.delivery.doordash.*`) for a
  future third option.

Live deliveries are tracked by `UberDeliveryPoller`, and Uber status strings are
normalized into the app's own states by `UberDeliveryStatusMapper`. Courier
progress also arrives via webhook (below). The pickup address and per-order
quote/fee/ETA/tracking-URL are stored on the order.

---

## Webhooks

Inbound, under the public `/webhooks/**` matcher — each handler verifies its own
signature rather than relying on the API key:

- **`StripeWebhookController`** — payment lifecycle events, verified with
  `STRIPE_WEBHOOK_SECRET` against the `Stripe-Signature` header. Drives
  `PENDING_PAYMENT → PAID` and refund handling.
- **`UberWebhookController`** — delivery status callbacks, verified with the
  Uber webhook signing key.

`WebhookEventService` / `WebhookEvent` record inbound events (dedupe/audit), and
`OrderLockService` guards against concurrent mutation when a webhook and a poll
land at once.

---

## Configuration

All app config is under the `app.*` prefix, bound to the typed `AppProperties`
record, and driven by environment variables (sensible localhost defaults in
`application.yaml`; production overrides in `application-prod.yaml`). Key groups:

| Env var | Purpose |
|---|---|
| `MONGODB_URI` | Mongo connection (default local) |
| `SERVER_PORT` | HTTP port (default 8080) |
| `API_KEY` | shared key for the `/api/**` chain — **must match the POS / boards** |
| `CLIENT_ID` | tenant/client tag (metrics, Stripe metadata) |
| `CORS_ORIGINS` | comma-separated allowed origins (defaults include kiosk dev ports) |
| `BCRYPT_STRENGTH` | password hashing cost (default 12) |
| `COOKIE_SECURE` | secure-flag on cookies (default true) |
| `CAPTCHA_SITE_KEY` / `CAPTCHA_SECRET_KEY` | Cloudflare Turnstile (test keys by default) |
| `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` | Stripe API keys (unset ⇒ mock mode) |
| `STRIPE_WEBHOOK_SECRET` | verifies Stripe webhook signatures |
| `STRIPE_LOCATION_ID` | Stripe Terminal location |
| `DELIVERY_PROVIDER` | `mock` \| `uber` \| (`doordash` later) |
| `PICKUP_ADDRESS` | dispatch origin for delivery quotes |
| `UBER_CUSTOMER_ID` / `UBER_CLIENT_ID` / `UBER_CLIENT_SECRET` / `UBER_WEBHOOK_SIGNING_KEY` | Uber Direct creds |
| `KITCHEN_CAPACITY` | concurrent prep slots for ready-time quoting (solo cook ≈ 2) |
| `KITCHEN_DEFAULT_ITEM_PREP_MINUTES` / `KITCHEN_PER_ITEM_SURCHARGE_MINUTES` | quote tuning |
| `BRAND_NAME` / `TIMEZONE` / `CONTACT_*` / `*_URL` / image paths | storefront branding |

Actuator exposes `health`, `info`, `metrics`, and `prometheus`; liveness/readiness
probes are enabled. `/actuator/health` is the unauthenticated probe the kiosks
use for connectivity.

> **Secrets:** never commit real values. Everything sensitive is an env var with
> a safe default (Stripe/Uber default to empty ⇒ mock/disabled; the API key
> defaults to an obvious change-me placeholder).

---

## Running locally

Prerequisites: **JDK 21**, **Maven**, and a local **MongoDB** (or a `MONGODB_URI`
pointing at one).

```bash
# from the repo root
./mvnw spring-boot:run
# app on http://localhost:8080
```

With no Stripe/Uber credentials set, the app runs fully in **mock mode** —
checkout returns mock payment flows and delivery returns fake quotes, so you can
exercise the whole ordering path offline. Thymeleaf and static-resource caching
are disabled by default in dev for live reload.

To let a locally-running POS / kiosk app reach this backend, set `API_KEY` here
and add the kiosk's dev origin (e.g. `http://localhost:5173`) to `CORS_ORIGINS`.

Build a runnable jar:

```bash
./mvnw clean package
java -jar target/cgsKitchen-0.0.1-SNAPSHOT.jar
```

---

## Deployment

Standard Spring Boot fat-jar. Run with the `prod` profile active
(`SPRING_PROFILES_ACTIVE=prod`) so `application-prod.yaml` overrides apply, and
supply the real env vars (Mongo URI, API key, Stripe + Uber credentials, brand
config, `CORS_ORIGINS` for the live kiosk/site origins). The server supports
graceful shutdown and response compression out of the box. Point Stripe's and
Uber's webhook dashboards at `/webhooks/stripe` and the Uber webhook path
respectively, using the configured signing secrets.

---

## Project layout

```
src/main/java/com/celtech/solutions/cgsKitchen/
  config/            SecurityConfig (2 chains + ApiKeyFilter), AppProperties,
                     CartCookieFilter, Mongo auditing/index config, AuthHandlers,
                     SecurityHeadersFilter, GlobalModelAttributes, ImageUrls
  controllers/
    storefront/      StorefrontController, CartController  (Thymeleaf + cart)
    admin/           Orders, Menu, Options, Events, Users  (admin console)
    api/pos/         PosApiController, OrderStatusController,
                     PosMenuController, PosEventController
    api/             PublicApiController                   (keyless /api/public)
    user/            AuthController, AccountController
    webhooks/        StripeWebhookController, UberWebhookController
  services/
    storefront/shop/ CartService, CheckoutService, CartValidation,
                     AbandonedCheckoutSweeper, PaymentMetrics
    storefront/kitchen/ OrderService, OrderTransitionService,
                     OrderStateTransitions, KitchenQuoteService,
                     OrderEventService, DeliveryEventService
    storefront/event/ EventService, EventSummaryService
    storefront/menu/ MenuService
    user/            UserService, AppUserDetailsService, AddressService,
                     TurnstileService
    webhooks/        WebhookEventService, OrderLockService
  delivery/
    DeliveryProvider (interface), DeliveryConfig (provider selection),
    uber/            UberDirectProvider, UberDirectClient, UberDirectAuthClient,
                     UberDeliveryPoller, UberDeliveryStatusMapper, RetryHelper
  models/
    storefront/kitchen/  Order, OrderEvent, DeliveryEvent
    storefront/menu/     MenuItem, meta/{Category,Badge,OptionGroup,OptionChoice},
                         view/MenuItemView
    storefront/event/    Event, EventOccurrence
    storefront/shop/     Cart
    user/                User, Address, PaymentMethod
    webhooks/            WebhookEvent
  repositories/      Spring Data Mongo repositories (per aggregate)

src/main/resources/
  application.yaml          base config (localhost defaults)
  application-prod.yaml     production overrides
  templates/                Thymeleaf: storefront/, admin/, account/, auth/,
                            fragments/layout.html
```

---

## Related repositories

This backend is the hub; these are its API clients (separate repos):

- **CGS POS** — Vue web terminal (ordering, cash, kitchen board, inventory,
  events). Consumes `/api/pos/**`, `/api/orders/**`, `/api/menu/**`,
  `/api/events/**`.
- **CGS Kitchen Board** — read-only Vue expo display; polls `/api/orders/active`.
- **CGS Menu Board** — read-only customer-facing Vue menu; polls `/api/menu/all`.