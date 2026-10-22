# Rules

A *rule* tells Butterfly III how to turn one kind of notification into a
transaction: where to find the amount, which accounts to use, what to write
in the description. Common formats work without any rule of your own (see
[Built-in rules](#built-in-rules)). You write a rule when a bank's wording is
different, or when you want your own accounts, categories and tags.

You find the rules in **Settings → Rules**.

<img src="img/rules-list.png" width="270" alt="The list of rules">

## How a notification is matched

For every notification from a monitored app, the app goes through your rules
from the top of the list down. The **first rule that fits wins**; the rest are
not tried.

A rule fits when all of these are true:

1. The rule is **enabled**.
2. The notification comes from the rule's **app**. A rule belongs to exactly
   one app.
3. The rule's **regular expression** matches the notification text.
4. The expression captured an **amount** that the app can read as a number.

If no rule fits, the built-in rules are tried last. If nothing fits at all,
the message stays in the list without a transaction, and tapping it tells you
why (no rule for this app, no pattern matched, or the amount could not be
read).

The text a rule sees is the notification **title, a new line, then the body**.
For a Google Wallet notification with the title `Rewe` and the body
`12.60 EUR with Vivid`, the rule reads:

```
Rewe
12.60 EUR with Vivid
```

To change the order, open the **⋮** menu of a rule and choose *Move up* or
*Move down*. Press and hold a rule to select several; then you can move,
enable, disable or delete them together.

## The regular expression

A *regular expression* (regex) describes the shape of the text you want to
read. Butterfly III uses the Java flavour. The expression only has to **find**
a match somewhere in the text; it does not have to cover all of it. A dot (`.`)
also matches a line break, so the title and the body can be matched together.

What you need to know is how to name the parts you want. A *named group* looks
like `(?<name>…)` and takes whatever its inner part matches:

| Group | Meaning |
|---|---|
| `amount` | **Required.** The amount, for example `12.60` or `1.234,56`. |
| `currency` | The currency, as a code (`EUR`) or a symbol (`€`). |
| `foreignAmount`, `foreignCurrency` | The original amount and currency of a payment made in another currency. |
| `date` | The date the transaction really happened, when the text gives one. Needs a *Date format*, see [below](#the-date-of-the-transaction). |

You can add groups with other names too, for example `counterparty` for the
shop. They are not special, but you can use them as `{macros}` in the fields
below the expression.

### A first example

For the notification above, this rule is enough:

```
(?<amount>\d[\d.,]*)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥])\s+with\s+Vivid
```

Read it from the left:

- `(?<amount>\d[\d.,]*)` takes a digit followed by digits, dots and commas:
  `12.60`.
- `\s*` allows spaces (or none) between the amount and the currency.
- `(?<currency>…)` takes `EUR`, or a symbol such as `€`, `£`, `$`, `¥`.
- `\s+with\s+Vivid` makes the rule fit only payments made with this card.

The built-in rules use the same `amount` and `currency` parts; copy them as a
starting point.

### More examples

All of these were checked against the sample text next to them.

The shop name in the text, with the currency symbol before the amount:

```
Card payment
Payment to Rewe of €12.60 was successful
```

```
Payment to (?<counterparty>.+?) of (?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥])(?<amount>\d[\d.,]*)
```

This captures `counterparty` = `Rewe`, `currency` = `€`, `amount` = `12.60`.
The lazy `.+?` stops at the first ` of `, so the name does not swallow the rest.

A payment in another currency, with the original amount in brackets:

```
Amex
45.20 EUR (49.00 USD) at Shop
```

```
(?<amount>\d[\d.,]*)\s*(?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥]).*?\((?<foreignAmount>\d[\d.,]*)\s*(?<foreignCurrency>[A-Z]{3})\)
```

### Two things that often go wrong

- **Do not start with a greedy `.*` before the amount.** It lets the engine
  settle for the *last digit* of a long number. Use `[^\d]*` to skip only
  non-digits: `Zahlung[^\d]*(?<amount>\d[\d.,]*)`.
- **Write `\d[\d.,]*`, not just `\d+`.** `\d+` stops at the first dot or comma,
  so `1.234,56` becomes `1`.

### How the amount is read

Notifications write numbers in many ways. The app looks at the **last**
separator (dot, comma, apostrophe or space): it is a decimal point only if
exactly two digits follow it. So `12,60` and `12.60` are both 12.60, and
`1.234,56` is 1234.56. Every other separator is treated as a thousands
separator. The sign is ignored; the direction comes from the rule's *Type*.

### Currency symbols

A bank often prints `€50.00` and not `50.00 EUR`. The app translates symbols
to codes with the table in **Settings → Currencies**. A symbol that is not in
the table is looked up in Firefly III and added for you. A symbol shared by
several currencies (such as `$`) is not added by itself; add it to the table
yourself.

## The fields of a rule

<img src="img/rules-editor-top.png" width="270" alt="The top of the rule editor">

- **Label**: the name you see in the list and in notifications.
- **App**: the app whose notifications this rule reads.
- **Type**: *Withdrawal* (money leaves your account), *Deposit* (money comes
  in) or *Transfer* (between your own accounts).
- **Regular expression**: see above.
- **Pick message…** puts the text of one of the saved notifications for this
  app into the field, as a starting point you can edit.
- **Test…** runs the expression on the saved notifications of this app and
  shows the first few matches, with what each group captured.

<img src="img/rules-test-results.png" width="270" alt="The Test results dialog">

### Accounts

<img src="img/rules-editor-accounts.png" width="270" alt="The account fields of the rule editor">

For a **withdrawal**, your account is the *source* and the shop is the
*destination*. For a **deposit** it is the other way round. A **transfer**
needs both.

Your own account (the *asset* side) is chosen from the list of accounts in
Firefly III. The other side is a text with macros: `{title}` uses the
notification title, `{counterparty}` uses a group you captured. Firefly III
creates the shop as an expense account by that name if it does not exist.

**Account currency** is the currency of your account. If the notification is in
a different currency, see [Foreign currency](#foreign-currency). Leave it empty
to use the default currency from **Settings → Transactions**.

### Account is a guess

Some banks do not say which card or account was used. Then the account in the
rule is only a guess. Turn on **Account is a guess** for such a rule:

- The app **never creates the transaction by itself** for it. You get a
  notification to review it first.
- Duplicate detection compares **amount and time only**, not accounts. If your
  wallet app reports the same payment and knows the real account, the wallet's
  transaction is created and the bank's reminder is removed from the shade.

### Amount is entered by hand

Some notifications carry no amount at all, for example "Payment received" from
your employer. Turn on **Amount is entered by hand** and the expression does
not need an `amount` group. When such a message matches, the app opens the
review screen with the amount empty and the account, category and description
already filled in. You type the amount and create the transaction. It is never
created by itself. If the expression does capture an amount, that one is used.

A message that no rule matches can also be made into a transaction by hand:
tap it and choose **Create manually**.

### Description, notes, category and tags

<img src="img/rules-editor-templates.png" width="270" alt="The template fields of the rule editor">

These are **templates**. Plain text stays as it is, and `{name}` is replaced:

- `{title}` and `{body}` are the notification title and body.
- `{amount}`, `{currency}` and any other group of your expression.

To write a real `{word}` that is not a macro, double the braces: `{{word}}`.
The editor lists the macros you can use under the expression.

- **Category template** gives one category name. Leave it empty for none.
- **Tags template** is a comma-separated list. Tags that should be on *every*
  transaction go into **Settings → Transactions → Add tags to all created
  transactions** instead of into every rule.

While you type a category or a tag, the app suggests the ones that already
exist in Firefly III.

### The date of the transaction

By default the transaction gets the time the notification arrived. Some banks
announce a charge for a later day, such as `The direct debit on 01.02.30 of
€99.99`:

```
direct debit on (?<date>\d{2}\.\d{2}\.\d{2}) of (?<currency>[A-Z]{1,3}\$|[A-Z]{3}|[€£$¥])(?<amount>\d[\d.,]*)
```

Capture the date in a group named `date` and set **Date format** to match it,
here `dd.MM.yy`. The format is never guessed: `01.02.30` could be the first of
February or the second of January, and only you know your bank's habit. The
app has presets for the common ones, and you can type your own. A notification
gives no time of day, so the transaction gets noon on that date. If the text
does not fit the format, the app tells you and uses the arrival time.

### Foreign currency

When the currency in the notification differs from the account currency and
there is no `foreignAmount` group, the app treats the amount as the **foreign**
amount and asks a public exchange-rate service for an approximate amount in
your currency (it sends only the two currency codes and the date). Such a
transaction gets the tag from **Settings → Transactions →
Approximate-conversion tag**, so you can find and fix it when the real amount
is known. If
the rate cannot be fetched, the transaction is not created by itself.

If the notification gives both amounts, use the `foreignAmount` and
`foreignCurrency` groups, and nothing is converted.

## Built-in rules

Two rules are built in and always come **after** yours:

- *Default: Currency before amount*, for text like `€12.60`.
- *Default: Currency after amount*, for text like `12.60 EUR`.

They work for any monitored app and take the whole notification as the
description. They do not know your accounts, so a transaction made from one of
them is always **reviewed** by you first: you pick your account on the create
screen, and the transaction type follows from which side you pick.

In the list they are shaded. Tapping one shows it read-only; **Duplicate**
turns it into a rule of your own that you can edit. One switch in the rule
details turns both on or off.

## Share your rules

The two arrows in the toolbar export your rules to a file and import them
again. Import **adds** the rules from the file as new ones; it never replaces
what you have. For a copy of all your settings, see **Settings → Backup &
restore**.
