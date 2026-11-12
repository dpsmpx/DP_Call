# WebRTC: Java-классы вызываются из нативного кода по именам
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Наш обработчик звука вызывается из нативного кода WebRTC через интерфейс AudioProcessing
-keep class com.dpsmpx.dpcall.webrtc.ReceiveNoiseGate { *; }

# secp256k1: нативная реализация подгружается по имени класса
-keep class fr.acinq.secp256k1.** { *; }

# kotlinx.serialization: сериализаторы моделей (контакты, сигналы, сообщения релеев)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.dpsmpx.dpcall.** {
    *** Companion;
}
-keepclasseswithmembers class com.dpsmpx.dpcall.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dpsmpx.dpcall.**$$serializer { *; }

# Понятные стек-трейсы в отчётах об ошибках
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
