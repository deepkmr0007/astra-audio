# User guide

**Home** — Record · Import audio · Transcribe · Text tools · Translate · Speak text · Search library · Settings.
The chip at the top right always shows the network state: *Strict offline*, *Offline*, *Online request
pending*, or *Online: approved for this task*. Tap it for the Privacy dashboard.

## 1. Get a speech model (once)
*Models* lists models with size, memory need, licence, checksum and limitations, and suggests one for your
phone. **Import file** works fully offline; **Download…** shows a consent screen (task, server(s), what is
sent, why, size, "Not verified" provider terms) with *Allow this request once / Use offline alternative / Cancel*.
If Strict Offline Mode is on, ASTRA first asks whether to turn it off.

## 2. Record or import
* *Record* → explain-then-ask microphone permission → **Start recording**. The red dot, timer and level meter
  show it is recording. Pause/Resume, **Stop & save**, or **Discard**. Leaving the app pauses recording.
* *Live dictation* (Android 12+ with an on-device recognizer) types as you speak; save as a note.
* *Library › Import audio* copies any audio file the phone can decode (MP3, M4A/AAC, WAV, OGG/Opus, FLAC, AMR…).
  The original file is never changed.

## 3. Transcribe
Open the item → choose language (or *Detect automatically*), **Fast / Balanced / Highest accuracy**,
**Standard / Verbatim**, optional glossary, speaker-change marks (tinydiarize model), cleaned audio → **Transcribe on
this phone**. Progress shows part *x of y*; you can leave the screen. If the app is killed, Home offers **Resume**.

## 4. Review and correct
* Lines with low engine confidence are highlighted; tap **Play** to hear that part.
* Tap a time to play from there; tap text to correct it; tap a speaker label to rename that speaker everywhere.
* *Raw* shows the untouched engine output; *Corrected* holds your edits. *Clean* view hides fillers and
  stutters without deleting them. *Reset to raw* restores the engine output (recorded in history).
* **Glossary corrections** previews every replacement from your glossary before applying.
* **Make cleaned audio copy** creates a separate noise-reduced version; the original stays.

## 5. Export & share
*Export* → TXT / SRT / VTT / JSON, raw or corrected, clean or verbatim, with preview. **Save to a location I
choose** uses Android's file picker; **Share** warns that the content leaves ASTRA, then opens the share sheet.

## 6. Text tools (offline)
Summary (verbatim sentences), Action items, Proofread (suggestions you accept), Format, Classify (with the
words behind each score), Compare (word diff), Analyse (counts, reading time, script mix), Glossary fixes.
Results are labelled **EXTRACTED** or **SUGGESTED**. Rewrite/simplify/expand are not available in this version.

## 7. Translate
Speech → English on the phone using a multilingual (non-turbo) model, shown side by side with the original
transcript. Always labelled as machine translation.

## 8. Speak text
Reads text aloud with your phone's installed offline voices; adjust voice, speed and pitch.

## 9. Search
Searches your transcripts and notes only: words, “exact phrases”, `prefix*`, date ranges and #tags.

## 10. Benchmark accuracy
*Settings › Accuracy benchmark*: pick recordings, paste what was really said, run. You get WER/CER per
recording and overall, plus processing time — computed on the phone. Save the report as a note.

## 11. Privacy & data
Privacy dashboard: kill switch, microphone status, model and licence, encryption, storage, last network request,
audit log. Settings: app lock, block screenshots, automatic deletion (asks for confirmation and shows how many
items it would delete), delete transcripts / temporary files / models / audit log, **Erase everything**.

---

### हिन्दी सारांश
1. *मॉडल* में जाकर मॉडल फ़ाइल इम्पोर्ट करें (बिना इंटरनेट) या स्वीकृति देकर डाउनलोड करें।
2. *रिकॉर्ड करें* या *ऑडियो इम्पोर्ट करें*।
3. आइटम खोलें → भाषा व सटीकता चुनें → *इसी फ़ोन पर ट्रांसक्राइब करें*।
4. पंक्ति दबाकर सुधारें, समय दबाकर सुनें, वक्ता का नाम बदलें; कम-भरोसे वाली पंक्तियाँ हाइलाइट होती हैं।
5. *निर्यात* से TXT/SRT/VTT/JSON सहेजें या साझा करें।
6. गोपनीयता डैशबोर्ड में *पूर्ण ऑफ़लाइन मोड* से ऐप का सारा इंटरनेट बंद करें।
