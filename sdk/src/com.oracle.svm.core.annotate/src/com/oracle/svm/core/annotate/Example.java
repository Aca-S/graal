package com.oracle.svm.core.annotate;

public class Example {
    // @ConstantReflectionArgument?
    // @ReflectionArgument?
    private Object instantiate(@ReflectivelyAccesses(ForNameHandler.class) String className)
    {
        try {
            return Class.forName(className);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to find class");
        }
    }
}

// instantiate("abc");

// instatitate("cde");