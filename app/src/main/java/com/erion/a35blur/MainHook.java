package com.erion.a35blur;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.android.systemui";
    private static final String BLUR_PROP = "ro.surface_flinger.supports_background_blur";

    private static final String[] METHOD_CLASSES = {
        "com.android.systemui.statusbar.BlurUtils",
        "com.android.systemui.blur.data.repository.SecBlurSettingsRepositoryImpl",
        "com.android.systemui.blur.domain.interactor.SecBlurSettingsInteractor",
        "com.android.systemui.blur.data.repository.SecCapturedBlurRepositoryImpl",
        "com.android.systemui.blur.domain.interactor.SecCapturedBlurInteractor",
        "com.android.systemui.blur.domain.interactor.SecCapturedBlurInfoInteractor",
        "com.android.systemui.blur.domain.interactor.SecPanelWindowBlurInteractor",
        "com.android.systemui.blur.ui.viewmodel.SecCapturedBlurContainerViewModel",
        "com.android.systemui.blur.ui.viewbinder.SecCapturedBlurContainerBinder",
        "com.android.systemui.statusbar.phone.CapturedBlurContainer",
        "com.android.systemui.blur.SecQpBlurController",
        "com.android.systemui.blur.SecCoverBlurController",
        "com.android.systemui.statusbar.policy.QuickPanelBlur",
        "com.android.systemui.window.data.repository.WindowRootViewBlurRepositoryImpl",
        "com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor",
        "com.android.systemui.globalactions.features.CapturedBlurStrategy",
        "com.android.systemui.keyguard.ui.transitions.GlanceableHubBlurProvider",
        "androidx.core.view.SemBlurCompat",
    };

    private static final String[] FLAG_CLASSES = {
        "com.android.systemui.LsRune",
        "com.android.systemui.Rune",
        "com.android.systemui.BasicRune",
        "androidx.core.view.SemBlurCompat",
        "com.android.systemui.statusbar.BlurUtils",
    };

    private static final Set<String> FORCE_TRUE = new HashSet<>(Arrays.asList(
        "supportsBlursOnWindows", "isBlurSupported", "isCapturedBlurSupported",
        "isCapturedBlurAllowed", "isBlurCurrentlySupported", "isSupportBlur",
        "isBlurEnable", "isBlurEnabled", "isBlurredWallpaperOnShadeSupported",
        "isCrossWindowBlurEnabled"));

    private static final Pattern BAD_FLAG =
        Pattern.compile("DEBUG|FORCE|OFF|DISABLE|REDUCE|BLOCK|NOT_|NO_|DISALLOW");

    private static final ConcurrentHashMap<String, String> LAST = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;

        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", cl);
            XposedBridge.hookAllMethods(sp, "getBoolean", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 0 && BLUR_PROP.equals(param.args[0])) {
                            param.setResult(true);
                        }
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("A35Blur: prop hook failed: " + e);
        }

        for (String cn : METHOD_CLASSES) {
            hookClass(cn, cl);
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        try {
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (done.compareAndSet(false, true)) setFlags(cl);
                    }
                });
        } catch (Throwable e) {
            XposedBridge.log("A35Blur: onCreate hook failed: " + e);
            setFlags(cl);
        }
    }

    private static void hookClass(String cn, ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
            if (c == null) {
                XposedBridge.log("A35Trace: no class " + cn);
                return;
            }
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != boolean.class || m.getParameterTypes().length != 0) continue;
                final String name = m.getName();
                if (name.contains("$")) continue;
                final boolean force = FORCE_TRUE.contains(name);
                if (!force && !name.toLowerCase().contains("blur")) continue;
                final String key = c.getSimpleName() + "#" + name;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object old = p.getResult();
                                if (force) p.setResult(true);
                                String s = old + (force ? "->true" : "");
                                if (!s.equals(LAST.put(key, s))) {
                                    XposedBridge.log("A35Trace " + key + " = " + s);
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
                    n++;
                } catch (Throwable ignored) { }
            }
            XposedBridge.log("A35Trace: " + c.getSimpleName() + " hooked " + n);
        } catch (Throwable e) {
            XposedBridge.log("A35Trace: skip " + cn + ": " + e);
        }
    }

    private static void setFlags(ClassLoader cl) {
        for (String cn : FLAG_CLASSES) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
                if (c == null) {
                    XposedBridge.log("A35Flag: no class " + cn);
                    continue;
                }
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != boolean.class) continue;
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        String up = f.getName().toUpperCase();
                        if (!up.contains("BLUR") || BAD_FLAG.matcher(up).find()) continue;
                        f.setAccessible(true);
                        boolean old = f.getBoolean(null);
                        if (!old) f.setBoolean(null, true);
                        XposedBridge.log("A35Flag " + c.getSimpleName() + "." + f.getName()
                            + " " + old + (old ? "" : "->true"));
                    } catch (Throwable t) {
                        XposedBridge.log("A35Flag: field " + f.getName() + ": " + t);
                    }
                }
            } catch (Throwable e) {
                XposedBridge.log("A35Flag: skip " + cn + ": " + e);
            }
        }
    }
}
