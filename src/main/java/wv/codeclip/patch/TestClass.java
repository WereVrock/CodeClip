// ===== TestClass.java =====
package wv.codeclip.patch;

public class TestClass {

    public static String hello() {
        return "hello - patch AND class paste now work together!";
    }

    public static String goodbye() {
        return "goodbye - method replace works";
    }

    public static String testInserted() {
        return "inserted via test patch";
    }

    public static String inserted() {
        return "inserted after goodbye";
    }

    public static String insertedAtEnd() {
        return "inserted at end of class";
    }

    public static String wholeClassTest() {
        return "Whole class paste is working again!";
    }

    public static String chatterTest() {
        return "This method was added by the whole-class replacement.";
    }
}
