package android.net;

import android.os.Parcel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/** Identity-only Uri for local JVM tests; Android framework factories are stubbed there. */
public final class TestUri extends Uri {
    private TestUri() { super(); }

    public static Uri create() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            return (Uri) allocate.invoke(field.get(null), TestUri.class);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Override public Builder buildUpon() { return null; }
    @Override public String getAuthority() { return "test"; }
    @Override public String getEncodedAuthority() { return "test"; }
    @Override public String getEncodedFragment() { return null; }
    @Override public String getEncodedPath() { return "/content"; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getEncodedSchemeSpecificPart() { return "//test/content"; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getHost() { return "test"; }
    @Override public String getLastPathSegment() { return "content"; }
    @Override public String getPath() { return "/content"; }
    @Override public List<String> getPathSegments() { return Collections.singletonList("content"); }
    @Override public int getPort() { return -1; }
    @Override public String getQuery() { return null; }
    @Override public String getScheme() { return "content"; }
    @Override public String getSchemeSpecificPart() { return "//test/content"; }
    @Override public String getUserInfo() { return null; }
    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public String toString() { return "content://test/content"; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel parcel, int flags) { }
}
