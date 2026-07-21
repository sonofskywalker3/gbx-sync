# GBX Sync

Android app for syncing Game Boy / Game Boy Color / Game Boy Advance cartridge
saves with the `.sav` files in your ROM folder, using an insideGadgets
**GBxCart RW v1.4/a/b/c** over USB-C. Designed for Android gaming handhelds
(Retroid, AYN Odin, etc.).

Keep real-cartridge progress and emulator progress in step: plug in the
GBxCart, insert a cart, and the app tells you which side is newer and moves
the save the right way.

## Features (v0.1)

- GBxCart RW v1.4-series support (modern firmware, `L`-series ≥ 12 recommended —
  update with FlashGBX desktop if the app reports old firmware)
- GB/GBC saves: MBC1, MBC2, MBC3/MBC30, MBC5 (covers nearly all battery carts)
- GBA saves: SRAM/FRAM, Flash 64K/128K (SST/Macronix/Panasonic/Sanyo, non-Atmel),
  EEPROM 4K/64K — save type detected automatically like FlashGBX does
- Cart ↔ ROM matching by header (exact hash first, title fallback)
- Three-way sync: knows whether the cart, the file, or both changed since the
  last sync; conflicts always ask
- Safety: the side being overwritten is backed up into app storage
  (`Android/data/com.jeff.gbxsync/files/backups`) and every write is verified
  by reading back
- Exotic carts (MBC6/MBC7/TAMA5/HuC, Atmel flash, DACS) are detected and
  reported as unsupported instead of risking a bad dump

## Building

Requirements: JDK 17, Android SDK (platform 35). Then:

```
gradlew.bat assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
gradlew.bat testDebugUnitTest    # protocol/header/sync-logic unit tests
```

## Installing on the handheld

1. Enable developer options + USB debugging (or just sideload the APK with a
   file manager).
2. `adb install app-debug.apk` or copy the APK over and open it.
3. First launch: pick your ROM folder (the one your emulators use).
4. Plug in the GBxCart RW — Android will offer to open GBX Sync; tick
   "always open" to skip the prompt in the future.

## First hardware test (recommended order)

1. Connect the GBxCart with **no cartridge** — the app should show the PCB
   revision and firmware version.
2. Insert a common GB cart (e.g. Pokémon Red — MBC3, 32 KB SRAM) and press
   Detect: title and save size should match FlashGBX's output.
3. Sync cart → file, load the `.sav` in an emulator, confirm it works.
4. Save in the emulator, sync file → cart, boot the cart on real hardware.
5. Repeat with a GBA SRAM title, a flash title (e.g. Pokémon Emerald), and an
   EEPROM title (e.g. Metroid Fusion).

## Notes

- `reference/FlashGBX/` (git-ignored) is a clone of
  [Lesserkuma's FlashGBX](https://github.com/lesserkuma/FlashGBX), used as the
  protocol reference. This app is an independent Kotlin implementation.
- RTC data (MBC3/HuC3 clocks) is deliberately not appended to `.sav` files so
  they stay emulator-clean.
