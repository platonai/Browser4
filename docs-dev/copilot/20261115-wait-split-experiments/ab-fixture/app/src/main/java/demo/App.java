package demo;

/** Prints one arithmetic result. The A/B task adds a multiplication call here. */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        System.out.println("add(2, 3) = " + Lib.add(2, 3));
        System.out.println("add(5, 7) = " + Lib.add(5, 7));
    }
}
