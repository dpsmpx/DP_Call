# Keep WebRTC classes when we add the dependency
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# secp256k1: нативная реализация подгружается по имени класса
-keep class fr.acinq.secp256k1.** { *; }
