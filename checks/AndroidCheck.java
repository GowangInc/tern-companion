package io.github.gowanginc.terncompanion;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Real Android certificate objects and optional platform APK archive checks. */
public final class AndroidCheck {
    public static void main(String[] args) throws Exception {
        Method signers = AppUpdater.class.getDeclaredMethod("sameSigners", Signature[].class, Signature[].class);
        signers.setAccessible(true);
        Signature a = new Signature(new byte[] {1, 2, 3});
        Signature b = new Signature(new byte[] {4, 5, 6});
        expect(signers, new Signature[] {a}, new Signature[] {new Signature(new byte[] {1, 2, 3})}, true);
        expect(signers, new Signature[] {a, b}, new Signature[] {b, a}, true);
        expect(signers, new Signature[] {a}, new Signature[] {b}, false);
        expect(signers, new Signature[] {a}, new Signature[] {a, b}, false);
        expect(signers, new Signature[] {a, a}, new Signature[] {a, b}, false);
        expect(signers, new Signature[] {a, b}, new Signature[] {a, a}, false);
        expect(signers, new Signature[0], new Signature[0], false);
        expect(signers, null, new Signature[] {a}, false);
        expect(signers, new Signature[] {null}, new Signature[] {a}, false);
        System.out.println("Android current-signer compatibility checks passed.");
        if (args.length == 0) {
            System.out.println("Platform APK archive guards NOT RUN; supply newer, wrong-signer and wrong-package APK paths.");
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("Expected three test APK paths");
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object main = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(main);
        Activity owner = new Activity() {
            @Override public String getPackageName() { return "io.github.gowanginc.terncompanion"; }
            @Override public PackageManager getPackageManager() { return context.getPackageManager(); }
        };
        Class<?> releaseType = Class.forName("io.github.gowanginc.terncompanion.AppUpdater$Release");
        Constructor<?> constructor = releaseType.getDeclaredConstructor(String.class, String.class, long.class);
        constructor.setAccessible(true);
        Method validate = AppUpdater.class.getDeclaredMethod("validateApk", Activity.class, File.class, releaseType);
        validate.setAccessible(true);
        File good = new File(args[0]);
        String goodVersion = context.getPackageManager().getPackageArchiveInfo(good.getPath(), 0).versionName;
        Object release = constructor.newInstance("v" + goodVersion, "https://github.com/GowangInc/tern-companion/", good.length());
        validate.invoke(null, owner, good, release);
        File installed = new File(context.getPackageManager().getApplicationInfo(owner.getPackageName(), 0).sourceDir);
        reject(validate, owner, installed, constructor, "v" + goodVersion, "not newer");
        reject(validate, owner, new File(args[1]), constructor, "v" + goodVersion, "signing certificate");
        reject(validate, owner, new File(args[2]), constructor, "v" + goodVersion, "valid APK for this app");
        Object truncated = constructor.newInstance("v" + goodVersion, "https://github.com/", good.length() + 1);
        try {
            validate.invoke(null, owner, good, truncated);
            throw new AssertionError("Incomplete APK accepted");
        } catch (InvocationTargetException expected) {
            if (!expected.getCause().getMessage().contains("incomplete")) throw expected;
        }
        System.out.println("Platform APK package, signer, version and size guards passed.");
    }

    private static void expect(Method method, Signature[] a, Signature[] b, boolean expected) throws Exception {
        boolean actual = (Boolean) method.invoke(null, a, b);
        if (actual != expected) throw new AssertionError("Current signer set comparison failed");
    }

    private static void reject(Method validate, Activity owner, File file, Constructor<?> constructor,
                               String tag, String reason) throws Exception {
        Object release = constructor.newInstance(tag, "https://github.com/", file.length());
        try {
            validate.invoke(null, owner, file, release);
            throw new AssertionError("Unsafe APK accepted");
        } catch (InvocationTargetException expected) {
            if (!expected.getCause().getMessage().contains(reason)) throw expected;
        }
    }
}
