# Writing Cosmo plugins

Plugins are JavaScript files that add widgets to the Wallet tab. Install one in
**Settings → Plugins → Import script** (pick a `.js` file) or **Paste script**. After that, its
widgets appear on the Wallet tab, where you can move and resize them like any other module.

See [`examples/`](examples) for complete plugins you can install as they are:

| Example | Shows |
| --- | --- |
| [`savings-goal.js`](examples/savings-goal.js) | Progress toward an XLM target you set |
| [`activity-summary.js`](examples/activity-summary.js) | Money in and out from recent payments |
| [`tip-jar.js`](examples/tip-jar.js) | Buttons that suggest a payment |
| [`xlm-price.js`](examples/xlm-price.js) | Live XLM price, 24-hour sparkline, your balance's worth, and a price alert |
| [`cosmo-pet.js`](examples/cosmo-pet.js) | Nova, a virtual pet that grows over the days and cheers up when you get paid |

## A first widget

```js
cosmo.registerWidget({
  id: 'hello',            // letters, digits, - or _
  title: 'Hello',
  render: function (data) {
    return ui.column(
      ui.title('Hello, ' + data.cosmoId),
      ui.text('You have ' + data.xlm + ' XLM')
    );
  }
});
```

The script runs once when the plugin loads, and must register at least one widget (up to 10).
`render(data)` runs again whenever the wallet data changes and after any of the widget's buttons
is tapped, so keep state in variables (or `cosmo.storage`) and build the UI from it.

## What `data` contains

A read-only snapshot of the wallet:

| Field | Example | Notes |
| --- | --- | --- |
| `cosmoId` | `"COSMO-7KQ2-M9XD"` | |
| `network` | `"testnet"` or `"public"` | |
| `address` | `"GAAZ…CWN7"` | `null` if the wallet key isn't on this device |
| `activated` | `true` | Whether the Stellar account exists yet |
| `xlm` | `"1250.5"` | XLM balance as a string (exact) |
| `balances` | `[{ code: "XLM", issuer: null, amount: "1250.5", value: 1250.5 }]` | `value` is the same amount as a number |
| `payments` | `[{ incoming: true, amount: "5", value: 5, asset: "XLM", counterparty: "G…", date: "2026-10-25T14:00:00Z" }]` | Newest first, up to 20 |
| `kyc` | `"ACCEPTED"` | Identity verification status with the selected anchor (SEP-12: `ACCEPTED`, `PROCESSING`, `NEEDS_INFO`, `REJECTED`), or `null` |
| `price` | `{ usd: 0.1234, change24h: -1.8, history: [0.1256, …, 0.1234], updatedAt: "2026-10-26T09:00:00Z", source: "Stellar DEX (XLM/USDC)" }` | XLM's price in US dollars from Stellar's exchange (public network, also shown on testnet), refreshed every minute. `history` is hourly closing prices over the last day, oldest first; `change24h` is a percent or `null`. `price` is `null` until the first fetch |

## Building the UI: `ui`

| Function | Draws |
| --- | --- |
| `ui.text(text, { style, color })` | Text. `style`: `"body"` (default), `"title"`, `"headline"`, `"label"`, `"caption"`. `color`: `"#RRGGBB"` |
| `ui.title(text)` | Same as `ui.text(text, { style: 'title' })` |
| `ui.column(a, b, …)` | Stacks elements vertically |
| `ui.row(a, b, …)` | Puts elements side by side |
| `ui.button(label, function () { … })` | A button; the function runs when it's tapped |
| `ui.progress(0.75)` | A progress bar from 0 to 1 |
| `ui.spacer(height)` | Empty space, in dp (default 8) |
| `ui.divider()` | A horizontal line |

`render` may also return a plain string or number, which is shown as text. Widgets are drawn with
the app's own components, so they follow the user's theme and font.

## Talking to the app: `cosmo`

| Function | Does |
| --- | --- |
| `cosmo.registerWidget({ id, title, render })` | Adds a widget (only while the script first loads) |
| `cosmo.storage.get(key)` / `.set(key, value)` / `.remove(key)` | Saves short strings that survive restarts (up to 50 keys of 4,000 characters each, separate for every plugin) |
| `cosmo.toast(message)` | Shows a short message at the bottom of the screen |
| `cosmo.suggestPayment({ to, amount, memo })` | Opens the payment form filled in. The user reviews and confirms it; plugins can't send payments themselves |

## What plugins can't do

Plugins run in a sandbox. They have the standard JavaScript built-ins (`Math`, `JSON`, `Date`,
arrays, strings…) and nothing else:

- no recovery phrase, wallet keys, chat keys or messages;
- no sending payments (only suggesting them);
- no internet, files, or Android/Java APIs;
- no access to other plugins' variables or storage.

Each call has a time limit, so a plugin stuck in a loop is stopped rather than freezing the app,
and a widget can contain at most 300 elements.

## Language support

Plugins run on Mozilla Rhino 1.7.15 in interpreted mode: ES5 plus much of ES6, including `let`,
`const`, arrow functions and template literals. Classes, `async`/`await`, modules (`import`) and
`fetch` aren't available.

## Errors

If a script fails to load, the error shows under the plugin in **Settings → Plugins**. If a widget's
`render` throws, the error (with its line number) shows inside the widget on the Wallet tab.
