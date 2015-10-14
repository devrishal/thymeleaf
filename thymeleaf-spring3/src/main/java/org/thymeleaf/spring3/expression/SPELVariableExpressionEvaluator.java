/*
 * =============================================================================
 * 
 *   Copyright (c) 2011-2014, The THYMELEAF team (http://www.thymeleaf.org)
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 * =============================================================================
 */
package org.thymeleaf.spring3.expression;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.web.servlet.support.BindStatus;
import org.springframework.web.servlet.tags.form.ValueFormatterWrapper;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.cache.ICache;
import org.thymeleaf.cache.ICacheManager;
import org.thymeleaf.context.IMutableVariablesMap;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.expression.IExpressionObjects;
import org.thymeleaf.spring3.util.FieldUtils;
import org.thymeleaf.standard.expression.IStandardConversionService;
import org.thymeleaf.standard.expression.IStandardVariableExpressionEvaluator;
import org.thymeleaf.standard.expression.StandardExpressionExecutionContext;
import org.thymeleaf.standard.expression.StandardExpressions;
import org.thymeleaf.standard.util.StandardExpressionUtils;

/**
 * <p>
 *   Evaluator for variable expressions (<tt>${...}</tt>) in Thymeleaf Standard Expressions, using the
 *   SpringEL expression language.
 * </p>
 * <p>
 *   Note a class with this name existed since 2.0.9, but it was completely reimplemented
 *   in Thymeleaf 3.0
 * </p>
 *
 * @author Daniel Fern&aacute;ndez
 * @author Guven Demir
 * 
 * @since 3.0.0
 *
 */
public class SPELVariableExpressionEvaluator
        implements IStandardVariableExpressionEvaluator {


    public static final SPELVariableExpressionEvaluator INSTANCE = new SPELVariableExpressionEvaluator();

    // The reason we will be using a prefix with the expression cache is in order to separate entries coming
    // from this VariableExpressionEvaluator and those coming from the parsing of assignation sequences,
    // each expressions, fragment selections, etc. See org.thymeleaf.standard.expression.ExpressionCache
    private static final String SPEL_CACHE_PREFIX = "ognl|";
    
    
    private static final Logger logger = LoggerFactory.getLogger(SPELVariableExpressionEvaluator.class);

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    
    protected SPELVariableExpressionEvaluator() {
        super();
    }
    
    
    
    
    public final Object evaluate(
            final IExpressionContext context, final String spelExpression,
            final StandardExpressionExecutionContext expContext, final boolean useSelectionAsRoot) {
        
        if (logger.isTraceEnabled()) {
            logger.trace("[THYMELEAF][{}] SpringEL expression: evaluating expression \"{}\" on target", TemplateEngine.threadIndex(), spelExpression);
        }

        try {

            /*
             * TRY TO DELEGATE EVALUATION TO SPRING IF EXPRESSION IS ON A BOUND OBJECT
             */
            if (expContext.getPerformTypeConversion()) {
                // This is a {{...}} expression, so we should use binding info (if available) for formatting.

                if (useSelectionAsRoot || !isLocalVariableOverriding(context, spelExpression)) {
                    // The "local variable override" check avoids scenarios where a locally defined variable
                    // (e.g. the iterated variable in a th:each) has the same name as a bound object (e.g. a
                    // form-backing bean). If this was not detected, the bound object value would be always used
                    // instead of the local variable's

                    final BindStatus bindStatus =
                            FieldUtils.getBindStatusFromParsedExpression(context, true, useSelectionAsRoot, spelExpression);

                    if (bindStatus != null) {
                        // The expression goes against a bound object! Let Spring do its magic for displaying it...
                        return ValueFormatterWrapper.getDisplayString(bindStatus.getValue(), bindStatus.getEditor(), false);
                    }

                }

            }

            final IEngineConfiguration configuration = context.getConfiguration();


            /*
             * OBTAIN THE EXPRESSION (SpelExpression OBJECT) FROM THE CACHE, OR PARSE IT
             */
            final ComputedSpelExpression exp = getExpression(configuration, spelExpression);


            /*
             * COMPUTE EXPRESSION OBJECTS AND ADDITIONAL CONTEXT VARIABLES MAP
             * The IExpressionObjects implementation returned by processing contexts that include the Standard
             * Dialects will be lazy in the creation of expression objects (i.e. they won't be created until really
             * needed).
             */
            final IExpressionObjects expressionObjects =
                    (exp.mightNeedExpressionObjects? context.getExpressionObjects() : null);


            /*
             * CREATE/OBTAIN THE SPEL EVALUATION CONTEXT OBJECT
             */
            EvaluationContext evaluationContext =
                    (EvaluationContext) context.getVariables().
                            getVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME);

            if (evaluationContext == null) {

                // Using a StandardEvaluationContext one as base: we are losing bean resolution and conversion service!!
                //
                // The ideal scenario is that this is created before processing the page, e.g. at the ThymeleafView
                // class, but it can happen that no ThymeleafView is ever called if we are using the Spring-integrated
                // template engine on a standalone (non-web) scenario...
                //
                // Also, note Spring's EvaluationContexts are NOT THREAD-SAFE (in exchange for SpelExpressions being
                // thread-safe). That's why we need to create a new EvaluationContext for each request / template
                // execution, even if it is quite expensive to create because of requiring the initialization of
                // several ConcurrentHashMaps.
                evaluationContext = new ThymeleafEvaluationContextWrapper(new StandardEvaluationContext());

                final IExecutableContext variablesMap = context.getVariables();
                if (variablesMap instanceof IMutableVariablesMap) {
                    ((IMutableVariablesMap)variablesMap).put(
                            ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, evaluationContext);
                }

            } else if (!(evaluationContext instanceof  IThymeleafEvaluationContext)) {

                evaluationContext = new ThymeleafEvaluationContextWrapper(evaluationContext);

                final IExecutableContext variablesMap = context.getVariables();
                if (variablesMap instanceof IMutableVariablesMap) {
                    ((IMutableVariablesMap)variablesMap).put(
                            ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, evaluationContext);
                }

            }


            /*
             * AT THIS POINT, WE ARE SURE IT IS AN IThymeleafEvaluationContext
             *
             * This is needed in order to be sure we can modify the 'requestParametersRestricted' flag and also the
             * expression objects.
             */
            final IThymeleafEvaluationContext thymeleafEvaluationContext = (IThymeleafEvaluationContext) evaluationContext;


            /*
             * CONFIGURE THE IThymeleafEvaluationContext INSTANCE: expression objects and restrictions
             *
             * NOTE this is possible even if the evaluation context object is shared for the whole template execution
             * because evaluation contexts are not thread-safe and are only used in a single template execution
             */
            thymeleafEvaluationContext.setExpressionObjects(expressionObjects);
            thymeleafEvaluationContext.setVariableAccessRestricted(expContext.getRestrictVariableAccess());


            /*
             * RESOLVE THE EVALUATION ROOT
             */
            final IExecutableContext variablesMap = context.getVariables();
            final Object evaluationRoot =
                    (useSelectionAsRoot && variablesMap.hasSelectionTarget()?
                            variablesMap.getSelectionTarget() : new SPELContextMapWrapper(variablesMap, thymeleafEvaluationContext));


            /*
             * If no conversion is to be made, JUST RETURN
             */
            if (!expContext.getPerformTypeConversion()) {
                return exp.expression.getValue(thymeleafEvaluationContext, evaluationRoot);
            }


            /*
             * If a conversion is to be made, OBTAIN THE CONVERSION SERVICE AND EXECUTE IT
             */
            final IStandardConversionService conversionService =
                    StandardExpressions.getConversionService(configuration);

            if (conversionService instanceof SpringStandardConversionService) {
                // The conversion service is a mere bridge with the Spring ConversionService, therefore
                // this makes use of the complete Spring type conversion infrastructure, without needing
                // to manually execute the conversion.
                return exp.expression.getValue(thymeleafEvaluationContext, evaluationRoot, String.class);
            }

            // We need type conversion, but conversion service is not a mere bridge to the Spring one,
            // so we need manual execution.
            final Object result = exp.expression.getValue(thymeleafEvaluationContext, evaluationRoot);
            return conversionService.convert(context, result, String.class);


        } catch (final TemplateProcessingException e) {
            throw e;
        } catch(final Exception e) {
            throw new TemplateProcessingException(
                    "Exception evaluating SpringEL expression: \"" + spelExpression + "\"", e);
        }
        
    }


    private static ComputedSpelExpression getExpression(final IEngineConfiguration configuration, final String spelExpression) {

        ComputedSpelExpression exp = null;
        ICache<String, Object> cache = null;

        final ICacheManager cacheManager = configuration.getCacheManager();
        if (cacheManager != null) {
            cache = cacheManager.getExpressionCache();
            if (cache != null) {
                exp = (ComputedSpelExpression) cache.get(SPEL_CACHE_PREFIX + spelExpression);
            }
        }

        if (exp == null) {

            final SpelExpression spelExpressionObject = (SpelExpression) PARSER.parseExpression(spelExpression);
            final boolean mightNeedExpressionObjects = StandardExpressionUtils.mightNeedExpressionObjects(spelExpression);

            exp = new ComputedSpelExpression(spelExpressionObject, mightNeedExpressionObjects);

            if (cache != null && null != exp) {
                cache.put(SPEL_CACHE_PREFIX + spelExpression, exp);
            }

        }

        return exp;
        
    }



    private static boolean isLocalVariableOverriding(final IExpressionContext context, final String expression) {

        final IExecutableContext variablesMap = context.getVariables();
        if (!(variablesMap instanceof IMutableVariablesMap)) {
            // We don't even have support for local variables!
            return false;
        }

        // NOTE this IMutableVariablesMap interface is internal and should not be used in users' code
        final IMutableVariablesMap mutableVariablesMap = (IMutableVariablesMap) variablesMap;

        final int dotPos = expression.indexOf('.');
        if (dotPos == -1) {
            return false;
        }
        // Once we extract the first part of the expression, we check whether it is a local variable...
        final String expressionFirstComponent = expression.substring(0, dotPos);
        return mutableVariablesMap.isVariableLocal(expressionFirstComponent);

    }




    @Override
    public String toString() {
        return "SpringEL";
    }



    private static final class ComputedSpelExpression {

        final SpelExpression expression;
        final boolean mightNeedExpressionObjects;

        ComputedSpelExpression(final SpelExpression expression, final boolean mightNeedExpressionObjects) {
            super();
            this.expression = expression;
            this.mightNeedExpressionObjects = mightNeedExpressionObjects;
        }


    }


}
