/*
 * PixelBoard - Gboard Enhancement Mod
 * Licensed under the GNU General Public License v3.0 (GPLv3)
 */
package com.akshaykadam.pixelboard.extension.language;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Globe-key language cycling without duplicate multilingual entries.
 *
 * <p>With multilingual typing Gboard keeps one input method entry per primary language, so
 * "English + German" and "German + English" are both in the globe-key rotation although they
 * type identically. {@link #nextEntry} replaces the rotation list's "next entry" lookup inside
 * switchToNextInputMethodEntry and skips any entry whose languages and layout are already
 * covered by an earlier entry in the Languages list.
 *
 * <p>Only the globe-key path is affected: the long-press picker, the stored entries and
 * multilingual typing itself are unchanged. Any failure falls back to Gboard's own result.
 */
public final class GboardLanguageCycleRuntime {
    private static final String TAG = "PixelBoardLangCycle";

    // Gboard 18.6.3 input method entry (oyq): i() primary language, j() active multilingual
    // languages, q() layout/variant. Kept in step with the patch's version-specific target.
    private static final String ENTRY_PRIMARY_LANGUAGE = "i";
    private static final String ENTRY_MULTILINGUAL_LANGUAGES = "j";
    private static final String ENTRY_LAYOUT = "q";
    // DynamicRotationList (qyf).g(entry, wrap): the stock next-entry lookup.
    private static final String ROTATION_NEXT_ENTRY = "g";
    // Entry context accessor (oyq.a()), used only for the temporary diagnostic toast.
    private static final String ENTRY_CONTEXT = "a";
    // TEMPORARY: show what the dedupe sees on every globe press. Remove once verified.
    private static final boolean DIAGNOSTICS = true;

    private static final Map<Class<?>, Method> NEXT_ENTRY_METHODS = new WeakHashMap<>();

    private GboardLanguageCycleRuntime() {
    }

    /**
     * Drop-in for {@code rotationList.g(entry, wrap)}: returns the next entry in the rotation,
     * skipping entries that duplicate an earlier entry's language set and layout.
     */
    public static Object nextEntry(Object rotationList, Object entry, boolean wrap) {
        Method next = nextEntryMethod(rotationList, entry);
        if (next == null) {
            // Cannot happen on a matching Gboard build; never break the globe key.
            Log.w(TAG, "next-entry lookup not found; globe key left unchanged");
            if (DIAGNOSTICS) {
                showDiagnostics(entry, "next-entry method NOT FOUND on "
                        + (rotationList == null ? "null" : rotationList.getClass().getName()));
            }
            return null;
        }
        Object candidate = invoke(next, rotationList, entry, wrap);
        StringBuilder report = DIAGNOSTICS ? new StringBuilder() : null;
        Object result = candidate;
        try {
            List<?> entries = entriesInListOrder(rotationList);
            if (report != null) {
                report.append("wrap=").append(wrap)
                        .append(" entries=").append(entries == null ? "null" : entries.size())
                        .append("\ncur: ").append(describe(entry))
                        .append("\ncand: ").append(describe(candidate));
                if (entries != null) {
                    for (int i = 0; i < entries.size(); i++) {
                        report.append("\n#").append(i).append(' ').append(describe(entries.get(i)));
                    }
                }
            }
            if (candidate == null || entries == null) {
                return candidate;
            }
            Object current = candidate;
            result = null;
            // Bounded walk: at most one lap of the rotation.
            for (int step = 0; step <= entries.size() && current != null; step++) {
                if (!isShadowedDuplicate(current, entries) || current.equals(entry)) {
                    result = current;
                    return current;
                }
                if (report != null) {
                    report.append("\nskip: ").append(describe(current));
                }
                current = invoke(next, rotationList, current, wrap);
            }
            result = current == null ? null : candidate;
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "dedupe failed; using stock next entry", t);
            if (report != null) {
                report.append("\nERROR: ").append(t);
            }
            result = candidate;
            return candidate;
        } finally {
            if (report != null) {
                report.append("\n=> ").append(describe(result));
                showDiagnostics(entry, report.toString());
            }
        }
    }

    /** TEMPORARY diagnostic: one line per entry with everything the dedupe looks at. */
    private static String describe(Object entry) {
        if (entry == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(entry.getClass().getSimpleName());
        for (String name : new String[] {ENTRY_PRIMARY_LANGUAGE, ENTRY_MULTILINGUAL_LANGUAGES, "k", ENTRY_LAYOUT}) {
            out.append(' ').append(name).append('=');
            try {
                out.append(call(entry, name));
            } catch (Throwable t) {
                out.append('!').append(t.getClass().getSimpleName());
            }
        }
        try {
            out.append(" key=").append(dedupeKey(entry));
        } catch (Throwable t) {
            out.append(" key=!").append(t.getClass().getSimpleName());
        }
        return out.toString();
    }

    private static void showDiagnostics(Object entry, String message) {
        Log.i(TAG, message);
        try {
            Object context = entry == null ? null : call(entry, ENTRY_CONTEXT);
            if (!(context instanceof Context)) {
                return;
            }
            Context app = ((Context) context).getApplicationContext();
            // Toasts are capped at two lines on Android 12+, so put the full report on the
            // clipboard and only announce it.
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    ClipboardManager clipboard =
                            (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("PixelBoard language cycle", message));
                    }
                    Toast.makeText(app, "PixelBoard: language-cycle report copied", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.w(TAG, "diagnostic toast failed", t);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "diagnostic toast failed", t);
        }
    }

    /** True when an earlier entry in the Languages list has the same language set and layout. */
    private static boolean isShadowedDuplicate(Object candidate, List<?> entries) throws Exception {
        String key = dedupeKey(candidate);
        if (key == null) {
            return false;
        }
        for (Object other : entries) {
            if (other == candidate) {
                return false;
            }
            if (other != null && key.equals(dedupeKey(other))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Order-independent key: sorted {primary} ∪ multilingual languages, plus the layout.
     * Returns null for entries without multilingual languages, which are never skipped.
     */
    private static String dedupeKey(Object entry) throws Exception {
        Object multilingual = call(entry, ENTRY_MULTILINGUAL_LANGUAGES);
        if (!(multilingual instanceof Collection) || ((Collection<?>) multilingual).isEmpty()) {
            return null;
        }
        TreeSet<String> languages = new TreeSet<>();
        for (Object language : (Collection<?>) multilingual) {
            if (language != null) {
                languages.add(language.toString());
            }
        }
        Object primary = call(entry, ENTRY_PRIMARY_LANGUAGE);
        if (primary != null) {
            languages.add(primary.toString());
        }
        if (languages.size() < 2) {
            return null;
        }
        return languages + "|" + Objects.toString(call(entry, ENTRY_LAYOUT), "");
    }

    /**
     * The rotation list stores its entries (in Languages-list order) as a List field and the
     * rotation order as an int[] field; find the List by type rather than by obfuscated name.
     */
    private static List<?> entriesInListOrder(Object rotationList) throws IllegalAccessException {
        for (Field field : rotationList.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(rotationList);
            if (value instanceof List) {
                return (List<?>) value;
            }
        }
        return null;
    }

    /** The rotation list's {@code (Entry, boolean) -> Entry} method, found by shape. */
    private static synchronized Method nextEntryMethod(Object rotationList, Object entry) {
        if (rotationList == null || entry == null) {
            return null;
        }
        Class<?> owner = rotationList.getClass();
        Method cached = NEXT_ENTRY_METHODS.get(owner);
        if (cached != null) {
            return cached;
        }
        // The rotation list class is merged with unrelated code by R8, so prefer the known
        // 18.6.3 name and only accept an (Entry, boolean) -> Entry shape with a real entry type.
        Method byShape = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            boolean shape = params.length == 2
                    && params[1] == boolean.class
                    && params[0] != Object.class
                    && params[0].isInstance(entry)
                    && method.getReturnType() == params[0]
                    && !java.lang.reflect.Modifier.isStatic(method.getModifiers());
            if (!shape) {
                continue;
            }
            if (ROTATION_NEXT_ENTRY.equals(method.getName())) {
                byShape = method;
                break;
            }
            if (byShape == null) {
                byShape = method;
            }
        }
        if (byShape != null) {
            byShape.setAccessible(true);
            NEXT_ENTRY_METHODS.put(owner, byShape);
        }
        return byShape;
    }

    private static Object invoke(Method method, Object target, Object entry, boolean wrap) {
        try {
            return method.invoke(target, entry, wrap);
        } catch (Throwable t) {
            Log.w(TAG, "next-entry lookup failed", t);
            return null;
        }
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = findNoArgMethod(target.getClass(), name);
        return method == null ? null : method.invoke(target);
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep looking in superclasses
            }
        }
        return null;
    }
}
