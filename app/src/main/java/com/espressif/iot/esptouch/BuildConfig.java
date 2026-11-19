package com.espressif.iot.esptouch;

/**
 * 本地占位：官方 esptouch 源码引用了库模块自己的 BuildConfig，
 * 我们把它作为源码内联到 App 里，因此补一个等价常量类。
 */
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final String VERSION_NAME = "0.3.4.7";

    private BuildConfig() {
    }
}
