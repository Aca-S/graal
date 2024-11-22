package com.oracle.svm.hosted.snippets;

import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.TypeResult;
import com.oracle.svm.core.hub.PredefinedClassesSupport;
import com.oracle.svm.hosted.ExceptionSynthesizer;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.hosted.reflectionanalysis.ConstantReflectionTransformer;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.graphbuilderconf.ClassInitializationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.lang.reflect.Method;
import java.util.List;

public class BytecodeReflectionPlugins {

    private static final Object NULL_MARKER = new Object();

    private final ImageClassLoader imageClassLoader;
    private final ClassInitializationPlugin classInitializationPlugin;
    private final AnalysisUniverse aUniverse;
    private final ParsingReason reason;

    public BytecodeReflectionPlugins(ImageClassLoader imageClassLoader, InvocationPlugins plugins,
                                     ClassInitializationPlugin classInitializationPlugin, AnalysisUniverse aUniverse,
                                     ParsingReason reason) {
        this.imageClassLoader = imageClassLoader;
        this.classInitializationPlugin = classInitializationPlugin;
        this.aUniverse = aUniverse;
        this.reason = reason;
        registerClassForNameInvocationPlugins(plugins);
    }

    private void registerClassForNameInvocationPlugins(InvocationPlugins plugins) {
        InvocationPlugins.Registration r = new InvocationPlugins.Registration(plugins, Class.class);
        r.register(new InvocationPlugin.RequiredInvocationPlugin("forName", String.class) {
            @Override
            public boolean apply(GraphBuilderContext builderContext, ResolvedJavaMethod targetMethod, Receiver receiver) {
                if (builderContext.getMethod() != null) {
                    return processClassForName(builderContext, targetMethod, false);
                } else {
                    return false;
                }
            }
        });
        r.register(new InvocationPlugin.RequiredInvocationPlugin("forName", String.class, boolean.class, ClassLoader.class) {
            @Override
            public boolean apply(GraphBuilderContext builderContext, ResolvedJavaMethod targetMethod, Receiver receiver) {
                if (builderContext.getMethod() != null) {
                    return processClassForName(builderContext, targetMethod, true);
                } else {
                    return false;
                }
            }
        });
    }

    private boolean processClassForName(GraphBuilderContext builderContext, ResolvedJavaMethod targetMethod, boolean hasInitializeParameter) {
        List<Object> arguments = ConstantReflectionTransformer.callRegistry.get(builderContext.getMethod(), builderContext.bci());
        if (arguments == null) {
            return false;
        }

        String className = (String) arguments.getFirst();
        boolean initialize = hasInitializeParameter ? (Boolean) arguments.get(1) : true;

        /*
         * Check which variant of Class.forName was called in order to avoid logging
         * the initialize argument value for the single parameter version of the call.
         */
        Object[] argValues = hasInitializeParameter
                ? new Object[] {className, initialize}
                : new Object[] {className};

        TypeResult<Class<?>> typeResult = imageClassLoader.findClass(className, false);
        if (!typeResult.isPresent()) {
            Throwable e = typeResult.getException();
            return throwException(builderContext, targetMethod, null, argValues, e.getClass(), e.getMessage());
        }
        Class<?> clazz = typeResult.get();
        if (PredefinedClassesSupport.isPredefined(clazz)) {
            return false;
        }

        JavaConstant classConstant = pushConstant(builderContext, targetMethod, null, argValues, JavaKind.Object, clazz);
        if (classConstant == null) {
            return false;
        }

        if (initialize) {
            classInitializationPlugin.apply(builderContext, builderContext.getMetaAccess().lookupJavaType(clazz), () -> null);
        }
        return true;
    }

    private boolean throwException(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Object targetCaller, Object[] targetArguments, Class<? extends Throwable> exceptionClass, String originalMessage) {
        /* Get the exception throwing method that has a mesReflectionPlsage parameter. */
        Method exceptionMethod = ExceptionSynthesizer.throwExceptionMethodOrNull(exceptionClass, String.class);
        if (exceptionMethod == null) {
            return false;
        }
        Method intrinsic = getIntrinsic(b, exceptionMethod);
        if (intrinsic == null) {
            return false;
        }

        String message = originalMessage + ". This exception was synthesized during native image building from a call to " + targetMethod.format("%H.%n(%p)") +
                " with constant arguments.";
        ExceptionSynthesizer.throwException(b, exceptionMethod, message);
        //traceException(b, targetMethod, targetCaller, targetArguments, exceptionClass);
        return true;
    }

    private JavaConstant pushConstant(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Object targetCaller, Object[] targetArguments, JavaKind returnKind, Object returnValue) {
        Object intrinsicValue = getIntrinsic(b, returnValue);
        if (intrinsicValue == null) {
            return null;
        }

        JavaConstant intrinsicConstant;
        if (returnKind.isPrimitive()) {
            intrinsicConstant = JavaConstant.forBoxedPrimitive(intrinsicValue);
        } else if (intrinsicValue == NULL_MARKER) {
            intrinsicConstant = JavaConstant.NULL_POINTER;
        } else {
            intrinsicConstant = b.getSnippetReflection().forObject(intrinsicValue);
        }

        b.addPush(returnKind, ConstantNode.forConstant(intrinsicConstant, b.getMetaAccess()));
        //traceConstant(b, targetMethod, targetCaller, targetArguments, intrinsicValue);
        return intrinsicConstant;
    }

    private <T> T getIntrinsic(GraphBuilderContext context, T element) {
        if (reason == ParsingReason.AutomaticUnsafeTransformation || reason == ParsingReason.EarlyClassInitializerAnalysis) {
            /* We are analyzing the static initializers and should always intrinsify. */
            return element;
        }
//        if (isDeleted(element, context.getMetaAccess())) {
//            /*
//             * Should not intrinsify. Will fail during the reflective lookup at runtime. @Delete-ed
//             * elements are ignored by the reflection plugins regardless of the value of
//             * ReportUnsupportedElementsAtRuntime.
//             */
//            return null;
//        }
        return (T) aUniverse.replaceObject(element);
    }
}
