package com.erion.a35blur;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.android.systemui";
    private static final String BLUR_PROP = "ro.surface_flinger.supports_background_blur";
    private static final String[] FLAG_CLASSES = {
        "com.android.systemui.LsRune",
        "com.android.systemui.BasicRune",
        "com.android.systemui.Rune",
    };
    private static final Set<String> FORCE_FLAGS = new HashSet<>(Arrays.asList(
        "LOCKUI_BLUR", "LOCKUI_SHORTCUT_BLUR_BG"));
    private static final ConcurrentHashMap<String, Boolean> SEEN = new ConcurrentHashMap<>();

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
            XposedBridge.log("A35Fail prop hook: " + e);
        }

        try {
            Class<?> bu = XposedHelpers.findClass("com.android.systemui.statusbar.BlurUtils", cl);
            XposedBridge.hookAllMethods(bu, "supportsBlursOnWindows",
                XC_MethodReplacement.returnConstant(true));
        } catch (Throwable e) {
            XposedBridge.log("A35Fail BlurUtils: " + e);
        }

        probe("android.view.SurfaceControl$Transaction", "setBackgroundBlurRadius", cl);
        probe("com.android.internal.graphics.drawable.BackgroundBlurDrawable", "setBlurRadius", cl);
        probe("android.view.View", "semSetBlurInfo", cl);
        probe("android.view.ViewRootImpl", "createBackgroundBlurDrawable", cl);

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
            XposedBridge.log("A35Fail onCreate hook: " + e);
            setFlags(cl);
        }
    }

    private static String callers() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("com.android.systemui")) {
                sb.append(" < ").append(c).append('.').append(e.getMethodName());
                if (++n >= 4) break;
            }
        }
        return sb.toString();
    }

    private static void probe(String cn, final String mn, ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
            if (c == null) {
                XposedBridge.log("A35Probe no class " + cn);
                return;
            }
            final String tag = c.getSimpleName() + "#" + mn;
            XposedBridge.hookAllMethods(c, mn, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (SEEN.size() > 300) return;
                        String key = tag + " " + Arrays.toString(p.args);
                        if (SEEN.putIfAbsent(key, Boolean.TRUE) == null) {
                            XposedBridge.log("A35Probe " + key + callers());
                        }
                    } catch (Throwable ignored) { }
                }
            });
            XposedBridge.log("A35Probe hooked " + tag);
        } catch (Throwable e) {
            XposedBridge.log("A35Probe skip " + cn + ": " + e);
        }
    }

    private static void setFlags(ClassLoader cl) {
        for (String cn : FLAG_CLASSES) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
                if (c == null) continue;
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != boolean.class) continue;
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        String name = f.getName();
                        if (!FORCE_FLAGS.contains(name)) continue;
                        f.setAccessible(true);
                        if (!f.getBoolean(null)) {
                            boolean ok = forceSet(f, true);
                            XposedBridge.log("A35Set " + c.getSimpleName() + "." + name + " ok=" + ok);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("A35Fail field " + f.getName() + ": " + t);
                    }
                }
            } catch (Throwable e) {
                XposedBridge.log("A35Fail class " + cn + ": " + e);
            }
        }
    }

    private static boolean forceSet(Field f, boolean v) {
        try {
            f.setAccessible(true);
            f.setBoolean(null, v);
            if (f.getBoolean(null) == v) return true;
        } catch (Throwable ignored) { }
        try {
            Field af = Field.class.getDeclaredField("accessFlags");
            af.setAccessible(true);
            af.setInt(f, af.getInt(f) & ~Modifier.FINAL);
            f.setBoolean(null, v);
            if (f.getBoolean(null) == v) return true;
        } catch (Throwable ignored) { }
        try {
            Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            Field of = Field.class.getDeclaredField("offset");
            of.setAccessible(true);
            long off = ((Number) of.get(f)).longValue();
            Method put = unsafe.getClass().getMethod("putBoolean",
                Object.class, long.class, boolean.class);
            put.invoke(unsafe, f.getDeclaringClass(), off, v);
            if (f.getBoolean(null) == v) return true;
        } catch (Throwable ignored) { }
        return false;
    }
}
