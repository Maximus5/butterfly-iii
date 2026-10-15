package io.github.maximus5.ffnotifyimporter.rules

/**
 * Named-group conventions a [ParseRule.pattern] can define, that this layer knows how to use.
 * `amount` is the only mandatory one. The `parser` layer doesn't know any of these names - it
 * just extracts whatever's declared.
 *
 * `amount`/`currency`               - amount as reported by the bank
 * `foreignAmount`/`foreignCurrency` - original amount for a cross-currency payment
 * `date`                            - the transaction's *own* date, when a notification reports
 *                                      one that differs from when the notification itself arrived
 *                                      (e.g. "the charge will happen on 01.02.30") - only used
 *                                      when [ParseRule.dateFormat] is also set, see its KDoc.
 *
 * `counterparty`                   - who was paid / who paid. Also an ordinary `{macro}` name for the
 *                                      templates, but this exact name is what "Auto categories"
 *                                      ([AutoCategories]) keys on: a rule that names the group
 *                                      differently never learns or gets a learned category.
 *
 * Nothing else is privileged. `source`/`destination` are always [ParseRule.sourceAccountName]/
 * [ParseRule.destinationAccountName] as configured - never auto-filled from a regex capture.
 */
object Groups {
    const val AMOUNT = "amount"
    const val CURRENCY = "currency"
    const val FOREIGN_AMOUNT = "foreignAmount"
    const val FOREIGN_CURRENCY = "foreignCurrency"
    const val DATE = "date"
    const val COUNTERPARTY = "counterparty"
}
