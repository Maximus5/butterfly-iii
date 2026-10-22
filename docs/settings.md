# Settings

Open the settings with the gear icon at the top right of the message list.
This page walks through every screen, in the order of the list. For the first
setup, [Getting started](getting-started.md) is shorter.

## Server

The connection to your Firefly III.

- **Firefly III server URL**: the address you open Firefly III at in a browser,
  without `/api`. An `https://` address is expected. A plain `http://` address
  is refused until you turn on **Allow unencrypted HTTP**, because your token
  and your transactions would then travel unprotected. Use that only inside a
  network you trust.
- **Personal access token**: create it in Firefly III (profile icon →
  *Remote access and tokens*). Once saved, it is never shown again; leave the
  field empty to keep it. If you change the server address, the saved token is
  cleared and you enter a new one.
- **Test connection** checks the address and the token and shows the version of
  your Firefly III.
- **Protect from screenshots** keeps this screen out of screenshots and screen
  recordings. It turns itself on every time you open the screen.

After you save, the app fills in the default currency and the default source
account when Firefly III has only one possible choice.

## Transactions

How transactions are created.

- **Create transactions automatically**: when a rule fits, the app creates the
  transaction at once. When it is off, you get a notification to review the
  payment first. Even when it is on, a transaction is only created by itself
  when everything is known: a rule matched, your account is set, and the amount
  is available. In every other case you are asked to review it. See also
  [Account is a guess](rules.md#account-is-a-guess).
- **Avoid duplicate transactions**: before creating a transaction, the app
  checks whether one with the same accounts, amount and currency already
  exists around the same time. This is what keeps your bank app and your wallet
  app from creating the same payment twice.
  - **Also compare description** makes the check stricter.
  - **Dedup window (minutes)** is how far apart in time two payments may be to
    count as the same one. The default is 5.
- **Default account currency** and **Default Source account**: used when a rule
  does not say. The default account is only a starting point on the create
  screen; automatic creation still needs the rule itself to name an account.
- **Add tags to all created transactions**: a comma-separated list. These tags
  are added to everything the app creates, whatever rule was used.
- **Approximate-conversion tag**: the tag for a transaction whose amount had to
  be worked out from an exchange rate. See
  [Foreign currency](rules.md#foreign-currency).

The two tag fields suggest the tags that already exist in Firefly III.

## Notifications

What the app's own notifications show.

- **Show notifications**: a notification for each payment: *Transaction to
  review* when you have to look at it, or *Transaction created* with buttons to
  open it in the app or in Firefly III.
- **Show financial information in notification**: add the amount, currency and
  description to the notification text. It is off by default, because a
  notification can be read by someone else, for example on a locked screen.
- **Notify about skipped duplicates**: say so when automatic creation skips a
  payment that already exists. The duplicate is skipped either way; this only
  controls the message.
- **Open system notification settings…** opens Android's own page for this app,
  where you can change the sound and the importance of each kind.

If Android blocks the app's notifications, a card at the top says so and has a
button to allow them.

### Keeping the monitoring running

The same screen has a section that warns about two things that can silently
stop the app from seeing payments: **battery optimization** and Android
**removing permissions** of apps you do not open for a while. Each warning has
a button that opens the right system page. More about this is in
[Using the app without a connection](offline.md#keep-the-app-running-in-the-background).

## Monitored apps

The apps whose notifications are read. Search by name and tick the apps you
want, for example your bank and Google Wallet. Notifications from all other apps
are ignored.

## Currencies

A table that connects a symbol to a currency code, such as `€` to `EUR`. It is
how the app understands `€12.60` in a notification. You can add, edit and delete
rows. The app also adds an unknown symbol by itself after looking it up in
Firefly III. See [Currency symbols](rules.md#currency-symbols).

## Rules

How a notification becomes a transaction. See [Rules](rules.md).

## Message history

<img src="img/message-list.png" width="270" alt="The message list with six captured payments">

The list on the main screen holds the notifications the app has captured, newest
first. Each message shows the number of its transaction once one is created.
Tap a message to review and create its transaction, or to edit the one that is
already linked. For a message no rule matches, the dialog offers **Create
manually**, which opens the same screen with the amount empty. The **⋮** menu of a message can also open the transaction in
Firefly III, check whether it already exists, or forget a link.

- **Save history to local storage**: when on, captured notifications survive
  closing the app and restarting the phone. When off, they are kept in memory
  only. Turn it on to [use the app without a connection](offline.md).
- **Keep for 1, 3, 5, 7 or 30 days**: how long messages are kept. This also
  applies to the log of created transactions that duplicate detection uses.
  Older entries are deleted automatically.
- **Add test message…** adds a made-up notification, as if it had just arrived.
  It runs through the rules in exactly the same way, so it is the easy way to
  try a rule. With **Delay adding by 10 seconds** the screen closes at once and
  the message appears ten seconds later, so you can leave the app first and see
  what a real notification does.

Transactions that are already in Firefly III are not affected by deleting
history.

## Backup & restore

- **Create backup…** saves your monitored apps, currencies, rules and message
  history to a file. The connection to Firefly III (address and token) is not
  included, so enter it again after a restore.
- **Restore backup…** replaces everything now saved on the phone with the file.
  The app asks you to confirm first.
- **Export rules…** and **Import rules…** do the same for the rules alone.
  Import adds the rules to the ones you have.

The history in a backup contains the full text of your notifications: amounts,
card digits, shop names. Keep the file somewhere safe.

## About

The version of the app and a link to the project.

**Write diagnostic log** is for finding problems. It is off by default. When it
is on, the app keeps short technical notes about what it did with each message:
your rule names, times and Firefly III transaction numbers, and a short
fingerprint of each amount. It does not write amounts, descriptions or account
names. **Copy diagnostic log** puts it on the clipboard so you can send it with
a bug report; turning the switch off deletes it.
