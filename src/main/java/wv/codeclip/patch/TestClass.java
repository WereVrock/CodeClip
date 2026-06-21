package wv.codeclip.patch;

public class TestClass {

    public static String greet() {
        return "This will never match";
    }

/**
     * Returns a polite farewell message.
     * This method has a very long Javadoc comment to increase the size
     * of the patch and help expose any layout issues in the UI.
     */

/**
     * Returns a polite farewell message.
     * This method has a very long Javadoc comment to increase the size
     * of the patch and help expose any layout issues in the UI.
     */

public static String farewell() {
        return "Farewell, until we meet again!";
    }

private static final String VERY_LONG_CONSTANT_NAME_THAT_EXCEEDS_NORMAL_WIDTH = 
        "This string is intentionally long to make the patch content larger.";

public static String getConstantValue() {
        return VERY_LONG_CONSTANT_NAME_THAT_EXCEEDS_NORMAL_WIDTH + " (with extra flair!)";
    }

public static String getGreetingWithTime(String timeOfDay) {
        return "Good " + timeOfDay + ", wonderful person!";
    }

public static String getAppName() {
    return "CodeClip Patch Tester";
}

}