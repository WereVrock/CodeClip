package wv.codeclip.patch;

public class TestClass {

public static String greet() {
        return "Hello from Patch 2 (greet)!";
    }

/**
     * Returns a polite farewell message.
     * This method has a very long Javadoc comment to increase the size
     * of the patch and help expose any layout issues in the UI.
     */
    public static String farewell() {
        return "Adios from Patch 3 (fixed Javadoc)!";
    }

public static String farewell2() {
        return "Farewell from Patch 1!";
    }

public static String farewell3() {
        return "So long from Patch 1!";
    }

public static String farewell4() {
        return "Goodbye from Patch 3!";
    }

public static String farewell5() {
        return "Hello from Patch 2 (farewell5)!";
    }

public static String farewell6() {
        return "Fresh from Patch 3!";
    }

public static String farewell7() {
        return "Seventh farewell from Patch 2!";
    }

private static final String VERY_LONG_CONSTANT_NAME_THAT_EXCEEDS_NORMAL_WIDTH = 
        "Patch 3 final constant.";

public static String getConstantValue() {
        return VERY_LONG_CONSTANT_NAME_THAT_EXCEEDS_NORMAL_WIDTH + " (with extra flair!)";
    }

public static String getGreetingWithTime(String timeOfDay) {
        return "Greetings, " + timeOfDay + " friend!";
    }

public static String getAppName() {
        return "CodeClip Patch Tester v2";
    }

public static String getVersion() {
        return "3.0.0-patch-2";
    }

}