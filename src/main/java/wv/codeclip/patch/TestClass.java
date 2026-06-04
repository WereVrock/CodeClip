// ===== TestClass.java =====
package wv.codeclip.patch;

public class TestClass {

    // ---- overloaded: hello() ----
    public static String hello() {
        return "hello - base version for fuzzy tests";
    }

    // ---- overloaded: hello(String) ----

public static String hello(String name) {
        return "Hello " + name + "! (patched with parameter type)";
    }

public static String goodbye() {
        return "goodbye - base version";
    }

public static String fuzzyOne() {
        return "fuzzyOne - UI test passed";
    }

public static String fuzzyTwo() {


        return "fuzzyTwo original";
    }

    public static String fuzzyThree() {
		return "fuzzyThree original";	// tabs used here
    }

    public static String fuzzyFour() {
        return "fuzzyFour original";
    }

    public static String fuzzyFive() {
        return "fuzzyFive original";
    }
}