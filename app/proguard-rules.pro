# 출시 빌드(R8) 규칙. 줄이되 밖에서 이름으로 부르는 것은 남긴다

# Pleos SDK(차량·내비·음성 인식): AIDL·리플렉션으로 이어지므로 그대로 둔다
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# ONNX Runtime(말소리 판정기): 네이티브(JNI)가 자바 클래스를 이름으로 찾는다
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# 안드로이드 자동차 라이브러리(android.car)는 기기에 있다
-dontwarn android.car.**

# Pleos SDK가 안에서 Gson TypeToken으로 응답을 푼다: 제네릭 서명과 TypeToken 하위 클래스를 남긴다(10/29 출시 빌드 시험에서 공조·열선·무드등 확인이 깨졌다)
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
