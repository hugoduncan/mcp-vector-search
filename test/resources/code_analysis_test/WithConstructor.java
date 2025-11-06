package test;

/**
 * Sample class with constructor.
 */
public class WithConstructor {
    private String name;
    private int value;

    /**
     * Constructor with name parameter.
     */
    public WithConstructor(String name) {
        this.name = name;
        this.value = 0;
    }

    /**
     * Constructor with name and value parameters.
     */
    public WithConstructor(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return this.name;
    }

    public int getValue() {
        return this.value;
    }
}
