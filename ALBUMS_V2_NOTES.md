# Albums v2 / app v6

Albums have been rebuilt around a Room photo catalog.

- Albums reference PicRoulette photo UUIDs through a many-to-many cross-reference table.
- The current Android URI is a mutable property of a catalog photo, not the album identity.
- SAF document identity is based on provider + document ID; a conservative unique name+size fallback is used when necessary.
- Removing a photo from an album or deleting an album never calls ContentResolver.delete and never deletes a physical photo.
- The legacy SharedPreferences album JSON is intentionally ignored; this is a clean Albums v2 start.
- Five Room instrumentation tests cover URI changes, many-to-many membership, isolated removal, album deletion safety, and duplicate membership.

Run connected Android tests from Android Studio to execute AlbumDatabaseTest.

## v6.0.3 test discovery fix
- The Room database tests remain Android instrumented tests under `app/src/androidTest` (6 tests including the existing instrumented smoke test).
- Added 10 JVM unit tests under `app/src/test` for the stable album identity rules, so ordinary unit-test runs now discover 36 tests instead of the previous 26.
- Extracted stable-key construction into `AlbumIdentityKey.kt` and made production `AlbumStorage` use that exact tested function.

## v6.0.4
- Removed the automatic **Not in an Album** smart collection from the Albums browser.
- Albums now displays only user-created albums.
- This UI change does not delete, move, or modify any physical photos.

## v6.0.7 album tiles
- Removed photo thumbnails from album rows.
- Albums now use a consistent rounded-square monogram tile based on the first character of the album name.
- Uses Material theme primary-container colors; no random per-album colors.
- Empty albums still get a normal monogram tile and keep Roulette disabled until they contain a resolvable photo.
- This is UI-only; Albums v2 membership, Room storage, and URI reconciliation are unchanged.
