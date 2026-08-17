package com.ysmef.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards the official YSM 2.6.5 animation transition evaluator against null
 * transition conditions.
 *
 * YSM's AnimationControllerRuntime#evaluateTransitions iterates
 * currentEntry.getTransitions() and calls transition.right().evalAsBoolean(...)
 * without a null check. A malformed/corrupted model can put a pair with a null
 * right value into that list, which crashes the whole render thread (observed
 * in the YSM model-preview screen: "Cannot invoke IValue.evalAsBoolean because
 * the return value of IntReferenceImmutablePair.right() is null").
 *
 * Redirecting only this call site makes the broken transition evaluate as
 * false instead of crashing. Other YSM code paths are untouched.
 *
 * The official YSM 2.6.5 release jar is obfuscated on the compile classpath,
 * so the mixin target and the redirected method are specified as strings and
 * the handler uses @Coerce Object parameters + reflection - the same pattern
 * the other OpenYSM/LegacyYSM mixins in this project use for fork-specific
 * classes.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.geckolib3.core.controller.AnimationControllerRuntime", remap = false)
public abstract class YsmAnimationTransitionGuardMixin {

    private static volatile boolean NULL_TRANSITION_LOGGED = false;
    private static volatile boolean INVOKE_FAILED_LOGGED = false;
    private static final Map<Class<?>, Method> EVAL_METHODS = new ConcurrentHashMap<>();

    @Redirect(
            method = "evaluateTransitions(Lcom/elfmcys/yesstevemodel/molang/runtime/ExpressionEvaluator;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/elfmcys/yesstevemodel/geckolib3/core/molang/value/IValue;evalAsBoolean(Lcom/elfmcys/yesstevemodel/molang/runtime/ExpressionEvaluator;)Z"
            ),
            require = 0
    )
    private boolean ysmef$guardNullTransitionCondition(@Coerce Object condition, @Coerce Object evaluator) {
        if (condition == null) {
            if (!NULL_TRANSITION_LOGGED) {
                NULL_TRANSITION_LOGGED = true;
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: YSM animation transition has a null condition; treating it as false "
                                + "(the render-thread crash would otherwise happen inside YSM). "
                                + "This usually means the current model has a malformed animation transition.");
            }
            return false;
        }
        return invokeEvalAsBoolean(condition, evaluator);
    }

    /**
     * Invoke IValue#evalAsBoolean(ExpressionEvaluator) reflectively. The method
     * is a default interface method, so it is looked up on the concrete value
     * class once and cached - this runs once per animation transition per frame
     * and must not do a reflective lookup every call.
     */
    private static boolean invokeEvalAsBoolean(Object condition, Object evaluator) {
        Method method = EVAL_METHODS.get(condition.getClass());
        if (method == null) {
            Method found = null;
            for (Method candidate : condition.getClass().getMethods()) {
                if ("evalAsBoolean".equals(candidate.getName())
                        && candidate.getParameterCount() == 1
                        && candidate.getReturnType() == boolean.class) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                if (!INVOKE_FAILED_LOGGED) {
                    INVOKE_FAILED_LOGGED = true;
                    com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                            "YSM-EF Compat: cannot find IValue#evalAsBoolean on {}; treating transitions as false",
                            condition.getClass().getName());
                }
                return false;
            }
            Method previous = EVAL_METHODS.putIfAbsent(condition.getClass(), found);
            method = previous != null ? previous : found;
        }
        try {
            Object result = method.invoke(condition, evaluator);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            if (!INVOKE_FAILED_LOGGED) {
                INVOKE_FAILED_LOGGED = true;
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: YSM transition condition evaluation failed, treating it as false", t);
            }
            return false;
        }
    }
}
