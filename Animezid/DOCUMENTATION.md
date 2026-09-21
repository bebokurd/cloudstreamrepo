# Ondemand (DAMITV) Public API — Reference

Authoritative provider exposed by the Nuvio server. Base: `https://ondemand.st/`.
All shapes below were verified against the live endpoints on 2025-09-21 (not doc-only).

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /papi/api/ping` | Server status / health |
| `GET /papi/api/streams` | Categories with their live streams and metadata |

Poll `/streams` every 1–2 minutes. Implement a 60s cache. No rate limits enforced;
abuse results in IP ban, so be polite with caching.

## `GET /papi/api/ping`

Response (live-verified):

```json
{
  "success": true,
  "domains": ["dami-tv.pro", "damitvsports.com"]
}
```

Note: live shape uses `domains` (list of CDN/resolver domains), not a `timestamp` field.

## `GET /papi/api/streams`

Response (live-verified):

```json
{
  "success": true,
  "timestamp": 1781600000,
  "READ_ME": "Free public API by DAMITV.",
  "performance": 0.12,
  "streams": [
    {
      "category": "football",
      "id": 1,
      "streams": [
        {
          "id": "wc/2026-06-16/fra-sen",
          "name": "France vs. Senegal",
          "poster": "https://api.ppv.to/assets/thumb/...",
          "starts_at": 1781625600,
          "ends_at": 1781652600,
          "category_name": "football",
          "status": "live",
          "league": "FIFA World Cup 2026",
          "teams": {
            "home": { "name": "France", "badge": "" },
            "away": { "name": "Senegal", "badge": "" }
          },
          "viewers": 1250,
          "sources": [
            { "source": "hls", "id": "s1", "name": "Server 1",          "embed": "https://damitv.st/embed/?id=wc/2026-06-16/fra-sen" },
            { "source": "hls", "id": "s2", "name": "BBC One",           "embed": "https://damitv.st/embed/?id=wc/2026-06-16/fra-sen/uk" },
            { "source": "hls", "id": "s3", "name": "Telemundo",         "embed": "https://damitv.st/embed/?id=wc/2026-06-16/fra-sen/telemundo" },
            { "source": "hls", "id": "s4", "name": "FOX 4K",            "embed": "https://damitv.st/embed/?id=wc/2026-06-16/fra-sen/fox-4k" }
          ],
          "iframe": "https://damitv.st/embed/?id=wc/2026-06-16/fra-sen",
          "embed": "https://damitv.st/embed/?id=wc/2026-06-16/fra-sen"
        }
      ]
    }
  ]
}
```

Field notes:

- `streams[].streams[].sources[]` — each entry has `source` (`hls`/`m3u8`/`iframe`),
  `id`, `name` (display label, may encode quality, e.g. `FOX 4K`), and `embed` URL.
- `embed` / `iframe` — equivalent playable embed URL for the stream.
- `starts_at`, `ends_at`, `timestamp` — Unix timestamps (seconds).
- `poster` — event imagery.
- `size` is not part of the API; `size` should only be forwarded if present in a
  provider-supplied payload (it is not fabricated here).

## Embed usage

```html
<iframe src="https://damitv.st/embed/?id=MATCH_ID" width="100%" height="500" frameborder="0" allowfullscreen></iframe>
```

## Integration (Nuvio server)

The `Nuvio` server (registered in `Animezid` right after `MegaMax`) resolves via the
existing AnimeZid playback-session flow for its sources, and the Ondemand public API
above is the authoritative provider contract for resolvable embeds. Stream objects are
normalized to the project's `ExtractorLink` shape preserving:

- `url` (embed/iframe)
- `quality` (parsed from `name`/source label when present)
- `title`/`name`
- `headers` (`Referer` → `https://ondemand.st/`, `User-Agent`)
- `size` forwarded only when actually present (never invented)
