# Camera-magic · Blackmagic Camera Mahdi Toolkit

أدوات محلية قابلة للتدقيق لإعادة تسمية APK، وحزمة **عشر فلاتر Pseudo-HDR أصلية**
مع مكتبة OpenGL مرجعية. الهدف المقترح: `Blackmagic Camera Mahdi` بالمعرّف
`com.mahdi.blackmagic.camera`.

> **حالة المشروع:** هذه أدوات ومصادر تمهيدية، وليست نسخة Blackmagic معدلة جاهزة.
> لم تُقدَّم نسخة APK أصلية. لا يوجد `Blackmagic_Camera_Mahdi.apk` نهائي، ولم يُختبر
> التطبيق الأصلي/المعدّل على Android 6 أو 15 أو أي هاتف. الفلاتر **غير مدمجة داخل
> Blackmagic**. وجود سكربت أو إعداد CI لا يعني تنفيذ البناء أو الاختبار بنجاح.

## ما الموجود فعليًا؟

- CLI لفحص APK وDEX وABI، وفك **الموارد وSmali كاملًا**، وتحليل Manifest.
- تعديل في مجلد جديد، دون الكتابة فوق الأصل: الاسم، أيقونة أصلية جديدة، معرّف
  التطبيق، authorities والأذونات المخصصة المرتبطة به، وSDK في Manifest و`apktool.yml`.
- الحفاظ على أسماء أصناف Java/JNI الأصلية؛ تغيير application ID لا يستلزم إعادة
  تسمية كل أصناف Smali بصورة عمياء.
- رفض تعديل Split APK ناقص، أو `sharedUserId`، أو سياسة `exported` غير المراجعة.
  جعل ميزات العتاد اختيارية يتم بأسماء محددة فقط، ولا تُحوّل فحوص الأجهزة إلى `true`.
- مولّد عشر LUTs بصيغتي `.cube` وPNG atlas، وشيدر وضوح/تباين محلي، وتحكم بالشدة 0–100%.
- `hdr-kit`: مكتبة Java/API 23+ تستخدم EGL/OpenGL ES 2.0 وexternal OES لتوفير مسار
  SDR مرجعي إلى المعاينة وMediaCodec وقراءة إطار معالج. لا تستخدم RenderScript المهجور.
- أوامر بناء ومحاذاة ZIP بصفحات 16 KiB وتوقيع **v1 + v2 + v3**، والتحقق قبل حفظ
  الناتج المحلي. **v1 ضروري لـAndroid 6**؛ v2 بدأ في Android 7 وv3 في Android 9.
- اختبارات وحدة، وCI لبناء المكتبة المستقلة، ومصفوفة اختيارية لاختبار شيدراتها،
  وأداة smoke test على جهاز ADB محدد صراحةً. راجع [معايير القبول](docs/ACCEPTANCE.md).

## لماذا لا يكفي تغيير minSdk؟

خفضه إلى 23 يزيل قيد تثبيت فقط؛ لا يحوّل استدعاءات Java الحديثة أو رموز NDK أو
Camera2/MediaCodec أو مكتبات ARM إلى بدائل تعمل على Android 6. وقد يفشل تجميع DEX
أو تحميل الأصناف/المكتبات قبل فتح الواجهة. كما أن رفع target إلى 35 يغيّر سلوك
الأذونات، التخزين، المكونات المصدّرة وخدمات الكاميرا/الميكروفون الأمامية.

دعم Android 6–15 وكل أجهزة Xiaomi/Huawei/Samsung **ليس وعدًا**. يلزم فحص النسخة
الأصلية، بدائل حقيقية للكود غير المتوافق، واختبارات على أجهزة ذات ABI وعتاد مناسبين.
لا يمكن اكتساب تصوير 10-bit/ProRes أو HDR حقيقي بتعديل Manifest.

## بدء العمل محليًا

المطلوب Python 3.11+، ونسخة APK مستقلة حصلت عليها بصورة مشروعة. لا يتصل CLI بالشبكة،
ولا ينزّل تطبيقات أو أدوات أو يتجاوز الترخيص/سلامة التطبيق.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements-dev.txt
python -m pip install -e .
python -m camera_magic --help
python -m unittest discover -v
python -m camera_magic generate-luts --check
```

لتجديد الأصول بعد تعديل `assets/mahdi_hdr/presets.json`:

```bash
python -m camera_magic generate-luts
```

الأوامر تعمل من جذر checkout. التثبيت editable يبقي مسار الأصول صحيحًا؛ عند استعمال
نسخة من الحزمة خارج المستودع، مرّر `--hdr-assets /path/to/assets/mahdi_hdr` قبل اسم الأمر.

### الأدوات الخارجية

| الأداة | شرط الأدوات المحلية | ملاحظات |
|---|---|---|
| JDK + keytool | 17+ | مطلوب للأدوات الحديثة؛ لا تُحفظ كلمات المرور في الشيفرة |
| [Apktool](https://github.com/iBotPeaches/Apktool/releases) | 2.11.1+ | يدعم السكربت تنسيق YAML في 2.x و3.x |
| [jadx](https://github.com/skylot/jadx/releases) | 1.5.1+ | اختياري للتحليل، لا يعيد الشيفرة الأصلية بصورة مضمونة |
| Android SDK Build-Tools | إصدار مستقر 35.0.0+ | `aapt2`, `zipalign`, `apksigner`, وD8 عند الدمج اليدوي |
| Platform tools / emulator / system images | للاختبار | صور API 23/29/33/35 ومطابقة ABI ضرورية |
| [Smali/Baksmali](https://github.com/google/smali/releases) | يُراجع عند تعديل DEX يدويًا | Apktool يضم assembler/disassembler؛ لا نزعم تحقق إصدار مستقل 3.0.9+ |

تحقّق من آخر إصدار مستقر ومجموع التحقق من المصدر الرسمي عند الإعداد. أظهرت بيانات
إصدارات GitHub أثناء تجهيز الأدوات Apktool `3.0.3` وjadx `1.5.6`؛ ليست هذه ضمانة أنها
ستظل الأحدث. لم يُتحقق من وجود إصدار APK `3.5.0.0064` أو أحدث، ولا من إصدار Smali
مستقل `3.0.9+` أو تثبيته. لا توجد روابط تنزيل مختلقة أو أدوات ثنائية معاد توزيعها.

لبناء **المكتبة الأصلية فقط**: إعداد قابل للتكرار AGP `8.9.2`، Gradle `8.11.1`،
JDK 17، compile/target API 35 وmin API 23. هذا baseline متوافق مع API 35، وليس ادعاء
باستخدام أحدث Android Studio/AGP تلقائيًا. ليست هناك حاجة إلى NDK لأن المكتبة Java/GL.

```bash
cp config/toolchain.example.json toolchain.json
# عدّل المسارات المطلقة إلى أدواتك الفعلية؛ أو استعمل متغيرات البيئة أدناه.
python -m camera_magic doctor
```

بدل ملف الإعداد يمكن استخدام `APKTOOL_JAR`, `JADX`, `JAVA_HOME`, `ANDROID_SDK_ROOT`.
يختار السكربت أعلى Build-Tools مستقر >=35 من SDK، أو الإصدار المحدد في الإعداد.
غياب الأدوات يرجع exit code 1 من `doctor`، ولا يُخفى كنجاح. تفاصيل إضافية:
[دليل الأدوات](docs/TOOLCHAIN.md).

## مسار تعديل محلي — إعادة تسمية فقط

ضع الأصل في `inputs/original.apk` (المجلد مستبعد من Git). لملف `.apks`/`.xapk`
يمكن إجراء inventory؛ **لا يمكن دمج splits بمجرد حذف isSplitRequired أو ضغطها معًا**.
وفّر نسخة مستقلة كاملة أو دمجًا خارجيًا مدققًا يشمل الموارد وDEX والمكتبات.

```bash
mkdir -p inputs
# انسخ APK الأصلي بنفسك إلى inputs/original.apk
python -m camera_magic inventory inputs/original.apk
python -m camera_magic decode inputs/original.apk \
  --output work/decompiled --jadx-output work/jadx
python -m camera_magic audit work/decompiled

# إقرار صريح: تخفيض SDK لا يعني دعم Android 6.
python -m camera_magic patch work/decompiled --output work/patched \
  --acknowledge-unsupported-sdk
```

`--jadx-output` اختياري. لا تُستخدم `apktool d -r -s` هنا، لأنها تمنع فك الموارد
وSmali اللازمين للتعديل. يمكن إضافة `--optional-feature NAME` بعد فحص تقرير audit
لجعل ميزة موجودة بعينها اختيارية، و`--remove-max-sdk` بعد مراجعة قيد maxSdk إن وجد.
لا تُزل متطلبات GL/العتاد التي لا يملك الكود بديلًا لها.

راجع `.camera-magic/patch.json` داخل الناتج، وخاصة hardcoded IDs ومواضع فحص العتاد.
لا تُبدَّل سلاسل native/JNI أو resource IDs أو كل أسماء الحزم آليًا. تغيير authorities
في Manifest قد يحتاج تعديلات مقابلة للكود الذي يستخدمها. **التثبيت بجانب الأصل
يحتاج اختبارًا فعليًا** رغم استخدام application ID مختلف.

```bash
python -m camera_magic build work/patched --rebrand-only
```

ينتج APK غير موقع باسم `artifacts/Blackmagic_Camera_Mahdi_REBRAND_ONLY-unsigned.apk`
**إذا نجحت الأدوات الحقيقية**. نسخ LUTs إلى assets لا يفعّلها؛ لهذا يفرض CLI وسم
`REBRAND_ONLY` ولا ينتحل ناتج HDR نهائيًا. خطوات الدمج الحقيقي:
[دليل الدمج](docs/INTEGRATION.md).

### التوقيع الآمن محليًا

لا ترسل المفاتيح أو كلمات المرور في المحادثة أو إلى Git. نفّذ التالي في طرفية محلية:

```bash
read -r -s -p 'Keystore password (12+ characters): ' MAHDI_STORE_PASSWORD; echo
export MAHDI_STORE_PASSWORD
export MAHDI_KEY_PASSWORD="$MAHDI_STORE_PASSWORD"
python -m camera_magic keygen --keystore keys/mahdi-release.jks --alias mahdi
python -m camera_magic sign \
  artifacts/Blackmagic_Camera_Mahdi_REBRAND_ONLY-unsigned.apk \
  --rebrand-only --keystore keys/mahdi-release.jks --alias mahdi
unset MAHDI_STORE_PASSWORD MAHDI_KEY_PASSWORD
python -m camera_magic verify artifacts/Blackmagic_Camera_Mahdi_REBRAND_ONLY.apk
```

إن كان المفتاح موجودًا فتجاوز `keygen` واستعمله لتحديثاتك اللاحقة. يتم تنفيذ
`zipalign -P 16` **قبل** التوقيع، ثم `apksigner verify` وفحص المحاذاة والاسم وSDK بعده.
لا يُستخدم jarsigner. محاذاة ZIP لا تصلح ELF ذا مقاطع PT_LOAD غير مناسبة لـ16 KiB.
ملف `.verification.json` يثبت ما فحصته الأدوات فقط، لا تشغيل الكاميرا أو الفلاتر.

## الاختبار والتثبيت

```bash
adb devices
python -m camera_magic smoke-test \
  artifacts/Blackmagic_Camera_Mahdi_REBRAND_ONLY.apk \
  --serial emulator-5554 --api 23 --report-dir work/reports/api23-run1
```

كرّر على API 29 و33 و35 بأرقام أجهزة ومجلدات تقارير جديدة. تتوقف الأداة عند اختلاف
ABI أو API، ولا تحذف التطبيق الأصلي أو تمسح logcat العام. تتضمن التقارير محاولة
التثبيت/التشغيل وصورة شاشة حقيقية إن أمكن. لا توجد لقطات شاشة مختلقة في المستودع.
راجع [خطة الاختبار](docs/TESTING.md) و[بوابة القبول](docs/ACCEPTANCE.md).

التثبيت اليدوي، **فقط بعد امتلاك APK مبني وموقع والتحقق منه**: فعّل «تثبيت تطبيقات
غير معروفة / من مصادر غير معروفة» لمصدر الملف، وافتح الحزمة وامنح أذونات الكاميرا
والميكروفون عند الحاجة. لا يتطلب ذلك تعطيل حماية الجهاز عمومًا. التوقيع الجديد لا
يحدّث النسخة الرسمية؛ المعرّف المختلف مقصود للتثبيت بجانبها.

## الملفات والنشر

```text
camera_magic/          CLI + فحص وتعديل وبناء وتوقيع واختبار محلي
assets/mahdi_hdr/      10 LUTs أصلية + presets + شيدرات
hdr-kit/              مكتبة GL مرجعية واختبارات Android لشيدراتها
config/               مثال إعداد الأدوات دون أسرار
scripts/              فحوص مساعدة
tests/                اختبارات وحدة بمدخلات اصطناعية (ليست APK قابلة للتثبيت)
docs/                 الدمج، الأدوات، الاختبار ومعايير القبول
.github/workflows/    CI للمصادر فقط؛ لا ينزّل التطبيق الأصلي
```

`inputs/`, `work/`, `decompiled/`, `artifacts/`, `keys/` وامتدادات APK/DEX/keystore
مستبعدة من Git عمدًا. لا يوجد مجلد decompiled أو APK نهائي مزيّف لتعويض الأصل المفقود.
احتفظ بمصدر الطرف الثالث والناتج محليًا/في تخزين مقيد ما لم تكن تملك إذن إعادة توزيعه.
المستودع عام؛ لا يساوي طلب التعديل إذنًا من صاحب الحقوق لنشر شيفرته المفكوكة.

**تحذير الاستخدام المطلوب لأي نسخة معدلة مستقبلًا:** «هذا التطبيق معدل للاستخدام
الشخصي فقط. الحقوق الأصلية محفوظة لـ Blackmagic Design.» لا ننتسب إلى Blackmagic
Design ولا نمنح إذنًا لاستخدام علاماتها. عبارة «تعليمي/شخصي» لا تلغي شروط الترخيص.

[LICENSE](LICENSE) هو MIT **للأدوات والأصول الأصلية هنا فقط**؛ لا يعيد ترخيص Blackmagic
Camera أو شيفرته أو علاماته أو تبعيات الطرف الثالث. راجع [CHANGELOG](CHANGELOG.md).
