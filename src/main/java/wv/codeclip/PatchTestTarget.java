package wv.codeclip;

public class PatchTestTarget {

    private String status = "initial";
    private int count = 0;

    public void singleUniqueMethod() {
        System.out.println("original single method");
    }

    public void overloadedMethod(String s) {
        System.out.println("overload string: " + s);
    }

    public void overloadedMethod(int n) {
        System.out.println("overload int: " + n);
    }

    public void methodWithUniqueText() {
        // UNIQUE_MARKER_XQ9
        status = "unchanged";
    }

    public void anotherMethod() {
        count++;
        System.out.println("count: " + count);
    }

    public String duplicateLine() {
        count++;
        return status;
    }
}