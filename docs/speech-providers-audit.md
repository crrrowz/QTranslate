# تدقيق مزودات تحويل الصوت لنص (السحابية والسيرفرات)

آخر تحديث: 2026-10-27 · الفرع وقت التدقيق: `fix/transcript-stability` · النوع: قراءة كود فقط، ما انبنى ولا انشغل شي، وما انبعث أي طلب حقيقي لأي مزود

هذا يكمّل [local-models-audit.md](local-models-audit.md) (اللي يغطي Vosk المحلي). هنا: Groq و OpenAI و Deepgram و Local Whisper و Custom و Audio chat model، والمحركات المباشرة Deepgram live و OpenAI Realtime. البرومبتات بـ [local-models-fix-prompts.md](local-models-fix-prompts.md) (المهام 8–10).

> **تنبيه:** معلومات المزودات الخارجية (أسماء الموديلات، شكل الـ API) تتغير. كل شي هنا عن الـ API الخارجي لازم ينفحص ويا وثائق المزود الحالية قبل التنفيذ، والبرومبتات تطلب هذا صراحة.

## 1. شلون تشتغل هسه

إضافة **Speech Recognition** بيها مجموعتين، وكل وحدة تنضبط بروحها:

| المجموعة | الخيارات | شلون ينبعث الصوت | الملف |
|---|---|---|---|
| **Accurate text** (كل سطر بعد ما يخلص) | Groq، OpenAI، Local Whisper server، Custom | `POST {url}/audio/transcriptions` (multipart) | `SpeechClient.openAi` |
| | Deepgram | `POST {url}/listen` (الصوت هو الـ body) | `SpeechClient.deepgram` |
| | Audio chat model | `POST {url}/chat/completions` ويا `input_audio`، وتعليمة "انسخ الكلام" | `SpeechClient.chatAudio` |
| **Live text** (أثناء الكلام) | Deepgram live | WebSocket `wss://api.deepgram.com/v1/listen` | `DeepgramLiveService.kt` |
| | OpenAI Realtime | WebSocket `wss://api.openai.com/v1/realtime?model=...` | `OpenAiRealtimeService.kt` |

- كل مزود عنده عنوان وموديل افتراضي (`SpeechProvider.kt`). إذا الحقل فارغ، ينستخدم الافتراضي.
- حقل **Model** نص حر. البرنامج ما يعرف شنو نوع الموديل اللي كتبه المستخدم.
- زر **Test connection** يفحص: العنوان صالح، والمفتاح موجود، وإذا السيرفر عنده `/models` يتأكد إن الاسم موجود بالقائمة. **ما يبعث صوت.**

### جواب سؤالك: "إذا حط الـ API key والموديل، كلشي يشتغل؟"

| الحالة | يشتغل؟ |
|---|---|
| Groq أو OpenAI أو Deepgram، **والموديل فارغ** (الافتراضي) | نعم حسب الكود والاختبارات. ما فحصته ويا طلب حقيقي. |
| OpenAI ويا `gpt-4o-transcribe` أو `gpt-4o-mini-transcribe` | يشتغل، بس **كل سطر ينبعث مرتين** (أول مرة ينرفض، تفاصيل بـ P3). |
| المستخدم كتب **موديل دردشة نصي** (`gpt-4o`، `llama-3.3-70b`) بمزود تحويل | **لا.** زر Test **ينجح** لأن الاسم موجود بـ `/models`، بعدين كل سطر يفشل. |
| Audio chat model ويا موديل ما يسمع صوت | **لا.** Test ينجح، والفشل يطلع بأول سطر. |
| Custom أو Local Whisper والموديل فارغ | Custom ياخذ `whisper-1` افتراضياً، وأغلب السيرفرات المحلية ما عندها هالاسم. |
| **OpenAI Realtime** (نص مباشر) | **على الأغلب ما يشتغل** (P1 تحت)، وإذا اشتغل فهو مكلف. |
| Deepgram live | يشتغل حسب الكود. "تلقائي" يستخدم `language=multi`، وهذا يدعم لغات محدودة (لازم ينفحص هل العربي منها). |

### هل لازم نحدد نوع الموديل؟

**نعم، بس مو نقفله.** أكو 4 أنواع، وكل حقل يقبل نوع واحد بس:

| النوع | وين ينفع | أمثلة (لازم تنفحص ويا وثائق المزود) |
|---|---|---|
| **موديل تحويل** (transcription / STT) | Accurate: Groq، OpenAI، Local، Custom | `whisper-large-v3-turbo`، `whisper-large-v3`، `whisper-1`، `gpt-4o-transcribe` |
| **موديل Deepgram** | Accurate: Deepgram، و Live: Deepgram live | `nova-3`، `nova-2` |
| **موديل صوتي مباشر** (realtime / streaming) | Live: OpenAI Realtime | موديل جلسة realtime، أو جلسة تحويل بموديل مثل `gpt-4o-transcribe` |
| **موديل دردشة يسمع صوت** (audio chat) | Accurate: Audio chat model | `gemini-*`، `gpt-4o-audio-preview` |
| ~~موديل دردشة نصي بس~~ | **ما ينفع بأي مكان** | `gpt-4o-mini`، `llama-*` |

**الاقتراح:** قائمة منسدلة **قابلة للكتابة** لكل مزود، بيها الموديلات المعروفة من النوع الصحيح، ويا إمكانية كتابة اسم ثاني (لأن المزودات تضيف موديلات كل فترة). والحماية الحقيقية: **Test يبعث مقطع صامت قصير** (ثانية وحدة) للعنوان الفعلي. هيچ يتأكد إن الموديل يقبل صوت. الدالة `SpeechClient.silentWav` موجودة أصلاً بس تنستخدم بالاختبارات فقط.

## 2. المشاكل

### P0 / P1: يمنع الاستخدام أو يكلف فلوس

| # | الأولوية | المشكلة | الدليل |
|---|---|---|---|
| P1 | P0 | **OpenAI Realtime على الأغلب ما يبعث إعدادات الجلسة.** `onOpen` يستدعي `enqueue`، و`enqueue` يرجع بدون ما يسوي شي إذا `socket` لسه `null`. و`socket` ينضبط بعد ما يكمل `buildAsync(...).await()`، بينما JDK غالباً يستدعي `onOpen` قبلها. النتيجة: `session.update` ما يوصل، فماكو `input_audio_transcription`، يعني ماكو نص، والجلسة تبقى بالإعدادات الافتراضية (ممكن تولّد ردود صوتية مكلفة). **ما تأكدت بالتشغيل**، بس ماكو أي اختبار يغطي هالمسار (الاختبارات تفحص قراءة الرسائل بس). | `OpenAiRealtimeService.kt:111-116`، `WebSocketLiveSession.kt:43-44`، `OpenAiRealtimeService.kt:149-154` |
| P2 | P0 | **OpenAI Realtime مصمم غلط للتحويل:** يفتح جلسة محادثة بموديل `gpt-4o-realtime-preview` (سعر صوت realtime) حتى بس يحوّل لنص، وموديل التحويل `whisper-1` مكتوب ثابت. حقل "Live Model" يغيّر موديل الجلسة، مو موديل التحويل، فإذا كتب المستخدم `gpt-4o-transcribe` الاتصال يفشل. ولغة الجلسة ما تنبعث أبداً (`openSession` يهمل `language`). و`whisper-1` ما يطلّع نص جزئي، فالنص "المباشر" يجي بس بعد كل جملة. وهيدر `OpenAI-Beta: realtime=v1` والموديل preview ممكن انتهى دعمهم (لازم ينفحص). *(تم الإصلاح بـ Task 8 و P2 follow-up: جلسة transcription-only GA بموديل gpt-live-transcribe ودعم اللغة وتحديث الوصف وتضمين VAD بالعميل لإنهاء الجملة commit بعد صمت 600ms أو 15s كحد أقصى مع اختبارات كاملة)* | `OpenAiRealtimeService.kt:48-56`، `113`، `147-152` |
| P3 | P1 | **طلبين لكل سطر ويا `gpt-4o-transcribe`:** كل طلب يبدي بـ `verbose_json` + توقيت الكلمات، وهالموديلات ترفضه، فيعيد بـ `json`. هذا يتكرر **كل سطر**، والسبب ما ينحفظ. وأي خطأ غير المصادقة والشبكة ينعاد، حتى "الموديل غير موجود" و"الملف كبير" و 5xx. | `SpeechClient.kt:91-107` |
| P4 | P1 | **Test ما يفحص نوع الموديل** (قسم 1). قائمة `/models` عند OpenAI و Groq فيها موديلات دردشة، فأي اسم موجود ينجح. | `SpeechClient.kt:64-85` |
| P5 | P1 | **مفتاح Live ياخذ مفتاح المزود الدقيق حتى لو شركة ثانية.** إذا الدقيق Groq والمباشر Deepgram وحقل Live API Key فارغ، ينبعث مفتاح Groq لـ Deepgram ويطلع خطأ مصادقة مبهم. | `SpeechSettings.kt:225` |
| P6 | P1 | **زر Test مخفي بالوضع الهجين** (نفس B3 بالتدقيق الأول). يعني مستخدم Hybrid ويا Groq ما يگدر يفحص مفتاحه. وفحص Deepgram live و OpenAI Realtime (`validate`) موجود بس ما يوصله أحد من الواجهة. | `SpeechPlugin.kt:167`، `MainAppFrame.kt:318` |

### P2: صحة وتجربة

| # | المشكلة | الدليل |
|---|---|---|
| P7 | Deepgram live: "تلقائي" = `language=multi`. هذا يشتغل ويا موديلات معينة بس، ولغات محدودة. ماكو تحذير إذا الموديل أو اللغة (مثلاً العربي) ما مدعومة. | `DeepgramLiveService.kt:80-89` |
| P8 | Deepgram live: العنوان `wss://api.deepgram.com` مكتوب ثابت، ويهمل Server URL (منطقة EU أو سيرفر خاص). وخطأ الاتصال يطلع عام ("Could not connect")، وتضيع حالة 401 (مفتاح غلط) أو 400 (موديل غلط). | `DeepgramLiveService.kt:174-187` |
| P9 | Custom: الموديل الافتراضي `whisper-1`، وهذا ما موجود بأغلب السيرفرات المحلية. الأصح يكون إلزامي، أو ينقرأ من `/models`. | `SpeechProvider.kt:68-73` |
| P10 | `SpeechProvider.fromLabel` يرجع **Groq** بصمت إذا ما عرف الاسم. يعني لو اسم مزود تغيّر بتحديث، إعدادات المستخدم تتحول لمزود سحابي، والصوت يطلع لبرا بدون علمه. | `SpeechProvider.kt:86` |
| P11 | Deepgram (الدقيق): ثقة الكلمات (`confidence`) ما تنقرأ (`DeepgramWord` ما بيه الحقل)، بس بالمباشر تنقرأ. ملف الحالة يگول إنها مدعومة. | `SpeechDTOs.kt:62-68` |
| P12 | `isLocal` يعتبر `192.168.*` و `10.*` شبكة محلية، بس ما يعتبر `172.16–31.*`. | `SpeechSettings.kt:283-285` |
| P13 | Audio chat model: النص يتنظف بس من علامات التنصيص. إذا الموديل رد بـ "Transcript: ..." أو بشرح، ينكتب كما هو. | `SpeechClient.kt:213` |

### تقليل الكود (كود ميت أو مكرر)

| # | الشي | الحالة |
|---|---|---|
| R1 | `SpeechProvider.supportsBatch / supportsRealtime / supportsWordTimings / supportsLanguageDetection` | **ما ينقرأ بأي مكان.** يا ينحذف، يا ينستخدم فعلاً (مثلاً `supportsWordTimings` يقرر يطلب `verbose_json` أو لا، وهذا يحل P3). |
| R2 | `enum LiveEngine` كله، ويا `LiveEngine.DROPDOWN_OPTIONS` | **ما ينستخدم.** نسخة ثانية من `LIVE_ENGINE_OPTIONS`. |
| R3 | `SpeechSettings.liveEnabled` | ينستخدم **بالاختبارات بس**، والتشغيل الفعلي يقرر بـ `VoskLiveService.openSession`. فالمنطق مكتوب مرتين وممكن يختلفون. |
| R4 | `LIVE_VOSK` (اسم ثاني لـ `LIVE_VOSK_BUILTIN`) | ينستخدم بالاختبارات بس. |
| R5 | `DeepgramLiveSession` و `OpenAiRealtimeSession` | نفس الـ listener (تجميع النص، و onClose، و onError)، ونفس `connect`، ونفس `ping`. ينقلون لـ `WebSocketLiveSession`، ويختفي خطأ P1 من مكان واحد. |
| R6 | `VoskLiveService` | الاسم مضلل: هو موجّه لكل المحركات المباشرة (Vosk و Deepgram و OpenAI). يتغير اسم الكلاس بس، ويبقى `key = "vosk-live"` حتى ما تنكسر الإعدادات المحفوظة. |
| R7 | `SpeechProvider.DROPDOWN_OPTIONS` | نسخة يدوية من أسماء المزودات (لازم تبقى `const`). الاختبار `every dropdown option maps to its provider` يغطي اتجاه واحد بس. يحتاج يتأكد إنهم متطابقين بالاتجاهين وبنفس الترتيب. |

## 3. المهام

| المهمة | تغطي | الأولوية |
|---|---|---|
| **8. إصلاح OpenAI Realtime** | P1، P2 | P0 |
| **9. أنواع الموديلات والفحص الحقيقي** | P3، P4، P5، P6، P9، P13، P7، P8 | P0/P1 |
| **10. تنظيف الكود وأمان الإعدادات** | R1–R7، P10، P11، P12 | P2 |

- **المهمة 8** معزولة تقريباً بـ `OpenAiRealtimeService.kt` و `WebSocketLiveSession.kt`، فتگدر تبديها قبل مهام Vosk.
- **المهمة 9** تلمس `SpeechSettings` و `SpeechClient`، ومرتبطة بـ B3 بالمهمة 2. إذا المهمة 2 خلصت، تكمّل عليها. وإذا لا، تسوي B3 هي.
- **المهمة 10** بالأخير، لأن تنظيف الكود بعد تثبيت السلوك أأمن.

## 4. قرارات تحتاجك

1. **حقل الموديل:** قائمة قابلة للكتابة (اقتراحات + أي اسم)، لو قائمة مقفولة؟ أنصح بالقابلة للكتابة، والبرومبت مكتوب عليها.
2. **OpenAI Realtime:** نصلّحه على جلسة تحويل (أرخص وأصح)، لو نشيله من الخيارات لحد ما يتأكد بالتشغيل؟ البرومبت يصلّحه، وإذا ما گدر يتأكد يخفيه ويا ملاحظة.
3. **Test يبعث مقطع صامت:** ممكن يكلف أجزاء سنت عند المزود. مقبول؟ البرومبت يفترض نعم، بس بضغطة الزر وبس.
