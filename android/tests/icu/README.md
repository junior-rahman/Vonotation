# Optional ICU4J Banglish reference check

`IcuBanglishCheck.java` mirrors `RomanizationReference` using desktop ICU4J (the same ICU
transliteration rules Android exposes as `android.icu`) to sanity-check `TranscriptGuard`
against real ICU references instead of hand-written ones. Informational only.

```sh
curl -fLO https://repo1.maven.org/maven2/com/ibm/icu/icu4j/74.2/icu4j-74.2.jar
S=app/src/main/java/app/voiceflow
javac -encoding UTF-8 -cp icu4j-74.2.jar -d /tmp/icu $S/TranscriptGuard.java $S/LanguagePolicy.java tests/icu/IcuBanglishCheck.java
java -cp icu4j-74.2.jar:/tmp/icu app.voiceflow.IcuBanglishCheck
```

Android's ICU version may differ from ICU4J 74.2; this does not prove on-device output.
