package io.muse.nextepshim;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.view.SurfaceControl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Ports NeXtep's WorkspaceImeSurfaceHook to the classic Xposed API so the
 * input method can be fitted to the main pane on HyperOS: when the workspace
 * is active and WM leaves the keyboard on the display (full width, partly
 * under the rail), apply the main pane's transform to the IME container.
 * Geometry comes from the same Settings.Global snapshot NeXtep publishes.
 * Unlike the original, condition misses are logged (state changes only) so a
 * MIUI-specific mismatch is visible instead of silent.
 */
public class ImeFit {
    private static final String TAG = "NeXtepHomeShim: ";
    private static final String KEY_ACTIVE = "nextep_workspace_active_v1";
    private static final String KEY_GEOMETRY = "nextep_workspace_geometry_v1";

    static class Geo {
        int sw, sh, mainTaskTop;
        float mainScale, mainTx, imeTy;
    }

    private static volatile boolean active = false;
    private static volatile Geo geo = null;
    private static volatile Context wmContext = null;
    private static boolean observerStarted = false;
    private static SurfaceControl fitted = null;
    private static String lastDiagState = "";
    private static boolean failureReported = false;
    private static boolean capabilityDumped = false;
    private static long lastSyncReadMs = 0;
    private static final String KEY_STATE = "nextepshim_ime_state_v1";
    private static String lastPublished = "";
    private static String lastEvent = "-";

    private static void publishState(String state) {
        String value = state + " event=" + lastEvent;
        if (value.equals(lastPublished)) return;
        lastPublished = value;
        Context ctx = wmContext;
        if (ctx == null) return;
        try {
            Settings.Global.putString(ctx.getContentResolver(), KEY_STATE, value);
        } catch (Throwable ignored) {
        }
    }

    private static final Map<String, Method> methodCache = new HashMap<>();
    private static final Map<String, Field> fieldCache = new HashMap<>();

    public static void install(ClassLoader cl) {
        try {
            Class<?> displayContent = Class.forName("com.android.server.wm.DisplayContent", false, cl);
            XposedHelpers.findAndHookMethod(displayContent, "prepareSurfaces", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        fit(param.thisObject);
                    } catch (Throwable t) {
                        if (!failureReported) {
                            failureReported = true;
                            XposedBridge.log(TAG + "ime fit failed " + t);
                        }
                    }
                }
            });
            XposedBridge.log(TAG + "ime hook installed (DisplayContent.prepareSurfaces)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ime hook install failed " + t);
        }
    }

    // ---------- geometry (portrait derivations from WorkspaceGeometry) ----------

    private static Geo compute(String csv) {
        try {
            String[] p = csv.split(",");
            if (p.length < 14) return null;
            int sw = Integer.parseInt(p[0].trim());
            int sh = Integer.parseInt(p[1].trim());
            int topH = Integer.parseInt(p[2].trim());
            int sb = Integer.parseInt(p[3].trim());
            String side = p[4].trim();
            int navL = Integer.parseInt(p[5].trim());
            int navT = Integer.parseInt(p[6].trim());
            int navR = Integer.parseInt(p[7].trim());
            int navB = Integer.parseInt(p[8].trim());
            boolean cab = Integer.parseInt(p[10].trim()) == 1;
            int si = Integer.parseInt(p[11].trim());
            int mch = Integer.parseInt(p[12].trim());
            int bg = Integer.parseInt(p[13].trim());
            if (sw <= 0 || sh <= 0 || sw > sh) return null; // portrait only for now
            int aL = navL, aT = navT + si, aR = sw - navR, aB = sh - navB;
            int aW = aR - aL, aH = aB - aT;
            if (aW <= 1 || aH <= 1 || sb <= 0 || sb >= aW || topH < 0) return null;
            int contentTop = aT + (cab ? 0 : topH);
            int contentWidth = aW - sb;
            int contentHeight = aH - topH - bg;
            int contentLeft = aL + ("LEFT".equals(side) ? sb : 0);
            float contentScale = Math.min((float) contentWidth / aW, (float) contentHeight / aH);
            float contentTx = contentLeft + (contentWidth - aW * contentScale) / 2f - aL * contentScale;
            float contentTy = contentTop - aT * contentScale;
            Geo g = new Geo();
            g.sw = sw; g.sh = sh;
            if (mch > 0) {
                g.mainScale = (float) contentWidth / aW;
                g.mainTx = contentLeft - aL * g.mainScale;
                float mainTy = contentTop - aT * g.mainScale;
                int mainTaskHeight = mch + aT + (sh - aB);
                g.mainTaskTop = Math.max(0, sh - mainTaskHeight);
                g.imeTy = mainTy - g.mainTaskTop * g.mainScale;
            } else {
                g.mainScale = contentScale;
                g.mainTx = contentTx;
                g.mainTaskTop = 0;
                g.imeTy = contentTy;
            }
            return g;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void refreshSettings(Context ctx) {
        try {
            active = Settings.Global.getInt(ctx.getContentResolver(), KEY_ACTIVE, 0) == 1;
            String csv = Settings.Global.getString(ctx.getContentResolver(), KEY_GEOMETRY);
            geo = csv != null ? compute(csv) : null;
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void ensureObserver(Context ctx) {
        if (observerStarted) return;
        observerStarted = true;
        refreshSettings(ctx);
        HandlerThread thread = new HandlerThread("nextepshim-ime");
        thread.start();
        ContentObserver observer = new ContentObserver(new Handler(thread.getLooper())) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                Context c = wmContext;
                if (c != null) refreshSettings(c);
            }
        };
        ctx.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY_ACTIVE), false, observer);
        ctx.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY_GEOMETRY), false, observer);
        XposedBridge.log(TAG + "ime geometry observer started");
    }

    // ---------- the fit ----------

    private static void fit(Object display) {
        Object id = call(display, "getDisplayId");
        if (!(id instanceof Integer) || (Integer) id != 0) return;
        if (wmContext == null) {
            Object service = field(display, "mWmService");
            Object ctx = service != null ? field(service, "mContext") : null;
            if (ctx instanceof Context) wmContext = (Context) ctx;
            if (wmContext == null) return;
            ensureObserver(wmContext);
        }
        // Belt and braces: if the observer thread died, a throttled sync read
        // keeps the cached state honest.
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSyncReadMs > 250) {
            lastSyncReadMs = now;
            refreshSettings(wmContext);
        }
        if (!capabilityDumped) {
            capabilityDumped = true;
            XposedBridge.log(TAG + "ime caps: layeringTargetField=" + hasField(display, "mImeLayeringTarget")
                + " imeTargetField=" + hasField(display, "mImeTarget")
                + " inputTargetField=" + hasField(display, "mImeInputTarget")
                + " controlTargetField=" + hasField(display, "mImeControlTarget")
                + " containerMethod=" + hasMethod(display, "getImeContainer")
                + " containerField=" + (hasField(display, "mImeWindowsContainer") || hasField(display, "mImeContainer"))
                + " pendingTxMethod=" + hasMethod(display, "getPendingTransaction")
                + " shouldAttachMethod=" + hasMethod(display, "shouldImeAttachedToApp"));
        }
        Geo g = active ? geo : null;
        SurfaceControl.Transaction tx =
            (SurfaceControl.Transaction) call(display, "getPendingTransaction");
        Object container = call(display, "getImeContainer");
        if (container == null) container = field(display, "mImeWindowsContainer");
        if (container == null) container = field(display, "mImeContainer");
        SurfaceControl surface = null;
        if (container != null) {
            Object sc = call(container, "getSurfaceControl");
            if (sc instanceof SurfaceControl) surface = (SurfaceControl) sc;
        }
        // Evaluate all three IME targets independently: on MIUI the layering
        // target can be a taskless system window while the workspace is active,
        // which would mask the real editor sitting in the input target.
        String[] srcNames = {"layering", "input", "control"};
        String[] fieldNames = {"mImeLayeringTarget", "mImeInputTarget", "mImeControlTarget"};
        Object[] targets = new Object[3];
        Object[] tasks = new Object[3];
        for (int i = 0; i < 3; i++) {
            targets[i] = field(display, fieldNames[i]);
            if (targets[i] == null && i == 0) targets[i] = field(display, "mImeTarget");
            if (targets[i] != null) {
                tasks[i] = call(targets[i], "getTask");
                if (tasks[i] == null) {
                    Object ar = field(targets[i], "mActivityRecord");
                    if (ar != null) tasks[i] = call(ar, "getTask");
                }
            }
        }
        Object attachedObj = call(display, "shouldImeAttachedToApp");
        boolean attached = Boolean.TRUE.equals(attachedObj);
        Rect expected = g != null ? new Rect(0, g.mainTaskTop, g.sw, g.sh) : null;
        boolean canFit = false;
        String winSrc = "-";
        StringBuilder cand = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            cand.append(' ').append(srcNames[i]).append("Task=").append(tasks[i] != null);
            if (!canFit && g != null && surface != null && tasks[i] != null && !attached) {
                Object wm = call(tasks[i], "getWindowingMode");
                Object bounds = call(tasks[i], "getBounds");
                cand.append("(wm=").append(wm).append(",b=").append(bounds).append(')');
                if (Integer.valueOf(1).equals(wm) && expected.equals(bounds)) {
                    canFit = true;
                    winSrc = srcNames[i];
                }
            }
        }
        Object target = targets[0];
        String title = "-", pkg = "-";
        if (target != null) {
            Object t = call(target, "getTitle");
            if (t != null) title = String.valueOf(t);
            Object attrs = field(target, "mAttrs");
            Object p = attrs != null ? field(attrs, "packageName") : null;
            if (p != null) pkg = String.valueOf(p);
        }

        {
            StringBuilder state = new StringBuilder();
            state.append("active=").append(active)
                .append(" geo=").append(g != null)
                .append(" surface=").append(surface != null)
                .append(" tx=").append(tx != null)
                .append(" attached=").append(attached)
                .append(" win=").append(winSrc)
                .append(cand)
                .append(" lTitle=").append(title)
                .append(" lPkg=").append(pkg)
                .append(" expected=").append(expected);
            String s = state.toString();
            if (!s.equals(lastDiagState)) {
                lastDiagState = s;
                XposedBridge.log(TAG + "ime state: " + s);
                publishState(s);
            }
        }

        if (tx == null) return;
        if (canFit) {
            setMatrix(tx, surface, g.mainScale);
            tx.setPosition(surface, g.mainTx, g.imeTy);
            if (fitted != surface) {
                fitted = surface;
                lastEvent = "fitted:" + winSrc;
                XposedBridge.log(TAG + "ime fitted scale=" + g.mainScale + " tx=" + g.mainTx + " ty=" + g.imeTy);
                publishState(lastDiagState + " scale=" + g.mainScale);
            }
        } else if (fitted != null) {
            if (fitted.isValid()) {
                setMatrix(tx, fitted, 1f);
                tx.setPosition(fitted, 0f, 0f);
            }
            fitted = null;
            lastEvent = "released";
            XposedBridge.log(TAG + "ime released");
            publishState(lastDiagState);
        }
    }

    private static Method setMatrixMethod = null;

    /** Transaction.setMatrix is not in the public SDK; invoke it reflectively like the original. */
    private static void setMatrix(SurfaceControl.Transaction tx, SurfaceControl sc, float scale) {
        try {
            if (setMatrixMethod == null) {
                setMatrixMethod = SurfaceControl.Transaction.class.getDeclaredMethod("setMatrix",
                    SurfaceControl.class, float.class, float.class, float.class, float.class);
                setMatrixMethod.setAccessible(true);
            }
            setMatrixMethod.invoke(tx, sc, scale, 0f, 0f, scale);
        } catch (Throwable t) {
            if (!failureReported) {
                failureReported = true;
                XposedBridge.log(TAG + "ime setMatrix unavailable " + t);
            }
        }
    }

    private static boolean hasField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try { c.getDeclaredField(name); return true; }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        return false;
    }

    private static boolean hasMethod(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    // ---------- reflection helpers (walk superclasses, cached) ----------

    private static Object field(Object target, String name) {
        try {
            String key = target.getClass().getName() + "#" + name;
            Field f;
            synchronized (fieldCache) {
                if (!fieldCache.containsKey(key)) {
                    Field found = null;
                    Class<?> c = target.getClass();
                    while (c != null && found == null) {
                        try { found = c.getDeclaredField(name); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
                    }
                    if (found != null) found.setAccessible(true);
                    fieldCache.put(key, found);
                }
                f = fieldCache.get(key);
            }
            return f != null ? f.get(target) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object call(Object target, String name) {
        try {
            String key = target.getClass().getName() + "." + name;
            Method m;
            synchronized (methodCache) {
                if (!methodCache.containsKey(key)) {
                    Method found = null;
                    Class<?> c = target.getClass();
                    while (c != null && found == null) {
                        for (Method candidate : c.getDeclaredMethods()) {
                            if (candidate.getName().equals(name) && candidate.getParameterCount() == 0) { found = candidate; break; }
                        }
                        if (found == null) c = c.getSuperclass();
                    }
                    if (found != null) found.setAccessible(true);
                    methodCache.put(key, found);
                }
                m = methodCache.get(key);
            }
            return m != null ? m.invoke(target) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
