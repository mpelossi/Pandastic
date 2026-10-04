package android.util;

/** Desktop stand-in: android.util.Log to stderr. */
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6;

    private Log() {}

    public static boolean isLoggable(String tag, int level) { return level >= INFO; }

    private static int print(String level, String tag, String message, Throwable error) {
        System.err.println(level + "/" + tag + ": " + message + (error == null ? "" : " (" + error + ")"));
        return 0;
    }

    public static int d(String tag, String message) { return print("D", tag, message, null); }
    public static int i(String tag, String message) { return print("I", tag, message, null); }
    public static int w(String tag, String message) { return print("W", tag, message, null); }
    public static int w(String tag, String message, Throwable error) { return print("W", tag, message, error); }
    public static int e(String tag, String message) { return print("E", tag, message, null); }
    public static int e(String tag, String message, Throwable error) { return print("E", tag, message, error); }
}
