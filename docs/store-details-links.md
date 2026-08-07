# Store Details Links

GameNative exposes a shared **View on {Store}** action from the game detail hero
and from **Options > Help & Info**.

## Provider support

| Provider | Behavior |
| --- | --- |
| Steam | Try the official Steam Android app, then open the canonical HTTPS page |
| GOG | Open the canonical HTTPS page derived from the synchronized game slug |
| Epic Games Store | Disabled until Epic metadata provides an explicit public page slug |
| Amazon Games | Disabled until a stable public product destination exists |
| Custom games | Disabled |

Epic URLs must not be inferred from a title, catalog ID, app name, product ID,
or sandbox name. When Epic exposes a trustworthy slug, pass that explicit value
through `StorePageResolver.epic`.

## Design

- `StorePageResolver` validates provider identifiers and constructs allow-listed
  destinations.
- `StorePageLauncher` attempts explicit native targets and falls back to an
  external browser.
- `BaseAppScreen` owns the shared action and failure recovery.
- A total failure offers the canonical link for copying.
- When usage analytics are enabled, only the store and successful route type
  are recorded. Game IDs, names, URLs, exception text, and package lists are
  excluded.

Store pages intentionally open outside GameNative's WebView because storefront
authentication, commerce, cookies, and navigation require a full browser or
official store app.
