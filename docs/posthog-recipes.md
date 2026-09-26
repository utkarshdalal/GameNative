# PostHog recipes

Queries we derive server-side instead of adding client tracking. All of them use events that already
ship behind the usage-analytics setting.

## Time away after a store click

The app does not record when a user comes back from a store page. Derive it from the click event and
the next `Application Opened` event for the same person. That is the PostHog SDK's own lifecycle event
and it fires on every return to the foreground. Do not use our custom `app_foregrounded`; as of
September 2026 it does not appear in PostHog at all. Use `recommendation_link_clicked` for the Discover
buy button and `featured_action_clicked` for campaign CTAs; both carry `sid` (the CJ sub-ID), `source`
and `rank`.

```sql
SELECT sid, game_name, source, rank, click_ts, next_fg,
       dateDiff('second', click_ts, next_fg) AS away_s
FROM (
    SELECT event, timestamp AS click_ts,
           properties.sid AS sid,
           properties.game_name AS game_name,
           properties.source AS source,
           properties.rank AS rank,
           -- first foreground after this row, per person
           minIf(timestamp, event = 'Application Opened') OVER (
               PARTITION BY person_id ORDER BY timestamp
               ROWS BETWEEN 1 FOLLOWING AND UNBOUNDED FOLLOWING
           ) AS next_fg
    FROM events
    WHERE timestamp >= now() - INTERVAL 7 DAY
      AND event IN ('recommendation_link_clicked', 'Application Opened')
)
WHERE event = 'recommendation_link_clicked'
  AND next_fg > click_ts                          -- minIf returns epoch 0 when nothing follows
  AND next_fg <= click_ts + INTERVAL 30 MINUTE    -- longer gaps are a new session, not a return
ORDER BY click_ts DESC
```

Notes:

- One scan of `events`; no join. `minIf` over the window finds the next foreground per person.
- Clicks with no foreground inside 30 minutes drop out. Count them separately if you want a
  "never came back" rate: same query without the two `next_fg` filters, then `countIf(next_fg = 0 OR
  next_fg > click_ts + INTERVAL 30 MINUTE)`.
- `hero` source with rank `-1` is the All-tab hero card, not Discover.

## Joining CJ sales to placements

The sub-ID on GOG affiliate links is `gn_{source}_r{rank}_{clickId}`. `clickId` is present only when
usage analytics is on. In the CJ commission report, split the SID on `_` to get source and rank, and
match `clickId` to `properties.click_id` on `recommendation_link_clicked` or `featured_action_clicked`
for the rest of the click's properties.

## Campaign to install or launch

`game_install_started` and `game_launched` carry `campaign_id`, `campaign_source` and
`campaign_click_age_s` when the Steam app was reached through a campaign CTA in the last 14 days. No
derivation needed; filter on `campaign_id` being set.
