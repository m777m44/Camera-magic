# نشر Mahdi Camera المستقل

هذا دليل تطبيق `camera-app` / `com.mahdi.camera`، وليس دليل نسخ Blackmagic أو إعادة
تسميته. لا يلزم تنزيل APK طرف ثالث. أصول الإصدارات خارج Git ولا تشمل مفتاحًا خاصًا.

## دورة النشر

1. تنجح `Mahdi Camera app validation` على SHA الحالي لـmain: بناء/Lint/اختبارات وحدة،
   ثم معاينة وتسجيل وفك MP4 وحفظه ودورة حياة الكاميرا على محاكي API 23 و35.
2. يبدأ `Publish Mahdi Camera APK` من `workflow_run` موثوق في المستودع نفسه.
3. إذا كانت Secrets الثابتة متاحة يعاد بناء release من المصدر، ثم zipalign 36.0.0
   وتوقيع v1/v2/v3. لا يوجد توليد تلقائي لمفتاح خاص جديد في CI.
4. يتحقق الناشر من الباكج والاسم وSDK والأذونات وبصمة الشهادة العامة والمحاذاة،
   ومن مساواة **كل ملفات الحزمة غير ملفات توقيع JAR** لبناء المصدر، بما فيها الموارد وDEX.
5. يرفع APK وتقرير JSON وSHA256SUMS إلى Draft، ثم يراجع SHA main ثانية وينشر Latest.
   الفشل يترك آخر تنزيل ناجح دون تغيير؛ البناء القديم أو الحزمة غير الموقعة لا تصبح Latest.

رابط التطبيق المستقل الثابت:

```text
https://github.com/m777m44/Camera-magic/releases/latest/download/Mahdi_Camera.apk
```

يبقى خاضعًا لوجود إصدار منشور كامل وعدم حذف أصله/تغيير اسمه. رابط النسخة القديمة
`Blackmagic_Camera_Mahdi.apk` لا يمثل هذا التطبيق. لا يوجد محدث ذاتي يثبت APK على الهاتف.

## التوقيع الثابت والتحديثات

شهادة التطبيق العامة مثبتة في `config/mahdi-camera-signing.json`. لا تحتوي مفتاحًا خاصًا.
المفتاح الإنتاجي الأول محفوظ في بيئة المالك الخاصة داخل `keys/mahdi-camera-release.jks`،
ومعلومات فتحه في `keys/mahdi-camera-private.json` بصلاحيات 600 وخارج Git.
**احفظ نسخة احتياطية خاصة من الملفين خارج مساحة العمل**؛ إعادة clone لا تسترجعهما.
لا ترسلهما في المحادثة ولا تضفهما إلى Git أو release assets أو CI artifacts.

التطبيق مستقل بشهادته ومعرفه، ولا يحدّث Blackmagic. لا يمكن تحديث release بمفتاح
جديد عشوائي أو نسخة debug ذات شهادة مختلفة. لا تزعم هذه الأدوات تجاوز قيود Android
على التوقيع؛ تغيير بصمة pin لا يجعل شهادة جديدة متوافقة مع تثبيت قديم.

يشتق CI versionCode من عدد commits في التاريخ الكامل، وversionName من `1.0.<count>`.
لا تُعد كتابة تاريخ main أو تنقص versionCode. يستعمل البناء والتوقيع والنشر نفس SHA
وعدد commits؛ رقم إصدار toolkit Python منفصل عن رقم التطبيق.

## تفعيل النشر الآلي بالكامل

على المالك إعداد Repository Secrets التالية من Settings → Secrets and variables → Actions:

| Secret | المحتوى |
|---|---|
| `MAHDI_APP_KEYSTORE_B64` | base64 في سطر واحد للمفتاح الثابت المطابق للبصمة العامة |
| `MAHDI_APP_STORE_PASSWORD` | كلمة مرور المخزن، 12 حرفًا على الأقل |
| `MAHDI_APP_KEY_PASSWORD` | كلمة مرور المفتاح، 12 حرفًا على الأقل؛ alias هو `mahdi` |

بعد إعدادها، كل تحديث main ناجح ينشر بنفس المفتاح دون مصدر APK خارجي أو تدخل يدوي.
غيابها يجعل الناشر يتوقف دون استبدال التنزيل، لا أن ينشر تطبيقًا بتوقيع debug.
لا يحتاج التطبيق المستقل `APK_SOURCE_URL` أو `APK_SOURCE_SHA256` أو متغير النشر القديم.

يمكن استعمال `scripts/configure_app_signing.py` من بيئة خاصة تحتوي الملفين والمخول لها
إدارة Secrets. ينقل القيم إلى gh عبر stdin ولا يطبعها أو يضعها في argv.
إذا أعادت GitHub خطأ 403، فصلاحيات الاتصال لا تسمح بإعداد الأسرار؛ لا تحاول وضع المفتاح
في ملف workflow أو متغير عام لتعويض هذا القيد. إعداد المالك في واجهة GitHub هو البديل.

## بديل آمن: التوقيع محليًا وتسليم APK العامة فقط

لبيئات لا تستطيع تعديل Secrets أو تنزيل artifacts عبر شبكتها، يدعم الناشر أحداث
`repository_dispatch`. هذه تتطلب Contents-write، ولا تستعمل `pull_request_target`.

1. تحقق من نجاح **اختبارات التطبيق** على main الحالي واحصل على رقم run.
2. أرسل طلب بناء غير موقع:

```bash
gh api --method POST repos/m777m44/Camera-magic/dispatches --input request-unsigned.json
```

ومحتوى الملف العام (ضع رقم run الفعلي، لا قيمة تجريبية):

```json
{"event_type":"mahdi-build-unsigned","client_payload":{"validation_run":"<successful-main-run-id>"}}
```

3. يعيد CI البناء بالمصدر المثبت، ويضع APK غير موقعة مع SHA-256 داخل جسم **Draft خاص
   للتسليم**. لا تُرفع كنسخة عامة ولا تعلم Latest؛ لا يوجد مفتاح داخل المسودة.
4. احصل على JSON للمسودة وأزل ترميز الحزمة:

```bash
gh api repos/m777m44/Camera-magic/releases/tags/TAG > work/unsigned-release.json
python -m camera_magic.app_release extract --release-json work/unsigned-release.json \
  --apk artifacts/unsigned-source.apk
```

5. وقع محليًا باستخدام المفتاح نفسه وبيئة Java. مساعد `local-sign` الأولي يقبل JAR
   apksigner 33.0.2 المثبت بـSHA-256 `925fb5189d62fea563eaa24636108cb28a7281c05b63f3478f6c53e7768c7d3b`
   من الملف الموجود في تاريخ مصدر `patrickfav/uber-apk-signer`، Git blob
   `8a8aca644940fd9ad5ae244c111ec1abc367bfc9`؛ لا يُعاد توزيعه في المستودع.
   هذا استثناء أداة التوقيع المحلي الأولي فقط؛ **CI يبني ويحاذي ويعيد التحقق بأدوات
   SDK 36.0.0** قبل نشر APK. المسار المعتاد ذو Secrets يستخدم 36.0.0 للتوقيع أيضًا.

```bash
python -m camera_magic.app_release local-sign --apk artifacts/unsigned-source.apk \
  --java /private/path/to/java --apksigner-jar /private/path/to/apksigner-33.0.2.jar \
  --output artifacts/Mahdi_Camera.apk
# الملف التالي لا يحتوي إلا APK العامة الموقعة وبصمتها ومرجع البناء؛ لا كلمة مرور أو مفتاح.
gh api --method POST repos/m777m44/Camera-magic/dispatches --input work/mahdi-dispatch.json
```

6. يتحقق CI من main وrun ناجح، **ويعيد بناء المصدر مرة أخرى** ويقارن كل ملفات APK
   المستلمة بغير ملفات التوقيع. ثم يفحص التوقيع والشهادة وSDK والمحاذاة ويرفع الأصول
   من runner إلى Draft جديد، ولا ينقل Latest إلا بعد اكتمالها. الحزمة الموقعة وحدها
   لا تتجاوز فحص المصدر أو بصمة الشهادة المثبتة.
7. يمكن حذف مسودة التسليم غير الموقعة بعد النشر. حجم طريقة التسليم محدود بما تسمح
   به أجسام GitHub/dispatch؛ للتطبيقات الكبيرة استعمل Secrets أو تخزين أصول خاصًا موثوقًا،
   ولا تقسم مفاتيح خاصة إلى تعليقات عامة أو تضعها في payload.

هذه الطريقة تتيح الإصدار الأول والتحديثات الموقعة محليًا لكنها **ليست نشرًا تلقائيًا
بالكامل دون مالك**. ما لم تُضبط Secrets، كل تحديث يحتاج التوقيع المحلي بالمفتاح الثابت.

## نطاق الاختبار

اختبارات Python تحمي شروط النشر، البصمات، عدم حقن أذونات الشبكة، سلامة تسليم APK
وشروط المصدر/الشهادة. اختبارات Java تتحقق من حسابات الدوران والتعريض. اختبارات
المحاكي تتحقق من فيديو وصوت فعليين وفك إطار، لا من جودة مستشعر هاتف أو كل ميزات
Blackmagic. مراجعة فنية فعلية للأجهزة والصوت والاتجاه والتسجيل الطويل ما زالت مطلوبة.
