# PS Bridge local protocol

The bridge's service (`com.tals.psbridge.ModuleService`, package
`com.tals.psbridge`) is bound by the client app, which starts it and keeps it
alive. The client then creates a connected socket pair
(`ParcelFileDescriptor.createSocketPair()`) and hands one end to the bridge
with Binder transaction `1` (a parcel holding the file descriptor); the reply
holds the protocol version as an int. Apps cannot connect to each other's
sockets directly since Android 9, a descriptor passed through Binder is the way.
The bridge accepts one client, which must be an app signed with the bridge's
own key (checked on the Binder caller).

## Framing

Every message is `[type: u8][length: u32 big-endian][payload]`, payload at most 8 MiB.

| Type | Direction | Payload |
|---|---|---|
| `0x01` REQUEST | client → bridge | JSON `{"id": n, "op": "...", ...}` |
| `0x02` PAD | client → bridge | 7 × i32 BE: buttons, l2, r2, lx, ly, rx, ry |
| `0x03` KEYFRAME | client → bridge | empty: ask the server for a keyframe |
| `0x81` RESPONSE | bridge → client | JSON `{"id": n, "ok": true, ...}` or `{"id": n, "ok": false, "error": "code"}` |
| `0x82` STATUS | bridge → client | JSON `{"state", "kind", "step", "detail", "error"}`, on every change |
| `0x83` VIDEO | bridge → client | one sample: Annex-B codec header, or one access unit |
| `0x84` AUDIO_FORMAT | bridge → client | 2 × i32 BE: channels, sample rate |
| `0x85` AUDIO | bridge → client | interleaved 16-bit little-endian PCM |

Buttons are chiaki's `ChiakiControllerButton` bits; triggers 0..255; sticks
signed 16-bit with up negative.

## Requests

| op | fields | reply |
|---|---|---|
| `hello` | — | `version` (1), `native`, `signed_in`, `status` |
| `sign_in` | `code`: the npsso, bare or as the `{"npsso":"…"}` line | — |
| `sign_out` | — | — |
| `consoles` | — | `consoles`: `[{name, uid, ps5}]` (PS5 with Remote Play on) |
| `cloud_library` | — | `games`: `[{name, id, entitlement, image}]` (the account's PS5 library) |
| `start` | `kind`: `console` or `cloud`; `width`, `height`, `fps`, `bitrate_kbps`, `codec` (`h265` or `h264`, the default); console: `console_uid`, `console_ps5`; cloud: `game_id`, `game_entitlement`, `game_name` | — (progress comes as STATUS) |
| `stop` | `reason` | — |
| `rest` | — | puts the console in rest mode |

## Status

`state`: `idle`, `connecting`, `streaming`, `error`. `kind`: `console`, `cloud`.
`step` while connecting: `psn`, `console`, `stream`, `cloud`; `detail`: the
cloud allocation's progress line. `error` codes: `not_configured`,
`native_missing`, `signed_out`, `network`, `psn`, `console_unreachable`,
`session`, `in_use`, `console_off`, `link`, `unknown`, `no_game`, `no_premium`,
`game_unavailable`, `datacenter`, `cloud`.

Video is the codec the client asked for (`codec`), H.264 for the PS Now classics. When the client
disconnects, the running session is stopped.
