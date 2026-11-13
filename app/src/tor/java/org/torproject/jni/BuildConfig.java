/*
 * Замена BuildConfig библиотеки tor-android: TorService сообщает из него версию Tor.
 * Обновляйте вместе с версией в app/build.gradle.kts (TOR_ANDROID_VERSION).
 */
package org.torproject.jni;

public final class BuildConfig {
  public static final String VERSION_NAME = "0.4.9.14";
}
