package wv.codeclip.patch; public class TestClass { public static String hello() { return "hello - base version for fuzzy tests"; } public static String goodbye() { return "goodbye - base version"; } public static String fuzzyOne() { // this method has trailing spaces in its comment return "fuzzyOne original"; } public static String fuzzyTwo() { return "fuzzyTwo UPDATED - blank lines collapsed"; }    public static String fuzzyThree() {
        return "fuzzyThree UPDATED - tab normalisation works";
    }     public static String fuzzyFour() {
        return "fuzzyFour UPDATED - indent stripping works";
    } public static String fuzzyFive() { return "fuzzyFive original"; } }