# Anime source read-only capability review (2026-11-17)

Scope: seven native adapters configured by the app, not the unknown current
on-device enabled-source selection. No media files were requested, no scripts
executed, no keys copied, and no local builds/tests or device playback performed.
HTTP success is not a declaration of playable video or mainland-China speed.

User case: 脑洞学生会 / 学生会也有洞！, episode two.

| Adapter | Observed evidence | Boundary |
| --- | --- | --- |
| AGE | Search/detail HTTP200, ID20260293; xigua wrapper HTTP200 with encrypted Vurl/Go WASM; ffm3u8 same-episode wrapper gives literal HTTPS m3u8 | Dynamic xigua unsupported; ffm3u8 address extraction only, not playback |
| GiriGiriLove | Search/detail HTTP200, ID27203, cht/chs 01–07; episode02 in both groups directly provides HTTPS playlist.m3u8 | No media fetched; exact-track contract applies, not arbitrary alias linking |
| TvTFun | Search/detail HTTP200, slug554779 and bangumiId554779; one season with 14 episode records, explicit stations cover 7/6 | Playback resolve not executed; catalog bug fixed below |
| AkiAnime | Search/detail/play HTML HTTP200, WWcDDE; selected 3-2 page provides opaque Doki token and from=YDY | Not a direct video URL; dynamic/public parser follow-up not verified in this pass |
| DMBus | Search/detail/play HTML HTTP200, 6290; selected1-2 page declares hhjx.hhplayer.com iframe with opaque URL value | No resolved media claimed |
| AniCh | Binary search HTTP200, ID39419; detail, episodes and exact episode2 vod HTTP200 | Address decoding/playback follow-up not verified in this pass; airdate is epoch milliseconds in this sample |
| LMM22 | Original keyword returns HTTP200 with zero results; alternate same-work title returns one record8308 | Empty original search is not network failure; catalog/playback not verified in this pass |

Automatically prepared external scripts have not been executed in this pass.
Preparation/installation and compatible runtime availability remain separate from
search, catalog and playback success; no claim of an all-source playback audit.

## TvTFun high-impact mapping defect

The actual detail response contains 12 type0 episodes and type2/type3 records both
numbered1. Those last two have music titles, not textual OP/ED prefixes. The old
adapter guessed kinds from labels, creating three MAIN1 records. It now honors
structured type through the existing Bangumi kind mapping. The sample directly
covers 0/2/3 only, not every possible upstream type. The mapping contract is
[Bangumi's official EpisodeType schema](https://github.com/bangumi/api/blob/master/open-api/api.yml).
TvTFun's response carries bangumiId/bangumiSubjectId and Bangumi episode names;
using the shared mapping is consistent with these fields, not a separate claim
that all TvTFun types have been independently sampled.

The automatic group contains all14 actual records while explicit station groups
contain only7 and6. The generic equal-coverage guard previously rejected the
unique typed automatic group before reaching its adapter-specific handling. Now
TvTFun's single typed automatic group can be selected before comparing optional
explicit stations. Multiple automatic groups, multiple/empty editions, duplicate
(kind,number), missing/nonfinite numbers and absent requested episodes still fail
closed. Station resolution still uses the chosen real episode ID; no synthesized
future playback, cross-season selection or array-position mapping was added.

## AniCh detail-year correction

The observed `airdate=1791043200000` is epoch milliseconds, not the previously
expected formatted date. The adapter now accepts bounded 12/13-digit millisecond
values using UTC, plus valid date-only or zoned ISO timestamps. It does not guess
seconds or use the device timezone. Missing, malformed or conflicting years remain
unconfirmed; exact source URL, title and routing checks remain unchanged. This is
trusted identity enrichment, not evidence of video playback success. The patch was
independently statically reviewed; no local build or automated tests were run.
