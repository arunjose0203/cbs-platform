# Screening and transaction monitoring

Added 11 November 2026 (control migration V45). This closes the automated part of functional area F13 (compliance cases). The lists are fictional in tests, and no real sanctions or PEP list ships with CBS: each bank uploads the lists its regulator requires.

## What it does

| Area | Behaviour |
| --- | --- |
| Screening lists | Uploaded per bank as a CSV draft. A draft has no effect. A different administrator must approve its activation (`SCREENING_LIST`). Activation supersedes the previous version with the same name. |
| When customers are screened | Every approved customer profile version is screened against all active lists, in the same transaction that applies it. Activating a list version also queues a re-screen of every customer, run in resumable chunks of 500. |
| Possible match | Recorded as an **open hit**. An open hit restricts the customer and any organization the customer is a director, owner or representative of, through the existing compliance restriction check. Funding, withdrawals, transfers, cash, payouts, account opening and daily servicing all treat that customer as restricted. |
| Decisions on a hit | **Escalate**: takes effect immediately and opens a `SCREENING_ALERT` compliance case, which keeps the customer restricted until an independent review. **Dismiss as a false positive**: needs maker-checker approval (`COMPLIANCE_ALERT_DISMISS`). A dismissal carries over to later list versions while the screened name and the list entry are unchanged. |
| Transaction monitoring | Runs after posting. A background scan copies each newly completed customer movement into an immutable monitoring history: teller cash, funding, transfers in both directions, withdrawals and M-Pesa payouts. It then evaluates the bank's approved rules for that currency (`MONITORING_POLICY`). |
| Alerts | Alerts are for investigation only and **never block the customer**, so the customer is not tipped off. Escalation opens a `TRANSACTION_REVIEW` case, which does restrict. Dismissal needs approval, as for hits. |
| Evidence | List versions, their entries and the monitoring history cannot be changed or deleted (database triggers). Viewing a hit or alert is audited. Every decision records who made it, the evidence reference, the reason and, for dismissals, the approval. |

## Screening list CSV

```
entry_ref,entity_type,name,aliases,birth_date,country,program
UN-1,INDIVIDUAL,Viktor Ivanovich Petrov,"V. Petrov; Victor Petrov",1970-03-01,RU,Asset freeze
UN-2,ENTITY,"Blue Ocean Trading Co., Ltd",,,PA,
```

Rules for the file:

- **Size:** UTF-8, at most 6 MB and 100,000 entries per version.
- **entry_ref:** required and unique within the file.
- **entity_type:** `INDIVIDUAL` or `ENTITY`.
- **Names:** `aliases` holds extra names separated by semicolons. An entry can have at most 20 names in total.
- **Optional fields:** `birth_date` (YYYY-MM-DD), `country` (two letters) and `program`.

An invalid file is rejected with the line number. The upload records a SHA-256 checksum. The approver sees that checksum, the list name, the category, the source, the number of entries and the threshold. Activation fails if the checksum does not match the draft.

Convert official lists, such as UN consolidated or national lists, to this format. The source field should say which list and which publication date was used.

## How names are matched

- **Normalization:** accents are removed; case, punctuation and word order are ignored. Titles (Mr, Dr) and company suffixes (Ltd, PLC, Co.) are dropped.
- **Scoring:** words are compared with Jaro–Winkler similarity. An initial matches the word it abbreviates.
- **Single-word names:** a one-word name against a longer listed name is discounted, because a shared first name alone is weak evidence.
- **Birth dates:** when both the customer and the entry are individuals with a birth date, a difference of more than one year rules the entry out.
- **Threshold:** set per list version, between 0.70 and 1.00. The default offered is 0.88.
- **Known gap:** candidates are found through the first three letters of each word, so a name misspelled in the first three letters of every word is not found.

The thresholds and the matcher are a reasonable baseline, not a certified screening engine. The bank should tune the threshold against its own lists and test cases before relying on it. It should also keep its regulator's list-update timetable: re-screening runs only when a new list version is activated.

## Monitoring rules

Rules are set per bank and currency. Each rule is off until all of its fields are filled in.

| Rule | Raises an alert when |
| --- | --- |
| Large cash | A single teller cash deposit or withdrawal is at or above the amount. |
| Structuring | At least *count* cash deposits, each below the amount, total at least the amount within *days* business days. Raised once per account and day. |
| Velocity | An account has at least *n* debits in one business day. Raised once per account and day. |
| Unusual amount | A movement is at least *multiplier* × the account's average for the same direction over the previous *days*, with at least *minimum* earlier movements. |

The scan runs every minute. It reads completed postings by completion time, with a ten-minute overlap, and de-duplicates them, so a slow commit is not missed and a movement is never counted twice. A teller deposit also creates a funding record, and a teller withdrawal or a payout also creates an account operation. Each movement is still classified once, by the channel it came from.

## Screens and API

The portal has a new section, **Screening & monitoring**, with four panels:

- possible matches
- monitoring alerts
- screening lists (upload a draft and request activation)
- monitoring rules

Hits and alerts each have Evidence, Escalate and Request dismissal actions.

API, under `/api/v1/admin/tenants/{bank}/compliance`:

- `GET|POST /screening/lists`
- `GET /screening/lists/{id}/entries`
- `POST /screening/activations` (approval)
- `GET /screening/hits`
- `GET /screening/hits/{id}`
- `POST /screening/hits/{id}/escalate`
- `GET|POST /monitoring/policies` (POST needs approval)
- `GET /monitoring/alerts`
- `GET /monitoring/alerts/{id}`
- `POST /monitoring/alerts/{id}/escalate`
- `POST /dismissals` (approval)

## Verification

`screeningListsRestrictMatchesUntilDecidedAndMonitoringRaisesAlertsWithoutTippingOff` runs the whole flow against real postings. It covers:

- **Draft handling:** a draft is inert, a tampered checksum is refused, and self-approval is refused.
- **Re-screening:** existing customers are screened in chunks after activation.
- **Screening on onboarding:** new customers are screened when onboarded, and a namesake with a different birth year is ruled out.
- **Organizations:** an organization is restricted through its director.
- **Escalation:** opens a restricting case, and a second escalation of the same hit is refused.
- **Dismissal:** goes through approval, lifts the restriction, and carries over to a new list version.
- **Immutable evidence:** lists, entries and monitoring history cannot be changed or deleted.
- **Monitoring alerts:** on real teller cash, funding, withdrawals and transfers, all four rules fire, and open alerts do not restrict the customer.
- **Re-scan:** a second scan adds nothing.
- **Alert decisions:** dismissal with approval, and escalation into a restricting case.

The payment lifecycle test confirms that an M-Pesa payout is classified once, as `MOBILE/OUT`. `NameMatcherTest` covers normalization, reordering, misspellings, initials, birth-date exclusion and weak names. The UI tests cover escaping, idempotent submission and the CSV upload.

The test found and fixed one defect during development. Teller cash was first recorded under its funding twin record and so never reached the cash rules.

## Not included

- **Real lists and feeds:** no real list is included, and there are no automatic list feeds.
- **Watch-listing of payment beneficiaries:** M-Pesa payee names are not screened.
- **Peer-group and network analytics.**
- **Suspicious-activity report filing:** there is no regulator filing format.
- **Automatic periodic re-screening:** customers are re-screened only when a list version is activated, not on a timetable.
- **Case-management SLAs.**
