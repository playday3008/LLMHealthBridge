# LLM Health Bridge

An Android app that lets an LLM chat app do the estimating and puts the result into Health Connect. You send meal photos, or just a description, to Claude, ChatGPT, Gemini or another LLM app, talk it through there, copy the JSON reply back, check it, and save it as Health Connect records.

The app has no network access and no API keys. It only talks to the LLM apps you already have installed, through Android's share sheet and the clipboard.

## How it works

1. **Send.** Take a photo, pick photos, or tap Describe to send only the instructions. The app shares the photos together with an instructions file to the LLM app you pick (or any other app via "+ other"). Photos are re-encoded first, which drops their EXIF data.
2. **Talk it through.** Describe the meal, correct portions, add a drink or a blood pressure reading. The instructions ask the LLM to answer with one JSON code block listing every entry.
3. **Bring the reply back.** Tap Copy on the JSON block and return to the app. It offers the reply from the clipboard in a banner; Paste and sharing text or a text file to the app work too.
4. **Review and save.** Edit times, values and items, untick what you do not want, and save. Entries are written as Health Connect records owned by this app.

Saved entries from the last 7 days show under Recent, where they can be reopened or deleted.

## Corrections and resends

Every send carries a request id, and the LLM gives each entry an id of its own. Records are stored under those ids, so when you correct something in the same conversation and paste the new reply, the corrected entries replace the saved ones instead of being added a second time. Entries saved earlier for the same request that the new reply leaves out are listed so you can delete them.

## What it logs

| Kind      | Health Connect records                                                                                                                       |
|-----------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Nutrition | Nutrition, per food item, with energy, macros, fat breakdown, fiber, sugar, caffeine and 28 vitamins and minerals                            |
| Hydration | Hydration                                                                                                                                    |
| Body      | Weight, height, body fat, lean body mass, bone mass, body water mass, basal metabolic rate                                                   |
| Vitals    | Blood pressure, blood glucose, body temperature, basal body temperature, heart rate, resting heart rate, oxygen saturation, respiratory rate |

The app asks only for write permissions, for the types you actually save. It reads back only its own records.

## Supported LLM apps

Claude, ChatGPT, Gemini, DeepSeek, Le Chat and Perplexity get their own buttons when installed. Any other app that accepts shared files works through "+ other".

## Requirements

- Android 9 (API 28) or newer
- Health Connect: built into Android 14 and newer; on older versions, the Health Connect app from Google Play

## Building

The build uses JDK 25 through Gradle toolchains; Gradle downloads it if none is installed.

```sh
./gradlew assembleDebug      # debug APK
./gradlew check              # unit tests, ktlint, detekt and Android lint
./gradlew assembleRelease    # release APK (unsigned)
```

To sign the release APK, set `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` before building. CI does this from repository secrets; signed APKs of each release are on the Releases page.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
