# تقرير ميزة Conversation Sessions — وين وصلنا وشنو باقي

آخر تحديث: 2026-10-22 · الفرع: `feature/live-conversation`

## 1. الخلاصة

QTranslate صار عنده صفحة **Conversation Sessions** تسجّل مكالمة ديسكورد بمسارين منفصلين (المايك وصوت المكالمة)، وتحوّل الكلام لنص، وتترجم أي كلمة بمجرد تمرير الماوس عليها. التحويل لنص صار عن طريق إضافة مستقلة اسمها **Speech Recognition**، تشتغل ويا أي مزود (سحابي أو محلي).

تم اكتمال المهام الأساسية بالكامل:
1. **محرك Vosk مدمج داخل الإضافة:** يعمل أوفلاين تماماً بدون بايثون أو دوكر، مع مدير موديلات ينزل الموديل ويفك ضغطه داخل مجلد بيانات الإضافة.
2. **محركات بث سحابية مباشرة:** Deepgram live و OpenAI Realtime كـ `LiveSpeechRecognizer`.
3. **تبني اللغة المكتشفة تلقائياً:** في وضع Auto-Detect، المزود الدقيق يحدث لغة النص المباشر للمقاطع التالية تلقائياً مع بقاء الخيارات اليدوية الصريحة ثابتة.
4. **تعريب وترجمة كاملة:** لجميع اللغات الـ 18 المدعومة في البرنامج.
5. **إصلاح خطأ OCR في Windows:** معالجة حالة الصور التي لا تحتوي على نصوص.

## 2. شنو انسوّى

### التسجيل والجلسات
- تسجيل المايك (Java Sound) وصوت النظام عن طريق **WASAPI loopback** (سكربت PowerShell، بدون مكتبات إضافية)، كل واحد بملف WAV مستقل ينكتب أول بأول.
- تقطيع الكلام عند السكوت بكاشف صوت يتأقلم ويا مستوى الضوضاء.
- إيقاف مؤقت واستئناف وإيقاف وحفظ. الجهاز اللي ينفصل يوگف بس مساره، والجلسة اللي انقطعت بسبب crash تنسترجع ويا صوتها.
- قائمة جلسات: بحث بالنصوص، وإعادة تسمية، وتصدير، وحذف.
- خيار "Keep recordings" للخصوصية، وتنبيه خصوصية واضح.

### التحويل لنص
- إضافة **Speech Recognition** مستقلة (منفصلة عن AI Services) بمجموعتين:
  - **Live text:** محركات مباشرة (Off، أو Vosk مدمج، أو Vosk سيرفر محلي، أو Deepgram live، أو OpenAI Realtime).
  - **Accurate text:** مزود دقيق، أو None (نص مباشر بس)، أو Groq، أو OpenAI، أو Deepgram، أو Whisper محلي، أو Custom متوافق ويا OpenAI، أو موديل دردشة صوتي.
- زر Test connection يفحص الاتصال والموديلات بالإعدادات.
- لغة منفصلة للمايك ولغة للمكالمة.
- المقاطع اللي تفشل تنحفظ ويا صوتها ويا زر **Retry failed**.

### النص المباشر والوضع الهجين
- محرك **Vosk Built-in** مدمج في الإضافة عبر مكتبة `com.alphacephei:vosk` الرسمية، مع إدارة وتنزيل الموديلات محلياً.
- محركات سحابية مباشرة: **Deepgram live** و **OpenAI Realtime**.
- النص الجزئي يطلع بشريط مباشر، والسطر المنتهي يطلع بخط مائل لحد ما يوصل النص الدقيق ويتبدل.
- تبني اللغة المكتشفة من المزود الدقيق تلقائياً للمسار عند ضبط اللغة على Auto-Detect.

### الترجمة والتشغيل
- تمرير الماوس على كلمة يطلّع ترجمتها وترجمة الجملة كاملة.
- تظليل عبارة يترجمها، وزر **Meanings** يجيب المعاني من القاموس.
- تشغيل السطر أو الكلمة الوحدة (مع توقيت الكلمات من Groq و whisper-1 و Vosk و Deepgram).

### التوثيق واللغات
- ترجمة جميع نصوص Conversation Sessions عبر اللغات الـ 18 في `languages/`.
- توثيق كامل في [wiki/Conversation-Sessions.md](../wiki/Conversation-Sessions.md) ودليل بناء وتشغيل ويندوز في [docs/windows-build-and-run-guide.md](windows-build-and-run-guide.md).

## 3. شنو تأكدنا منه فعلاً، وشنو لا

| تأكدنا منه | ما تأكدنا منه بعد |
|---|---|
| نجاح جميع اختبارات المشروع الكاملة بدون استثناء (100% Pass) | التجربة الحية للأجهزة الصوتية الشخصية للمستخدم على ديسكورد حقيقي |
| تحميل مكتبة Vosk C++ الأصلية بنجاح على Windows x64 | |
| حل مشكلة تشوه الرسم (Direct3D) على ويندوز برمجياً بشكل افتراضي في AppUiSetup | |
| تفريغ المقاطع الصوتية محلياً بالكامل عبر Vosk في وضع Local only | |
| فك وضغط وإدارة موديلات Vosk وتخزينها في مجلد الإضافة | |
| بروتوكول ورسائل Deepgram live و OpenAI Realtime | |
| توافق جميع ملفات اللغات الـ 18 وفحص صيغ `%s`/`%d` بنجاح | |
| إصلاح خطأ `windows-ocr.ps1` عند عدم وجود نصوص | |
| نجاح بناء الحزمة المحمولة المستقلة عبر `assemblePortable` | |

## 4. حالة المهام

1. [x] **Vosk مدمج بالإضافة (بدون Docker وبدون Python):** منجز ومختبر ومدمج للبث الحي وتفريغ المقاطع محلياً.
2. [x] **فصل المزودين حسب النوع، وإضافة محركات بث سحابية مباشرة (Deepgram, OpenAI):** منجز ومختبر.
3. [x] **كشف وتبني اللغة تلقائياً للنص المباشر:** منجز ومختبر.
4. [x] **ترجمة نصوص الواجهة لجميع اللغات الـ 18:** منجز وتم التحقق باختبارات التوطين.
5. [x] **إصلاح خطأ System Services OCR على Windows:** منجز ومختبر.
6. [x] **حل تشوه واجهة المستخدم (Direct3D):** تم تضمين التعطيل الافتراضي لـ Direct3D في `AppUiSetup` على Windows.
7. [x] **دليل بناء وتشغيل نسخة ويندوز المستقلة (.exe):** تم التوليد والتوثيق واختبار `assemblePortable`.

## 5. Transcript stability and verification (`fix/transcript-stability`)

تم تنفيذ معالجة شاملة من 10 خطوات (كل خطوة بكومنت مستقل ومختبر) لحل مشاكل تحرك البطاقات تحت الماوس، وتداخل الكلام المتزامن من المتحدثين، والتحقق الصادق من دقة النص الصوتي:

1. **`315b625` — `feat(conversation): freeze transcript rendering across reading zone with 900ms grace period`**
   - تجميد تحديثات منطقة البطاقات بالكامل بمجرد وجود الماوس داخل نافذة النص (`RenderGate`)، مع مهلة سماح `900 ms` بعد خروج الماوس، واستمرار عمل شريط التحديثات المعلقة القابل للنقر.
2. **`1941f3e` — `feat(conversation): anchor card under pointer to exact on-screen Y across transcript renders`**
   - تثبيت البطاقة التي يقع عليها المؤشر على نفس الإحداثي الرأسي `Y` بالبكسل على الشاشة عند إضافة بطاقات جديدة في وضعي الأحدث بالأعلى والأقدم بالأعلى.
3. **`a9229e2` — `feat(conversation): add honest VerificationState (LIVE, VERIFIED, CORRECTED, UNVERIFIED)`**
   - إضافة حالات التحقق الصادقة (`LIVE`, `VERIFIED`, `CORRECTED`, `UNVERIFIED`) وإلغاء الاعتماد الوهمي على طاقة الصوت `RMS` عند غياب المزود الدقيق أو فشله.
4. **`b8430f8` — `feat(conversation): cut rolling verification at word pause boundaries with 500ms overlap filtering`**
   - قطع مقاطع التحقق الدوري عند فجوات التوقف بين الكلمات (`>= 150 ms`) الأقرب لنافذة `4.5–6 s` (`VerificationSlicer`) مع إرسال `500 ms` سياق متداخل وتصفية الكلمات المتداخلة زمنياً.
5. **`ccfaf0b` — `feat(conversation): add word-level edit distance aligner with Arabic normalization and agreement score`**
   - محاذاة الكلمات بالمسافة التحريرية (`WordAlignment`) مع تطبيع النص العربي (حذف التطويل والتشكيل وتوحيد الألف والياء والتاء المربوطة) وحساب نسبة التطابق `agreement`.
6. **`8eaae57` — `feat(conversation): show verification state, 3s correction highlight fade, and accuracy badge`**
   - عرض شارة التحقق ونسبة الدقة (`✓ 92%`) مع تلميح مترجم، وتمييز الكلمات المصححة بإضاءة ناعمة تتلاشى بعد `3 s`، وخط منقط للنصوص غير المدققة.
7. **`b500543` — `feat(speech): add word confidence to RecognizedWord/TranscriptWord and underline low-confidence live words`**
   - إضافة `confidence: Double? = null` إلى `RecognizedWord` (ورفع إصدار `ApiVersion` إلى `2.3.0`) وإلى `TranscriptWord`، واستخراجها من Vosk و Deepgram، ووضع خط خفيف تحت الكلمات المباشرة ذات الثقة الأقل من `0.6`.
8. **`be9085c` — `feat(conversation): add fixed 2-speaker LiveSlotsPanel and RTL-aware chat layout`**
   - فصل العرض المباشر في منطقة ثابتة بخانتين دائمتين (`LiveSlotsPanel`: خانة "أنا" وخانة "المكالمة") بارتفاع مستقر، وإضافة خيار تخطيط المحادثة (`chatLayout`) المتوافق مع `RTL`.
9. **`95f921e` — `feat(conversation): order archive by settle time and restrict coalescing to last archive card`**
   - ترتيب بطاقات الأرشيف حسب وقت الاستقرار (`settledAtMillis`) بحيث لا تتحرك البطاقات المنشأة أبداً، وحصر دمج المقاطع المتتالية (`coalescing`) في البطاقة الأخيرة فقط.
10. **`25c087c` — `feat(conversation): add 250ms partial render throttling, incremental card updates, and 150ms fade-in`**
    - تهدئة معدل الرسم (`<= 1` تحديث جزئي كل `250 ms` مع تمرير فوري للمقاطع المستقرة)، والتحديث الجزئي لنطاق البطاقة المتغيرة فقط دون مسح المستند بالكامل، مع ظهور تدريجي ناعم (`150 ms`) للبطاقات الجديدة.
    - إجمالي نتائج الاختبارات الفعلي من ملفات XML (`*/build/test-results/test/*.xml`): **1,580 اختباراً (1,571 ناجح، 9 متخطى، 0 فشل، 0 أخطاء)**.
11. **`1a81fd7`, `34e80f8`, `f650e81`, `2c4c1d2`, `5ba7bca`, `d8cc064` — `fix(conversation): overlay pause pill, fixed-height live panel, synchronous on-screen anchor compensation, and EDT layout tests`**
    - جعل زر الإيقاف المؤقت طافياً كطبقة فوقية (`TranscriptOverlayPane`) بدون إزاحة تخطيط البطاقات نهائياً عند الظهور أو الإخفاء، وتثبيت ارتفاع خانات `LiveSlotsPanel` على 4 أسطر ثابتة أثناء التسجيل بدون أي تغير بارتفاعها مع تدفق النصوص، وحفظ/استعادة موضع البطاقة (`captureScrollAnchor` / `restoreScrollAnchor`) نسبةً إلى الحاوية الثابتة (`transcriptInnerPanel`) مع التحقق المتزامن (`validate`) على خيط `EDT` دون وميض إطاري (`invokeLater`) عند بدء/إيقاف التسجيل أو تبديل ترتيب البطاقات (`NORTH` ↔ `SOUTH`)، وتشغيل جميع اختبارات `Swing` على `EDT` (`SwingUtilities.invokeAndWait`) مع التحقق من نجاحها 20 مرة متتالية (`--rerun-tasks`).
12. **`1afd8689`..`d8736572` (`refactor/transcript-card-list`) — `feat(conversation): replace single-JTextPane transcript with virtualised card list (TranscriptListView)`**
    - استبدال المستند النصي الواحد (`JTextPane`) بقائمة بطاقات افتراضية (`TranscriptListView : JComponent, Scrollable, TranscriptSurface`) مدعومة بفهرس ارتفاعات ومجاميع تراكمية نقي بدون Swing (`CardHeightIndex`) وختم رسم منفرد قابل لإعادة الاستخدام (`TranscriptCardStamp` / `CellRendererPane`).
    - حساب إحداثيات البطاقات وتثبيت التمرير (`computeAnchoredScrollY`) حسابياً بدقة البكسل (`±1 px`) عبر المجاميع التراكمية دون تخمين التخطيط، ورسم البطاقات المرئية فقط (`visibleRange` + هامش `2` بطاقات) مع تحديث البطاقة المتغيرة في أقل من `1 ms` حتى مع وجود `2,000` بطاقة.
    - إجمالي نتائج الاختبارات الفعلي من ملفات XML (`192` ملفاً عبر `*/build/test-results/test/*.xml` و `plugins/*/build/test-results/test/*.xml`): **1,589 اختباراً (1,580 ناجح، 9 متخطى، 0 فشل، 0 أخطاء)**، ونجاح اختبارات `Swing` 10 مرات متتالية (`--rerun-tasks`) وبناء الحزمة المحمولة (`assemblePortable`).
