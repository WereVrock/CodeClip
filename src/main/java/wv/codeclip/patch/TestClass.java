package wv.codeclip.patch;

public class TestClass {

    public static String hello() {
        return "hello - base version for fuzzy tests";
    }

    public static String goodbye() {
        return "goodbye - base version";
    }

    public static String fuzzyOne() {
        // this method has trailing spaces in its comment    
        return "fuzzyOne original";
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