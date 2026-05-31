package wv.codeclip.patch;

// test class - does nothing
public class TestClass {

public static String hello() {
    return "hello - patch applied successfully";
}

public static String goodbye() {
    return "goodbye - method replace works";
}

public static String testInserted() {
    return "inserted via test patch";
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

}