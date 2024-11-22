package com.oracle.svm.core.annotate;

import java.lang.reflect.AnnotatedElement;
import java.util.Set;
import java.util.function.Function;

public class ForNameHandler implements Function<String, Set<Object>> {
    @Override
    public Set<Object> apply(String s) {
        try {
            return Set.of(Class.forName(s));
        } catch (ClassNotFoundException e) {
            return Set.of(s);
        }
    }
}
