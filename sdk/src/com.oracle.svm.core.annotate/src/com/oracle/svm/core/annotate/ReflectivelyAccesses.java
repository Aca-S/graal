package com.oracle.svm.core.annotate;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Platforms(Platform.HOSTED_ONLY.class)
public @interface ReflectivelyAccesses {
    Class<?> value();
}
